package com.limelight.binding.input.driver

import com.limelight.nvstream.input.ControllerPacket

/**
 * Pure-Kotlin parser for Flydigi Vader 5 Pro (VID 0x37d7 / PID 0x2401) vendor HID
 * input reports. This class deliberately has NO Android dependencies so it can be
 * exercised by plain JVM unit tests.
 *
 * Protocol (per the SDL HIDAPI Flydigi driver):
 *  - 32-byte reports, magic 0x5A 0xA5 at [0],[1], command byte at [2] (0xEF = input).
 *  - On Vader 5 reports are *unnumbered*; callers that receive a leading report-ID byte
 *    (0x03) must strip it before parsing. [stripReportId] exists for that.
 *  - Stick/gyro/accel values are int16 little-endian.
 */
object FlydigiVaderInputParser {

    enum class BatteryStatus {
        DISCHARGING,
        CHARGING,
        FULL,
        UNKNOWN
    }

    /** Result of parsing one input report. */
    data class InputState(
        val buttonFlags: Int,
        val leftStickX: Float,
        val leftStickY: Float,
        val rightStickX: Float,
        val rightStickY: Float,
        val leftTrigger: Float,   // 0..1
        val rightTrigger: Float,  // 0..1
        val gyroX: Float,         // rad/s
        val gyroY: Float,
        val gyroZ: Float,
        val accelX: Float,        // m/s^2
        val accelY: Float,
        val accelZ: Float,
        val hasImu: Boolean       // true when a gyro-enabled report (size >= 28) was parsed
    )

    /** Result of parsing an info response (battery + more in future). */
    data class InfoState(
        val batteryState: BatteryStatus,
        val batteryPercentage: Int  // 0..100
    )

    private const val MIN_PACKET_LENGTH = 31
    private const val IMU_MIN_SIZE = 28
    private const val INFO_MIN_SIZE = 12

    private const val MAGIC1 = 0x5A
    private const val MAGIC2 = 0xA5
    private const val CMD_INPUT_REPORT = 0xEF

    // SDL maps 16-bit gyro to +-DEG2RAD(2000); we report rad/s to the host.
    private const val GYRO_SCALE = 2000.0f * (Math.PI.toFloat() / 180.0f)
    private const val ACCEL_SCALE = 9.80665f / 4096.0f

    /** Returns the packet with a leading report-ID byte stripped, or null if invalid. */
    fun stripReportId(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        return if ((raw[0].toInt() and 0xFF) == MAGIC1) {
            raw
        } else if (raw.size > 1) {
            raw.copyOfRange(1, raw.size)
        } else {
            null
        }
    }

    /** Returns true if the payload starts with the Flydigi magic. */
    fun looksLikeFlydigiReport(payload: ByteArray): Boolean {
        return payload.size >= 2 &&
            (payload[0].toInt() and 0xFF) == MAGIC1 &&
            (payload[1].toInt() and 0xFF) == MAGIC2
    }

    /** Returns the command byte of a validated payload, or -1 if too short. */
    fun commandByte(payload: ByteArray): Int {
        if (!looksLikeFlydigiReport(payload)) return -1
        return payload[2].toInt() and 0xFF
    }

