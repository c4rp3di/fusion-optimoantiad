package com.optimo.navegador

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.TreeMap

/** Qué le pasa a la protección en la página activa (lo que dibuja el escudo de la barra). */
enum class EstadoEscudo {
    /** Bloqueando en esta página. */
    ACTIVO,

    /** El usuario pausó el bloqueo en este sitio. */
    PAUSADO,

    /** Bloqueo de anuncios desactivado en Ajustes (para todos los sitios). */
    APAGADO,

    /** Inicio, archivos locales, páginas guardadas…: no hay nada que bloquear. */
    NO_APLICA
}

/** Pantallas existentes a las que el centro de protección enlaza (las implementa MainActivity). */
enum class Destino {
    LISTAS, TECNICAS, REGLAS, DOMINIOS, PAUSADOS, POR_SITIO,
    EMERGENTES_MODO, EMERGENTES_SITIOS,
    PRIVACIDAD, COPIA, CERTIFICADOS, PERMISOS, BARRA
}

/** Lo que el panel del escudo y el centro de protección necesitan de MainActivity. */
interface AnfitrionProteccion {
    fun estado(p: Pestana): EstadoEscudo
    fun fijarBloqueoGlobal(activo: Boolean)
    fun fijarCookiesTerceros(activas: Boolean)
    fun alternarPausa(p: Pestana)
    fun recargarConAjustes(p: Pestana, host: String)
    /** Elige el modo de emergentes del sitio; [alTerminar] se llama cuando se elige uno. */
    fun elegirEmergentes(p: Pestana, host: String, alTerminar: () -> Unit)
    fun quitarCapa(p: Pestana)
    fun elegirElemento(p: Pestana)
    fun bloquearSitio(p: Pestana)
    fun desbloquearSitio(p: Pestana)
    fun datosDelSitio(host: String)
    fun personalizar(p: Pestana)
    fun conexion()
    fun verBloqueados(p: Pestana, host: String)
    /** Pregunta si permitir [dominio] solo en el sitio de [p] o en todos, y recarga. */
    fun permitirDominio(p: Pestana, dominio: String)
    fun abrirCentro()
    fun volverAlMenu()
    fun pestanaActual(): Pestana?
    fun abrir(destino: Destino)
    fun avisar(texto: String)
    fun confirmar(titulo: String, mensaje: String, boton: String, accion: () -> Unit)
}

/** Nombres de los ajustes por sitio de tres estados (mismo orden que usa [Ajustes]). */
object NombresSitio {
    val EMERGENTES = listOf("según ajustes", "recomendado", "estricto", "permitir todas")
    val COOKIES = listOf("según ajustes", "permitir", "bloquear")
    val IMAGENES = listOf("según ajustes", "cargar siempre", "sin imágenes")
}

/**
 * Icono de escudo dibujado a mano (no depende de las fuentes de emoji y se puede teñir):
 * relleno suave y marca de verificación si protege; solo contorno si no aplica; tachado si está pausado o apagado.
 */
class VistaEscudo(contexto: Context) : View(contexto) {
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camino = Path()
    private var color: Int = Color.GRAY
    private var relleno = true
    private var tachado = false
    private var marca = true

