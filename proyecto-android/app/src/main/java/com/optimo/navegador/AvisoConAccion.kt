package com.optimo.navegador

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Aviso breve sobre la barra inferior con un botón (p. ej. «Deshacer»). Se quita solo a los pocos segundos, al pulsar el botón o
 * cuando sale otro aviso. Un único recordatorio de un solo disparo, sin sondeos. También se descarta deslizándolo hacia un lado.
 */
class AvisoConAccion(private val act: Activity, private val raiz: FrameLayout) {
    private var vista: View? = null
    private val manejador = Handler(Looper.getMainLooper())
    private val quitarYa = Runnable { quitar() }

    /** Fila que se puede arrastrar hacia los lados para descartarla (también si el dedo empieza sobre el botón). */
    private inner class FilaDeslizable(c: Context) : LinearLayout(c) {
        private var x0 = 0f
        private var arrastrando = false
        private val margen = ViewConfiguration.get(c).scaledTouchSlop

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = ev.rawX
                    arrastrando = false
                }
                MotionEvent.ACTION_MOVE -> if (!arrastrando && abs(ev.rawX - x0) > margen) {
                    arrastrando = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            return arrastrando
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> x0 = ev.rawX
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - x0
                    translationX = dx
                    alpha = (1f - abs(dx) / width.coerceAtLeast(1)).coerceIn(0.2f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.rawX - x0
                    arrastrando = false
                    if (abs(dx) > width / 3f) {
                        manejador.removeCallbacks(quitarYa)
                        animate().translationX(if (dx > 0) width.toFloat() else -width.toFloat()).alpha(0f).setDuration(150L)
                            .withEndAction { quitar() }
                    } else {
                        animate().translationX(0f).alpha(1f).setDuration(150L)
                    }
                }
            }
            return true
        }
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    fun mostrar(texto: String, boton: String, ms: Long, alPulsar: () -> Unit) {
        quitar()
        val fila = FilaDeslizable(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(dp(16), dp(4), dp(6), dp(4))
        val fondo = GradientDrawable()
        fondo.setColor(Color.parseColor("#323232"))
        fondo.cornerRadius = dp(12).toFloat()
        fila.background = fondo
        fila.elevation = dp(8).toFloat()
        val t = TextView(act)
        t.text = texto
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        fila.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = TextView(act)
        b.text = boton
        b.setTextColor(Color.parseColor("#4DD0C4"))
        b.textSize = 14f
        b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
        b.setPadding(dp(14), dp(12), dp(14), dp(12))
        b.isClickable = true
        b.setOnClickListener {
            quitar()
            alPulsar()
        }
        fila.addView(b)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(dp(12), 0, dp(12), dp(72))
        raiz.addView(fila, lp)
        vista = fila
        manejador.postDelayed(quitarYa, ms)
    }

    fun quitar() {
        manejador.removeCallbacks(quitarYa)
        val v = vista
        if (v != null) raiz.removeView(v)
        vista = null
    }
}
