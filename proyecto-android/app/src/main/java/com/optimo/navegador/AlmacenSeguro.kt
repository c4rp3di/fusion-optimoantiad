package com.optimo.navegador

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado de los datos locales de Optimo (marcadores, historial, sesion, reglas del usuario).
 *
 * Misma idea que La Boveda: AES-256-GCM con una clave que vive en el Android Keystore y nunca sale de el.
 * Cada dato lleva una etiqueta (nombre del archivo) como datos autenticados, asi que un archivo cifrado no
 * se puede intercambiar por otro. Formato del archivo: "OPT1" + IV (12 bytes) + texto cifrado con etiqueta GCM.
 *
 * Los archivos antiguos en texto plano se leen y se migran a cifrado al instante. Si el Keystore no esta
 * disponible NO se escribe en claro: se pierde la escritura (privacidad antes que persistencia).
 * Sin dependencias: solo APIs del sistema.
 */
object AlmacenSeguro {

    private const val ALIAS = "optimo_datos_v1"
    private const val TAM_IV = 12
    private const val TAM_ETIQUETA_BITS = 128
    private val MAGIA = byteArrayOf(0x4F, 0x50, 0x54, 0x31) // "OPT1"

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existente = ks.getKey(ALIAS, null)
        if (existente is SecretKey) return existente
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Solo cuando la clave es inservible (invalidada o irrecuperable): se descarta para crear una nueva. */
    private fun descartarClave() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Nada mas que hacer.
        }
    }

    fun esCifrado(datos: ByteArray): Boolean {
        if (datos.size < MAGIA.size) return false
        for (i in MAGIA.indices) {
            if (datos[i] != MAGIA[i]) return false
        }
        return true
    }

    private fun cifrarBytes(claro: ByteArray, etiqueta: String): ByteArray? {
        for (intento in 0..1) {
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, clave())
                c.updateAAD(etiqueta.toByteArray(Charsets.UTF_8))
                val iv = c.iv
                val cifrado = c.doFinal(claro)
                return MAGIA + iv + cifrado
            } catch (e: Exception) {
                if (intento == 0 && (e is InvalidKeyException || e is UnrecoverableKeyException)) {
                    descartarClave()
                } else {
                    return null
                }
            }
        }
        return null
    }

    private fun descifrarBytes(datos: ByteArray, etiqueta: String): ByteArray? {
        if (!esCifrado(datos) || datos.size < MAGIA.size + TAM_IV + 16) return null
        return try {
            val iv = datos.copyOfRange(MAGIA.size, MAGIA.size + TAM_IV)
            val cifrado = datos.copyOfRange(MAGIA.size + TAM_IV, datos.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(TAM_ETIQUETA_BITS, iv))
            c.updateAAD(etiqueta.toByteArray(Charsets.UTF_8))
            c.doFinal(cifrado)
        } catch (e: Exception) {
            null
        }
    }

    /** Texto cifrado y en Base64, para guardarlo en SharedPreferences. Null si el Keystore falla. */
    fun cifrarTexto(texto: String, etiqueta: String): String? {
        val b = cifrarBytes(texto.toByteArray(Charsets.UTF_8), etiqueta) ?: return null
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    fun descifrarTexto(base64: String, etiqueta: String): String? {
        return try {
            val claro = descifrarBytes(Base64.decode(base64, Base64.NO_WRAP), etiqueta) ?: return null
            String(claro, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Lee un archivo cifrado (o uno antiguo en texto plano, que migra). Null si no existe o no se puede descifrar. */
    fun leerTexto(f: File, etiqueta: String = f.name): String? {
        try {
            if (!f.exists()) return null
            val datos = f.readBytes()
            if (esCifrado(datos)) {
                val claro = descifrarBytes(datos, etiqueta) ?: return null
                return String(claro, Charsets.UTF_8)
            }
            val texto = String(datos, Charsets.UTF_8)
            escribirTexto(f, texto, etiqueta)
            return texto
        } catch (e: Exception) {
            return null
        }
    }

    /** Escribe cifrado y de forma atomica (archivo temporal + renombrar). False si no se pudo. */
    fun escribirTexto(f: File, texto: String, etiqueta: String = f.name): Boolean {
        val cifrado = cifrarBytes(texto.toByteArray(Charsets.UTF_8), etiqueta) ?: return false
        return try {
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(cifrado)
            if (!tmp.renameTo(f)) {
                f.writeBytes(cifrado)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

