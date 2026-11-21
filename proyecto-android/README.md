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
- (El menú «Diagnóstico del audio» de esta versión se quitó en 1.11.5.)

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

## 1.11.2 — Voz del modo lectura

- **Causa probable de que 🔊 no hablara:** el manifiesto no declaraba la consulta `android.intent.action.TTS_SERVICE`; desde Android 11 sin ella la app no ve el motor de voz instalado y la síntesis no arranca. Añadida en `<queries>`.
- Si la voz sigue sin arrancar, ahora avisa con un mensaje (antes fallaba en silencio).
- Nuevo **Ajustes › Lectura y voz…**: velocidad y tono de la voz, «Probar la voz», acceso a los ajustes de voz de Android (motor e idioma), y el aspecto por defecto del modo lectura (tamaño, fondo —incluido «automático»—, tipo de letra e interlineado).
- Sin compilar ni probar en dispositivo.

## 1.11.3 — Quitar capa que tapa la página

- Menú ⋮ › **«Quitar capa que tapa la página»**: busca capas a pantalla completa (muros del tipo «desactiva tu bloqueador», modales, iframes fijos, también en Shadow DOM cerrado) y las lista. Puedes ocultarlas **solo ahora** o **siempre en este sitio** (queda como regla de ocultar) y desbloquea el desplazamiento si la página lo bloqueó (`overflow:hidden`). Lo guardado aparece en «Reglas de este sitio».
- El selector de elementos de «Personalizar este sitio» ya no falla cuando lo que se toca es `html`/`body` pero hay elementos encima (usa la pila de elementos del punto).
- Límite: si la página genera nombres de clase aleatorios en cada carga, la regla guardada puede no volver a coincidir; entonces hay que repetir «Solo ahora».
- Probado en Chromium con muros simulados (capa fija que se recrea, Shadow DOM cerrado, iframe fijo); sin probar en la web real ni en dispositivo, y sin compilar el Kotlin.

## 1.11.4 — «Quitar capa» también detecta avisos dentro de la página

- Algunos avisos «Adblocker detectado» no son una capa fija sino una tarjeta dentro del flujo de la página (caso de clksz.com). «Quitar capa que tapa la página» ahora también los busca por su texto («adblock», «bloqueador de anuncios»…), elige la tarjeta más ajustada y la marca por defecto si tiene botones o enlaces. Un párrafo que solo menciona el tema no se marca.
- Se evita listar dos veces la misma capa.
- Probado en Chromium con una tarjeta simulada como la de la captura; en la web real y en el móvil, sin probar.

## 1.11.5 — Posición y duración en la sesión multimedia; se quita el diagnóstico

- La pantalla de bloqueo y el panel de control muestran ahora la **barra de progreso** (posición y duración) de lo que suena. Se leen del `<video>`/`<audio>` de la página (el que está reproduciendo, o el primero) y la barra avanza sola entre actualizaciones.
- Se puede **arrastrar la barra** para buscar: Optimo coloca `currentTime` del vídeo de la página. En directos o cuando la duración es desconocida no hay barra de búsqueda.
- Se elimina el diagnóstico del audio (menú, registro `__optimoLog` en `segundo_plano.js` y `ServicioSegundoPlano.diag`).
- Probado el JS con un DOM simulado; el Kotlin, sin compilar con el SDK y sin probar en el móvil.

## 1.11.6 — Pausar el bloqueo en un sitio

- Menú ⋮ › **«Bloqueo de anuncios en este sitio: activo (pausar)»**: pausa o reanuda el bloqueo solo en ese dominio (y sus subdominios) y recarga la pestaña. En un sitio pausado no se bloquean peticiones ni actúan los scripts de página de Óptimo (ocultar huecos, saltar anuncios, datos de anuncios de YouTube). El bloqueo normal sigue en el resto de sitios.
- Tus **reglas propias** (ocultar elementos, estilos) se siguen aplicando en un sitio pausado.
- Ajustes › Privacidad y datos › **«Sitios con el bloqueo de anuncios pausado»**: lista para reanudar cada uno.
- El menú muestra la opción aunque el bloqueo global esté desactivado solo si el sitio está pausado (para poder reanudarlo).
- Límite: en la primera petición tras un cambio de dirección dentro de una pestaña puede usarse todavía el sitio anterior para decidir; recargar lo corrige.
- Probada la lógica de los scripts en Chromium; Kotlin sin compilar con el SDK ni probado en el móvil.

## 1.11.7 — Menos trabajo de fondo en los scripts de saltar anuncios

