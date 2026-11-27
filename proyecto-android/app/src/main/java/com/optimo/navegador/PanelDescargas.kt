package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Lo que el panel de descargas necesita de MainActivity. */
interface AnfitrionDescargas {
    /** Abre la carpeta Descargas en tu gestor de archivos. */
    fun abrirCarpeta()

    /** Vuelve a descargar [r] con su mismo nombre. */
    fun reintentar(r: RegistroDescarga)
    fun abrirEnPestana(url: String)
    fun copiar(texto: String)
    fun avisar(texto: String)
    fun volverAlMenu()
}

/**
 * Descargas hechas desde Optimo (solo pestañas normales): la que está en curso con su progreso y velocidad, y el resto por días,
 * con filtros por estado y tipo, búsqueda, selección múltiple y un menú por descarga (abrir, compartir, reintentar, quitar de la
 * lista, borrar el archivo). Al final, los ajustes de descarga.
 */
class PanelDescargas(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionDescargas,
    private val privada: Boolean
) {
    companion object {
        private val ESTADOS = listOf("Todas", "En curso", "Completadas", "Fallidas")
        private val TIPOS = listOf("Todos", "Imágenes", "Vídeos", "Audio", "Documentos", "Apps", "Otros")
        private val TIPOS_ID = listOf(-1, TiposDescarga.IMAGEN, TiposDescarga.VIDEO, TiposDescarga.AUDIO, TiposDescarga.DOCUMENTO, TiposDescarga.APP, TiposDescarga.OTRO)
    }

    private val hoja = Hoja(act, paleta, privada, true)
    private val gestor = GestorDescargas(act)
    private val ui = Handler(Looper.getMainLooper())
    private var filtroEstado = 0
    private var filtroTipo = 0
    private var consulta = ""
    private val seleccion = LinkedHashSet<Long>()
    private var estados: Map<Long, EstadoDescarga> = emptyMap()
    private val muestra = HashMap<Long, Pair<Long, Long>>()
    private val velocidad = HashMap<Long, Long>()
    private var vivo = true
    private lateinit var zonaLista: LinearLayout

    fun mostrar() {
        refrescarEstados()
        pintarTodo()
        hoja.alCerrar { vivo = false }
        hoja.interceptarAtras {
            if (seleccion.isNotEmpty()) {
                seleccion.clear()
                pintarLista()
            } else {
                hoja.cerrar()
            }
            true
        }
        hoja.mostrar()
        programar()
    }

    /** Mientras haya algo descargándose, la lista se actualiza cada segundo (progreso y velocidad). */
    private fun programar() {
        ui.postDelayed({
            if (vivo) {
                refrescarEstados()
                if (estados.values.any { it.enCurso }) pintarLista()
                programar()
            }
        }, 1000L)
    }

    private fun refrescarEstados() {
        estados = gestor.consultar(ListaDescargas.todas().map { it.id })
        val ahora = SystemClock.elapsedRealtime()
        for ((id, e) in estados) {
            if (!e.enCurso) continue
            val previo = muestra[id]
            if (previo != null && ahora > previo.second) {
                val v = (e.actual - previo.first) * 1000L / (ahora - previo.second)
                if (v >= 0L) velocidad[id] = v
            }
            muestra[id] = Pair(e.actual, ahora)
        }
    }

    private fun confirmar(titulo: String, mensaje: String, boton: String, accion: () -> Unit) {
        Dialogos.confirmar(act, paleta, privada, titulo, mensaje, boton, true, accion)
    }

    private fun dpi(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- datos

    private fun nombreDe(r: RegistroDescarga): String = estados[r.id]?.nombreLocal?.takeIf { it.isNotEmpty() } ?: r.nombre

    private fun tipoDe(r: RegistroDescarga): Int = TiposDescarga.de(nombreDe(r), r.mime)

    private fun cumple(r: RegistroDescarga): Boolean {
        val e = estados[r.id]
        val q = consulta.trim().lowercase()
        if (q.isNotEmpty() && !nombreDe(r).lowercase().contains(q) && !r.host.lowercase().contains(q)) return false
        val okEstado = when (filtroEstado) {
            1 -> e?.enCurso == true
            2 -> e?.completada == true
            3 -> e?.fallida == true
            else -> true
        }
        val tipoSel = TIPOS_ID[filtroTipo]
        return okEstado && (tipoSel < 0 || tipoDe(r) == tipoSel)
    }

    private fun etiquetaDia(t: Long): String {
        val hoy = java.util.Calendar.getInstance()
        val d = java.util.Calendar.getInstance()
        d.timeInMillis = t
        fun mismo(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (mismo(hoy, d)) return "Hoy"
        hoy.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (mismo(hoy, d)) return "Ayer"
        return java.text.SimpleDateFormat("EEEE, d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(t))
            .replaceFirstChar { c -> c.uppercase() }
    }

    // ---------------------------------------------------------------- estructura

    private fun pintarTodo() {
        hoja.limpiar()
        val todas = ListaDescargas.todas()
        val total = estados.values.filter { it.completada }.sumOf { it.total.coerceAtLeast(0L) }
        val cab = LinearLayout(act)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        val titulo = TextView(act)
        titulo.text = "Descargas · " + todas.size + (if (total > 0) " · " + formatoTamano(total) else "")
        titulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        titulo.setTextColor(paleta.texto)
        titulo.typeface = android.graphics.Typeface.DEFAULT_BOLD
        titulo.setPadding(dpi(4), 0, 0, 0)
        cab.addView(titulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cab.addView(hoja.botonIcono("☑") {
            if (seleccion.isEmpty()) {
                val primera = todas.firstOrNull { cumple(it) }
                if (primera != null) seleccion.add(primera.id)
            } else {
                seleccion.clear()
            }
            pintarLista()
        }, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        cab.addView(hoja.botonIcono("✕") { hoja.cerrar() }, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        hoja.agregar(cab)

        hoja.campo("Buscar por nombre o sitio", consulta) { t ->
            consulta = t
            pintarLista()
        }
        val enCurso = estados.values.count { it.enCurso }
        val fallidas = estados.values.count { it.fallida }
        val etiquetasEstado = ESTADOS.mapIndexed { i, e ->
            when (i) {
                1 -> if (enCurso > 0) "$e $enCurso" else e
                3 -> if (fallidas > 0) "$e $fallidas" else e
                else -> e
            }
        }
        hoja.chips(etiquetasEstado, filtroEstado) { i ->
            filtroEstado = i
            seleccion.clear()
            pintarTodo()
        }
        hoja.chips(TIPOS, filtroTipo) { i ->
            filtroTipo = i
            seleccion.clear()
            pintarTodo()
        }
        zonaLista = hoja.zona()
        pintarLista()
        pintarAjustes()
    }

    private fun pintarLista() {
        hoja.llenar(zonaLista) { listar() }
    }

    private fun alternar(id: Long) {
        if (!seleccion.remove(id)) seleccion.add(id)
        pintarLista()
    }

    private fun listar() {
        val todas = ListaDescargas.todas()
        if (seleccion.isNotEmpty()) {
            hoja.barraAcciones(
                seleccion.size.toString() + (if (seleccion.size == 1) " elegida" else " elegidas"),
                listOf(
                    Triple("Compartir", false, { compartirElegidas() }),
                    Triple("Quitar de la lista", false, { quitarElegidas() }),
                    Triple("Borrar archivos", true, { borrarElegidas() }),
                    Triple("✕", false, {
                        seleccion.clear()
                        pintarLista()
                    })
                )
            )
        }
        val visibles = todas.filter { cumple(it) }
        if (visibles.isEmpty()) {
            hoja.nota(
                if (todas.isEmpty()) "Aún no hay descargas. Las que hagas desde Optimo aparecerán aquí (las de pestañas privadas no se anotan)."
                else "Nada coincide con estos filtros."
            )
            return
        }
        // En curso, arriba y con su progreso.
        for (r in visibles) {
            val e = estados[r.id] ?: continue
            if (!e.enCurso) continue
            val v = velocidad[r.id] ?: 0L
            val avance = if (e.total > 0) formatoTamano(e.actual) + " de " + formatoTamano(e.total) else formatoTamano(e.actual)
            val detalle = gestor.motivo(e).let { m ->
                if (e.estado == android.app.DownloadManager.STATUS_RUNNING) avance + (if (v > 0L) " · " + formatoTamano(v) + "/s" else "") else m + " · " + avance
            } + " · " + r.host
            hoja.tarjetaProgreso(
                nombreDe(r), detalle,
                if (e.total > 0) (e.actual * 1000L / e.total).toInt() else -1,
                "Cancelar", { cancelar(r) }
            )
        }
        val resto = visibles.filter { estados[it.id]?.enCurso != true }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        var dia = ""
        for (r in resto) {
            val d = etiquetaDia(r.fecha)
            if (d != dia) {
                hoja.seccion(d)
                dia = d
            }
            fila(r, fmt.format(java.util.Date(r.fecha)))
        }
        if (resto.isNotEmpty()) hoja.nota("Mantén pulsado para elegir varias.")
    }

    private fun fila(r: RegistroDescarga, hora: String) {
        val e = estados[r.id]
        val tipo = tipoDe(r)
        val detalle = when {
            e == null -> "Archivo eliminado · " + r.host + " · " + hora
            e.fallida -> "Falló · " + gestor.motivo(e) + " · " + r.host
            else -> r.host + " · " + (if (e.total > 0) formatoTamano(e.total) + " · " else "") + hora
        }
        val rgb = when (tipo) {
            TiposDescarga.IMAGEN -> 0x2E7D32
            TiposDescarga.VIDEO -> 0xEF6C00
            TiposDescarga.AUDIO -> 0x1E88E5
            TiposDescarga.DOCUMENTO -> 0xC62828
            TiposDescarga.APP -> 0x6D4C41
            else -> 0x607D8B
        }
        val tono = rgb or (0xFF shl 24)
        hoja.filaLista(
            TiposDescarga.icono(tipo), tono, nombreDe(r), detalle,
            if (seleccion.isEmpty()) null else seleccion.contains(r.id),
            {
                if (seleccion.isNotEmpty()) {
                    alternar(r.id)
                } else if (e != null && e.completada) {
                    abrir(r, false)
                } else {
                    menu(r)
                }
            },
            { alternar(r.id) },
            { menu(r) }
        )
    }

    // ---------------------------------------------------------------- acciones

    private fun abrir(r: RegistroDescarga, elegir: Boolean) {
        if (!gestor.abrir(r.id, r.mime, elegir)) g.avisar("No hay ninguna app para abrir este archivo, o ya no existe")
    }

    private fun cancelar(r: RegistroDescarga) {
        confirmar("Cancelar la descarga", "Se detendrá «" + nombreDe(r) + "» y se borrará lo descargado.", "Cancelar descarga") {
            gestor.borrarArchivo(r.id)
            ListaDescargas.quitar(r.id)
            g.avisar("Descarga cancelada")
            refrescarEstados()
            pintarTodo()
        }
    }

    private fun menu(r: RegistroDescarga) {
        val e = estados[r.id]
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        if (e != null && e.completada) {
            l.add(Triple("Abrir", false, { abrir(r, false) }))
            l.add(Triple("Abrir con…", false, { abrir(r, true) }))
            l.add(Triple("Compartir…", false, {
                if (!gestor.compartir(listOf(r.id), mapOf(r.id to r.mime))) g.avisar("No se pudo compartir el archivo")
            }))
            l.add(Triple("Mostrar en la carpeta", false, {
                hoja.cerrar()
                g.abrirCarpeta()
            }))
        } else if (e == null || e.fallida) {
            l.add(Triple(if (e == null) "Descargar de nuevo" else "Reintentar", false, {
                g.reintentar(r)
                ListaDescargas.quitar(r.id)
                refrescarEstados()
                pintarTodo()
            }))
        }
        l.add(Triple("Copiar el enlace de descarga", false, { g.copiar(r.url) }))
        if (r.origen.startsWith("http")) {
            l.add(Triple("Abrir la página de origen", false, {
                hoja.cerrar()
                g.abrirEnPestana(r.origen)
            }))
        }
        l.add(Triple("Quitar de la lista", false, {
            ListaDescargas.quitar(r.id)
            pintarTodo()
        }))
        if (e != null) {
            l.add(Triple("Borrar el archivo", true, {
                confirmar("Borrar el archivo", "Se borrará «" + nombreDe(r) + "» del dispositivo.", "Borrar") {
                    gestor.borrarArchivo(r.id)
                    ListaDescargas.quitar(r.id)
                    g.avisar("Archivo borrado")
                    refrescarEstados()
                    pintarTodo()
                }
            }))
        }
        Dialogos.acciones(act, paleta, privada, nombreDe(r), l)
    }

    private fun elegidas(): List<RegistroDescarga> = ListaDescargas.todas().filter { seleccion.contains(it.id) }

    private fun compartirElegidas() {
        val l = elegidas().filter { estados[it.id]?.completada == true }
        if (l.isEmpty() || !gestor.compartir(l.map { it.id }, l.associate { it.id to it.mime })) g.avisar("Nada que compartir en la selección")
    }

    private fun quitarElegidas() {
        for (r in elegidas()) ListaDescargas.quitar(r.id)
        seleccion.clear()
        pintarTodo()
    }

    private fun borrarElegidas() {
        val l = elegidas()
        confirmar("Borrar " + l.size + (if (l.size == 1) " archivo" else " archivos"), "Se borrarán del dispositivo y de la lista.", "Borrar") {
            for (r in l) {
                gestor.borrarArchivo(r.id)
                ListaDescargas.quitar(r.id)
            }
            seleccion.clear()
            g.avisar("Archivos borrados")
            refrescarEstados()
            pintarTodo()
        }
    }

    // ---------------------------------------------------------------- pie

    private fun pintarAjustes() {
        hoja.seccion("Carpeta y ajustes")
        hoja.fila("Abrir la carpeta Descargas", "En tu gestor de archivos", icono = "📂") {
            hoja.cerrar()
            g.abrirCarpeta()
        }
        hoja.interruptor("Confirmar antes de descargar", "Ver el sitio y poder cambiar el nombre del archivo", ajustes.descargaConfirmar) { v ->
            ajustes.descargaConfirmar = v
        }
        hoja.interruptor("Descargar solo con Wi-Fi", "Sin Wi-Fi, la descarga espera a tenerlo", ajustes.descargaSoloWifi) { v ->
            ajustes.descargaSoloWifi = v
        }
        hoja.interruptor("Recordar las descargas en esta lista", "No se anotan las de pestañas privadas", ajustes.descargaRecordar) { v ->
            ajustes.descargaRecordar = v
        }
        if (ListaDescargas.todas().isNotEmpty()) {
            hoja.boton("Vaciar la lista…", true) {
                confirmar("Vaciar la lista", "Se quitan las descargas de la lista. Los archivos no se borran.", "Vaciar") {
                    ListaDescargas.vaciar()
                    g.avisar("Lista vaciada")
                    pintarTodo()
                }
            }
        }
    }
}
