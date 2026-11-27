package com.optimo.navegador

import android.app.Activity
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.LinearLayout
import kotlin.math.abs

/** Lo que la interfaz del rastreador necesita de MainActivity. */
interface AnfitrionRastreador {
    fun avisar(texto: String)
    fun avisarConAccion(texto: String, boton: String, accion: () -> Unit)
    fun copiar(texto: String)
    fun compartir(texto: String)
    fun nuevaPestana(url: String, privada: Boolean)
    fun opciones(titulo: String, items: List<Pair<String, () -> Unit>>)
    fun pedirTexto(titulo: String, inicial: String, alAceptar: (String) -> Unit)
    fun exportar(contenido: String, ext: String, mime: String, titulo: String)
    fun hayAppPara(e: EnlaceEncontrado): Boolean
    fun abrirConApp(e: EnlaceEncontrado, privada: Boolean)
    fun enPantalla(): Boolean
}

/**
 * Interfaz del rastreador de enlaces con las piezas de siempre de Optimo ([Hoja]):
 * configurar (panel lateral), progreso (hoja inferior que se pliega a una tira y deja usar la página), resultados (panel lateral)
 * y acciones sobre lo marcado (hoja inferior). El motor está en [Rastreador]; esto solo lo maneja y lo muestra.
 */