    /**
     * Parses an input report (already stripped of a leading report-ID byte).
     * Returns null for malformed / non-input packets.
     */
    fun parseInput(payload: ByteArray): InputState? {
        if (!looksLikeFlydigiReport(payload)) return null
        if (payload.size < MIN_PACKET_LENGTH) return null
        if (commandByte(payload) != CMD_INPUT_REPORT) return null

        val b11 = payload[11].toInt() and 0xFF
        val b12 = payload[12].toInt() and 0xFF
        val b13 = payload[13].toInt() and 0xFF
        val b14 = payload[14].toInt() and 0xFF

        var flags = 0
        flags = setOn(flags, ControllerPacket.A_FLAG, b11 and 0x10)
        flags = setOn(flags, ControllerPacket.B_FLAG, b11 and 0x20)
        flags = setOn(flags, ControllerPacket.BACK_FLAG, b11 and 0x40)
        flags = setOn(flags, ControllerPacket.X_FLAG, b11 and 0x80)

        flags = setOn(flags, ControllerPacket.Y_FLAG, b12 and 0x01)
        flags = setOn(flags, ControllerPacket.PLAY_FLAG, b12 and 0x02)
        flags = setOn(flags, ControllerPacket.LB_FLAG, b12 and 0x04)
        flags = setOn(flags, ControllerPacket.RB_FLAG, b12 and 0x08)
        flags = setOn(flags, ControllerPacket.LS_CLK_FLAG, b12 and 0x40)
        flags = setOn(flags, ControllerPacket.RS_CLK_FLAG, b12 and 0x80)

        // Extended buttons (byte 13): C=0x01, Z=0x02, M1=0x04, M2=0x08, M3=0x10,
        // M4=0x20, LM=0x40, RM=0x80
        flags = setOn(flags, ControllerPacket.MISC_FLAG, b13 and 0x01)      // C
        flags = setOn(flags, ControllerPacket.MISC_FLAG, b13 and 0x02)      // Z
        flags = setOn(flags, ControllerPacket.PADDLE1_FLAG, b13 and 0x04)   // M1
        flags = setOn(flags, ControllerPacket.PADDLE2_FLAG, b13 and 0x08)   // M2
        flags = setOn(flags, ControllerPacket.PADDLE3_FLAG, b13 and 0x10)   // M3
        flags = setOn(flags, ControllerPacket.PADDLE4_FLAG, b13 and 0x20)   // M4
        flags = setOn(flags, ControllerPacket.MISC_FLAG, b13 and 0x40)      // LM
        flags = setOn(flags, ControllerPacket.MISC_FLAG, b13 and 0x80)      // RM

        // Specialty (byte 14): Circle(FN)=0x01, Guide=0x08
        flags = setOn(flags, ControllerPacket.MISC_FLAG, b14 and 0x01)
        flags = setOn(flags, ControllerPacket.SPECIAL_BUTTON_FLAG, b14 and 0x08)

        // D-pad (byte 11 low nibble): bitmask 1=up, 2=right, 4=down, 8=left.
        when (b11 and 0x0F) {
            0x01 -> flags = flags or ControllerPacket.UP_FLAG
            0x02 -> flags = flags or ControllerPacket.RIGHT_FLAG
            0x03 -> flags = flags or ControllerPacket.RIGHT_FLAG or ControllerPacket.UP_FLAG
            0x04 -> flags = flags or ControllerPacket.DOWN_FLAG
            0x06 -> flags = flags or ControllerPacket.RIGHT_FLAG or ControllerPacket.DOWN_FLAG
            0x08 -> flags = flags or ControllerPacket.LEFT_FLAG
            0x09 -> flags = flags or ControllerPacket.LEFT_FLAG or ControllerPacket.UP_FLAG
            0x0C -> flags = flags or ControllerPacket.LEFT_FLAG or ControllerPacket.DOWN_FLAG
            else -> Unit
        }

        // Sticks (int16 LE, signed). Normalize to [-1, 1] with -32768 saturated to
        // -1.0 before inverting Y (matches SDL / gamecontroller convention).
        val lx = clampUnit(load16Signed(payload, 3) / 32767.0f)
        val ly = -clampUnit(load16Signed(payload, 5) / 32767.0f)
        val rx = clampUnit(load16Signed(payload, 7) / 32767.0f)
        val ry = -clampUnit(load16Signed(payload, 9) / 32767.0f)

        // Triggers (raw 0..255)
        val lt = (payload[15].toInt() and 0xFF) / 255.0f
        val rt = (payload[16].toInt() and 0xFF) / 255.0f

        var gx = 0f; var gy = 0f; var gz = 0f
        var ax = 0f; var ay = 0f; var az = 0f
        val hasImu = payload.size >= IMU_MIN_SIZE
        if (hasImu) {
            gx = remap16(payload[17], payload[18], GYRO_SCALE)
            gy = remap16(payload[21], payload[22], GYRO_SCALE)
            gz = -remap16(payload[19], payload[20], GYRO_SCALE)

            ax = load16Signed(payload, 23) * ACCEL_SCALE
            ay = load16Signed(payload, 27) * ACCEL_SCALE
            az = -load16Signed(payload, 25) * ACCEL_SCALE
        }

        return InputState(
            buttonFlags = flags,
            leftStickX = lx,
            leftStickY = ly,
            rightStickX = rx,
            rightStickY = ry,
            leftTrigger = lt,
            rightTrigger = rt,
            gyroX = gx,
            gyroY = gy,
            gyroZ = gz,
            accelX = ax,
            accelY = ay,
            accelZ = az,
            hasImu = hasImu
        )
    }

    /**
     * Parses the battery info response (battery bytes in data[11]).
     * Returns null for malformed packets.
     */
    fun parseInfo(payload: ByteArray): InfoState? {
        if (!looksLikeFlydigiReport(payload)) return null
        if (payload.size < INFO_MIN_SIZE) return null

        val statusNibble = (payload[11].toInt() ushr 4) and 0x0F
        val levelNibble = payload[11].toInt() and 0x0F

        val status = when (statusNibble) {
            0 -> BatteryStatus.DISCHARGING
            1 -> BatteryStatus.CHARGING
            2 -> BatteryStatus.FULL
            else -> BatteryStatus.UNKNOWN
        }
        val percent = if (status == BatteryStatus.FULL) 100 else (levelNibble * 20).coerceAtMost(100)

        return InfoState(status, percent)
    }

    private fun load16Signed(data: ByteArray, offset: Int): Int {
        val unsigned = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
    }

    /** Clamps a normalized float to [-1, 1] (already divided by 32767). */
    private fun clampUnit(v: Float): Float = v.coerceIn(-1.0f, 1.0f)

    private fun remap16(lo: Byte, hi: Byte, scale: Float): Float =
        (load16SignedLoHi(lo, hi) / 32767.0f) * scale

    private fun load16SignedLoHi(lo: Byte, hi: Byte): Int {
        val unsigned = (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
        return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
    }

    private fun setOn(flags: Int, flag: Int, condition: Int): Int =
        if (condition != 0) flags or flag else flags
}