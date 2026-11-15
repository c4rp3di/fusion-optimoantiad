package com.optimo.navegador

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Una lista de bloqueo descargable. [tam] es un texto orientativo (nº de dominios). */
class ListaDef(val id: String, val nombre: String, val tam: String, val url: String, val propia: Boolean = false)

object UtilDominio {
    fun parseIpv4(entrada: String): Int? {
        val partes = entrada.trim().split('.')
        if (partes.size != 4) return null
        var resultado = 0
        for (p in partes) {
            if (p.isEmpty() || p.length > 3) return null
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            resultado = (resultado shl 8) or n
        }
        return resultado
    }

    /** Dominio en minúsculas, o null si no es válido (menos de 3 caracteres, sin punto, caracteres raros o IP). */
    fun normalizar(entrada: String): String? {
        var t = entrada.trim().lowercase()
        if (t.startsWith("http://")) t = t.substring(7)
        if (t.startsWith("https://")) t = t.substring(8)
        val barra = t.indexOf('/')
        if (barra >= 0) t = t.substring(0, barra)
        val dosPuntos = t.indexOf(':')
        if (dosPuntos >= 0) t = t.substring(0, dosPuntos)
        t = t.trim('.')
        if (t.length < 3 || t.length > 253 || !t.contains('.')) return null
        for (c in t) {
            val valido = (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_'
            if (!valido) return null
        }
        if (parseIpv4(t) != null) return null
        return t
    }
}

/**
 * Lee una línea de una lista de bloqueo en cualquiera de estos formatos y devuelve el dominio, o null si no sirve:
 *  - hosts:     «0.0.0.0 ads.ejemplo.com»  (AdAway, StevenBlack, 1Hosts)
 *  - dominios:  «ads.ejemplo.com»          (OISD, Firebog, anudeepND)
 *  - Adblock:   «||ads.ejemplo.com^»       (EasyList, HaGeZi, AdGuard) y excepciones «@@||ok.ejemplo.com^»
 * Solo se pueden aplicar reglas de dominio entero: se descartan rutas, comodines, modificadores y reglas cosméticas.
 */
object ParserListas {

    class Entrada(val dominio: String, val permitido: Boolean)

    private val IGNORADOS = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback",
        "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0"
    )

    private val MODIFICADORES_ACEPTADOS = setOf("", "|", "\$important", "\$all")

    fun procesarLinea(linea: String): Entrada? {
        var s = linea.trim()
        if (s.isEmpty()) return null
        val c0 = s[0]
        if (c0 == '#' || c0 == '!' || c0 == '[') return null
        if (s.contains("##") || s.contains("#@#") || s.contains("#?#") || s.contains("#\$#")) return null

        if (s.startsWith("||") || s.startsWith("@@||")) {
            val permitido = s.startsWith("@@")
            val resto = s.substring(if (permitido) 4 else 2)
            val fin = resto.indexOf('^')
            if (fin < 0) return null
            if (!MODIFICADORES_ACEPTADOS.contains(resto.substring(fin + 1))) return null
            return crear(resto.substring(0, fin), permitido)
        }

        // Formato hosts o lista de dominios: se corta el comentario final.
        val almohadilla = s.indexOf('#')
        if (almohadilla > 0) s = s.substring(0, almohadilla).trim()
        val partes = s.split(' ', '\t').filter { it.isNotEmpty() }
        if (partes.isEmpty()) return null
        val candidato: String
        if (partes.size == 1) {
            candidato = partes[0]
        } else {
            val primero = partes[0]
            val esIp = UtilDominio.parseIpv4(primero) != null || primero.contains(':')
            if (!esIp) return null
            candidato = partes[1]
        }
        return crear(candidato, false)
    }

    private fun crear(crudo: String, permitido: Boolean): Entrada? {
        var d = crudo
        if (d.startsWith("*.")) d = d.substring(2)
        if (d.startsWith(".")) d = d.substring(1)
        if (d.contains('/') || d.contains('*') || d.contains(':') || d.contains('?') || d.contains('|')) return null
        val limpio = UtilDominio.normalizar(d) ?: return null
        if (IGNORADOS.contains(limpio)) return null
        return Entrada(limpio, permitido)
    }
}

