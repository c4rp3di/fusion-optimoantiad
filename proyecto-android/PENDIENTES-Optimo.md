# Pendientes de Optimo y Optimo Play

Estado (1.9.2, 1.10.0, 1.11.0 y 1.11.7 aplican: oferta en YouTube y Wi-Fi/cierre en pausa del servicio): Optimo 1.7.2 y Optimo Play 0.2.1 entregados. Nada de lo siguiente está aplicado.

## 1. Oferta de «Reproducir en Optimo Play» (Optimo)
Causa confirmada con el panel Información: Optimo detectó `youtube.com/s/search/audio/success.mp3` (sonido de interfaz de 6.636 bytes)
y lo envió como si fuera el vídeo. Los vídeos reales (`googlevideo.com/videoplayback`) no tienen extensión y nunca se detectan.
- Ocultar la oferta en `youtube.com`, `youtu.be`, `youtube-nocookie.com` y `googlevideo.com`.
- Descartar rutas de interfaz (`/s/...`) y medios minúsculos (sondeo de longitud).
- Preferir un manifiesto `.m3u8`/`.mpd` frente a un `.mp3`/`.mp4` suelto.
- Reproducir YouTube dentro de Play: descartado (flujos firmados, cambiantes y contra sus condiciones de uso).

## 2. Eficiencia y batería (revisión estática, sin medir)
### Optimo Play (prioridad alta)
1. Cerrar el servicio tras ~10 min en pausa o terminado.
2. Pausar el torrent a los 1–2 min de pausa y limitar la subida durante el streaming (hoy sigue descargando y subiendo hasta cerrar).
3. `FLAG_KEEP_SCREEN_ON` solo con pista de vídeo y reproduciendo (hoy también con solo audio).
4. Wi-Fi: `WIFI_MODE_FULL` en vez de `FULL_HIGH_PERF` (obsoleto).
5. Refresco de la interfaz de 400 ms a 1 s con los controles ocultos.
### Optimo
1. Wi-Fi: `WIFI_MODE_FULL` en vez de `FULL_LOW_LATENCY` (solo actúa con pantalla encendida).
2. Soltar CPU/Wi-Fi y ocultar el WebView a los 30–60 s de pausa (hoy hasta 5 min).
3. Scripts de vigilancia de anuncios (500 ms YouTube, 1000 ms genérico): espaciarlos a 1,5–2 s en segundo plano.
4. Prioridad del renderer «importante»: aplicarla solo mientras hay audio.
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

