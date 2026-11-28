package com.optimo.navegador

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Lo que el panel de Aspecto necesita de MainActivity. */
interface AnfitrionAspecto {
    fun pestana(): Pestana?
    fun ocultarElemento()
    fun quitarCapa()
    fun reglasDe(sitio: String)
    fun aplicarTexto()
    fun aplicarVisibilidad()
    fun aplicarOscurecer()
    fun aplicarBarra()
    fun nombreAhorro(): String
    fun ciclarAhorro()
    fun refrescarInicio()
    fun coloresInicio(): Colores
    fun normalizarUrl(texto: String): String?
    fun avisar(texto: String)
    fun volverAlMenu()
}

/**
 * Vista previa de la pantalla de inicio con los colores y opciones actuales (título, buscador, accesos, escudo…).
 * Se vuelve a crear en cada repintado, así que siempre muestra el estado real.
 */
class VistaPrevioInicio(
    contexto: Context,
    private val col: Colores,
    private val titulo: String,
    private val subtitulo: String,
    private val buscador: Boolean,
    private val accesos: Int,
    private val cuadrados: Boolean,
    private val escudo: Boolean,
    private val enlace: Boolean
) : View(contexto) {
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun color(hex: String, defecto: Int): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        defecto
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val fondo = color(col.fondo, Color.DKGRAY)
        val texto = color(col.texto, Color.WHITE)
        val sup = color(col.superficie, Color.GRAY)
        val acento = color(col.acento, Color.CYAN)
        val tenue = color(col.tenue, Color.LTGRAY)
        pincel.style = Paint.Style.FILL
        pincel.color = fondo
        val r = h * 0.07f
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, pincel)
        var y = h * 0.09f
        pincel.textAlign = Paint.Align.CENTER
        if (titulo.isNotBlank()) {
            pincel.color = acento
            pincel.textSize = h * 0.10f
            pincel.isFakeBoldText = true
            y += pincel.textSize
            canvas.drawText(titulo.take(24), w / 2f, y, pincel)
            y += h * 0.03f
        }
        pincel.isFakeBoldText = false
        if (subtitulo.isNotBlank()) {
            pincel.color = tenue
            pincel.textSize = h * 0.058f
            y += pincel.textSize
            canvas.drawText(subtitulo.take(34), w / 2f, y, pincel)
            y += h * 0.03f
        }
        if (titulo.isBlank() && subtitulo.isBlank()) y += h * 0.05f
        if (buscador) {
            pincel.color = sup
            val bh = h * 0.12f
            canvas.drawRoundRect(RectF(w * 0.14f, y, w * 0.86f, y + bh), bh / 2f, bh / 2f, pincel)
            pincel.color = tenue
            pincel.textSize = h * 0.06f
            canvas.drawText("Buscar o escribir dirección", w / 2f, y + bh * 0.64f, pincel)
            y += bh + h * 0.05f
        }
        if (accesos > 0) {
            val n = minOf(accesos, 8)
            val celda = h * 0.15f
            val hueco = w * 0.06f
            val columnas = 4
            val anchoTotal = columnas * celda + (columnas - 1) * hueco
            val x0 = (w - anchoTotal) / 2f
            for (i in 0 until n) {
                val fila = i / columnas
                val c = i % columnas
                val x = x0 + c * (celda + hueco)
                val yy = y + fila * (celda + h * 0.045f)
                pincel.color = sup
                if (cuadrados) {
                    canvas.drawRoundRect(RectF(x, yy, x + celda, yy + celda), celda * 0.22f, celda * 0.22f, pincel)
                } else {
                    canvas.drawCircle(x + celda / 2f, yy + celda / 2f, celda / 2f, pincel)
                }
                pincel.color = texto
                pincel.alpha = 150
                canvas.drawCircle(x + celda / 2f, yy + celda / 2f, celda * 0.13f, pincel)
                pincel.alpha = 255
            }
        }
        if (escudo) {
            val cx = w / 2f
            val cy = h - h * 0.11f
            pincel.color = sup
            canvas.drawCircle(cx, cy, h * 0.065f, pincel)
            pincel.color = acento
            pincel.style = Paint.Style.STROKE
            pincel.strokeWidth = h * 0.012f
            canvas.drawCircle(cx, cy, h * 0.03f, pincel)
            pincel.style = Paint.Style.FILL
        }
        if (enlace) {
            pincel.color = tenue
            pincel.textSize = h * 0.045f
            pincel.textAlign = Paint.Align.RIGHT
            canvas.drawText("Personalizar", w - w * 0.04f, h - h * 0.035f, pincel)
        }
    }
}

