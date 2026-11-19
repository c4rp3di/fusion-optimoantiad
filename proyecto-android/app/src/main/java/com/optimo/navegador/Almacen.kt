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

    /** Minutos sin usar una pestaña antes de suspenderla (0 = nunca). Desactivado por defecto. */
    var suspenderMin: Int
        get() = sp.getInt("suspender_min", 0).coerceIn(0, 240)
        set(v) {
            sp.edit().putInt("suspender_min", v).apply()
        }

    /** Ahorro de datos: 0 no, 1 siempre, 2 solo con conexión de datos limitados (móvil). Bloquea las imágenes que vienen de la red. */
    var ahorroDatos: Int
        get() = sp.getInt("ahorro_datos", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("ahorro_datos", v).apply()
        }

    private fun conjunto(k: String): Set<String> = HashSet(sp.getStringSet(k, emptySet()) ?: emptySet())

    /** ¿Está [host] (o un dominio superior suyo) en el conjunto? Una regla de example.com vale también para m.example.com. */
    private fun enConjunto(k: String, host: String): Boolean {
        val s = conjunto(k)
        if (s.isEmpty()) return false
        var h = claveSitio(host)
        while (h.isNotEmpty()) {
            if (s.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
        return false
    }

    private fun quitarDeConjunto(k: String, host: String) {
        val s = HashSet(conjunto(k))
        var h = claveSitio(host)
        while (h.isNotEmpty()) {
            s.remove(h)
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        sp.edit().putStringSet(k, s).apply()
    }

    private fun ponerEnConjunto(k: String, host: String) {
        val h = claveSitio(host)
        if (h.isEmpty()) return
        val s = HashSet(conjunto(k))
        if (s.size >= 500) return
        s.add(h)
        sp.edit().putStringSet(k, s).apply()
    }

    /** Sitios que siempre se abren con la versión de escritorio. */
    fun escritorioEn(host: String): Boolean = enConjunto("escritorio_sitios", host)

    fun fijarEscritorioEn(host: String, v: Boolean) {
        quitarDeConjunto("escritorio_sitios", host)
        if (v) ponerEnConjunto("escritorio_sitios", host)
    }

    /** Modo oscuro de páginas en un sitio: 0 según el ajuste general, 1 siempre, 2 nunca. */
    fun modoOscuroEn(host: String): Int =
        if (enConjunto("oscuro_si", host)) 1 else if (enConjunto("oscuro_no", host)) 2 else 0

    fun fijarModoOscuroEn(host: String, modo: Int) {
        quitarDeConjunto("oscuro_si", host)
        quitarDeConjunto("oscuro_no", host)
        if (modo == 1) ponerEnConjunto("oscuro_si", host)
        if (modo == 2) ponerEnConjunto("oscuro_no", host)
    }

    /** Qué borrar al cerrar Optimo (suma de bits): 1 cookies y datos de sitios, 2 historial, 4 caché. */
    var borrarAlSalir: Int
        get() = sp.getInt("borrar_al_salir", 0).coerceIn(0, 7)
        set(v) {
            sp.edit().putInt("borrar_al_salir", v).apply()
        }

    /** Marca: Optimo dejó de estar en pantalla y aún no se ha hecho la limpieza. Si el proceso muere, se hace al volver a abrir. */
    var salidaPendiente: Boolean
        get() = sp.getBoolean("salida_pendiente", false)
        set(v) {
            sp.edit().putBoolean("salida_pendiente", v).apply()
        }

    /**
     * Ajustes que entran en la copia de seguridad, con su tipo (b booleano, i entero, s texto, ss lista de textos). Lista cerrada a propósito:
     * una copia manipulada no puede escribir otras claves ni de otro tipo. Quedan fuera los permisos de sitios, las apps elegidas
     * para multimedia/magnets (dependen del móvil) y «borrar al salir» (se decide en cada dispositivo).
     */
    private val tiposCopia: Map<String, String> = mapOf(
        "anuncios" to "b", "cookies3" to "b", "dev_captura" to "b", "guardar_historial" to "b", "guardar_sesion" to "b",
        "https" to "b", "inicio_accesos" to "b", "inicio_buscador" to "b", "inicio_cuadrados" to "b", "inicio_enlace" to "b",
        "listas_wifi" to "b", "oscuro" to "b", "pip" to "b", "redirecciones" to "b", "segundo_plano" to "b",
        "ahorro_datos" to "i", "audio_pestanas" to "i", "emergentes" to "i", "inicio_max" to "i", "inicio_origen" to "i", "lectura_inter" to "i",
        "lectura_tam" to "i", "magnet_modo" to "i", "medio_modo" to "i", "suspender_min" to "i", "voz_tono" to "i", "voz_velocidad" to "i",
        "inicio_c_acento" to "s", "inicio_c_fondo" to "s", "inicio_c_sup" to "s", "inicio_c_texto" to "s", "inicio_propios" to "s",
        "inicio_subtitulo" to "s", "inicio_tema" to "s", "inicio_titulo" to "s", "lectura_fuente" to "s", "lectura_tema" to "s",
        "listas_propias" to "s", "motor" to "s",
        "bloqueo_pausado" to "ss", "emergentes_ok" to "ss", "listas_activas" to "ss",
        "escritorio_sitios" to "ss", "oscuro_si" to "ss", "oscuro_no" to "ss"
    )

    fun exportarJson(): org.json.JSONObject {
        val arr = org.json.JSONArray()
        val todo = sp.all
        for ((k, t) in tiposCopia) {
            val v = todo[k] ?: continue
            val e = org.json.JSONObject().put("k", k).put("t", t)
            if (t == "b" && v is Boolean) e.put("v", v)
            else if (t == "i" && v is Int) e.put("v", v)
            else if (t == "s" && v is String) e.put("v", v)
            else if (t == "ss" && v is Set<*>) {
                val a = org.json.JSONArray()
                for (x in v) if (x is String) a.put(x)
                e.put("v", a)
            } else continue
            arr.put(e)
        }
        return org.json.JSONObject().put("valores", arr)
    }

    /** Aplica los ajustes de una copia (solo claves y tipos de [tiposCopia]). Devuelve cuántos se aplicaron. */
    fun importarJson(j: org.json.JSONObject): Int {
        val arr = j.optJSONArray("valores") ?: return 0
        val ed = sp.edit()
        var n = 0
        for (i in 0 until minOf(arr.length(), 200)) {
            val e = arr.optJSONObject(i) ?: continue
            val k = e.optString("k")
            val t = tiposCopia[k] ?: continue
            if (e.optString("t") != t || !e.has("v")) continue
            if (t == "b") {
                ed.putBoolean(k, e.optBoolean("v"))
                n++
            } else if (t == "i") {
                ed.putInt(k, e.optInt("v"))
                n++
            } else if (t == "s") {
                val s = e.optString("v")
                if (s.length <= 50000) {
                    ed.putString(k, s)
                    n++
                }
            } else if (t == "ss") {
                val a = e.optJSONArray("v") ?: continue
                val s = HashSet<String>()
                for (x in 0 until minOf(a.length(), 1000)) {
                    val c = a.optString(x)
                    if (c.length <= 2000) s.add(c)
                }
                ed.putStringSet(k, s)
                n++
            }
        }
        ed.apply()
        return n
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

    // ---------------------------------------------------------------- ventanas emergentes y redirecciones

    /**
     * Pestañas que abre una página por su cuenta: 0 bloquear las de anuncios (cierra las de dominios de las listas y limita a una
     * por toque y cada pocos segundos), 1 estricto (solo si tocas un enlace y la pestaña va a ese mismo sitio), 2 permitir todas.
     */
    var emergentes: Int
        get() = sp.getInt("emergentes", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("emergentes", v).apply()
        }

    /** Bloquear que la página te lleve sola a otro sitio (sin tocar nada) o a un dominio de anuncios. */
    var bloquearRedirecciones: Boolean
        get() = sp.getBoolean("redirecciones", true)
        set(v) {
            sp.edit().putBoolean("redirecciones", v).apply()
        }

    fun emergentesPermitidos(): Set<String> = HashSet(sp.getStringSet("emergentes_ok", emptySet()) ?: emptySet())

    /** ¿Este sitio (o un dominio padre suyo) tiene permiso para abrir emergentes y redirigir? */
    fun emergentesPermitidosEn(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        if (h.isEmpty()) return false
        return emergentesPermitidos().any { h == it || h.endsWith("." + it) }
    }

    fun permitirEmergentes(host: String, permitir: Boolean) {
        val h = host.lowercase().removePrefix("www.")
        if (h.isEmpty()) return
        val s = HashSet(emergentesPermitidos())
        if (permitir) s.add(h) else s.removeAll { h == it || h.endsWith("." + it) }
        sp.edit().putStringSet("emergentes_ok", s).apply()
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

    // ---------------------------------------------------------------- bloqueo de anuncios pausado por sitio

    @Volatile
    private var pausadosCache: Set<String>? = null

    private fun normalizarSitio(host: String): String = host.trim().lowercase().removePrefix("www.")

    private fun pausados(): Set<String> {
        var c = pausadosCache
        if (c == null) {
            c = HashSet(sp.getStringSet("bloqueo_pausado", emptySet()) ?: emptySet())
            pausadosCache = c
        }
        return c
    }

    /** ¿Está pausado el bloqueo de anuncios en [host]? Vale también para sus subdominios. */
    fun bloqueoPausadoEn(host: String): Boolean {
        val h = normalizarSitio(host)
        if (h.isEmpty()) return false
        val p = pausados()
        if (p.isEmpty()) return false
        return p.any { h == it || h.endsWith("." + it) }
    }

    fun pausarBloqueoEn(host: String, pausar: Boolean) {
        val h = normalizarSitio(host)
        if (h.isEmpty()) return
        val s = HashSet(pausados())
        if (pausar) s.add(h) else s.remove(h)
        sp.edit().putStringSet("bloqueo_pausado", s).apply()
        pausadosCache = s
    }

    fun sitiosPausados(): List<String> = pausados().sorted()

    fun reanudarTodosLosSitios() {
        sp.edit().remove("bloqueo_pausado").apply()
        pausadosCache = emptySet()
    }

    // ---------------------------------------------------------------- modo lectura

    var lecturaTam: Int
        get() = sp.getInt("lectura_tam", 19).coerceIn(14, 32)
        set(v) {
            sp.edit().putInt("lectura_tam", v.coerceIn(14, 32)).apply()
        }

    /** claro, sepia, oscuro o negro; vacío = según el modo del sistema. */
    var lecturaTema: String
        get() = sp.getString("lectura_tema", "") ?: ""
        set(v) {
            if (v == "" || v == "claro" || v == "sepia" || v == "oscuro" || v == "negro") sp.edit().putString("lectura_tema", v).apply()
        }

    var lecturaFuente: String
        get() = if (sp.getString("lectura_fuente", "serif") == "sans") "sans" else "serif"
        set(v) {
            sp.edit().putString("lectura_fuente", if (v == "sans") "sans" else "serif").apply()
        }

    /** Interlineado: 0 compacto, 1 normal, 2 amplio. */
    var lecturaInter: Int
        get() = sp.getInt("lectura_inter", 1).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("lectura_inter", v.coerceIn(0, 2)).apply()
        }

    /** Velocidad de la voz en %, de 50 a 250 (100 = normal). */
    var vozVelocidad: Int
        get() = sp.getInt("voz_velocidad", 100).coerceIn(50, 250)
        set(v) {
            sp.edit().putInt("voz_velocidad", v.coerceIn(50, 250)).apply()
        }

    /** Tono de la voz en %, de 50 a 200 (100 = normal). */
    var vozTono: Int
        get() = sp.getInt("voz_tono", 100).coerceIn(50, 200)
        set(v) {
            sp.edit().putInt("voz_tono", v.coerceIn(50, 200)).apply()
        }

    // ---------------------------------------------------------------- privacidad y datos guardados

    /** Guardar el historial de las pestañas normales (las privadas nunca lo guardan). */
    var guardarHistorial: Boolean
        get() = sp.getBoolean("guardar_historial", true)
        set(v) {
            sp.edit().putBoolean("guardar_historial", v).apply()
        }

    /** Recordar las pestañas abiertas para restaurarlas al reabrir Optimo. */
    var guardarSesion: Boolean
        get() = sp.getBoolean("guardar_sesion", true)
        set(v) {
            sp.edit().putBoolean("guardar_sesion", v).apply()
        }

    private fun claveSitio(host: String): String = host.lowercase().removePrefix("www.")

    /** Permisos web que se dejaron concedidos «siempre» a un sitio: entradas «sitio|tipo» (tipo: camara, microfono, ubicacion). */
    private fun permisosGuardados(): Set<String> = HashSet(sp.getStringSet("permisos_sitio", emptySet()) ?: emptySet())

    /** Permisos con caducidad: entradas «sitio|tipo|caducaEnMs». Las caducadas se descartan al leer. */
    private fun permisosTemporales(): Set<String> {
        val todos = sp.getStringSet("permisos_temp", emptySet()) ?: emptySet()
        val ahora = System.currentTimeMillis()
        val vigentes = HashSet<String>()
        for (e in todos) {
            val caduca = e.substringAfterLast('|').toLongOrNull() ?: continue
            if (caduca > ahora) vigentes.add(e)
        }
        if (vigentes.size != todos.size) sp.edit().putStringSet("permisos_temp", vigentes).apply()
        return vigentes
    }

    fun permisoRecordado(host: String, tipo: String): Boolean {
        val h = claveSitio(host)
        if (h.isEmpty()) return false
        if (permisosGuardados().contains(h + "|" + tipo)) return true
        val prefijo = h + "|" + tipo + "|"
        for (e in permisosTemporales()) {
            if (e.startsWith(prefijo)) return true
        }
        return false
    }

    /** Concede el permiso solo durante [duracionMs] (p. ej. 1 h). Sustituye a otro temporal anterior del mismo tipo. */
    fun recordarPermisoTemporal(host: String, tipo: String, duracionMs: Long) {
        val h = claveSitio(host)
        if (h.isEmpty()) return
        val prefijo = h + "|" + tipo + "|"
        val s = HashSet<String>()
        for (e in permisosTemporales()) {
            if (!e.startsWith(prefijo)) s.add(e)
        }
        s.add(prefijo + (System.currentTimeMillis() + duracionMs))
        sp.edit().putStringSet("permisos_temp", s).apply()
    }

    /** Milisegundos que le quedan a un permiso temporal; 0 si no es temporal (o no existe). */
    fun restantePermiso(host: String, tipo: String): Long {
        val prefijo = claveSitio(host) + "|" + tipo + "|"
        val ahora = System.currentTimeMillis()
        for (e in permisosTemporales()) {
            if (e.startsWith(prefijo)) return (e.substringAfterLast('|').toLongOrNull() ?: 0L) - ahora
        }
        return 0L
    }

    fun recordarPermiso(host: String, tipo: String) {
        val h = claveSitio(host)
        if (h.isEmpty()) return
        val s = HashSet(permisosGuardados())
        s.add(h + "|" + tipo)
        sp.edit().putStringSet("permisos_sitio", s).apply()
    }

    fun quitarPermiso(host: String, tipo: String) {
        val h = claveSitio(host)
        val s = HashSet(permisosGuardados())
        s.remove(h + "|" + tipo)
        sp.edit().putStringSet("permisos_sitio", s).apply()
        val prefijo = h + "|" + tipo + "|"
        val t = HashSet<String>()
        for (e in permisosTemporales()) {
            if (!e.startsWith(prefijo)) t.add(e)
        }
        sp.edit().putStringSet("permisos_temp", t).apply()
    }

    /** Tipos de permiso que tiene guardados un sitio. */
    fun permisosDe(host: String): List<String> {
        val h = claveSitio(host)
        val res = java.util.TreeSet<String>()
        for (e in permisosGuardados()) {
            if (e.substringBefore('|') == h) res.add(e.substringAfter('|'))
        }
        for (e in permisosTemporales()) {
            if (e.substringBefore('|') == h) res.add(e.substringAfter('|').substringBefore('|'))
        }
        return ArrayList(res)
    }

    fun sitiosConPermisos(): List<String> =
        (permisosGuardados() + permisosTemporales()).map { it.substringBefore('|') }.distinct().sorted()

    fun totalPermisos(): Int = permisosGuardados().size + permisosTemporales().size

    fun quitarTodosPermisos() {
        sp.edit().remove("permisos_sitio").remove("permisos_temp").apply()
    }
}

