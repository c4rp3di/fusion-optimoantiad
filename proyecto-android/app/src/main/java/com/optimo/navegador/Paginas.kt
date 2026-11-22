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
            "a.p{font-size:12px;color:" + c.tenue + ";text-decoration:none;opacity:.8}" +
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

    // ---------------------------------------------------------------- pantalla de inicio editable

    /** Apariencia y contenido de la página de inicio (se guardan en Ajustes; ver «Pantalla de inicio…»). */
    class ConfigInicio(
        val titulo: String,
        val subtitulo: String,
        val buscador: Boolean,
        val accesos: Boolean,
        val maxAccesos: Int,
        val cuadrados: Boolean,
        val enlacePersonalizar: Boolean
    )

    /** Tema de colores de la página de inicio. [colores] es null en «Automático» (sigue al sistema) y «Personalizado». */
    class TemaInicio(val id: String, val nombre: String, val colores: Colores?)

    val TEMAS_INICIO: List<TemaInicio> = listOf(
        TemaInicio("auto", "Automático (claro u oscuro según el sistema)", null),
        TemaInicio("claro", "Claro", Colores("#FFFFFF", "#202124", "#F1F3F4", "#00897B", "#5F6368")),
        TemaInicio("oscuro", "Oscuro", Colores("#121418", "#E8EAED", "#1E2229", "#00BFA5", "#9AA0A6")),
        TemaInicio("negro", "Negro (AMOLED)", Colores("#000000", "#F1F1F1", "#15171A", "#00BFA5", "#8E9399")),
        TemaInicio("oceano", "Océano", Colores("#0B1F33", "#E6F1FB", "#12304D", "#4FC3F7", "#8FB3CF")),
        TemaInicio("bosque", "Bosque", Colores("#10201A", "#E8F3EC", "#1A3328", "#7BD389", "#93B3A0")),
        TemaInicio("atardecer", "Atardecer", Colores("#2A1620", "#FBEAEF", "#3C2030", "#FF8A65", "#C9A3B0")),
        TemaInicio("papel", "Papel", Colores("#FAF4E8", "#3B3226", "#EFE6D2", "#B5651D", "#7C705D")),
        TemaInicio("personalizado", "Personalizado (eliges cada color)", null)
    )

    private val RE_HEX = Regex("^#[0-9A-Fa-f]{6}$")

    /** "#RRGGBB" válido o null. Acepta con o sin «#» y la forma corta de 3 cifras. Los colores van a CSS: solo pasan los válidos. */
    fun normalizarHex(texto: String): String? {
        var t = texto.trim()
        if (t.startsWith("#")) t = t.substring(1)
        if (t.length == 3 && t.all { it.isLetterOrDigit() }) t = "" + t[0] + t[0] + t[1] + t[1] + t[2] + t[2]
        val r = "#" + t.uppercase()
        return if (RE_HEX.matches(r)) r else null
    }

    /** Mezcla dos colores "#RRGGBB": 0 = a, 1 = b. Sirve para derivar el color atenuado del texto. */
    fun mezclar(a: String, b: String, t: Double): String {
        fun canal(s: String, i: Int): Int = s.substring(1 + 2 * i, 3 + 2 * i).toInt(16)
        val sb = StringBuilder("#")
        for (i in 0..2) {
            val v = Math.round(canal(a, i) * (1 - t) + canal(b, i) * t).toInt().coerceIn(0, 255)
            sb.append(String.format("%02X", v))
        }
        return sb.toString()
    }

    /** Colores a partir de los cuatro elegidos a mano; el atenuado sale de mezclar texto y fondo. */
    fun coloresPersonalizados(fondo: String, texto: String, acento: String, superficie: String): Colores =
        Colores(fondo, texto, superficie, acento, mezclar(texto, fondo, 0.4))

    /** Pagina de nueva pestaña: buscador y accesos directos (marcadores, propios o predeterminados), todo opcional. */
    fun inicio(c: Colores, motor: Motor, atajos: List<Pair<String, String>>, cfg: ConfigInicio): String {
        val sb = StringBuilder()
        sb.append(cabecera(c, "Optimo"))
        if (cfg.cuadrados) sb.append("<style>.i{border-radius:14px}</style>")
        if (cfg.titulo.isNotBlank()) sb.append("<h1>").append(esc(cfg.titulo)).append("</h1>")
        else sb.append("<div style=\"height:40px\"></div>")
        if (cfg.subtitulo.isNotBlank()) sb.append("<p>").append(esc(cfg.subtitulo)).append("</p>")
        if (cfg.buscador) {
            sb.append("<form action=\"").append(esc(motor.accion())).append("\" method=\"get\">")
            sb.append("<input type=\"search\" name=\"").append(esc(motor.parametro()))
            sb.append("\" placeholder=\"Buscar con ").append(esc(motor.nombre))
            sb.append("\" autocomplete=\"off\"></form>")
        }
        if (cfg.accesos) {
            sb.append("<div class=\"g\">")
            var n = 0
            for ((titulo, url) in atajos) {
                if (n >= cfg.maxAccesos) break
                // Solo direcciones web y marcadores de archivos locales (optimo://local/…, los abre MainActivity).
                if (!(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("optimo://local/"))) continue
                val nombre = if (titulo.isNotBlank()) titulo.trim() else Omnibox.hostDe(url)
                val inicial = nombre.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                sb.append("<a class=\"a\" href=\"").append(esc(url)).append("\">")
                sb.append("<span class=\"i\">").append(esc(inicial)).append("</span>")
                sb.append("<span class=\"t\">").append(esc(nombre)).append("</span></a>")
                n++
            }
            sb.append("</div>")
        }
        if (cfg.enlacePersonalizar) {
            sb.append("<p style=\"margin-top:36px\"><a class=\"p\" href=\"").append(URL_PERSONALIZAR)
            sb.append("\">⚙ Personalizar esta pantalla</a></p>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    /** Enlace interno de la página de inicio que abre sus ajustes (lo recoge MainActivity). */
    const val URL_PERSONALIZAR = "optimo://personalizar"

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

