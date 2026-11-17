package com.optimo.navegador

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Regla «ocultar elemento»: en el sitio [host] (y subdominios) se oculta lo que encaje con [sel]. */
class ReglaOcultar(val host: String, val sel: String)

/** Regla de estilo: en el sitio [host] (y subdominios) se aplica la hoja CSS [css]. [nombre] es la etiqueta que ve el usuario. */
class ReglaCss(val host: String, val nombre: String, val css: String)

/**
 * Reglas que añade el usuario (al mantener pulsado un anuncio o desde «Personalizar este sitio»). Se guardan en un JSON pequeño.
 *  - Ocultar elemento: selector CSS por sitio.
 *  - Estilo: CSS por sitio (apariencia de un elemento elegido o CSS propio del sitio).
 *  - Bloquear dominio: vale para todas las webs y gana a las excepciones de las listas.
 * Las lecturas (desde hilos de red) usan instantáneas inmutables; las escrituras van en el hilo principal.
 */
object ReglasUsuario {

    @Volatile
    private var ocultar: List<ReglaOcultar> = emptyList()

    @Volatile
    private var estilos: List<ReglaCss> = emptyList()

    @Volatile
    private var dominios: Set<String> = emptySet()

    private var archivo: File? = null

    fun iniciar(c: Context) {
        archivo = File(c.applicationContext.filesDir, "reglas_usuario.json")
        cargar()
    }

    val total: Int get() = ocultar.size + estilos.size + dominios.size

    fun listaOcultar(): List<ReglaOcultar> = ocultar

    fun listaDominios(): List<String> = dominios.sorted()

    fun listaCss(): List<ReglaCss> = estilos

    /** Reglas (ocultar + estilo) guardadas exactamente para el sitio [host] (ya normalizado con [clave]). */
    fun totalDe(host: String): Int = ocultar.count { it.host == host } + estilos.count { it.host == host }

    /** Nombre de la hoja CSS propia de un sitio (la que se edita a mano desde «Personalizar este sitio»). */
    const val NOMBRE_CSS_PROPIO = "CSS propio"

    private const val MAX_CSS = 20000

    fun agregarCss(host: String, nombre: String, css: String): Boolean {
        val h = clave(host)
        val c = css.trim()
        if (h.isEmpty() || c.isEmpty() || c.length > MAX_CSS) return false
        if (estilos.any { it.host == h && it.css == c }) return false
        estilos = estilos + ReglaCss(h, nombre.take(120), c)
        guardar()
        return true
    }

    fun cssPropio(host: String): String {
        val h = clave(host)
        return estilos.firstOrNull { it.host == h && it.nombre == NOMBRE_CSS_PROPIO }?.css ?: ""
    }

    /** Fija (o, si [css] está vacío, borra) el CSS propio del sitio. Devuelve false si es demasiado largo. */
    fun fijarCssPropio(host: String, css: String): Boolean {
        val h = clave(host)
        val c = css.trim()
        if (h.isEmpty() || c.length > MAX_CSS) return false
        estilos = estilos.filter { !(it.host == h && it.nombre == NOMBRE_CSS_PROPIO) }
        if (c.isNotEmpty()) estilos = estilos + ReglaCss(h, NOMBRE_CSS_PROPIO, c)
        guardar()
        return true
    }

    fun quitarCss(r: ReglaCss) {
        estilos = estilos.filter { it !== r }
        guardar()
    }

    /** Hojas CSS del usuario que aplican a la página en [host]. */
    fun cssPara(host: String): List<String> {
        val h = host.lowercase()
        return estilos.filter { h == it.host || h.endsWith("." + it.host) }.map { it.css }
    }

    /** Sitio normalizado: minúsculas y sin «www.» (la regla vale también para sus subdominios). */
    fun clave(host: String): String = host.lowercase().removePrefix("www.")

    fun agregarOcultar(host: String, sel: String): Boolean {
        val h = clave(host)
        if (h.isEmpty() || sel.isBlank()) return false
        if (ocultar.any { it.host == h && it.sel == sel }) return false
        ocultar = ocultar + ReglaOcultar(h, sel)
        guardar()
        return true
    }

    fun agregarDominio(dominio: String): Boolean {
        val d = UtilDominio.normalizar(dominio.removePrefix("www.")) ?: return false
        if (dominios.contains(d)) return false
        dominios = dominios + d
        guardar()
        return true
    }

    fun quitarOcultar(r: ReglaOcultar) {
        ocultar = ocultar.filter { it !== r }
        guardar()
    }

    fun quitarDominio(d: String) {
        dominios = dominios - d
        guardar()
    }

    /** Selectores de ocultación que aplican a la página en [host]. */
    fun selectoresPara(host: String): List<String> {
        val h = host.lowercase()
        return ocultar.filter { h == it.host || h.endsWith("." + it.host) }.map { it.sel }
    }

    fun hostsConReglas(): List<String> = (ocultar.map { it.host } + estilos.map { it.host }).distinct()

    /** True si [host] o alguno de sus dominios padre está en los dominios bloqueados por el usuario. */
    fun bloqueaDominio(host: String): Boolean {
        val set = dominios
        if (set.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (set.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (h.indexOf('.') < 0) return false
        }
    }

    private fun cargar() {
        try {
            val f = archivo ?: return
            val texto = AlmacenSeguro.leerTexto(f) ?: return
            val j = JSONObject(texto)
            val o = ArrayList<ReglaOcultar>()
            j.optJSONArray("ocultar")?.let {
                for (i in 0 until it.length()) {
                    val x = it.getJSONObject(i)
                    o.add(ReglaOcultar(x.getString("host"), x.getString("sel")))
                }
            }
            val e = ArrayList<ReglaCss>()
            j.optJSONArray("estilos")?.let {
                for (i in 0 until it.length()) {
                    val x = it.getJSONObject(i)
                    e.add(ReglaCss(x.getString("host"), x.optString("nombre"), x.getString("css")))
                }
            }
            val d = HashSet<String>()
            j.optJSONArray("dominios")?.let { for (i in 0 until it.length()) d.add(it.getString(i)) }
            ocultar = o
            estilos = e
            dominios = d
        } catch (e: Exception) {
            // Archivo dañado: se empieza sin reglas.
        }
    }

    private fun guardar() {
        try {
            val f = archivo ?: return
            val j = JSONObject()
            val o = JSONArray()
            for (r in ocultar) o.put(JSONObject().put("host", r.host).put("sel", r.sel))
            j.put("ocultar", o)
            val e = JSONArray()
            for (r in estilos) e.put(JSONObject().put("host", r.host).put("nombre", r.nombre).put("css", r.css))
            j.put("estilos", e)
            val d = JSONArray()
            for (x in dominios.sorted()) d.put(x)
            j.put("dominios", d)
            AlmacenSeguro.escribirTexto(f, j.toString())
        } catch (e: Exception) {
            // Sin espacio o sin permiso: se pierde solo esta escritura.
        }
    }
}

