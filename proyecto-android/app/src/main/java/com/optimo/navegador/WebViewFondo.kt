package com.optimo.navegador

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView que, mientras suena audio, no se entera de que la ventana se ha ocultado (pantalla bloqueada o
 * cambio de app). Sin esto Chromium marca la página como oculta y pausa el vídeo por su cuenta, y el script de
 * la página no puede evitarlo. [audioActivo] se consulta en el momento del aviso: si no suena nada, el WebView
 * se oculta con normalidad y no gasta batería en segundo plano.
 */
class WebViewFondo(contexto: Context) : WebView(contexto) {

    /** Ajuste «Seguir sonando»: si es false, este WebView se comporta como uno normal. */
    @Volatile
    var activo: Boolean = false

    /** Devuelve true si ahora mismo suena audio (o acaba de sonar). Se llama en el hilo principal. */
    var audioActivo: () -> Boolean = { false }

    private var ocultoDeVerdad = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (activo && !ocultoDeVerdad && visibility == View.GONE && audioActivo()) return
        super.onWindowVisibilityChanged(visibility)
    }

    /** Oculta el WebView de verdad (el audio terminó y la app sigue fuera de pantalla). */
    fun ocultarAhora() {
        ocultoDeVerdad = true
        try {
            onWindowVisibilityChanged(View.GONE)
        } finally {
            ocultoDeVerdad = false
        }
    }
}
