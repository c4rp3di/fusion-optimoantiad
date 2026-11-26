package com.optimo.navegador

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.webkit.WebView

/**
 * Miniaturas de las pestañas para la cuadrícula del gestor. Se toman al salir de una pestaña (y al abrir el gestor, la activa),
 * solo en memoria: no se guardan en disco ni se hacen de pestañas privadas, y se borran con el historial.
 */
object Miniaturas {
    private const val ANCHO = 320
    private const val ALTO_MAX = 480
    private val cache = object : LruCache<Int, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(clave: Int, valor: Bitmap): Int = valor.byteCount
    }

    /** Toma la miniatura de lo que muestra ahora la página de [p]. Hay que llamarlo en el hilo principal. */
    fun capturar(p: Pestana) {
        if (p.privada || p.inicio) return
        val w = p.web ?: return
        capturarWeb(p.id, w)
    }

    private fun capturarWeb(id: Int, w: WebView) {
        try {
            if (w.width <= 0 || w.height <= 0) return
            val escala = ANCHO.toFloat() / w.width
            val alto = minOf(ALTO_MAX, maxOf(1, (w.height * escala).toInt()))
            val bmp = Bitmap.createBitmap(ANCHO, alto, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.drawColor(0xFFFFFFFF.toInt())
            c.scale(escala, escala)
            w.draw(c)
            cache.put(id, bmp)
        } catch (e: Exception) {
            // Sin memoria o WebView en un estado raro: simplemente no hay miniatura.
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
        }
    }

    fun obtener(p: Pestana): Bitmap? = if (p.privada) null else cache.get(p.id)

    fun olvidar(p: Pestana) {
        cache.remove(p.id)
    }

    fun vaciar() {
        cache.evictAll()
    }
}