/**
 * Aspecto: lo que más se usa para adaptar las páginas y Optimo a ti. Arriba, ocultar un elemento tocándolo; después
 * texto y zoom (por sitio o general), visibilidad, accesos rápidos del menú y, dentro del mismo panel, la barra de
 * direcciones y la pantalla de inicio.
 */
class PanelAspecto(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionAspecto,
    inicial: String = RAIZ
) {
    companion object {
        const val RAIZ = "aspecto"
        const val BARRA = "barra"
        const val INICIO = "inicio"
        const val TEMAS = "temas"
        const val COLORES = "colores"
        const val ACCESOS_INICIO = "accesos_inicio"
        const val ACCESOS_MENU = "accesos_menu"
        const val GESTOS = "gestos"
        const val ANADIR = "anadir_boton"
        const val MOVER = "mover_boton"
        private val NOMBRES_OSCURO = listOf("auto", "siempre", "nunca")
        private val NOMBRES_ORIGEN = listOf("marcadores recientes", "lista propia", "predeterminados")
    }

    private val privada = g.pestana()?.privada == true
    private val hoja = Hoja(act, paleta, privada, true)
    private val ui = Handler(Looper.getMainLooper())
    private var pagina = inicial

    /** 0 = el tamaño de texto se aplica solo a este sitio, 1 = a todos. */
    private var ambito = 1

    fun mostrar() {
        val p = g.pestana()
        if (p != null && esWeb(p) && ReglasUsuario.clave(Omnibox.hostDe(p.url)).isNotEmpty()) ambito = 0
        pintar()
        hoja.interceptarAtras {
            atras()
            true
        }
        hoja.mostrar()
    }

    private fun esWeb(p: Pestana): Boolean = !p.inicio && !p.appWeb && p.url.startsWith("http")

    private fun repintar() {
        ui.post { pintar() }
    }

    private fun ir(p: String) {
        pagina = p
        pintar()
    }

    private fun atras() {
        when (pagina) {
            BARRA, INICIO, ACCESOS_MENU, GESTOS -> ir(RAIZ)
            ANADIR, MOVER -> ir(BARRA)
            TEMAS, COLORES, ACCESOS_INICIO -> ir(INICIO)
            else -> {
                hoja.cerrar()
                g.volverAlMenu()
            }
        }
    }

    private fun titulo(t: String) {
        hoja.barraTitulo(t, { atras() }, { hoja.cerrar() })
    }

    private fun confirmar(titulo: String, mensaje: String, boton: String, accion: () -> Unit) {
        Dialogos.confirmar(act, paleta, privada, titulo, mensaje, boton, true, accion)
    }

    private fun pintar() {
        hoja.limpiar()
        when (pagina) {
            BARRA -> pintarBarra()
            INICIO -> pintarInicio()
            TEMAS -> pintarTemas()
            COLORES -> pintarColores()
            ACCESOS_INICIO -> pintarAccesosInicio()
            ACCESOS_MENU -> pintarAccesosMenu()
            GESTOS -> pintarGestos()
            ANADIR -> pintarAnadir()
            MOVER -> pintarMover()
            else -> pintarRaiz()
        }
    }

    // ---------------------------------------------------------------- raíz

    private fun pintarRaiz() {
        titulo("Aspecto")
        val p = g.pestana()
        val conPagina = p != null && esWeb(p)
        val host = if (p != null && conPagina) ReglasUsuario.clave(Omnibox.hostDe(p.url)) else ""

        hoja.botonGrande(
            "👆", "Ocultar un elemento",
            if (conPagina) "Toca algo de la página y desaparece" + (if (host.isNotEmpty()) " (en " + host + ")" else "")
            else "Abre una página para poder elegir un elemento",
            conPagina
        ) {
            if (conPagina) {
                hoja.cerrar()
                g.ocultarElemento()
            } else {
                g.avisar("Abre una página primero")
            }
        }
        if (conPagina && host.isNotEmpty()) {
            val oscuro = ajustes.modoOscuroEn(host)
            val ocultos = ReglasUsuario.selectoresPara(host).size
            hoja.accesos(
                listOf(
                    Triple("🧹", "Quitar capa", {
                        hoja.cerrar()
                        g.quitarCapa()
                    }),
                    Triple("🌙", "Oscuro: " + NOMBRES_OSCURO[oscuro], {
                        ajustes.fijarModoOscuroEn(host, (oscuro + 1) % 3)
                        g.aplicarOscurecer()
                        g.avisar("Modo oscuro en " + host + ": " + NOMBRES_OSCURO[(oscuro + 1) % 3] + ". Se aplica al recargar")
                        repintar()
                    }),
                    Triple("🙈", "Ocultos ($ocultos)", {
                        hoja.cerrar()
                        g.reglasDe(host)
                    })
                )
            )
        }

        // ---- texto y zoom
        hoja.seccion("Texto y zoom")
        val sitioDisponible = host.isNotEmpty()
        if (!sitioDisponible) ambito = 1
        val valor = if (ambito == 0 && sitioDisponible) ajustes.textoZoomEfectivo(host) else ajustes.textoZoom
        val cab = LinearLayout(act)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.setPadding(dpi(4), dpi(6), dpi(4), 0)
        val etiqueta = TextView(act)
        etiqueta.text = "Tamaño del texto de las páginas"
        etiqueta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        etiqueta.setTextColor(paleta.texto)
        cab.addView(etiqueta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val numero = TextView(act)
        numero.text = valor.toString() + " %"
        numero.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        numero.setTextColor(paleta.acento)
        numero.typeface = android.graphics.Typeface.DEFAULT_BOLD
        cab.addView(numero)
        hoja.agregar(cab)
        val vista = TextView(act)
        vista.text = "Así se verá el texto de la página."
        vista.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * valor / 100f)
        vista.setTextColor(paleta.texto)
        vista.setPadding(dpi(4), dpi(4), dpi(4), dpi(2))
        hoja.deslizador(50, 200, 10, valor, { v ->
            numero.text = v.toString() + " %"
            vista.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * v / 100f)
        }, { v ->
            if (ambito == 0 && sitioDisponible) ajustes.fijarTextoZoomEn(host, v) else ajustes.textoZoom = v
            g.aplicarTexto()
        })
        hoja.agregar(vista)
        hoja.chips(listOf("Solo este sitio", "Todos los sitios", "Restablecer"), ambito) { i ->
            when (i) {
                0 -> if (sitioDisponible) ambito = 0 else g.avisar("Abre una página para ajustarlo solo en ese sitio")
                1 -> ambito = 1
                else -> {
                    if (ambito == 0 && sitioDisponible) ajustes.fijarTextoZoomEn(host, 0) else ajustes.textoZoom = 100
                    g.aplicarTexto()
                }
            }
            repintar()
        }
        val propios = ajustes.sitiosConAjustes().size
        hoja.nota(
            "El tamaño de «Solo este sitio» gana al general. Se aplica al momento en las páginas abiertas." +
                (if (propios > 0) " Sitios con ajustes propios: " + propios + "." else "")
        )
        hoja.interruptor("Zoom con los dedos en todas las páginas", "Aunque la página lo prohíba", ajustes.forzarZoom) { v ->
            ajustes.forzarZoom = v
            g.aplicarVisibilidad()
            repintar()
        }

        // ---- visibilidad
        hoja.seccion("Visibilidad")
        hoja.interruptor("Oscurecer páginas web", "Modo oscuro para las páginas que no lo tienen", ajustes.oscurecerPaginas) { v ->
            ajustes.oscurecerPaginas = v
            g.aplicarOscurecer()
            repintar()
        }
        hoja.interruptor("Alto contraste", "Más diferencia entre texto y fondo", ajustes.altoContraste) { v ->
            ajustes.altoContraste = v
            g.aplicarVisibilidad()
            repintar()
        }
        hoja.interruptor("Reducir animaciones", "Quita movimientos y transiciones de las páginas", ajustes.reducirAnimaciones) { v ->
            ajustes.reducirAnimaciones = v
            g.aplicarVisibilidad()
            repintar()
        }
        hoja.fila("Ahorro de datos (sin imágenes)", "Se aplica a las páginas que cargues desde ahora", g.nombreAhorro()) {
            g.ciclarAhorro()
            repintar()
        }

        // ---- menú e interfaz
        hoja.seccion("Menú")
        hoja.fila("Accesos rápidos del menú", "Qué botones salen arriba y en qué orden", AccesosMenu.lista(ajustes).size.toString()) { ir(ACCESOS_MENU) }
        hoja.seccion("Interfaz de Optimo")
        hoja.fila("Barras superior e inferior", "Botones, filas, tamaños y separación de cada una") { ir(BARRA) }
        hoja.fila(
            "Gestos de arrastre", "Cerrar paneles y plegar barras y hojas: activa o apaga cada uno",
            Gestos.cuantosActivos().toString() + "/" + Gestos.TODOS.size
        ) { ir(GESTOS) }
        hoja.fila("Pantalla de inicio", "Colores, accesos, buscador y título") { ir(INICIO) }
    }

    private fun dpi(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- accesos rápidos del menú

    private fun pintarAccesosMenu() {
        titulo("Accesos rápidos del menú")
        hoja.nota("Elige hasta " + AccesosMenu.MAX + " botones y su orden. Salen arriba en el menú.")
        val sel = ArrayList(AccesosMenu.lista(ajustes).map { it.accion })
        val resto = AccesosMenu.POOL.map { it.accion }.filter { !sel.contains(it) }
        val todos = sel + resto
        for (a in todos) {
            val def = AccesosMenu.POOL.first { it.accion == a }
            val activo = sel.contains(a)
            val pos = sel.indexOf(a)
            hoja.filaOrden(
                def.icono + "  " + def.nombre, activo,
                activo && pos > 0, activo && pos < sel.size - 1,
                {
                    sel.removeAt(pos)
                    sel.add(pos - 1, a)
                    AccesosMenu.guardar(ajustes, sel)
                    repintar()
                },
                {
                    sel.removeAt(pos)
                    sel.add(pos + 1, a)
                    AccesosMenu.guardar(ajustes, sel)
                    repintar()
                },
                { v ->
                    if (v) {
                        if (sel.size >= AccesosMenu.MAX) {
                            g.avisar("Máximo " + AccesosMenu.MAX + " botones")
                        } else {
                            sel.add(a)
                            AccesosMenu.guardar(ajustes, sel)
                        }
                    } else {
                        sel.remove(a)
                        AccesosMenu.guardar(ajustes, sel)
                    }
                    repintar()
                }
            )
        }
        hoja.boton("Restablecer los accesos", true) {
            ajustes.accesosMenu = ""
            repintar()
        }
    }

    // ---------------------------------------------------------------- gestos de arrastre

    private fun interruptorGesto(id: String, titulo: String, detalle: String) {
        hoja.interruptor(titulo, detalle, Gestos.activo(id)) { v ->
            Gestos.fijar(id, v)
            repintar()
        }
    }

    private fun pintarGestos() {
        titulo("Gestos de arrastre")
        hoja.nota("Cada gesto se activa o se apaga por separado. Las tiras que ya estén plegadas se pueden desplegar siempre tocándolas.")
        hoja.seccion("Paneles")
        interruptorGesto(Gestos.PANELES, "Cerrar paneles arrastrando", "Menú, ajustes, escudo… se cierran arrastrándolos hacia la derecha")
        hoja.seccion("Barras")
        interruptorGesto(Gestos.BARRA_SUP, "Plegar la barra superior", "Arrastrarla hacia arriba la deja en una tira")
        interruptorGesto(Gestos.BARRA_INF, "Plegar la barra inferior", "Arrastrarla hacia abajo la deja en una tira")
        hoja.seccion("Hojas de arriba y abajo")
        interruptorGesto(
            Gestos.HOJA_PLEGAR, "Plegar hojas arrastrándolas",
            "Las que dejan ver la página (ocultar elemento, capas, elegir elemento) pasan a una tira"
        )
        interruptorGesto(Gestos.TIRADOR, "Plegar tocando el tirador", "En esas mismas hojas; tocar la tira las despliega")
        interruptorGesto(Gestos.HOJA_CERRAR, "Cerrar hojas arrastrándolas", "Confirmaciones, listas de opciones y acciones, hacia abajo")
        hoja.boton("Activar todos los gestos") {
            Gestos.activarTodos()
            repintar()
        }
    }

    // ---------------------------------------------------------------- barra de direcciones

    /** Barra que se está editando (la pantalla «Barras» muestra una a la vez) y botón que se está moviendo. */
    private var barraSel = BarraConfig.SUP
    private var moviendo = ""

    private fun cambioBarra() {
        g.aplicarBarra()
        repintar()
    }

    private fun nombreBarra(b: Barra): String = if (b.arriba) "Barra superior" else "Barra inferior"

    private fun nombreBoton(id: String): String {
        if (id == BarraConfig.OMNIBOX) return "⌨  Campo de dirección"
        val d = BarraConfig.definicion(id)
        return (d?.icono ?: "") + "  " + (d?.nombre ?: id)
    }

    private fun pintarBarra() {
        titulo("Barras de Optimo")
        hoja.chips(listOf("Barra superior", "Barra inferior"), if (barraSel == BarraConfig.SUP) 0 else 1) { i ->
            barraSel = if (i == 0) BarraConfig.SUP else BarraConfig.INF
            repintar()
        }
        val b = BarraConfig.barra(barraSel)
        hoja.seccion("Vista previa")
        hoja.agregar(vistaPreviaBarra(b))
        if (b.filas.isEmpty()) {
            hoja.nota("Esta barra no tiene botones, así que no se ve. Añade alguno.")
        } else {
            hoja.nota(
                "Cada fila va de izquierda a derecha. Un botón con ancho «reparto» ocupa lo que sobra; si hay varios, lo comparten. " +
                    "Hasta " + BarraConfig.MAX_FILAS + " filas; la barra entera se pliega arrastrándola."
            )
        }
        for ((fi, fila) in b.filas.withIndex()) {
            hoja.seccion("Fila " + (fi + 1))
            for ((i, e) in fila.withIndex()) {
                hoja.filaOrden(
                    nombreBoton(e.id), null, i > 0, i < fila.size - 1,
                    {
                        BarraConfig.mover(e.id, -1)
                        cambioBarra()
                    },
                    {
                        BarraConfig.mover(e.id, 1)
                        cambioBarra()
                    },
                    { }
                )
                if (e.id != BarraConfig.OMNIBOX) {
                    hoja.paso(
                        "      Ancho", if (e.tamano == 0) "reparto" else e.tamano.toString() + " dp", e.tamano > 0, e.tamano < BarraConfig.TAM_MAX,
                        {
                            BarraConfig.tamano(e.id, if (e.tamano <= BarraConfig.TAM_MIN) 0 else e.tamano - 2)
                            cambioBarra()
                        },
                        {
                            BarraConfig.tamano(e.id, if (e.tamano == 0) BarraConfig.TAM_MIN else e.tamano + 2)
                            cambioBarra()
                        }
                    )
                }
                hoja.fila("      Mover" + (if (BarraConfig.puedeQuitarse(e.id)) " o quitar" else ""), null) {
                    moviendo = e.id
                    ir(MOVER)
                }
            }
        }
        hoja.boton("＋ Añadir un botón") { ir(ANADIR) }
        hoja.seccion("Esta barra")
        hoja.paso(
            "Separación", b.separacion.toString() + " dp", b.separacion > 0, b.separacion < BarraConfig.SEP_MAX,
            {
                BarraConfig.fijarSeparacion(b, b.separacion - 2)
                cambioBarra()
            },
            {
                BarraConfig.fijarSeparacion(b, b.separacion + 2)
                cambioBarra()
            }
        )
        hoja.paso(
            "Alto de cada fila", b.alto.toString() + " dp", b.alto > BarraConfig.ALTO_MIN, b.alto < BarraConfig.ALTO_MAX,
            {
                BarraConfig.fijarAlto(b, b.alto - 2)
                cambioBarra()
            },
            {
                BarraConfig.fijarAlto(b, b.alto + 2)
                cambioBarra()
            }
        )
        hoja.seccion("Las dos barras")
        hoja.interruptor("Contador de bloqueos sobre el escudo", null, BarraConfig.contador) { v ->
            BarraConfig.fijarContador(v)
            cambioBarra()
        }
        hoja.boton("Restablecer las dos barras", true) {
            BarraConfig.restablecer()
            cambioBarra()
        }
    }

    /** Catálogo de botones que no están en la barra que se edita; si uno ya está en la otra, se mueve aquí. */
    private fun pintarAnadir() {
        val b = BarraConfig.barra(barraSel)
        titulo("Añadir a la " + nombreBarra(b).lowercase())
        hoja.nota("Elige un botón: se pone al final de la última fila. Los que están en la otra barra pasan a esta. Luego puedes moverlo o cambiarle el ancho.")
        for (grupo in BarraConfig.GRUPOS) {
            val lista = BarraConfig.ELEMENTOS.filter { d -> d.grupo == grupo && BarraConfig.donde(d.id)?.first !== b }
            if (lista.isEmpty()) continue
            hoja.seccion(grupo)
            for (d in lista) {
                val donde = BarraConfig.donde(d.id)
                val detalle = if (donde == null) null else "Ahora en la " + nombreBarra(donde.first).lowercase() + ", fila " + (donde.second + 1)
                hoja.fila(d.icono + "  " + d.nombre, detalle) {
                    BarraConfig.colocar(d.id, b, maxOf(0, b.filas.size - 1))
                    g.aplicarBarra()
                    ir(BARRA)
                }
            }
        }
    }

    /** Elegir a qué barra y fila va el botón [moviendo], o quitarlo. */
    private fun pintarMover() {
        val id = moviendo
        titulo("Mover")
        hoja.nota(nombreBoton(id))
        val actual = BarraConfig.donde(id)
        for (b in listOf(BarraConfig.sup, BarraConfig.inf)) {
            if (!BarraConfig.puedeIrA(id, b)) continue
            hoja.seccion(nombreBarra(b))
            for (fi in 0 until b.filas.size) {
                val aqui = actual != null && actual.first === b && actual.second == fi
                hoja.fila("Fila " + (fi + 1), if (aqui) "Está aquí" else null) {
                    if (!aqui) {
                        BarraConfig.colocar(id, b, fi)
                        g.aplicarBarra()
                    }
                    barraSel = b.id
                    ir(BARRA)
                }
            }
            if (b.filas.size < BarraConfig.MAX_FILAS) {
                hoja.fila("Fila nueva", "Debajo de las demás") {
                    BarraConfig.colocar(id, b, b.filas.size)
                    g.aplicarBarra()
                    barraSel = b.id
                    ir(BARRA)
                }
            }
        }
        if (BarraConfig.puedeQuitarse(id)) {
            hoja.seccion("Quitar")
            hoja.fila("Quitar de las barras", "Vuelve al catálogo; se puede añadir otra vez") {
                BarraConfig.quitar(id)
                g.aplicarBarra()
                ir(BARRA)
            }
        } else {
            hoja.nota(if (id == BarraConfig.OMNIBOX) "El campo de dirección no se puede quitar ni bajar." else "El menú no se puede quitar, para no quedarte sin él; sí puedes moverlo.")
        }
    }

    /** Maqueta de una barra con la configuración actual. */
    private fun vistaPreviaBarra(b: Barra): View {
        val caja = LinearLayout(act)
        caja.orientation = LinearLayout.VERTICAL
        val f = GradientDrawable()
        f.setColor(paleta.fondo)
        f.setStroke(dpi(1), paleta.campo)
        f.cornerRadius = dpi(10).toFloat()
        caja.background = f
        caja.setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
        if (b.filas.isEmpty()) {
            val t = TextView(act)
            t.text = "(vacía: no se ve)"
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            t.setTextColor(paleta.tenue)
            t.gravity = Gravity.CENTER
            caja.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(b.alto * 3 / 4)))
        }
        for (fila in b.filas) {
            val barra = LinearLayout(act)
            barra.orientation = LinearLayout.HORIZONTAL
            barra.gravity = Gravity.CENTER_VERTICAL
            var primero = true
            for (e in fila) {
                val lp: LinearLayout.LayoutParams
                val v: View
                if (e.id == BarraConfig.OMNIBOX) {
                    val c = TextView(act)
                    c.text = "dirección"
                    c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    c.setTextColor(paleta.tenue)
                    c.gravity = Gravity.CENTER_VERTICAL
                    c.setPadding(dpi(10), 0, 0, 0)
                    val fc = GradientDrawable()
                    fc.setColor(paleta.campo)
                    fc.cornerRadius = dpi(14).toFloat()
                    c.background = fc
                    v = c
                    lp = LinearLayout.LayoutParams(0, dpi(b.alto * 3 / 4), 1f)
                } else {
                    val c = TextView(act)
                    c.text = BarraConfig.definicion(e.id)?.icono ?: "?"
                    c.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (e.tamano > 0) e.tamano else 36) * 0.34f)
                    c.setTextColor(paleta.texto)
                    c.gravity = Gravity.CENTER
                    v = c
                    lp = if (e.tamano > 0) LinearLayout.LayoutParams(dpi(e.tamano * 3 / 4), dpi(b.alto * 3 / 4))
                    else LinearLayout.LayoutParams(0, dpi(b.alto * 3 / 4), 1f)
                }
                if (!primero) lp.leftMargin = dpi(b.separacion * 3 / 4)
                primero = false
                barra.addView(v, lp)
            }
            caja.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return caja
    }

    // ---------------------------------------------------------------- pantalla de inicio

    private fun cambioInicio() {
        g.refrescarInicio()
        repintar()
    }

    private fun vistaPrevia(): View {
        val v = VistaPrevioInicio(
            act, g.coloresInicio(), ajustes.inicioTitulo, ajustes.inicioSubtitulo, ajustes.inicioBuscador,
            if (ajustes.inicioAccesos) ajustes.inicioMaxAccesos else 0, ajustes.inicioCuadrados, ajustes.inicioEscudo,
            ajustes.inicioEnlacePersonalizar
        )
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(210))
        lp.bottomMargin = dpi(8)
        v.layoutParams = lp
        return v
    }

    private fun nombreTema(): String =
        Paginas.TEMAS_INICIO.firstOrNull { it.id == ajustes.inicioTema }?.nombre?.substringBefore(" (") ?: "Automático"

    private fun pintarInicio() {
        titulo("Pantalla de inicio")
        hoja.agregar(vistaPrevia())
        hoja.fila("Colores", "Tema de la pantalla de inicio", nombreTema(), icono = "🎨") { ir(TEMAS) }
        hoja.fila(
            "Título", "Texto grande arriba", if (ajustes.inicioTitulo.isBlank()) "ninguno" else ajustes.inicioTitulo.take(18)
        ) {
            Dialogos.texto(act, paleta, privada, "Título (vacío = sin título)", ajustes.inicioTitulo, true) { t ->
                ajustes.inicioTitulo = t.take(80)
                cambioInicio()
            }
        }
        hoja.fila(
            "Subtítulo", "Texto pequeño bajo el título", if (ajustes.inicioSubtitulo.isBlank()) "ninguno" else ajustes.inicioSubtitulo.take(18)
        ) {
            Dialogos.texto(act, paleta, privada, "Subtítulo (vacío = sin subtítulo)", ajustes.inicioSubtitulo, true) { t ->
                ajustes.inicioSubtitulo = t.take(80)
                cambioInicio()
            }
        }
        hoja.interruptor("Buscador", "Campo para buscar o escribir una dirección", ajustes.inicioBuscador) { v ->
            ajustes.inicioBuscador = v
            cambioInicio()
        }
        hoja.seccion("Accesos directos")
        hoja.interruptor("Mostrar accesos", "Marcadores o sitios en la pantalla de inicio", ajustes.inicioAccesos) { v ->
            ajustes.inicioAccesos = v
            cambioInicio()
        }
        if (ajustes.inicioAccesos) {
            hoja.fila("Origen de los accesos", null, NOMBRES_ORIGEN[ajustes.inicioOrigen.coerceIn(0, 2)]) {
                Dialogos.elegir(act, paleta, privada, "Origen de los accesos", NOMBRES_ORIGEN, ajustes.inicioOrigen.coerceIn(0, 2)) { i ->
                    ajustes.inicioOrigen = i
                    cambioInicio()
                }
            }
            if (ajustes.inicioOrigen == 1) {
                hoja.fila("Mis accesos", "Añadir y quitar", ajustes.accesosPropios().size.toString()) { ir(ACCESOS_INICIO) }
            }
            val n = ajustes.inicioMaxAccesos
            hoja.paso(
                "Cantidad", n.toString(), n > 4, n < 12,
                {
                    ajustes.inicioMaxAccesos = maxOf(4, n - 4)
                    cambioInicio()
                },
                {
                    ajustes.inicioMaxAccesos = minOf(12, n + 4)
                    cambioInicio()
                }
            )
            hoja.interruptor("Iconos cuadrados", "Si no, redondos", ajustes.inicioCuadrados) { v ->
                ajustes.inicioCuadrados = v
                cambioInicio()
            }
        }
        hoja.seccion("Al pie")
        hoja.interruptor("Icono de escudo", "Abre Protección desde la pantalla de inicio", ajustes.inicioEscudo) { v ->
            ajustes.inicioEscudo = v
            cambioInicio()
        }
        hoja.interruptor("Enlace «Personalizar»", "Atajo a estos ajustes", ajustes.inicioEnlacePersonalizar) { v ->
            ajustes.inicioEnlacePersonalizar = v
            cambioInicio()
        }
        hoja.boton("Restablecer el aspecto original", true) {
            confirmar("Restablecer inicio", "Colores, título, buscador y accesos vuelven a su valor original. Tus accesos propios se conservan.", "Restablecer") {
                ajustes.restablecerInicio()
                g.refrescarInicio()
                g.avisar("Pantalla de inicio restablecida")
                repintar()
            }
        }
    }

    private fun pintarTemas() {
        titulo("Colores de la pantalla de inicio")
        hoja.agregar(vistaPrevia())
        for (t in Paginas.TEMAS_INICIO) {
            hoja.fila(t.nombre, null, null, false, if (t.id == ajustes.inicioTema) "●" else "○") {
                ajustes.inicioTema = t.id
                g.refrescarInicio()
                if (t.id == "personalizado") ir(COLORES) else repintar()
            }
        }
        if (ajustes.inicioTema == "personalizado") hoja.fila("Editar colores personalizados…", icono = "✎") { ir(COLORES) }
    }

    private fun pintarColores() {
        titulo("Colores personalizados")
        hoja.agregar(vistaPrevia())
        fun color(nombre: String, valor: String, guardar: (String) -> Unit) {
            hoja.fila(nombre, null, valor) {
                Dialogos.texto(act, paleta, privada, nombre + " (#RRGGBB)", valor) { t ->
                    val h = Paginas.normalizarHex(t)
                    if (h == null) {
                        g.avisar("Color no válido. Ejemplo: #1E88E5")
                    } else {
                        guardar(h)
                        g.refrescarInicio()
                    }
                    repintar()
                }
            }
        }
        color("Fondo", ajustes.inicioColorFondo) { ajustes.inicioColorFondo = it }
        color("Texto", ajustes.inicioColorTexto) { ajustes.inicioColorTexto = it }
        color("Acento (título y botones)", ajustes.inicioColorAcento) { ajustes.inicioColorAcento = it }
        color("Iconos y buscador", ajustes.inicioColorSuperficie) { ajustes.inicioColorSuperficie = it }
    }

    private fun pintarAccesosInicio() {
        titulo("Mis accesos")
        hoja.nota("Toca uno para quitarlo.")
        hoja.fila("Añadir un acceso…", icono = "＋") {
            Dialogos.texto(act, paleta, privada, "Dirección del sitio", "https://") { u ->
                val url = g.normalizarUrl(u)
                if (url == null) {
                    g.avisar("Dirección no válida")
                } else {
                    Dialogos.texto(act, paleta, privada, "Nombre", Omnibox.hostDe(url).removePrefix("www.")) { nombre ->
                        ajustes.guardarAccesosPropios(ajustes.accesosPropios() + Pair(nombre, url))
                        g.refrescarInicio()
                        repintar()
                    }
                }
            }
        }
        val p = g.pestana()
        if (p != null && esWeb(p)) {
            hoja.fila("Añadir la página actual", icono = "＋") {
                val nombre = if (p.titulo.isNotBlank()) p.titulo else Omnibox.hostDe(p.url)
                ajustes.guardarAccesosPropios(ajustes.accesosPropios() + Pair(nombre, p.url))
                g.refrescarInicio()
                repintar()
            }
        }
        for ((i, e) in ajustes.accesosPropios().withIndex()) {
            hoja.fila(e.first, Omnibox.hostDe(e.second), "Quitar", aviso = true) {
                val nueva = ArrayList(ajustes.accesosPropios())
                if (i < nueva.size) nueva.removeAt(i)
                ajustes.guardarAccesosPropios(nueva)
                g.refrescarInicio()
                repintar()
            }
        }
    }
}
