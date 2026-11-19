package com.optimo.navegador

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri

/** Una app a la que se puede entregar un enlace multimedia o un magnet. [actividad] es null para Optimo Play (se abre con ReproductorExterno). */
class AppDestino(val paquete: String, val actividad: String?, val etiqueta: String, val icono: Drawable?)

/**
 * Apps del dispositivo que pueden abrir un enlace de vídeo, audio, torrent o un magnet, para ofrecerlas al usuario.
 * Optimo Play (si está instalada) va siempre la primera; Optimo y los navegadores no se listan (el navegador ya es Optimo).
 * Necesita las declaraciones <queries> del manifiesto (visibilidad de paquetes, Android 11+).
 */
object AppsMedia {

    private const val MAGNET_EJEMPLO = "magnet:?xt=urn:btih:0000000000000000000000000000000000000000"

    /** Tipo MIME para el Intent: el que dé el servidor si es multimedia; si no, el de la extensión; en último caso un tipo genérico de vídeo. */
    fun tipoDe(url: String, mime: String?): String {
        if (!mime.isNullOrEmpty()) {
            val m = mime.lowercase().substringBefore(';').trim()
            if (m == "application/vnd.apple.mpegurl" || m == "application/x-mpegurl") return "application/x-mpegURL"
            if (m.startsWith("video/") || m.startsWith("audio/") || m == "application/x-bittorrent" || m == "application/dash+xml") return m
        }
        val r = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            r.endsWith(".m3u8") -> "application/x-mpegURL"
            r.endsWith(".m3u") -> "audio/x-mpegurl"
            r.endsWith(".mpd") -> "application/dash+xml"
            r.endsWith(".torrent") -> "application/x-bittorrent"
            r.endsWith(".mp4") -> "video/mp4"
            r.endsWith(".mkv") -> "video/x-matroska"
            r.endsWith(".webm") -> "video/webm"
            r.endsWith(".mov") -> "video/quicktime"
            r.endsWith(".m4v") -> "video/x-m4v"
            r.endsWith(".avi") -> "video/x-msvideo"
            r.endsWith(".mp3") -> "audio/mpeg"
            r.endsWith(".flac") -> "audio/flac"
            r.endsWith(".m4a") -> "audio/mp4"
            r.endsWith(".ogg") || r.endsWith(".opus") -> "audio/ogg"
            else -> "video/*"
        }
    }

    private fun intentVer(url: String, magnet: Boolean, mime: String?): Intent {
        val i = Intent(Intent.ACTION_VIEW)
        // Magnets y enlaces de Mega no tienen tipo de contenido: se entregan solo con la dirección.
        if (magnet || ReproductorExterno.esMega(url)) i.data = Uri.parse(url) else i.setDataAndType(Uri.parse(url), tipoDe(url, mime))
        return i
    }

    @Suppress("DEPRECATION")
    private fun consultar(ctx: Context, intent: Intent): List<ResolveInfo> =
        try {
            ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            emptyList()
        }

    /** Paquetes de navegadores (los que abren una página https): no se ofrecen como «otra app» para un vídeo. */
    private fun navegadores(ctx: Context): Set<String> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val res = HashSet<String>()
        for (ri in consultar(ctx, i)) ri.activityInfo?.packageName?.let { res.add(it) }
        return res
    }

    private fun iconoDePlay(ctx: Context): Drawable? =
        try { ctx.packageManager.getApplicationIcon(ReproductorExterno.PAQUETE) } catch (e: Exception) { null }

    /** Apps que pueden abrir [url] (con [mime] si se conoce). Optimo Play primero. */
    fun apps(ctx: Context, url: String, mime: String?): List<AppDestino> {
        val res = ArrayList<AppDestino>()
        val vistos = HashSet<String>()
        if (ReproductorExterno.instalado(ctx)) {
            res.add(AppDestino(ReproductorExterno.PAQUETE, null, "Optimo Play", iconoDePlay(ctx)))
            vistos.add(ReproductorExterno.PAQUETE)
        }
        val magnet = ReproductorExterno.esMagnet(url)
        val navs = if (magnet) emptySet() else navegadores(ctx)
        val pm = ctx.packageManager
        for (ri in consultar(ctx, intentVer(url, magnet, mime))) {
            val ai = ri.activityInfo ?: continue
            val paquete = ai.packageName
            if (paquete == ctx.packageName || paquete in vistos || paquete in navs) continue
            vistos.add(paquete)
            val etiqueta = try { ri.loadLabel(pm).toString() } catch (e: Exception) { paquete }
            val icono = try { ri.loadIcon(pm) } catch (e: Exception) { null }
            res.add(AppDestino(paquete, ai.name, etiqueta, icono))
        }
        return res
    }

    /** Todas las apps que sirven para algún tipo de contenido (vídeo, audio, lista, torrent) o para magnets: para elegir una fija en Ajustes. */
    fun appsGenericas(ctx: Context, magnet: Boolean): List<AppDestino> {
        if (magnet) return apps(ctx, MAGNET_EJEMPLO, null)
        val res = ArrayList<AppDestino>()
        val vistos = HashSet<String>()
        for (u in listOf(
            "https://example.com/v.mp4", "https://example.com/a.mp3",
            "https://example.com/v.m3u8", "https://example.com/l.m3u", "https://example.com/f.torrent",
            "https://mega.nz/file/abcdefgh#clave"
        )) {
            for (a in apps(ctx, u, null)) {
                if (vistos.add(a.paquete)) res.add(a)
            }
        }
        return res
    }

    /** Nombre visible de una app instalada, o null si ya no lo está. */
    fun etiquetaDe(ctx: Context, paquete: String): String? =
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(paquete, 0)).toString()
        } catch (e: Exception) {
            null
        }

    /** Entrega el contenido a la app. Devuelve false si no se pudo abrir. */
    fun abrir(ctx: Context, app: AppDestino, url: String, mime: String?, titulo: String?, referer: String?, ua: String?): Boolean {
        if (app.actividad == null) {
            return if (app.paquete == ReproductorExterno.PAQUETE) ReproductorExterno.enviar(ctx, url, titulo, referer, ua) else false
        }
        return try {
            val magnet = ReproductorExterno.esMagnet(url)
            val i = intentVer(url, magnet, mime)
            i.setClassName(app.paquete, app.actividad)
            if (!titulo.isNullOrBlank()) i.putExtra("title", titulo)
            if (!magnet && !ReproductorExterno.esMega(url)) {
                // Convención de MX Player y similares para reproductores que aceptan cabeceras.
                val cab = ArrayList<String>()
                if (!referer.isNullOrBlank()) { cab.add("Referer"); cab.add(referer) }
                if (!ua.isNullOrBlank()) { cab.add("User-Agent"); cab.add(ua) }
                if (cab.isNotEmpty()) i.putExtra("headers", cab.toTypedArray())
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
