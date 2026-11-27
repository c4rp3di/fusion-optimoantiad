package com.optimo.navegador

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Ajustes de un rastreo. [profMax]: saltos desde la página de partida (0 = sin límite: solo frenan los topes). */
class ConfigRastreo(
    val inicio: String,
    val profMax: Int,
    val maxPaginas: Int,
    val maxMs: Long,
    val pausaMs: Long,
    val otrosSitios: Boolean,
    val cookies: Boolean,
    val robots: Boolean,
    val ua: String,
    val bloquear: Boolean,
    val tiposMascara: Int,
    val propio: FiltroPropio?,
    /** Si no está vacío, en el sitio de partida solo se siguen páginas cuya ruta empiece así («esta sección»). */
    val prefijoRuta: String = "",
    /** Solo se lee la página de partida; no se sigue ningún enlace. */
    val soloPagina: Boolean = false
)

class ProgresoRastreo(
    val paginas: Int,
    val enlaces: Int,
    val nivel: Int,
    val enCola: Int,
    val actual: String,
    /** Enlaces encontrados por tipo (índice = [TipoEnlace.ordinal]). */
    val porTipo: IntArray = IntArray(0),
    /** Enlaces ya vistos en otra página (no se cuentan dos veces). */
    val repetidos: Int = 0,
    /** Los últimos enlaces nuevos, del más antiguo al más reciente. */
    val recientes: List<EnlaceEncontrado> = emptyList(),
    val transcurridoMs: Long = 0L
)

class ResultadoRastreo(val enlaces: List<EnlaceEncontrado>, val paginas: Int, val motivo: String, val inicio: String, val titulo: String)

/** Reglas de un `robots.txt` para el grupo `*`: la regla más larga que encaje gana; a igual longitud, permitir. */
class ReglasRobots(texto: String) {
    private class Regla(val permitir: Boolean, val patron: Regex, val largo: Int)

    private val reglas = ArrayList<Regla>()

    init {
        var enGrupo = false
        var vistoRegla = false
        for (linea0 in texto.lineSequence().take(2000)) {
            val linea = linea0.substringBefore('#').trim()
            val i = linea.indexOf(':')
            if (i <= 0) continue
            val clave = linea.substring(0, i).trim().lowercase()
            val valor = linea.substring(i + 1).trim()
            if (clave == "user-agent") {
                if (vistoRegla) {
                    enGrupo = false
                    vistoRegla = false
                }
                if (valor == "*" || valor.lowercase().contains("optimo")) enGrupo = true
            } else if ((clave == "allow" || clave == "disallow") && valor.isNotEmpty()) {
                vistoRegla = true
                if (enGrupo && reglas.size < 500) {
                    try {
                        val fin = valor.endsWith("$")
                        val cuerpo = if (fin) valor.dropLast(1) else valor
                        val re = Regex("^" + cuerpo.split('*').joinToString(".*") { Regex.escape(it) } + (if (fin) "$" else ""))
                        reglas.add(Regla(clave == "allow", re, valor.length))
                    } catch (e: Exception) {
                        // Regla ilegible: se ignora.
                    }
                }
            } else if (clave == "allow" || clave == "disallow") {
                vistoRegla = true
            }
        }
    }

    fun permite(rutaYConsulta: String): Boolean {
        var mejor: Regla? = null
        for (r in reglas) {
            if (!r.patron.containsMatchIn(rutaYConsulta)) continue
            val m = mejor
            if (m == null || r.largo > m.largo || (r.largo == m.largo && r.permitir)) mejor = r
        }
        return mejor?.permitir ?: true
    }
}

/**
 * Rastreo en segundo plano (un hilo, una petición cada vez con pausa) que sigue los enlaces de una página por anchura:
 * primero todo lo de un salto, luego lo de dos… No usa WebView ni ejecuta JavaScript: solo lee el HTML descargado.
 * Los dominios que bloquea el bloqueador de anuncios no se visitan ni se listan. Los avisos llegan al hilo principal.
 */