- `skip_yt.js` y `skip_generico.js` pasan de `setInterval` fijo a una cadencia **adaptable**: 500 ms (YouTube) / 1 s (resto) con la app delante; **2 s en segundo plano** (usan el indicador `__optimoBg` que ya pone Optimo al salir); y se espacian hasta 1,5 s (YouTube) / 4 s (resto) cuando llevan un rato sin encontrar anuncios, volviendo a la cadencia normal al haber cambios en la página o un anuncio.
- Menos CPU y menos despertares en pestañas abiertas mucho tiempo y en reproducción con la pantalla apagada.
- **No se toca la prioridad del renderer** (`RENDERER_PRIORITY_IMPORTANT`): bajarla sin probarla en un móvil podría cortar el audio de fondo, y la ganancia esperada es pequeña. Queda como pendiente a medir.
- Ya aplicado antes: Wi-Fi `WIFI_MODE_FULL` y cierre del servicio a los ~80 s en pausa (1.9.2).
- JS probado en Chromium; sin medir consumo real (falta la comparación de batería con `dumpsys batterystats`).

## 1.12.0 — DevTools: guardar lo que haces como regla del sitio

- **Elementos › ✎ Editar** (menú de edición del elemento seleccionado):
  - **★ Eliminar y recordar en este sitio…**: guarda una regla de *ocultar* con un selector corto y estable (id o clases; posicional si no hay otra forma) y muestra **a cuántos elementos afecta** antes de guardar. También hay un botón «Recordar en el sitio» en el diálogo de «Eliminar elemento». Se oculta con CSS, no quitándolo del DOM, para no romper los scripts de la página.
  - **★ Guardar estilo en este sitio…**: escribes las propiedades (p. ej. `color: red;`) y se guarda como regla de estilo (con `!important`).
  - **Inyectar CSS en la página…**: tras aplicarlo pregunta si guardarlo como **CSS propio del sitio**.
  - **Copiar XPath** y **Duplicar elemento** (nuevos).
- Lo guardado es lo mismo que «Personalizar este sitio»: aparece en «Reglas de este sitio» y se aplica en cada visita. Editar texto, HTML o atributos **no** se guarda (las páginas repintan esos nodos): siguen siendo temporales.
- **Info:** ahora muestra el contenido principal (LCP) y los saltos de diseño (CLS), y el botón **«Diagnóstico»** copia un informe para compartir: datos de la página, errores y avisos de la consola y peticiones bloqueadas o fallidas. No incluye cookies, cabeceras, cuerpos ni parámetros de las URL.
- **Pestañas privadas:** no se guardan reglas ni pausas de bloqueo desde ellas (se guardarían en disco con el nombre del sitio). Nota: «Bloquear un anuncio (tocar)» y la pulsación larga siguen guardando reglas también en privadas, como antes.
- Agente (`agente.js`): nuevas funciones `selGuardar`, `xpath` y `duplicar`; observadores de LCP y CLS. Probadas en Chromium (selector, XPath, duplicado, métricas). El Kotlin del panel, sin compilar con el SDK ni probado en el móvil.
- No incluido todavía: modo sencillo y Óptimo Inspector (auditoría), inspector CSS de reglas aplicadas con casillas, red avanzada y HAR (ver `Optimo-DevTools-plan.md`, entregas 1.13 y 1.14).

## 1.12.1 — DevTools: diagnóstico sin fugas, pausa del agente y mejoras del selector

- **Diagnóstico sin fugas.** El botón «Diagnóstico» de la pestaña Info copiaba la URL de la página completa (con `?consulta` y `#fragmento`, donde suelen ir tokens) y el título, y los mensajes de consola sin tocar, aunque decía no incluir parámetros. Ahora la URL de la página y las de red salen **sin credenciales (`usuario:clave@`, `token@`), sin consulta y sin fragmento**; el título y los mensajes de consola pasan por una redacción de secretos (Bearer/Basic, JWT, `password:`/`token=`…, cadenas largas con aspecto de clave y parámetros sensibles de URL) y salen como `[redactado]`. El informe ya no se arrastra de una página o pestaña anterior.
- **Pausa del agente.** Al cerrar el panel (y si «captura desde el inicio» está desactivada) el agente de la pestaña deja de registrar consola y red y deja pasar todo; se reanuda al abrir el panel. Solo afecta a la pestaña donde se cerró.
- **Selector de elementos:** el resaltado en la página muestra ahora una etiqueta con el tipo y las **dimensiones** (`<button#btn.caja> 120×40`), y una barra de **jerarquía** bajo el árbol (`html › body › div.caja › p`).
- **XPath:** ahora funciona con elementos SVG/MathML (`*[local-name()="circle"]`) y con ids que contienen comillas, que antes no se resolvían.
- Probado en Chromium: XPath (HTML, SVG, comillas), pausa/reanudación, etiqueta y regresión de consola, red, DOM, edición y selección táctil; los patrones de redacción, con `java.util.regex`. El Kotlin, sin compilar con el SDK ni probado en el móvil.
- Se mantiene todo lo de 1.12.0 (guardar reglas, duplicar, LCP/CLS).

## 1.12.2 — DevTools: estilo rápido, sin teclear

