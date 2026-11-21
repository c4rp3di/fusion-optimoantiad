package com.optimo.navegador

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import java.security.MessageDigest

/**
 * Autorización para continuar con un certificado no válido (solo equipos de la red local). Está atada al sitio (host:puerto),
 * a la huella SHA-256 del certificado exacto y a los motivos del fallo: otro certificado, o un motivo nuevo, vuelven a preguntar.
 * [caduca] = 0 significa «solo esta sesión» (no se escribe en disco).
 */
class ExcepcionSsl(
    val sitio: String,
    val huella: String,
    val motivos: Int,
    val concedida: Long,
    val caduca: Long,
    val privada: Boolean = false
)

/** Utilidades sobre certificados: huella, motivos del fallo y textos. Sin estado. */
object Certificados {

    const val CADUCADO = 1
    const val AUTOFIRMADO = 2
    const val ENTIDAD = 4
    const val NOMBRE = 8
    const val AUN_NO = 16
    const val OTRO = 32

    const val HORA_MS = 3600000L
    const val DIA_MS = 86400000L

    /** Huella SHA-256 del certificado en hexadecimal con «:» (AB:CD:…), o null si el sistema no la da. */
    fun huella(c: SslCertificate?): String? {
        val der = derDe(c) ?: return null
        return try {
            val d = MessageDigest.getInstance("SHA-256").digest(der)
            val sb = StringBuilder()
            for (i in d.indices) {
                if (i > 0) sb.append(':')
                sb.append(String.format("%02X", d[i].toInt() and 0xFF))
            }
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun derDe(c: SslCertificate?): ByteArray? {
        if (c == null) return null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val x = c.x509Certificate
                if (x != null) return x.encoded
            } catch (e: Exception) {
                // Se prueba el otro camino.
            }
        }
        // API 26-28: saveState guarda el certificado codificado en el Bundle (clave interna «x509-certificate»).
        return try {
            SslCertificate.saveState(c)?.getByteArray("x509-certificate")
        } catch (e: Exception) {
            null
        }
    }

    /** Autofirmado: el certificado lo firmó el propio equipo (emitido para y por el mismo nombre). */
    fun autofirmado(c: SslCertificate?): Boolean {
        if (c == null) return false
        return try {
            val a = c.issuedTo?.dName ?: ""
            val b = c.issuedBy?.dName ?: ""
            a.isNotEmpty() && a == b
        } catch (e: Exception) {
            false
        }
    }

    /** Caducado o aún no válido, mirando las fechas del certificado (el WebView suele dar solo «fecha no válida»). */
    private fun motivoFecha(c: SslCertificate?): Int {
        try {
            val ahora = java.util.Date()
            val hasta = c?.validNotAfterDate
            val desde = c?.validNotBeforeDate
            if (hasta != null && ahora.after(hasta)) return CADUCADO
            if (desde != null && ahora.before(desde)) return AUN_NO
        } catch (e: Exception) {
            // Sin fechas fiables.
        }
        return OTRO
    }

    /** Motivos del fallo como bits (CADUCADO, AUTOFIRMADO, ENTIDAD, NOMBRE, AUN_NO, OTRO). */
    fun motivos(error: SslError): Int {
        var m = 0
        if (error.hasError(SslError.SSL_EXPIRED)) m = m or CADUCADO
        if (error.hasError(SslError.SSL_NOTYETVALID)) m = m or AUN_NO
        if (error.hasError(SslError.SSL_DATE_INVALID)) m = m or motivoFecha(error.certificate)
        if (error.hasError(SslError.SSL_UNTRUSTED)) m = m or (if (autofirmado(error.certificate)) AUTOFIRMADO else ENTIDAD)
        if (error.hasError(SslError.SSL_IDMISMATCH)) m = m or NOMBRE
        if (m == 0) m = OTRO
        return m
    }

