# Pruebas del modo lectura y del ancho de la versión de escritorio

Requieren Python con Playwright y Chromium (`pip install playwright && playwright install chromium`). No usan el móvil: emulan un teléfono (412 px, táctil).

    python3 pruebas/lectura/probar_extraccion.py app/src/main/assets/lectura/lectura.js
    python3 pruebas/lectura/probar_vista.py app/src/main/assets/lectura/lectura.js

- `probar_extraccion.py`: `<span>` dentro de párrafos (no debe haber `<p>` anidados), texto en `<div>` con `<br>` y sin `<p>` (debe abrir), y ancho de la vista al abrir la lectura.
- `probar_vista.py`: desplegando el script de la versión de escritorio (ancho 1280), la lectura se maqueta al ancho del móvil (412) y al cerrar se restaura el ancho de escritorio.