Pensado para el móvil: cambiar el aspecto de un elemento y guardarlo como regla del sitio **sin escribir nada**.
- **🎨 Estilo rápido** (Elementos › Editar › «🎨 Estilo rápido», o el botón 🎨 de la pantalla Estilos): una pantalla dentro del panel, con la página
  visible arriba, donde cada ajuste es un toque y se ve en vivo: color del texto y del fondo (paleta), letra más grande/pequeña (±2 px), negrita,
  alineación, margen y relleno (±8 px o ninguno), opacidad, ancho/alto, quitar la fijación de una barra (`position: static`), bordes, esquinas y sombra.
  Los ajustes relativos se calculan sobre el valor real del elemento y se acumulan (A+ dos veces = +4 px).
- **💾 Guardar** guarda como regla del sitio (la misma de «Reglas de este sitio») **solo lo que tú cambiaste**: el agente anota los cambios de estilo
  hechos desde el inspector (no el `style` que ya traía la página). Antes de guardar se muestra la regla, el selector estable y a cuántos elementos afecta.
- **↶ Deshacer** devuelve el elemento a como estaba (si no tenía `style`, se quita el atributo).
- «★ Guardar estilo en este sitio…» ya no pide escribir la propiedad: guarda los cambios hechos con Estilo rápido (o con «Añadir estilo en línea»,
  que sigue existiendo para quien quiera escribir) y, si aún no hay cambios, abre Estilo rápido.
- Probado en Chromium: ajustes acumulativos, mínimo 0, solo se registra lo cambiado, la regla guardada reproduce el aspecto sin estilos en línea,
  deshacer con y sin `style` previo. El Kotlin, sin compilar con el SDK ni probado en el móvil.


## 1.12.3 — Centro de privacidad por sitio (ampliado)

Sobre lo que ya existía en «Privacidad de este sitio…» (revocar permisos, borrar cookies y almacenamiento del sitio), sin servicios ni sondeos nuevos:
- **Permisos temporales.** En los avisos de cámara, micrófono y ubicación, «Recordar…» ofrece **1 hora, 24 horas o siempre**. Los temporales caducan solos (se descartan al consultarlos, sin temporizadores) y se ven con su tiempo restante en la privacidad del sitio. Cerrar el aviso sin elegir deniega.
- **Cookies del sitio una a una:** lista de nombres (el valor no se muestra) y borrado individual, sin cerrar el resto de la sesión.
- **Almacenamiento de la página abierta:** cuenta de localStorage, sessionStorage, IndexedDB y Cache Storage, con borrado de cada uno. Solo de la página abierta: Android no deja listar el de otros sitios.
- **Rastreadores bloqueados** de la página, por dominio (últimos 500 bloqueos registrados).
- **🛡 Acceso desde el candado** de la barra de direcciones.
- Límites: la caché HTTP de WebView es global (no se puede borrar por sitio) y el borrado de una cookie concreta es aproximado (dominio y ruta). Pestañas privadas: no aplica (su perfil se destruye al cerrar).
- El Kotlin, sin compilar con el SDK ni probado en el móvil.

## 1.12.4 — Gestor de pestañas básico

Todo dentro de la lista de pestañas (botón de pestañas), sin servicios ni tareas periódicas:
- **Buscar**: campo en la parte superior que filtra por título o dirección mientras escribes (varias palabras = todas deben coincidir).
- **Selección múltiple**: «☑ Seleccionar» marca pestañas con un toque; «Todas» marca las que se ven con el filtro (buscar «youtube» + Todas + Cerrar) y «Cerrar (n)» las cierra de golpe.
- **↩ Cerradas**: las últimas 10 pestañas normales cerradas se pueden reabrir desde la lista de pestañas o desde el menú ⋮ («Reabrir pestaña cerrada»). Solo se guardan **en memoria** (nunca en disco), no se anotan las pestañas privadas ni las emergentes bloqueadas, no se anota nada con «Guardar historial» desactivado y la lista se vacía al borrar el historial o los datos.
- Mientras se selecciona se ocultan 🔊/✕ de cada fila para evitar cierres accidentales.
- Pendiente de una entrega aparte (más delicada): suspender pestañas inactivas, agrupar por categorías. La memoria por pestaña no se puede medir con fiabilidad en WebView, así que no se ofrece.
- El Kotlin, sin compilar con el SDK ni probado en el móvil.

## 1.12.5 — Suspender pestañas inactivas y ajuste fino de segundo plano

- **Suspender pestañas inactivas** (Ajustes › Segundo plano › «Suspender pestañas inactivas»: desactivado / 15 min / 30 min / 1 h; **desactivado por defecto**). Se destruye el WebView de la pestaña y se conserva su historial, su posición de scroll y los datos de formulario que guarda Chromium (`saveState`, solo en memoria); al volver a ella se restaura con `restoreState` y, si no se pudiera, se recarga su dirección. En la lista de pestañas aparece con 💤.
  - **Nunca se suspende:** la pestaña activa, la que sigue sonando, la que se lee en voz alta, las privadas, la página de inicio, las que están cargando ni las que tienen **audio sonando, texto escrito sin enviar** (formularios y campos editables) o **un vídeo largo empezado** (se comprueba en la página justo antes de suspender).
  - **Sin temporizadores:** solo se revisa al cambiar de pestaña, al ocultar la app y cuando el sistema avisa de poca memoria (entonces basta con 2 min sin usar la pestaña).
  - Límites: se pierde el estado que solo vive en el JavaScript de la página (una aplicación web abierta, un chat en tiempo real) y la posición de un vídeo; por eso el ajuste es opcional.
