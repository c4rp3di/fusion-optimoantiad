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
    }

    private lateinit var paleta: Paleta
    private lateinit var ajustes: Ajustes
    private lateinit var anuncios: MotorAnuncios
    private lateinit var marcadores: ListaPersistente
    private lateinit var historial: ListaPersistente
    private lateinit var uaEscritorio: String

    private val pestanas = ArrayList<Pestana>()
    private var actual: Pestana? = null
    private var siguienteId = 1

    private val httpPermitido = HashSet<String>()
    private val sslPermitido = HashSet<String>()

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
        anuncios = MotorAnuncios(this, ajustes)
        marcadores = ListaPersistente(File(filesDir, "marcadores.json"), 500)
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
        procesarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        actual?.web?.onResume()
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
        omnibox.setPadding(16.dp(), 0, 16.dp(), 0)
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
            if (!tiene) actualizarUi()
        }
        barraSup.addView(omnibox, LinearLayout.LayoutParams(0, 40.dp(), 1f))
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

    private fun nuevoDialogo(): AlertDialog.Builder {
        val tema = if (paleta.oscuro) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert
        return AlertDialog.Builder(this, tema)
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
        s.cacheMode = if (p.privada) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        if (p.escritorio) s.userAgentString = uaEscritorio
        w.setWebViewClient(ClienteWeb(p))
        w.setWebChromeClient(ClienteChrome(p))
        w.setDownloadListener { url, ua, disposicion, mime, _ ->
            descargar(url, ua, disposicion, mime)
        }
        w.setFindListener { ordinal, total, _ ->
            contadorBuscar.text = if (total == 0) "0/0" else "${ordinal + 1}/$total"
        }
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, ajustes.cookiesTerceros)
    }

    private fun crearPestana(privada: Boolean, url: String?, cargada: Boolean): Pestana? {
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
                w.loadUrl(pend)
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
            if (!quedan) actual?.web?.clearCache(true)
        }
    }

    private fun cargarEn(p: Pestana, url: String) {
        p.cargada = true
        p.pendiente = null
        p.inicio = false
        p.url = url
        p.titulo = ""
        webDe(p).loadUrl(url)
        if (p === actual) actualizarUi()
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
        } else {
            marcadores.agregar(Entrada(tituloDe(p), p.url, System.currentTimeMillis()))
            toast("Marcador guardado")
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
        add("Marcadores") { mostrarLista("Marcadores", marcadores, "Aún no tienes marcadores", false) }
        add("Historial") { mostrarLista("Historial", historial, "El historial está vacío", true) }
        if (!p.inicio && p.url.isNotEmpty()) {
            add("Compartir página") { compartir(p.url) }
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
        add("Técnicas contra anuncios…") { elegirTecnicasAnuncios() }
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
            .setTitle("Optimo 1.1.0")
            .setMessage(
                "Navegador ligero sobre el WebView del sistema (motor Chromium).\n\n" +
                    "• Bloqueador de anuncios y rastreadores por dominio (" + AdBlock.TOTAL + " dominios), con técnicas extra: vídeo de anuncio sustituido por uno vacío válido, scripts sustitutos, ocultar huecos y saltar anuncios (se eligen en Ajustes).\n" +
                    "• HTTPS primero, con respaldo a http si el sitio no lo ofrece.\n" +
                    "• Pestaña privada: no guarda historial ni caché. Por una limitación de WebView, comparte las cookies con el resto de pestañas.\n" +
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
                menuEnlace(extra, null, p.privada)
                return true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = Handler(Looper.getMainLooper()) { m ->
                    val enlace = m.data.getString("url")
                    if (enlace != null) menuEnlace(enlace, extra, p.privada)
                    true
                }.obtainMessage()
                v.requestFocusNodeHref(msg)
                return true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (extra == null) return false
                menuEnlace(null, extra, p.privada)
                return true
            }
        }
        return false
    }

    private fun menuEnlace(enlace: String?, imagen: String?, privada: Boolean) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, a: () -> Unit) {
            items.add(Pair(t, a))
        }
        if (enlace != null) {
            add("Abrir en pestaña nueva") { nuevaPestana(enlace, privada) }
            add("Copiar enlace") { copiar(enlace) }
            add("Compartir enlace") { compartir(enlace) }
        }
        if (imagen != null) {
            add("Abrir imagen en pestaña nueva") { nuevaPestana(imagen, privada) }
            add("Descargar imagen") {
                descargar(imagen, actual?.web?.settings?.userAgentString, null, null)
            }
        }
        opciones(enlace ?: imagen, items)
    }

    // ---------------------------------------------------------------- descargas, externos, intents

    private fun descargar(url: String, ua: String?, disposicion: String?, mime: String?) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("Este tipo de descarga no es compatible")
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendienteDescarga = { descargar(url, ua, disposicion, mime) }
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_ALMACENAMIENTO)
            return
        }
        try {
            val nombre = URLUtil.guessFileName(url, disposicion, mime)
            val pet = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrEmpty()) pet.setMimeType(mime)
            if (!ua.isNullOrEmpty()) pet.addRequestHeader("User-Agent", ua)
            val cookie = CookieManager.getInstance().getCookie(url)
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
        getSharedPreferences("optimo_sesion", Context.MODE_PRIVATE).edit()
            .putString("urls", arr.toString())
            .putInt("activa", activa)
            .apply()
    }

    private fun restaurarSesion() {
        var restaurada = false
        try {
            val sp = getSharedPreferences("optimo_sesion", Context.MODE_PRIVATE)
            val texto = sp.getString("urls", null)
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
        ajustarBarrasSistema(false)
    }

    // ---------------------------------------------------------------- clientes del WebView

    private inner class ClienteWeb(private val p: Pestana) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (view == null || request == null) return false
            val uri = request.url
            val esquema = uri.scheme?.lowercase() ?: return false
            if (esquema == "http" || esquema == "https") {
                val host = uri.host ?: ""
                if (esquema == "http" && request.isForMainFrame && ajustes.httpsPrimero &&
                    !httpPermitido.contains(host) && !Omnibox.esLocal(host)
                ) {
                    p.mejoradoDesde = uri.toString()
                    view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                    return true
                }
                return false
            }
            if (esquema == "about" || esquema == "blob" || esquema == "data" || esquema == "javascript") return false
            if (esquema == "file" || esquema == "content") return true
            if (request.hasGesture()) abrirExterno(uri.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
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
                Omnibox.hostDe(original) == (request.url.host ?: "")
            ) {
                p.mejoradoDesde = null
                httpPermitido.add(request.url.host ?: "")
                view.loadUrl(original)
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
            val host = Omnibox.hostDe(error?.url ?: "")
            if (sslPermitido.contains(host)) {
                handler.proceed()
                return
            }
            val motivo = when (error?.primaryError) {
                SslError.SSL_EXPIRED -> "El certificado ha caducado."
                SslError.SSL_UNTRUSTED -> "El certificado no es de una entidad de confianza."
                SslError.SSL_IDMISMATCH -> "El certificado no corresponde a este sitio."
                SslError.SSL_NOTYETVALID -> "El certificado aún no es válido."
                else -> "El certificado no es válido."
            }
            nuevoDialogo()
                .setTitle("La conexión no es privada")
                .setMessage(host + "\n\n" + motivo + "\n\nSi continúas, tus datos podrían ser interceptados.")
                .setPositiveButton("Volver") { _, _ -> handler.cancel() }
                .setNegativeButton("Continuar (no seguro)") { _, _ ->
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
