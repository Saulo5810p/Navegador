package com.xaulinxs.aosp.browser.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Indicador circular de progresso de download: uma bolinha com contorno
 * fino que vai se "enchendo" de branco de baixo pra cima conforme o
 * conteúdo é baixado - visual usado por Chromium/Samsung Internet e
 * outros navegadores open source na lista de downloads em andamento.
 *
 * setProgressPercent(-1) desenha só o contorno vazio (progresso
 * desconhecido - servidor não informou o tamanho total do arquivo).
 */
class DownloadProgressCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#9E9E9E")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val clipPath = Path()
    private val ovalBounds = RectF()

    private var percent: Int = 0 // 0-100; -1 = desconhecido (só contorno)

    fun setProgressPercent(value: Int) {
        val clamped = if (value < 0) -1 else value.coerceIn(0, 100)
        if (clamped != percent) {
            percent = clamped
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val strokeInset = trackPaint.strokeWidth / 2f + dp(1f)
        ovalBounds.set(strokeInset, strokeInset, width - strokeInset, height - strokeInset)

        if (percent > 0) {
            canvas.save()
            clipPath.reset()
            clipPath.addOval(ovalBounds, Path.Direction.CW)
            canvas.clipPath(clipPath)
            val fillTop = ovalBounds.bottom - (percent / 100f) * ovalBounds.height()
            canvas.drawRect(ovalBounds.left, fillTop, ovalBounds.right, ovalBounds.bottom, fillPaint)
            canvas.restore()
        }

        canvas.drawOval(ovalBounds, trackPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
