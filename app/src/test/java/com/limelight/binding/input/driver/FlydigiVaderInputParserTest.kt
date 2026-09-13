package com.limelight.binding.input.driver

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [FlydigiVaderInputParser]. No Android device required;
 * run with `./gradlew :app:testDebugUnitTest --tests *FlydigiVaderInputParserTest`.
 */
class FlydigiVaderInputParserTest {

    // ---- helpers ----

    private fun u(int: Int): Byte = (int and 0xFF).toByte()

    /** Build a 32-byte unnumbered Flydigi input report (command 0xEF). */
    private fun inputReport(
        hat: Int = 0,
        b11: Int = 0,
        b12: Int = 0,
        b13: Int = 0,
        b14: Int = 0,
        lx: Int = 0,
        ly: Int = 0,
        rx: Int = 0,
        ry: Int = 0,
        lt: Int = 0,
        rt: Int = 0,
        imu: Boolean = true
    ): ByteArray {
        val p = ByteArray(32)
        p[0] = u(0x5A)
        p[1] = u(0xA5)
        p[2] = u(0xEF)
        put16(p, 3, lx)
        put16(p, 5, ly)
        put16(p, 7, rx)
        put16(p, 9, ry)
        p[11] = u((hat and 0x0F) or (b11 and 0xF0))
        p[12] = u(b12)
        p[13] = u(b13)
        p[14] = u(b14)
        p[15] = u(lt)
        p[16] = u(rt)
        if (imu) {
            put16(p, 17, 1000) // gyro pitch
            put16(p, 19, 1000) // gyro roll (parsed negated on Z)
            put16(p, 21, 1000) // gyro yaw
            put16(p, 23, 4096) // accel X = 1g
            put16(p, 25, 4096) // accel Z (parsed negated)
            put16(p, 27, 4096) // accel Y = 1g
        }
        return p
    }

    private fun put16(p: ByteArray, off: Int, value: Int) {
        p[off] = u(value)
        p[off + 1] = u(value shr 8)
    }

    private fun infoResponse(status: Int, level: Int): ByteArray {
        val p = ByteArray(32)
        p[0] = u(0x5A)
        p[1] = u(0xA5)
        p[2] = u(0x01) // get-info response
        p[11] = u(((status and 0x0F) shl 4) or (level and 0x0F))
        return p
    }

    private fun strip(raw: ByteArray): ByteArray =
        FlydigiVaderInputParser.stripReportId(raw)!!

    // ---- magic / header ----

    @Test
    fun stripReportId_leavesUnnumberedPacketUntouched() {
        val raw = inputReport(hat = 0)
        val stripped = FlydigiVaderInputParser.stripReportId(raw)!!
        assertEquals(raw.size, stripped.size)
        assertEquals(0x5A, stripped[0].toInt() and 0xFF)
    }

    @Test
    fun stripReportId_dropsLeadByteId() {
        val raw = inputReport(hat = 0)
        val withId = ByteArray(raw.size + 1) { if (it == 0) 0x03 else raw[it - 1].toInt().toByte() }
        val stripped = FlydigiVaderInputParser.stripReportId(withId)
        assertNotNull(stripped)
        assertEquals(0x5A, stripped!![0].toInt() and 0xFF)
    }

    @Test
    fun stripReportId_rejectsEmpty() {
        assertNull(FlydigiVaderInputParser.stripReportId(ByteArray(0)))
        assertNull(FlydigiVaderInputParser.stripReportId(ByteArray(1) { 0x03 }))
    }

    @Test
    fun looksLikeFlydigiReport_checksMagic() {
        val ok = inputReport(hat = 0)
        assertTrue(FlydigiVaderInputParser.looksLikeFlydigiReport(ok))
        val bad = ok.copyOf()
        bad[1] = 0x00
        assertFalse(FlydigiVaderInputParser.looksLikeFlydigiReport(bad))
    }

    // ---- buttons ----

    @Test
    fun faceButtons_mappedCorrectly() {
        val state = FlydigiVaderInputParser.parseInput(inputReport(b11 = 0x10 or 0x20 or 0x40 or 0x80))!!
        assertTrue(state.buttonFlags and ControllerPacket.A_FLAG != 0)
        assertTrue(state.buttonFlags and ControllerPacket.B_FLAG != 0)
        assertTrue(state.buttonFlags and ControllerPacket.BACK_FLAG != 0)
        assertTrue(state.buttonFlags and ControllerPacket.X_FLAG != 0)
    }

