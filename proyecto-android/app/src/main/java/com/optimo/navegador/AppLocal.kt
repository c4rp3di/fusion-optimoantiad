package com.optimo.navegador

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/** Una aplicación web local conocida: su espacio de datos, el último archivo con que se abrió, cuándo y la copia guardada (optimo://local/…, o vacío). */
class AppRegistrada(val espacio: String, val nombre: String, val ultima: Long, val copia: String)

/**
 * «Aplicación web» (Optimo 1.24.0): un .html del dispositivo abierto como si fuera una web, con su propio origen https, para que funcionen
 * IndexedDB, localStorage, la red y el resto de APIs de un navegador normal. Solo se activa archivo a archivo, tras confirmar.
 *
 * Cómo funciona: el archivo no se abre con file:// sino con una dirección falsa `https://app-<espacio>.optimo-local.invalid/<nombre>.html`.
 * `.invalid` es un dominio reservado que nunca resuelve: si la intercepción fallara, la petición no llegaría a ningún servidor.
 * `ClienteWeb.shouldInterceptRequest` responde con el contenido del archivo (solo a la pestaña que lo abrió). Como el origen es https,
 * la página es un contexto seguro y su almacenamiento persiste entre aperturas. Todos los archivos con el mismo «espacio» comparten
 * origen y por tanto datos (así una versión nueva de la misma aplicación sigue viendo los datos de la anterior).
 */
object AppLocal {

    const val SUFIJO = ".optimo-local.invalid"

    /** Regla de origen (addDocumentStartJavaScript / addWebMessageListener) que cubre todos los espacios. */
    const val REGLA_ORIGEN = "https://*$SUFIJO"

    /** Nombre del objeto que ve la página (solo en estos orígenes) para las descargas blob:. */
    const val NOMBRE_PUENTE = "optimoDescarga"

    private val servidos = HashMap<String, File>()

    // ------------------------------------------------------------------ registro de aplicaciones (Guardados › Aplicaciones)

    private var registro: SharedPreferences? = null

    fun iniciarRegistro(c: Context) {
        registro = c.getSharedPreferences("optimo_apps", Context.MODE_PRIVATE)
    }

    /** Formato guardado: «espacio|nombre|última|copia» (el nombre solo lleva letras, dígitos y . _ -, y la copia no lleva «|»). */
    private fun leerRegistro(): MutableList<AppRegistrada> {
        val r = ArrayList<AppRegistrada>()
        for (e in registro?.getStringSet("apps", emptySet()) ?: emptySet()) {
            val p = e.split('|')
            if (p.size < 3) continue
            val esp = limpiarEspacio(p[0])
            if (esp.isEmpty()) continue
            r.add(AppRegistrada(esp, p[1], p[2].toLongOrNull() ?: 0L, if (p.size > 3) p[3] else ""))
        }
        return r
    }

    private fun guardarRegistro(l: List<AppRegistrada>) {
        registro?.edit()?.putStringSet("apps", l.take(100).map { it.espacio + "|" + it.nombre + "|" + it.ultima + "|" + it.copia }.toSet())?.apply()
    }

    /** Las aplicaciones conocidas, la más reciente primero. */
    fun registradas(): List<AppRegistrada> = leerRegistro().sortedByDescending { it.ultima }

    /** Anota que se abrió [espacio] con el archivo [nombre]. Si [copia] va vacía se conserva la que ya hubiera. */
    fun anotar(espacio: String, nombre: String, copia: String) {
        val l = leerRegistro()
        val antes = l.firstOrNull { it.espacio == espacio }
        l.removeAll { it.espacio == espacio }
        l.add(AppRegistrada(espacio, nombre, System.currentTimeMillis(), if (copia.isNotEmpty()) copia else (antes?.copia ?: "")))
        guardarRegistro(l.sortedByDescending { it.ultima })
    }

    fun actualizarCopia(espacio: String, copia: String) {
        val l = leerRegistro()
        val i = l.indexOfFirst { it.espacio == espacio }
        if (i < 0) return
        val x = l[i]
        l[i] = AppRegistrada(x.espacio, x.nombre, x.ultima, copia)
        guardarRegistro(l.sortedByDescending { it.ultima })
    }

    fun olvidar(espacio: String) {
        val l = leerRegistro()
        l.removeAll { it.espacio == espacio }
        guardarRegistro(l)
    }

