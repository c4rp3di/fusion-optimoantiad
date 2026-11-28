package com.optimo.navegador

import android.content.Context
import android.content.SharedPreferences

/**
 * Un botón del catálogo común a las dos barras. [accion] es la acción del menú que ejecuta (los que no tienen la resuelve MainActivity por id).
 * [texto] es el tamaño del símbolo cuando el botón se reparte el ancho de la fila (tamaño 0).
 */
class ElementoBarra(
    val id: String,
    val nombre: String,
    val icono: String,
    val tamanoDefecto: Int,
    val grupo: String,
    val accion: AccionMenu? = null,
    val texto: Float = 18f
)

/** Un botón colocado en una fila: [tamano] es su ancho en dp (el símbolo se escala con él), o 0 para repartirse el ancho que sobra. */
class EntradaBarra(val id: String, var tamano: Int)

/** Una de las dos barras: sus filas (cada una con sus botones, de izquierda a derecha), el alto de cada fila y la separación. */
class Barra(val id: String) {
    var filas: MutableList<MutableList<EntradaBarra>> = ArrayList()
    var alto: Int = 40
    var separacion: Int = 0
    val arriba: Boolean get() = id == BarraConfig.SUP
}

/**
 * Configuración de las dos barras de Optimo (Ajustes › Aspecto › «Barras»): la superior (con el campo de dirección) y la inferior.
 * Comparten un único catálogo de botones; cada botón está en una sola barra y fila, o en ninguna. Cada barra puede tener hasta
 * [MAX_FILAS] filas, su propio alto y separación, y se pliega entera. Por defecto son las de siempre. La barra inferior puede quedarse vacía
 * (entonces no se ve); el campo de dirección solo va en la superior y no se puede quitar, y tampoco el botón de menú (se puede mover).
 * Se guarda en preferencias propias; sin datos personales.
 */
object BarraConfig {
    const val SUP = "sup"
    const val INF = "inf"
    const val OMNIBOX = "omnibox"
    const val MENU = "menu"
    const val TAM_MIN = 24
    const val TAM_MAX = 60
    const val SEP_MAX = 16
    const val ALTO_MIN = 32
    const val ALTO_MAX = 64
    const val MAX_FILAS = 3

    const val G_NAV = "Navegación"
    const val G_PEST = "Pestañas y menú"
    const val G_PAG = "Esta página"
    const val G_GUARD = "Guardados"
    const val G_HERR = "Herramientas"
    const val G_OPTIMO = "Optimo"

    val GRUPOS: List<String> = listOf(G_NAV, G_PEST, G_PAG, G_GUARD, G_HERR, G_OPTIMO)

