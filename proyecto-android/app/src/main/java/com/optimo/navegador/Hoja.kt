package com.optimo.navegador

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** ScrollView que no pasa de [maxAlto] píxeles (la hoja deja siempre ver un trozo de la página detrás). */
private class ScrollLimitado(c: Context, private val maxAlto: Int) : ScrollView(c) {
    override fun onMeasure(anchoSpec: Int, altoSpec: Int) {
        super.onMeasure(anchoSpec, View.MeasureSpec.makeMeasureSpec(maxAlto, View.MeasureSpec.AT_MOST))
    }
}

/** Gráfico de barras sencillo: un valor por barra, la última (hoy) resaltada. */
private class VistaBarras(c: Context, private val valores: IntArray, private val color: Int, private val tenue: Int) : View(c) {
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val n = valores.size
        if (n == 0 || width == 0 || height == 0) return
        val max = maxOf(valores.maxOrNull() ?: 0, 1)
        val hueco = width / 60f
        val ancho = (width - hueco * (n - 1)) / n
        for (i in 0 until n) {
            val h = if (valores[i] == 0) 2f * resources.displayMetrics.density else height * valores[i] / max.toFloat()
            val izq = i * (ancho + hueco)
            pincel.color = if (i == n - 1) color else Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
            if (valores[i] == 0) pincel.color = tenue and 0x00FFFFFF or 0x55000000
            canvas.drawRoundRect(izq, height - h, izq + ancho, height.toFloat(), ancho / 3f, ancho / 3f, pincel)
        }
    }
}

/**
 * Hoja inferior con el aspecto de Optimo: título, secciones, filas, interruptores, cifras y gráfico.
 * Se vuelve a pintar entera con [pintar] cuando cambia algo (así los interruptores y los números siempre están al día).
 */
class Hoja(private val act: Activity, private val paleta: Paleta, private val privada: Boolean) {
    private val dialogo = Dialog(act)
    private val contenido = LinearLayout(act)
    private var cuandoCierra: (() -> Unit)? = null

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    init {
        dialogo.requestWindowFeature(Window.FEATURE_NO_TITLE)
        contenido.orientation = LinearLayout.VERTICAL
        contenido.setPadding(dp(16), dp(8), dp(16), dp(20))
        val maxAlto = (act.resources.displayMetrics.heightPixels * 0.86f).toInt()
        val scroll = ScrollLimitado(act, maxAlto)
        scroll.addView(contenido, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val raiz = LinearLayout(act)
        raiz.orientation = LinearLayout.VERTICAL
        val fondo = GradientDrawable()
        fondo.setColor(paleta.barra)
        fondo.cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
        raiz.background = fondo
        // Tirador
        val tirador = View(act)
        val formaTirador = GradientDrawable()
        formaTirador.setColor(paleta.tenue)
        formaTirador.cornerRadius = dp(2).toFloat()
        tirador.background = formaTirador
        tirador.alpha = 0.5f
        val lpT = LinearLayout.LayoutParams(dp(36), dp(4))
        lpT.gravity = Gravity.CENTER_HORIZONTAL
        lpT.topMargin = dp(8)
        raiz.addView(tirador, lpT)
        raiz.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogo.setContentView(raiz)
        val w = dialogo.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.45f)
            w.attributes.windowAnimations = android.R.style.Animation_InputMethod
            if (privada) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialogo.setOnDismissListener { cuandoCierra?.invoke() }
    }

    fun mostrar() {
        if (!act.isFinishing) dialogo.show()
    }

    fun cerrar() {
        if (dialogo.isShowing) dialogo.dismiss()
    }

    fun alCerrar(accion: () -> Unit) {
        cuandoCierra = accion
    }

    fun limpiar() {
        contenido.removeAllViews()
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

    fun agregar(v: View, lp: LinearLayout.LayoutParams? = null) {
        if (lp == null) contenido.addView(v) else contenido.addView(v, lp)
    }

    /** Cabecera: [icono] (una vista), título grande, subtítulo y, opcional, un botón a la derecha. */
    fun cabecera(icono: View, titulo: String, subtitulo: String, colorSub: Int, derecha: View? = null) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(0, dp(8), 0, dp(8))
        fila.addView(icono, LinearLayout.LayoutParams(dp(44), dp(44)))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val t = texto(titulo, 18f, paleta.texto, true)
        t.setSingleLine(true)
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(t)
        col.addView(texto(subtitulo, 13f, colorSub))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(12)
        fila.addView(col, lp)
        if (derecha != null) fila.addView(derecha, LinearLayout.LayoutParams(dp(44), dp(44)))
        agregar(fila)
    }

    /** Botón de icono redondo (para la cabecera). */
    fun botonIcono(simbolo: String, accion: () -> Unit): TextView {
        val b = texto(simbolo, 20f, paleta.texto)
        b.gravity = Gravity.CENTER
        b.isClickable = true
        conRipple(b)
        b.setOnClickListener { accion() }
        return b
    }

