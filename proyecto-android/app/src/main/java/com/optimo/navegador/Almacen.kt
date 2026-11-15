package com.optimo.navegador

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Elemento de marcadores o historial. */
class Entrada(val titulo: String, val url: String, val tiempo: Long)

/**
 * Lista persistente en un archivo JSON (marcadores e historial). Lo mas reciente va primero,
 * sin duplicados por URL y con un maximo. Las escrituras frecuentes se agrupan (guardarPronto).
 */
class ListaPersistente(private val archivo: File, private val maximo: Int) {

    private val datos = ArrayList<Entrada>()
    private val handler = Handler(Looper.getMainLooper())
    private var pendiente = false
    private val tarea = Runnable {
        pendiente = false
        guardar()
    }

    init {
        cargar()
    }

    fun todos(): List<Entrada> = datos

    fun contiene(url: String): Boolean {
        for (e in datos) {
            if (e.url == url) return true
        }
        return false
    }

    fun agregar(e: Entrada) {
        quitarSinGuardar(e.url)
        datos.add(0, e)
        while (datos.size > maximo) {
            datos.removeAt(datos.size - 1)
        }
        guardarPronto()
    }

    fun quitar(url: String) {
        if (quitarSinGuardar(url)) guardarPronto()
    }

    fun vaciar() {
        datos.clear()
        guardarAhora()
    }

    fun guardarPronto() {
        if (!pendiente) {
            pendiente = true
            handler.postDelayed(tarea, 3000L)
        }
    }

    fun guardarAhora() {
        handler.removeCallbacks(tarea)
        pendiente = false
        guardar()
    }

    private fun quitarSinGuardar(url: String): Boolean {
        var quitado = false
        val it = datos.iterator()
        while (it.hasNext()) {
            if (it.next().url == url) {
                it.remove()
                quitado = true
            }
        }
        return quitado
    }

    private fun cargar() {
        try {
            if (!archivo.exists()) return
            val arr = JSONArray(archivo.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                datos.add(Entrada(o.optString("t"), o.getString("u"), o.optLong("h")))
            }
        } catch (e: Exception) {
            datos.clear()
        }
    }

    private fun guardar() {
        try {
            val arr = JSONArray()
            for (e in datos) {
                val o = JSONObject()
                o.put("t", e.titulo)
                o.put("u", e.url)
                o.put("h", e.tiempo)
                arr.put(o)
            }
            val texto = arr.toString()
            val tmp = File(archivo.path + ".tmp")
            tmp.writeText(texto)
            if (!tmp.renameTo(archivo)) {
                archivo.writeText(texto)
                tmp.delete()
            }
        } catch (e: Exception) {
            // Sin espacio o sin permiso: se pierde solo esta escritura.
        }
    }
}

/** Ajustes del usuario en SharedPreferences. */
class Ajustes(contexto: Context) {

    private val sp = contexto.getSharedPreferences("optimo_ajustes", Context.MODE_PRIVATE)

    var motor: String
        get() = sp.getString("motor", "ddg") ?: "ddg"
        set(v) {
            sp.edit().putString("motor", v).apply()
        }

    var bloquearAnuncios: Boolean
        get() = sp.getBoolean("anuncios", true)
        set(v) {
            sp.edit().putBoolean("anuncios", v).apply()
        }

    var httpsPrimero: Boolean
        get() = sp.getBoolean("https", true)
        set(v) {
            sp.edit().putBoolean("https", v).apply()
        }

    var oscurecerPaginas: Boolean
        get() = sp.getBoolean("oscuro", false)
        set(v) {
            sp.edit().putBoolean("oscuro", v).apply()
        }

    /** Técnicas contra anuncios (ver MotorAnuncios.TECNICAS): todas activas por defecto. */
    fun tecnica(id: String): Boolean = sp.getBoolean("ads_$id", true)

    fun ponerTecnica(id: String, valor: Boolean) {
        sp.edit().putBoolean("ads_$id", valor).apply()
    }

    // ---- listas de bloqueo (ver Listas.kt)
    var soloWifi: Boolean
        get() = sp.getBoolean("listas_wifi", true)
        set(v) {
            sp.edit().putBoolean("listas_wifi", v).apply()
        }

    var ultimaActualizacion: Long
        get() = sp.getLong("listas_ultima", 0L)
        set(v) {
            sp.edit().putLong("listas_ultima", v).apply()
        }

    fun listasActivas(): Set<String> {
        val g = sp.getStringSet("listas_activas", null) ?: return ListasManager.POR_DEFECTO
        return HashSet(g)
    }

    fun ponerListasActivas(v: Set<String>) {
        sp.edit().putStringSet("listas_activas", HashSet(v)).apply()
    }

    fun propias(): List<ListaDef> {
        val texto = sp.getString("listas_propias", "") ?: ""
        val res = ArrayList<ListaDef>()
        for (linea in texto.split('\n')) {
            val partes = linea.split('\t')
            if (partes.size == 3) res.add(ListaDef(partes[0], partes[1], "propia", partes[2], true))
        }
        return res
    }

    /** Añade una lista propia (solo https) y la activa. Devuelve null si la URL no es válida. */
    fun agregarPropia(urlCruda: String): ListaDef? {
        val url = urlCruda.trim()
        if (!url.startsWith("https://") || url.length < 12 || url.contains('\t') || url.contains('\n')) return null
        val host = try { android.net.Uri.parse(url).host } catch (e: Exception) { null } ?: return null
        val id = "p" + Integer.toHexString(url.hashCode())
        val lista = ListaDef(id, host, "propia", url, true)
        guardarPropias(propias().filter { it.id != id } + lista)
        val activas = HashSet(listasActivas())
        activas.add(id)
        ponerListasActivas(activas)
        return lista
    }

    fun quitarPropia(id: String) {
        guardarPropias(propias().filter { it.id != id })
        val activas = HashSet(listasActivas())
        activas.remove(id)
        ponerListasActivas(activas)
    }

    private fun guardarPropias(lista: List<ListaDef>) {
        sp.edit().putString("listas_propias", lista.joinToString("\n") { it.id + "\t" + it.nombre + "\t" + it.url }).apply()
    }

    fun etag(id: String): String? = sp.getString("etag_$id", null)

    fun modificado(id: String): String? = sp.getString("mod_$id", null)

    fun setCabecerasCache(id: String, etag: String?, modificado: String?) {
        sp.edit().putString("etag_$id", etag).putString("mod_$id", modificado).apply()
    }

    var cookiesTerceros: Boolean
        get() = sp.getBoolean("cookies3", false)
        set(v) {
            sp.edit().putBoolean("cookies3", v).apply()
        }
}
