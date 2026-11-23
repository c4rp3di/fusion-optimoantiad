package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper

/** Acciones del menú que ejecuta MainActivity. [cierra] indica si el panel se cierra antes (las que solo cambian un ajuste lo dejan abierto). */
enum class AccionMenu(val cierra: Boolean = true) {
    NUEVA, PRIVADA, MARCADORES, HISTORIAL, DESCARGAS, COMPARTIR, COPIAR_DIRECCION, IR_INICIO,
    PROTECCION_PAGINA, CENTRO,
    BUSCAR_PAGINA, LEER, ESCRITORIO(false), PANTALLA, GUARDAR_SIN_CONEXION, BUSCAR_ENLACES, RASTREAR, VER_RASTREO,
    CARGAR_IMAGENES, PERSONALIZAR, HERRAMIENTAS, REPRODUCIR_MEDIO, ABRIR_MEGA,
    PAGINAS_GUARDADAS, LISTAS_ENLACES, REABRIR, CERRAR_TODAS,
    MEDIA, SEGUNDO_PLANO, LECTURA_VOZ,
    BARRA, INICIO_AJUSTES, OSCURECER(false), AHORRO(false),
    MOTOR, ARRANQUE, DEV_CAPTURA(false), COPIA, ACERCA
}

/** Lo que el menú lateral necesita saber de MainActivity y cómo pedirle cosas. */
interface AnfitrionMenu {
    fun pestana(): Pestana?
    fun ejecutar(a: AccionMenu)
    fun estado(p: Pestana): EstadoEscudo
    fun cerradas(): Int
    fun paginasGuardadas(): Int
    fun listasEnlaces(): Int

    /** Enlaces del último rastreo, o -1 si no hay. */
    fun enlacesRastreo(): Int
    fun hayMedio(p: Pestana): Boolean
    fun hayMega(p: Pestana): Boolean
    fun imagenesBloqueadas(p: Pestana): Boolean
    fun escritorio(p: Pestana): Boolean
    fun buscador(): String
    fun arranque(): String
    fun oscurecer(): Boolean
    fun ahorro(): String
    fun devCaptura(): Boolean
}

/**
 * Menú de Optimo: panel lateral con la página actual, accesos rápidos y seis categorías que se abren dentro del propio
 * panel (con «‹» para volver). Sustituye al menú ⋮ y a la lista de Ajustes.
 */
