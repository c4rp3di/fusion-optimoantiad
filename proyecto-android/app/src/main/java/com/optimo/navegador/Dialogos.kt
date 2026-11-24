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
}
