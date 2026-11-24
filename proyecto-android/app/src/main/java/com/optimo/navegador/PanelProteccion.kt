package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View

private val manejadorUi = Handler(Looper.getMainLooper())

private fun num(n: Int): String = if (n >= 100000) (n / 1000).toString() + " k" else n.toString()

/** Color y trazo del escudo según el estado. */
private fun pintarEscudo(v: VistaEscudo, e: EstadoEscudo, paleta: Paleta) {
    when (e) {
        EstadoEscudo.ACTIVO -> v.fijar(paleta.seguro, true, false, true)
        EstadoEscudo.PAUSADO -> v.fijar(paleta.advertencia, false, true, false)
        EstadoEscudo.APAGADO -> v.fijar(paleta.tenue, false, true, false)
        EstadoEscudo.NO_APLICA -> v.fijar(paleta.tenue, false, false, false)
    }
}

/**
 * Hoja del escudo de la barra de direcciones: qué hace la protección en la página activa (estado, cifras, dominios más
 * bloqueados) y sus ajustes por sitio, con acceso al resto (datos del sitio, personalizar, centro de protección).
 */
class PanelProteccion(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionProteccion,
    private val p: Pestana
) {
    private val hoja = Hoja(act, paleta, p.privada)
    private val host = ReglasUsuario.clave(Omnibox.hostDe(p.url))

    fun mostrar() {
        pintar()
        hoja.mostrar()
    }

    /** Se vuelve a pintar después de que termine la acción actual (no dentro del propio evento del interruptor). */
    private fun repintar() {
        manejadorUi.post { pintar() }
    }

    private fun pintar() {
        hoja.limpiar()
        val estado = g.estado(p)
        val icono = VistaEscudo(act)
        pintarEscudo(icono, estado, paleta)
        val sub = when (estado) {
            EstadoEscudo.ACTIVO -> if (p.privada) "Pestaña privada · protección activa" else "Protección activa en este sitio"
            EstadoEscudo.PAUSADO -> "Protección pausada en este sitio"
            EstadoEscudo.APAGADO -> "Bloqueo desactivado en todos los sitios"
            EstadoEscudo.NO_APLICA -> "Nada que bloquear en esta página"
        }
        val colorSub = if (estado == EstadoEscudo.ACTIVO) paleta.seguro else if (estado == EstadoEscudo.NO_APLICA) paleta.tenue else paleta.advertencia
        val ajustesBtn = hoja.botonIcono("⚙") {
            hoja.cerrar()
            g.abrirCentro()
        }
        hoja.cabecera(icono, if (host.isEmpty()) "Esta página" else host, sub, colorSub, ajustesBtn)

        if (!p.privada) {
            hoja.interruptor(
                "Protección en este sitio",
                if (estado == EstadoEscudo.APAGADO) "Desactivada para todos los sitios: al activarla se enciende para todos"
                else "Anuncios, rastreadores, emergentes y redirecciones",
                estado == EstadoEscudo.ACTIVO
            ) { v ->
                if (estado == EstadoEscudo.APAGADO) {
                    if (v) {
                        g.fijarBloqueoGlobal(true)
                        g.recargarConAjustes(p, host)
                    }
                } else if ((estado == EstadoEscudo.ACTIVO) != v) {
                    g.alternarPausa(p)
                }
                repintar()
            }
        }

        val hoy = EstadisticasBloqueo.hoy()
        hoja.cifras(
            listOf(
                Pair(num(p.bloqueados.get()), "anuncios y\nrastreadores"),
                Pair(num(p.emergentesBloqueados.get()), "emergentes y\nredirecciones"),
                Pair(num(hoy[0] + hoy[1] + hoy[2] + hoy[3]), "hoy en todos\nlos sitios")
            )
        )

        val tops = p.dev.bloqueadosPorHost()
        if (tops.isNotEmpty()) {
            hoja.seccion("Más bloqueados en esta página")
            for ((dominio, n) in tops.take(4)) {
                hoja.fila(dominio, null, n.toString()) { g.permitirDominio(p, dominio) }
            }
            if (tops.size > 4) hoja.fila("Ver todos (" + tops.size + ")…") {
                hoja.cerrar()
                g.verBloqueados(p, host)
            }
            hoja.nota("Toca un dominio para permitirlo si algo de la página deja de funcionar.")
        }

        if (!p.privada && host.isNotEmpty()) {
            hoja.seccion("En este sitio")
            hoja.fila("Ventanas emergentes", null, NombresSitio.EMERGENTES[ajustes.emergentesEn(host) + 1]) {
                g.elegirEmergentes(p, host) { repintar() }
            }
            hoja.fila("Cookies de terceros", "Al cambiar se recarga la página", NombresSitio.COOKIES[ajustes.cookiesTercerosModoEn(host)]) {
                ajustes.fijarCookiesTercerosEn(host, (ajustes.cookiesTercerosModoEn(host) + 1) % 3)
                g.recargarConAjustes(p, host)
                repintar()
            }
            hoja.fila("Imágenes", "Al cambiar se recarga la página", NombresSitio.IMAGENES[ajustes.imagenesEn(host)]) {
                ajustes.fijarImagenesEn(host, (ajustes.imagenesEn(host) + 1) % 3)
                g.recargarConAjustes(p, host)
                repintar()
            }
        }

        if (estado != EstadoEscudo.NO_APLICA) {
            hoja.seccion("En la página")
            hoja.fila("Quitar capa que tapa la página", "Avisos, muros anti-bloqueador, ventanas modales") {
                hoja.cerrar()
                g.quitarCapa(p)
            }
            hoja.fila("Bloquear un anuncio o elemento", "Toca lo que quieres ocultar o bloquear") {
                hoja.cerrar()
                g.elegirElemento(p)
            }
            if (!p.privada && host.isNotEmpty()) {
                if (ReglasUsuario.bloqueaDominio(host.lowercase())) {
                    hoja.fila("Desbloquear este sitio", "Lo bloqueaste tú") {
                        hoja.cerrar()
                        g.desbloquearSitio(p)
                    }
                } else {
                    hoja.fila("Bloquear este sitio", "Sale de la página y no vuelve a cargar") {
                        hoja.cerrar()
                        g.bloquearSitio(p)
                    }
                }
            }
        }

        hoja.seccion("Más")
        if (!p.privada && host.isNotEmpty()) {
            hoja.fila("Datos y permisos de este sitio", "Cookies, almacenamiento, permisos, reglas") {
                hoja.cerrar()
                g.datosDelSitio(host)
            }
            hoja.fila("Personalizar este sitio", "CSS propio, versión de escritorio, modo oscuro…") {
                hoja.cerrar()
                g.personalizar(p)
            }
        }
        if (estado != EstadoEscudo.NO_APLICA) {
            hoja.fila("Conexión y certificado") {
                hoja.cerrar()
                g.conexion()
            }
        }
        hoja.fila("Todas las opciones de protección") {
            hoja.cerrar()
            g.abrirCentro()
        }
        if (p.privada) hoja.nota("En pestañas privadas no se guardan ajustes, reglas ni estadísticas por sitio.")
    }
}

