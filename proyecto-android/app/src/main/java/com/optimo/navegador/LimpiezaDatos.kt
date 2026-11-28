package com.optimo.navegador

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.io.File

/** Dominio «base» aproximado (sin lista de sufijos públicos): example.com, example.co.uk. Las IP se comparan enteras. */
fun dominioBase(host: String): String {
    val h = host.lowercase().trimEnd('.')
    val partes = h.split('.')
    if (partes.size <= 2 || partes.all { x -> x.isNotEmpty() && x.all { c -> c.isDigit() } }) return h
    val ult = partes[partes.size - 1]
    val pen = partes[partes.size - 2]
    val n = if (ult.length == 2 && pen.length <= 3) 3 else 2
    return partes.takeLast(n).joinToString(".")
}

/** Lo que un sitio puede conservar frente a los borrados globales (suma de bits; ver [Ajustes.conservadoDe]). */
object Conservar {
    const val HISTORIAL = 1
    const val COOKIES = 2
    const val DATOS = 4
    const val AJUSTES = 8
    const val TODO = 15

    /** Bit, título y detalle de cada opción, en el orden en que se muestran. */
    val OPCIONES: List<Triple<Int, String, String>> = listOf(
        Triple(HISTORIAL, "Historial", "Sus páginas se quedan al borrar el historial (todo, por rango o por plazo)"),
        Triple(COOKIES, "Sesión (cookies)", "Sigues con la sesión iniciada después de un borrado global"),
        Triple(DATOS, "Datos locales", "localStorage, IndexedDB… del sitio (también de una aplicación web local)"),
        Triple(AJUSTES, "Permisos y ajustes", "Los permisos concedidos y los ajustes propios del sitio")
    )

    fun cuantos(bits: Int): Int = OPCIONES.count { (bits and it.first) != 0 }

    /** Texto corto para una fila: «No», «2 de 4»… */
    fun resumen(bits: Int): String = if (bits == 0) "No" else cuantos(bits).toString() + " de " + OPCIONES.size

    /** Los interruptores de un sitio. [alCambiar] se llama después de guardar cada cambio. */
    fun pintar(hoja: Hoja, ajustes: Ajustes, host: String, alCambiar: () -> Unit) {
        val bits = ajustes.conservadoDe(host)
        for (o in OPCIONES) {
            hoja.interruptor(o.second, o.third, (bits and o.first) != 0) { v ->
                val ahora = ajustes.conservadoDe(host)
                ajustes.fijarConservadoDe(host, if (v) ahora or o.first else ahora and o.first.inv())
                alCambiar()
            }
        }
        hoja.nota(
            "Lo que marques se salta los borrados globales: «Borrar todo», borrar al salir, borrar por rango o plazo del historial, " +
                "«Borrar cookies y datos de todos los sitios» y «Revocar todos los permisos». Si borras algo de este sitio a propósito, sí se borra. " +
                "La caché no es por sitio y se borra siempre."
        )
    }
}

fun tamanoDir(d: File): Long {
    var t = 0L
    try {
        val hijos = d.listFiles() ?: return d.length()
        for (h in hijos) t += if (h.isDirectory) tamanoDir(h) else h.length()
    } catch (e: Exception) {
        // Carpeta que desaparece mientras se cuenta: se devuelve lo contado.
    }
    return t
}

fun formatoTamano(b: Long): String =
    if (b < 1024L * 1024L) (b / 1024L).toString() + " KB" else String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1048576.0)

fun etiquetaPermiso(t: String): String = when (t) {
    "camara" -> "cámara"
    "microfono" -> "micrófono"
    "ubicacion" -> "ubicación"
    else -> t
}

fun textoRestante(ms: Long): String {
    if (ms <= 0L) return ""
    val min = (ms / 60000L).toInt()
    return if (min >= 60) " (caduca en " + (min / 60) + " h)" else " (caduca en " + maxOf(min, 1) + " min)"
}

/**
 * Borrados y limpiezas de datos de navegación (historial, caché, cookies, almacenamiento, certificados), sin interfaz.
 * Lo usan el centro de privacidad y MainActivity (borrado al salir, retención del historial).
 */
