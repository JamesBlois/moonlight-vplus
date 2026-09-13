package com.limelight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log

import com.limelight.nvstream.jni.MoonBridge

import java.nio.ByteBuffer

/**
 * Flydigi Vader 5 Pro (VID 0x37d7 / PID 0x2401) custom vendor-HID controller.
 *
 * The device exposes two HID interfaces. Interface 0 is a standard XInput pad
 * (handled by the Android input stack or [XboxOneController]) which carries no extended
 * buttons or IMU. This driver claims the vendor HID interface (interface 1..., or 2 on
 * older hardware) which streams the Flydigi V2 protocol: 32-byte reports (unnumbered
 * on Vader 5), magic 0x5A 0xA5, command byte, stick axes, paddle/C/Z buttons, IMU,
 * and battery info.
 *
 * All report decoding lives in [FlydigiVaderInputParser] (pure Kotlin, no Android
 * dependency) so it can be covered by plain JVM unit tests.
 */
class FlydigiVaderController(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractPlayStationUsbController(device, connection, deviceId, listener) {

    private val parser = FlydigiVaderInputParser

    private var lastBatteryState: Byte = -1
    private var lastBatteryLevel: Byte = -1

    override val driverControllerType: Byte = MoonBridge.LI_CTYPE_XBOX

    override val extraCapabilities: Int =
        MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt() or MoonBridge.LI_CCAP_BATTERY_STATE.toInt()

    /** Flydigi Vader 5 exposes the vendor HID protocol on interface 1 (or 2 on older
     *  hardware). The base [findInterface] would pick the first HID interface with two
     *  endpoints (interface 0, the XInput pad) which doesn't stream vendor reports. */
    override fun findPreferredInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                iface.endpointCount >= 2 &&
                iface.interfaceProtocol == 0
            ) {
                Log.d(TAG, "Using Flydigi vendor HID interface $i")
                return iface
            }
        }
        return super.findPreferredInterface(device)
    }

    override fun doInit(): Boolean {
        Log.d(TAG, "Sending Flydigi get-info / status / acquire handshake")
        sendCommand(buildInfoCommand())
        sendCommand(buildStatusCommand())
        sendCommand(buildAcquireCommand(acquire = true))
        return true
    }

    override fun sendCommand(data: ByteArray) {
        if (outEndpt == null) {
            Log.w(TAG, "Cannot send command: invalid endpoint")
            return
        }
        synchronized(outputLock) {
            if (outputClosed) {
                return
            }
            // Vader 5 uses unnumbered output reports: a report-ID byte is dropped on write.
            var out = data
            if (out.size == 32 && (out[0].toInt() and 0xFF) == FLYDIGI_V2_CMD_REPORT_ID) {
                out = ByteArray(32) { i -> if (i == 0) 0 else data[i] }
            }
            val res = connection.bulkTransfer(outEndpt, out, out.size, 2000)
            if (res != out.size) {
                Log.w(TAG, "Command transfer failed: expected ${out.size}, got $res")
            }
        }
    }

    override fun handleRead(buffer: ByteBuffer): Boolean {
        val raw = ByteArray(buffer.remaining())
        buffer.get(raw)

        // Vader 5 input reports are unnumbered. If the first byte is not magic, drop
        // a leading report-ID byte (0x03 on numbered Flydigi devices).
        val payload = parser.stripReportId(raw) ?: return false
        if (!parser.looksLikeFlydigiReport(payload)) {
            Log.d(TAG, "Ignoring malformed Flydigi report (size=${payload.size})")
            return false
        }

        val command = parser.commandByte(payload)
        when (command) {
            FLYDIGI_V2_INPUT_REPORT -> {
                val state = parser.parseInput(payload) ?: return false
                buttonFlags = state.buttonFlags
                leftStickX = state.leftStickX
                leftStickY = state.leftStickY
                rightStickX = state.rightStickX
                rightStickY = state.rightStickY
                leftTrigger = state.leftTrigger
                rightTrigger = state.rightTrigger
                if (state.hasImu) {
                    gyroX = state.gyroX
                    gyroY = state.gyroY
                    gyroZ = state.gyroZ
                    accelX = state.accelX
                    accelY = state.accelY
                    accelZ = state.accelZ
                }
            }
            FLYDIGI_V2_GET_INFO_COMMAND -> handleInfo(parser.parseInfo(payload))
            FLYDIGI_V2_GET_STATUS_COMMAND, FLYDIGI_V2_SET_STATUS_COMMAND,
            FLYDIGI_V2_ACQUIRE_CONTROLLER_COMMAND -> Unit
            else ->
                Log.d(TAG, "Ignoring unknown Flydigi command 0x" + Integer.toHexString(command))
        }
        return true
    }

    private fun handleInfo(info: FlydigiVaderInputParser.InfoState?) {
        if (info == null) return
        val batteryState: Byte = when (info.batteryState) {
            FlydigiVaderInputParser.BatteryStatus.DISCHARGING -> MoonBridge.LI_BATTERY_STATE_DISCHARGING
            FlydigiVaderInputParser.BatteryStatus.CHARGING -> MoonBridge.LI_BATTERY_STATE_CHARGING
            FlydigiVaderInputParser.BatteryStatus.FULL -> MoonBridge.LI_BATTERY_STATE_FULL
            FlydigiVaderInputParser.BatteryStatus.UNKNOWN -> MoonBridge.LI_BATTERY_STATE_UNKNOWN
        }
        val batteryPercentage = info.batteryPercentage.toByte()

        if (batteryState != lastBatteryState || batteryPercentage != lastBatteryLevel) {
            lastBatteryState = batteryState
            lastBatteryLevel = batteryPercentage
            notifyBatteryState(batteryState, batteryPercentage)
        }
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        val packet = byteArrayOf(
            FLYDIGI_V2_CMD_REPORT_ID.toByte(),    // report id (sendCommand strips it when it is the report id)
            FLYDIGI_V2_MAGIC1.toByte(),
            FLYDIGI_V2_MAGIC2.toByte(),
            FLYDIGI_V2_HAPTIC_COMMAND.toByte(),
            6,
            (highFreqMotor.toInt() ushr 8).toByte(),
            (lowFreqMotor.toInt() ushr 8).toByte(),
            0, 0, 0
        )
        sendCommand(packet)
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        // Trigger motors are only available in Flydigi haptic mode which we don't configure here.
    }

    override fun clearControllerSpecificOutput() {
        // Release the controller acquisition so subsequent apps start from a clean slate.
        sendCommand(buildAcquireCommand(acquire = false))
    }

    private fun buildInfoCommand(): ByteArray = protocolCommand(FLYDIGI_V2_GET_INFO_COMMAND)

    private fun buildStatusCommand(): ByteArray = protocolCommand(FLYDIGI_V2_GET_STATUS_COMMAND)

    private fun protocolCommand(command: Int): ByteArray {
        return byteArrayOf(
            FLYDIGI_V2_CMD_REPORT_ID.toByte(),
            FLYDIGI_V2_MAGIC1.toByte(),
            FLYDIGI_V2_MAGIC2.toByte(),
            command.toByte(),
            2, 0
        )
    }

    private fun buildAcquireCommand(acquire: Boolean): ByteArray {
        return byteArrayOf(
            FLYDIGI_V2_CMD_REPORT_ID.toByte(),
            FLYDIGI_V2_MAGIC1.toByte(),
            FLYDIGI_V2_MAGIC2.toByte(),
            FLYDIGI_V2_ACQUIRE_CONTROLLER_COMMAND.toByte(),
            23,
            if (acquire) 1 else 0,
            'S'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
    }

    companion object {
        private const val TAG = "FlydigiVaderCtrl"

        private const val VID_FLYDIGI_V2 = 0x37d7
        private const val PID_VADER_5_PRO = 0x2401

        const val FLYDIGI_V2_CMD_REPORT_ID = 0x03
        const val FLYDIGI_V2_MAGIC1 = 0x5A
        const val FLYDIGI_V2_MAGIC2 = 0xA5
        const val FLYDIGI_V2_GET_INFO_COMMAND = 0x01
        const val FLYDIGI_V2_GET_STATUS_COMMAND = 0x10
        const val FLYDIGI_V2_SET_STATUS_COMMAND = 0x11
        const val FLYDIGI_V2_HAPTIC_COMMAND = 0x12
        const val FLYDIGI_V2_ACQUIRE_CONTROLLER_COMMAND = 0x1C
        const val FLYDIGI_V2_INPUT_REPORT = 0xEF

        @JvmStatic
        fun canClaimDevice(device: UsbDevice?): Boolean {
            if (device == null) return false
            if (device.vendorId != VID_FLYDIGI_V2 || device.productId != PID_VADER_5_PRO) return false

            // Prefer the vendor HID interface (protocol 0 / None) over the XInput interface
            // (protocol 1) which Android may present as a standard pad.
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    iface.endpointCount >= 2 &&
                    iface.interfaceProtocol == 0
                ) {
                    return true
                }
            }
            return false
        }
    }
}