/**
 * Protección: la categoría del menú para todo el bloqueo. Estado y estadísticas arriba, lo que se puede hacer en la página
 * abierta, y después las listas, reglas, emergentes y rastreo. Se abre desde el menú, desde el ⚙ del escudo y desde el
 * icono de escudo de la pantalla de inicio.
 */
class CentroProteccion(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionProteccion,
    private val privada: Boolean
) {
    private val hoja = Hoja(act, paleta, privada, true)

    fun mostrar() {
        pintar()
        hoja.interceptarAtras {
            hoja.cerrar()
            g.volverAlMenu()
            true
        }
        hoja.mostrar()
    }

    private fun repintar() {
        manejadorUi.post { pintar() }
    }

    /** Cierra la hoja y abre otra pantalla (las pantallas de ajustes son diálogos propios de MainActivity). */
    private fun ir(d: Destino) {
        hoja.cerrar()
        g.abrir(d)
    }

    private fun pintar() {
        hoja.limpiar()
        hoja.barraTitulo("Protección", {
            hoja.cerrar()
            g.volverAlMenu()
        }, { hoja.cerrar() })
        val activo = ajustes.bloquearAnuncios
        val hoy = EstadisticasBloqueo.hoy()
        val nHoy = hoy[0] + hoy[1] + hoy[2] + hoy[3]
        val icono = VistaEscudo(act)
        pintarEscudo(icono, if (activo) EstadoEscudo.ACTIVO else EstadoEscudo.APAGADO, paleta)
        hoja.tarjetaEstado(
            icono,
            if (activo) "Protección activa" else "Protección desactivada",
            if (activo) nHoy.toString() + (if (nHoy == 1) " bloqueo hoy" else " bloqueos hoy") else "Los anuncios y rastreadores no se bloquean",
            if (activo) paleta.seguro else paleta.advertencia,
            activo
        ) { v ->
            g.fijarBloqueoGlobal(v)
            repintar()
        }

        // ---- estadísticas
        val total = EstadisticasBloqueo.total()
        val sem = EstadisticasBloqueo.ultimos(7)
        val sumaTotal = total.sum()
        if (!EstadisticasBloqueo.activas && sumaTotal == 0) {
            hoja.nota("Las estadísticas están desactivadas: no se guarda nada de lo que se bloquea.")
        } else if (sumaTotal == 0) {
            hoja.nota("Aún no hay datos. Se irán sumando mientras navegas (las pestañas privadas no cuentan).")
        } else {
            hoja.cifras(listOf(Pair(num(nHoy), "hoy"), Pair(num(sem.sum()), "últimos 7 días"), Pair(num(sumaTotal), "en total")))
            hoja.barras(EstadisticasBloqueo.serieDiaria(14), "hace 14 días", "hoy")
            for (i in 0..3) {
                if (total[i] > 0) hoja.dato(EstadisticasBloqueo.NOMBRES[i], num(total[i]))
            }
            val desde = EstadisticasBloqueo.desdeFecha()
            if (desde.isNotEmpty()) hoja.nota("Datos desde el " + desde + " (se guardan hasta 90 días).")
            val dominios = EstadisticasBloqueo.topDominios(5)
            if (dominios.isNotEmpty()) {
                hoja.seccion("Dominios más bloqueados")
                for ((d, n) in dominios) hoja.dato(d, num(n))
                hoja.fila("Ver los 50 más bloqueados…") { verTop(true) }
            }
            val sitios = EstadisticasBloqueo.topSitios(5)
            if (sitios.isNotEmpty()) {
                hoja.seccion("Sitios con más bloqueos")
                for ((s, n) in sitios) hoja.dato(s, num(n))
                hoja.fila("Ver los 50 sitios con más bloqueos…") { verTop(false) }
            }
        }

        // ---- la página abierta
        val p = g.pestanaActual()
        if (p != null && g.estado(p) != EstadoEscudo.NO_APLICA) {
            val host = ReglasUsuario.clave(Omnibox.hostDe(p.url))
            hoja.seccion("En esta página · " + host)
            if (!p.privada && activo) {
                hoja.interruptor("Bloqueo en este sitio", "Quítalo si el sitio no funciona bien", g.estado(p) != EstadoEscudo.PAUSADO) {
                    g.alternarPausa(p)
                    repintar()
                }
            }
            if (!p.privada && host.isNotEmpty()) {
                hoja.fila("Ventanas emergentes", null, NombresSitio.EMERGENTES[ajustes.emergentesEn(host) + 1]) {
                    g.elegirEmergentes(p, host) { repintar() }
                }
            }
            val nb = p.bloqueados.get()
            if (nb > 0) hoja.fila("Ver lo bloqueado en esta página", "Dominios y cuántas veces", nb.toString()) {
                hoja.cerrar()
                g.verBloqueados(p, host)
            }
            hoja.fila("Quitar capa que tapa la página", "Avisos, muros anti-bloqueador, ventanas modales") {
                hoja.cerrar()
                g.quitarCapa(p)
            }
            hoja.fila("Bloquear un anuncio o elemento", "Toca lo que quieres ocultar o bloquear") {
                hoja.cerrar()
                g.elegirElemento(p)
            }
        }

        // ---- reglas y listas
        hoja.seccion("Reglas y listas")
        hoja.fila("Listas de bloqueo", "OISD, HaGeZi, EasyList… y las tuyas") { ir(Destino.LISTAS) }
        hoja.fila("Técnicas contra anuncios", "Scripts sustitutos, vídeo vacío, rutas, huecos") { ir(Destino.TECNICAS) }
        hoja.fila("Reglas de sitios y anuncios", "Ocultar elementos, estilos y excepciones", ReglasUsuario.total.toString()) { ir(Destino.REGLAS) }
        hoja.fila("Mis dominios bloqueados", null, ReglasUsuario.listaDominios().size.toString()) { ir(Destino.DOMINIOS) }
        hoja.fila("Sitios con el bloqueo pausado", null, ajustes.sitiosPausados().size.toString()) { ir(Destino.PAUSADOS) }

        // ---- emergentes
        hoja.seccion("Ventanas emergentes y redirecciones")
        val modos = listOf("recomendado", "estricto", "permitir todas")
        hoja.fila("Pestañas emergentes", null, modos[ajustes.emergentes.coerceIn(0, 2)]) { ir(Destino.EMERGENTES_MODO) }
        hoja.interruptor("Bloquear redirecciones automáticas", "Cuando la página te lleva a otro sitio sin que toques nada", ajustes.bloquearRedirecciones) { v ->
            ajustes.bloquearRedirecciones = v
            repintar()
        }
        hoja.fila("Sitios con permiso", null, ajustes.emergentesPermitidos().size.toString()) { ir(Destino.EMERGENTES_SITIOS) }

        // ---- rastreo y conexión
        hoja.seccion("Rastreo y conexión")
        hoja.interruptor("Cookies de terceros", "Las que ponen otros sitios dentro de la página", ajustes.cookiesTerceros) { v ->
            g.fijarCookiesTerceros(v)
            repintar()
        }
        hoja.interruptor("Usar HTTPS primero", "Prueba la versión cifrada y vuelve a http solo si falla", ajustes.httpsPrimero) { v ->
            ajustes.httpsPrimero = v
            repintar()
        }

        // ---- privacidad
        hoja.seccion("Privacidad y datos")
        hoja.fila("Privacidad y datos", "Historial, datos por sitio, permisos, borrado al salir") { ir(Destino.PRIVACIDAD) }
        hoja.fila("Ajustes propios de cada sitio", null, ajustes.sitiosConAjustes().size.toString()) { ir(Destino.POR_SITIO) }

        // ---- estadísticas
        hoja.seccion("Estadísticas")
        hoja.interruptor("Guardar estadísticas de bloqueo", "Solo en este dispositivo, cifradas", EstadisticasBloqueo.activas) { v ->
            EstadisticasBloqueo.fijarActivas(v)
            repintar()
        }
        if (sumaTotal > 0) {
            hoja.boton("Borrar estadísticas", true) {
                g.confirmar("Borrar estadísticas", "Se borran los contadores y las listas de sitios y dominios bloqueados. El bloqueo no cambia.", "Borrar") {
                    EstadisticasBloqueo.reiniciar()
                    g.avisar("Estadísticas borradas")
                    repintar()
                }
            }
        }
    }

    /** Lista larga (hasta 50) de los dominios bloqueados o de los sitios con más bloqueos. */
    private fun verTop(dominios: Boolean) {
        val h = Hoja(act, paleta, privada)
        h.encabezado(if (dominios) "Dominios más bloqueados" else "Sitios con más bloqueos")
        val lista = if (dominios) EstadisticasBloqueo.topDominios(50) else EstadisticasBloqueo.topSitios(50)
        for ((d, n) in lista) h.dato(d, num(n))
        h.boton("Cerrar") { h.cerrar() }
        h.mostrar()
    }
}
