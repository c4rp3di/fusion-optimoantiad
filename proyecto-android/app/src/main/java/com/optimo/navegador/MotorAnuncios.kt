package com.optimo.navegador

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.WeakHashMap

/**
 * Reglas de las técnicas (portadas de AntiAds): patrones de red y técnicas de página por sitio.
 * Se leen de assets/anuncios/reglas.json; si existe filesDir/reglas_anuncios.json y es válido, manda ese.
 */
class ReglasAnuncios(
    val vast: List<String>,
    val stubs: List<Pair<String, String>>,
    val juguete: List<String>,
    val permitir: List<String>,
    val sitios: List<Pair<String, Set<String>>>
) {
    /** Técnicas de página que aplican a un solo grupo: «*» o un sitio concreto. */
    fun tecnicasDe(host: String): Set<String> {
        val res = HashSet<String>()
        for ((h, t) in sitios) if (h == host) res.addAll(t)
        return res
    }

    /** Las de «*» más las del sitio que mejor encaje por sufijo de dominio. */
    fun tecnicasPara(host: String): Set<String> {
        val res = HashSet<String>(tecnicasDe("*"))
        val mejor = sitios.filter { it.first != "*" && (host == it.first || host.endsWith("." + it.first)) }
            .maxByOrNull { it.first.length }
        if (mejor != null) res.addAll(mejor.second)
        return res
    }

    companion object {
        fun parse(texto: String): ReglasAnuncios {
            val j = JSONObject(texto)
            fun lista(clave: String): List<String> {
                val res = ArrayList<String>()
                val a = j.optJSONArray(clave) ?: return res
                for (i in 0 until a.length()) res.add(a.getString(i).lowercase())
                return res
            }
            val stubs = ArrayList<Pair<String, String>>()
            j.optJSONArray("stubs")?.let {
                for (i in 0 until it.length()) {
                    val o = it.getJSONObject(i)
                    stubs.add(Pair(o.getString("patron").lowercase(), o.getString("stub")))
                }
            }
            val sitios = ArrayList<Pair<String, Set<String>>>()
            j.optJSONArray("sitios")?.let {
                for (i in 0 until it.length()) {
                    val o = it.getJSONObject(i)
                    val t = HashSet<String>()
                    val a = o.optJSONArray("tecnicas")
                    if (a != null) for (k in 0 until a.length()) t.add(a.getString(k))
                    sitios.add(Pair(o.getString("host").lowercase(), t))
                }
            }
            return ReglasAnuncios(lista("vast"), stubs, lista("juguete"), lista("permitir"), sitios)
        }
    }
}

class TecnicaAnuncios(val id: String, val nombre: String)

/**
 * Técnicas contra anuncios de navegación sobre el WebView, sin VPN ni certificados:
 *  - Red (shouldInterceptRequest): bloqueo con CORS, VAST/VMAP vacíos, scripts sustitutos (GPT, IMA, AdSense)
 *    y «anuncio de juguete» (MP4 válido de 0,3 s con soporte de Range).
 *  - Página (script de inicio de documento): borrar datos de anuncios de YouTube, saltador y CSS de huecos.
 * Las técnicas de página se instalan con androidx.webkit (DOCUMENT_START_SCRIPT); si el WebView del sistema
 * no lo soporta, se ejecutan en onPageStarted (más tarde, menos eficaz).
 */
class MotorAnuncios(contexto: Context, private val ajustes: Ajustes) {

    private val c = contexto.applicationContext

    private val reglas: ReglasAnuncios by lazy {
        val f = File(c.filesDir, "reglas_anuncios.json")
        var r: ReglasAnuncios? = null
        if (f.exists()) {
            try { r = ReglasAnuncios.parse(f.readText()) } catch (e: Exception) { r = null }
        }
        r ?: ReglasAnuncios.parse(leerTexto("reglas.json"))
    }

