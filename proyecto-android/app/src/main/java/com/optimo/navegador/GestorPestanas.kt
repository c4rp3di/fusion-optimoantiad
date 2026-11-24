package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout

/** Lo que el gestor de pestañas necesita de MainActivity. */
interface AnfitrionPestanas {
    fun pestanas(): List<Pestana>
    fun actual(): Pestana?
    fun titulo(p: Pestana): String
    fun sonando(): Pestana?
    fun activar(p: Pestana)
    fun cerrar(p: Pestana)
    fun silenciar(p: Pestana)

    /** Duerme la pestaña (libera su memoria); [alTerminar] recibe false si no se pudo (suena, formulario sin enviar…). */
    fun dormir(p: Pestana, alTerminar: (Boolean) -> Unit)
    fun nueva(privada: Boolean)
    fun cerradas(): Int
    fun reabrir()
    fun cerrarTodas()
    fun marcar(p: Pestana): Boolean

    /** Pregunta a las páginas si suenan y llama a [alCambiar] si algo cambió. */
    fun comprobarSonido(alCambiar: () -> Unit)

    /** Milisegundos desde que se usó la pestaña. */
    fun usoHace(p: Pestana): Long
    fun compartir(url: String)
    fun copiar(url: String)
    fun avisar(texto: String)
}

/**
 * Gestor de pestañas: lista agrupada por sitio o plana, o cuadrícula; filtros (sonando, dormidas, privadas, duplicadas),
 * búsqueda, selección múltiple (mantén pulsado) y acciones por pestaña. Pensado para decenas de pestañas.
 */