class LimpiezaDatos(
    private val ctx: Context,
    private val ajustes: Ajustes,
    private val historial: ListaPersistente,
    private val webs: () -> List<WebView>,
    private val alVaciarHistorial: () -> Unit
) {
    companion object {
        val ETIQUETAS_RETENCION = listOf("Siempre (sin límite)", "7 días", "30 días", "90 días")
        val DIAS_RETENCION = listOf(0, 7, 30, 90)
        val ETIQUETAS_RANGO = listOf("Última hora", "Hoy", "Últimos 7 días", "Últimos 30 días", "Todo")
    }

    // ---------------------------------------------------------------- historial

    fun etiquetaRetencion(d: Int): String {
        val i = DIAS_RETENCION.indexOf(d)
        return if (i >= 0) ETIQUETAS_RETENCION[i] else d.toString() + " días"
    }

    /** Borra del historial lo anterior al plazo elegido en «Conservar historial». Se llama al abrir y al volver a Optimo. */
    fun aplicarRetencion() {
        val d = ajustes.retencionHistorial
        if (d <= 0) return
        val limite = System.currentTimeMillis() - d * 86400000L
        if (!ajustes.hayConservados(Conservar.HISTORIAL)) {
            historial.quitarAnterioresA(limite)
        } else {
            quitarHistorialSi { e -> e.tiempo > 0L && e.tiempo < limite }
        }
    }

    private fun hostDe(e: Entrada): String = ReglasUsuario.clave(Omnibox.hostDe(e.url))

    /** Quita del historial lo que cumpla [cond], salvo lo de los sitios que conservan su historial. */
    private fun quitarHistorialSi(cond: (Entrada) -> Boolean) {
        var n = 0
        for (e in historial.todos().toList()) {
            if (cond(e) && !ajustes.conservaHost(hostDe(e), Conservar.HISTORIAL)) {
                historial.quitar(e.url)
                n++
            }
        }
        if (n > 0) historial.guardarAhora()
    }

    /** Entradas que borraría un borrado desde [desde] (ms; 0 = todo), sin las de los sitios que conservan su historial. */
    fun contarDesde(desde: Long): Int =
        historial.todos().count { e -> e.tiempo >= desde && !ajustes.conservaHost(hostDe(e), Conservar.HISTORIAL) }

    /** Entradas que borraría la retención con ese [limite] (ms), sin las de los sitios que conservan su historial. */
    fun contarAnterioresA(limite: Long): Int =
        historial.todos().count { e -> e.tiempo > 0L && e.tiempo < limite && !ajustes.conservaHost(hostDe(e), Conservar.HISTORIAL) }

    /** Instante (ms) desde el que se borra para cada opción de [ETIQUETAS_RANGO]; 0 = todo. */
    fun inicioRango(i: Int): Long {
        val ahora = System.currentTimeMillis()
        return when (i) {
            0 -> ahora - 3600000L
            1 -> {
                val c = java.util.Calendar.getInstance()
                c.set(java.util.Calendar.HOUR_OF_DAY, 0)
                c.set(java.util.Calendar.MINUTE, 0)
                c.set(java.util.Calendar.SECOND, 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                c.timeInMillis
            }
            2 -> ahora - 7 * 86400000L
            3 -> ahora - 30 * 86400000L
            else -> 0L
        }
    }

    fun vaciarHistorial() {
        Miniaturas.vaciar()
        if (!ajustes.hayConservados(Conservar.HISTORIAL)) historial.vaciar() else quitarHistorialSi { true }
        alVaciarHistorial()
    }

    fun quitarHistorialDesde(desde: Long) {
        if (!ajustes.hayConservados(Conservar.HISTORIAL)) historial.quitarDesde(desde) else quitarHistorialSi { e -> e.tiempo >= desde }
        alVaciarHistorial()
    }

    /** Quita del historial todas las páginas de un sitio (clave sin «www.»). */
    fun quitarHistorialDe(sitio: String) {
        for (e in historial.todos().toList()) {
            if (ReglasUsuario.clave(Omnibox.hostDe(e.url)) == sitio) historial.quitar(e.url)
        }
        alVaciarHistorial()
    }

    // ---------------------------------------------------------------- caché, cookies y almacenamiento

    fun borrarCache() {
        var hecho = false
        for (w in webs()) {
            w.clearCache(true)
            hecho = true
            break
        }
        if (!hecho) {
            try {
                File(ctx.cacheDir, "WebView").deleteRecursively()
            } catch (e: Exception) {
                // Sin caché que borrar.
            }
        }
    }

    fun tamanoCache(): Long = tamanoDir(ctx.cacheDir)

    /** Sitios que Optimo conoce (páginas abiertas, historial, permisos, ajustes y reglas): los únicos que se pueden borrar de uno en uno. */
    private fun hostsConocidos(): List<String> {
        val r = LinkedHashSet<String>()
        fun a(h: String) {
            val k = ReglasUsuario.clave(h)
            if (k.isNotEmpty()) r.add(k)
        }
        for (w in webs()) {
            val u = try { w.url } catch (e: Exception) { null } ?: continue
            a(Omnibox.hostDe(u))
        }
        for (e in historial.todos()) a(Omnibox.hostDe(e.url))
        for (h in ajustes.sitiosConPermisos()) a(h)
        for (h in ajustes.sitiosConAjustes()) a(h)
        for (h in ReglasUsuario.hostsConReglas()) a(h)
        return ArrayList(r)
    }

    private fun borrarOrigenes(host: String) {
        try {
            WebStorage.getInstance().deleteOrigin("https://" + host)
            WebStorage.getInstance().deleteOrigin("http://" + host)
        } catch (e: Exception) {
            // Origen desconocido para el WebView.
        }
    }

    /**
     * Cookies y almacenamiento local de todos los sitios (perfil normal). Si algún sitio conserva su sesión o sus datos locales, se borra
     * sitio por sitio (los que Optimo conoce: Android no permite listar el resto) saltándose esos. [hosts] permite calcular la lista antes
     * de vaciar el historial, de donde sale en buena parte.
     */
    fun borrarCookiesYAlmacenamiento(hosts: List<String>? = null) {
        if (!ajustes.hayConservados(Conservar.COOKIES or Conservar.DATOS)) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            return
        }
        for (h in hosts ?: hostsConocidos()) {
            if (!ajustes.conservaHost(h, Conservar.COOKIES)) {
                for (c in cookiesDeSitio(h)) borrarCookie(h, c.first)
            }
            if (!ajustes.conservaHost(h, Conservar.DATOS)) borrarOrigenes(h)
        }
        CookieManager.getInstance().flush()
    }

    /** Historial, excepciones de certificado, cookies, almacenamiento y caché de la página abierta. */
    fun borrarDatosNavegacion() {
        val hosts = if (ajustes.hayConservados(Conservar.COOKIES or Conservar.DATOS)) hostsConocidos() else null
        ListaDescargas.vaciar()
        vaciarHistorial()
        ExcepcionesSsl.quitarTodas()
        olvidarSsl()
        borrarCookiesYAlmacenamiento(hosts)
        webs().firstOrNull()?.clearCache(true)
    }

    fun olvidarSsl() {
        for (w in webs()) {
            try {
                w.clearSslPreferences()
            } catch (e: Exception) {
                // Sin WebView o ya destruido.
            }
        }
    }

    /**
     * Limpieza de «Al salir, borrar…». Se llama al cerrar la app (onDestroy con la actividad terminando) y, si el proceso murió
     * antes de poder hacerlo (quitarla de recientes, Android que la cierra), al abrirla de nuevo. La marca [Ajustes.salidaPendiente]
     * solo se quita cuando el borrado de cookies termina de verdad.
     */
    fun limpiarAlSalir() {
        val m = ajustes.borrarAlSalir
        if (m == 0) {
            ajustes.salidaPendiente = false
            return
        }
        val conservan = ajustes.hayConservados(Conservar.COOKIES or Conservar.DATOS)
        // La lista de sitios sale en buena parte del historial: se calcula antes de vaciarlo.
        val hosts = if ((m and 1) != 0 && conservan) hostsConocidos() else null
        if ((m and 2) != 0) vaciarHistorial()
        if ((m and 4) != 0) borrarCache()
        if ((m and 1) != 0 && conservan) {
            borrarCookiesYAlmacenamiento(hosts)
            ajustes.salidaPendiente = false
        } else if ((m and 1) != 0) {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies { _ -> ajustes.salidaPendiente = false }
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        } else {
            ajustes.salidaPendiente = false
        }
    }

    // ---------------------------------------------------------------- por sitio

    /** Dominios con los que se prueban las cookies de un sitio: él mismo, su dominio base y «www.». */
    private fun hostsCookies(host: String): List<String> {
        val base = dominioBase(host)
        val hosts = LinkedHashSet<String>()
        hosts.add(host)
        hosts.add(base)
        hosts.add("www." + base)
        return ArrayList(hosts)
    }

    /** Nombre y tamaño del valor de cada cookie del sitio (el valor no se muestra). Solo el perfil normal. */
    fun cookiesDeSitio(host: String): List<Pair<String, Int>> {
        val cm = CookieManager.getInstance()
        val res = LinkedHashMap<String, Int>()
        for (x in hostsCookies(host)) {
            val cookies = cm.getCookie("https://" + x + "/") ?: continue
            for (par in cookies.split(";")) {
                val nombre = par.substringBefore('=').trim()
                if (nombre.isEmpty() || res.containsKey(nombre)) continue
                res[nombre] = par.substringAfter('=', "").length
            }
        }
        return res.entries.map { Pair(it.key, it.value) }
    }

    fun borrarCookie(host: String, nombre: String) {
        val cm = CookieManager.getInstance()
        for (x in hostsCookies(host)) {
            val url = "https://" + x + "/"
            for (dom in listOf("", "; Domain=" + x, "; Domain=." + x)) {
                cm.setCookie(url, nombre + "=; Max-Age=0; Path=/" + dom)
            }
        }
        cm.flush()
    }

    /** Cookies (del sitio y de su dominio base) y almacenamiento local de un sitio. Solo el perfil normal. */
    fun borrarDatosSitio(host: String) {
        for (c in cookiesDeSitio(host)) borrarCookie(host, c.first)
        borrarOrigenes(host)
    }

    /**
     * «Olvidar este sitio»: cookies y almacenamiento, permisos, ajustes propios e historial del sitio.
     * Las reglas de ocultar elementos y el CSS propio no se tocan.
     */
    fun olvidarSitio(host: String) {
        borrarDatosSitio(host)
        for (t in ajustes.permisosDe(host)) ajustes.quitarPermiso(host, t)
        ajustes.restablecerSitio(host)
        quitarHistorialDe(host)
    }
}