- **Servicio de segundo plano:** el servicio se cierra tras ~1 min en pausa (antes ~80 s) y se corrige el comentario obsoleto de «5 min».
- **`skip_generico.js`:** en segundo plano pasa de 2 s fijos a 4 s cuando lleva rato sin encontrar botones de «saltar anuncio».
- Probado en Chromium: la comprobación previa a suspender (vacía, formulario limpio/sucio, textarea, casilla, campo editable con y sin texto, campo oculto, vídeo largo empezado). El Kotlin, sin compilar con el SDK ni probado en el móvil.

## 1.12.6 — Copia de seguridad y borrar datos al salir

- **Copia de seguridad** (Ajustes › «Copia de seguridad…»), siempre con una acción manual y con el selector de archivos del sistema (sin permisos nuevos):
  - **Exportar:** un archivo `.json` con marcadores y carpetas, reglas de sitios y anuncios (ocultar, estilos, dominios) y ajustes. **No incluye** historial, cookies, sesión, permisos de sitios, apps elegidas para multimedia ni «borrar al salir». El archivo va **sin cifrar**: se avisa antes de exportar.
  - **Importar:** pide confirmación mostrando lo que contiene. Marcadores y reglas se **añaden** a los existentes (carpetas con el mismo nombre se reutilizan; las URL y reglas repetidas se saltan; no se borra nada). Los ajustes de la copia sustituyen a los actuales. Para aplicarlo todo conviene reiniciar Optimo.
  - **Seguridad de la importación:** tamaño máximo 5 MB; solo se aceptan ajustes de una lista cerrada, con su tipo; solo marcadores `http(s)`; límites de cantidad y longitud. Una copia manipulada no puede escribir otras claves, conceder permisos ni dejar ajustes con un tipo erróneo.
- **Borrar datos al salir** (Ajustes › Privacidad y datos): tres opciones independientes, desactivadas por defecto: cookies y datos de sitios, historial y caché.
  - Se ejecuta al cerrar Optimo (atrás hasta salir). Si el proceso muere antes (quitarla de recientes en algunos móviles, o Android que la cierra con ella en segundo plano), queda una marca y la limpieza se hace **al abrirla de nuevo**, antes de cargar nada; la marca solo se quita cuando el borrado de cookies termina. Consecuencia: si Android cierra Optimo por falta de memoria con la opción activada, también se cerrarán tus sesiones.
  - Sin excepciones por sitio: Android no permite listar las cookies de otros sitios ni conservar solo algunas con sus atributos.
- El Kotlin, sin compilar con el SDK ni probado en el móvil.

## 1.12.7 — Ajustes por sitio y ahorro de datos

- **Ajustes por sitio** (⋮ › «Personalizar este sitio…», no disponible en pestañas privadas porque se guardan en disco con el nombre del sitio):
  - **Versión de escritorio en este sitio:** se abre siempre en escritorio, sin tocar el interruptor de la pestaña.
  - **Modo oscuro en este sitio:** según ajustes / siempre / nunca; manda sobre «Oscurecer páginas web».
  - **Bloqueo de anuncios en este sitio:** atajo a la pausa por sitio que ya existía.
  - Las reglas valen también para los subdominios (una regla de `example.com` vale para `m.example.com`) y entran en la copia de seguridad. Se aplican **al empezar a navegar** (escrita, enlace o redirección); sin temporizadores. En un enlace tocado, Android puede aplicar el cambio de versión de escritorio a partir de la carga siguiente: si no lo notas, recarga.
- **Ahorro de datos** (Ajustes › «Ahorro de datos (sin imágenes)»: desactivado / siempre / solo con datos móviles; desactivado por defecto): no carga las imágenes que vienen de la red (`blockNetworkImage`). «Solo con datos móviles» se decide en cada navegación según si la conexión es de datos limitados (también cuenta un Wi-Fi marcado como de uso medido). En el menú ⋮ aparece «Cargar imágenes en esta página» para cargarlas solo esa vez. El vídeo ya no se reproduce solo: el WebView exige un toque (valor por defecto).
- **Oferta «Reproducir en Optimo Play» en YouTube:** ya estaba resuelta desde 1.12.2 (se verificó en el código); `PENDIENTES-Optimo.md` se actualizó con el estado real de cada punto.
- El Kotlin, sin compilar con el SDK ni probado en el móvil.

## 1.12.8 — Guardar página para leer sin conexión (y punto de anclaje para procesadores)