class MenuLateral(
    private val act: Activity,
    private val paleta: Paleta,
    private val g: AnfitrionMenu,
    private val ajustes: Ajustes,
    private val inicial: String = RAIZ
) {
    companion object {
        const val RAIZ = "raiz"
        const val PAGINA = "pagina"
        const val DATOS = "datos"
        const val MULTIMEDIA = "multimedia"
        const val ASPECTO = "aspecto"
        const val GENERAL = "general"
    }

    private val privada = g.pestana()?.privada == true
    private val hoja = Hoja(act, paleta, privada, true)
    private val ui = Handler(Looper.getMainLooper())
    private var pagina = inicial

    fun mostrar() {
        pintar()
        hoja.interceptarAtras {
            if (pagina != RAIZ) {
                ir(RAIZ)
                true
            } else {
                false
            }
        }
        hoja.mostrar()
    }

    private fun repintar() {
        ui.post { pintar() }
    }

    private fun ir(p: String) {
        pagina = p
        pintar()
    }

    /** Ejecuta una acción: las que abren otra pantalla cierran antes el panel; las de ajuste lo dejan y lo repintan. */
    private fun hacer(a: AccionMenu) {
        if (a.cierra) hoja.cerrar()
        g.ejecutar(a)
        if (!a.cierra) repintar()
    }

    private fun pintar() {
        hoja.limpiar()
        when (pagina) {
            PAGINA -> pintarPagina()
            DATOS -> pintarDatos()
            MULTIMEDIA -> pintarMultimedia()
            ASPECTO -> pintarAspecto()
            GENERAL -> pintarGeneral()
            else -> pintarRaiz()
        }
    }

    private fun sub(titulo: String) {
        hoja.barraTitulo(titulo, { ir(RAIZ) }, { hoja.cerrar() })
    }

    private fun si(v: Boolean): String = if (v) "activado" else "desactivado"

    // ---------------------------------------------------------------- raíz

    private fun pintarRaiz() {
        val p = g.pestana()
        val estado = if (p != null) g.estado(p) else EstadoEscudo.NO_APLICA
        val icono = VistaEscudo(act)
        when (estado) {
            EstadoEscudo.ACTIVO -> icono.fijar(paleta.seguro, true, false, true)
            EstadoEscudo.PAUSADO -> icono.fijar(paleta.advertencia, false, true, false)
            EstadoEscudo.APAGADO -> icono.fijar(paleta.tenue, false, true, false)
            EstadoEscudo.NO_APLICA -> icono.fijar(paleta.tenue, false, false, false)
        }
        icono.isClickable = true
        icono.setOnClickListener { hacer(AccionMenu.PROTECCION_PAGINA) }
        val host = if (p == null || p.inicio) "" else ReglasUsuario.clave(Omnibox.hostDe(p.url))
        val titulo = if (p == null || p.inicio) "Optimo" else if (host.isNotEmpty()) host else p.titulo
        val sub = when (estado) {
            EstadoEscudo.ACTIVO -> "Protección activa"
            EstadoEscudo.PAUSADO -> "Protección pausada en este sitio"
            EstadoEscudo.APAGADO -> "Bloqueo desactivado"
            EstadoEscudo.NO_APLICA -> if (privada) "Pestaña privada" else "Menú"
        }
        val colorSub = if (estado == EstadoEscudo.ACTIVO) paleta.seguro else if (estado == EstadoEscudo.NO_APLICA) paleta.tenue else paleta.advertencia
        hoja.cabecera(icono, titulo, sub, colorSub, hoja.botonIcono("✕") { hoja.cerrar() })

        val puedeCompartir = p != null && !p.inicio && p.url.startsWith("http")
        hoja.accesos(
            listOf(
                Triple("＋", "Nueva", { hacer(AccionMenu.NUEVA) }),
                Triple("🕶", "Privada", { hacer(AccionMenu.PRIVADA) }),
                Triple("🔖", "Marcadores", { hacer(AccionMenu.MARCADORES) }),
                Triple("🕘", "Historial", { hacer(AccionMenu.HISTORIAL) }),
                Triple("⬇", "Descargas", { hacer(AccionMenu.DESCARGAS) }),
                if (puedeCompartir) Triple("⤴", "Compartir", { hacer(AccionMenu.COMPARTIR) })
                else Triple("⌂", "Inicio", { hacer(AccionMenu.IR_INICIO) })
            )
        )

        val hoy = EstadisticasBloqueo.hoy()
        val nHoy = hoy[0] + hoy[1] + hoy[2] + hoy[3]
        hoja.tarjeta("🛡", "Protección y privacidad",
            if (ajustes.bloquearAnuncios) "Bloqueo activo · " + nHoy + " bloqueos hoy" else "Bloqueo desactivado"
        ) { hacer(AccionMenu.CENTRO) }
        hoja.tarjeta("📄", "Esta página", "Buscar, leer, escritorio, pantalla completa, guardar…") { ir(PAGINA) }
        hoja.tarjeta("🔖", "Marcadores y datos", "Historial, páginas guardadas, listas, descargas") { ir(DATOS) }
        hoja.tarjeta("🎬", "Multimedia", "Enlaces de vídeo, segundo plano, lectura y voz") { ir(MULTIMEDIA) }
        hoja.tarjeta("🎨", "Aspecto", "Barra de direcciones, inicio, oscurecer páginas") { ir(ASPECTO) }
        hoja.tarjeta("⚙", "General", "Buscador, arranque, copia de seguridad, acerca de") { ir(GENERAL) }

        if (g.cerradas() > 0) hoja.fila("Reabrir pestaña cerrada (" + g.cerradas() + ")…", icono = "↺") { hacer(AccionMenu.REABRIR) }
        hoja.fila("Cerrar todas las pestañas", icono = "✕") { hacer(AccionMenu.CERRAR_TODAS) }
    }

    // ---------------------------------------------------------------- esta página

    private fun pintarPagina() {
        sub("Esta página")
        val p = g.pestana()
        if (p == null) {
            hoja.nota("No hay ninguna pestaña abierta.")
            return
        }
        val conPagina = !p.inicio && p.url.isNotEmpty()
        val http = p.url.startsWith("http")
        hoja.fila("Buscar en la página", icono = "🔎") { hacer(AccionMenu.BUSCAR_PAGINA) }
        if (g.hayMedio(p)) hoja.fila("Reproducir vídeo en otra app…", "Hay un vídeo en esta página", icono = "▶") { hacer(AccionMenu.REPRODUCIR_MEDIO) }
        if (g.hayMega(p)) hoja.fila("Abrir enlace de Mega en otra app…", icono = "▶") { hacer(AccionMenu.ABRIR_MEGA) }
        if (conPagina && http) hoja.fila("Modo lectura", icono = "📖") { hacer(AccionMenu.LEER) }
        hoja.interruptor("Versión de escritorio", null, g.escritorio(p)) { hacer(AccionMenu.ESCRITORIO) }
        hoja.fila("Pantalla completa", icono = "⛶") { hacer(AccionMenu.PANTALLA) }
        if (conPagina && http && g.imagenesBloqueadas(p)) {
            hoja.fila("Cargar imágenes en esta página", "Ahorro de datos activo", icono = "🖼") { hacer(AccionMenu.CARGAR_IMAGENES) }
        }

        if (conPagina) {
            hoja.seccion("Compartir y guardar")
            if (!p.archivoLocal) hoja.fila("Compartir página", icono = "⤴") { hacer(AccionMenu.COMPARTIR) }
            hoja.fila("Copiar dirección", icono = "⧉") { hacer(AccionMenu.COPIAR_DIRECCION) }
            if (http && !p.privada && !p.sinConexion && !p.appWeb) {
                hoja.fila("Guardar para leer sin conexión", icono = "💾") { hacer(AccionMenu.GUARDAR_SIN_CONEXION) }
            }
        }
        if (conPagina && http && !p.appWeb) {
            hoja.seccion("Enlaces")
            hoja.fila("Buscar enlaces en la página…", icono = "🔗") { hacer(AccionMenu.BUSCAR_ENLACES) }
            hoja.fila("Rastrear este sitio…", icono = "🕸") { hacer(AccionMenu.RASTREAR) }
            val n = g.enlacesRastreo()
            if (n >= 0) hoja.fila("Ver el último rastreo", null, n.toString() + " enlaces", icono = "📋") { hacer(AccionMenu.VER_RASTREO) }
        }
        hoja.seccion("Más")
        if (conPagina && http && !p.privada) hoja.fila("Personalizar este sitio…", "CSS propio, modo oscuro, emergentes…", icono = "🎛") { hacer(AccionMenu.PERSONALIZAR) }
        if (conPagina) hoja.fila("Herramientas de desarrollo", icono = "🛠") { hacer(AccionMenu.HERRAMIENTAS) }
        hoja.fila("Ir a la pantalla de inicio", icono = "⌂") { hacer(AccionMenu.IR_INICIO) }
    }

    // ---------------------------------------------------------------- marcadores y datos

    private fun pintarDatos() {
        sub("Marcadores y datos")
        hoja.fila("Marcadores", icono = "🔖") { hacer(AccionMenu.MARCADORES) }
        hoja.fila("Historial", icono = "🕘") { hacer(AccionMenu.HISTORIAL) }
        hoja.fila("Descargas", icono = "⬇") { hacer(AccionMenu.DESCARGAS) }
        hoja.fila("Páginas guardadas", "Para leer sin conexión", g.paginasGuardadas().toString(), icono = "💾") { hacer(AccionMenu.PAGINAS_GUARDADAS) }
        hoja.fila("Listas de enlaces", "Enlaces encontrados y guardados", g.listasEnlaces().toString(), icono = "📋") { hacer(AccionMenu.LISTAS_ENLACES) }
        if (g.cerradas() > 0) hoja.fila("Reabrir pestaña cerrada", null, g.cerradas().toString(), icono = "↺") { hacer(AccionMenu.REABRIR) }
        hoja.seccion("Privacidad")
        hoja.fila("Privacidad y datos…", "Borrar historial, cookies, caché; borrado al salir", icono = "🛡") { hacer(AccionMenu.CENTRO) }
    }

    // ---------------------------------------------------------------- multimedia

    private fun pintarMultimedia() {
        sub("Multimedia")
        hoja.fila("Enlaces multimedia, magnets y Mega", "Qué hacer con vídeos, audio y torrents", icono = "🔗") { hacer(AccionMenu.MEDIA) }
        hoja.fila("Reproducción en segundo plano", "Seguir oyendo con la pantalla apagada", icono = "🎧") { hacer(AccionMenu.SEGUNDO_PLANO) }
        hoja.fila("Lectura y voz", "Voz del modo lectura y aspecto de la vista", icono = "📖") { hacer(AccionMenu.LECTURA_VOZ) }
    }

    // ---------------------------------------------------------------- aspecto

    private fun pintarAspecto() {
        sub("Aspecto")
        hoja.fila("Barra de direcciones", "Botones, orden, tamaños y separación", icono = "▭") { hacer(AccionMenu.BARRA) }
        hoja.fila("Pantalla de inicio", "Colores, accesos, buscador, título", icono = "⌂") { hacer(AccionMenu.INICIO_AJUSTES) }
        hoja.interruptor("Oscurecer páginas web", "Aplica un modo oscuro a las páginas que no lo tienen", g.oscurecer()) { hacer(AccionMenu.OSCURECER) }
        hoja.fila("Ahorro de datos (sin imágenes)", "Se aplica a las páginas que cargues desde ahora", g.ahorro(), icono = "🖼") { hacer(AccionMenu.AHORRO) }
    }

    // ---------------------------------------------------------------- general

    private fun pintarGeneral() {
        sub("General")
        hoja.fila("Buscador", null, g.buscador(), icono = "🔍") { hacer(AccionMenu.MOTOR) }
        hoja.fila("Al abrir Optimo", null, g.arranque(), icono = "🚀") { hacer(AccionMenu.ARRANQUE) }
        hoja.interruptor("Herramientas de desarrollo: capturar desde el inicio", "Recarga las páginas abiertas para aplicarlo", g.devCaptura()) { hacer(AccionMenu.DEV_CAPTURA) }
        hoja.fila("Copia de seguridad", "Exportar e importar tus datos", icono = "💽") { hacer(AccionMenu.COPIA) }
        hoja.fila("Acerca de Optimo", icono = "ℹ") { hacer(AccionMenu.ACERCA) }
    }
}