    private val juguete: ByteArray? by lazy {
        try { c.assets.open("anuncios/vacio.mp4").use { it.readBytes() } } catch (e: Exception) { null }
    }

    private val textos = HashMap<String, String>()

    private fun leerTexto(nombre: String): String = synchronized(textos) {
        textos.getOrPut(nombre) {
            try { c.assets.open("anuncios/$nombre").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
        }
    }

    private val manejadores = WeakHashMap<WebView, MutableList<ScriptHandler>>()
    private val soportaInicio: Boolean by lazy {
        try { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } catch (e: Throwable) { false }
    }

    // ---------------------------------------------------------------- red

    /** Respuesta inventada o bloqueo para una petición de subrecurso; null si se deja pasar. */
    fun interceptar(request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val uri = request.url
        val host = (uri.host ?: return null).lowercase()
        val ruta = (uri.encodedPath ?: "").lowercase()
        val consulta = uri.encodedQuery
        val url = host + ruta + (if (consulta != null) "?" + consulta.lowercase() else "")
        val r = reglas
        val listaNegra = AdBlock.bloquea(host)

        // 1) Anuncio de juguete: .mp4 de dominios de la lista, o URL que encaja con un patrón de «juguete».
        val patronJuguete = r.juguete.firstOrNull { url.contains(it) }
        if (ajustes.tecnica("juguete") && ((ruta.endsWith(".mp4") && listaNegra) || patronJuguete != null)) {
            val resp = respuestaJuguete(request)
            if (resp != null) return resp
        }
        // 2) Scripts sustitutos.
        if (ajustes.tecnica("stubs")) {
            for ((patron, stub) in r.stubs) {
                if (!url.contains(patron)) continue
                val js = when (stub) {
                    "gpt" -> leerTexto("gpt.js")
                    "ima" -> leerTexto("ima.js")
                    "adsbygoogle" -> leerTexto("adsbygoogle.js")
                    else -> ""
                }
                if (js.isEmpty()) continue
                return conCors(request, "application/javascript", js.toByteArray(Charsets.UTF_8))
            }
        }
        // 3) VAST / VMAP vacíos.
        if (ajustes.tecnica("vast") && r.vast.any { url.contains(it) }) {
            val vmap = url.contains("vmap")
            return conCors(request, "application/xml", (if (vmap) VMAP_VACIO else VAST_VACIO).toByteArray(Charsets.UTF_8))
        }
        // 4) Lista «permitir»: se deja pasar (p. ej. el script que detecta bloqueadores y rompería la web).
        if (r.permitir.any { url.contains(it) }) return null
        // 5) Bloqueo clásico por dominio y, opcionalmente, por rutas típicas de anuncios; respuesta vacía con CORS.
        if (listaNegra || (ajustes.tecnica("rutas") && RUTAS.any { ruta.contains(it) })) {
            val mime = if (ruta.endsWith(".js")) "application/javascript" else "text/plain"
            return conCors(request, mime, ByteArray(0))
        }
        return null
    }

    private fun cabecera(request: WebResourceRequest, nombre: String): String? {
        val h = request.requestHeaders ?: return null
        for ((k, v) in h) if (k.equals(nombre, ignoreCase = true)) return v
        return null
    }

    private fun razon(estado: Int): String = when (estado) {
        206 -> "Partial Content"
        416 -> "Range Not Satisfiable"
        else -> "OK"
    }

    /** Respuesta con cabeceras CORS: sin ellas el emisor la toma por fallo y reintenta en bucle. */
    private fun conCors(
        request: WebResourceRequest, mime: String, datos: ByteArray,
        estado: Int = 200, extra: Map<String, String> = emptyMap()
    ): WebResourceResponse {
        val h = HashMap<String, String>()
        val origen = cabecera(request, "Origin")
        h["Access-Control-Allow-Origin"] = origen ?: "*"
        if (origen != null) {
            h["Access-Control-Allow-Credentials"] = "true"
            h["Vary"] = "Origin"
        }
        h["Access-Control-Allow-Methods"] = "GET, POST, HEAD, OPTIONS"
        h["Access-Control-Allow-Headers"] = cabecera(request, "Access-Control-Request-Headers") ?: "*"
        h["Cache-Control"] = "no-store"
        h["Content-Length"] = datos.size.toString()
        h.putAll(extra)
        val codificacion = if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("xml")) "utf-8" else null
        return WebResourceResponse(mime, codificacion, estado, razon(estado), h, ByteArrayInputStream(datos))
    }

