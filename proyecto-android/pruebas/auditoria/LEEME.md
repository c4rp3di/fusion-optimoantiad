# Prueba del Óptimo Inspector (solo el JavaScript)

Sirve una página con imágenes pesadas, CSS lento, un script largo, un salto de diseño, 12 peticiones de un «tercero»
y descargas repetidas, y llama a `window.__optimoDev.auditar()` con el agente inyectado al inicio y también tarde.

    pip install playwright && playwright install chromium
    python3 pruebas/auditoria/probar.py          # o CHROMIUM=/ruta/a/chrome python3 ...

Imprime métricas y hallazgos. No prueba el Kotlin (VistaAuditoria.kt), que hay que compilar y probar en el móvil.
