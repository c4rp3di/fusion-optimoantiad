# Optimo

Navegador Android ligero sobre el WebView del sistema (motor Chromium). Paquete `com.optimo.navegador`,
pensado para compilarse desde Fusion. Una sola dependencia (androidx.webkit); el resto son APIs del sistema.

## Funciones
- Pestañas normales y privadas (la privada usa un perfil de WebView aislado, con cookies, almacenamiento y caché propios que se destruyen al cerrar la última pestaña privada; no guarda historial y bloquea capturas de pantalla).
- Omnibox con busqueda (DuckDuckGo, Google, Bing, Brave, Ecosia) y HTTPS primero con respaldo a http.
- Marcadores, historial, pagina de inicio con accesos directos, sesion restaurada al reabrir.
- Descargas (DownloadManager), buscar en la pagina, compartir, abrir enlaces de otras apps y "compartir con Optimo".
- Bloqueo de anuncios/rastreadores por dominio mas las listas descargables de AntiAds (OISD, HaGeZi, AdAway, EasyList... en `Listas.kt`, con cache compilada) reglas propias (mantener pulsado un anuncio: ocultar elemento por sitio o bloquear dominio; `ReglasUsuario.kt`) y tecnicas extra portadas de AntiAds (`MotorAnuncios.kt`, `assets/anuncios/`): anuncio de juguete (MP4 valido), VAST vacio, scripts sustitutos GPT/IMA/AdSense, CSS de huecos, YouTube (datos de anuncios y saltador); se activan una a una en Ajustes, versión de escritorio, modo oscuro de paginas (experimental).
- Permisos de camara, microfono y ubicacion con confirmacion; subida de archivos; pantalla completa de video.
- Certificados no válidos: bloqueados sin opción de saltarlos (solo equipos de la red local, y tras confirmar).
- Sitios sin HTTPS: nunca se abren por http sin confirmación; descargas sin cifrar bloqueadas; contenido mixto bloqueado.
- Datos locales cifrados (marcadores, historial, sesión y reglas) con AES-256-GCM y clave en el Keystore (`AlmacenSeguro.kt`).
- Botón ⛶ en el extremo derecho de la barra de direcciones: página a pantalla completa (sin barras de Optimo ni del sistema); se sale pulsando atrás dos veces.
- Marcadores con carpetas anidadas: botón 🔖 junto a ⛶ (tocar abre; mantener pulsado edita, mueve, renombra o elimina). Mantén pulsada ☆ para guardar la página eligiendo nombre y carpeta. Al eliminar una carpeta, su contenido pasa a la carpeta superior.
- ✕ dentro de la barra de direcciones (al escribir) para borrar el texto de golpe.
- Compatible con el autorrelleno de La Bóveda (selecciónala como servicio de autorrelleno de Android).
- Herramientas de desarrollo en el móvil (el «F12» sin PC): menú ⋮ › «Herramientas de desarrollo». Ver 1.8.0.

## Firma
El workflow de Fusion genera `apk-build/debug.keystore` la primera vez que compila en el repositorio de este
proyecto y lo conserva: firma propia, distinta de cualquier otra app, estable entre builds.

## Estructura
`app/src/main/java/com/optimo/navegador/`: `MainActivity.kt` (interfaz, pestañas, menus, clientes WebView),
`Omnibox.kt`, `Silenciador.kt` (silenciar pestañas), `AppsMedia.kt` (apps externas para multimedia/magnets), `AdBlock.kt`, `Paginas.kt` (logica pura), `Almacen.kt` (marcadores, historial, ajustes), `AlmacenSeguro.kt` (cifrado local), `Marcadores.kt` (marcadores y carpetas), `Modelo.kt`, `ServicioSegundoPlano.kt` (reproducción con la pantalla bloqueada), `PanelDev.kt` / `DevAgente.kt` / `DevDatos.kt` (herramientas de desarrollo).


## 1.6.0 — Integración con Optimo Play