    // ------------------------------------------------------------------ nombres

    /** Letras y dígitos en minúscula separados por guiones, máx. 40 (cabe en una etiqueta DNS de 63 con el prefijo). */
    fun limpiarEspacio(texto: String): String =
        texto.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')

    /** Espacio sugerido: el nombre del archivo sin extensión ni números de versión («Fusion-25-compila.html» → «fusion-compila»). */
    fun espacioPorDefecto(nombreArchivo: String): String {
        val base = nombreArchivo.substringBeforeLast('.').lowercase().replace(Regex("[0-9]+([._][0-9]+)*"), " ")
        return limpiarEspacio(base).ifEmpty { "aplicacion" }
    }

    /** Nombre apto para una ruta: solo letras, dígitos y . _ - ; siempre acaba en .html/.htm/.xhtml. */
    fun nombreSeguro(nombre: String): String {
        var n = nombre.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.').take(60)
        if (n.isEmpty()) n = "app"
        val m = n.lowercase()
        if (!(m.endsWith(".html") || m.endsWith(".htm") || m.endsWith(".xhtml"))) n += ".html"
        return n
    }

    fun host(espacio: String): String = "app-$espacio$SUFIJO"

    fun esHostApp(host: String?): Boolean = host != null && host.endsWith(SUFIJO)

    // ------------------------------------------------------------------ servir el archivo

    /** Registra [archivo] bajo el espacio y devuelve la dirección https con la que se carga. */
    fun registrar(espacio: String, nombre: String, archivo: File): String {
        val h = host(espacio)
        val ruta = "/" + nombreSeguro(nombre)
        synchronized(servidos) { servidos[h + ruta] = archivo }
        return "https://$h$ruta"
    }

    /** Quita el registro solo si sigue apuntando a este archivo (otra pestaña pudo reemplazarlo). */
    fun liberar(url: String, archivo: File) {
        val u = try { Uri.parse(url) } catch (e: Exception) { return }
        val clave = (u.host ?: "") + (u.path ?: "")
        synchronized(servidos) {
            if (servidos[clave]?.absolutePath == archivo.absolutePath) servidos.remove(clave)
        }
    }

    /** ¿Este archivo de la caché lo está sirviendo alguna pestaña? (para no borrarlo al limpiar la caché). */
    fun enUso(archivo: File): Boolean = synchronized(servidos) { servidos.values.any { it.absolutePath == archivo.absolutePath } }

