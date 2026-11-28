package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper

/** Lo que se sabe de un sitio: visitas, cookies, permisos, reglas y ajustes propios. */
class InfoSitio(val host: String) {
    var visitas = 0
    var cookies = 0
    var permisos: List<String> = emptyList()
    var reglas = 0
    var ajustes: List<String> = emptyList()
}

/** Lo que el centro de privacidad necesita de MainActivity (pantallas que siguen allí y avisos). */
interface AnfitrionPrivacidad {
    fun pestana(): Pestana?
    fun avisar(texto: String)
    fun copiar(texto: String)
    fun volverAlMenu()
    fun paginasGuardadas()
    fun listasEnlaces()
    fun reglasDe(sitio: String)
    fun almacenamientoDe(p: Pestana)
    fun reinstalarAnuncios()
    fun borrarSesionGuardada()

    /** Sitios (sin «www.») de las pestañas normales abiertas. */
    fun hostsAbiertos(): List<String>

    /** Pestaña normal abierta en ese sitio, si hay. */
    fun paginaAbiertaEn(host: String): Pestana?
}

/**
 * Privacidad y datos: qué guarda Optimo, cuánto, y cómo controlarlo o borrarlo, organizado por temas
 * (navegación, sitios, guardado en el dispositivo, conexión segura, borrado al salir). Incluye «Datos por sitio»:
 * para cada sitio, sus cookies, permisos, ajustes, reglas e historial, con borrado individual o de todo a la vez.
 */
