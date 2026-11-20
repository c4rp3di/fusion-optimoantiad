package com.optimo.navegador

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** Una carga que falló: [estado] HTTP (0 si no hubo respuesta) y [error] si la petición falló antes de recibirla. */
class FalloRed(val url: String, val estado: Int, val error: String?)

/** Lo que el panel sabe de la página abierta, ya resumido para la vista sencilla. */
class DatosResumen(
    val host: String,
    /** Errores de la consola agrupados: mensaje y cuántas veces salió. */
    val errores: List<Pair<String, Int>>,
    val fallos: List<FalloRed>,
    val peticiones: Int,
    val bloqueadas: Int,
    val hostsBloqueados: List<Pair<String, Int>>
)

class GanchosResumen(
    val datos: () -> DatosResumen?,
    /** Llama a un método del agente y entrega el JSON de respuesta (con «sin» si el agente no está en la página). */
    val consulta: (String, (JSONObject) -> Unit) -> Unit,
    /** Salta a otra pestaña de DevTools (0 Consola, 2 Red, 5 Auditoría…). */
    val irA: (Int) -> Unit,
    val pausarBloqueo: () -> Unit,
    /** Abre la lista de dominios bloqueados en la página para permitir alguno. */
    val revisarBloqueos: () -> Unit,
    /** ¿Está activo el bloqueo de anuncios en esta página? */
    val bloqueoActivo: () -> Boolean,
    val capturaDesdeInicio: () -> Boolean,
    val activarCaptura: () -> Unit,
    val esSencillo: () -> Boolean,
    val cambiarModo: () -> Unit,
    val copiar: (String) -> Unit,
    val avisar: (String) -> Unit,
    /** Informe completo para compartir (sin cookies, cabeceras ni cuerpos). */
    val diagnostico: () -> String
)

/**
 * Pestaña «Resumen» (modo sencillo de DevTools): dice en lenguaje llano qué le pasa a la página abierta, sin tecnicismos.
 * Reúne lo que ya captura el panel (consola, red, bloqueos de Optimo) y las métricas de la auditoría; no mide nada por su cuenta.
 */