    /** Respuesta para una petición a un origen de aplicación web: el archivo registrado, o 404 para cualquier otra ruta. */
    fun respuesta(url: Uri): WebResourceResponse {
        val clave = (url.host ?: "") + (url.path ?: "")
        val archivo = synchronized(servidos) { servidos[clave] }
        if (archivo != null && archivo.exists()) {
            try {
                return WebResourceResponse(
                    "text/html", "utf-8", 200, "OK",
                    mapOf("Cache-Control" to "no-store"),
                    FileInputStream(archivo)
                )
            } catch (e: Exception) {
                // cae al 404
            }
        }
        return WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    // ------------------------------------------------------------------ descargas blob:

    /**
     * Script de inicio de documento (solo en estos orígenes). Una descarga blob: llega al WebView como una URL blob: sin nombre, y la
     * página suele revocarla enseguida: se recuerdan los últimos blobs creados (30 s tras revocarlos) y el nombre del último `<a download>`.
     */
    val GUION_INICIO: String = """
(function(){
  if(window.__optimoBlobs)return;
  var mapa=new Map();window.__optimoBlobs=mapa;window.__optimoDl={n:'',t:0};
  var crear=URL.createObjectURL,revocar=URL.revokeObjectURL;
  URL.createObjectURL=function(o){
    var u=crear.apply(URL,arguments);
    try{ if(o&&typeof o.size==='number'){ mapa.set(u,o); if(mapa.size>6){ mapa.delete(mapa.keys().next().value); } } }catch(e){}
    return u;
  };
  URL.revokeObjectURL=function(u){
    try{ setTimeout(function(){ mapa.delete(u); },30000); }catch(e){}
    return revocar.apply(URL,arguments);
  };
  function nota(a){ try{ if(a&&a.download){ window.__optimoDl={n:String(a.download),t:Date.now()}; } }catch(e){} }
  var clic=HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click=function(){ nota(this); return clic.apply(this,arguments); };
  document.addEventListener('click',function(e){ var a=e.target&&e.target.closest?e.target.closest('a[download]'):null; nota(a); },true);
})();
"""

    /** Script que se ejecuta en la página cuando Optimo recibe la descarga: pasa el blob a trozos, con confirmación de cada uno. */
    fun guionDescarga(token: String, blobUrl: String, nombre: String): String = """
(function(){
  var T=${JSONObject.quote(token)},U=${JSONObject.quote(blobUrl)},N=${JSONObject.quote(nombre)};
  var P=window.${NOMBRE_PUENTE};
  if(!P)return;
  function enviar(m){ m.t=T; P.postMessage(JSON.stringify(m)); }
  var b=window.__optimoBlobs&&window.__optimoBlobs.get(U);
  var nombre=N||((window.__optimoDl&&Date.now()-window.__optimoDl.t<15000)?window.__optimoDl.n:'');
  (b?Promise.resolve(b):fetch(U).then(function(r){return r.blob();})).then(function(blob){
    var total=blob.size,pos=0,CH=786432;
    P.onmessage=function(ev){ if(ev.data==='ok')paso(); else P.onmessage=null; };
    enviar({a:'ini',n:nombre,m:blob.type||'application/octet-stream',s:total});
    function paso(){
      if(pos>=total){ P.onmessage=null; enviar({a:'fin'}); return; }
      var parte=blob.slice(pos,Math.min(pos+CH,total)); pos+=CH;
      var fr=new FileReader();
      fr.onload=function(){ var d=String(fr.result); enviar({a:'dat',d:d.substring(d.indexOf(',')+1)}); };
      fr.onerror=function(){ enviar({a:'err'}); };
      fr.readAsDataURL(parte);
    }
  }).catch(function(){ enviar({a:'err'}); });
})();
"""
}

/**
 * Escribe un archivo en la carpeta Descargas del dispositivo, trozo a trozo (se llama desde un hilo de fondo).
 * Android 10+: MediaStore (sin permisos); antes: carpeta pública Descargas (permiso de almacenamiento ya concedido).
 */
class EscritorDescarga(private val c: Context, nombre: String, val mime: String) {

    val nombre: String = limpiar(nombre, mime)
    private var salida: OutputStream? = null
    private var uri: Uri? = null
    private var archivo: File? = null

    /** Bytes escritos hasta ahora (se actualiza desde el hilo de escritura). */
    @Volatile var bytes: Long = 0L
        private set

    /** Dónde quedó el archivo: la dirección de MediaStore (Android 10+) o la ruta del archivo (antes). */
    val ubicacion: String get() = uri?.toString() ?: archivo?.absolutePath ?: ""

    private fun limpiar(n: String, mime: String): String {
        var r = n.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(120)
        if (r.isEmpty()) r = "descarga"
        if (!r.contains('.')) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (!ext.isNullOrEmpty()) r += ".$ext"
        }
        return r
    }

    fun abrir(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val valores = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, nombre)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores) ?: return false
                uri = u
                salida = c.contentResolver.openOutputStream(u)
                salida != null
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                var f = File(dir, nombre)
                var n = 1
                val base = nombre.substringBeforeLast('.', nombre)
                val ext = if (nombre.contains('.')) "." + nombre.substringAfterLast('.') else ""
                while (f.exists()) {
                    f = File(dir, "$base ($n)$ext")
                    n++
                }
                archivo = f
                salida = FileOutputStream(f)
                true
            }
        } catch (e: Exception) {
            abortar()
            false
        }
    }

    fun escribir(datos: ByteArray) {
        salida!!.write(datos)
        bytes += datos.size
    }

    /** Cierra y publica el archivo. */
    fun cerrar(): Boolean {
        return try {
            salida?.flush()
            salida?.close()
            salida = null
            val u = uri
            if (u != null) {
                val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                c.contentResolver.update(u, v, null, null)
            }
            true
        } catch (e: Exception) {
            abortar()
            false
        }
    }

    /** Descarta lo escrito hasta ahora. */
    fun abortar() {
        try { salida?.close() } catch (e: Exception) { }
        salida = null
        try {
            val u = uri
            if (u != null) c.contentResolver.delete(u, null, null)
            archivo?.delete()
        } catch (e: Exception) { }
        uri = null
        archivo = null
    }
}
