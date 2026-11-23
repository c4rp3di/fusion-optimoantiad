package com.optimo.navegador

import android.content.Context
import android.content.SharedPreferences

/** Un elemento que puede ir en la barra de direcciones (además del propio campo de dirección). */
class ElementoBarra(
    val id: String,
    val nombre: String,
    val icono: String,
    val tamanoDefecto: Int
)

/** Estado de un elemento en la barra: si se ve y cuánto mide (ancho en dp; el icono se escala con él). */
class EntradaBarra(val id: String, var visible: Boolean, var tamano: Int)

/**
 * Configuración de la barra de direcciones (Ajustes › «Barra de direcciones…»): qué elementos se ven, en qué orden
 * (lo que queda antes del campo de dirección va a su izquierda y lo que queda después, a su derecha), su tamaño,
 * la separación entre ellos y el alto de la barra. Se guarda en preferencias propias; sin datos personales.
 */
object BarraConfig {
    const val OMNIBOX = "omnibox"
    const val TAM_MIN = 24
    const val TAM_MAX = 60
    const val SEP_MAX = 16
    const val ALTO_MIN = 32
    const val ALTO_MAX = 56

    val ELEMENTOS: List<ElementoBarra> = listOf(
        ElementoBarra("candado", "Candado (conexión)", "🔒", 32),
        ElementoBarra("escudo", "Escudo (protección)", "🛡", 36),
        ElementoBarra("altavoz", "Altavoz (si suena)", "🔊", 36),
        ElementoBarra("marcadores", "Marcadores", "🔖", 36),
        ElementoBarra("pantalla", "Pantalla completa", "⛶", 36)
    )

    private const val ORDEN_DEFECTO = "candado,omnibox,escudo,altavoz,marcadores,pantalla"

    private var sp: SharedPreferences? = null
    private var lista: MutableList<EntradaBarra> = ArrayList()

    /** Separación entre elementos (dp) y alto de la barra (dp). */
    var separacion: Int = 0
        private set
    var alto: Int = 40
        private set

    /** Contador de bloqueos sobre el escudo. */
    var contador: Boolean = true
        private set

    fun iniciar(c: Context) {
        val p = c.getSharedPreferences("optimo_barra", Context.MODE_PRIVATE)
        sp = p
        separacion = p.getInt("sep", 0).coerceIn(0, SEP_MAX)
        alto = p.getInt("alto", 40).coerceIn(ALTO_MIN, ALTO_MAX)
        contador = p.getBoolean("contador", true)
        lista = leer(p.getString("barra", null) ?: "")
    }

    fun definicion(id: String): ElementoBarra? = ELEMENTOS.firstOrNull { it.id == id }

    /** Entradas en el orden de la barra (incluye el campo de dirección, que no se puede ocultar). */
    fun orden(): List<EntradaBarra> = lista

    private fun entradaNueva(id: String): EntradaBarra {
        if (id == OMNIBOX) return EntradaBarra(OMNIBOX, true, 0)
        return EntradaBarra(id, true, definicion(id)?.tamanoDefecto ?: 36)
    }

    /** Formato: «id:visible:tamaño» separado por comas. Lo que falte se añade al final; lo desconocido se ignora. */
    private fun leer(texto: String): MutableList<EntradaBarra> {
        val r = ArrayList<EntradaBarra>()
        for (parte in texto.split(',')) {
            val c = parte.split(':')
            val id = c.getOrNull(0)?.trim() ?: continue
            if (id != OMNIBOX && definicion(id) == null) continue
            if (r.any { it.id == id }) continue
            val e = entradaNueva(id)
            if (id != OMNIBOX) {
                e.visible = c.getOrNull(1) != "0"
                e.tamano = (c.getOrNull(2)?.toIntOrNull() ?: e.tamano).coerceIn(TAM_MIN, TAM_MAX)
            }
            r.add(e)
        }
        // Lo que falta (primera vez o elemento nuevo en una versión posterior) se coloca según el orden por defecto.
        val defecto = ORDEN_DEFECTO.split(',')
        for ((i, id) in defecto.withIndex()) {
            if (r.any { it.id == id }) continue
            var pos = r.size
            // Se inserta justo antes del siguiente elemento del orden por defecto que ya esté, si lo hay.
            for (j in i + 1 until defecto.size) {
                val k = r.indexOfFirst { it.id == defecto[j] }
                if (k >= 0) {
                    pos = k
                    break
                }
            }
            r.add(pos, entradaNueva(id))
        }
        return r
    }

    private fun guardar() {
        val p = sp ?: return
        val s = lista.joinToString(",") { e -> e.id + ":" + (if (e.visible) "1" else "0") + ":" + e.tamano }
        p.edit().putString("barra", s).putInt("sep", separacion).putInt("alto", alto).putBoolean("contador", contador).apply()
    }

    fun mostrar(id: String, visible: Boolean) {
        lista.firstOrNull { it.id == id }?.visible = visible
        guardar()
    }

    fun tamano(id: String, tam: Int) {
        lista.firstOrNull { it.id == id }?.tamano = tam.coerceIn(TAM_MIN, TAM_MAX)
        guardar()
    }

    /** Mueve una posición hacia delante (-1) o hacia atrás (+1). */
    fun mover(id: String, delta: Int) {
        val i = lista.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= lista.size) return
        val e = lista.removeAt(i)
        lista.add(j, e)
        guardar()
    }

    fun fijarSeparacion(v: Int) {
        separacion = v.coerceIn(0, SEP_MAX)
        guardar()
    }

    fun fijarAlto(v: Int) {
        alto = v.coerceIn(ALTO_MIN, ALTO_MAX)
        guardar()
    }

    fun fijarContador(v: Boolean) {
        contador = v
        guardar()
    }

    fun restablecer() {
        separacion = 0
        alto = 40
        contador = true
        lista = leer("")
        guardar()
    }
}
