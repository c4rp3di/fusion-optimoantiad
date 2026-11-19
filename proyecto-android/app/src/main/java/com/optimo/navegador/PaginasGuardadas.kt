package com.optimo.navegador

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Una página guardada para leer sin conexión. [bytes] suma el archivo MHTML y, si existe, la instantánea HTML. */
class PaginaGuardada(
    val id: Long,
    val titulo: String,
    val url: String,
    val fecha: Long,
    val bytes: Long,
    val tieneHtml: Boolean
)

/**
 * PUNTO DE ANCLAJE para procesar las páginas que guarda el usuario (p. ej. un futuro «scrapper»).
 *
 * Para engancharse basta con añadir una implementación a [PaginasGuardadas.procesadores] (por ejemplo en `MainActivity.onCreate`).
 * Contrato:
 *  - Se llama **en un hilo de fondo** (nunca en el principal), uno tras otro y con cada excepción aislada: un procesador que falla
 *    no afecta ni a los demás ni al guardado.
 *  - Solo recibe páginas que el usuario guardó a propósito con «Guardar página para leer sin conexión»: nunca de pestañas privadas ni
 *    de la navegación normal.
 *  - Los archivos pertenecen a Optimo y pueden borrarse en cualquier momento: el procesador debe leerlos o copiarlos al recibirlos.
 */
interface ProcesadorPagina {
    /**
     * [mht]: la página completa (MHTML, con sus recursos). [html]: instantánea del DOM ya renderizado (HTML en UTF-8, máx. 3 MB),
     * o null si no se pudo capturar; es lo más cómodo de analizar.
     */
    fun alGuardar(pagina: PaginaGuardada, mht: File, html: File?)

    /** Se llama justo después de eliminar la página, para que el procesador retire lo suyo. Opcional. */
    fun alEliminar(pagina: PaginaGuardada) {}
}

/**
 * Páginas guardadas para leer sin conexión. Los archivos van en `noBackupFilesDir/paginas` (privado de la app y fuera de las copias
 * de seguridad de Android) como `<id>.mht` y `<id>.html`; el índice (títulos y direcciones) se guarda cifrado en `paginas_guardadas.json`.
 * Todo se llama desde el hilo principal, salvo los [procesadores], que se ejecutan en un hilo de fondo.
 */
object PaginasGuardadas {

    const val MAX_PAGINAS = 100
    const val MAX_BYTES_PAGINA = 25L * 1024L * 1024L
    const val MAX_HTML_CARACTERES = 3_000_000

    /** Procesadores registrados (ver [ProcesadorPagina]). */
    val procesadores = CopyOnWriteArrayList<ProcesadorPagina>()

    private val fondo = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "optimo-paginas")
        t.isDaemon = true
        t
    }

    private val lista = ArrayList<PaginaGuardada>()
    private lateinit var dir: File
    private lateinit var indice: File

    fun iniciar(c: Context) {
        val app = c.applicationContext
        dir = File(app.noBackupFilesDir, "paginas")
        dir.mkdirs()
        indice = File(app.filesDir, "paginas_guardadas.json")
        cargar()
    }

    /** Más recientes primero. */
    fun todas(): List<PaginaGuardada> = ArrayList(lista)

    fun archivoMht(id: Long): File = File(dir, "$id.mht")

    fun archivoHtml(id: Long): File = File(dir, "$id.html")

    fun totalBytes(): Long = lista.sumOf { it.bytes }

    fun nuevoId(): Long {
        val max = lista.maxOfOrNull { it.id } ?: 0L
        val ahora = System.currentTimeMillis()
        return if (ahora > max) ahora else max + 1
    }

    /** Anota una página ya escrita en disco y avisa a los procesadores. Null si se alcanzó el máximo. */
    fun registrar(id: Long, titulo: String, url: String, bytes: Long, tieneHtml: Boolean): PaginaGuardada? {
        if (lista.size >= MAX_PAGINAS) return null
        val pg = PaginaGuardada(id, titulo.take(300), url.take(2000), System.currentTimeMillis(), bytes, tieneHtml)
        lista.add(0, pg)
        guardar()
        val mht = archivoMht(id)
        val html = if (tieneHtml) archivoHtml(id) else null
        fondo.execute {
            for (p in procesadores) {
                try {
                    p.alGuardar(pg, mht, html)
                } catch (e: Exception) {
                    // Un procesador que falla no afecta a los demás.
                }
            }
        }
        return pg
    }

    fun eliminar(id: Long) {
        val pg = lista.firstOrNull { it.id == id } ?: return
        lista.remove(pg)
        archivoMht(id).delete()
        archivoHtml(id).delete()
        guardar()
        fondo.execute {
            for (p in procesadores) {
                try {
                    p.alEliminar(pg)
                } catch (e: Exception) {
                    // Idem.
                }
            }
        }
    }

    fun eliminarTodas() {
        for (pg in ArrayList(lista)) eliminar(pg.id)
    }

    private fun cargar() {
        lista.clear()
        var indiceLegible = true
        try {
            if (indice.exists()) {
                val t = AlmacenSeguro.leerTexto(indice)
                if (t == null) {
                    indiceLegible = false
                } else {
                    val a = JSONArray(t)
                    for (i in 0 until minOf(a.length(), MAX_PAGINAS)) {
                        val o = a.optJSONObject(i) ?: continue
                        val id = o.optLong("id")
                        if (!archivoMht(id).exists()) continue
                        lista.add(
                            PaginaGuardada(
                                id, o.optString("t"), o.optString("u"), o.optLong("f"), o.optLong("b"),
                                o.optBoolean("h") && archivoHtml(id).exists()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            indiceLegible = false
        }
        // Archivos sin entrada en el índice (restos de un guardado interrumpido): se borran, salvo que el índice no se haya podido leer.
        if (indiceLegible) {
            try {
                val ids = lista.map { it.id.toString() }.toSet()
                dir.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in ids) f.delete() }
            } catch (e: Exception) {
                // Sin permisos o sin carpeta: no pasa nada.
            }
        }
    }

    private fun guardar() {
        try {
            val a = JSONArray()
            for (p in lista) {
                a.put(JSONObject().put("id", p.id).put("t", p.titulo).put("u", p.url).put("f", p.fecha).put("b", p.bytes).put("h", p.tieneHtml))
            }
            AlmacenSeguro.escribirTexto(indice, a.toString())
        } catch (e: Exception) {
            // Sin espacio: se pierde solo esta escritura.
        }
    }
}
