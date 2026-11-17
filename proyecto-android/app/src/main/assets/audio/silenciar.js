/*
 * Silenciar pestaña (Optimo 1.9.0). Se inyecta al inicio de cada documento y de cada iframe.
 * __MUTE__ se sustituye por true/false según el estado de la pestaña al crear el documento.
 *
 * El WebView de Android no tiene un «mute» propio, así que se silencia desde la página:
 *  - <video>/<audio>: la propiedad `muted` se sustituye por una que guarda lo que la página cree (valor lógico)
 *    y fuerza el valor real a true mientras la pestaña esté silenciada. Al quitar el silencio se restaura el valor lógico.
 *  - Web Audio: todo lo que se conecta a ctx.destination pasa por un GainNode propio (0 = silencio, 1 = normal).
 *  - Los iframes reciben el cambio por postMessage desde el documento principal.
 */
(function () {
  var inicial = __MUTE__;
  if (window.__optimoSetMute) {
    window.__optimoSetMute(inicial);
    return;
  }
  var mudo = !!inicial;
  var gains = [];
  var d = null;
  try { d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted'); } catch (e) {}

  function realMuted(el, v) {
    try { if (d && d.set) d.set.call(el, v); else el.muted = v; } catch (e) {}
  }
  function realGet(el) {
    try { return d && d.get ? d.get.call(el) : el.muted; } catch (e) { return false; }
  }

  function forzar(el) {
    if (!el) return;
    if (el.__omL === undefined) el.__omL = realGet(el);
    if (!realGet(el)) realMuted(el, true);
  }
  function soltar(el) {
    if (el && el.__omL !== undefined) realMuted(el, !!el.__omL);
  }

  // `muted` lógico: la página ve su propio valor; el real es (lógico || silenciada).
  if (d && d.get && d.set) {
    try {
      Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
        configurable: true,
        enumerable: d.enumerable,
        get: function () { return this.__omL === undefined ? d.get.call(this) : this.__omL; },
        set: function (v) {
          this.__omL = !!v;
          d.set.call(this, !!v || mudo);
        }
      });
    } catch (e) {}
  }

  // play() sobre elementos creados con new Audio() (sin DOM): se fuerzan justo antes de sonar.
  try {
    var play0 = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      if (mudo) forzar(this);
      return play0.apply(this, arguments);
    };
  } catch (e) {}

  function todosLosMedios() {
    try { return document.querySelectorAll('video,audio'); } catch (e) { return []; }
  }

  // Web Audio: un GainNode maestro por contexto.
  try {
    var AN = window.AudioNode;
    var AD = window.AudioDestinationNode;
    if (AN && AD) {
      var connect0 = AN.prototype.connect;
      var disconnect0 = AN.prototype.disconnect;
      var gainDe = function (ctx) {
        if (!ctx.__omG) {
          var g = ctx.createGain();
          connect0.call(g, ctx.destination);
          g.gain.value = mudo ? 0 : 1;
          ctx.__omG = g;
          gains.push(g);
        }
        return ctx.__omG;
      };
      AN.prototype.connect = function (t) {
        if (t instanceof AD && this.context === t.context && this !== t.context.__omG) {
          try {
            var a = Array.prototype.slice.call(arguments);
            a[0] = gainDe(t.context);
            connect0.apply(this, a);
            return t;
          } catch (e) {}
        }
        return connect0.apply(this, arguments);
      };
      AN.prototype.disconnect = function (t) {
        if (t instanceof AD && t.context.__omG) {
          var a = Array.prototype.slice.call(arguments);
          a[0] = t.context.__omG;
          return disconnect0.apply(this, a);
        }
        return disconnect0.apply(this, arguments);
      };
    }
  } catch (e) {}

  function aplicar() {
    var v = todosLosMedios();
    for (var i = 0; i < v.length; i++) {
      if (mudo) forzar(v[i]); else soltar(v[i]);
    }
    for (var k = 0; k < gains.length; k++) {
      try { gains[k].gain.value = mudo ? 0 : 1; } catch (e) {}
    }
  }

  function aIframes() {
    var fr;
    try { fr = document.querySelectorAll('iframe,frame'); } catch (e) { return; }
    for (var i = 0; i < fr.length; i++) {
      try { fr[i].contentWindow.postMessage({ __optimoMute: mudo }, '*'); } catch (e) {}
    }
  }

  function poner(v) {
    mudo = !!v;
    window.__optimoMudo = mudo;
    aplicar();
    aIframes();
  }

  window.__optimoMudo = mudo;
  window.__optimoSetMute = poner;

  // Elementos nuevos (se añaden al DOM o empiezan a reproducir).
  try {
    document.addEventListener('play', function (e) { if (mudo) forzar(e.target); }, true);
    document.addEventListener('loadedmetadata', function (e) { if (mudo) forzar(e.target); }, true);
  } catch (e) {}
  try {
    var iniciarObservador = function () {
      var raiz = document.documentElement || document;
      new MutationObserver(function (cambios) {
        if (!mudo) return;
        for (var i = 0; i < cambios.length; i++) {
          var n = cambios[i].addedNodes;
          for (var j = 0; j < n.length; j++) {
            var x = n[j];
            if (!x || x.nodeType !== 1) continue;
            if (x.tagName === 'VIDEO' || x.tagName === 'AUDIO') forzar(x);
            else if (x.querySelectorAll) {
              var q = x.querySelectorAll('video,audio');
              for (var k = 0; k < q.length; k++) forzar(q[k]);
            }
            if (x.tagName === 'IFRAME' || x.tagName === 'FRAME') {
              try { x.addEventListener('load', aIframes); } catch (e) {}
            }
          }
        }
      }).observe(raiz, { childList: true, subtree: true });
    };
    if (document.documentElement) iniciarObservador();
    else document.addEventListener('readystatechange', function f() {
      if (document.documentElement) { document.removeEventListener('readystatechange', f); iniciarObservador(); }
    });
  } catch (e) {}

  // Un iframe recibe el estado de su documento padre.
  try {
    window.addEventListener('message', function (e) {
      var m = e.data;
      if (m && typeof m === 'object' && typeof m.__optimoMute === 'boolean' && e.source === window.parent && window.parent !== window) {
        poner(m.__optimoMute);
      }
    });
  } catch (e) {}

  if (mudo) aplicar();
})();
