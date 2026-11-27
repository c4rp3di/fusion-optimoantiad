package com.optimo.navegador

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Gestos de arrastre de la interfaz (Ajustes › Aspecto › «Gestos de arrastre»): cada uno se activa o apaga por separado. Apagado, el gesto
 * no intercepta los toques. Las tiras ya plegadas se pueden desplegar igualmente tocándolas. Por defecto todos activos; si en 1.37.0 se había
 * apagado el interruptor único (`gestos_arrastre`), ese valor es el de partida de todos hasta que se cambie alguno.
 */
object Gestos {
    /** Cerrar los paneles laterales (menú, ajustes, escudo…) arrastrándolos hacia la derecha. */
    const val PANELES = "paneles"

    /** Plegar la barra superior arrastrándola hacia arriba. */
    const val BARRA_SUP = "barra_sup"

    /** Plegar la barra inferior arrastrándola hacia abajo. */
    const val BARRA_INF = "barra_inf"

    /** Plegar a una tira, arrastrándolas, las hojas que dejan ver la página (ocultar elemento, capas, elegir elemento). */
    const val HOJA_PLEGAR = "hoja_plegar"

    /** Plegar esas mismas hojas tocando su tirador. */
    const val TIRADOR = "tirador"

    /** Cerrar arrastrándolas hacia abajo las demás hojas (confirmar, elegir, acciones, escribir). */
    const val HOJA_CERRAR = "hoja_cerrar"

    val TODOS: List<String> = listOf(PANELES, BARRA_SUP, BARRA_INF, HOJA_PLEGAR, TIRADOR, HOJA_CERRAR)

    private var sp: SharedPreferences? = null

    fun iniciar(c: Context) {
        sp = c.getSharedPreferences("optimo_ajustes", Context.MODE_PRIVATE)
    }

    private fun base(): Boolean = sp?.getBoolean("gestos_arrastre", true) ?: true

    fun activo(id: String): Boolean = sp?.getBoolean("gesto_" + id, base()) ?: true

    fun fijar(id: String, v: Boolean) {
        sp?.edit()?.putBoolean("gesto_" + id, v)?.apply()
    }

    fun cuantosActivos(): Int = TODOS.count { activo(it) }

    /** Vuelve a activar todos. */
    fun activarTodos() {
        val e = sp?.edit() ?: return
        e.putBoolean("gestos_arrastre", true)
        TODOS.forEach { e.remove("gesto_" + it) }
        e.apply()
    }
}

/**
 * Barra (superior o inferior) que se pliega al arrastrarla hacia fuera de la pantalla: la superior hacia arriba, la inferior hacia
 * abajo. Si el arrastre es corto, vuelve a su sitio. Al plegarse avisa con [alPlegar] (quien la llama la oculta y muestra el asa).
 */
class BarraPlegable(c: Context, private val haciaArriba: Boolean, private val alPlegar: () -> Unit) : LinearLayout(c) {
    private var x0 = 0f
    private var y0 = 0f
    private var arrastrando = false
    private val margen = ViewConfiguration.get(c).scaledTouchSlop

    private fun sentidoValido(dy: Float): Boolean = if (haciaArriba) dy < 0f else dy > 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!Gestos.activo(if (haciaArriba) Gestos.BARRA_SUP else Gestos.BARRA_INF)) {
            arrastrando = false
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = ev.rawX
                y0 = ev.rawY
                arrastrando = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - y0
                val dx = ev.rawX - x0
                if (!arrastrando && abs(dy) > margen && abs(dy) > 1.5f * abs(dx) && sentidoValido(dy)) {
                    arrastrando = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return arrastrando
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                y0 = ev.rawY
                x0 = ev.rawX
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - y0
                translationY = if (sentidoValido(dy)) dy else 0f
                alpha = (1f - abs(translationY) / height.coerceAtLeast(1)).coerceIn(0.3f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = ev.rawY - y0
                arrastrando = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && sentidoValido(dy) && abs(dy) > height * 0.4f) {
                    animate().translationY(if (haciaArriba) -height.toFloat() else height.toFloat()).alpha(0f).setDuration(140L)
                        .withEndAction { alPlegar() }
                } else {
                    animate().translationY(0f).alpha(1f).setDuration(140L)
                }
            }
        }
        return true
    }

    /** Deja la barra como nueva (tras un pliegue, para cuando vuelva a mostrarse). */
    fun restablecer() {
        animate().cancel()
        translationY = 0f
        alpha = 1f
    }
}

/**
 * Tira fina que ocupa el sitio de una barra plegada. Pulsarla, o arrastrarla hacia dentro (la de arriba hacia abajo, la de abajo hacia
 * arriba), despliega la barra.
 */
class AsaBarra(c: Context, paleta: Paleta, private val arriba: Boolean, private val alDesplegar: () -> Unit) : FrameLayout(c) {
    private var y0 = 0f
    private val margen = ViewConfiguration.get(c).scaledTouchSlop

    init {
        val d = c.resources.displayMetrics.density
        setBackgroundColor(paleta.barra)
        isClickable = true
        val pastilla = View(c)
        val forma = GradientDrawable()
        forma.setColor(paleta.tenue)
        forma.cornerRadius = 2f * d
        pastilla.background = forma
        pastilla.alpha = 0.6f
        addView(pastilla, LayoutParams((40 * d + 0.5f).toInt(), (4 * d + 0.5f).toInt(), Gravity.CENTER))
        contentDescription = if (arriba) "Mostrar la barra superior" else "Mostrar la barra inferior"
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> y0 = ev.rawY
            MotionEvent.ACTION_UP -> {
                val dy = ev.rawY - y0
                // Un toque o un arrastre hacia dentro; no un arrastre hacia fuera de la pantalla.
                val haciaDentro = if (arriba) dy > -margen else dy < margen
                if (haciaDentro) alDesplegar()
            }
        }
        return true
    }
}