    /** Nombres cortos de los motivos, separados por comas. */
    fun etiqueta(m: Int): String {
        val l = ArrayList<String>()
        if ((m and AUTOFIRMADO) != 0) l.add("autofirmado")
        if ((m and ENTIDAD) != 0) l.add("entidad no reconocida")
        if ((m and NOMBRE) != 0) l.add("nombre incorrecto")
        if ((m and CADUCADO) != 0) l.add("caducado")
        if ((m and AUN_NO) != 0) l.add("aún no válido")
        if ((m and OTRO) != 0) l.add("no válido")
        return l.joinToString(", ")
    }

    fun frase(m: Int): String = "Certificado no válido: " + etiqueta(m) + "."

    /** Explicación de cada motivo, una línea por motivo. */
    fun explicacion(m: Int): String {
        val l = ArrayList<String>()
        if ((m and AUTOFIRMADO) != 0) l.add("• Autofirmado: lo ha firmado el propio equipo, no una entidad de confianza. Es habitual en routers y NAS.")
        if ((m and ENTIDAD) != 0) l.add("• Entidad no reconocida: la firma no es de una entidad en la que confíe Android.")
        if ((m and NOMBRE) != 0) l.add("• Nombre incorrecto: el certificado no corresponde a la dirección que has abierto.")
        if ((m and CADUCADO) != 0) l.add("• Caducado: su fecha de validez ya pasó.")
        if ((m and AUN_NO) != 0) l.add("• Aún no válido: su fecha de inicio es futura (¿reloj del móvil mal puesto?).")
        if ((m and OTRO) != 0) l.add("• El certificado no es válido por otro motivo.")
        return l.joinToString("\n")
    }

    /** Cuánto se puede recordar: menos si el certificado está caducado o aún no vale (va a cambiar pronto). */
    fun duracionRecordar(m: Int): Long =
        if ((m and (CADUCADO or AUN_NO)) != 0) DIA_MS else 30 * DIA_MS

    fun etiquetaDuracion(ms: Long): String = if (ms <= DIA_MS) "24 horas" else "30 días"

    fun huellaCorta(h: String): String = if (h.length > 23) h.substring(0, 23) + "…" else h

    fun vigencia(e: ExcepcionSsl): String {
        if (e.caduca == 0L) return "solo esta sesión"
        val ms = e.caduca - System.currentTimeMillis()
        val h = (ms / HORA_MS).toInt()
        return if (h >= 48) "caduca en " + (h / 24) + " días" else "caduca en " + maxOf(h, 1) + " h"
    }
}

/**
 * Excepciones de certificado concedidas por el usuario. Las de «esta sesión» (y todas las de pestañas privadas) solo viven en memoria;
 * las recordadas se guardan con su caducidad y se descartan al vencer. No entran en la copia de seguridad.
 */
object ExcepcionesSsl {

    private const val CLAVE = "excepciones"
    private var sp: SharedPreferences? = null
    private val sesion = ArrayList<ExcepcionSsl>()

    fun iniciar(c: Context) {
        if (sp == null) sp = c.applicationContext.getSharedPreferences("optimo_ssl", Context.MODE_PRIVATE)
    }

    /** Clave de sitio: host en minúsculas y puerto (443 si no se indica). Vacía si la dirección no tiene host. */
    fun clave(url: String): String {
        var r = ""
        try {
            val u = Uri.parse(url)
            val h = (u.host ?: "").lowercase()
            if (h.isNotEmpty()) r = h + ":" + (if (u.port == -1) 443 else u.port)
        } catch (e: Exception) {
            r = ""
        }
        return r
    }

    private fun escribir(e: ExcepcionSsl): String =
        e.sitio + "|" + e.huella + "|" + e.motivos + "|" + e.concedida + "|" + e.caduca

    private fun leer(t: String): ExcepcionSsl? {
        val p = t.split('|')
        if (p.size != 5) return null
        val motivos = p[2].toIntOrNull() ?: return null
        val concedida = p[3].toLongOrNull() ?: return null
        val caduca = p[4].toLongOrNull() ?: return null
        if (p[0].isEmpty() || p[1].isEmpty()) return null
        return ExcepcionSsl(p[0], p[1], motivos, concedida, caduca, false)
    }