    @Test
    fun topRowButtons_mappedCorrectly() {
        val state = FlydigiVaderInputParser.parseInput(
            inputReport(b12 = 0x01 or 0x02 or 0x04 or 0x08 or 0x40 or 0x80)
        )!!
        assertTrue(state.buttonFlags and ControllerPacket.Y_FLAG != 0)
        assertTrue(state.buttonFlags and ControllerPacket.PLAY_FLAG != 0)
        assertTrue(state.buttonFlags and ControllerPacket.LB_FLAG != 0)
        assertTrue(state.buttonFlags and ControllerPacket.RB_FLAG != 0)
        assertTrue(state.buttonFlags and ControllerPacket.LS_CLK_FLAG != 0)
        assertTrue(state.buttonFlags and ControllerPacket.RS_CLK_FLAG != 0)
    }

    @Test
    fun extendedButtons_mapToPaddlesAndMisc() {
        val state = FlydigiVaderInputParser.parseInput(
            inputReport(b13 = 0x01 or 0x02 or 0x04 or 0x08 or 0x10 or 0x20 or 0x40 or 0x80)
        )!!
        assertTrue(state.buttonFlags and ControllerPacket.MISC_FLAG != 0) // C, Z, LM, RM
        assertTrue(state.buttonFlags and ControllerPacket.PADDLE1_FLAG != 0) // M1
        assertTrue(state.buttonFlags and ControllerPacket.PADDLE2_FLAG != 0) // M2
        assertTrue(state.buttonFlags and ControllerPacket.PADDLE3_FLAG != 0) // M3
        assertTrue(state.buttonFlags and ControllerPacket.PADDLE4_FLAG != 0) // M4
    }

    @Test
    fun specialtyButtons_mapToMiscAndSpecial() {
        val state = FlydigiVaderInputParser.parseInput(inputReport(b14 = 0x01 or 0x08))!!
        assertTrue(state.buttonFlags and ControllerPacket.MISC_FLAG != 0) // Circle/FN
        assertTrue(state.buttonFlags and ControllerPacket.SPECIAL_BUTTON_FLAG != 0) // Guide
    }

    @Test
    fun cleanState_hasNoStaleButtons() {
        val none = FlydigiVaderInputParser.parseInput(inputReport())!!
        assertEquals(0, none.buttonFlags)
        val aOnly = FlydigiVaderInputParser.parseInput(inputReport(b11 = 0x10))!!
        assertTrue(aOnly.buttonFlags and ControllerPacket.A_FLAG != 0)
        assertEquals(
            0,
            aOnly.buttonFlags and (ControllerPacket.A_FLAG.inv().toInt())
        )
    }

    // ---- d-pad ----

    @Test
    fun dpad_directions() {
        fun dir(hat: Int): Int =
            FlydigiVaderInputParser.parseInput(inputReport(hat = hat))!!.buttonFlags

        assertEquals(ControllerPacket.UP_FLAG, dir(0x01))
        assertEquals(ControllerPacket.RIGHT_FLAG, dir(0x02))
        assertEquals(ControllerPacket.RIGHT_FLAG or ControllerPacket.UP_FLAG, dir(0x03))
        assertEquals(ControllerPacket.DOWN_FLAG, dir(0x04))
        assertEquals(ControllerPacket.RIGHT_FLAG or ControllerPacket.DOWN_FLAG, dir(0x06))
        assertEquals(ControllerPacket.LEFT_FLAG, dir(0x08))
        assertEquals(ControllerPacket.LEFT_FLAG or ControllerPacket.UP_FLAG, dir(0x09))
        assertEquals(ControllerPacket.LEFT_FLAG or ControllerPacket.DOWN_FLAG, dir(0x0C))
        assertEquals(0, dir(0x00))
    }

    // ---- sticks / triggers ----

    @Test
    fun sticks_normalizedAndYInverted() {
        val max = 32767
        val state = FlydigiVaderInputParser.parseInput(
            inputReport(lx = max, ly = -max, rx = -max, ry = max)
        )!!
        assertEquals(1.0f, state.leftStickX, 1e-3f)
        assertEquals(1.0f, state.leftStickY, 1e-3f)   // y inverted: -(-max) -> +1
        assertEquals(-1.0f, state.rightStickX, 1e-3f)
        assertEquals(-1.0f, state.rightStickY, 1e-3f) // y inverted: -(max) -> -1
    }

