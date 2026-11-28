package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper

/** Acciones del menú que ejecuta MainActivity. [cierra] indica si el panel se cierra antes (las que solo cambian un ajuste lo dejan abierto). */
enum class AccionMenu(val cierra: Boolean = true) {
    NUEVA, PRIVADA, MARCADORES, HISTORIAL, DESCARGAS, COMPARTIR, COPIAR_DIRECCION, IR_INICIO,
    PROTECCION_PAGINA, CENTRO, PRIVACIDAD, ASPECTO, OCULTAR_ELEMENTO, QUITAR_CAPA, PESTANAS,
    BUSCAR_PAGINA, LEER, ESCRITORIO(false), PANTALLA, GUARDAR_SIN_CONEXION, BUSCAR_ENLACES, RASTREAR, VER_RASTREO,
    CARGAR_IMAGENES, PERSONALIZAR, HERRAMIENTAS, REPRODUCIR_MEDIO, ABRIR_MEGA,
    PAGINAS_GUARDADAS, APPS_LOCALES, LISTAS_ENLACES, REABRIR, CERRAR_TODAS, SALIR,
    MEDIA, SEGUNDO_PLANO, LECTURA_VOZ,
    BARRA, INICIO_AJUSTES, OSCURECER(false), AHORRO(false),
    MOTOR, ARRANQUE, HTML_LOCALES, DEV_CAPTURA(false), COPIA, ACERCA
}

/** Un botón de los accesos rápidos del menú. */
class AccesoMenu(val accion: AccionMenu, val icono: String, val etiqueta: String, val nombre: String)

/** Accesos rápidos del menú: los posibles, los de por defecto y cuáles ha elegido el usuario (Aspecto › Accesos rápidos). */
object AccesosMenu {
    const val MAX = 8

    val POOL: List<AccesoMenu> = listOf(
        AccesoMenu(AccionMenu.OCULTAR_ELEMENTO, "👆", "Ocultar", "Ocultar un elemento (tocar)"),
        AccesoMenu(AccionMenu.QUITAR_CAPA, "🧹", "Quitar capa", "Quitar capa que tapa la página"),
        AccesoMenu(AccionMenu.NUEVA, "＋", "Nueva", "Pestaña nueva"),
        AccesoMenu(AccionMenu.PRIVADA, "🕶", "Privada", "Pestaña privada"),
        AccesoMenu(AccionMenu.PESTANAS, "🗂", "Pestañas", "Gestor de pestañas"),
        AccesoMenu(AccionMenu.MARCADORES, "🔖", "Marcadores", "Marcadores"),
        AccesoMenu(AccionMenu.HISTORIAL, "🕘", "Historial", "Historial"),
        AccesoMenu(AccionMenu.DESCARGAS, "⬇", "Descargas", "Descargas"),
        AccesoMenu(AccionMenu.COMPARTIR, "⤴", "Compartir", "Compartir la página"),
        AccesoMenu(AccionMenu.COPIAR_DIRECCION, "⧉", "Copiar", "Copiar la dirección"),
        AccesoMenu(AccionMenu.BUSCAR_PAGINA, "🔎", "Buscar", "Buscar en la página"),
        AccesoMenu(AccionMenu.LEER, "📖", "Lectura", "Modo lectura"),
        AccesoMenu(AccionMenu.PANTALLA, "⛶", "Completa", "Pantalla completa"),
        AccesoMenu(AccionMenu.IR_INICIO, "⌂", "Inicio", "Ir a la pantalla de inicio")
    )

    private val DEFECTO = listOf(
        AccionMenu.OCULTAR_ELEMENTO, AccionMenu.NUEVA, AccionMenu.PRIVADA,
        AccionMenu.MARCADORES, AccionMenu.HISTORIAL, AccionMenu.DESCARGAS
    )

    /** Los accesos que se muestran, en orden. */
    fun lista(ajustes: Ajustes): List<AccesoMenu> {
        val texto = ajustes.accesosMenu
        val acciones = ArrayList<AccionMenu>()
        if (texto.isNotBlank()) {
            for (n in texto.split(',')) {
                val a = try {
                    AccionMenu.valueOf(n.trim())
                } catch (e: Exception) {
                    null
                }
                if (a != null && !acciones.contains(a) && POOL.any { it.accion == a }) acciones.add(a)
            }
        }
        if (acciones.isEmpty()) acciones.addAll(DEFECTO)
        return acciones.take(MAX).mapNotNull { a -> POOL.firstOrNull { it.accion == a } }
    }

    fun guardar(ajustes: Ajustes, acciones: List<AccionMenu>) {
        ajustes.accesosMenu = acciones.take(MAX).joinToString(",") { it.name }
    }
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