class Rastreador(
    private val cfg: ConfigRastreo,
    private val alProgreso: (ProgresoRastreo) -> Unit,
    private val alTerminar: (ResultadoRastreo) -> Unit
) {
    companion object {
        private const val MAX_BYTES_PAGINA = 2 * 1024 * 1024
        private const val MAX_COLA = 20000
        private const val MAX_REDIRECCIONES = 5
        private const val MAX_VARIANTES_RUTA = 40
        private val EXT_PAGINA = setOf("", "html", "htm", "xhtml", "php", "asp", "aspx", "jsp", "shtml", "cfm", "cgi")
        private val EVITAR = Regex("(log-?out|sign-?out|cerrar-?sesion|delete|remove|unsubscribe|/logout)", RegexOption.IGNORE_CASE)
        private val RE_ETIQUETA_A = Regex("<a\\b([^>]*)>", RegexOption.IGNORE_CASE)
        private val RE_HREF = Regex("\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
        private val RE_TITLE_ATTR = Regex("\\btitle\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
        private val RE_ARIA = Regex("\\baria-label\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
        private val RE_ENCABEZADO = Regex("<h[1-4]\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val RE_TITULO = Regex("<title[^>]*>(.{0,300}?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val RE_BASE = Regex("<base\\b[^>]*\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
        private val RE_CRUDO = Regex(
            "(magnet:\\?xt=urn:bt(?:ih|mh):[^\\s\"'<>\\\\]+|https?://(?:www\\.)?mega\\.(?:nz|io|co\\.nz)/[^\\s\"'<>\\\\]+)",
            RegexOption.IGNORE_CASE
        )
        private val RE_ETIQUETAS = Regex("<[^>]*>")
        private val RE_ESPACIOS = Regex("\\s+")
        private val RE_ENTIDAD = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

        fun decodificar(s: String): String {
            if (s.indexOf('&') < 0) return s
            return RE_ENTIDAD.replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    e == "amp" -> "&"
                    e == "lt" -> "<"
                    e == "gt" -> ">"
                    e == "quot" -> "\""
                    e == "apos" -> "'"
                    e == "nbsp" -> " "
                    e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else "" } ?: ""
                    else -> e.substring(1).toIntOrNull()?.let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else "" } ?: ""
                }
            }
        }

        private fun limpio(s: String, max: Int): String =
            RE_ESPACIOS.replace(decodificar(RE_ETIQUETAS.replace(s, " ")), " ").trim().take(max)

        /** Quita el fragmento y deja el host en minúsculas. */
        fun normalizar(url: String): String {
            val sin = url.substringBefore('#')
            return try {
                val u = URI(sin)
                if (u.host == null) sin else sin.replaceFirst(u.host, u.host.lowercase())
            } catch (e: Exception) {
                sin
            }
        }
    }

    private val principal = Handler(Looper.getMainLooper())

    @Volatile
    private var motivoParada: String? = null

    @Volatile
    private var hilo: Thread? = null

    @Volatile
    private var pausado = false

    private val contTipo = IntArray(TipoEnlace.values().size)
    private var repetidos = 0
    private val recientes = ArrayDeque<EnlaceEncontrado>()

    val enPausa: Boolean get() = pausado

    /** Detiene el rastreo sin perder lo encontrado; el tiempo en pausa no cuenta para el tope de minutos. */
    fun pausar() {
        pausado = true
    }

    fun reanudar() {
        pausado = false
    }

    fun iniciar() {
        val t = Thread { correr() }
        t.isDaemon = true
        hilo = t
        t.start()
    }

    /** Pide parar; el rastreo termina enseguida entregando lo encontrado hasta ahora. */
    fun parar(motivo: String) {
        if (motivoParada == null) motivoParada = motivo
        hilo?.interrupt()
    }

    private class Pagina(val url: String, val html: String)

    private val robotsPorHost = HashMap<String, ReglasRobots?>()
    private val variantesPorRuta = HashMap<String, Int>()

    private fun bloqueado(host: String): Boolean = cfg.bloquear && AdBlock.bloquea(host)

    private fun hostDe(url: String): String = try { (URI(url).host ?: "").lowercase() } catch (e: Exception) { "" }

    private fun dentroDelAlcance(host: String, hostInicio: String): Boolean =
        cfg.otrosSitios || Enlaces.dominioBase(host) == Enlaces.dominioBase(hostInicio)

    private fun robotsPermite(url: String): Boolean {
        if (!cfg.robots) return true
        return try {
            val u = URI(url)
            val host = (u.host ?: return true).lowercase()
            val clave = u.scheme + "://" + host + (if (u.port > 0) ":" + u.port else "")
            val reglas = if (robotsPorHost.containsKey(clave)) robotsPorHost[clave] else {
                val r = descargarTexto("$clave/robots.txt", 200 * 1024)?.let { ReglasRobots(it) }
                robotsPorHost[clave] = r
                r
            }
            reglas?.permite((u.rawPath ?: "/") + (if (u.rawQuery != null) "?" + u.rawQuery else "")) ?: true
        } catch (e: Exception) {
            true
        }
    }

    private fun abrir(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", cfg.ua)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
        c.setRequestProperty("Accept-Language", "es,en;q=0.8")
        if (cfg.cookies) {
            val ck = try { CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }
            if (!ck.isNullOrEmpty()) c.setRequestProperty("Cookie", ck)
        }
        return c
    }

    private fun leerCuerpo(c: HttpURLConnection, limite: Int): ByteArray {
        val salida = ByteArrayOutputStream()
        c.inputStream.use { entrada ->
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < limite) {
                val n = entrada.read(buf, 0, minOf(buf.size, limite - total))
                if (n < 0) break
                salida.write(buf, 0, n)
                total += n
            }
        }
        return salida.toByteArray()
    }

    private fun descargarTexto(url: String, limite: Int): String? {
        val c = abrir(url)
        try {
            if (c.responseCode != 200) return null
            return String(leerCuerpo(c, limite), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        } finally {
            c.disconnect()
        }
    }

    private fun decodificarHtml(bytes: ByteArray, tipo: String?): String {
        var cs = Regex("charset\\s*=\\s*\"?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(tipo ?: "")?.groupValues?.get(1)
        if (cs == null) {
            val cabeza = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            cs = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(cabeza)?.groupValues?.get(1)
        }
        return try {
            String(bytes, java.nio.charset.Charset.forName(cs ?: "UTF-8"))
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    /** Descarga una página HTML siguiendo redirecciones a mano (cada salto vuelve a pasar por el bloqueo y el alcance). Null si no hay página. */
    private fun descargarPagina(inicio: String, hostInicio: String): Pagina? {
        var url = inicio
        for (salto in 0..MAX_REDIRECCIONES) {
            val host = hostDe(url)
            if (host.isEmpty() || bloqueado(host) || !dentroDelAlcance(host, hostInicio)) return null
            val c = try { abrir(url) } catch (e: Exception) { return null }
            try {
                val codigo = c.responseCode
                if (codigo in 301..308 && codigo != 304) {
                    val destino = c.getHeaderField("Location") ?: return null
                    url = try { normalizar(URI(url).resolve(destino.trim()).toString()) } catch (e: Exception) { return null }
                    continue
                }
                if (codigo != 200) return null
                val tipo = c.contentType
                if (tipo != null && !tipo.lowercase().let { it.contains("html") || it.contains("xml") }) return null
                return Pagina(url, decodificarHtml(leerCuerpo(c, MAX_BYTES_PAGINA), tipo))
            } catch (e: Exception) {
                return null
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun esPaginaSeguible(url: String): Boolean {
        if (url.length > 400) return false
        val minus = url.lowercase()
        if (!(minus.startsWith("http://") || minus.startsWith("https://"))) return false
        if (!EXT_PAGINA.contains(Enlaces.extension(url))) return false
        if (EVITAR.containsMatchIn(minus)) return false
        return true
    }

    private fun resolver(base: String, href: String): String? {
        val h = href.trim().replace(" ", "%20").replace("\n", "").replace("\r", "").replace("\t", "")
        if (h.isEmpty() || h.startsWith("#") || h.lowercase().startsWith("javascript:") || h.lowercase().startsWith("mailto:") ||
            h.lowercase().startsWith("tel:") || h.lowercase().startsWith("data:")
        ) return null
        if (h.lowercase().startsWith("magnet:")) return h
        return try {
            val r = URI(base).resolve(h).toString()
            if (r.length > 4000) null else r
        } catch (e: Exception) {
            null
        }
    }

    private fun atributo(m: MatchResult?): String = m?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""

    private fun correr() {
        val resultados = LinkedHashMap<String, EnlaceEncontrado>()
        val vistas = HashSet<String>()
        val cola = ArrayDeque<Pair<String, Int>>()
        val hostInicio = hostDe(cfg.inicio)
        val inicio = normalizar(cfg.inicio)
        cola.addLast(Pair(inicio, 0))
        vistas.add(inicio)
        var paginas = 0
        var intentos = 0
        var tituloInicio = ""
        var motivo = "completo"
        var t0 = SystemClock.elapsedRealtime()
        val limiteProf = if (cfg.soloPagina) 0 else if (cfg.profMax <= 0) Int.MAX_VALUE else cfg.profMax
        try {
            while (cola.isNotEmpty()) {
                while (pausado && motivoParada == null) {
                    val ini = SystemClock.elapsedRealtime()
                    Thread.sleep(200)
                    t0 += SystemClock.elapsedRealtime() - ini
                }
                val parada = motivoParada
                if (parada != null) { motivo = parada; break }
                if (paginas >= cfg.maxPaginas) { motivo = "tope de páginas"; break }
                if (intentos >= cfg.maxPaginas * 3) { motivo = "demasiados fallos"; break }
                if (SystemClock.elapsedRealtime() - t0 > cfg.maxMs) { motivo = "tiempo agotado"; break }
                if (resultados.size >= Enlaces.MAX_ENLACES) { motivo = "tope de enlaces"; break }
                val (url, prof) = cola.removeFirst()
                intentos++
                if (!robotsPermite(url)) continue
                val pagina = descargarPagina(url, hostInicio) ?: continue
                paginas++
                val titulo = RE_TITULO.find(pagina.html)?.let { limpio(it.groupValues[1], 120) } ?: ""
                if (paginas == 1) tituloInicio = titulo
                if (pagina.url != url) vistas.add(normalizar(pagina.url))
                procesarPagina(pagina, titulo, prof, limiteProf, hostInicio, resultados, vistas, cola)
                val p = ProgresoRastreo(
                    paginas, resultados.size, prof, cola.size, pagina.url,
                    contTipo.copyOf(), repetidos, recientes.toList(), SystemClock.elapsedRealtime() - t0
                )
                principal.post { alProgreso(p) }
                if (cola.isNotEmpty() && cfg.pausaMs > 0) Thread.sleep(cfg.pausaMs)
            }
        } catch (e: InterruptedException) {
            motivo = motivoParada ?: "detenido"
        } catch (e: Exception) {
            motivo = "error"
        }
        val res = ResultadoRastreo(ArrayList(resultados.values), paginas, motivo, cfg.inicio, tituloInicio)
        principal.post { alTerminar(res) }
    }

    private fun anadirResultado(e: EnlaceEncontrado, origen: String, resultados: MutableMap<String, EnlaceEncontrado>) {
        if (e.tipo != TipoEnlace.PROPIO && (cfg.tiposMascara shr e.tipo.ordinal) and 1 == 0) return
        if (e.tipo != TipoEnlace.MAGNET && bloqueado(e.host)) return
        val k = Enlaces.clave(e)
        val previo = resultados[k]
        if (previo == null) {
            if (resultados.size < Enlaces.MAX_ENLACES) {
                val nuevo = e.conOrigen(origen)
                resultados[k] = nuevo
                contTipo[nuevo.tipo.ordinal]++
                recientes.addLast(nuevo)
                while (recientes.size > 3) recientes.removeFirst()
            }
        } else {
            repetidos++
            if (Enlaces.pesoOrigen(e.origenNombre) > Enlaces.pesoOrigen(previo.origenNombre)) {
                resultados[k] = e.conOrigen(previo.origen)
            }
        }
    }

    private fun procesarPagina(
        pagina: Pagina, titulo: String, prof: Int, limiteProf: Int, hostInicio: String,
        resultados: MutableMap<String, EnlaceEncontrado>, vistas: MutableSet<String>, cola: ArrayDeque<Pair<String, Int>>
    ) {
        val html = pagina.html
        val base = RE_BASE.find(html)?.let { m -> resolver(pagina.url, atributo(m)) } ?: pagina.url
        val encabezados = ArrayList<Pair<Int, String>>()
        for (m in RE_ENCABEZADO.findAll(html)) {
            val fin = html.indexOf("</h", m.range.last, ignoreCase = true).let { if (it < 0 || it - m.range.last > 400) minOf(html.length, m.range.last + 200) else it }
            encabezados.add(Pair(m.range.first, limpio(html.substring(m.range.last + 1, fin), 120)))
            if (encabezados.size >= 400) break
        }
        var ie = 0
        var actual = ""
        for (m in RE_ETIQUETA_A.findAll(html)) {
            val attrs = m.groupValues[1]
            val href = RE_HREF.find(attrs)?.let { x -> x.groupValues[1].ifEmpty { x.groupValues[2].ifEmpty { x.groupValues[3] } } } ?: continue
            val abs = resolver(base, decodificar(href)) ?: continue
            while (ie < encabezados.size && encabezados[ie].first < m.range.first) {
                actual = encabezados[ie].second
                ie++
            }
            val fin = html.indexOf("</a", m.range.last, ignoreCase = true).let { if (it < 0 || it - m.range.last > 600) minOf(html.length, m.range.last + 300) else it }
            val texto = limpio(html.substring(m.range.last + 1, fin), 200)
            val aviso = decodificar(atributo(RE_TITLE_ATTR.find(attrs)).ifEmpty { atributo(RE_ARIA.find(attrs)) }).trim().take(200)
            val e = Enlaces.clasificar(EnlaceCrudo(abs, texto, aviso, "", actual), hostInicio, titulo, cfg.propio)
            if (e != null) {
                anadirResultado(e, pagina.url, resultados)
            } else if (prof < limiteProf) {
                seguir(abs, prof + 1, hostInicio, vistas, cola)
            }
        }
        // Magnets y enlaces de Mega escritos como texto o dentro de scripts.
        for (m in RE_CRUDO.findAll(html)) {
            val u = decodificar(m.value).trimEnd('.', ',', ';', ')', ']')
            if (u.length > 4000) continue
            val e = Enlaces.clasificar(EnlaceCrudo(u, "", "", "", ""), hostInicio, titulo, cfg.propio) ?: continue
            anadirResultado(e, pagina.url, resultados)
        }
    }

    private fun seguir(url0: String, prof: Int, hostInicio: String, vistas: MutableSet<String>, cola: ArrayDeque<Pair<String, Int>>) {
        if (cola.size >= MAX_COLA || !esPaginaSeguible(url0)) return
        val url = normalizar(url0)
        val host = hostDe(url)
        if (host.isEmpty() || bloqueado(host) || !dentroDelAlcance(host, hostInicio)) return
        if (cfg.prefijoRuta.isNotEmpty() && host == hostInicio) {
            val ruta0 = try { URI(url).path ?: "" } catch (e: Exception) { "" }
            if (!ruta0.startsWith(cfg.prefijoRuta)) return
        }
        if (vistas.contains(url)) return
        // Trampas (calendarios, filtros infinitos): muchas variantes de la misma ruta solo cambiando la consulta.
        val ruta = url.substringBefore('?')
        val n = (variantesPorRuta[ruta] ?: 0) + 1
        if (n > MAX_VARIANTES_RUTA) return
        variantesPorRuta[ruta] = n
        vistas.add(url)
        cola.addLast(Pair(url, prof))
    }
}
