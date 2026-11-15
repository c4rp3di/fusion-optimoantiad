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

    /** URL http original cuando se intento mejorar a https (para el respaldo si falla). */
    var mejoradoDesde: String? = null
}