    fun fijar(color: Int, relleno: Boolean, tachado: Boolean, marca: Boolean) {
        this.color = color
        this.relleno = relleno
        this.tachado = tachado
        this.marca = marca
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        val k = s / 24f
        camino.reset()
        camino.moveTo(ox + 12f * k, oy + 2.5f * k)
        camino.lineTo(ox + 19.5f * k, oy + 5.5f * k)
        camino.lineTo(ox + 19.5f * k, oy + 11f * k)
        camino.cubicTo(ox + 19.5f * k, oy + 16f * k, ox + 16.2f * k, oy + 19.8f * k, ox + 12f * k, oy + 21.5f * k)
        camino.cubicTo(ox + 7.8f * k, oy + 19.8f * k, ox + 4.5f * k, oy + 16f * k, ox + 4.5f * k, oy + 11f * k)
        camino.lineTo(ox + 4.5f * k, oy + 5.5f * k)
        camino.close()
        if (relleno) {
            pincel.style = Paint.Style.FILL
            pincel.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawPath(camino, pincel)
        }
        pincel.style = Paint.Style.STROKE
        pincel.strokeWidth = 1.8f * k
        pincel.strokeJoin = Paint.Join.ROUND
        pincel.strokeCap = Paint.Cap.ROUND
        pincel.color = color
        canvas.drawPath(camino, pincel)
        if (marca && !tachado) {
            camino.reset()
            camino.moveTo(ox + 8.3f * k, oy + 12f * k)
            camino.lineTo(ox + 11f * k, oy + 14.7f * k)
            camino.lineTo(ox + 15.8f * k, oy + 9.2f * k)
            canvas.drawPath(camino, pincel)
        }
        if (tachado) {
            pincel.strokeWidth = 2.2f * k
            canvas.drawLine(ox + 3.5f * k, oy + 3f * k, ox + 20.5f * k, oy + 21f * k, pincel)
        }
    }
}

/**
 * Estadísticas de bloqueo (solo en este dispositivo, cifradas como el resto de datos locales).
 * Se llevan en memoria y se guardan al pasar la app a segundo plano; no se anota nada de las pestañas privadas.
 * Guardan qué sitios y dominios se han bloqueado, así que se pueden desactivar y borrar desde el centro de protección.
 */
object EstadisticasBloqueo {
    const val ANUNCIO = 0
    const val SUSTITUIDO = 1
    const val EMERGENTE = 2
    const val REDIRECCION = 3
    val NOMBRES = listOf("Anuncios y rastreadores", "Scripts y vídeos sustituidos", "Pestañas emergentes", "Redirecciones automáticas")

    private const val MAX_DIAS = 90
    private const val MAX_CLAVES = 800

    private val lock = Any()
    private var archivo: File? = null
    private var prefs: SharedPreferences? = null
    private val dias = TreeMap<String, IntArray>()
    private val sitios = HashMap<String, Int>()
    private val dominios = HashMap<String, Int>()
    private var desde: String = ""

    @Volatile private var sucio = false

    /** Si está desactivado no se anota nada (lo ya guardado se conserva hasta que lo borres). */
    var activas: Boolean = true
        private set

    fun iniciar(c: Context) {
        val p = c.getSharedPreferences("optimo_estadisticas", Context.MODE_PRIVATE)
        prefs = p
        activas = p.getBoolean("activas", true)
        archivo = File(c.filesDir, "estadisticas.json")
        cargar()
    }

    fun fijarActivas(v: Boolean) {
        activas = v
        prefs?.edit()?.putBoolean("activas", v)?.apply()
    }

    private fun hoyClave(): String = LocalDate.now().toString()

    /** Anota un bloqueo. [sitio] es la página donde ocurrió y [dominio] el recurso bloqueado (puede ser null). Cualquier hilo. */
    fun registrar(categoria: Int, sitio: String, dominio: String?) {
        if (!activas || categoria < 0 || categoria > 3) return
        synchronized(lock) {
            val d = hoyClave()
            if (desde.isEmpty()) desde = d
            val fila = dias.getOrPut(d) { IntArray(4) }
            fila[categoria]++
            if (dias.size > MAX_DIAS) dias.remove(dias.firstKey())
            if (sitio.isNotEmpty()) sitios[sitio] = (sitios[sitio] ?: 0) + 1
            if (!dominio.isNullOrEmpty()) dominios[dominio.lowercase()] = (dominios[dominio.lowercase()] ?: 0) + 1
            if (sitios.size > MAX_CLAVES) podar(sitios)
            if (dominios.size > MAX_CLAVES) podar(dominios)
            sucio = true
        }
    }