    /** Todas las vigentes (memoria y disco). Las caducadas del disco se descartan aquí. */
    private fun vigentes(): List<ExcepcionSsl> {
        val res = ArrayList<ExcepcionSsl>(sesion)
        val s = sp ?: return res
        val crudo = s.getStringSet(CLAVE, emptySet()) ?: emptySet()
        val ahora = System.currentTimeMillis()
        val quedan = HashSet<String>()
        for (t in crudo) {
            val e = leer(t) ?: continue
            if (e.caduca > ahora) {
                res.add(e)
                quedan.add(t)
            }
        }
        if (quedan.size != crudo.size) s.edit().putStringSet(CLAVE, quedan).apply()
        return res
    }

    /** Las del usuario para mostrar en Ajustes (sin las de pestañas privadas). */
    fun todas(): List<ExcepcionSsl> = vigentes().filter { e -> !e.privada }

    /** ¿Hay una autorización que cubra este certificado y estos motivos? Un motivo nuevo no está cubierto. */
    fun permite(sitio: String, huella: String, motivos: Int, privada: Boolean): Boolean {
        for (e in vigentes()) {
            if (e.privada && !privada) continue
            if (e.sitio == sitio && e.huella == huella && (motivos and e.motivos.inv()) == 0) return true
        }
        return false
    }

    /** ¿Hay una autorización de este sitio para OTRO certificado? Señal de que el equipo ha cambiado de certificado. */
    fun hayOtraHuella(sitio: String, huella: String, privada: Boolean): Boolean {
        for (e in vigentes()) {
            if (e.privada && !privada) continue
            if (e.sitio == sitio && e.huella != huella) return true
        }
        return false
    }

    fun hayParaSitio(sitio: String, privada: Boolean): Boolean {
        if (sitio.isEmpty()) return false
        for (e in vigentes()) {
            if (e.privada && !privada) continue
            if (e.sitio == sitio) return true
        }
        return false
    }

    fun anadir(e: ExcepcionSsl) {
        quitar(e)
        if (e.caduca == 0L || e.privada) {
            sesion.add(e)
            return
        }
        val s = sp ?: return
        val nuevo = HashSet<String>(s.getStringSet(CLAVE, emptySet()) ?: emptySet())
        nuevo.add(escribir(e))
        s.edit().putStringSet(CLAVE, nuevo).apply()
    }

    /** Quita la autorización de ese sitio y esa huella. */
    fun quitar(e: ExcepcionSsl) {
        sesion.removeAll { x -> x.sitio == e.sitio && x.huella == e.huella }
        val s = sp ?: return
        val actual = s.getStringSet(CLAVE, emptySet()) ?: emptySet()
        val nuevo = HashSet<String>()
        for (t in actual) {
            val x = leer(t) ?: continue
            if (!(x.sitio == e.sitio && x.huella == e.huella)) nuevo.add(t)
        }
        s.edit().putStringSet(CLAVE, nuevo).apply()
    }

    fun quitarTodas() {
        sesion.clear()
        sp?.edit()?.remove(CLAVE)?.apply()
    }

    /** Descarta las de «esta sesión» (al cerrar Optimo). */
    fun olvidarSesion() {
        sesion.clear()
    }

    /** Descarta las de pestañas privadas (al cerrar la última). */
    fun olvidarPrivadas() {
        sesion.removeAll { x -> x.privada }
    }

    /** Descarta las recordadas que ya vencieron. true si había alguna (el WebView aún podría recordarlas). */
    fun limpiarCaducadas(): Boolean {
        val s = sp ?: return false
        val crudo = s.getStringSet(CLAVE, emptySet()) ?: emptySet()
        val antes = crudo.size
        vigentes()
        val despues = (s.getStringSet(CLAVE, emptySet()) ?: emptySet()).size
        return despues < antes
    }
}