class CentroPrivacidad(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val historial: ListaPersistente,
    private val limpieza: LimpiezaDatos,
    private val g: AnfitrionPrivacidad,
    inicial: String = RESUMEN,
    sitioInicial: String = "",
    filtroInicial: Int = 0
) {
    companion object {
        const val RESUMEN = "resumen"
        const val HISTORIAL = "historial"
        const val SITIOS = "sitios"
        const val SITIO = "sitio"
        const val COOKIES = "cookies"
        const val CERTIFICADOS = "certificados"

        /** Filtros de la lista de sitios. */
        const val F_TODOS = 0
        const val F_COOKIES = 1
        const val F_PERMISOS = 2
        const val F_AJUSTES = 3
        const val F_CONSERVADOS = 4
        private val ETIQUETAS_FILTRO = listOf("Todos", "Con cookies", "Con permisos", "Con ajustes", "Conservados")
    }

    private val privada = g.pestana()?.privada == true
    private val hoja = Hoja(act, paleta, privada, true)
    private val ui = Handler(Looper.getMainLooper())
    private var pagina = inicial
    private var sitio = sitioInicial
    private var filtro = filtroInicial
    private var cache: List<InfoSitio>? = null

    fun mostrar() {
        pintar()
        hoja.interceptarAtras {
            atras()
            true
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

    private fun atras() {
        when (pagina) {
            COOKIES -> ir(SITIO)
            SITIO -> ir(SITIOS)
            HISTORIAL, SITIOS, CERTIFICADOS -> ir(RESUMEN)
            else -> {
                hoja.cerrar()
                g.volverAlMenu()
            }
        }
    }

    private fun titulo(t: String) {
        hoja.barraTitulo(t, { atras() }, { hoja.cerrar() })
    }

    private fun confirmar(titulo: String, mensaje: String, boton: String, aviso: Boolean = true, accion: () -> Unit) {
        Dialogos.confirmar(act, paleta, privada, titulo, mensaje, boton, aviso, accion)
    }

    private fun pintar() {
        hoja.limpiar()
        when (pagina) {
            HISTORIAL -> pintarHistorial()
            SITIOS -> pintarSitios()
            SITIO -> pintarSitio()
            COOKIES -> pintarCookies()
            CERTIFICADOS -> pintarCertificados()
            else -> pintarResumen()
        }
    }

    // ---------------------------------------------------------------- sitios conocidos

    private fun invalidar() {
        cache = null
    }

    /** Sitios con algo guardado o visitado: abiertos, historial (los 400 más recientes), permisos, ajustes y reglas. */
    private fun sitios(): List<InfoSitio> {
        val previo = cache
        if (previo != null) return previo
        val mapa = LinkedHashMap<String, InfoSitio>()
        fun de(h: String): InfoSitio? {
            val k = ReglasUsuario.clave(h)
            if (k.isEmpty()) return null
            return mapa.getOrPut(k) { InfoSitio(k) }
        }
        for (h in g.hostsAbiertos()) de(h)
        for (e in historial.todos()) {
            val h = ReglasUsuario.clave(Omnibox.hostDe(e.url))
            if (h.isEmpty()) continue
            val info = mapa[h] ?: (if (mapa.size < 400) de(h) else null) ?: continue
            info.visitas++
        }
        for (h in ajustes.sitiosConPermisos()) de(h)
        for (h in ajustes.sitiosConAjustes()) de(h)
        for (h in ReglasUsuario.hostsConReglas()) de(h)
        for (h in ajustes.sitiosConservados()) de(h)
        for (info in mapa.values) rellenar(info)
        val lista = mapa.values.sortedWith(
            compareByDescending<InfoSitio> { it.cookies }.thenByDescending { it.visitas }.thenBy { it.host }
        )
        cache = lista
        return lista
    }

    private fun rellenar(info: InfoSitio) {
        info.cookies = limpieza.cookiesDeSitio(info.host).size
        info.permisos = ajustes.permisosDe(info.host)
        info.reglas = ReglasUsuario.selectoresPara(info.host).size
        info.ajustes = ajustes.resumenSitio(info.host)
    }

    private fun infoDe(host: String): InfoSitio {
        val i = InfoSitio(host)
        i.visitas = historial.todos().count { e -> ReglasUsuario.clave(Omnibox.hostDe(e.url)) == host }
        rellenar(i)
        return i
    }

    private fun detalle(i: InfoSitio): String {
        val p = ArrayList<String>()
        if (i.cookies > 0) p.add(i.cookies.toString() + (if (i.cookies == 1) " cookie" else " cookies"))
        if (i.permisos.isNotEmpty()) p.add("permisos: " + i.permisos.joinToString(", ") { t -> etiquetaPermiso(t) })
        if (i.reglas > 0) p.add(i.reglas.toString() + (if (i.reglas == 1) " regla" else " reglas"))
        if (i.ajustes.isNotEmpty()) p.add(i.ajustes.size.toString() + (if (i.ajustes.size == 1) " ajuste" else " ajustes"))
        if (i.visitas > 0) p.add(i.visitas.toString() + (if (i.visitas == 1) " visita" else " visitas"))
        if (ajustes.conservadoDe(i.host) != 0) p.add("🔒 conservado")
        return if (p.isEmpty()) "Sin datos guardados" else p.joinToString(" · ")
    }

    // ---------------------------------------------------------------- resumen

    private fun pintarResumen() {
        titulo("Privacidad y datos")
        val lista = sitios()
        val conCookies = lista.count { it.cookies > 0 }
        hoja.cifras(
            listOf(
                Pair(historial.todos().size.toString(), "páginas en el\nhistorial"),
                Pair(conCookies.toString(), "sitios con\ncookies"),
                Pair(formatoTamano(limpieza.tamanoCache()), "de caché")
            )
        )

        hoja.seccion("Navegación")
        hoja.fila("Historial", "Guardar, conservar y borrar", historial.todos().size.toString() + " páginas", icono = "🕘") { ir(HISTORIAL) }
        hoja.interruptor("Recordar pestañas abiertas", "Al reabrir Optimo vuelven las pestañas", ajustes.guardarSesion) { v ->
            ajustes.guardarSesion = v
            if (!v) g.borrarSesionGuardada()
            repintar()
        }

        hoja.seccion("Sitios web")
        hoja.fila("Datos por sitio", "Cookies, permisos, ajustes y reglas de cada sitio", lista.size.toString(), icono = "🌐") {
            filtro = F_TODOS
            ir(SITIOS)
        }
        hoja.fila("Permisos concedidos", "Cámara, micrófono, ubicación…", ajustes.totalPermisos().toString(), icono = "🔑") {
            filtro = F_PERMISOS
            ir(SITIOS)
        }
        hoja.fila("Sitios conservados", "A salvo de los borrados globales (se elige en cada sitio)", ajustes.sitiosConservados().size.toString(), icono = "🔒") {
            filtro = F_CONSERVADOS
            ir(SITIOS)
        }

        hoja.seccion("Guardado en el dispositivo")
        hoja.fila(
            "Páginas guardadas sin conexión", null,
            PaginasGuardadas.todas().size.toString() + " · " + formatoTamano(PaginasGuardadas.totalBytes()), icono = "💾"
        ) {
            hoja.cerrar()
            g.paginasGuardadas()
        }
        hoja.fila("Lista de descargas", "Las descargas anotadas en Optimo (los archivos no se borran)", ListaDescargas.todas().size.toString(), icono = "⬇") {
            confirmar("Vaciar la lista de descargas", "Se quitan las descargas de la lista de Optimo. Los archivos del dispositivo no se borran.", "Vaciar") {
                ListaDescargas.vaciar()
                g.avisar("Lista de descargas vaciada")
                repintar()
            }
        }
        hoja.fila("Listas de enlaces", "Enlaces encontrados y guardados", ListasEnlaces.todas().size.toString(), icono = "📋") {
            hoja.cerrar()
            g.listasEnlaces()
        }
        hoja.fila("Borrar la caché", "Imágenes y archivos que aceleran las páginas", formatoTamano(limpieza.tamanoCache()), icono = "🧹") {
            confirmar("Borrar la caché", "Las páginas se volverán a descargar la próxima vez que las abras.", "Borrar") {
                limpieza.borrarCache()
                g.avisar("Caché borrada")
                repintar()
            }
        }

        hoja.seccion("Conexión segura")
        hoja.fila("Excepciones de certificado", "Sitios que autorizaste a pesar de un aviso", ExcepcionesSsl.todas().size.toString(), icono = "🔐") {
            ir(CERTIFICADOS)
        }

        hoja.seccion("Borrar al salir de Optimo")
        hoja.interruptor("Cookies y datos de sitios", null, (ajustes.borrarAlSalir and 1) != 0) { v -> alternarAlSalir(1, v) }
        hoja.interruptor("Historial", null, (ajustes.borrarAlSalir and 2) != 0) { v -> alternarAlSalir(2, v) }
        hoja.interruptor("Caché", null, (ajustes.borrarAlSalir and 4) != 0) { v -> alternarAlSalir(4, v) }
        if (ajustes.sitiosConservados().isNotEmpty()) hoja.nota(notaConservados())

        hoja.seccion("Limpieza total")
        hoja.boton("Borrar todo…", true) {
            confirmar(
                "Borrar todo",
                "Se borrarán el historial, la caché, las cookies, el almacenamiento de los sitios, los permisos concedidos y las estadísticas de bloqueo. Los marcadores y las reglas se conservan. " + notaConservados(),
                "Borrar todo"
            ) {
                limpieza.borrarDatosNavegacion()
                limpieza.borrarCache()
                ListaDescargas.vaciar()
                ajustes.quitarTodosPermisos()
                EstadisticasBloqueo.reiniciar()
                invalidar()
                g.avisar("Todo borrado")
                repintar()
            }
        }
    }

    private fun notaConservados(): String {
        val n = ajustes.sitiosConservados().size
        return if (n == 0) "" else "Los " + n + (if (n == 1) " sitio que conservas" else " sitios que conservas") + " no se tocan en lo que hayas marcado en cada uno."
    }

    private fun alternarAlSalir(bit: Int, activo: Boolean) {
        val actual = (ajustes.borrarAlSalir and bit) != 0
        if (actual != activo) ajustes.borrarAlSalir = ajustes.borrarAlSalir xor bit
        if (activo) g.avisar("Se borrará al cerrar Optimo (o al reabrirla, si Android la cierra antes)")
    }

    // ---------------------------------------------------------------- historial

    private fun pintarHistorial() {
        titulo("Historial")
        hoja.cifras(listOf(Pair(historial.todos().size.toString(), "páginas guardadas")))
        hoja.interruptor("Guardar historial", "Si lo desactivas no se anota lo que visitas", ajustes.guardarHistorial) { v ->
            ajustes.guardarHistorial = v
            if (!v && historial.todos().isNotEmpty()) {
                confirmar(
                    "Historial desactivado",
                    "¿Quieres borrar también el historial que ya está guardado (" + historial.todos().size + " páginas)?",
                    "Borrar"
                ) {
                    limpieza.vaciarHistorial()
                    g.avisar("Historial borrado")
                    repintar()
                }
            }
            repintar()
        }
        hoja.fila("Conservar historial", "Las páginas más antiguas se borran solas", limpieza.etiquetaRetencion(ajustes.retencionHistorial)) {
            val sel = maxOf(LimpiezaDatos.DIAS_RETENCION.indexOf(ajustes.retencionHistorial), 0)
            Dialogos.elegir(act, paleta, privada, "Conservar historial", LimpiezaDatos.ETIQUETAS_RETENCION, sel) { i ->
                cambiarRetencion(LimpiezaDatos.DIAS_RETENCION[i])
            }
        }
        hoja.seccion("Borrar")
        for (i in LimpiezaDatos.ETIQUETAS_RANGO.indices) {
            val desde = limpieza.inicioRango(i)
            val n = limpieza.contarDesde(desde)
            hoja.fila(
                (if (i == 4) "Borrar todo el historial" else "Borrar: " + LimpiezaDatos.ETIQUETAS_RANGO[i].lowercase()),
                null, n.toString() + (if (n == 1) " página" else " páginas"), aviso = i == 4
            ) { confirmarBorradoRango(i, desde, n) { repintar() } }
        }
        hoja.nota("Los marcadores se conservan.")
    }

    private fun cambiarRetencion(dias: Int) {
        if (dias <= 0) {
            ajustes.retencionHistorial = 0
            repintar()
            return
        }
        val n = limpieza.contarAnterioresA(System.currentTimeMillis() - dias * 86400000L)
        if (n == 0) {
            ajustes.retencionHistorial = dias
            repintar()
            return
        }
        confirmar(
            "Conservar " + limpieza.etiquetaRetencion(dias),
            "Se borrarán ahora " + n + " páginas más antiguas, y a partir de ahora se irán borrando las que superen ese plazo. Los marcadores se conservan.",
            "Aplicar", false
        ) {
            ajustes.retencionHistorial = dias
            limpieza.aplicarRetencion()
            g.avisar("Historial limpiado")
            repintar()
        }
    }

    private fun confirmarBorradoRango(i: Int, desde: Long, n: Int, volver: () -> Unit) {
        if (n == 0) {
            g.avisar("No hay páginas en ese rango")
            volver()
            return
        }
        confirmar(
            LimpiezaDatos.ETIQUETAS_RANGO[i],
            "Se borrarán " + n + " páginas del historial. Los marcadores se conservan.",
            "Borrar"
        ) {
            limpieza.quitarHistorialDesde(desde)
            invalidar()
            g.avisar("Historial borrado")
            volver()
        }
    }

    /** Lista «última hora / hoy / 7 días / 30 días / todo» y confirmación; [volver] se ejecuta al terminar. */
    fun elegirRangoBorrado(volver: () -> Unit) {
        Dialogos.elegir(act, paleta, privada, "Borrar historial", LimpiezaDatos.ETIQUETAS_RANGO, -1) { i ->
            val desde = limpieza.inicioRango(i)
            confirmarBorradoRango(i, desde, limpieza.contarDesde(desde), volver)
        }
    }

    // ---------------------------------------------------------------- datos por sitio

    private fun pasaFiltro(i: InfoSitio): Boolean = when (filtro) {
        F_COOKIES -> i.cookies > 0
        F_PERMISOS -> i.permisos.isNotEmpty()
        F_AJUSTES -> i.ajustes.isNotEmpty() || i.reglas > 0
        F_CONSERVADOS -> ajustes.conservadoDe(i.host) != 0
        else -> true
    }

    private fun pintarSitios() {
        titulo("Datos por sitio")
        val todos = sitios()
        hoja.chips(ETIQUETAS_FILTRO, filtro) { i ->
            filtro = i
            pintar()
        }
        val visibles = todos.filter { pasaFiltro(it) }
        if (visibles.isEmpty()) {
            hoja.nota(if (todos.isEmpty()) "Aún no hay sitios con datos. Aparecerán según navegues." else "Ningún sitio cumple este filtro.")
        }
        for (i in visibles.take(120)) {
            hoja.fila(i.host, detalle(i)) {
                sitio = i.host
                ir(SITIO)
            }
        }
        if (visibles.size > 120) hoja.nota("Se muestran 120 de " + visibles.size + ". Usa los filtros para acotar.")
        hoja.nota("Android no permite listar el almacenamiento de sitios que no tengas abiertos: la lista sale del historial, los permisos, los ajustes y las reglas.")

        hoja.seccion("Todos los sitios")
        if (ajustes.totalPermisos() > 0) {
            hoja.boton("Revocar todos los permisos…", true) {
                confirmar("Revocar permisos", "Los sitios volverán a preguntarte cada vez que pidan cámara, micrófono o ubicación.", "Revocar") {
                    ajustes.quitarTodosPermisos()
                    invalidar()
                    g.avisar("Permisos revocados")
                    repintar()
                }
            }
        }
        hoja.boton("Borrar cookies y datos de todos los sitios…", true) {
            confirmar(
                "Borrar cookies y datos de sitios",
                "Se cerrarán tus sesiones en los sitios web y se borrará su almacenamiento local, incluidos los datos de las aplicaciones web locales (por ejemplo, los proyectos de un editor abierto como archivo). " + notaConservados() +
                    (if (ajustes.hayConservados(Conservar.COOKIES or Conservar.DATOS)) " Con sitios conservados se borra sitio por sitio, solo los que Optimo conoce." else ""),
                "Borrar"
            ) {
                limpieza.borrarCookiesYAlmacenamiento()
                invalidar()
                g.avisar("Cookies y datos de sitios borrados")
                repintar()
            }
        }
        val conAjustes = ajustes.sitiosConAjustes().filter { h -> !ajustes.conservaHost(h, Conservar.AJUSTES) }
        if (conAjustes.isNotEmpty()) {
            hoja.boton("Restablecer los ajustes de todos los sitios…", true) {
                confirmar(
                    "Restablecer ajustes por sitio",
                    "Se quitarán los ajustes propios de " + conAjustes.size + " sitios (escritorio, modo oscuro, emergentes, cookies de terceros, imágenes, suspensión y pausa del bloqueo). Los permisos, las reglas y el CSS no se tocan.",
                    "Restablecer"
                ) {
                    for (h in conAjustes) ajustes.restablecerSitio(h)
                    g.reinstalarAnuncios()
                    invalidar()
                    g.avisar("Ajustes por sitio restablecidos. Recarga las páginas")
                    repintar()
                }
            }
        }
    }

    // ---------------------------------------------------------------- un sitio

    private fun pintarSitio() {
        val host = sitio
        titulo(host)
        val info = infoDe(host)
        hoja.cifras(
            listOf(
                Pair(info.cookies.toString(), "cookies"),
                Pair(info.visitas.toString(), "visitas"),
                Pair(info.reglas.toString(), "reglas")
            )
        )

        hoja.seccion("Permisos")
        if (info.permisos.isEmpty()) {
            hoja.nota("Sin permisos guardados: el sitio te preguntará cada vez.")
        }
        for (t in info.permisos) {
            val resto = textoRestante(ajustes.restantePermiso(host, t))
            hoja.fila(etiquetaPermiso(t).replaceFirstChar { c -> c.uppercase() }, if (resto.isEmpty()) "Siempre en este sitio" else resto.trim(), "Revocar") {
                ajustes.quitarPermiso(host, t)
                invalidar()
                g.avisar("Permiso revocado: " + etiquetaPermiso(t))
                repintar()
            }
        }

        hoja.seccion("Cookies y almacenamiento")
        if (info.cookies > 0) {
            hoja.fila("Cookies", "Ver y borrar una a una", info.cookies.toString(), icono = "🍪") { ir(COOKIES) }
        } else {
            hoja.nota("Este sitio no tiene cookies guardadas.")
        }
        val abierta = g.paginaAbiertaEn(host)
        if (abierta != null) {
            hoja.fila("Almacenamiento de la página abierta", "localStorage, IndexedDB, Cache Storage…", icono = "🗄") {
                hoja.cerrar()
                g.almacenamientoDe(abierta)
            }
        }
        hoja.fila("Borrar cookies y almacenamiento", "Cierra tu sesión en este sitio; los demás no se tocan", aviso = true) {
            confirmar("Borrar datos de " + host, "Se cerrará tu sesión en este sitio y se borrará su almacenamiento local. Los demás sitios no se tocan.", "Borrar") {
                limpieza.borrarDatosSitio(host)
                invalidar()
                g.avisar("Datos de " + host + " borrados. Recarga la página")
                repintar()
            }
        }

        hoja.seccion("Conservar este sitio")
        Conservar.pintar(hoja, ajustes, host) {
            invalidar()
            repintar()
        }

        hoja.seccion("Protección en este sitio")
        if (ajustes.bloquearAnuncios) {
            hoja.interruptor("Bloqueo de anuncios", "Quítalo si el sitio no funciona bien", !ajustes.bloqueoPausadoEn(host)) { v ->
                ajustes.pausarBloqueoEn(host, !v)
                g.reinstalarAnuncios()
                invalidar()
                repintar()
            }
        } else {
            hoja.nota("El bloqueo de anuncios está desactivado para todos los sitios.")
        }
        hoja.fila("Ventanas emergentes", null, NombresSitio.EMERGENTES[ajustes.emergentesEn(host) + 1]) {
            Dialogos.elegir(act, paleta, privada, "Ventanas emergentes en " + host, NombresSitio.EMERGENTES, ajustes.emergentesEn(host) + 1) { i ->
                ajustes.fijarEmergentesEn(host, i - 1)
                invalidar()
                repintar()
            }
        }
        hoja.fila("Cookies de terceros", null, NombresSitio.COOKIES[ajustes.cookiesTercerosModoEn(host)]) {
            Dialogos.elegir(act, paleta, privada, "Cookies de terceros en " + host, NombresSitio.COOKIES, ajustes.cookiesTercerosModoEn(host)) { i ->
                ajustes.fijarCookiesTercerosEn(host, i)
                invalidar()
                repintar()
            }
        }
        hoja.fila("Imágenes", null, NombresSitio.IMAGENES[ajustes.imagenesEn(host)]) {
            Dialogos.elegir(act, paleta, privada, "Imágenes en " + host, NombresSitio.IMAGENES, ajustes.imagenesEn(host)) { i ->
                ajustes.fijarImagenesEn(host, i)
                invalidar()
                repintar()
            }
        }
        if (info.reglas > 0) {
            hoja.fila("Reglas de ocultar elementos", null, info.reglas.toString()) {
                hoja.cerrar()
                g.reglasDe(host)
            }
        }
        hoja.nota("Los cambios se aplican al recargar las páginas de este sitio.")

        if (info.visitas > 0) {
            hoja.seccion("Historial")
            hoja.fila("Borrar el historial de este sitio", null, info.visitas.toString() + (if (info.visitas == 1) " página" else " páginas"), aviso = true) {
                confirmar("Borrar historial de " + host, "Se borrarán " + info.visitas + " páginas de este sitio del historial.", "Borrar") {
                    limpieza.quitarHistorialDe(host)
                    invalidar()
                    g.avisar("Historial de " + host + " borrado")
                    repintar()
                }
            }
        }

        hoja.seccion("Olvidar este sitio")
        hoja.boton("Borrar todo de " + host + "…", true) {
            confirmar(
                "Olvidar " + host,
                "Se borrarán sus cookies y almacenamiento, sus permisos, sus ajustes propios y su historial. Las reglas de ocultar elementos y el CSS no se tocan.",
                "Olvidar"
            ) {
                limpieza.olvidarSitio(host)
                g.reinstalarAnuncios()
                invalidar()
                g.avisar(host + " olvidado. Recarga sus páginas")
                ir(SITIOS)
            }
        }
    }

    private fun pintarCookies() {
        val host = sitio
        titulo("Cookies de " + host)
        val lista = limpieza.cookiesDeSitio(host)
        if (lista.isEmpty()) {
            hoja.nota("Sin cookies guardadas para este sitio.")
            return
        }
        hoja.nota("Toca una para borrarla. El valor no se muestra.")
        for ((nombre, largo) in lista.take(60)) {
            hoja.fila(nombre, null, largo.toString() + " car.", icono = "🍪") {
                limpieza.borrarCookie(host, nombre)
                invalidar()
                g.avisar("Cookie borrada: " + nombre)
                repintar()
            }
        }
        if (lista.size > 60) hoja.nota("Se muestran 60 de " + lista.size + ".")
    }

    // ---------------------------------------------------------------- certificados

    private fun pintarCertificados() {
        titulo("Excepciones de certificado")
        val lista = ExcepcionesSsl.todas()
        if (lista.isEmpty()) {
            hoja.nota("No hay excepciones de certificado. Aparecen cuando autorizas un sitio a pesar de un aviso.")
            return
        }
        for (e in lista) {
            hoja.fila(
                e.sitio.substringBeforeLast(':'),
                Certificados.etiqueta(e.motivos) + "\n" + Certificados.vigencia(e) + " · " + Certificados.huellaCorta(e.huella),
                icono = "🔐"
            ) { detalleCertificado(e) }
        }
        hoja.boton("Revocar todas…", true) {
            confirmar(
                "Revocar todas",
                "Se olvidarán todas las excepciones de certificado. Los equipos con certificado no válido volverán a preguntar.",
                "Revocar"
            ) {
                ExcepcionesSsl.quitarTodas()
                limpieza.olvidarSsl()
                g.avisar("Excepciones revocadas. Recarga las páginas")
                repintar()
            }
        }
    }

    private fun detalleCertificado(e: ExcepcionSsl) {
        val fmt = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        val msg = "Autorizado a pesar de: " + Certificados.etiqueta(e.motivos) + "\n" +
            "Concedida: " + fmt.format(java.util.Date(e.concedida)) + "\n" +
            "Vigencia: " + Certificados.vigencia(e) + "\n\n" +
            "Huella SHA-256:\n" + e.huella + "\n\n" +
            "Solo vale para este certificado exacto. Si el equipo presenta otro, Optimo volverá a preguntar."
        val h = Hoja(act, paleta, privada)
        h.encabezado(e.sitio.substringBeforeLast(':'))
        h.parrafo(msg)
        h.boton("Revocar", true) {
            h.cerrar()
            ExcepcionesSsl.quitar(e)
            limpieza.olvidarSsl()
            g.avisar("Excepción revocada. Recarga la página")
            repintar()
        }
        h.boton("Copiar huella") {
            h.cerrar()
            g.copiar(e.huella)
        }
        h.boton("Cerrar") { h.cerrar() }
        h.mostrar()
    }
}