    /** MP4 válido de 0,3 s. Un <video> pide «bytes=0-» y espera un 206, así que se atiende Range (206/416). */
    private fun respuestaJuguete(request: WebResourceRequest): WebResourceResponse? {
        val v = juguete ?: return null
        val n = v.size.toLong()
        val extra = HashMap<String, String>()
        extra["Accept-Ranges"] = "bytes"
        val rango = cabecera(request, "Range")
        val m = if (rango != null) Regex("bytes=(\\d*)-(\\d*)").find(rango) else null
        if (m == null || (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty())) {
            return conCors(request, "video/mp4", v, 200, extra)
        }
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        val desde: Long
        val hasta: Long
        if (a == null) {
            val k = (b ?: 0L).coerceAtMost(n)
            desde = n - k
            hasta = n - 1L
        } else {
            desde = a
            hasta = if (b == null) n - 1L else minOf(b, n - 1L)
        }
        if (desde >= n || desde > hasta) {
            extra["Content-Range"] = "bytes */$n"
            return conCors(request, "video/mp4", ByteArray(0), 416, extra)
        }
        extra["Content-Range"] = "bytes $desde-$hasta/$n"
        return conCors(request, "video/mp4", v.copyOfRange(desde.toInt(), hasta.toInt() + 1), 206, extra)
    }

    // ---------------------------------------------------------------- página

    private fun esYoutube(h: String): Boolean =
        h == "youtube.com" || h.endsWith(".youtube.com") || h.endsWith("youtube-nocookie.com")

    /** Script combinado de las técnicas de página [tecnicas] para el sitio [sitio] («*» o un dominio); null si no hay. */
    private fun guion(tecnicas: Set<String>, sitio: String): String? {
        val sb = StringBuilder()
        if (tecnicas.contains("yt") && ajustes.tecnica("yt")) sb.append(envolver(leerTexto("yt_prune.js")))
        if (tecnicas.contains("skip") && ajustes.tecnica("skip")) {
            sb.append(envolver(leerTexto(if (esYoutube(sitio)) "skip_yt.js" else "skip_generico.js")))
        }
        if (tecnicas.contains("css") && ajustes.tecnica("css")) {
            val css = CSS_GENERICO + (if (tecnicas.contains("yt")) "\n" + leerTexto("yt.css").trim() else "")
            sb.append(envolver(CSS_JS.replace("__CSS__", JSONObject.quote(css))))
        }
        if (sb.isEmpty()) return null
        // El indicador evita ejecutar dos veces lo mismo cuando coinciden el script de «*» y el del sitio.
        return "(function(){ if(window.__optimoAds) return; window.__optimoAds=1;\n$sb})();"
    }

    private fun envolver(js: String): String = if (js.isBlank()) "" else "try{\n$js\n}catch(e){}\n"

    private fun reglasDeOrigen(sitio: String): Set<String> =
        if (sitio == "*") setOf("*")
        else setOf("https://$sitio", "https://*.$sitio", "http://$sitio", "http://*.$sitio")

