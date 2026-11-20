package com.optimo.navegador

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URLDecoder

/** Tipos de enlace que busca el rastreador de enlaces. [PROPIO] son los que cumplen el filtro escrito por el usuario. */
enum class TipoEnlace(val etiqueta: String) {
    MAGNET("Magnet"),
    MEGA("Mega"),
    NUBE("Nube"),
    TORRENT("Torrent"),
    LISTA("Lista"),
    VIDEO("Vídeo"),
    AUDIO("Audio"),
    COMPRIMIDO("Comprimido"),
    DOCUMENTO("Documento"),
    IMAGEN("Imagen"),
    PROPIO("Propio")
}

/** Enlace tal como lo recoge `assets/enlaces/extraer.js`, con el texto que lo rodea. */
class EnlaceCrudo(val url: String, val texto: String, val titulo: String, val contexto: String, val encabezado: String)

/**
 * Enlace ya clasificado. [nombre] es lo mejor que se pudo averiguar y [origenNombre] dice de dónde sale (ver [Enlaces.nombreFiable]).
 * [tamano] en bytes, o -1 si no se conoce. [externo]: el enlace va a otro sitio distinto del de la página.
 */
class EnlaceEncontrado(
    val url: String,
    val tipo: TipoEnlace,
    val nombre: String,
    val origenNombre: String,
    val tamano: Long,
    val host: String,
    val externo: Boolean,
    /** Página donde se encontró el enlace (solo en los rastreos; vacío si es la página abierta). */
    val origen: String = ""
) {
    fun conOrigen(o: String): EnlaceEncontrado = EnlaceEncontrado(url, tipo, nombre, origenNombre, tamano, host, externo, o)
}

class ResultadoExtraccion(val titulo: String, val url: String, val crudos: List<EnlaceCrudo>)

/**
 * Filtro propio del usuario: lista separada por comas de extensiones (`epub, .cbz`), trozos de texto de la dirección (`/descargar/`)
 * o expresiones regulares entre barras (`/\.part\d+\.rar/`).
 */
class FiltroPropio(texto: String) {
    private val extensiones = HashSet<String>()
    private val trozos = ArrayList<String>()
    private val expresiones = ArrayList<Regex>()

    init {
        for (parte in texto.split(',', ';', '\n')) {
            val p = parte.trim()
            if (p.isEmpty()) continue
            if (p.length > 2 && p.startsWith("/") && p.endsWith("/")) {
                try {
                    expresiones.add(Regex(p.substring(1, p.length - 1), RegexOption.IGNORE_CASE))
                } catch (e: Exception) {
                    // Expresión inválida: se ignora.
                }
            } else if (p.startsWith(".") && p.length in 2..9 && p.drop(1).all { it.isLetterOrDigit() }) {
                extensiones.add(p.drop(1).lowercase())
            } else if (p.length <= 6 && p.all { it.isLetterOrDigit() }) {
                extensiones.add(p.lowercase())
            } else {
                trozos.add(p.lowercase())
            }
        }
    }

    fun coincide(url: String): Boolean {
        val ext = Enlaces.extension(url)
        if (ext.isNotEmpty() && extensiones.contains(ext)) return true
        if (trozos.isNotEmpty()) {
            val minus = url.lowercase()
            if (trozos.any { minus.contains(it) }) return true
        }
        return expresiones.any { it.containsMatchIn(url) }
    }
}

/** Datos que lleva un magnet en su propia dirección. */
class DatosMagnet(val hash: String, val nombre: String, val tamano: Long)

/** Clasificación, nombres y exportación de los enlaces encontrados en una página. Lógica pura: no toca la interfaz. */
object Enlaces {

    const val MAX_ENLACES = 5000

