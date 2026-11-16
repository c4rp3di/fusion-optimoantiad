package com.optimo.navegador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mantiene vivo el proceso mientras suena audio con la pantalla bloqueada o con Optimo en segundo plano.
 * Es un servicio en primer plano de tipo «reproducción de medios»: notificación con Pausa/Cerrar, MediaSession
 * (controles en la pantalla de bloqueo y botones de auriculares) y bloqueos de CPU y Wi-Fi.
 *
 * No reproduce nada por sí mismo: el audio lo sigue emitiendo el WebView. Las órdenes de la notificación se
 * pasan a MainActivity mediante [manejador], que las ejecuta como JavaScript en la pestaña actual.
 * El servicio se detiene solo si el audio se para (>5 min en pausa) y MainActivity lo cierra al volver al primer plano.
 */
class ServicioSegundoPlano : Service() {

    private val principal = Handler(Looper.getMainLooper())
    private var sesion: MediaSession? = null
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var reproduciendo = true
    private var sinAudio = 0
    private var ciclosEnPausa = 0
    private var vigilando = false
    private var ciclos = 0

    // Carátula y artista de lo que suena (los pide MainActivity a la página; no se usan en pestañas privadas).
    private var artista = ""
    private var urlImagen = ""
    private var imagen: Bitmap? = null
    private var generacionImagen = 0

    private val vigilante = object : Runnable {
        override fun run() {
            // Cada ~12 s (y en el primer ciclo) se actualizan título, artista e imagen.
            ciclos++
            if (ciclos % 3 == 1) manejador?.invoke("metadatos")
            revisarAudio()
            if (vigilando) principal.postDelayed(this, PERIODO_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_ALTERNAR -> {
                if (!vigilando) ponerEnPrimerPlano()
                orden(if (reproduciendo) "pausar" else "reanudar")
            }
            ACCION_CERRAR -> {
                manejador?.invoke("pausar")
                detenerTodo()
            }
            else -> ponerEnPrimerPlano()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Optimo se cerró desde recientes: el WebView desaparece, así que no hay nada que mantener.
        manejador?.invoke("pausar")
        detenerTodo()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        vigilando = false
        principal.removeCallbacks(vigilante)
        liberarBloqueos()
        try {
            sesion?.isActive = false
            sesion?.release()
        } catch (e: Exception) {
        }
        sesion = null
        corriendo = false
        instancia = null
        generacionImagen++
        imagen = null
        super.onDestroy()
    }

    private fun ponerEnPrimerPlano() {
        if (vigilando) {
            actualizarNotificacion()
            return
        }
        crearCanal()
        crearSesion()
        reproduciendo = true
        sinAudio = 0
        ciclosEnPausa = 0
        val n = construirNotificacion()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID_NOTIF, n)
        }
        tomarBloqueos()
        corriendo = true
        vigilando = true
        principal.postDelayed(vigilante, PERIODO_MS)
    }