    /** (Re)instala los scripts de inicio en [web]; llamar al crear la pestaña y al cambiar ajustes. */
    fun instalar(web: WebView) {
        manejadores.remove(web)?.forEach { try { it.remove() } catch (e: Exception) { } }
        if (!ajustes.bloquearAnuncios || !soportaInicio) return
        val lista = ArrayList<ScriptHandler>()
        // Primero los sitios concretos (llevan la unión de «*» y lo suyo) y al final «*»: el indicador evita duplicados.
        val sitios = reglas.sitios.map { it.first }.filter { it != "*" }.distinct()
        for (s in sitios) {
            val js = guion(reglas.tecnicasPara(s), s) ?: continue
            try {
                lista.add(WebViewCompat.addDocumentStartJavaScript(web, js, reglasDeOrigen(s)))
            } catch (e: Exception) { }
        }
        val global = guion(reglas.tecnicasDe("*"), "*")
        if (global != null) {
            try {
                lista.add(WebViewCompat.addDocumentStartJavaScript(web, global, reglasDeOrigen("*")))
            } catch (e: Exception) { }
        }
        manejadores[web] = lista
    }

    /** Respaldo si el WebView no soporta scripts de inicio: se ejecuta al empezar la página. */
    fun alEmpezar(web: WebView, url: String?) {
        if (soportaInicio || !ajustes.bloquearAnuncios || url == null) return
        val host = try { android.net.Uri.parse(url).host?.lowercase() } catch (e: Exception) { null } ?: return
        val js = guion(reglas.tecnicasPara(host), host) ?: return
        web.evaluateJavascript(js, null)
    }

    companion object {
        val TECNICAS = listOf(
            TecnicaAnuncios("rutas", "Bloqueo por rutas de anuncios (/pagead/, /adserver/…)"),
            TecnicaAnuncios("vast", "Anuncios de vídeo vacíos (VAST)"),
            TecnicaAnuncios("juguete", "Anuncio de juguete (vídeo válido de 0,3 s)"),
            TecnicaAnuncios("stubs", "Scripts sustitutos (GPT, IMA, AdSense)"),
            TecnicaAnuncios("yt", "YouTube: quitar datos de anuncios"),
            TecnicaAnuncios("skip", "Saltar anuncios (YouTube y webs configuradas)"),
            TecnicaAnuncios("css", "Ocultar huecos de anuncios (CSS)")
        )

        const val VAST_VACIO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"3.0\"></VAST>"
        const val VMAP_VACIO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\"></vmap:VMAP>"

        /** Rutas típicas de anuncios en dominios que mezclan contenido y publicidad. Conservadora a propósito. */
        private val RUTAS = listOf("/pagead/", "/adserver/", "/ads/banner", "/doubleclick/", "/adsbygoogle.js", "/prebid")

        private val SELECTORES = listOf(
            "ins.adsbygoogle", "[id^=\"google_ads_\"]", "[id^=\"div-gpt-ad\"]", "[class*=\"adsbygoogle\"]",
            "iframe[src*=\"doubleclick.net\"]", "iframe[src*=\"googlesyndication.com\"]", "iframe[src*=\"adservice.\"]",
            "[data-ad-slot]", "[data-ad-unit]", "[aria-label=\"Advertisement\"]", "[aria-label=\"Publicidad\"]",
            ".ad-slot", ".ad-container", ".ad-banner", ".advert", ".advertisement", ".publicidad", ".sponsored-ad",
            "#ad-banner", "#ads", "#adContainer"
        )

        val CSS_GENERICO: String = SELECTORES.joinToString(",") +
            "{display:none!important;height:0!important;min-height:0!important;margin:0!important;padding:0!important}"

        /** Inserta una hoja de estilo en cuanto exista <head> o <html> (al inicio del documento aún pueden no existir). */
        private const val CSS_JS = """
(function(){
  var s=document.createElement('style'); s.textContent=__CSS__;
  function ins(){ var r=document.head||document.documentElement; if(r){ r.appendChild(s); return true; } return false; }
  if(!ins()){ var o=new MutationObserver(function(){ if(ins()) o.disconnect(); }); o.observe(document,{childList:true,subtree:true}); }
})();
"""
    }
}
