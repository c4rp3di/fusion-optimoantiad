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
 * Centro de protección: el sitio único de los ajustes globales de bloqueo y privacidad. Muestra las estadísticas y
 * lleva a cada pantalla de ajustes (listas, técnicas, reglas, emergentes, permisos, datos…). Se abre desde el escudo
 * de la barra (⚙), desde el icono de escudo de la pantalla de inicio y desde Ajustes.
 */
class CentroProteccion(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionProteccion,
    privada: Boolean
) {
    private val hoja = Hoja(act, paleta, privada)

    fun mostrar() {
        pintar()
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
        val activo = ajustes.bloquearAnuncios
        val icono = VistaEscudo(act)
        pintarEscudo(icono, if (activo) EstadoEscudo.ACTIVO else EstadoEscudo.APAGADO, paleta)
        hoja.cabecera(
            icono, "Protección y privacidad",
            if (activo) "Bloqueo de anuncios y rastreadores activo" else "Bloqueo de anuncios y rastreadores desactivado",
            if (activo) paleta.seguro else paleta.advertencia
        )
        hoja.interruptor("Bloquear anuncios y rastreadores", "Para todos los sitios (se puede pausar en cada uno)", activo) { v ->
            g.fijarBloqueoGlobal(v)
            repintar()
        }

        // ---- estadísticas
        hoja.seccion("Estadísticas de bloqueo")
        val total = EstadisticasBloqueo.total()
        val hoy = EstadisticasBloqueo.hoy()
        val sem = EstadisticasBloqueo.ultimos(7)
        val sumaTotal = total.sum()
        if (!EstadisticasBloqueo.activas && sumaTotal == 0) {
            hoja.nota("Las estadísticas están desactivadas: no se guarda nada de lo que se bloquea.")
        } else if (sumaTotal == 0) {
            hoja.nota("Aún no hay datos. Se irán sumando mientras navegas (las pestañas privadas no cuentan).")
        } else {
            hoja.cifras(
                listOf(
                    Pair(num(hoy.sum()), "hoy"),
                    Pair(num(sem.sum()), "últimos 7 días"),
                    Pair(num(sumaTotal), "en total")
                )
            )
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
            }
            val sitios = EstadisticasBloqueo.topSitios(5)
            if (sitios.isNotEmpty()) {
                hoja.seccion("Sitios con más bloqueos")
                for ((s, n) in sitios) hoja.dato(s, num(n))
            }
        }

        // ---- bloqueo
        hoja.seccion("Bloqueo")
        hoja.fila("Listas de bloqueo", "OISD, HaGeZi, EasyList… y las tuyas") { ir(Destino.LISTAS) }
        hoja.fila("Técnicas contra anuncios", "Scripts sustitutos, vídeo vacío, rutas, huecos") { ir(Destino.TECNICAS) }
        hoja.fila("Reglas de sitios y anuncios", null, ReglasUsuario.total.toString()) { ir(Destino.REGLAS) }
        hoja.fila("Mis dominios bloqueados", null, ReglasUsuario.listaDominios().size.toString()) { ir(Destino.DOMINIOS) }
        hoja.fila("Sitios con el bloqueo pausado", null, ajustes.sitiosPausados().size.toString()) { ir(Destino.PAUSADOS) }
        hoja.fila("Ajustes por sitio", "Emergentes, cookies, imágenes, escritorio…", ajustes.sitiosConAjustes().size.toString()) { ir(Destino.POR_SITIO) }

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
        hoja.fila("Excepciones de certificado", null, ExcepcionesSsl.todas().size.toString()) { ir(Destino.CERTIFICADOS) }

        // ---- datos
        hoja.seccion("Datos")
        hoja.fila("Privacidad y datos", "Historial, cookies, caché, borrado al salir…") { ir(Destino.PRIVACIDAD) }
        hoja.fila("Permisos concedidos a sitios", null, ajustes.totalPermisos().toString()) { ir(Destino.PERMISOS) }
        hoja.fila("Copia de seguridad") { ir(Destino.COPIA) }

        // ---- estadísticas: ajustes
        hoja.seccion("Estadísticas y barra")
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
        hoja.fila("Barra de direcciones", "Qué botones se ven, su orden y su tamaño") { ir(Destino.BARRA) }
    }
}
