package com.optimo.navegador

/** Colores como cadenas "#RRGGBB" para las paginas internas. */
class Colores(
    val fondo: String,
    val texto: String,
    val superficie: String,
    val acento: String,
    val tenue: String
)

/** Paginas internas (inicio y error) generadas como HTML estatico, sin JavaScript. */
object Paginas {

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun estilo(c: Colores): String =
        "*{box-sizing:border-box}" +
            "body{margin:0;padding:24px 20px;background:" + c.fondo + ";color:" + c.texto +
            ";font-family:sans-serif;text-align:center}" +
            "h1{font-size:34px;margin:56px 0 4px;letter-spacing:1px;color:" + c.acento + "}" +
            "p{color:" + c.tenue + ";margin:6px 0 22px}" +
            "form{max-width:520px;margin:0 auto 28px}" +
            "input{width:100%;padding:14px 18px;font-size:17px;border:0;border-radius:28px;" +
            "background:" + c.superficie + ";color:" + c.texto + ";outline:none}" +
            ".g{display:flex;flex-wrap:wrap;justify-content:center;max-width:520px;margin:0 auto}" +
            ".a{width:25%;min-width:76px;padding:10px 4px;text-decoration:none;color:" + c.texto + "}" +
            ".i{display:block;width:48px;height:48px;line-height:48px;margin:0 auto 6px;border-radius:50%;" +
            "background:" + c.superficie + ";color:" + c.acento + ";font-size:22px;font-weight:bold}" +
            ".t{display:block;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
            "a.b{display:inline-block;margin-top:8px;padding:12px 26px;border-radius:24px;" +
            "background:" + c.acento + ";color:" + c.fondo + ";text-decoration:none;font-weight:bold}"

    private fun cabecera(c: Colores, titulo: String): String =
        "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>" + esc(titulo) + "</title><style>" + estilo(c) + "</style></head><body>"

    /** Titulo de la pagina de error: el historial lo usa para no guardar paginas de error. */
    const val TITULO_ERROR = "No se pudo cargar la página"

    val ATAJOS_POR_DEFECTO: List<Pair<String, String>> = listOf(
        Pair("Wikipedia", "https://es.wikipedia.org/"),
        Pair("YouTube", "https://m.youtube.com/"),
        Pair("GitHub", "https://github.com/"),
        Pair("OpenStreetMap", "https://www.openstreetmap.org/")
    )

    /** Pagina de nueva pestaña: buscador y accesos directos (marcadores). */
    fun inicio(c: Colores, motor: Motor, atajos: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.append(cabecera(c, "Optimo"))
        sb.append("<h1>Optimo</h1><p>Navega rápido y con control.</p>")
        sb.append("<form action=\"").append(esc(motor.accion())).append("\" method=\"get\">")
        sb.append("<input type=\"search\" name=\"").append(esc(motor.parametro()))
        sb.append("\" placeholder=\"Buscar con ").append(esc(motor.nombre))
        sb.append("\" autocomplete=\"off\"></form><div class=\"g\">")
        var n = 0
        for ((titulo, url) in atajos) {
            if (n >= 8) break
            if (!(url.startsWith("http://") || url.startsWith("https://"))) continue
            val nombre = if (titulo.isNotBlank()) titulo.trim() else Omnibox.hostDe(url)
            val inicial = nombre.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            sb.append("<a class=\"a\" href=\"").append(esc(url)).append("\">")
            sb.append("<span class=\"i\">").append(esc(inicial)).append("</span>")
            sb.append("<span class=\"t\">").append(esc(nombre)).append("</span></a>")
            n++
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    /** Pagina de error de carga con enlace para reintentar. */
    fun error(c: Colores, url: String, descripcion: String): String {
        val sb = StringBuilder()
        sb.append(cabecera(c, TITULO_ERROR))
        sb.append("<h1>No se pudo cargar</h1>")
        sb.append("<p>").append(esc(url)).append("</p>")
        sb.append("<p>").append(esc(descripcion)).append("</p>")
        sb.append("<p>Comprueba tu conexión o la dirección e inténtalo de nuevo.</p>")
        sb.append("<a class=\"b\" href=\"").append(esc(url)).append("\">Reintentar</a>")
        sb.append("</body></html>")
        return sb.toString()
    }
}
