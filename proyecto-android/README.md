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
`Omnibox.kt`, `AdBlock.kt`, `Paginas.kt` (logica pura), `Almacen.kt` (marcadores, historial, ajustes), `AlmacenSeguro.kt` (cifrado local), `Marcadores.kt` (marcadores y carpetas), `Modelo.kt`.


## 1.6.0 — Integración con Optimo Play

Si está instalada la app **Optimo Play** (`com.optimo.play`), Optimo le entrega el contenido multimedia:
- Descargas de vídeo/audio/.torrent: pregunta «Reproducir en Optimo Play» o «Descargar».
- Enlaces `magnet:`: se abren directamente en el reproductor.
- Pulsación larga sobre un enlace de vídeo/magnet/.torrent: «Reproducir en Optimo Play».
- Menú principal: «▶ Reproducir vídeo de la página» cuando se ha visto un flujo m3u8/mpd/mp4 (se manda con Referer y User-Agent).
Sin Optimo Play instalada, todo funciona como en 1.5.0.