    private val LISTA = setOf("m3u", "m3u8", "mpd", "pls")
    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "wmv", "flv", "mpg", "mpeg", "ts", "3gp")
    private val AUDIO = setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")
    private val COMPRIMIDO = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso")
    private val DOCUMENTO = setOf("pdf", "epub", "mobi", "azw3", "cbz", "cbr", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt")
    private val IMAGEN = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif")

    private val HOSTS_NUBE = listOf(
        "mediafire.com", "drive.google.com", "dropbox.com", "1fichier.com", "gofile.io", "pixeldrain.com",
        "terabox.com", "1024terabox.com", "wetransfer.com", "we.tl", "icedrive.net", "pcloud.com", "workupload.com", "krakenfiles.com"
    )

    /** Textos de enlace que no dicen nada sobre el contenido. */
    private val GENERICOS = setOf(
        "descargar", "descarga", "download", "aquí", "aqui", "here", "click", "clic", "link", "enlace", "mega", "ver", "abrir", "open",
        "magnet", "torrent", "get", "descargar ahora", "download now", "ver online", "ir", "más", "mas", "opción 1", "opcion 1"
    )

    fun esGenerico(texto: String): Boolean {
        val x = texto.trim().lowercase().trimEnd('.', ':', '>', '»')
        return x.length < 3 || GENERICOS.contains(x)
    }

    /** Extensión (en minúsculas) del último tramo de la ruta, sin consulta ni fragmento; vacía si no hay. */
    fun extension(url: String): String {
        val ruta = url.substringBefore('#').substringBefore('?')
        val ultimo = ruta.substringAfterLast('/')
        val i = ultimo.lastIndexOf('.')
        if (i < 0 || i == ultimo.length - 1) return ""
        return ultimo.substring(i + 1).lowercase().take(10)
    }

    /** Dominio «base» aproximado (example.com, example.co.uk). Las IP se comparan enteras. */
    fun dominioBase(host: String): String {
        val h = host.lowercase().trimEnd('.')
        val partes = h.split('.')
        if (partes.size <= 2 || partes.all { x -> x.isNotEmpty() && x.all { c -> c.isDigit() } }) return h
        val ult = partes[partes.size - 1]
        val pen = partes[partes.size - 2]
        val n = if (ult.length == 2 && pen.length <= 3) 3 else 2
        return partes.takeLast(n).joinToString(".")
    }

    /** Hash, nombre (`dn`) y tamaño (`xl`) de un magnet. Se lee a mano: un `magnet:?…` es una URI opaca y `Uri` no da sus parámetros. */
    fun datosMagnet(url: String): DatosMagnet {
        var hash = ""
        var nombre = ""
        var tamano = -1L
        for (par in url.substringAfter('?', "").split('&')) {
            val k = par.substringBefore('=').lowercase()
            val v = par.substringAfter('=', "")
            try {
                if (k == "xt" && hash.isEmpty()) {
                    val d = URLDecoder.decode(v, "UTF-8")
                    if (d.lowercase().startsWith("urn:btih:")) hash = d.substring(9).lowercase()
                } else if (k == "dn" && nombre.isEmpty()) {
                    nombre = URLDecoder.decode(v, "UTF-8").trim()
                } else if (k == "xl" && tamano < 0) {
                    tamano = v.toLongOrNull() ?: -1L
                }
            } catch (e: Exception) {
                // Parámetro mal codificado: se salta.
            }
        }
        return DatosMagnet(hash, nombre, tamano)
    }

    private fun esNube(u: Uri): Boolean {
        val host = (u.host ?: "").lowercase()
        if (HOSTS_NUBE.none { host == it || host.endsWith(".$it") }) return false
        val ruta = u.path ?: ""
        if (ruta.length <= 1) return false
        if (host.endsWith("drive.google.com")) {
            return ruta.startsWith("/file/") || ruta.startsWith("/drive/folders") || ruta.startsWith("/folders/") ||
                ruta.startsWith("/uc") || ruta.startsWith("/open")
        }
        if (host.endsWith("dropbox.com")) return ruta.startsWith("/s/") || ruta.startsWith("/scl/") || ruta.startsWith("/sh/")
        return true
    }

    private fun nombreDeArchivo(url: String): String {
        val ruta = url.substringBefore('#').substringBefore('?').trimEnd('/')
        return Uri.decode(ruta.substringAfterLast('/')).trim()
    }

    /** ¿El nombre es de fiar como nombre del contenido (viene del propio enlace) o es una pista de la página? */
    fun nombreFiable(origen: String): Boolean = origen == "magnet" || origen == "archivo del enlace" || origen == "texto del enlace"

    fun pesoOrigen(origen: String): Int = when (origen) {
        "magnet", "archivo del enlace" -> 3
        "texto del enlace", "título del enlace" -> 2
        "texto cercano", "encabezado cercano" -> 1
        else -> 0
    }

    private fun nombreDe(c: EnlaceCrudo, tipo: TipoEnlace, url: String, tituloPagina: String, host: String): Pair<String, String> {
        val archivo = nombreDeArchivo(url)
        // Mega no trae nombre en la dirección (el contenido va cifrado): el nombre solo puede salir de la página.
        if (tipo != TipoEnlace.MEGA && archivo.isNotEmpty() && extension(url).isNotEmpty()) return Pair(archivo, "archivo del enlace")
        if (!esGenerico(c.texto)) return Pair(c.texto.take(120), "texto del enlace")
        if (!esGenerico(c.titulo)) return Pair(c.titulo.take(120), "título del enlace")
        if (!esGenerico(c.contexto)) return Pair(c.contexto.take(120), "texto cercano")
        if (!esGenerico(c.encabezado)) return Pair(c.encabezado.take(120), "encabezado cercano")
        if (tituloPagina.isNotBlank()) return Pair(tituloPagina.take(120), "título de la página")
        return Pair(host.ifEmpty { url.take(60) }, "dirección")
    }

    /** Clasifica un enlace; null si no es de ningún tipo buscado. */
    fun clasificar(c: EnlaceCrudo, paginaHost: String, tituloPagina: String, propio: FiltroPropio?): EnlaceEncontrado? {
        val url = c.url.trim()
        if (url.isEmpty() || url.length > 4000) return null
        val minus = url.lowercase()
        if (minus.startsWith("magnet:")) {
            val d = datosMagnet(url)
            val nombre: String
            val origen: String
            if (d.nombre.isNotEmpty()) {
                nombre = d.nombre
                origen = "magnet"
            } else if (!esGenerico(c.texto)) {
                nombre = c.texto.take(120)
                origen = "texto del enlace"
            } else if (d.hash.isNotEmpty()) {
                nombre = "btih:" + d.hash.take(12)
                origen = "hash"
            } else {
                nombre = "Magnet"
                origen = "hash"
            }
            return EnlaceEncontrado(url, TipoEnlace.MAGNET, nombre, origen, d.tamano, "magnet", false)
        }
        if (!(minus.startsWith("http://") || minus.startsWith("https://"))) return null
        val uri = Uri.parse(url)
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return null
        val ext = extension(url)
        val tipo: TipoEnlace = when {
            ReproductorExterno.esMega(url) -> TipoEnlace.MEGA
            esNube(uri) -> TipoEnlace.NUBE
            ext == "torrent" -> TipoEnlace.TORRENT
            LISTA.contains(ext) -> TipoEnlace.LISTA
            VIDEO.contains(ext) -> TipoEnlace.VIDEO
            AUDIO.contains(ext) -> TipoEnlace.AUDIO
            COMPRIMIDO.contains(ext) -> TipoEnlace.COMPRIMIDO
            DOCUMENTO.contains(ext) -> TipoEnlace.DOCUMENTO
            IMAGEN.contains(ext) -> TipoEnlace.IMAGEN
            propio != null && propio.coincide(url) -> TipoEnlace.PROPIO
            else -> return null
        }
        val nombreOrigen = nombreDe(c, tipo, url, tituloPagina, host)
        val externo = dominioBase(host) != dominioBase(paginaHost)
        return EnlaceEncontrado(url, tipo, nombreOrigen.first, nombreOrigen.second, -1L, host, externo)
    }

    fun clave(e: EnlaceEncontrado): String {
        if (e.tipo == TipoEnlace.MAGNET) {
            val h = datosMagnet(e.url).hash
            return "m:" + (if (h.isNotEmpty()) h else e.url)
        }
        // En Mega el fragmento es la clave del archivo: forma parte de la dirección. En el resto se ignora.
        return if (e.tipo == TipoEnlace.MEGA) e.url else e.url.substringBefore('#')
    }

    /** Clasifica todos los enlaces de la página, quita repetidos (un magnet se repite por su hash) y conserva el mejor nombre. */
    fun procesar(crudos: List<EnlaceCrudo>, paginaHost: String, tituloPagina: String, propio: FiltroPropio?): List<EnlaceEncontrado> {
        val mapa = LinkedHashMap<String, EnlaceEncontrado>()
        for (c in crudos) {
            val e = clasificar(c, paginaHost, tituloPagina, propio) ?: continue
            val k = clave(e)
            val previo = mapa[k]
            if (previo == null || pesoOrigen(e.origenNombre) > pesoOrigen(previo.origenNombre)) mapa[k] = e
            if (mapa.size >= MAX_ENLACES) break
        }
        return ArrayList(mapa.values)
    }

    /** Lee lo que devuelve `evaluateJavascript` con `extraer.js` (un literal JSON con otro JSON dentro). Null si no es válido. */
    fun leerResultado(bruto: String?): ResultadoExtraccion? {
        if (bruto == null || bruto == "null") return null
        try {
            val texto = JSONTokener(bruto).nextValue()
            if (texto !is String) return null
            val j = JSONObject(texto)
            val arr = j.optJSONArray("items") ?: return null
            val crudos = ArrayList<EnlaceCrudo>()
            for (i in 0 until minOf(arr.length(), MAX_ENLACES)) {
                val o = arr.optJSONObject(i) ?: continue
                crudos.add(EnlaceCrudo(o.optString("u"), o.optString("t"), o.optString("a"), o.optString("c"), o.optString("h")))
            }
            return ResultadoExtraccion(j.optString("titulo"), j.optString("url"), crudos)
        } catch (e: Exception) {
            return null
        }
    }

    /** Un enlace por línea. */
    fun texto(lista: List<EnlaceEncontrado>): String = lista.joinToString("\n") { it.url }

    fun textoConNombres(lista: List<EnlaceEncontrado>): String = lista.joinToString("\n") { it.nombre + " — " + it.url }

    private fun campoCsv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    /** CSV con BOM (Excel lo abre bien en UTF-8): tipo, nombre, origen del nombre, tamaño (bytes), dirección, página de origen. */
    fun csv(lista: List<EnlaceEncontrado>, pagina: String): String {
        val sb = StringBuilder("﻿")
        sb.append("tipo,nombre,origen_del_nombre,tamano_bytes,url,pagina\r\n")
        for (e in lista) {
            sb.append(campoCsv(e.tipo.etiqueta)).append(',').append(campoCsv(e.nombre)).append(',').append(campoCsv(e.origenNombre)).append(',')
                .append(if (e.tamano >= 0) e.tamano.toString() else "").append(',').append(campoCsv(e.url)).append(',')
                .append(campoCsv(e.origen.ifEmpty { pagina })).append("\r\n")
        }
        return sb.toString()
    }
}