class VistaResumen(
    private val act: Activity,
    private val paleta: Paleta,
    private val g: GanchosResumen
) {
    private val rojo = Color.parseColor("#E53935")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var cuerpo: LinearLayout? = null
    private var auditoria: JSONObject? = null
    private var auditando = false

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
        else -> paleta.tenue
    }

    // ---------------------------------------------------------------- estructura

    fun construir(): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val barra = LinearLayout(act)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.setBackgroundColor(paleta.barra)
        barra.addView(boton("Analizar de nuevo") { cargar() }, LinearLayout.LayoutParams(0, dp(38), 1.2f))
        barra.addView(boton("Copiar informe") { g.copiar(g.diagnostico()); g.avisar("Informe copiado") }, LinearLayout.LayoutParams(0, dp(38), 1.1f))
        barra.addView(boton(if (g.esSencillo()) "Modo: sencillo ⇄" else "Modo: profesional ⇄") { g.cambiarModo() }, LinearLayout.LayoutParams(0, dp(38), 1.3f))
        col.addView(barra, LinearLayout.LayoutParams(MATCH, WRAP))
        val sv = ScrollView(act)
        sv.setBackgroundColor(paleta.fondo)
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(8), dp(8), dp(8), dp(16))
        sv.addView(c, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        cuerpo = c
        pintar()
        return col
    }

    /** Olvida la auditoría anterior (al cambiar de página o de pestaña del navegador). */
    fun olvidar() {
        auditoria = null
        auditando = false
        firma = ""
    }

    private var firma = ""

    private fun firmaActual(): String {
        val d = g.datos() ?: return "sin"
        return d.host + "|" + d.errores.sumOf { it.second } + "|" + d.fallos.size + "|" + d.bloqueadas + "|" + d.peticiones
    }

    /** Se llama mientras la pestaña está abierta: solo vuelve a pintar si ha cambiado algo, para no parpadear ni perder el desplazamiento. */
    fun refrescar() {
        if (cuerpo == null) return
        if (firmaActual() != firma) pintar()
    }

    /** Pinta ya lo que se sabe y pide al agente las métricas de carga para completarlo. */
    fun cargar() {
        pintar()
        if (auditando) return
        auditando = true
        g.consulta("auditar()") { o ->
            auditando = false
            auditoria = if (o.has("sin") || o.has("err")) null else o
            pintar()
        }
    }

    // ---------------------------------------------------------------- pintado

    private fun pintar() {
        val c = cuerpo ?: return
        c.removeAllViews()
        firma = firmaActual()
        val d = g.datos()
        if (d == null) {
            c.addView(texto("Abre una página para analizarla.", 12f, paleta.tenue))
            return
        }
        val m = auditoria?.optJSONObject("m")
        val hs = auditoria?.optJSONArray("h")
        var graves = 0
        if (hs != null) {
            for (i in 0 until hs.length()) if (hs.getJSONObject(i).optInt("sev") >= 2) graves++
        }
        val totalErrores = d.errores.sumOf { it.second }
        val carga = m?.optInt("carga") ?: 0

        // Veredicto
        val problemas = (if (totalErrores > 0) 1 else 0) + (if (d.fallos.isNotEmpty()) 1 else 0) + (if (carga > 5000) 1 else 0) + (if (graves > 0) 1 else 0)
        val sev = if (graves > 0 || totalErrores >= 10 || d.fallos.size >= 5) 2 else if (problemas > 0) 1 else 0
        c.addView(texto(d.host, 14f, paleta.texto, true))
        val veredicto = when (sev) {
            0 -> "Todo en orden: no se ve nada raro en esta página."
            1 -> if (problemas == 1) "Hay 1 cosa que revisar." else "Hay $problemas cosas que revisar."
            else -> "Hay problemas importantes."
        }
        val tv = texto(veredicto, 13f, colorSev(sev), true)
        tv.setPadding(0, dp(2), 0, dp(8))
        c.addView(tv)

        // Cuatro cifras
        val f1 = LinearLayout(act)
        f1.orientation = LinearLayout.HORIZONTAL
        f1.addView(cifra(totalErrores.toString(), "errores en la página", if (totalErrores == 0) 0 else if (totalErrores >= 10) 2 else 1), lpm(0, WRAP, 1f, 0, 3, 3, 3))
        f1.addView(cifra(d.fallos.size.toString(), "archivos que no cargaron", if (d.fallos.isEmpty()) 0 else if (d.fallos.size >= 5) 2 else 1), lpm(0, WRAP, 1f, 3, 3, 0, 3))
        c.addView(f1, LinearLayout.LayoutParams(MATCH, WRAP))
        val f2 = LinearLayout(act)
        f2.orientation = LinearLayout.HORIZONTAL
        f2.addView(cifra(d.bloqueadas.toString(), "bloqueadas por Óptimo", -1), lpm(0, WRAP, 1f, 0, 3, 3, 3))
        val tiempo = if (carga > 0) String.format(Locale.getDefault(), "%.1f s", carga / 1000.0) else "…"
        val semT = if (carga == 0) -1 else if (carga < 2500) 0 else if (carga < 5000) 1 else 2
        f2.addView(cifra(tiempo, "tiempo de carga", semT), lpm(0, WRAP, 1f, 3, 3, 0, 3))
        c.addView(f2, LinearLayout.LayoutParams(MATCH, WRAP))

        val notas = ArrayList<String>()
        if (!g.capturaDesdeInicio()) {
            notas.add("Solo cuenta lo ocurrido desde que abriste este panel. Para ver también el arranque de la página, activa la captura desde el inicio y recarga.")
        }
        if (auditoria == null && !auditando) {
            notas.add("Aún no hay medidas de tiempo: espera a que la página termine de cargar y pulsa «Analizar de nuevo».")
        }
        for (n in notas) {
            val t = texto("ⓘ $n", 11f, paleta.tenue)
            t.setPadding(0, dp(6), 0, 0)
            c.addView(t)
        }
        if (!g.capturaDesdeInicio()) c.addView(chip("Activar captura desde el inicio") { g.activarCaptura() }, lpm(WRAP, WRAP, 0f, 0, 6, 0, 0))

        // Qué pasa
        c.addView(seccion("Qué pasa"))
        var tarjetas = 0
        if (totalErrores > 0) {
            val det = StringBuilder("Suelen ser fallos del propio sitio y muchas veces no se nota nada. Si algo no funciona, pueden ser la causa.")
            for (e in d.errores.take(3)) det.append("\n• ").append(DevFormato.cortar(DevFormato.redactarTexto(e.first), 130)).append(if (e.second > 1) "  (×" + e.second + ")" else "")
            c.addView(tarjeta(if (totalErrores >= 10) 2 else 1, "La página tiene " + totalErrores + (if (totalErrores == 1) " error interno" else " errores internos"), det.toString(), listOf(chip("Ver los errores") { g.irA(0) })), lpm(MATCH, WRAP, 0f, 0, 0, 0, 6))
            tarjetas++
        }
        if (d.fallos.isNotEmpty()) {
            val det = StringBuilder()
            for (f in d.fallos.take(4)) {
                det.append("• ").append(motivo(f)).append(" · ").append(nombreCorto(f.url)).append("\n")
            }
            if (d.fallos.size > 4) det.append("… y " + (d.fallos.size - 4) + " más")
            c.addView(tarjeta(if (d.fallos.size >= 5) 2 else 1, d.fallos.size.toString() + (if (d.fallos.size == 1) " archivo no se pudo cargar" else " archivos no se pudieron cargar"), det.toString().trim(), listOf(chip("Ver la lista") { g.irA(2) })), lpm(MATCH, WRAP, 0f, 0, 0, 0, 6))
            tarjetas++
        }
        if (d.bloqueadas > 0) {
            val det = StringBuilder()
            for (b in d.hostsBloqueados.take(4)) det.append("• ").append(b.first).append(" (").append(b.second).append(")\n")
            if (d.hostsBloqueados.size > 4) det.append("… y " + (d.hostsBloqueados.size - 4) + " sitios más\n")
            val acciones = ArrayList<TextView>()
            acciones.add(chip("Revisar bloqueos") { g.revisarBloqueos() })
            val algoFalla = totalErrores > 0 || d.fallos.isNotEmpty()
            if (algoFalla && g.bloqueoActivo()) {
                det.append("Si la página no funciona bien, puede ser por este bloqueo.")
                acciones.add(chip("Pausar el bloqueo en este sitio") { g.pausarBloqueo() })
            }
            c.addView(tarjeta(0, "Óptimo bloqueó " + d.bloqueadas + (if (d.bloqueadas == 1) " petición" else " peticiones") + " (anuncios y rastreadores)", det.toString().trim(), acciones), lpm(MATCH, WRAP, 0f, 0, 0, 0, 6))
            tarjetas++
        }
        if (m != null && carga > 5000) {
            val det = "Tardó " + String.format(Locale.getDefault(), "%.1f", carga / 1000.0) + " s en terminar de cargar: " + m.optInt("peticiones") + " peticiones, " +
                DevFormato.tam(m.optLong("peso")) + " en total, " + m.optInt("terceros") + " sitios de terceros. El modo lectura deja solo el texto."
            c.addView(tarjeta(1, "La página es lenta", det, emptyList()), lpm(MATCH, WRAP, 0f, 0, 0, 0, 6))
            tarjetas++
        }
        if (hs != null) {
            var mostrados = 0
            for (i in 0 until hs.length()) {
                val h = hs.getJSONObject(i)
                if (h.optInt("sev") < 1 || mostrados >= 3) continue
                c.addView(tarjeta(h.optInt("sev"), h.optString("t"), h.optString("d"), emptyList()), lpm(MATCH, WRAP, 0f, 0, 0, 0, 6))
                mostrados++
                tarjetas++
            }
            if (hs.length() > mostrados) c.addView(chip("Ver la auditoría completa") { g.irA(5) }, lpm(WRAP, WRAP, 0f, 0, 0, 0, 6))
        }
        if (tarjetas == 0) {
            c.addView(texto("No se encontró nada que revisar con lo que se ha visto hasta ahora.", 12f, paleta.tenue))
        }

        val pie = texto(
            "Medido en este móvil, sin simulaciones. Los detalles técnicos (elementos, estilos, almacenamiento, auditoría) están en el modo profesional.",
            11f, paleta.tenue
        )
        pie.setPadding(0, dp(14), 0, dp(6))
        c.addView(pie)
        c.addView(chip(if (g.esSencillo()) "Ver detalles técnicos (modo profesional)" else "Pasar al modo sencillo") { g.cambiarModo() })
    }

    private fun seccion(t: String): View {
        val v = texto(t, 12f, paleta.acento, true)
        v.setPadding(dp(2), dp(14), dp(2), dp(6))
        return v
    }

    private fun cifra(valor: String, nombre: String, sev: Int): View {
        val v = LinearLayout(act)
        v.orientation = LinearLayout.VERTICAL
        v.background = fondo(paleta.superficie, dp(8))
        v.setPadding(dp(10), dp(8), dp(10), dp(8))
        v.addView(texto(valor, 18f, colorSev(sev), true))
        v.addView(texto(nombre, 11f, paleta.tenue))
        return v
    }

    private fun tarjeta(sev: Int, titulo: String, detalle: String, acciones: List<TextView>): View {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.background = fondo(paleta.superficie, dp(8))
        val franja = View(act)
        franja.setBackgroundColor(colorSev(sev))
        fila.addView(franja, LinearLayout.LayoutParams(dp(4), MATCH))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), dp(8), dp(10), dp(8))
        col.addView(texto(titulo, 13f, paleta.texto, true))
        if (detalle.isNotEmpty()) col.addView(texto(detalle, 12f, paleta.tenue))
        if (acciones.isNotEmpty()) {
            val hs = HorizontalScrollView(act)
            hs.isHorizontalScrollBarEnabled = false
            val f = LinearLayout(act)
            f.orientation = LinearLayout.HORIZONTAL
            for (a in acciones) f.addView(a, lpm(WRAP, WRAP, 0f, 0, 6, 6, 0))
            hs.addView(f, FrameLayout.LayoutParams(WRAP, WRAP))
            col.addView(hs)
        }
        fila.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return fila
    }

    private fun nombreCorto(url: String): String {
        val limpia = DevFormato.sinParametros(url)
        val n = limpia.trimEnd('/').substringAfterLast('/')
        val host = DevFormato.hostUrl(url)
        return if (n.isEmpty() || n == host) host else DevFormato.cortar(n, 36) + " (" + host + ")"
    }

    /** Explica un fallo de carga con palabras de uso diario. */
    private fun motivo(f: FalloRed): String = when {
        f.estado == 404 || f.estado == 410 -> "no existe (" + f.estado + ")"
        f.estado == 401 || f.estado == 403 -> "acceso denegado (" + f.estado + ")"
        f.estado == 429 -> "demasiadas peticiones (429)"
        f.estado in 500..599 -> "fallo del servidor (" + f.estado + ")"
        f.estado >= 400 -> "error " + f.estado
        else -> "sin respuesta"
    }
}
