package com.optimo.navegador

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Carpeta de marcadores. [padre] = 0 es la raiz. */
class CarpetaMarcadores(val id: Long, var nombre: String, var padre: Long)

/** Marcador dentro de una carpeta ([carpeta] = 0 es la raiz). */
class Marcador(var titulo: String, var url: String, var carpeta: Long, val tiempo: Long)

/** Fila del gestor: una carpeta, un marcador o la fila «subir». */
class FilaMarcadores(val carpeta: CarpetaMarcadores?, val marcador: Marcador?, val subir: Boolean = false)

/**
 * Marcadores con carpetas anidadas, guardados cifrados (AlmacenSeguro) en un unico JSON.
 * Formato v2: {"v":2,"carpetas":[{"id","n","p"}],"marcadores":[{"t","u","c","h"}]}.
 * Lee tambien el formato antiguo (lista plana) y lo conserva en la raiz. Una URL solo puede estar una vez.
 */
class Marcadores(private val archivo: File, private val maximo: Int) {

    private val carpetas = ArrayList<CarpetaMarcadores>()
    private val items = ArrayList<Marcador>()
    private var siguienteId = 1L
    private val handler = Handler(Looper.getMainLooper())
    private var pendiente = false
    private val tarea = Runnable {
        pendiente = false
        guardar()
    }

    init {
        cargar()
    }

    /** Todos los marcadores (de cualquier carpeta), los mas recientes primero. */
    fun todos(): List<Marcador> = items

    fun contiene(url: String): Boolean = items.any { it.url == url }

    fun buscar(url: String): Marcador? = items.firstOrNull { it.url == url }

    fun carpeta(id: Long): CarpetaMarcadores? = carpetas.firstOrNull { it.id == id }

    /** Elementos directos de una carpeta (subcarpetas + marcadores). */
    fun elementosEn(id: Long): Int = carpetas.count { it.padre == id } + items.count { it.carpeta == id }

    /** Contenido de una carpeta: fila «subir» (si no es la raiz), subcarpetas por nombre y marcadores. */
    fun filas(padre: Long): List<FilaMarcadores> {
        val r = ArrayList<FilaMarcadores>()
        if (padre != 0L) r.add(FilaMarcadores(null, null, true))
        for (c in carpetas.filter { it.padre == padre }.sortedBy { it.nombre.lowercase() }) {
            r.add(FilaMarcadores(c, null))
        }
        for (m in items) {
            if (m.carpeta == padre) r.add(FilaMarcadores(null, m))
        }
        return r
    }

    /** «Marcadores › Trabajo › Proyectos». */
    fun ruta(id: Long): String {
        val partes = ArrayList<String>()
        var c = carpeta(id)
        var guarda = 0
        while (c != null && guarda < 50) {
            partes.add(0, c.nombre)
            c = carpeta(c.padre)
            guarda++
        }
        partes.add(0, "Marcadores")
        return partes.joinToString(" › ")
    }

    /** Arbol aplanado (carpeta, nivel) para elegir destino. Se omite la carpeta [excluir] y todo lo que cuelga de ella. */
    fun arbol(excluir: Long = 0L): List<Pair<CarpetaMarcadores, Int>> {
        val r = ArrayList<Pair<CarpetaMarcadores, Int>>()
        fun recorrer(padre: Long, nivel: Int) {
            for (c in carpetas.filter { it.padre == padre }.sortedBy { it.nombre.lowercase() }) {
                if (c.id == excluir) continue
                r.add(Pair(c, nivel))
                recorrer(c.id, nivel + 1)
            }
        }
        recorrer(0L, 0)
        return r
    }

    // ---- marcadores

    /** False si la URL ya existe o se alcanzo el maximo. */
    fun agregar(titulo: String, url: String, carpeta: Long): Boolean {
        if (contiene(url) || items.size >= maximo) return false
        items.add(0, Marcador(titulo, url, if (carpeta(carpeta) != null) carpeta else 0L, System.currentTimeMillis()))
        guardarPronto()
        return true
    }

