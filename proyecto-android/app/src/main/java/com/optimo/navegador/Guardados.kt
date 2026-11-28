package com.optimo.navegador

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout

/** Lo que Guardados necesita de MainActivity. */
interface AnfitrionGuardados {
    fun abrir(url: String)
    fun abrirNueva(url: String)
    fun copiar(texto: String)
    fun compartir(url: String)
    fun avisar(texto: String)
    fun quitarMarcador(url: String)
    fun esLocal(url: String): Boolean
    fun descripcionLocal(url: String): String
    fun normalizarUrl(texto: String): String?
    fun refrescarUi()
    fun abrirSinConexion(pg: PaginaGuardada)
    fun verLista(l: ListaEnlaces)
    fun rastrearDeNuevo(l: ListaEnlaces)
    fun descargas()
    fun ajustesHistorial()
    fun borrarPorRango(volver: () -> Unit)
    fun borrarHistorialDe(sitio: String)
    fun cerradas(): Int
    fun reabrir()
    fun volverAlMenu()

    /** Aplicaciones web locales (Guardados › Aplicaciones): borrar sus datos, quitarlas de la lista y conservar sus datos en los borrados globales. */
    fun borrarDatosApp(espacio: String)
    fun quitarApp(espacio: String)
    fun datosConservados(espacio: String): Boolean
    fun conservarDatos(espacio: String, v: Boolean)
}

/** Color estable para el círculo de un sitio (sale del texto, así el mismo sitio siempre tiene el mismo color). */
fun tonoDe(texto: String): Int {
    val h = ((texto.hashCode() % 360) + 360) % 360
    return Color.HSVToColor(floatArrayOf(h.toFloat(), 0.55f, 0.85f))
}

private fun letraDe(texto: String): String {
    val t = texto.trim().removePrefix("www.")
    return if (t.isEmpty()) "?" else t.substring(0, 1).uppercase()
}

/** Formularios de marcador: editar (nombre, dirección, carpeta) y elegir carpeta, como hojas inferiores. */
class EditorMarcador(
    private val act: Activity,
    private val paleta: Paleta,
    private val marcadores: Marcadores,
    private val g: AnfitrionGuardados,
    private val privada: () -> Boolean
) {
    /** [alGuardar] devuelve false para dejar el formulario abierto. */
    fun editar(encabezado: String, titulo: String, url: String, carpeta: Long, alGuardar: (String, String, Long) -> Boolean) {
        val h = Hoja(act, paleta, privada())
        h.teclado(false)
        var elegida = carpeta
        // Un marcador de archivo del dispositivo (optimo://local/…) no es una dirección web: se muestra sin poder editarse.
        val local = g.esLocal(url)
        h.encabezado(encabezado)
        h.etiqueta("Nombre")
        val cTitulo = h.campo("Nombre", titulo)
        h.etiqueta("Dirección")
        val cUrl = h.campo("https://…", if (local) g.descripcionLocal(url) else url, true)
        cUrl.isEnabled = !local
        val zona = h.zona()
        fun pintarCarpeta() {
            h.llenar(zona) {
                h.fila("Carpeta", null, marcadores.ruta(elegida), icono = "📁") {
                    elegirCarpeta("Guardar en", elegida, 0L) { id ->
                        elegida = id
                        pintarCarpeta()
                    }
                }
            }
        }
        pintarCarpeta()
        h.boton("Guardar") {
            val u = if (local) url else g.normalizarUrl(cUrl.text.toString())
            if (u == null) {
                cUrl.error = "Dirección no válida"
            } else {
                val nombre = cTitulo.text.toString().trim()
                if (alGuardar(if (nombre.isEmpty()) u else nombre, u, elegida)) h.cerrar()
            }
        }
        h.boton("Cancelar") { h.cerrar() }
        h.mostrar()
    }

    /** Lista de carpetas (con sangría) para elegir destino. [excluir] oculta una carpeta y todo lo que cuelga de ella. */
    fun elegirCarpeta(titulo: String, actualId: Long, excluir: Long, alElegir: (Long) -> Unit) {
        val h = Hoja(act, paleta, privada())
        h.encabezado(titulo)
        h.fila("Marcadores (raíz)", null, null, false, if (actualId == 0L) "●" else "📁") {
            h.cerrar()
            alElegir(0L)
        }
        for ((c, nivel) in marcadores.arbol(excluir)) {
            h.fila("    ".repeat(nivel) + c.nombre, null, null, false, if (c.id == actualId) "●" else "📁") {
                h.cerrar()
                alElegir(c.id)
            }
        }
        h.mostrar()
    }
}

