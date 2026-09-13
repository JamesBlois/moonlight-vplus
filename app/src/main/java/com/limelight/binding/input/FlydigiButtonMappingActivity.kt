package com.limelight.binding.input

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import java.util.concurrent.atomic.AtomicReference

/**
 * Lets a user bind the Vader 5 Pro Bluetooth extended buttons (M1..M4, C, Z, LM, RM).
 *
 * Each row shows the button name and its current binding. Tapping a row enters capture
 * mode: whichever key/button the user presses next (from a keyboard or a gamepad) is
 * recorded as `<keyCode>#<scanCode>` and the list refreshes. Tapping the same row again
 * while it is already capturing clears the binding. Capturing auto-cancels after a short
 * timeout.
 *
 * The mapping is consumed by [ControllerHandler] through
 * [PreferenceConfiguration.flydigiBtExtraButtons].
 */
class FlydigiButtonMappingActivity : ComponentActivity() {

    private val captureButton = AtomicReference<String?>(null)
    private val timeoutHandler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var rowViews: MutableMap<String, Button>

    private val map: MutableMap<String, String> =
        PreferenceConfiguration.readPreferences(this).flydigiBtExtraButtons.toMutableMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flydigi_button_mapping)

        statusText = findViewById(R.id.mapping_status)
        listContainer = findViewById(R.id.mapping_list)
        rowViews = HashMap()

        // Seed defaults only for buttons the user has never touched, so the extended
        // buttons still work out of the box while capture can override them later.
        for ((button, binding) in FlydigiButtonRemapper.DEFAULT_BINDINGS) {
            if (!map.containsKey(button)) {
                map[button] = binding
            }
        }

        findViewById<Button>(R.id.mapping_reset).setOnClickListener { resetToDefaults() }
        rebuildList()
        renderStatus(R.string.status_flydigi_mapping_idle)
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStop() {
        // Persist whatever was captured even when the user leaves through the
        // system back-gesture (which does not always go through onBackPressed()).
        persist()
        super.onStop()
    }

    override fun onBackPressed() {
        // Persist whatever was captured before leaving.
        persist()
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Never swallow the system Back navigation so the user can always leave.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            handleCapturedKey(event.keyCode, event.scanCode)
        }
        // Swallow all other keys while the mapping screen is in front, so pressed
        // keys are not interpreted as UI navigation during capture.
        return true
    }

    private fun handleCapturedKey(keyCode: Int, scanCode: Int) {
        val active = captureButton.get() ?: return
        val binding = FlydigiButtonRemapper.makeBinding(keyCode, scanCode)

        if (map[active] == binding) {
            // Re-pressing the same physical key clears the binding.
            map.remove(active)
            renderStatus(R.string.status_flydigi_mapping_removed)
        } else {
            // The same physical key cannot belong to two buttons; move it if needed
            // (first captured button keeps the key, the older owner loses it).
            for ((button, existing) in map.entries.toList()) {
                if (button != active && existing == binding) {
                    map.remove(button)
                }
            }
            map[active] = binding
            renderStatus(
                R.string.status_flydigi_mapping_saved,
                FlydigiButtonRemapper.bindingLabel(binding)
            )
        }
        captureButton.set(null)
        timeoutHandler.removeCallbacksAndMessages(null)
        persist()
        rebuildList()
    }

    private fun startCapture(button: String) {
        val active = captureButton.get()
        if (active == button) {
            // Second tap on the same row while capturing clears the binding directly.
            map.remove(button)
            captureButton.set(null)
            timeoutHandler.removeCallbacksAndMessages(null)
            renderStatus(R.string.status_flydigi_mapping_removed)
            persist()
            rebuildList()
            return
        }

        captureButton.set(button)
        renderStatus(R.string.status_flydigi_mapping_capturing)
        highlightRow(button, capturing = true)

        timeoutHandler.removeCallbacksAndMessages(null)
        timeoutHandler.postDelayed({ cancelCapture(button) }, CAPTURE_TIMEOUT_MS)
    }

    private fun cancelCapture(button: String) {
        if (captureButton.compareAndSet(button, null)) {
            highlightRow(button, capturing = false)
            renderStatus(R.string.status_flydigi_mapping_idle)
        }
    }

    private fun highlightRow(button: String, capturing: Boolean) {
        rowViews[button]?.alpha = if (capturing) 0.5f else 1.0f
    }

    private fun rebuildList() {
        listContainer.removeAllViews()
        rowViews.clear()
        for (button in FlydigiButtonRemapper.EXTENDED_BUTTONS) {
            val binding = map[button]
            val label = binding?.let { FlydigiButtonRemapper.bindingLabel(it) }
                ?: getString(R.string.status_flydigi_mapping_idle)
            val row = Button(this)
            row.text = getString(
                R.string.title_flydigi_mapping_item,
                FlydigiButtonRemapper.buttonDisplayName(button),
                label
            )
            row.setOnClickListener { startCapture(button) }
            rowViews[button] = row
            listContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            )
        }
    }

    private fun renderStatus(stringId: Int, arg: String? = null) {
        statusText.text = if (arg != null) getString(stringId, arg) else getString(stringId)
    }

    private fun persist() {
        PreferenceConfiguration.writeFlydigiBtExtraButtons(this, map)
    }

    private fun resetToDefaults() {
        map.clear()
        map.putAll(FlydigiButtonRemapper.DEFAULT_BINDINGS)
        captureButton.set(null)
        timeoutHandler.removeCallbacksAndMessages(null)
        renderStatus(R.string.status_flydigi_mapping_idle)
        rebuildList()
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 3000L
    }
}