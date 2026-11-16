package com.optimo.navegador

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Una línea de la consola: del agente (página) o nativa (el WebView: errores, red, avisos del navegador). */
class EntradaConsola(
    val ts: Long,
    /** log, info, warn, error, debug, in (lo escrito por el usuario) u out (el resultado). */
    val nivel: String,
    val texto: String,
    val origen: String?,
    val nativa: Boolean
)

/** Una petición de red tal como la ve el agente, o sintetizada con lo que solo sabe la app. */
class EntradaRed(val i: Long) {
    var ts = 0L
    var tipo = "otro"
    var metodo = "GET"
    var url = ""
    var estado = 0
    var ms = -1
    var tam = -1L
    var err: String? = null
    var pendiente = false
    var bloqueado = false
    var nota: String? = null
    var sintetica = false
}

class BloqueoNativo(val url: String, val metodo: String, val ts: Long)
class ErrorNativo(val url: String, val metodo: String, val estado: Int, val texto: String, val ts: Long)

/**
 * Datos de herramientas de desarrollo de una pestaña. Los registros nativos se escriben desde el WebView
 * (algunos desde hilos de red), por eso van con candado; lo del agente solo se toca en el hilo principal.
 */
class DatosDev {
    private val lock = Any()
    private val consola = ArrayList<EntradaConsola>()
    private val bloqueos = ArrayList<BloqueoNativo>()
    private val errores = ArrayList<ErrorNativo>()

    // Solo hilo principal.
    val agenteConsola = ArrayList<EntradaConsola>()
    var agenteUltimoI = 0L
    val agenteRed = LinkedHashMap<Long, EntradaRed>()
    var agenteUltimaV = 0L

    fun limpiar() {
        synchronized(lock) {
            consola.clear()
            bloqueos.clear()
            errores.clear()
        }
        agenteConsola.clear()
        agenteUltimoI = 0L
        agenteRed.clear()
        agenteUltimaV = 0L
    }

    fun consola(nivel: String, texto: String, origen: String?) {
        synchronized(lock) {
            consola.add(EntradaConsola(System.currentTimeMillis(), nivel, texto, origen, true))
            if (consola.size > MAX) consola.removeAt(0)
        }
    }

    fun bloqueo(url: String, metodo: String) {
        synchronized(lock) {
            bloqueos.add(BloqueoNativo(url, metodo, System.currentTimeMillis()))
            if (bloqueos.size > MAX) bloqueos.removeAt(0)
        }
    }

    fun errorHttp(url: String, metodo: String, estado: Int, texto: String) {
        synchronized(lock) {
            errores.add(ErrorNativo(url, metodo, estado, texto, System.currentTimeMillis()))
            if (errores.size > MAX) errores.removeAt(0)
        }
    }

    fun copiaConsola(): List<EntradaConsola> = synchronized(lock) { ArrayList(consola) }
    fun copiaBloqueos(): List<BloqueoNativo> = synchronized(lock) { ArrayList(bloqueos) }
    fun copiaErrores(): List<ErrorNativo> = synchronized(lock) { ArrayList(errores) }

    fun vaciarConsola() {
        synchronized(lock) { consola.clear() }
        agenteConsola.clear()
    }

    fun vaciarRed() {
        synchronized(lock) {
            bloqueos.clear()
            errores.clear()
        }
        agenteRed.clear()
    }

    companion object {
        const val MAX = 500
    }
}

/** Funciones puras de formato y de lectura de las respuestas del agente. */
object DevFormato {

    /** evaluateJavascript devuelve el texto como literal JSON entre comillas, o «null». */
    fun desenvolver(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else null
        } catch (e: Exception) {
            null
        }
    }

    fun objeto(s: String?): JSONObject? {
        if (s == null) return null
        return try { JSONObject(s) } catch (e: Exception) { null }
    }

    fun arreglo(s: String?): JSONArray? {
        if (s == null) return null
        return try { JSONArray(s) } catch (e: Exception) { null }
    }

    fun tam(b: Long): String = when {
        b < 0 -> "—"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    }

    fun tiempo(ms: Int): String = when {
        ms < 0 -> "—"
        ms < 1000 -> "$ms ms"
        else -> String.format(java.util.Locale.US, "%.2f s", ms / 1000.0)
    }

    fun nombreUrl(url: String): String {
        return try {
            val u = Uri.parse(url)
            val seg = u.lastPathSegment
            val q = u.encodedQuery
            val base = if (seg.isNullOrEmpty()) (u.host ?: url) else seg
            if (q.isNullOrEmpty()) base else base + "?" + (if (q.length > 24) q.substring(0, 24) + "…" else q)
        } catch (e: Exception) {
            url
        }
    }

    fun hostUrl(url: String): String = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }

    fun cortar(s: String, n: Int): String = if (s.length > n) s.substring(0, n) + "…" else s

    /** Reconstruye la petición como orden curl (solo las cabeceras que puso la página). */
    fun curl(metodo: String, url: String, cabeceras: Map<String, String>): String {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val sb = StringBuilder("curl ").append(q(url))
        if (metodo != "GET") sb.append(" -X ").append(metodo)
        for ((k, v) in cabeceras) sb.append(" -H ").append(q("$k: $v"))
        return sb.toString()
    }

    fun mapa(o: JSONObject?): LinkedHashMap<String, String> {
        val res = LinkedHashMap<String, String>()
        if (o == null) return res
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            res[k] = o.optString(k)
        }
        return res
    }
}