/** Una lista de enlaces guardada para consultarla después. */
class ListaEnlaces(val id: Long, val nombre: String, val fuente: String, val fecha: Long, val items: List<EnlaceEncontrado>)

/**
 * Listas de enlaces que el usuario guarda a propósito. Se guardan cifradas (como marcadores y reglas) en `listas_enlaces.json`.
 * No se guardan desde pestañas privadas. Todo se llama desde el hilo principal.
 */
object ListasEnlaces {

    const val MAX_LISTAS = 30
    const val MAX_ITEMS = 2000

    private val listas = ArrayList<ListaEnlaces>()
    private lateinit var archivo: File

    fun iniciar(c: Context) {
        archivo = File(c.applicationContext.filesDir, "listas_enlaces.json")
        cargar()
    }

    /** Más recientes primero. */
    fun todas(): List<ListaEnlaces> = ArrayList(listas)

    /** Guarda una copia de [items] (hasta [MAX_ITEMS]). Null si ya hay [MAX_LISTAS] listas. */
    fun guardar(nombre: String, fuente: String, items: List<EnlaceEncontrado>): ListaEnlaces? {
        if (listas.size >= MAX_LISTAS || items.isEmpty()) return null
        val ahora = System.currentTimeMillis()
        val id = if (listas.isEmpty() || ahora > listas.maxOf { it.id }) ahora else listas.maxOf { it.id } + 1
        val l = ListaEnlaces(id, nombre.trim().ifEmpty { "Lista" }.take(120), fuente.take(2000), ahora, ArrayList(items.take(MAX_ITEMS)))
        listas.add(0, l)
        persistir()
        return l
    }

