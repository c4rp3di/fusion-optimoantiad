package com.optimo.navegador

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Optimo: navegador sobre el WebView del sistema. Pestañas (normales y privadas), omnibox con
 * busqueda, marcadores, historial, descargas, buscar en la pagina, bloqueo basico de anuncios,
 * HTTPS primero, modo escritorio, permisos de camara/microfono/ubicacion y pantalla completa.
 * Sin dependencias externas: todo con APIs del sistema.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_ARCHIVO = 101
        private const val REQ_PERMISOS_WEB = 102
        private const val REQ_UBICACION = 103
        private const val REQ_ALMACENAMIENTO = 104
        private const val REQ_NOTIFICACIONES = 105
        private const val REQ_EXPORTAR = 106
        private const val REQ_IMPORTAR = 107
        private const val REQ_EXPORTAR_ENLACES = 108
        private const val MAX_PESTANAS = 20
        private const val MAX_CERRADAS = 10
        private val NOMBRES_AUDIO_PESTANAS = listOf("preguntar", "seguir sonando", "pausar")
        private val NOMBRES_AHORRO = listOf("desactivado", "siempre", "solo con datos móviles")
        private val NOMBRES_OSCURO_SITIO = listOf("según ajustes", "siempre", "nunca")

        /** Cuenta lo guardado por la página; IndexedDB y Cache Storage rellenan su cifra al resolverse. Formato: ls,ss,idb,cache (-1 = no disponible). */
        private const val JS_ALMACEN_CONTAR =
            "(function(){try{var o=[0,0,-1,-1];" +
                "try{o[0]=localStorage.length}catch(e){}try{o[1]=sessionStorage.length}catch(e){}" +
                "function g(){window.__optAlm=o.join(',')}g();" +
                "try{if(window.indexedDB&&indexedDB.databases)indexedDB.databases().then(function(l){o[2]=l.length;g()}).catch(function(){})}catch(e){}" +
                "try{if(window.caches)caches.keys().then(function(l){o[3]=l.length;g()}).catch(function(){})}catch(e){}" +
                "}catch(e){}})()"
        private const val JS_ALMACEN_LEER =
            "(function(){var v=window.__optAlm;try{delete window.__optAlm}catch(e){}return v||null})()"

        /** Instantánea del DOM ya renderizado (para guardar la página); null si supera el máximo. */
        private const val JS_DOM_INSTANTANEA =
            "(function(){try{var h='<!doctype html>'+document.documentElement.outerHTML;return h.length>3000000?null:h}catch(e){return null}})()"

        /** 1 si algún vídeo o audio de la página suena de verdad (no en silencio). */
        private const val JS_SUENA =
            "(function(){try{if(window.__optimoMudo)return 0;var v=document.querySelectorAll('video,audio');for(var i=0;i<v.length;i++){var e=v[i];" +
                "if(!e.paused&&!e.ended&&e.readyState>2&&!e.muted&&e.volume>0)return 1;}}catch(x){}return 0;})()"

        /**
         * Antes de suspender una pestaña: 0 = se puede; 1 = suena; 2 = hay texto escrito sin enviar (formularios, campos editables);
         * 3 = un vídeo largo empezado (su posición no se conserva).
         */
        private const val JS_PUEDE_SUSPENDER =
            "(function(){try{if(!window.__optimoMudo){var v=document.querySelectorAll('video,audio');for(var i=0;i<v.length;i++){var e=v[i];" +
                "if(!e.paused&&!e.ended&&e.readyState>2&&!e.muted&&e.volume>0)return 1;" +
                "if(e.currentTime>5&&!e.ended&&isFinite(e.duration)&&e.duration>120)return 3;}}" +
                "var c=document.querySelectorAll('input:not([type=hidden]):not([type=submit]):not([type=button]),textarea');" +
                "for(var j=0;j<c.length;j++){var x=c[j];if(x.type==='checkbox'||x.type==='radio'){if(x.checked!==x.defaultChecked)return 2;}" +
                "else if(x.value!==x.defaultValue)return 2;}" +
                "var ce=document.querySelectorAll('[contenteditable]');for(var k=0;k<ce.length;k++){" +
                "if(ce[k].isContentEditable&&(ce[k].textContent||'').trim().length>0)return 2;}}catch(x){}return 0;})()"

        /** Título, artista e imagen de lo que suena: mediaSession de la página, o og:image / póster como respaldo. */
        private const val JS_META =
            "(function(){try{var r={t:'',a:'',i:'',j:'',p:-1,d:-1};var m=navigator.mediaSession&&navigator.mediaSessio" +
                "n.metadata;if(m){r.t=m.title||'';r.a=m.artist||'';var al=m.artwork||[],best='',bw=-1,small='',sw=1e9;for(" +
                "var k=0;k<al.length;k++){var src=al[k].src;if(!src)continue;var s=parseInt((al[k].sizes||'0').split('x')[" +
                "0],10)||0;if(s<=640&&s>=bw){best=src;bw=s;}if(s<sw){small=src;sw=s;}}r.i=best||small;}var id='';try{var h" +
                "=location.hostname,q=null;if(/(^|[.])(youtube[.]com|youtube-nocookie[.]com)$/.test(h)){q=/[?&]v=([A-Za-z0" +
                "-9_-]{11})/.exec(location.search)||/^[/](shorts|embed|live)[/]([A-Za-z0-9_-]{11})/.exec(location.pathname" +
                ");if(q)q=[0,q[q.length-1]];}else if(/(^|[.])youtu[.]be$/.test(h)){q=/^[/]([A-Za-z0-9_-]{11})/.exec(locati" +
                "on.pathname);}if(q)id=q[1];}catch(x){}if(id){r.j='https://i.ytimg.com/vi/'+id+'/hqdefault.jpg';if(!r.i)r." +
                "i='https://i.ytimg.com/vi/'+id+'/maxresdefault.jpg';}if(!r.i){var vp=document.querySelector('video[poster" +
                "]');if(vp)r.i=vp.poster||'';}if(!r.i&&!id){var og=document.querySelector('meta[property=\"og:image\"]');if(" +
                "og)r.i=og.content||'';}if(r.i){try{r.i=new URL(r.i,location.href).href;}catch(x){}}if(!r.t)r.t=(document." +
                "title||'').replace(/^[(][0-9]+[)] */,'').replace(/ +- +YouTube( Music)?$/,'');var vv=document.querySelect" +
                "orAll('video,audio'),v=null;for(var n=0;n<vv.length;n++){if(!vv[n].paused&&vv[n].readyState>1){v=vv[n];br" +
                "eak;}}if(!v&&vv.length)v=vv[0];if(v){r.p=Math.round((v.currentTime||0)*1000);r.d=isFinite(v.duration)?Mat" +
                "h.round(v.duration*1000):-1;}return JSON.stringify(r);}catch(e){return '';}})()"

        /** Perfil de WebView (cookies, almacenamiento, caché) exclusivo de las pestañas privadas. */
        private const val PERFIL_PRIVADO = "optimo_privado"
    }

    private lateinit var paleta: Paleta
    private lateinit var ajustes: Ajustes
    private val lector = LectorVoz(this)
    private var pestanaVoz: Pestana? = null
    private lateinit var anuncios: MotorAnuncios
    private lateinit var silenciador: Silenciador
    private val manejadorPrincipal = Handler(Looper.getMainLooper())
    private lateinit var agenteDev: AgenteDev
    private lateinit var herramientas: PanelDev
    private lateinit var marcadores: Marcadores
    private lateinit var historial: ListaPersistente
    private lateinit var uaEscritorio: String

    private val pestanas = ArrayList<Pestana>()
    private var actual: Pestana? = null
    private var siguienteId = 1

    /** Hosts a los que el usuario confirmó entrar por http (solo hasta cerrar la app). */
    private val httpPermitido = HashSet<String>()

    /** El WebView del sistema admite perfiles separados (necesario para aislar las pestañas privadas). */
    private val perfilesOk: Boolean by lazy {
        try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (e: Throwable) { false }
    }

    // Vistas
    private lateinit var principal: LinearLayout
    private lateinit var pantallaCompleta: FrameLayout
    private lateinit var contenedor: FrameLayout
    private lateinit var candado: TextView
    private lateinit var omnibox: EditText
    private lateinit var progreso: ProgressBar
    private lateinit var barraBuscar: LinearLayout
    private lateinit var campoBuscar: EditText
    private lateinit var contadorBuscar: TextView
    private lateinit var btnAtras: TextView
    private lateinit var btnAdelante: TextView
    private lateinit var btnRecargar: TextView
    private lateinit var btnMarcador: TextView
    private lateinit var btnPestanas: TextView
    private lateinit var btnAltavoz: TextView
    private lateinit var barraSuperior: LinearLayout
    private lateinit var borrarTexto: TextView
    private lateinit var barraInferior: LinearLayout

    // Modo «página completa»: oculta barras de Optimo y del sistema; se sale con doble pulsación de atrás.
    private var paginaCompleta = false
    private var ultimoAtras = 0L

    // Estado de peticiones pendientes
    private var selectorArchivos: ValueCallback<Array<Uri>>? = null
    private var pendientePermisosWeb: Pair<PermissionRequest, Array<String>>? = null
    private var pendienteUbicacion: Pair<GeolocationPermissions.Callback, String>? = null
    private var pendienteDescarga: (() -> Unit)? = null
    private var vistaCompleta: View? = null
    private var callbackCompleta: WebChromeClient.CustomViewCallback? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val noche = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        paleta = Paleta(noche)
        ajustes = Ajustes(this)
        borrarPerfilPrivado()
        anuncios = MotorAnuncios(this, ajustes)
        silenciador = Silenciador(this)
        agenteDev = AgenteDev(this, ajustes)
        ReglasUsuario.iniciar(this)
        PaginasGuardadas.iniciar(this)
        ListasEnlaces.iniciar(this)
        ListasManager.iniciar(this, ajustes)
        ExcepcionesSsl.iniciar(this)
        marcadores = Marcadores(File(filesDir, "marcadores.json"), 500)
        historial = ListaPersistente(File(filesDir, "historial.json"), 1000)
        aplicarRetencionHistorial()
        if (ajustes.salidaPendiente) limpiarAlSalir()
        uaEscritorio = calcularUaEscritorio()
        construirUi()
        configurarVentana()
        restaurarSesion()
        procesarIntent(intent)
        ServicioSegundoPlano.manejador = { orden -> controlarMedios(orden) }
        registrarEscuchaAudio()
        if (ajustes.segundoPlano && !ajustes.notifPedido) {
            ajustes.notifPedido = true
            pedirPermisoNotificaciones()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        salirPaginaCompleta()
        procesarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // De vuelta en primer plano: ya no hace falta el servicio de segundo plano.
        if (ServicioSegundoPlano.corriendo) ServicioSegundoPlano.detener(this)
        actual?.web?.onResume()
        marcarFondo(false)
        // Al volver de otra app el sistema puede haber mostrado sus barras.
        if (paginaCompleta && vistaCompleta == null) ajustarBarrasSistema(true)
    }

    override fun onStart() {
        super.onStart()
        enPantalla = true
        if (ajustes.salidaPendiente) ajustes.salidaPendiente = false
        aplicarRetencionHistorial()
        if (ExcepcionesSsl.limpiarCaducadas()) olvidarSslWebView()
    }

    override fun onStop() {
        super.onStop()
        enPantalla = false
        rastreador?.parar("app en segundo plano")
        revisarSuspension(false)
        if (ajustes.borrarAlSalir != 0 && !isChangingConfigurations) ajustes.salidaPendiente = true
    }

    /** Poca memoria (10–15) o el sistema a punto de recortar procesos (60+): se suspenden pestañas sin uso. 20 es «app oculta», que no cuenta. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level in 10..15 || level >= 60) revisarSuspension(true)
    }

    override fun onPause() {
        super.onPause()
        marcarFondo(true)
        iniciarSegundoPlano()
        guardarSesion()
        marcadores.guardarAhora()
        historial.guardarAhora()
        CookieManager.getInstance().flush()
    }

    /** Pulsar Inicio/Recientes con un vídeo a pantalla completa lo pasa a imagen en imagen (el bloqueo de pantalla no). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!ajustes.pip || vistaCompleta == null) return
        try {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()
            )
        } catch (e: Exception) {
            // El sistema no admite PiP o el usuario lo desactivó para Optimo.
        }
    }

    /** Si suena audio al salir de la app o bloquear la pantalla, arranca el servicio que mantiene la reproducción. */
    private fun iniciarSegundoPlano() {
        if (!ajustes.segundoPlano || isChangingConfigurations) return
        if (!audioReciente()) return
        val p = sonando ?: actual
        val nombre = if (p == null || p.privada) {
            "Optimo" + (if (p != null && p.privada) " (pestaña privada)" else "")
        } else if (p.titulo.isBlank()) "Optimo" else p.titulo
        ServicioSegundoPlano.iniciar(this, nombre)
    }

    /** Órdenes de la notificación y de la pantalla de bloqueo: se aplican a los medios de la pestaña actual. */
    private fun controlarMedios(orden: String) {
        val w = (sonando ?: actual)?.web ?: return
        val js = when (orden) {
            "pausar" -> "window.__optimoPausa=1;(function(){var v=document.querySelectorAll('video,audio');for(var i=0;i<v.length;i++){try{v[i].pause();}catch(e){}}})()"
            "reanudar" -> "window.__optimoPausa=0;(function(){var v=document.querySelector('video,audio');if(v){try{v.play();}catch(e){}}})()"
            "ocultar" -> {
                // El audio terminó y la app sigue fuera de pantalla: el WebView vuelve a ocultarse de verdad.
                if (!enPantalla) {
                    for (q in listOfNotNull(actual, sonando)) {
                        val x = q.web
                        if (x is WebViewFondo) x.ocultarAhora()
                    }
                }
                return
            }
            "metadatos" -> {
                pedirMetadatos()
                return
            }
            else -> {
                // «buscar:<ms>»: la barra de progreso de la pantalla de bloqueo / panel de control.
                if (!orden.startsWith("buscar:")) return
                val ms = orden.substring(7).toLongOrNull() ?: return
                "(function(){var vv=document.querySelectorAll('video,audio'),v=null;for(var n=0;n<vv.length;n++){" +
                    "if(!vv[n].paused&&vv[n].readyState>1){v=vv[n];break;}}if(!v&&vv.length)v=vv[0];" +
                    "if(v){try{v.currentTime=" + (ms / 1000.0) + ";}catch(e){}}})()"
            }
        }
        w.evaluateJavascript(js, null)
    }

    // ---------------------------------------------------------------- segundo plano: estado del audio

    private var enPantalla = false
    private var ultimoAudioMs = 0L
    private var escuchaAudio: android.media.AudioManager.AudioPlaybackCallback? = null

    /**
     * True si suena audio o sonó hace un instante. Cubre la carrera de salir de la app: si la página se pausa justo
     * al ocultarse, el servicio debe arrancar igualmente.
     */
    private fun audioReciente(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val ahora = android.os.SystemClock.uptimeMillis()
        if (am.isMusicActive) {
            ultimoAudioMs = ahora
            return true
        }
        return ahora - ultimoAudioMs < 2000L
    }

    private fun registrarEscuchaAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val cb = object : android.media.AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                    if (am.isMusicActive) ultimoAudioMs = android.os.SystemClock.uptimeMillis()
                    // Empieza o termina algún audio: se actualiza el altavoz de la barra (y otra vez algo después,
                    // porque al saltar el aviso la página puede no haber marcado aún el vídeo como reproduciéndose).
                    comprobarSuena()
                    manejadorSonando.postDelayed({ comprobarSuena() }, 900L)
                }
            }
            am.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper()))
            escuchaAudio = cb
        } catch (e: Exception) {
            escuchaAudio = null
        }
    }

    // ---------------------------------------------------------------- audio al cambiar de pestaña

    /** Pestaña que sigue sonando en segundo plano mientras se usa otra (su WebView sigue en pantalla, tapado por la pestaña activa). */
    private var sonando: Pestana? = null
    private val manejadorSonando = Handler(Looper.getMainLooper())
    private var sinSonar = 0

    /** Vigila la pestaña de fondo: si deja de sonar ~45 s se libera, para no mantener un WebView vivo sin motivo. */
    private val tareaSonando = object : Runnable {
        override fun run() {
            val s = sonando ?: return
            val w = s.web
            if (w == null) {
                sonando = null
                return
            }
            w.evaluateJavascript(JS_SUENA) { r ->
                if (sonando === s) {
                    if (r == "1") sinSonar = 0 else sinSonar++
                    if (sinSonar >= 3) liberarSonando() else manejadorSonando.postDelayed(this, 15000L)
                }
            }
        }
    }

    /**
     * Al volver a la pestaña que sonaba, pasa a primer plano. No se reordena quitándola y volviendo a ponerla
     * (eso pausaría el vídeo): bringToFront solo cambia el orden interno de las vistas.
     */
    private fun restaurarVista(w: WebView) {
        w.bringToFront()
    }

    private fun quitarVista(w: WebView) {
        contenedor.removeView(w)
        w.onPause()
    }

    private fun liberarSonando() {
        val s = sonando ?: return
        sonando = null
        manejadorSonando.removeCallbacks(tareaSonando)
        val w = s.web
        if (w != null && s !== actual) quitarVista(w)
    }

    /** La pestaña [a] deja de ser la activa: se pausa como siempre, salvo que esté sonando y se quiera seguir oyéndola. */
    private fun retirarAnterior(a: Pestana) {
        val wa = a.web ?: return
        if (ajustes.audioPestanas == 2) {
            quitarVista(wa)
            return
        }
        // Mientras se decide, el WebView sigue en su sitio, a tamaño completo; la pestaña nueva se añade encima y lo tapa.
        // (En 1.7.3 se reducía a 1×1 px y transparente, y el vídeo se pausaba.)
        wa.evaluateJavascript(JS_SUENA) { r ->
            if (a === actual || a.web == null || !pestanas.contains(a)) {
                // Se volvió a ella o se cerró mientras se comprobaba: nada que hacer.
            } else if (r != "1") {
                quitarVista(wa)
            } else if (ajustes.audioPestanas == 1) {
                adoptarSonando(a)
            } else {
                preguntarSeguirSonando(a)
            }
        }
    }

    private fun adoptarSonando(a: Pestana) {
        val previa = sonando
        if (previa != null && previa !== a) liberarSonando()
        sonando = a
        sinSonar = 0
        manejadorSonando.removeCallbacks(tareaSonando)
        manejadorSonando.postDelayed(tareaSonando, 15000L)
        actualizarUi()
    }

    private fun preguntarSeguirSonando(a: Pestana) {
        val wa = a.web ?: return
        val d = nuevoDialogo()
            .setTitle("Audio en esta pestaña")
            .setMessage("«" + tituloDe(a) + "» está sonando. ¿Quieres seguir oyéndola mientras usas otra pestaña?")
            .setPositiveButton("Seguir sonando") { _, _ ->
                if (a === actual) return@setPositiveButton
                adoptarSonando(a)
            }
            .setNegativeButton("Pausar") { _, _ ->
                if (a !== actual) quitarVista(wa)
            }
            .setNeutralButton("Siempre seguir") { _, _ ->
                ajustes.audioPestanas = 1
                if (a !== actual) adoptarSonando(a)
            }
            .create()
        d.setOnCancelListener {
            if (a !== actual && sonando !== a) quitarVista(wa)
        }
        d.show()
    }

    // ---------------------------------------------------------------- carátula y datos de lo que suena

    /** Pide a la pestaña que suena su título, artista, imagen, posición y duración y se los pasa al servicio (no en pestañas privadas). */
    private fun pedirMetadatos() {
        val p = sonando ?: actual ?: return
        if (p.privada) return
        val w = p.web ?: return
        w.evaluateJavascript(JS_META) { r ->
            try {
                val s = org.json.JSONTokener(r ?: "").nextValue() as? String
                if (!s.isNullOrEmpty()) {
                    val o = org.json.JSONObject(s)
                    ServicioSegundoPlano.metadatos(
                        o.optString("t"), o.optString("a"), o.optString("i"), o.optString("j"),
                        o.optLong("p", -1L), o.optLong("d", -1L)
                    )
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun quitarEscuchaAudio() {
        val cb = escuchaAudio ?: return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.unregisterAudioPlaybackCallback(cb)
        } catch (e: Exception) {
        }
        escuchaAudio = null
    }

    /** Ajusta un WebView al ajuste «Seguir sonando» (ignorar el aviso de ventana oculta y prioridad del renderer). */
    private fun aplicarFondo(w: WebView) {
        if (w is WebViewFondo) w.activo = ajustes.segundoPlano
        try {
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, !ajustes.segundoPlano)
        } catch (e: Exception) {
        }
    }

    /** Avisa a la página de que la app sale o vuelve: el script de segundo plano reanuda un vídeo pausado al salir. */
    private fun marcarFondo(enFondo: Boolean) {
        if (!ajustes.segundoPlano) return
        val js = if (enFondo) {
            "window.__optimoBg=1;window.__optimoBgDesde=Date.now();window.__optimoPausa=0;window.__optimoIntentos=0;"
        } else {
            "window.__optimoBg=0;window.__optimoPausa=0;"
        }
        for (q in listOfNotNull(actual, sonando)) q.web?.evaluateJavascript(js, null)
    }

    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICACIONES)
        }
    }

    override fun onDestroy() {
        rastreador?.parar("descartado")
        if (isFinishing) {
            limpiarAlSalir()
            ExcepcionesSsl.olvidarSesion()
            olvidarSslWebView()
        }
        lector.cerrar()
        ServicioSegundoPlano.manejador = null
        quitarEscuchaAudio()
        manejadorSonando.removeCallbacks(tareaSonando)
        for (p in pestanas) {
            val w = p.web
            if (w != null) {
                contenedor.removeView(w)
                w.destroy()
                p.web = null
            }
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (vistaCompleta != null) {
            quitarPantallaCompleta()
            return
        }
        if (paginaCompleta) {
            val ahora = android.os.SystemClock.elapsedRealtime()
            if (ahora - ultimoAtras <= 2000L) {
                ultimoAtras = 0L
                salirPaginaCompleta()
            } else {
                ultimoAtras = ahora
                toast("Pulsa atrás otra vez para salir de pantalla completa")
            }
            return
        }
        val lee = actual
        if (lee != null && lee.lectura) {
            lee.web?.evaluateJavascript("window.__optimoLectura&&window.__optimoLectura.cerrar()", null)
            lee.lectura = false
            return
        }
        if (::herramientas.isInitialized && herramientas.visible && herramientas.alAtras()) return
        if (barraBuscar.visibility == View.VISIBLE) {
            cerrarBusqueda()
            return
        }
        if (omnibox.hasFocus()) {
            omnibox.clearFocus()
            ocultarTeclado(omnibox)
            return
        }
        val p = actual
        val w = p?.web
        if (w != null && w.canGoBack()) {
            w.goBack()
            return
        }
        if (p != null && pestanas.size > 1) {
            cerrarPestana(p)
            return
        }
        super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ARCHIVO) {
            val cb = selectorArchivos
            selectorArchivos = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        } else if (requestCode == REQ_EXPORTAR && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) escribirCopia(uri)
        } else if (requestCode == REQ_IMPORTAR && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) leerCopia(uri)
        } else if (requestCode == REQ_EXPORTAR_ENLACES) {
            val contenido = enlacesPorExportar
            enlacesPorExportar = null
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && contenido != null) escribirEnlaces(uri, contenido)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        var todos = grantResults.isNotEmpty()
        for (r in grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) todos = false
        }
        when (requestCode) {
            REQ_PERMISOS_WEB -> {
                val par = pendientePermisosWeb
                pendientePermisosWeb = null
                if (par != null) {
                    if (todos) par.first.grant(par.second) else par.first.deny()
                }
            }
            REQ_UBICACION -> {
                val par = pendienteUbicacion
                pendienteUbicacion = null
                if (par != null) par.first.invoke(par.second, todos, false)
            }
            REQ_NOTIFICACIONES -> {
                if (!todos) toast("Sin permiso de notificaciones: seguirá sonando, pero sin controles en la barra")
            }
            REQ_ALMACENAMIENTO -> {
                val tarea = pendienteDescarga
                pendienteDescarga = null
                if (todos && tarea != null) tarea() else toast("Sin permiso para guardar archivos")
            }
        }
    }

    // ---------------------------------------------------------------- interfaz

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(texto: String) {
        Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()
    }

    private fun fondoRedondeado(color: Int, radio: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.setCornerRadius(radio.toFloat())
        return g
    }

    private fun ripple(v: View) {
        val tv = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            v.setBackgroundResource(tv.resourceId)
        }
    }

    private fun boton(texto: String, tam: Float = 20f, accion: () -> Unit): TextView {
        val t = TextView(this)
        t.text = texto
        t.textSize = tam
        t.gravity = Gravity.CENTER
        t.setTextColor(paleta.texto)
        t.isClickable = true
        t.setOnClickListener { accion() }
        ripple(t)
        return t
    }

    private fun construirUi() {
        val raiz = FrameLayout(this)
        raiz.setBackgroundColor(paleta.fondo)

        principal = LinearLayout(this)
        principal.setOrientation(LinearLayout.VERTICAL)

        // Barra superior: indicador de seguridad + omnibox
        val barraSup = LinearLayout(this)
        barraSup.setOrientation(LinearLayout.HORIZONTAL)
        barraSup.setGravity(Gravity.CENTER_VERTICAL)
        barraSup.setBackgroundColor(paleta.barra)
        barraSup.setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())

        candado = TextView(this)
        candado.textSize = 16f
        candado.gravity = Gravity.CENTER
        candado.isClickable = true
        candado.setOnClickListener { mostrarInfoSitio() }
        barraSup.addView(candado, LinearLayout.LayoutParams(34.dp(), ViewGroup.LayoutParams.WRAP_CONTENT))

        omnibox = EditText(this)
        omnibox.hint = "Busca o escribe una dirección"
        omnibox.setHintTextColor(paleta.tenue)
        omnibox.setTextColor(paleta.texto)
        omnibox.textSize = 15f
        omnibox.setSingleLine(true)
        omnibox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        omnibox.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        omnibox.setSelectAllOnFocus(true)
        omnibox.setPadding(16.dp(), 0, 34.dp(), 0)
        omnibox.background = fondoRedondeado(paleta.campo, 20.dp())
        omnibox.setOnEditorActionListener { _, accion, evento ->
            if (accion == EditorInfo.IME_ACTION_GO ||
                (evento != null && evento.keyCode == KeyEvent.KEYCODE_ENTER && evento.action == KeyEvent.ACTION_DOWN)
            ) {
                ir(omnibox.text.toString())
                true
            } else {
                false
            }
        }
        omnibox.setOnFocusChangeListener { _, tiene ->
            actualizarBotonBorrar()
            if (!tiene) actualizarUi()
        }
        omnibox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                actualizarBotonBorrar()
            }
        })

        // ✕ dentro del omnibox (a la derecha): borra lo escrito y deja el teclado listo
        borrarTexto = TextView(this)
        borrarTexto.text = "✕"
        borrarTexto.textSize = 15f
        borrarTexto.gravity = Gravity.CENTER
        borrarTexto.setTextColor(paleta.tenue)
        borrarTexto.visibility = View.GONE
        borrarTexto.setOnClickListener {
            omnibox.setText("")
            omnibox.requestFocus()
            mostrarTeclado(omnibox)
        }
        val cajaOmnibox = FrameLayout(this)
        cajaOmnibox.addView(omnibox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cajaOmnibox.addView(
            borrarTexto,
            FrameLayout.LayoutParams(36.dp(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL)
        )
        barraSup.addView(cajaOmnibox, LinearLayout.LayoutParams(0, 40.dp(), 1f))
        // Altavoz: solo aparece si la pestaña suena o está silenciada (tocar para silenciar / activar el sonido)
        btnAltavoz = boton("🔊", 17f) { actual?.let { alternarSilencio(it) } }
        btnAltavoz.visibility = View.GONE
        barraSup.addView(btnAltavoz, LinearLayout.LayoutParams(38.dp(), 40.dp()))
        // Marcadores (junto al de pantalla completa, que queda en el extremo derecho)
        barraSup.addView(boton("🔖", 17f) { mostrarMarcadores() }, LinearLayout.LayoutParams(38.dp(), 40.dp()))
        barraSup.addView(boton("⛶", 18f) { entrarPaginaCompleta() }, LinearLayout.LayoutParams(38.dp(), 40.dp()))
        barraSuperior = barraSup
        principal.addView(
            barraSup,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Barra de progreso con alto fijo explicito (3 dp)
        progreso = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progreso.max = 100
        progreso.isIndeterminate = false
        progreso.progressTintList = ColorStateList.valueOf(paleta.acento)
        progreso.progressBackgroundTintList = ColorStateList.valueOf(paleta.campo)
        progreso.visibility = View.INVISIBLE
        principal.addView(
            progreso,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3.dp())
        )

        // Barra de buscar en la pagina
        barraBuscar = LinearLayout(this)
        barraBuscar.setOrientation(LinearLayout.HORIZONTAL)
        barraBuscar.setGravity(Gravity.CENTER_VERTICAL)
        barraBuscar.setBackgroundColor(paleta.barra)
        barraBuscar.setPadding(8.dp(), 4.dp(), 4.dp(), 4.dp())
        barraBuscar.visibility = View.GONE
        campoBuscar = EditText(this)
        campoBuscar.hint = "Buscar en la página"
        campoBuscar.setHintTextColor(paleta.tenue)
        campoBuscar.setTextColor(paleta.texto)
        campoBuscar.textSize = 15f
        campoBuscar.setSingleLine(true)
        campoBuscar.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        campoBuscar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                buscarEnPagina(s?.toString() ?: "")
            }
        })
        campoBuscar.setOnEditorActionListener { _, _, _ ->
            actual?.web?.findNext(true)
            true
        }
        contadorBuscar = TextView(this)
        contadorBuscar.textSize = 13f
        contadorBuscar.setTextColor(paleta.tenue)
        contadorBuscar.setPadding(8.dp(), 0, 8.dp(), 0)
        barraBuscar.addView(campoBuscar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barraBuscar.addView(contadorBuscar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        barraBuscar.addView(boton("▲", 16f) { actual?.web?.findNext(false) }, LinearLayout.LayoutParams(40.dp(), 40.dp()))
        barraBuscar.addView(boton("▼", 16f) { actual?.web?.findNext(true) }, LinearLayout.LayoutParams(40.dp(), 40.dp()))
        barraBuscar.addView(boton("✕", 16f) { cerrarBusqueda() }, LinearLayout.LayoutParams(40.dp(), 40.dp()))
        principal.addView(
            barraBuscar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Contenido (el WebView de la pestaña activa)
        contenedor = FrameLayout(this)
        contenedor.setBackgroundColor(paleta.fondo)
        principal.addView(contenedor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Herramientas de desarrollo: panel acoplado bajo la página (oculto hasta abrirlo desde el menú)
        herramientas = PanelDev(
            this, paleta, ajustes, agenteDev, contenedor,
            { actual?.web }, { actual }, { w -> gestorCookies(w) }, { nuevoDialogo() },
            { t -> toast(t) }, { t -> copiar(t) }, { reinstalarDev() },
            { modo, a, b -> reglaDevTools(modo, a, b) },
            { actual?.let { abrirModoLectura(it) } },
            { actual?.let { if (!it.privada) alternarPausaBloqueo(it) } },
            { val q = actual; q != null && !q.privada && ajustes.bloquearAnuncios && !bloqueoPausadoEnPagina(q.url) },
            { dominio, todos -> permitirDominioDevTools(dominio, todos) }
        )
        principal.addView(herramientas.vista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 5f))

        // Barra inferior: atras, adelante, recargar/parar, marcador, pestañas, menu
        val barraInf = LinearLayout(this)
        barraInf.setOrientation(LinearLayout.HORIZONTAL)
        barraInf.setGravity(Gravity.CENTER_VERTICAL)
        barraInf.setBackgroundColor(paleta.barra)
        barraInferior = barraInf

        btnAtras = boton("←") {
            val w = actual?.web
            if (w != null && w.canGoBack()) w.goBack()
        }
        btnAdelante = boton("→") {
            val w = actual?.web
            if (w != null && w.canGoForward()) w.goForward()
        }
        btnRecargar = boton("↻") { recargarOParar() }
        btnMarcador = boton("☆") { alternarMarcador() }
        btnMarcador.setOnLongClickListener {
            editarMarcadorActual()
            true
        }

        btnPestanas = TextView(this)
        btnPestanas.text = "1"
        btnPestanas.textSize = 12f
        btnPestanas.gravity = Gravity.CENTER
        btnPestanas.setTextColor(paleta.texto)
        val cajaPestanas = GradientDrawable()
        cajaPestanas.setColor(Color.TRANSPARENT)
        cajaPestanas.setStroke(2.dp(), paleta.texto)
        cajaPestanas.setCornerRadius(6.dp().toFloat())
        btnPestanas.background = cajaPestanas
        val envoltorio = FrameLayout(this)
        envoltorio.isClickable = true
        envoltorio.setOnClickListener { mostrarPestanas() }
        ripple(envoltorio)
        envoltorio.addView(btnPestanas, FrameLayout.LayoutParams(26.dp(), 22.dp(), Gravity.CENTER))

        val btnMenu = boton("⋮", 22f) { mostrarMenu() }

        val vistasInf = listOf<View>(btnAtras, btnAdelante, btnRecargar, btnMarcador, envoltorio, btnMenu)
        for (v in vistasInf) {
            barraInf.addView(v, LinearLayout.LayoutParams(0, 48.dp(), 1f))
        }
        principal.addView(
            barraInf,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        pantallaCompleta = FrameLayout(this)
        pantallaCompleta.setBackgroundColor(Color.BLACK)
        pantallaCompleta.visibility = View.GONE

        raiz.addView(principal, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        raiz.addView(pantallaCompleta, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(raiz)
    }

    @Suppress("DEPRECATION")
    private fun configurarVentana() {
        window.setStatusBarColor(paleta.barra)
        window.setNavigationBarColor(paleta.barra)
        if (!paleta.oscuro) {
            var f = window.decorView.systemUiVisibility
            f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }
    }

    private fun tituloDe(p: Pestana): String {
        if (p.titulo.isNotEmpty()) return p.titulo
        if (p.url.isNotEmpty()) return p.url
        return "Nueva pestaña"
    }

    private fun actualizarUi() {
        val p = actual ?: return
        aplicarPantallaSegura(p.privada)
        if (!omnibox.hasFocus()) {
            omnibox.setText(if (p.inicio) "" else p.url)
        }
        omnibox.background = fondoRedondeado(if (p.privada) paleta.campoPrivado else paleta.campo, 20.dp())
        if (p.privada) {
            candado.text = "🕶"
            candado.setTextColor(paleta.texto)
        } else if (p.sinConexion) {
            candado.text = "📄"
            candado.setTextColor(paleta.tenue)
        } else if (p.url.startsWith("https://")) {
            if (ExcepcionesSsl.hayParaSitio(ExcepcionesSsl.clave(p.url), p.privada)) {
                // Cifrada, pero con un certificado no válido que el usuario autorizó: no es un candado verde.
                candado.text = "⚠"
                candado.setTextColor(paleta.advertencia)
            } else {
                candado.text = "🔒"
                candado.setTextColor(paleta.seguro)
            }
        } else if (p.url.startsWith("http://")) {
            candado.text = "⚠"
            candado.setTextColor(paleta.advertencia)
        } else {
            candado.text = "🔍"
            candado.setTextColor(paleta.tenue)
        }
        val w = p.web
        btnAtras.alpha = if (w != null && w.canGoBack()) 1f else 0.35f
        btnAdelante.alpha = if (w != null && w.canGoForward()) 1f else 0.35f
        val guardado = p.url.isNotEmpty() && marcadores.contiene(p.url)
        btnMarcador.text = if (guardado) "★" else "☆"
        btnMarcador.setTextColor(if (guardado) paleta.acento else paleta.texto)
        btnPestanas.text = pestanas.size.toString()
        val verAltavoz = p.silenciada || p.suena
        btnAltavoz.visibility = if (verAltavoz) View.VISIBLE else View.GONE
        btnAltavoz.text = if (p.silenciada) "🔇" else "🔊"
        btnAltavoz.setTextColor(if (p.silenciada) paleta.advertencia else paleta.texto)
        actualizarProgreso()
    }

    // ---------------------------------------------------------------- silenciar pestañas

    /** Silencia o devuelve el sonido a una pestaña (se aplica a la página actual y a las siguientes de esa pestaña). */
    private fun alternarSilencio(p: Pestana) {
        p.silenciada = !p.silenciada
        if (p.silenciada) p.suena = false
        p.web?.let { silenciador.poner(it, p.silenciada) }
        toast(if (p.silenciada) "Pestaña silenciada" else "Sonido activado")
        if (p === actual) actualizarUi()
        if (!p.silenciada) comprobarSuena()
    }

    /** Comprueba si la pestaña actual suena (por JavaScript) para mostrar u ocultar el altavoz de la barra. */
    private fun comprobarSuena() {
        val p = actual ?: return
        val w = p.web ?: return
        if (p.silenciada) return
        w.evaluateJavascript(JS_SUENA) { r ->
            val s = r == "1"
            if (p.suena != s) {
                p.suena = s
                if (p === actual) actualizarUi()
            }
        }
    }

    /** Candado: estado de la conexión y acciones rápidas del sitio, entre ellas silenciar la pestaña. */
    private fun mostrarInfoSitio() {
        val p = actual ?: return
        val estado = when {
            p.sinConexion -> "Página guardada (sin conexión)"
            p.privada -> "Pestaña privada"
            p.inicio -> "Página de inicio de Optimo"
            p.url.startsWith("https://") && ExcepcionesSsl.hayParaSitio(ExcepcionesSsl.clave(p.url), p.privada) ->
                "Conexión cifrada, pero con un certificado no válido que autorizaste"
            p.url.startsWith("https://") -> "Conexión segura (HTTPS)"
            p.url.startsWith("http://") -> "Conexión sin cifrar (HTTP)"
            else -> "Sin conexión"
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair(if (p.silenciada) "🔊 Activar el sonido de esta pestaña" else "🔇 Silenciar esta pestaña") {
            alternarSilencio(p)
        })
        if (!p.sinConexion && !p.inicio && p.url.startsWith("https://")) {
            items.add(Pair("🔒 Certificado de la conexión…") { mostrarCertificado(p) })
        }
        if (!p.privada && !p.inicio && p.url.startsWith("http")) {
            items.add(Pair("🛡 Privacidad de este sitio…") { mostrarPrivacidadSitio(Omnibox.hostDe(p.url)) })
        }
        if (!p.privada && !p.inicio && !p.sinConexion && p.url.startsWith("http")) {
            if (ReglasUsuario.bloqueaDominio(Omnibox.hostDe(p.url).lowercase())) {
                items.add(Pair("✅ Desbloquear este sitio…") { desbloquearSitio(p) })
            } else {
                items.add(Pair("🚫 Bloquear este sitio…") { bloquearSitio(p) })
            }
        }
        if (!p.inicio && p.url.isNotEmpty()) {
            items.add(Pair("Copiar dirección") { copiar(p.url) })
            items.add(Pair("Compartir página") { compartir(p.url) })
        }
        opciones(estado + (if (!p.inicio && p.url.isNotEmpty()) "\n" + Omnibox.hostDe(p.url) else ""), items)
    }

    private fun nombreDn(d: android.net.http.SslCertificate.DName?): String {
        if (d == null) return "—"
        val cn = d.cName ?: ""
        val o = d.oName ?: ""
        return when {
            cn.isNotEmpty() && o.isNotEmpty() && cn != o -> cn + " (" + o + ")"
            cn.isNotEmpty() -> cn
            o.isNotEmpty() -> o
            else -> "—"
        }
    }

    private fun fechaCert(d: java.util.Date?): String =
        if (d == null) "—" else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(d)

    /** Certificado de la conexión de la página abierta (lo que expone el WebView) y, si existe, la autorización que diste. */
    private fun mostrarCertificado(p: Pestana) {
        val sitio = ExcepcionesSsl.clave(p.url)
        val c: android.net.http.SslCertificate? = try {
            p.web?.certificate
        } catch (e: Exception) {
            null
        }
        val sb = StringBuilder()
        sb.append(Omnibox.hostDe(p.url)).append("\n\n")
        var huella: String? = null
        if (c == null) {
            sb.append("Optimo no ha recibido el certificado de esta página (puede venir de la caché o de una página guardada).")
        } else {
            huella = Certificados.huella(c)
            sb.append("Emitido para: ").append(nombreDn(c.issuedTo)).append("\n")
            sb.append("Emitido por: ").append(nombreDn(c.issuedBy)).append("\n")
            sb.append("Válido desde: ").append(fechaCert(c.validNotBeforeDate)).append("\n")
            sb.append("Válido hasta: ").append(fechaCert(c.validNotAfterDate)).append("\n")
            sb.append("\nHuella SHA-256:\n").append(huella ?: "no disponible")
        }
        val h = huella
        val exc = if (h == null) null else ExcepcionesSsl.todas().firstOrNull { e -> e.sitio == sitio && e.huella == h }
        if (exc != null) {
            sb.append("\n\n⚠ Autorizaste este certificado a pesar de: ").append(Certificados.etiqueta(exc.motivos))
                .append(" (").append(Certificados.vigencia(exc)).append(").")
        } else if (p.privada && ExcepcionesSsl.hayParaSitio(sitio, true)) {
            sb.append("\n\n⚠ Autorizaste continuar con un certificado no válido en esta sesión privada.")
        }
        sb.append("\n\nEl cifrado solo protege la conexión: no garantiza que el sitio sea de confianza.")
        val b = nuevoDialogo().setTitle("Certificado").setMessage(sb.toString()).setPositiveButton("Cerrar", null)
        if (h != null) b.setNeutralButton("Copiar huella") { _, _ -> copiar(h) }
        b.show()
    }

    private fun actualizarProgreso() {
        val p = actual ?: return
        progreso.progress = p.progreso
        progreso.visibility = if (p.progreso in 0..99) View.VISIBLE else View.INVISIBLE
        btnRecargar.text = if (p.progreso in 0..99) "✕" else "↻"
    }

    private fun ocultarTeclado(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun mostrarTeclado(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Con una pestaña privada visible no se permiten capturas de pantalla ni miniatura en «recientes». */
    private fun aplicarPantallaSegura(privada: Boolean) {
        if (privada) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun hayPrivadas(): Boolean {
        for (q in pestanas) {
            if (q.privada) return true
        }
        return false
    }

    /** Los diálogos son ventanas aparte: si hay pestañas privadas abiertas (p. ej. el selector de pestañas) también son seguros. */
    private fun nuevoDialogo(): AlertDialog.Builder {
        val tema = if (paleta.oscuro) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert
        return object : AlertDialog.Builder(this@MainActivity, tema) {
            override fun create(): AlertDialog {
                val d = super.create()
                if (hayPrivadas()) d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                return d
            }
        }
    }

    private fun opciones(titulo: String?, items: List<Pair<String, () -> Unit>>) {
        val b = nuevoDialogo()
        if (titulo != null) b.setTitle(titulo)
        val etiquetas = Array(items.size) { i -> items[i].first }
        b.setItems(etiquetas) { _, i -> items[i].second() }
        b.show()
    }

    // ---------------------------------------------------------------- pestañas

    private fun calcularUaEscritorio(): String {
        val base = WebSettings.getDefaultUserAgent(this)
        return base.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webDe(p: Pestana): WebView {
        val existente = p.web
        if (existente != null) return existente
        val w = WebViewFondo(this)
        w.audioActivo = { audioReciente() }
        // Debe ir antes que cualquier otra llamada al WebView: la pestaña privada usa su propio perfil aislado.
        if (p.privada && perfilesOk) WebViewCompat.setProfile(w, PERFIL_PRIVADO)
        w.setBackgroundColor(paleta.fondo)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.setDisplayZoomControls(false)
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(true)
        s.safeBrowsingEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = if (p.privada) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        if (p.sinConexion) {
            // Visor de una página guardada: solo lee su archivo. Sin red (ni imágenes, ni enlaces), sin JavaScript y sin almacenamiento.
            s.allowFileAccess = true
            s.javaScriptEnabled = false
            s.domStorageEnabled = false
            s.databaseEnabled = false
            s.blockNetworkLoads = true
            s.setGeolocationEnabled(false)
        }
        if (p.escritorio) s.userAgentString = uaEscritorio
        aplicarFondo(w)
        w.setWebViewClient(ClienteWeb(p))
        w.setWebChromeClient(ClienteChrome(p))
        w.setDownloadListener { url, ua, disposicion, mime, _ ->
            if (ReproductorExterno.esMedio(url, mime)) {
                // Vídeo, audio o torrent: según Ajustes › «Enlaces multimedia y magnets», se descarga, se pregunta o va a otra app.
                gestionarMedio(url, mime, p, ua, URLUtil.guessFileName(url, disposicion, mime), "Descargar") {
                    descargar(url, ua, disposicion, mime, p.privada)
                }
            } else {
                descargar(url, ua, disposicion, mime, p.privada)
            }
        }
        w.setFindListener { ordinal, total, _ ->
            contadorBuscar.text = if (total == 0) "0/0" else "${ordinal + 1}/$total"
        }
        w.setOnTouchListener { v, ev -> alTocar(v as WebView, p, ev) }
        w.setOnLongClickListener { v -> menuContextual(v as WebView, p) }
        aplicarAjustesWeb(w)
        anuncios.instalar(w)
        agenteDev.instalar(w)
        silenciador.instalar(w, p.silenciada)
        p.web = w
        return w
    }

    @Suppress("DEPRECATION")
    private fun aplicarAjustesWeb(w: WebView) {
        val u = w.url
        aplicarOscuro(w, oscuroPara(if (u == null) "" else Omnibox.hostDe(u).lowercase()))
        w.settings.blockNetworkImage = imagenesBloqueadas(w)
        gestorCookies(w).setAcceptThirdPartyCookies(w, ajustes.cookiesTerceros)
    }

    private fun aplicarOscuro(w: WebView, oscuro: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            w.settings.isAlgorithmicDarkeningAllowed = oscuro
        } else if (Build.VERSION.SDK_INT >= 29) {
            w.settings.forceDark = if (oscuro) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    /** Oscurecer páginas en [host]: la regla del sitio (siempre / nunca) manda sobre el ajuste general. */
    private fun oscuroPara(host: String): Boolean = when (ajustes.modoOscuroEn(host)) {
        1 -> true
        2 -> false
        else -> ajustes.oscurecerPaginas
    }

    /** Ahorro de datos activo ahora: siempre, o solo si la conexión actual es de datos limitados (móvil). */
    private fun ahorroActivo(): Boolean = when (ajustes.ahorroDatos) {
        1 -> true
        2 -> try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
        } catch (e: Exception) {
            false
        }
        else -> false
    }

    /** El ahorro de datos no se aplica al visor de páginas guardadas (sus recursos vienen del archivo, no de la red). */
    private fun imagenesBloqueadas(w: WebView): Boolean = ahorroActivo() && !w.settings.blockNetworkLoads

    /**
     * Antes de cargar una página (escrita, enlace o redirección de la web): versión de escritorio, modo oscuro y ahorro de datos
     * según las reglas del sitio y los ajustes. Se llama solo al empezar a navegar, sin temporizadores.
     */
    private fun prepararNavegacion(p: Pestana, w: WebView, host: String) {
        val h = host.lowercase()
        val escritorio = p.escritorio || (h.isNotEmpty() && ajustes.escritorioEn(h))
        if (escritorio != (w.settings.userAgentString == uaEscritorio)) {
            w.settings.userAgentString = if (escritorio) uaEscritorio else null
            w.settings.useWideViewPort = true
            w.settings.loadWithOverviewMode = true
        }
        aplicarOscuro(w, oscuroPara(h))
        w.settings.blockNetworkImage = imagenesBloqueadas(w)
    }

    /** Gestor de cookies del perfil al que pertenece el WebView (el privado tiene el suyo). */
    private fun gestorCookies(w: WebView): CookieManager {
        if (perfilesOk) {
            try {
                return WebViewCompat.getProfile(w).cookieManager
            } catch (e: Throwable) {
                // Se usa el gestor general.
            }
        }
        return CookieManager.getInstance()
    }

    private fun gestorCookiesPrivado(): CookieManager? {
        if (!perfilesOk) return null
        return try {
            ProfileStore.getInstance().getOrCreateProfile(PERFIL_PRIVADO).cookieManager
        } catch (e: Throwable) {
            null
        }
    }

    /** Borra cookies, almacenamiento y caché de las pestañas privadas (el perfil entero). No hace nada si sigue en uso. */
    private fun borrarPerfilPrivado() {
        ExcepcionesSsl.olvidarPrivadas()
        if (!perfilesOk) return
        try {
            ProfileStore.getInstance().deleteProfile(PERFIL_PRIVADO)
        } catch (e: Throwable) {
            // En uso o inexistente: se reintenta al cerrar la última pestaña privada o al arrancar.
        }
    }

    private fun crearPestana(privada: Boolean, url: String?, cargada: Boolean, activarYa: Boolean = true): Pestana? {
        if (privada && !perfilesOk) {
            toast("Tu WebView no admite pestañas privadas aisladas. Actualiza «Android System WebView».")
            return null
        }
        if (pestanas.size >= MAX_PESTANAS) {
            toast("Has alcanzado el máximo de pestañas")
            return null
        }
        val p = Pestana(siguienteId++, privada)
        p.cargada = cargada
        p.pendiente = url
        p.inicio = (url == null && !cargada)
        pestanas.add(p)
        if (activarYa) activar(p)
        return p
    }

    private fun nuevaPestana(url: String?, privada: Boolean = false): Pestana? =
        crearPestana(privada, url, false)

    private fun activar(p: Pestana) {
        val anterior = actual
        val ahoraUso = android.os.SystemClock.elapsedRealtime()
        if (anterior != null) anterior.ultimoUsoMs = ahoraUso
        p.ultimoUsoMs = ahoraUso
        if (anterior != null && anterior !== p) retirarAnterior(anterior)
        actual = p
        if (p === sonando) {
            // Se vuelve a la pestaña que sonaba en segundo plano: recupera su tamaño normal.
            sonando = null
            manejadorSonando.removeCallbacks(tareaSonando)
            p.web?.let { restaurarVista(it) }
        }
        val w = webDe(p)
        if (w.parent == null) {
            contenedor.addView(
                w,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        if (p.suspendida) restaurarSuspendida(p, w)
        w.onResume()
        if (!p.cargada) {
            p.cargada = true
            val pend = p.pendiente
            p.pendiente = null
            if (pend != null) {
                p.inicio = false
                p.url = pend
                cargarUrl(p, w, pend)
            } else {
                mostrarInicio(p)
            }
        }
        cerrarBusqueda()
        actualizarUi()
        comprobarSuena()
        if (::herramientas.isInitialized) herramientas.alCambiarPestana()
        revisarSuspension(false)
    }

    // ---------------------------------------------------------------- suspender pestañas inactivas

    private val revisandoSuspension = HashSet<Int>()

    /**
     * Una pestaña se puede suspender si no es la activa, no suena ni se está leyendo en voz alta, no es privada ni la página de inicio,
     * ya terminó de cargar y es una web normal.
     */
    private fun elegibleSuspension(p: Pestana): Boolean =
        p !== actual && p !== sonando && p !== pestanaVoz && !p.privada && !p.inicio && !p.sinConexion && !p.lectura && !p.suena &&
            !p.eligiendo && !p.oculta && p.web != null && p.progreso >= 100 && p.url.startsWith("http")

    /**
     * Se llama solo con eventos (cambio de pestaña, app oculta, poca memoria del sistema), nunca con un temporizador.
     * [urgente]: el sistema pide memoria; basta con 2 min sin usar la pestaña. Con el ajuste desactivado no hace nada.
     */
    private fun revisarSuspension(urgente: Boolean) {
        val minutos = ajustes.suspenderMin
        if (minutos <= 0) return
        val umbral = if (urgente) 2L * 60_000L else minutos * 60_000L
        val ahora = android.os.SystemClock.elapsedRealtime()
        for (p in ArrayList(pestanas)) {
            if (!elegibleSuspension(p) || ahora - p.ultimoUsoMs < umbral) continue
            val w = p.web ?: continue
            if (!revisandoSuspension.add(p.id)) continue
            w.evaluateJavascript(JS_PUEDE_SUSPENDER) { r ->
                revisandoSuspension.remove(p.id)
                if (r == "0" && p.web === w && pestanas.contains(p) && elegibleSuspension(p)) suspender(p)
            }
        }
    }

    /** Destruye el WebView de la pestaña conservando su historial, scroll y datos de formulario que guarda Chromium (en memoria). */
    private fun suspender(p: Pestana) {
        val w = p.web ?: return
        if (!elegibleSuspension(p)) return
        val estado = android.os.Bundle()
        val guardado = try {
            w.saveState(estado)
        } catch (e: Exception) {
            null
        }
        val u = w.url
        if (u != null && u.startsWith("http")) p.url = u
        contenedor.removeView(w)
        w.stopLoading()
        w.destroy()
        p.web = null
        p.suena = false
        p.suspendida = true
        p.estadoSuspendida = if (guardado != null) estado else null
    }

    /** Al volver a una pestaña suspendida: se restaura su estado; si no se puede, se vuelve a cargar su dirección. */
    private fun restaurarSuspendida(p: Pestana, w: WebView) {
        p.suspendida = false
        val estado = p.estadoSuspendida
        p.estadoSuspendida = null
        var ok = false
        if (estado != null) {
            ok = try {
                w.restoreState(estado) != null
            } catch (e: Exception) {
                false
            }
        }
        if (!ok && p.url.isNotEmpty()) cargarUrl(p, w, p.url)
    }

    private fun nombreSuspension(min: Int): String = if (min <= 0) "desactivado" else if (min < 60) "$min min" else "1 h"

    private fun cerrarPestana(p: Pestana) {
        val idx = pestanas.indexOf(p)
        if (idx < 0) return
        recordarCerrada(p)
        pestanas.removeAt(idx)
        if (p === sonando) {
            sonando = null
            manejadorSonando.removeCallbacks(tareaSonando)
        }
        val w = p.web
        if (w != null) {
            contenedor.removeView(w)
            w.stopLoading()
            w.destroy()
            p.web = null
        }
        if (p === actual) {
            actual = null
            if (pestanas.isEmpty()) {
                nuevaPestana(null)
            } else {
                activar(pestanas[minOf(idx, pestanas.size - 1)])
            }
        } else {
            actualizarUi()
        }
        if (p.privada) {
            var quedan = false
            for (q in pestanas) {
                if (q.privada) quedan = true
            }
            if (!quedan) {
                // Última pestaña privada: se destruye su perfil (cookies, almacenamiento y caché).
                borrarPerfilPrivado()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!hayPrivadas()) borrarPerfilPrivado()
                }, 1500L)
            }
        }
    }

    private fun cargarEn(p: Pestana, url: String) {
        p.suspendida = false
        p.estadoSuspendida = null
        p.cargada = true
        p.pendiente = null
        p.inicio = false
        p.url = url
        p.titulo = ""
        cargarUrl(p, webDe(p), url)
        if (p === actual) actualizarUi()
    }

    /**
     * Punto único para cargar una URL. Un sitio por http (salvo la red local) nunca se abre sin más:
     * con «HTTPS primero» se intenta https y, si falla, se pregunta; sin él, se pregunta directamente.
     */
    private fun cargarUrl(p: Pestana, w: WebView, url: String, esEnlace: Boolean = false) {
        val sinCifrar = url.startsWith("http://", ignoreCase = true)
        val host = Omnibox.hostDe(url).lowercase()
        prepararNavegacion(p, w, host)
        if (!sinCifrar || httpPermitido.contains(host) || Omnibox.esLocal(host)) {
            w.loadUrl(url)
            return
        }
        if (ajustes.httpsPrimero) {
            p.mejoradoDesde = url
            w.loadUrl("https://" + url.substring(7))
        } else {
            preguntarHttp(p, w, url, null, esEnlace)
        }
    }

    private fun preguntarHttp(p: Pestana, w: WebView, url: String, motivo: String?, esEnlace: Boolean) {
        val host = Omnibox.hostDe(url).lowercase()
        val aviso = StringBuilder()
        aviso.append(host).append(" no ofrece una conexión cifrada (HTTPS).")
        if (motivo != null) aviso.append(" (").append(motivo).append(")")
        aviso.append("\n\nSi continúas, lo que veas o envíes puede ser leído o modificado por terceros. No escribas contraseñas ni datos personales.")
        val volver: () -> Unit = {
            if (!esEnlace) mostrarBloqueoHttp(w, url, motivo)
        }
        nuevoDialogo()
            .setTitle("Conexión sin cifrar")
            .setMessage(aviso.toString())
            .setPositiveButton("Volver") { _, _ -> volver() }
            .setNegativeButton("Continuar sin cifrar") { _, _ ->
                httpPermitido.add(host)
                w.loadUrl(url)
            }
            .setOnCancelListener { volver() }
            .show()
    }

    private fun mostrarBloqueoHttp(w: WebView, url: String, motivo: String?) {
        val destino = if (motivo != null) "https://" + url.substring(7) else url
        val texto = motivo ?: "Conexión sin cifrar bloqueada."
        w.loadDataWithBaseURL(destino, Paginas.error(paleta.colores, destino, texto), "text/html", "utf-8", destino)
    }

    private fun mostrarInicio(p: Pestana) {
        p.cargada = true
        p.pendiente = null
        p.inicio = true
        p.url = ""
        p.titulo = "Nueva pestaña"
        p.progreso = 100
        val w = webDe(p)
        w.setBackgroundColor(Color.parseColor(coloresInicio().fondo))
        w.loadDataWithBaseURL("optimo://inicio", paginaInicio(), "text/html", "utf-8", "optimo://inicio")
        if (p === actual) actualizarUi()
    }

    private fun paginaInicio(): String {
        val max = ajustes.inicioMaxAccesos
        val atajos = ArrayList<Pair<String, String>>()
        when (ajustes.inicioOrigen) {
            1 -> atajos.addAll(ajustes.accesosPropios())
            2 -> atajos.addAll(Paginas.ATAJOS_POR_DEFECTO)
            else -> {
                for (e in marcadores.todos()) {
                    atajos.add(Pair(e.titulo, e.url))
                    if (atajos.size >= max) break
                }
                if (atajos.isEmpty()) atajos.addAll(Paginas.ATAJOS_POR_DEFECTO)
            }
        }
        val cfg = Paginas.ConfigInicio(
            ajustes.inicioTitulo, ajustes.inicioSubtitulo, ajustes.inicioBuscador, ajustes.inicioAccesos,
            max, ajustes.inicioCuadrados, ajustes.inicioEnlacePersonalizar
        )
        return Paginas.inicio(coloresInicio(), Omnibox.motor(ajustes.motor), atajos, cfg)
    }

    /** Colores de la página de inicio según el tema elegido (por defecto, los del sistema). */
    private fun coloresInicio(): Colores {
        return when (ajustes.inicioTema) {
            "auto" -> paleta.colores
            "personalizado" -> Paginas.coloresPersonalizados(
                ajustes.inicioColorFondo, ajustes.inicioColorTexto, ajustes.inicioColorAcento, ajustes.inicioColorSuperficie
            )
            else -> Paginas.TEMAS_INICIO.firstOrNull { it.id == ajustes.inicioTema }?.colores ?: paleta.colores
        }
    }

    /** Vuelve a pintar las pestañas que muestran la página de inicio (tras cambiar su configuración). */
    private fun refrescarInicio() {
        for (q in pestanas) {
            val w = q.web ?: continue
            if (q.inicio) {
                w.setBackgroundColor(Color.parseColor(coloresInicio().fondo))
                w.loadDataWithBaseURL("optimo://inicio", paginaInicio(), "text/html", "utf-8", "optimo://inicio")
            }
        }
    }

    private fun fijarUrl(p: Pestana, url: String?) {
        if (url == null || p.sinConexion) return
        if (url.startsWith("optimo://") || ((url.startsWith("data:") || url == "about:blank") && p.inicio)) {
            p.inicio = true
            p.url = ""
        } else {
            p.inicio = false
            p.url = url
        }
    }

    private fun ir(texto: String) {
        val r = Omnibox.resolver(texto, Omnibox.motor(ajustes.motor))
        when (r) {
            is Omnibox.Resolucion.Vacio -> return
            is Omnibox.Resolucion.Navegar -> cargarEnActual(r.url)
            is Omnibox.Resolucion.Buscar -> cargarEnActual(r.url)
            is Omnibox.Resolucion.Externo -> abrirExterno(r.url)
            is Omnibox.Resolucion.NoSoportado -> toast("Esquema no soportado: " + r.esquema)
        }
        omnibox.clearFocus()
        ocultarTeclado(omnibox)
    }

    private fun cargarEnActual(url: String) {
        val p = actual
        if (p == null) {
            nuevaPestana(url)
        } else {
            cargarEn(p, url)
            p.web?.requestFocus()
        }
    }

    private fun recargarOParar() {
        val p = actual ?: return
        val w = p.web ?: return
        if (p.progreso in 0..99) {
            w.stopLoading()
            p.progreso = 100
            actualizarUi()
        } else if (p.inicio) {
            mostrarInicio(p)
        } else {
            w.reload()
        }
    }

    private fun alternarMarcador() {
        val p = actual ?: return
        if (p.inicio || p.url.isEmpty()) {
            toast("No hay nada que guardar")
            return
        }
        if (marcadores.contiene(p.url)) {
            marcadores.quitar(p.url)
            toast("Marcador eliminado")
        } else if (marcadores.agregar(tituloDe(p), p.url, 0L)) {
            toast("Marcador guardado (mantén pulsada ☆ para elegir carpeta)")
        } else {
            toast("Has alcanzado el máximo de marcadores")
        }
        actualizarUi()
    }

    private fun alternarEscritorio() {
        val p = actual ?: return
        p.escritorio = !p.escritorio
        val w = p.web ?: return
        w.settings.userAgentString = if (p.escritorio) uaEscritorio else null
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
        if (p.inicio) mostrarInicio(p) else w.reload()
        toast(if (p.escritorio) "Versión de escritorio" else "Versión móvil")
    }

    // ---------------------------------------------------------------- buscar en la pagina

    private fun mostrarBusqueda() {
        barraBuscar.visibility = View.VISIBLE
        campoBuscar.setText("")
        contadorBuscar.text = ""
        campoBuscar.requestFocus()
        mostrarTeclado(campoBuscar)
    }

    private fun buscarEnPagina(q: String) {
        val w = actual?.web ?: return
        if (q.isEmpty()) {
            w.clearMatches()
            contadorBuscar.text = ""
        } else {
            w.findAllAsync(q)
        }
    }

    private fun cerrarBusqueda() {
        if (barraBuscar.visibility == View.VISIBLE) {
            actual?.web?.clearMatches()
            barraBuscar.visibility = View.GONE
            ocultarTeclado(campoBuscar)
        }
    }

    // ---------------------------------------------------------------- menus y dialogos

    private fun si(b: Boolean): String = if (b) "sí" else "no"

    private fun mostrarMenu() {
        val p = actual ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Nueva pestaña") { nuevaPestana(null) }
        add("Nueva pestaña privada") { nuevaPestana(null, true) }
        add("Buscar en la página") { mostrarBusqueda() }
        val medio = p.medioDetectado
        if (medio != null && AppsMedia.apps(this, medio, null).isNotEmpty()) {
            add("▶ Reproducir vídeo de la página en otra app…") {
                elegirAppMedio(medio, null, p, p.web?.settings?.userAgentString, p.titulo)
            }
        }
        if (!p.privada && ReproductorExterno.esMega(p.url) && AppsMedia.apps(this, p.url, null).isNotEmpty()) {
            add("▶ Abrir este enlace de Mega en otra app…") {
                elegirAppMedio(p.url, null, p, null, p.titulo)
            }
        }
        add("Marcadores") { mostrarMarcadores() }
        add("Páginas guardadas (" + PaginasGuardadas.todas().size + ")…") { mostrarPaginasGuardadas() }
        if (!p.inicio && p.url.startsWith("http")) add("Buscar enlaces en la página…") { buscarEnlaces(p) }
        if (!p.inicio && p.url.startsWith("http")) add("Rastrear este sitio…") { configurarRastreo(p) }
        val ur = ultimoRastreo
        if (ur != null) add("Ver el último rastreo (" + ur.enlaces.size + " enlaces)") { mostrarResultadoRastreo(p, ur) }
        if (ListasEnlaces.todas().isNotEmpty()) add("Listas de enlaces guardadas (" + ListasEnlaces.todas().size + ")…") { mostrarListasEnlaces() }
        add("Historial") { mostrarLista("Historial", historial, "El historial está vacío", true) }
        if (!p.inicio && p.url.isNotEmpty()) {
            add("Compartir página") { compartir(p.url) }
            add("Bloquear un anuncio (tocar)") { iniciarSeleccion(p) }
            if (p.url.startsWith("http")) {
                val pausadoAqui = bloqueoPausadoEnPagina(p.url)
                if (!p.privada && (ajustes.bloquearAnuncios || pausadoAqui)) {
                    add("Bloqueo de anuncios en este sitio: " + (if (pausadoAqui) "pausado (reanudar)" else "activo (pausar)")) { alternarPausaBloqueo(p) }
                }
                if (p.web?.settings?.blockNetworkImage == true) {
                    add("Cargar imágenes en esta página (ahorro de datos)") {
                        val w = p.web
                        if (w != null) {
                            w.settings.blockNetworkImage = false
                            w.reload()
                        }
                    }
                }
                if (!p.privada && !p.sinConexion) add("Guardar página para leer sin conexión") { guardarSinConexion(p) }
                add("Quitar capa que tapa la página") { quitarMuros(p) }
                add("Modo lectura") { abrirModoLectura(p) }
                // Se guardan en disco con el nombre del sitio: no se ofrecen en pestañas privadas.
                if (!p.privada) add("Personalizar este sitio…") { mostrarPersonalizarSitio(p) }
            }
            add("Herramientas de desarrollo") { herramientas.alternar() }
        }
        if (!p.inicio && p.url.startsWith("http")) {
            if (!p.privada) add("Privacidad de este sitio…") { mostrarPrivacidadSitio(Omnibox.hostDe(p.url)) }
            val ok = ajustes.emergentes == 2 || ajustes.emergentesPermitidosEn(Omnibox.hostDe(p.url))
            add("Emergentes y redirecciones en este sitio: " + (if (ok) "permitidas" else "bloqueadas")) {
                if (ajustes.emergentes == 2) {
                    toast("Están permitidas en todos los sitios (Ajustes › Ventanas emergentes)")
                } else {
                    ajustes.permitirEmergentes(Omnibox.hostDe(p.url), !ok)
                    toast(if (ok) "Bloqueadas en este sitio" else "Permitidas en este sitio")
                }
            }
        }
        add("Versión de escritorio: " + si(p.escritorio)) { alternarEscritorio() }
        add("Ir a la página de inicio") { mostrarInicio(p) }
        add("Descargas") { abrirDescargas() }
        add("Ajustes") { mostrarAjustes() }
        if (cerradas.isNotEmpty()) add("Reabrir pestaña cerrada (" + cerradas.size + ")…") { mostrarCerradas() }
        add("Cerrar todas las pestañas") { cerrarTodas() }
        val partes = ArrayList<String>()
        if (ajustes.bloquearAnuncios) partes.add("Bloqueados en esta página: " + p.bloqueados.get())
        val em = p.emergentesBloqueados.get()
        if (em > 0) partes.add("Emergentes y redirecciones bloqueadas: $em")
        opciones(if (partes.isEmpty()) null else partes.joinToString("\n"), items)
    }

    private fun mostrarAjustes() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Buscador: " + Omnibox.motor(ajustes.motor).nombre) { elegirMotor() }
        add("Bloquear anuncios y rastreadores: " + si(ajustes.bloquearAnuncios)) {
            ajustes.bloquearAnuncios = !ajustes.bloquearAnuncios
            reinstalarAnuncios()
            mostrarAjustes()
        }
        add("Pantalla de inicio…") { mostrarAjustesInicio() }
        add("Enlaces multimedia, magnets y Mega…") { mostrarAjustesMedia() }
        add("Ventanas emergentes y redirecciones…") { mostrarAjustesEmergentes() }
        add("Listas de bloqueo…") { mostrarListas() }
        add("Técnicas contra anuncios…") { elegirTecnicasAnuncios() }
        add("Reglas de sitios y anuncios (" + ReglasUsuario.total + ")…") { mostrarReglasUsuario() }
        add("Herramientas de desarrollo: captura desde el inicio: " + si(ajustes.devCaptura)) {
            ajustes.devCaptura = !ajustes.devCaptura
            reinstalarDev()
            toast("Recarga las páginas abiertas para aplicarlo")
            mostrarAjustes()
        }
        add("Usar HTTPS primero: " + si(ajustes.httpsPrimero)) {
            ajustes.httpsPrimero = !ajustes.httpsPrimero
            mostrarAjustes()
        }
        add("Oscurecer páginas web: " + si(ajustes.oscurecerPaginas)) {
            ajustes.oscurecerPaginas = !ajustes.oscurecerPaginas
            for (q in pestanas) {
                val w = q.web
                if (w != null) aplicarAjustesWeb(w)
            }
            mostrarAjustes()
        }
        add("Ahorro de datos (sin imágenes): " + NOMBRES_AHORRO[ajustes.ahorroDatos]) {
            ajustes.ahorroDatos = (ajustes.ahorroDatos + 1) % 3
            for (q in pestanas) {
                val w = q.web
                if (w != null) w.settings.blockNetworkImage = imagenesBloqueadas(w)
            }
            toast("Se aplica a las páginas que cargues desde ahora")
            mostrarAjustes()
        }
        add("Permitir cookies de terceros: " + si(ajustes.cookiesTerceros)) {
            ajustes.cookiesTerceros = !ajustes.cookiesTerceros
            for (q in pestanas) {
                val w = q.web
                if (w != null) aplicarAjustesWeb(w)
            }
            mostrarAjustes()
        }
        add("Reproducción en segundo plano…") { mostrarSegundoPlano() }
        add("Lectura y voz…") { mostrarAjustesLectura() }
        add("Copia de seguridad…") { mostrarCopiaSeguridad() }
        add("Privacidad y datos…") { mostrarPrivacidad() }
        add("Acerca de Optimo") { acercaDe() }
        opciones("Ajustes", items)
    }

    // ---------------------------------------------------------------- ventanas emergentes y redirecciones

    /** Dominio «base» aproximado (sin lista de sufijos públicos): example.com, example.co.uk. Las IP se comparan enteras. */
    private fun dominioBase(host: String): String {
        val h = host.lowercase().trimEnd('.')
        val partes = h.split('.')
        if (partes.size <= 2 || partes.all { x -> x.isNotEmpty() && x.all { c -> c.isDigit() } }) return h
        val ult = partes[partes.size - 1]
        val pen = partes[partes.size - 2]
        val n = if (ult.length == 2 && pen.length <= 3) 3 else 2
        return partes.takeLast(n).joinToString(".")
    }

    private fun mismoSitio(a: String, b: String): Boolean = dominioBase(a) == dominioBase(b)

    /** La emergente oculta pasa el control: se muestra. */
    private fun mostrarEmergente(q: Pestana) {
        if (!q.oculta || !pestanas.contains(q)) return
        q.oculta = false
        activar(q)
    }

    /** Cierra una emergente bloqueada y devuelve a la pestaña de origen. Se puede llamar desde cualquier hilo. */
    private fun cerrarEmergente(q: Pestana) {
        if (q.cerrando) return
        q.cerrando = true
        val origen = q.abiertaPor
        origen?.emergentesBloqueados?.incrementAndGet()
        manejadorPrincipal.post {
            if (!pestanas.contains(q)) return@post
            val eraActual = q === actual
            q.oculta = false
            cerrarPestana(q)
            if (eraActual && origen != null && pestanas.contains(origen)) activar(origen)
            toast("Pestaña emergente bloqueada")
            if (origen === actual) actualizarUi()
        }
    }

    /**
     * Vigila una emergente no confiable en sus primeros segundos: se cierra si va a un dominio de anuncios o, en modo estricto,
     * a un sitio distinto del enlace que se tocó. Devuelve true si se ha cerrado. Si la dirección pasa, se muestra la pestaña.
     * Se llama desde el hilo de red (shouldInterceptRequest) y desde el principal.
     */
    private fun vigilarEmergente(q: Pestana, uri: Uri): Boolean {
        if (android.os.SystemClock.uptimeMillis() - q.nacidoMs > 15000L) return false
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return false
        val anuncio = ajustes.bloquearAnuncios && AdBlock.bloquea(host)
        val esperado = q.enlaceEsperado
        val otroSitio = esperado != null && !mismoSitio(host, esperado)
        if (anuncio || otroSitio) {
            cerrarEmergente(q)
            return true
        }
        q.enlaceEsperado = null
        if (q.oculta) manejadorPrincipal.post { mostrarEmergente(q) }
        return false
    }

    /**
     * Navegación de la propia pestaña que inicia la página (no tú): se bloquea si va a un dominio de anuncios o, sin que hayas
     * tocado nada en 5 s, a otro sitio. Los redirects HTTP normales (acortadores, inicio de sesión) y los sitios con permiso pasan.
     */
    private fun bloquearNavegacionAutomatica(p: Pestana, uri: Uri, request: WebResourceRequest): Boolean {
        if (!ajustes.bloquearRedirecciones || request.isRedirect) return false
        if (p.inicio || p.url.isEmpty()) return false
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return false
        if (ajustes.emergentes == 2 || ajustes.emergentesPermitidosEn(Omnibox.hostDe(p.url))) return false
        val actualHost = Omnibox.hostDe(p.url).lowercase()
        val aAnuncio = ajustes.bloquearAnuncios && !mismoSitio(host, actualHost) && AdBlock.bloquea(host)
        val sinTocar = !request.hasGesture() && !mismoSitio(host, actualHost) &&
            android.os.SystemClock.uptimeMillis() - p.toqueMs > 5000L
        if (!aAnuncio && !sinTocar) return false
        p.emergentesBloqueados.incrementAndGet()
        toast(if (aAnuncio) "Redirección a anuncios bloqueada" else "Redirección automática bloqueada (" + host + ")")
        if (p === actual) actualizarUi()
        return true
    }

    private fun mostrarAjustesEmergentes() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val modos = listOf(
            "Bloquear las de anuncios (recomendado)",
            "Estricto: solo si tocas un enlace",
            "Permitir todas (como antes)"
        )
        items.add(Pair("Pestañas emergentes: " + modos[ajustes.emergentes]) { elegirModoEmergentes() })
        items.add(Pair("Bloquear redirecciones automáticas: " + si(ajustes.bloquearRedirecciones)) {
            ajustes.bloquearRedirecciones = !ajustes.bloquearRedirecciones
            mostrarAjustesEmergentes()
        })
        items.add(Pair("Sitios con permiso (" + ajustes.emergentesPermitidos().size + ")…") { editarSitiosEmergentes() })
        opciones("Ventanas emergentes y redirecciones", items)
    }

    private fun elegirModoEmergentes() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun modo(n: Int, texto: String) {
            items.add(Pair((if (ajustes.emergentes == n) "● " else "") + texto) {
                ajustes.emergentes = n
                mostrarAjustesEmergentes()
            })
        }
        modo(0, "Bloquear las de anuncios (recomendado): cierra las pestañas que van a dominios de las listas y limita a una por toque y cada 4 s")
        modo(1, "Estricto: solo se abren si tocas un enlace y la pestaña va a ese mismo sitio (puede romper algún inicio de sesión)")
        modo(2, "Permitir todas (como antes de 1.9.1)")
        opciones("Pestañas emergentes", items)
    }

    private fun editarSitiosEmergentes() {
        val sitios = ajustes.emergentesPermitidos().sorted()
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (h in sitios) {
            items.add(Pair("✕ " + h) {
                ajustes.permitirEmergentes(h, false)
                editarSitiosEmergentes()
            })
        }
        items.add(Pair("Listo") { mostrarAjustesEmergentes() })
        opciones(if (sitios.isEmpty()) "Ningún sitio con permiso (se añaden desde el menú ⋮ de cada página)" else "Sitios con permiso (toca uno para quitarlo)", items)
    }

    // ---------------------------------------------------------------- ajustes de la pantalla de inicio

    private val NOMBRES_ORIGEN_INICIO = listOf("marcadores recientes", "lista propia", "predeterminados")

    private fun nombreTemaInicio(): String =
        Paginas.TEMAS_INICIO.firstOrNull { it.id == ajustes.inicioTema }?.nombre?.substringBefore(" (") ?: "Automático"

    private fun mostrarAjustesInicio() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        fun cambiar(a: () -> Unit): () -> Unit = {
            a()
            refrescarInicio()
            mostrarAjustesInicio()
        }
        add("Colores: " + nombreTemaInicio() + "…") { elegirTemaInicio() }
        add("Mostrar accesos directos / marcadores: " + si(ajustes.inicioAccesos), cambiar {
            ajustes.inicioAccesos = !ajustes.inicioAccesos
        })
        if (ajustes.inicioAccesos) {
            add("Origen de los accesos: " + NOMBRES_ORIGEN_INICIO[ajustes.inicioOrigen], cambiar {
                ajustes.inicioOrigen = (ajustes.inicioOrigen + 1) % 3
            })
            if (ajustes.inicioOrigen == 1) {
                add("Editar mis accesos (" + ajustes.accesosPropios().size + ")…") { editarAccesosPropios() }
            }
            add("Cantidad de accesos: " + ajustes.inicioMaxAccesos, cambiar {
                ajustes.inicioMaxAccesos = if (ajustes.inicioMaxAccesos >= 12) 4 else ajustes.inicioMaxAccesos + 4
            })
            add("Forma de los iconos: " + (if (ajustes.inicioCuadrados) "cuadrados" else "redondos"), cambiar {
                ajustes.inicioCuadrados = !ajustes.inicioCuadrados
            })
        }
        add("Mostrar el buscador: " + si(ajustes.inicioBuscador), cambiar {
            ajustes.inicioBuscador = !ajustes.inicioBuscador
        })
        add("Título: " + (if (ajustes.inicioTitulo.isBlank()) "(ninguno)" else ajustes.inicioTitulo) + "…") {
            pedirTextoOpcional("Título de la pantalla de inicio (vacío = sin título)", ajustes.inicioTitulo) { t ->
                ajustes.inicioTitulo = t
                refrescarInicio()
                mostrarAjustesInicio()
            }
        }
        add("Subtítulo: " + (if (ajustes.inicioSubtitulo.isBlank()) "(ninguno)" else ajustes.inicioSubtitulo) + "…") {
            pedirTextoOpcional("Subtítulo (vacío = sin subtítulo)", ajustes.inicioSubtitulo) { t ->
                ajustes.inicioSubtitulo = t
                refrescarInicio()
                mostrarAjustesInicio()
            }
        }
        add("Enlace «Personalizar» al pie de la pantalla: " + si(ajustes.inicioEnlacePersonalizar), cambiar {
            ajustes.inicioEnlacePersonalizar = !ajustes.inicioEnlacePersonalizar
        })
        add("Restablecer el aspecto original") {
            ajustes.restablecerInicio()
            refrescarInicio()
            toast("Pantalla de inicio restablecida")
            mostrarAjustesInicio()
        }
        opciones("Pantalla de inicio", items)
    }

    private fun elegirTemaInicio() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (t in Paginas.TEMAS_INICIO) {
            val marca = if (t.id == ajustes.inicioTema) "● " else ""
            items.add(Pair(marca + t.nombre) {
                ajustes.inicioTema = t.id
                refrescarInicio()
                if (t.id == "personalizado") editarColoresInicio() else mostrarAjustesInicio()
            })
        }
        opciones("Colores de la pantalla de inicio", items)
    }

    /** Los cuatro colores del tema personalizado, uno a uno, en hexadecimal (#RRGGBB). */
    private fun editarColoresInicio() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun color(nombre: String, valor: String, guardar: (String) -> Unit) {
            items.add(Pair(nombre + ": " + valor) {
                pedirTexto(nombre + " (#RRGGBB)", valor) { t ->
                    val h = Paginas.normalizarHex(t)
                    if (h == null) {
                        toast("Color no válido. Ejemplo: #1E88E5")
                    } else {
                        guardar(h)
                        refrescarInicio()
                    }
                    editarColoresInicio()
                }
            })
        }
        color("Fondo", ajustes.inicioColorFondo) { ajustes.inicioColorFondo = it }
        color("Texto", ajustes.inicioColorTexto) { ajustes.inicioColorTexto = it }
        color("Acento (título y botones)", ajustes.inicioColorAcento) { ajustes.inicioColorAcento = it }
        color("Iconos y buscador", ajustes.inicioColorSuperficie) { ajustes.inicioColorSuperficie = it }
        items.add(Pair("Listo") { mostrarAjustesInicio() })
        opciones("Colores personalizados", items)
    }

    /** Lista propia de accesos directos de la pantalla de inicio: añadir, añadir la página actual o quitar. */
    private fun editarAccesosPropios() {
        val lista = ajustes.accesosPropios()
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair("➕ Añadir un acceso…") {
            pedirTexto("Dirección del sitio", "https://") { u ->
                val url = normalizarUrl(u)
                if (url == null) {
                    toast("Dirección no válida")
                    editarAccesosPropios()
                } else {
                    pedirTexto("Nombre", Omnibox.hostDe(url).removePrefix("www.")) { nombre ->
                        ajustes.guardarAccesosPropios(ajustes.accesosPropios() + Pair(nombre, url))
                        refrescarInicio()
                        editarAccesosPropios()
                    }
                }
            }
        })
        val p = actual
        if (p != null && !p.inicio && p.url.startsWith("http")) {
            items.add(Pair("➕ Añadir la página actual") {
                val nombre = if (p.titulo.isNotBlank()) p.titulo else Omnibox.hostDe(p.url)
                ajustes.guardarAccesosPropios(ajustes.accesosPropios() + Pair(nombre, p.url))
                refrescarInicio()
                editarAccesosPropios()
            })
        }
        for ((i, e) in lista.withIndex()) {
            items.add(Pair("✕ " + e.first + " — " + Omnibox.hostDe(e.second)) {
                val nueva = ArrayList(ajustes.accesosPropios())
                if (i < nueva.size) nueva.removeAt(i)
                ajustes.guardarAccesosPropios(nueva)
                refrescarInicio()
                editarAccesosPropios()
            })
        }
        items.add(Pair("Listo") { mostrarAjustesInicio() })
        opciones("Mis accesos (toca uno para quitarlo)", items)
    }

    /** Como pedirTexto, pero admite dejar el campo vacío (para quitar el título o el subtítulo). */
    private fun pedirTextoOpcional(titulo: String, inicial: String, alAceptar: (String) -> Unit) {
        val campo = EditText(this)
        campo.setSingleLine(true)
        campo.setText(inicial)
        campo.setSelectAllOnFocus(true)
        val caja = FrameLayout(this)
        caja.setPadding(20.dp(), 8.dp(), 20.dp(), 0)
        caja.addView(campo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        nuevoDialogo()
            .setTitle(titulo)
            .setView(caja)
            .setPositiveButton("Aceptar") { _, _ -> alAceptar(campo.text.toString().trim().take(80)) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarSegundoPlano() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Al cambiar de pestaña con audio: " + NOMBRES_AUDIO_PESTANAS[ajustes.audioPestanas]) {
            ajustes.audioPestanas = (ajustes.audioPestanas + 1) % 3
            mostrarSegundoPlano()
        }
        add("Suspender pestañas inactivas: " + nombreSuspension(ajustes.suspenderMin)) {
            ajustes.suspenderMin = when (ajustes.suspenderMin) {
                0 -> 15
                15 -> 30
                30 -> 60
                else -> 0
            }
            mostrarSegundoPlano()
        }
        add("Seguir sonando con la pantalla bloqueada: " + si(ajustes.segundoPlano)) {
            ajustes.segundoPlano = !ajustes.segundoPlano
            for (q in pestanas) q.web?.let { aplicarFondo(it) }
            if (ajustes.segundoPlano) pedirPermisoNotificaciones()
            reinstalarAnuncios()
            toast("Recarga la página de YouTube para aplicarlo")
            mostrarSegundoPlano()
        }
        add("Imagen en imagen al salir (vídeo a pantalla completa): " + si(ajustes.pip)) {
            ajustes.pip = !ajustes.pip
            mostrarSegundoPlano()
        }
        add("Batería de Optimo («Sin restricciones»)…") { abrirAjustesBateria() }
        opciones("Reproducción en segundo plano", items)
    }

    private fun abrirAjustesBateria() {
        toast("Pon el ahorro de batería de Optimo en «Sin restricciones»")
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))
            )
        } catch (e: Exception) {
            toast("No se pudo abrir los ajustes de la app")
        }
    }

    private fun mostrarListas() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Mis dominios bloqueados (" + ReglasUsuario.listaDominios().size + ")…") { mostrarDominiosPropios() }
        add("Elegir listas…") { elegirListas() }
        add("Añadir lista por URL (https)…") { anadirLista() }
        add("Actualizar ahora") { actualizarListas(false) }
        add("Actualizar solo con Wi-Fi: " + si(ajustes.soloWifi)) {
            ajustes.soloWifi = !ajustes.soloWifi
            mostrarListas()
        }
        if (ajustes.propias().isNotEmpty()) add("Quitar una lista añadida…") { quitarLista() }
        opciones(ListasManager.resumen(), items)
    }

    private fun actualizarListas(forzarCompilar: Boolean) {
        toast("Actualizando listas…")
        ListasManager.actualizarAsync(forzarCompilar) { mensaje -> toast(mensaje) }
    }

    private fun elegirListas() {
        val todas = ListasManager.todas()
        val nombres = Array(todas.size) { i -> todas[i].nombre + " · " + todas[i].tam }
        val activas = ajustes.listasActivas()
        val marcadas = BooleanArray(todas.size) { i -> activas.contains(todas[i].id) }
        nuevoDialogo()
            .setTitle("Listas de bloqueo")
            .setMultiChoiceItems(nombres, marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton("Aplicar") { _, _ ->
                val nuevas = HashSet<String>()
                for (i in todas.indices) if (marcadas[i]) nuevas.add(todas[i].id)
                ajustes.ponerListasActivas(nuevas)
                actualizarListas(true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun anadirLista() {
        val campo = android.widget.EditText(this)
        campo.hint = "https://…"
        campo.setSingleLine(true)
        nuevoDialogo()
            .setTitle("Añadir lista por URL")
            .setView(campo)
            .setPositiveButton("Añadir") { _, _ ->
                val lista = ajustes.agregarPropia(campo.text.toString())
                if (lista == null) toast("URL no válida: debe empezar por https://")
                else actualizarListas(true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun quitarLista() {
        val propias = ajustes.propias()
        val nombres = Array(propias.size) { i -> propias[i].nombre }
        nuevoDialogo()
            .setTitle("Quitar lista")
            .setItems(nombres) { _, i ->
                ajustes.quitarPropia(propias[i].id)
                actualizarListas(true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun reinstalarDev() {
        for (q in pestanas) {
            val w = q.web
            if (w != null) agenteDev.instalar(w)
        }
    }

    private fun reinstalarAnuncios() {
        for (q in pestanas) {
            val w = q.web
            if (w != null) anuncios.instalar(w)
        }
    }

    private fun elegirTecnicasAnuncios() {
        val tecnicas = MotorAnuncios.TECNICAS
        val nombres = Array(tecnicas.size) { i -> tecnicas[i].nombre }
        val marcadas = BooleanArray(tecnicas.size) { i -> ajustes.tecnica(tecnicas[i].id) }
        nuevoDialogo()
            .setTitle("Técnicas contra anuncios")
            .setMultiChoiceItems(nombres, marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton("Aplicar") { _, _ ->
                for (i in tecnicas.indices) ajustes.ponerTecnica(tecnicas[i].id, marcadas[i])
                reinstalarAnuncios()
                toast("Recarga la página para aplicar los cambios")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun elegirMotor() {
        val nombres = Array(Omnibox.MOTORES.size) { i -> Omnibox.MOTORES[i].nombre }
        var elegido = 0
        for (i in Omnibox.MOTORES.indices) {
            if (Omnibox.MOTORES[i].id == ajustes.motor) elegido = i
        }
        nuevoDialogo()
            .setTitle("Buscador")
            .setSingleChoiceItems(nombres, elegido) { d, i ->
                ajustes.motor = Omnibox.MOTORES[i].id
                d.dismiss()
                mostrarAjustes()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun confirmarBorrado() {
        nuevoDialogo()
            .setTitle("Borrar datos de navegación")
            .setMessage("Se borrarán el historial, la caché y las cookies (se cerrarán tus sesiones en los sitios web). Los marcadores se conservan.")
            .setPositiveButton("Borrar") { _, _ -> borrarDatos() }
            .setNegativeButton("Cancelar", null)
            .show()
    }


    // ---------------------------------------------------------------- rastreador de enlaces (fase 1: la página abierta)

    private var enlacesPorExportar: String? = null
    private var enlacesDeRastreo = false
    private var rastreador: Rastreador? = null
    private var ultimoRastreo: ResultadoRastreo? = null
    private var dialogoRastreo: AlertDialog? = null

    /** Extrae los enlaces de la página abierta con `assets/enlaces/extraer.js` y los muestra clasificados. Solo mira esta página. */
    private fun buscarEnlaces(p: Pestana) {
        elegirTiposEnlaces("¿Qué enlaces buscar?", "Buscar", { buscarEnlaces(p) }) { ejecutarBusquedaEnlaces(p) }
    }

    /** Casillas con los tipos de enlace a buscar (se recuerdan) y botón de filtro propio. [reabrir] vuelve a mostrar esta ventana. */
    private fun elegirTiposEnlaces(titulo: String, boton: String, reabrir: () -> Unit, alAceptar: () -> Unit) {
        val tipos = TipoEnlace.values().filter { it != TipoEnlace.PROPIO }
        val marcados = BooleanArray(tipos.size) { (ajustes.enlacesTipos shr tipos[it].ordinal) and 1 != 0 }
        nuevoDialogo()
            .setTitle(titulo)
            .setMultiChoiceItems(Array(tipos.size) { tipos[it].etiqueta }, marcados) { _, i, v -> marcados[i] = v }
            .setPositiveButton(boton) { _, _ ->
                var m = 0
                for (i in tipos.indices) if (marcados[i]) m = m or (1 shl tipos[i].ordinal)
                ajustes.enlacesTipos = m
                alAceptar()
            }
            .setNeutralButton("Filtro propio…") { _, _ ->
                var m = 0
                for (i in tipos.indices) if (marcados[i]) m = m or (1 shl tipos[i].ordinal)
                ajustes.enlacesTipos = m
                pedirTextoOpcional("Filtro propio (extensiones, texto o /regex/, separados por comas)", ajustes.enlacesPropio) { t ->
                    ajustes.enlacesPropio = t
                    reabrir()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ejecutarBusquedaEnlaces(p: Pestana) {
        enlacesDeRastreo = false
        val w = p.web
        if (w == null || p.suspendida) {
            toast("Abre la página antes de buscar enlaces")
            return
        }
        if (p.progreso in 0..99) toast("La página aún está cargando: puede faltar algún enlace")
        val js = try {
            assets.open("enlaces/extraer.js").bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            toast("No se pudo preparar la búsqueda")
            return
        }
        toast("Buscando enlaces…")
        w.evaluateJavascript(js) { bruto ->
            val r = Enlaces.leerResultado(bruto)
            if (r == null) {
                toast("No se pudieron leer los enlaces de esta página")
            } else {
                val host = try { Uri.parse(r.url.ifEmpty { p.url }).host ?: "" } catch (e: Exception) { "" }
                val propio = ajustes.enlacesPropio.let { if (it.isBlank()) null else FiltroPropio(it) }
                val mascara = ajustes.enlacesTipos
                val todos = Enlaces.procesar(r.crudos, host, r.titulo.ifBlank { p.titulo }, propio)
                    .filter { it.tipo == TipoEnlace.PROPIO || (mascara shr it.tipo.ordinal) and 1 != 0 }
                if (todos.isEmpty()) {
                    nuevoDialogo()
                        .setTitle("Sin resultados")
                        .setMessage("No hay enlaces de los tipos elegidos en esta página.")
                        .setPositiveButton("Cambiar tipos") { _, _ -> buscarEnlaces(p) }
                        .setNegativeButton("Cerrar", null)
                        .show()
                } else {
                    mostrarEnlaces(p, "Enlaces", r.titulo.ifBlank { p.titulo }, r.url.ifEmpty { p.url }, todos, null, true)
                }
            }
        }
    }

    /** ¿Se muestra este enlace con los ajustes actuales? Magnets, Mega y nubes se ven siempre; los archivos de otros sitios solo si se activó. */
    private fun enlaceVisible(e: EnlaceEncontrado, filtro: TipoEnlace?): Boolean {
        if (filtro != null && e.tipo != filtro) return false
        if (!e.externo) return true
        return when (e.tipo) {
            TipoEnlace.MAGNET, TipoEnlace.MEGA, TipoEnlace.NUBE -> true
            else -> ajustes.enlacesOtros
        }
    }

    private fun lineaEnlace(e: EnlaceEncontrado): String {
        val sb = StringBuilder(e.tipo.etiqueta)
        if (e.host.isNotEmpty() && e.tipo != TipoEnlace.MAGNET) sb.append(" · ").append(e.host)
        if (e.tamano >= 0) sb.append(" · ").append(formatoTamano(e.tamano))
        if (e.origen.isNotEmpty()) {
            val u = Uri.parse(e.origen)
            sb.append(" · en ").append(((u.host ?: "") + (u.path ?: "")).trimEnd('/').take(45))
        }
        if (!Enlaces.nombreFiable(e.origenNombre) && e.origenNombre != "dirección") sb.append(" · nombre: ").append(e.origenNombre)
        return sb.toString()
    }

    /**
     * Diálogo con la lista. [guardada] = la lista viene de una lista ya guardada (no se puede volver a guardar ni se rehace la búsqueda).
     */
    private fun mostrarEnlaces(
        p: Pestana, titulo: String, fuenteTitulo: String, fuenteUrl: String,
        todos: List<EnlaceEncontrado>, filtro: TipoEnlace?, nueva: Boolean
    ) {
        val visibles = todos.filter { enlaceVisible(it, filtro) }
        val ocultos = todos.size - todos.count { enlaceVisible(it, null) }
        val vista = ListView(this)
        val adaptador = object : ArrayAdapter<EnlaceEncontrado>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, visibles
        ) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(pos, convertView, parent)
                val e = visibles[pos]
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                t1.text = e.nombre
                t1.maxLines = 2
                t1.ellipsize = android.text.TextUtils.TruncateAt.END
                t2.text = lineaEnlace(e)
                t2.maxLines = 2
                t1.setTextColor(paleta.texto)
                t2.setTextColor(paleta.tenue)
                return v
            }
        }
        vista.adapter = adaptador
        val cabecera = (if (filtro != null) filtro.etiqueta + ": " else "") + visibles.size + " enlaces" +
            (if (ocultos > 0) " (" + ocultos + " de otros sitios ocultos)" else "")
        vista.setOnItemClickListener { _, _, i, _ -> menuEnlace(p, visibles[i]) }
        nuevoDialogo()
            .setTitle(cabecera)
            .setView(vista)
            .setPositiveButton("Más") { _, _ ->
                menuMasEnlaces(p, fuenteTitulo, fuenteUrl, todos, visibles, filtro, nueva)
            }
            .setNeutralButton("Filtrar") { _, _ -> filtrarEnlaces(p, fuenteTitulo, fuenteUrl, todos, filtro, nueva) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun filtrarEnlaces(p: Pestana, ft: String, fu: String, todos: List<EnlaceEncontrado>, filtro: TipoEnlace?, nueva: Boolean) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair<String, () -> Unit>("Todos (" + todos.count { enlaceVisible(it, null) } + ")", { mostrarEnlaces(p, "", ft, fu, todos, null, nueva) }))
        for (t in TipoEnlace.values()) {
            val n = todos.count { enlaceVisible(it, t) }
            if (n > 0) items.add(Pair<String, () -> Unit>(t.etiqueta + " (" + n + ")", { mostrarEnlaces(p, "", ft, fu, todos, t, nueva) }))
        }
        opciones("Mostrar solo…", items)
    }

    private fun menuEnlace(p: Pestana, e: EnlaceEncontrado) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair<String, () -> Unit>("Copiar enlace", { copiar(e.url) }))
        items.add(Pair<String, () -> Unit>("Copiar nombre", { copiar(e.nombre) }))
        if (AppsMedia.apps(this, e.url, null).isNotEmpty() &&
            (e.tipo == TipoEnlace.MAGNET || e.tipo == TipoEnlace.MEGA || e.tipo == TipoEnlace.LISTA || e.tipo == TipoEnlace.VIDEO || e.tipo == TipoEnlace.AUDIO || e.tipo == TipoEnlace.TORRENT)
        ) {
            items.add(Pair<String, () -> Unit>("Abrir con otra app…", { elegirAppMedio(e.url, null, p, null, e.nombre) }))
        }
        if (e.url.startsWith("http")) items.add(Pair<String, () -> Unit>("Abrir en una pestaña nueva", { nuevaPestana(e.url, p.privada) }))
        items.add(Pair<String, () -> Unit>("Compartir enlace", { compartir(e.url) }))
        opciones(e.nombre.take(80), items)
    }

    private fun menuMasEnlaces(
        p: Pestana, ft: String, fu: String, todos: List<EnlaceEncontrado>, visibles: List<EnlaceEncontrado>,
        filtro: TipoEnlace?, nueva: Boolean
    ) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair<String, () -> Unit>("Copiar los " + visibles.size + " enlaces", { copiar(Enlaces.texto(visibles)) }))
        items.add(Pair<String, () -> Unit>("Copiar con nombres", { copiar(Enlaces.textoConNombres(visibles)) }))
        items.add(Pair<String, () -> Unit>("Exportar a archivo (.txt)…", { exportarEnlaces(Enlaces.texto(visibles), "txt", "text/plain", ft) }))
        items.add(Pair<String, () -> Unit>("Exportar a archivo (.csv)…", { exportarEnlaces(Enlaces.csv(visibles, fu), "csv", "text/csv", ft) }))
        if (nueva && !p.privada) {
            items.add(Pair<String, () -> Unit>("Guardar esta lista para después…", { guardarListaEnlaces(ft, fu, visibles) }))
        }
        if (nueva) {
            items.add(Pair<String, () -> Unit>("Incluir archivos de otros sitios: " + si(ajustes.enlacesOtros), {
                ajustes.enlacesOtros = !ajustes.enlacesOtros
                mostrarEnlaces(p, "", ft, fu, todos, filtro, nueva)
            }))
            if (!enlacesDeRastreo) items.add(Pair<String, () -> Unit>("Filtro propio: " + (ajustes.enlacesPropio.ifBlank { "ninguno" }).take(30) + "…", {
                pedirTextoOpcional("Filtro propio (extensiones, texto o /regex/, separados por comas)", ajustes.enlacesPropio) { t ->
                    ajustes.enlacesPropio = t
                    ejecutarBusquedaEnlaces(p)
                }
            }))
            if (!enlacesDeRastreo) items.add(Pair<String, () -> Unit>("Cambiar tipos a buscar…", { buscarEnlaces(p) }))
        }
        opciones("Enlaces", items)
    }

    private fun guardarListaEnlaces(ft: String, fu: String, visibles: List<EnlaceEncontrado>) {
        pedirTextoOpcional("Nombre de la lista", ft.ifBlank { "Lista de enlaces" }) { nombre ->
            val l = ListasEnlaces.guardar(nombre, fu, visibles)
            if (l == null) toast("No se pudo guardar (máximo " + ListasEnlaces.MAX_LISTAS + " listas)")
            else toast("Lista guardada (" + l.items.size + " enlaces)")
        }
    }

    private fun exportarEnlaces(contenido: String, ext: String, mime: String, ft: String) {
        enlacesPorExportar = contenido
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        val base = ft.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40).ifEmpty { "enlaces" }
        i.putExtra(Intent.EXTRA_TITLE, "enlaces-" + base + "." + ext)
        try {
            startActivityForResult(i, REQ_EXPORTAR_ENLACES)
        } catch (e: Exception) {
            enlacesPorExportar = null
            toast("Este móvil no tiene selector de archivos")
        }
    }

    private fun escribirEnlaces(uri: Uri, contenido: String) {
        try {
            val bytes = contenido.toByteArray(Charsets.UTF_8)
            val salida = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("sin salida")
            salida.use { it.write(bytes) }
            toast("Lista exportada")
        } catch (e: Exception) {
            toast("No se pudo guardar el archivo")
        }
    }


    // ---------------------------------------------------------------- rastreador de enlaces (fase 2: seguir enlaces)

    private fun pedirNumero(titulo: String, inicial: Int, alAceptar: (Int) -> Unit) {
        pedirTextoOpcional(titulo, inicial.toString()) { t ->
            val n = t.toIntOrNull()
            if (n == null) toast("Escribe un número") else alAceptar(n)
        }
    }

    private fun configurarRastreo(p: Pestana) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Profundidad: " + (if (ajustes.rastroProf == 0) "sin límite (solo frenan los topes)" else ajustes.rastroProf.toString() + " saltos")) {
            pedirNumero("Profundidad en saltos desde esta página (0 = sin límite)", ajustes.rastroProf) { ajustes.rastroProf = it; configurarRastreo(p) }
        }
        add("Máximo de páginas: " + ajustes.rastroPaginas) {
            pedirNumero("Máximo de páginas a leer (1–2000)", ajustes.rastroPaginas) { ajustes.rastroPaginas = it; configurarRastreo(p) }
        }
        add("Tiempo máximo: " + ajustes.rastroMin + " min") {
            pedirNumero("Tiempo máximo en minutos (1–120)", ajustes.rastroMin) { ajustes.rastroMin = it; configurarRastreo(p) }
        }
        add("Pausa entre páginas: " + ajustes.rastroPausa + " ms") {
            pedirNumero("Pausa entre páginas en milisegundos (0–10000)", ajustes.rastroPausa) { ajustes.rastroPausa = it; configurarRastreo(p) }
        }
        add("Seguir también otros sitios: " + si(ajustes.rastroOtros)) {
            ajustes.rastroOtros = !ajustes.rastroOtros
            configurarRastreo(p)
        }
        add("Enviar mis cookies (sesión iniciada): " + (if (p.privada) "no (pestaña privada)" else si(ajustes.rastroCookies))) {
            if (!p.privada) ajustes.rastroCookies = !ajustes.rastroCookies
            configurarRastreo(p)
        }
        add("Respetar robots.txt: " + si(ajustes.rastroRobots)) {
            ajustes.rastroRobots = !ajustes.rastroRobots
            configurarRastreo(p)
        }
        add("Tipos a buscar…") {
            elegirTiposEnlaces("Tipos a buscar", "Aceptar", { configurarRastreo(p) }) { configurarRastreo(p) }
        }
        add("▶ Empezar el rastreo") { iniciarRastreo(p) }
        opciones(
            "Rastrear este sitio\nLee el HTML de las páginas (sin JavaScript) y sigue sus enlaces. Solo con la app abierta. No visita ni lista dominios bloqueados por el bloqueador.",
            items
        )
    }

    private fun textoProgresoRastreo(pr: ProgresoRastreo): String =
        "Páginas leídas: " + pr.paginas + "\nEnlaces encontrados: " + pr.enlaces + "\nSaltos: " + pr.nivel + " · en cola: " + pr.enCola +
            "\n" + pr.actual.removePrefix("https://").removePrefix("http://").take(70)

    private fun iniciarRastreo(p: Pestana) {
        if (rastreador != null) {
            toast("Ya hay un rastreo en marcha")
            return
        }
        if (!p.url.startsWith("http")) return
        val propio = ajustes.enlacesPropio.let { if (it.isBlank()) null else FiltroPropio(it) }
        val cfg = ConfigRastreo(
            p.url, ajustes.rastroProf, ajustes.rastroPaginas, ajustes.rastroMin * 60000L, ajustes.rastroPausa.toLong(),
            ajustes.rastroOtros, ajustes.rastroCookies && !p.privada, ajustes.rastroRobots,
            WebSettings.getDefaultUserAgent(this), ajustes.bloquearAnuncios, ajustes.enlacesTipos, propio
        )
        val r = Rastreador(cfg, { pr -> dialogoRastreo?.setMessage(textoProgresoRastreo(pr)) }, { res -> terminarRastreo(p, res) })
        rastreador = r
        dialogoRastreo = nuevoDialogo()
            .setTitle("Rastreando…")
            .setMessage("Empezando…")
            .setCancelable(false)
            .setPositiveButton("Parar y ver") { _, _ -> r.parar("detenido") }
            .setNegativeButton("Descartar") { _, _ -> r.parar("descartado") }
            .show()
        r.iniciar()
    }

    private fun terminarRastreo(p: Pestana, res: ResultadoRastreo) {
        if (isDestroyed) return
        rastreador = null
        try {
            dialogoRastreo?.dismiss()
        } catch (e: Exception) {
            // Ya cerrado.
        }
        dialogoRastreo = null
        if (res.motivo == "descartado") {
            toast("Rastreo descartado")
            return
        }
        ultimoRastreo = res
        if (!enPantalla) return
        mostrarResultadoRastreo(actual ?: p, res)
    }

    private fun mostrarResultadoRastreo(p: Pestana, res: ResultadoRastreo) {
        val resumen = res.paginas.toString() + " páginas leídas (" + res.motivo + ")"
        if (res.enlaces.isEmpty()) {
            nuevoDialogo()
                .setTitle("Rastreo terminado")
                .setMessage("Sin enlaces de los tipos elegidos.\n" + resumen)
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }
        toast(resumen)
        enlacesDeRastreo = true
        mostrarEnlaces(p, "", res.titulo.ifBlank { Omnibox.hostDe(res.inicio) }, res.inicio, res.enlaces, null, true)
    }

    private fun mostrarListasEnlaces() {
        val listas = ListasEnlaces.todas()
        if (listas.isEmpty()) {
            toast("No hay listas guardadas")
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        for (l in listas) {
            items.add(Pair<String, () -> Unit>(l.nombre + "\n" + l.items.size + " enlaces · " + fmt.format(java.util.Date(l.fecha)), { menuListaEnlaces(l) }))
        }
        items.add(Pair<String, () -> Unit>("Borrar todas las listas…", {
            nuevoDialogo()
                .setTitle("Borrar listas")
                .setMessage("Se borrarán las " + listas.size + " listas de enlaces guardadas.")
                .setPositiveButton("Borrar") { _, _ ->
                    ListasEnlaces.eliminarTodas()
                    toast("Listas borradas")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }))
        opciones("Listas de enlaces", items)
    }

    private fun menuListaEnlaces(l: ListaEnlaces) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair<String, () -> Unit>("Ver la lista", {
            val p = actual
            if (p != null) mostrarEnlaces(p, "", l.nombre, l.fuente, l.items, null, false)
        }))
        if (l.fuente.startsWith("http")) items.add(Pair<String, () -> Unit>("Abrir la página de origen", { nuevaPestana(l.fuente) }))
        items.add(Pair<String, () -> Unit>("Borrar esta lista", {
            ListasEnlaces.eliminar(l.id)
            toast("Lista borrada")
            if (ListasEnlaces.todas().isNotEmpty()) mostrarListasEnlaces()
        }))
        opciones(l.nombre.take(80), items)
    }

    // ---------------------------------------------------------------- páginas guardadas (sin conexión)

    /** Guarda la página abierta (MHTML con sus recursos + instantánea del DOM) para leerla sin conexión. Nunca en pestañas privadas. */
    private fun guardarSinConexion(p: Pestana) {
        val w = p.web ?: return
        if (p.privada || p.sinConexion || !p.url.startsWith("http")) return
        if (p.progreso in 0..99) {
            toast("Espera a que termine de cargar la página")
            return
        }
        if (PaginasGuardadas.todas().size >= PaginasGuardadas.MAX_PAGINAS) {
            toast("Máximo " + PaginasGuardadas.MAX_PAGINAS + " páginas guardadas: borra alguna")
            return
        }
        val id = PaginasGuardadas.nuevoId()
        val mht = PaginasGuardadas.archivoMht(id)
        val titulo = tituloDe(p)
        val url = p.url
        toast("Guardando…")
        try {
            w.saveWebArchive(mht.absolutePath, false) { ruta ->
                if (ruta == null || !mht.exists()) {
                    mht.delete()
                    toast("No se pudo guardar la página")
                } else if (mht.length() > PaginasGuardadas.MAX_BYTES_PAGINA) {
                    mht.delete()
                    toast("La página es demasiado grande para guardarla (máx. 25 MB)")
                } else {
                    capturarHtmlYRegistrar(w, id, titulo, url)
                }
            }
        } catch (e: Exception) {
            mht.delete()
            toast("No se pudo guardar la página")
        }
    }

    private fun capturarHtmlYRegistrar(w: WebView, id: Long, titulo: String, url: String) {
        w.evaluateJavascript(JS_DOM_INSTANTANEA) { r ->
            var tieneHtml = false
            val htmlArchivo = PaginasGuardadas.archivoHtml(id)
            try {
                if (r != null && r != "null") {
                    val valor = org.json.JSONTokener(r).nextValue()
                    if (valor is String && valor.isNotEmpty() && valor.length <= PaginasGuardadas.MAX_HTML_CARACTERES) {
                        htmlArchivo.writeText(valor, Charsets.UTF_8)
                        tieneHtml = true
                    }
                }
            } catch (e: Exception) {
                htmlArchivo.delete()
            }
            val mht = PaginasGuardadas.archivoMht(id)
            val bytes = mht.length() + (if (tieneHtml) htmlArchivo.length() else 0L)
            if (PaginasGuardadas.registrar(id, titulo, url, bytes, tieneHtml) == null) {
                mht.delete()
                htmlArchivo.delete()
                toast("No se pudo guardar la página")
            } else {
                toast("Página guardada para leer sin conexión")
            }
        }
    }

    private fun mostrarPaginasGuardadas() {
        val todas = PaginasGuardadas.todas()
        if (todas.isEmpty()) {
            toast("No hay páginas guardadas")
            return
        }
        val fecha = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (pg in todas) {
            val etiqueta = pg.titulo + "\n" + Omnibox.hostDe(pg.url) + " · " + fecha.format(java.util.Date(pg.fecha)) + " · " + formatoTamano(pg.bytes)
            items.add(Pair<String, () -> Unit>(etiqueta, { menuPaginaGuardada(pg) }))
        }
        items.add(Pair<String, () -> Unit>("Borrar todas…", {
            nuevoDialogo()
                .setTitle("Borrar páginas guardadas")
                .setMessage("Se borrarán las " + todas.size + " páginas guardadas (" + formatoTamano(PaginasGuardadas.totalBytes()) + ").")
                .setPositiveButton("Borrar") { _, _ ->
                    PaginasGuardadas.eliminarTodas()
                    toast("Páginas guardadas borradas")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }))
        opciones("Páginas guardadas (" + todas.size + ", " + formatoTamano(PaginasGuardadas.totalBytes()) + ")", items)
    }

    private fun menuPaginaGuardada(pg: PaginaGuardada) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair<String, () -> Unit>("Abrir sin conexión", { abrirSinConexion(pg) }))
        items.add(Pair<String, () -> Unit>("Abrir la dirección original", { nuevaPestana(pg.url) }))
        items.add(Pair<String, () -> Unit>("Eliminar", {
            PaginasGuardadas.eliminar(pg.id)
            toast("Página eliminada")
            if (PaginasGuardadas.todas().isNotEmpty()) mostrarPaginasGuardadas()
        }))
        opciones(pg.titulo, items)
    }

    /** Abre la página guardada en una pestaña visor: sin red, sin JavaScript; los enlaces se abren en una pestaña normal. */
    private fun abrirSinConexion(pg: PaginaGuardada) {
        val archivo = PaginasGuardadas.archivoMht(pg.id)
        if (!archivo.exists()) {
            toast("El archivo de esta página ya no existe")
            return
        }
        val q = crearPestana(false, null, true, false) ?: return
        q.sinConexion = true
        q.url = pg.url
        q.titulo = pg.titulo
        activar(q)
        q.web?.loadUrl("file://" + archivo.absolutePath)
    }

    // ---------------------------------------------------------------- borrar datos al salir

    private fun alternarBorrarAlSalir(bit: Int) {
        ajustes.borrarAlSalir = ajustes.borrarAlSalir xor bit
        if ((ajustes.borrarAlSalir and bit) != 0) {
            toast("Se borrará al cerrar Optimo (o al reabrirla, si Android la cierra antes)")
        }
        mostrarPrivacidad()
    }

    // ---------------------------------------------------------------- retención y borrado del historial por rango

    private val etiquetasRetencion = arrayOf("Siempre (sin límite)", "7 días", "30 días", "90 días")
    private val diasRetencion = intArrayOf(0, 7, 30, 90)

    private fun etiquetaRetencion(d: Int): String {
        val i = diasRetencion.indexOf(d)
        return if (i >= 0) etiquetasRetencion[i] else d.toString() + " días"
    }

    /** Borra del historial lo anterior al plazo elegido en «Conservar historial». Se llama al abrir y al volver a Optimo. */
    private fun aplicarRetencionHistorial() {
        val d = ajustes.retencionHistorial
        if (d <= 0) return
        historial.quitarAnterioresA(System.currentTimeMillis() - d * 86400000L)
    }

    private fun elegirRetencionHistorial() {
        val actual = maxOf(diasRetencion.indexOf(ajustes.retencionHistorial), 0)
        nuevoDialogo()
            .setTitle("Conservar historial")
            .setSingleChoiceItems(etiquetasRetencion, actual) { d, i ->
                d.dismiss()
                cambiarRetencion(diasRetencion[i])
            }
            .setNegativeButton("Cancelar") { _, _ -> mostrarPrivacidad() }
            .setOnCancelListener { mostrarPrivacidad() }
            .show()
    }

    private fun cambiarRetencion(dias: Int) {
        if (dias <= 0) {
            ajustes.retencionHistorial = 0
            mostrarPrivacidad()
            return
        }
        val n = historial.contarAnterioresA(System.currentTimeMillis() - dias * 86400000L)
        if (n == 0) {
            ajustes.retencionHistorial = dias
            mostrarPrivacidad()
            return
        }
        nuevoDialogo()
            .setTitle("Conservar " + etiquetaRetencion(dias))
            .setMessage("Se borrarán ahora " + n + " páginas más antiguas, y a partir de ahora se irán borrando las que superen ese plazo. Los marcadores se conservan.")
            .setPositiveButton("Aplicar") { _, _ ->
                ajustes.retencionHistorial = dias
                aplicarRetencionHistorial()
                toast("Historial limpiado")
                mostrarPrivacidad()
            }
            .setNegativeButton("Cancelar") { _, _ -> mostrarPrivacidad() }
            .setOnCancelListener { mostrarPrivacidad() }
            .show()
    }

    private val etiquetasRango = arrayOf("Última hora", "Hoy", "Últimos 7 días", "Últimos 30 días", "Todo")

    /** Instante (ms) desde el que se borra para cada opción de [etiquetasRango]; 0 = todo. */
    private fun inicioRango(i: Int): Long {
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

    /** Borrar del historial lo visitado en la última hora, hoy, 7 o 30 días, o todo. [volver] se ejecuta al terminar o cancelar. */
    private fun elegirRangoBorrado(volver: () -> Unit) {
        nuevoDialogo()
            .setTitle("Borrar historial")
            .setItems(etiquetasRango) { _, i ->
                val desde = inicioRango(i)
                val n = historial.contarDesde(desde)
                if (n == 0) {
                    toast("No hay páginas en ese rango")
                    volver()
                } else {
                    nuevoDialogo()
                        .setTitle(etiquetasRango[i])
                        .setMessage("Se borrarán " + n + " páginas del historial. Los marcadores se conservan.")
                        .setPositiveButton("Borrar") { _, _ ->
                            historial.quitarDesde(desde)
                            cerradas.clear()
                            toast("Historial borrado")
                            volver()
                        }
                        .setNegativeButton("Cancelar") { _, _ -> volver() }
                        .setOnCancelListener { volver() }
                        .show()
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> volver() }
            .setOnCancelListener { volver() }
            .show()
    }

    // ---------------------------------------------------------------- bloquear un sitio a mano (candado) y «Mis dominios bloqueados»

    /** Dos etiquetas del tipo co.uk, com.ar…: sufijos públicos que no se ofrecen como «dominio padre» a bloquear. */
    private fun pareceSufijoPublico(d: String): Boolean {
        val i = d.indexOf('.')
        if (i <= 0 || d.indexOf('.', i + 1) >= 0) return false
        val a = d.substring(0, i)
        val b = d.substring(i + 1)
        val comunes = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")
        return b.length == 2 && comunes.contains(a)
    }

    /** Lo que se puede bloquear para [host]: él mismo (sin «www.») y sus dominios padre, hasta dos etiquetas y sin sufijos públicos. */
    private fun candidatosBloqueo(host: String): List<String> {
        val res = ArrayList<String>()
        var h = host.lowercase().trim('.')
        if (h.startsWith("www.")) h = h.substring(4)
        if (UtilDominio.normalizar(h) == null) return res
        var d = h
        res.add(d)
        while (d.contains('.')) {
            d = d.substring(d.indexOf('.') + 1)
            if (d.contains('.') && !pareceSufijoPublico(d)) res.add(d)
        }
        return res
    }

    /** Candado › «Bloquear este sitio…»: elige qué bloquear (el sitio o un dominio padre) y sale de la página. */
    private fun bloquearSitio(p: Pestana) {
        val candidatos = candidatosBloqueo(Omnibox.hostDe(p.url))
        if (candidatos.isEmpty()) {
            toast("No se puede bloquear esta dirección")
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (d in candidatos) {
            val etiqueta = if (d == candidatos[0]) "Este sitio: " + d else "Todo " + d + " (incluye otros subdominios)"
            items.add(Pair<String, () -> Unit>(etiqueta, { aplicarBloqueoSitio(p, d) }))
        }
        items.add(Pair<String, () -> Unit>("Cancelar", { }))
        opciones("Bloquear y salir de esta página", items)
    }

    private fun aplicarBloqueoSitio(p: Pestana, d: String) {
        if (!ReglasUsuario.agregarDominio(d)) {
            toast("No se pudo bloquear " + d + " (ya estaba bloqueado o no es válido)")
            return
        }
        val aviso = if (ajustes.bloquearAnuncios) "" else " El bloqueo de anuncios está desactivado: actívalo para que surta efecto."
        toast("Bloqueado: " + d + ". Se revierte en Listas de bloqueo › Mis dominios bloqueados." + aviso)
        // La página principal nunca se bloquea: si era una página de anuncios, se sale de ella.
        val w = p.web
        if (p.esEmergente) {
            cerrarEmergente(p)
        } else if (w != null && w.canGoBack()) {
            w.goBack()
        } else if (pestanas.size > 1) {
            cerrarPestana(p)
        } else {
            mostrarInicio(p)
        }
    }

    /** Candado › «Desbloquear este sitio…»: quita de tus dominios los que cubren la página abierta. */
    private fun desbloquearSitio(p: Pestana) {
        val host = Omnibox.hostDe(p.url).lowercase()
        val coinciden = ReglasUsuario.listaDominios().filter { d -> host == d || host.endsWith("." + d) }
        if (coinciden.isEmpty()) {
            toast("Este sitio no está en tus dominios bloqueados")
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (d in coinciden) {
            items.add(Pair<String, () -> Unit>("Desbloquear " + d, {
                ReglasUsuario.quitarDominio(d)
                toast("Desbloqueado: " + d + ". Recarga la página para ver el efecto")
            }))
        }
        opciones("Desbloquear este sitio", items)
    }

    /** Listas de bloqueo › «Mis dominios bloqueados»: los que has bloqueado tú, para desbloquearlos o añadir más. */
    private fun mostrarDominiosPropios() {
        val lista = ReglasUsuario.listaDominios()
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (d in lista) {
            items.add(Pair<String, () -> Unit>("🚫 " + d, { confirmarDesbloqueo(d) }))
        }
        items.add(Pair<String, () -> Unit>("➕ Añadir dominio…", { anadirDominioPropio() }))
        if (lista.isNotEmpty()) items.add(Pair<String, () -> Unit>("Desbloquear todos…", { confirmarDesbloquearTodos() }))
        val pie = if (lista.isEmpty()) "\nAún no has bloqueado ninguno" else "\nToca uno para desbloquearlo"
        opciones("Mis dominios bloqueados (" + lista.size + ")" + pie, items)
    }

    private fun confirmarDesbloqueo(d: String) {
        nuevoDialogo()
            .setTitle("Desbloquear " + d)
            .setMessage("Volverá a poder cargarse, salvo que lo bloquee una de las listas activas.")
            .setPositiveButton("Desbloquear") { _, _ ->
                ReglasUsuario.quitarDominio(d)
                toast("Desbloqueado: " + d)
                mostrarDominiosPropios()
            }
            .setNegativeButton("Cancelar") { _, _ -> mostrarDominiosPropios() }
            .setOnCancelListener { mostrarDominiosPropios() }
            .show()
    }

    private fun anadirDominioPropio() {
        val campo = android.widget.EditText(this)
        campo.hint = "ejemplo.com"
        campo.setSingleLine(true)
        nuevoDialogo()
            .setTitle("Bloquear un dominio")
            .setView(campo)
            .setPositiveButton("Bloquear") { _, _ ->
                val n = UtilDominio.normalizar(campo.text.toString())?.removePrefix("www.")
                if (n != null && ReglasUsuario.agregarDominio(n)) toast("Bloqueado: " + n)
                else toast("Dominio no válido o ya estaba bloqueado")
                mostrarDominiosPropios()
            }
            .setNegativeButton("Cancelar") { _, _ -> mostrarDominiosPropios() }
            .setOnCancelListener { mostrarDominiosPropios() }
            .show()
    }

    private fun confirmarDesbloquearTodos() {
        val n = ReglasUsuario.listaDominios().size
        nuevoDialogo()
            .setTitle("Desbloquear todos")
            .setMessage("Se quitarán tus " + n + " dominios bloqueados. Las reglas de ocultar elementos y de estilo no cambian.")
            .setPositiveButton("Desbloquear") { _, _ ->
                for (d in ReglasUsuario.listaDominios()) ReglasUsuario.quitarDominio(d)
                toast("Dominios desbloqueados")
                mostrarDominiosPropios()
            }
            .setNegativeButton("Cancelar") { _, _ -> mostrarDominiosPropios() }
            .setOnCancelListener { mostrarDominiosPropios() }
            .show()
    }

    // ---------------------------------------------------------------- excepciones de certificado

    /** El WebView recuerda en memoria los certificados con los que se continuó; al revocar o vencer hay que hacérselo olvidar. */
    private fun olvidarSslWebView() {
        for (q in pestanas) {
            try {
                q.web?.clearSslPreferences()
            } catch (e: Exception) {
                // Sin WebView o ya destruido.
            }
        }
    }

    private fun mostrarExcepcionesSsl() {
        val lista = ExcepcionesSsl.todas()
        if (lista.isEmpty()) {
            toast("No hay excepciones de certificado")
            mostrarPrivacidad()
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (e in lista) {
            val texto = e.sitio.substringBeforeLast(':') + " — " + Certificados.etiqueta(e.motivos) + "\n" +
                Certificados.vigencia(e) + " · " + Certificados.huellaCorta(e.huella)
            items.add(Pair<String, () -> Unit>(texto, { detalleExcepcionSsl(e) }))
        }
        items.add(Pair<String, () -> Unit>("Revocar todas…", { confirmarRevocarTodasSsl() }))
        opciones("Excepciones de certificado", items)
    }

    private fun detalleExcepcionSsl(e: ExcepcionSsl) {
        val fmt = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        val msg = e.sitio.substringBeforeLast(':') + "\n\n" +
            "Autorizado a pesar de: " + Certificados.etiqueta(e.motivos) + "\n" +
            "Concedida: " + fmt.format(java.util.Date(e.concedida)) + "\n" +
            "Vigencia: " + Certificados.vigencia(e) + "\n\n" +
            "Huella SHA-256:\n" + e.huella + "\n\n" +
            "Solo vale para este certificado exacto. Si el equipo presenta otro, Optimo volverá a preguntar."
        nuevoDialogo()
            .setTitle("Excepción de certificado")
            .setMessage(msg)
            .setPositiveButton("Revocar") { _, _ ->
                ExcepcionesSsl.quitar(e)
                olvidarSslWebView()
                toast("Excepción revocada. Recarga la página")
                mostrarExcepcionesSsl()
            }
            .setNeutralButton("Copiar huella") { _, _ ->
                copiar(e.huella)
                mostrarExcepcionesSsl()
            }
            .setNegativeButton("Cerrar") { _, _ -> mostrarExcepcionesSsl() }
            .setOnCancelListener { mostrarExcepcionesSsl() }
            .show()
    }

    private fun confirmarRevocarTodasSsl() {
        nuevoDialogo()
            .setTitle("Revocar todas")
            .setMessage("Se olvidarán todas las excepciones de certificado. Los equipos con certificado no válido volverán a preguntar.")
            .setPositiveButton("Revocar") { _, _ ->
                ExcepcionesSsl.quitarTodas()
                olvidarSslWebView()
                toast("Excepciones revocadas. Recarga las páginas")
                mostrarPrivacidad()
            }
            .setNegativeButton("Cancelar") { _, _ -> mostrarExcepcionesSsl() }
            .setOnCancelListener { mostrarExcepcionesSsl() }
            .show()
    }

    /**
     * Limpieza de «Al salir, borrar…». Se llama al cerrar la app (onDestroy con la actividad terminando) y, si el proceso murió
     * antes de poder hacerlo (quitarla de recientes, Android que la cierra), al abrirla de nuevo. La marca [Ajustes.salidaPendiente]
     * solo se quita cuando el borrado de cookies termina de verdad.
     */
    private fun limpiarAlSalir() {
        val m = ajustes.borrarAlSalir
        if (m == 0) {
            ajustes.salidaPendiente = false
            return
        }
        if ((m and 2) != 0) {
            historial.vaciar()
            cerradas.clear()
        }
        if ((m and 4) != 0) borrarCache()
        if ((m and 1) != 0) {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies { _ -> ajustes.salidaPendiente = false }
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        } else {
            ajustes.salidaPendiente = false
        }
    }

    // ---------------------------------------------------------------- copia de seguridad

    private fun mostrarCopiaSeguridad() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair<String, () -> Unit>("Exportar a un archivo…", { confirmarExportar() }))
        items.add(Pair<String, () -> Unit>("Importar desde un archivo…", { elegirArchivoCopia() }))
        opciones("Copia de seguridad\nMarcadores, reglas de sitios y anuncios, y ajustes. No incluye historial, cookies ni permisos de sitios.", items)
    }

    private fun confirmarExportar() {
        nuevoDialogo()
            .setTitle("Exportar copia")
            .setMessage(
                "El archivo se guarda SIN cifrar (texto legible) con tus marcadores, reglas y ajustes. " +
                    "Guárdalo en un sitio privado y no lo compartas. No incluye historial, cookies, contraseñas ni permisos."
            )
            .setPositiveButton("Exportar") { _, _ ->
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/json"
                i.putExtra(
                    Intent.EXTRA_TITLE,
                    "optimo-copia-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
                )
                try {
                    startActivityForResult(i, REQ_EXPORTAR)
                } catch (e: Exception) {
                    toast("Este móvil no tiene selector de archivos")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun elegirArchivoCopia() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, REQ_IMPORTAR)
        } catch (e: Exception) {
            toast("Este móvil no tiene selector de archivos")
        }
    }

    private fun escribirCopia(uri: Uri) {
        try {
            val j = JSONObject()
            j.put("optimo", 1)
            j.put("fecha", System.currentTimeMillis())
            j.put("ajustes", ajustes.exportarJson())
            j.put("marcadores", marcadores.exportarJson())
            j.put("reglas", ReglasUsuario.exportarJson())
            val bytes = j.toString(2).toByteArray(Charsets.UTF_8)
            val salida = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("sin salida")
            salida.use { it.write(bytes) }
            toast("Copia guardada (" + (bytes.size / 1024 + 1) + " KB)")
        } catch (e: Exception) {
            toast("No se pudo guardar la copia")
        }
    }

    /** Lee y valida la copia (máx. 5 MB) y pide confirmación antes de aplicarla. */
    private fun leerCopia(uri: Uri) {
        val j: JSONObject? = try {
            val entrada = contentResolver.openInputStream(uri) ?: throw java.io.IOException("sin entrada")
            val buf = java.io.ByteArrayOutputStream()
            entrada.use { f ->
                val b = ByteArray(8192)
                while (true) {
                    val n = f.read(b)
                    if (n < 0) break
                    buf.write(b, 0, n)
                    if (buf.size() > 5 * 1024 * 1024) throw java.io.IOException("demasiado grande")
                }
            }
            JSONObject(buf.toString("UTF-8"))
        } catch (e: Exception) {
            null
        }
        if (j == null || j.optInt("optimo", 0) != 1) {
            toast("No es una copia válida de Optimo")
            return
        }
        val nM = j.optJSONObject("marcadores")?.optJSONArray("marcadores")?.length() ?: 0
        val r = j.optJSONObject("reglas")
        val nR = (r?.optJSONArray("ocultar")?.length() ?: 0) + (r?.optJSONArray("estilos")?.length() ?: 0) +
            (r?.optJSONArray("dominios")?.length() ?: 0)
        val nA = j.optJSONObject("ajustes")?.optJSONArray("valores")?.length() ?: 0
        nuevoDialogo()
            .setTitle("Importar copia")
            .setMessage(
                "La copia tiene " + nM + " marcadores, " + nR + " reglas y " + nA + " ajustes.\n\n" +
                    "Marcadores y reglas se AÑADEN a los que ya tienes (no se borra nada ni se duplican los repetidos). " +
                    "Los ajustes de la copia sustituyen a los actuales. Importa solo copias que hayas hecho tú."
            )
            .setPositiveButton("Importar") { _, _ ->
                try {
                    val ra = ajustes.importarJson(j.optJSONObject("ajustes") ?: JSONObject())
                    val rm = marcadores.importarJson(j.optJSONObject("marcadores") ?: JSONObject())
                    val rr = ReglasUsuario.importarJson(j.optJSONObject("reglas") ?: JSONObject())
                    toast("Importado: " + rm.second + " marcadores, " + rr + " reglas, " + ra + " ajustes. Reinicia Optimo para aplicarlo todo")
                } catch (e: Exception) {
                    toast("No se pudo importar la copia")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun borrarDatos() {
        historial.vaciar()
        cerradas.clear()
        ExcepcionesSsl.quitarTodas()
        olvidarSslWebView()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        actual?.web?.clearCache(true)
        toast("Datos de navegación borrados")
    }

    // ---------------------------------------------------------------- privacidad y datos guardados

    private fun tamanoDir(d: File): Long {
        var t = 0L
        try {
            val hijos = d.listFiles() ?: return d.length()
            for (h in hijos) t += if (h.isDirectory) tamanoDir(h) else h.length()
        } catch (e: Exception) {
        }
        return t
    }

    private fun formatoTamano(b: Long): String =
        if (b < 1024L * 1024L) (b / 1024L).toString() + " KB" else String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1048576.0)

    private fun etiquetaPermiso(t: String): String = when (t) {
        "camara" -> "cámara"
        "microfono" -> "micrófono"
        "ubicacion" -> "ubicación"
        else -> t
    }

    private fun borrarCache() {
        var hecho = false
        for (q in pestanas) {
            val w = q.web
            if (w != null) {
                w.clearCache(true)
                hecho = true
                break
            }
        }
        if (!hecho) {
            try {
                File(cacheDir, "WebView").deleteRecursively()
            } catch (e: Exception) {
            }
        }
    }

    private fun confirmarAccionPrivacidad(titulo: String, mensaje: String, boton: String, accion: () -> Unit) {
        nuevoDialogo()
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton(boton) { _, _ ->
                accion()
                toast("Hecho")
                mostrarPrivacidad()
            }
            .setNegativeButton("Cancelar") { _, _ -> mostrarPrivacidad() }
            .setOnCancelListener { mostrarPrivacidad() }
            .show()
    }

    /** Ajustes › Privacidad y datos: qué guarda Optimo, cuánto, y cómo controlarlo o borrarlo. */
    private fun mostrarPrivacidad() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Guardar historial: " + si(ajustes.guardarHistorial)) {
            ajustes.guardarHistorial = !ajustes.guardarHistorial
            if (!ajustes.guardarHistorial && historial.todos().isNotEmpty()) {
                nuevoDialogo()
                    .setTitle("Historial desactivado")
                    .setMessage("¿Quieres borrar también el historial que ya está guardado (" + historial.todos().size + " páginas)?")
                    .setPositiveButton("Borrar") { _, _ ->
                        historial.vaciar()
                        cerradas.clear()
                        toast("Historial borrado")
                        mostrarPrivacidad()
                    }
                    .setNegativeButton("Conservar") { _, _ -> mostrarPrivacidad() }
                    .setOnCancelListener { mostrarPrivacidad() }
                    .show()
            } else {
                mostrarPrivacidad()
            }
        }
        add("Conservar historial: " + etiquetaRetencion(ajustes.retencionHistorial)) { elegirRetencionHistorial() }
        add("Borrar historial por rango…") { elegirRangoBorrado { mostrarPrivacidad() } }
        add("Guardar pestañas abiertas al cerrar: " + si(ajustes.guardarSesion)) {
            ajustes.guardarSesion = !ajustes.guardarSesion
            if (!ajustes.guardarSesion) borrarSesionGuardada()
            mostrarPrivacidad()
        }
        add("Páginas guardadas sin conexión (" + PaginasGuardadas.todas().size + ", " + formatoTamano(PaginasGuardadas.totalBytes()) + ")…") {
            mostrarPaginasGuardadas()
        }
        add("Listas de enlaces guardadas (" + ListasEnlaces.todas().size + ")…") { mostrarListasEnlaces() }
        add("Al salir, borrar cookies y datos de sitios: " + si((ajustes.borrarAlSalir and 1) != 0)) { alternarBorrarAlSalir(1) }
        add("Al salir, borrar historial: " + si((ajustes.borrarAlSalir and 2) != 0)) { alternarBorrarAlSalir(2) }
        add("Al salir, borrar caché: " + si((ajustes.borrarAlSalir and 4) != 0)) { alternarBorrarAlSalir(4) }
        add("Borrar historial (" + historial.todos().size + " páginas)") {
            confirmarAccionPrivacidad("Borrar historial", "Se borrará el historial de navegación. Los marcadores se conservan.", "Borrar") {
                historial.vaciar()
                cerradas.clear()
            }
        }
        add("Borrar caché (" + formatoTamano(tamanoDir(cacheDir)) + ")") {
            confirmarAccionPrivacidad("Borrar caché", "Las páginas se volverán a descargar la próxima vez que las abras.", "Borrar") {
                borrarCache()
            }
        }
        add("Borrar cookies y datos de sitios") {
            confirmarAccionPrivacidad("Borrar cookies y datos de sitios", "Se cerrarán tus sesiones en los sitios web y se borrará su almacenamiento local.", "Borrar") {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
        add("Permisos concedidos a sitios (" + ajustes.totalPermisos() + ")…") { mostrarPermisosSitios() }
        add("Excepciones de certificado (" + ExcepcionesSsl.todas().size + ")…") { mostrarExcepcionesSsl() }
        add("Sitios con el bloqueo de anuncios pausado (" + ajustes.sitiosPausados().size + ")…") { mostrarSitiosPausados() }
        add("Reglas de sitios y anuncios (" + ReglasUsuario.total + ")…") { mostrarReglasUsuario() }
        add("Borrar todo: historial, caché, cookies y permisos") {
            confirmarAccionPrivacidad(
                "Borrar todo",
                "Se borrarán el historial, la caché, las cookies, el almacenamiento de los sitios y los permisos concedidos. Los marcadores y las reglas se conservan.",
                "Borrar todo"
            ) {
                borrarDatos()
                borrarCache()
                ajustes.quitarTodosPermisos()
            }
        }
        opciones("Privacidad y datos", items)
    }

    private fun mostrarPermisosSitios() {
        val sitios = ajustes.sitiosConPermisos()
        if (sitios.isEmpty()) {
            toast("Ningún sitio tiene permisos guardados")
            mostrarPrivacidad()
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (sitio in sitios) {
            val texto = sitio + " — " + ajustes.permisosDe(sitio).joinToString(", ") { t -> etiquetaPermiso(t) }
            items.add(Pair<String, () -> Unit>(texto, { mostrarPrivacidadSitio(sitio) }))
        }
        opciones("Permisos concedidos", items)
    }

    /** Datos y permisos de un sitio concreto (menú ⋮ › Privacidad de este sitio, o desde la lista de permisos). */
    private fun mostrarPrivacidadSitio(host: String) {
        val h = host.lowercase().removePrefix("www.")
        if (h.isEmpty()) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        val permisos = ajustes.permisosDe(h)
        for (t in permisos) {
            add("Revocar permiso de " + etiquetaPermiso(t) + textoRestante(ajustes.restantePermiso(h, t))) {
                ajustes.quitarPermiso(h, t)
                toast("Permiso revocado: " + etiquetaPermiso(t))
                mostrarPrivacidadSitio(h)
            }
        }
        if (permisos.isEmpty()) add("Sin permisos guardados (te preguntará cada vez)") { mostrarPrivacidadSitio(h) }
        add("Reglas de ocultar elementos en este sitio: " + ReglasUsuario.selectoresPara(h).size) { mostrarReglasUsuario() }
        add("Cookies de este sitio (" + cookiesDeSitio(h).size + ")…") { mostrarCookiesSitio(h) }
        val pa = actual
        val enPagina = pa != null && !pa.privada && !pa.inicio && pa.web != null &&
            dominioBase(Omnibox.hostDe(pa.url)) == dominioBase(h)
        if (enPagina && pa != null) {
            add("Almacenamiento de la página abierta…") { inspeccionarAlmacenamiento(pa) }
            if (pa.bloqueados.get() > 0) {
                add("Rastreadores bloqueados en esta página: " + pa.bloqueados.get() + "…") { mostrarBloqueadosPagina(pa, h) }
            }
        }
        add("Borrar cookies y almacenamiento de este sitio") {
            nuevoDialogo()
                .setTitle("Borrar datos de " + h)
                .setMessage("Se cerrará tu sesión en este sitio y se borrará su almacenamiento local. Los demás sitios no se tocan.")
                .setPositiveButton("Borrar") { _, _ ->
                    borrarDatosSitio(h)
                    toast("Datos de " + h + " borrados. Recarga la página")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        opciones(h, items)
    }

    /** «Recordar…» en un permiso: 1 hora, 24 horas o siempre. Si se cierra sin elegir, se deniega (la petición no puede quedar colgada). */
    private fun elegirDuracionPermiso(sitio: String, tipos: List<String>, conceder: () -> Unit, denegar: () -> Unit) {
        var decidido = false
        nuevoDialogo()
            .setTitle("Recordar permiso en " + sitio)
            .setItems(arrayOf("Durante 1 hora", "Durante 24 horas", "Siempre en este sitio")) { _, i ->
                decidido = true
                for (t in tipos) {
                    when (i) {
                        0 -> ajustes.recordarPermisoTemporal(sitio, t, 60L * 60L * 1000L)
                        1 -> ajustes.recordarPermisoTemporal(sitio, t, 24L * 60L * 60L * 1000L)
                        else -> ajustes.recordarPermiso(sitio, t)
                    }
                }
                conceder()
            }
            .setNegativeButton("Denegar") { _, _ ->
                decidido = true
                denegar()
            }
            .setOnCancelListener { if (!decidido) denegar() }
            .show()
    }

    private fun textoRestante(ms: Long): String {
        if (ms <= 0L) return ""
        val min = (ms / 60000L).toInt()
        return if (min >= 60) " (caduca en " + (min / 60) + " h)" else " (caduca en " + maxOf(min, 1) + " min)"
    }

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
    private fun cookiesDeSitio(host: String): List<Pair<String, Int>> {
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

    private fun borrarCookie(host: String, nombre: String) {
        val cm = CookieManager.getInstance()
        for (x in hostsCookies(host)) {
            val url = "https://" + x + "/"
            for (dom in listOf("", "; Domain=" + x, "; Domain=." + x)) {
                cm.setCookie(url, nombre + "=; Max-Age=0; Path=/" + dom)
            }
        }
        cm.flush()
    }

    private fun mostrarCookiesSitio(h: String) {
        val lista = cookiesDeSitio(h)
        if (lista.isEmpty()) {
            toast("Sin cookies guardadas para " + h)
            mostrarPrivacidadSitio(h)
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for ((nombre, largo) in lista.take(60)) {
            items.add(Pair<String, () -> Unit>(nombre + "  (" + largo + " car.)", {
                borrarCookie(h, nombre)
                toast("Cookie borrada: " + nombre)
                mostrarCookiesSitio(h)
            }))
        }
        opciones("Cookies de " + h + " (toca una para borrarla)", items)
    }

    /** Dominios bloqueados en la página (solo lectura; el registro guarda los últimos 500 bloqueos). */
    private fun mostrarBloqueadosPagina(p: Pestana, h: String) {
        val lista = p.dev.bloqueadosPorHost()
        if (lista.isEmpty()) {
            toast("Sin bloqueos registrados")
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for ((dominio, n) in lista.take(40)) {
            items.add(Pair<String, () -> Unit>(dominio + " — " + n, { mostrarPrivacidadSitio(h) }))
        }
        opciones("Bloqueados en esta página: " + p.bloqueados.get(), items)
    }

    /**
     * Cuenta lo que la página abierta tiene guardado (localStorage, sessionStorage, IndexedDB, Cache Storage).
     * IndexedDB y Cache Storage responden de forma asíncrona: se lanza la consulta y se lee el resultado un instante después.
     * Solo informa de la página abierta: Android no permite listar el almacenamiento de otros sitios.
     */
    private fun inspeccionarAlmacenamiento(p: Pestana) {
        val w = p.web ?: return
        w.evaluateJavascript(JS_ALMACEN_CONTAR, null)
        manejadorPrincipal.postDelayed({
            if (isFinishing || p.web !== w) return@postDelayed
            w.evaluateJavascript(JS_ALMACEN_LEER) { r ->
                val v = (r ?: "").trim('"').split(',')
                if (v.size != 4 || actual !== p) return@evaluateJavascript
                fun n(i: Int): String = if (v[i] == "-1") "no disponible" else v[i]
                val items = ArrayList<Pair<String, () -> Unit>>()
                items.add(Pair<String, () -> Unit>("localStorage: " + n(0) + " → borrar", { borrarAlmacen(p, "ls") }))
                items.add(Pair<String, () -> Unit>("sessionStorage: " + n(1) + " → borrar", { borrarAlmacen(p, "ss") }))
                items.add(Pair<String, () -> Unit>("IndexedDB: " + n(2) + " → borrar", { borrarAlmacen(p, "idb") }))
                items.add(Pair<String, () -> Unit>("Cache Storage: " + n(3) + " → borrar", { borrarAlmacen(p, "cache") }))
                opciones("Almacenamiento de " + Omnibox.hostDe(p.url), items)
            }
        }, 400L)
    }

    private fun borrarAlmacen(p: Pestana, que: String) {
        val js = when (que) {
            "ls" -> "try{localStorage.clear()}catch(e){}"
            "ss" -> "try{sessionStorage.clear()}catch(e){}"
            "idb" -> "try{indexedDB.databases().then(function(l){l.forEach(function(d){indexedDB.deleteDatabase(d.name)})})}catch(e){}"
            else -> "try{caches.keys().then(function(k){k.forEach(function(n){caches.delete(n)})})}catch(e){}"
        }
        p.web?.evaluateJavascript(js, null)
        toast("Borrado. Recarga la página")
    }

    /** Cookies (del sitio y de su dominio base) y almacenamiento local de un sitio. Solo el perfil normal. */
    private fun borrarDatosSitio(host: String) {
        val cm = CookieManager.getInstance()
        val base = dominioBase(host)
        val hosts = LinkedHashSet<String>()
        hosts.add(host)
        hosts.add(base)
        hosts.add("www." + base)
        for (x in hosts) {
            val url = "https://" + x + "/"
            val cookies = cm.getCookie(url) ?: continue
            for (par in cookies.split(";")) {
                val nombre = par.substringBefore('=').trim()
                if (nombre.isEmpty()) continue
                for (dom in listOf("", "; Domain=" + x, "; Domain=." + x)) {
                    cm.setCookie(url, nombre + "=; Max-Age=0; Path=/" + dom)
                }
            }
        }
        cm.flush()
        try {
            WebStorage.getInstance().deleteOrigin("https://" + host)
            WebStorage.getInstance().deleteOrigin("http://" + host)
        } catch (e: Exception) {
        }
    }

    /** Versión de la app tal como está instalada (la de build.gradle), para no escribirla a mano en cada entrega. */
    private fun versionInstalada(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

    private fun acercaDe() {
        nuevoDialogo()
            .setTitle("Optimo " + versionInstalada())
            .setMessage(
                "Navegador ligero sobre el WebView del sistema (motor Chromium).\n\n" +
                    "• Bloqueador de anuncios y rastreadores por dominio (" + (AdBlock.TOTAL + ListasManager.filtro.totalDominios) + " dominios, con las listas descargadas), con técnicas extra: vídeo de anuncio sustituido por uno vacío válido, scripts sustitutos, ocultar huecos y saltar anuncios (se eligen en Ajustes). Mantén pulsado un anuncio para ocultarlo o bloquear su dominio.\n" +
                    "• Ventanas emergentes: cierra las pestañas de anuncios, limita una por toque y bloquea redirecciones automáticas (Ajustes › Ventanas emergentes y redirecciones; por sitio desde el menú ⋮).\n" +
                    "• Silenciar pestañas: altavoz en la barra de direcciones (aparece si la pestaña suena o está silenciada), en el candado y en la lista de pestañas.\n" +
                    "• Pantalla de inicio editable (Ajustes › Pantalla de inicio): colores, accesos o marcadores, buscador, título e iconos.\n" +
                    "• Vídeos, audios, listas .m3u/.m3u8, torrents, magnets y enlaces de Mega: pregunta si abrirlos aquí o en otra app (Optimo Play u otras); se ajusta en Ajustes.\n" +
                    "• Reproducción en segundo plano (YouTube): sigue sonando con la pantalla bloqueada y, con un vídeo a pantalla completa, pasa a imagen en imagen al salir. Se activa o desactiva en Ajustes.\n" +
                    "• HTTPS primero. Si el sitio no lo ofrece, solo se abre por http si lo confirmas. Los certificados no válidos se bloquean (solo la red local permite continuar, tras confirmar).\n" +
                    "• Pestaña privada: perfil aislado (cookies, almacenamiento y caché propios, que se borran al cerrar la última pestaña privada), sin historial y sin capturas de pantalla.\n" +
                    "• Marcadores, historial, sesión y reglas se guardan cifrados con una clave del Keystore del dispositivo.\n" +
                    "• El modo oscuro de páginas es experimental y funciona mejor con el sistema en modo oscuro."
            )
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun cerrarTodas() {
        val copia = ArrayList<Pestana>(pestanas)
        for (q in copia) {
            cerrarPestana(q)
        }
    }

    private fun abrirDescargas() {
        // Tu gestor de archivos, en la carpeta Descargas; si no está instalado, el gestor de descargas del sistema.
        if (GestorExterno.abrirDescargas(this)) return
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            toast("No se pudo abrir las descargas")
        }
    }

    private fun compartir(url: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(i, "Compartir enlace"))
    }

    private fun copiar(texto: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", texto))
        toast("Copiado")
    }

    private fun mostrarLista(titulo: String, lista: ListaPersistente, vacio: String, conBorrarTodo: Boolean) {
        if (lista.todos().isEmpty()) {
            toast(vacio)
            return
        }
        val vista = ListView(this)
        val adaptador = object : ArrayAdapter<Entrada>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, lista.todos()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = getItem(position)
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                if (e != null) {
                    t1.text = if (e.titulo.isNotEmpty()) e.titulo else e.url
                    t2.text = e.url
                }
                t1.maxLines = 1
                t2.maxLines = 1
                t1.ellipsize = TextUtils.TruncateAt.END
                t2.ellipsize = TextUtils.TruncateAt.END
                t1.setTextColor(paleta.texto)
                t2.setTextColor(paleta.tenue)
                return v
            }
        }
        vista.setAdapter(adaptador)
        var dialogo: AlertDialog? = null
        vista.setOnItemClickListener { _, _, pos, _ ->
            val e = lista.todos()[pos]
            dialogo?.dismiss()
            cargarEnActual(e.url)
        }
        vista.setOnItemLongClickListener { _, _, pos, _ ->
            val e = lista.todos()[pos]
            val items = ArrayList<Pair<String, () -> Unit>>()
            items.add(Pair("Abrir en pestaña nueva", {
                dialogo?.dismiss()
                nuevaPestana(e.url)
                Unit
            }))
            items.add(Pair("Eliminar", {
                lista.quitar(e.url)
                adaptador.notifyDataSetChanged()
                actualizarUi()
            }))
            opciones(null, items)
            true
        }
        val b = nuevoDialogo().setTitle(titulo).setView(vista).setNegativeButton("Cerrar", null)
        if (conBorrarTodo) {
            b.setNeutralButton("Borrar…") { _, _ -> elegirRangoBorrado { } }
        }
        val d = b.create()
        dialogo = d
        d.show()
    }

    private fun actualizarBotonBorrar() {
        borrarTexto.visibility = if (omnibox.hasFocus() && omnibox.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- marcadores: gestor con carpetas

    private fun normalizarUrl(texto: String): String? {
        val t = texto.trim()
        if (t.isEmpty()) return null
        val esquema = Omnibox.esquemaExplicito(t)
        val u = when {
            esquema == "http" || esquema == "https" -> t
            esquema == null && Omnibox.pareceDominio(t) ->
                (if (Omnibox.esLocal(Omnibox.hostDe(t))) "http://" else "https://") + t
            else -> return null
        }
        return if (Omnibox.hostDe(u).isNotEmpty()) u else null
    }

    private fun pedirTexto(titulo: String, inicial: String, alAceptar: (String) -> Unit) {
        val campo = EditText(this)
        campo.setSingleLine(true)
        campo.setText(inicial)
        campo.setSelectAllOnFocus(true)
        val caja = FrameLayout(this)
        caja.setPadding(20.dp(), 8.dp(), 20.dp(), 0)
        caja.addView(campo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        nuevoDialogo()
            .setTitle(titulo)
            .setView(caja)
            .setPositiveButton("Aceptar") { _, _ ->
                val t = campo.text.toString().trim()
                if (t.isNotEmpty()) alAceptar(t)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Lista de carpetas (con sangría) para elegir destino. [excluir] oculta una carpeta y todo lo que cuelga de ella. */
    private fun elegirCarpeta(titulo: String, actualId: Long, excluir: Long, alElegir: (Long) -> Unit) {
        val ids = ArrayList<Long>()
        val nombres = ArrayList<String>()
        ids.add(0L)
        nombres.add((if (actualId == 0L) "● " else "") + "Marcadores (raíz)")
        for ((c, nivel) in marcadores.arbol(excluir)) {
            ids.add(c.id)
            nombres.add("    ".repeat(nivel) + (if (c.id == actualId) "● " else "") + "📁 " + c.nombre)
        }
        nuevoDialogo()
            .setTitle(titulo)
            .setItems(nombres.toTypedArray()) { _, i -> alElegir(ids[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Formulario nombre + dirección + carpeta. [alGuardar] devuelve false para dejar el formulario abierto. */
    private fun editarMarcador(
        encabezado: String,
        titulo: String,
        url: String,
        carpeta: Long,
        alGuardar: (String, String, Long) -> Boolean
    ) {
        val caja = LinearLayout(this)
        caja.setOrientation(LinearLayout.VERTICAL)
        caja.setPadding(20.dp(), 8.dp(), 20.dp(), 0)
        val campoTitulo = EditText(this)
        campoTitulo.hint = "Nombre"
        campoTitulo.setSingleLine(true)
        campoTitulo.setText(titulo)
        val campoUrl = EditText(this)
        campoUrl.hint = "Dirección (https://…)"
        campoUrl.setSingleLine(true)
        campoUrl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        campoUrl.setText(url)
        val etiquetaCarpeta = TextView(this)
        etiquetaCarpeta.textSize = 15f
        etiquetaCarpeta.setTextColor(paleta.acento)
        etiquetaCarpeta.setPadding(4.dp(), 14.dp(), 4.dp(), 14.dp())
        etiquetaCarpeta.isClickable = true
        var elegida = carpeta
        fun pintar() {
            etiquetaCarpeta.text = "📁  " + marcadores.ruta(elegida) + "  ▸"
        }
        pintar()
        etiquetaCarpeta.setOnClickListener {
            elegirCarpeta("Guardar en", elegida, 0L) { id ->
                elegida = id
                pintar()
            }
        }
        caja.addView(campoTitulo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        caja.addView(campoUrl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        caja.addView(etiquetaCarpeta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = nuevoDialogo()
            .setTitle(encabezado)
            .setView(caja)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        d.show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val u = normalizarUrl(campoUrl.text.toString())
            if (u == null) {
                toast("Dirección no válida")
            } else {
                val nombre = campoTitulo.text.toString().trim()
                if (alGuardar(if (nombre.isEmpty()) u else nombre, u, elegida)) d.dismiss()
            }
        }
    }

    /** Pulsación larga en ☆: guardar la página eligiendo nombre y carpeta, o editar su marcador. */
    private fun editarMarcadorActual() {
        val p = actual ?: return
        if (p.inicio || p.url.isEmpty()) {
            toast("No hay nada que guardar")
            return
        }
        val existente = marcadores.buscar(p.url)
        if (existente != null) {
            editarMarcador("Editar marcador", existente.titulo, existente.url, existente.carpeta) { t, u, c ->
                val ok = marcadores.actualizar(existente, t, u, c)
                if (!ok) toast("Ya existe un marcador con esa dirección")
                actualizarUi()
                ok
            }
        } else {
            editarMarcador("Guardar marcador", tituloDe(p), p.url, 0L) { t, u, c ->
                val ok = marcadores.agregar(t, u, c)
                if (ok) {
                    toast("Marcador guardado")
                } else {
                    toast(if (marcadores.contiene(u)) "Ya existe un marcador con esa dirección" else "Has alcanzado el máximo de marcadores")
                }
                actualizarUi()
                ok
            }
        }
    }

    private fun confirmarBorrarCarpeta(c: CarpetaMarcadores, alBorrar: () -> Unit) {
        val n = marcadores.elementosEn(c.id)
        val detalle = if (n > 0) "Sus $n elemento(s) pasarán a «" + marcadores.ruta(c.padre) + "»." else "Está vacía."
        nuevoDialogo()
            .setTitle("Eliminar carpeta")
            .setMessage("Se eliminará «" + c.nombre + "». " + detalle)
            .setPositiveButton("Eliminar") { _, _ ->
                marcadores.borrarCarpeta(c)
                alBorrar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Gestor de marcadores: tocar abre (o entra en una carpeta); mantener pulsado abre las acciones
     * (editar, mover, eliminar, renombrar...). «Nueva carpeta» crea dentro de la carpeta actual.
     */
    private fun mostrarMarcadores(carpetaInicial: Long = 0L) {
        var carpetaActual = if (marcadores.carpeta(carpetaInicial) != null) carpetaInicial else 0L
        var filas: List<FilaMarcadores> = marcadores.filas(carpetaActual)
        var dialogo: AlertDialog? = null

        val vacio = TextView(this)
        vacio.setTextColor(paleta.tenue)
        vacio.textSize = 14f
        vacio.setPadding(24.dp(), 16.dp(), 24.dp(), 8.dp())
        val vista = ListView(this)
        val adaptador = object : ArrayAdapter<FilaMarcadores>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList<FilaMarcadores>()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val f = getItem(position)
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                if (f != null) {
                    val c = f.carpeta
                    val m = f.marcador
                    if (f.subir) {
                        t1.text = "⬆  Subir"
                        t2.text = marcadores.ruta(marcadores.carpeta(carpetaActual)?.padre ?: 0L)
                    } else if (c != null) {
                        t1.text = "📁  " + c.nombre
                        val n = marcadores.elementosEn(c.id)
                        t2.text = if (n == 1) "1 elemento" else "$n elementos"
                    } else if (m != null) {
                        t1.text = if (m.titulo.isNotEmpty()) m.titulo else m.url
                        t2.text = m.url
                    }
                }
                t1.maxLines = 1
                t2.maxLines = 1
                t1.ellipsize = TextUtils.TruncateAt.END
                t2.ellipsize = TextUtils.TruncateAt.END
                t1.setTextColor(paleta.texto)
                t2.setTextColor(paleta.tenue)
                return v
            }
        }
        vista.setAdapter(adaptador)

        fun refrescar() {
            filas = marcadores.filas(carpetaActual)
            adaptador.clear()
            adaptador.addAll(filas)
            adaptador.notifyDataSetChanged()
            dialogo?.setTitle(marcadores.ruta(carpetaActual))
            var hay = false
            for (f in filas) {
                if (!f.subir) hay = true
            }
            vacio.text = if (carpetaActual == 0L) "Aún no tienes marcadores. Pulsa ☆ para guardar una página." else "Carpeta vacía."
            vacio.visibility = if (hay) View.GONE else View.VISIBLE
            actualizarUi()
        }

        vista.setOnItemClickListener { _, _, pos, _ ->
            val f = filas[pos]
            val c = f.carpeta
            val m = f.marcador
            if (f.subir) {
                carpetaActual = marcadores.carpeta(carpetaActual)?.padre ?: 0L
                refrescar()
            } else if (c != null) {
                carpetaActual = c.id
                refrescar()
            } else if (m != null) {
                dialogo?.dismiss()
                cargarEnActual(m.url)
            }
        }
        vista.setOnItemLongClickListener { _, _, pos, _ ->
            val f = filas[pos]
            val c = f.carpeta
            val m = f.marcador
            val items = ArrayList<Pair<String, () -> Unit>>()
            if (m != null) {
                items.add(Pair("Abrir en pestaña nueva", {
                    dialogo?.dismiss()
                    nuevaPestana(m.url)
                    Unit
                }))
                items.add(Pair("Editar…", {
                    editarMarcador("Editar marcador", m.titulo, m.url, m.carpeta) { t, u, cf ->
                        val ok = marcadores.actualizar(m, t, u, cf)
                        if (ok) refrescar() else toast("Ya existe un marcador con esa dirección")
                        ok
                    }
                    Unit
                }))
                items.add(Pair("Mover a…", {
                    elegirCarpeta("Mover a", m.carpeta, 0L) { destino ->
                        marcadores.moverMarcador(m, destino)
                        refrescar()
                    }
                    Unit
                }))
                items.add(Pair("Eliminar", {
                    marcadores.quitar(m.url)
                    refrescar()
                    Unit
                }))
            } else if (c != null) {
                items.add(Pair("Renombrar…", {
                    pedirTexto("Renombrar carpeta", c.nombre) { n ->
                        marcadores.renombrarCarpeta(c, n)
                        refrescar()
                    }
                    Unit
                }))
                items.add(Pair("Mover a…", {
                    elegirCarpeta("Mover carpeta a", c.padre, c.id) { destino ->
                        if (marcadores.moverCarpeta(c, destino)) refrescar() else toast("No se puede mover ahí")
                    }
                    Unit
                }))
                items.add(Pair("Eliminar carpeta", {
                    confirmarBorrarCarpeta(c) { refrescar() }
                    Unit
                }))
            }
            if (items.isNotEmpty()) opciones(null, items)
            true
        }

        val caja = LinearLayout(this)
        caja.setOrientation(LinearLayout.VERTICAL)
        caja.addView(vacio, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        caja.addView(vista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val d = nuevoDialogo()
            .setTitle(marcadores.ruta(carpetaActual))
            .setView(caja)
            .setPositiveButton("Nueva carpeta", null)
            .setNegativeButton("Cerrar", null)
            .create()
        dialogo = d
        d.show()
        // «Nueva carpeta» no debe cerrar el gestor.
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            pedirTexto("Nueva carpeta", "") { n ->
                if (marcadores.crearCarpeta(n, carpetaActual) == null) toast("No se pudo crear la carpeta")
                refrescar()
            }
        }
        refrescar()
    }

    /** Últimas pestañas normales cerradas. Solo en memoria: no se guardan en disco, no se anotan las privadas y se vacía al borrar el historial. */
    private val cerradas = ArrayList<Pair<String, String>>()

    private fun recordarCerrada(p: Pestana) {
        if (p.privada || p.esEmergente || !ajustes.guardarHistorial) return
        val url = if (p.url.startsWith("http")) p.url else (p.pendiente ?: "")
        if (!url.startsWith("http")) return
        cerradas.removeAll { it.first == url }
        cerradas.add(0, Pair(url, p.titulo))
        while (cerradas.size > MAX_CERRADAS) cerradas.removeAt(cerradas.size - 1)
    }

    private fun mostrarCerradas() {
        if (cerradas.isEmpty()) {
            toast("No hay pestañas cerradas recientes")
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (e in ArrayList(cerradas)) {
            val etiqueta = if (e.second.isNotEmpty()) e.second + "\n" + e.first else e.first
            items.add(Pair<String, () -> Unit>(etiqueta, {
                cerradas.remove(e)
                nuevaPestana(e.first)
            }))
        }
        items.add(Pair<String, () -> Unit>("Vaciar esta lista", { cerradas.clear() }))
        opciones("Pestañas cerradas (solo en memoria)", items)
    }

    /** Lista de pestañas con búsqueda por título o dirección, selección múltiple para cerrar y acceso a las cerradas. */
    private fun mostrarPestanas() {
        val lista = ListView(this)
        var dialogo: AlertDialog? = null
        var adaptador: BaseAdapter? = null
        var filtro = ""
        var seleccion = false
        val marcadas = HashSet<Int>()
        val visibles = ArrayList<Pestana>()
        var actualizarBarra: () -> Unit = {}

        fun refrescar() {
            visibles.clear()
            val palabras = filtro.lowercase().split(' ').filter { it.isNotEmpty() }
            for (q in pestanas) {
                val txt = (tituloDe(q) + " " + q.url).lowercase()
                if (palabras.all { txt.contains(it) }) visibles.add(q)
            }
            val ids = HashSet<Int>()
            for (q in pestanas) ids.add(q.id)
            marcadas.retainAll(ids)
            adaptador?.notifyDataSetChanged()
            actualizarBarra()
        }

        adaptador = object : BaseAdapter() {
            override fun getCount(): Int = visibles.size
            override fun getItem(position: Int): Any = visibles[position]
            override fun getItemId(position: Int): Long = visibles[position].id.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val p = visibles[position]
                val fila = LinearLayout(this@MainActivity)
                fila.setOrientation(LinearLayout.HORIZONTAL)
                fila.setGravity(Gravity.CENTER_VERTICAL)
                fila.setPadding(16.dp(), 8.dp(), 4.dp(), 8.dp())
                val textos = LinearLayout(this@MainActivity)
                textos.setOrientation(LinearLayout.VERTICAL)
                val t1 = TextView(this@MainActivity)
                t1.text = (if (seleccion) (if (marcadas.contains(p.id)) "☑ " else "☐ ") else "") +
                    (if (p === actual) "● " else "") + (if (p === sonando) "🔊 " else "") + (if (p.suspendida) "💤 " else "") +
                    (if (p.privada) "🕶 " else "") + tituloDe(p)
                t1.textSize = 16f
                t1.maxLines = 1
                t1.ellipsize = TextUtils.TruncateAt.END
                t1.setTextColor(paleta.texto)
                val t2 = TextView(this@MainActivity)
                t2.text = if (p.url.isNotEmpty()) p.url else "Pestaña nueva"
                t2.textSize = 12f
                t2.maxLines = 1
                t2.ellipsize = TextUtils.TruncateAt.END
                t2.setTextColor(paleta.tenue)
                textos.addView(t1)
                textos.addView(t2)
                fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (!seleccion) {
                    if (p.silenciada || p.suena) {
                        val altavoz = boton(if (p.silenciada) "🔇" else "🔊", 18f) {
                            alternarSilencio(p)
                            notifyDataSetChanged()
                        }
                        fila.addView(altavoz, LinearLayout.LayoutParams(44.dp(), 48.dp()))
                    }
                    val cerrar = boton("✕", 18f) {
                        cerrarPestana(p)
                        refrescar()
                    }
                    fila.addView(cerrar, LinearLayout.LayoutParams(48.dp(), 48.dp()))
                }
                return fila
            }
        }
        lista.setAdapter(adaptador)

        val campo = EditText(this)
        campo.hint = "Buscar en pestañas (título o dirección)"
        campo.isSingleLine = true
        campo.textSize = 15f
        campo.setTextColor(paleta.texto)
        campo.setHintTextColor(paleta.tenue)
        campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filtro = s?.toString() ?: ""
                refrescar()
            }
        })

        val btnSel = boton("☑ Seleccionar", 14f) {
            seleccion = !seleccion
            marcadas.clear()
            refrescar()
        }
        val btnTodas = boton("Todas", 14f) {
            val todas = marcadas.size == visibles.size
            marcadas.clear()
            if (!todas) for (q in visibles) marcadas.add(q.id)
            refrescar()
        }
        val btnCerrarSel = boton("Cerrar", 14f) {
            val aCerrar = pestanas.filter { marcadas.contains(it.id) }
            if (aCerrar.isEmpty()) {
                toast("Marca alguna pestaña")
            } else {
                for (q in aCerrar) cerrarPestana(q)
                marcadas.clear()
                seleccion = false
                refrescar()
                toast(aCerrar.size.toString() + " pestañas cerradas. Se pueden reabrir desde «↩ Cerradas»")
            }
        }
        val btnCerradas = boton("↩ Cerradas", 14f) {
            dialogo?.dismiss()
            mostrarCerradas()
        }
        actualizarBarra = {
            btnSel.text = if (seleccion) "Salir" else "☑ Seleccionar"
            btnTodas.visibility = if (seleccion) View.VISIBLE else View.GONE
            btnCerrarSel.visibility = if (seleccion) View.VISIBLE else View.GONE
            btnCerrarSel.text = "Cerrar (" + marcadas.size + ")"
            btnCerradas.visibility = if (seleccion) View.GONE else View.VISIBLE
            btnCerradas.text = "↩ Cerradas (" + cerradas.size + ")"
        }
        val barra = LinearLayout(this)
        barra.setOrientation(LinearLayout.HORIZONTAL)
        for (b in listOf(btnSel, btnTodas, btnCerrarSel, btnCerradas)) {
            barra.addView(b, LinearLayout.LayoutParams(0, 40.dp(), 1f))
        }

        val vacio = TextView(this)
        vacio.text = "Sin coincidencias"
        vacio.setTextColor(paleta.tenue)
        vacio.gravity = Gravity.CENTER
        vacio.setPadding(16.dp(), 24.dp(), 16.dp(), 24.dp())
        vacio.visibility = View.GONE

        val raiz = LinearLayout(this)
        raiz.setOrientation(LinearLayout.VERTICAL)
        raiz.setPadding(16.dp(), 4.dp(), 16.dp(), 0)
        raiz.addView(campo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        raiz.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        raiz.addView(vacio)
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lista.emptyView = vacio

        refrescar()
        // Qué pestañas suenan ahora: se pregunta a cada página y se refresca la lista según llegan las respuestas.
        for (q in pestanas) {
            if (q.silenciada) continue
            q.web?.evaluateJavascript(JS_SUENA) { r ->
                val s = r == "1"
                if (q.suena != s) {
                    q.suena = s
                    adaptador?.notifyDataSetChanged()
                }
            }
        }
        lista.setOnItemClickListener { _, _, pos, _ ->
            if (pos < 0 || pos >= visibles.size) return@setOnItemClickListener
            val q = visibles[pos]
            if (seleccion) {
                if (!marcadas.remove(q.id)) marcadas.add(q.id)
                refrescar()
            } else {
                dialogo?.dismiss()
                activar(q)
            }
        }
        val d = nuevoDialogo()
            .setTitle("Pestañas")
            .setView(raiz)
            .setPositiveButton("Nueva") { _, _ -> nuevaPestana(null) }
            .setNeutralButton("Privada") { _, _ -> nuevaPestana(null, true) }
            .setNegativeButton("Cerrar", null)
            .create()
        dialogo = d
        d.show()
    }

    private fun menuContextual(v: WebView, p: Pestana): Boolean {
        val r = v.hitTestResult
        val extra = r.extra
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                if (extra == null) return false
                menuEnlace(extra, null, p.privada, v, p)
                return true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = Handler(Looper.getMainLooper()) { m ->
                    val enlace = m.data.getString("url")
                    if (enlace != null) menuEnlace(enlace, extra, p.privada, v, p)
                    true
                }.obtainMessage()
                v.requestFocusNodeHref(msg)
                return true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (extra == null) return false
                menuEnlace(null, extra, p.privada, v, p)
                return true
            }
        }
        return false
    }

    private fun menuEnlace(enlace: String?, imagen: String?, privada: Boolean, web: WebView, p: Pestana) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        if (enlace != null) {
            add("Abrir en pestaña nueva") { nuevaPestana(enlace, privada) }
            add("Copiar enlace") { copiar(enlace) }
            add("Compartir enlace") { compartir(enlace) }
            if (ReproductorExterno.esMedio(enlace, null) || ReproductorExterno.esMega(enlace)) {
                if (ReproductorExterno.instalado(this)) {
                    add("Reproducir en Optimo Play") {
                        ReproductorExterno.enviar(this, enlace, null, p.url, web.settings.userAgentString)
                    }
                }
                add("Abrir con otra app…") { elegirAppMedio(enlace, null, p, web.settings.userAgentString, null) }
            }
        }
        if (imagen != null) {
            add("Abrir imagen en pestaña nueva") { nuevaPestana(imagen, privada) }
            add("Descargar imagen") {
                descargar(imagen, actual?.web?.settings?.userAgentString, null, null, actual?.privada == true)
            }
        }
        add("Bloquear este anuncio…") { bloquearAnuncio(web, p, imagen, p.toqueX, p.toqueY) }
        opciones(enlace ?: imagen, items)
    }

    // ---------------------------------------------------------------- reglas propias contra anuncios

    /** Registra la última pulsación y, en modo «elegir», convierte el siguiente toque en la selección. */
    private fun alTocar(v: WebView, p: Pestana, ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            p.toqueX = ev.x
            p.toqueY = ev.y
            p.toqueMs = android.os.SystemClock.uptimeMillis()
        }
        if (!p.eligiendo) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_UP -> {
                p.eligiendo = false
                if (p.eligiendoElemento) {
                    p.eligiendoElemento = false
                    elegirElemento(v, p, ev.x, ev.y, 0)
                } else {
                    bloquearAnuncio(v, p, null, ev.x, ev.y)
                }
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val holgura = android.view.ViewConfiguration.get(this).scaledTouchSlop
                if (Math.abs(ev.x - p.toqueX) > holgura || Math.abs(ev.y - p.toqueY) > holgura) {
                    p.eligiendo = false
                    p.eligiendoElemento = false
                    toast("Selección cancelada")
                    return false
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                p.eligiendo = false
                p.eligiendoElemento = false
                return false
            }
        }
        return true
    }

    private fun iniciarSeleccion(p: Pestana) {
        p.eligiendo = true
        toast("Toca el anuncio que quieres bloquear")
    }

    /** Identifica el anuncio en (x, y) —píxeles de pantalla— y ofrece ocultarlo y/o bloquear su dominio. */
    @Suppress("DEPRECATION")
    private fun bloquearAnuncio(web: WebView, p: Pestana, imagen: String?, x: Float, y: Float) {
        val escala = if (web.scale > 0f) web.scale else resources.displayMetrics.density
        web.evaluateJavascript(anuncios.scriptElegir(x / escala, y / escala)) { crudo ->
            var datos: org.json.JSONObject? = null
            try {
                val v = org.json.JSONTokener(crudo ?: "null").nextValue()
                if (v is String) datos = org.json.JSONObject(v)
            } catch (e: Exception) {
                datos = null
            }
            if (datos == null) {
                toast("No he encontrado un anuncio ahí. Prueba «Bloquear un anuncio (tocar)» en el menú")
                return@evaluateJavascript
            }
            ofrecerReglas(web, p, imagen, datos)
        }
    }

    private fun ofrecerReglas(web: WebView, p: Pestana, imagen: String?, d: org.json.JSONObject) {
        val paginaHost = (try { Uri.parse(web.url ?: p.url).host } catch (e: Exception) { null } ?: "").lowercase()
        val sel = d.optString("sel")
        if (paginaHost.isEmpty() || sel.isEmpty()) {
            toast("No se pudo crear la regla para esta página")
            return
        }
        var dom = d.optString("dom").lowercase().removePrefix("www.")
        if (dom.isEmpty() && imagen != null) {
            dom = (try { Uri.parse(imagen).host } catch (e: Exception) { null } ?: "").lowercase().removePrefix("www.")
        }
        // Nunca se ofrece bloquear el propio dominio de la página (o uno emparentado): rompería el sitio entero.
        val propio = dom.isEmpty() || dom == paginaHost || paginaHost.endsWith(".$dom") || dom.endsWith(".$paginaHost") ||
            UtilDominio.normalizar(dom) == null
        val sitio = ReglasUsuario.clave(paginaHost)
        val n = d.optInt("n", 1)
        val etiquetas = ArrayList<String>()
        etiquetas.add("Ocultar en $sitio (" + d.optString("tag") + " " + d.optInt("w") + "×" + d.optInt("h") +
            (if (n > 1) ", $n elementos" else "") + ")\n" + sel)
        if (!propio) etiquetas.add("Bloquear el dominio $dom en todas partes")
        val marcadas = BooleanArray(etiquetas.size) { i -> i == 0 }
        nuevoDialogo()
            .setTitle("Bloquear este anuncio")
            .setMultiChoiceItems(etiquetas.toTypedArray(), marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton("Añadir") { _, _ ->
                var hecho = false
                if (marcadas[0] && ReglasUsuario.agregarOcultar(sitio, sel)) {
                    hecho = true
                    reinstalarAnuncios()
                    anuncios.aplicarAhora(web, paginaHost)
                }
                if (marcadas.size > 1 && marcadas[1] && ReglasUsuario.agregarDominio(dom)) hecho = true
                toast(if (hecho) "Regla añadida. Recarga la página para ver el efecto completo" else "No se añadió nada")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarReglasUsuario(sitio: String? = null) {
        val ocultar = ReglasUsuario.listaOcultar().filter { sitio == null || it.host == sitio }
        val estilos = ReglasUsuario.listaCss().filter { sitio == null || it.host == sitio }
        val dominios = if (sitio == null) ReglasUsuario.listaDominios() else emptyList()
        val permisos = ReglasUsuario.listaPermisos().filter { sitio == null || it.sitio == sitio }
        if (ocultar.isEmpty() && estilos.isEmpty() && dominios.isEmpty() && permisos.isEmpty()) {
            toast("Aún no has añadido reglas")
            return
        }
        val etiquetas = ArrayList<String>()
        for (r in ocultar) etiquetas.add("Ocultar en " + r.host + ": " + r.sel)
        for (r in estilos) etiquetas.add("Estilo en " + r.host + ": " + r.nombre)
        for (dd in dominios) etiquetas.add("Bloquear dominio: $dd")
        for (r in permisos) etiquetas.add("Permitir " + r.dominio + (if (r.sitio == ReglasUsuario.TODOS) " en todos los sitios" else " en " + r.sitio))
        val marcadas = BooleanArray(etiquetas.size)
        nuevoDialogo()
            .setTitle(if (sitio == null) "Reglas añadidas (marca para borrar)" else "Reglas de $sitio (marca para borrar)")
            .setMultiChoiceItems(etiquetas.toTypedArray(), marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton("Borrar marcadas") { _, _ ->
                var borradas = 0
                for (i in ocultar.indices) if (marcadas[i]) { ReglasUsuario.quitarOcultar(ocultar[i]); borradas++ }
                for (j in estilos.indices) if (marcadas[ocultar.size + j]) { ReglasUsuario.quitarCss(estilos[j]); borradas++ }
                for (k in dominios.indices) if (marcadas[ocultar.size + estilos.size + k]) { ReglasUsuario.quitarDominio(dominios[k]); borradas++ }
                for (m in permisos.indices) if (marcadas[ocultar.size + estilos.size + dominios.size + m]) { ReglasUsuario.quitarPermiso(permisos[m]); borradas++ }
                if (borradas > 0) {
                    reinstalarAnuncios()
                    toast("$borradas regla(s) borradas. Recarga las páginas abiertas")
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ---------------------------------------------------------------- personalizar un sitio: elegir elementos y estilos

    private fun mostrarPersonalizarSitio(p: Pestana) {
        val sitio = ReglasUsuario.clave(Omnibox.hostDe(p.url))
        if (sitio.isEmpty()) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Versión de escritorio en este sitio: " + si(ajustes.escritorioEn(sitio))) {
            ajustes.fijarEscritorioEn(sitio, !ajustes.escritorioEn(sitio))
            val w = p.web
            if (w != null) {
                prepararNavegacion(p, w, sitio)
                w.reload()
            }
            mostrarPersonalizarSitio(p)
        }
        add("Modo oscuro en este sitio: " + NOMBRES_OSCURO_SITIO[ajustes.modoOscuroEn(sitio)]) {
            ajustes.fijarModoOscuroEn(sitio, (ajustes.modoOscuroEn(sitio) + 1) % 3)
            val w = p.web
            if (w != null) {
                prepararNavegacion(p, w, sitio)
                w.reload()
            }
            mostrarPersonalizarSitio(p)
        }
        if (ajustes.bloquearAnuncios || bloqueoPausadoEnPagina(p.url)) {
            add("Bloqueo de anuncios en este sitio: " + (if (bloqueoPausadoEnPagina(p.url)) "pausado" else "activo")) { alternarPausaBloqueo(p) }
        }
        add("Elegir un elemento de la página (tocar)") {
            p.eligiendo = true
            p.eligiendoElemento = true
            toast("Toca el elemento que quieres ocultar o cambiar")
        }
        add("CSS propio de este sitio…") {
            editarCss("CSS de $sitio", ReglasUsuario.cssPropio(sitio), ".ejemplo { display: none !important }", true) { css ->
                if (ReglasUsuario.fijarCssPropio(sitio, css)) {
                    reinstalarAnuncios()
                    if (css.isNotBlank()) p.web?.let { anuncios.aplicarCssAhora(it, css) }
                    toast(if (css.isBlank()) "CSS borrado. Recarga la página" else "Guardado. Recarga la página para quitar lo anterior")
                } else {
                    toast("El CSS es demasiado largo (máximo 20 000 caracteres)")
                }
            }
        }
        add("Reglas de este sitio (" + ReglasUsuario.totalDe(sitio) + ")…") { mostrarReglasUsuario(sitio) }
        opciones("Personalizar " + sitio, items)
    }

    /** Editor de texto multilínea para CSS. [alGuardar] recibe el texto (vacío = borrar, si [puedeBorrar]). */
    private fun editarCss(titulo: String, inicial: String, pista: String, puedeBorrar: Boolean, alGuardar: (String) -> Unit) {
        val campo = EditText(this)
        campo.setText(inicial)
        campo.hint = pista
        campo.minLines = 6
        campo.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        campo.typeface = android.graphics.Typeface.MONOSPACE
        campo.textSize = 13f
        campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        campo.setHorizontallyScrolling(false)
        val caja = FrameLayout(this)
        val m = (16 * resources.displayMetrics.density).toInt()
        caja.setPadding(m, m / 2, m, 0)
        caja.addView(campo)
        val b = nuevoDialogo()
            .setTitle(titulo)
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ -> alGuardar(campo.text.toString()) }
            .setNegativeButton("Cancelar", null)
        if (puedeBorrar && inicial.isNotEmpty()) b.setNeutralButton("Borrar") { _, _ -> alGuardar("") }
        b.show()
    }

    private fun quitarMarca(web: WebView) {
        web.evaluateJavascript(
            "(function(){var m=window.__optimoMarca;if(m){try{m.el.style.outline=m.o;m.el.style.outlineOffset=m.oo;}catch(e){}window.__optimoMarca=null;}})()",
            null
        )
    }

    /** Identifica el elemento en (x, y) —píxeles de pantalla—, subiendo [nivel] contenedores, y ofrece qué hacer con él. */
    @Suppress("DEPRECATION")
    private fun elegirElemento(web: WebView, p: Pestana, x: Float, y: Float, nivel: Int) {
        val escala = if (web.scale > 0f) web.scale else resources.displayMetrics.density
        web.evaluateJavascript(anuncios.scriptElemento(x / escala, y / escala, nivel)) { crudo ->
            var datos: org.json.JSONObject? = null
            try {
                val v = org.json.JSONTokener(crudo ?: "null").nextValue()
                if (v is String) datos = org.json.JSONObject(v)
            } catch (e: Exception) {
                datos = null
            }
            if (datos == null) {
                toast("No he podido seleccionar nada ahí")
            } else {
                mostrarElemento(web, p, x, y, nivel, datos)
            }
        }
    }

    private fun mostrarElemento(web: WebView, p: Pestana, x: Float, y: Float, nivel: Int, d: org.json.JSONObject) {
        val paginaHost = (try { Uri.parse(web.url ?: p.url).host } catch (e: Exception) { null } ?: "").lowercase()
        val sel = d.optString("sel")
        if (paginaHost.isEmpty() || sel.isEmpty()) {
            quitarMarca(web)
            toast("No se pudo crear la regla para esta página")
            return
        }
        val sitio = ReglasUsuario.clave(paginaHost)
        val n = d.optInt("n", 1)
        val texto = d.optString("texto")
        val cabecera = d.optString("tag") + " " + d.optInt("w") + "×" + d.optInt("h") +
            (if (n > 1) " · coincide con $n elementos" else "") + (if (texto.isNotEmpty()) "\n«" + texto + "»" else "")
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Ocultar este elemento") {
            quitarMarca(web)
            if (ReglasUsuario.agregarOcultar(sitio, sel)) {
                reinstalarAnuncios()
                anuncios.aplicarAhora(web, paginaHost)
                toast("Elemento oculto en $sitio")
            } else {
                toast("Ya estaba oculto")
            }
        }
        add("Cambiar su apariencia…") { mostrarApariencia(web, sitio, sel) }
        if (d.optBoolean("hayPadre")) add("Elegir el contenedor ▲") { elegirElemento(web, p, x, y, nivel + 1) }
        if (nivel > 0) add("Elegir uno más pequeño ▼") { elegirElemento(web, p, x, y, nivel - 1) }
        val b = nuevoDialogo()
            .setTitle(cabecera)
            .setItems(Array(items.size) { i -> items[i].first }) { _, i -> items[i].second() }
            .setNegativeButton("Cancelar") { _, _ -> quitarMarca(web) }
            .setOnCancelListener { quitarMarca(web) }
        b.show()
    }

    /** Cambios de apariencia listos para usar sobre el elemento elegido; «§» se sustituye por su selector. */
    private val APARIENCIAS = listOf(
        Pair("Ampliar un 25 %", "§{zoom:1.25!important}"),
        Pair("Reducir al 80 %", "§{zoom:.8!important}"),
        Pair("Texto más grande", "§{font-size:130%!important}"),
        Pair("Fondo oscuro, texto claro", "§,§ *{background:#121212!important;color:#e8e8e8!important;border-color:#444!important}"),
        Pair("Fondo claro, texto oscuro", "§,§ *{background:#ffffff!important;color:#202124!important}"),
        Pair("Quitar fondos e imágenes de fondo", "§,§ *{background:none!important}"),
        Pair("Ancho completo", "§{width:100%!important;max-width:100%!important;margin-left:0!important;margin-right:0!important}"),
        Pair("Semitransparente (30 %)", "§{opacity:.3!important}"),
        Pair("Soltarlo de la pantalla (barras fijas)", "§{position:static!important}"),
        Pair("Quitar sombras y bordes", "§{box-shadow:none!important;border:none!important;text-shadow:none!important}")
    )

    private fun mostrarApariencia(web: WebView, sitio: String, sel: String) {
        val nombres = ArrayList<String>()
        for (a in APARIENCIAS) nombres.add(a.first)
        nombres.add("Escribir CSS propio…")
        fun guardar(nombre: String, css: String) {
            quitarMarca(web)
            if (ReglasUsuario.agregarCss(sitio, nombre + ": " + sel, css)) {
                reinstalarAnuncios()
                anuncios.aplicarCssAhora(web, css)
                toast("Aplicado en $sitio. Se mantendrá en tus visitas")
            } else {
                toast("No se añadió (ya existía o es demasiado largo)")
            }
        }
        nuevoDialogo()
            .setTitle("Apariencia del elemento")
            .setItems(nombres.toTypedArray()) { _, i ->
                if (i < APARIENCIAS.size) {
                    guardar(APARIENCIAS[i].first, APARIENCIAS[i].second.replace("§", sel))
                } else {
                    editarCss("CSS del elemento", sel + " {\n  \n}", "propiedad: valor !important;", false) { css ->
                        guardar("CSS propio", css)
                    }
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> quitarMarca(web) }
            .setOnCancelListener { quitarMarca(web) }
            .show()
    }

    // ---------------------------------------------------------------- ajustes de lectura y voz

    private fun factor(v: Int): String = String.format(java.util.Locale.getDefault(), "%.1f×", v / 100.0)

    private fun elegirNumero(titulo: String, nombres: Array<String>, valores: IntArray, alElegir: (Int) -> Unit) {
        nuevoDialogo()
            .setTitle(titulo)
            .setItems(nombres) { _, i ->
                alElegir(valores[i])
                mostrarAjustesLectura()
            }
            .setNegativeButton("Cancelar") { _, _ -> mostrarAjustesLectura() }
            .setOnCancelListener { mostrarAjustesLectura() }
            .show()
    }

    /** Ajustes › Lectura y voz: voz del modo lectura y aspecto por defecto de la vista de lectura. */
    private fun mostrarAjustesLectura() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        add("Velocidad de la voz: " + factor(ajustes.vozVelocidad)) {
            elegirNumero("Velocidad de la voz", arrayOf("0,6×", "0,8×", "1,0× (normal)", "1,2×", "1,5×", "2,0×"), intArrayOf(60, 80, 100, 120, 150, 200)) {
                ajustes.vozVelocidad = it
            }
        }
        add("Tono de la voz: " + factor(ajustes.vozTono)) {
            elegirNumero("Tono de la voz", arrayOf("Grave (0,8×)", "Normal (1,0×)", "Agudo (1,2×)"), intArrayOf(80, 100, 120)) {
                ajustes.vozTono = it
            }
        }
        add("Probar la voz") {
            configurarVoz()
            lector.alTerminar = { }
            lector.leer("Hola. Así suena la lectura en voz alta de Optimo.", "")
            mostrarAjustesLectura()
        }
        add("Voz e idioma: ajustes de Android…") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (e2: Exception) {
                    toast("No se pudieron abrir los ajustes de voz")
                }
            }
        }
        add("Tamaño de letra del modo lectura: " + ajustes.lecturaTam) {
            elegirNumero("Tamaño de letra", arrayOf("Pequeña (16)", "Normal (19)", "Grande (24)", "Muy grande (30)"), intArrayOf(16, 19, 24, 30)) {
                ajustes.lecturaTam = it
            }
        }
        val temas = arrayOf("Automático (según el modo del sistema)", "Claro", "Sepia", "Oscuro", "Negro")
        val claves = arrayOf("", "claro", "sepia", "oscuro", "negro")
        val temaActual = claves.indexOf(ajustes.lecturaTema).coerceAtLeast(0)
        add("Fondo del modo lectura: " + temas[temaActual].substringBefore(" (")) {
            nuevoDialogo()
                .setTitle("Fondo del modo lectura")
                .setItems(temas) { _, i ->
                    ajustes.lecturaTema = claves[i]
                    mostrarAjustesLectura()
                }
                .setNegativeButton("Cancelar") { _, _ -> mostrarAjustesLectura() }
                .setOnCancelListener { mostrarAjustesLectura() }
                .show()
        }
        add("Tipo de letra: " + (if (ajustes.lecturaFuente == "sans") "sin serifas" else "con serifas")) {
            ajustes.lecturaFuente = if (ajustes.lecturaFuente == "sans") "serif" else "sans"
            mostrarAjustesLectura()
        }
        add("Interlineado: " + arrayOf("compacto", "normal", "amplio")[ajustes.lecturaInter]) {
            ajustes.lecturaInter = (ajustes.lecturaInter + 1) % 3
            mostrarAjustesLectura()
        }
        opciones("Lectura y voz", items)
    }

    // ---------------------------------------------------------------- excepciones de bloqueo (permitir dominios)

    private fun hostDePagina(url: String): String {
        if (url.isEmpty()) return ""
        return (try { Uri.parse(url).host } catch (e: Exception) { null } ?: "").lowercase()
    }

    /** Permite [dominio] en el sitio de la pestaña activa (o en todos los sitios si [todos]). */
    private fun permitirDominioDevTools(dominio: String, todos: Boolean): String {
        val p = actual ?: return "No hay pestaña activa"
        if (p.privada) return "En pestañas privadas no se guardan excepciones"
        val sitio = if (todos) ReglasUsuario.TODOS else ReglasUsuario.clave(hostDePagina(p.url))
        if (sitio.isEmpty()) return "Solo se guardan excepciones en páginas http/https"
        if (!ReglasUsuario.agregarPermiso(sitio, dominio)) return "Esa excepción ya estaba guardada (o el dominio no es válido)"
        return if (todos) "Permitido $dominio en todos los sitios. Recarga la página" else "Permitido $dominio en $sitio. Recarga la página"
    }

    // ---------------------------------------------------------------- reglas permanentes desde las herramientas de desarrollo

    /** Guarda una regla del sitio abierto (la misma que «Personalizar este sitio») y la aplica ya en la página. */
    private fun reglaDevTools(modo: String, a: String, b: String): String {
        val p = actual ?: return "No hay pestaña activa"
        val w = p.web ?: return "No hay página cargada"
        if (p.privada) return "En pestañas privadas no se guardan reglas"
        if (modo == "bloquear") {
            // Desde la Auditoría: bloquea un dominio de terceros en todas las páginas (AdBlock lo consulta en cada petición).
            return if (ReglasUsuario.agregarDominio(a)) "Bloqueado: $a. Recarga la página para ver el efecto" else "No se pudo bloquear $a (ya estaba bloqueado o no es válido)"
        }
        val host = (try { Uri.parse(w.url ?: p.url).host } catch (e: Exception) { null } ?: "").lowercase()
        val sitio = ReglasUsuario.clave(host)
        if (sitio.isEmpty()) return "Solo se guardan reglas en páginas http/https"
        val nuevo: Boolean
        val css: String
        when (modo) {
            "ocultar" -> {
                nuevo = ReglasUsuario.agregarOcultar(sitio, a)
                css = a + "{display:none!important}"
            }
            "estilo" -> {
                nuevo = ReglasUsuario.agregarCss(sitio, a, b)
                css = b
            }
            "cssPropio" -> {
                nuevo = ReglasUsuario.fijarCssPropio(sitio, a)
                css = a
            }
            else -> return "Regla desconocida"
        }
        if (!nuevo) return if (modo == "cssPropio") "El CSS es demasiado largo (máximo 20 000 caracteres)" else "Esa regla ya estaba guardada en $sitio"
        reinstalarAnuncios()
        if (css.isNotBlank()) anuncios.aplicarCssAhora(w, css)
        return "Guardado en $sitio. Se aplicará en tus visitas (Reglas de este sitio)"
    }

    // ---------------------------------------------------------------- bloqueo de anuncios pausado por sitio

    /** ¿Está pausado el bloqueo en el sitio de [url] (la página, no el recurso que pide)? */
    private fun bloqueoPausadoEnPagina(url: String): Boolean {
        if (url.isEmpty()) return false
        val host = try { Uri.parse(url).host } catch (e: Exception) { null } ?: return false
        return ajustes.bloqueoPausadoEn(host)
    }

    /** Menú ⋮ › «Bloqueo de anuncios en este sitio»: pausa o reanuda el bloqueo solo en ese dominio y recarga. */
    private fun alternarPausaBloqueo(p: Pestana) {
        val host = (try { Uri.parse(p.url).host } catch (e: Exception) { null } ?: "").lowercase().removePrefix("www.")
        if (host.isEmpty()) return
        val pausar = !ajustes.bloqueoPausadoEn(host)
        ajustes.pausarBloqueoEn(host, pausar)
        reinstalarAnuncios()
        for (q in pestanas) {
            val w = q.web ?: continue
            if (bloqueoPausadoEnPagina(q.url) == pausar && q.url.isNotEmpty()) w.reload()
        }
        toast(if (pausar) "Bloqueo pausado en $host. Recargando…" else "Bloqueo reanudado en $host. Recargando…")
    }

    private fun mostrarSitiosPausados() {
        val sitios = ajustes.sitiosPausados()
        if (sitios.isEmpty()) {
            toast("No hay sitios con el bloqueo pausado")
            mostrarPrivacidad()
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (sitio in sitios) {
            items.add(Pair<String, () -> Unit>("Reanudar el bloqueo en $sitio", {
                ajustes.pausarBloqueoEn(sitio, false)
                reinstalarAnuncios()
                toast("Bloqueo reanudado en $sitio. Recarga sus pestañas")
                mostrarSitiosPausados()
            }))
        }
        opciones("Bloqueo pausado en estos sitios", items)
    }

    // ---------------------------------------------------------------- capas que tapan la página (muros anti-bloqueador, modales)

    /** Busca capas a pantalla completa que tapan la página y permite ocultarlas ahora o siempre en este sitio. */
    private fun quitarMuros(p: Pestana) {
        val w = p.web
        if (w == null || p.inicio) {
            toast("Abre primero una página")
            return
        }
        val paginaHost = (try { Uri.parse(w.url ?: p.url).host } catch (e: Exception) { null } ?: "").lowercase()
        val sitio = ReglasUsuario.clave(paginaHost)
        if (sitio.isEmpty()) return
        w.evaluateJavascript(anuncios.scriptMuros()) { crudo ->
            var muros: org.json.JSONArray? = null
            var scrollBloqueado = false
            try {
                val v = org.json.JSONTokener(crudo ?: "null").nextValue()
                if (v is String) {
                    val o = org.json.JSONObject(v)
                    muros = o.optJSONArray("muros")
                    scrollBloqueado = o.optBoolean("scrollBloqueado")
                }
            } catch (e: Exception) {
                muros = null
            }
            if (muros == null || muros.length() == 0) {
                if (scrollBloqueado) {
                    nuevoDialogo()
                        .setTitle("No veo ninguna capa")
                        .setMessage("La página tiene el desplazamiento bloqueado. ¿Quieres desbloquearlo?")
                        .setPositiveButton("Desbloquear siempre aquí") { _, _ ->
                            val css = "html,body{overflow:auto!important}"
                            ReglasUsuario.agregarCss(sitio, "Desbloquear desplazamiento", css)
                            reinstalarAnuncios()
                            anuncios.aplicarCssAhora(w, css)
                        }
                        .setNeutralButton("Solo ahora") { _, _ ->
                            anuncios.aplicarCssAhora(w, "html,body{overflow:auto!important}")
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    toast("No he encontrado ninguna capa que tape la página. Prueba «Elegir un elemento» en Personalizar este sitio")
                }
            } else {
                mostrarMuros(w, sitio, muros, scrollBloqueado)
            }
        }
    }

    private fun mostrarMuros(w: WebView, sitio: String, muros: org.json.JSONArray, scrollBloqueado: Boolean) {
        val n = muros.length()
        val sels = ArrayList<String>()
        val etiquetas = ArrayList<String>()
        val marcadas = BooleanArray(n)
        for (i in 0 until n) {
            val m = muros.getJSONObject(i)
            sels.add(m.optString("sel"))
            val texto = m.optString("texto")
            etiquetas.add(m.optString("tag") + " " + m.optInt("w") + "×" + m.optInt("h") + (if (texto.isNotEmpty()) " · «" + texto + "»" else ""))
            marcadas[i] = m.optBoolean("fuerte")
        }
        fun aplicar(guardar: Boolean) {
            var hechos = 0
            for (i in 0 until n) {
                if (!marcadas[i] || sels[i].isEmpty()) continue
                hechos++
                if (guardar) ReglasUsuario.agregarOcultar(sitio, sels[i])
                anuncios.aplicarCssAhora(w, sels[i] + "{display:none!important}")
            }
            if (hechos > 0 || scrollBloqueado) {
                val css = "html,body{overflow:auto!important}"
                if (guardar) ReglasUsuario.agregarCss(sitio, "Desbloquear desplazamiento", css)
                anuncios.aplicarCssAhora(w, css)
            }
            if (guardar) reinstalarAnuncios()
            toast(if (hechos == 0) "No marcaste ninguna capa" else if (guardar) "Capas ocultas en $sitio. Se mantendrá en tus visitas" else "Capas ocultas hasta recargar")
        }
        nuevoDialogo()
            .setTitle("Capas que tapan la página")
            .setMultiChoiceItems(etiquetas.toTypedArray(), marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton("Ocultar siempre aquí") { _, _ -> aplicar(true) }
            .setNeutralButton("Solo ahora") { _, _ -> aplicar(false) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- modo lectura

    private fun abrirModoLectura(p: Pestana) {
        val w = p.web
        if (w == null || p.inicio) {
            toast("Abre primero una página")
            return
        }
        w.evaluateJavascript(Lectura.guion(this, ajustes, paleta.oscuro)) { r ->
            val v = (r ?: "").trim('"')
            if (v == "ok") {
                p.lectura = true
            } else if (v == "cerrado") {
                p.lectura = false
            } else if (v == "vacio") {
                toast("Esta página no parece un artículo")
            } else {
                toast("No se pudo abrir el modo lectura")
            }
        }
    }

    /** Mensajes «optimo-lectura:…» que envía la vista de lectura por la consola de la página. */
    private fun alMensajeLectura(p: Pestana, m: String) {
        if (m.startsWith("prefs:")) {
            Lectura.guardarPrefs(ajustes, m.substring(6))
        } else if (m == "cerrar") {
            p.lectura = false
            if (pestanaVoz === p) {
                lector.parar()
                pestanaVoz = null
            }
        } else if (m == "voz" && p.lectura) {
            alternarVoz(p)
        }
    }

    /** Pone al lector la velocidad y el tono de Ajustes y los avisos de fin y de fallo. */
    private fun configurarVoz() {
        lector.velocidad = ajustes.vozVelocidad / 100f
        lector.tono = ajustes.vozTono / 100f
        lector.alFallo = { motivo -> toast(motivo) }
        lector.alTerminar = {
            val q = pestanaVoz
            pestanaVoz = null
            if (q != null) marcarVoz(q, false)
        }
    }

    private fun marcarVoz(p: Pestana, activa: Boolean) {
        p.web?.evaluateJavascript("window.__optimoLectura&&window.__optimoLectura.voz(" + activa + ")", null)
    }

    private fun alternarVoz(p: Pestana) {
        if (lector.hablando && pestanaVoz === p) {
            lector.parar()
            pestanaVoz = null
            marcarVoz(p, false)
            return
        }
        val w = p.web ?: return
        w.evaluateJavascript("window.__optimoLectura?window.__optimoLectura.texto():''") { crudo ->
            var texto = ""
            var idioma = ""
            try {
                val v = org.json.JSONTokener(crudo ?: "null").nextValue()
                if (v is String && v.isNotEmpty()) {
                    val o = org.json.JSONObject(v)
                    texto = o.optString("texto")
                    idioma = o.optString("lang")
                }
            } catch (e: Exception) {
                texto = ""
            }
            if (texto.isEmpty()) {
                toast("No hay texto para leer")
            } else {
                pestanaVoz = p
                configurarVoz()
                lector.leer(texto, idioma)
                marcarVoz(p, true)
            }
        }
    }

    // ---------------------------------------------------------------- descargas, externos, intents

    // ---------------------------------------------------------------- enlaces multimedia y magnets: ¿navegador u otra app?

    /** Una opción del diálogo de apps; [accion] recibe si se marcó «Recordar mi elección». */
    private class FilaApp(val etiqueta: String, val icono: Drawable?, val accion: (Boolean) -> Unit)

    /**
     * Decide qué hacer con un vídeo, audio, torrent o magnet según Ajustes › «Enlaces multimedia y magnets»:
     * preguntar, hacerlo en el navegador (como antes) o enviarlo siempre a la app elegida (Optimo Play u otra).
     * [enNavegador] es lo que ocurre «aquí» (null en magnets, que un navegador no puede abrir); [etiquetaNavegador] su texto.
     */
    private fun gestionarMedio(
        url: String, mime: String?, p: Pestana, ua: String?, titulo: String?,
        etiquetaNavegador: String?, enNavegador: (() -> Unit)?
    ) {
        val magnet = ReproductorExterno.esMagnet(url)
        val modo = if (magnet) ajustes.modoMagnet else ajustes.modoMedio
        val paqueteFijo = if (magnet) ajustes.appMagnet else ajustes.appMedio
        val apps = AppsMedia.apps(this, url, mime)
        val lanzar = { app: AppDestino ->
            if (!AppsMedia.abrir(this, app, url, mime, null, p.url.ifEmpty { null }, ua)) toast("No se pudo abrir con " + app.etiqueta)
        }

        if (modo == 1) {
            // Navegador / automático: el comportamiento de siempre.
            if (!magnet) {
                enNavegador?.invoke()
            } else {
                val play = apps.firstOrNull { it.paquete == ReproductorExterno.PAQUETE }
                if (play != null) lanzar(play) else abrirExternoSistema(url)
            }
            return
        }
        if (modo == 2) {
            val app = apps.firstOrNull { it.paquete == paqueteFijo }
            if (app != null) {
                lanzar(app)
                return
            }
            // La app elegida ya no está instalada: se pregunta.
        }
        if (apps.isEmpty()) {
            // Nada que ofrecer: no se pregunta.
            if (enNavegador != null) enNavegador() else abrirExternoSistema(url)
            return
        }
        val filas = ArrayList<FilaApp>()
        if (enNavegador != null) {
            filas.add(FilaApp(etiquetaNavegador ?: "Abrir en el navegador", null) { recordar ->
                if (recordar) ajustes.modoMedio = 1
                enNavegador()
            })
        }
        for (a in apps) {
            filas.add(FilaApp(a.etiqueta, a.icono) { recordar ->
                if (recordar) {
                    if (magnet) {
                        ajustes.modoMagnet = 2
                        ajustes.appMagnet = a.paquete
                    } else {
                        ajustes.modoMedio = 2
                        ajustes.appMedio = a.paquete
                    }
                }
                lanzar(a)
            })
        }
        filas.add(FilaApp("Copiar enlace", null) { copiar(url) })
        val cabecera = if (magnet) "Abrir enlace magnet con…"
        else (titulo ?: URLUtil.guessFileName(url, null, mime)).ifBlank { "Abrir con…" }
        dialogoApps(cabecera, filas, true)
    }

    /** Elección manual de app para un enlace o vídeo detectado (menú ⋮ y pulsación larga): sin «recordar» ni opción de navegador. */
    private fun elegirAppMedio(url: String, mime: String?, p: Pestana, ua: String?, titulo: String?) {
        val apps = AppsMedia.apps(this, url, mime)
        if (apps.isEmpty()) {
            toast("No hay ninguna aplicación para abrir esto")
            return
        }
        val abrir = { a: AppDestino ->
            if (!AppsMedia.abrir(this, a, url, mime, titulo, p.url.ifEmpty { null }, ua)) toast("No se pudo abrir con " + a.etiqueta)
        }
        if (apps.size == 1) {
            abrir(apps[0])
            return
        }
        val filas = ArrayList<FilaApp>()
        for (a in apps) filas.add(FilaApp(a.etiqueta, a.icono) { abrir(a) })
        filas.add(FilaApp("Copiar enlace", null) { copiar(url) })
        dialogoApps("Abrir con…", filas, false)
    }

    /** Diálogo con una fila por app (icono y nombre) y, opcionalmente, la casilla «Recordar mi elección». */
    private fun dialogoApps(titulo: String, filas: List<FilaApp>, conRecordar: Boolean) {
        var dialogo: AlertDialog? = null
        val casilla = CheckBox(this)
        casilla.text = "Recordar mi elección (se cambia en Ajustes)"
        casilla.textSize = 13f
        casilla.setTextColor(paleta.tenue)
        val lista = LinearLayout(this)
        lista.setOrientation(LinearLayout.VERTICAL)
        for (f in filas) {
            val fila = LinearLayout(this)
            fila.setOrientation(LinearLayout.HORIZONTAL)
            fila.setGravity(Gravity.CENTER_VERTICAL)
            fila.setPadding(20.dp(), 10.dp(), 20.dp(), 10.dp())
            if (f.icono != null) {
                val ic = ImageView(this)
                ic.setImageDrawable(f.icono)
                fila.addView(ic, LinearLayout.LayoutParams(36.dp(), 36.dp()))
            }
            val t = TextView(this)
            t.text = f.etiqueta
            t.textSize = 16f
            t.setTextColor(paleta.texto)
            t.setPadding(if (f.icono != null) 14.dp() else 0, 0, 0, 0)
            fila.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fila.isClickable = true
            ripple(fila)
            fila.setOnClickListener {
                dialogo?.dismiss()
                f.accion(conRecordar && casilla.isChecked)
            }
            lista.addView(fila, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val desplazable = android.widget.ScrollView(this)
        desplazable.addView(lista)
        val caja = LinearLayout(this)
        caja.setOrientation(LinearLayout.VERTICAL)
        caja.addView(desplazable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (conRecordar) {
            casilla.setPadding(0, 0, 0, 0)
            val marco = FrameLayout(this)
            marco.setPadding(20.dp(), 6.dp(), 20.dp(), 6.dp())
            marco.addView(casilla)
            caja.addView(marco, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val d = nuevoDialogo()
            .setTitle(titulo)
            .setView(caja)
            .setNegativeButton("Cancelar", null)
            .create()
        dialogo = d
        d.show()
    }

    // ---------------------------------------------------------------- ajustes de enlaces multimedia y magnets

    private fun descripcionModoMedia(magnet: Boolean): String {
        val modo = if (magnet) ajustes.modoMagnet else ajustes.modoMedio
        val paquete = if (magnet) ajustes.appMagnet else ajustes.appMedio
        return when (modo) {
            0 -> "preguntar cada vez"
            1 -> if (magnet) "automático (Optimo Play si está instalada)" else "en el navegador"
            else -> "siempre en " + (AppsMedia.etiquetaDe(this, paquete) ?: "una app (no instalada: se preguntará)")
        }
    }

    private fun mostrarAjustesMedia() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair("Vídeo, audio y torrents: " + descripcionModoMedia(false)) { elegirModoMedia(false) })
        items.add(Pair("Enlaces magnet: " + descripcionModoMedia(true)) { elegirModoMedia(true) })
        opciones("Enlaces multimedia, magnets y Mega", items)
    }

    private fun elegirModoMedia(magnet: Boolean) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val modo = if (magnet) ajustes.modoMagnet else ajustes.modoMedio
        fun fijar(m: Int) {
            if (magnet) ajustes.modoMagnet = m else ajustes.modoMedio = m
            mostrarAjustesMedia()
        }
        items.add(Pair((if (modo == 0) "● " else "") + "Preguntar cada vez") { fijar(0) })
        items.add(Pair(
            (if (modo == 1) "● " else "") +
                (if (magnet) "Automático (Optimo Play si está instalada; si no, el selector del sistema)"
                else "En el navegador (abrir o descargar aquí, como antes)")
        ) { fijar(1) })
        items.add(Pair((if (modo == 2) "● " else "") + "Siempre en una app…") { elegirAppFija(magnet) })
        opciones(if (magnet) "Enlaces magnet" else "Vídeo, audio y torrents", items)
    }

    private fun elegirAppFija(magnet: Boolean) {
        val apps = AppsMedia.appsGenericas(this, magnet)
        if (apps.isEmpty()) {
            toast("No hay aplicaciones compatibles instaladas")
            mostrarAjustesMedia()
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (a in apps) {
            items.add(Pair(a.etiqueta) {
                if (magnet) {
                    ajustes.modoMagnet = 2
                    ajustes.appMagnet = a.paquete
                } else {
                    ajustes.modoMedio = 2
                    ajustes.appMedio = a.paquete
                }
                mostrarAjustesMedia()
            })
        }
        opciones("Siempre abrir con…", items)
    }

    private fun descargar(url: String, ua: String?, disposicion: String?, mime: String?, privada: Boolean = false) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("Este tipo de descarga no es compatible")
            return
        }
        if (url.startsWith("http://")) {
            val h = Omnibox.hostDe(url).lowercase()
            if (!httpPermitido.contains(h) && !Omnibox.esLocal(h)) {
                toast("Descarga sin cifrar bloqueada")
                return
            }
        }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendienteDescarga = { descargar(url, ua, disposicion, mime, privada) }
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_ALMACENAMIENTO)
            return
        }
        try {
            val nombre = URLUtil.guessFileName(url, disposicion, mime)
            val pet = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrEmpty()) pet.setMimeType(mime)
            if (!ua.isNullOrEmpty()) pet.addRequestHeader("User-Agent", ua)
            // La descarga desde una pestaña privada usa las cookies del perfil privado, nunca las de las normales.
            val gestor = if (privada) gestorCookiesPrivado() else CookieManager.getInstance()
            val cookie = gestor?.getCookie(url)
            if (!cookie.isNullOrEmpty()) pet.addRequestHeader("Cookie", cookie)
            pet.setTitle(nombre)
            pet.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            pet.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(pet)
            toast("Descargando $nombre")
        } catch (e: Exception) {
            toast("No se pudo iniciar la descarga")
        }
    }

    /** Enlaces que salen del navegador. Los magnet pasan por la decisión de Ajustes (preguntar / app fija / automático). */
    private fun abrirExterno(url: String) {
        if (ReproductorExterno.esMagnet(url)) {
            val p = actual
            if (p != null) {
                gestionarMedio(url, null, p, null, null, null, null)
                return
            }
        }
        abrirExternoSistema(url)
    }

    private fun abrirExternoSistema(url: String) {
        try {
            val intent = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            val respaldo = intent.getStringExtra("browser_fallback_url")
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                if (respaldo != null && (respaldo.startsWith("https://") || respaldo.startsWith("http://"))) {
                    cargarEnActual(respaldo)
                } else {
                    toast("No hay ninguna aplicación para abrir este enlace")
                }
            }
        } catch (e: Exception) {
            toast("No se pudo abrir el enlace")
        }
    }

    private fun procesarIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_VIEW) {
            val d = i.data
            if (d != null) abrirDesdeFuera(d.toString())
        } else if (i.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (t != null) abrirDesdeFuera(t)
        }
    }

    private fun abrirDesdeFuera(texto: String) {
        val r = Omnibox.resolver(texto, Omnibox.motor(ajustes.motor))
        val url: String? = when (r) {
            is Omnibox.Resolucion.Navegar -> r.url
            is Omnibox.Resolucion.Buscar -> r.url
            else -> null
        }
        if (url == null) return
        val p = actual
        if (p != null && p.inicio && pestanas.size == 1) {
            cargarEn(p, url)
        } else {
            nuevaPestana(url)
        }
    }

    // ---------------------------------------------------------------- sesion

    private fun borrarSesionGuardada() {
        getSharedPreferences("optimo_sesion", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun guardarSesion() {
        if (!ajustes.guardarSesion) {
            borrarSesionGuardada()
            return
        }
        val arr = JSONArray()
        var activa = 0
        var n = 0
        for (p in pestanas) {
            if (p.privada || p.sinConexion) continue
            if (p === actual) activa = n
            arr.put(if (p.inicio) "" else p.url)
            n++
        }
        val cifrada = AlmacenSeguro.cifrarTexto(arr.toString(), "sesion")
        val ed = getSharedPreferences("optimo_sesion", Context.MODE_PRIVATE).edit()
        ed.remove("urls") // formato antiguo, en texto plano
        if (cifrada != null) ed.putString("urls_c", cifrada) else ed.remove("urls_c")
        ed.putInt("activa", activa)
        ed.apply()
    }

    private fun restaurarSesion() {
        var restaurada = false
        try {
            val sp = getSharedPreferences("optimo_sesion", Context.MODE_PRIVATE)
            val cifrada = sp.getString("urls_c", null)
            val texto = if (!ajustes.guardarSesion) null else if (cifrada != null) AlmacenSeguro.descifrarTexto(cifrada, "sesion") else sp.getString("urls", null)
            if (texto != null) {
                val arr = JSONArray(texto)
                val n = minOf(arr.length(), 10)
                for (i in 0 until n) {
                    val u = arr.getString(i)
                    val p = Pestana(siguienteId++, false)
                    if (u.isEmpty()) {
                        p.inicio = true
                    } else {
                        p.url = u
                        p.pendiente = u
                    }
                    pestanas.add(p)
                }
                if (pestanas.isNotEmpty()) {
                    val activa = sp.getInt("activa", 0)
                    activar(pestanas[if (activa in 0 until pestanas.size) activa else 0])
                    restaurada = true
                }
            }
        } catch (e: Exception) {
            restaurada = false
        }
        if (!restaurada) {
            pestanas.clear()
            nuevaPestana(null)
        }
    }

    // ---------------------------------------------------------------- pantalla completa

    @Suppress("DEPRECATION")
    private fun ajustarBarrasSistema(ocultar: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController
            if (c != null) {
                if (ocultar) {
                    c.hide(WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    c.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            var f = window.decorView.systemUiVisibility
            val banderas = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            f = if (ocultar) f or banderas else f and banderas.inv()
            window.decorView.systemUiVisibility = f
        }
    }

    private fun quitarPantallaCompleta() {
        val v = vistaCompleta ?: return
        pantallaCompleta.removeView(v)
        pantallaCompleta.visibility = View.GONE
        principal.visibility = View.VISIBLE
        vistaCompleta = null
        callbackCompleta?.onCustomViewHidden()
        callbackCompleta = null
        ajustarBarrasSistema(paginaCompleta)
    }

    private fun entrarPaginaCompleta() {
        if (paginaCompleta) return
        paginaCompleta = true
        ultimoAtras = 0L
        omnibox.clearFocus()
        ocultarTeclado(omnibox)
        cerrarBusqueda()
        barraSuperior.visibility = View.GONE
        barraInferior.visibility = View.GONE
        ajustarBarrasSistema(true)
        toast("Pantalla completa: pulsa atrás dos veces para salir")
    }

    private fun salirPaginaCompleta() {
        if (!paginaCompleta) return
        paginaCompleta = false
        barraSuperior.visibility = View.VISIBLE
        barraInferior.visibility = View.VISIBLE
        if (vistaCompleta == null) ajustarBarrasSistema(false)
    }

    // ---------------------------------------------------------------- clientes del WebView

    private inner class ClienteWeb(private val p: Pestana) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (view == null || request == null) return false
            val uri = request.url
            val esquema = uri.scheme?.lowercase() ?: return false
            if (esquema == "http" || esquema == "https") {
                if (p.sinConexion) {
                    // Visor sin red: un enlace tocado se abre de verdad en una pestaña normal.
                    if (request.isForMainFrame && request.hasGesture()) nuevaPestana(uri.toString())
                    return true
                }
                // Enlace tocado a un vídeo/audio/torrent: según Ajustes se abre aquí, se pregunta o va a otra app.
                if (request.isForMainFrame && request.hasGesture() && ajustes.modoMedio != 1) {
                    val u = uri.toString()
                    // Mega: se pregunta salvo que ya estés navegando por Mega (ahí los enlaces son de su propia web).
                    if (ReproductorExterno.esMedio(u, null) || (ReproductorExterno.esMega(u) && !ReproductorExterno.esHostMega(p.url))) {
                        // loadUrl no vuelve a pasar por aquí, así que «abrir en el navegador» no pregunta dos veces.
                        gestionarMedio(u, null, p, view.settings.userAgentString, null, "Abrir en el navegador") {
                            view.post { cargarUrl(p, view, u, true) }
                        }
                        return true
                    }
                }
                if (request.isForMainFrame) {
                    if (p.esEmergente && !p.libre) {
                        if (vigilarEmergente(p, uri)) return true
                    } else if (!p.esEmergente && bloquearNavegacionAutomatica(p, uri, request)) {
                        return true
                    }
                }
                val host = (uri.host ?: "").lowercase()
                if (request.isForMainFrame) prepararNavegacion(p, view, host)
                if (esquema == "http" && request.isForMainFrame &&
                    !httpPermitido.contains(host) && !Omnibox.esLocal(host)
                ) {
                    cargarUrl(p, view, uri.toString(), true)
                    return true
                }
                return false
            }
            if (esquema == "optimo") {
                if (uri.toString() == Paginas.URL_PERSONALIZAR) mostrarAjustesInicio()
                return true
            }
            if (esquema == "about" || esquema == "blob" || esquema == "data" || esquema == "javascript") return false
            if (esquema == "file" || esquema == "content") return true
            if (esquema == "magnet") {
                if (request.hasGesture()) gestionarMedio(uri.toString(), null, p, null, null, null, null)
                return true
            }
            if (request.hasGesture()) abrirExterno(uri.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request != null && request.isForMainFrame && p.esEmergente && !p.libre && vigilarEmergente(p, request.url)) {
                // Emergente de anuncios: no llega a salir la petición.
                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            if (request != null) {
                val u = request.url.toString()
                if (ReproductorExterno.esMedio(u, null) && !ReproductorExterno.esMagnet(u) && ReproductorExterno.esDetectable(u)) {
                    // Un manifiesto (m3u8/mpd) manda sobre un mp4 suelto (suelen ser anuncios o miniaturas).
                    val previo = p.medioDetectado
                    if (previo == null || !ReproductorExterno.esManifiesto(previo) || ReproductorExterno.esManifiesto(u)) {
                        p.medioDetectado = u
                    }
                }
            }
            if (request != null && ajustes.bloquearAnuncios && !request.isForMainFrame && !bloqueoPausadoEnPagina(p.url)) {
                val respuesta = anuncios.interceptar(request, hostDePagina(p.url))
                if (respuesta != null) {
                    p.bloqueados.incrementAndGet()
                    p.dev.bloqueo(request.url.toString(), request.method ?: "GET")
                    return respuesta
                }
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (p.lectura) {
                p.lectura = false
                if (pestanaVoz === p) {
                    lector.parar()
                    pestanaVoz = null
                }
            }
            if (view != null) anuncios.alEmpezar(view, url)
            if (view != null) agenteDev.alEmpezar(view)
            if (view != null) silenciador.alEmpezar(view, p.silenciada)
            p.dev.limpiar()
            if (p === actual && ::herramientas.isInitialized) herramientas.alNavegar()
            p.progreso = 0
            p.bloqueados.set(0)
            p.emergentesBloqueados.set(0)
            p.medioDetectado = null
            fijarUrl(p, url)
            if (view != null && !p.inicio) view.setBackgroundColor(paleta.fondo)
            if (p === actual) actualizarUi()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            p.progreso = 100
            if (p === actual && ::herramientas.isInitialized) herramientas.alCargada()
            p.mejoradoDesde = null
            fijarUrl(p, url)
            val titulo = view?.title ?: ""
            if (titulo.isNotEmpty() && !p.inicio) p.titulo = titulo
            if (ajustes.guardarHistorial && !p.privada && !p.inicio && url != null && titulo != Paginas.TITULO_ERROR &&
                (url.startsWith("http://") || url.startsWith("https://"))
            ) {
                historial.agregar(Entrada(if (titulo.isNotEmpty()) titulo else url, url, System.currentTimeMillis()))
            }
            if (p === actual) actualizarUi()
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            fijarUrl(p, url)
            if (p === actual) actualizarUi()
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request != null && errorResponse != null) {
                p.dev.errorHttp(request.url.toString(), request.method ?: "GET", errorResponse.statusCode, errorResponse.reasonPhrase ?: "")
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request != null && !request.isForMainFrame) {
                p.dev.errorHttp(request.url.toString(), request.method ?: "GET", 0, error?.description?.toString() ?: "Error de red")
            }
            if (view == null || request == null || !request.isForMainFrame) return
            val fallida = request.url.toString()
            val descripcion = error?.description?.toString() ?: "Error de red"
            if (descripcion.contains("ERR_ABORTED")) return
            val original = p.mejoradoDesde
            if (original != null && fallida.startsWith("https://") &&
                Omnibox.hostDe(original).equals(request.url.host ?: "", ignoreCase = true) &&
                !descripcion.contains("ERR_CERT") && !descripcion.contains("ERR_SSL")
            ) {
                // El sitio no responde por https: se ofrece http, pero solo si la persona lo confirma.
                p.mejoradoDesde = null
                preguntarHttp(p, view, original, descripcion, false)
                return
            }
            view.loadDataWithBaseURL(
                fallida,
                Paginas.error(paleta.colores, fallida, descripcion),
                "text/html",
                "utf-8",
                fallida
            )
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            if (handler == null) return
            val urlError = error?.url ?: ""
            val host = Omnibox.hostDe(urlError).lowercase()
            val esPrincipal = host.isNotEmpty() && host == Omnibox.hostDe(p.url).lowercase()
            val motivos = if (error != null) Certificados.motivos(error) else Certificados.OTRO
            val motivo = Certificados.frase(motivos)
            // Un fallo de certificado jamás debe acabar degradado a http.
            if (esPrincipal) p.mejoradoDesde = null

            // Internet: nunca se continúa con un certificado inválido (sin excepción ni botón para saltarlo).
            if (!Omnibox.esLocal(host)) {
                handler.cancel()
                if (esPrincipal && view != null) {
                    view.post {
                        view.loadDataWithBaseURL(
                            urlError,
                            Paginas.error(paleta.colores, urlError, motivo + " La conexión se ha bloqueado por seguridad."),
                            "text/html",
                            "utf-8",
                            urlError
                        )
                    }
                }
                return
            }

            // Red local (router, NAS...): solo la página principal y tras confirmar. La autorización queda atada al sitio, a la huella
            // SHA-256 del certificado exacto y a los motivos del fallo: otro certificado, o un fallo nuevo, vuelven a preguntar.
            val sitio = ExcepcionesSsl.clave(urlError)
            val huella = Certificados.huella(error?.certificate)
            if (huella != null && ExcepcionesSsl.permite(sitio, huella, motivos, p.privada)) {
                handler.proceed()
                return
            }
            if (!esPrincipal) {
                handler.cancel()
                return
            }
            if (huella == null) {
                // Sin huella no se puede atar la autorización a un certificado concreto: no se ofrece continuar.
                handler.cancel()
                if (view != null) {
                    view.post {
                        view.loadDataWithBaseURL(
                            urlError,
                            Paginas.error(paleta.colores, urlError, motivo + " No se ha podido identificar el certificado, así que no se puede autorizar."),
                            "text/html",
                            "utf-8",
                            urlError
                        )
                    }
                }
                return
            }
            val cambiado = ExcepcionesSsl.hayOtraHuella(sitio, huella, p.privada)
            val ahora = System.currentTimeMillis()
            val duracion = Certificados.duracionRecordar(motivos)
            val sb = StringBuilder()
            if (cambiado) {
                sb.append("⚠ El certificado de este equipo es distinto del que autorizaste antes. Puede ser otro dispositivo haciéndose pasar por él.\n\n")
            }
            sb.append(sitio.substringBeforeLast(':')).append("\n\n")
            sb.append(Certificados.explicacion(motivos)).append("\n\n")
            sb.append("Huella SHA-256:\n").append(huella).append("\n\n")
            sb.append("Es un equipo de tu red local. Continúa solo si reconoces esta huella.")
            val dialogo = nuevoDialogo()
                .setTitle("Certificado no válido")
                .setMessage(sb.toString())
                .setPositiveButton("Volver") { _, _ -> handler.cancel() }
                .setNegativeButton("Solo esta sesión") { _, _ ->
                    ExcepcionesSsl.anadir(ExcepcionSsl(sitio, huella, motivos, ahora, 0L, p.privada))
                    handler.proceed()
                }
                .setOnCancelListener { handler.cancel() }
            if (!p.privada) {
                dialogo.setNeutralButton("Recordar " + Certificados.etiquetaDuracion(duracion)) { _, _ ->
                    ExcepcionesSsl.anadir(ExcepcionSsl(sitio, huella, motivos, ahora, ahora + duracion, false))
                    handler.proceed()
                }
            }
            dialogo.show()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // El proceso de render murio: se destruye el WebView y se recrea al reactivar la pestaña.
            if (view == null || view !== p.web) return true
            val url = p.url
            contenedor.removeView(view)
            view.destroy()
            p.web = null
            p.cargada = false
            p.pendiente = if (url.isEmpty()) null else url
            p.inicio = url.isEmpty()
            p.progreso = 100
            Handler(Looper.getMainLooper()).post {
                if (p === actual) activar(p)
            }
            return true
        }
    }

    private inner class ClienteChrome(private val p: Pestana) : WebChromeClient() {

        override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
            if (m == null) return false
            val msg = m.message() ?: return false
            if (msg.startsWith("optimo-lectura:")) {
                alMensajeLectura(p, msg.substring(15))
                return true
            }
            // Lo que ya tiene el agente (marcado con el valor aleatorio) no se duplica.
            if (msg.startsWith(agenteDev.marca) || msg.take(40).contains(agenteDev.nonce)) return false
            val nivel = when (m.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warn"
                ConsoleMessage.MessageLevel.DEBUG -> "debug"
                ConsoleMessage.MessageLevel.TIP -> "info"
                else -> "log"
            }
            val src = m.sourceId() ?: ""
            val origen = if (src.isEmpty()) null else src + ":" + m.lineNumber()
            p.dev.consola(nivel, msg, origen)
            return false
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            p.progreso = newProgress
            if (p === actual) actualizarProgreso()
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (!title.isNullOrEmpty() && !p.inicio) p.titulo = title
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            if (resultMsg == null) return false
            if (!isUserGesture) {
                // Una página que abre pestañas sin que toques nada: siempre bloqueado.
                p.emergentesBloqueados.incrementAndGet()
                return false
            }
            val libre = ajustes.emergentes == 2 || ajustes.emergentesPermitidosEn(Omnibox.hostDe(p.url))
            var enlace: String? = null
            if (!libre) {
                val ahora = android.os.SystemClock.uptimeMillis()
                // Límite: una emergente por toque y como mucho una cada 4 s (las ráfagas de 3 o 4 pestañas son anuncios).
                if ((p.toqueMs != 0L && p.emergenteDelToque == p.toqueMs) || ahora - p.ultimoEmergenteMs < 4000L) {
                    p.emergentesBloqueados.incrementAndGet()
                    toast("Pestaña emergente bloqueada")
                    return false
                }
                if (ajustes.emergentes == 1) {
                    // Estricto: tiene que ser un enlace que has tocado de verdad.
                    val r = view?.hitTestResult
                    val tipo = r?.type
                    if (tipo != WebView.HitTestResult.SRC_ANCHOR_TYPE && tipo != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                        p.emergentesBloqueados.incrementAndGet()
                        toast("Pestaña emergente bloqueada")
                        return false
                    }
                    if (tipo == WebView.HitTestResult.SRC_ANCHOR_TYPE) enlace = Omnibox.hostDe(r?.extra ?: "").lowercase().ifEmpty { null }
                }
                p.emergenteDelToque = p.toqueMs
                p.ultimoEmergenteMs = ahora
            }
            // Si no es de confianza, la pestaña nace oculta hasta ver su primera dirección (si es de anuncios se cierra sin parpadeos).
            val nueva = crearPestana(p.privada, null, true, libre) ?: return false
            nueva.esEmergente = true
            nueva.abiertaPor = p
            nueva.libre = libre
            nueva.nacidoMs = android.os.SystemClock.uptimeMillis()
            nueva.enlaceEsperado = enlace
            if (!libre) {
                nueva.oculta = true
                manejadorPrincipal.postDelayed({ mostrarEmergente(nueva) }, 1500L)
            }
            val transporte = resultMsg.obj as WebView.WebViewTransport
            transporte.webView = webDe(nueva)
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            Handler(Looper.getMainLooper()).post {
                if (pestanas.contains(p)) cerrarPestana(p)
            }
        }

        @Suppress("DEPRECATION")
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            if (filePathCallback == null || fileChooserParams == null) return false
            selectorArchivos?.onReceiveValue(null)
            selectorArchivos = filePathCallback
            return try {
                startActivityForResult(fileChooserParams.createIntent(), REQ_ARCHIVO)
                true
            } catch (e: Exception) {
                selectorArchivos = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            val soportados = ArrayList<String>()
            val permisosAndroid = ArrayList<String>()
            val etiquetas = ArrayList<String>()
            val tipos = ArrayList<String>()
            for (r in request.resources) {
                if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                    soportados.add(r)
                    permisosAndroid.add(Manifest.permission.CAMERA)
                    etiquetas.add("la cámara")
                    tipos.add("camara")
                } else if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                    soportados.add(r)
                    permisosAndroid.add(Manifest.permission.RECORD_AUDIO)
                    etiquetas.add("el micrófono")
                    tipos.add("microfono")
                }
            }
            if (soportados.isEmpty()) {
                request.deny()
                return
            }
            val origen = request.origin.host ?: ""
            val recursos = soportados.toTypedArray()
            val conceder: () -> Unit = {
                val faltan = ArrayList<String>()
                for (perm in permisosAndroid) {
                    if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) faltan.add(perm)
                }
                if (faltan.isEmpty()) {
                    request.grant(recursos)
                } else {
                    pendientePermisosWeb = Pair(request, recursos)
                    requestPermissions(faltan.toTypedArray(), REQ_PERMISOS_WEB)
                }
            }
            // Permiso que dejaste concedido «siempre» a este sitio (nunca en pestañas privadas).
            if (!p.privada && tipos.all { t -> ajustes.permisoRecordado(origen, t) }) {
                conceder()
                return
            }
            val b = nuevoDialogo()
                .setTitle("Permiso del sitio")
                .setMessage(origen + " quiere usar " + etiquetas.joinToString(" y ") + ".")
                .setPositiveButton("Permitir") { _, _ -> conceder() }
            if (!p.privada && origen.isNotEmpty()) {
                b.setNeutralButton("Recordar…") { _, _ ->
                    elegirDuracionPermiso(origen, tipos, conceder) { request.deny() }
                }
            }
            b.setNegativeButton("Denegar") { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            if (origin == null || callback == null) return
            val sitio = Omnibox.hostDe(origin)
            val conceder: () -> Unit = {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    pendienteUbicacion = Pair(callback, origin)
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_UBICACION)
                }
            }
            if (!p.privada && ajustes.permisoRecordado(sitio, "ubicacion")) {
                conceder()
                return
            }
            val b = nuevoDialogo()
                .setTitle("Ubicación")
                .setMessage(sitio + " quiere conocer tu ubicación.")
                .setPositiveButton("Permitir") { _, _ -> conceder() }
            if (!p.privada && sitio.isNotEmpty()) {
                b.setNeutralButton("Recordar…") { _, _ ->
                    elegirDuracionPermiso(sitio, listOf("ubicacion"), conceder) { callback.invoke(origin, false, false) }
                }
            }
            b.setNegativeButton("Denegar") { _, _ -> callback.invoke(origin, false, false) }
                .setOnCancelListener { callback.invoke(origin, false, false) }
                .show()
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) return
            if (vistaCompleta != null) {
                callback?.onCustomViewHidden()
                return
            }
            vistaCompleta = view
            callbackCompleta = callback
            pantallaCompleta.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            pantallaCompleta.visibility = View.VISIBLE
            principal.visibility = View.GONE
            ajustarBarrasSistema(true)
        }

        override fun onHideCustomView() {
            quitarPantallaCompleta()
        }
    }
}

