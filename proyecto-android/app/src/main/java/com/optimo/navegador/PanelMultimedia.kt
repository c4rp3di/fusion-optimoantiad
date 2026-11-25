package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper

/** Lo que el panel de multimedia necesita de MainActivity. */
interface AnfitrionMultimedia {
    fun descripcionMedia(magnet: Boolean): String
    fun elegirModoMedia(magnet: Boolean)
    fun nombresAudioPestanas(): List<String>
    fun nombreSuspension(min: Int): String
    fun fijarSegundoPlano(activo: Boolean)
    fun abrirBateria()
    fun probarVoz()
    fun abrirAjustesVoz()
    fun volverAlMenu()
}

/**
 * Multimedia como páginas del panel lateral: qué hacer con los enlaces de vídeo, audio, torrents y magnets; reproducción en
 * segundo plano y suspensión de pestañas; y la lectura en voz alta con el aspecto del modo lectura.
 */
class PanelMultimedia(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionMultimedia,
    inicial: String = MEDIA
) {
    companion object {
        const val MEDIA = "media"
        const val SEGUNDO_PLANO = "segundo_plano"
        const val LECTURA = "lectura"
        private val SUSPENSION = listOf(0, 15, 30, 60)
        private val VEL_NOMBRES = listOf("0,6×", "0,8×", "1,0× (normal)", "1,2×", "1,5×", "2,0×")
        private val VEL_VALORES = listOf(60, 80, 100, 120, 150, 200)
        private val TONO_NOMBRES = listOf("Grave (0,8×)", "Normal (1,0×)", "Agudo (1,2×)")
        private val TONO_VALORES = listOf(80, 100, 120)
        private val TAM_NOMBRES = listOf("Pequeña (16)", "Normal (19)", "Grande (24)", "Muy grande (30)")
        private val TAM_VALORES = listOf(16, 19, 24, 30)
        private val TEMAS = listOf("Automático (según el modo del sistema)", "Claro", "Sepia", "Oscuro", "Negro")
        private val TEMAS_CLAVES = listOf("", "claro", "sepia", "oscuro", "negro")
        private val INTERLINEADO = listOf("Compacto", "Normal", "Amplio")
    }

    private val hoja = Hoja(act, paleta, false, true)
    private val ui = Handler(Looper.getMainLooper())
    private var pagina = inicial

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

    private fun atras() {
        hoja.cerrar()
        g.volverAlMenu()
    }

    private fun titulo(t: String) {
        hoja.barraTitulo(t, { atras() }, { hoja.cerrar() })
    }

    private fun factor(v: Int): String = String.format(java.util.Locale.getDefault(), "%.1f×", v / 100.0)

    private fun elegir(titulo: String, nombres: List<String>, seleccion: Int, alElegir: (Int) -> Unit) {
        Dialogos.elegir(act, paleta, false, titulo, nombres, seleccion) { i ->
            alElegir(i)
            repintar()
        }
    }

    private fun pintar() {
        hoja.limpiar()
        when (pagina) {
            SEGUNDO_PLANO -> pintarSegundoPlano()
            LECTURA -> pintarLectura()
            else -> pintarMedia()
        }
    }

    // ---------------------------------------------------------------- enlaces multimedia

    private fun pintarMedia() {
        titulo("Enlaces multimedia, magnets y Mega")
        hoja.nota("Qué hace Optimo al tocar un enlace de vídeo, audio, lista .m3u/.m3u8, torrent, magnet o Mega.")
        hoja.fila("Vídeo, audio y torrents", "Abrirlos aquí o en otra app", g.descripcionMedia(false), icono = "🎬") {
            hoja.cerrar()
            g.elegirModoMedia(false)
        }
        hoja.fila("Enlaces magnet", "Optimo Play u otra app de torrents", g.descripcionMedia(true), icono = "🧲") {
            hoja.cerrar()
            g.elegirModoMedia(true)
        }
    }

    // ---------------------------------------------------------------- segundo plano

    private fun pintarSegundoPlano() {
        titulo("Reproducción en segundo plano")
        val audio = g.nombresAudioPestanas()
        hoja.fila("Al cambiar de pestaña con audio", "Qué pasa con la pestaña que suena", audio[ajustes.audioPestanas.coerceIn(0, audio.size - 1)]) {
            elegir("Al cambiar de pestaña con audio", audio.map { it.replaceFirstChar { c -> c.uppercase() } }, ajustes.audioPestanas) { i ->
                ajustes.audioPestanas = i
            }
        }
        hoja.fila("Suspender pestañas inactivas", "Libera memoria; se recuperan al volver a ellas", g.nombreSuspension(ajustes.suspenderMin)) {
            val nombres = SUSPENSION.map { g.nombreSuspension(it).replaceFirstChar { c -> c.uppercase() } }
            elegir("Suspender pestañas inactivas", nombres, SUSPENSION.indexOf(ajustes.suspenderMin)) { i ->
                ajustes.suspenderMin = SUSPENSION[i]
            }
        }
        hoja.interruptor("Seguir sonando con la pantalla bloqueada", "Recarga la página de YouTube para aplicarlo", ajustes.segundoPlano) { v ->
            g.fijarSegundoPlano(v)
            repintar()
        }
        hoja.interruptor("Imagen en imagen al salir", "Con un vídeo a pantalla completa", ajustes.pip) { v ->
            ajustes.pip = v
            repintar()
        }
        hoja.fila("Batería de Optimo", "Pon «Sin restricciones» para que no se corte", icono = "🔋") {
            hoja.cerrar()
            g.abrirBateria()
        }
    }

    // ---------------------------------------------------------------- lectura y voz

    private fun pintarLectura() {
        titulo("Lectura y voz")
        hoja.seccion("Voz")
        hoja.fila("Velocidad de la voz", null, factor(ajustes.vozVelocidad)) {
            elegir("Velocidad de la voz", VEL_NOMBRES, VEL_VALORES.indexOf(ajustes.vozVelocidad)) { i -> ajustes.vozVelocidad = VEL_VALORES[i] }
        }
        hoja.fila("Tono de la voz", null, factor(ajustes.vozTono)) {
            elegir("Tono de la voz", TONO_NOMBRES, TONO_VALORES.indexOf(ajustes.vozTono)) { i -> ajustes.vozTono = TONO_VALORES[i] }
        }
        hoja.fila("Probar la voz", icono = "▶") { g.probarVoz() }
        hoja.fila("Voz e idioma", "Ajustes de Android", icono = "⚙") { g.abrirAjustesVoz() }

        hoja.seccion("Modo lectura")
        hoja.fila("Tamaño de letra", null, ajustes.lecturaTam.toString()) {
            elegir("Tamaño de letra", TAM_NOMBRES, TAM_VALORES.indexOf(ajustes.lecturaTam)) { i -> ajustes.lecturaTam = TAM_VALORES[i] }
        }
        val temaActual = TEMAS_CLAVES.indexOf(ajustes.lecturaTema).coerceAtLeast(0)
        hoja.fila("Fondo", null, TEMAS[temaActual].substringBefore(" (")) {
            elegir("Fondo del modo lectura", TEMAS, temaActual) { i -> ajustes.lecturaTema = TEMAS_CLAVES[i] }
        }
        hoja.fila("Tipo de letra", null, if (ajustes.lecturaFuente == "sans") "sin serifas" else "con serifas") {
            elegir("Tipo de letra", listOf("Con serifas", "Sin serifas"), if (ajustes.lecturaFuente == "sans") 1 else 0) { i ->
                ajustes.lecturaFuente = if (i == 1) "sans" else "serif"
            }
        }
        hoja.fila("Interlineado", null, INTERLINEADO[ajustes.lecturaInter.coerceIn(0, 2)].lowercase()) {
            elegir("Interlineado", INTERLINEADO, ajustes.lecturaInter.coerceIn(0, 2)) { i -> ajustes.lecturaInter = i }
        }
    }
}