class RastreadorUI(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionRastreador
) {
    private companion object {
        val PAGINAS = intArrayOf(10, 25, 50, 100, 200, 300, 500, 1000, 2000)
        val MINUTOS = intArrayOf(1, 2, 5, 10, 20, 30, 60, 120)
        val PAUSAS = intArrayOf(0, 100, 300, 600, 1000, 2000, 5000, 10000)
        const val LOTE = 150
    }

    private var rastreador: Rastreador? = null
    private var cfgActual: ConfigRastreo? = null
    private var privadaRastreo = false
    private var previosRastreo: Set<String>? = null
    private var ultimoProgreso: ProgresoRastreo? = null
    private var hojaProgreso: Hoja? = null
    private var zonaProgreso: LinearLayout? = null

    /** Último rastreo terminado (para «Ver el último rastreo» del menú). */
    var ultimo: ResultadoRastreo? = null
        private set

    val enMarcha: Boolean get() = rastreador != null

    /** Enlaces del rastreo en curso o del último terminado; -1 si no hay ninguno. */
    fun enlacesAhora(): Int = if (rastreador != null) (ultimoProgreso?.enlaces ?: 0) else (ultimo?.enlaces?.size ?: -1)

    fun parar(motivo: String) {
        rastreador?.parar(motivo)
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- configurar

    private fun prefijoRuta(url: String): String {
        val ruta = try { Uri.parse(url).path ?: "/" } catch (e: Exception) { "/" }
        val i = ruta.lastIndexOf('/')
        return if (i <= 0) "/" else ruta.substring(0, i + 1)
    }

    private fun mas(a: IntArray, v: Int, d: Int): Int {
        var m = 0
        for (i in a.indices) if (abs(a[i] - v) < abs(a[m] - v)) m = i
        return a[(m + d).coerceIn(0, a.size - 1)]
    }

    private fun textoPausa(v: Int): String = if (v >= 1000) (v / 1000).toString() + " s" else v.toString() + " ms"

    /**
     * Panel de ajustes del rastreo desde [inicio]. [previos]: enlaces que ya se conocían (al rastrear de nuevo una lista), para marcar
     * los nuevos en los resultados.
     */
    fun configurar(inicio: String, privada: Boolean, previos: Set<String>? = null) {
        if (!inicio.startsWith("http")) {
            g.avisar("Abre una página web para rastrearla")
            return
        }
        val h = Hoja(act, paleta, privada, true)
        var avanzado = false
        val tipos = TipoEnlace.values().filter { it != TipoEnlace.PROPIO }
        val pref = prefijoRuta(inicio)
        val sitio = inicio.removePrefix("https://").removePrefix("http://").take(60)

        fun pintar() {
            h.limpiar()
            if (avanzado) {
                h.barraTitulo("Ajustes avanzados", { avanzado = false; pintar() }) { h.cerrar() }
                h.nota("Topes del rastreo. Frena en cuanto se alcanza el primero.")
                h.paso(
                    "Profundidad (saltos)", if (ajustes.rastroProf == 0) "∞" else ajustes.rastroProf.toString(),
                    ajustes.rastroProf > 0, ajustes.rastroProf < 50,
                    { ajustes.rastroProf = ajustes.rastroProf - 1; pintar() }, { ajustes.rastroProf = ajustes.rastroProf + 1; pintar() }
                )
                h.paso(
                    "Máximo de páginas", ajustes.rastroPaginas.toString(),
                    ajustes.rastroPaginas > PAGINAS[0], ajustes.rastroPaginas < PAGINAS[PAGINAS.size - 1],
                    { ajustes.rastroPaginas = mas(PAGINAS, ajustes.rastroPaginas, -1); pintar() },
                    { ajustes.rastroPaginas = mas(PAGINAS, ajustes.rastroPaginas, 1); pintar() }
                )
                h.paso(
                    "Tiempo máximo", ajustes.rastroMin.toString() + " min",
                    ajustes.rastroMin > MINUTOS[0], ajustes.rastroMin < MINUTOS[MINUTOS.size - 1],
                    { ajustes.rastroMin = mas(MINUTOS, ajustes.rastroMin, -1); pintar() },
                    { ajustes.rastroMin = mas(MINUTOS, ajustes.rastroMin, 1); pintar() }
                )
                h.paso(
                    "Pausa entre páginas", textoPausa(ajustes.rastroPausa),
                    ajustes.rastroPausa > PAUSAS[0], ajustes.rastroPausa < PAUSAS[PAUSAS.size - 1],
                    { ajustes.rastroPausa = mas(PAUSAS, ajustes.rastroPausa, -1); pintar() },
                    { ajustes.rastroPausa = mas(PAUSAS, ajustes.rastroPausa, 1); pintar() }
                )
                h.nota("Profundidad ∞ = sin límite: solo frenan los demás topes. Una pausa mayor es más amable con el sitio.")
                return
            }
            h.barraTitulo("Rastrear este sitio", null) { h.cerrar() }
            h.nota(sitio)
            h.rotulo("Hasta dónde llegar")
            h.segmentos(listOf("Esta página", "Esta sección", "Todo el sitio"), ajustes.rastroAlcance) { ajustes.rastroAlcance = it; pintar() }
            h.nota(
                when {
                    ajustes.rastroAlcance == 0 -> "Solo lee esta página y lista sus enlaces; no sigue ninguno."
                    ajustes.rastroAlcance == 1 && pref != "/" -> "Sigue las páginas de «" + pref + "…»: esta carpeta y sus subcarpetas."
                    else -> "Sigue las páginas de todo el sitio."
                }
            )
            h.rotulo("Qué buscar")
            val marcados = BooleanArray(tipos.size) { (ajustes.enlacesTipos shr tipos[it].ordinal) and 1 != 0 }
            h.chipsMultiples(tipos.map { it.etiqueta }, marcados) { i, v ->
                val bit = 1 shl tipos[i].ordinal
                ajustes.enlacesTipos = if (v) ajustes.enlacesTipos or bit else ajustes.enlacesTipos and bit.inv()
                pintar()
            }
            h.fila("Filtro propio", "Extensiones, texto o /regex/, separados por comas", ajustes.enlacesPropio.ifBlank { "ninguno" }.take(18)) {
                g.pedirTexto("Filtro propio (extensiones, texto o /regex/, separados por comas)", ajustes.enlacesPropio) { t ->
                    ajustes.enlacesPropio = t
                    pintar()
                }
            }
            val paginas = if (ajustes.rastroAlcance == 0) 1 else ajustes.rastroPaginas
            val minutos = maxOf(1L, minOf(ajustes.rastroMin.toLong(), (paginas * (ajustes.rastroPausa + 700L) + 59999L) / 60000L))
            h.cifras(
                listOf(
                    (if (paginas == 1) "1" else "≤ " + paginas) to "páginas",
                    "≤ " + minutos + " min" to "tiempo"
                )
            )
            h.nota("Son topes, no una predicción: termina antes si el sitio tiene menos páginas.")
            h.interruptor("Respetar robots.txt", "No lee lo que el sitio pide que no se lea", ajustes.rastroRobots) { ajustes.rastroRobots = it }
            if (ajustes.rastroAlcance != 0) {
                h.interruptor("Seguir a otros sitios", "Además del sitio de partida", ajustes.rastroOtros) { ajustes.rastroOtros = it }
            }
            h.interruptor(
                "Usar mi sesión (cookies)", if (privada) "No en pestañas privadas" else "Para sitios donde has iniciado sesión",
                ajustes.rastroCookies && !privada
            ) {
                if (privada) {
                    pintar()
                } else {
                    ajustes.rastroCookies = it
                }
            }
            h.fila("Ajustes avanzados", "Profundidad, páginas, minutos y pausa") { avanzado = true; pintar() }
            h.boton("▶ Empezar a rastrear") {
                val sinTipos = tipos.none { (ajustes.enlacesTipos shr it.ordinal) and 1 != 0 }
                if (sinTipos && ajustes.enlacesPropio.isBlank()) {
                    g.avisar("Elige al menos un tipo de enlace")
                } else {
                    h.cerrar()
                    iniciar(inicio, privada, previos)
                }
            }
            h.nota("Lee el HTML de las páginas (sin JavaScript). Solo con la app abierta. No visita dominios bloqueados por el bloqueador.")
        }
        h.interceptarAtras {
            if (avanzado) {
                avanzado = false
                pintar()
                true
            } else {
                false
            }
        }
        pintar()
        h.mostrar()
    }

    // ---------------------------------------------------------------- en marcha

    private fun iniciar(inicio: String, privada: Boolean, previos: Set<String>?) {
        if (rastreador != null) {
            g.avisar("Ya hay un rastreo en marcha")
            return
        }
        val alcance = ajustes.rastroAlcance
        val propio = ajustes.enlacesPropio.let { if (it.isBlank()) null else FiltroPropio(it) }
        val cfg = ConfigRastreo(
            inicio, ajustes.rastroProf, if (alcance == 0) 1 else ajustes.rastroPaginas, ajustes.rastroMin * 60000L,
            ajustes.rastroPausa.toLong(), ajustes.rastroOtros && alcance != 0, ajustes.rastroCookies && !privada, ajustes.rastroRobots,
            WebSettings.getDefaultUserAgent(act), ajustes.bloquearAnuncios, ajustes.enlacesTipos, propio,
            if (alcance == 1) prefijoRuta(inicio).let { if (it == "/") "" else it } else "",
            alcance == 0
        )
        cfgActual = cfg
        privadaRastreo = privada
        previosRastreo = previos
        ultimoProgreso = null
        val r = Rastreador(cfg, { pr -> alProgreso(pr) }, { res -> terminar(res) })
        rastreador = r
        verProgreso()
        r.iniciar()
    }

    /** Abre (o vuelve a abrir) la hoja de progreso del rastreo en curso. */
    fun verProgreso() {
        if (rastreador == null) return
        val previa = hojaProgreso
        if (previa != null) {
            previa.cerrar()
            hojaProgreso = null
        }
        val h = Hoja(act, paleta, privadaRastreo, false)
        h.atenuar(0f)
        h.sinBloquearDetras()
        h.fijarCancelable(false)
        zonaProgreso = h.zona()
        hojaProgreso = h
        h.interceptarAtras {
            if (!h.plegarAhora()) {
                // Ya plegada: «atrás» la quita, pero el rastreo sigue y se puede volver a verlo desde el menú.
                h.cerrar()
                g.avisarConAccion("El rastreo sigue en marcha", "Ver") { verProgreso() }
            }
            true
        }
        h.alCerrar { if (hojaProgreso === h) hojaProgreso = null }
        h.mostrar()
        pintarProgreso()
    }

    private fun alProgreso(pr: ProgresoRastreo) {
        ultimoProgreso = pr
        pintarProgreso()
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun barra(frac: Float): View {
        val fila = LinearLayout(act)
        fila.orientation = LinearLayout.HORIZONTAL
        val p = frac.coerceIn(0f, 1f)
        val a = View(act)
        a.setBackgroundColor(paleta.acento)
        val b = View(act)
        b.setBackgroundColor(paleta.campo)
        fila.addView(a, LinearLayout.LayoutParams(0, dp(6), p))
        fila.addView(b, LinearLayout.LayoutParams(0, dp(6), 1f - p))
        return fila
    }

    private fun pintarProgreso() {
        val h = hojaProgreso ?: return
        val z = zonaProgreso ?: return
        val r = rastreador ?: return
        val pr = ultimoProgreso
        val pausado = r.enPausa
        h.llenar(z) {
            h.encabezado((if (pausado) "Rastreo en pausa" else "Rastreando") + " · " + mmss(pr?.transcurridoMs ?: 0L))
            if (pr == null) {
                h.nota("Empezando…")
                h.agregar(barra(0f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                h.nota(pr.actual.removePrefix("https://").removePrefix("http://").take(70))
                val cfg = cfgActual
                val total = if (cfg == null) pr.paginas + pr.enCola else minOf(cfg.maxPaginas, pr.paginas + pr.enCola)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                lp.bottomMargin = dp(4)
                h.agregar(barra(pr.paginas.toFloat() / total.coerceAtLeast(1)), lp)
                h.cifras(listOf(pr.enlaces.toString() to "Enlaces", pr.paginas.toString() to "Páginas", pr.enCola.toString() to "En cola"))
                val porTipo = TipoEnlace.values()
                    .filter { pr.porTipo.getOrElse(it.ordinal) { 0 } > 0 }
                    .sortedByDescending { pr.porTipo[it.ordinal] }
                    .take(5)
                    .map { it.etiqueta + " " + pr.porTipo[it.ordinal] }
                h.insignias(porTipo)
                if (pr.repetidos > 0) h.nota(pr.repetidos.toString() + " repetidos no se cuentan dos veces")
                for (e in pr.recientes.reversed()) {
                    h.filaLista(e.tipo.etiqueta.take(1), tonoDe(e.tipo.etiqueta), e.nombre, lineaEnlace(e), null, {}, null, null)
                }
            }
            h.barraAcciones(
                "",
                listOf(
                    Triple(if (pausado) "Reanudar" else "Pausar", false, {
                        if (pausado) r.reanudar() else r.pausar()
                        pintarProgreso()
                    }),
                    Triple("Descartar", true, { r.parar("descartado") }),
                    Triple("Ver resultados", false, { r.parar("detenido") })
                )
            )
        }
    }

    private fun terminar(res: ResultadoRastreo) {
        if (act.isDestroyed) return
        rastreador = null
        ultimoProgreso = null
        val h = hojaProgreso
        val interrumpe = h != null && !h.estaPlegada
        hojaProgreso = null
        if (h != null) {
            try {
                h.cerrar()
            } catch (e: Exception) {
                // Ya cerrada.
            }
        }
        if (res.motivo == "descartado") {
            g.avisar("Rastreo descartado")
            return
        }
        ultimo = res
        if (!g.enPantalla()) return
        if (interrumpe) {
            mostrarResultado(res)
        } else {
            g.avisarConAccion("Rastreo terminado: " + res.enlaces.size + " enlaces", "Ver") { mostrarResultado(res) }
        }
    }

    /** Vuelve a mostrar los resultados del último rastreo. */
    fun verUltimo() {
        val r = ultimo
        if (r != null) mostrarResultado(r)
    }

    private fun mostrarResultado(res: ResultadoRastreo) {
        val resumen = res.paginas.toString() + " páginas leídas (" + res.motivo + ")"
        if (res.enlaces.isEmpty()) {
            g.avisar("Sin enlaces de los tipos elegidos. " + resumen)
            return
        }
        g.avisar(resumen)
        mostrarEnlaces(
            privadaRastreo, res.titulo.ifBlank { Omnibox.hostDe(res.inicio) }, res.inicio, res.enlaces,
            nueva = true, deRastreo = true, previos = previosRastreo
        )
    }

    // ---------------------------------------------------------------- resultados

    /** ¿Se muestra este enlace con los ajustes actuales? Magnets, Mega y nubes se ven siempre; los archivos de otros sitios solo si se activó. */
    private fun enlaceVisible(e: EnlaceEncontrado): Boolean {
        if (!e.externo) return true
        return when (e.tipo) {
            TipoEnlace.MAGNET, TipoEnlace.MEGA, TipoEnlace.NUBE -> true
            else -> ajustes.enlacesOtros
        }
    }

    private fun lineaEnlace(e: EnlaceEncontrado): String {
        val sb = StringBuilder(e.tipo.etiqueta)
        if (e.host.isNotEmpty() && e.tipo != TipoEnlace.MAGNET) sb.append(" · ").append(e.host)
        if (e.tamano >= 0) sb.append(" · ").append(formatoTamano(e.tamano))
        if (e.origen.isNotEmpty()) {
            val u = Uri.parse(e.origen)
            sb.append(" · en ").append(((u.host ?: "") + (u.path ?: "")).trimEnd('/').take(45))
        }
        if (!Enlaces.nombreFiable(e.origenNombre) && e.origenNombre != "dirección") sb.append(" · nombre: ").append(e.origenNombre)
        return sb.toString()
    }

    private fun hostCorto(e: EnlaceEncontrado): String = if (e.host.isEmpty()) "Sin sitio" else e.host

    private fun paginaDe(e: EnlaceEncontrado): String {
        if (e.origen.isEmpty()) return "Esta página"
        val u = Uri.parse(e.origen)
        return ((u.host ?: "") + (u.path ?: "")).trimEnd('/').take(60).ifEmpty { e.origen.take(60) }
    }

    /**
     * Lista de enlaces en el panel lateral: búsqueda, filtro por tipo, agrupar (tipo, página o sitio), pulsar una fila abre su menú y
     * una pulsación larga pasa a marcar varias, con la hoja de acciones abajo. [nueva]: resultado recién obtenido (no una lista guardada).
     * [alCambiarTipos] y [alRepetirBusqueda] solo para la búsqueda en la página abierta.
     */
    fun mostrarEnlaces(
        privada: Boolean,
        fuenteTitulo: String,
        fuenteUrl: String,
        todos: List<EnlaceEncontrado>,
        nueva: Boolean,
        deRastreo: Boolean,
        previos: Set<String>? = null,
        alCambiarTipos: (() -> Unit)? = null,
        alRepetirBusqueda: (() -> Unit)? = null
    ) {
        val h = Hoja(act, paleta, privada, true)
        val quitados = HashSet<String>()
        val marcados = HashSet<String>()
        var seleccion = false
        var consulta = ""
        var tipoSel: TipoEnlace? = null
        var agrupar = 0
        var limite = LOTE
        var hojaAcc: Hoja? = null
        var refrescarTodo: () -> Unit = {}
        val modos = if (deRastreo) listOf(0, 1, 2, 3) else listOf(0, 1, 3)
        val nombresModo = mapOf(0 to "Sin agrupar", 1 to "Tipo", 2 to "Página", 3 to "Sitio")

        fun base(): List<EnlaceEncontrado> = todos.filter { enlaceVisible(it) && !quitados.contains(it.url) }

        fun filtrados(): List<EnlaceEncontrado> {
            val q = consulta.trim().lowercase()
            return base().filter { e ->
                (tipoSel == null || e.tipo == tipoSel) &&
                    (q.isEmpty() || e.nombre.lowercase().contains(q) || e.url.lowercase().contains(q) ||
                        e.host.lowercase().contains(q) || e.tipo.etiqueta.lowercase().contains(q))
            }
        }

        fun guardar(items: List<EnlaceEncontrado>) {
            g.pedirTexto("Nombre de la lista", fuenteTitulo.ifBlank { "Lista de enlaces" }) { nombre ->
                val l = ListasEnlaces.guardar(nombre, fuenteUrl, items)
                if (l == null) g.avisar("No se pudo guardar (máximo " + ListasEnlaces.MAX_LISTAS + " listas)")
                else g.avisar("Lista guardada (" + l.items.size + " enlaces)")
            }
        }

        fun exportarMenu(items: List<EnlaceEncontrado>) {
            g.opciones(
                "Exportar " + items.size + " enlaces",
                listOf(
                    Pair<String, () -> Unit>("Archivo de texto (.txt)…", { g.exportar(Enlaces.texto(items), "txt", "text/plain", fuenteTitulo) }),
                    Pair<String, () -> Unit>("Archivo CSV (.csv)…", { g.exportar(Enlaces.csv(items, fuenteUrl), "csv", "text/csv", fuenteTitulo) }),
                    Pair<String, () -> Unit>("Copiar con nombres", { g.copiar(Enlaces.textoConNombres(items)) })
                )
            )
        }

        fun menuEnlace(e: EnlaceEncontrado) {
            val items = ArrayList<Pair<String, () -> Unit>>()
            items.add(Pair<String, () -> Unit>("Copiar enlace", { g.copiar(e.url) }))
            items.add(Pair<String, () -> Unit>("Copiar nombre", { g.copiar(e.nombre) }))
            if (g.hayAppPara(e) &&
                (e.tipo == TipoEnlace.MAGNET || e.tipo == TipoEnlace.MEGA || e.tipo == TipoEnlace.LISTA || e.tipo == TipoEnlace.VIDEO ||
                    e.tipo == TipoEnlace.AUDIO || e.tipo == TipoEnlace.TORRENT)
            ) {
                items.add(Pair<String, () -> Unit>("Abrir con otra app…", { g.abrirConApp(e, privada) }))
            }
            if (e.url.startsWith("http")) items.add(Pair<String, () -> Unit>("Abrir en una pestaña nueva", { g.nuevaPestana(e.url, privada) }))
            items.add(Pair<String, () -> Unit>("Compartir enlace", { g.compartir(e.url) }))
            g.opciones(e.nombre.take(80), items)
        }

        val zTitulo = h.zona()
        val campo = h.campo("Buscar en la lista", "") { t ->
            consulta = t
            limite = LOTE
        }
        val zFiltros = h.zona()
        val zLista = h.zona()

        fun pintarTitulo() {
            h.llenar(zTitulo) {
                val n = filtrados().size
                h.barraTitulo(n.toString() + " enlaces", null) { h.cerrar() }
                val ocultos = todos.size - todos.count { enlaceVisible(it) }
                val nuevos = if (previos == null) 0 else base().count { !previos.contains(it.url) }
                val sb = StringBuilder(fuenteTitulo.ifBlank { "Enlaces" }.take(40))
                if (nuevos > 0) sb.append(" · +").append(nuevos).append(" nuevos")
                if (ocultos > 0) sb.append(" · ").append(ocultos).append(" de otros sitios ocultos")
                h.nota(sb.toString())
                if (seleccion) {
                    h.barraAcciones(
                        marcados.size.toString() + " marcados",
                        listOf(
                            Triple("Todos", false, {
                                val v = filtrados()
                                if (v.isNotEmpty() && v.all { marcados.contains(it.url) }) v.forEach { marcados.remove(it.url) }
                                else v.forEach { marcados.add(it.url) }
                                refrescarTodo()
                            }),
                            Triple("Salir", false, {
                                seleccion = false
                                marcados.clear()
                                refrescarTodo()
                            })
                        )
                    )
                } else {
                    h.barraAcciones(
                        "",
                        listOf(
                            Triple("☑ Seleccionar", false, {
                                seleccion = true
                                refrescarTodo()
                            }),
                            Triple("Más", false, { menuMas(h, base(), filtrados(), nueva, deRastreo, fuenteTitulo, fuenteUrl, alCambiarTipos, alRepetirBusqueda, ::guardar, ::exportarMenu) { refrescarTodo() } })
                        )
                    )
                }
            }
        }

        fun pintarFiltros() {
            h.llenar(zFiltros) {
                val lista = base()
                val tiposPresentes = TipoEnlace.values().filter { t -> lista.any { it.tipo == t } }
                if (tiposPresentes.size > 1) {
                    val etiquetas = ArrayList<String>()
                    etiquetas.add("Todo " + lista.size)
                    for (t in tiposPresentes) etiquetas.add(t.etiqueta + " " + lista.count { it.tipo == t })
                    val sel = if (tipoSel == null) 0 else tiposPresentes.indexOfFirst { it == tipoSel }.let { if (it < 0) 0 else it + 1 }
                    h.chips(etiquetas, sel) { i ->
                        tipoSel = if (i == 0) null else tiposPresentes[i - 1]
                        limite = LOTE
                        refrescarTodo()
                    }
                }
                h.segmentos(modos.map { nombresModo[it] ?: "" }, modos.indexOf(agrupar).let { if (it < 0) 0 else it }) { i ->
                    agrupar = modos[i]
                    limite = LOTE
                    refrescarTodo()
                }
            }
        }

        fun fila(e: EnlaceEncontrado) {
            val nuevo = previos != null && !previos.contains(e.url)
            val sel: Boolean? = if (seleccion) marcados.contains(e.url) else null
            h.filaLista(
                e.tipo.etiqueta.take(1), tonoDe(e.tipo.etiqueta), e.nombre, (if (nuevo) "NUEVO · " else "") + lineaEnlace(e), sel,
                {
                    if (seleccion) {
                        if (!marcados.add(e.url)) marcados.remove(e.url)
                        refrescarTodo()
                    } else {
                        menuEnlace(e)
                    }
                },
                {
                    seleccion = true
                    marcados.add(e.url)
                    refrescarTodo()
                },
                null
            )
        }

        fun pintarLista() {
            h.llenar(zLista) {
                val lista = filtrados()
                if (lista.isEmpty()) {
                    h.nota(if (consulta.isBlank()) "No hay enlaces con estos filtros." else "Nada coincide con la búsqueda.")
                } else {
                    var pintados = 0
                    if (agrupar == 0) {
                        for (e in lista) {
                            if (pintados >= limite) break
                            fila(e)
                            pintados++
                        }
                    } else {
                        val grupos = lista.groupBy {
                            when (agrupar) {
                                1 -> it.tipo.etiqueta
                                2 -> paginaDe(it)
                                else -> hostCorto(it)
                            }
                        }.entries.sortedByDescending { it.value.size }
                        for (gr in grupos) {
                            if (pintados >= limite) break
                            h.seccion(gr.key.take(48) + " · " + gr.value.size)
                            for (e in gr.value) {
                                if (pintados >= limite) break
                                fila(e)
                                pintados++
                            }
                        }
                    }
                    if (lista.size > pintados) {
                        h.fila("Mostrar más", null, (lista.size - pintados).toString()) {
                            limite += LOTE
                            refrescarTodo()
                        }
                    }
                }
                if (seleccion) h.agregar(View(act), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
            }
        }

        // Hoja inferior con las acciones sobre lo marcado: no bloquea la lista y se pliega a una tira.
        fun pintarAcciones() {
            val hayMarcas = seleccion && marcados.isNotEmpty()
            if (!hayMarcas) {
                hojaAcc?.cerrar()
                hojaAcc = null
                return
            }
            val sel = base().filter { marcados.contains(it.url) }
            var s = hojaAcc
            if (s == null) {
                val nueva2 = Hoja(act, paleta, privada, false)
                nueva2.atenuar(0f)
                nueva2.sinBloquearDetras()
                nueva2.fijarCancelable(false)
                nueva2.alCerrar { if (hojaAcc === nueva2) hojaAcc = null }
                hojaAcc = nueva2
                nueva2.mostrar()
                s = nueva2
            }
            s.limpiar()
            val peso = sel.filter { it.tamano >= 0 }.sumOf { it.tamano }
            s.encabezado(sel.size.toString() + " seleccionados" + (if (peso > 0) " · " + formatoTamano(peso) else ""))
            val acc = ArrayList<Triple<String, String, () -> Unit>>()
            acc.add(Triple("⧉", "Copiar", { g.copiar(Enlaces.texto(sel)); g.avisar(sel.size.toString() + " enlaces copiados") }))
            acc.add(Triple("⤴", "Compartir", { g.compartir(Enlaces.texto(sel)) }))
            acc.add(Triple("⬇", "Exportar", { exportarMenu(sel) }))
            if (nueva && !privada) acc.add(Triple("🔖", "Guardar", { guardar(sel) }))
            acc.add(Triple("🗑", "Quitar", {
                quitados.addAll(sel.map { it.url })
                marcados.clear()
                seleccion = false
                refrescarTodo()
            }))
            s.accesos(acc, acc.size)
        }

        refrescarTodo = {
            pintarTitulo()
            pintarFiltros()
            pintarLista()
            pintarAcciones()
        }
        campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                pintarTitulo()
                pintarLista()
            }
        })
        h.alCerrar {
            hojaAcc?.cerrar()
            hojaAcc = null
        }
        refrescarTodo()
        h.mostrar()
    }

    private fun menuMas(
        h: Hoja, visiblesTodos: List<EnlaceEncontrado>, visibles: List<EnlaceEncontrado>, nueva: Boolean, deRastreo: Boolean,
        ft: String, fu: String, alCambiarTipos: (() -> Unit)?, alRepetirBusqueda: (() -> Unit)?,
        guardar: (List<EnlaceEncontrado>) -> Unit, exportar: (List<EnlaceEncontrado>) -> Unit, alCambiar: () -> Unit
    ) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair<String, () -> Unit>("Copiar los " + visibles.size + " enlaces", { g.copiar(Enlaces.texto(visibles)) }))
        items.add(Pair<String, () -> Unit>("Exportar o copiar con nombres…", { exportar(visibles) }))
        if (nueva) {
            items.add(Pair<String, () -> Unit>("Guardar esta lista para después…", { guardar(visibles) }))
            items.add(Pair<String, () -> Unit>("Incluir archivos de otros sitios: " + (if (ajustes.enlacesOtros) "sí" else "no"), {
                ajustes.enlacesOtros = !ajustes.enlacesOtros
                alCambiar()
            }))
            if (!deRastreo && alRepetirBusqueda != null) {
                items.add(Pair<String, () -> Unit>("Filtro propio: " + ajustes.enlacesPropio.ifBlank { "ninguno" }.take(30) + "…", {
                    g.pedirTexto("Filtro propio (extensiones, texto o /regex/, separados por comas)", ajustes.enlacesPropio) { t ->
                        ajustes.enlacesPropio = t
                        h.cerrar()
                        alRepetirBusqueda()
                    }
                }))
            }
            if (!deRastreo && alCambiarTipos != null) {
                items.add(Pair<String, () -> Unit>("Cambiar tipos a buscar…", {
                    h.cerrar()
                    alCambiarTipos()
                }))
            }
        }
        g.opciones("Enlaces", items)
    }
}
