package com.optimo.navegador

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Lo que el control de descargas necesita de MainActivity. */
interface AnfitrionControlDescargas {
    /** True si ese host puede descargarse sin cifrar (lo autorizó el usuario o es local). */
    fun httpPermitido(host: String): Boolean

    /** Cookies de la descarga: las del perfil normal o, en pestañas privadas, las del perfil privado. */
    fun cookiesDe(url: String, privada: Boolean): String?

    /** True si ya se puede escribir en Descargas; si no, pide el permiso y llama a [reintentar] cuando se conceda. */
    fun permisoAlmacenamiento(reintentar: () -> Unit): Boolean
    fun paginaActual(): String
    fun appActual(): Pestana?
    fun avisar(texto: String)
}

/**
 * Descargas de Optimo: las normales (con el gestor de descargas de Android: comprueba la dirección, pide confirmar y el nombre si
 * se ha pedido, solo con Wi-Fi si se ha pedido) y las de las aplicaciones web locales (blob:, a través del puente de la página).
 * Las dos se anotan en la lista de Descargas, salvo en pestañas privadas.
 */
class ControlDescargas(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionControlDescargas
) {
    // ---------------------------------------------------------------- descargas normales

    fun descargar(url: String, ua: String?, disposicion: String?, mime: String?, privada: Boolean = false, nombreElegido: String? = null) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            g.avisar("Este tipo de descarga no es compatible")
            return
        }
        if (url.startsWith("http://")) {
            val h = Omnibox.hostDe(url).lowercase()
            if (!g.httpPermitido(h) && !Omnibox.esLocal(h)) {
                g.avisar("Descarga sin cifrar bloqueada")
                return
            }
        }
        // «Confirmar antes de descargar»: se muestra el sitio y se deja cambiar el nombre.
        if (nombreElegido == null && ajustes.descargaConfirmar) {
            confirmarDescarga(act, paleta, privada, URLUtil.guessFileName(url, disposicion, mime), Omnibox.hostDe(url), mime ?: "") { n ->
                descargar(url, ua, disposicion, mime, privada, n)
            }
            return
        }
        if (!g.permisoAlmacenamiento { descargar(url, ua, disposicion, mime, privada, nombreElegido) }) return
        try {
            val nombre = nombreElegido ?: URLUtil.guessFileName(url, disposicion, mime)
            val pet = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrEmpty()) pet.setMimeType(mime)
            if (!ua.isNullOrEmpty()) pet.addRequestHeader("User-Agent", ua)
            val cookie = g.cookiesDe(url, privada)
            if (!cookie.isNullOrEmpty()) pet.addRequestHeader("Cookie", cookie)
            pet.setTitle(nombre)
            pet.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            pet.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre)
            if (ajustes.descargaSoloWifi) pet.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            val dm = act.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(pet)
            // Se anota para la pantalla de Descargas, salvo en pestañas privadas.
            if (!privada && ajustes.descargaRecordar) {
                val pagina = g.paginaActual()
                ListaDescargas.agregar(
                    RegistroDescarga(
                        id, nombre, url, mime ?: "", Omnibox.hostDe(url).lowercase().removePrefix("www."),
                        System.currentTimeMillis(), if (pagina.startsWith("http")) pagina else ""
                    )
                )
            }
            g.avisar("Descargando $nombre")
        } catch (e: Exception) {
            g.avisar("No se pudo iniciar la descarga")
        }
    }

    // ---------------------------------------------------------------- aplicación web local: descargas blob:

    private val ejecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var descargaBlob: EscritorDescarga? = null
    private var tokenBlob: String? = null

    private val soportaPuente: Boolean by lazy {
        try { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) } catch (e: Throwable) { false }
    }
    private val soportaInicio: Boolean by lazy {
        try { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } catch (e: Throwable) { false }
    }

    /** Instala (antes de cargar nada) el script de blobs y el puente de descargas, ambos restringidos a los orígenes de aplicaciones web. */
    fun instalarPuente(w: WebView) {
        if (!soportaPuente || !soportaInicio) return
        try {
            WebViewCompat.addDocumentStartJavaScript(w, AppLocal.GUION_INICIO, setOf(AppLocal.REGLA_ORIGEN))
            WebViewCompat.addWebMessageListener(w, AppLocal.NOMBRE_PUENTE, setOf(AppLocal.REGLA_ORIGEN)) { _, mensaje, origen, _, respuesta ->
                alMensaje(origen, mensaje.data, respuesta)
            }
        } catch (e: Exception) {
            // Sin puente: la aplicación funciona igual, solo fallan las descargas blob:.
        }
    }

    fun descargarBlob(w: WebView, url: String, disposicion: String?, mime: String?) {
        if (!soportaPuente) {
            g.avisar("Este WebView no admite descargas de la aplicación; actualiza Android System WebView")
            return
        }
        if (tokenBlob != null || descargaBlob != null) {
            g.avisar("Ya hay una descarga en curso")
            return
        }
        if (!g.permisoAlmacenamiento { descargarBlob(w, url, disposicion, mime) }) return
        val token = java.util.UUID.randomUUID().toString()
        tokenBlob = token
        // Si la página no empieza a mandar datos en 30 s, se olvida el intento.
        w.postDelayed({ if (tokenBlob == token && descargaBlob == null) tokenBlob = null }, 30000)
        val nombre = if (disposicion != null && disposicion.contains("filename", true)) URLUtil.guessFileName(url, disposicion, mime) else ""
        w.evaluateJavascript(AppLocal.guionDescarga(token, url, nombre), null)
    }

    private fun abortarBlob(mensaje: String) {
        val esc = descargaBlob
        descargaBlob = null
        tokenBlob = null
        if (esc != null) ejecutor.execute { esc.abortar() }
        g.avisar(mensaje)
    }

    /** Mensajes de la página durante una descarga blob: solo de un origen de aplicación web y solo con el token de la descarga en curso. */
    private fun alMensaje(origen: Uri, texto: String?, respuesta: androidx.webkit.JavaScriptReplyProxy) {
        if (origen.scheme != "https" || !AppLocal.esHostApp(origen.host?.lowercase())) return
        val j = try { JSONObject(texto ?: return) } catch (e: Exception) { return }
        val token = tokenBlob ?: return
        if (j.optString("t") != token) return
        when (j.optString("a")) {
            "ini" -> {
                if (descargaBlob != null) return
                val esc = EscritorDescarga(act, j.optString("n"), j.optString("m").ifEmpty { "application/octet-stream" })
                if (!esc.abrir()) {
                    abortarBlob("No se pudo crear el archivo en Descargas")
                    return
                }
                descargaBlob = esc
                respuesta.postMessage("ok")
            }
            "dat" -> {
                val esc = descargaBlob ?: return
                val datos = j.optString("d")
                ejecutor.execute {
                    val ok = try {
                        esc.escribir(android.util.Base64.decode(datos, android.util.Base64.DEFAULT))
                        true
                    } catch (e: Exception) {
                        false
                    }
                    act.runOnUiThread {
                        if (descargaBlob !== esc) return@runOnUiThread
                        if (ok) respuesta.postMessage("ok") else abortarBlob("No se pudo escribir el archivo")
                    }
                }
            }
            "fin" -> {
                val esc = descargaBlob ?: return
                descargaBlob = null
                tokenBlob = null
                ejecutor.execute {
                    val ok = esc.cerrar()
                    act.runOnUiThread {
                        if (ok) registrarLocal(esc)
                        g.avisar(if (ok) "Descargado en Descargas: " + esc.nombre else "No se pudo guardar el archivo")
                    }
                }
            }
            "err" -> abortarBlob("No se pudo leer el archivo de la página")
        }
    }

    /** Anota en la lista de Descargas un archivo guardado desde una aplicación web local. */
    private fun registrarLocal(esc: EscritorDescarga) {
        if (!ajustes.descargaRecordar) return
        val app = g.appActual()
        val origen = "Aplicación web" + (if (app != null && app.appNombre.isNotEmpty()) " · " + app.appNombre.take(30) else "")
        val ahora = System.currentTimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Android 10+: el archivo está en MediaStore; se guarda su dirección.
                ListaDescargas.agregar(RegistroDescarga(-ahora, esc.nombre, "", esc.mime, origen, ahora, "", true, esc.ubicacion, esc.bytes))
            } else {
                // Antes: se registra en el gestor de descargas del sistema para poder abrirlo y compartirlo con seguridad.
                val dm = act.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                @Suppress("DEPRECATION")
                val id = dm.addCompletedDownload(esc.nombre, esc.nombre, true, esc.mime, esc.ubicacion, esc.bytes, false)
                ListaDescargas.agregar(RegistroDescarga(id, esc.nombre, "", esc.mime, origen, ahora, ""))
            }
        } catch (e: Exception) {
            // Sin registro: el archivo está igualmente en Descargas.
        }
    }
}
