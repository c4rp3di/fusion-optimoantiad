package com.optimo.navegador

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Puente con Optimo Play, la app de reproduccion multimedia (com.optimo.play).
 * Optimo no reproduce nada por su cuenta: entrega la URL, el magnet o el .torrent con un Intent explicito.
 * Si Optimo Play no esta instalada, todo sigue funcionando como antes (descarga / abrir externo).
 */
object ReproductorExterno {

    const val PAQUETE = "com.optimo.play"
    private const val ACTIVIDAD = "com.optimo.play.PlayerActivity"

    private val EXT_MEDIO = listOf(
        ".m3u8", ".mpd", ".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".mp3", ".flac", ".m4a", ".ogg", ".opus"
    )

    fun instalado(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(PAQUETE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun esMagnet(url: String): Boolean = url.startsWith("magnet:", true)

    fun esManifiesto(url: String): Boolean {
        val r = url.substringBefore('?').substringBefore('#').lowercase()
        return r.endsWith(".m3u8") || r.endsWith(".mpd")
    }

    /** ¿Parece un contenido multimedia o torrent por su extension o su tipo MIME? */
    fun esMedio(url: String, mime: String?): Boolean {
        if (esMagnet(url)) return true
        if (!mime.isNullOrEmpty()) {
            val m = mime.lowercase()
            if (m.startsWith("video/") || m.startsWith("audio/") || m == "application/x-bittorrent" ||
                m == "application/vnd.apple.mpegurl" || m == "application/x-mpegurl" || m == "application/dash+xml"
            ) return true
        }
        val r = url.substringBefore('?').substringBefore('#').lowercase()
        return r.endsWith(".torrent") || EXT_MEDIO.any { r.endsWith(it) }
    }

    /** Envia el contenido al reproductor. Devuelve false si no esta instalado o no puede abrirlo. */
    fun enviar(ctx: Context, url: String, titulo: String?, referer: String?, ua: String?): Boolean {
        if (!instalado(ctx)) return false
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.setClassName(PAQUETE, ACTIVIDAD)
            if (!titulo.isNullOrBlank()) i.putExtra("title", titulo)
            if (!referer.isNullOrBlank() && !esMagnet(url)) i.putExtra("referer", referer)
            if (!ua.isNullOrBlank() && !esMagnet(url)) i.putExtra("user_agent", ua)
            // Tarea aparte: Optimo Play aparece como su propia tarjeta en Recientes y Optimo queda intacta para volver a ella.
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
