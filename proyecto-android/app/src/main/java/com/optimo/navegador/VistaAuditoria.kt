package com.optimo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Lo que la vista de auditoría necesita del panel y de la app (así no depende de PanelDev). */
class GanchosAuditoria(
    /** Llama a un método del agente y entrega el JSON de respuesta (con «sin» si el agente no está en la página). */
    val consulta: (String, (JSONObject) -> Unit) -> Unit,
    /** Dominios que Optimo ha bloqueado en la página actual, con su número de peticiones. */
    val bloqueadosPorHost: () -> List<Pair<String, Int>>,
    val yaBloqueado: (String) -> Boolean,
    /** Abre el elemento en la pestaña Elementos. */
    val verNodo: (Int) -> Unit,
    /** Guarda una regla para ocultar el elemento en este sitio (pide confirmación). */
    val ocultarNodo: (Int) -> Unit,
    /** Bloquea un dominio en todas las páginas y devuelve el mensaje para el usuario. */
    val bloquear: (String) -> String,
    val lectura: () -> Unit,
    val copiar: (String) -> Unit,
    val avisar: (String) -> Unit,
    val dialogo: () -> AlertDialog.Builder,
    /** Entrega el informe en texto cada vez que se audita (para incluirlo en el diagnóstico). */
    val alInforme: (String) -> Unit,
    /** Pregunta si se permite un dominio que Optimo ha bloqueado en esta página. */
    val permitir: (String) -> Unit = {}
)

/**
 * Pestaña «Auditoría» (Óptimo Inspector). Toda la lógica de medición y de hallazgos vive en el agente
 * (assets/devtools/agente.js, función auditar); aquí solo se pinta el resultado y se ofrecen las acciones.
 */
