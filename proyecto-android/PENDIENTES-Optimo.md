# Pendientes de Optimo y Optimo Play

Estado (1.9.2, 1.10.0, 1.11.0 y 1.11.7 aplican: oferta en YouTube y Wi-Fi/cierre en pausa del servicio): Optimo 1.7.2 y Optimo Play 0.2.1 entregados. Revisado contra 1.12.7: lo marcado HECHO está aplicado; el resto sigue pendiente.

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