/** Hash de 64 bits de un dominio, calculado de DERECHA a IZQUIERDA para comprobar todos sus sufijos en una pasada. */
object HashDominio {
    const val SEMILLA = 1125899906842597L
    const val PRIMO = 1099511628211L

    fun completo(dominio: String): Long {
        var h = SEMILLA
        var i = dominio.length - 1
        while (i >= 0) {
            h = (h xor dominio[i].code.toLong()) * PRIMO
            i--
        }
        return h
    }
}

/**
 * Conjunto de hashes de 64 bits en una tabla abierta de longs: ~16 bytes por dominio, sin objetos por entrada.
 * 600 000 dominios ocupan unos 16 MB y una consulta tarda menos de un microsegundo. El 0 marca «hueco».
 */
class ConjuntoHash64 private constructor(capacidad: Int) {
    private var tabla = LongArray(capacidad)

    var tamano = 0
        private set

    constructor() : this(1024)

    fun agregar(valor: Long): Boolean {
        val h = if (valor == 0L) 1L else valor
        if ((tamano + 1) * 10 > tabla.size * 6) crecer()
        if (insertar(tabla, h)) {
            tamano++
            return true
        }
        return false
    }

    fun contiene(valor: Long): Boolean {
        val h = if (valor == 0L) 1L else valor
        val t = tabla
        val mascara = t.size - 1
        var i = ((h xor (h ushr 32)).toInt()) and mascara
        while (true) {
            val v = t[i]
            if (v == 0L) return false
            if (v == h) return true
            i = (i + 1) and mascara
        }
    }

    private fun insertar(t: LongArray, h: Long): Boolean {
        val mascara = t.size - 1
        var i = ((h xor (h ushr 32)).toInt()) and mascara
        while (true) {
            val v = t[i]
            if (v == 0L) {
                t[i] = h
                return true
            }
            if (v == h) return false
            i = (i + 1) and mascara
        }
    }

    private fun crecer() {
        val nueva = LongArray(tabla.size * 2)
        for (v in tabla) {
            if (v != 0L) insertar(nueva, v)
        }
        tabla = nueva
    }

    fun escribir(salida: OutputStream) {
        val cab = ByteBuffer.allocate(8)
        cab.putInt(tamano)
        cab.putInt(tabla.size)
        salida.write(cab.array())
        val bloque = ByteBuffer.allocate(BLOQUE * 8)
        var i = 0
        while (i < tabla.size) {
            val n = minOf(BLOQUE, tabla.size - i)
            bloque.clear()
            for (j in 0 until n) bloque.putLong(tabla[i + j])
            salida.write(bloque.array(), 0, n * 8)
            i += n
        }
    }

    companion object {
        private const val BLOQUE = 8192

        fun leer(entrada: InputStream): ConjuntoHash64 {
            val din = DataInputStream(entrada)
            val tam = din.readInt()
            val cap = din.readInt()
            if (cap < 16 || cap > (1 shl 26) || (cap and (cap - 1)) != 0 || tam < 0 || tam > cap) {
                throw IllegalStateException("caché de filtro no válida")
            }
            val c = ConjuntoHash64(cap)
            val bytes = ByteArray(BLOQUE * 8)
            var i = 0
            while (i < cap) {
                val n = minOf(BLOQUE, cap - i)
                din.readFully(bytes, 0, n * 8)
                val bb = ByteBuffer.wrap(bytes, 0, n * 8)
                for (j in 0 until n) c.tabla[i + j] = bb.getLong()
                i += n
            }
            c.tamano = tam
            return c
        }
    }
}

/** Dominios bloqueados y excepciones. Un dominio bloqueado incluye sus subdominios; una excepción gana siempre. */
class FiltroListas {

    class Tablas(val bloqueo: ConjuntoHash64, val permitido: ConjuntoHash64)

    @Volatile
    private var tablas: Tablas? = null

    val cargado: Boolean get() = tablas != null