Si está instalada la app **Optimo Play** (`com.optimo.play`), Optimo le entrega el contenido multimedia:
- Descargas de vídeo/audio/.torrent: pregunta «Reproducir en Optimo Play» o «Descargar».
- Enlaces `magnet:`: se abren directamente en el reproductor.
- Pulsación larga sobre un enlace de vídeo/magnet/.torrent: «Reproducir en Optimo Play».
- Menú principal: «▶ Reproducir vídeo de la página» cuando se ha visto un flujo m3u8/mpd/mp4 (se manda con Referer y User-Agent).
Sin Optimo Play instalada, todo funciona como en 1.5.0.

## 1.7.0 — Reproducción en segundo plano

Ajustes › «Reproducción en segundo plano…» (cada opción se puede desactivar por separado):
- **Seguir sonando con la pantalla bloqueada** (YouTube): script de inicio (`assets/anuncios/segundo_plano.js`) que mantiene la página «visible»
  y silencia `visibilitychange`/`pagehide`/`blur`, más un servicio en primer plano de tipo mediaPlayback (`ServicioSegundoPlano.kt`) con
  notificación (Pausa/Cerrar), MediaSession y bloqueos de CPU/Wi-Fi. Arranca en `onPause` si suena audio y se cierra al volver a la app.
- **Imagen en imagen**: con un vídeo a pantalla completa, Inicio/Recientes lo pasa a PiP (`onUserLeaveHint`).
- Acceso directo a los ajustes de batería de la app (en Xiaomi y similares hay que dejarla en «Sin restricciones»).
Permisos nuevos: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.

## 1.7.1 — Descargas en tu gestor de archivos

Ajustes/menú › «Descargas» abre el gestor `com.carpe.gestorarchivos` en la carpeta Descargas (`GestorExterno.kt`), sin modificar el gestor:
usa un `ACTION_VIEW` con `content://com.android.externalstorage.documents/document/primary:Download`, que su `MainActivity` ya resuelve a una ruta.
Si el gestor no está instalado, se abre el gestor de descargas del sistema como antes.

## 1.7.2 — Segundo plano de YouTube más robusto

El síntoma en 1.7.1 era que el vídeo se paraba al instante al bloquear o cambiar de app: el script engañaba a la página, pero Chromium
marcaba el WebView como oculto y pausaba por su cuenta. Cambios (hipótesis, sin probar en dispositivo):
- `WebViewFondo.kt`: WebView que ignora el aviso de ventana oculta (`onWindowVisibilityChanged(GONE)`) solo mientras suena audio.
  Cuando el servicio se cierra, `ocultarAhora()` lo oculta de verdad. Prioridad del renderer «importante» aunque no esté visible.
- El servicio arranca si suena audio **o sonó hace menos de 2 s** (`AudioPlaybackCallback`), para no perder la carrera si la página se pausa al salir.
- `segundo_plano.js`: si la página pausa el vídeo en los 3 primeros segundos tras salir (sin pausa tuya desde la notificación), lo reanuda (máx. 3 veces).
- Sin cambios en la oferta de «Reproducir en Optimo Play» (queda para otra revisión).
Si sigue pausándose, hace falta un logcat con la pantalla bloqueada (`adb logcat | grep -i -E "chromium|optimo|AudioManager"`).

## 1.7.3 — Audio al cambiar de pestaña y carátula

- **Audio entre pestañas:** al abrir o cambiar de pestaña con audio sonando (se comprueba por JavaScript que haya un `video`/`audio` sonando, no en silencio),
  Optimo pregunta «Seguir sonando» / «Pausar» / «Siempre seguir». Ajustes › Reproducción en segundo plano › «Al cambiar de pestaña con audio»:
  preguntar (por defecto), seguir sonando o pausar.
  - La pestaña que sigue sonando conserva su WebView en pantalla (ver 1.7.4: en 1.7.3 se reducía a 1×1 px y transparente, y el vídeo se pausaba).
  - Solo una pestaña de fondo a la vez; si deja de sonar ~45 s se libera. Se marca con 🔊 en la lista de pestañas y al volver a ella recupera su tamaño.
  - La notificación y la pantalla de bloqueo controlan la pestaña que suena.
