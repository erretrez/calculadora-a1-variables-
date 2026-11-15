package cl.erretrez.calculadora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Dibuja el detalle del cálculo como imagen (respaldo para guardar en la galería o compartir).
 * Siempre en colores claros para que se lea bien impresa o enviada por mensaje.
 */
object ResumenImagen {
    private const val ANCHO = 1080
    private const val MARGEN = 56

    private val TEXTO = Color.parseColor("#1B2430")
    private val SUAVE = Color.parseColor("#5B6776")
    private val ACENTO = Color.parseColor("#1E6FD9")
    private val VERDE = Color.parseColor("#1B8A3A")
    private val VIOLETA = Color.parseColor("#6A3DE8")
    private val FONDO_TOTAL = Color.parseColor("#EAF1FF")
    private val LINEA = Color.parseColor("#D5DBE2")

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(text: String, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .build()

    fun render(filas: List<Fila>): Bitmap {
        val alto = pintar(filas, null)
        val bmp = Bitmap.createBitmap(ANCHO, alto, Bitmap.Config.ARGB_8888)
        pintar(filas, Canvas(bmp))
        return bmp
    }

    /** Con canvas == null solo mide; devuelve el alto total en píxeles. */
    private fun pintar(filas: List<Fila>, canvas: Canvas?): Int {
        val ancho = ANCHO - 2 * MARGEN
        canvas?.drawColor(Color.WHITE)

        // Encabezado: nombre del cálculo y subtítulo, sobre una banda de colores vivos.
        val titulo = filas.firstOrNull { it.estilo == Estilo.TITULO }
        val sub = filas.firstOrNull { it.estilo == Estilo.SUBTITULO_ENC }
        val pTitulo = paint(72f, Color.WHITE, bold = true)
        val pSub = paint(34f, Color.parseColor("#E8F0FF"))
        val lTitulo = layout(titulo?.etiqueta.orEmpty(), pTitulo, ancho)
        val lSub = layout(sub?.etiqueta.orEmpty(), pSub, ancho)
        val altoBanda = MARGEN + lTitulo.height + 16 + lSub.height + MARGEN
        if (canvas != null) {
            val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, ANCHO.toFloat(), altoBanda.toFloat(),
                    intArrayOf(Color.parseColor("#00B8D9"), Color.parseColor("#7C4DFF"), Color.parseColor("#FF2DAA")),
                    null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, ANCHO.toFloat(), altoBanda.toFloat(), fondo)
            canvas.save(); canvas.translate(MARGEN.toFloat(), MARGEN.toFloat()); lTitulo.draw(canvas); canvas.restore()
            canvas.save()
            canvas.translate(MARGEN.toFloat(), (MARGEN + lTitulo.height + 16).toFloat())
            lSub.draw(canvas); canvas.restore()
        }

        var y = altoBanda + 28
        for (fila in filas) {
            when (fila.estilo) {
                Estilo.TITULO, Estilo.SUBTITULO_ENC -> Unit
                Estilo.SEPARADOR -> y += 24
                Estilo.SECCION -> {
                    y += 22
                    val p = paint(34f, ACENTO, bold = true)
                    val l = layout(fila.etiqueta.uppercase(), p, ancho)
                    canvas?.let { it.save(); it.translate(MARGEN.toFloat(), y.toFloat()); l.draw(it); it.restore() }
                    y += l.height + 6
                    canvas?.drawRect(MARGEN.toFloat(), y.toFloat(), (ANCHO - MARGEN).toFloat(), (y + 3).toFloat(),
                        Paint().apply { color = LINEA })
                    y += 14
                }
                Estilo.NOTA -> {
                    val l = layout(fila.etiqueta, paint(28f, SUAVE), ancho)
                    y += 8
                    canvas?.let { it.save(); it.translate(MARGEN.toFloat(), y.toFloat()); l.draw(it); it.restore() }
                    y += l.height + 14
                }
                else -> {
                    val (tam, color, negrita, alto) = when (fila.estilo) {
                        Estilo.TOTAL -> Cuatro(44f, TEXTO, true, 92)
                        Estilo.GANANCIA -> Cuatro(38f, VERDE, true, 76)
                        Estilo.VENTA -> Cuatro(38f, VIOLETA, true, 76)
                        else -> Cuatro(34f, TEXTO, false, 66)
                    }
                    if (canvas != null) {
                        if (fila.estilo == Estilo.TOTAL) {
                            canvas.drawRect(
                                (MARGEN - 16).toFloat(), y.toFloat(), (ANCHO - MARGEN + 16).toFloat(),
                                (y + alto - 8).toFloat(), Paint().apply { this.color = FONDO_TOTAL }
                            )
                        }
                        val pEtiqueta = paint(tam, color, negrita)
                        val pValor = paint(tam, color, true).apply { textAlign = Paint.Align.RIGHT }
                        val base = y + alto / 2f + tam / 3f - 4f
                        // La etiqueta se recorta con "…" si no cabe junto al valor.
                        val disponible = ancho - pValor.measureText(fila.valor) - 24
                        val texto = android.text.TextUtils.ellipsize(
                            fila.etiqueta, pEtiqueta, disponible, android.text.TextUtils.TruncateAt.END
                        ).toString()
                        canvas.drawText(texto, MARGEN.toFloat(), base, pEtiqueta)
                        canvas.drawText(fila.valor, (ANCHO - MARGEN).toFloat(), base, pValor)
                    }
                    y += alto
                }
            }
        }
        return y + MARGEN
    }

    private data class Cuatro(val a: Float, val b: Int, val c: Boolean, val d: Int)
}