    val totalDominios: Int get() = tablas?.bloqueo?.tamano ?: 0

    fun reemplazar(nuevas: Tablas) {
        tablas = nuevas
    }

    /** NO_LISTADO, BLOQUEADO o PERMITIDO (excepción de las listas). */
    fun consultar(nombre: String): Int {
        val t = tablas ?: return NO_LISTADO
        return evaluar(t, nombre.lowercase().trim('.'))
    }

    private fun evaluar(t: Tablas, nombre: String): Int {
        var h = HashDominio.SEMILLA
        var puntos = 0
        var bloq = false
        var perm = false
        var i = nombre.length - 1
        while (i >= 0) {
            val c = nombre[i]
            h = (h xor c.code.toLong()) * HashDominio.PRIMO
            if (c == '.') puntos++
            // [i..fin] es un sufijo completo (empieza en una etiqueta). Los sufijos sin punto (TLD) no se miran.
            if ((i == 0 || nombre[i - 1] == '.') && puntos > 0) {
                if (t.permitido.contiene(h)) perm = true
                if (t.bloqueo.contiene(h)) bloq = true
            }
            i--
        }
        if (perm) return PERMITIDO
        return if (bloq) BLOQUEADO else NO_LISTADO
    }

    fun guardarCache(archivo: File, t: Tablas) {
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        tmp.outputStream().buffered().use { out ->
            out.write(MAGIA)
            t.bloqueo.escribir(out)
            t.permitido.escribir(out)
        }
        if (!tmp.renameTo(archivo)) {
            archivo.delete()
            tmp.renameTo(archivo)
        }
    }