    /** Quita las claves con menos bloqueos hasta dejar el mapa al 80 %. */
    private fun podar(m: HashMap<String, Int>) {
        val dejar = MAX_CLAVES * 8 / 10
        val orden = m.entries.sortedByDescending { it.value }
        m.clear()
        for (e in orden.take(dejar)) m[e.key] = e.value
    }

    /** Bloqueos de hoy por categoría. */
    fun hoy(): IntArray = synchronized(lock) { (dias[hoyClave()] ?: IntArray(4)).copyOf() }

    /** Suma de los últimos [n] días (incluido hoy) por categoría. */
    fun ultimos(n: Int): IntArray = synchronized(lock) {
        val r = IntArray(4)
        val limite = LocalDate.now().minusDays((n - 1).toLong()).toString()
        for ((k, v) in dias) if (k >= limite) for (i in 0..3) r[i] += v[i]
        r
    }

    /** Todo lo guardado (hasta [MAX_DIAS] días) por categoría. */
    fun total(): IntArray = synchronized(lock) {
        val r = IntArray(4)
        for (v in dias.values) for (i in 0..3) r[i] += v[i]
        r
    }

    /** Totales diarios de los últimos [n] días, el más antiguo primero (para el gráfico). */
    fun serieDiaria(n: Int): IntArray = synchronized(lock) {
        val r = IntArray(n)
        for (i in 0 until n) {
            val d = LocalDate.now().minusDays((n - 1 - i).toLong()).toString()
            r[i] = dias[d]?.sum() ?: 0
        }
        r
    }

    fun deSitio(sitio: String): Int = synchronized(lock) { sitios[sitio] ?: 0 }

    fun topDominios(n: Int): List<Pair<String, Int>> = synchronized(lock) {
        dominios.entries.sortedByDescending { it.value }.take(n).map { Pair(it.key, it.value) }
    }

    fun topSitios(n: Int): List<Pair<String, Int>> = synchronized(lock) {
        sitios.entries.sortedByDescending { it.value }.take(n).map { Pair(it.key, it.value) }
    }

    /** Desde cuándo hay datos (AAAA-MM-DD) o vacío. */
    fun desdeFecha(): String = synchronized(lock) { desde }

    fun reiniciar() {
        synchronized(lock) {
            dias.clear()
            sitios.clear()
            dominios.clear()
            desde = ""
            sucio = true
        }
        guardarSiHaceFalta()
    }

    fun guardarSiHaceFalta() {
        if (!sucio) return
        try {
            val f = archivo ?: return
            val j = JSONObject()
            synchronized(lock) {
                j.put("desde", desde)
                val d = JSONObject()
                for ((k, v) in dias) d.put(k, JSONArray(v.toList()))
                j.put("dias", d)
                val s = JSONObject()
                for ((k, v) in sitios) s.put(k, v)
                j.put("sitios", s)
                val o = JSONObject()
                for ((k, v) in dominios) o.put(k, v)
                j.put("dominios", o)
                sucio = false
            }
            AlmacenSeguro.escribirTexto(f, j.toString())
        } catch (e: Exception) {
            // Sin espacio o sin permiso: se pierde solo esta escritura.
        }
    }

    private fun cargar() {
        try {
            val f = archivo ?: return
            val texto = AlmacenSeguro.leerTexto(f) ?: return
            val j = JSONObject(texto)
            synchronized(lock) {
                dias.clear()
                sitios.clear()
                dominios.clear()
                desde = j.optString("desde")
                j.optJSONObject("dias")?.let { d ->
                    val it = d.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val a = d.getJSONArray(k)
                        val fila = IntArray(4)
                        for (i in 0..3) fila[i] = a.optInt(i)
                        dias[k] = fila
                    }
                }
                j.optJSONObject("sitios")?.let { s ->
                    val it = s.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        sitios[k] = s.optInt(k)
                    }
                }
                j.optJSONObject("dominios")?.let { s ->
                    val it = s.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        dominios[k] = s.optInt(k)
                    }
                }
            }
        } catch (e: Exception) {
            // Archivo dañado: se empieza de cero.
        }
    }
}
