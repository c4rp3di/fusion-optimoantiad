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
  // Si la página pausa el vídeo justo al salir de la app (sin que lo pidas tú), se reanuda: como mucho 3 veces en
  // los 3 primeros segundos. MainActivity pone __optimoBg al salir y __optimoPausa cuando pausas tú desde la notificación.
  try {
    document.addEventListener('pause', function(e){
      try {
        var v = e.target;
        if (!window.__optimoBg || window.__optimoPausa) return;
        if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'AUDIO') || v.ended) return;
        if (Date.now() - (window.__optimoBgDesde || 0) > 3000) return;
        window.__optimoIntentos = (window.__optimoIntentos || 0) + 1;
        if (window.__optimoIntentos > 3) return;
        setTimeout(function(){
          try {
            if (window.__optimoBg && !window.__optimoPausa && v.paused && !v.ended) {
              var r = v.play();
              if (r && r.catch) r.catch(function(){});
            }
          } catch(x){}
        }, 150);
      } catch(x){}
    }, true);
  } catch(e){}
})();