- **Carátula y datos:** el servicio pide cada ~12 s a la página su `navigator.mediaSession.metadata` (título, artista, miniaturas), con `og:image` o el póster del vídeo
  como respaldo. La imagen se descarga aparte (máx. 2 MB, reducida a ~512 px) y se pone en la sesión multimedia (pantalla de bloqueo, panel de control de Xiaomi)
  y como icono grande de la notificación. No se hace en pestañas privadas (se mantiene el título genérico). Si falla algo, queda la nota musical.
- Sin cambios en la oferta de «Reproducir en Optimo Play» (pendiente).

## 1.7.4 — Corrección del audio entre pestañas y diagnóstico

En 1.7.3 el aviso salía bien pero el vídeo se pausaba al cambiar de pestaña, y la carátula no aparecía.
- **Pestaña de fondo a tamaño completo:** ya no se reduce a 1×1 px ni se hace transparente (hipótesis: el cambio de tamaño/alfa hacía que la página o Chromium
  pausara el vídeo). Se queda como estaba, tapada por la pestaña nueva que se añade encima, que es lo más parecido al caso que sí funciona (salir de la app).
  Coste: la página de fondo sigue pintándose, algo más de batería mientras haya una pestaña sonando de fondo.
- **Carátula:** la primera petición de datos se hace ~0,8 s después de arrancar el servicio (antes ~4 s). La carátula solo aparece con el servicio en marcha
  (app fuera de pantalla con audio sonando); con la app abierta no hay servicio. Si el audio se pausaba por el fallo anterior, el servicio ni arrancaba.
- **Ajustes › Reproducción en segundo plano › «Diagnóstico del audio (copiar y enviar)»:** muestra el estado de los medios de la pestaña, el tamaño de la ventana,
  el registro de eventos pause/play/resize/ended (los anota `segundo_plano.js` en páginas de YouTube) y el estado de la carátula (datos recibidos, URL de la imagen,
  error de descarga). Sirve para saber, sin logcat, qué ocurre si vuelve a pausarse.

## 1.7.5 — Carátula del vídeo que suena

En 1.7.4 la carátula ya llegaba al panel de control y a la pantalla de bloqueo, pero era la de otro vídeo: YouTube navega sin recargar la página y su
`og:image` se queda con el primer vídeo cargado (y `mediaSession` no daba imagen).
- **YouTube:** la imagen sale ahora del identificador del vídeo de la URL actual (`v=`, `/shorts/`, `/embed/`, `/live/`, `youtu.be/`): primero `maxresdefault.jpg`
  (16:9 sin barras) y, si no existe, `hqdefault.jpg`. Si la página sí da `mediaSession.metadata.artwork`, manda esa imagen (con `hqdefault` de respaldo).
- **Otras webs:** `mediaSession` › póster del vídeo › `og:image`. En YouTube ya no se usa `og:image`.
- Si ninguna imagen sirve, se quita la anterior (mejor ninguna que la de otro vídeo).
- El título de respaldo (`document.title`) se limpia: sin « - YouTube» ni el contador «(3)».
- Los datos se consultan cada ~8 s (antes ~12 s) para que el cambio de canción se note antes.

## 1.8.0 — Herramientas de desarrollo (el «F12» del móvil)

Menú ⋮ › «Herramientas de desarrollo» abre un panel acoplado **debajo de la página** (la página sigue visible y se puede usar arriba).
⬍ alterna entre mitad y casi pantalla completa; ✕ o atrás lo cierra. Pestañas:
- **Consola**: errores y avisos de la página y del navegador (fallos de carga, CSP, contenido mixto, con archivo:línea), `console.*` con objetos
  legibles, y una línea para ejecutar JavaScript en la página (con historial ↑; muestra el resultado y espera promesas). Filtro por nivel y texto.
- **Elementos**: árbol DOM por niveles; «🎯 Elegir» (tocas un elemento de la página y se localiza en el árbol); el elemento elegido se resalta en la página.
  Detalle (selector CSS, atributos, caja, HTML), Estilos (en línea, reglas aplicadas, calculados con filtro) y edición en vivo: atributos, HTML,
  texto, estilo en línea, eliminar, y CSS inyectado.