    fun eliminar(id: Long) {
        if (listas.removeAll { it.id == id }) persistir()
    }

    fun eliminarTodas() {
        listas.clear()
        persistir()
    }

    private fun cargar() {
        listas.clear()
        try {
            val t = AlmacenSeguro.leerTexto(archivo) ?: return
            val a = JSONArray(t)
            for (i in 0 until minOf(a.length(), MAX_LISTAS)) {
                val o = a.optJSONObject(i) ?: continue
                val its = ArrayList<EnlaceEncontrado>()
                val ia = o.optJSONArray("i") ?: continue
                for (k in 0 until minOf(ia.length(), MAX_ITEMS)) {
                    val x = ia.optJSONObject(k) ?: continue
                    val tipo = try {
                        TipoEnlace.valueOf(x.optString("t"))
                    } catch (e: Exception) {
                        continue
                    }
                    its.add(EnlaceEncontrado(x.optString("u"), tipo, x.optString("n"), x.optString("o"), x.optLong("s", -1L), x.optString("h"), x.optBoolean("e"), x.optString("p")))
                }
                listas.add(ListaEnlaces(o.optLong("id"), o.optString("n"), o.optString("f"), o.optLong("d"), its))
            }
        } catch (e: Exception) {
            // Archivo dañado o ilegible: se empieza sin listas.
        }
    }

    private fun persistir() {
        try {
            val a = JSONArray()
            for (l in listas) {
                val ia = JSONArray()
                for (e in l.items) {
                    ia.put(
                        JSONObject().put("u", e.url).put("t", e.tipo.name).put("n", e.nombre).put("o", e.origenNombre)
                            .put("s", e.tamano).put("h", e.host).put("e", e.externo).put("p", e.origen)
                    )
                }
                a.put(JSONObject().put("id", l.id).put("n", l.nombre).put("f", l.fuente).put("d", l.fecha).put("i", ia))
            }
            AlmacenSeguro.escribirTexto(archivo, a.toString())
        } catch (e: Exception) {
            // Sin espacio: se pierde solo esta escritura.
        }
    }
}