    val ELEMENTOS: List<ElementoBarra> = listOf(
        ElementoBarra("atras", "Atrás", "←", 36, G_NAV, null, 20f),
        ElementoBarra("adelante", "Adelante", "→", 36, G_NAV, null, 20f),
        ElementoBarra("recargar", "Recargar / parar", "↻", 36, G_NAV, null, 20f),
        ElementoBarra("inicio", "Pantalla de inicio", "⌂", 36, G_NAV, AccionMenu.IR_INICIO),
        ElementoBarra("subir", "Subir al principio de la página", "⤒", 36, G_NAV),
        ElementoBarra("bajar", "Bajar al final de la página", "⤓", 36, G_NAV),

        ElementoBarra("pestanas", "Pestañas (con contador)", "▢", 36, G_PEST),
        ElementoBarra("nueva", "Pestaña nueva", "＋", 36, G_PEST, AccionMenu.NUEVA, 20f),
        ElementoBarra("privada", "Pestaña privada", "🕶", 36, G_PEST, AccionMenu.PRIVADA),
        ElementoBarra("cerrar", "Cerrar esta pestaña", "✕", 36, G_PEST),
        ElementoBarra("reabrir", "Reabrir la última cerrada", "↺", 36, G_PEST, AccionMenu.REABRIR),
        ElementoBarra("menu", "Menú de Optimo", "⋮", 36, G_PEST, null, 22f),

        ElementoBarra("candado", "Candado (conexión)", "🔒", 32, G_PAG),
        ElementoBarra("escudo", "Escudo (protección)", "🛡", 36, G_PAG),
        ElementoBarra("altavoz", "Altavoz (si suena)", "🔊", 36, G_PAG),
        ElementoBarra("marcador", "Marcar la página (☆)", "☆", 36, G_PAG, null, 20f),
        ElementoBarra("compartir", "Compartir la página", "⤴", 36, G_PAG, AccionMenu.COMPARTIR),
        ElementoBarra("copiar", "Copiar la dirección", "⧉", 36, G_PAG, AccionMenu.COPIAR_DIRECCION),
        ElementoBarra("buscar", "Buscar en la página", "🔎", 36, G_PAG, AccionMenu.BUSCAR_PAGINA),
        ElementoBarra("leer", "Modo lectura", "📖", 36, G_PAG, AccionMenu.LEER),
        ElementoBarra("escritorio", "Sitio de escritorio (sí / no)", "🖥", 36, G_PAG, AccionMenu.ESCRITORIO),
        ElementoBarra("oscurecer", "Oscurecer páginas (sí / no)", "🌙", 36, G_PAG, AccionMenu.OSCURECER),
        ElementoBarra("ocultar", "Ocultar un elemento (tocar)", "👆", 36, G_PAG, AccionMenu.OCULTAR_ELEMENTO),
        ElementoBarra("capa", "Quitar capa que tapa la página", "🧹", 36, G_PAG, AccionMenu.QUITAR_CAPA),
        ElementoBarra("guardar", "Guardar la página sin conexión", "💾", 36, G_PAG, AccionMenu.GUARDAR_SIN_CONEXION),
        ElementoBarra("pantalla", "Pantalla completa", "⛶", 36, G_PAG, AccionMenu.PANTALLA),

        ElementoBarra("marcadores", "Marcadores", "🔖", 36, G_GUARD, AccionMenu.MARCADORES),
        ElementoBarra("historial", "Historial", "🕘", 36, G_GUARD, AccionMenu.HISTORIAL),
        ElementoBarra("descargas", "Descargas", "⬇", 36, G_GUARD, AccionMenu.DESCARGAS),
        ElementoBarra("guardadas", "Páginas guardadas", "📁", 36, G_GUARD, AccionMenu.PAGINAS_GUARDADAS),

        ElementoBarra("devtools", "Herramientas de desarrollo", "🛠", 36, G_HERR, AccionMenu.HERRAMIENTAS),
        ElementoBarra("enlaces", "Buscar enlaces en la página", "🔗", 36, G_HERR, AccionMenu.BUSCAR_ENLACES),
        ElementoBarra("rastrear", "Rastrear el sitio", "🕸", 36, G_HERR, AccionMenu.RASTREAR),
        ElementoBarra("medio", "Reproducir el vídeo detectado", "▶", 36, G_HERR, AccionMenu.REPRODUCIR_MEDIO),

        ElementoBarra("centro", "Centro de protección", "🔰", 36, G_OPTIMO, AccionMenu.CENTRO),
        ElementoBarra("aspecto", "Aspecto y barras", "🎨", 36, G_OPTIMO, AccionMenu.ASPECTO)
    )

    private const val DEF_SUP = "candado:32,omnibox:0,escudo:36,altavoz:36,marcadores:36,pantalla:36"
    private const val DEF_INF = "atras:0,adelante:0,recargar:0,marcador:0,pestanas:0,menu:0"

    private var sp: SharedPreferences? = null

    val sup = Barra(SUP)
    val inf = Barra(INF)

    /** Contador de bloqueos sobre el escudo. */
    var contador: Boolean = true
        private set

