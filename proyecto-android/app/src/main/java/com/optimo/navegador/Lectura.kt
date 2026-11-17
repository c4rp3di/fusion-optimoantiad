package com.optimo.navegador

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale

/**
 * Modo lectura. El artículo se extrae y se muestra con JavaScript (assets/lectura/lectura.js): una vista aislada (Shadow DOM)
 * encima de la propia página, sin interfaz JavaScript hacia la app. Los controles de la vista avisan con mensajes de consola
 * «optimo-lectura:...» que recoge MainActivity (guardar preferencias, voz, cerrar).
 */
object Lectura {

    private var guion: String? = null

    private fun leerGuion(c: Context): String {
        val texto = try {
            c.assets.open("lectura/lectura.js").bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            ""
        }
        guion = texto
        return texto
    }

    /** Script de apertura/cierre con las preferencias del usuario. */
    fun guion(c: Context, ajustes: Ajustes, noche: Boolean): String {
        val js: String = guion ?: leerGuion(c)
        if (js.isEmpty()) return "'error'"
        val tema = if (ajustes.lecturaTema.isEmpty()) (if (noche) "oscuro" else "claro") else ajustes.lecturaTema
        val o = JSONObject()
            .put("tam", ajustes.lecturaTam)
            .put("tema", tema)
            .put("fuente", ajustes.lecturaFuente)
            .put("inter", ajustes.lecturaInter)
        return "($js)($o)"
    }

    /** Guarda las preferencias que envía la vista, validándolas (la página podría enviar cualquier cosa). */
    fun guardarPrefs(ajustes: Ajustes, json: String) {
        try {
            val o = JSONObject(json)
            ajustes.lecturaTam = o.optInt("tam", ajustes.lecturaTam)
            val tema = o.optString("tema", "")
            if (tema.isNotEmpty()) ajustes.lecturaTema = tema
            ajustes.lecturaFuente = o.optString("fuente", "serif")
            ajustes.lecturaInter = o.optInt("inter", ajustes.lecturaInter)
        } catch (e: Exception) {
            // Mensaje no válido: se ignora.
        }
    }
}

/** Lectura en voz alta con el motor de síntesis de Android (sin dependencias). */
class LectorVoz(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    private var listo = false
    private var pendiente: Pair<String, String>? = null
    private val principal = Handler(Looper.getMainLooper())

    @Volatile
    var hablando = false
        private set

    /** Se llama (en el hilo principal) cuando termina la lectura o falla. */
    var alTerminar: (() -> Unit)? = null

    /** Se llama (en el hilo principal) con el motivo si no se puede hablar. */
    var alFallo: ((String) -> Unit)? = null

    /** Velocidad y tono (1.0 = normal). */
    var velocidad: Float = 1f
    var tono: Float = 1f

    fun leer(texto: String, idioma: String) {
        parar()
        hablando = true
        val actual = tts
        if (actual == null) {
            pendiente = Pair(texto, idioma)
            tts = TextToSpeech(ctx.applicationContext) { estado ->
                listo = estado == TextToSpeech.SUCCESS
                val pend = pendiente
                pendiente = null
                if (listo && pend != null && hablando) {
                    hablar(pend.first, pend.second)
                } else if (!listo) {
                    fallar("No se pudo iniciar la voz. Comprueba en «ajustes de voz de Android» que hay un motor de síntesis instalado")
                }
            }
        } else if (listo) {
            hablar(texto, idioma)
        } else {
            pendiente = Pair(texto, idioma)
        }
    }

    private fun hablar(texto: String, idioma: String) {
        val t = tts ?: return
        try {
            val loc = if (idioma.isNotBlank()) Locale.forLanguageTag(idioma) else Locale.getDefault()
            t.setLanguage(if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) loc else Locale.getDefault())
        } catch (e: Exception) {
            // Se queda con el idioma por defecto.
        }
        try {
            t.setSpeechRate(velocidad)
            t.setPitch(tono)
        } catch (e: Exception) {
            // Se queda con los valores por defecto.
        }
        val trozos = trocear(texto, minOf(TextToSpeech.getMaxSpeechInputLength() - 100, 3000))
        if (trozos.isEmpty()) {
            terminar()
            return
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == "ultimo") terminar()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                terminar()
            }
        })
        for (i in trozos.indices) {
            val r = t.speak(trozos[i], TextToSpeech.QUEUE_ADD, null, if (i == trozos.size - 1) "ultimo" else "p$i")
            if (r != TextToSpeech.SUCCESS) {
                fallar("La voz no pudo leer el texto. Revisa el motor de voz en los ajustes de Android")
                return
            }
        }
    }

    private fun fallar(motivo: String) {
        parar()
        principal.post {
            alFallo?.invoke(motivo)
            alTerminar?.invoke()
        }
    }

    private fun terminar() {
        if (!hablando) return
        hablando = false
        principal.post { alTerminar?.invoke() }
    }

    fun parar() {
        hablando = false
        pendiente = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Nada que parar.
        }
    }

    fun cerrar() {
        parar()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // Ya cerrado.
        }
        tts = null
        listo = false
    }

    companion object {
        /** Divide el texto en trozos de como mucho [max] caracteres, cortando por párrafos y, si hace falta, por frases. */
        fun trocear(texto: String, max: Int): List<String> {
            val res = ArrayList<String>()
            val actual = StringBuilder()
            fun volcar() {
                if (actual.isNotBlank()) res.add(actual.toString().trim())
                actual.setLength(0)
            }
            for (parrafo in texto.split('\n')) {
                val p = parrafo.trim()
                if (p.isEmpty()) continue
                if (p.length > max) {
                    volcar()
                    for (frase in p.split(Regex("(?<=[.!?…])\\s+"))) {
                        var f = frase
                        while (f.length > max) {
                            res.add(f.substring(0, max))
                            f = f.substring(max)
                        }
                        if (actual.length + f.length + 1 > max) volcar()
                        actual.append(f).append(' ')
                    }
                    volcar()
                } else {
                    if (actual.length + p.length + 1 > max) volcar()
                    actual.append(p).append('\n')
                }
            }
            volcar()
            return res
        }
    }
}
