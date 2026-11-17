package com.optimo.navegador

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * Silenciar pestañas. El WebView de Android no ofrece un «mute» propio, así que se hace desde la página con
 * assets/audio/silenciar.js (medios HTML, Web Audio e iframes). El estado se «hornea» en el script de inicio
 * de documento: cada cambio lo vuelve a registrar, de modo que las páginas que se abran después en esa pestaña
 * ya nacen silenciadas; y se aplica en vivo a la página actual con evaluateJavascript.
 */
class Silenciador(contexto: Context) {

    private val c = contexto.applicationContext

    private val plantilla: String by lazy {
        try { c.assets.open("audio/silenciar.js").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
    }
    private val manejadores = WeakHashMap<WebView, ScriptHandler>()
    private val soportaInicio: Boolean by lazy {
        try { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } catch (e: Throwable) { false }
    }

    private fun guion(silenciada: Boolean): String = plantilla.replace("__MUTE__", if (silenciada) "true" else "false")

    /** (Re)registra el script de inicio con el estado actual. Llamar al crear el WebView y en cada cambio. */
    fun instalar(web: WebView, silenciada: Boolean) {
        manejadores.remove(web)?.let { try { it.remove() } catch (e: Exception) { } }
        if (!soportaInicio || plantilla.isEmpty()) return
        // Una pestaña sin silenciar no necesita script: se registra solo la primera vez que se silencia.
        if (!silenciada) return
        try {
            manejadores[web] = WebViewCompat.addDocumentStartJavaScript(web, guion(true), setOf("*"))
        } catch (e: Exception) {
            // Sin script de inicio: alEmpezar() lo inyecta al comenzar cada página.
        }
    }

    /** Cambia el estado de la pestaña: páginas futuras (script de inicio) y la actual (en vivo). */
    fun poner(web: WebView, silenciada: Boolean) {
        instalar(web, silenciada)
        if (plantilla.isEmpty()) return
        if (silenciada) {
            // El script puede no estar en la página actual (se registró después de cargarla): se inyecta ya.
            web.evaluateJavascript(guion(true), null)
        } else {
            web.evaluateJavascript("window.__optimoSetMute&&window.__optimoSetMute(false)", null)
        }
    }

    /** Respaldo si el WebView no admite scripts de inicio de documento. */
    fun alEmpezar(web: WebView, silenciada: Boolean) {
        if (soportaInicio || !silenciada || plantilla.isEmpty()) return
        web.evaluateJavascript(guion(true), null)
    }
}