class GestorPestanas(
    private val act: Activity,
    private val paleta: Paleta,
    private val g: AnfitrionPestanas,
    private val privada: Boolean
) {
    companion object {
        private val FILTROS = listOf("Todas", "Sonando", "Dormidas", "Privadas", "Duplicadas")
    }

    private val hoja = Hoja(act, paleta, privada, true)
    private val prefs = act.getSharedPreferences("optimo_pestanas", android.content.Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private var filtro = 0
    private var consulta = ""
    private val seleccion = LinkedHashSet<Int>()
    private val plegados = HashSet<String>()
    private lateinit var zonaLista: LinearLayout
    private lateinit var zonaSel: LinearLayout

    private var agrupar: Boolean
        get() = prefs.getBoolean("agrupar", true)
        set(v) {
            prefs.edit().putBoolean("agrupar", v).apply()
        }
    private var cuadricula: Boolean
        get() = prefs.getBoolean("cuadricula", false)
        set(v) {
            prefs.edit().putBoolean("cuadricula", v).apply()
        }

    fun mostrar() {
        pintarTodo()
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
        // Qué pestañas suenan: se pregunta a las páginas y se refresca la lista si algo cambió.
        g.comprobarSonido { ui.post { pintarLista() } }
    }

    private fun repintarTodo() {
        ui.post { pintarTodo() }
    }

    private fun confirmar(titulo: String, mensaje: String, boton: String, aviso: Boolean = true, accion: () -> Unit) {
        Dialogos.confirmar(act, paleta, privada, titulo, mensaje, boton, aviso, accion)
    }

    // ---------------------------------------------------------------- datos

    private fun host(p: Pestana): String = when {
        p.inicio -> "Optimo"
        p.url.startsWith("http") -> ReglasUsuario.clave(Omnibox.hostDe(p.url))
        else -> ""
    }

    private fun claveDuplicada(p: Pestana): String? {
        if (!p.url.startsWith("http")) return null
        return p.url.substringBefore('#').trimEnd('/')
    }

    /** Pestañas repetidas (misma dirección sin «#» ni barra final) que sobran: se conserva la activa o, si no, la primera. */
    private fun sobrantes(): List<Pestana> {
        val grupos = LinkedHashMap<String, MutableList<Pestana>>()
        for (p in g.pestanas()) {
            val k = claveDuplicada(p) ?: continue
            grupos.getOrPut(k + "|" + p.privada) { ArrayList() }.add(p)
        }
        val r = ArrayList<Pestana>()
        for (l in grupos.values) {
            if (l.size < 2) continue
            val quedarse = l.firstOrNull { it === g.actual() } ?: l[0]
            for (p in l) if (p !== quedarse) r.add(p)
        }
        return r
    }

    private fun esDuplicada(p: Pestana): Boolean {
        val k = claveDuplicada(p) ?: return false
        return g.pestanas().count { claveDuplicada(it) == k && it.privada == p.privada } > 1
    }

    private fun cumpleFiltro(p: Pestana): Boolean = when (filtro) {
        1 -> p === g.sonando() || p.suena
        2 -> p.suspendida
        3 -> p.privada
        4 -> esDuplicada(p)
        else -> true
    }

    private fun filtradas(): List<Pestana> {
        val q = consulta.trim().lowercase()
        return g.pestanas().filter { p ->
            cumpleFiltro(p) && (q.isEmpty() || g.titulo(p).lowercase().contains(q) || p.url.lowercase().contains(q))
        }
    }

    private fun hace(ms: Long): String {
        val min = ms / 60000L
        return when {
            min < 1 -> "ahora"
            min < 60 -> "hace " + min + " min"
            min < 1440 -> "hace " + (min / 60) + " h"
            else -> "hace " + (min / 1440) + " d"
        }
    }

    private fun detalle(p: Pestana): String {
        val partes = ArrayList<String>()
        val h = host(p)
        partes.add(if (p.privada) "privada" + (if (h.isNotEmpty()) " · " + h else "") else if (h.isNotEmpty()) h else "sin dirección")
        partes.add(if (p === g.actual()) "activa" else hace(g.usoHace(p)))
        if (p === g.sonando() || p.suena) partes.add(if (p.silenciada) "🔇 silenciada" else "🔊 suena")
        if (p.suspendida) partes.add("💤 dormida")
        return partes.joinToString(" · ")
    }

    // ---------------------------------------------------------------- estructura

    private fun pintarTodo() {
        hoja.limpiar()
        val todas = g.pestanas()
        val privadas = todas.count { it.privada }
        val cabecera = LinearLayout(act)
        cabecera.orientation = LinearLayout.HORIZONTAL
        cabecera.gravity = android.view.Gravity.CENTER_VERTICAL
        cabecera.setPadding(0, 0, 0, 0)
        val titulo = android.widget.TextView(act)
        titulo.text = "Pestañas · " + todas.size + (if (privadas > 0) " (" + privadas + " privadas)" else "")
        titulo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f)
        titulo.setTextColor(paleta.texto)
        titulo.typeface = android.graphics.Typeface.DEFAULT_BOLD
        titulo.setPadding(dpi(4), 0, 0, 0)
        cabecera.addView(titulo, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cabecera.addView(hoja.botonIcono(if (cuadricula) "☰" else "▦") {
            cuadricula = !cuadricula
            pintarLista()
            repintarTodo()
        }, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        cabecera.addView(hoja.botonIcono("✕") { hoja.cerrar() }, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        hoja.agregar(cabecera)

        hoja.barraAcciones(
            "",
            listOf(
                Triple("＋ Nueva", false, { cerrarYHacer { g.nueva(false) } }),
                Triple("🕶 Privada", false, { cerrarYHacer { g.nueva(true) } }),
                Triple("↩ Cerradas " + g.cerradas(), false, { cerrarYHacer { g.reabrir() } }),
                Triple("⋯", false, { menuGeneral() })
            )
        )
        hoja.campo("Buscar por título o dirección", consulta) { t ->
            consulta = t
            pintarLista()
        }
        val etiquetas = ArrayList<String>()
        for ((i, f) in FILTROS.withIndex()) {
            val n = when (i) {
                1 -> todas.count { it === g.sonando() || it.suena }
                2 -> todas.count { it.suspendida }
                3 -> privadas
                4 -> sobrantes().size
                else -> todas.size
            }
            etiquetas.add(if (i == 0 || n > 0) f + " " + n else f)
        }
        hoja.chips(etiquetas, filtro) { i ->
            filtro = i
            seleccion.clear()
            pintarTodo()
        }
        hoja.chips(listOf("Por sitio", "Sin agrupar", if (seleccion.isNotEmpty()) "✓ Elegir" else "Elegir"), if (agrupar) 0 else 1) { i ->
            when (i) {
                0 -> agrupar = true
                1 -> agrupar = false
                else -> {
                    if (seleccion.isEmpty()) {
                        val a = g.actual()
                        if (a != null) seleccion.add(a.id)
                    } else {
                        seleccion.clear()
                    }
                }
            }
            pintarTodo()
        }
        zonaSel = hoja.zona()
        zonaLista = hoja.zona()
        pintarLista()
    }

    private fun dpi(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    private fun cerrarYHacer(accion: () -> Unit) {
        hoja.cerrar()
        accion()
    }

    private fun alternarSeleccion(p: Pestana) {
        if (!seleccion.remove(p.id)) seleccion.add(p.id)
        pintarLista()
    }

    private fun pintarLista() {
        hoja.llenar(zonaSel) { pintarSeleccion() }
        hoja.llenar(zonaLista) {
            val lista = filtradas()
            if (lista.isEmpty()) {
                hoja.nota(if (consulta.isEmpty() && filtro == 0) "No hay pestañas." else "Ninguna pestaña coincide.")
                return@llenar
            }
            if (cuadricula) {
                hoja.cuadricula(lista.map { ficha(it) })
            } else if (agrupar) {
                val grupos = LinkedHashMap<String, MutableList<Pestana>>()
                for (p in lista) {
                    val h = if (p.privada) "Privadas" else if (host(p).isNotEmpty()) host(p) else "Sin dirección"
                    grupos.getOrPut(h) { ArrayList() }.add(p)
                }
                for ((h, ps) in grupos) cabeceraGrupo(h, ps)
            } else {
                for (p in lista) fila(p)
            }
        }
    }

    // ---------------------------------------------------------------- filas

    private fun letra(p: Pestana): String {
        val h = host(p)
        val t = if (h.isNotEmpty()) h else g.titulo(p)
        return if (t.isEmpty()) "•" else t.substring(0, 1).uppercase()
    }

    private fun tono(p: Pestana): Int = tonoDe(host(p).ifEmpty { g.titulo(p) })

    private fun fila(p: Pestana) {
        hoja.filaLista(
            letra(p), tono(p), g.titulo(p), detalle(p),
            if (seleccion.isEmpty()) null else seleccion.contains(p.id),
            {
                if (seleccion.isNotEmpty()) {
                    alternarSeleccion(p)
                } else {
                    hoja.cerrar()
                    g.activar(p)
                }
            },
            { alternarSeleccion(p) },
            { menuPestana(p) },
            if (seleccion.isEmpty()) {
                {
                    g.cerrar(p)
                    repintarTodo()
                }
            } else {
                null
            }
        )
    }

    private fun ficha(p: Pestana): Hoja.Ficha = Hoja.Ficha(
        letra(p), tono(p), g.titulo(p), detalle(p),
        if (seleccion.isEmpty()) null else seleccion.contains(p.id), p === g.actual(),
        {
            if (seleccion.isNotEmpty()) {
                alternarSeleccion(p)
            } else {
                hoja.cerrar()
                g.activar(p)
            }
        },
        { alternarSeleccion(p) },
        if (seleccion.isEmpty()) {
            {
                g.cerrar(p)
                repintarTodo()
            }
        } else {
            null
        }
    )

    /** Cabecera de un grupo de sitio con sus pestañas debajo (plegable); «Cerrar grupo» cierra todas. */
    private fun cabeceraGrupo(h: String, ps: List<Pestana>) {
        val plegado = plegados.contains(h)
        hoja.barraAcciones(
            (if (plegado) "▸ " else "▾ ") + h + " · " + ps.size,
            listOf(
                Triple(if (plegado) "Ver" else "Plegar", false, {
                    if (!plegados.remove(h)) plegados.add(h)
                    pintarLista()
                }),
                Triple("Cerrar grupo", true, { cerrarGrupo(h, ps) })
            )
        )
        if (!plegado) for (p in ps) fila(p)
    }

    private fun cerrarGrupo(h: String, ps: List<Pestana>) {
        val hacer = {
            for (p in ps.toList()) g.cerrar(p)
            g.avisar(ps.size.toString() + (if (ps.size == 1) " pestaña cerrada" else " pestañas cerradas"))
            repintarTodo()
        }
        if (ps.size > 1) confirmar("Cerrar " + h, "Se cerrarán " + ps.size + " pestañas.", "Cerrar") { hacer() } else hacer()
    }

    // ---------------------------------------------------------------- selección y menús

    private fun elegidas(): List<Pestana> = g.pestanas().filter { seleccion.contains(it.id) }

    private fun pintarSeleccion() {
        if (seleccion.isEmpty()) {
            val sob = sobrantes().size
            if (sob > 0) {
                hoja.barraAcciones(
                    sob.toString() + (if (sob == 1) " pestaña repetida" else " pestañas repetidas"),
                    listOf(Triple("Cerrar repetidas", true, { cerrarRepetidas() }))
                )
            }
            return
        }
        hoja.barraAcciones(
            seleccion.size.toString() + (if (seleccion.size == 1) " elegida" else " elegidas"),
            listOf(
                Triple("Todas", false, {
                    for (p in filtradas()) seleccion.add(p.id)
                    pintarLista()
                }),
                Triple("＋ Marcadores", false, { marcarElegidas() }),
                Triple("Dormir", false, { dormirElegidas() }),
                Triple("Cerrar", true, { cerrarElegidas() }),
                Triple("✕", false, {
                    seleccion.clear()
                    repintarTodo()
                })
            )
        )
    }

    private fun cerrarElegidas() {
        val l = elegidas()
        confirmar("Cerrar " + l.size, "Se cerrarán " + l.size + (if (l.size == 1) " pestaña." else " pestañas."), "Cerrar") {
            for (p in l) g.cerrar(p)
            seleccion.clear()
            g.avisar(l.size.toString() + (if (l.size == 1) " pestaña cerrada" else " pestañas cerradas"))
            repintarTodo()
        }
    }

    private fun marcarElegidas() {
        var n = 0
        for (p in elegidas()) if (g.marcar(p)) n++
        g.avisar(if (n == 0) "No se añadió ninguna (ya estaban o no son webs)" else n.toString() + " añadidas a marcadores")
        seleccion.clear()
        repintarTodo()
    }

    private fun dormirElegidas() {
        val l = elegidas()
        var pendientes = l.size
        var hechas = 0
        if (pendientes == 0) return
        for (p in l) {
            g.dormir(p) { ok ->
                if (ok) hechas++
                pendientes--
                if (pendientes == 0) {
                    g.avisar(if (hechas == 0) "No se pudo dormir ninguna (activa, suena o tiene texto sin enviar)" else hechas.toString() + " dormidas")
                    seleccion.clear()
                    repintarTodo()
                }
            }
        }
    }

    private fun cerrarRepetidas() {
        val l = sobrantes()
        confirmar("Cerrar repetidas", "Se cerrarán " + l.size + " pestañas con la misma dirección que otra. Se conserva una de cada.", "Cerrar") {
            for (p in l) g.cerrar(p)
            g.avisar(l.size.toString() + " cerradas")
            repintarTodo()
        }
    }

    private fun menuGeneral() {
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        val sob = sobrantes().size
        if (sob > 0) l.add(Triple("Cerrar repetidas ($sob)", false, { cerrarRepetidas() }))
        val dormidas = g.pestanas().filter { it.suspendida }
        if (dormidas.isNotEmpty()) {
            l.add(Triple("Cerrar las dormidas (" + dormidas.size + ")", true, {
                confirmar("Cerrar dormidas", "Se cerrarán " + dormidas.size + " pestañas dormidas.", "Cerrar") {
                    for (p in dormidas) g.cerrar(p)
                    repintarTodo()
                }
            }))
        }
        l.add(Triple("Cerrar todas las pestañas", true, {
            confirmar("Cerrar todas", "Se cerrarán las " + g.pestanas().size + " pestañas y se abrirá una nueva.", "Cerrar todas") {
                hoja.cerrar()
                g.cerrarTodas()
            }
        }))
        Dialogos.acciones(act, paleta, privada, "Pestañas", l)
    }

    private fun menuPestana(p: Pestana) {
        val l = ArrayList<Triple<String, Boolean, () -> Unit>>()
        l.add(Triple("Abrir", false, { cerrarYHacer { g.activar(p) } }))
        if (p === g.sonando() || p.suena) {
            l.add(Triple(if (p.silenciada) "Activar el sonido" else "Silenciar", false, {
                g.silenciar(p)
                pintarLista()
            }))
        }
        if (p.url.startsWith("http")) {
            l.add(Triple("＋ Añadir a marcadores", false, {
                g.avisar(if (g.marcar(p)) "Marcador guardado" else "Ya estaba en marcadores")
            }))
            l.add(Triple("Copiar dirección", false, { g.copiar(p.url) }))
            l.add(Triple("Compartir…", false, { g.compartir(p.url) }))
        }
        if (!p.suspendida && p !== g.actual()) {
            l.add(Triple("Dormir (liberar memoria)", false, {
                g.dormir(p) { ok ->
                    g.avisar(if (ok) "Pestaña dormida" else "No se puede dormir ahora (suena o tiene texto sin enviar)")
                    repintarTodo()
                }
            }))
        }
        val h = host(p)
        if (h.isNotEmpty() && !p.privada && !p.inicio) {
            val mismas = g.pestanas().filter { it !== p && !it.privada && host(it) == h }
            if (mismas.isNotEmpty()) {
                l.add(Triple("Cerrar las demás de " + h + " (" + mismas.size + ")", true, {
                    confirmar("Cerrar las demás de " + h, "Se cerrarán " + mismas.size + " pestañas.", "Cerrar") {
                        for (q in mismas) g.cerrar(q)
                        repintarTodo()
                    }
                }))
            }
        }
        l.add(Triple("Cerrar esta pestaña", true, {
            g.cerrar(p)
            repintarTodo()
        }))
        Dialogos.acciones(act, paleta, privada, g.titulo(p), l)
    }
}