    fun quitar(url: String) {
        if (items.removeAll { it.url == url }) guardarPronto()
    }

    /** False si la nueva URL ya la usa otro marcador. */
    fun actualizar(m: Marcador, titulo: String, url: String, carpeta: Long): Boolean {
        if (url != m.url && contiene(url)) return false
        m.titulo = titulo
        m.url = url
        m.carpeta = if (carpeta(carpeta) != null) carpeta else 0L
        guardarPronto()
        return true
    }

    fun moverMarcador(m: Marcador, carpeta: Long) {
        m.carpeta = if (carpeta(carpeta) != null) carpeta else 0L
        guardarPronto()
    }

    // ---- carpetas

    fun crearCarpeta(nombre: String, padre: Long): CarpetaMarcadores? {
        val n = nombre.trim()
        if (n.isEmpty() || carpetas.size >= 200) return null
        val c = CarpetaMarcadores(siguienteId++, n, if (carpeta(padre) != null) padre else 0L)
        carpetas.add(c)
        guardarPronto()
        return c
    }

    fun renombrarCarpeta(c: CarpetaMarcadores, nombre: String): Boolean {
        val n = nombre.trim()
        if (n.isEmpty()) return false
        c.nombre = n
        guardarPronto()
        return true
    }

    /** True si [id] es [ancestro] o cuelga de el. */
    private fun cuelgaDe(id: Long, ancestro: Long): Boolean {
        var actual = id
        var pasos = 0
        while (actual != 0L && pasos <= carpetas.size) {
            if (actual == ancestro) return true
            actual = carpeta(actual)?.padre ?: 0L
            pasos++
        }
        return false
    }

    /** False si el destino es la propia carpeta o una de sus subcarpetas. */
    fun moverCarpeta(c: CarpetaMarcadores, nuevoPadre: Long): Boolean {
        if (nuevoPadre != 0L && (carpeta(nuevoPadre) == null || cuelgaDe(nuevoPadre, c.id))) return false
        c.padre = nuevoPadre
        guardarPronto()
        return true
    }

    /** Borra la carpeta; su contenido (subcarpetas y marcadores) pasa a la carpeta superior. Nunca se pierden marcadores. */
    fun borrarCarpeta(c: CarpetaMarcadores) {
        for (otra in carpetas) {
            if (otra.padre == c.id) otra.padre = c.padre
        }
        for (m in items) {
            if (m.carpeta == c.id) m.carpeta = c.padre
        }
        carpetas.remove(c)
        guardarPronto()
    }

    // ---- persistencia

    fun guardarPronto() {
        if (!pendiente) {
            pendiente = true
            handler.postDelayed(tarea, 1500L)
        }
    }

    fun guardarAhora() {
        handler.removeCallbacks(tarea)
        pendiente = false
        guardar()
    }

