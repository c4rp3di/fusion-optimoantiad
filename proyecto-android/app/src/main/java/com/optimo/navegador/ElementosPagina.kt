package com.optimo.navegador

import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/** Lo que el selector de elementos necesita de MainActivity. */
interface AnfitrionElementos {
    fun scriptElemento(x: Float, y: Float, nivel: Int): String
    fun escala(web: WebView): Float
    fun aplicarReglas(web: WebView, host: String)
    fun aplicarCss(web: WebView, css: String)
    fun reinstalarAnuncios()
    fun editarCss(titulo: String, inicial: String, pista: String, conAyuda: Boolean, alGuardar: (String) -> Unit)
    fun avisar(texto: String)
    fun avisarConAccion(texto: String, boton: String, accion: () -> Unit)
}

/**
 * Elegir un elemento de la página tocándolo y decidir qué hacer con él. La página sigue a la vista con el elemento resaltado y
 * una hoja pequeña (arriba si el elemento está abajo) ofrece, en este orden: ocultarlo siempre en el sitio, solo ahora, cambiar su
 * apariencia, bloquear su origen y ver o editar su selector, con botones para elegir el contenedor o algo más pequeño.
 */
class ElementosPagina(
    private val act: Activity,
    private val paleta: Paleta,
    private val g: AnfitrionElementos
) {
    companion object {
        private val APARIENCIAS = listOf(
            Pair("Ampliar un 25 %", "§{zoom:1.25!important}"),
            Pair("Reducir al 80 %", "§{zoom:.8!important}"),
            Pair("Texto más grande", "§{font-size:130%!important}"),
            Pair("Fondo oscuro, texto claro", "§,§ *{background:#121212!important;color:#e8e8e8!important;border-color:#444!important}"),
            Pair("Fondo claro, texto oscuro", "§,§ *{background:#ffffff!important;color:#202124!important}"),
            Pair("Quitar fondos e imágenes de fondo", "§,§ *{background:none!important}"),
            Pair("Ancho completo", "§{width:100%!important;max-width:100%!important;margin-left:0!important;margin-right:0!important}"),
            Pair("Semitransparente (30 %)", "§{opacity:.3!important}"),
            Pair("Soltarlo de la pantalla (barras fijas)", "§{position:static!important}"),
            Pair("Quitar sombras y bordes", "§{box-shadow:none!important;border:none!important;text-shadow:none!important}")
        )
    }

    /** Lo que se sabe del elemento elegido. */
    private class Datos(var sel: String, var tag: String, var w: Int, var h: Int, var n: Int, var hayPadre: Boolean, var texto: String, var dom: String)

    private fun analizar(crudo: String?): JSONObject? {
        return try {
            val v = JSONTokener(crudo ?: "null").nextValue()
            if (v is String) JSONObject(v) else null
        } catch (e: Exception) {
            null
        }
    }

    fun quitarMarca(web: WebView) {
        web.evaluateJavascript(
            "(function(){var m=window.__optimoMarca;if(m){try{m.el.style.outline=m.o;m.el.style.outlineOffset=m.oo;}catch(e){}window.__optimoMarca=null;}})()",
            null
        )
    }

    private fun hostDe(web: WebView, p: Pestana): String =
        (try { Uri.parse(web.url ?: p.url).host } catch (e: Exception) { null } ?: "").lowercase()

    /** Identifica el elemento en (x, y) —píxeles de pantalla—, subiendo [nivel] contenedores, y abre la hoja. */
    fun elegir(web: WebView, p: Pestana, x: Float, y: Float, nivel: Int) {
        val escala = g.escala(web)
        web.evaluateJavascript(g.scriptElemento(x / escala, y / escala, nivel)) { crudo ->
            val d = analizar(crudo)
            if (d == null) g.avisar("No he podido seleccionar nada ahí") else mostrar(web, p, x, y, nivel, d, null, false)
        }
    }

    /** Resultado de tocar un anuncio (script de anuncios, con su dominio de origen): misma hoja, sin niveles. */
    fun ofrecerAnuncio(web: WebView, p: Pestana, imagen: String?, d: JSONObject, x: Float, y: Float) {
        mostrar(web, p, x, y, 0, d, imagen, true)
    }

    private fun mostrar(web: WebView, p: Pestana, x: Float, y: Float, nivel0: Int, j: JSONObject, imagen: String?, anuncio: Boolean) {
        val paginaHost = hostDe(web, p)
        val sel0 = j.optString("sel")
        if (paginaHost.isEmpty() || sel0.isEmpty()) {
            quitarMarca(web)
            g.avisar("No se pudo crear la regla para esta página")
            return
        }
        val sitio = ReglasUsuario.clave(paginaHost)
        val d = Datos(sel0, j.optString("tag"), j.optInt("w"), j.optInt("h"), j.optInt("n", 1), j.optBoolean("hayPadre"), j.optString("texto"), "")
        var dom = j.optString("dom").lowercase().removePrefix("www.")
        if (dom.isEmpty() && imagen != null) {
            dom = (try { Uri.parse(imagen).host } catch (e: Exception) { null } ?: "").lowercase().removePrefix("www.")
        }
        d.dom = dom
        // Nunca se ofrece bloquear el propio dominio de la página (o uno emparentado): rompería el sitio entero.
        val propio = dom.isEmpty() || dom == paginaHost || paginaHost.endsWith(".$dom") || dom.endsWith(".$paginaHost") ||
            UtilDominio.normalizar(dom) == null
        val abajo = y > act.resources.displayMetrics.heightPixels * 0.5f
        var nivel = nivel0
        val hoja = Hoja(act, paleta, p.privada)
        hoja.atenuar(0.12f)
        if (abajo) hoja.anclarArriba()

        fun cambiarNivel(nuevo: Int) {
            val escala = g.escala(web)
            web.evaluateJavascript(g.scriptElemento(x / escala, y / escala, nuevo)) { crudo ->
                val n = analizar(crudo)
                if (n == null) {
                    g.avisar(if (nuevo > nivel) "No hay un contenedor mayor" else "No se pudo elegir")
                } else {
                    d.sel = n.optString("sel")
                    d.tag = n.optString("tag")
                    d.w = n.optInt("w")
                    d.h = n.optInt("h")
                    d.n = n.optInt("n", 1)
                    d.hayPadre = n.optBoolean("hayPadre")
                    d.texto = n.optString("texto")
                    nivel = nuevo
                    pintar(hoja, web, p, d, anuncio, sitio, paginaHost, propio, nivel, ::cambiarNivel)
                }
            }
        }
        arribaActual = abajo
        hoja.alCerrar { quitarMarca(web) }
        pintar(hoja, web, p, d, anuncio, sitio, paginaHost, propio, nivel, ::cambiarNivel)
        hoja.mostrar()
    }

    /** True si la hoja actual está anclada arriba (el elemento elegido estaba en la mitad baja). */
    private var arribaActual = false

    private fun pintar(
        hoja: Hoja, web: WebView, p: Pestana, d: Datos, anuncio: Boolean, sitio: String, paginaHost: String,
        propio: Boolean, nivel: Int, cambiarNivel: (Int) -> Unit
    ) {
        hoja.limpiar()
        hoja.encabezado(if (anuncio) "Bloquear este anuncio" else "Elemento seleccionado")
        val dim = if (d.w > 0 && d.h > 0) " · " + d.w + "×" + d.h else ""
        hoja.parrafo(d.tag + dim + (if (d.texto.isNotEmpty()) "\n«" + d.texto + "»" else ""))
        if (d.n > 1) hoja.nota("⚠ Coincide con " + d.n + " elementos de la página: todos se ocultarían. Prueba con «Más pequeño» o «Más grande».")
        if (d.n == 0) hoja.nota("⚠ El selector no coincide con nada en esta página.")
        if (!anuncio) {
            val tamanos = ArrayList<Triple<String, Boolean, () -> Unit>>()
            if (d.hayPadre) tamanos.add(Triple("▲ Más grande", false, { cambiarNivel(nivel + 1) }))
            if (nivel > 0) tamanos.add(Triple("▼ Más pequeño", false, { cambiarNivel(nivel - 1) }))
            if (tamanos.isNotEmpty()) hoja.barraAcciones("Lo resaltado en naranja", tamanos)
        }

        hoja.seccion("Qué hacer")
        hoja.fila("Ocultar siempre en " + sitio, "Se mantiene cada vez que entres", icono = "👁") {
            hoja.cerrar()
            if (ReglasUsuario.agregarOcultar(sitio, d.sel)) {
                g.reinstalarAnuncios()
                g.aplicarReglas(web, paginaHost)
                g.avisarConAccion("Oculto en " + sitio, "Deshacer") {
                    // Se quita la regla y se recarga para que el elemento vuelva a verse (las hojas inyectadas no se pueden retirar una a una).
                    ReglasUsuario.quitarOcultar(sitio, d.sel)
                    g.reinstalarAnuncios()
                    try {
                        web.reload()
                    } catch (e: Exception) {
                        // La pestaña ya se cerró.
                    }
                    g.avisar("Deshecho")
                }
            } else {
                g.avisar("Ya estaba oculto")
            }
        }
        hoja.fila("Ocultar solo ahora", "Hasta que recargues la página", icono = "⏱") {
            hoja.cerrar()
            g.aplicarCss(web, d.sel + "{display:none!important}")
            g.avisar("Oculto hasta que recargues")
        }
        hoja.fila("Cambiar su apariencia…", "Tamaño, colores, transparencia, posición", icono = "🎨") {
            hoja.cerrar()
            apariencia(web, p, sitio, d.sel, arribaActual)
        }
        if (!propio) {
            hoja.fila("Bloquear su origen: " + d.dom, "Bloquea ese dominio en todas las páginas", aviso = true, icono = "🚫") {
                hoja.cerrar()
                g.avisar(if (ReglasUsuario.agregarDominio(d.dom)) "Dominio bloqueado: " + d.dom + ". Recarga la página" else "Ya estaba bloqueado")
            }
        }
        hoja.fila("Ver y editar el selector", d.sel, icono = "✎") {
            Dialogos.texto(act, paleta, p.privada, "Selector CSS", d.sel) { nuevo ->
                web.evaluateJavascript(
                    "(function(s){try{return document.querySelectorAll(s).length}catch(e){return -1}})(" + JSONObject.quote(nuevo) + ")"
                ) { r ->
                    val n = r?.trim()?.toIntOrNull() ?: -1
                    if (n < 0) {
                        g.avisar("Selector no válido")
                    } else {
                        d.sel = nuevo
                        d.n = n
                        d.tag = "selector"
                        d.w = 0
                        d.h = 0
                        d.texto = ""
                        d.hayPadre = false
                        pintar(hoja, web, p, d, anuncio, sitio, paginaHost, propio, nivel, cambiarNivel)
                    }
                }
            }
        }
        hoja.boton("Cancelar") { hoja.cerrar() }
    }

    /** Lista de apariencias para el elemento; se guarda como regla de estilo del sitio y se aplica al momento. */
    private fun apariencia(web: WebView, p: Pestana, sitio: String, sel: String, arriba: Boolean) {
        val nombres = ArrayList<String>()
        for (a in APARIENCIAS) nombres.add(a.first)
        nombres.add("Escribir CSS propio…")
        fun guardar(nombre: String, css: String) {
            quitarMarca(web)
            if (ReglasUsuario.agregarCss(sitio, nombre + ": " + sel, css)) {
                g.reinstalarAnuncios()
                g.aplicarCss(web, css)
                g.avisar("Aplicado en " + sitio + ". Se mantendrá en tus visitas")
            } else {
                g.avisar("No se añadió (ya existía o es demasiado largo)")
            }
        }
        Dialogos.elegirEnPagina(act, paleta, p.privada, "Apariencia del elemento", nombres, arriba, { quitarMarca(web) }) { i ->
            if (i < APARIENCIAS.size) {
                guardar(APARIENCIAS[i].first, APARIENCIAS[i].second.replace("§", sel))
            } else {
                quitarMarca(web)
                g.editarCss("CSS del elemento", sel + " {\n  \n}", "propiedad: valor !important;", false) { css -> guardar("CSS propio", css) }
            }
        }
    }
}
