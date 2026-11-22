# Pendientes de Optimo y Optimo Play

Estado (1.9.2, 1.10.0, 1.11.0, 1.11.7 y 1.16.0 aplican: oferta en YouTube y Wi-Fi/cierre en pausa del servicio): Optimo 1.7.2 y Optimo Play 0.2.1 entregados. Revisado contra 1.12.7: lo marcado HECHO está aplicado; el resto sigue pendiente.

## 1. Oferta de «Reproducir en Optimo Play» (Optimo) — HECHO (verificado en 1.12.2)
`ReproductorExterno.esDetectable` ya descarta youtube.com, youtu.be, youtube-nocookie.com y googlevideo.com y las rutas de interfaz `/s/...`, y un manifiesto `.m3u8`/`.mpd`
manda sobre un `.mp3`/`.mp4` suelto (`MainActivity`, shouldInterceptRequest). Sin hacer, a propósito: sondeo de longitud de medios minúsculos (exigiría una petición extra por recurso).
Reproducir YouTube dentro de Play: descartado (flujos firmados, cambiantes y contra sus condiciones de uso).

## 2. Eficiencia y batería (revisión estática, sin medir)
### Optimo Play (prioridad alta)
1. Cerrar el servicio tras ~10 min en pausa o terminado.
2. Pausar el torrent a los 1–2 min de pausa y limitar la subida durante el streaming (hoy sigue descargando y subiendo hasta cerrar).
3. `FLAG_KEEP_SCREEN_ON` solo con pista de vídeo y reproduciendo (hoy también con solo audio).
4. Wi-Fi: `WIFI_MODE_FULL` en vez de `FULL_HIGH_PERF` (obsoleto).
5. Refresco de la interfaz de 400 ms a 1 s con los controles ocultos.
### Optimo
1. Wi-Fi `WIFI_MODE_FULL` — HECHO (`ServicioSegundoPlano.tomarBloqueos`).
2. Soltar CPU/Wi-Fi tras una pausa — HECHO en 1.12.5: el servicio se cierra a los ~60 s en pausa (`MAX_CICLOS_EN_PAUSA` = 15 × 4 s).
3. Scripts de vigilancia de anuncios — HECHO: `skip_yt.js` (2 s en segundo plano) y `skip_generico.js` (2 s, y 4 s tras un rato sin anuncios).
4. Prioridad del renderer — parcial: `aplicarFondo` la mantiene mientras «Seguir sonando» esté activado, haya o no audio en ese momento. Medir antes de tocarla.
### Medir
`adb shell dumpsys batterystats` y la pantalla de batería; 30 min con pantalla apagada en cada app.

## 3. Con el logcat por wifi (cuando se pueda)
Solo si el segundo plano de YouTube vuelve a fallar en algún dispositivo: `adb logcat | grep -i -E "chromium|optimo|AudioManager"`.

## 4. Herramientas de desarrollo (Optimo 1.8.0)
Estado: implementadas y fusionadas con 1.7.5; el JS se probó en Chromium con Playwright, **el Kotlin no se ha compilado ni probado en dispositivo**.
- Compilar en Fusion y corregir lo que salga; probar en el móvil: consola, «🎯 Elegir», edición de atributos, Red (fetch/XHR) y Almacén.
- Experimento opcional («opción B»): conectar la app al protocolo real de Chrome (CDP) por el socket local del WebView para tener red con cuerpos
  de todo tipo, estilos exactos, puntos de ruptura y consola nativa. Primero hay que comprobar en el móvil si Android deja a la app conectarse
  a su propio socket de depuración; iría como ajuste desactivado por defecto.
- Hojas CSS de otro dominio (CORS): se podrían descargar desde la app para leer sus reglas en la pestaña Estilos.

## 5. Rastreador de enlaces («scrapper»)
Idea: buscar en una página o sitio enlaces de cierto tipo (magnet, Mega, `.torrent`, por extensión de archivo o expresión propia), con rastreo recursivo y profundidad configurable.
Punto de anclaje ya hecho en 1.12.8: `ProcesadorPagina` / `PaginasGuardadas.procesadores` (páginas que el usuario guarda a propósito; hilo de fondo).

### Fase 1 — extraer y listar — HECHO en 1.13.0 (pendiente de probar en el móvil)
- Quedan fuera de la 1.13.0: selección múltiple, búsqueda por texto dentro de la lista, enviar varios a Optimo Play, enlaces de páginas guardadas sin abrirlas.
- Fuente: la página abierta o una página guardada. Script en el WebView: `a[href]` + búsqueda por expresión regular en el texto (los enlaces de Mega suelen venir escritos como texto).
- Tipos (con filtros y contador por tipo): magnet, Mega, `.torrent`, listas `.m3u/.m3u8`, vídeo, audio, archivos comprimidos, PDF… más lista de extensiones o expresión propia. Reutilizar `ReproductorExterno.esMedio/esMagnet/esMega`.
- Lista: cada fila con dos líneas, **nombre** arriba y debajo «tipo · dominio · tamaño si lo hay» (el enlace recortado, se copia completo; origen de la página cuando haya rastreo). Búsqueda por texto, selección múltiple, copiar, compartir, exportar a archivo (selector del sistema), enviar magnets/Mega a Optimo Play.
- De dónde sale el nombre (se indica de cuál): magnets → `dn=` (y `xl=` para el tamaño); archivos con extensión → final de la URL decodificado; **Mega → el enlace no trae nombre** (el contenido va cifrado y no se puede consultar sin su SDK): texto del enlace, título cercano, fila o título de la página, marcado como «nombre sacado de la página»; otros → texto del enlace, `title`, `aria-label` o título de la página. Texto cercano limitado a pocas palabras.
- Deduplicar (magnets por infohash, URL normalizada).

