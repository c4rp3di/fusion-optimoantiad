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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
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
        private const val MAX_PESTANAS = 20

        /** Perfil de WebView (cookies, almacenamiento, caché) exclusivo de las pestañas privadas. */
        private const val PERFIL_PRIVADO = "optimo_privado"
    }

    private lateinit var paleta: Paleta
    private lateinit var ajustes: Ajustes
    private lateinit var anuncios: MotorAnuncios
    private lateinit var marcadores: Marcadores
    private lateinit var historial: ListaPersistente
    private lateinit var uaEscritorio: String

    private val pestanas = ArrayList<Pestana>()
    private var actual: Pestana? = null
    private var siguienteId = 1

    /** Hosts a los que el usuario confirmó entrar por http (solo hasta cerrar la app). */
    private val httpPermitido = HashSet<String>()

    /** Equipos de la red local con certificado no válido a los que el usuario confirmó continuar. */
    private val sslPermitido = HashSet<String>()

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
        ReglasUsuario.iniciar(this)
        ListasManager.iniciar(this, ajustes)
        marcadores = Marcadores(File(filesDir, "marcadores.json"), 500)
        historial = ListaPersistente(File(filesDir, "historial.json"), 1000)
        uaEscritorio = calcularUaEscritorio()
        construirUi()
        configurarVentana()
        restaurarSesion()
        procesarIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        salirPaginaCompleta()
        procesarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        actual?.web?.onResume()
        // Al volver de otra app el sistema puede haber mostrado sus barras.
        if (paginaCompleta && vistaCompleta == null) ajustarBarrasSistema(true)
    }

    override fun onPause() {
        super.onPause()
        guardarSesion()
        marcadores.guardarAhora()
        historial.guardarAhora()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
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
        } else if (p.url.startsWith("https://")) {
            candado.text = "🔒"
            candado.setTextColor(paleta.seguro)
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
        actualizarProgreso()
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
        val w = WebView(this)
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
        if (p.escritorio) s.userAgentString = uaEscritorio
        w.setWebViewClient(ClienteWeb(p))
        w.setWebChromeClient(ClienteChrome(p))
        w.setDownloadListener { url, ua, disposicion, mime, _ ->
            if (ReproductorExterno.esMedio(url, mime) && ReproductorExterno.instalado(this)) {
                val alternativas = ArrayList<Pair<String, () -> Unit>>()
                alternativas.add(Pair("Reproducir en Optimo Play") {
                    ReproductorExterno.enviar(this, url, null, p.url, ua)
                    Unit
                })
                alternativas.add(Pair("Descargar") {
                    descargar(url, ua, disposicion, mime, p.privada)
                })
                opciones(URLUtil.guessFileName(url, disposicion, mime), alternativas)
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
        p.web = w
        return w
    }

    @Suppress("DEPRECATION")
    private fun aplicarAjustesWeb(w: WebView) {
        val oscuro = ajustes.oscurecerPaginas
        if (Build.VERSION.SDK_INT >= 33) {
            w.settings.isAlgorithmicDarkeningAllowed = oscuro
        } else if (Build.VERSION.SDK_INT >= 29) {
            w.settings.forceDark = if (oscuro) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
        gestorCookies(w).setAcceptThirdPartyCookies(w, ajustes.cookiesTerceros)
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
        if (!perfilesOk) return
        try {
            ProfileStore.getInstance().deleteProfile(PERFIL_PRIVADO)
        } catch (e: Throwable) {
            // En uso o inexistente: se reintenta al cerrar la última pestaña privada o al arrancar.
        }
    }

    private fun crearPestana(privada: Boolean, url: String?, cargada: Boolean): Pestana? {
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
        activar(p)
        return p
    }

    private fun nuevaPestana(url: String?, privada: Boolean = false): Pestana? =
        crearPestana(privada, url, false)

    private fun activar(p: Pestana) {
        val anterior = actual
        if (anterior != null && anterior !== p) {
            val wa = anterior.web
            if (wa != null) {
                contenedor.removeView(wa)
                wa.onPause()
            }
        }
        actual = p
        val w = webDe(p)
        if (w.parent == null) {
            contenedor.addView(
                w,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
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
    }

    private fun cerrarPestana(p: Pestana) {
        val idx = pestanas.indexOf(p)
        if (idx < 0) return
        pestanas.removeAt(idx)
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
        webDe(p).loadDataWithBaseURL("optimo://inicio", paginaInicio(), "text/html", "utf-8", "optimo://inicio")
        if (p === actual) actualizarUi()
    }

    private fun paginaInicio(): String {
        val atajos = ArrayList<Pair<String, String>>()
        for (e in marcadores.todos()) {
            atajos.add(Pair(e.titulo, e.url))
            if (atajos.size >= 8) break
        }
        if (atajos.isEmpty()) atajos.addAll(Paginas.ATAJOS_POR_DEFECTO)
        return Paginas.inicio(paleta.colores, Omnibox.motor(ajustes.motor), atajos)
    }

    private fun fijarUrl(p: Pestana, url: String?) {
        if (url == null) return
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
        if (medio != null && ReproductorExterno.instalado(this)) {
            add("▶ Reproducir vídeo de la página (Optimo Play)") {
                ReproductorExterno.enviar(this, medio, p.titulo, p.url, p.web?.settings?.userAgentString)
            }
        }
        add("Marcadores") { mostrarMarcadores() }
        add("Historial") { mostrarLista("Historial", historial, "El historial está vacío", true) }
        if (!p.inicio && p.url.isNotEmpty()) {
            add("Compartir página") { compartir(p.url) }
            add("Bloquear un anuncio (tocar)") { iniciarSeleccion(p) }
        }
        add("Versión de escritorio: " + si(p.escritorio)) { alternarEscritorio() }
        add("Ir a la página de inicio") { mostrarInicio(p) }
        add("Descargas") { abrirDescargas() }
        add("Ajustes") { mostrarAjustes() }
        add("Cerrar todas las pestañas") { cerrarTodas() }
        val bloq = p.bloqueados.get()
        val titulo = if (ajustes.bloquearAnuncios) "Bloqueados en esta página: $bloq" else null
        opciones(titulo, items)
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
        add("Listas de bloqueo…") { mostrarListas() }
        add("Técnicas contra anuncios…") { elegirTecnicasAnuncios() }
        add("Reglas de anuncios añadidas (" + ReglasUsuario.total + ")…") { mostrarReglasUsuario() }
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
        add("Permitir cookies de terceros: " + si(ajustes.cookiesTerceros)) {
            ajustes.cookiesTerceros = !ajustes.cookiesTerceros
            for (q in pestanas) {
                val w = q.web
                if (w != null) aplicarAjustesWeb(w)
            }
            mostrarAjustes()
        }
        add("Borrar historial, caché y cookies") { confirmarBorrado() }
        add("Acerca de Optimo") { acercaDe() }
        opciones("Ajustes", items)
    }

    private fun mostrarListas() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
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

    private fun borrarDatos() {
        historial.vaciar()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        actual?.web?.clearCache(true)
        toast("Datos de navegación borrados")
    }

    private fun acercaDe() {
        nuevoDialogo()
            .setTitle("Optimo 1.5.0")
            .setMessage(
                "Navegador ligero sobre el WebView del sistema (motor Chromium).\n\n" +
                    "• Bloqueador de anuncios y rastreadores por dominio (" + (AdBlock.TOTAL + ListasManager.filtro.totalDominios) + " dominios, con las listas descargadas), con técnicas extra: vídeo de anuncio sustituido por uno vacío válido, scripts sustitutos, ocultar huecos y saltar anuncios (se eligen en Ajustes). Mantén pulsado un anuncio para ocultarlo o bloquear su dominio.\n" +
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
            b.setNeutralButton("Borrar todo") { _, _ ->
                lista.vaciar()
                toast("Historial borrado")
            }
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

    private fun mostrarPestanas() {
        val lista = ListView(this)
        var dialogo: AlertDialog? = null
        val adaptador = object : BaseAdapter() {
            override fun getCount(): Int = pestanas.size
            override fun getItem(position: Int): Any = pestanas[position]
            override fun getItemId(position: Int): Long = pestanas[position].id.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val p = pestanas[position]
                val fila = LinearLayout(this@MainActivity)
                fila.setOrientation(LinearLayout.HORIZONTAL)
                fila.setGravity(Gravity.CENTER_VERTICAL)
                fila.setPadding(16.dp(), 8.dp(), 4.dp(), 8.dp())
                val textos = LinearLayout(this@MainActivity)
                textos.setOrientation(LinearLayout.VERTICAL)
                val t1 = TextView(this@MainActivity)
                t1.text = (if (p === actual) "● " else "") + (if (p.privada) "🕶 " else "") + tituloDe(p)
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
                val cerrar = boton("✕", 18f) {
                    cerrarPestana(p)
                    notifyDataSetChanged()
                }
                fila.addView(cerrar, LinearLayout.LayoutParams(48.dp(), 48.dp()))
                return fila
            }
        }
        lista.setAdapter(adaptador)
        lista.setOnItemClickListener { _, _, pos, _ ->
            dialogo?.dismiss()
            activar(pestanas[pos])
        }
        val d = nuevoDialogo()
            .setTitle("Pestañas")
            .setView(lista)
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
            if (ReproductorExterno.esMedio(enlace, null) && ReproductorExterno.instalado(this)) {
                add("Reproducir en Optimo Play") {
                    ReproductorExterno.enviar(this, enlace, null, p.url, web.settings.userAgentString)
                }
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
        }
        if (!p.eligiendo) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_UP -> {
                p.eligiendo = false
                bloquearAnuncio(v, p, null, ev.x, ev.y)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val holgura = android.view.ViewConfiguration.get(this).scaledTouchSlop
                if (Math.abs(ev.x - p.toqueX) > holgura || Math.abs(ev.y - p.toqueY) > holgura) {
                    p.eligiendo = false
                    toast("Selección cancelada")
                    return false
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                p.eligiendo = false
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

    private fun mostrarReglasUsuario() {
        val ocultar = ReglasUsuario.listaOcultar()
        val dominios = ReglasUsuario.listaDominios()
        if (ocultar.isEmpty() && dominios.isEmpty()) {
            toast("Aún no has añadido reglas")
            return
        }
        val etiquetas = ArrayList<String>()
        for (r in ocultar) etiquetas.add("Ocultar en " + r.host + ": " + r.sel)
        for (dd in dominios) etiquetas.add("Bloquear dominio: $dd")
        val marcadas = BooleanArray(etiquetas.size)
        nuevoDialogo()
            .setTitle("Reglas añadidas (marca para borrar)")
            .setMultiChoiceItems(etiquetas.toTypedArray(), marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton("Borrar marcadas") { _, _ ->
                var borradas = 0
                for (i in ocultar.indices) if (marcadas[i]) { ReglasUsuario.quitarOcultar(ocultar[i]); borradas++ }
                for (j in dominios.indices) if (marcadas[ocultar.size + j]) { ReglasUsuario.quitarDominio(dominios[j]); borradas++ }
                if (borradas > 0) {
                    reinstalarAnuncios()
                    toast("$borradas regla(s) borradas. Recarga las páginas abiertas")
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ---------------------------------------------------------------- descargas, externos, intents

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

    private fun abrirExterno(url: String) {
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

    private fun guardarSesion() {
        val arr = JSONArray()
        var activa = 0
        var n = 0
        for (p in pestanas) {
            if (p.privada) continue
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
            val texto = if (cifrada != null) AlmacenSeguro.descifrarTexto(cifrada, "sesion") else sp.getString("urls", null)
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
                val host = (uri.host ?: "").lowercase()
                if (esquema == "http" && request.isForMainFrame &&
                    !httpPermitido.contains(host) && !Omnibox.esLocal(host)
                ) {
                    cargarUrl(p, view, uri.toString(), true)
                    return true
                }
                return false
            }
            if (esquema == "about" || esquema == "blob" || esquema == "data" || esquema == "javascript") return false
            if (esquema == "file" || esquema == "content") return true
            if (esquema == "magnet" && request.hasGesture() &&
                ReproductorExterno.enviar(this@MainActivity, uri.toString(), null, null, null)
            ) return true
            if (request.hasGesture()) abrirExterno(uri.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request != null) {
                val u = request.url.toString()
                if (ReproductorExterno.esMedio(u, null) && !ReproductorExterno.esMagnet(u)) {
                    // Un manifiesto (m3u8/mpd) manda sobre un mp4 suelto (suelen ser anuncios o miniaturas).
                    val previo = p.medioDetectado
                    if (previo == null || !ReproductorExterno.esManifiesto(previo) || ReproductorExterno.esManifiesto(u)) {
                        p.medioDetectado = u
                    }
                }
            }
            if (request != null && ajustes.bloquearAnuncios && !request.isForMainFrame) {
                val respuesta = anuncios.interceptar(request)
                if (respuesta != null) {
                    p.bloqueados.incrementAndGet()
                    return respuesta
                }
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (view != null) anuncios.alEmpezar(view, url)
            p.progreso = 0
            p.bloqueados.set(0)
            p.medioDetectado = null
            fijarUrl(p, url)
            if (p === actual) actualizarUi()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            p.progreso = 100
            p.mejoradoDesde = null
            fijarUrl(p, url)
            val titulo = view?.title ?: ""
            if (titulo.isNotEmpty() && !p.inicio) p.titulo = titulo
            if (!p.privada && !p.inicio && url != null && titulo != Paginas.TITULO_ERROR &&
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

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
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
            val motivo = when (error?.primaryError) {
                SslError.SSL_EXPIRED -> "El certificado ha caducado."
                SslError.SSL_UNTRUSTED -> "El certificado no es de una entidad de confianza."
                SslError.SSL_IDMISMATCH -> "El certificado no corresponde a este sitio."
                SslError.SSL_NOTYETVALID -> "El certificado aún no es válido."
                else -> "El certificado no es válido."
            }
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

            // Red local (router, NAS...): certificados autofirmados habituales; solo la página principal y tras confirmar.
            if (sslPermitido.contains(host)) {
                handler.proceed()
                return
            }
            if (!esPrincipal) {
                handler.cancel()
                return
            }
            nuevoDialogo()
                .setTitle("Certificado no válido")
                .setMessage(host + "\n\n" + motivo + "\n\nEs un equipo de tu red local. Continúa solo si lo reconoces.")
                .setPositiveButton("Volver") { _, _ -> handler.cancel() }
                .setNegativeButton("Continuar (equipo local)") { _, _ ->
                    sslPermitido.add(host)
                    handler.proceed()
                }
                .setOnCancelListener { handler.cancel() }
                .show()
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
            if (!isUserGesture || resultMsg == null) return false
            val nueva = crearPestana(p.privada, null, true) ?: return false
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
            for (r in request.resources) {
                if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                    soportados.add(r)
                    permisosAndroid.add(Manifest.permission.CAMERA)
                    etiquetas.add("la cámara")
                } else if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                    soportados.add(r)
                    permisosAndroid.add(Manifest.permission.RECORD_AUDIO)
                    etiquetas.add("el micrófono")
                }
            }
            if (soportados.isEmpty()) {
                request.deny()
                return
            }
            val origen = request.origin.host ?: ""
            val recursos = soportados.toTypedArray()
            nuevoDialogo()
                .setTitle("Permiso del sitio")
                .setMessage(origen + " quiere usar " + etiquetas.joinToString(" y ") + ".")
                .setPositiveButton("Permitir") { _, _ ->
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
                .setNegativeButton("Denegar") { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            if (origin == null || callback == null) return
            nuevoDialogo()
                .setTitle("Ubicación")
                .setMessage(Omnibox.hostDe(origin) + " quiere conocer tu ubicación.")
                .setPositiveButton("Permitir") { _, _ ->
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        callback.invoke(origin, true, false)
                    } else {
                        pendienteUbicacion = Pair(callback, origin)
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_UBICACION)
                    }
                }
                .setNegativeButton("Denegar") { _, _ -> callback.invoke(origin, false, false) }
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

