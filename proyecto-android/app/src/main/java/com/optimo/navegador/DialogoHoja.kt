package com.optimo.navegador

import android.app.Activity
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * Sustituto de AlertDialog con el diseño de las hojas: mismos métodos (título, mensaje, botones, lista, casillas, vista propia)
 * pero se dibuja como hoja inferior. Hace de constructor y de diálogo a la vez: `show()` devuelve el mismo objeto.
 */
class DialogoHoja(private val act: Activity, private val paleta: Paleta, private val privada: Boolean) {
    private class Boton(val texto: CharSequence, val alPulsar: DialogInterface.OnClickListener?)

    private var titulo: CharSequence? = null
    private var mensaje: CharSequence? = null
    private var positivo: Boton? = null
    private var negativo: Boton? = null
    private var neutro: Boton? = null
    private var items: Array<out CharSequence>? = null
    private var alElegir: DialogInterface.OnClickListener? = null
    private var unica = -1
    private var multi: Array<out CharSequence>? = null
    private var marcadas: BooleanArray? = null
    private var alMarcar: DialogInterface.OnMultiChoiceClickListener? = null
    private var vista: View? = null
    private var cancelable = true
    private var alCancelar: DialogInterface.OnCancelListener? = null
    private var hoja: Hoja? = null
    private var textoMensaje: android.widget.TextView? = null
    private var porBoton = false

    private val interfaz = object : DialogInterface {
        override fun cancel() {
            this@DialogoHoja.cancel()
        }

        override fun dismiss() {
            this@DialogoHoja.dismiss()
        }
    }

    fun setTitle(t: CharSequence?): DialogoHoja {
        titulo = t
        return this
    }

    fun setMessage(m: CharSequence?): DialogoHoja {
        mensaje = m
        textoMensaje?.text = m
        return this
    }

    fun setPositiveButton(t: CharSequence, l: DialogInterface.OnClickListener?): DialogoHoja {
        positivo = Boton(t, l)
        return this
    }

    fun setNegativeButton(t: CharSequence, l: DialogInterface.OnClickListener?): DialogoHoja {
        negativo = Boton(t, l)
        return this
    }

    fun setNeutralButton(t: CharSequence, l: DialogInterface.OnClickListener?): DialogoHoja {
        neutro = Boton(t, l)
        return this
    }

    fun setItems(i: Array<out CharSequence>, l: DialogInterface.OnClickListener?): DialogoHoja {
        items = i
        alElegir = l
        unica = -1
        return this
    }

    fun setSingleChoiceItems(i: Array<out CharSequence>, elegido: Int, l: DialogInterface.OnClickListener?): DialogoHoja {
        items = i
        alElegir = l
        unica = elegido
        return this
    }

    fun setMultiChoiceItems(i: Array<out CharSequence>, marcados: BooleanArray?, l: DialogInterface.OnMultiChoiceClickListener?): DialogoHoja {
        multi = i
        marcadas = marcados ?: BooleanArray(i.size)
        alMarcar = l
        return this
    }

    fun setView(v: View): DialogoHoja {
        vista = v
        return this
    }

    fun setCancelable(b: Boolean): DialogoHoja {
        cancelable = b
        return this
    }

    fun setOnCancelListener(l: DialogInterface.OnCancelListener?): DialogoHoja {
        alCancelar = l
        return this
    }

    fun create(): DialogoHoja = this

    val isShowing: Boolean get() = hoja != null

    fun show(): DialogoHoja {
        // Las listas de opciones y casillas (ajustes) van en el panel lateral; confirmar y escribir, en una hoja inferior.
        val lateral = items != null || multi != null
        val h = Hoja(act, paleta, privada, lateral)
        hoja = h
        h.fijarCancelable(cancelable)
        val t = titulo
        if (t != null && t.isNotEmpty()) {
            if (lateral) h.barraTitulo(t.toString(), null) { h.cerrar() } else h.encabezado(t.toString())
        }
        val m = mensaje
        if (m != null && m.isNotEmpty()) textoMensaje = h.parrafo(m.toString())
        val v = vista
        if (v != null) {
            estilar(v)
            (v.parent as? ViewGroup)?.removeView(v)
            h.agregar(v)
        }
        val lista = h.zona()
        pintarLista(h, lista)
        val po = positivo
        if (po != null) h.boton(po.texto.toString(), esDestructivo(po.texto.toString())) { pulsar(po, DialogInterface.BUTTON_POSITIVE) }
        val ne = neutro
        if (ne != null) h.boton(ne.texto.toString()) { pulsar(ne, DialogInterface.BUTTON_NEUTRAL) }
        val ng = negativo
        if (ng != null) h.boton(ng.texto.toString()) { pulsar(ng, DialogInterface.BUTTON_NEGATIVE) }
        h.alCerrar {
            val estaba = hoja != null
            hoja = null
            if (estaba && !porBoton) alCancelar?.onCancel(interfaz)
        }
        h.mostrar()
        return this
    }

    private fun esDestructivo(t: String): Boolean =
        Regex("^(Borrar|Eliminar|Quitar|Revocar|Cerrar todas|Desbloquear|Olvidar|Restablecer|Descartar|Vaciar|Salir)").containsMatchIn(t)

    private fun pulsar(b: Boton, cual: Int) {
        porBoton = true
        hoja?.cerrar()
        b.alPulsar?.onClick(interfaz, cual)
    }

    private fun pintarLista(h: Hoja, zona: android.widget.LinearLayout) {
        h.llenar(zona) {
            val its = items
            if (its != null) {
                for ((i, nombre) in its.withIndex()) {
                    val icono = if (unica < 0) null else if (i == unica) "●" else "○"
                    h.fila(nombre.toString(), null, null, false, icono) {
                        if (unica >= 0) unica = i
                        if (unica < 0) {
                            porBoton = true
                            h.cerrar()
                        }
                        alElegir?.onClick(interfaz, i)
                        if (unica >= 0 && hoja != null) pintarLista(h, zona)
                    }
                }
            }
            val mu = multi
            val ma = marcadas
            if (mu != null && ma != null) {
                for ((i, nombre) in mu.withIndex()) {
                    h.fila((if (ma[i]) "☑  " else "☐  ") + nombre.toString()) {
                        ma[i] = !ma[i]
                        alMarcar?.onClick(interfaz, i, ma[i])
                        pintarLista(h, zona)
                    }
                }
            }
        }
    }

    /** Los campos de texto creados sin tema de diálogo salen con colores del tema claro: se ajustan a la paleta. */
    private fun estilar(v: View) {
        if (v is EditText) {
            v.setTextColor(paleta.texto)
            v.setHintTextColor(paleta.tenue)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) estilar(v.getChildAt(i))
        }
    }

    fun dismiss() {
        val h = hoja ?: return
        porBoton = true
        h.cerrar()
    }

    fun cancel() {
        val h = hoja ?: return
        h.cerrar()
    }
}