    fun iniciar(c: Context) {
        val p = c.getSharedPreferences("optimo_barra", Context.MODE_PRIVATE)
        sp = p
        contador = p.getBoolean("contador", true)
        // Los ajustes de la única barra de las versiones anteriores pasan a la superior.
        sup.alto = p.getInt("alto_sup", p.getInt("alto", 40)).coerceIn(ALTO_MIN, ALTO_MAX)
        sup.separacion = p.getInt("sep_sup", p.getInt("sep", 0)).coerceIn(0, SEP_MAX)
        inf.alto = p.getInt("alto_inf", 48).coerceIn(ALTO_MIN, ALTO_MAX)
        inf.separacion = p.getInt("sep_inf", 0).coerceIn(0, SEP_MAX)
        val fs = p.getString("f_sup", null)
        val fi = p.getString("f_inf", null)
        if (fs == null && fi == null) {
            val viejo = p.getString("barra", null)
            sup.filas = if (viejo != null) migrar(viejo) else leerFilas(DEF_SUP)
            inf.filas = leerFilas(DEF_INF)
        } else {
            sup.filas = leerFilas(fs ?: DEF_SUP)
            inf.filas = leerFilas(fi ?: "")
        }
        normalizar()
    }

    fun definicion(id: String): ElementoBarra? = ELEMENTOS.firstOrNull { it.id == id }

    fun barra(id: String): Barra = if (id == INF) inf else sup

    private fun tamanoValido(t: Int): Int = if (t <= 0) 0 else t.coerceIn(TAM_MIN, TAM_MAX)

    /** Formato: filas separadas por «|», botones por «,», cada uno «id:tamaño». Lo desconocido se ignora. */
    private fun leerFilas(texto: String): MutableList<MutableList<EntradaBarra>> {
        val r = ArrayList<MutableList<EntradaBarra>>()
        for (f in texto.split('|')) {
            val fila = ArrayList<EntradaBarra>()
            for (parte in f.split(',')) {
                val c = parte.split(':')
                val id = c.getOrNull(0)?.trim() ?: continue
                if (id != OMNIBOX && definicion(id) == null) continue
                fila.add(EntradaBarra(id, tamanoValido(c.getOrNull(1)?.toIntOrNull() ?: 0)))
            }
            if (fila.isNotEmpty()) r.add(fila)
        }
        return r.take(MAX_FILAS).toMutableList()
    }

    /** Formato de las versiones anteriores («id:visible:tamaño»): solo pasan los botones que se veían, en su orden. */
    private fun migrar(texto: String): MutableList<MutableList<EntradaBarra>> {
        val fila = ArrayList<EntradaBarra>()
        for (parte in texto.split(',')) {
            val c = parte.split(':')
            val id = c.getOrNull(0)?.trim() ?: continue
            if (id != OMNIBOX && definicion(id) == null) continue
            if (id != OMNIBOX && c.getOrNull(1) == "0") continue
            fila.add(EntradaBarra(id, if (id == OMNIBOX) 0 else tamanoValido(c.getOrNull(2)?.toIntOrNull() ?: 36)))
        }
        val r = ArrayList<MutableList<EntradaBarra>>()
        if (fila.isNotEmpty()) r.add(fila)
        return r
    }

    /** Sin repetidos entre las dos barras, sin filas vacías, el campo de dirección arriba y el menú en alguna parte. */
    private fun normalizar() {
        val vistos = HashSet<String>()
        for (b in listOf(sup, inf)) {
            for (fila in b.filas) {
                val iter = fila.iterator()
                while (iter.hasNext()) {
                    val e = iter.next()
                    if (!vistos.add(e.id) || (e.id == OMNIBOX && b === inf)) iter.remove()
                }
            }
            b.filas.removeAll { it.isEmpty() }
            while (b.filas.size > MAX_FILAS) b.filas.removeAt(b.filas.size - 1)
        }
        if (!vistos.contains(OMNIBOX)) {
            if (sup.filas.isEmpty()) sup.filas.add(ArrayList())
            sup.filas[0].add(0, EntradaBarra(OMNIBOX, 0))
        }
        if (!vistos.contains(MENU)) {
            if (inf.filas.isEmpty()) inf.filas.add(ArrayList())
            inf.filas[inf.filas.size - 1].add(EntradaBarra(MENU, 0))
        }
    }

    private fun texto(b: Barra): String = b.filas.joinToString("|") { f -> f.joinToString(",") { e -> e.id + ":" + e.tamano } }

