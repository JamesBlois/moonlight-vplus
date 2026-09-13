package com.limelight.binding.input

import android.view.KeyEvent

/**
 * Shared knowledge about the Vader 5 Pro Bluetooth extended buttons (M1..M4, C, Z, LM, RM).
 *
 * The controller exposes these as extra re-mappable inputs. Android usually reports the
 * physical press as one of the generic gamepad key codes ([KeyEvent.KEYCODE_BUTTON_1]..6)
 * or as an unmapped scan code (e.g. BTN_TRIGGER_HAPPY5..8 used by our USB driver). Because
 * the exact mapping depends on the device's HID descriptor, the app lets the user capture
 * the physical key interactively and stores "keyCode#scanCode" strings.
 *
 * This object keeps the button names, the default assignments and the parsing shared by the
 * capture UI ([FlydigiButtonMappingActivity]) and the runtime remap in [ControllerHandler].
 */
object FlydigiButtonRemapper {
    /** Stable button identifiers used as map keys. */
    const val BTN_M1 = "m1"
    const val BTN_M2 = "m2"
    const val BTN_M3 = "m3"
    const val BTN_M4 = "m4"
    const val BTN_C = "c"
    const val BTN_Z = "z"
    const val BTN_LM = "lm"
    const val BTN_RM = "rm"

    /** Vendor ID of Flydigi controllers (VID 0x37d7). */
    const val FLYDIGI_VID = 0x37d7

    /** Product ID of the Vader 5 Pro (0x2401). */
    const val VADER5_PID = 0x2401

    val EXTENDED_BUTTONS: List<String> = listOf(
        BTN_M1, BTN_M2, BTN_M3, BTN_M4, BTN_C, BTN_Z, BTN_LM, BTN_RM
    )

    fun buttonDisplayName(button: String): String = when (button) {
        BTN_M1 -> "M1"
        BTN_M2 -> "M2"
        BTN_M3 -> "M3"
        BTN_M4 -> "M4"
        BTN_C -> "C"
        BTN_Z -> "Z"
        BTN_LM -> "LM"
        BTN_RM -> "RM"
        else -> button
    }

    /** Human-readable label for a stored "keyCode#scanCode" binding, e.g. "Button 1 (#304)". */
    fun bindingLabel(binding: String): String {
        val (keyCode, scanCode) = parseBinding(binding)
        val keyName = if (keyCode != 0) KeyEvent.keyCodeToString(keyCode) else null
        val scanPart = if (scanCode != 0) "scan #$scanCode" else null
        return listOf(keyName, scanPart).filterNotNull().joinToString(" / ")
    }

    fun parseBinding(binding: String): Pair<Int, Int> {
        val parts = binding.split('#')
        val keyCode = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val scanCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return keyCode to scanCode
    }

    fun makeBinding(keyCode: Int, scanCode: Int): String = "$keyCode#$scanCode"

    /**
     * Starting point when the user has not captured anything yet.
     *
     * M1..M4 use the generic Android gamepad button key codes that Flydigi's HID
     * descriptor reports for the paddles. C and Z use the next generic buttons.
     *
     * LM and RM are intentionally left unbound: on Bluetooth they arrive as analog
     * trigger presses (LTRIGGER/RTRIGGER) rather than reliable buttons, so binding
     * them to a key code by default would steal the triggers. The capture screen
     * lets the user bind them explicitly when needed.
     */
    val DEFAULT_BINDINGS: Map<String, String> = mapOf(
        BTN_M1 to makeBinding(KeyEvent.KEYCODE_BUTTON_1, 0),
        BTN_M2 to makeBinding(KeyEvent.KEYCODE_BUTTON_2, 0),
        BTN_M3 to makeBinding(KeyEvent.KEYCODE_BUTTON_3, 0),
        BTN_M4 to makeBinding(KeyEvent.KEYCODE_BUTTON_4, 0),
        BTN_C to makeBinding(KeyEvent.KEYCODE_BUTTON_5, 0),
        BTN_Z to makeBinding(KeyEvent.KEYCODE_BUTTON_6, 0)
        // BTN_LM, BTN_RM intentionally omitted — see comment above.
    )
}