package com.optimo.navegador

import android.graphics.Color
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger

/** Paleta clara u oscura segun el modo del sistema al arrancar. */
class Paleta(val oscuro: Boolean) {
    val colores: Colores =
        if (oscuro) Colores("#121418", "#E8EAED", "#1E2229", "#00BFA5", "#9AA0A6")
        else Colores("#FFFFFF", "#202124", "#F1F3F4", "#00897B", "#5F6368")

    val fondo: Int = Color.parseColor(colores.fondo)
    val texto: Int = Color.parseColor(colores.texto)
    val superficie: Int = Color.parseColor(colores.superficie)
    val acento: Int = Color.parseColor(colores.acento)
    val tenue: Int = Color.parseColor(colores.tenue)
    val barra: Int = Color.parseColor(if (oscuro) "#181B20" else "#F8F9FA")
    val campo: Int = Color.parseColor(if (oscuro) "#272B33" else "#E8EAED")
    val campoPrivado: Int = Color.parseColor(if (oscuro) "#3A2D52" else "#E6DBF5")
    val advertencia: Int = Color.parseColor("#F9A825")
    val seguro: Int = Color.parseColor(if (oscuro) "#00BFA5" else "#00796B")
}

/**
 * Una pestaña. El WebView se crea de forma perezosa (las pestañas restauradas de la sesion
 * anterior no cargan hasta que se activan).
 */
class Pestana(val id: Int, val privada: Boolean) {
    var web: WebView? = null
    var url: String = ""
    var titulo: String = ""
    var progreso: Int = 100
    var escritorio: Boolean = false

    /** Muestra la pagina de inicio propia (nueva pestaña). */
    var inicio: Boolean = false

    /** Ya se ha ordenado la primera carga (o la hara quien creo la pestaña). */
    var cargada: Boolean = false

    /** URL por cargar la primera vez que se active (sesion restaurada). */
    var pendiente: String? = null

    /** Anuncios y rastreadores bloqueados en la pagina actual (se escribe desde hilos de red). */
    val bloqueados = AtomicInteger(0)

    /** Registros de las herramientas de desarrollo de esta pestaña (consola nativa, bloqueos, errores de red). */
    val dev = DatosDev()

    /** Última pulsación sobre el WebView (px de pantalla) y modo «toca el anuncio que quieres bloquear». */
    var toqueX: Float = 0f
    var toqueY: Float = 0f
    var eligiendo: Boolean = false

    /** El modo «elegir» es para cualquier elemento de la página (ocultar o cambiar su apariencia), no solo anuncios. */
    var eligiendoElemento: Boolean = false

    /** La vista de modo lectura está abierta sobre esta página. */
    var lectura: Boolean = false

    /** URL http original cuando se intento mejorar a https (para el respaldo si falla). */
    var mejoradoDesde: String? = null

    /** Pestaña silenciada por el usuario (altavoz de la barra de direcciones, del candado o de la lista de pestañas). */
    var silenciada: Boolean = false

    /** Último estado conocido: algún vídeo/audio de la pestaña suena de verdad (para mostrar el altavoz 🔊). */
    var suena: Boolean = false

    // ---- ventanas emergentes y redirecciones (Ajustes › «Ventanas emergentes y redirecciones…»)

    /** Pestaña abierta por la página de otra pestaña (window.open / target=_blank). */
    var esEmergente: Boolean = false

    /** Pestaña desde la que se abrió (para volver a ella si la emergente se bloquea). */
    var abiertaPor: Pestana? = null

    /** La emergente se creó sin mostrarla: espera a ver su primera dirección para decidir si se cierra o se muestra. */
    @Volatile var oculta: Boolean = false

    /** Emergente de un sitio con permiso (o modo «permitir todas»): no se vigila. */
    @Volatile var libre: Boolean = true

    /** Hora (uptimeMillis) en que nació la emergente; solo se vigila en sus primeros segundos. */
    @Volatile var nacidoMs: Long = 0L

    /** Modo estricto: host del enlace que se tocó para abrirla; si la emergente va a otro sitio, era un secuestro del toque. */
    @Volatile var enlaceEsperado: String? = null

    /** Ya se ha ordenado cerrar esta emergente (evita contarla dos veces). */
    @Volatile var cerrando: Boolean = false

    /** Emergentes y redirecciones automáticas bloqueadas en la página actual. */
    val emergentesBloqueados = AtomicInteger(0)

    /** Última emergente permitida desde esta pestaña y toque al que correspondía (límite: una por toque y cada pocos segundos). */
    var ultimoEmergenteMs: Long = 0L
    var emergenteDelToque: Long = -1L

    /** Hora (uptimeMillis) del último toque del usuario sobre la página. */
    var toqueMs: Long = 0L

    /** Última vez (elapsedRealtime) que se entró o salió de esta pestaña; sirve para suspender las que llevan mucho sin usarse. */
    @Volatile var ultimoUsoMs: Long = 0L

    /** Visor de una página guardada: sin red, sin JavaScript y con acceso de lectura a su archivo (ver [PaginasGuardadas]). */
    var sinConexion: Boolean = false

    /** Visor de un .html del dispositivo (abierto desde otra app), no de una página guardada. */
    var archivoLocal: Boolean = false

    /** Solo en el visor de un archivo local: el usuario permitió su JavaScript (sigue sin red ni almacenamiento). */
    var permitirJs: Boolean = false

    /**
     * «Aplicación web» (ver [AppLocal]): un .html del dispositivo abierto con origen https propio, con almacenamiento y red. No es un visor sin
     * conexión (sinConexion es false): se comporta como una web normal salvo que no se guarda en historial ni en la sesión y no se suspende.
     */
    var appWeb: Boolean = false
    var appEspacio: String = ""
    var appHost: String = ""
    var appNombre: String = ""

    /** Visor de un archivo local: ruta del archivo (en la caché) que muestra, y dirección de su marcador si ya está guardado. */
    var archivoRuta: String = ""
    var urlLocal: String = ""

    /** Pestaña suspendida: su WebView se destruyó para liberar memoria y se rehace, con su historial y scroll, al volver a ella. */
    var suspendida: Boolean = false
    var estadoSuspendida: android.os.Bundle? = null

    /** Ultimo flujo de video/audio visto en la pagina (m3u8, mpd, mp4...). Se escribe desde hilos de red. */
    @Volatile var medioDetectado: String? = null
}