/**
 * Guardados: marcadores (con carpetas), historial, páginas guardadas sin conexión y listas de enlaces en un solo panel,
 * con búsqueda, selección múltiple (mantén pulsado), menú por elemento y orden. Descargas abre tu gestor de archivos.
 */
class PantallaGuardados(
    private val act: Activity,
    private val paleta: Paleta,
    private val marcadores: Marcadores,
    private val historial: ListaPersistente,
    private val g: AnfitrionGuardados,
    private val editor: EditorMarcador,
    private val privada: Boolean,
    inicial: String = MARCADORES,
    carpetaInicial: Long = 0L
) {
    companion object {
        const val MARCADORES = "marcadores"
        const val HISTORIAL = "historial"
        const val PAGINAS = "paginas"
        const val APPS = "apps"
        const val LISTAS = "listas"
        private val ORDENES = listOf("Como los guardaste", "Nombre (A–Z)", "Más recientes primero")
        private const val LOTE = 150
    }

    private val hoja = Hoja(act, paleta, privada, true)
    private val prefs = act.getSharedPreferences("optimo_guardados", android.content.Context.MODE_PRIVATE)
    private var pestana = inicial
    private var carpeta = if (marcadores.carpeta(carpetaInicial) != null) carpetaInicial else 0L
    private var consulta = ""
    private var limite = LOTE
    private val seleccion = LinkedHashSet<String>()
    private lateinit var zonaAcciones: LinearLayout
    private lateinit var zonaLista: LinearLayout

    fun mostrar() {
        pintarTodo()
        hoja.interceptarAtras {
            atras()
            true
        }
        hoja.mostrar()
    }

    private fun atras() {
        if (seleccion.isNotEmpty()) {
            seleccion.clear()
            pintarLista()
        } else if (pestana == MARCADORES && carpeta != 0L && consulta.isEmpty()) {
            carpeta = marcadores.carpeta(carpeta)?.padre ?: 0L
            pintarLista()
        } else {
            hoja.cerrar()
            g.volverAlMenu()
        }
    }

    private fun cerrarYHacer(accion: () -> Unit) {
        hoja.cerrar()
        accion()
    }

    private fun confirmar(titulo: String, mensaje: String, boton: String, aviso: Boolean = true, accion: () -> Unit) {
        Dialogos.confirmar(act, paleta, privada, titulo, mensaje, boton, aviso, accion)
    }

    private fun acciones(titulo: String?, items: List<Triple<String, Boolean, () -> Unit>>) {
        Dialogos.acciones(act, paleta, privada, titulo, items)
    }

    private fun orden(): Int = prefs.getInt("orden", 0).coerceIn(0, 2)

    // ---------------------------------------------------------------- estructura

    private fun pintarTodo() {
        hoja.limpiar()
        hoja.barraTitulo("Guardados", { atras() }, { hoja.cerrar() })
        val nombres = listOf("Marcadores", "Historial", "Páginas guardadas", "Aplicaciones", "Listas de enlaces", "Descargas")
        val claves = listOf(MARCADORES, HISTORIAL, PAGINAS, APPS, LISTAS, "descargas")
        hoja.chips(nombres, claves.indexOf(pestana).coerceAtLeast(0)) { i ->
            if (claves[i] == "descargas") {
                cerrarYHacer { g.descargas() }
            } else {
                pestana = claves[i]
                consulta = ""
                limite = LOTE
                seleccion.clear()
                pintarTodo()
            }
        }
        val pista = when (pestana) {
            HISTORIAL -> "Buscar en el historial"
            PAGINAS -> "Buscar en las páginas guardadas"
            APPS -> "Buscar en las aplicaciones"
            LISTAS -> "Buscar en las listas"
            else -> "Buscar en los marcadores"
        }
        hoja.campo(pista, consulta) { t ->
            consulta = t
            limite = LOTE
            pintarLista()
        }
        zonaAcciones = hoja.zona()
        zonaLista = hoja.zona()
        pintarLista()
    }

    private fun pintarLista() {
        hoja.llenar(zonaAcciones) { pintarAcciones() }
        hoja.llenar(zonaLista) {
            when (pestana) {
                HISTORIAL -> listaHistorial()
                PAGINAS -> listaPaginas()
                APPS -> listaApps()
                LISTAS -> listaListas()
                else -> listaMarcadores()
            }
        }
    }

    private fun alternarSeleccion(url: String) {
        if (!seleccion.remove(url)) seleccion.add(url)
        pintarLista()
    }

    private fun pintarAcciones() {
        if (seleccion.isNotEmpty() && (pestana == MARCADORES || pestana == HISTORIAL)) {
            val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
            l.add(Triple("Abrir", false, { abrirSeleccion() }))
            if (pestana == MARCADORES) {
                l.add(Triple("Mover", false, { moverSeleccion() }))
            } else {
                l.add(Triple("＋ Marcadores", false, { marcarSeleccion() }))
            }
            l.add(Triple("Eliminar", true, { eliminarSeleccion() }))
            l.add(Triple("✕", false, {
                seleccion.clear()
                pintarLista()
            }))
            hoja.barraAcciones(seleccion.size.toString() + (if (seleccion.size == 1) " elegido" else " elegidos"), l)
            return
        }
        when (pestana) {
            MARCADORES -> {
                val total = marcadores.todos().size
                hoja.barraAcciones(
                    if (carpeta == 0L) total.toString() + (if (total == 1) " marcador" else " marcadores") else marcadores.ruta(carpeta),
                    listOf(
                        Triple("＋ Carpeta", false, { nuevaCarpeta() }),
                        Triple("Orden", false, { elegirOrden() })
                    )
                )
            }
            HISTORIAL -> {
                val n = historial.todos().size
                hoja.barraAcciones(
                    n.toString() + (if (n == 1) " página" else " páginas"),
                    listOf(
                        Triple("Borrar…", true, { cerrarYHacer { g.borrarPorRango { } } }),
                        Triple("Ajustes", false, { cerrarYHacer { g.ajustesHistorial() } })
                    )
                )
            }
            PAGINAS -> {
                val n = PaginasGuardadas.todas().size
                hoja.barraAcciones(
                    n.toString() + (if (n == 1) " página" else " páginas") + " · " + formatoTamano(PaginasGuardadas.totalBytes()),
                    if (n > 0) listOf(Triple("Borrar todas…", true, { borrarTodasPaginas() })) else emptyList()
                )
            }
            APPS -> {
                val n = AppLocal.registradas().size
                hoja.barraAcciones(n.toString() + (if (n == 1) " aplicación" else " aplicaciones"), emptyList())
            }
            else -> {
                val n = ListasEnlaces.todas().size
                hoja.barraAcciones(
                    n.toString() + (if (n == 1) " lista" else " listas"),
                    if (n > 0) listOf(Triple("Borrar todas…", true, { borrarTodasListas() })) else emptyList()
                )
            }
        }
    }

    // ---------------------------------------------------------------- marcadores

    private fun coincide(texto: String, url: String): Boolean {
        val q = consulta.trim().lowercase()
        return q.isEmpty() || texto.lowercase().contains(q) || url.lowercase().contains(q)
    }

    private fun ordenar(l: List<Marcador>): List<Marcador> = when (orden()) {
        1 -> l.sortedBy { (if (it.titulo.isNotEmpty()) it.titulo else it.url).lowercase() }
        2 -> l.sortedByDescending { it.tiempo }
        else -> l
    }

    private fun listaMarcadores() {
        val q = consulta.trim()
        if (q.isNotEmpty()) {
            val res = ordenar(marcadores.todos().filter { coincide(it.titulo, it.url) })
            if (res.isEmpty()) hoja.nota("Ningún marcador coincide.")
            for (m in res.take(200)) filaMarcador(m, true)
            return
        }
        if (carpeta != 0L) {
            val padre = marcadores.carpeta(carpeta)?.padre ?: 0L
            hoja.fila("Subir", marcadores.ruta(padre), null, false, "⬆") {
                carpeta = padre
                pintarLista()
            }
        }
        val filas = marcadores.filas(carpeta)
        var carpetas = filas.mapNotNull { it.carpeta }
        if (orden() == 1) carpetas = carpetas.sortedBy { it.nombre.lowercase() }
        val items = ordenar(filas.mapNotNull { it.marcador })
        if (carpetas.isEmpty() && items.isEmpty()) {
            hoja.nota(if (carpeta == 0L) "Aún no tienes marcadores. Pulsa ☆ en la barra para guardar una página." else "Carpeta vacía.")
        }
        for (c in carpetas) {
            val n = marcadores.elementosEn(c.id)
            hoja.filaLista(
                "📁", paleta.acento, c.nombre, if (n == 1) "1 elemento" else "$n elementos", null,
                {
                    carpeta = c.id
                    pintarLista()
                },
                { menuCarpeta(c) }, { menuCarpeta(c) }
            )
        }
        for (m in items) filaMarcador(m, false)
    }

    private fun filaMarcador(m: Marcador, conRuta: Boolean) {
        val host = ReglasUsuario.clave(Omnibox.hostDe(m.url))
        val nombre = if (m.titulo.isNotEmpty()) m.titulo else m.url
        val detalle = if (g.esLocal(m.url)) g.descripcionLocal(m.url)
        else if (conRuta && m.carpeta != 0L) host + " · " + marcadores.ruta(m.carpeta) else host
        hoja.filaLista(
            letraDe(if (host.isNotEmpty()) host else nombre), tonoDe(host.ifEmpty { nombre }), nombre, detalle,
            if (seleccion.isEmpty()) null else seleccion.contains(m.url),
            {
                if (seleccion.isNotEmpty()) {
                    alternarSeleccion(m.url)
                } else {
                    hoja.cerrar()
                    g.abrir(m.url)
                }
            },
            { alternarSeleccion(m.url) },
            { menuMarcador(m) }
        )
    }

    private fun menuMarcador(m: Marcador) {
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        l.add(Triple("Abrir en pestaña nueva", false, { cerrarYHacer { g.abrirNueva(m.url) } }))
        l.add(Triple("Editar…", false, {
            editor.editar("Editar marcador", m.titulo, m.url, m.carpeta) { t, u, c ->
                val ok = marcadores.actualizar(m, t, u, c)
                if (ok) {
                    g.refrescarUi()
                    pintarLista()
                } else {
                    g.avisar("Ya existe un marcador con esa dirección")
                }
                ok
            }
        }))
        l.add(Triple("Mover a…", false, {
            editor.elegirCarpeta("Mover a", m.carpeta, 0L) { destino ->
                marcadores.moverMarcador(m, destino)
                pintarLista()
            }
        }))
        if (!g.esLocal(m.url)) {
            l.add(Triple("Copiar dirección", false, { g.copiar(m.url) }))
            l.add(Triple("Compartir…", false, { g.compartir(m.url) }))
        }
        l.add(Triple("Eliminar", true, {
            g.quitarMarcador(m.url)
            g.refrescarUi()
            pintarLista()
        }))
        acciones(if (m.titulo.isNotEmpty()) m.titulo else m.url, l)
    }

    private fun menuCarpeta(c: CarpetaMarcadores) {
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        l.add(Triple("Abrir todos en pestañas", false, { abrirCarpetaEnPestanas(c) }))
        l.add(Triple("Renombrar…", false, {
            Dialogos.texto(act, paleta, privada, "Renombrar carpeta", c.nombre) { n ->
                marcadores.renombrarCarpeta(c, n)
                pintarLista()
            }
        }))
        l.add(Triple("Mover a…", false, {
            editor.elegirCarpeta("Mover carpeta a", c.padre, c.id) { destino ->
                if (marcadores.moverCarpeta(c, destino)) pintarLista() else g.avisar("No se puede mover ahí")
            }
        }))
        l.add(Triple("Eliminar carpeta", true, {
            val n = marcadores.elementosEn(c.id)
            val detalle = if (n > 0) "Sus $n elemento(s) pasarán a «" + marcadores.ruta(c.padre) + "»." else "Está vacía."
            confirmar("Eliminar carpeta", "Se eliminará «" + c.nombre + "». " + detalle, "Eliminar") {
                marcadores.borrarCarpeta(c)
                pintarLista()
            }
        }))
        acciones(c.nombre, l)
    }

    private fun urlsDeCarpeta(id: Long): List<String> {
        val r = ArrayList<String>()
        for (m in marcadores.todos()) if (m.carpeta == id && !g.esLocal(m.url)) r.add(m.url)
        for (f in marcadores.filas(id)) {
            val c = f.carpeta
            if (c != null) r.addAll(urlsDeCarpeta(c.id))
        }
        return r
    }

    private fun abrirCarpetaEnPestanas(c: CarpetaMarcadores) {
        val urls = urlsDeCarpeta(c.id)
        if (urls.isEmpty()) {
            g.avisar("La carpeta no tiene páginas web")
            return
        }
        abrirVarias(urls, "Abrir «" + c.nombre + "»")
    }

    /** Abre varias direcciones en pestañas nuevas; pide confirmación si son muchas. */
    private fun abrirVarias(urls: List<String>, titulo: String) {
        val hacer = {
            hoja.cerrar()
            for (u in urls) g.abrirNueva(u)
        }
        if (urls.size > 8) {
            confirmar(titulo, "Se abrirán " + urls.size + " pestañas nuevas. Muchas pestañas a la vez pueden ralentizar el móvil.", "Abrir", false) { hacer() }
        } else {
            hacer()
        }
    }

    private fun nuevaCarpeta() {
        Dialogos.texto(act, paleta, privada, "Nueva carpeta", "") { n ->
            if (marcadores.crearCarpeta(n, carpeta) == null) g.avisar("No se pudo crear la carpeta")
            pintarLista()
        }
    }

    private fun elegirOrden() {
        Dialogos.elegir(act, paleta, privada, "Ordenar marcadores", ORDENES, orden()) { i ->
            prefs.edit().putInt("orden", i).apply()
            pintarLista()
        }
    }

    // ---------------------------------------------------------------- selección

    private fun abrirSeleccion() {
        val urls = seleccion.filter { !g.esLocal(it) }
        if (urls.isEmpty()) {
            g.avisar("Nada que abrir en la selección")
            return
        }
        abrirVarias(urls, "Abrir selección")
    }

    private fun moverSeleccion() {
        editor.elegirCarpeta("Mover " + seleccion.size + " a", -1L, 0L) { destino ->
            for (url in seleccion.toList()) {
                val m = marcadores.buscar(url)
                if (m != null) marcadores.moverMarcador(m, destino)
            }
            seleccion.clear()
            pintarLista()
        }
    }

    private fun marcarSeleccion() {
        var n = 0
        for (url in seleccion.toList()) {
            if (marcadores.contiene(url)) continue
            val e = historial.todos().firstOrNull { it.url == url }
            if (marcadores.agregar(if (e != null && e.titulo.isNotEmpty()) e.titulo else url, url, 0L)) n++
        }
        g.avisar(if (n == 0) "Ya estaban en marcadores" else n.toString() + " añadidos a marcadores")
        g.refrescarUi()
        seleccion.clear()
        pintarLista()
    }

    private fun eliminarSeleccion() {
        val n = seleccion.size
        val deMarcadores = pestana == MARCADORES
        confirmar(
            "Eliminar " + n + (if (n == 1) " elemento" else " elementos"),
            if (deMarcadores) "Se quitarán de tus marcadores." else "Se quitarán del historial. Los marcadores se conservan.",
            "Eliminar"
        ) {
            for (url in seleccion.toList()) {
                if (deMarcadores) g.quitarMarcador(url) else historial.quitar(url)
            }
            seleccion.clear()
            g.refrescarUi()
            pintarLista()
        }
    }

    // ---------------------------------------------------------------- historial

    private fun etiquetaDia(t: Long): String {
        val hoy = java.util.Calendar.getInstance()
        val d = java.util.Calendar.getInstance()
        d.timeInMillis = t
        fun mismoDia(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (mismoDia(hoy, d)) return "Hoy"
        hoy.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (mismoDia(hoy, d)) return "Ayer"
        return java.text.SimpleDateFormat("EEEE, d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(t))
            .replaceFirstChar { c -> c.uppercase() }
    }

    private fun listaHistorial() {
        if (g.cerradas() > 0 && consulta.isEmpty()) {
            hoja.fila("Pestañas cerradas recientes", "Reabre las que cerraste hace poco", g.cerradas().toString(), icono = "↺") {
                cerrarYHacer { g.reabrir() }
            }
        }
        val todas = historial.todos().filter { coincide(it.titulo, it.url) }
        if (todas.isEmpty()) {
            hoja.nota(if (consulta.isEmpty()) "El historial está vacío." else "Nada coincide con la búsqueda.")
            return
        }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        var dia = ""
        for (e in todas.take(limite)) {
            val d = etiquetaDia(e.tiempo)
            if (d != dia) {
                hoja.seccion(d)
                dia = d
            }
            val host = ReglasUsuario.clave(Omnibox.hostDe(e.url))
            val nombre = if (e.titulo.isNotEmpty()) e.titulo else e.url
            hoja.filaLista(
                letraDe(if (host.isNotEmpty()) host else nombre), tonoDe(host.ifEmpty { nombre }), nombre,
                host + " · " + fmt.format(java.util.Date(e.tiempo)),
                if (seleccion.isEmpty()) null else seleccion.contains(e.url),
                {
                    if (seleccion.isNotEmpty()) {
                        alternarSeleccion(e.url)
                    } else {
                        hoja.cerrar()
                        g.abrir(e.url)
                    }
                },
                { alternarSeleccion(e.url) },
                { menuHistorial(e, host) }
            )
        }
        if (todas.size > limite) {
            hoja.boton("Mostrar más (" + (todas.size - limite) + ")") {
                limite += LOTE
                pintarLista()
            }
        }
        hoja.nota("Mantén pulsado para elegir varias páginas.")
    }

    private fun menuHistorial(e: Entrada, host: String) {
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        l.add(Triple("Abrir en pestaña nueva", false, { cerrarYHacer { g.abrirNueva(e.url) } }))
        l.add(Triple("Copiar dirección", false, { g.copiar(e.url) }))
        l.add(Triple("Compartir…", false, { g.compartir(e.url) }))
        if (!marcadores.contiene(e.url)) {
            l.add(Triple("Añadir a marcadores", false, {
                val ok = marcadores.agregar(if (e.titulo.isNotEmpty()) e.titulo else e.url, e.url, 0L)
                g.avisar(if (ok) "Marcador guardado" else "No se pudo guardar el marcador")
                g.refrescarUi()
            }))
        }
        l.add(Triple("Quitar del historial", true, {
            historial.quitar(e.url)
            pintarLista()
        }))
        if (host.isNotEmpty()) {
            val n = historial.todos().count { ReglasUsuario.clave(Omnibox.hostDe(it.url)) == host }
            if (n > 1) {
                l.add(Triple("Borrar todo lo de " + host + " (" + n + ")", true, {
                    confirmar("Borrar historial de " + host, "Se borrarán " + n + " páginas de este sitio del historial.", "Borrar") {
                        g.borrarHistorialDe(host)
                        pintarLista()
                    }
                }))
            }
        }
        acciones(if (e.titulo.isNotEmpty()) e.titulo else e.url, l)
    }

    // ---------------------------------------------------------------- páginas guardadas

    private fun listaPaginas() {
        val todas = PaginasGuardadas.todas().filter { coincide(it.titulo, it.url) }
        if (todas.isEmpty()) {
            hoja.nota(
                if (consulta.isEmpty()) "No hay páginas guardadas. Usa «Guardar para leer sin conexión» en el menú de una página."
                else "Nada coincide con la búsqueda."
            )
            return
        }
        val fecha = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
        for (pg in todas) {
            val host = ReglasUsuario.clave(Omnibox.hostDe(pg.url))
            hoja.filaLista(
                letraDe(host.ifEmpty { pg.titulo }), tonoDe(host.ifEmpty { pg.titulo }), pg.titulo,
                host + " · " + fecha.format(java.util.Date(pg.fecha)) + " · " + formatoTamano(pg.bytes), null,
                { cerrarYHacer { g.abrirSinConexion(pg) } },
                { menuPagina(pg) }, { menuPagina(pg) }
            )
        }
    }

    private fun menuPagina(pg: PaginaGuardada) {
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        l.add(Triple("Abrir sin conexión", false, { cerrarYHacer { g.abrirSinConexion(pg) } }))
        l.add(Triple("Abrir la dirección original", false, { cerrarYHacer { g.abrirNueva(pg.url) } }))
        l.add(Triple("Copiar dirección", false, { g.copiar(pg.url) }))
        l.add(Triple("Eliminar", true, {
            PaginasGuardadas.eliminar(pg.id)
            g.avisar("Página eliminada")
            pintarLista()
        }))
        acciones(pg.titulo, l)
    }

    private fun borrarTodasPaginas() {
        confirmar(
            "Borrar páginas guardadas",
            "Se borrarán las " + PaginasGuardadas.todas().size + " páginas guardadas (" + formatoTamano(PaginasGuardadas.totalBytes()) + ").",
            "Borrar"
        ) {
            PaginasGuardadas.eliminarTodas()
            g.avisar("Páginas guardadas borradas")
            pintarLista()
        }
    }

    // ---------------------------------------------------------------- aplicaciones web locales

    private fun listaApps() {
        val todas = AppLocal.registradas().filter { coincide(it.nombre + " " + it.espacio, it.copia) }
        if (todas.isEmpty()) {
            hoja.nota(
                if (consulta.isEmpty()) "Aquí aparecen los .html que abres como aplicación web (con sus propios datos). Ábrelos desde el gestor de archivos con «Abrir con Optimo» y elige abrirlos como aplicación."
                else "Nada coincide con la búsqueda."
            )
            return
        }
        val fecha = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
        for (a in todas) {
            val detalle = "espacio " + a.espacio + " · " + fecha.format(java.util.Date(a.ultima)) +
                (if (a.copia.isEmpty()) " · sin copia guardada" else "") +
                (if (g.datosConservados(a.espacio)) " · 🔒 datos conservados" else "")
            hoja.filaLista(
                letraDe(a.espacio), tonoDe(a.espacio), a.nombre, detalle, null,
                { abrirApp(a) }, { menuApp(a) }, { menuApp(a) }
            )
        }
        hoja.nota("Los archivos con el mismo espacio comparten sus datos. «Conservar sus datos» los salva de los borrados globales; si no, «Borrar todo» y «Borrar cookies y datos de todos los sitios» también los borran.")
    }

    private fun abrirApp(a: AppRegistrada) {
        if (a.copia.isEmpty()) {
            g.avisar("No hay copia guardada de este archivo: ábrelo de nuevo desde el gestor de archivos")
        } else {
            cerrarYHacer { g.abrir(a.copia) }
        }
    }

    private fun menuApp(a: AppRegistrada) {
        val conservados = g.datosConservados(a.espacio)
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        if (a.copia.isNotEmpty()) l.add(Triple("Abrir", false, { abrirApp(a) }))
        l.add(Triple(if (conservados) "No conservar sus datos" else "Conservar sus datos", false, {
            g.conservarDatos(a.espacio, !conservados)
            g.avisar(if (conservados) "Sus datos ya no están protegidos" else "Sus datos se conservan en los borrados globales")
            pintarLista()
        }))
        l.add(Triple("Borrar sus datos…", true, {
            confirmar(
                "Borrar los datos de " + a.nombre,
                "Se borrarán los datos que guarda esta aplicación en Optimo (proyectos, ajustes…) de todo el espacio «" + a.espacio + "». Si está abierta, recárgala.",
                "Borrar"
            ) {
                g.borrarDatosApp(a.espacio)
                g.avisar("Datos borrados")
                pintarLista()
            }
        }))
        l.add(Triple("Quitar de la lista…", true, {
            confirmar(
                "Quitar " + a.nombre,
                "Se quita de esta lista y se borra su copia guardada. Sus datos no se borran: si los quieres borrar, hazlo antes.",
                "Quitar"
            ) {
                g.quitarApp(a.espacio)
                pintarLista()
            }
        }))
        acciones(a.nombre, l)
    }

    // ---------------------------------------------------------------- listas de enlaces

    private fun listaListas() {
        val todas = ListasEnlaces.todas().filter { coincide(it.nombre, it.fuente) }
        if (todas.isEmpty()) {
            hoja.nota(
                if (consulta.isEmpty()) "No hay listas guardadas. Se crean desde «Buscar enlaces en la página»."
                else "Nada coincide con la búsqueda."
            )
            return
        }
        val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        for (lista in todas) {
            hoja.filaLista(
                letraDe(lista.nombre), tonoDe(lista.nombre), lista.nombre,
                lista.items.size.toString() + " enlaces · " + fmt.format(java.util.Date(lista.fecha)), null,
                { cerrarYHacer { g.verLista(lista) } },
                { menuLista(lista) }, { menuLista(lista) }
            )
        }
    }

    private fun menuLista(lista: ListaEnlaces) {
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        l.add(Triple("Ver la lista", false, { cerrarYHacer { g.verLista(lista) } }))
        if (lista.fuente.startsWith("http")) l.add(Triple("Abrir la página de origen", false, { cerrarYHacer { g.abrirNueva(lista.fuente) } }))
        if (lista.fuente.startsWith("http")) l.add(Triple("Rastrear de nuevo (marca lo nuevo)", false, { cerrarYHacer { g.rastrearDeNuevo(lista) } }))
        l.add(Triple("Copiar todos los enlaces", false, {
            g.copiar(lista.items.joinToString("\n") { it.url })
            g.avisar(lista.items.size.toString() + " enlaces copiados")
        }))
        l.add(Triple("Borrar esta lista", true, {
            ListasEnlaces.eliminar(lista.id)
            g.avisar("Lista borrada")
            pintarLista()
        }))
        acciones(lista.nombre.take(80), l)
    }

    private fun borrarTodasListas() {
        confirmar("Borrar listas", "Se borrarán las " + ListasEnlaces.todas().size + " listas de enlaces guardadas.", "Borrar") {
            ListasEnlaces.eliminarTodas()
            g.avisar("Listas borradas")
            pintarLista()
        }
    }
}
