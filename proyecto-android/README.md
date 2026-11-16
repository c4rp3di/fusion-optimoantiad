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

## Firma
El workflow de Fusion genera `apk-build/debug.keystore` la primera vez que compila en el repositorio de este
proyecto y lo conserva: firma propia, distinta de cualquier otra app, estable entre builds.

## Estructura
`app/src/main/java/com/optimo/navegador/`: `MainActivity.kt` (interfaz, pestañas, menus, clientes WebView),
`Omnibox.kt`, `AdBlock.kt`, `Paginas.kt` (logica pura), `Almacen.kt` (marcadores, historial, ajustes), `AlmacenSeguro.kt` (cifrado local), `Marcadores.kt` (marcadores y carpetas), `Modelo.kt`, `ServicioSegundoPlano.kt` (reproducción con la pantalla bloqueada).


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

## Pendientes
Ver PENDIENTES-Optimo.md (oferta de Play en Optimo, eficiencia y batería).