- **Red**: fetch, XHR, WebSocket y recursos (scripts, CSS, imágenes, fuentes, documentos) con estado, tamaño y tiempo; cabeceras de petición y
  respuesta y cuerpo (texto/JSON) de fetch/XHR; peticiones **bloqueadas por Optimo** (⛔) y errores HTTP; copiar URL o cURL.
- **Almacén**: cookies (incluidas las HttpOnly, que JS no ve), localStorage y sessionStorage (ver, editar, borrar, añadir), IndexedDB, Cache Storage
  y service workers (solo lectura).
- **Info**: tiempos de carga, memoria, viewport, nodos, versión de WebView; **código fuente** de la página y de sus scripts/hojas de estilo.

Cómo funciona: un agente JS (`assets/devtools/agente.js`) vive en la página y la app le **pregunta** con `evaluateJavascript` (no hay
`addJavascriptInterface`, así que ninguna página puede llamar a la app). Lo que el agente no puede ver lo aporta la app: mensajes del WebView
(`onConsoleMessage`), bloqueos (`shouldInterceptRequest`), errores HTTP/red y cookies (`CookieManager`).
- Ajustes › «Herramientas de desarrollo: captura desde el inicio» (desactivado por defecto): inyecta el agente al inicio de cada documento para
  capturar consola y red desde el primer momento. Desactivado, el agente se inyecta al abrir el panel (la consola nativa y los bloqueos sí se
  registran siempre; fetch/XHR anteriores a abrir el panel no se ven, y los recursos sí por Resource Timing).
- Los datos viven solo en memoria de la pestaña (nada se guarda en disco) y el agente solo actúa en el documento principal, no en iframes.

Límites conocidos (frente al F12 de PC):
- Sin puntos de ruptura ni depurador paso a paso, ni pestañas de rendimiento/memoria detalladas.
- Reglas CSS de hojas de **otro dominio** sin CORS no se pueden leer desde JS (se avisa en Estilos); el shadow DOM y los iframes no se expanden.
- Cuerpo de respuesta solo para fetch/XHR de tipo texto, JSON o HTML (hasta 20 000 caracteres); no para imágenes ni scripts cargados por etiqueta.
- Peticiones de Web Workers o service workers no aparecen en Red.
- En páginas con CSP que prohíbe `eval`, el resultado de la consola se muestra como JSON sin formato.
- **No se ha podido compilar ni probar en dispositivo** (no hay SDK de Android en el entorno de desarrollo): el agente JS sí se probó con Node, el
  código Kotlin está revisado a mano. Si Fusion da errores de compilación o algo no se comporta, pasa el log y se corrige.

## 1.9.0 — Silenciar pestañas, pantalla de inicio editable y apps externas para multimedia

> 1.9.0: Optimo Play y las demás apps externas se lanzan con `FLAG_ACTIVITY_NEW_TASK` (tarea propia en Recientes, Optimo no se tapa).

**Silenciar pestañas** (`Silenciador.kt`, `assets/audio/silenciar.js`)
- Altavoz 🔊/🔇 en la barra de direcciones (junto a 🔖): aparece solo si la pestaña suena o está silenciada; tocar alterna.
- El candado ahora es un botón: «Silenciar esta pestaña / Activar el sonido», copiar dirección y compartir.
- Lista de pestañas: altavoz en cada fila que suena o está silenciada.
- El WebView no tiene «mute» propio: el script (inyectado al inicio de documento y en iframes) fuerza `muted` en `<video>`/`<audio>` (la página sigue viendo su
  valor, y se respeta si ya estaba en mute al quitar el silencio) y pasa Web Audio por un GainNode a 0. El estado se aplica en vivo y a las páginas siguientes de la pestaña.
- Límites: el silencio es por pestaña y no se guarda al cerrar la app; audio que no pasa por la página (p. ej. el vídeo nativo a pantalla completa de algunos sitios) puede escaparse.

