package com.xaulinxs.aosp.browser.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.xaulinxs.aosp.browser.R

/**
 * Card/barra retangular com cantos arredondados (configurável), gradiente
 * linear e sombra suave, desenhado inteiramente em Canvas puro (sem
 * elevation/Material, seguindo a mesma regra de "UI crua do framework" do
 * resto do app - elevation depende de androidx/Material pra sombra
 * bonita em API baixa, então a sombra aqui é pintada manualmente com
 * Paint.setShadowLayer).
 *
 * Reaproveitada em vários lugares da UI: card de atalhos da Home (cantos
 * bem arredondados), nova barra superior de navegação e painel lateral de
 * atalhos (cantos menores/retos) - cada um passa o raio que faz sentido
 * via setCornerRadiusDp() ou pelo atributo cornerRadiusDp no XML.
 *
 * As cores do gradiente respeitam claro/escuro automaticamente: por
 * padrão usa os mesmos tons de cinza translúcido do bg_bottom_bar_gradient
 * existente, mas podem ser customizadas via setGradientColors().
 */
class GradientShadowCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Raio dos cantos e "força" da sombra em px, convertidos de dp no init.
    private var cornerRadiusPx: Float
    private val shadowRadiusPx: Float
    private val shadowDy: Float

    // Cores do gradiente horizontal (mesmo espírito do bg_bottom_bar_gradient:
    // cinza translúcido claro-escuro-claro), mais a cor da sombra.
    private var gradientStart = 0x332C2C2C
    private var gradientCenter = 0x33454545
    private var gradientEnd = 0x332C2C2C
    private var shadowColor = 0x40000000

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val cardRect = RectF()

    init {
        val density = resources.displayMetrics.density
        var initialCornerRadiusDp = 24f
        shadowRadiusPx = 10f * density
        shadowDy = 4f * density

        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.GradientShadowCardView)
            initialCornerRadiusDp = typedArray.getFloat(R.styleable.GradientShadowCardView_cornerRadiusDp, initialCornerRadiusDp)
            typedArray.recycle()
        }
        cornerRadiusPx = initialCornerRadiusDp * density

        // Sombra pintada via Paint.setShadowLayer - exige camada de
        // software pra funcionar em qualquer API (hardware acceleration
        // ignora setShadowLayer em alguns drivers/GPUs mais antigos,
        // cenário AOSP/GSI minimalista que já motivou outras decisões do
        // projeto). O custo de performance é irrelevante aqui: a View é
        // pequena e só é redesenhada quando o layout muda.
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        strokePaint.color = 0x33808080
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Margem interna igual ao raio da sombra, senão o Canvas corta a
        // sombra nas bordas da própria View.
        val margin = shadowRadiusPx + kotlin.math.abs(shadowDy)
        cardRect.set(margin, margin / 2f, w - margin, h - margin / 2f)

        fillPaint.shader = LinearGradient(
            cardRect.left, 0f, cardRect.right, 0f,
            intArrayOf(gradientStart, gradientCenter, gradientEnd),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.setShadowLayer(shadowRadiusPx, 0f, shadowDy, shadowColor)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, fillPaint)
        canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, strokePaint)
    }

    /** Permite trocar as cores do gradiente em tempo de execução (ex: variante -night manual, se necessário no futuro). */
    fun setGradientColors(start: Int, center: Int, end: Int) {
        gradientStart = start
        gradientCenter = center
        gradientEnd = end
        if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
        invalidate()
    }

    /** Permite trocar o raio dos cantos em tempo de execução (dp) - útil quando a mesma View muda de contexto (ex: sidebar recolhida vs expandida). */
    fun setCornerRadiusDp(radiusDp: Float) {
        cornerRadiusPx = radiusDp * resources.displayMetrics.density
        invalidate()
    }
}
