package com.optimo.navegador

import android.app.Activity

/** Diálogos de confirmar y elegir con el diseño de las hojas (sustituyen a los AlertDialog del sistema). */
object Dialogos {
    /** Pide confirmación: [boton] ejecuta [accion] (en rojo si [aviso]); «Cancelar» no hace nada. */
    fun confirmar(
        act: Activity, paleta: Paleta, privada: Boolean,
        titulo: String, mensaje: String, boton: String, aviso: Boolean = true, accion: () -> Unit
    ) {
        val h = Hoja(act, paleta, privada)
        h.encabezado(titulo)
        h.parrafo(mensaje)
        h.boton(boton, aviso) {
            h.cerrar()
            accion()
        }
        h.boton("Cancelar") { h.cerrar() }
        h.mostrar()
    }

    /** Lista de opciones con la actual marcada ([seleccion] = -1 si no hay). */
    fun elegir(
        act: Activity, paleta: Paleta, privada: Boolean,
        titulo: String, opciones: List<String>, seleccion: Int, alElegir: (Int) -> Unit
    ) {
        val h = Hoja(act, paleta, privada)
        h.encabezado(titulo)
        for ((i, o) in opciones.withIndex()) {
            h.fila(o, null, null, false, if (seleccion < 0) null else if (i == seleccion) "●" else "○") {
                h.cerrar()
                alElegir(i)
            }
        }
        h.mostrar()
    }

    /** Pide un texto (nombre, dirección…). Vacío solo se acepta con [permitirVacio]. */
    fun texto(
        act: Activity, paleta: Paleta, privada: Boolean,
        titulo: String, inicial: String, permitirVacio: Boolean = false, alAceptar: (String) -> Unit
    ) {
        val h = Hoja(act, paleta, privada)
        h.teclado(true)
        h.encabezado(titulo)
        val c = h.campo("", inicial)
        c.setSelectAllOnFocus(true)
        h.boton("Aceptar") {
            val t = c.text.toString().trim()
            if (t.isEmpty() && !permitirVacio) {
                c.error = "Escribe algo"
            } else {
                h.cerrar()
                alAceptar(t)
            }
        }
        h.boton("Cancelar") { h.cerrar() }
        h.mostrar()
        c.requestFocus()
    }

    /** Lista de acciones (nombre, en rojo si destructiva, acción). Cada una cierra la hoja antes de ejecutarse. */
    fun acciones(act: Activity, paleta: Paleta, privada: Boolean, titulo: String?, items: List<Triple<String, Boolean, () -> Unit>>) {
        val h = Hoja(act, paleta, privada)
        if (titulo != null) h.titulo(titulo)
        for (a in items) {
            h.fila(a.first, null, null, a.second) {
                h.cerrar()
                a.third()
            }
        }
        h.mostrar()
    }
}
