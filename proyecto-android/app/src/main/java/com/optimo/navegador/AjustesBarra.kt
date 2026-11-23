package com.optimo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Ajustes › «Barra de direcciones…»: mostrar u ocultar cada botón, cambiar su orden y su tamaño, la separación y el alto.
 * Los cambios se aplican al momento ([alCambiar] vuelve a montar la barra) y arriba hay una vista previa.
 */
class AjustesBarra(
    private val act: Activity,
    private val paleta: Paleta,
    private val nuevoDialogo: () -> AlertDialog.Builder,
    private val alCambiar: () -> Unit
) {
    private val contenido = LinearLayout(act)

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    fun mostrar() {
        contenido.orientation = LinearLayout.VERTICAL
        contenido.setPadding(dp(16), dp(8), dp(16), dp(8))
        val scroll = ScrollView(act)
        scroll.addView(contenido)
        pintar()
        nuevoDialogo()
            .setTitle("Barra de direcciones")
            .setView(scroll)
            .setPositiveButton("Listo", null)
            .setNeutralButton("Restablecer") { _, _ ->
                BarraConfig.restablecer()
                alCambiar()
                AjustesBarra(act, paleta, nuevoDialogo, alCambiar).mostrar()
            }
            .show()
    }

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean = false): TextView {
        val v = TextView(act)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, tam)
        v.setTextColor(color)
        if (negrita) v.typeface = Typeface.DEFAULT_BOLD
        return v
    }

    private fun conRipple(v: View) {
        v.background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, ColorDrawable(Color.WHITE))
    }

    private fun cambio() {
        alCambiar()
        pintar()
    }

    /** Botón cuadrado pequeño (▲ ▼ − +). */
    private fun mini(t: String, activo: Boolean = true, accion: () -> Unit): TextView {
        val b = texto(t, 16f, paleta.texto)
        b.gravity = Gravity.CENTER
        b.alpha = if (activo) 1f else 0.3f
        b.isClickable = activo
        conRipple(b)
        if (activo) b.setOnClickListener { accion() }
        return b
    }

    private fun pintar() {
        contenido.removeAllViews()
        contenido.addView(texto("Vista previa", 11f, paleta.acento, true))
        contenido.addView(vistaPrevia(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(6)
            it.bottomMargin = dp(10)
        })
        contenido.addView(texto("De arriba abajo = de izquierda a derecha. Lo que queda antes del campo de dirección va a su izquierda.", 12f, paleta.tenue))

        val entradas = BarraConfig.orden()
        for ((i, e) in entradas.withIndex()) {
            val omnibox = e.id == BarraConfig.OMNIBOX
            val def = BarraConfig.definicion(e.id)
            val fila = LinearLayout(act)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.gravity = Gravity.CENTER_VERTICAL
            fila.setPadding(0, dp(8), 0, 0)
            val nombre = texto((if (omnibox) "⌨  Campo de dirección" else def?.icono + "  " + def?.nombre), 15f,
                if (omnibox || e.visible) paleta.texto else paleta.tenue)
            fila.addView(nombre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fila.addView(mini("▲", i > 0) { BarraConfig.mover(e.id, -1); cambio() }, LinearLayout.LayoutParams(dp(40), dp(40)))
            fila.addView(mini("▼", i < entradas.size - 1) { BarraConfig.mover(e.id, 1); cambio() }, LinearLayout.LayoutParams(dp(40), dp(40)))
            if (!omnibox) {
                val sw = Switch(act)
                val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                sw.thumbTintList = ColorStateList(estados, intArrayOf(paleta.acento, paleta.tenue))
                sw.trackTintList = ColorStateList(
                    estados,
                    intArrayOf(
                        Color.argb(110, Color.red(paleta.acento), Color.green(paleta.acento), Color.blue(paleta.acento)),
                        Color.argb(70, Color.red(paleta.tenue), Color.green(paleta.tenue), Color.blue(paleta.tenue))
                    )
                )
                sw.isChecked = e.visible
                sw.setOnCheckedChangeListener { _, v ->
                    BarraConfig.mostrar(e.id, v)
                    cambio()
                }
                fila.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                fila.addView(View(act), LinearLayout.LayoutParams(dp(56), dp(1)))
            }
            contenido.addView(fila)
            if (!omnibox && e.visible) {
                contenido.addView(
                    paso("Tamaño", e.tamano.toString() + " dp",
                        e.tamano > BarraConfig.TAM_MIN, e.tamano < BarraConfig.TAM_MAX,
                        { BarraConfig.tamano(e.id, e.tamano - 2); cambio() },
                        { BarraConfig.tamano(e.id, e.tamano + 2); cambio() })
                )
            }
        }

        contenido.addView(texto("Toda la barra", 11f, paleta.acento, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(18) })
        contenido.addView(
            paso("Separación", BarraConfig.separacion.toString() + " dp",
                BarraConfig.separacion > 0, BarraConfig.separacion < BarraConfig.SEP_MAX,
                { BarraConfig.fijarSeparacion(BarraConfig.separacion - 2); cambio() },
                { BarraConfig.fijarSeparacion(BarraConfig.separacion + 2); cambio() })
        )
        contenido.addView(
            paso("Alto", BarraConfig.alto.toString() + " dp",
                BarraConfig.alto > BarraConfig.ALTO_MIN, BarraConfig.alto < BarraConfig.ALTO_MAX,
                { BarraConfig.fijarAlto(BarraConfig.alto - 2); cambio() },
                { BarraConfig.fijarAlto(BarraConfig.alto + 2); cambio() })
        )
        val filaContador = LinearLayout(act)
        filaContador.orientation = LinearLayout.HORIZONTAL
        filaContador.gravity = Gravity.CENTER_VERTICAL
        filaContador.setPadding(0, dp(10), 0, 0)
        filaContador.addView(texto("Contador de bloqueos sobre el escudo", 14f, paleta.texto), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sc = Switch(act)
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sc.thumbTintList = ColorStateList(estados, intArrayOf(paleta.acento, paleta.tenue))
        sc.trackTintList = ColorStateList(
            estados,
            intArrayOf(
                Color.argb(110, Color.red(paleta.acento), Color.green(paleta.acento), Color.blue(paleta.acento)),
                Color.argb(70, Color.red(paleta.tenue), Color.green(paleta.tenue), Color.blue(paleta.tenue))
            )
        )
        sc.isChecked = BarraConfig.contador
        sc.setOnCheckedChangeListener { _, v ->
            BarraConfig.fijarContador(v)
            cambio()
        }
        filaContador.addView(sc)
        contenido.addView(filaContador)
    }

    /** «Etiqueta   −  valor  +». */
    private fun paso(etiqueta: String, valor: String, puedeBajar: Boolean, puedeSubir: Boolean, menos: () -> Unit, mas: () -> Unit): View {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(dp(28), 0, 0, 0)
        fila.addView(texto(etiqueta, 13f, paleta.tenue), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fila.addView(mini("−", puedeBajar, menos), LinearLayout.LayoutParams(dp(40), dp(40)))
        val v = texto(valor, 14f, paleta.texto)
        v.gravity = Gravity.CENTER
        fila.addView(v, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        fila.addView(mini("+", puedeSubir, mas), LinearLayout.LayoutParams(dp(40), dp(40)))
        return fila
    }

    /** Maqueta de la barra con la configuración actual (iconos y campo de dirección). */
    private fun vistaPrevia(): View {
        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.gravity = Gravity.CENTER_VERTICAL
        val f = GradientDrawable()
        f.setColor(paleta.barra)
        f.setStroke(dp(1), paleta.campo)
        f.cornerRadius = dp(10).toFloat()
        barra.background = f
        barra.setPadding(dp(4), dp(4), dp(4), dp(4))
        var primero = true
        for (e in BarraConfig.orden()) {
            if (e.id != BarraConfig.OMNIBOX && !e.visible) continue
            val lp: LinearLayout.LayoutParams
            val v: View
            if (e.id == BarraConfig.OMNIBOX) {
                val c = texto("dirección", 12f, paleta.tenue)
                c.gravity = Gravity.CENTER_VERTICAL
                c.setPadding(dp(10), 0, 0, 0)
                val fc = GradientDrawable()
                fc.setColor(paleta.campo)
                fc.cornerRadius = dp(14).toFloat()
                c.background = fc
                v = c
                lp = LinearLayout.LayoutParams(0, dp(BarraConfig.alto * 3 / 4), 1f)
            } else {
                // Escala reducida (los iconos reales miden 24–60 dp y la vista previa es más estrecha).
                val c = texto(BarraConfig.definicion(e.id)?.icono ?: "?", e.tamano * 0.34f, paleta.texto)
                c.gravity = Gravity.CENTER
                v = c
                lp = LinearLayout.LayoutParams(dp(e.tamano * 3 / 4), dp(BarraConfig.alto * 3 / 4))
            }
            if (!primero) lp.leftMargin = dp(BarraConfig.separacion * 3 / 4)
            primero = false
            barra.addView(v, lp)
        }
        return barra
    }
}