- **Guardar:** ⋮ › «Guardar página para leer sin conexión» (solo pestañas normales, con la página ya cargada). Se guarda el **MHTML** de la página (con sus imágenes y estilos, `saveWebArchive`) y una **instantánea del DOM ya renderizado** en HTML (máx. 3 MB). Límites: 100 páginas y 25 MB por página.
- **Ver y gestionar:** ⋮ › «Páginas guardadas (N)…» (también en Ajustes › Privacidad y datos, con el tamaño total): abrir sin conexión, abrir la dirección original, eliminar o borrar todas.
- **Visor sin conexión:** una pestaña aparte (candado 📄) sin red, **sin JavaScript** y sin almacenamiento; es la única que puede leer archivos locales (`allowFileAccess`), el resto del navegador sigue sin hacerlo. Un enlace tocado dentro del visor se abre en una pestaña normal. No se guarda en la sesión, no entra en el historial, no se suspende y el ahorro de datos no le afecta.
- **Dónde y cómo se guarda:** los archivos van en `noBackupFilesDir/paginas` (privado de la app y fuera de cualquier copia de seguridad de Android); el índice (títulos y direcciones) va cifrado como el resto. **Los archivos de página no van cifrados** (un MHTML tiene que leerse como archivo): si guardas una página con sesión iniciada, su contenido queda en el almacenamiento privado de Optimo. Las pestañas privadas no permiten guardar. Las páginas guardadas no entran en la copia de seguridad ni las toca «borrar al salir»: solo se borran desde su lista.
- **Punto de anclaje (`PaginasGuardadas.kt`):** `ProcesadorPagina` es una interfaz para engancharse a las páginas guardadas, pensada para un futuro «scrapper». Basta con añadir una implementación a `PaginasGuardadas.procesadores` (p. ej. en `MainActivity.onCreate`, junto a `PaginasGuardadas.iniciar`). Recibe `alGuardar(pagina, mht, html)` con los metadatos (`id`, título, URL, fecha) y los dos archivos, y `alEliminar(pagina)`. Contrato: se llama en un **hilo de fondo**, uno por uno y con cada excepción aislada; solo con páginas que el usuario guardó a propósito (nunca navegación normal ni pestañas privadas); los archivos pueden borrarse en cualquier momento, así que hay que leerlos o copiarlos al recibirlos.
- Pendiente de comprobar en el móvil: que el visor muestra bien el MHTML cargado desde `file://` (lo normal en Chromium; si no, el plan B sería servirlo con `WebViewAssetLoader`, que ya está en las dependencias). El Kotlin, sin compilar con el SDK ni probado en el móvil.

## 1.12.9 — Enlaces de Mega y listas .m3u en «Abrir con…»

- **Enlaces de Mega** (`mega.nz`, `mega.io`, `mega.co.nz`: `/file/…`, `/folder/…`, `/embed/…` y el formato antiguo `#!…`/`#F!…`) y **listas `.m3u`** (las `.m3u8` ya estaban) se tratan como los vídeos: al tocarlos, Optimo pregunta si abrirlos en Optimo Play, en otra app (p. ej. la de Mega), en el navegador o copiar el enlace, con «Recordar mi elección». Se ajusta en Ajustes › «Enlaces multimedia, magnets y Mega…» (comparte modo y app con los enlaces multimedia).
- Si ya estás navegando por Mega, sus enlaces no preguntan (son de su propia web). Con el modo «navegador» se abren como siempre.
- Pulsación larga en un enlace de Mega: «Reproducir en Optimo Play» y «Abrir con otra app…». Si la página abierta es un enlace de Mega, el menú ⋮ ofrece «Abrir este enlace de Mega en otra app…».
- A Optimo Play se le entrega el mismo `Intent` que a los magnets (`ACTION_VIEW` con la dirección, sin tipo ni cabeceras: no se le pasa la página de origen). **Suponiendo que su `PlayerActivity` ya sabe resolver esas direcciones**; si no es así, hay que revisar en Optimo Play cómo lee el `Intent`.
- `PENDIENTES-Optimo.md`: se añade el plan del rastreador de enlaces (fases, forma de la lista, límites y decisiones abiertas).
- El Kotlin, sin compilar con el SDK ni probado en el móvil.

## 1.13.1 — Rastreador de enlaces: elegir tipos antes de buscar (sobre 1.13.0, fase 1 de la página abierta)