**Pantalla de inicio editable** — Ajustes › «Pantalla de inicio…» (o el enlace «⚙ Personalizar» al pie de la propia pantalla)
- Colores: Automático, Claro, Oscuro, Negro (AMOLED), Océano, Bosque, Atardecer, Papel o Personalizado (fondo, texto, acento e iconos en #RRGGBB).
- Quitar los accesos/marcadores, o elegir su origen: marcadores recientes, lista propia (añadir/quitar, también la página actual) o los predeterminados; 4, 8 o 12 accesos; iconos redondos o cuadrados.
- Mostrar/ocultar el buscador, cambiar o quitar el título y el subtítulo, y «Restablecer el aspecto original».

**Vídeo, audio, torrents y magnets** — Ajustes › «Enlaces multimedia y magnets…» (`AppsMedia.kt`)
- Al tocar un enlace a vídeo/audio/m3u8/mpd/torrent, al descargarlo o al pulsar un magnet, Optimo pregunta: abrir en el navegador (como antes) u otra app
  (Optimo Play primero, luego las apps del dispositivo que lo admitan; los navegadores no se listan). Casilla «Recordar mi elección».
- Ajuste independiente para vídeo/audio/torrents (preguntar · en el navegador · siempre en la app X) y para magnets (preguntar · automático como antes · siempre en la app X).
  Si la app fija se desinstala, vuelve a preguntar. Si no hay ninguna otra app, no pregunta y funciona como antes.
- Pulsación larga en un enlace multimedia o magnet: «Abrir con otra app…»; menú ⋮ › «Reproducir vídeo de la página en otra app…».
- Manifiesto: nuevas `<queries>` (visibilidad de paquetes) para encontrar esas apps.
- Por defecto ambos ajustes están en «preguntar».

**No se ha podido compilar ni probar en dispositivo** (sin SDK de Android aquí): el script de silencio se probó con Node y simulaciones del navegador,
y las llaves/paréntesis de todo el Kotlin se comprobaron con un analizador propio; la compilación real queda para Fusion.

## 1.9.1 — Ventanas emergentes y redirecciones

Ajustes › «Ventanas emergentes y redirecciones…» (probado el caso de páginas de torrents, que secuestran cualquier toque para abrir anuncios):
- **Bloquear las de anuncios (por defecto):** una pestaña abierta por una página nace oculta; si su primera dirección es un dominio de las listas de bloqueo se cierra sin
  mostrarse y se vuelve a la pestaña de origen (si no, aparece en ≤1,5 s). Además, solo se permite una emergente por toque y una cada 4 s, y ninguna sin toque.
- **Estricto:** solo si el toque fue sobre un enlace y la pestaña va al mismo sitio que ese enlace (un enlace que abre otro sitio = toque secuestrado). Puede romper algún inicio de sesión.
- **Permitir todas:** como antes.
- **Redirecciones automáticas:** se bloquea que la propia pestaña navegue a un dominio de anuncios, o a otro sitio sin que hayas tocado nada en 5 s. Los redirects HTTP
  (acortadores, inicio de sesión) y los enlaces que tocas pasan.
- **Excepciones por sitio:** menú ⋮ › «Emergentes y redirecciones en este sitio» (y gestión en Ajustes).
- El menú ⋮ muestra cuántas se han bloqueado en la página. Límite: un anuncio de un dominio que no esté en ninguna lista y se abra con un toque en un enlace real no se puede distinguir de uno legítimo.

## Pendientes
Ver PENDIENTES-Optimo.md (oferta de Play en Optimo, eficiencia y batería).

## 1.9.2 — Ajustes de eficiencia y falsa oferta de vídeo

- «Reproducir vídeo de la página en otra app…» ya no se ofrece con recursos de `youtube.com`, `youtu.be`, `youtube-nocookie.com` ni `googlevideo.com`, ni con rutas de interfaz `/s/...` (`ReproductorExterno.esDetectable`).
- Servicio de segundo plano: bloqueo Wi-Fi `WIFI_MODE_FULL` (antes `FULL_LOW_LATENCY`/`FULL_HIGH_PERF`) y cierre tras ~80 s en pausa (antes ~5 min).
- Sin compilar ni probar en dispositivo (igual que 1.9.1). Siguen pendientes: sondeo de tamaño de medios, espaciar scripts de vigilancia y prioridad del renderer.

## 1.10.0 — Privacidad y datos

Ajustes › «Privacidad y datos…» (sustituye a «Borrar historial, caché y cookies»):
- **Qué se guarda:** «Guardar historial» y «Guardar pestañas abiertas al cerrar» se pueden desactivar (al desactivarlos se ofrece borrar lo ya guardado; las pestañas privadas nunca guardan nada).
- **Borrar por categoría:** historial, caché (muestra su tamaño), cookies y datos de sitios, o todo a la vez (también los permisos concedidos). Marcadores y reglas se conservan.
- **Permisos por sitio:** al pedir cámara, micrófono o ubicación aparece un tercer botón «Siempre en este sitio» (no en pestañas privadas). Esos permisos se listan en «Permisos concedidos a sitios» y se pueden revocar uno a uno. Si Android aún no ha concedido el permiso a Optimo, se sigue pidiendo como antes.
- **Por sitio:** menú ⋮ › «Privacidad de este sitio…»: revocar permisos, ver cuántas reglas de ocultar tiene y borrar sus cookies y almacenamiento local sin tocar los demás sitios (cookies del sitio y de su dominio base; es una limpieza «mejor esfuerzo»).
- Sin gestión cookie a cookie (de momento; ya se pueden borrar todas de golpe).
- **No se ha podido compilar ni probar en dispositivo.** Revisado a mano (llaves y paréntesis). Si Fusion da errores, pasa el log.
- Próximo: exportar e importar la configuración (cifrada con contraseña propia; la clave del Keystore no sale del móvil).

## 1.11.0 — Personalizar sitios y modo lectura

**Menú ⋮ › «Personalizar este sitio…»** (solo páginas http/https):
- **Elegir un elemento (tocar):** toca cualquier parte de la página; se resalta en naranja y puedes *ocultarlo*, *cambiar su apariencia* (ampliar/reducir, texto más grande, fondo oscuro/claro, quitar fondos, ancho completo, semitransparente, soltar barras fijas, quitar sombras y bordes, o CSS propio) y moverte entre el elemento, su contenedor (▲) y uno más pequeño (▼).
- **CSS propio de este sitio:** editor de CSS que se guarda y se aplica en cada visita (dominio y subdominios). El límite es 20 000 caracteres.
- **Reglas de este sitio:** ver y borrar las reglas (ocultar y estilos) de ese sitio. En Ajustes › «Reglas de sitios y anuncios» están todas, con los dominios bloqueados.
- Las reglas se aplican con hojas de estilo construibles (no las bloquea la CSP de las páginas) y **ya no dependen de tener activado el bloqueo de anuncios**.
- «Bloquear un anuncio (tocar)» y la pulsación larga siguen igual.

**Menú ⋮ › «Modo lectura»:** extrae el artículo (texto, títulos, listas, citas, imágenes) y lo muestra limpio sobre la página, en una vista aislada del CSS del sitio. Barra superior: ✕ salir, A− / A+ tamaño, Aa tipo de letra (serif/sans), ↕ interlineado, ◐ fondo (claro, sepia, oscuro, negro) y 🔊 lectura en voz alta (síntesis de voz de Android; usa el idioma de la página si el sistema lo tiene). Tus preferencias se recuerdan. Atrás también sale. Si la página no parece un artículo, avisa.
- La extracción es heurística (sin librerías): bien en blogs y noticias; puede fallar en webs muy raras o aplicaciones.
- La voz sigue mientras la app esté viva en segundo plano, pero no hay servicio ni controles de notificación para ella.

**Pruebas:** los tres scripts (lectura, elegir elemento, CSS con CSP estricta) se probaron en Chromium con Playwright sobre páginas de ejemplo. El Kotlin **no se ha compilado ni probado en dispositivo**: revisado a mano (llaves y paréntesis). Si Fusion da errores, pasa el log.

**1.11.1:** corrige un error de compilación en `Lectura.kt` (tipo nulo al leer el script de lectura).