    fun seccion(t: String) {
        val v = texto(t.uppercase(), 11f, paleta.acento, true)
        v.letterSpacing = 0.08f
        v.setPadding(dp(4), dp(18), 0, dp(4))
        agregar(v)
    }

    fun nota(t: String) {
        val v = texto(t, 12f, paleta.tenue)
        v.setPadding(dp(4), dp(4), dp(4), dp(4))
        agregar(v)
    }

    /** Fila táctil: título, detalle opcional debajo y [valor] a la derecha (si no hay, una flecha). */
    fun fila(titulo: String, detalle: String? = null, valor: String? = null, aviso: Boolean = false, accion: () -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.minimumHeight = dp(52)
        fila.setPadding(dp(4), dp(6), dp(4), dp(6))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.addView(texto(titulo, 15f, paleta.texto))
        if (!detalle.isNullOrEmpty()) col.addView(texto(detalle, 12f, paleta.tenue))
        fila.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val der = texto(if (valor != null) valor + "  ›" else "›", 14f, if (aviso) paleta.advertencia else paleta.tenue)
        der.gravity = Gravity.END
        fila.addView(der, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fila.isClickable = true
        conRipple(fila)
        fila.setOnClickListener { accion() }
        agregar(fila)
    }

    /** Fila con interruptor. [alCambiar] recibe el nuevo valor; quien llama suele volver a pintar la hoja. */
    fun interruptor(titulo: String, detalle: String? = null, activo: Boolean, alCambiar: (Boolean) -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.minimumHeight = dp(56)
        fila.setPadding(dp(4), dp(6), dp(4), dp(6))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.addView(texto(titulo, 15f, paleta.texto))
        if (!detalle.isNullOrEmpty()) col.addView(texto(detalle, 12f, paleta.tenue))
        fila.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(act)
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(estados, intArrayOf(paleta.acento, paleta.tenue))
        val pista = Color.argb(110, Color.red(paleta.acento), Color.green(paleta.acento), Color.blue(paleta.acento))
        val pistaOff = Color.argb(70, Color.red(paleta.tenue), Color.green(paleta.tenue), Color.blue(paleta.tenue))
        sw.trackTintList = ColorStateList(estados, intArrayOf(pista, pistaOff))
        sw.isChecked = activo
        sw.setOnCheckedChangeListener { _, v -> alCambiar(v) }
        fila.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fila.isClickable = true
        conRipple(fila)
        fila.setOnClickListener { sw.toggle() }
        agregar(fila)
    }

    /** Fila de tarjetas con un número grande y su etiqueta. */
    fun cifras(items: List<Pair<String, String>>) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.setPadding(0, dp(8), 0, dp(4))
        for ((i, par) in items.withIndex()) {
            val tarjeta = LinearLayout(act)
            tarjeta.orientation = LinearLayout.VERTICAL
            tarjeta.gravity = Gravity.CENTER
            tarjeta.setPadding(dp(6), dp(12), dp(6), dp(12))
            val f = GradientDrawable()
            f.setColor(paleta.campo)
            f.cornerRadius = dp(14).toFloat()
            tarjeta.background = f
            val n = texto(par.first, 24f, paleta.texto, true)
            n.gravity = Gravity.CENTER
            tarjeta.addView(n)
            val e = texto(par.second, 11f, paleta.tenue)
            e.gravity = Gravity.CENTER
            tarjeta.addView(e)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(8)
            fila.addView(tarjeta, lp)
        }
        agregar(fila)
    }

    /** Gráfico de barras con una línea de pie (izquierda y derecha). */
    fun barras(valores: IntArray, izquierda: String, derecha: String) {
        val caja = LinearLayout(act)
        caja.orientation = LinearLayout.VERTICAL
        caja.setPadding(dp(4), dp(10), dp(4), dp(4))
        caja.addView(VistaBarras(act, valores, paleta.acento, paleta.tenue), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        val pie = LinearLayout(act)
        pie.orientation = LinearLayout.HORIZONTAL
        pie.addView(texto(izquierda, 11f, paleta.tenue), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val d = texto(derecha, 11f, paleta.tenue)
        d.gravity = Gravity.END
        pie.addView(d, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        caja.addView(pie)
        agregar(caja)
    }

    /** Línea «nombre ........ valor» sin acción (desglose). */
    fun dato(nombre: String, valor: String) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.setPadding(dp(4), dp(5), dp(4), dp(5))
        fila.addView(texto(nombre, 14f, paleta.texto), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fila.addView(texto(valor, 14f, paleta.tenue))
        agregar(fila)
    }

    /** Botón de texto ancho (acciones destructivas o principales). */
    fun boton(t: String, aviso: Boolean = false, accion: () -> Unit) {
        val b = texto(t, 14f, if (aviso) paleta.advertencia else paleta.acento, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(8), dp(14), dp(8), dp(14))
        b.isClickable = true
        conRipple(b)
        b.setOnClickListener { accion() }
        agregar(b)
    }
}