- Menú ⋮ › **«Buscar enlaces en la página…»**: extrae los enlaces de la página abierta (también de una guardada sin conexión) con `assets/enlaces/extraer.js` (`a[href]` + búsqueda por expresión regular de magnets y enlaces de Mega escritos como texto) y los clasifica: **Magnet, Mega, Nube** (MediaFire, Drive, Dropbox…), **Torrent, Lista** (.m3u/.m3u8), **Vídeo, Audio, Comprimido, Documento, Imagen** y **Propio** (filtro escrito por ti: extensiones, trozos de dirección o `/regex/`). Solo mira la página abierta; nada de rastreo ni red extra.
- Al pulsar «Buscar enlaces…» se abre primero una lista con casillas para **elegir qué tipos buscar** (se recuerda; imágenes desactivadas por defecto) y un botón «Filtro propio…». Si no hay resultados se avisa con un diálogo que permite cambiar los tipos.
- Cada fila: **nombre** arriba y «tipo · dominio · tamaño» debajo. Magnets: nombre y tamaño del propio enlace (`dn`, `xl`). Archivos: el final de la dirección. **Mega no trae nombre** (va cifrado): se toma del texto del enlace, texto/encabezado cercano o título de la página, y la fila lo indica («nombre: texto cercano»).
- Duplicados fuera (magnets por infohash). Tope de 5000 enlaces por página.
- **Mismo dominio por defecto**: los archivos de otros sitios se ocultan (se avisa cuántos) hasta activar «Incluir archivos de otros sitios» en «Más». Magnets, Mega y nubes se muestran siempre, porque casi siempre están en otro sitio.
- Tocar una fila: copiar enlace o nombre, abrir con otra app / Optimo Play (magnet, Mega, listas, vídeo, audio, torrent), abrir en pestaña nueva, compartir. «Filtrar» por tipo. «Más»: copiar todos (con o sin nombres), **exportar a .txt o .csv** (selector del sistema; el CSV lleva tipo, nombre, origen del nombre, tamaño, enlace y página), **guardar la lista para después**, filtro propio.
- **Listas guardadas** (máx. 30 de hasta 2000 enlaces), cifradas con Keystore como marcadores y reglas. Menú ⋮ y Ajustes › Privacidad. No se pueden guardar desde pestañas privadas.
- Copia de seguridad: se añaden `enlaces_propio` y `enlaces_otros` (las listas guardadas no van en la copia).
- El Kotlin, sin compilar con el SDK ni probado en el móvil; el JS sí se probó en Chromium.

## 1.14.0 — Rastreador de enlaces, fase 2: seguir enlaces (rastreo)

- Menú ⋮ › **«Rastrear este sitio…»**: ventana de ajustes y botón «▶ Empezar el rastreo». Desde la página abierta sigue sus enlaces **por anchura** (primero todo lo de un salto, luego dos…) y reúne los enlaces encontrados en una sola lista, que se maneja igual que la de la fase 1 (filtrar, copiar, exportar, guardar, abrir con otra app). Cada fila indica la página donde se encontró («· en sitio.com/ruta»), y el CSV la lleva en su columna de página.
- **Profundidad editable**: 1–50 saltos o 0 = sin límite. La profundidad no es el freno real: lo son los topes, siempre activos y editables: **máximo de páginas** (200 por defecto, hasta 2000), **tiempo máximo** (10 min, hasta 120), **pausa entre páginas** (600 ms) y 5000 enlaces. Una sola petición cada vez.
- Solo lee el **HTML descargado** (HttpURLConnection, sin WebView ni JavaScript, sin dependencias): los sitios que construyen sus enlaces con JavaScript o piden captcha/Cloudflare darán poco o nada (eso sería la fase 3). Páginas de hasta 2 MB; las redirecciones se siguen a mano y cada salto vuelve a pasar por el bloqueo y el alcance.
- **Mismo dominio por defecto**; «Seguir también otros sitios» lo amplía. Los enlaces a archivos no se descargan, se listan. No se siguen enlaces tipo cerrar sesión/borrar/darse de baja ni más de 40 variantes de una misma ruta (calendarios, filtros infinitos).
- **Dominios bloqueados**: lo que bloquean el bloqueador de anuncios y sus listas (incluidos los dominios bloqueados a mano) no se visita ni aparece en los resultados.
- **robots.txt** respetado por defecto (grupo `*`, regla más larga gana; se puede desactivar). **Cookies**: no se envían salvo que lo actives («Enviar mis cookies»), y nunca desde una pestaña privada.
- Mientras corre: ventana con páginas leídas, enlaces, saltos y cola; «Parar y ver» (entrega lo encontrado hasta ahora) o «Descartar». Si la app pasa a segundo plano se detiene sola y el resultado queda en ⋮ › «Ver el último rastreo». No se guarda nada en disco salvo que guardes la lista.
- Copia de seguridad: se añaden los ajustes `rastro_*`.
- «Buscar enlaces en la página…» y «Rastrear…» comparten la ventana de tipos (casillas + filtro propio). La versión de «Acerca de» sigue escrita a mano en el código (pendiente leerla de `BuildConfig`).
- No se aplican las reglas de ocultación de elementos (exigirían interpretar el HTML con selectores CSS; ver PENDIENTES).
- El Kotlin, sin compilar con el SDK ni probado en el móvil.

## 1.15.0 — Óptimo Inspector: auditoría automática de la página (pestaña «Auditoría» en las DevTools)

