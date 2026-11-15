package com.optimo.navegador

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Regla «ocultar elemento»: en el sitio [host] (y subdominios) se oculta lo que encaje con [sel]. */
class ReglaOcultar(val host: String, val sel: String)

/**
 * Reglas que añade el usuario al mantener pulsado un anuncio. Se guardan en un JSON pequeño.
 *  - Ocultar elemento: selector CSS por sitio.
 *  - Bloquear dominio: vale para todas las webs y gana a las excepciones de las listas.
 * Las lecturas (desde hilos de red) usan instantáneas inmutables; las escrituras van en el hilo principal.
 */
object ReglasUsuario {

    @Volatile
    private var ocultar: List<ReglaOcultar> = emptyList()

    @Volatile
    private var dominios: Set<String> = emptySet()

    private var archivo: File? = null

    fun iniciar(c: Context) {
        archivo = File(c.applicationContext.filesDir, "reglas_usuario.json")
        cargar()
    }

    val total: Int get() = ocultar.size + dominios.size

    fun listaOcultar(): List<ReglaOcultar> = ocultar

    fun listaDominios(): List<String> = dominios.sorted()

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

    fun hostsConReglas(): List<String> = ocultar.map { it.host }.distinct()

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
            val d = HashSet<String>()
            j.optJSONArray("dominios")?.let { for (i in 0 until it.length()) d.add(it.getString(i)) }
            ocultar = o
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
            val d = JSONArray()
            for (x in dominios.sorted()) d.put(x)
            j.put("dominios", d)
            AlmacenSeguro.escribirTexto(f, j.toString())
        } catch (e: Exception) {
            // Sin espacio o sin permiso: se pierde solo esta escritura.
        }
    }
}

