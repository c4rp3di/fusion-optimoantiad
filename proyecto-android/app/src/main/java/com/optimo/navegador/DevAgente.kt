package com.optimo.navegador

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * Puente con el agente de las herramientas de desarrollo (assets/devtools/agente.js).
 *
 * Modelo de «consulta»: la app pregunta al agente con evaluateJavascript y el agente responde con texto JSON.
 * No se usa addJavascriptInterface, así que ninguna página puede llamar a la app.
 *
 * El agente se instala de dos formas: al inicio de cada documento (si «captura desde el inicio» está activada)
 * o, al abrir el panel, inyectándolo en la página ya cargada (sin consola ni red anteriores a ese momento).
 */
class AgenteDev(contexto: Context, private val ajustes: Ajustes) {

    private val c = contexto.applicationContext
    private val h = Handler(Looper.getMainLooper())

    /** Valor aleatorio por ejecución: marca los mensajes de consola que el agente ya tiene, para no duplicarlos. */
    val nonce: String = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
    val marca: String = "\u2063" + nonce + "\u2063"

    private val guion: String by lazy {
        val t = try { c.assets.open("devtools/agente.js").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
        t.replace("__NONCE__", nonce)
    }
    private val manejadores = WeakHashMap<WebView, ScriptHandler>()
    private val soportaInicio: Boolean by lazy {
        try { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } catch (e: Throwable) { false }
    }

    /** (Re)instala el script de inicio de documento según el ajuste; llamar al crear la pestaña y al cambiar el ajuste. */
    fun instalar(web: WebView) {
        manejadores.remove(web)?.let { try { it.remove() } catch (e: Exception) { } }
        if (!ajustes.devCaptura || !soportaInicio || guion.isEmpty()) return
        try {
            manejadores[web] = WebViewCompat.addDocumentStartJavaScript(web, guion, setOf("*"))
        } catch (e: Exception) {
            // Sin captura desde el inicio: el panel lo inyecta al abrirse.
        }
    }

    /** Respaldo si el WebView no admite scripts de inicio: se inyecta al empezar la página. */
    fun alEmpezar(web: WebView) {
        if (soportaInicio || !ajustes.devCaptura || guion.isEmpty()) return
        web.evaluateJavascript(guion, null)
    }

    /** Se asegura de que el agente esté en la página (lo inyecta si falta) y avisa cuando está listo. */
    fun asegurar(web: WebView, listo: () -> Unit) {
        web.evaluateJavascript("typeof window.__optimoDev") { r ->
            if (r != null && r.contains("object")) {
                listo()
            } else if (guion.isNotEmpty()) {
                web.evaluateJavascript(guion) { listo() }
            } else {
                listo()
            }
        }
    }

    /** Llama a un método del agente. [expr] es la llamada JS sin «window.__optimoDev.», p. ej. «hijos(0)». */
    fun llamar(web: WebView, expr: String, cb: (String?) -> Unit) {
        val js = "(function(){try{var d=window.__optimoDev;if(!d)return '{\"sin\":1}';return String(d.$expr);}" +
            "catch(e){return JSON.stringify({err:String(e)});}})()"
        web.evaluateJavascript(js) { raw -> cb(DevFormato.desenvolver(raw)) }
    }

    /** Espera el resultado de una tarea asíncrona del agente (consulta cada 300 ms, hasta ~12 s). */
    fun esperar(web: WebView, id: String, cb: (JSONObject) -> Unit) {
        var intentos = 0
        val paso = object : Runnable {
            override fun run() {
                llamar(web, "leer($id)") { s ->
                    val o = DevFormato.objeto(s)
                    if (o == null) {
                        cb(JSONObject().put("err", "Sin respuesta del agente"))
                    } else if (o.has("pend")) {
                        intentos++
                        if (intentos > 40) cb(JSONObject().put("err", "Tiempo de espera agotado")) else h.postDelayed(this, 300)
                    } else {
                        cb(o)
                    }
                }
            }
        }
        h.postDelayed(paso, 200)
    }

    companion object {
        fun lit(s: String): String = JSONObject.quote(s)
    }
}
