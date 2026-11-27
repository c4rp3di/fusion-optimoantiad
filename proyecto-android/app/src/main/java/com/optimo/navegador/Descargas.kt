package com.optimo.navegador

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una descarga hecha desde Optimo (solo pestañas normales): lo que hace falta para listarla, reintentarla o volver a su página. */
class RegistroDescarga(
    val id: Long, val nombre: String, val url: String, val mime: String, val host: String, val fecha: Long, val origen: String
)

/** Tipos de archivo para filtrar y poner icono. */
object TiposDescarga {
    const val OTRO = 0
    const val IMAGEN = 1
    const val VIDEO = 2
    const val AUDIO = 3
    const val DOCUMENTO = 4
    const val APP = 5

    fun de(nombre: String, mime: String): Int {
        val n = nombre.lowercase()
        val m = mime.lowercase()
        return when {
            n.endsWith(".apk") || m == "application/vnd.android.package-archive" -> APP
            m.startsWith("image/") || Regex(".*\\.(jpe?g|png|gif|webp|bmp|svg|heic|avif)$").matches(n) -> IMAGEN
            m.startsWith("video/") || Regex(".*\\.(mp4|mkv|webm|avi|mov|m4v|3gp)$").matches(n) -> VIDEO
            m.startsWith("audio/") || Regex(".*\\.(mp3|m4a|ogg|opus|flac|wav|aac)$").matches(n) -> AUDIO
            m == "application/pdf" || m.startsWith("text/") || m.contains("document") || m.contains("sheet") || m.contains("presentation") ||
                Regex(".*\\.(pdf|docx?|xlsx?|pptx?|odt|ods|txt|csv|epub|rtf|md)$").matches(n) -> DOCUMENTO
            else -> OTRO
        }
    }

    fun icono(t: Int): String = when (t) {
        IMAGEN -> "🖼"
        VIDEO -> "🎬"
        AUDIO -> "🎧"
        DOCUMENTO -> "📄"
        APP -> "📦"
        else -> "📁"
    }
}

/** Lista de descargas de Optimo, guardada cifrada en el dispositivo (la más reciente primero, hasta 300). */
object ListaDescargas {
    private const val MAX = 300
    private val lista = ArrayList<RegistroDescarga>()
    private var archivo: File? = null
    private val lock = Any()

    fun iniciar(c: Context) {
        archivo = File(c.filesDir, "descargas.json")
        cargar()
    }

    fun todas(): List<RegistroDescarga> = synchronized(lock) { ArrayList(lista) }

    fun agregar(r: RegistroDescarga) {
        synchronized(lock) {
            lista.removeAll { it.id == r.id }
            lista.add(0, r)
            while (lista.size > MAX) lista.removeAt(lista.size - 1)
        }
        guardar()
    }

    fun quitar(id: Long) {
        synchronized(lock) { lista.removeAll { it.id == id } }
        guardar()
    }

    fun vaciar() {
        synchronized(lock) { lista.clear() }
        guardar()
    }

    private fun guardar() {
        try {
            val f = archivo ?: return
            val a = JSONArray()
            for (r in todas()) {
                a.put(
                    JSONObject().put("id", r.id).put("n", r.nombre).put("u", r.url).put("m", r.mime)
                        .put("h", r.host).put("f", r.fecha).put("o", r.origen)
                )
            }
            AlmacenSeguro.escribirTexto(f, a.toString())
        } catch (e: Exception) {
            // Sin espacio o sin permiso: se pierde solo esta escritura.
        }
    }

