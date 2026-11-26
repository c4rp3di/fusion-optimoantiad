package com.optimo.navegador

import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Lo que las capas y el editor de CSS necesitan de MainActivity. */
interface AnfitrionCapas {
    fun scriptMuros(): String
    fun aplicarCss(web: WebView, css: String)
    fun reinstalarAnuncios()
    fun avisar(texto: String)
}

/**
 * Quitar la capa que tapa la página (avisos de cookies, muros anti-bloqueador, ventanas modales, desplazamiento bloqueado) y
 * el editor de CSS. Las capas se detectan en la página; se marcan las más probables y se ocultan siempre en el sitio o solo
 * hasta recargar. La página queda a la vista (apenas se oscurece).
 */
class CapasPagina(
    private val act: Activity,
    private val paleta: Paleta,
    private val g: AnfitrionCapas
) {
    private class Capa(val sel: String, val etiqueta: String, var marcada: Boolean)

    private val cssScroll = "html,body{overflow:auto!important}"

    fun quitar(p: Pestana) {
        val w = p.web
        if (w == null || p.inicio) {
            g.avisar("Abre primero una página")
            return
        }
        val paginaHost = (try { Uri.parse(w.url ?: p.url).host } catch (e: Exception) { null } ?: "").lowercase()
        val sitio = ReglasUsuario.clave(paginaHost)
        if (sitio.isEmpty()) return
        w.evaluateJavascript(g.scriptMuros()) { crudo ->
            var muros: JSONArray? = null
            var scrollBloqueado = false
            try {
                val v = JSONTokener(crudo ?: "null").nextValue()
                if (v is String) {
                    val o = JSONObject(v)
                    muros = o.optJSONArray("muros")
                    scrollBloqueado = o.optBoolean("scrollBloqueado")
                }
            } catch (e: Exception) {
                muros = null
            }
            if (muros == null || muros.length() == 0) {
                if (scrollBloqueado) sinCapasConScroll(w, p, sitio) else {
                    g.avisar("No he encontrado ninguna capa que tape la página. Prueba «Ocultar un elemento» en Aspecto")
                }
            } else {
                val capas = ArrayList<Capa>()
                for (i in 0 until muros.length()) {
                    val m = muros.getJSONObject(i)
                    val texto = m.optString("texto")
                    capas.add(
                        Capa(
                            m.optString("sel"),
                            m.optString("tag") + " " + m.optInt("w") + "×" + m.optInt("h") + (if (texto.isNotEmpty()) " · «" + texto + "»" else ""),
                            m.optBoolean("fuerte")
                        )
                    )
                }
                mostrar(w, p, sitio, capas, scrollBloqueado)
            }
        }
    }

    private fun sinCapasConScroll(w: WebView, p: Pestana, sitio: String) {
        val h = Hoja(act, paleta, p.privada)
        h.atenuar(0.12f)
        h.encabezado("No veo ninguna capa")
        h.parrafo("La página tiene el desplazamiento bloqueado. ¿Quieres desbloquearlo?")
        h.boton("Desbloquear siempre aquí") {
            h.cerrar()
            ReglasUsuario.agregarCss(sitio, "Desbloquear desplazamiento", cssScroll)
            g.reinstalarAnuncios()
            g.aplicarCss(w, cssScroll)
        }
        h.boton("Solo ahora") {
            h.cerrar()
            g.aplicarCss(w, cssScroll)
        }
        h.boton("Cancelar") { h.cerrar() }
        h.mostrar()
    }

    private fun mostrar(w: WebView, p: Pestana, sitio: String, capas: List<Capa>, scrollBloqueado: Boolean) {
        val hoja = Hoja(act, paleta, p.privada)
        hoja.atenuar(0.12f)
        var desbloquear = true

        fun aplicar(guardar: Boolean) {
            var hechos = 0
            for (c in capas) {
                if (!c.marcada || c.sel.isEmpty()) continue
                hechos++
                if (guardar) ReglasUsuario.agregarOcultar(sitio, c.sel)
                g.aplicarCss(w, c.sel + "{display:none!important}")
            }
            if (desbloquear && (hechos > 0 || scrollBloqueado)) {
                if (guardar) ReglasUsuario.agregarCss(sitio, "Desbloquear desplazamiento", cssScroll)
                g.aplicarCss(w, cssScroll)
            }
            if (guardar) g.reinstalarAnuncios()
            g.avisar(
                if (hechos == 0) "No marcaste ninguna capa"
                else if (guardar) "Capas ocultas en $sitio. Se mantendrá en tus visitas"
                else "Capas ocultas hasta recargar"
            )
        }

        fun pintar() {
            hoja.limpiar()
            hoja.encabezado("Capas que tapan la página")
            hoja.nota("Marca las que quieres ocultar; las más probables ya vienen marcadas.")
            for (c in capas) {
                hoja.fila(c.etiqueta, null, null, false, if (c.marcada) "☑" else "☐") {
                    c.marcada = !c.marcada
                    pintar()
                }
            }
            hoja.interruptor("Desbloquear el desplazamiento", "Por si la capa deja la página sin poder moverse", desbloquear) { v -> desbloquear = v }
            hoja.boton("Ocultar siempre en " + sitio) {
                hoja.cerrar()
                aplicar(true)
            }
            hoja.boton("Solo ahora") {
                hoja.cerrar()
                aplicar(false)
            }
            hoja.boton("Cancelar") { hoja.cerrar() }
        }
        pintar()
        hoja.mostrar()
    }

    /**
     * Editor de CSS en una hoja: [probar] (opcional) aplica el texto a la página sin guardar; «Borrar» aparece si había algo
     * guardado y [puedeBorrar]. [alGuardar] recibe el texto (vacío = borrar).
     */
    fun editarCss(
        privada: Boolean, titulo: String, inicial: String, pista: String, puedeBorrar: Boolean,
        probar: ((String) -> Unit)?, alGuardar: (String) -> Unit
    ) {
        val h = Hoja(act, paleta, privada)
        h.teclado(true)
        h.encabezado(titulo)
        val campo = h.campoMultilinea(pista, inicial, 7)
        h.nota("Usa !important si el cambio no se nota.")
        if (probar != null) h.boton("Probar sin guardar") { probar(campo.text.toString()) }
        h.boton("Guardar") {
            h.cerrar()
            alGuardar(campo.text.toString())
        }
        if (puedeBorrar && inicial.isNotEmpty()) {
            h.boton("Borrar el CSS", true) {
                h.cerrar()
                alGuardar("")
            }
        }
        h.boton("Cancelar") { h.cerrar() }
        h.mostrar()
    }
}
