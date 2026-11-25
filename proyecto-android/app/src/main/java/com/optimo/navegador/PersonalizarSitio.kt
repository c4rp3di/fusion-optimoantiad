package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Lo que Personalizar este sitio necesita de MainActivity. */
interface AnfitrionSitio {
    /** Prepara la navegación con los ajustes del sitio y recarga la página. */
    fun recargar(p: Pestana, sitio: String)
    fun aplicarTexto()
    fun reinstalarAnuncios()
    fun elegirElemento(p: Pestana)
    fun quitarCapa(p: Pestana)
    fun reglasDe(sitio: String)
    fun editarCss(p: Pestana, sitio: String)
    fun datosDelSitio(sitio: String)
    fun avisar(texto: String)
}

/**
 * Personalizar este sitio, como panel lateral: cómo se ve la página (escritorio, oscuro, imágenes, texto), qué se permite
 * (anuncios, emergentes, cookies de terceros, suspensión) y sus elementos (ocultar, reglas, CSS). Los valores se eligen
 * directamente en segmentos y los cambios que necesitan recargar se juntan en un único aviso «Recargar».
 */
class PanelSitio(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionSitio,
    private val p: Pestana,
    private val sitio: String
) {
    private val hoja = Hoja(act, paleta, false, true)
    private val ui = Handler(Looper.getMainLooper())
    private var pendiente = false

    fun mostrar() {
        pintar()
        hoja.mostrar()
    }

    private fun repintar() {
        ui.post { pintar() }
    }

    /** Un cambio que solo se ve al recargar: se anota y se muestra el aviso. */
    private fun cambio() {
        pendiente = true
        repintar()
    }

    private fun confirmar(titulo: String, mensaje: String, boton: String, accion: () -> Unit) {
        Dialogos.confirmar(act, paleta, false, titulo, mensaje, boton, true, accion)
    }

    private fun dpi(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    private fun pintar() {
        hoja.limpiar()
        val cab = LinearLayout(act)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = android.view.Gravity.CENTER_VERTICAL
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(act)
        t.text = "Personalizar"
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        t.setTextColor(paleta.texto)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        col.addView(t)
        val sub = TextView(act)
        sub.text = sitio + " · también sus subdominios"
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        sub.setTextColor(paleta.tenue)
        sub.setSingleLine(true)
        sub.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(sub)
        cab.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cab.addView(hoja.botonIcono("✕") { hoja.cerrar() }, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        hoja.agregar(cab)

        val propios = ajustes.resumenSitio(sitio).size
        val reglas = ReglasUsuario.totalDe(sitio)
        val css = ReglasUsuario.cssPropio(sitio)
        val insignias = ArrayList<String>()
        if (propios > 0) insignias.add(propios.toString() + (if (propios == 1) " ajuste propio" else " ajustes propios"))
        if (reglas > 0) insignias.add(reglas.toString() + (if (reglas == 1) " regla" else " reglas"))
        if (css.isNotBlank()) insignias.add("CSS propio")
        hoja.insignias(insignias)

        if (pendiente) {
            hoja.avisoPendiente("Cambios pendientes", "Recargar") {
                pendiente = false
                g.recargar(p, sitio)
                repintar()
            }
        }

        // ---- ver la página
        hoja.seccion("Ver la página")
        hoja.interruptor("Versión de escritorio", null, ajustes.escritorioEn(sitio)) { v ->
            ajustes.fijarEscritorioEn(sitio, v)
            cambio()
        }
        hoja.rotulo("Modo oscuro")
        hoja.segmentos(listOf("Auto", "Siempre", "Nunca"), ajustes.modoOscuroEn(sitio)) { i ->
            ajustes.fijarModoOscuroEn(sitio, i)
            cambio()
        }
        hoja.rotulo("Imágenes")
        hoja.segmentos(listOf("Según ajustes", "Siempre", "Nunca"), ajustes.imagenesEn(sitio)) { i ->
            ajustes.fijarImagenesEn(sitio, i)
            cambio()
        }
        val zoom = ajustes.textoZoomEfectivo(sitio)
        val propioZoom = ajustes.textoZoomEn(sitio) > 0
        val cz = LinearLayout(act)
        cz.orientation = LinearLayout.HORIZONTAL
        cz.setPadding(dpi(4), dpi(10), dpi(4), 0)
        val et = TextView(act)
        et.text = "Tamaño del texto" + (if (propioZoom) "" else " (el general)")
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        et.setTextColor(paleta.texto)
        cz.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val num = TextView(act)
        num.text = zoom.toString() + " %"
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        num.setTextColor(paleta.acento)
        num.typeface = android.graphics.Typeface.DEFAULT_BOLD
        cz.addView(num)
        hoja.agregar(cz)
        hoja.deslizador(50, 200, 10, zoom, { v -> num.text = v.toString() + " %" }, { v ->
            ajustes.fijarTextoZoomEn(sitio, v)
            g.aplicarTexto()
            repintar()
        })
        if (propioZoom) {
            hoja.fila("Usar el tamaño general (" + ajustes.textoZoom + " %)") {
                ajustes.fijarTextoZoomEn(sitio, 0)
                g.aplicarTexto()
                repintar()
            }
        }

        // ---- qué se permite
        hoja.seccion("Qué se permite")
        if (ajustes.bloquearAnuncios || ajustes.bloqueoPausadoEn(sitio)) {
            hoja.interruptor("Bloqueo de anuncios", "Quítalo si el sitio no funciona bien", !ajustes.bloqueoPausadoEn(sitio)) { v ->
                ajustes.pausarBloqueoEn(sitio, !v)
                g.reinstalarAnuncios()
                cambio()
            }
        }
        hoja.rotulo("Ventanas emergentes")
        hoja.segmentos(listOf("Ajustes", "Normal", "Estricto", "Todas"), ajustes.emergentesEn(sitio) + 1) { i ->
            ajustes.fijarEmergentesEn(sitio, i - 1)
            repintar()
        }
        hoja.rotulo("Cookies de terceros")
        hoja.segmentos(listOf("Ajustes", "Permitir", "Bloquear"), ajustes.cookiesTercerosModoEn(sitio)) { i ->
            ajustes.fijarCookiesTercerosEn(sitio, i)
            cambio()
        }
        if (ajustes.suspenderMin > 0 || ajustes.noSuspenderEn(sitio)) {
            hoja.interruptor("No suspender por inactividad", "Para que no se duerma al cambiar de pestaña", ajustes.noSuspenderEn(sitio)) { v ->
                ajustes.fijarNoSuspenderEn(sitio, v)
                repintar()
            }
        }

        // ---- elementos
        hoja.seccion("Elementos de la página")
        hoja.botonGrande("👆", "Ocultar o cambiar un elemento", "Toca algo de la página") {
            hoja.cerrar()
            g.elegirElemento(p)
        }
        hoja.fila("Quitar capa que tapa la página", "Avisos, muros anti-bloqueador, ventanas modales", icono = "🧹") {
            hoja.cerrar()
            g.quitarCapa(p)
        }
        val ocultar = ReglasUsuario.listaOcultar().filter { it.host == sitio }
        hoja.fila("Reglas de este sitio", "Ocultar, estilos y permisos de dominios", reglas.toString(), icono = "📋") {
            hoja.cerrar()
            g.reglasDe(sitio)
        }
        for (r in ocultar.take(3)) {
            hoja.fila(r.sel, null, "✕", aviso = true, icono = "🙈") {
                confirmar("Quitar regla", "Dejará de ocultarse «" + r.sel + "» en " + sitio + ".", "Quitar") {
                    ReglasUsuario.quitarOcultar(r)
                    g.reinstalarAnuncios()
                    cambio()
                }
            }
        }
        if (ocultar.size > 3) hoja.nota("Y " + (ocultar.size - 3) + " más en «Reglas de este sitio».")
        val lineas = if (css.isBlank()) 0 else css.lines().size
        hoja.fila("CSS propio", "Estilos que escribes tú para este sitio", if (lineas == 0) "vacío" else lineas.toString() + (if (lineas == 1) " línea" else " líneas"), icono = "🎨") {
            hoja.cerrar()
            g.editarCss(p, sitio)
        }

        // ---- pie
        hoja.seccion("Este sitio")
        hoja.fila("Datos del sitio", "Cookies, permisos, historial y almacenamiento", icono = "🗂") {
            hoja.cerrar()
            g.datosDelSitio(sitio)
        }
        hoja.boton("Restablecer ajustes de este sitio…", true) {
            confirmar(
                "Restablecer " + sitio,
                "Escritorio, modo oscuro, emergentes, cookies de terceros, imágenes, tamaño del texto, suspensión y pausa del bloqueo vuelven a los ajustes generales. Las reglas, el CSS y los permisos no se tocan.",
                "Restablecer"
            ) {
                ajustes.restablecerSitio(sitio)
                g.reinstalarAnuncios()
                g.aplicarTexto()
                cambio()
            }
        }
    }
}
