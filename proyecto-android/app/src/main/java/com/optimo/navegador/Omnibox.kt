package com.optimo.navegador

import java.net.URLEncoder

/** Buscador seleccionable. [base] termina en "?q=" (o equivalente) y se le añade la consulta codificada. */
class Motor(val id: String, val nombre: String, val base: String) {
    /** URL del formulario de busqueda (sin la consulta). */
    fun accion(): String = base.substringBefore('?')

    /** Nombre del parametro de la consulta (por defecto "q"). */
    fun parametro(): String {
        val q = base.substringAfter('?', "q=")
        val nombre = q.substringBefore('=')
        return if (nombre.isEmpty()) "q" else nombre
    }
}

/**
 * Resolucion del texto del omnibox (reglas del omnibox de NIA, simplificadas y sin
 * dependencias): vacio; http/https explicito; esquema externo (mailto, tel...);
 * esquema bloqueado; parece dominio (https implicito, http en redes locales); si no, busqueda.
 */
object Omnibox {

    val MOTORES: List<Motor> = listOf(
        Motor("ddg", "DuckDuckGo", "https://duckduckgo.com/?q="),
        Motor("google", "Google", "https://www.google.com/search?q="),
        Motor("bing", "Bing", "https://www.bing.com/search?q="),
        Motor("brave", "Brave Search", "https://search.brave.com/search?q="),
        Motor("ecosia", "Ecosia", "https://www.ecosia.org/search?q=")
    )

    fun motor(id: String): Motor {
        for (m in MOTORES) {
            if (m.id == id) return m
        }
        return MOTORES[0]
    }

    sealed class Resolucion {
        object Vacio : Resolucion()
        class Navegar(val url: String) : Resolucion()
        class Buscar(val url: String) : Resolucion()
        class Externo(val url: String) : Resolucion()
        class NoSoportado(val esquema: String) : Resolucion()
    }

    private val EXTERNOS = setOf("mailto", "tel", "sms", "geo")
    private val RE_ESQUEMA = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*$")
    private val CONOCIDOS = setOf(
        "about", "file", "chrome", "javascript", "data", "view-source", "mailto",
        "ftp", "ws", "wss", "blob", "tel", "sms", "geo", "intent", "content"
    )

    fun resolver(texto: String, motor: Motor): Resolucion {
        val t = texto.trim()
        if (t.isEmpty()) return Resolucion.Vacio
        val esquema = esquemaExplicito(t)
        if (esquema == "http" || esquema == "https") {
            return if (hostDe(t).isNotEmpty()) {
                Resolucion.Navegar(t)
            } else {
                Resolucion.Buscar(motor.base + codificar(t))
            }
        }
        if (esquema != null) {
            return if (esquema in EXTERNOS) Resolucion.Externo(t) else Resolucion.NoSoportado(esquema)
        }
        if (!pareceDominio(t)) return Resolucion.Buscar(motor.base + codificar(t))
        val prefijo = if (esLocal(hostDe(t))) "http://" else "https://"
        return Resolucion.Navegar(prefijo + t)
    }

    /** Esquema explicito en minusculas, o null. "host:puerto" no es un esquema. */
    fun esquemaExplicito(texto: String): String? {
        val i = texto.indexOf(':')
        if (i <= 0) return null
        val candidato = texto.substring(0, i)
        if (!RE_ESQUEMA.matches(candidato)) return null
        val min = candidato.lowercase()
        if (min == "http" || min == "https") return min
        if (texto.substring(i + 1).startsWith("//")) return min
        return if (min in CONOCIDOS) min else null
    }

    /** Host de una URL o de un texto sin esquema ("example.com:80/ruta" -> "example.com"). */
    fun hostDe(texto: String): String {
        var s = texto
        val i = s.indexOf("://")
        if (i >= 0) s = s.substring(i + 3)
        var fin = s.length
        for (c in charArrayOf('/', '?', '#')) {
            val k = s.indexOf(c)
            if (k in 0 until fin) fin = k
        }
        s = s.substring(0, fin)
        val arroba = s.lastIndexOf('@')
        if (arroba >= 0) s = s.substring(arroba + 1)
        if (s.startsWith("[")) {
            val c = s.indexOf(']')
            return if (c > 0) s.substring(0, c + 1) else ""
        }
        val p = s.indexOf(':')
        if (p >= 0) s = s.substring(0, p)
        return s
    }

    /** Sin espacios, sin arroba (correo), localhost, IP o host con punto (no "3.14"). */
    fun pareceDominio(texto: String): Boolean {
        if (texto.any { it <= ' ' }) return false
        val autoridad = texto.substringBefore('/').substringBefore('?').substringBefore('#')
        if (autoridad.contains('@')) return false
        if (autoridad.startsWith("[")) return true
        val host = hostDe(texto)
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        if (!host.contains('.')) return false
        val partes = host.split('.')
        if (partes.any { it.isEmpty() }) return false
        if (partes.all { p -> p.all { it.isDigit() } }) return partes.size == 4
        return true
    }

    /** Hosts a los que se accede por http por defecto (red local). */
    fun esLocal(host: String): Boolean {
        if (host == "localhost" || host.startsWith("[") || host.endsWith(".local")) return true
        val partes = host.split('.')
        return partes.size == 4 && partes.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }
    }

    fun codificar(q: String): String = URLEncoder.encode(q, "UTF-8")
}
