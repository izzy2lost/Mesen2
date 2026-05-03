package com.mesen2.android

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import com.mesen2.android.databinding.ActivityEmulatorBinding

class EmulatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmulatorBinding

    companion object {
        const val EXTRA_ROM_PATH = "ROM_PATH"
    }

    // Mapping from Android KeyEvent keycodes to Mesen NES button IDs
    private val keyMap = mapOf(
        KeyEvent.KEYCODE_BUTTON_A      to NativeLib.BTN_A,
        KeyEvent.KEYCODE_BUTTON_B      to NativeLib.BTN_B,
        KeyEvent.KEYCODE_BUTTON_X      to NativeLib.BTN_B,
        KeyEvent.KEYCODE_BUTTON_START  to NativeLib.BTN_START,
        KeyEvent.KEYCODE_BUTTON_SELECT to NativeLib.BTN_SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE   to NativeLib.BTN_SELECT,
        KeyEvent.KEYCODE_DPAD_UP       to NativeLib.BTN_UP,
        KeyEvent.KEYCODE_DPAD_DOWN     to NativeLib.BTN_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT     to NativeLib.BTN_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT    to NativeLib.BTN_RIGHT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmulatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUi()

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH) ?: run {
            finish()
            return
        }

        NativeLib.loadRom(romPath)
    }

    override fun onResume() {
        super.onResume()
        binding.glSurface.onResume()
        NativeLib.resume()
    }

    override fun onPause() {
        super.onPause()
        NativeLib.pause()
        binding.glSurface.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeLib.stopRom()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        keyMap[keyCode]?.let { btn ->
            NativeLib.setButtonState(btn, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        keyMap[keyCode]?.let { btn ->
            NativeLib.setButtonState(btn, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun hideSystemUi() {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