    private fun cargar() {
        try {
            val texto = AlmacenSeguro.leerTexto(archivo)
            if (texto != null) {
                val t = texto.trim()
                if (t.startsWith("[")) {
                    val arr = JSONArray(t)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        items.add(Marcador(o.optString("t"), o.getString("u"), 0L, o.optLong("h")))
                    }
                } else {
                    val j = JSONObject(t)
                    val cs = j.optJSONArray("carpetas")
                    if (cs != null) {
                        for (i in 0 until cs.length()) {
                            val o = cs.getJSONObject(i)
                            carpetas.add(CarpetaMarcadores(o.getLong("id"), o.optString("n"), o.optLong("p")))
                        }
                    }
                    val ms = j.optJSONArray("marcadores")
                    if (ms != null) {
                        for (i in 0 until ms.length()) {
                            val o = ms.getJSONObject(i)
                            items.add(Marcador(o.optString("t"), o.getString("u"), o.optLong("c"), o.optLong("h")))
                        }
                    }
                }
                reparar()
            }
        } catch (e: Exception) {
            carpetas.clear()
            items.clear()
        }
        var mayor = 0L
        for (c in carpetas) {
            if (c.id > mayor) mayor = c.id
        }
        siguienteId = mayor + 1L
    }

    /** Un archivo manipulado o dañado no puede dejar carpetas huerfanas ni ciclos. */
    private fun reparar() {
        val ids = HashSet<Long>()
        for (c in carpetas) ids.add(c.id)
        for (c in carpetas) {
            if (c.padre != 0L && !ids.contains(c.padre)) c.padre = 0L
        }
        for (c in carpetas) {
            var p = c.padre
            var pasos = 0
            while (p != 0L && pasos <= carpetas.size) {
                p = carpeta(p)?.padre ?: 0L
                pasos++
            }
            if (p != 0L) c.padre = 0L
        }
        for (m in items) {
            if (m.carpeta != 0L && !ids.contains(m.carpeta)) m.carpeta = 0L
        }
    }

    /** Mismo formato que el archivo de marcadores (v2), para la copia de seguridad. */
    fun exportarJson(): JSONObject {
        val j = JSONObject()
        j.put("v", 2)
        val cs = JSONArray()
        for (c in carpetas) cs.put(JSONObject().put("id", c.id).put("n", c.nombre).put("p", c.padre))
        j.put("carpetas", cs)
        val ms = JSONArray()
        for (m in items) ms.put(JSONObject().put("t", m.titulo).put("u", m.url).put("c", m.carpeta).put("h", m.tiempo))
        j.put("marcadores", ms)
        return j
    }

    /**
     * Añade lo de una copia a lo que ya hay, sin borrar nada: las carpetas con el mismo nombre y carpeta superior se reutilizan
     * y las URL que ya existen se saltan. Devuelve (carpetas nuevas, marcadores nuevos).
     */
    fun importarJson(j: JSONObject): Pair<Int, Int> {
        val info = HashMap<Long, Pair<String, Long>>()
        val cs = j.optJSONArray("carpetas")
        if (cs != null) {
            for (i in 0 until minOf(cs.length(), 500)) {
                val o = cs.optJSONObject(i) ?: continue
                info[o.optLong("id")] = Pair(o.optString("n").take(120), o.optLong("p"))
            }
        }
        val mapa = HashMap<Long, Long>()
        mapa[0L] = 0L
        var nuevasC = 0
        fun resolver(id: Long, prof: Int): Long {
            val ya = mapa[id]
            if (ya != null) return ya
            val d = info[id] ?: return 0L
            if (prof > 20) return 0L
            val padre = resolver(d.second, prof + 1)
            val existente = carpetas.firstOrNull { it.padre == padre && it.nombre.equals(d.first, true) }
            val r: Long
            if (existente != null) {
                r = existente.id
            } else {
                val c = crearCarpeta(d.first, padre)
                if (c != null) nuevasC++
                r = c?.id ?: 0L
            }
            mapa[id] = r
            return r
        }
        var nuevosM = 0
        val ms = j.optJSONArray("marcadores")
        if (ms != null) {
            // La copia va de más reciente a más antiguo: se añade al revés para conservar el orden.
            for (i in minOf(ms.length(), 2000) - 1 downTo 0) {
                val o = ms.optJSONObject(i) ?: continue
                val u = o.optString("u")
                if (u.length > 4000 || !(u.startsWith("http://") || u.startsWith("https://"))) continue
                if (agregar(o.optString("t").take(300), u, resolver(o.optLong("c"), 0))) nuevosM++
            }
        }
        guardarAhora()
        return Pair(nuevasC, nuevosM)
    }

    private fun guardar() {
        try {
            val j = JSONObject()
            j.put("v", 2)
            val cs = JSONArray()
            for (c in carpetas) cs.put(JSONObject().put("id", c.id).put("n", c.nombre).put("p", c.padre))
            j.put("carpetas", cs)
            val ms = JSONArray()
            for (m in items) ms.put(JSONObject().put("t", m.titulo).put("u", m.url).put("c", m.carpeta).put("h", m.tiempo))
            j.put("marcadores", ms)
            AlmacenSeguro.escribirTexto(archivo, j.toString())
        } catch (e: Exception) {
            // Sin espacio o sin permiso: se pierde solo esta escritura.
        }
    }
}

