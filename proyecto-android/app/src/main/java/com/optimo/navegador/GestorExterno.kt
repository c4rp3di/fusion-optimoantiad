package com.optimo.navegador

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * Puente con el gestor de archivos propio (com.carpe.gestorarchivos). No hace falta tocar el gestor:
 * su MainActivity ya abre la carpeta de un ACTION_VIEW cuyo content:// sea del almacenamiento del sistema.
 * Si no está instalado, «Descargas» sigue abriendo el gestor de descargas del sistema.
 */
object GestorExterno {

    const val PAQUETE = "com.carpe.gestorarchivos"
    private const val ACTIVIDAD = "com.carpe.gestorarchivos.MainActivity"
    private const val AUTORIDAD_ALMACENAMIENTO = "com.android.externalstorage.documents"

    fun instalado(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(PAQUETE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * content://com.android.externalstorage.documents/document/primary:Download (no file://, que Android
     * prohíbe entre apps). No se concede permiso de lectura: el gestor solo lee la ruta que lleva el Uri.
     */
    @Suppress("DEPRECATION")
    private fun uriDescargas(): Uri {
        val raiz = Environment.getExternalStorageDirectory().absolutePath
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        val relativa = if (dir.startsWith("$raiz/")) dir.substring(raiz.length + 1) else Environment.DIRECTORY_DOWNLOADS
        return DocumentsContract.buildDocumentUri(AUTORIDAD_ALMACENAMIENTO, "primary:$relativa")
    }

    /** Abre el gestor en la carpeta Descargas. Devuelve false si no está instalado o no puede abrirlo. */
    fun abrirDescargas(ctx: Context): Boolean {
        if (!instalado(ctx)) return false
        return try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uriDescargas(), "vnd.android.document/directory")
            i.setClassName(PAQUETE, ACTIVIDAD)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
