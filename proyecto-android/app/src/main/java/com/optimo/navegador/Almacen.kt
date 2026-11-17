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
            val texto = AlmacenSeguro.leerTexto(archivo) ?: return
            val arr = JSONArray(texto)
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
            AlmacenSeguro.escribirTexto(archivo, arr.toString())
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

    /** Herramientas de desarrollo: inyectar el agente al inicio de cada página (captura completa de consola y red). */
    var devCaptura: Boolean
        get() = sp.getBoolean("dev_captura", false)
        set(v) {
            sp.edit().putBoolean("dev_captura", v).apply()
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

    /** Al abrir o cambiar de pestaña con audio sonando: 0 preguntar, 1 seguir sonando, 2 pausar. */
    var audioPestanas: Int
        get() = sp.getInt("audio_pestanas", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("audio_pestanas", v).apply()
        }

    /** Seguir reproduciendo con la pantalla bloqueada o en segundo plano (servicio + script de visibilidad). */
    var segundoPlano: Boolean
        get() = sp.getBoolean("segundo_plano", true)
        set(v) {
            sp.edit().putBoolean("segundo_plano", v).apply()
        }

    /** Imagen en imagen al salir de la app con un vídeo a pantalla completa. */
    var pip: Boolean
        get() = sp.getBoolean("pip", true)
        set(v) {
            sp.edit().putBoolean("pip", v).apply()
        }

    /** Ya se pidió una vez el permiso de notificaciones (Android 13+) para los controles de segundo plano. */
    var notifPedido: Boolean
        get() = sp.getBoolean("notif_pedido", false)
        set(v) {
            sp.edit().putBoolean("notif_pedido", v).apply()
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

    // ---------------------------------------------------------------- pantalla de inicio editable (Paginas.inicio)

    /** Tema de colores: id de Paginas.TEMAS_INICIO ("auto" sigue al sistema; "personalizado" usa los cuatro colores de abajo). */
    var inicioTema: String
        get() = sp.getString("inicio_tema", "auto") ?: "auto"
        set(v) {
            sp.edit().putString("inicio_tema", v).apply()
        }

    var inicioColorFondo: String
        get() = Paginas.normalizarHex(sp.getString("inicio_c_fondo", "") ?: "") ?: "#121418"
        set(v) {
            sp.edit().putString("inicio_c_fondo", v).apply()
        }

    var inicioColorTexto: String
        get() = Paginas.normalizarHex(sp.getString("inicio_c_texto", "") ?: "") ?: "#E8EAED"
        set(v) {
            sp.edit().putString("inicio_c_texto", v).apply()
        }

    var inicioColorAcento: String
        get() = Paginas.normalizarHex(sp.getString("inicio_c_acento", "") ?: "") ?: "#00BFA5"
        set(v) {
            sp.edit().putString("inicio_c_acento", v).apply()
        }

    var inicioColorSuperficie: String
        get() = Paginas.normalizarHex(sp.getString("inicio_c_sup", "") ?: "") ?: "#1E2229"
        set(v) {
            sp.edit().putString("inicio_c_sup", v).apply()
        }

    var inicioTitulo: String
        get() = sp.getString("inicio_titulo", "Optimo") ?: "Optimo"
        set(v) {
            sp.edit().putString("inicio_titulo", v).apply()
        }

    var inicioSubtitulo: String
        get() = sp.getString("inicio_subtitulo", "Navega rápido y con control.") ?: ""
        set(v) {
            sp.edit().putString("inicio_subtitulo", v).apply()
        }

    var inicioBuscador: Boolean
        get() = sp.getBoolean("inicio_buscador", true)
        set(v) {
            sp.edit().putBoolean("inicio_buscador", v).apply()
        }

    /** Mostrar la rejilla de accesos directos (con false desaparecen los marcadores de la pantalla de inicio). */
    var inicioAccesos: Boolean
        get() = sp.getBoolean("inicio_accesos", true)
        set(v) {
            sp.edit().putBoolean("inicio_accesos", v).apply()
        }

    /** De dónde salen los accesos: 0 marcadores recientes, 1 lista propia, 2 los predeterminados. */
    var inicioOrigen: Int
        get() = sp.getInt("inicio_origen", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("inicio_origen", v).apply()
        }

    var inicioMaxAccesos: Int
        get() = sp.getInt("inicio_max", 8).coerceIn(4, 12)
        set(v) {
            sp.edit().putInt("inicio_max", v).apply()
        }

    /** Forma de los iconos: false círculo, true cuadrado redondeado. */
    var inicioCuadrados: Boolean
        get() = sp.getBoolean("inicio_cuadrados", false)
        set(v) {
            sp.edit().putBoolean("inicio_cuadrados", v).apply()
        }

    /** Enlace «⚙ Personalizar esta pantalla» al pie de la página de inicio. */
    var inicioEnlacePersonalizar: Boolean
        get() = sp.getBoolean("inicio_enlace", true)
        set(v) {
            sp.edit().putBoolean("inicio_enlace", v).apply()
        }

    /** Accesos propios: (nombre, url). Se guardan en una cadena con tabuladores y saltos de línea. */
    fun accesosPropios(): List<Pair<String, String>> {
        val texto = sp.getString("inicio_propios", "") ?: ""
        val res = ArrayList<Pair<String, String>>()
        for (linea in texto.split('\n')) {
            val partes = linea.split('\t')
            if (partes.size == 2 && partes[1].isNotEmpty()) res.add(Pair(partes[0], partes[1]))
        }
        return res
    }

    fun guardarAccesosPropios(lista: List<Pair<String, String>>) {
        val limpio = lista.filter { !it.second.contains('\t') && !it.second.contains('\n') }
        sp.edit().putString(
            "inicio_propios",
            limpio.joinToString("\n") { it.first.replace('\t', ' ').replace('\n', ' ') + "\t" + it.second }
        ).apply()
    }

    /** Vuelve la pantalla de inicio a su aspecto original (no toca los accesos propios). */
    fun restablecerInicio() {
        sp.edit()
            .remove("inicio_tema").remove("inicio_c_fondo").remove("inicio_c_texto").remove("inicio_c_acento")
            .remove("inicio_c_sup").remove("inicio_titulo").remove("inicio_subtitulo").remove("inicio_buscador")
            .remove("inicio_accesos").remove("inicio_origen").remove("inicio_max").remove("inicio_cuadrados")
            .remove("inicio_enlace")
            .apply()
    }

    // ---------------------------------------------------------------- enlaces multimedia y magnets

    /**
     * Qué hacer con un enlace o descarga de vídeo, audio o torrent:
     * 0 preguntar cada vez, 1 en el navegador (como antes: se abre o descarga en Optimo), 2 siempre en otra app ([appMedio]).
     */
    var modoMedio: Int
        get() = sp.getInt("medio_modo", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("medio_modo", v).apply()
        }

    /** Paquete de la app elegida para el modo 2 (por defecto Optimo Play). */
    var appMedio: String
        get() = sp.getString("medio_app", ReproductorExterno.PAQUETE) ?: ReproductorExterno.PAQUETE
        set(v) {
            sp.edit().putString("medio_app", v).apply()
        }

    /** Lo mismo para enlaces magnet: 0 preguntar, 1 automático (Optimo Play si está instalada; si no, el selector del sistema), 2 siempre en [appMagnet]. */
    var modoMagnet: Int
        get() = sp.getInt("magnet_modo", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("magnet_modo", v).apply()
        }

    var appMagnet: String
        get() = sp.getString("magnet_app", ReproductorExterno.PAQUETE) ?: ReproductorExterno.PAQUETE
        set(v) {
            sp.edit().putString("magnet_app", v).apply()
        }
}