    private fun cargar() {
        try {
            val f = archivo ?: return
            val texto = AlmacenSeguro.leerTexto(f) ?: return
            val a = JSONArray(texto)
            synchronized(lock) {
                lista.clear()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    lista.add(
                        RegistroDescarga(
                            o.optLong("id"), o.optString("n"), o.optString("u"), o.optString("m"),
                            o.optString("h"), o.optLong("f"), o.optString("o")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Archivo dañado: se empieza de cero.
        }
    }
}

/** Estado de una descarga según el gestor de descargas de Android. */
class EstadoDescarga(
    val estado: Int, val total: Long, val actual: Long, val razon: Int, val uriLocal: String?
) {
    val enCurso: Boolean
        get() = estado == DownloadManager.STATUS_RUNNING || estado == DownloadManager.STATUS_PENDING || estado == DownloadManager.STATUS_PAUSED
    val completada: Boolean get() = estado == DownloadManager.STATUS_SUCCESSFUL
    val fallida: Boolean get() = estado == DownloadManager.STATUS_FAILED

    /** Nombre real del archivo en disco (el gestor puede haberlo cambiado, p. ej. «foto (1).jpg»). */
    val nombreLocal: String? get() = uriLocal?.let { try { Uri.parse(it).lastPathSegment } catch (e: Exception) { null } }
}

/** Consultas y acciones sobre el gestor de descargas de Android. */
class GestorDescargas(private val ctx: Context) {
    private val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Estado de cada descarga; las que ya no existen en el gestor (archivo borrado desde fuera) no aparecen. */
    fun consultar(ids: List<Long>): Map<Long, EstadoDescarga> {
        val r = HashMap<Long, EstadoDescarga>()
        if (ids.isEmpty()) return r
        try {
            val consulta = DownloadManager.Query().setFilterById(*ids.toLongArray())
            dm.query(consulta)?.use { c ->
                val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val iEstado = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iActual = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iRazon = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                val iUri = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                while (c.moveToNext()) {
                    r[c.getLong(iId)] = EstadoDescarga(
                        c.getInt(iEstado), c.getLong(iTotal), c.getLong(iActual), c.getInt(iRazon),
                        if (iUri >= 0) c.getString(iUri) else null
                    )
                }
            }
        } catch (e: Exception) {
            // Sin permiso o gestor no disponible: se muestra lo que haya.
        }
        return r
    }

    fun motivo(e: EstadoDescarga): String = when (e.estado) {
        DownloadManager.STATUS_PENDING -> "En cola"
        DownloadManager.STATUS_PAUSED -> when (e.razon) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Esperando Wi-Fi"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Sin conexión"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Reintentando"
            else -> "En pausa"
        }
        DownloadManager.STATUS_FAILED -> when (e.razon) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Sin espacio"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "El archivo ya existe"
            DownloadManager.ERROR_FILE_ERROR -> "No se pudo escribir el archivo"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Error de red"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Demasiadas redirecciones"
            DownloadManager.ERROR_CANNOT_RESUME -> "No se pudo reanudar"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Almacenamiento no disponible"
            401, 403 -> "Acceso denegado"
            404 -> "No encontrado"
            in 400..599 -> "Error del servidor (" + e.razon + ")"
            else -> "Error"
        }
        else -> ""
    }

    private fun uri(id: Long): Uri? = try {
        dm.getUriForDownloadedFile(id)
    } catch (e: Exception) {
        null
    }

    private fun tipo(id: Long, mime: String): String {
        val t = try { dm.getMimeTypeForDownloadedFile(id) } catch (e: Exception) { null }
        return if (!t.isNullOrEmpty()) t else if (mime.isNotEmpty()) mime else "*/*"
    }

    /** Abre el archivo con la app que corresponda; con [elegir] muestra siempre el selector de apps. */
    fun abrir(id: Long, mime: String, elegir: Boolean): Boolean {
        val u = uri(id) ?: return false
        return try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(u, tipo(id, mime))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(if (elegir) Intent.createChooser(i, "Abrir con").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun compartir(ids: List<Long>, mimes: Map<Long, String>): Boolean {
        val uris = ArrayList<Uri>()
        for (id in ids) uri(id)?.let { uris.add(it) }
        if (uris.isEmpty()) return false
        return try {
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
            i.type = if (ids.size == 1) tipo(ids[0], mimes[ids[0]] ?: "") else "*/*"
            if (uris.size == 1) i.putExtra(Intent.EXTRA_STREAM, uris[0]) else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Cancela la descarga si sigue en curso y borra el archivo (o lo que lleve descargado). */
    fun borrarArchivo(id: Long) {
        try {
            dm.remove(id)
        } catch (e: Exception) {
            // Ya no existía.
        }
    }
}

/** Quita de un nombre de archivo lo que no vale en un nombre (barras, dos puntos, comillas…). */
fun limpiarNombreArchivo(n: String): String = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)

/** Antes de descargar: muestra de dónde viene el archivo y deja cambiarle el nombre. */
fun confirmarDescarga(act: Activity, paleta: Paleta, privada: Boolean, nombre: String, host: String, mime: String, alAceptar: (String) -> Unit) {
    val h = Hoja(act, paleta, privada)
    h.teclado(true)
    h.encabezado("Descargar archivo")
    h.parrafo("De " + host + (if (mime.isNotEmpty()) " · " + mime else ""))
    h.etiqueta("Nombre")
    val campo = h.campo("", nombre)
    campo.setSelectAllOnFocus(true)
    h.boton("Descargar") {
        val n = limpiarNombreArchivo(campo.text.toString())
        h.cerrar()
        alAceptar(if (n.isEmpty()) nombre else n)
    }
    h.boton("Cancelar") { h.cerrar() }
    h.mostrar()
    campo.requestFocus()
}
