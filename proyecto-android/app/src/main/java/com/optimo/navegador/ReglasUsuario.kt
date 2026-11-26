package com.optimo.navegador

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Regla «ocultar elemento»: en el sitio [host] (y subdominios) se oculta lo que encaje con [sel]. */
class ReglaOcultar(val host: String, val sel: String)

/** Regla de estilo: en el sitio [host] (y subdominios) se aplica la hoja CSS [css]. [nombre] es la etiqueta que ve el usuario. */
class ReglaCss(val host: String, val nombre: String, val css: String)

/** Excepción de bloqueo: en las páginas de [sitio] («*» = todas) se deja pasar [dominio] (y sus subdominios). */
class ReglaPermiso(val sitio: String, val dominio: String)

/**
 * Reglas que añade el usuario (al mantener pulsado un anuncio o desde «Personalizar este sitio»). Se guardan en un JSON pequeño.
 *  - Ocultar elemento: selector CSS por sitio.
 *  - Estilo: CSS por sitio (apariencia de un elemento elegido o CSS propio del sitio).
 *  - Bloquear dominio: vale para todas las webs y gana a las excepciones de las listas.
 *  - Permitir dominio: excepción por sitio (o para todos) que gana a todo lo demás en la red: ni se bloquea ni se sustituye.
 * Las lecturas (desde hilos de red) usan instantáneas inmutables; las escrituras van en el hilo principal.
 */
object ReglasUsuario {

    @Volatile
    private var ocultar: List<ReglaOcultar> = emptyList()

    @Volatile
    private var estilos: List<ReglaCss> = emptyList()

    @Volatile
    private var dominios: Set<String> = emptySet()

    @Volatile
    private var permisos: List<ReglaPermiso> = emptyList()

    private var archivo: File? = null

    fun iniciar(c: Context) {
        archivo = File(c.applicationContext.filesDir, "reglas_usuario.json")
        cargar()
    }

    val total: Int get() = ocultar.size + estilos.size + dominios.size + permisos.size

    fun listaOcultar(): List<ReglaOcultar> = ocultar

    fun listaDominios(): List<String> = dominios.sorted()

    fun listaCss(): List<ReglaCss> = estilos

    /** Reglas (ocultar + estilo) guardadas exactamente para el sitio [host] (ya normalizado con [clave]). */
    fun totalDe(host: String): Int = ocultar.count { it.host == host } + estilos.count { it.host == host } + permisos.count { it.sitio == host }

    /** «Sitio» de las excepciones que valen para todas las páginas. */
    const val TODOS = "*"

    fun listaPermisos(): List<ReglaPermiso> = permisos

    /** Permite [dominio] en las páginas de [sitio] (o en todas con [TODOS]). Devuelve false si no es válido o ya estaba. */
    fun agregarPermiso(sitio: String, dominio: String): Boolean {
        val s = if (sitio == TODOS) TODOS else clave(sitio)
        val d = UtilDominio.normalizar(dominio.removePrefix("www.")) ?: return false
        if (s.isEmpty()) return false
        if (permisos.any { it.sitio == s && it.dominio == d }) return false
        permisos = permisos + ReglaPermiso(s, d)
        guardar()
        return true
    }

    fun quitarPermiso(r: ReglaPermiso) {
        permisos = permisos.filter { it !== r }
        guardar()
    }

    /** ¿Está permitido cargar [host] estando en la página [paginaHost]? Se llama por cada petición, desde hilos de red. */
    fun permitido(paginaHost: String, host: String): Boolean {
        val ps = permisos
        if (ps.isEmpty()) return false
        val pg = paginaHost.lowercase()
        val h = host.lowercase()
        for (r in ps) {
            if (h != r.dominio && !h.endsWith("." + r.dominio)) continue
            if (r.sitio == TODOS || pg == r.sitio || pg.endsWith("." + r.sitio)) return true
        }
        return false
    }

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

    /** Quita la regla de ocultar [sel] en [host] (para «Deshacer» justo después de crearla). */
    fun quitarOcultar(host: String, sel: String) {
        val h = clave(host)
        ocultar = ocultar.filter { !(it.host == h && it.sel == sel) }
        guardar()
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
            val pm = ArrayList<ReglaPermiso>()
            j.optJSONArray("permisos")?.let {
                for (i in 0 until it.length()) {
                    val x = it.getJSONObject(i)
                    pm.add(ReglaPermiso(x.getString("sitio"), x.getString("dominio")))
                }
            }
            ocultar = o
            estilos = e
            dominios = d
            permisos = pm
        } catch (e: Exception) {
            // Archivo dañado: se empieza sin reglas.
        }
    }

    /** Reglas para la copia de seguridad (mismo formato que el archivo de reglas). */
    fun exportarJson(): JSONObject {
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
        val pm = JSONArray()
        for (r in permisos) pm.put(JSONObject().put("sitio", r.sitio).put("dominio", r.dominio))
        j.put("permisos", pm)
        return j
    }

    /** Añade las reglas de una copia a las que ya hay (las repetidas se saltan). Devuelve cuántas se añadieron. */
    fun importarJson(j: JSONObject): Int {
        var n = 0
        val o = j.optJSONArray("ocultar")
        if (o != null) {
            for (i in 0 until minOf(o.length(), 300)) {
                val x = o.optJSONObject(i) ?: continue
                val sel = x.optString("sel")
                if (sel.length <= 1000 && agregarOcultar(x.optString("host"), sel)) n++
            }
        }
        val e = j.optJSONArray("estilos")
        if (e != null) {
            for (i in 0 until minOf(e.length(), 300)) {
                val x = e.optJSONObject(i) ?: continue
                if (agregarCss(x.optString("host"), x.optString("nombre"), x.optString("css"))) n++
            }
        }
        val d = j.optJSONArray("dominios")
        if (d != null) {
            for (i in 0 until minOf(d.length(), 300)) {
                if (agregarDominio(d.optString(i))) n++
            }
        }
        val pm = j.optJSONArray("permisos")
        if (pm != null) {
            for (i in 0 until minOf(pm.length(), 300)) {
                val x = pm.optJSONObject(i) ?: continue
                if (agregarPermiso(x.optString("sitio"), x.optString("dominio"))) n++
            }
        }
        return n
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
            val pm = JSONArray()
            for (r in permisos) pm.put(JSONObject().put("sitio", r.sitio).put("dominio", r.dominio))
            j.put("permisos", pm)
            AlmacenSeguro.escribirTexto(f, j.toString())
        } catch (e: Exception) {
            // Sin espacio o sin permiso: se pierde solo esta escritura.
        }
    }
}

