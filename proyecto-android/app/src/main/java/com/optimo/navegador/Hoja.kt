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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.HorizontalScrollView
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
class Hoja(private val act: Activity, private val paleta: Paleta, private val privada: Boolean, private val lateral: Boolean = false) {
    private val dialogo = Dialog(act)
    private val contenido = LinearLayout(act)
    private var cuandoCierra: (() -> Unit)? = null
    private var destino: LinearLayout = contenido
    private val fondo = GradientDrawable()

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    init {
        dialogo.requestWindowFeature(Window.FEATURE_NO_TITLE)
        contenido.orientation = LinearLayout.VERTICAL
        contenido.setPadding(dp(16), dp(if (lateral) 20 else 8), dp(16), dp(20))
        val raiz = LinearLayout(act)
        raiz.orientation = LinearLayout.VERTICAL
        fondo.setColor(paleta.barra)
        val r = dp(20).toFloat()
        fondo.cornerRadii = if (lateral) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        raiz.background = fondo
        if (lateral) {
            val scroll = ScrollView(act)
            scroll.addView(contenido, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            raiz.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val maxAlto = (act.resources.displayMetrics.heightPixels * 0.86f).toInt()
            val scroll = ScrollLimitado(act, maxAlto)
            scroll.addView(contenido, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        }
        dialogo.setContentView(raiz)
        dialogo.setCanceledOnTouchOutside(true)
        val w = dialogo.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (lateral) {
                val ancho = minOf((act.resources.displayMetrics.widthPixels * 0.88f).toInt(), dp(420))
                w.setLayout(ancho, ViewGroup.LayoutParams.MATCH_PARENT)
                w.setGravity(Gravity.END)
                w.attributes.windowAnimations = R.style.AnimPanelLateral
            } else {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                w.setGravity(Gravity.BOTTOM)
                w.attributes.windowAnimations = android.R.style.Animation_InputMethod
            }
            w.setDimAmount(0.45f)
            if (privada) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialogo.setOnDismissListener { cuandoCierra?.invoke() }
    }

    fun mostrar() {
        if (!act.isFinishing) dialogo.show()
    }

    /** Cuánto se oscurece la página detrás (0 = nada). Para ver lo que se está eligiendo en la página. */
    fun atenuar(cantidad: Float) {
        dialogo.window?.setDimAmount(cantidad)
    }

    /** Coloca la hoja arriba en vez de abajo (para no tapar un elemento elegido en la parte baja de la página). */
    fun anclarArriba() {
        val r = dp(20).toFloat()
        fondo.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        dialogo.window?.setGravity(Gravity.TOP)
    }

    fun cerrar() {
        if (dialogo.isShowing) dialogo.dismiss()
    }

    /** Para que «atrás» pueda volver a la página anterior del panel: [f] devuelve true si lo ha gestionado. */
    fun interceptarAtras(f: () -> Boolean) {
        dialogo.setOnKeyListener { _, tecla, ev ->
            tecla == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP && f()
        }
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
        if (lp == null) destino.addView(v) else destino.addView(v, lp)
    }

    /** Zona de la hoja que se puede rellenar y vaciar sola (para listas que se filtran sin repintar el resto). */
    fun zona(): LinearLayout {
        val z = LinearLayout(act)
        z.orientation = LinearLayout.VERTICAL
        contenido.addView(z, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return z
    }

    /** Vacía [z] y ejecuta [bloque] con todo lo que se añada yendo a esa zona. */
    fun llenar(z: LinearLayout, bloque: () -> Unit) {
        z.removeAllViews()
        val previo = destino
        destino = z
        try {
            bloque()
        } finally {
            destino = previo
        }
    }

    /** Teclado: [visible] lo abre al mostrar la hoja (formularios); si no, la hoja se ajusta cuando salga por otro motivo. */
    fun teclado(visible: Boolean) {
        val w = dialogo.window ?: return
        w.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                (if (visible) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        )
    }

    /** Campo de texto de una línea con el estilo de Optimo. [alCambiar] se llama a cada pulsación. */
    fun campo(pista: String, inicial: String, uri: Boolean = false, alCambiar: ((String) -> Unit)? = null): EditText {
        val c = EditText(act)
        c.setSingleLine(true)
        c.hint = pista
        c.setText(inicial)
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        c.setTextColor(paleta.texto)
        c.setHintTextColor(paleta.tenue)
        c.setPadding(dp(14), dp(10), dp(14), dp(10))
        c.inputType = if (uri) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
        val f = GradientDrawable()
        f.setColor(paleta.campo)
        f.cornerRadius = dp(12).toFloat()
        c.background = f
        if (alCambiar != null) {
            c.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    alCambiar(e?.toString() ?: "")
                }
            })
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(8)
        agregar(c, lp)
        return c
    }

    /** Etiqueta pequeña sobre un campo. */
    fun etiqueta(t: String) {
        val v = texto(t, 12f, paleta.tenue)
        v.setPadding(dp(4), dp(6), 0, 0)
        agregar(v)
    }

    /**
     * Fila de lista con círculo (letra del sitio o ✓ si está marcada), título, detalle, «⋮» opcional y pulsación larga.
     * [marcado] null = sin selección en curso.
     */
    fun filaLista(
        letra: String, tono: Int, titulo: String, detalle: String?, marcado: Boolean?,
        alTocar: () -> Unit, alMantener: (() -> Unit)?, alMenu: (() -> Unit)?, alCerrar: (() -> Unit)? = null
    ) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.minimumHeight = dp(56)
        fila.setPadding(dp(2), dp(6), dp(2), dp(6))
        val sel = marcado == true
        val c = texto(if (sel) "✓" else letra, 14f, if (sel) Color.WHITE else paleta.texto, true)
        c.gravity = Gravity.CENTER
        val fc = GradientDrawable()
        fc.shape = GradientDrawable.OVAL
        fc.setColor(if (sel) paleta.acento else Color.argb(60, Color.red(tono), Color.green(tono), Color.blue(tono)))
        c.background = fc
        fila.addView(c, LinearLayout.LayoutParams(dp(38), dp(38)))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val t = texto(titulo, 14.5f, paleta.texto)
        t.setSingleLine(true)
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(t)
        if (!detalle.isNullOrEmpty()) {
            val d = texto(detalle, 12f, paleta.tenue)
            d.setSingleLine(true)
            d.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(d)
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(12)
        fila.addView(col, lp)
        if (alMenu != null) fila.addView(botonIcono("⋮") { alMenu() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        if (alCerrar != null) fila.addView(botonIcono("✕") { alCerrar() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        fila.isClickable = true
        conRipple(fila)
        fila.setOnClickListener { alTocar() }
        if (alMantener != null) {
            fila.setOnLongClickListener {
                alMantener()
                true
            }
        }
        agregar(fila)
    }

    /** Barra de acciones en «píldoras»: [texto] a la izquierda (puede ir vacío) y acciones (nombre, en rojo, acción). */
    fun barraAcciones(izquierda: String, acciones: List<Triple<String, Boolean, () -> Unit>>) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(0, dp(8), 0, dp(4))
        val t = texto(izquierda, 12f, paleta.tenue)
        t.setSingleLine(true)
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        fila.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (a in acciones) {
            val p = texto(a.first, 12f, if (a.second) paleta.advertencia else paleta.texto, true)
            p.gravity = Gravity.CENTER
            p.setPadding(dp(12), dp(8), dp(12), dp(8))
            val f = GradientDrawable()
            f.cornerRadius = dp(16).toFloat()
            f.setColor(paleta.campo)
            p.background = f
            p.isClickable = true
            p.setOnClickListener { a.third() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(6)
            fila.addView(p, lp)
        }
        agregar(fila)
    }

    /** Tarjeta de estado con icono, título, subtítulo e interruptor (la protección general, por ejemplo). */
    fun tarjetaEstado(icono: View, titulo: String, sub: String, color: Int, activo: Boolean, alCambiar: (Boolean) -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(dp(14), dp(12), dp(14), dp(12))
        val f = GradientDrawable()
        f.setColor(Color.argb(45, Color.red(color), Color.green(color), Color.blue(color)))
        f.cornerRadius = dp(16).toFloat()
        fila.background = f
        fila.addView(icono, LinearLayout.LayoutParams(dp(40), dp(40)))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.addView(texto(titulo, 15f, paleta.texto, true))
        col.addView(texto(sub, 12f, paleta.tenue))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(12)
        fila.addView(col, lp)
        val sw = Switch(act)
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(estados, intArrayOf(color, paleta.tenue))
        sw.trackTintList = ColorStateList(
            estados,
            intArrayOf(Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(70, Color.red(paleta.tenue), Color.green(paleta.tenue), Color.blue(paleta.tenue)))
        )
        sw.isChecked = activo
        sw.setOnCheckedChangeListener { _, v -> alCambiar(v) }
        fila.addView(sw)
        val lpf = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpf.bottomMargin = dp(6)
        agregar(fila, lpf)
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
    fun fila(titulo: String, detalle: String? = null, valor: String? = null, aviso: Boolean = false, icono: String? = null, accion: () -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.minimumHeight = dp(52)
        fila.setPadding(dp(4), dp(6), dp(4), dp(6))
        if (icono != null) {
            val ic = texto(icono, 18f, paleta.texto)
            ic.gravity = Gravity.CENTER
            fila.addView(ic, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
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

    /** Encabezado grande de un diálogo (confirmar, elegir). */
    fun encabezado(t: String) {
        val v = texto(t, 18f, paleta.texto, true)
        v.setPadding(dp(4), dp(12), dp(4), dp(6))
        agregar(v)
    }

    /** Párrafo de texto normal (mensaje de un diálogo). */
    fun parrafo(t: String): TextView {
        val v = texto(t, 14f, paleta.texto)
        v.setPadding(dp(4), dp(4), dp(4), dp(10))
        agregar(v)
        return v
    }

    /** Si la hoja se puede cerrar tocando fuera o con «atrás». */
    fun fijarCancelable(b: Boolean) {
        dialogo.setCancelable(b)
        dialogo.setCanceledOnTouchOutside(b)
    }

    /** Fila de «chips» para filtrar (se desplaza en horizontal si no cabe). */
    fun chips(etiquetas: List<String>, seleccion: Int, alElegir: (Int) -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.setPadding(0, dp(8), 0, dp(8))
        for ((i, e) in etiquetas.withIndex()) {
            val marcado = i == seleccion
            val t = texto(e, 12f, if (marcado) Color.WHITE else paleta.texto, marcado)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(12), dp(8), dp(12), dp(8))
            val f = GradientDrawable()
            f.cornerRadius = dp(16).toFloat()
            f.setColor(if (marcado) paleta.acento else paleta.campo)
            t.background = f
            t.isClickable = true
            t.setOnClickListener { alElegir(i) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = dp(6)
            fila.addView(t, lp)
        }
        val desplazable = HorizontalScrollView(act)
        desplazable.isHorizontalScrollBarEnabled = false
        desplazable.addView(fila)
        agregar(desplazable)
    }

    /** Título de una lista sencilla (varias líneas si hace falta). */
    fun titulo(t: String) {
        val v = texto(t, 15f, paleta.tenue)
        v.setPadding(dp(4), dp(12), dp(4), dp(8))
        agregar(v)
    }

    /** Barra de título de una página del panel lateral: «‹» para volver (si [atras] no es null), título y «✕» para cerrar. */
    fun barraTitulo(t: String, atras: (() -> Unit)?, cerrar: () -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(0, dp(4), 0, dp(8))
        if (atras != null) fila.addView(botonIcono("‹") { atras() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val tt = texto(t, 20f, paleta.texto, true)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (atras == null) lp.leftMargin = dp(4)
        fila.addView(tt, lp)
        fila.addView(botonIcono("✕") { cerrar() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        agregar(fila)
    }

    /** Cuadrícula de accesos rápidos: (icono, etiqueta, acción), [porFila] por fila. */
    fun accesos(items: List<Triple<String, String, () -> Unit>>, porFila: Int = 3) {
        var i = 0
        while (i < items.size) {
            val fila = LinearLayout(act)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.setPadding(0, dp(4), 0, dp(4))
            for (j in 0 until porFila) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (j > 0) lp.leftMargin = dp(8)
                if (i + j < items.size) {
                    val it3 = items[i + j]
                    val t = LinearLayout(act)
                    t.orientation = LinearLayout.VERTICAL
                    t.gravity = Gravity.CENTER
                    t.setPadding(dp(4), dp(12), dp(4), dp(10))
                    val f = GradientDrawable()
                    f.setColor(paleta.campo)
                    f.cornerRadius = dp(14).toFloat()
                    t.background = f
                    val ic = texto(it3.first, 22f, paleta.texto)
                    ic.gravity = Gravity.CENTER
                    t.addView(ic)
                    val et = texto(it3.second, 11f, paleta.tenue)
                    et.gravity = Gravity.CENTER
                    et.setSingleLine(true)
                    t.addView(et)
                    t.isClickable = true
                    t.setOnClickListener { it3.third() }
                    fila.addView(t, lp)
                } else {
                    fila.addView(View(act), lp)
                }
            }
            agregar(fila)
            i += porFila
        }
    }

    /** Tarjeta de categoría: icono en círculo, título, detalle y flecha. */
    fun tarjeta(icono: String, titulo: String, detalle: String?, accion: () -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(dp(12), dp(12), dp(12), dp(12))
        val f = GradientDrawable()
        f.setColor(paleta.campo)
        f.cornerRadius = dp(16).toFloat()
        fila.background = f
        val circulo = texto(icono, 20f, paleta.texto)
        circulo.gravity = Gravity.CENTER
        val fc = GradientDrawable()
        fc.setColor(Color.argb(40, Color.red(paleta.acento), Color.green(paleta.acento), Color.blue(paleta.acento)))
        fc.shape = GradientDrawable.OVAL
        circulo.background = fc
        fila.addView(circulo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.addView(texto(titulo, 15f, paleta.texto, true))
        if (!detalle.isNullOrEmpty()) col.addView(texto(detalle, 12f, paleta.tenue))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(12)
        fila.addView(col, lp)
        fila.addView(texto("›", 20f, paleta.tenue))
        fila.isClickable = true
        fila.setOnClickListener { accion() }
        val lpf = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpf.topMargin = dp(8)
        agregar(fila, lpf)
    }

    /** Una ficha de la cuadrícula (pestañas): círculo grande con la letra, título y detalle. */
    class Ficha(
        val letra: String, val tono: Int, val titulo: String, val detalle: String, val marcado: Boolean?, val activa: Boolean,
        val alTocar: () -> Unit, val alMantener: (() -> Unit)?, val alCerrar: (() -> Unit)?,
        val miniatura: android.graphics.Bitmap? = null
    )

    /** Cuadrícula de dos columnas de fichas. */
    fun cuadricula(items: List<Ficha>) {
        var i = 0
        while (i < items.size) {
            val fila = LinearLayout(act)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.setPadding(0, dp(4), 0, dp(4))
            for (j in 0..1) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (j == 1) lp.leftMargin = dp(8)
                if (i + j >= items.size) {
                    fila.addView(View(act), lp)
                    continue
                }
                val it2 = items[i + j]
                val tarjeta = LinearLayout(act)
                tarjeta.orientation = LinearLayout.VERTICAL
                tarjeta.setPadding(dp(10), dp(10), dp(10), dp(10))
                val f = GradientDrawable()
                f.setColor(paleta.campo)
                f.cornerRadius = dp(14).toFloat()
                val sel = it2.marcado == true
                if (it2.activa || sel) f.setStroke(dp(2), paleta.acento)
                tarjeta.background = f
                val mini = it2.miniatura
                if (mini != null) {
                    // Miniatura de la página: ocupa todo el ancho de la tarjeta y se recorta por abajo (se ve la parte de arriba).
                    val iv = android.widget.ImageView(act)
                    iv.scaleType = android.widget.ImageView.ScaleType.MATRIX
                    iv.setImageBitmap(mini)
                    iv.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                        val ancho = r - l
                        if (ancho > 0) {
                            val m = android.graphics.Matrix()
                            val e = ancho.toFloat() / mini.width
                            m.setScale(e, e)
                            (v as android.widget.ImageView).imageMatrix = m
                        }
                    }
                    iv.clipToOutline = true
                    iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                        }
                    }
                    tarjeta.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
                } else {
                    val cuadro = texto(if (sel) "✓" else it2.letra, 24f, if (sel) Color.WHITE else paleta.texto, true)
                    cuadro.gravity = Gravity.CENTER
                    val fc = GradientDrawable()
                    fc.cornerRadius = dp(10).toFloat()
                    fc.setColor(if (sel) paleta.acento else Color.argb(60, Color.red(it2.tono), Color.green(it2.tono), Color.blue(it2.tono)))
                    cuadro.background = fc
                    tarjeta.addView(cuadro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
                }
                val t = texto(it2.titulo, 13f, paleta.texto)
                t.setSingleLine(true)
                t.ellipsize = android.text.TextUtils.TruncateAt.END
                t.setPadding(0, dp(6), 0, 0)
                tarjeta.addView(t)
                val d = texto(it2.detalle, 11f, paleta.tenue)
                d.setSingleLine(true)
                d.ellipsize = android.text.TextUtils.TruncateAt.END
                tarjeta.addView(d)
                if (it2.alCerrar != null) {
                    val x = botonIcono("✕") { it2.alCerrar.invoke() }
                    tarjeta.addView(x, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
                }
                tarjeta.isClickable = true
                tarjeta.setOnClickListener { it2.alTocar() }
                if (it2.alMantener != null) {
                    tarjeta.setOnLongClickListener {
                        it2.alMantener.invoke()
                        true
                    }
                }
                fila.addView(tarjeta, lp)
            }
            agregar(fila)
            i += 2
        }
    }

    /** Rótulo de un ajuste (encima de sus segmentos). */
    fun rotulo(t: String) {
        val v = texto(t, 15f, paleta.texto)
        v.setPadding(dp(4), dp(10), dp(4), dp(2))
        agregar(v)
    }

    /** Control segmentado: una fila de opciones excluyentes con la elegida resaltada. */
    fun segmentos(etiquetas: List<String>, seleccion: Int, alElegir: (Int) -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.setPadding(dp(3), dp(3), dp(3), dp(3))
        val f = GradientDrawable()
        f.setColor(paleta.campo)
        f.cornerRadius = dp(12).toFloat()
        fila.background = f
        for ((i, e) in etiquetas.withIndex()) {
            val marcado = i == seleccion
            val t = texto(e, 12.5f, if (marcado) paleta.texto else paleta.tenue, marcado)
            t.gravity = Gravity.CENTER
            t.setSingleLine(true)
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            t.setPadding(dp(4), dp(9), dp(4), dp(9))
            if (marcado) {
                val fs = GradientDrawable()
                fs.setColor(paleta.barra)
                fs.setStroke(dp(1), paleta.tenue)
                fs.cornerRadius = dp(10).toFloat()
                t.background = fs
            }
            t.isClickable = true
            t.setOnClickListener { if (!marcado) alElegir(i) }
            fila.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(4)
        agregar(fila, lp)
    }

    /** Fila de insignias informativas (no se pulsan). */
    fun insignias(textos: List<String>) {
        if (textos.isEmpty()) return
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.setPadding(0, dp(6), 0, dp(2))
        val desplazable = HorizontalScrollView(act)
        desplazable.isHorizontalScrollBarEnabled = false
        for ((i, t) in textos.withIndex()) {
            val v = texto(t, 12f, paleta.acento, true)
            v.setPadding(dp(10), dp(6), dp(10), dp(6))
            val f = GradientDrawable()
            f.setColor(Color.argb(40, Color.red(paleta.acento), Color.green(paleta.acento), Color.blue(paleta.acento)))
            f.cornerRadius = dp(14).toFloat()
            v.background = f
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = dp(6)
            fila.addView(v, lp)
        }
        desplazable.addView(fila)
        agregar(desplazable)
    }

    /** Aviso de «cambios pendientes» con un botón (recargar la página, por ejemplo). */
    fun avisoPendiente(mensaje: String, boton: String, alPulsar: () -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(dp(14), dp(10), dp(10), dp(10))
        val f = GradientDrawable()
        f.setColor(Color.argb(60, Color.red(paleta.advertencia), Color.green(paleta.advertencia), Color.blue(paleta.advertencia)))
        f.cornerRadius = dp(12).toFloat()
        fila.background = f
        fila.addView(texto(mensaje, 13.5f, paleta.texto), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = texto(boton, 13f, Color.WHITE, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        val fb = GradientDrawable()
        fb.setColor(paleta.advertencia)
        fb.cornerRadius = dp(14).toFloat()
        b.background = fb
        b.isClickable = true
        b.setOnClickListener { alPulsar() }
        fila.addView(b)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        agregar(fila, lp)
    }

    /** Botón grande de acción principal (color de acento) con icono, título y subtítulo. */
    fun botonGrande(icono: String, titulo: String, sub: String, activo: Boolean = true, accion: () -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(dp(14), dp(14), dp(14), dp(14))
        val f = GradientDrawable()
        f.setColor(if (activo) paleta.acento else paleta.campo)
        f.cornerRadius = dp(16).toFloat()
        fila.background = f
        val ic = texto(icono, 26f, if (activo) Color.WHITE else paleta.tenue)
        ic.gravity = Gravity.CENTER
        fila.addView(ic, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.addView(texto(titulo, 15f, if (activo) Color.WHITE else paleta.texto, true))
        col.addView(texto(sub, 12f, if (activo) Color.argb(220, 255, 255, 255) else paleta.tenue))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(10)
        fila.addView(col, lp)
        fila.addView(texto("›", 22f, if (activo) Color.WHITE else paleta.tenue))
        fila.isClickable = true
        fila.setOnClickListener { accion() }
        val lpf = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpf.bottomMargin = dp(8)
        agregar(fila, lpf)
    }

    /** «Etiqueta   −  valor  +». */
    fun paso(etiqueta: String, valor: String, puedeBajar: Boolean, puedeSubir: Boolean, menos: () -> Unit, mas: () -> Unit) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.minimumHeight = dp(48)
        fila.setPadding(dp(4), 0, 0, 0)
        fila.addView(texto(etiqueta, 15f, paleta.texto), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fila.addView(miniBoton("−", puedeBajar, menos), LinearLayout.LayoutParams(dp(42), dp(42)))
        val v = texto(valor, 14f, paleta.texto)
        v.gravity = Gravity.CENTER
        fila.addView(v, LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT))
        fila.addView(miniBoton("+", puedeSubir, mas), LinearLayout.LayoutParams(dp(42), dp(42)))
        agregar(fila)
    }

    private fun miniBoton(t: String, activo: Boolean, accion: () -> Unit): TextView {
        val b = texto(t, 18f, paleta.texto)
        b.gravity = Gravity.CENTER
        b.alpha = if (activo) 1f else 0.3f
        b.isClickable = activo
        conRipple(b)
        if (activo) b.setOnClickListener { accion() }
        return b
    }

    /** Deslizador de [min] a [max] de [paso] en [paso]. [alCambiar] mientras se arrastra; [alSoltar] al terminar. */
    fun deslizador(min: Int, max: Int, paso: Int, valor: Int, alCambiar: (Int) -> Unit, alSoltar: (Int) -> Unit) {
        val sb = android.widget.SeekBar(act)
        sb.max = (max - min) / paso
        sb.progress = ((valor.coerceIn(min, max) - min) / paso)
        sb.progressTintList = ColorStateList.valueOf(paleta.acento)
        sb.thumbTintList = ColorStateList.valueOf(paleta.acento)
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: android.widget.SeekBar?, p: Int, deUsuario: Boolean) {
                if (deUsuario) alCambiar(min + p * paso)
            }

            override fun onStartTrackingTouch(b: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(b: android.widget.SeekBar?) {
                alSoltar(min + (b?.progress ?: 0) * paso)
            }
        })
        agregar(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Fila de lista ordenable: nombre, ▲ ▼ y, si [visible] no es null, un interruptor para mostrarla u ocultarla. */
    fun filaOrden(
        nombre: String, visible: Boolean?, puedeSubir: Boolean, puedeBajar: Boolean,
        alSubir: () -> Unit, alBajar: () -> Unit, alVisible: (Boolean) -> Unit
    ) {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.minimumHeight = dp(52)
        fila.setPadding(dp(4), dp(2), dp(2), dp(2))
        fila.addView(texto(nombre, 15f, if (visible == false) paleta.tenue else paleta.texto), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fila.addView(miniBoton("▲", puedeSubir, alSubir), LinearLayout.LayoutParams(dp(40), dp(40)))
        fila.addView(miniBoton("▼", puedeBajar, alBajar), LinearLayout.LayoutParams(dp(40), dp(40)))
        if (visible != null) {
            val sw = Switch(act)
            val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            sw.thumbTintList = ColorStateList(estados, intArrayOf(paleta.acento, paleta.tenue))
            sw.trackTintList = ColorStateList(
                estados,
                intArrayOf(Color.argb(110, Color.red(paleta.acento), Color.green(paleta.acento), Color.blue(paleta.acento)), Color.argb(70, Color.red(paleta.tenue), Color.green(paleta.tenue), Color.blue(paleta.tenue)))
            )
            sw.isChecked = visible
            sw.setOnCheckedChangeListener { _, v -> alVisible(v) }
            fila.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            fila.addView(View(act), LinearLayout.LayoutParams(dp(56), dp(1)))
        }
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