    /** Nombre corto del modo elegido para abrir los .html del dispositivo. */
    fun htmlLocales(): String
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
        const val MULTIMEDIA = "multimedia"
        const val GENERAL = "general"
        const val CONSERVAR = "conservar"
    }

    private val privada = g.pestana()?.privada == true
    private val hoja = Hoja(act, paleta, privada, true)
    private val ui = Handler(Looper.getMainLooper())
    private var pagina = inicial

    fun mostrar() {
        pintar()
        hoja.interceptarAtras {
            if (pagina == CONSERVAR) {
                ir(PAGINA)
                true
            } else if (pagina != RAIZ) {
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
            MULTIMEDIA -> pintarMultimedia()
            GENERAL -> pintarGeneral()
            CONSERVAR -> pintarConservar()
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

        val rapidos = AccesosMenu.lista(ajustes)
        hoja.accesos(
            rapidos.map { a -> Triple(a.icono, a.etiqueta, { hacer(a.accion) }) },
            if (rapidos.size <= 6) 3 else 4
        )

        val hoy = EstadisticasBloqueo.hoy()
        val nHoy = hoy[0] + hoy[1] + hoy[2] + hoy[3]
        hoja.tarjeta("🛡", "Protección",
            if (ajustes.bloquearAnuncios) "Bloqueo activo · " + nHoy + " bloqueos hoy" else "Bloqueo desactivado"
        ) { hacer(AccionMenu.CENTRO) }
        hoja.tarjeta("🔒", "Privacidad y datos", "Historial, datos por sitio, permisos, borrado al salir") { hacer(AccionMenu.PRIVACIDAD) }
        hoja.tarjeta("📄", "Esta página", "Buscar, leer, escritorio, pantalla completa, guardar…") { ir(PAGINA) }
        hoja.tarjeta("🔖", "Guardados", "Marcadores, historial, páginas guardadas, listas, descargas") { hacer(AccionMenu.MARCADORES) }
        hoja.tarjeta("🎬", "Multimedia", "Enlaces de vídeo, segundo plano, lectura y voz") { ir(MULTIMEDIA) }
        hoja.tarjeta("🎨", "Aspecto", "Ocultar elementos, texto y zoom, barra de direcciones, inicio") { hacer(AccionMenu.ASPECTO) }
        hoja.tarjeta("⚙", "General", "Buscador, arranque, copia de seguridad, acerca de") { ir(GENERAL) }

        if (g.cerradas() > 0) hoja.fila("Reabrir pestaña cerrada (" + g.cerradas() + ")…", icono = "↺") { hacer(AccionMenu.REABRIR) }
        hoja.fila("Cerrar todas las pestañas", icono = "✕") { hacer(AccionMenu.CERRAR_TODAS) }
        hoja.fila("Cerrar Optimo", "Sale de la app sin ir pestaña por pestaña", icono = "⏻") { hacer(AccionMenu.SALIR) }
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
        if (conPagina && (http || p.appWeb) && !p.privada) {
            val host = ReglasUsuario.clave(Omnibox.hostDe(p.url))
            if (host.isNotEmpty()) {
                hoja.fila("Conservar este sitio", "Que los borrados globales no le afecten", Conservar.resumen(ajustes.conservadoDe(host)), icono = "🔒") { ir(CONSERVAR) }
            }
        }
        if (conPagina) hoja.fila("Herramientas de desarrollo", icono = "🛠") { hacer(AccionMenu.HERRAMIENTAS) }
        hoja.fila("Ir a la pantalla de inicio", icono = "⌂") { hacer(AccionMenu.IR_INICIO) }
    }

    // ---------------------------------------------------------------- conservar el sitio de la pestaña

    private fun pintarConservar() {
        hoja.barraTitulo("Conservar este sitio", { ir(PAGINA) }, { hoja.cerrar() })
        val p = g.pestana()
        val host = if (p == null || p.inicio) "" else ReglasUsuario.clave(Omnibox.hostDe(p.url))
        if (host.isEmpty()) {
            hoja.nota("Abre una página para elegir qué conservar de su sitio.")
            return
        }
        hoja.nota("Sitio: " + host)
        Conservar.pintar(hoja, ajustes, host) { repintar() }
    }

    // ---------------------------------------------------------------- multimedia

    private fun pintarMultimedia() {
        sub("Multimedia")
        hoja.fila("Enlaces multimedia, magnets y Mega", "Qué hacer con vídeos, audio y torrents", icono = "🔗") { hacer(AccionMenu.MEDIA) }
        hoja.fila("Reproducción en segundo plano", "Seguir oyendo con la pantalla apagada", icono = "🎧") { hacer(AccionMenu.SEGUNDO_PLANO) }
        hoja.fila("Lectura y voz", "Voz del modo lectura y aspecto de la vista", icono = "📖") { hacer(AccionMenu.LECTURA_VOZ) }
    }

    // ---------------------------------------------------------------- general

    private fun pintarGeneral() {
        sub("General")
        hoja.fila("Buscador", null, g.buscador(), icono = "🔍") { hacer(AccionMenu.MOTOR) }
        hoja.fila("Al abrir Optimo", null, g.arranque(), icono = "🚀") { hacer(AccionMenu.ARRANQUE) }
        hoja.fila("Aplicaciones web guardadas", "Los .html que abres como aplicación, con sus datos", icono = "🧩") { hacer(AccionMenu.APPS_LOCALES) }
        hoja.fila("Archivos .html del dispositivo", "Cómo se abren los que traen JavaScript", g.htmlLocales(), icono = "📄") { hacer(AccionMenu.HTML_LOCALES) }
        hoja.interruptor("Herramientas de desarrollo: capturar desde el inicio", "Recarga las páginas abiertas para aplicarlo", g.devCaptura()) { hacer(AccionMenu.DEV_CAPTURA) }
        hoja.fila("Copia de seguridad", "Exportar e importar tus datos", icono = "💽") { hacer(AccionMenu.COPIA) }
        hoja.fila("Acerca de Optimo", icono = "ℹ") { hacer(AccionMenu.ACERCA) }
    }
}
