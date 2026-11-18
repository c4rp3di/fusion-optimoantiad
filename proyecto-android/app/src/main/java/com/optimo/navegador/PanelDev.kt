package com.optimo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Una fila del árbol de elementos (ver «hijos» en el agente). */
class NodoVista(val id: Int, val nivel: Int, val t: Int, val et: String, val hs: Int) {
    var abierto = false
}

/** Una fila de la pestaña Almacén: cabecera de sección (titulo) o elemento (tipo: ck, ls, ss, info). */
class FilaAlm(val tipo: String, val clave: String = "", val valor: String = "", val titulo: String? = null)

/**
 * Panel de herramientas de desarrollo, acoplado bajo la página (la página sigue visible y usable arriba).
 * Pestañas: Consola, Elementos, Red, Almacén e Info. Se alimenta del agente (assets/devtools/agente.js) y de lo
 * que solo ve la app (errores y mensajes del WebView, bloqueos de Optimo, cookies HttpOnly).
 */
class PanelDev(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val agente: AgenteDev,
    private val contenedorWeb: View,
    private val webActual: () -> WebView?,
    private val pestanaActual: () -> Pestana?,
    private val gestorCookies: (WebView) -> CookieManager,
    private val dialogo: () -> AlertDialog.Builder,
    private val avisar: (String) -> Unit,
    private val copiar: (String) -> Unit,
    private val alCambiarCaptura: () -> Unit,
    /** Guarda una regla permanente del sitio abierto: modo «ocultar» (a = selector), «estilo» (a = nombre, b = CSS) o «cssPropio» (a = CSS). Devuelve el mensaje para el usuario. */
    private val reglaSitio: (String, String, String) -> String
) {
    private val h = Handler(Looper.getMainLooper())
    private val raiz = LinearLayout(act)
    private val cuerpo = FrameLayout(act)
    private val barraTabs = LinearLayout(act)
    private val nombresTabs = listOf("Consola", "Elementos", "Red", "Almacén", "Info")
    private val vistasTabs = ArrayList<TextView>()
    private var tab = 0
    private var grande = false
    private val pila = ArrayList<View>()
    private val rojo = Color.parseColor("#E53935")

    var visible = false
        private set

    val vista: View get() = raiz

    // ---------------------------------------------------------------- utilidades de interfaz

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    private fun fondoRedondeado(color: Int, radio: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radio.toFloat()
        return g
    }

    private fun texto(t: CharSequence, sp: Float = 13f, color: Int = paleta.texto, mono: Boolean = false): TextView {
        val v = TextView(act)
        v.text = t
        v.textSize = sp
        v.setTextColor(color)
        if (mono) v.typeface = Typeface.MONOSPACE
        return v
    }

    private fun boton(t: String, sp: Float = 13f, accion: () -> Unit): TextView {
        val v = texto(t, sp)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(10), dp(6), dp(10), dp(6))
        v.isClickable = true
        v.setOnClickListener { accion() }
        return v
    }

    private fun campo(pista: String, mono: Boolean = false): EditText {
        val e = EditText(act)
        e.hint = pista
        e.textSize = 13f
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        e.maxLines = 1
        e.setTextColor(paleta.texto)
        e.setHintTextColor(paleta.tenue)
        e.background = fondoRedondeado(paleta.campo, dp(8))
        e.setPadding(dp(10), dp(4), dp(10), dp(4))
        if (mono) e.typeface = Typeface.MONOSPACE
        return e
    }

    private fun EditText.alCambiar(f: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
        })
    }

    private fun lp(w: Int, hh: Int, peso: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, hh, peso)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private abstract inner class Lista<T : Any> : BaseAdapter() {
        var datos: List<T> = emptyList()
        override fun getCount(): Int = datos.size
        override fun getItem(p: Int): Any = datos[p]
        override fun getItemId(p: Int): Long = p.toLong()
    }

    private fun nuevaListView(): ListView {
        val l = ListView(act)
        l.divider = null
        l.setBackgroundColor(paleta.fondo)
        l.transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
        return l
    }

    private fun vistaTexto(contenido: CharSequence, mono: Boolean = true): View {
        val sv = ScrollView(act)
        sv.setBackgroundColor(paleta.fondo)
        val tv = texto(contenido, 12f, paleta.texto, mono)
        tv.setPadding(dp(10), dp(8), dp(10), dp(8))
        tv.setTextIsSelectable(true)
        sv.addView(tv, FrameLayout.LayoutParams(MATCH, WRAP))
        return sv
    }

    /** Muestra una pantalla secundaria (detalle) encima de la pestaña; «←» o atrás la cierra. */
    private fun empujar(titulo: String, contenido: View, acciones: List<Pair<String, () -> Unit>> = emptyList()) {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(paleta.barra)
        val cab = LinearLayout(act)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        cab.addView(boton("←", 16f) { alAtras() }, lp(dp(44), dp(38)))
        val tt = texto(titulo, 13f, paleta.acento)
        tt.setTypeface(tt.typeface, Typeface.BOLD)
        tt.maxLines = 1
        tt.ellipsize = TextUtils.TruncateAt.END
        cab.addView(tt, lp(0, WRAP, 1f))
        for ((t, a) in acciones) cab.addView(boton(t, 12f) { a() })
        col.addView(cab, lp(MATCH, dp(38)))
        col.addView(contenido, lp(MATCH, 0, 1f))
        cuerpo.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        pila.add(col)
    }

    private fun cerrarPila() {
        while (pila.isNotEmpty()) cuerpo.removeView(pila.removeAt(pila.size - 1))
    }

    private fun consulta(expr: String, cb: (String?) -> Unit) {
        val w = webActual() ?: return
        agente.llamar(w, expr, cb)
    }

    private fun consultaObj(expr: String, cb: (JSONObject) -> Unit) {
        consulta(expr) { s ->
            val o = DevFormato.objeto(s)
            if (o != null) cb(o)
        }
    }

    private fun pedirTexto(titulo: String, inicial: String, multilinea: Boolean, aceptar: (String) -> Unit) {
        val e = campo("", true)
        if (multilinea) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            e.maxLines = 8
            e.minLines = 4
            e.gravity = Gravity.TOP or Gravity.START
        }
        e.setText(inicial)
        val caja = FrameLayout(act)
        caja.setPadding(dp(20), dp(8), dp(20), 0)
        caja.addView(e, FrameLayout.LayoutParams(MATCH, WRAP))
        dialogo().setTitle(titulo).setView(caja)
            .setPositiveButton("Aceptar") { _, _ -> aceptar(e.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirDos(titulo: String, pista1: String, pista2: String, aceptar: (String, String) -> Unit) {
        val a = campo(pista1, true)
        val b = campo(pista2, true)
        val caja = LinearLayout(act)
        caja.orientation = LinearLayout.VERTICAL
        caja.setPadding(dp(20), dp(8), dp(20), 0)
        caja.addView(a, lp(MATCH, WRAP))
        caja.addView(b, lp(MATCH, WRAP))
        dialogo().setTitle(titulo).setView(caja)
            .setPositiveButton("Aceptar") { _, _ -> aceptar(a.text.toString(), b.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun elegirDeLista(titulo: String?, etiquetas: List<String>, alElegir: (Int) -> Unit) {
        val b = dialogo()
        if (titulo != null) b.setTitle(titulo)
        b.setItems(etiquetas.toTypedArray()) { _, i -> alElegir(i) }
        b.show()
    }

    // ---------------------------------------------------------------- estructura del panel

    init {
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(paleta.barra)
        raiz.visibility = View.GONE

        val cab = LinearLayout(act)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        barraTabs.orientation = LinearLayout.HORIZONTAL
        for (i in nombresTabs.indices) {
            val t = boton(nombresTabs[i], 13f) { seleccionarTab(i) }
            t.setPadding(dp(12), dp(8), dp(12), dp(8))
            vistasTabs.add(t)
            barraTabs.addView(t, lp(WRAP, dp(38)))
        }
        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(barraTabs, FrameLayout.LayoutParams(WRAP, dp(38)))
        cab.addView(hs, lp(0, dp(38), 1f))
        cab.addView(boton("⬍", 16f) { alternarAlto() }, lp(dp(40), dp(38)))
        cab.addView(boton("✕", 16f) { ocultar() }, lp(dp(40), dp(38)))
        raiz.addView(cab, lp(MATCH, dp(38)))

        val sep = View(act)
        sep.setBackgroundColor(paleta.campo)
        raiz.addView(sep, lp(MATCH, 1))
        cuerpo.setBackgroundColor(paleta.fondo)
        raiz.addView(cuerpo, lp(MATCH, 0, 1f))
    }

    private fun estiloTabs() {
        for (i in vistasTabs.indices) {
            val t = vistasTabs[i]
            val sel = i == tab
            t.setTextColor(if (sel) paleta.acento else paleta.tenue)
            t.setTypeface(Typeface.DEFAULT, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun ajustarAlto() {
        val f = if (grande) 0.78f else 0.5f
        val lpPanel = raiz.layoutParams as? LinearLayout.LayoutParams
        val lpWeb = contenedorWeb.layoutParams as? LinearLayout.LayoutParams
        if (lpPanel != null) {
            lpPanel.height = 0
            lpPanel.weight = f * 10f
            raiz.layoutParams = lpPanel
        }
        if (lpWeb != null) {
            lpWeb.weight = (1f - f) * 10f
            contenedorWeb.layoutParams = lpWeb
        }
    }

    private fun alternarAlto() {
        grande = !grande
        ajustarAlto()
    }

    fun alternar() {
        if (visible) ocultar() else mostrar()
    }

    fun mostrar() {
        val web = webActual()
        if (web == null) {
            avisar("Abre una página primero")
            return
        }
        visible = true
        raiz.visibility = View.VISIBLE
        ajustarAlto()
        seleccionarTab(tab)
        listoAgente(web)
        h.removeCallbacks(sondeo)
        h.postDelayed(sondeo, 700)
    }

    /** Asegura el agente en la página, lo saca de la pausa y recarga la pestaña visible. */
    private fun listoAgente(web: WebView) {
        agente.asegurar(web) {
            consulta("pausar(false)") { }
            recargarTab()
        }
    }

    fun ocultar() {
        if (eligiendo) detenerElegir()
        consulta("quitarResalte()") { }
        // Con el panel cerrado el agente no registra nada (salvo que se pidiera captura desde el inicio).
        if (!ajustes.devCaptura) consulta("pausar(true)") { }
        cerrarPila()
        visible = false
        raiz.visibility = View.GONE
        val lpWeb = contenedorWeb.layoutParams as? LinearLayout.LayoutParams
        if (lpWeb != null) {
            lpWeb.weight = 1f
            contenedorWeb.layoutParams = lpWeb
        }
        h.removeCallbacks(sondeo)
    }

    /** Atrás: cierra la pantalla secundaria; si no hay, cierra el panel. Devuelve si lo ha gestionado. */
    fun alAtras(): Boolean {
        if (pila.isNotEmpty()) {
            cuerpo.removeView(pila.removeAt(pila.size - 1))
            return true
        }
        if (visible) {
            ocultar()
            return true
        }
        return false
    }

    /** Empieza a cargarse una página nueva en la pestaña activa. */
    fun alNavegar() {
        informe = ""
        infoUrl = ""
        infoTitulo = ""
        if (!visible) return
        cerrarPila()
        arbol.clear()
        seleccionado = 0
        eligiendo = false
        notificarArbol()
        refrescarConsola()
        refrescarRed()
    }

    /** La página terminó de cargarse: se reasegura el agente y se recarga la pestaña visible. */
    fun alCargada() {
        if (!visible) return
        val web = webActual() ?: return
        listoAgente(web)
    }

    fun alCambiarPestana() {
        informe = ""
        infoUrl = ""
        infoTitulo = ""
        if (!visible) return
        cerrarPila()
        arbol.clear()
        seleccionado = 0
        eligiendo = false
        seleccionarTab(tab)
        val web = webActual() ?: return
        listoAgente(web)
    }

    private fun seleccionarTab(i: Int) {
        if (eligiendo) detenerElegir()
        if (tab == 1 && i != 1) consulta("quitarResalte()") { }
        tab = i
        estiloTabs()
        cerrarPila()
        cuerpo.removeAllViews()
        val v = when (i) {
            0 -> construirConsola()
            1 -> construirElementos()
            2 -> construirRed()
            3 -> construirAlmacen()
            else -> construirInfo()
        }
        cuerpo.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        recargarTab()
    }

    private fun recargarTab() {
        if (!visible) return
        when (tab) {
            0 -> refrescarConsola()
            1 -> if (arbol.isEmpty()) cargarRaiz() else notificarArbol()
            2 -> refrescarRed()
            3 -> cargarAlmacen()
            else -> cargarInfo()
        }
    }

    private val sondeo = object : Runnable {
        override fun run() {
            if (!visible) return
            val web = webActual()
            val p = pestanaActual()
            if (web != null && p != null && pila.isEmpty()) {
                if (tab == 0) sondearConsola(web, p.dev)
                if (tab == 2) sondearRed(web, p.dev)
            }
            h.postDelayed(this, 900)
        }
    }

    // ---------------------------------------------------------------- CONSOLA

    private var listaConsola: ListView? = null
    private var btnNivel: TextView? = null
    private var campoConsola: EditText? = null
    private var filtroNivel = 0
    private var filtroConsola = ""
    private var selConsola: EntradaConsola? = null
    private var visibleConsola: List<EntradaConsola> = emptyList()
    private val nombresNivel = listOf("Todo", "Errores", "Avisos", "Info")
    private val historial = ArrayList<String>()
    private var posHistorial = 0

    private val adaptadorConsola = object : Lista<EntradaConsola>() {
        override fun getView(pos: Int, cv: View?, parent: ViewGroup?): View {
            val tv = (cv as? TextView) ?: texto("", 12f, paleta.texto, true).also {
                it.setPadding(dp(8), dp(4), dp(8), dp(4))
                it.ellipsize = TextUtils.TruncateAt.END
            }
            val e = datos[pos]
            val (color, prefijo) = when (e.nivel) {
                "error" -> Pair(rojo, "")
                "warn" -> Pair(paleta.advertencia, "")
                "in" -> Pair(paleta.tenue, "› ")
                "out" -> Pair(paleta.acento, "← ")
                "debug" -> Pair(paleta.tenue, "")
                else -> Pair(paleta.texto, "")
            }
            val sb = SpannableStringBuilder(prefijo + e.texto)
            sb.setSpan(ForegroundColorSpan(color), 0, sb.length, 0)
            val o = e.origen
            if (o != null && o.isNotEmpty()) {
                val ini = sb.length
                sb.append("   ").append(if (o.length > 40) "…" + o.takeLast(40) else o)
                sb.setSpan(ForegroundColorSpan(paleta.tenue), ini, sb.length, 0)
                sb.setSpan(RelativeSizeSpan(0.8f), ini, sb.length, 0)
            }
            tv.text = sb
            tv.setBackgroundColor(
                when (e.nivel) {
                    "error" -> Color.parseColor("#22E53935")
                    "warn" -> Color.parseColor("#22F9A825")
                    else -> Color.TRANSPARENT
                }
            )
            tv.maxLines = if (selConsola === e) Int.MAX_VALUE else 6
            return tv
        }
    }

    private fun construirConsola(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.gravity = Gravity.CENTER_VERTICAL
        barra.setPadding(dp(4), dp(3), dp(4), dp(3))
        val bn = boton(nombresNivel[filtroNivel] + " ▾", 12f) {
            filtroNivel = (filtroNivel + 1) % nombresNivel.size
            btnNivel?.text = nombresNivel[filtroNivel] + " ▾"
            refrescarConsola()
        }
        btnNivel = bn
        barra.addView(bn, lp(WRAP, dp(34)))
        val filtro = campo("Filtrar…")
        filtro.setText(filtroConsola)
        filtro.alCambiar { filtroConsola = it; refrescarConsola() }
        barra.addView(filtro, lp(0, dp(34), 1f))
        barra.addView(boton("🗑", 14f) { limpiarConsola() }, lp(dp(40), dp(34)))
        col.addView(barra, lp(MATCH, WRAP))

        if (!ajustes.devCaptura) {
            val aviso = texto("Captura desde el inicio: desactivada (se pierden los registros de antes de abrir el panel). Toca para activarla y recargar.", 11f, paleta.advertencia)
            aviso.setPadding(dp(10), dp(4), dp(10), dp(4))
            aviso.isClickable = true
            aviso.setOnClickListener {
                ajustes.devCaptura = true
                alCambiarCaptura()
                avisar("Captura activada: recargando")
                webActual()?.reload()
                seleccionarTab(0)
            }
            col.addView(aviso, lp(MATCH, WRAP))
        }

        val lista = nuevaListView()
        lista.adapter = adaptadorConsola
        lista.setOnItemClickListener { _, _, pos, _ ->
            val e = visibleConsola.getOrNull(pos)
            selConsola = if (selConsola === e) null else e
            adaptadorConsola.notifyDataSetChanged()
        }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            val e = visibleConsola.getOrNull(pos)
            if (e != null) {
                copiar(e.texto)
                avisar("Copiado")
            }
            true
        }
        listaConsola = lista
        col.addView(lista, lp(MATCH, 0, 1f))

        val entrada = LinearLayout(act)
        entrada.orientation = LinearLayout.HORIZONTAL
        entrada.gravity = Gravity.CENTER_VERTICAL
        entrada.setPadding(dp(4), dp(3), dp(4), dp(3))
        val ec = campo("Ejecutar JavaScript…", true)
        ec.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ec.maxLines = 3
        campoConsola = ec
        entrada.addView(boton("↑", 14f) { historialAnterior() }, lp(dp(34), dp(34)))
        entrada.addView(ec, lp(0, WRAP, 1f))
        entrada.addView(boton("▶", 16f) { ejecutarEntrada() }, lp(dp(44), dp(34)))
        col.addView(entrada, lp(MATCH, WRAP))
        return col
    }

    private fun historialAnterior() {
        if (historial.isEmpty()) return
        posHistorial = if (posHistorial <= 0) historial.size - 1 else posHistorial - 1
        campoConsola?.setText(historial[posHistorial])
        campoConsola?.setSelection(campoConsola?.text?.length ?: 0)
    }

    private fun limpiarConsola() {
        val p = pestanaActual() ?: return
        p.dev.vaciarConsola()
        consulta("limpiarConsola()") { }
        selConsola = null
        refrescarConsola()
    }

    private fun sondearConsola(web: WebView, d: DatosDev) {
        agente.llamar(web, "consola(" + d.agenteUltimoI + ")") { s ->
            val arr = DevFormato.arreglo(s)
            if (arr != null) {
                for (k in 0 until arr.length()) {
                    val o = arr.getJSONObject(k)
                    val i = o.optLong("i")
                    if (i > d.agenteUltimoI) d.agenteUltimoI = i
                    d.agenteConsola.add(
                        EntradaConsola(o.optLong("ts"), o.optString("t", "log"), o.optString("m"), o.optString("o").ifEmpty { null }, false)
                    )
                }
                while (d.agenteConsola.size > DatosDev.MAX) d.agenteConsola.removeAt(0)
            } else if (s != null && s.contains("\"sin\"")) {
                agente.asegurar(web) { }
            }
            if (webActual() === web) refrescarConsola()
        }
    }

    private fun refrescarConsola() {
        val lista = listaConsola ?: return
        val p = pestanaActual() ?: return
        val todas = ArrayList<EntradaConsola>(p.dev.copiaConsola())
        todas.addAll(p.dev.agenteConsola)
        todas.sortBy { it.ts }
        val q = filtroConsola.trim().lowercase()
        val res = ArrayList<EntradaConsola>()
        for (e in todas) {
            val ok = when (filtroNivel) {
                1 -> e.nivel == "error"
                2 -> e.nivel == "warn"
                3 -> e.nivel == "log" || e.nivel == "info" || e.nivel == "debug"
                else -> true
            }
            if (!ok) continue
            if (q.isNotEmpty() && !e.texto.lowercase().contains(q)) continue
            res.add(e)
        }
        val cambio = res.size != visibleConsola.size || (res.isNotEmpty() && visibleConsola.isNotEmpty() && res.last() !== visibleConsola.last())
        visibleConsola = res
        adaptadorConsola.datos = res
        if (cambio) adaptadorConsola.notifyDataSetChanged()
        if (cambio && res.isNotEmpty() && lista.lastVisiblePosition >= res.size - 3) lista.setSelection(res.size - 1)
    }

    private fun ejecutarEntrada() {
        val ec = campoConsola ?: return
        val codigo = ec.text.toString().trim()
        if (codigo.isEmpty()) return
        ec.setText("")
        historial.add(codigo)
        if (historial.size > 50) historial.removeAt(0)
        posHistorial = historial.size
        ejecutar(codigo)
    }

    private fun ejecutar(codigo: String) {
        val web = webActual() ?: return
        val p = pestanaActual() ?: return
        val d = p.dev
        d.consola("in", codigo, null)
        refrescarConsola()
        agente.llamar(web, "ev(" + AgenteDev.lit(codigo) + ")") { s ->
            val o = DevFormato.objeto(s)
            when {
                o == null -> d.consola("error", "Sin respuesta del agente", null)
                o.has("sin") -> {
                    d.consola("error", "El agente no está en la página; reinyectando, vuelve a intentarlo", null)
                    agente.asegurar(web) { }
                }
                o.has("csp") -> {
                    // La página prohíbe eval (CSP): se ejecuta directamente, con resultado en JSON sin formato.
                    web.evaluateJavascript(codigo) { raw ->
                        d.consola("out", raw ?: "undefined", null)
                        if (webActual() === web) refrescarConsola()
                    }
                }
                o.has("err") -> d.consola("error", o.optString("err"), null)
                o.has("p") -> {
                    agente.esperar(web, o.optString("p")) { r ->
                        if (r.has("err")) d.consola("error", "Promesa rechazada: " + r.optString("err"), null)
                        else d.consola("out", "Promise → " + r.optString("v"), null)
                        if (webActual() === web) refrescarConsola()
                    }
                }
                else -> d.consola("out", o.optString("r"), null)
            }
            if (webActual() === web) refrescarConsola()
        }
    }

    // ---------------------------------------------------------------- ELEMENTOS

    private val arbol = ArrayList<NodoVista>()
    private var seleccionado = 0
    private var listaArbol: ListView? = null
    private var btnElegir: TextView? = null
    private var eligiendo = false

    private val adaptadorArbol = object : Lista<NodoVista>() {
        override fun getView(pos: Int, cv: View?, parent: ViewGroup?): View {
            val tv = (cv as? TextView) ?: texto("", 12f, paleta.texto, true).also {
                it.maxLines = 3
                it.ellipsize = TextUtils.TruncateAt.END
            }
            val n = datos[pos]
            tv.setPadding(dp(6) + n.nivel * dp(10), dp(5), dp(6), dp(5))
            val marca = if (n.hs > 0) (if (n.abierto) "▾ " else "▸ ") else "   "
            tv.text = marca + n.et
            tv.setTextColor(if (n.t == 1) paleta.texto else paleta.tenue)
            tv.setBackgroundColor(if (n.id > 0 && n.id == seleccionado) paleta.campo else Color.TRANSPARENT)
            return tv
        }
    }

    private fun construirElementos(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val lista = nuevaListView()
        lista.transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
        lista.adapter = adaptadorArbol
        lista.setOnItemClickListener { _, _, pos, _ ->
            val n = arbol.getOrNull(pos)
            if (n != null && n.id > 0) {
                seleccionar(n.id)
                if (n.hs > 0) alternarNodo(n.id)
            }
        }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            val n = arbol.getOrNull(pos)
            if (n != null && n.id > 0) {
                seleccionar(n.id)
                menuEditar()
            }
            true
        }
        listaArbol = lista
        col.addView(lista, lp(MATCH, 0, 1f))

        // Jerarquía del elemento seleccionado (html › body › div.caja › p)
        val migas = texto("", 11f, paleta.tenue, true)
        migas.setPadding(dp(8), dp(3), dp(8), dp(3))
        migas.setSingleLine(true)
        migas.ellipsize = TextUtils.TruncateAt.START
        migas.setBackgroundColor(paleta.barra)
        rutaTexto = migas
        col.addView(migas, lp(MATCH, WRAP))
        actualizarMigas()

        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.gravity = Gravity.CENTER_VERTICAL
        barra.setBackgroundColor(paleta.barra)
        val be = boton("🎯 Elegir", 12f) { alternarElegir() }
        btnElegir = be
        barra.addView(be, lp(0, dp(38), 1.3f))
        barra.addView(boton("Detalle", 12f) { conSeleccion { mostrarDetalle(it) } }, lp(0, dp(38), 1f))
        barra.addView(boton("Estilos", 12f) { conSeleccion { mostrarEstilos(it) } }, lp(0, dp(38), 1f))
        barra.addView(boton("Editar", 12f) { conSeleccion { menuEditar() } }, lp(0, dp(38), 1f))
        barra.addView(boton("⟳", 14f) { refrescarArbol() }, lp(0, dp(38), 0.6f))
        col.addView(barra, lp(MATCH, WRAP))
        return col
    }

    private var rutaTexto: TextView? = null

    private fun nombreNodo(n: NodoVista): String {
        val m = Regex("^<([A-Za-z][A-Za-z0-9-]*)").find(n.et) ?: return if (n.t == 3) "#texto" else "…"
        var r = m.groupValues[1]
        val id = Regex("\\sid=\"([^\"]*)\"").find(n.et)
        val cl = Regex("\\sclass=\"([^\"\\s]*)").find(n.et)
        if (id != null && id.groupValues[1].isNotEmpty()) r += "#" + id.groupValues[1]
        else if (cl != null && cl.groupValues[1].isNotEmpty()) r += "." + cl.groupValues[1]
        return r
    }

    private fun actualizarMigas() {
        val tv = rutaTexto ?: return
        val idx = arbol.indexOfFirst { it.id == seleccionado }
        if (seleccionado <= 0 || idx < 0) {
            tv.text = "Sin selección: toca un elemento o usa «Elegir»"
            return
        }
        val partes = ArrayList<String>()
        var nivel = arbol[idx].nivel
        partes.add(nombreNodo(arbol[idx]))
        var k = idx - 1
        while (k >= 0 && nivel > 0) {
            if (arbol[k].nivel < nivel) {
                nivel = arbol[k].nivel
                partes.add(nombreNodo(arbol[k]))
            }
            k--
        }
        partes.reverse()
        tv.text = partes.joinToString(" › ")
    }

    private fun conSeleccion(f: (Int) -> Unit) {
        if (seleccionado > 0) f(seleccionado) else avisar("Toca un elemento del árbol o usa «Elegir»")
    }

    private fun notificarArbol() {
        adaptadorArbol.datos = ArrayList(arbol)
        adaptadorArbol.notifyDataSetChanged()
        actualizarMigas()
    }

    private fun parseNodos(arr: JSONArray?, nivel: Int): List<NodoVista> {
        val res = ArrayList<NodoVista>()
        if (arr == null) return res
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            res.add(NodoVista(o.getInt("id"), nivel, o.optInt("t"), o.optString("et"), o.optInt("hs")))
        }
        return res
    }

    private fun cargarRaiz() {
        consultaObj("hijos(0)") { o ->
            if (o.has("sin") || o.has("err")) return@consultaObj
            arbol.clear()
            arbol.addAll(parseNodos(o.optJSONArray("h"), 0))
            notificarArbol()
            val idx = arbol.indexOfFirst { it.et.startsWith("<html") }
            if (idx >= 0) abrirNodo(arbol[idx].id)
        }
    }

    /** Recarga el árbol y vuelve a abrir el camino hasta el elemento seleccionado. */
    private fun refrescarArbol() {
        val sel = seleccionado
        arbol.clear()
        notificarArbol()
        consultaObj("hijos(0)") { o ->
            if (o.has("sin") || o.has("err")) return@consultaObj
            arbol.addAll(parseNodos(o.optJSONArray("h"), 0))
            notificarArbol()
            if (sel > 0) {
                seleccionarEnArbol(sel)
            } else {
                val idx = arbol.indexOfFirst { it.et.startsWith("<html") }
                if (idx >= 0) abrirNodo(arbol[idx].id)
            }
        }
    }

    private fun abrirNodo(id: Int, despues: (() -> Unit)? = null) {
        consultaObj("hijos($id)") { o ->
            val idx = arbol.indexOfFirst { it.id == id }
            if (idx < 0) return@consultaObj
            val n = arbol[idx]
            if (o.has("err")) {
                avisar(o.optString("err"))
                return@consultaObj
            }
            if (n.abierto) {
                despues?.invoke()
                return@consultaObj
            }
            val hs = ArrayList<NodoVista>(parseNodos(o.optJSONArray("h"), n.nivel + 1))
            val total = o.optInt("n")
            if (total > hs.size + 20) hs.add(NodoVista(-1, n.nivel + 1, 0, "… hay más nodos hijos (se muestran los primeros 200)", 0))
            arbol.addAll(idx + 1, hs)
            n.abierto = true
            notificarArbol()
            despues?.invoke()
        }
    }

    private fun alternarNodo(id: Int) {
        val idx = arbol.indexOfFirst { it.id == id }
        if (idx < 0) return
        val n = arbol[idx]
        if (n.abierto) {
            var k = idx + 1
            while (k < arbol.size && arbol[k].nivel > n.nivel) arbol.removeAt(k)
            n.abierto = false
            notificarArbol()
        } else {
            abrirNodo(id)
        }
    }

    private fun seleccionar(id: Int) {
        seleccionado = id
        adaptadorArbol.notifyDataSetChanged()
        actualizarMigas()
        consulta("resaltar($id)") { }
    }

    private fun seleccionarEnArbol(id: Int) {
        consulta("camino($id)") { s ->
            val arr = DevFormato.arreglo(s)
            if (arr == null) {
                val o = DevFormato.objeto(s)
                avisar(o?.optString("err") ?: "No se pudo localizar el elemento")
                return@consulta
            }
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val pid = o.getInt("pid")
                val hs = o.optJSONArray("h")
                if (pid == 0) {
                    if (arbol.isEmpty()) arbol.addAll(parseNodos(hs, 0))
                } else {
                    val idx = arbol.indexOfFirst { it.id == pid }
                    if (idx >= 0 && !arbol[idx].abierto) {
                        arbol.addAll(idx + 1, parseNodos(hs, arbol[idx].nivel + 1))
                        arbol[idx].abierto = true
                    }
                }
            }
            seleccionar(id)
            notificarArbol()
            val pos = arbol.indexOfFirst { it.id == id }
            if (pos >= 0) listaArbol?.setSelection(maxOf(0, pos - 2))
        }
    }

    // --- elegir tocando la página

    private fun alternarElegir() {
        if (eligiendo) {
            detenerElegir()
            return
        }
        eligiendo = true
        consulta("elegir(true)") { }
        btnElegir?.setTextColor(paleta.acento)
        avisar("Toca un elemento de la página")
        h.postDelayed(sondeoElegir, 400)
    }

    private fun detenerElegir() {
        eligiendo = false
        h.removeCallbacks(sondeoElegir)
        btnElegir?.setTextColor(paleta.texto)
        consulta("elegir(false)") { }
    }

    private val sondeoElegir = object : Runnable {
        override fun run() {
            if (!eligiendo || !visible) return
            consulta("elegido()") { s ->
                val id = s?.toIntOrNull() ?: 0
                if (id > 0) {
                    eligiendo = false
                    btnElegir?.setTextColor(paleta.texto)
                    seleccionarEnArbol(id)
                } else if (eligiendo) {
                    h.postDelayed(this, 400)
                }
            }
        }
    }

    // --- detalle

    private fun mostrarDetalle(id: Int) {
        consultaObj("info($id)") { o ->
            if (o.has("err")) {
                avisar(o.optString("err"))
                return@consultaObj
            }
            if (o.has("texto")) {
                empujar("Texto", vistaTexto(o.optString("texto")), listOf("Copiar" to { copiar(o.optString("texto")); avisar("Copiado") }))
                return@consultaObj
            }
            val sb = StringBuilder()
            val sel = o.optString("sel")
            sb.append("Selector\n").append(sel).append("\n\n")
            sb.append("Atributos\n")
            val at = o.optJSONArray("attrs")
            if (at == null || at.length() == 0) sb.append("  (ninguno)\n")
            else for (k in 0 until at.length()) {
                val par = at.getJSONArray(k)
                sb.append("  ").append(par.optString(0)).append(" = ").append(par.optString(1)).append("\n")
            }
            val r = o.optJSONObject("r")
            if (r != null) {
                sb.append("\nPosición y tamaño\n  x ").append(r.optDouble("x")).append(", y ").append(r.optDouble("y"))
                    .append("  ·  ").append(r.optDouble("w")).append(" × ").append(r.optDouble("h")).append("\n")
            }
            fun lado(nombre: String, a: JSONArray?) {
                if (a == null) return
                sb.append("  ").append(nombre).append(": ")
                    .append(a.optDouble(0)).append("  ").append(a.optDouble(1)).append("  ")
                    .append(a.optDouble(2)).append("  ").append(a.optDouble(3)).append("   (arr der abj izq)\n")
            }
            sb.append("\nCaja\n")
            lado("margen ", o.optJSONArray("m"))
            lado("borde  ", o.optJSONArray("b"))
            lado("relleno", o.optJSONArray("p"))
            sb.append("\nHTML\n").append(o.optString("html"))
            val acciones: List<Pair<String, () -> Unit>> = listOf(
                "Selector" to { copiar(sel); avisar("Selector copiado") },
                "HTML" to { copiar(o.optString("html")); avisar("HTML copiado") }
            )
            empujar("<" + o.optString("tag") + ">", vistaTexto(sb.toString()), acciones)
        }
    }

    private val propsUtiles = listOf(
        "display", "position", "top", "right", "bottom", "left", "z-index", "float", "width", "height", "min-width", "max-width",
        "min-height", "max-height", "margin-top", "margin-right", "margin-bottom", "margin-left", "padding-top", "padding-right",
        "padding-bottom", "padding-left", "border-top-width", "border-top-style", "border-top-color", "box-sizing", "color",
        "background-color", "background-image", "font-family", "font-size", "font-weight", "line-height", "text-align",
        "text-decoration-line", "overflow", "opacity", "visibility", "flex-direction", "justify-content", "align-items", "gap",
        "transform", "cursor", "pointer-events"
    )

    private fun mostrarEstilos(id: Int) {
        consultaObj("estilos($id)") { o ->
            if (o.has("err")) {
                avisar(o.optString("err"))
                return@consultaObj
            }
            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            val filtro = campo("Filtrar propiedades calculadas…")
            val barra = FrameLayout(act)
            barra.setPadding(dp(6), dp(4), dp(6), dp(4))
            barra.addView(filtro, FrameLayout.LayoutParams(MATCH, dp(34)))
            col.addView(barra, lp(MATCH, WRAP))
            val tv = texto("", 12f, paleta.texto, true)
            tv.setPadding(dp(10), dp(6), dp(10), dp(8))
            tv.setTextIsSelectable(true)
            val sv = ScrollView(act)
            sv.setBackgroundColor(paleta.fondo)
            sv.addView(tv, FrameLayout.LayoutParams(MATCH, WRAP))
            col.addView(sv, lp(MATCH, 0, 1f))

            val comp = o.optJSONObject("comp") ?: JSONObject()
            fun pintar(f: String) {
                val q = f.trim().lowercase()
                val sb = StringBuilder()
                if (q.isEmpty()) {
                    val inl = o.optString("inline")
                    sb.append("style en línea\n  ").append(if (inl.isEmpty()) "(vacío)" else inl).append("\n\n")
                    val reglas = o.optJSONArray("reglas")
                    sb.append("Reglas que se aplican (la más reciente primero)\n")
                    if (reglas == null || reglas.length() == 0) sb.append("  (ninguna legible)\n")
                    else for (k in 0 until reglas.length()) {
                        val r = reglas.getJSONObject(k)
                        sb.append("\n").append(r.optString("s")).append("  — ").append(DevFormato.nombreUrl(r.optString("o")))
                        val ds = r.optJSONArray("d")
                        if (ds != null) for (j in 0 until ds.length()) {
                            val dd = ds.getJSONArray(j)
                            sb.append("\n    ").append(dd.optString(0)).append(": ").append(dd.optString(1))
                            if (dd.optString(2).isNotEmpty()) sb.append(" !important")
                            sb.append(";")
                        }
                        sb.append("\n")
                    }
                    val bl = o.optJSONArray("bloq")
                    if (bl != null && bl.length() > 0) {
                        sb.append("\n⚠ ").append(bl.length()).append(" hoja(s) de estilo de otro dominio no se pueden leer (CORS), sus reglas no aparecen arriba.\n")
                    }
                    sb.append("\nCalculados (escribe arriba para ver todos)\n")
                    for (p in propsUtiles) if (comp.has(p)) sb.append("  ").append(p).append(": ").append(comp.optString(p)).append("\n")
                } else {
                    sb.append("Calculados que contienen «").append(q).append("»\n")
                    val claves = comp.keys()
                    val ks = ArrayList<String>()
                    while (claves.hasNext()) ks.add(claves.next())
                    ks.sort()
                    for (k in ks) {
                        val v = comp.optString(k)
                        if (k.contains(q) || v.lowercase().contains(q)) sb.append("  ").append(k).append(": ").append(v).append("\n")
                    }
                }
                tv.text = sb.toString()
            }
            pintar("")
            filtro.alCambiar { pintar(it) }
            val acciones: List<Pair<String, () -> Unit>> = listOf(
                "🎨" to { mostrarEstiloRapido(id) },
                "＋ Estilo" to { pedirTexto("Añadir al style del elemento", "color: red;", false) { d -> aplicarYRefrescar("estiloAdd($id, " + AgenteDev.lit(d) + ")") } },
                "CSS…" to { menuCssGlobal() }
            )
            empujar("Estilos", col, acciones)
        }
    }

    private fun menuCssGlobal() {
        consulta("cssActual()") { actual ->
            pedirTexto("CSS inyectado en la página", actual ?: "", true) { t ->
                consulta("css(" + AgenteDev.lit(t) + ")") { r ->
                    if (r == "ok") {
                        avisar("CSS aplicado")
                        if (t.isNotBlank()) preguntarGuardarCss(t)
                    } else {
                        avisar(r ?: "Error")
                    }
                }
            }
        }
    }

    private fun aplicarYRefrescar(expr: String) {
        consulta(expr) { r ->
            if (r == "ok") {
                avisar("Hecho")
                refrescarArbol()
            } else {
                avisar(r ?: "Error")
            }
        }
    }

    // --- edición

    private fun menuEditar() {
        val id = seleccionado
        if (id <= 0) return
        val etiquetas = listOf(
            "🎨 Estilo rápido (sin teclear)…",
            "★ Guardar estilo en este sitio…",
            "★ Eliminar y recordar en este sitio…",
            "Editar atributo…", "Añadir atributo…", "Editar HTML del elemento…", "Editar texto (nodo de texto)…",
            "Añadir estilo en línea (escribir)…", "Eliminar elemento", "Copiar selector", "Copiar HTML", "Copiar XPath",
            "Duplicar elemento", "Inyectar CSS en la página…"
        )
        elegirDeLista("Editar", etiquetas) { i ->
            when (i) {
                0 -> mostrarEstiloRapido(id)
                1 -> guardarEstiloSitio(id)
                2 -> guardarOcultarSitio(id)
                3 -> editarAtributo(id)
                4 -> pedirDos("Añadir atributo", "nombre", "valor") { n, v ->
                    if (n.isNotBlank()) aplicarYRefrescar("attr($id, " + AgenteDev.lit(n.trim()) + ", " + AgenteDev.lit(v) + ")")
                }
                5 -> consultaObj("info($id)") { o ->
                    pedirTexto("Editar HTML", o.optString("html"), true) { t -> aplicarYRefrescar("html($id, " + AgenteDev.lit(t) + ")"); seleccionado = 0 }
                }
                6 -> consultaObj("info($id)") { o ->
                    if (!o.has("texto")) {
                        avisar("Selecciona una fila de texto (entre comillas)")
                    } else {
                        pedirTexto("Editar texto", o.optString("texto"), true) { t -> aplicarYRefrescar("texto($id, " + AgenteDev.lit(t) + ")") }
                    }
                }
                7 -> pedirTexto("Añadir al style del elemento", "color: red;", false) { d -> aplicarYRefrescar("estiloAdd($id, " + AgenteDev.lit(d) + ")") }
                8 -> dialogo().setTitle("Eliminar elemento").setMessage("Se quita de la página (hasta recargarla).")
                    .setPositiveButton("Eliminar") { _, _ -> seleccionado = 0; aplicarYRefrescar("borrar($id)") }
                    .setNeutralButton("Recordar en el sitio") { _, _ -> guardarOcultarSitio(id) }
                    .setNegativeButton("Cancelar", null).show()
                9 -> consultaObj("info($id)") { o -> copiar(o.optString("sel")); avisar("Selector copiado") }
                10 -> consultaObj("info($id)") { o -> copiar(o.optString("html")); avisar("HTML copiado") }
                11 -> consulta("xpath($id)") { x -> if (x.isNullOrEmpty()) avisar("No se pudo calcular") else { copiar(x); avisar("XPath copiado") } }
                12 -> aplicarYRefrescar("duplicar($id)")
                else -> menuCssGlobal()
            }
        }
    }

    // --- reglas permanentes del sitio (las mismas de «Personalizar este sitio»)

    /** Oculta el elemento en cada visita al sitio: guarda una regla con un selector corto y estable. */
    private fun guardarOcultarSitio(id: Int) {
        consultaObj("selGuardar($id)") { o ->
            if (o.has("err")) {
                avisar(o.optString("err"))
                return@consultaObj
            }
            val sel = o.optString("sel")
            val n = o.optInt("n", 1)
            dialogo().setTitle("Ocultar siempre en este sitio")
                .setMessage(
                    "Regla: ocultar\n" + sel + "\n\nCoincide con " + n + (if (n == 1) " elemento." else " elementos.") +
                        (if (n > 3) "\nSi son demasiados, elige un elemento más concreto." else "")
                )
                .setPositiveButton("Guardar") { _, _ ->
                    avisar(reglaSitio("ocultar", sel, ""))
                    seleccionado = 0
                    refrescarArbol()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    /**
     * Guarda como regla del sitio los estilos que has cambiado desde el inspector, sin teclear nada: se toman del registro del agente
     * (se rellenan con «Estilo rápido»). Si aún no hay cambios, abre «Estilo rápido» para elegirlos con toques.
     */
    private fun guardarEstiloSitio(id: Int, desdeRapido: Boolean = false, alGuardar: (() -> Unit)? = null) {
        consultaObj("guardable($id)") { o ->
            if (o.has("err")) {
                avisar(o.optString("err"))
                return@consultaObj
            }
            val css = o.optString("css")
            val sel = o.optString("sel")
            val n = o.optInt("n", 1)
            if (css.isEmpty()) {
                if (desdeRapido) {
                    avisar("Aún no has cambiado ningún estilo")
                } else {
                    avisar("Elige los cambios aquí y luego pulsa 💾 Guardar")
                    mostrarEstiloRapido(id)
                }
                return@consultaObj
            }
            val bonito = StringBuilder(sel).append(" {\n")
            val props = o.optJSONObject("props")
            if (props != null) {
                val ks = props.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    bonito.append("  ").append(k).append(": ").append(props.optString(k)).append(" !important;\n")
                }
            }
            bonito.append("}")
            dialogo().setTitle("Guardar estilos en este sitio")
                .setMessage(
                    bonito.toString() + "\n\nCoincide con " + n + (if (n == 1) " elemento." else " elementos.") +
                        (if (n > 3) "\nSi son demasiados, elige un elemento más concreto." else "") +
                        "\n\nSe aplicará en cada visita (Reglas de este sitio)."
                )
                .setPositiveButton("Guardar") { _, _ ->
                    avisar(reglaSitio("estilo", "Estilo: " + DevFormato.cortar(sel, 60), css))
                    consulta("olvidar($id)") { alGuardar?.invoke() }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    // --- Estilo rápido: cambiar el aspecto de un elemento solo con toques (la página se ve arriba y cambia en vivo)

    private val coloresRapidos = listOf(
        "#FFFFFF", "#F5F5F5", "#BDBDBD", "#757575", "#424242", "#000000",
        "#E53935", "#FB8C00", "#FDD835", "#43A047", "#00897B", "#1E88E5", "#3949AB", "#8E24AA", "#D81B60", "#6D4C41"
    )

    private fun chip(t: String, accion: () -> Unit): TextView {
        val v = boton(t, 12f, accion)
        v.background = fondoRedondeado(paleta.campo, dp(8))
        v.setPadding(dp(12), dp(6), dp(12), dp(6))
        return v
    }

    private fun muestraColor(color: String, accion: () -> Unit): View {
        val v = View(act)
        val g = GradientDrawable()
        g.setColor(Color.parseColor(color))
        g.cornerRadius = dp(8).toFloat()
        g.setStroke(dp(1), paleta.tenue)
        v.background = g
        v.contentDescription = color
        v.minimumWidth = dp(38)
        v.minimumHeight = dp(38)
        v.isClickable = true
        v.setOnClickListener { accion() }
        return v
    }

    private fun mostrarEstiloRapido(id: Int) {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val resumen = texto("", 11f, paleta.tenue, true)
        resumen.setPadding(dp(10), dp(6), dp(10), dp(6))
        resumen.setBackgroundColor(paleta.barra)
        col.addView(resumen, lp(MATCH, WRAP))

        val lista = LinearLayout(act)
        lista.orientation = LinearLayout.VERTICAL
        lista.setPadding(dp(8), dp(2), dp(8), dp(14))
        val sv = ScrollView(act)
        sv.setBackgroundColor(paleta.fondo)
        sv.addView(lista, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(sv, lp(MATCH, 0, 1f))

        fun refrescarResumen() {
            consultaObj("guardable($id)") { o ->
                if (o.has("err")) {
                    resumen.text = o.optString("err")
                    return@consultaObj
                }
                val n = o.optInt("n")
                val sb = StringBuilder(DevFormato.cortar(o.optString("sel"), 70))
                    .append("  ·  ").append(n).append(if (n == 1) " elemento" else " elementos").append("\n")
                val props = o.optJSONObject("props")
                if (props == null || props.length() == 0) {
                    sb.append("Sin cambios todavía: toca un ajuste y se ve arriba al momento")
                } else {
                    sb.append("Sin guardar: ")
                    val ks = props.keys()
                    var primero = true
                    while (ks.hasNext()) {
                        val k = ks.next()
                        if (!primero) sb.append(", ")
                        sb.append(k).append(" ").append(DevFormato.cortar(props.optString(k), 18))
                        primero = false
                    }
                }
                resumen.text = sb.toString()
            }
        }

        fun aplicar(decl: String) {
            consulta("estiloAdd($id, " + AgenteDev.lit(decl) + ")") { r ->
                if (r != "ok") avisar(r ?: "Error")
                refrescarResumen()
            }
        }

        fun ajustar(props: String, delta: Int, minimo: Int) {
            consulta("ajustarPx($id, " + AgenteDev.lit(props) + ", $delta, $minimo)") { r ->
                if (r != "ok") avisar(r ?: "Error")
                refrescarResumen()
            }
        }

        fun fila(titulo: String, vararg chips: View) {
            val t = texto(titulo, 11f, paleta.tenue)
            t.setPadding(dp(4), dp(10), dp(4), dp(3))
            lista.addView(t, lp(MATCH, WRAP))
            val hs = HorizontalScrollView(act)
            hs.isHorizontalScrollBarEnabled = false
            val linea = LinearLayout(act)
            linea.orientation = LinearLayout.HORIZONTAL
            for (c in chips) {
                val par = lp(WRAP, dp(38))
                par.setMargins(0, 0, dp(6), 0)
                linea.addView(c, par)
            }
            hs.addView(linea, FrameLayout.LayoutParams(WRAP, WRAP))
            lista.addView(hs, lp(MATCH, WRAP))
        }

        val colTexto = ArrayList<View>()
        for (c in coloresRapidos) colTexto.add(muestraColor(c) { aplicar("color:$c") })
        fila("Color del texto", *colTexto.toTypedArray())

        val colFondo = ArrayList<View>()
        for (c in coloresRapidos) colFondo.add(muestraColor(c) { aplicar("background-color:$c") })
        colFondo.add(chip("Transparente") { aplicar("background-color:transparent") })
        fila("Color de fondo", *colFondo.toTypedArray())

        fila(
            "Letra",
            chip("A−") { ajustar("font-size", -2, 6) }, chip("A+") { ajustar("font-size", 2, 6) },
            chip("Negrita") { aplicar("font-weight:700") }, chip("Normal") { aplicar("font-weight:400") }
        )
        fila(
            "Alinear texto",
            chip("Izquierda") { aplicar("text-align:left") }, chip("Centro") { aplicar("text-align:center") },
            chip("Derecha") { aplicar("text-align:right") }, chip("Justificar") { aplicar("text-align:justify") }
        )
        val margenes = "margin-top,margin-right,margin-bottom,margin-left"
        fila(
            "Margen (por fuera)",
            chip("−8 px") { ajustar(margenes, -8, 0) }, chip("+8 px") { ajustar(margenes, 8, 0) }, chip("Sin margen") { aplicar("margin:0") }
        )
        val rellenos = "padding-top,padding-right,padding-bottom,padding-left"
        fila(
            "Relleno (por dentro)",
            chip("−8 px") { ajustar(rellenos, -8, 0) }, chip("+8 px") { ajustar(rellenos, 8, 0) }, chip("Sin relleno") { aplicar("padding:0") }
        )
        fila(
            "Opacidad",
            chip("100%") { aplicar("opacity:1") }, chip("75%") { aplicar("opacity:.75") }, chip("50%") { aplicar("opacity:.5") },
            chip("25%") { aplicar("opacity:.25") }, chip("0%") { aplicar("opacity:0") }
        )
        fila(
            "Tamaño",
            chip("Ancho auto") { aplicar("width:auto") }, chip("Ancho 100%") { aplicar("width:100%") }, chip("Alto auto") { aplicar("height:auto") }
        )
        fila(
            "Posición",
            chip("Quitar fijación") { aplicar("position:static") }, chip("Relativa") { aplicar("position:relative") }
        )
        fila(
            "Bordes y sombra",
            chip("Sin borde") { aplicar("border:none") }, chip("Borde gris") { aplicar("border:1px solid #888") },
            chip("Esquinas 8 px") { aplicar("border-radius:8px") }, chip("Círculo") { aplicar("border-radius:50%") },
            chip("Sin sombra") { aplicar("box-shadow:none") }
        )

        val acciones: List<Pair<String, () -> Unit>> = listOf(
            "↶ Deshacer" to {
                consulta("deshacerEstilos($id)") { r ->
                    avisar(if (r == "ok") "Cambios de estilo deshechos" else (r ?: "Error"))
                    refrescarResumen()
                }
            },
            "💾 Guardar" to { guardarEstiloSitio(id, true) { refrescarResumen() } }
        )
        empujar("🎨 Estilo rápido", col, acciones)
        refrescarResumen()
    }

    private fun preguntarGuardarCss(css: String) {
        dialogo().setTitle("¿Guardar en este sitio?")
            .setMessage("Este CSS solo vale hasta recargar la página. ¿Quieres guardarlo como CSS propio del sitio para que se aplique en cada visita?")
            .setPositiveButton("Guardar") { _, _ -> avisar(reglaSitio("cssPropio", css, "")) }
            .setNegativeButton("No", null)
            .show()
    }

    private fun editarAtributo(id: Int) {
        consultaObj("info($id)") { o ->
            val at = o.optJSONArray("attrs")
            if (at == null || at.length() == 0) {
                avisar("El elemento no tiene atributos")
                return@consultaObj
            }
            val nombres = ArrayList<String>()
            for (k in 0 until at.length()) nombres.add(at.getJSONArray(k).optString(0))
            elegirDeLista("Atributo", nombres) { i ->
                val nombre = nombres[i]
                val valor = at.getJSONArray(i).optString(1)
                dialogo().setTitle(nombre)
                    .setMessage(DevFormato.cortar(valor, 300))
                    .setPositiveButton("Editar") { _, _ ->
                        pedirTexto("Valor de $nombre", valor, true) { t -> aplicarYRefrescar("attr($id, " + AgenteDev.lit(nombre) + ", " + AgenteDev.lit(t) + ")") }
                    }
                    .setNegativeButton("Quitar") { _, _ -> aplicarYRefrescar("attr($id, " + AgenteDev.lit(nombre) + ", null)") }
                    .setNeutralButton("Cancelar", null)
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- RED

    private var listaRed: ListView? = null
    private var btnTipo: TextView? = null
    private var filtroRed = ""
    private var filtroTipo = 0
    private var visibleRed: List<EntradaRed> = emptyList()
    private val nombresTipo = listOf("Todo", "XHR/Fetch", "JS", "CSS", "Imágenes", "Documentos", "Otros", "Solo fallos")

    private val adaptadorRed = object : Lista<EntradaRed>() {
        override fun getView(pos: Int, cv: View?, parent: ViewGroup?): View {
            val tv = (cv as? TextView) ?: texto("", 12f, paleta.texto, false).also {
                it.setPadding(dp(8), dp(5), dp(8), dp(5))
                it.maxLines = 2
                it.ellipsize = TextUtils.TruncateAt.END
            }
            val e = datos[pos]
            val colorEstado = when {
                e.bloqueado -> paleta.advertencia
                e.err != null || e.estado >= 400 -> rojo
                e.pendiente -> paleta.tenue
                else -> paleta.texto
            }
            val etiqueta = when {
                e.bloqueado -> "⛔"
                e.pendiente -> "…"
                e.estado == 0 && e.err != null -> "ERR"
                e.estado == 0 -> "—"
                else -> e.estado.toString()
            }
            val sb = SpannableStringBuilder()
            sb.append(etiqueta)
            sb.setSpan(ForegroundColorSpan(colorEstado), 0, sb.length, 0)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, 0)
            sb.append("  ").append(e.metodo).append("  ").append(DevFormato.nombreUrl(e.url))
            val ini = sb.length
            val partes = ArrayList<String>()
            val host = DevFormato.hostUrl(e.url)
            if (host.isNotEmpty()) partes.add(host)
            partes.add(e.tipo)
            if (e.tam >= 0) partes.add(DevFormato.tam(e.tam))
            if (e.ms >= 0) partes.add(DevFormato.tiempo(e.ms))
            val nota = e.nota
            if (nota != null) partes.add(nota)
            sb.append("\n").append(partes.joinToString(" · "))
            sb.setSpan(ForegroundColorSpan(paleta.tenue), ini, sb.length, 0)
            sb.setSpan(RelativeSizeSpan(0.85f), ini, sb.length, 0)
            tv.text = sb
            return tv
        }
    }

    private fun construirRed(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.gravity = Gravity.CENTER_VERTICAL
        barra.setPadding(dp(4), dp(3), dp(4), dp(3))
        val bt = boton(nombresTipo[filtroTipo] + " ▾", 12f) {
            elegirDeLista("Tipo", nombresTipo) { i ->
                filtroTipo = i
                btnTipo?.text = nombresTipo[i] + " ▾"
                refrescarRed()
            }
        }
        btnTipo = bt
        barra.addView(bt, lp(WRAP, dp(34)))
        val filtro = campo("Filtrar por URL…")
        filtro.setText(filtroRed)
        filtro.alCambiar { filtroRed = it; refrescarRed() }
        barra.addView(filtro, lp(0, dp(34), 1f))
        barra.addView(boton("🗑", 14f) { limpiarRed() }, lp(dp(40), dp(34)))
        col.addView(barra, lp(MATCH, WRAP))

        val lista = nuevaListView()
        lista.adapter = adaptadorRed
        lista.setOnItemClickListener { _, _, pos, _ ->
            val e = visibleRed.getOrNull(pos)
            if (e != null) mostrarDetalleRed(e)
        }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            val e = visibleRed.getOrNull(pos)
            if (e != null) {
                copiar(e.url)
                avisar("URL copiada")
            }
            true
        }
        listaRed = lista
        col.addView(lista, lp(MATCH, 0, 1f))
        return col
    }

    private fun limpiarRed() {
        val p = pestanaActual() ?: return
        p.dev.vaciarRed()
        consulta("limpiarRed()") { }
        // El agente reinicia sus contadores solo al recargar; se conserva la versión para no repetir entradas.
        refrescarRed()
    }

    private fun sondearRed(web: WebView, d: DatosDev) {
        agente.llamar(web, "red(" + d.agenteUltimaV + ")") { s ->
            val o = DevFormato.objeto(s)
            if (o != null && o.has("e")) {
                d.agenteUltimaV = o.optLong("v", d.agenteUltimaV)
                val arr = o.getJSONArray("e")
                for (k in 0 until arr.length()) {
                    val x = arr.getJSONObject(k)
                    val i = x.optLong("i")
                    val e = d.agenteRed.getOrPut(i) { EntradaRed(i) }
                    e.ts = x.optLong("ts")
                    e.tipo = x.optString("tipo", "otro")
                    e.metodo = x.optString("m", "GET")
                    e.url = x.optString("u")
                    e.estado = x.optInt("st")
                    e.ms = x.optInt("ms", -1)
                    e.tam = x.optLong("tam", -1L)
                    e.err = x.optString("err").ifEmpty { null }
                    e.pendiente = x.optInt("pend") == 1
                }
                val claves = d.agenteRed.keys.iterator()
                while (d.agenteRed.size > DatosDev.MAX && claves.hasNext()) {
                    claves.next()
                    claves.remove()
                }
            } else if (s != null && s.contains("\"sin\"")) {
                agente.asegurar(web) { }
            }
            if (webActual() === web) refrescarRed()
        }
    }

    private fun coincideTipo(e: EntradaRed): Boolean = when (filtroTipo) {
        1 -> e.tipo == "fetch" || e.tipo == "xhr" || e.tipo == "ws"
        2 -> e.tipo == "js"
        3 -> e.tipo == "css"
        4 -> e.tipo == "img"
        5 -> e.tipo == "doc"
        6 -> e.tipo == "fuente" || e.tipo == "medio" || e.tipo == "otro"
        7 -> e.bloqueado || e.err != null || e.estado >= 400
        else -> true
    }

    /** Une lo que ve el agente con lo que solo sabe la app (bloqueos de Optimo y errores HTTP del WebView). */
    private fun filasRed(d: DatosDev): List<EntradaRed> {
        val filas = ArrayList<EntradaRed>(d.agenteRed.values)
        val bloqueos = d.copiaBloqueos()
        val errores = d.copiaErrores()
        val urlsAgente = HashSet<String>()
        for (e in filas) urlsAgente.add(e.url)
        val urlsBloqueadas = HashSet<String>()
        for (b in bloqueos) urlsBloqueadas.add(b.url)
        val errPorUrl = HashMap<String, ErrorNativo>()
        for (x in errores) errPorUrl[x.url] = x
        for (e in filas) {
            e.bloqueado = urlsBloqueadas.contains(e.url)
            e.nota = if (e.bloqueado) "bloqueado por Optimo" else null
            val x = errPorUrl[e.url]
            if (x != null && e.estado == 0 && !e.bloqueado) {
                e.estado = x.estado
                e.nota = x.texto.ifEmpty { null }
            }
        }
        var sid = -1L
        for (b in bloqueos) {
            if (urlsAgente.contains(b.url)) continue
            val e = EntradaRed(sid--)
            e.ts = b.ts
            e.metodo = b.metodo
            e.url = b.url
            e.bloqueado = true
            e.nota = "bloqueado por Optimo"
            e.sintetica = true
            filas.add(e)
        }
        for (x in errores) {
            if (urlsAgente.contains(x.url) || urlsBloqueadas.contains(x.url)) continue
            val e = EntradaRed(sid--)
            e.ts = x.ts
            e.metodo = x.metodo
            e.url = x.url
            e.estado = x.estado
            e.err = if (x.estado == 0) x.texto else null
            e.nota = x.texto.ifEmpty { null }
            e.sintetica = true
            filas.add(e)
        }
        filas.sortBy { it.ts }
        return filas
    }

    private fun refrescarRed() {
        val lista = listaRed ?: return
        val p = pestanaActual() ?: return
        val q = filtroRed.trim().lowercase()
        val res = ArrayList<EntradaRed>()
        for (e in filasRed(p.dev)) {
            if (!coincideTipo(e)) continue
            if (q.isNotEmpty() && !e.url.lowercase().contains(q)) continue
            res.add(e)
        }
        visibleRed = res
        adaptadorRed.datos = res
        adaptadorRed.notifyDataSetChanged()
        if (res.isNotEmpty() && lista.lastVisiblePosition >= res.size - 3) lista.setSelection(res.size - 1)
    }

    private fun mostrarDetalleRed(e: EntradaRed) {
        val base = StringBuilder()
        base.append(e.url).append("\n\n")
        base.append("Método: ").append(e.metodo).append("\n")
        base.append("Estado: ").append(if (e.estado == 0) "—" else e.estado.toString()).append("\n")
        base.append("Tipo: ").append(e.tipo).append("\n")
        base.append("Tiempo: ").append(DevFormato.tiempo(e.ms)).append("\n")
        base.append("Tamaño: ").append(DevFormato.tam(e.tam)).append("\n")
        if (e.bloqueado) base.append("\n⛔ Optimo bloqueó esta petición (anuncios o rastreadores).\n")
        val nota = e.nota
        if (nota != null && !e.bloqueado) base.append("Nota: ").append(nota).append("\n")
        val err = e.err
        if (err != null) base.append("Error: ").append(err).append("\n")
        val acciones = ArrayList<Pair<String, () -> Unit>>()
        acciones.add("URL" to { copiar(e.url); avisar("URL copiada") })

        if (e.sintetica) {
            base.append("\nSolo se conoce lo que ve la app: el agente no vio esta petición (p. ej. de un iframe o bloqueada antes de salir).\n")
            empujar(DevFormato.nombreUrl(e.url), vistaTexto(base.toString()), acciones)
            return
        }
        consultaObj("detalleRed(" + e.i + ")") { o ->
            if (o.has("err") && !o.has("u")) {
                base.append("\n").append(o.optString("err")).append("\n")
                empujar(DevFormato.nombreUrl(e.url), vistaTexto(base.toString()), acciones)
                return@consultaObj
            }
            val fu = o.optString("fu")
            if (fu.isNotEmpty() && fu != e.url) base.append("URL final (tras redirección): ").append(fu).append("\n")
            val n = o.optInt("n")
            if (n > 0) base.append("Mensajes recibidos: ").append(n).append("\n")
            val cierre = o.optInt("cierre")
            if (cierre > 0) base.append("Cierre: código ").append(cierre).append("\n")
            if (o.optInt("rt") == 1) {
                base.append("\nRecurso cargado por la propia página (etiqueta img, script, link…): solo se conocen estado, tiempo y tamaño.\n")
            } else {
                val rq = DevFormato.mapa(o.optJSONObject("rq"))
                base.append("\nCabeceras de la petición (solo las que añadió la página)\n")
                if (rq.isEmpty()) base.append("  (ninguna)\n") else for ((k, v) in rq) base.append("  ").append(k).append(": ").append(v).append("\n")
                val rs = DevFormato.mapa(o.optJSONObject("rs"))
                base.append("\nCabeceras de la respuesta\n")
                if (rs.isEmpty()) base.append("  (ninguna visible)\n") else for ((k, v) in rs) base.append("  ").append(k).append(": ").append(v).append("\n")
                val cuerpo = o.optString("cuerpo")
                base.append("\nCuerpo")
                if (cuerpo.isEmpty()) {
                    base.append("\n  (no disponible: solo se guarda texto, JSON o HTML de fetch/XHR)\n")
                } else {
                    base.append(" (").append(DevFormato.tam(o.optLong("cuerpoTam")))
                    if (o.optInt("corte") == 1) base.append(", cortado")
                    if (cuerpo.length >= 20000) base.append(", primeros 20000 caracteres")
                    base.append(")\n").append(cuerpo).append("\n")
                }
                acciones.add("cURL" to { copiar(DevFormato.curl(e.metodo, e.url, rq)); avisar("cURL copiado") })
                if (cuerpo.isNotEmpty()) acciones.add("Cuerpo" to { copiar(cuerpo); avisar("Cuerpo copiado") })
            }
            empujar(DevFormato.nombreUrl(e.url), vistaTexto(base.toString()), acciones)
        }
    }

    // ---------------------------------------------------------------- ALMACÉN

    private var listaAlm: ListView? = null
    private var generacionAlm = 0

    private val adaptadorAlm = object : Lista<FilaAlm>() {
        override fun getView(pos: Int, cv: View?, parent: ViewGroup?): View {
            val tv = (cv as? TextView) ?: texto("", 12f, paleta.texto, true).also {
                it.maxLines = 2
                it.ellipsize = TextUtils.TruncateAt.END
            }
            val f = datos[pos]
            if (f.titulo != null) {
                tv.text = f.titulo
                tv.setTextColor(paleta.acento)
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                tv.setPadding(dp(8), dp(10), dp(8), dp(3))
                tv.setBackgroundColor(Color.TRANSPARENT)
            } else {
                val sb = SpannableStringBuilder()
                sb.append(f.clave)
                if (f.valor.isNotEmpty()) {
                    val ini = sb.length
                    sb.append("  =  ").append(f.valor)
                    sb.setSpan(ForegroundColorSpan(paleta.tenue), ini, sb.length, 0)
                }
                tv.text = sb
                tv.setTextColor(paleta.texto)
                tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                tv.setPadding(dp(14), dp(5), dp(8), dp(5))
                tv.setBackgroundColor(Color.TRANSPARENT)
            }
            return tv
        }
    }

    private fun construirAlmacen(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val lista = nuevaListView()
        lista.transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
        lista.adapter = adaptadorAlm
        lista.setOnItemClickListener { _, _, pos, _ ->
            val f = adaptadorAlm.datos.getOrNull(pos)
            if (f != null && f.titulo == null) abrirFilaAlm(f)
        }
        listaAlm = lista
        col.addView(lista, lp(MATCH, 0, 1f))
        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.setBackgroundColor(paleta.barra)
        barra.addView(boton("⟳ Actualizar", 12f) { cargarAlmacen() }, lp(0, dp(38), 1f))
        barra.addView(boton("＋ Añadir", 12f) { anadirAlm() }, lp(0, dp(38), 1f))
        col.addView(barra, lp(MATCH, WRAP))
        return col
    }

    private fun cargarAlmacen() {
        val web = webActual() ?: return
        val gen = ++generacionAlm
        val url = web.url ?: ""
        val filas = ArrayList<FilaAlm>()
        filas.add(FilaAlm("t", titulo = "COOKIES (incluye las HttpOnly)"))
        val ck = try { gestorCookies(web).getCookie(url) } catch (e: Exception) { null }
        if (ck.isNullOrEmpty()) {
            filas.add(FilaAlm("info", "(sin cookies para esta URL)"))
        } else {
            for (par in ck.split("; ")) {
                val i = par.indexOf('=')
                if (i > 0) filas.add(FilaAlm("ck", par.substring(0, i), DevFormato.cortar(par.substring(i + 1), 200)))
            }
        }
        consultaObj("almacen()") { o ->
            if (gen != generacionAlm) return@consultaObj
            if (o.has("sin")) {
                filas.add(FilaAlm("info", "(el agente no está en la página)"))
            } else {
                for ((tipo, nombre, clave) in listOf(Triple("ls", "LOCALSTORAGE", "lsErr"), Triple("ss", "SESSIONSTORAGE", "ssErr"))) {
                    filas.add(FilaAlm("t", titulo = nombre))
                    val arr = o.optJSONArray(tipo)
                    if (!o.optString(clave).isNullOrEmpty()) filas.add(FilaAlm("info", "(no accesible: " + o.optString(clave) + ")"))
                    else if (arr == null || arr.length() == 0) filas.add(FilaAlm("info", "(vacío)"))
                    else for (k in 0 until arr.length()) {
                        val par = arr.getJSONArray(k)
                        filas.add(FilaAlm(tipo, par.optString(0), par.optString(1)))
                    }
                }
            }
            adaptadorAlm.datos = ArrayList(filas)
            adaptadorAlm.notifyDataSetChanged()
            consulta("bd()") { id ->
                if (id == null || id.toIntOrNull() == null) return@consulta
                agente.esperar(web, id) { r ->
                    if (gen != generacionAlm) return@esperar
                    val v = r.optJSONObject("v") ?: return@esperar
                    for ((clave, nombre) in listOf(Pair("idb", "INDEXEDDB"), Pair("caches", "CACHE STORAGE"), Pair("sw", "SERVICE WORKERS"))) {
                        val a = v.optJSONArray(clave)
                        if (a != null && a.length() > 0) {
                            filas.add(FilaAlm("t", titulo = nombre))
                            for (k in 0 until a.length()) filas.add(FilaAlm("info", a.optString(k)))
                        }
                    }
                    adaptadorAlm.datos = ArrayList(filas)
                    adaptadorAlm.notifyDataSetChanged()
                }
            }
        }
        adaptadorAlm.datos = ArrayList(filas)
        adaptadorAlm.notifyDataSetChanged()
    }

    private fun abrirFilaAlm(f: FilaAlm) {
        val web = webActual() ?: return
        when (f.tipo) {
            "ls", "ss" -> {
                val tipo = f.tipo
                consulta("almVer(" + AgenteDev.lit(tipo) + ", " + AgenteDev.lit(f.clave) + ")") { valor ->
                    val v = valor ?: ""
                    dialogo().setTitle(f.clave).setMessage(DevFormato.cortar(v, 1500))
                        .setPositiveButton("Editar") { _, _ ->
                            pedirTexto("Valor de " + f.clave, v, true) { t ->
                                consulta("almSet(" + AgenteDev.lit(tipo) + ", " + AgenteDev.lit(f.clave) + ", " + AgenteDev.lit(t) + ")") { cargarAlmacen() }
                            }
                        }
                        .setNegativeButton("Borrar") { _, _ ->
                            consulta("almDel(" + AgenteDev.lit(tipo) + ", " + AgenteDev.lit(f.clave) + ")") { cargarAlmacen() }
                        }
                        .setNeutralButton("Copiar") { _, _ -> copiar(v); avisar("Copiado") }
                        .show()
                }
            }
            "ck" -> {
                dialogo().setTitle(f.clave).setMessage(f.valor)
                    .setPositiveButton("Borrar") { _, _ -> borrarCookie(web, f.clave); cargarAlmacen() }
                    .setNeutralButton("Copiar") { _, _ -> copiar(f.clave + "=" + f.valor); avisar("Copiado") }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
            else -> {
                copiar(f.clave)
                avisar("Copiado")
            }
        }
    }

    /** Borra una cookie probando las combinaciones habituales de dominio (no se puede saber cuál usó el sitio). */
    private fun borrarCookie(web: WebView, nombre: String) {
        val url = web.url ?: return
        val host = DevFormato.hostUrl(url)
        val cm = gestorCookies(web)
        val intentos = ArrayList<String>()
        intentos.add("$nombre=; Max-Age=0; Path=/")
        if (host.isNotEmpty()) {
            intentos.add("$nombre=; Max-Age=0; Path=/; Domain=$host")
            intentos.add("$nombre=; Max-Age=0; Path=/; Domain=.$host")
            val partes = host.split(".")
            if (partes.size >= 3) intentos.add("$nombre=; Max-Age=0; Path=/; Domain=." + partes.drop(1).joinToString("."))
        }
        for (c in intentos) {
            try { cm.setCookie(url, c) } catch (e: Exception) { }
        }
        try { cm.flush() } catch (e: Exception) { }
        avisar("Cookie borrada (si el sitio usó otra ruta o dominio, puede seguir)")
    }

    private fun anadirAlm() {
        elegirDeLista("Añadir", listOf("localStorage", "sessionStorage")) { i ->
            val tipo = if (i == 0) "ls" else "ss"
            pedirDos("Nueva entrada", "clave", "valor") { k, v ->
                if (k.isNotBlank()) {
                    consulta("almSet(" + AgenteDev.lit(tipo) + ", " + AgenteDev.lit(k) + ", " + AgenteDev.lit(v) + ")") { cargarAlmacen() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- INFO

    private var textoInfo: TextView? = null
    private var informe = ""
    private var infoUrl = ""
    private var infoTitulo = ""

    private fun construirInfo(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.setBackgroundColor(paleta.barra)
        barra.addView(boton("Código fuente", 12f) { verFuente(null) }, lp(0, dp(38), 1.3f))
        barra.addView(boton("Scripts y CSS", 12f) { listarFuentes() }, lp(0, dp(38), 1.2f))
        barra.addView(boton("Copiar", 12f) { copiar(informe); avisar("Informe copiado") }, lp(0, dp(38), 1f))
        barra.addView(boton("Diagnóstico", 12f) { copiar(diagnosticoCompleto()); avisar("Diagnóstico copiado") }, lp(0, dp(38), 1.3f))
        col.addView(barra, lp(MATCH, WRAP))
        val sv = ScrollView(act)
        sv.setBackgroundColor(paleta.fondo)
        val tv = texto("Cargando…", 12f, paleta.texto, true)
        tv.setPadding(dp(10), dp(8), dp(10), dp(8))
        tv.setTextIsSelectable(true)
        textoInfo = tv
        sv.addView(tv, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(sv, lp(MATCH, 0, 1f))
        val pie = boton(capturaTexto(), 12f) { alternarCaptura() }
        pie.setBackgroundColor(paleta.barra)
        pie.tag = "pieCaptura"
        col.addView(pie, lp(MATCH, WRAP))
        return col
    }

    /** Informe para compartir: datos de la página, errores y avisos de la consola y peticiones bloqueadas o fallidas. Sin cookies, cabeceras ni cuerpos, y sin parámetros de las URL. */
    /** El informe de la pestaña Info listo para compartir: la URL sin credenciales, consulta ni fragmento, y el título redactado. */
    private fun informeSeguro(): String {
        var t = informe
        if (t.isEmpty()) return t
        if (infoUrl.isNotEmpty()) t = t.replace(infoUrl, DevFormato.sinParametros(infoUrl))
        if (infoTitulo.isNotEmpty()) t = t.replace(infoTitulo, DevFormato.cortar(DevFormato.redactarTexto(infoTitulo), 200))
        return t
    }

    private fun diagnosticoCompleto(): String {
        val p = pestanaActual()
        val sb = StringBuilder()
        sb.append("Diagnóstico de Óptimo DevTools\n\n")
        sb.append(informeSeguro().ifEmpty { "(abre antes la pestaña Info para cargar los datos de la página)\n" }).append("\n")
        if (p != null) {
            val d = p.dev
            val todas = ArrayList<EntradaConsola>(d.copiaConsola())
            todas.addAll(d.agenteConsola)
            todas.sortBy { it.ts }
            val graves = todas.filter { it.nivel == "error" || it.nivel == "warn" }
            sb.append("Consola: ").append(graves.size).append(" errores y avisos\n")
            for (e in graves.takeLast(40)) {
                sb.append("  [").append(e.nivel).append("] ").append(DevFormato.cortar(DevFormato.redactarTexto(e.texto.replace('\n', ' ')), 300)).append("\n")
            }
            val red = filasRed(d)
            val malas = red.filter { it.bloqueado || it.estado >= 400 || it.err != null }
            sb.append("\nRed: ").append(red.size).append(" peticiones, ").append(malas.size).append(" bloqueadas o con error\n")
            for (e in malas.takeLast(60)) {
                val estado = if (e.bloqueado) "BLOQUEADA" else if (e.err != null) "ERROR" else e.estado.toString()
                sb.append("  ").append(estado).append(" ").append(e.metodo).append(" ").append(DevFormato.cortar(DevFormato.sinParametros(e.url), 200)).append("\n")
            }
        }
        sb.append("\n(No incluye cookies, cabeceras, cuerpos ni parámetros de las URL. Los valores que parecen secretos en los mensajes de consola van como [redactado].)\n")
        return sb.toString()
    }

    private fun capturaTexto(): String =
        "Captura desde el inicio de cada página: " + (if (ajustes.devCaptura) "SÍ" else "no") + "  (toca para cambiar)"

    private fun alternarCaptura() {
        ajustes.devCaptura = !ajustes.devCaptura
        alCambiarCaptura()
        avisar(if (ajustes.devCaptura) "Activada: recarga la página para aplicarla" else "Desactivada")
        seleccionarTab(4)
    }

    private fun cargarInfo() {
        val tv = textoInfo ?: return
        val p = pestanaActual()
        consultaObj("rend()") { o ->
            if (o.has("sin")) {
                tv.text = "El agente no está en la página todavía."
                return@consultaObj
            }
            val sb = StringBuilder()
            sb.append("Página\n")
            sb.append("  ").append(o.optString("url")).append("\n")
            sb.append("  título: ").append(o.optString("titulo")).append("\n")
            sb.append("  estado: ").append(o.optString("estado")).append("  ·  ").append(o.optString("tipo")).append("  ·  ").append(o.optString("cod")).append("\n")
            sb.append("  contexto seguro (HTTPS): ").append(if (o.optBoolean("seguro")) "sí" else "no").append("\n")
            sb.append("\nPantalla\n")
            sb.append("  viewport ").append(o.optString("vp")).append("  ·  DPR ").append(o.optDouble("dpr")).append("  ·  zoom ").append(o.optDouble("zoom")).append("\n")
            sb.append("\nDocumento\n")
            sb.append("  nodos DOM: ").append(o.optInt("nodos")).append("  ·  scripts: ").append(o.optInt("scripts")).append("\n")
            val nav = o.optJSONObject("nav")
            if (nav != null) {
                sb.append("\nCarga (ms)\n")
                sb.append("  DNS ").append(nav.optInt("dns")).append("  ·  TCP ").append(nav.optInt("tcp")).append("  ·  TLS ").append(nav.optInt("tls")).append("\n")
                sb.append("  primer byte ").append(nav.optInt("ttfb")).append("  ·  descarga ").append(nav.optInt("desc")).append("\n")
                sb.append("  DOMContentLoaded ").append(nav.optInt("dcl")).append("  ·  carga completa ").append(nav.optInt("carga")).append("\n")
                if (o.has("fcp")) sb.append("  primer contenido visible ").append(o.optInt("fcp")).append("\n")
                if (o.has("lcp")) sb.append("  contenido principal (LCP) ").append(o.optInt("lcp")).append("\n")
                sb.append("  saltos de diseño (CLS) ").append(o.optDouble("cls")).append("\n")
                sb.append("  protocolo ").append(nav.optString("proto")).append("  ·  ").append(DevFormato.tam(nav.optLong("tam"))).append("\n")
            }
            val mem = o.optJSONObject("mem")
            if (mem != null) {
                sb.append("\nMemoria JS\n  usada ").append(DevFormato.tam(mem.optLong("u"))).append(" de ").append(DevFormato.tam(mem.optLong("t")))
                    .append(" (límite ").append(DevFormato.tam(mem.optLong("l"))).append(")\n")
            }
            sb.append("\nOptimo\n")
            if (p != null) sb.append("  peticiones bloqueadas en esta pestaña: ").append(p.bloqueados.get()).append("\n")
            val wv = try { WebView.getCurrentWebViewPackage()?.versionName } catch (e: Throwable) { null }
            if (wv != null) sb.append("  WebView (Chromium): ").append(wv).append("\n")
            sb.append("  captura desde el inicio: ").append(if (ajustes.devCaptura) "sí" else "no").append("\n")
            sb.append("\nAgente de usuario\n  ").append(o.optString("ua")).append("\n")
            informe = sb.toString()
            infoUrl = o.optString("url")
            infoTitulo = o.optString("titulo")
            tv.text = informe
        }
    }

    private fun verFuente(url: String?) {
        val web = webActual() ?: return
        val expr = if (url == null) "traer(location.href)" else "traer(" + AgenteDev.lit(url) + ")"
        avisar("Descargando…")
        consulta(expr) { id ->
            if (id == null || id.toIntOrNull() == null) {
                avisar("El agente no responde")
                return@consulta
            }
            agente.esperar(web, id) { r ->
                if (r.has("err")) {
                    avisar("No se pudo leer: " + r.optString("err") + " (puede bloquearlo CORS)")
                    return@esperar
                }
                val v = r.optJSONObject("v") ?: return@esperar
                val t = v.optString("t")
                val cab = "HTTP " + v.optInt("st") + " · " + v.optString("ct") + " · " + DevFormato.tam(v.optLong("tam")) +
                    (if (v.optLong("tam") > t.length) " (cortado a " + t.length + " caracteres)" else "") + "\n\n"
                empujar(
                    DevFormato.nombreUrl(url ?: (web.url ?: "")),
                    vistaTexto(cab + t),
                    listOf("Copiar" to { copiar(t); avisar("Copiado") })
                )
            }
        }
    }

    private fun listarFuentes() {
        consultaObj("fuentes()") { o ->
            if (o.has("sin")) return@consultaObj
            val urls = ArrayList<String>()
            val etiquetas = ArrayList<String>()
            val sc = o.optJSONArray("scripts")
            if (sc != null) for (k in 0 until sc.length()) { urls.add(sc.getString(k)); etiquetas.add("JS  " + DevFormato.nombreUrl(sc.getString(k))) }
            val es = o.optJSONArray("estilos")
            if (es != null) for (k in 0 until es.length()) { urls.add(es.getString(k)); etiquetas.add("CSS  " + DevFormato.nombreUrl(es.getString(k))) }
            if (urls.isEmpty()) {
                avisar("Sin scripts ni hojas externas (hay " + o.optInt("inlineScripts") + " scripts y " + o.optInt("inlineEstilos") + " estilos en línea)")
                return@consultaObj
            }
            val titulo = "Externos · en línea: " + o.optInt("inlineScripts") + " JS, " + o.optInt("inlineEstilos") + " CSS"
            elegirDeLista(titulo, etiquetas) { i -> verFuente(urls[i]) }
        }
    }
}