- Nueva pestaña **Auditoría** en el panel de DevTools (última a la derecha). Se rellena sola al abrirla y al terminar de cargar la página; «Auditar de nuevo» repite la medición y «Copiar informe» lo deja en el portapapeles (sin parámetros de URL y con los valores que parecen secretos tapados; también se añade al «Diagnóstico» de la pestaña Info).
- **Velocidad**: LCP, FCP, TTFB, CLS y TBT con semáforo (umbrales de Google: LCP 2,5/4 s, FCP 1,8/3 s, TTFB 0,8/1,8 s, CLS 0,1/0,25, TBT 200/600 ms) más el tiempo de carga. Si el LCP es lento indica en qué fase se pierde (servidor, descubrimiento tardío, descarga o pintado). El CLS se calcula por ventanas de sesión, como Chrome (el número de la pestaña Info es la suma de todos los saltos y puede ser mayor).
- **Imágenes**: pesadas (200 KB / 1 MB), mucho más grandes de lo que se muestran, imagen principal con `loading="lazy"`, imágenes lejanas que se cargan ya y formato antiguo (PNG/JPEG pesados).
- **Scripts**: tareas largas con el script responsable (`long-animation-frame`, si el WebView lo admite), TBT y exceso de JavaScript.
- **Saltos de diseño**: qué elemento se movió, cuánto aportó y la causa probable (imagen sin `width`/`height`, contenido incrustado, cambio de tamaño, empujado por contenido anterior).
- **Conexiones y recursos innecesarios**: recursos que bloquean el pintado, terceros por dominio (peticiones, peso, la petición más lenta), exceso de dominios, HTTP/1.1, texto sin comprimir, descargas repetidas, redirecciones, páginas con muchos elementos. Peso por tipo y listas de los recursos más pesados y más lentos.
- **Acciones sobre los hallazgos** (la web no es tuya, así que las recomendaciones son cosas que puedes hacer): «Ver en Elementos», «Ocultar siempre» (regla del sitio, con confirmación), «Bloquear dominio» (regla de usuario en todas las páginas, con aviso si es un CDN) y «Abrir en modo lectura» cuando la página pesa mucho. Muestra además lo que Optimo ya ha bloqueado en esa página.
- Límites: lo que Optimo bloquea no cuenta en tiempos ni en peso; de los dominios que no envían `Timing-Allow-Origin` el navegador no da tamaño (se avisa); no se mide INP (necesita interacción); una sola carga, sin simulación de red. Los observadores usan `buffered`, así que recogen también lo anterior a abrir el panel; solo los recursos pueden faltar si el panel se abre tarde en una página con más de ~250 (se avisa).
- Hecho: la lógica va en `assets/devtools/agente.js` (`auditar()`) y `VistaAuditoria.kt` solo la pinta. El JS se probó en Chromium con Playwright (imágenes pesadas, CSS lento, script largo, salto de diseño, terceros, duplicados; con el agente al inicio y inyectado tarde). **El Kotlin no se ha compilado ni probado en el móvil.**

## 1.16.0 — DevTools: modo sencillo

- **Nueva pestaña «Resumen»** (`VistaResumen.kt`): dice en lenguaje llano qué le pasa a la página abierta. Muestra un veredicto (todo en orden / hay cosas que revisar / problemas importantes), cuatro cifras (errores en la página, archivos que no cargaron, bloqueadas por Óptimo, tiempo de carga) y tarjetas explicadas sin tecnicismos:
  - errores internos de la página (con los tres más repetidos) → «Ver los errores»;
  - archivos que no se pudieron cargar, con el motivo en palabras (no existe, acceso denegado, fallo del servidor, sin respuesta) → «Ver la lista»;
  - peticiones bloqueadas por Óptimo y de qué sitios; si además algo falla, avisa de que puede ser por el bloqueo y ofrece **«Pausar el bloqueo en este sitio»**;
  - «La página es lenta» (más de 5 s) con peso y terceros, y hasta tres hallazgos de la Auditoría.
- **Dos modos**, con el botón «Modo: sencillo ⇄ / profesional ⇄»:
  - *Sencillo*: pestañas Resumen, Errores (la consola) y Red; el panel se abre en el Resumen.
  - *Profesional* (por defecto, como hasta ahora): todas las pestañas, con el Resumen al final.
  - Desde el Resumen, los botones que llevan a pestañas que el modo sencillo no tiene (p. ej. la auditoría completa) pasan antes al profesional.
- No mide nada por su cuenta: reúne lo que ya captura el panel (consola, red, bloqueos) y las métricas de la Auditoría. Como el resto del panel, solo cuenta lo ocurrido desde que se abrió, salvo con «captura desde el inicio»; el Resumen lo dice y ofrece activarla.
- «Copiar informe» usa el mismo diagnóstico de la pestaña Info (sin cookies, cabeceras, cuerpos ni parámetros de las URL).
- **«Acerca de» lee la versión del sistema** (`PackageManager`, la de `build.gradle`) en vez de tenerla escrita a mano.
- Ajuste nuevo: `devSencillo` (se recuerda el modo elegido).
- Kotlin sin compilar aquí (revisado a mano: llaves, paréntesis, tipos y nulos); sin cambios de JavaScript.