    private fun detenerTodo() {
        // El audio terminó (o se cerró): que el WebView vuelva a ocultarse de verdad si la app sigue fuera de pantalla.
        manejador?.invoke("ocultar")
        vigilando = false
        principal.removeCallbacks(vigilante)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    /** Aplica una orden (pausar/reanudar) a la página y refleja el estado enseguida. */
    private fun orden(accion: String) {
        manejador?.invoke(accion)
        reproduciendo = accion == "reanudar"
        sinAudio = 0
        ciclosEnPausa = 0
        actualizarNotificacion()
    }

    /** El estado real lo da el audio del sistema: así la notificación acierta aunque la página pause por su cuenta. */
    private fun revisarAudio() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.isMusicActive) {
            sinAudio = 0
            ciclosEnPausa = 0
            if (!reproduciendo) {
                reproduciendo = true
                actualizarNotificacion()
            }
            return
        }
        sinAudio++
        if (sinAudio >= 2 && reproduciendo) {
            reproduciendo = false
            actualizarNotificacion()
        }
        if (!reproduciendo) {
            ciclosEnPausa++
            if (ciclosEnPausa > MAX_CICLOS_EN_PAUSA) detenerTodo()
        }
    }

    // ---------------------------------------------------------------- notificación y sesión

    private fun crearCanal() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CANAL) == null) {
            val c = NotificationChannel(CANAL, "Reproducción en segundo plano", NotificationManager.IMPORTANCE_LOW)
            c.setShowBadge(false)
            c.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(c)
        }
    }

    private fun crearSesion() {
        try {
            val s = MediaSession(this, "Optimo")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    orden("reanudar")
                }

                override fun onPause() {
                    orden("pausar")
                }
            })
            s.isActive = true
            sesion = s
            estadoSesion()
        } catch (e: Exception) {
            sesion = null
        }
    }

    private fun estadoSesion() {
        val s = sesion ?: return
        try {
            val estado = if (reproduciendo) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(estado, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (reproduciendo) 1f else 0f)
                    .build()
            )
            val b = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
            if (artista.isNotBlank()) b.putString(MediaMetadata.METADATA_KEY_ARTIST, artista)
            val img = imagen
            if (img != null) {
                b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, img)
                b.putBitmap(MediaMetadata.METADATA_KEY_ART, img)
            }
            s.setMetadata(b.build())
        } catch (e: Exception) {
        }
    }

    private fun construirNotificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alternar = PendingIntent.getService(
            this, 1, Intent(this, ServicioSegundoPlano::class.java).setAction(ACCION_ALTERNAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cerrar = PendingIntent.getService(
            this, 2, Intent(this, ServicioSegundoPlano::class.java).setAction(ACCION_CERRAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val iconoAlternar = Icon.createWithResource(
            this, if (reproduciendo) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val iconoCerrar = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val estilo = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        sesion?.let { estilo.setMediaSession(it.sessionToken) }
        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(titulo)
            .setContentText(if (!reproduciendo) "En pausa" else if (artista.isNotBlank()) artista else "Reproduciendo en segundo plano")
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(iconoAlternar, if (reproduciendo) "Pausa" else "Reproducir", alternar).build())
            .addAction(Notification.Action.Builder(iconoCerrar, "Cerrar", cerrar).build())
            .setStyle(estilo)
        imagen?.let { b.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun actualizarNotificacion() {
        estadoSesion()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_NOTIF, construirNotificacion())
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- bloqueos de CPU y Wi-Fi

    private fun tomarBloqueos() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val l = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Optimo:segundoPlano")
            l.setReferenceCounted(false)
            l.acquire(TOPE_BLOQUEO_MS)
            cpu = l
        } catch (e: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val modo = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            val l = wm.createWifiLock(modo, "Optimo:segundoPlano")
            l.setReferenceCounted(false)
            l.acquire()
            wifi = l
        } catch (e: Exception) {
        }
    }

    private fun liberarBloqueos() {
        try {
            if (cpu?.isHeld == true) cpu?.release()
        } catch (e: Exception) {
        }
        try {
            if (wifi?.isHeld == true) wifi?.release()
        } catch (e: Exception) {
        }
        cpu = null
        wifi = null
    }

    // ---------------------------------------------------------------- carátula y datos de lo que suena

    private fun nuevosMetadatos(t: String, a: String, img: String) {
        var cambio = false
        val nuevoTitulo = t.trim()
        if (nuevoTitulo.isNotEmpty() && nuevoTitulo != titulo) {
            titulo = nuevoTitulo
            cambio = true
        }
        val nuevoArtista = a.trim()
        if (nuevoArtista != artista) {
            artista = nuevoArtista
            cambio = true
        }
        val u = img.trim()
        if (u.isNotEmpty() && u != urlImagen && (u.startsWith("https://") || u.startsWith("http://"))) {
            urlImagen = u
            cargarImagen(u)
        }
        if (cambio) actualizarNotificacion()
    }

    /** Descarga la imagen en un hilo aparte (tope de 2 MB, reducida a ~512 px). Si falla se queda la anterior. */
    private fun cargarImagen(url: String) {
        val generacion = ++generacionImagen
        Thread {
            var mapa: Bitmap? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 8000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Optimo")
                if (c.responseCode == 200) {
                    val limite = 2 * 1024 * 1024
                    val salida = ByteArrayOutputStream()
                    c.inputStream.use { entrada ->
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = entrada.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > limite) {
                                salida.reset()
                                break
                            }
                            salida.write(buf, 0, n)
                        }
                    }
                    val datos = salida.toByteArray()
                    if (datos.isNotEmpty()) mapa = decodificarReducida(datos, 512)
                }
                c.disconnect()
            } catch (e: Exception) {
                mapa = null
            }
            val resultado = mapa
            principal.post {
                if (generacion == generacionImagen && resultado != null) {
                    imagen = resultado
                    actualizarNotificacion()
                }
            }
        }.start()
    }

    private fun decodificarReducida(datos: ByteArray, maximo: Int): Bitmap? {
        val medida = BitmapFactory.Options()
        medida.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(datos, 0, datos.size, medida)
        if (medida.outWidth <= 0 || medida.outHeight <= 0) return null
        var muestreo = 1
        while (medida.outWidth / (muestreo * 2) >= maximo && medida.outHeight / (muestreo * 2) >= maximo) muestreo *= 2
        val op = BitmapFactory.Options()
        op.inSampleSize = muestreo
        return BitmapFactory.decodeByteArray(datos, 0, datos.size, op)
    }

    companion object {
        const val ACCION_ALTERNAR = "com.optimo.navegador.ALTERNAR"
        const val ACCION_CERRAR = "com.optimo.navegador.CERRAR"
        private const val CANAL = "optimo_segundo_plano"
        private const val ID_NOTIF = 4401
        private const val PERIODO_MS = 4000L
        private const val MAX_CICLOS_EN_PAUSA = 75 // ~5 minutos
        private const val TOPE_BLOQUEO_MS = 6L * 60L * 60L * 1000L

        /** MainActivity lo rellena: recibe «pausar» o «reanudar» y lo aplica a la página. Siempre en el hilo principal. */
        @Volatile
        var manejador: ((String) -> Unit)? = null

        @Volatile
        var titulo: String = "Optimo"

        @Volatile
        var corriendo: Boolean = false

        @Volatile
        private var instancia: ServicioSegundoPlano? = null

        /** Título, artista e imagen de lo que suena (los lee MainActivity de la página). Siempre en el hilo principal. */
        fun metadatos(titulo: String, artista: String, imagen: String) {
            instancia?.nuevosMetadatos(titulo, artista, imagen)
        }

        /** Arranca el servicio en primer plano. Devuelve false si el sistema no lo permite. */
        fun iniciar(c: Context, nombre: String): Boolean {
            titulo = nombre
            return try {
                c.startForegroundService(Intent(c, ServicioSegundoPlano::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }

        fun detener(c: Context) {
            try {
                c.stopService(Intent(c, ServicioSegundoPlano::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