class VistaAuditoria(
    private val act: Activity,
    private val paleta: Paleta,
    private val g: GanchosAuditoria
) {
    private val rojo = Color.parseColor("#E53935")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var cuerpo: LinearLayout? = null
    private var ultimo: JSONObject? = null
    private var verTodos = false

    /** Elementos que tiene sentido ocultar con una regla del sitio. */
    private val ocultables = setOf("salto", "imgpesada", "imggrande", "lejos")

    private val coloresTipo = mapOf(
        "js" to "#F9A825", "img" to "#42A5F5", "css" to "#AB47BC", "fuente" to "#8D6E63",
        "medio" to "#EC407A", "doc" to "#26A69A", "fetch" to "#78909C", "otro" to "#9E9E9E"
    )

    // ---------------------------------------------------------------- utilidades

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    private fun fondo(color: Int, radio: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radio.toFloat()
        return d
    }

    private fun texto(t: CharSequence, sp: Float, color: Int, negrita: Boolean = false, mono: Boolean = false): TextView {
        val v = TextView(act)
        v.text = t
        v.textSize = sp
        v.setTextColor(color)
        if (mono) v.typeface = Typeface.MONOSPACE
        if (negrita) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun boton(t: String, accion: () -> Unit): TextView {
        val v = texto(t, 12f, paleta.texto)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(10), dp(6), dp(10), dp(6))
        v.isClickable = true
        v.setOnClickListener { accion() }
        return v
    }

    private fun chip(t: String, accion: () -> Unit): TextView {
        val v = boton(t, accion)
        v.background = fondo(paleta.campo, dp(8))
        v.setPadding(dp(12), dp(6), dp(12), dp(6))
        return v
    }

    private fun lpm(w: Int, h: Int, peso: Float, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, peso)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun colorSev(s: Int): Int = when (s) {
        0 -> paleta.seguro
        1 -> paleta.advertencia
        2 -> rojo
        else -> paleta.texto
    }

    private fun etiquetaSev(s: Int): String = when (s) {
        2 -> "GRAVE"
        1 -> "MEJORABLE"
        else -> "AVISO"
    }

    private fun nombreCorto(url: String): String {
        val limpia = DevFormato.sinParametros(url)
        val n = limpia.trimEnd('/').substringAfterLast('/')
        return if (n.isEmpty()) DevFormato.hostUrl(url) else DevFormato.cortar(n, 40)
    }

    private fun tiempoOGuion(ms: Int): String = if (ms > 0) DevFormato.tiempo(ms) else "—"

    // ---------------------------------------------------------------- estructura

    fun construir(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.setBackgroundColor(paleta.barra)
        barra.addView(boton("Auditar de nuevo") { cargar() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        barra.addView(boton("Copiar informe") { copiarInforme() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        col.addView(barra, LinearLayout.LayoutParams(MATCH, WRAP))
        val sv = ScrollView(act)
        sv.setBackgroundColor(paleta.fondo)
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(8), dp(8), dp(8), dp(16))
        sv.addView(c, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        cuerpo = c
        val previo = ultimo
        if (previo != null) pintar(previo) else mostrarNota("Auditando…")
        return col
    }

    /** Olvida el resultado anterior (al cambiar de página o de pestaña del navegador). */
    fun olvidar() {
        ultimo = null
        verTodos = false
    }

    fun cargar() {
        g.consulta("auditar()") { o ->
            if (o.has("sin")) {
                mostrarNota("El agente aún no está en la página. Espera a que cargue y pulsa «Auditar de nuevo».")
            } else if (o.has("err")) {
                mostrarNota("No se pudo auditar: " + o.optString("err"))
            } else {
                pintar(o)
            }
        }
    }

    private fun mostrarNota(t: String) {
        val c = cuerpo ?: return
        c.removeAllViews()
        c.addView(texto(t, 12f, paleta.tenue))
    }

    private fun copiarInforme() {
        val o = ultimo
        if (o == null) {
            g.avisar("Todavía no hay auditoría")
            return
        }
        g.copiar(informe(o))
        g.avisar("Informe copiado")
    }

    // ---------------------------------------------------------------- pintado

    private fun pintar(o: JSONObject) {
        val c = cuerpo ?: return
        ultimo = o
        g.alInforme(informe(o))
        c.removeAllViews()
        val m = o.optJSONObject("m") ?: JSONObject()
        val hs = o.optJSONArray("h") ?: JSONArray()

        var graves = 0
        var mejorables = 0
        var avisos = 0
        for (i in 0 until hs.length()) {
            val s = hs.getJSONObject(i).optInt("sev")
            if (s >= 2) graves++ else if (s == 1) mejorables++ else avisos++
        }

        c.addView(texto(o.optString("host"), 14f, paleta.texto, true))
        val resumen = SpannableStringBuilder()
        resumen.append(graves.toString() + " graves")
        resumen.setSpan(ForegroundColorSpan(if (graves > 0) rojo else paleta.tenue), 0, resumen.length, 0)
        val ini = resumen.length
        resumen.append("  ·  " + mejorables + " mejorables  ·  " + avisos + " avisos")
        resumen.setSpan(ForegroundColorSpan(paleta.tenue), ini, resumen.length, 0)
        val tvResumen = texto(resumen, 12f, paleta.tenue)
        tvResumen.setPadding(0, dp(2), 0, dp(6))
        c.addView(tvResumen)

        for (n in notasDe(o, m)) {
            val t = texto("ⓘ " + n, 11f, paleta.tenue)
            t.setPadding(0, 0, 0, dp(4))
            c.addView(t)
        }

        c.addView(filaMetricas(m), LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(resumenCarga(m))
        pintarPeso(c, o.optJSONArray("tipos"))

        c.addView(seccion("Qué mejorar"))
        if (hs.length() == 0) {
            c.addView(texto("No se encontró nada importante en esta carga.", 12f, paleta.tenue))
        } else {
            val tope = if (verTodos) hs.length() else minOf(hs.length(), 12)
            for (i in 0 until tope) {
                c.addView(tarjetaHallazgo(hs.getJSONObject(i)), lpm(MATCH, WRAP, 0f, 0, 0, 0, 6))
            }
            if (hs.length() > tope) {
                c.addView(chip("Ver los " + (hs.length() - tope) + " restantes") {
                    verTodos = true
                    val u = ultimo
                    if (u != null) pintar(u)
                })
            }
        }

        if (graves + mejorables >= 3 || m.optLong("peso") >= 3L * 1024 * 1024) {
            val v = LinearLayout(act)
            v.orientation = LinearLayout.VERTICAL
            v.background = fondo(paleta.superficie, dp(8))
            v.setPadding(dp(10), dp(8), dp(10), dp(8))
            v.addView(texto("¿Solo quieres leer?", 13f, paleta.texto, true))
            v.addView(texto("El modo lectura deja el texto y quita casi todo lo anterior: scripts, terceros e imágenes pesadas.", 12f, paleta.tenue))
            v.addView(chip("Abrir en modo lectura") { g.lectura() }, lpm(WRAP, WRAP, 0f, 0, 6, 0, 0))
            c.addView(v, lpm(MATCH, WRAP, 0f, 0, 10, 0, 0))
        }

        pintarTerceros(c, o.optJSONArray("terceros"))
        pintarBloqueados(c)
        pintarRecursos(c, "Más pesados", o.optJSONArray("pesados"), true)
        pintarRecursos(c, "Más lentos (800 ms o más)", o.optJSONArray("lentos"), false)

        val pie = texto(
            "Medido en esta carga, en tu móvil y con tu red, sin simulaciones. Lo que Optimo bloquea no cuenta en tiempos ni en peso. " +
                "No se mide INP (necesita que interactúes con la página).",
            11f, paleta.tenue
        )
        pie.setPadding(0, dp(14), 0, 0)
        c.addView(pie)
    }

    private fun notasDe(o: JSONObject, m: JSONObject): List<String> {
        val r = ArrayList<String>()
        if (o.optInt("parcial") == 1) {
            r.add("El panel se abrió tarde en una página con muchos recursos: los primeros pueden faltar. Activa «captura desde el inicio» (pestaña Info) y recarga.")
        }
        val sop = o.optJSONObject("soporte")
        if (sop != null && sop.optInt("loaf") == 0) {
            r.add("Este WebView no indica qué script bloquea la página: solo se ve el tiempo total.")
        }
        if (m.optInt("carga") == 0) {
            r.add("La página aún no ha terminado de cargar: vuelve a auditar al acabar.")
        }
        if (m.optInt("opacos") > 0) {
            r.add("En " + m.optInt("opacos") + " peticiones de otros dominios el navegador no da el tamaño ni el desglose.")
        }
        return r
    }

    private fun seccion(titulo: String): View {
        val t = texto(titulo, 12f, paleta.acento, true)
        t.setPadding(dp(2), dp(14), dp(2), dp(6))
        return t
    }

    private fun tarjeta(nombre: String, valor: String, sev: Int): View {
        val v = LinearLayout(act)
        v.orientation = LinearLayout.VERTICAL
        v.background = fondo(paleta.superficie, dp(8))
        v.setPadding(dp(10), dp(8), dp(10), dp(8))
        v.addView(texto(valor, 16f, colorSev(sev), true))
        val etiqueta = nombre + when (sev) {
            0 -> " · bien"
            1 -> " · mejorable"
            2 -> " · mal"
            else -> ""
        }
        v.addView(texto(etiqueta, 11f, paleta.tenue))
        return v
    }

    private fun filaMetricas(m: JSONObject): View {
        val sem = m.optJSONObject("sem") ?: JSONObject()
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val f1 = LinearLayout(act)
        f1.orientation = LinearLayout.HORIZONTAL
        f1.addView(tarjeta("LCP", tiempoOGuion(m.optInt("lcp")), sem.optInt("lcp", -1)), lpm(0, WRAP, 1f, 0, 3, 3, 3))
        f1.addView(tarjeta("FCP", tiempoOGuion(m.optInt("fcp")), sem.optInt("fcp", -1)), lpm(0, WRAP, 1f, 3, 3, 3, 3))
        f1.addView(tarjeta("TTFB", tiempoOGuion(m.optInt("ttfb")), sem.optInt("ttfb", -1)), lpm(0, WRAP, 1f, 3, 3, 0, 3))
        col.addView(f1, LinearLayout.LayoutParams(MATCH, WRAP))

        val f2 = LinearLayout(act)
        f2.orientation = LinearLayout.HORIZONTAL
        val cls = String.format(Locale.US, "%.3f", m.optDouble("cls", 0.0))
        f2.addView(tarjeta("CLS", cls, sem.optInt("cls", -1)), lpm(0, WRAP, 1f, 0, 3, 3, 3))
        f2.addView(tarjeta("TBT", DevFormato.tiempo(m.optInt("tbt")), sem.optInt("tbt", -1)), lpm(0, WRAP, 1f, 3, 3, 3, 3))
        f2.addView(tarjeta("Carga", tiempoOGuion(m.optInt("carga")), -1), lpm(0, WRAP, 1f, 3, 3, 0, 3))
        col.addView(f2, LinearLayout.LayoutParams(MATCH, WRAP))
        return col
    }

    private fun resumenCarga(m: JSONObject): View {
        val t = texto(
            m.optInt("peticiones").toString() + " peticiones  ·  " + m.optInt("dominios") + " dominios (" + m.optInt("terceros") + " de terceros)  ·  " +
                DevFormato.tam(m.optLong("peso")) + "  ·  " + m.optInt("nodos") + " elementos",
            12f, paleta.tenue
        )
        t.setPadding(dp(2), dp(6), dp(2), 0)
        return t
    }

    private fun pintarPeso(c: LinearLayout, tipos: JSONArray?) {
        if (tipos == null || tipos.length() == 0) return
        var total = 0L
        for (i in 0 until tipos.length()) total += tipos.getJSONObject(i).optLong("tam")
        if (total <= 0L) return
        c.addView(seccion("Peso por tipo"))
        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        val leyenda = SpannableStringBuilder()
        for (i in 0 until tipos.length()) {
            val t = tipos.getJSONObject(i)
            val tam = t.optLong("tam")
            if (tam <= 0L) continue
            val nombre = t.optString("t")
            val color = Color.parseColor(coloresTipo[nombre] ?: "#9E9E9E")
            val trozo = View(act)
            trozo.setBackgroundColor(color)
            barra.addView(trozo, LinearLayout.LayoutParams(0, dp(10), tam.toFloat()))
            val ini = leyenda.length
            leyenda.append("● ")
            leyenda.setSpan(ForegroundColorSpan(color), ini, leyenda.length, 0)
            leyenda.append(nombre + " " + DevFormato.tam(tam) + " (" + t.optInt("n") + ")   ")
        }
        c.addView(barra, LinearLayout.LayoutParams(MATCH, dp(10)))
        val l = texto(leyenda, 11f, paleta.tenue)
        l.setPadding(0, dp(6), 0, 0)
        c.addView(l)
    }

    private fun tarjetaHallazgo(h: JSONObject): View {
        val sev = h.optInt("sev")
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.background = fondo(paleta.superficie, dp(8))
        val franja = View(act)
        franja.setBackgroundColor(colorSev(sev))
        fila.addView(franja, LinearLayout.LayoutParams(dp(4), MATCH))

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), dp(8), dp(10), dp(8))
        col.addView(texto(etiquetaSev(sev) + " · " + h.optString("cat"), 10f, colorSev(sev), true))
        col.addView(texto(h.optString("t"), 13f, paleta.texto, true))
        val d = h.optString("d")
        if (d.isNotEmpty()) col.addView(texto(d, 12f, paleta.tenue))

        val acciones = ArrayList<TextView>()
        val nodo = h.optInt("nodo")
        val host = h.optString("host")
        val id = h.optString("id")
        if (nodo > 0) acciones.add(chip("Ver en Elementos") { g.verNodo(nodo) })
        if (nodo > 0 && ocultables.contains(id)) acciones.add(chip("Ocultar siempre") { g.ocultarNodo(nodo) })
        if (host.isNotEmpty()) {
            if (g.yaBloqueado(host)) {
                acciones.add(chip("Bloqueado ✓") { g.avisar(host + " ya está bloqueado") })
            } else {
                val esCdn = h.optInt("cdn") == 1
                acciones.add(chip("Bloquear " + host) { confirmarBloqueo(host, esCdn) })
            }
        }
        if (acciones.isNotEmpty()) col.addView(filaChips(acciones))

        fila.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return fila
    }

    private fun filaChips(chips: List<TextView>): View {
        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        for (ch in chips) fila.addView(ch, lpm(WRAP, WRAP, 0f, 0, 6, 6, 0))
        hs.addView(fila, FrameLayout.LayoutParams(WRAP, WRAP))
        return hs
    }

    private fun confirmarBloqueo(host: String, cdn: Boolean) {
        val aviso = if (cdn) {
            "Es una red de distribución muy usada: es probable que rompa páginas."
        } else {
            "Puede romper páginas que lo usen (vídeos, fuentes, inicios de sesión…)."
        }
        g.dialogo()
            .setTitle("Bloquear " + host)
            .setMessage("Óptimo dejará de cargar todo lo que venga de " + host + " y sus subdominios, en todas las páginas.\n\n" + aviso + "\n\nPodrás quitarlo desde tus reglas de usuario. Recarga la página para ver el efecto.")
            .setPositiveButton("Bloquear") { _, _ ->
                g.avisar(g.bloquear(host))
                val u = ultimo
                if (u != null) pintar(u)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pintarTerceros(c: LinearLayout, arr: JSONArray?) {
        if (arr == null || arr.length() == 0) return
        c.addView(seccion("Dominios de terceros (toca uno para bloquearlo)"))
        val tope = minOf(arr.length(), 8)
        for (i in 0 until tope) {
            val t = arr.getJSONObject(i)
            val s = t.optString("s")
            val tam = t.optLong("tam")
            val bloqueado = g.yaBloqueado(s)
            val linea = s + "  ·  " + t.optInt("n") + " pet." +
                (if (tam > 0L) "  ·  " + DevFormato.tam(tam) else if (t.optInt("op") > 0) "  ·  peso desconocido" else "") +
                (if (bloqueado) "  ·  bloqueado" else "")
            val esCdn = t.optInt("cdn") == 1
            val fila = boton(linea) {
                if (bloqueado) g.avisar(s + " ya está bloqueado") else confirmarBloqueo(s, esCdn)
            }
            fila.gravity = Gravity.START
            fila.typeface = Typeface.MONOSPACE
            fila.textSize = 11f
            fila.maxLines = 1
            fila.ellipsize = TextUtils.TruncateAt.END
            c.addView(fila, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun pintarBloqueados(c: LinearLayout) {
        val lista = g.bloqueadosPorHost()
        c.addView(seccion("Lo que Optimo ya bloqueó en esta página"))
        if (lista.isEmpty()) {
            c.addView(texto("Nada: no hubo peticiones bloqueadas en esta página.", 12f, paleta.tenue))
            return
        }
        var total = 0
        for (p in lista) total += p.second
        c.addView(texto(total.toString() + " peticiones evitadas en " + lista.size + " dominios (toca uno para permitirlo)", 12f, paleta.texto))
        val tope = minOf(lista.size, 8)
        for (i in 0 until tope) {
            val p = lista[i]
            val fila = texto(p.first + "  ×" + p.second, 11f, paleta.tenue, false, true)
            fila.isClickable = true
            fila.setOnClickListener { g.permitir(p.first) }
            fila.maxLines = 1
            fila.ellipsize = TextUtils.TruncateAt.END
            fila.setPadding(dp(10), dp(2), 0, 0)
            c.addView(fila)
        }
    }

    private fun pintarRecursos(c: LinearLayout, titulo: String, arr: JSONArray?, porPeso: Boolean) {
        if (arr == null || arr.length() == 0) return
        if (porPeso && arr.getJSONObject(0).optLong("tam") <= 0L) return
        c.addView(seccion(titulo + " (toca para copiar la URL)"))
        val tope = minOf(arr.length(), 6)
        for (i in 0 until tope) {
            val r = arr.getJSONObject(i)
            val url = r.optString("u")
            val linea = nombreCorto(url) + "  ·  " + r.optString("t") + "  ·  " + DevFormato.tam(r.optLong("tam")) + "  ·  " + DevFormato.tiempo(r.optInt("ms")) +
                (if (r.optInt("ter") == 1) "  ·  tercero" else "")
            val fila = boton(linea) {
                g.copiar(DevFormato.sinParametros(url))
                g.avisar("URL copiada (sin parámetros)")
            }
            fila.gravity = Gravity.START
            fila.typeface = Typeface.MONOSPACE
            fila.textSize = 11f
            fila.maxLines = 1
            fila.ellipsize = TextUtils.TruncateAt.END
            c.addView(fila, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // ---------------------------------------------------------------- informe para compartir

    /** Texto para copiar: sin parámetros de URL y con los valores que parecen secretos tapados. */
    private fun informe(o: JSONObject): String {
        val m = o.optJSONObject("m") ?: JSONObject()
        val hs = o.optJSONArray("h") ?: JSONArray()
        val sb = StringBuilder()
        sb.append("Auditoría de Óptimo Inspector\n")
        sb.append("Página: ").append(DevFormato.sinParametros(o.optString("url"))).append("\n\n")
        sb.append("Métricas (esta carga, en este móvil)\n")
        sb.append("  contenido principal (LCP) ").append(tiempoOGuion(m.optInt("lcp"))).append("\n")
        sb.append("  primer contenido (FCP) ").append(tiempoOGuion(m.optInt("fcp"))).append("\n")
        sb.append("  primer byte (TTFB) ").append(tiempoOGuion(m.optInt("ttfb"))).append("\n")
        sb.append("  saltos de diseño (CLS) ").append(String.format(Locale.US, "%.3f", m.optDouble("cls", 0.0))).append("\n")
        sb.append("  bloqueo de scripts (TBT) ").append(DevFormato.tiempo(m.optInt("tbt"))).append("\n")
        sb.append("  carga completa ").append(tiempoOGuion(m.optInt("carga"))).append("\n")
        sb.append("  ").append(m.optInt("peticiones")).append(" peticiones, ").append(m.optInt("dominios")).append(" dominios (")
            .append(m.optInt("terceros")).append(" de terceros), ").append(DevFormato.tam(m.optLong("peso")))
            .append(", ").append(m.optInt("nodos")).append(" elementos\n\n")
        sb.append("Hallazgos (").append(hs.length()).append(")\n")
        for (i in 0 until hs.length()) {
            val h = hs.getJSONObject(i)
            sb.append("  [").append(etiquetaSev(h.optInt("sev"))).append("] ")
                .append(DevFormato.cortar(DevFormato.redactarTexto(h.optString("t")), 200)).append("\n")
            val d = h.optString("d")
            if (d.isNotEmpty()) sb.append("      ").append(DevFormato.cortar(DevFormato.redactarTexto(d), 300)).append("\n")
        }
        val lista = g.bloqueadosPorHost()
        if (lista.isNotEmpty()) {
            var total = 0
            for (p in lista) total += p.second
            sb.append("\nBloqueado por Optimo: ").append(total).append(" peticiones en ").append(lista.size).append(" dominios\n")
            for (p in lista.take(8)) sb.append("  ").append(p.first).append(" ×").append(p.second).append("\n")
        }
        sb.append("\n(Sin cookies, cabeceras, cuerpos ni parámetros de URL. Medido en una sola carga, sin simulación de red.)\n")
        return sb.toString()
    }
}
