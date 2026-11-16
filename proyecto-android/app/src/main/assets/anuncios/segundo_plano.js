(function(){
  if (window.__optimoFondo) return;
  window.__optimoFondo = 1;
  try {
    function fijar(obj, nombre, valor){
      try { Object.defineProperty(obj, nombre, { configurable: true, enumerable: true, get: function(){ return valor; } }); } catch(e){}
    }
    // La página siempre "se ve": así no pausa el vídeo al bloquear la pantalla o salir de la app.
    fijar(Document.prototype, 'hidden', false);
    fijar(Document.prototype, 'webkitHidden', false);
    fijar(Document.prototype, 'visibilityState', 'visible');
    fijar(Document.prototype, 'webkitVisibilityState', 'visible');
    try { Document.prototype.hasFocus = function(){ return true; }; } catch(e){}
    // Y los eventos que delatan el cambio no llegan a sus manejadores.
    function frenar(e){ e.stopImmediatePropagation(); }
    ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze'].forEach(function(t){
      window.addEventListener(t, frenar, true);
      document.addEventListener(t, frenar, true);
    });
    window.addEventListener('blur', function(e){ if (e.target === window) e.stopImmediatePropagation(); }, true);
  } catch(e){}
})();
