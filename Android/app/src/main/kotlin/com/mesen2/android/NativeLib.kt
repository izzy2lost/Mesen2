package com.mesen2.android

/**
 * JNI bridge to the Mesen2 NES core (libMesenCore.so).
 *
 * Button IDs map directly to the NesKeyCode constants defined in android_key_manager.h:
 *   A=1, B=2, Select=3, Start=4, Up=5, Down=6, Left=7, Right=8
 *
 * VideoFilter values map to the Mesen2 VideoFilterType enum.
 */
object NativeLib {

    // ── Button IDs ─────────────────────────────────────────────��──────────────
    const val BTN_A      = 1
    const val BTN_B      = 2
    const val BTN_SELECT = 3
    const val BTN_START  = 4
    const val BTN_UP     = 5
    const val BTN_DOWN   = 6
    const val BTN_LEFT   = 7
    const val BTN_RIGHT  = 8

    // ── Video filter IDs (VideoFilterType enum in SettingTypes.h) ────────────
    const val FILTER_NONE         = 0
    const val FILTER_NTSC         = 1  // NtscBlargg
    const val FILTER_BISQWIT_NTSC = 2  // NtscBisqwit
    const val FILTER_LCD_GRID     = 3
    const val FILTER_XBRZ_2X      = 4
    const val FILTER_XBRZ_3X      = 5
    const val FILTER_XBRZ_4X      = 6
    const val FILTER_XBRZ_5X      = 7
    const val FILTER_XBRZ_6X      = 8
    const val FILTER_HQ2X         = 9
    const val FILTER_HQ3X         = 10
    const val FILTER_HQ4X         = 11
    const val FILTER_SCALE2X      = 12
    const val FILTER_SCALE3X      = 13
    const val FILTER_SCALE4X      = 14
    const val FILTER_2XSAI        = 15
    const val FILTER_SUPER_2XSAI  = 16
    const val FILTER_SUPER_EAGLE  = 17

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    external fun initialize(homeFolder: String)
    external fun release()

    // ── ROM control ───────────────────────────────────────────────────────────
    external fun loadRom(romPath: String): Boolean
    external fun stopRom()
    external fun pause()
    external fun resume()
    external fun isRunning(): Boolean

    // ── Input ─────────────────────────────────────────────────────────────────
    external fun setButtonState(buttonId: Int, pressed: Boolean)

    // ── Settings ──────────────────────────────────────────────────────────────
    external fun setVideoFilter(filterType: Int)
    external fun setHdPacksEnabled(enabled: Boolean)

    // ── OpenGL (called on the GL thread) ──────────────────────────────────────
    external fun glInit()
    external fun glDrawFrame(viewWidth: Int, viewHeight: Int)
    external fun glDestroy()

    // ── Debug ─────────────────────────────────────────────────────────────────
    external fun getLog(): String

    init {
        System.loadLibrary("MesenCore")
    }
}
