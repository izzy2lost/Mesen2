package com.mesen2.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * On-screen NES gamepad overlay.
 *
 * Layout:
 *  - D-pad on the left (cross shape)
 *  - A / B buttons on the right
 *  - Start / Select buttons in the center
 *
 * Tracks multiple pointers so D-pad and buttons can be pressed simultaneously.
 */
class TouchControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Geometry (set in onSizeChanged) ──────────────────────────────────────
    private val dpadRect   = RectF()
    private val btnARect   = RectF()
    private val btnBRect   = RectF()
    private val btnStartR  = RectF()
    private val btnSelectR = RectF()

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val fillPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 200, 200)
        style = Paint.Style.FILL
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 80, 180, 255)
        style = Paint.Style.FILL
    }
    private val textPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private val pressed = mutableSetOf<Int>()  // set of NativeLib.BTN_* currently pressed

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)

        val pad   = w * 0.02f
        val dSize = h * 0.70f
        val bSize = h * 0.28f

        // D-pad: lower-left
        val dpadL = pad
        val dpadT = h - dSize - pad
        dpadRect.set(dpadL, dpadT, dpadL + dSize, dpadT + dSize)

        // Action buttons: lower-right
        val btnR = w - pad
        val btnB_cx = btnR - bSize * 0.5f
        val btnA_cx = btnR - bSize * 1.6f
        val btnCY   = h - bSize * 0.7f - pad

        btnARect.set(btnA_cx - bSize * 0.5f, btnCY - bSize * 0.5f,
                     btnA_cx + bSize * 0.5f, btnCY + bSize * 0.5f)
        btnBRect.set(btnB_cx - bSize * 0.5f, btnCY - bSize * 0.5f,
                     btnB_cx + bSize * 0.5f, btnCY + bSize * 0.5f)

        // Start / Select: center-bottom
        val centerX = w * 0.5f
        val smW = w * 0.12f
        val smH = h * 0.08f
        val smY = h - smH - pad
        btnSelectR.set(centerX - smW * 1.2f, smY, centerX - smW * 0.1f, smY + smH)
        btnStartR .set(centerX + smW * 0.1f, smY, centerX + smW * 1.2f, smY + smH)

        textPaint.textSize = smH * 0.55f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawDpad(canvas)
        drawButton(canvas, btnARect,   "A",      NativeLib.BTN_A)
        drawButton(canvas, btnBRect,   "B",      NativeLib.BTN_B)
        drawButton(canvas, btnStartR,  "START",  NativeLib.BTN_START)
        drawButton(canvas, btnSelectR, "SELECT", NativeLib.BTN_SELECT)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, btnId: Int) {
        val p = if (btnId in pressed) pressedPaint else fillPaint
        canvas.drawOval(rect, p)
        canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize * 0.35f, textPaint)
    }

    private fun drawDpad(canvas: Canvas) {
        val arm = dpadRect.width() / 3f
        val cx  = dpadRect.centerX()
        val cy  = dpadRect.centerY()

        val directions = listOf(
            Triple(NativeLib.BTN_UP,    RectF(cx - arm/2, dpadRect.top,    cx + arm/2, cy - arm/2), "▲"),
            Triple(NativeLib.BTN_DOWN,  RectF(cx - arm/2, cy + arm/2,      cx + arm/2, dpadRect.bottom), "▼"),
            Triple(NativeLib.BTN_LEFT,  RectF(dpadRect.left,  cy - arm/2, cx - arm/2,  cy + arm/2), "◀"),
            Triple(NativeLib.BTN_RIGHT, RectF(cx + arm/2,     cy - arm/2, dpadRect.right, cy + arm/2), "▶"),
        )
        for ((btnId, rect, label) in directions) {
            val p = if (btnId in pressed) pressedPaint else fillPaint
            canvas.drawRoundRect(rect, arm * 0.2f, arm * 0.2f, p)
            canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize * 0.35f, textPaint)
        }

        // Center piece
        val cRect = RectF(cx - arm/2, cy - arm/2, cx + arm/2, cy + arm/2)
        canvas.drawRoundRect(cRect, arm * 0.2f, arm * 0.2f, fillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val newPressed = mutableSetOf<Int>()

        for (i in 0 until event.pointerCount) {
            val x = event.getX(i)
            val y = event.getY(i)
            val action = event.actionMasked

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                // Pointer released – don't add to newPressed
                if (event.getPointerId(i) != event.getPointerId(event.actionIndex)) {
                    collectHits(x, y, newPressed)
                }
            } else {
                collectHits(x, y, newPressed)
            }
        }

        // Update changed buttons
        val toRelease = pressed - newPressed
        val toPress   = newPressed - pressed

        for (btn in toRelease) NativeLib.setButtonState(btn, false)
        for (btn in toPress)   NativeLib.setButtonState(btn, true)

        pressed.clear()
        pressed.addAll(newPressed)

        invalidate()
        return true
    }

    private fun collectHits(x: Float, y: Float, out: MutableSet<Int>) {
        if (dpadRect.contains(x, y)) {
            val dx = x - dpadRect.centerX()
            val dy = y - dpadRect.centerY()
            val threshold = dpadRect.width() / 6f
            if (dx < -threshold) out.add(NativeLib.BTN_LEFT)
            if (dx >  threshold) out.add(NativeLib.BTN_RIGHT)
            if (dy < -threshold) out.add(NativeLib.BTN_UP)
            if (dy >  threshold) out.add(NativeLib.BTN_DOWN)
        }
        if (btnARect.contains(x, y))    out.add(NativeLib.BTN_A)
        if (btnBRect.contains(x, y))    out.add(NativeLib.BTN_B)
        if (btnStartR.contains(x, y))   out.add(NativeLib.BTN_START)
        if (btnSelectR.contains(x, y))  out.add(NativeLib.BTN_SELECT)
    }
}