## 1.17.0 — Permitir dominios bloqueados (por sitio o en todos)

- **Excepciones de bloqueo:** un dominio permitido pasa tal cual en la red: ni se bloquea ni se sustituye por un script/vídeo vacío, y gana a las listas, a los bloqueos propios y a las técnicas de red. Incluye sus subdominios. Se aplica **por sitio** (solo en las páginas de ese sitio y sus subdominios) o **en todos los sitios**.
- **Dónde se activa** (en cada caso pregunta «En este sitio» / «En todos los sitios» / «Cancelar», y avisa de que puede volver a traer anuncios o rastreadores):
  - **Resumen** › tarjeta «Óptimo bloqueó N peticiones» › **«Revisar bloqueos»**: lista de dominios bloqueados en la página, toca uno.
  - **Red** › toca una petición bloqueada › botón **«Permitir»**.
  - **Auditoría** › «Lo que Optimo ya bloqueó en esta página»: toca un dominio.
- **Dónde se gestionan:** en «Reglas de sitios y anuncios» (Ajustes) y en «Reglas de este sitio» (menú ⋮ › Personalizar este sitio), donde aparecen como «Permitir dominio en sitio» y se pueden marcar y borrar como el resto. Entran también en la copia de seguridad.
- Hay que **recargar la página** para ver el efecto. Las pestañas privadas no guardan excepciones (se escribirían en disco con el nombre del sitio).
- **Límite:** solo actúa sobre peticiones de red. Ocultar huecos, saltar anuncios y el resto de técnicas dentro de la página siguen activos; para apagar técnicas concretas en un sitio, ver `PENDIENTES-Optimo.md`.
- Kotlin sin compilar aquí (revisado a mano: llaves, paréntesis, tipos y nulos); sin cambios de JavaScript.

## 1.18.0 — Certificados atados a su huella y retención del historial

**Certificados (`Certificados.kt`, `MainActivity.kt`)**
- **Excepciones atadas al certificado.** Seguir con un certificado no válido (solo equipos de la red local; en Internet sigue bloqueado sin opción de saltarlo) crea una autorización que guarda el **sitio (host:puerto), la huella SHA-256 del certificado exacto y los motivos del fallo**. Otro certificado en el mismo equipo, o un motivo nuevo, vuelven a preguntar. Si el equipo presenta un certificado distinto del que autorizaste, el aviso lo dice expresamente (puede ser otro dispositivo en la red).
- **Duración limitada.** «Solo esta sesión» (solo en memoria) o «Recordar 30 días» (24 horas si el certificado está caducado o aún no vale). En pestañas privadas solo existe «esta sesión», y se olvida al cerrar la última privada.
- **Motivos diferenciados:** autofirmado, entidad no reconocida, nombre incorrecto, caducado, aún no válido. El WebView suele avisar solo de «fecha no válida»; caducado o aún no válido se decide mirando las fechas del certificado.
- **Revocar:** Ajustes › Privacidad y datos › «Excepciones de certificado (N)…»: detalle con huella completa, «Revocar», «Copiar huella» y «Revocar todas». «Borrar todo» también las revoca. No entran en la copia de seguridad. Al revocar, vencer o cerrar Optimo se llama a `WebView.clearSslPreferences()`: el WebView recuerda en memoria los certificados con los que se continuó y, si no, seguiría dejando pasar.
- **Candado honesto:** una página cifrada con certificado no válido autorizado muestra ⚠ en vez de 🔒.
- **Panel del certificado:** candado › «Certificado de la conexión…»: emitido para/por, validez, huella SHA-256 (copiable) y la autorización vigente si la hay. Si el WebView no da certificado (caché, página guardada) lo dice, no inventa datos.
- Sin huella (API 26-28 usa una clave interna del `Bundle`; si falla) no se ofrece continuar: no se puede atar la autorización a un certificado.

**Historial (`Almacen.kt`, `MainActivity.kt`)**
- **Conservar historial:** Ajustes › Privacidad y datos: siempre, 7, 30 o 90 días. Lo anterior se borra al abrir Optimo y al volver a ella; al elegir un plazo avisa de cuántas páginas se borrarán. Las entradas sin fecha se conservan. Entra en la copia de seguridad (`historial_dias`).
- **Borrar por rango:** última hora, hoy, últimos 7 o 30 días, o todo (con recuento y confirmación). Disponible en Privacidad y datos y en el botón «Borrar…» de la lista de historial (sustituye a «Borrar todo»). El historial guarda una sola entrada por dirección (la última visita), así que el rango se aplica a la última visita.
- Kotlin sin compilar ni probar en dispositivo (revisado a mano: llaves, paréntesis, tipos y nulos). Sin cambios de JavaScript.