    /** Caché compilada (mucho más rápida que reprocesar las listas); null si no existe o está dañada. */
    fun leerCache(archivo: File): Tablas? {
        if (!archivo.exists()) return null
        return try {
            archivo.inputStream().buffered().use { entrada ->
                val magia = ByteArray(MAGIA.size)
                DataInputStream(entrada).readFully(magia)
                if (!magia.contentEquals(MAGIA)) {
                    null
                } else {
                    val bloqueo = ConjuntoHash64.leer(entrada)
                    val permitido = ConjuntoHash64.leer(entrada)
                    Tablas(bloqueo, permitido)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val NO_LISTADO = 0
        const val BLOQUEADO = 1
        const val PERMITIDO = 2
        private val MAGIA = "ADB1".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Descarga, valida, compila y guarda las listas. El filtro compilado se cachea en un archivo para arrancar rápido.
 * Todo el trabajo pesado va en un hilo aparte; los avisos al terminar se entregan en el hilo principal.
 */
object ListasManager {

    val CATALOGO = listOf(
        ListaDef("oisd_small", "OISD small", "~57 000", "https://small.oisd.nl/domainswild2"),
        ListaDef("adaway", "AdAway", "~6 000", "https://adaway.org/hosts.txt"),
        ListaDef("oisd_big", "OISD big", "~200 000", "https://big.oisd.nl/domainswild2"),
        ListaDef("hagezi_pro", "HaGeZi Pro", "~350 000", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt"),
        ListaDef("adguard_dns", "AdGuard DNS filter", "~100 000", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"),
        ListaDef("onehosts_lite", "1Hosts Lite", "~90 000", "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/hosts.txt"),
        ListaDef("stevenblack", "StevenBlack", "~150 000", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
        ListaDef("easylist", "EasyList", "solo dominios enteros", "https://easylist.to/easylist/easylist.txt"),
        ListaDef("easyprivacy", "EasyPrivacy", "solo dominios enteros", "https://easylist.to/easylist/easyprivacy.txt"),
        // Las que tenías añadidas por URL en AntiAds, ya incluidas para no tener que escribirlas.
        ListaDef("firebog_w3kbl", "Firebog w3kbl", "extra", "https://v.firebog.net/hosts/static/w3kbl.txt"),
        ListaDef("yoyo_adservers", "Yoyo adservers", "extra", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"),
        ListaDef("anudeep_adservers", "anudeepND adservers", "extra", "https://raw.githubusercontent.com/anudeepND/blacklist/master/adservers.txt"),
        ListaDef("hagezi_tif_medium", "HaGeZi TIF medium", "extra", "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/tif.medium.txt"),
        ListaDef("disconnect_malvertising", "Disconnect malvertising", "extra", "https://s3.amazonaws.com/lists.disconnect.me/simple_malvertising.txt"),
        ListaDef("hagezi_ultimate", "HaGeZi Ultimate", "extra · muy grande", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/ultimate.txt"),
        ListaDef("hagezi_pro_plus", "HaGeZi Pro++", "extra · muy grande", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.plus.txt"),
        ListaDef("hagezi_light", "HaGeZi Light", "extra", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt")
    )

    val POR_DEFECTO = setOf("oisd_small", "adaway")

    val filtro = FiltroListas()

    private const val LIMITE_BYTES = 80L * 1024L * 1024L
    private const val MIN_ENTRADAS = 50
    private const val DIA_MS = 24L * 60L * 60L * 1000L

    private val hilo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val enCurso = AtomicBoolean(false)
    private val cerrojo = Any()

    @Volatile
    private var app: Context? = null

    @Volatile
    private var ajustes: Ajustes? = null

    fun todas(): List<ListaDef> {
        val a = ajustes ?: return CATALOGO
        return CATALOGO + a.propias()
    }

    private fun activas(): List<ListaDef> {
        val a = ajustes ?: return emptyList()
        val ids = a.listasActivas()
        return todas().filter { ids.contains(it.id) }
    }

    private fun carpeta(c: Context): File {
        val d = File(c.filesDir, "listas")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun archivoLista(c: Context, id: String) = File(carpeta(c), "$id.txt")
    private fun archivoCache(c: Context) = File(carpeta(c), "filtro.bin")

    private fun redMedida(c: Context): Boolean = try {
        (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (e: Exception) {
        false
    }

    /** Texto para el título del menú de listas. */
    fun resumen(): String {
        val a = ajustes
        val total = filtro.totalDominios
        if (total == 0) return "Sin listas descargadas"
        val t = a?.ultimaActualizacion ?: 0L
        val fecha = if (t > 0L) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t)) else "—"
        return "$total dominios en listas · actualizado $fecha"
    }

    /** Al arrancar: carga el filtro desde la caché y, si toca (más de 24 h, red permitida), actualiza. */
    fun iniciar(contexto: Context, a: Ajustes) {
        app = contexto.applicationContext
        ajustes = a
        hilo.execute {
            val c = app ?: return@execute
            try {
                synchronized(cerrojo) {
                    if (!filtro.cargado) {
                        val t = filtro.leerCache(archivoCache(c))
                        if (t != null) filtro.reemplazar(t)
                        else if (activas().any { archivoLista(c, it.id).exists() }) compilar(c)
                    }
                }
            } catch (e: Throwable) {
                // Sin filtro de listas, sigue funcionando la lista integrada.
            }
            val ultima = a.ultimaActualizacion
            val permitido = !a.soloWifi || !redMedida(c)
            if (activas().isNotEmpty() && permitido && System.currentTimeMillis() - ultima > DIA_MS) {
                actualizar(false)
            }
        }
    }

    /**
     * Actualiza en segundo plano. [forzarCompilar] vuelve a compilar aunque ninguna lista haya cambiado
     * (al cambiar la selección de listas). [alTerminar] recibe un mensaje y se llama en el hilo principal.
     */
    fun actualizarAsync(forzarCompilar: Boolean, alTerminar: ((String) -> Unit)?) {
        hilo.execute {
            val msg = actualizar(forzarCompilar)
            if (alTerminar != null) principal.post { alTerminar(msg) }
        }
    }

    private fun actualizar(forzarCompilar: Boolean): String {
        val c = app ?: return "No iniciado"
        val a = ajustes ?: return "No iniciado"
        if (!enCurso.compareAndSet(false, true)) return "Ya hay una actualización en curso"
        try {
            val lista = activas()
            var ok = 0
            var err = 0
            val mensajes = ArrayList<String>()
            for (l in lista) {
                try {
                    if (descargar(c, a, l)) ok++
                } catch (e: Exception) {
                    err++
                    mensajes.add(l.nombre + ": " + (e.message ?: e.javaClass.simpleName))
                }
            }
            var dominios = filtro.totalDominios
            if (ok > 0 || forzarCompilar || !filtro.cargado) {
                synchronized(cerrojo) { dominios = compilar(c) }
            }
            if (lista.isEmpty() || err < lista.size) a.ultimaActualizacion = System.currentTimeMillis()
            return if (err == 0) "Listas al día · $dominios dominios"
            else "Actualizado con $err error(es): " + mensajes.joinToString("; ")
        } catch (e: OutOfMemoryError) {
            return "Memoria insuficiente: activa menos listas o evita las muy grandes"
        } catch (e: Exception) {
            return "Error: " + (e.message ?: e.javaClass.simpleName)
        } finally {
            enCurso.set(false)
        }
    }

    /** Devuelve true si la lista cambió, false si el servidor dijo «sin cambios». Lanza excepción si falla. */
    private fun descargar(c: Context, a: Ajustes, lista: ListaDef): Boolean {
        if (!lista.url.startsWith("https://")) throw IllegalArgumentException("solo se admiten URL https")
        val destino = archivoLista(c, lista.id)
        val conn = URL(lista.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Optimo/1.2")
            if (destino.exists()) {
                val etag = a.etag(lista.id)
                val mod = a.modificado(lista.id)
                if (etag != null) conn.setRequestProperty("If-None-Match", etag)
                if (mod != null) conn.setRequestProperty("If-Modified-Since", mod)
            }
            val codigo = conn.responseCode
            if (codigo == HttpURLConnection.HTTP_NOT_MODIFIED) return false
            if (codigo != HttpURLConnection.HTTP_OK) throw IllegalStateException("el servidor respondió $codigo")

            val tmp = File(destino.parentFile, lista.id + ".descarga")
            var bytes = 0L
            conn.inputStream.use { entrada ->
                tmp.outputStream().buffered().use { salida ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        bytes += n
                        if (bytes > LIMITE_BYTES) throw IllegalStateException("lista demasiado grande")
                        salida.write(buf, 0, n)
                    }
                }
            }
            val validas = contarEntradas(tmp)
            if (validas < MIN_ENTRADAS) {
                tmp.delete()
                throw IllegalStateException("no parece una lista válida ($validas entradas)")
            }
            if (!tmp.renameTo(destino)) {
                destino.delete()
                if (!tmp.renameTo(destino)) throw IllegalStateException("no se pudo guardar la lista")
            }
            a.setCabecerasCache(lista.id, conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun contarEntradas(f: File): Int {
        var n = 0
        f.useLines(Charsets.UTF_8) { lineas ->
            for (l in lineas) {
                if (ParserListas.procesarLinea(l) != null) {
                    n++
                    if (n >= MIN_ENTRADAS) break
                }
            }
        }
        return n
    }

    /** Procesa las listas activas, guarda la caché y cambia el filtro en caliente. Devuelve el nº de dominios. */
    private fun compilar(c: Context): Int {
        val bloqueo = ConjuntoHash64()
        val permitido = ConjuntoHash64()
        for (lista in activas()) {
            val f = archivoLista(c, lista.id)
            if (!f.exists()) continue
            f.useLines(Charsets.UTF_8) { lineas ->
                for (l in lineas) {
                    val e = ParserListas.procesarLinea(l) ?: continue
                    if (e.permitido) permitido.agregar(HashDominio.completo(e.dominio))
                    else bloqueo.agregar(HashDominio.completo(e.dominio))
                }
            }
        }
        val tablas = FiltroListas.Tablas(bloqueo, permitido)
        try {
            filtro.guardarCache(archivoCache(c), tablas)
        } catch (e: Exception) {
            // Sin caché el filtro funciona igual; solo tardará más en arrancar.
        }
        filtro.reemplazar(tablas)
        return bloqueo.tamano
    }
}