### Fase 2 — rastreo recursivo — HECHO en 1.14.0 (pendiente de probar en el móvil)
- Decidido: sin dependencias (sin Jsoup) y sin reglas de ocultación de elementos en el rastreo HTTP; profundidad editable sin tope fijo, frenada por topes de páginas/tiempo; dominios bloqueados excluidos.
- Componente nuevo («rastreador»): cola, deduplicación de URL, profundidad 1–3, tope de páginas y de tiempo, pausa entre peticiones (concurrencia baja), cancelación; por defecto solo el mismo dominio; `robots.txt` opcional.
- Descarga directa por HTTP + análisis del HTML (ligera; comparte las cookies del navegador). Falla en sitios con JavaScript o Cloudflare/captcha.

### Mejora a considerar — reglas de ocultación en el rastreo
- Excluir también los enlaces que están dentro de elementos que las reglas de ocultación del usuario esconderían. Exige evaluar selectores CSS sobre el HTML: en HTTP necesitaría una librería (Jsoup, ~400 KB) o un evaluador propio de selectores simples; en la fase 3 (WebView oculto) saldría gratis.

### Fase 3 — sitios con JavaScript
- WebView oculto como alternativa (más memoria y batería), seguir dominios externos y opciones avanzadas.

### Límites conocidos
- Mega: se recogen los enlaces, no su contenido. Enlaces acortados o con anuncios: resolverlos exige seguir redirecciones y a veces captchas, sin garantías.
- Rastreo largo = batería y datos; con la pantalla apagada haría falta un servicio en primer plano (decisión pendiente: de momento solo con la app abierta).

### Decisiones pendientes
- Decididas: solo con la app abierta; mismo dominio por defecto y configurable; resultados primero a la vista y copiables, con opción de guardar lista.

## 6. Óptimo Inspector (auditoría de páginas) — v1 y v2 HECHAS en 1.15.0 (Kotlin sin compilar)
Pestaña «Auditoría» del panel de DevTools; ver README 1.15.0. Pendiente:
- **Compilar y probar en el móvil**: la pestaña, «Ver en Elementos» (salta a Elementos y abre el camino hasta el nodo), «Ocultar siempre», «Bloquear dominio» y «Modo lectura». Comprobar que `long-animation-frame` existe en el WebView del dispositivo (si no, solo hay TBT total).
- Contrastar las cifras con Lighthouse/PageSpeed en una misma página (los umbrales son los de Google, pero la medición es real, no simulada).
- Idea (v3): auditar automáticamente al terminar de cargar (opcional) con aviso si hay algo grave; historial por sitio para comparar antes/después de bloquear un dominio; «auditar sin bloqueo» en una carga (hoy lo bloqueado no entra en los tiempos).
- Idea: peso de terceros sin `Timing-Allow-Origin` (hoy desconocido) midiéndolo desde `shouldInterceptRequest`, solo si compensa la petición extra.
- Idea: hallazgos de caché (`Cache-Control`) y de fuentes (`font-display`), que hoy no se miden.

## Apuntado en 1.17.0 (no hecho)

- **Técnicas de bloqueo por sitio:** poder apagar solo algunas técnicas (scripts sustitutos, vídeo vacío de VAST, «de juguete», rutas, ocultar huecos, saltar anuncios) en un sitio concreto, sin pausar todo el bloqueo. Hoy se eligen en Ajustes y valen para todas las webs. Requiere pasar el sitio de la página a varios puntos del motor (`MotorAnuncios.interceptar`, los scripts de página con guarda como la de la pausa) y una pantalla «Técnicas en este sitio». Las excepciones por dominio (1.17.0) cubren el caso de dejar pasar un dominio concreto.

## Apuntado en 1.18.0 (por comprobar en el móvil)

- **Compilar y probar:** certificados (`Certificados.kt`) y retención/borrado por rango del historial.
- **Probar con un router o NAS de la red local con certificado autofirmado:** aviso con huella, «Solo esta sesión», «Recordar», candado ⚠, panel del certificado, revocar desde Ajustes y comprobar que **vuelve a preguntar** (depende de `clearSslPreferences()`).
- **Comprobar en un móvil con Android 8-9** que la huella se obtiene (usa la clave interna `x509-certificate` del `Bundle` de `SslCertificate.saveState`). Si falla, ahí no se podrá autorizar ningún certificado.
- **Comprobar** que `WebView.getCertificate()` devuelve datos en tu WebView y no está marcado como obsoleto en las versiones recientes de Android.
- **Idea:** mostrar la cadena completa de certificados (hoy solo el del servidor) y la huella de la clave pública (SPKI), que sobrevive a renovaciones del certificado en un mismo equipo.
- **Idea (no hecho a propósito):** excepciones para sitios de Internet. Hoy siguen bloqueados sin opción de saltarlos.

## Apuntado en 1.19.0

- **Probar:** bloquear desde el candado una emergente de anuncios, una página normal con historial y la única pestaña abierta; desbloquear desde el candado y desde «Mis dominios bloqueados».
- **Idea (no hecho a propósito):** página «Sitio bloqueado por ti · Desbloquear» al abrir a propósito un dominio bloqueado.

## Apuntado en 1.20.0

- **Probar:** cada ajuste por sitio en una web real (emergentes en los tres modos con un sitio de anuncios, cookies de terceros con un inicio de sesión que las necesite, imágenes, suspensión) y «Ajustes por sitio» (ver, restablecer).
- **Comprobar** que `setAcceptThirdPartyCookies` por navegación se respeta al saltar de un sitio a otro dentro de la misma pestaña.
- **Idea (no hecho):** audio al cambiar de pestaña por sitio, y mostrar en «Ajustes por sitio» también las reglas y los permisos.