    @Test
    fun sticks_saturateNegativeMin() {
        val state = FlydigiVaderInputParser.parseInput(
            inputReport(lx = -32768, ly = -32768)
        )!!
        assertEquals(1.0f, state.leftStickX, 1e-3f)  // clamped -32768 -> +1
        assertEquals(1.0f, state.leftStickY, 1e-3f)
    }

    @Test
    fun triggers_normalizedToUnit() {
        val state = FlydigiVaderInputParser.parseInput(inputReport(lt = 255, rt = 128))!!
        assertEquals(1.0f, state.leftTrigger, 1e-3f)
        assertEquals(128f / 255f, state.rightTrigger, 1e-3f)
    }

    // ---- IMU ----

    @Test
    fun imu_parsedWhenPresent() {
        val state = FlydigiVaderInputParser.parseInput(inputReport(imu = true))!!
        assertTrue(state.hasImu)
        // gyro 1000/32767 * 2000deg = ~61.06 rad/s
        val expected = 1000f / 32767f * (2000f * (Math.PI.toFloat() / 180.0f))
        assertEquals(expected, state.gyroX, 1e-2f)
        assertEquals(expected, state.gyroY, 1e-2f)
        assertEquals(-expected, state.gyroZ, 1e-2f) // z inverted
        // accel 4096 * (9.80665/4096) = 9.80665
        assertEquals(9.80665f, state.accelX, 1e-3f)
        assertEquals(9.80665f, state.accelY, 1e-3f)
        assertEquals(-9.80665f, state.accelZ, 1e-3f) // z inverted
    }

    @Test
    fun imu_zeroedWhenAbsent() {
        // Real Vader 5 always sends 32-byte input reports; IMU fields are simply zero
        // when the gyro/sensor mode is not enabled.
        val report = inputReport(imu = true)
        for (off in intArrayOf(17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28)) {
            report[off] = 0
        }
        val state = FlydigiVaderInputParser.parseInput(report)!!
        assertFalse(state.hasImu)
        assertEquals(0f, state.gyroX, 0f)
        assertEquals(0f, state.gyroY, 0f)
        assertEquals(0f, state.gyroZ, 0f)
        assertEquals(0f, state.accelX, 0f)
        assertEquals(0f, state.accelY, 0f)
        assertEquals(0f, state.accelZ, 0f)
    }

    // ---- malformed ----

    @Test
    fun malformedInput_rejected() {
        assertNull(FlydigiVaderInputParser.parseInput(ByteArray(4)))
        val notInput = inputReport()
        notInput[2] = 0x55
        assertNull(FlydigiVaderInputParser.parseInput(notInput))
        // Report-ID prefix should be stripped by the call site, never fed to parseInput.
        val withId = ByteArray(33) { if (it == 0) 0x03 else 0 }
        assertNull(FlydigiVaderInputParser.parseInput(withId))
    }

    // ---- battery ----

    @Test
    fun battery_discharging() {
        val info = FlydigiVaderInputParser.parseInfo(infoResponse(status = 0, level = 3))!!
        assertEquals(FlydigiVaderInputParser.BatteryStatus.DISCHARGING, info.batteryState)
        assertEquals(60, info.batteryPercentage)
    }

    @Test
    fun battery_charging() {
        val info = FlydigiVaderInputParser.parseInfo(infoResponse(status = 1, level = 2))!!
        assertEquals(FlydigiVaderInputParser.BatteryStatus.CHARGING, info.batteryState)
        assertEquals(40, info.batteryPercentage)
    }

    @Test
    fun battery_full_alwaysHundred() {
        val info = FlydigiVaderInputParser.parseInfo(infoResponse(status = 2, level = 4))!!
        assertEquals(FlydigiVaderInputParser.BatteryStatus.FULL, info.batteryState)
        assertEquals(100, info.batteryPercentage)
    }

    @Test
    fun battery_unknownStatus() {
        val info = FlydigiVaderInputParser.parseInfo(infoResponse(status = 7, level = 0))!!
        assertEquals(FlydigiVaderInputParser.BatteryStatus.UNKNOWN, info.batteryState)
        assertEquals(0, info.batteryPercentage)
    }

    @Test
    fun battery_malformed_rejected() {
        assertNull(FlydigiVaderInputParser.parseInfo(ByteArray(4)))
    }
}