    private fun guardar() {
        val p = sp ?: return
        p.edit()
            .putString("f_sup", texto(sup)).putString("f_inf", texto(inf))
            .putInt("alto_sup", sup.alto).putInt("sep_sup", sup.separacion)
            .putInt("alto_inf", inf.alto).putInt("sep_inf", inf.separacion)
            .putBoolean("contador", contador)
            .apply()
    }

    /** Dónde está un botón: la barra y el número de fila (desde 0), o null si no está en ninguna. */
    fun donde(id: String): Pair<Barra, Int>? {
        for (b in listOf(sup, inf)) {
            for ((i, f) in b.filas.withIndex()) {
                if (f.any { it.id == id }) return Pair(b, i)
            }
        }
        return null
    }

    fun puedeQuitarse(id: String): Boolean = id != OMNIBOX && id != MENU

    fun puedeIrA(id: String, b: Barra): Boolean = id != OMNIBOX || b.arriba

    /**
     * Pone el botón al final de la fila [fila] de la barra [b] (una fila más allá de la última crea una nueva, si caben). Lo saca de donde
     * estuviera. Devuelve false si no se puede (el campo de dirección no va abajo).
     */
    fun colocar(id: String, b: Barra, fila: Int): Boolean {
        if (id != OMNIBOX && definicion(id) == null) return false
        if (!puedeIrA(id, b)) return false
        var entrada: EntradaBarra? = null
        for (x in listOf(sup, inf)) {
            for (f in x.filas) {
                val e = f.firstOrNull { it.id == id }
                if (e != null) {
                    entrada = e
                    f.remove(e)
                }
            }
        }
        val nueva = entrada ?: EntradaBarra(id, if (b.arriba) (definicion(id)?.tamanoDefecto ?: 36) else 0)
        val destino: MutableList<EntradaBarra> = when {
            fila in 0 until b.filas.size -> b.filas[fila]
            b.filas.size < MAX_FILAS -> ArrayList<EntradaBarra>().also { b.filas.add(it) }
            else -> b.filas[b.filas.size - 1]
        }
        destino.add(nueva)
        sup.filas.removeAll { it.isEmpty() }
        inf.filas.removeAll { it.isEmpty() }
        guardar()
        return true
    }

    /** Saca el botón de las barras (vuelve al catálogo). False si no se puede (campo de dirección y menú). */
    fun quitar(id: String): Boolean {
        if (!puedeQuitarse(id)) return false
        for (x in listOf(sup, inf)) {
            for (f in x.filas) f.removeAll { it.id == id }
            x.filas.removeAll { it.isEmpty() }
        }
        guardar()
        return true
    }

    /** Mueve un botón una posición hacia la izquierda (-1) o la derecha (+1) dentro de su fila. */
    fun mover(id: String, delta: Int) {
        for (x in listOf(sup, inf)) {
            for (f in x.filas) {
                val i = f.indexOfFirst { it.id == id }
                if (i < 0) continue
                val j = i + delta
                if (j < 0 || j >= f.size) return
                val e = f.removeAt(i)
                f.add(j, e)
                guardar()
                return
            }
        }
    }

    fun tamano(id: String, tam: Int) {
        for (x in listOf(sup, inf)) {
            for (f in x.filas) f.firstOrNull { it.id == id }?.tamano = tamanoValido(tam)
        }
        guardar()
    }

    fun fijarSeparacion(b: Barra, v: Int) {
        b.separacion = v.coerceIn(0, SEP_MAX)
        guardar()
    }

    fun fijarAlto(b: Barra, v: Int) {
        b.alto = v.coerceIn(ALTO_MIN, ALTO_MAX)
        guardar()
    }

    fun fijarContador(v: Boolean) {
        contador = v
        guardar()
    }

    /** Los botones del catálogo que no están en ninguna barra. */
    fun libres(): List<ElementoBarra> = ELEMENTOS.filter { donde(it.id) == null }

    fun restablecer() {
        contador = true
        sup.alto = 40
        sup.separacion = 0
        inf.alto = 48
        inf.separacion = 0
        sup.filas = leerFilas(DEF_SUP)
        inf.filas = leerFilas(DEF_INF)
        normalizar()
        guardar()
    }
}
