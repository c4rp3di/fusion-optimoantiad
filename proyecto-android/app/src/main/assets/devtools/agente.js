(function () {
  'use strict';
  try {
    if (window.top !== window) return;
    if (window.__optimoDev) return;
  } catch (e) { return; }

  var NONCE = '__NONCE__';
  var MARCA = '\u2063' + NONCE + '\u2063';
  var MAX = 500;
  var seq = 0, ver = 0;
  var cons = [], red = [];
  var activo = true;   // false = en pausa (panel cerrado): todo pasa sin registrarse

  function corto(s, n) { s = String(s); return s.length > n ? s.slice(0, n) + '…(' + (s.length - n) + ' más)' : s; }
  function recortar(s, n) { s = String(s); return s.length > n ? s.slice(0, n) + '…' : s; }
  function absoluta(u) { try { return new URL(u, location.href).href; } catch (e) { return String(u); } }
  function esc(x) { try { return (window.CSS && CSS.escape) ? CSS.escape(x) : x; } catch (e) { return x; } }

  // ------------------------------------------------------------ serialización
  function nodoStr(n) {
    try {
      if (n.nodeType === 1) {
        var s = '<' + n.localName;
        if (n.id) s += '#' + n.id;
        var c = n.getAttribute('class');
        if (c && c.trim()) s += '.' + c.trim().split(/\s+/).join('.');
        return s + '>';
      }
      if (n.nodeType === 3) return '#text "' + recortar(n.nodeValue.trim(), 40) + '"';
      if (n.nodeType === 9) return '#document';
      return '#' + n.nodeName.toLowerCase();
    } catch (e) { return '#nodo'; }
  }

  function ser(v, d, seen) {
    var t = typeof v;
    if (v === null) return 'null';
    if (t === 'undefined') return 'undefined';
    if (t === 'string') return JSON.stringify(recortar(v, 300));
    if (t === 'number' || t === 'boolean') return String(v);
    if (t === 'bigint') return v + 'n';
    if (t === 'symbol') return String(v);
    if (t === 'function') {
      var fn = '';
      try { fn = v.name; } catch (e) { }
      return 'ƒ ' + (fn || '(anónima)') + '()';
    }
    try {
      if (v instanceof Error) return corto(v.stack || (v.name + ': ' + v.message), 1500);
      if (typeof Node !== 'undefined' && v instanceof Node) return nodoStr(v);
      if (v instanceof Date) return isNaN(v) ? 'Invalid Date' : v.toISOString();
      if (v instanceof RegExp) return String(v);
      if (typeof Promise !== 'undefined' && v instanceof Promise) return 'Promise {…}';
    } catch (e) { }
    if (seen.indexOf(v) >= 0) return '[Circular]';
    if (d >= 3) return Array.isArray(v) ? 'Array(' + v.length + ')' : '{…}';
    seen.push(v);
    var out;
    try {
      if (Array.isArray(v)) {
        var p = [], n = Math.min(v.length, 20);
        for (var i = 0; i < n; i++) p.push(ser(v[i], d + 1, seen));
        if (v.length > n) p.push('…+' + (v.length - n));
        out = '[' + p.join(', ') + ']';
      } else if (typeof Map !== 'undefined' && v instanceof Map) {
        var pm = [], k = 0;
        v.forEach(function (val, key) { if (k++ < 20) pm.push(ser(key, d + 1, seen) + ' => ' + ser(val, d + 1, seen)); });
        out = 'Map(' + v.size + ') {' + pm.join(', ') + '}';
      } else if (typeof Set !== 'undefined' && v instanceof Set) {
        var ps = [], q = 0;
        v.forEach(function (val) { if (q++ < 20) ps.push(ser(val, d + 1, seen)); });
        out = 'Set(' + v.size + ') {' + ps.join(', ') + '}';
      } else {
        var ks = Object.keys(v), po = [], m = Math.min(ks.length, 20);
        for (var j = 0; j < m; j++) {
          var kk = ks[j], val;
          try { val = ser(v[kk], d + 1, seen); } catch (e) { val = '(error)'; }
          po.push(kk + ': ' + val);
        }
        if (ks.length > m) po.push('…+' + (ks.length - m));
        var cn = '';
        try { cn = (v.constructor && v.constructor.name && v.constructor.name !== 'Object') ? v.constructor.name + ' ' : ''; } catch (e) { }
        out = cn + '{' + po.join(', ') + '}';
      }
    } catch (e) { out = '[objeto no legible]'; }
    seen.pop();
    return out;
  }

  function formatear(args) {
    var a = Array.prototype.slice.call(args), out = [], i = 0;
    if (typeof a[0] === 'string' && a[0].indexOf('%') >= 0 && a.length > 1) {
      var k = 1;
      var s = a[0].replace(/%([sdifoOc%])/g, function (m, c) {
        if (c === '%') return '%';
        if (k >= a.length) return m;
        var v = a[k++];
        if (c === 'c') return '';
        if (c === 's') return typeof v === 'string' ? v : ser(v, 1, []);
        if (c === 'd' || c === 'i') return typeof v === 'object' ? 'NaN' : String(parseInt(v, 10));
        if (c === 'f') return String(parseFloat(v));
        return ser(v, 0, []);
      });
      out.push(s);
      i = k;
    }
    for (; i < a.length; i++) out.push(typeof a[i] === 'string' ? a[i] : ser(a[i], 0, []));
    return corto(out.join(' '), 4000);
  }

  function origen() {
    // Primer marco de la pila que NO sea del propio agente (el primero marca el archivo del agente).
    try {
      var l = (new Error().stack || '').split('\n').slice(1), re = /([^\s()]+):(\d+):(\d+)\)?\s*$/, propio = null;
      for (var i = 0; i < l.length; i++) {
        var m = re.exec(l[i]);
        if (!m) continue;
        if (propio === null) { propio = m[1]; continue; }
        if (m[1] !== propio) return m[1] + ':' + m[2] + ':' + m[3];
      }
    } catch (e) { }
    return '';
  }

  function agregar(buf, o) {
    o.i = ++seq;
    o.ts = o.ts || Date.now();
    buf.push(o);
    if (buf.length > MAX) buf.shift();
    return o;
  }

  // ------------------------------------------------------------ consola
  try {
    var contadores = {}, tiempos = {};
    var orig = {};
    ['log', 'info', 'warn', 'error', 'debug', 'trace', 'dir', 'table', 'assert', 'clear', 'count', 'time', 'timeEnd', 'timeLog'].forEach(function (nv) {
      if (typeof console[nv] === 'function') orig[nv] = console[nv];
    });
    var emitir = function (nivel, txt) {
      if (!activo) { try { orig.log.call(console, txt); } catch (e) { } return; }
      agregar(cons, { t: nivel, m: txt, o: origen() });
      try { orig.log.call(console, MARCA + txt); } catch (e) { }
    };
    ['log', 'info', 'warn', 'error', 'debug'].forEach(function (nv) {
      if (!orig[nv]) return;
      console[nv] = function () {
        if (!activo) { try { return orig[nv].apply(console, arguments); } catch (e) { } return; }
        try { emitir(nv, formatear(arguments)); } catch (e) { }
      };
    });
    if (orig.trace) console.trace = function () {
      try { emitir('log', 'Traza: ' + formatear(arguments) + '\n' + corto((new Error().stack || '').split('\n').slice(2).join('\n'), 1500)); } catch (e) { }
    };
    if (orig.dir) console.dir = function (o) { try { emitir('log', ser(o, 0, [])); } catch (e) { } };
    if (orig.table) console.table = function (o) { try { emitir('log', ser(o, 0, [])); } catch (e) { } };
    if (orig.assert) console.assert = function (c) {
      try { if (!c) emitir('error', 'Assertion failed: ' + formatear(Array.prototype.slice.call(arguments, 1))); } catch (e) { }
    };
    if (orig.clear) console.clear = function () {
      try { cons.length = 0; } catch (e) { }
      try { orig.clear.call(console); } catch (e) { }
    };
    if (orig.count) console.count = function (l) {
      try { l = l === undefined ? 'default' : String(l); contadores[l] = (contadores[l] || 0) + 1; emitir('log', l + ': ' + contadores[l]); } catch (e) { }
    };
    if (orig.time) {
      console.time = function (l) { l = l === undefined ? 'default' : String(l); tiempos[l] = performance.now(); };
      console.timeEnd = function (l) {
        try { l = l === undefined ? 'default' : String(l); if (tiempos[l] !== undefined) { emitir('log', l + ': ' + (performance.now() - tiempos[l]).toFixed(1) + ' ms'); delete tiempos[l]; } } catch (e) { }
      };
      console.timeLog = function (l) {
        try { l = l === undefined ? 'default' : String(l); if (tiempos[l] !== undefined) emitir('log', l + ': ' + (performance.now() - tiempos[l]).toFixed(1) + ' ms'); } catch (e) { }
      };
    }
  } catch (e) { }

  // ------------------------------------------------------------ red
  function nuevaRed(tipo, m, u) {
    var e = { tipo: tipo, m: m, u: u, st: 0, ms: -1, tam: -1, pend: 1, v: ++ver };
    agregar(red, e);
    return e;
  }
  function cambio(e) { e.v = ++ver; }
  function cabs(h) {
    var o = {};
    try {
      if (!h) return o;
      if (typeof Headers !== 'undefined' && h instanceof Headers) h.forEach(function (v, k) { o[k] = v; });
      else if (Array.isArray(h)) h.forEach(function (p) { o[p[0]] = p[1]; });
      else Object.keys(h).forEach(function (k) { o[k] = String(h[k]); });
    } catch (e) { }
    return o;
  }
  function parsear(txt) {
    var o = {};
    String(txt || '').split(/\r?\n/).forEach(function (l) {
      var i = l.indexOf(':');
      if (i > 0) o[l.slice(0, i).trim().toLowerCase()] = l.slice(i + 1).trim();
    });
    return o;
  }
  function textual(ct) { return /json|text|xml|javascript|x-www-form-urlencoded|html|svg/i.test(ct || ''); }

  function tomarCuerpo(e, r) {
    try {
      if (!r.body || !textual(e.ct) || /event-stream/i.test(e.ct)) return;
      var rd = r.clone().body.getReader();
      var dec = new TextDecoder();
      var acc = '', total = 0;
      var fin = function () { e.cuerpo = acc.slice(0, 20000); e.cuerpoTam = total; cambio(e); };
      (function paso() {
        rd.read().then(function (x) {
          if (x.done) { fin(); return; }
          total += x.value.length;
          if (acc.length < 20000) acc += dec.decode(x.value, { stream: true });
          if (total > 1048576) { try { rd.cancel(); } catch (z) { } e.corte = 1; fin(); return; }
          paso();
        }, function () { fin(); });
      })();
    } catch (x) { }
  }

  try {
    var of = window.fetch;
    if (typeof of === 'function') {
      window.fetch = function (input, init) {
        if (!activo) return of.apply(this, arguments);
        var e = null, ini = performance.now();
        try {
          var url, met = 'GET', hd = null;
          if (typeof input === 'string') url = input;
          else if (input && typeof input.url === 'string') { url = input.url; met = input.method || met; hd = input.headers; }
          else url = String(input);
          if (init) { if (init.method) met = init.method; if (init.headers) hd = init.headers; }
          e = nuevaRed('fetch', String(met).toUpperCase(), absoluta(url));
          e.rq = cabs(hd);
        } catch (x) { e = null; }
        var p = of.apply(this, arguments);
        if (!e) return p;
        return p.then(function (r) {
          try {
            e.pend = 0; e.st = r.status; e.ms = Math.round(performance.now() - ini);
            e.ct = r.headers.get('content-type') || '';
            var cl = r.headers.get('content-length');
            if (cl) e.tam = parseInt(cl, 10);
            e.rs = cabs(r.headers); e.fu = r.url || '';
            cambio(e);
            tomarCuerpo(e, r);
          } catch (x) { }
          return r;
        }, function (err) {
          try { e.pend = 0; e.err = String((err && err.message) || err); e.ms = Math.round(performance.now() - ini); cambio(e); } catch (x) { }
          throw err;
        });
      };
    }
  } catch (e) { }

  try {
    var XP = window.XMLHttpRequest && XMLHttpRequest.prototype;
    if (XP && typeof WeakMap !== 'undefined') {
      var mapa = new WeakMap();
      var oOpen = XP.open, oSend = XP.send, oSet = XP.setRequestHeader;
      XP.open = function (met, url) {
        if (activo) try { var e = nuevaRed('xhr', String(met).toUpperCase(), absoluta(String(url))); e.rq = {}; mapa.set(this, e); } catch (x) { }
        return oOpen.apply(this, arguments);
      };
      XP.setRequestHeader = function (k, v) {
        try { var e = mapa.get(this); if (e) e.rq[k] = v; } catch (x) { }
        return oSet.apply(this, arguments);
      };
      XP.send = function () {
        try {
          var e = mapa.get(this);
          if (e) {
            var ini = performance.now(), xhr = this;
            cambio(e);
            xhr.addEventListener('loadend', function () {
              try {
                e.pend = 0; e.ms = Math.round(performance.now() - ini); e.st = xhr.status; e.fu = xhr.responseURL || '';
                e.rs = parsear(xhr.getAllResponseHeaders());
                e.ct = xhr.getResponseHeader('content-type') || '';
                var cl = xhr.getResponseHeader('content-length');
                if (cl) e.tam = parseInt(cl, 10);
                if (!xhr.status) e.err = 'Sin respuesta (red, CORS o cancelada)';
                try {
                  if (xhr.responseType === '' || xhr.responseType === 'text') {
                    var t = xhr.responseText;
                    e.cuerpoTam = t.length;
                    if (e.tam < 0) e.tam = t.length;
                    if (!e.ct || textual(e.ct)) e.cuerpo = t.slice(0, 20000);
                  } else if (xhr.responseType === 'json') {
                    e.cuerpo = corto(JSON.stringify(xhr.response), 20000);
                  }
                } catch (z) { }
                cambio(e);
              } catch (z) { }
            });
          }
        } catch (x) { }
        return oSend.apply(this, arguments);
      };
    }
  } catch (e) { }

  try {
    if (typeof window.WebSocket === 'function' && typeof Proxy === 'function' && typeof Reflect !== 'undefined') {
      var OW = window.WebSocket;
      window.WebSocket = new Proxy(OW, {
        construct: function (t, args, nt) {
          var ws = Reflect.construct(t, args, nt);
          if (!activo) return ws;
          try {
            var ini = performance.now();
            var e = nuevaRed('ws', 'WS', String(args[0]));
            e.n = 0;
            ws.addEventListener('open', function () { e.pend = 0; e.st = 101; e.ms = Math.round(performance.now() - ini); cambio(e); });
            ws.addEventListener('message', function () { e.n++; cambio(e); });
            ws.addEventListener('error', function () { e.pend = 0; e.err = 'Error de WebSocket'; cambio(e); });
            ws.addEventListener('close', function (ev) { e.pend = 0; e.cierre = ev.code; cambio(e); });
          } catch (x) { }
          return ws;
        }
      });
    }
  } catch (e) { }

  function tipoRt(it, url) {
    var ext = '';
    try { ext = (new URL(url).pathname.split('.').pop() || '').toLowerCase(); } catch (e) { }
    if (/^(woff2?|ttf|otf|eot)$/.test(ext)) return 'fuente';
    if (/^(png|jpe?g|gif|webp|svg|avif|ico|bmp)$/.test(ext)) return 'img';
    if (it === 'script') return 'js';
    if (it === 'img' || it === 'image') return 'img';
    if (it === 'iframe' || it === 'frame' || it === 'navigation') return 'doc';
    if (it === 'video' || it === 'audio' || it === 'track') return 'medio';
    if (it === 'css') return 'css';
    if (it === 'link') return /^(css)$/.test(ext) ? 'css' : (/^(js|mjs)$/.test(ext) ? 'js' : 'otro');
    if (it === 'beacon') return 'fetch';
    return 'otro';
  }
  var navE = null;
  function rt(ent) {
    if (!activo) return;
    try {
      var it = ent.initiatorType;
      if (it === 'fetch' || it === 'xmlhttprequest') return;
      if (ent.entryType === 'navigation' && navE) {
        navE.st = ent.responseStatus || navE.st; navE.ms = Math.round(ent.duration) || navE.ms;
        navE.tam = (ent.transferSize || ent.encodedBodySize || ent.decodedBodySize || 0) || navE.tam;
        if (ent.nextHopProtocol) navE.proto = ent.nextHopProtocol;
        cambio(navE);
        return;
      }
      var u = ent.name;
      if (/^data:|^blob:/.test(u)) return;
      var e = {
        tipo: ent.entryType === 'navigation' ? 'doc' : tipoRt(it, u), m: 'GET', u: u,
        st: ent.responseStatus || 0, ms: Math.round(ent.duration),
        tam: (ent.transferSize || ent.encodedBodySize || ent.decodedBodySize || 0) || -1,
        pend: 0, rt: 1, v: ++ver,
        ts: Math.round(performance.timeOrigin + ent.startTime)
      };
      if (ent.nextHopProtocol) e.proto = ent.nextHopProtocol;
      agregar(red, e);
      if (ent.entryType === 'navigation') navE = e;
    } catch (x) { }
  }
  try {
    new PerformanceObserver(function (l) { l.getEntries().forEach(rt); }).observe({ type: 'resource', buffered: true });
  } catch (e) { }
  try {
    new PerformanceObserver(function (l) { l.getEntries().forEach(rt); }).observe({ type: 'navigation', buffered: true });
  } catch (e) { }

  // ------------------------------------------------------------ DOM
  var ids = new Map(), rev = new WeakMap(), nid = 0;
  var VOID = { area: 1, base: 1, br: 1, col: 1, embed: 1, hr: 1, img: 1, input: 1, link: 1, meta: 1, param: 1, source: 1, track: 1, wbr: 1 };
  function idDe(n) {
    var i = rev.get(n);
    if (!i) {
      i = ++nid; rev.set(n, i);
      ids.set(i, (typeof WeakRef !== 'undefined') ? new WeakRef(n) : { deref: function () { return n; } });
    }
    return i;
  }
  function nodo(i) {
    var w = ids.get(i);
    return w ? (w.deref() || null) : null;
  }
  function propio(c) {
    return c.nodeType === 1 && ((c.hasAttribute && c.hasAttribute('data-optimo-dev')) || c.id === '__optimo_dev_css');
  }
  function abrir(c) {
    var s = '<' + c.localName, at = c.attributes, n = Math.min(at.length, 6);
    for (var k = 0; k < n; k++) s += ' ' + at[k].name + '="' + recortar(at[k].value, 40) + '"';
    if (at.length > n) s += ' …';
    return s + '>';
  }
  function descr(c) {
    var t = c.nodeType;
    if (t === 1) {
      if (propio(c)) return null;
      var hs = 0, unico = null;
      for (var q = c.firstChild; q && hs < 2; q = q.nextSibling) {
        if (q.nodeType === 1) { if (propio(q)) continue; hs++; unico = q; }
        else if ((q.nodeType === 3 && q.nodeValue.trim()) || q.nodeType === 8) { hs++; unico = q; }
      }
      var ab = abrir(c), et;
      if (VOID[c.localName]) { et = ab; hs = 0; }
      else if (hs === 0) et = ab + '</' + c.localName + '>';
      else if (hs === 1 && unico && unico.nodeType === 3 && unico.nodeValue.trim().length <= 60) {
        et = ab + unico.nodeValue.trim() + '</' + c.localName + '>'; hs = 0;
      } else et = ab;
      return { id: idDe(c), t: 1, et: et, hs: hs };
    }
    if (t === 3) {
      var s = c.nodeValue.trim();
      if (!s) return null;
      return { id: idDe(c), t: 3, et: '"' + recortar(s, 120) + '"', hs: 0 };
    }
    if (t === 8) return { id: idDe(c), t: 8, et: '<!-- ' + recortar(c.nodeValue.trim(), 100) + ' -->', hs: 0 };
    if (t === 10) return { id: idDe(c), t: 10, et: '<!DOCTYPE ' + c.name + '>', hs: 0 };
    return null;
  }
  function lista(p, forzar) {
    var res = [], cs = p.childNodes;
    for (var k = 0; k < cs.length; k++) {
      var c = cs[k];
      if (res.length >= 200 && c !== forzar) continue;
      var d = descr(c);
      if (d) res.push(d);
    }
    return res;
  }
  function hijos(i) {
    var n = i ? nodo(i) : document;
    if (!n) return JSON.stringify({ err: 'El nodo ya no existe' });
    return JSON.stringify({ n: n.childNodes.length, h: lista(n, null) });
  }
  function camino(i) {
    var n = nodo(i);
    if (!n) return JSON.stringify({ err: 'El nodo ya no existe' });
    var cad = [];
    for (var x = n; x; x = x.parentNode) cad.unshift(x);
    if (cad[0] !== document) return JSON.stringify({ err: 'Nodo fuera del árbol visible (shadow DOM o desconectado)' });
    var out = [];
    for (var k = 0; k < cad.length - 1; k++) {
      var p = cad[k];
      out.push({ pid: p === document ? 0 : idDe(p), h: lista(p, cad[k + 1]) });
    }
    return JSON.stringify(out);
  }
  function selector(n) {
    if (!n || n.nodeType !== 1) return '';
    var partes = [];
    while (n && n.nodeType === 1) {
      var s = n.localName;
      if (n.id && document.getElementById(n.id) === n) { partes.unshift(s + '#' + esc(n.id)); break; }
      var p = n.parentNode;
      if (p && p.children) {
        var mismos = 0, idx = 0;
        for (var k = 0; k < p.children.length; k++) {
          if (p.children[k].localName === n.localName) { mismos++; if (p.children[k] === n) idx = mismos; }
        }
        if (mismos > 1) s += ':nth-of-type(' + idx + ')';
      }
      partes.unshift(s);
      if (n === document.documentElement) break;
      n = p;
    }
    return partes.join(' > ');
  }
  // ------------------------------------------------------------ guardar como regla del sitio
  var lcpMs = 0, clsVal = 0;
  try { new PerformanceObserver(function (l) { var e = l.getEntries(); if (e.length) lcpMs = Math.round(e[e.length - 1].startTime); }).observe({ type: 'largest-contentful-paint', buffered: true }); } catch (e) { }
  try { new PerformanceObserver(function (l) { l.getEntries().forEach(function (x) { if (!x.hadRecentInput) clsVal += x.value; }); }).observe({ type: 'layout-shift', buffered: true }); } catch (e) { }
  function cuentaSel(s) { try { return document.querySelectorAll(s).length; } catch (e) { return 0; } }
  // Selector corto y estable (id o clases; posicional si no hay otra forma): el que se guarda en las reglas del sitio.
  function selEstable(n0, posicional) {
    var p = [], m = n0;
    for (var d = 0; m && m.nodeType === 1 && m !== document.body && m !== document.documentElement && d < (posicional ? 12 : 5); d++, m = m.parentElement) {
      var t = m.localName;
      if (m.id && !/\d{4,}/.test(m.id)) { p.unshift(t + '#' + esc(m.id)); break; }
      var cl = posicional ? [] : (typeof m.className === 'string' ? m.className : '').trim().split(/\s+/).filter(function (c) { return c && !/\d{3,}/.test(c); }).slice(0, 2);
      if (cl.length) { t += '.' + cl.map(esc).join('.'); }
      else { var k = 1, s2 = m; while ((s2 = s2.previousElementSibling)) { if (s2.localName === m.localName) k++; } t += ':nth-of-type(' + k + ')'; }
      p.unshift(t);
      if (!posicional && cuentaSel(p.join('>')) === 1) break;
    }
    return p.join('>');
  }
  function selGuardar(i) {
    var n = nodo(i);
    if (!n || n.nodeType !== 1) return JSON.stringify({ err: 'Selecciona un elemento (no un nodo de texto)' });
    if (n === document.documentElement || n === document.body) return JSON.stringify({ err: 'No se guardan reglas sobre <html> ni <body>' });
    var s = selEstable(n, false), c = cuentaSel(s);
    if (c > 6) { s = selEstable(n, true); c = cuentaSel(s); }
    if (!s) return JSON.stringify({ err: 'No se pudo construir un selector' });
    return JSON.stringify({ sel: s, n: c, tag: n.localName });
  }
  function xpath(i) {
    var n = nodo(i);
    if (!n || n.nodeType !== 1) return '';
    var partes = [], xh = 'http://www.w3.org/1999/xhtml';
    for (var x = n; x && x.nodeType === 1; x = x.parentNode) {
      var k = 1, s = x, html = x.namespaceURI === xh;
      while ((s = s.previousElementSibling)) { if (s.localName === x.localName && (s.namespaceURI === xh) === html) k++; }
      // SVG/MathML: en un documento HTML, XPath solo los alcanza con local-name().
      partes.unshift((html ? x.localName : '*[local-name()="' + x.localName + '"]') + '[' + k + ']');
      if (x.id && x.id.indexOf('"') < 0 && document.getElementById(x.id) === x) { partes[0] = '*[@id="' + x.id + '"]'; break; }
    }
    return '//' + partes.join('/');
  }

  // ------------------------------------------------------------ cambios de estilo hechos desde el inspector (se pueden guardar como regla)
  var cambios = {}, bases = {};   // por id de nodo: {propiedad: valor} y el atributo style original (null si no tenía)
  function guardarBase(i, n) { if (!(i in bases)) bases[i] = n.hasAttribute('style') ? n.getAttribute('style') : null; }
  function parsearEstilo(css) {
    var o = {};
    try {
      var t = document.createElement('div');
      t.style.cssText = css || '';
      for (var k = 0; k < t.style.length; k++) o[t.style[k]] = t.style.getPropertyValue(t.style[k]);
    } catch (e) { }
    return o;
  }
  function registrarDiff(i, viejoCss, nuevoCss) {
    var a = parsearEstilo(viejoCss), b = parsearEstilo(nuevoCss), hay = false, k;
    for (k in b) {
      if (a[k] !== b[k]) { (cambios[i] || (cambios[i] = {}))[k] = b[k]; hay = true; }
    }
    return hay;
  }
  // Lo que se guardaría: selector estable + los estilos cambiados (con !important para ganar a los de la página).
  function guardable(i) {
    var n = nodo(i);
    if (!n || n.nodeType !== 1) return JSON.stringify({ err: 'Selecciona un elemento (no un nodo de texto)' });
    if (n === document.documentElement || n === document.body) return JSON.stringify({ err: 'No se guardan reglas sobre <html> ni <body>' });
    var sel = selEstable(n, false), c = cuentaSel(sel);
    if (c > 6) { sel = selEstable(n, true); c = cuentaSel(sel); }
    if (!sel) return JSON.stringify({ err: 'No se pudo construir un selector' });
    var props = cambios[i] || {}, partes = [], k;
    for (k in props) partes.push(k + ':' + props[k] + '!important');
    return JSON.stringify({ sel: sel, n: c, tag: n.localName, props: props, css: partes.length ? sel + '{' + partes.join(';') + '}' : '' });
  }
  function olvidar(i) { delete cambios[i]; delete bases[i]; return 'ok'; }
  function deshacerEstilos(i) {
    try {
      var n = nodo(i);
      if (!n || n.nodeType !== 1) return 'El elemento ya no existe';
      if (i in bases) { if (bases[i] === null) n.removeAttribute('style'); else n.setAttribute('style', bases[i]); }
      delete cambios[i]; delete bases[i];
      return 'ok';
    } catch (e) { return 'Error: ' + e.message; }
  }
  // Suma [delta] px al valor calculado de cada propiedad (separadas por comas), sin bajar de [minimo]: «+2 px» sin teclear.
  function ajustarPx(i, props, delta, minimo) {
    var n = nodo(i);
    if (!n || n.nodeType !== 1) return 'El elemento ya no existe';
    var cs = getComputedStyle(n), ps = String(props).split(','), decl = [];
    for (var k = 0; k < ps.length; k++) {
      var v = parseFloat(cs.getPropertyValue(ps[k]));
      if (isNaN(v)) v = 0;
      decl.push(ps[k] + ':' + Math.max(minimo, Math.round((v + delta) * 10) / 10) + 'px');
    }
    return estiloAdd(i, decl.join(';'));
  }
  function pausar(b) { activo = !b; return 'ok'; }
  function duplicar(i) {
    var n = nodo(i);
    if (!n || n.nodeType !== 1 || !n.parentNode || n === document.documentElement) return 'No se puede duplicar este nodo';
    n.parentNode.insertBefore(n.cloneNode(true), n.nextSibling);
    return 'ok';
  }
  function info(i) {
    var n = nodo(i);
    if (!n) return JSON.stringify({ err: 'El nodo ya no existe' });
    if (n.nodeType !== 1) return JSON.stringify({ tipo: n.nodeType, texto: corto(n.nodeValue || '', 8000) });
    var r = n.getBoundingClientRect(), cs = getComputedStyle(n);
    function num(v) { return Math.round((parseFloat(v) || 0) * 10) / 10; }
    var at = [];
    for (var k = 0; k < n.attributes.length; k++) at.push([n.attributes[k].name, n.attributes[k].value]);
    return JSON.stringify({
      tag: n.localName, sel: selector(n), attrs: at,
      r: { x: Math.round(r.left + scrollX), y: Math.round(r.top + scrollY), w: Math.round(r.width * 10) / 10, h: Math.round(r.height * 10) / 10 },
      m: [num(cs.marginTop), num(cs.marginRight), num(cs.marginBottom), num(cs.marginLeft)],
      b: [num(cs.borderTopWidth), num(cs.borderRightWidth), num(cs.borderBottomWidth), num(cs.borderLeftWidth)],
      p: [num(cs.paddingTop), num(cs.paddingRight), num(cs.paddingBottom), num(cs.paddingLeft)],
      html: corto(n.outerHTML, 8000)
    });
  }
  function decls(st) {
    var o = [];
    for (var i = 0; i < st.length; i++) {
      var p = st[i];
      o.push([p, st.getPropertyValue(p), st.getPropertyPriority(p)]);
    }
    return o;
  }
  function estilos(i) {
    var el = nodo(i);
    if (!el || el.nodeType !== 1) return JSON.stringify({ err: 'No es un elemento' });
    var reglas = [], bloq = [];
    function visita(rs, origen) {
      for (var k = 0; k < rs.length; k++) {
        var r = rs[k];
        try {
          if (r.type === 1) {
            if (el.matches(r.selectorText)) reglas.push({ s: r.selectorText, o: origen, d: decls(r.style) });
          } else if (r.type === 4) {
            if (!r.media || window.matchMedia(r.media.mediaText).matches) visita(r.cssRules, origen + ' @media ' + r.media.mediaText);
          } else if (r.type === 12) {
            visita(r.cssRules, origen + ' @supports');
          }
        } catch (e) { }
      }
    }
    var hojas = Array.prototype.slice.call(document.styleSheets || []);
    try { if (document.adoptedStyleSheets) hojas = hojas.concat(Array.prototype.slice.call(document.adoptedStyleSheets)); } catch (e) { }
    hojas.forEach(function (s) {
      var nombre = s.href ? s.href : 'style en línea';
      try { visita(s.cssRules, nombre); } catch (e) { bloq.push(nombre); }
    });
    reglas.reverse();
    var cs = getComputedStyle(el), comp = {};
    for (var j = 0; j < cs.length; j++) comp[cs[j]] = cs.getPropertyValue(cs[j]);
    return JSON.stringify({ inline: el.getAttribute('style') || '', reglas: reglas.slice(0, 80), bloq: bloq, comp: comp });
  }

  function attr(i, nombre, valor) {
    try {
      var n = nodo(i);
      if (!n || n.nodeType !== 1) return 'El nodo ya no existe';
      var antes = (nombre === 'style') ? (n.getAttribute('style') || '') : '';
      if (nombre === 'style') guardarBase(i, n);
      if (valor === null || valor === undefined) { n.removeAttribute(nombre); if (nombre === 'style') { delete cambios[i]; delete bases[i]; } }
      else { n.setAttribute(nombre, valor); if (nombre === 'style') registrarDiff(i, antes, valor); }
      return 'ok';
    } catch (e) { return 'Error: ' + e.message; }
  }
  function html(i, txt) {
    try {
      var n = nodo(i);
      if (!n || n.nodeType !== 1) return 'El nodo ya no existe';
      n.outerHTML = txt;
      return 'ok';
    } catch (e) { return 'Error: ' + e.message; }
  }
  function texto(i, txt) {
    try {
      var n = nodo(i);
      if (!n) return 'El nodo ya no existe';
      n.nodeValue = txt;
      return 'ok';
    } catch (e) { return 'Error: ' + e.message; }
  }
  function borrar(i) {
    try {
      var n = nodo(i);
      if (!n || !n.parentNode) return 'El nodo ya no existe';
      n.parentNode.removeChild(n);
      return 'ok';
    } catch (e) { return 'Error: ' + e.message; }
  }
  function estiloAdd(i, decl) {
    try {
      var n = nodo(i);
      if (!n || n.nodeType !== 1) return 'El nodo ya no existe';
      guardarBase(i, n);
      var antes = n.style.cssText;
      n.style.cssText = (antes ? antes + ';' : '') + decl;
      return registrarDiff(i, antes, n.style.cssText) ? 'ok' : 'Ya estaba así, o la declaración no es válida';
    } catch (e) { return 'Error: ' + e.message; }
  }
  function css(txt) {
    try {
      var s = document.getElementById('__optimo_dev_css');
      if (!s) {
        s = document.createElement('style');
        s.id = '__optimo_dev_css';
        (document.head || document.documentElement).appendChild(s);
      }
      s.textContent = txt;
      return 'ok';
    } catch (e) { return 'Error: ' + e.message; }
  }
  function cssActual() {
    var s = document.getElementById('__optimo_dev_css');
    return s ? s.textContent : '';
  }

  var ov = null;
  function overlay() {
    if (ov && ov.isConnected) return ov;
    ov = document.createElement('div');
    ov.setAttribute('data-optimo-dev', '1');
    ov.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;box-sizing:border-box;background:rgba(111,168,220,.4);outline:2px solid #1a73e8;display:none;left:0;top:0;width:0;height:0';
    var et = document.createElement('div');
    et.setAttribute('data-optimo-dev', '1');
    et.style.cssText = 'position:absolute;left:0;top:-20px;max-width:90vw;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font:11px/16px monospace;color:#fff;background:#1a73e8;padding:1px 5px;border-radius:3px;pointer-events:none';
    ov.appendChild(et);
    ov._et = et;
    document.documentElement.appendChild(ov);
    return ov;
  }
  function pintar(n) {
    var o = overlay(), r = n.getBoundingClientRect();
    o.style.display = 'block';
    o.style.left = r.left + 'px'; o.style.top = r.top + 'px';
    o.style.width = r.width + 'px'; o.style.height = r.height + 'px';
    if (o._et) {
      o._et.textContent = nodoStr(n) + '  ' + Math.round(r.width) + '×' + Math.round(r.height);
      o._et.style.top = r.top < 22 ? '2px' : '-20px';
    }
  }
  function resaltar(i) {
    try {
      var n = nodo(i);
      if (!n || n.nodeType !== 1) { quitarResalte(); return 'no'; }
      var r = n.getBoundingClientRect();
      if (r.top < 0 || r.bottom > innerHeight) n.scrollIntoView({ block: 'center', inline: 'center' });
      pintar(n);
      return 'ok';
    } catch (e) { return 'Error: ' + e.message; }
  }
  function quitarResalte() {
    try { if (ov) ov.style.display = 'none'; } catch (e) { }
    return 'ok';
  }

  var eligiendo = false, elegidoId = 0;
  var EVS = ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'touchstart', 'touchend', 'click'];
  function enPunto(e) {
    var t = (e.changedTouches && e.changedTouches[0]) || (e.touches && e.touches[0]) || e;
    var x = document.elementFromPoint(t.clientX, t.clientY);
    return (x && !propio(x)) ? x : null;
  }
  function manejador(e) {
    try {
      var el = enPunto(e);
      if (el) pintar(el);
      e.stopImmediatePropagation();
      if (e.type === 'click') {
        e.preventDefault();
        if (el) elegidoId = idDe(el);
        elegir(false);
      }
    } catch (x) { }
  }
  function elegir(on) {
    on = !!on;
    if (on === eligiendo) return 'ok';
    eligiendo = on;
    EVS.forEach(function (ev) {
      if (on) window.addEventListener(ev, manejador, { capture: true, passive: false });
      else window.removeEventListener(ev, manejador, { capture: true });
    });
    return 'ok';
  }
  function elegido() {
    var x = elegidoId;
    elegidoId = 0;
    return String(x);
  }

  // ------------------------------------------------------------ tareas asíncronas (consulta por sondeo)
  var trabajos = {}, tn = 0;
  function lanzar(fn) {
    var id = ++tn;
    trabajos[id] = { ok: false };
    try {
      Promise.resolve(fn()).then(function (v) { trabajos[id] = { ok: true, v: v }; },
        function (e) { trabajos[id] = { ok: true, err: String((e && e.message) || e) }; });
    } catch (e) { trabajos[id] = { ok: true, err: String(e) }; }
    return id;
  }
  function leer(id) {
    var t = trabajos[id];
    if (!t) return JSON.stringify({ err: 'La tarea ya no existe' });
    if (!t.ok) return JSON.stringify({ pend: 1 });
    delete trabajos[id];
    return JSON.stringify(t.err ? { err: t.err } : { v: t.v });
  }
  function traer(url) {
    return lanzar(function () {
      return fetch(url, { credentials: 'include' }).then(function (r) {
        return r.text().then(function (t) {
          return { st: r.status, ct: r.headers.get('content-type') || '', tam: t.length, t: t.slice(0, 150000) };
        });
      });
    });
  }
  function bd() {
    return lanzar(function () {
      var res = { idb: [], caches: [], sw: [] }, ps = [];
      if (window.indexedDB && indexedDB.databases) ps.push(indexedDB.databases().then(function (l) { res.idb = l.map(function (d) { return d.name + ' (v' + d.version + ')'; }); }));
      if (window.caches && caches.keys) ps.push(caches.keys().then(function (l) { res.caches = l; }));
      if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) ps.push(navigator.serviceWorker.getRegistrations().then(function (l) { res.sw = l.map(function (r) { return r.scope; }); }));
      return Promise.all(ps.map(function (p) { return p.catch(function () { }); })).then(function () { return res; });
    });
  }

  // ------------------------------------------------------------ evaluar, almacenamiento, info
  function ev(code) {
    var r;
    try { r = (0, eval)(code); }
    catch (e) {
      if (e instanceof EvalError || /unsafe-eval|Content Security Policy/i.test(String(e && e.message))) return JSON.stringify({ csp: 1 });
      return JSON.stringify({ err: corto((e && e.stack) ? e.stack : String(e), 1500) });
    }
    try {
      if (r && typeof r.then === 'function') {
        return JSON.stringify({ p: lanzar(function () { return r.then(function (v) { return ser(v, 0, []); }); }) });
      }
      return JSON.stringify({ r: ser(r, 0, []) });
    } catch (e) { return JSON.stringify({ r: String(r) }); }
  }
  function almacen() {
    var r = { ls: [], ss: [] };
    try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); r.ls.push([k, corto(localStorage.getItem(k), 800)]); } } catch (e) { r.lsErr = String(e.message || e); }
    try { for (var j = 0; j < sessionStorage.length; j++) { var k2 = sessionStorage.key(j); r.ss.push([k2, corto(sessionStorage.getItem(k2), 800)]); } } catch (e) { r.ssErr = String(e.message || e); }
    return JSON.stringify(r);
  }
  function store(tipo) { return tipo === 'ss' ? sessionStorage : localStorage; }
  function almSet(tipo, k, v) { try { store(tipo).setItem(k, v); return 'ok'; } catch (e) { return 'Error: ' + e.message; } }
  function almDel(tipo, k) { try { store(tipo).removeItem(k); return 'ok'; } catch (e) { return 'Error: ' + e.message; } }
  function almVer(tipo, k) { try { return store(tipo).getItem(k) || ''; } catch (e) { return ''; } }

  function fuentes() {
    var sc = [], es = [], inl = 0, inlE = 0, i;
    var ss = document.scripts;
    for (i = 0; i < ss.length; i++) { if (ss[i].src) sc.push(ss[i].src); else inl++; }
    var hs = document.styleSheets;
    for (i = 0; i < hs.length; i++) { if (hs[i].href) es.push(hs[i].href); else inlE++; }
    return JSON.stringify({ scripts: sc, estilos: es, inlineScripts: inl, inlineEstilos: inlE });
  }
  function rend() {
    var o = {};
    o.url = location.href; o.titulo = document.title; o.estado = document.readyState;
    o.cod = document.characterSet; o.tipo = document.contentType;
    o.vp = innerWidth + '×' + innerHeight; o.dpr = devicePixelRatio;
    o.zoom = window.visualViewport ? window.visualViewport.scale : 1;
    o.nodos = document.getElementsByTagName('*').length; o.scripts = document.scripts.length;
    o.seguro = window.isSecureContext; o.online = navigator.onLine; o.ua = navigator.userAgent;
    try {
      var nav = performance.getEntriesByType('navigation')[0];
      if (nav) {
        var r = function (x) { return Math.round(x); };
        o.nav = {
          dns: r(nav.domainLookupEnd - nav.domainLookupStart), tcp: r(nav.connectEnd - nav.connectStart),
          tls: nav.secureConnectionStart > 0 ? r(nav.connectEnd - nav.secureConnectionStart) : 0,
          ttfb: r(nav.responseStart - nav.requestStart), desc: r(nav.responseEnd - nav.responseStart),
          dcl: r(nav.domContentLoadedEventEnd), carga: r(nav.loadEventEnd),
          proto: nav.nextHopProtocol || '', tam: nav.transferSize || 0
        };
      }
      performance.getEntriesByType('paint').forEach(function (p) { if (p.name === 'first-contentful-paint') o.fcp = Math.round(p.startTime); });
    } catch (e) { }
    if (lcpMs) o.lcp = lcpMs;
    o.cls = Math.round(clsVal * 1000) / 1000;
    try { if (performance.memory) o.mem = { u: performance.memory.usedJSHeapSize, t: performance.memory.totalJSHeapSize, l: performance.memory.jsHeapSizeLimit }; } catch (e) { }
    return JSON.stringify(o);
  }

  // ------------------------------------------------------------ lectura de los registros
  function consola(desde) {
    var out = [];
    for (var i = 0; i < cons.length; i++) if (cons[i].i > desde) out.push(cons[i]);
    return JSON.stringify(out);
  }
  function redLista(desde) {
    var out = [];
    for (var i = 0; i < red.length; i++) {
      var e = red[i];
      if (e.v > desde) out.push({ i: e.i, ts: e.ts, tipo: e.tipo, m: e.m, u: e.u, st: e.st, ms: e.ms, tam: e.tam, err: e.err || '', pend: e.pend ? 1 : 0, ct: e.ct || '', proto: e.proto || '' });
    }
    return JSON.stringify({ v: ver, e: out });
  }
  function redDetalle(i) {
    for (var k = 0; k < red.length; k++) {
      var e = red[k];
      if (e.i === i) return JSON.stringify({ i: e.i, u: e.u, fu: e.fu || '', m: e.m, st: e.st, ct: e.ct || '', tipo: e.tipo, rq: e.rq || {}, rs: e.rs || {}, cuerpo: e.cuerpo || '', cuerpoTam: e.cuerpoTam || 0, corte: e.corte || 0, err: e.err || '', n: e.n || 0, cierre: e.cierre || 0, rt: e.rt || 0 });
    }
    return JSON.stringify({ err: 'Entrada no encontrada' });
  }
  function limpiarConsola() { cons.length = 0; return 'ok'; }
  function limpiarRed() { red.length = 0; return 'ok'; }

  // ------------------------------------------------------------ auditoría (Óptimo Inspector)
  // Observadores propios y siempre activos (no dependen de la pausa del panel). Con buffered:true recogen también lo
  // ocurrido antes de inyectar el agente; la excepción son los recursos (el búfer del navegador guarda unos 250).
  var AU_T0 = Math.round(performance.now());
  var auRecs = [], auTareas = [], auFrames = [], auSaltos = [], auLcp = null, auRt0 = 0;
  var AU_SOP = {};
  try { auRt0 = performance.getEntriesByType('resource').length; } catch (e) { }
  try {
    var sup = PerformanceObserver.supportedEntryTypes || [];
    AU_SOP = { loaf: sup.indexOf('long-animation-frame') >= 0, tareas: sup.indexOf('longtask') >= 0, saltos: sup.indexOf('layout-shift') >= 0, lcp: sup.indexOf('largest-contentful-paint') >= 0 };
  } catch (e) { }

  function auRec(x) {
    try {
      var u = x.name;
      if (/^(data|blob):/.test(u) || auRecs.length >= 1500) return;
      var hn = '', og = '';
      try { var uu = new URL(u); hn = uu.hostname; og = uu.origin; } catch (e) { }
      var it = x.initiatorType || '';
      auRecs.push({
        u: u, hn: hn, og: og, t: (it === 'fetch' || it === 'xmlhttprequest') ? 'fetch' : tipoRt(it, u),
        tam: x.transferSize || 0, enc: x.encodedBodySize || 0, dec: x.decodedBodySize || 0,
        ini: Math.round(x.startTime), fin: Math.round(x.responseEnd), ms: Math.round(x.duration),
        p: x.nextHopProtocol || '', rb: x.renderBlockingStatus || '', st: x.responseStatus || 0,
        red: Math.max(0, Math.round((x.redirectEnd || 0) - (x.redirectStart || 0)))
      });
    } catch (e) { }
  }
  try { new PerformanceObserver(function (l) { l.getEntries().forEach(auRec); }).observe({ type: 'resource', buffered: true }); } catch (e) { }
  try {
    new PerformanceObserver(function (l) {
      l.getEntries().forEach(function (x) { if (auTareas.length < 400) auTareas.push({ t: Math.round(x.startTime), d: Math.round(x.duration) }); });
    }).observe({ type: 'longtask', buffered: true });
  } catch (e) { }
  try {
    new PerformanceObserver(function (l) {
      l.getEntries().forEach(function (x) {
        if (auFrames.length >= 200) return;
        var sc = [];
        (x.scripts || []).forEach(function (s) { sc.push({ u: s.sourceURL || '', f: s.sourceFunctionName || '', i: s.invoker || '', d: Math.round(s.duration) }); });
        auFrames.push({ t: Math.round(x.startTime), d: Math.round(x.duration), b: Math.round(x.blockingDuration || 0), s: sc });
      });
    }).observe({ type: 'long-animation-frame', buffered: true });
  } catch (e) { }
  function auRect(r) { return r ? { y: Math.round(r.y), h: Math.round(r.height), w: Math.round(r.width) } : null; }
  try {
    new PerformanceObserver(function (l) {
      l.getEntries().forEach(function (x) {
        if (auSaltos.length >= 200) return;
        var f = [];
        (x.sources || []).slice(0, 3).forEach(function (s) {
          var n = s.node, id = 0, d = '', tag = '', sinDim = false;
          if (n && n.nodeType === 1) {
            try {
              id = idDe(n); d = recortar(nodoStr(n), 80); tag = n.localName;
              sinDim = tag === 'img' && !(n.getAttribute('width') && n.getAttribute('height'));
            } catch (e) { }
          }
          f.push({ id: id, d: d, tag: tag, sd: sinDim, p: auRect(s.previousRect), c: auRect(s.currentRect) });
        });
        auSaltos.push({ t: Math.round(x.startTime), v: x.value, ent: !!x.hadRecentInput, s: f });
      });
    }).observe({ type: 'layout-shift', buffered: true });
  } catch (e) { }
  try {
    new PerformanceObserver(function (l) {
      var es = l.getEntries(), x = es[es.length - 1];
      if (!x) return;
      var n = x.element, o = { t: Math.round(x.startTime), u: x.url || '', id: 0, d: '', tag: '', lazy: false };
      if (n && n.nodeType === 1) {
        try { o.id = idDe(n); o.d = recortar(nodoStr(n), 80); o.tag = n.localName; o.lazy = n.getAttribute('loading') === 'lazy'; } catch (e) { }
      }
      auLcp = o;
    }).observe({ type: 'largest-contentful-paint', buffered: true });
  } catch (e) { }

  var AU_SUF2 = /^(co|com|org|net|gov|edu|ac)\.[a-z]{2}$/;
  function sitioDe(h) {
    if (!h) return '';
    if (/^[\d.]+$/.test(h) || h.indexOf(':') >= 0) return h;
    var p = h.split('.');
    if (p.length <= 2) return h;
    return AU_SUF2.test(p.slice(-2).join('.')) ? p.slice(-3).join('.') : p.slice(-2).join('.');
  }
  var AU_CDN = /(^|\.)(googleapis|gstatic|cloudflare|jsdelivr|unpkg|bootstrapcdn|jquery|fontawesome|cloudfront|akamaihd|fastly|azureedge|cdn77|stackpath|bunnycdn)\.(com|net|org|io)$/;
  function nom(u) {
    try {
      var x = new URL(u), s = x.pathname.split('/').filter(Boolean).pop();
      return recortar(s ? decodeURIComponent(s) : x.hostname, 40);
    } catch (e) { return recortar(String(u), 40); }
  }
  function fB(b) { return b < 1024 ? b + ' B' : (b < 1048576 ? (b / 1024).toFixed(1) + ' KB' : (b / 1048576).toFixed(1) + ' MB'); }
  function fT(ms) { return ms < 1000 ? Math.round(ms) + ' ms' : (ms / 1000).toFixed(2) + ' s'; }
  function sem(v, bien, mal) { return v <= bien ? 0 : (v <= mal ? 1 : 2); }
  var AU_BIN = /\.(png|jpe?g|gif|webp|avif|ico|bmp|woff2?|ttf|otf|eot|mp[34]|m4a|webm|ogg|wasm|zip|gz|br|pdf|bin)$/i;
  function pesoDe(r) { return r.tam || r.enc || 0; }

  // Devuelve un JSON con métricas, desglose y hallazgos ordenados por impacto (sev: 2 mal, 1 mejorable, 0 aviso).
  function auditar() {
    var R = auRecs, hall = [], i, k;
    var host = location.hostname, sitioP = sitioDe(host);
    var nav = null, fcp = 0;
    try { nav = performance.getEntriesByType('navigation')[0]; } catch (e) { }
    try { performance.getEntriesByType('paint').forEach(function (p) { if (p.name === 'first-contentful-paint') fcp = Math.round(p.startTime); }); } catch (e) { }
    var ttfb = nav ? Math.round(nav.responseStart) : 0;
    var lcp = auLcp ? auLcp.t : lcpMs;

    // CLS por ventanas de sesión (como lo mide Chrome): saltos separados por menos de 1 s y ventanas de hasta 5 s.
    var cls = 0, win = 0, wIni = 0, ult = 0, nSaltos = 0;
    auSaltos.forEach(function (s) {
      if (s.ent) return;
      nSaltos++;
      if (win && (s.t - ult > 1000 || s.t - wIni > 5000)) win = 0;
      if (!win) wIni = s.t;
      win += s.v; ult = s.t;
      if (win > cls) cls = win;
    });
    cls = Math.round(cls * 1000) / 1000;

    // Tiempo total de bloqueo (TBT): lo que pasa de 50 ms en cada tarea larga tras el primer contenido.
    var tbt = 0, tareasN = 0, tareaMax = 0;
    if (AU_SOP.tareas) {
      auTareas.forEach(function (t) { if (t.t >= fcp) { tbt += Math.max(0, t.d - 50); tareasN++; if (t.d > tareaMax) tareaMax = t.d; } });
    } else if (AU_SOP.loaf) {
      auFrames.forEach(function (f) { if (f.t >= fcp) { tbt += f.b; tareasN++; if (f.d > tareaMax) tareaMax = f.d; } });
    }

    // Recursos: peso por tipo, dominios, terceros.
    var porTipo = {}, byUrl = {}, origenes = {}, T = {}, peso = 0, opacos = 0;
    R.forEach(function (r) {
      var w = pesoDe(r), o = porTipo[r.t] || (porTipo[r.t] = { t: r.t, n: 0, tam: 0 });
      o.n++; o.tam += w; peso += w;
      if (!byUrl[r.u]) byUrl[r.u] = r;
      if (r.og) origenes[r.og] = 1;
      var s = sitioDe(r.hn);
      r.ter = !!s && s !== sitioP;
      if (r.ter && r.tam === 0 && r.enc === 0 && r.dec === 0 && r.fin > 0) { opacos++; r.opaco = true; }
      if (r.ter) {
        var q = T[s] || (T[s] = { s: s, n: 0, tam: 0, ms: 0, js: 0, op: 0, cdn: AU_CDN.test(s) ? 1 : 0 });
        q.n++; q.tam += w; if (r.ms > q.ms) q.ms = r.ms; if (r.t === 'js') q.js++; if (r.opaco) q.op++;
      }
    });
    var docTam = nav ? (nav.transferSize || nav.encodedBodySize || 0) : 0;
    if (docTam) { var od = porTipo.doc || (porTipo.doc = { t: 'doc', n: 0, tam: 0 }); od.tam += docTam; od.n++; peso += docTam; }
    var terceros = Object.keys(T).map(function (s) { return T[s]; }).sort(function (a, b) { return (b.n - a.n) || (b.tam - a.tam); });
    var nodos = document.getElementsByTagName('*').length;

    function add(sev, cat, id, titulo, det, extra) {
      var x = { sev: sev, cat: cat, id: id, t: titulo, d: det };
      if (extra) for (var kk in extra) x[kk] = extra[kk];
      hall.push(x);
    }

    // ---- LCP: en qué fase se pierde el tiempo
    var lcpDet = null;
    if (lcp) {
      lcpDet = { ttfb: ttfb, retardo: 0, carga: 0, render: 0, texto: 0 };
      var rl = null;
      if (auLcp && auLcp.u) { for (i = 0; i < R.length; i++) { if (R[i].u === auLcp.u) { rl = R[i]; break; } } }
      if (rl) {
        lcpDet.retardo = Math.max(0, rl.ini - ttfb); lcpDet.carga = Math.max(0, rl.fin - rl.ini); lcpDet.render = Math.max(0, lcp - rl.fin);
        lcpDet.tam = pesoDe(rl);
      } else { lcpDet.texto = 1; lcpDet.render = Math.max(0, lcp - ttfb); }
    }
    var sLcp = lcp ? sem(lcp, 2500, 4000) : 0;
    if (sLcp > 0) {
      var fase = 'ttfb', mayor = lcpDet.ttfb;
      if (lcpDet.retardo > mayor) { fase = 'retardo'; mayor = lcpDet.retardo; }
      if (lcpDet.carga > mayor) { fase = 'carga'; mayor = lcpDet.carga; }
      if (lcpDet.render > mayor) { fase = 'render'; mayor = lcpDet.render; }
      var txt = {
        ttfb: 'La mayor parte (' + fT(mayor) + ') se va esperando la primera respuesta del servidor.',
        retardo: 'El navegador tardó ' + fT(mayor) + ' en empezar a pedir el elemento principal: lo descubre tarde o espera a otros recursos.',
        carga: 'El elemento principal tardó ' + fT(mayor) + ' en descargarse' + (lcpDet.tam ? ' (' + fB(lcpDet.tam) + ')' : '') + '.',
        render: lcpDet.texto ? 'Hasta pintar el texto principal pasan ' + fT(mayor) + ': suele ser CSS, fuentes o scripts que bloquean.' : 'Tras llegar, tardó ' + fT(mayor) + ' en pintarse (CSS o scripts bloqueando).'
      }[fase];
      add(sLcp === 2 ? 2 : 1, 'velocidad', 'lcp', 'Contenido principal lento: ' + fT(lcp), txt + (auLcp && auLcp.lazy ? ' Además la imagen tiene loading="lazy", lo que la retrasa.' : ''), { w: lcp, nodo: auLcp ? auLcp.id : 0 });
    }
    if (fcp && sem(fcp, 1800, 3000) > 0) {
      var nb = R.filter(function (r) { return r.rb === 'blocking'; }).length;
      add(sem(fcp, 1800, 3000), 'velocidad', 'fcp', 'Tarda en aparecer algo: ' + fT(fcp), 'Primer contenido visible. Respuesta del servidor: ' + fT(ttfb) + (nb ? '; ' + nb + ' recursos bloquean el pintado.' : '.'), { w: fcp });
    }
    if (ttfb && sem(ttfb, 800, 1800) > 0) {
      var rd = nav && nav.redirectCount ? ' La página redirigió ' + nav.redirectCount + ' vez/veces antes de cargar.' : '';
      add(sem(ttfb, 800, 1800), 'velocidad', 'ttfb', 'El servidor responde despacio: ' + fT(ttfb), 'Es tiempo de red y servidor, no de tu navegador.' + rd, { w: ttfb });
    }

    // ---- saltos de diseño
    if (cls > 0.1) {
      add(cls > 0.25 ? 2 : 1, 'diseño', 'cls', 'La página se mueve al cargar: CLS ' + cls, nSaltos + ' saltos. Bajo 0.1 es bueno.', { w: Math.round(cls * 1000) });
    }
    var porNodo = {};
    auSaltos.forEach(function (s) {
      if (s.ent || !s.s.length) return;
      var f = s.s[0];
      for (var z = 0; z < s.s.length; z++) { if (s.s[z].tag && s.s[z].tag !== 'body' && s.s[z].tag !== 'html') { f = s.s[z]; break; } }
      var key = f.id ? 'n' + f.id : 'x' + f.d;
      var o = porNodo[key] || (porNodo[key] = { f: f, v: 0, n: 0 });
      o.v += s.v; o.n++; o.f = f;
    });
    Object.keys(porNodo).map(function (kk) { return porNodo[kk]; }).sort(function (a, b) { return b.v - a.v; }).slice(0, 4).forEach(function (o) {
      if (o.v < 0.02) return;
      var f = o.f, causa;
      var toda = f.tag === 'body' || f.tag === 'html';
      if (toda) causa = 'Toda la página se desplazó: apareció contenido arriba o la página creció' + (f.p && f.c ? ' (' + Math.abs(f.c.h - f.p.h) + ' px de altura, ' + Math.abs(f.c.y - f.p.y) + ' px de desplazamiento).' : '.');
      else if (f.sd) causa = 'Es una imagen sin width/height: el navegador no reserva su espacio hasta que llega.';
      else if (f.tag === 'iframe') causa = 'Contenido incrustado (anuncio, vídeo…) que ocupa sitio al aparecer.';
      else if (f.p && f.c && (f.p.h !== f.c.h || f.p.w !== f.c.w)) causa = 'Cambió de tamaño (' + f.p.w + '×' + f.p.h + ' → ' + f.c.w + '×' + f.c.h + ' px).';
      else if (f.p && f.c) causa = 'Lo empujó contenido que apareció antes (se movió ' + Math.abs(f.c.y - f.p.y) + ' px).';
      else causa = 'El elemento se desplazó.';
      add(o.v >= 0.1 ? 2 : (o.v >= 0.04 ? 1 : 0), 'diseño', 'salto', (toda ? 'Se movió toda la página' : 'Se movió: ' + (f.d || 'un elemento que ya no existe')) + ' (+' + o.v.toFixed(3) + ')', causa + (o.n > 1 ? ' ' + o.n + ' veces.' : ''), { w: Math.round(o.v * 1000), nodo: toda ? 0 : f.id });
    });

    // ---- scripts que consumen tiempo
    if (sem(tbt, 200, 600) > 0) {
      add(sem(tbt, 200, 600), 'scripts', 'tbt', 'Scripts bloquean la página: ' + fT(tbt), tareasN + ' tareas largas (la peor ' + fT(tareaMax) + '). Mientras duran, la página no responde a toques.', { w: tbt });
    }
    var porScript = {};
    auFrames.forEach(function (f) {
      f.s.forEach(function (s) {
        var u = (!s.u || s.u.split('#')[0] === location.href.split('#')[0]) ? '(en línea)' : s.u, o = porScript[u] || (porScript[u] = { u: u, d: 0, n: 0, inv: s.i });
        o.d += s.d; o.n++;
      });
    });
    Object.keys(porScript).map(function (kk) { return porScript[kk]; }).sort(function (a, b) { return b.d - a.d; }).slice(0, 5).forEach(function (o) {
      if (o.d < 100) return;
      var hn = '', ter = false;
      try { hn = new URL(o.u).hostname; ter = sitioDe(hn) !== sitioP; } catch (e) { }
      add(o.d >= 300 ? 2 : 1, 'scripts', 'script', 'Script lento: ' + (o.u === '(en línea)' ? 'script en línea' : nom(o.u)) + ' (' + fT(o.d) + ' de CPU)',
        o.n + (o.n === 1 ? ' ejecución larga' : ' ejecuciones largas') + (hn ? ' · ' + hn + (ter ? ' (tercero)' : '') : '') + (o.inv && !/^https?:/.test(o.inv) ? ' · ' + recortar(o.inv, 40) : ''),
        { w: o.d, host: ter ? sitioDe(hn) : '', u: o.u });
    });
    var jsR = R.filter(function (r) { return r.t === 'js'; }), jsPeso = 0;
    jsR.forEach(function (r) { jsPeso += pesoDe(r); });
    if (jsPeso >= 500 * 1024) {
      var top = jsR.slice().sort(function (a, b) { return pesoDe(b) - pesoDe(a); }).slice(0, 3).map(function (r) { return nom(r.u) + ' ' + fB(pesoDe(r)); }).join(', ');
      add(jsPeso >= 1536 * 1024 ? 2 : 1, 'scripts', 'jspeso', 'Mucho JavaScript: ' + fB(jsPeso) + ' en ' + jsR.length + ' archivos', 'Los mayores: ' + top + '.', { w: jsPeso });
    }

    // ---- imágenes
    var imgs = [], im;
    try { imgs = Array.prototype.slice.call(document.images, 0, 400); } catch (e) { }
    var dpr = window.devicePixelRatio || 1;
    function imgDe(u) { for (var q = 0; q < imgs.length; q++) { if (imgs[q].currentSrc === u || imgs[q].src === u) return imgs[q]; } return null; }
    R.filter(function (r) { return r.t === 'img' && pesoDe(r) >= 200 * 1024; }).sort(function (a, b) { return pesoDe(b) - pesoDe(a); }).slice(0, 5).forEach(function (r) {
      var ext = '';
      try { ext = (new URL(r.u).pathname.split('.').pop() || '').toLowerCase(); } catch (e) { }
      var viejo = /^(jpe?g|png|gif|bmp)$/.test(ext);
      var el = imgDe(r.u);
      add(pesoDe(r) >= 1024 * 1024 ? 2 : 1, 'imágenes', 'imgpesada', 'Imagen pesada: ' + nom(r.u) + ' (' + fB(pesoDe(r)) + ')',
        r.hn + (viejo ? ' · formato .' + ext + ': en WebP o AVIF pesaría bastante menos' : ''), { w: pesoDe(r), nodo: el ? idDe(el) : 0, u: r.u, host: r.ter ? sitioDe(r.hn) : '' });
    });
    var grandes = [];
    for (k = 0; k < imgs.length; k++) {
      im = imgs[k];
      if (!im.complete || !im.naturalWidth || !im.clientWidth || !im.clientHeight) continue;
      var util = im.clientWidth * dpr, ratio = im.naturalWidth / util;
      var rr = byUrl[im.currentSrc], tam = rr ? pesoDe(rr) : 0;
      if (ratio >= 2 && im.naturalWidth >= 500 && (tam >= 60 * 1024 || (!tam && im.naturalWidth * im.naturalHeight > 1500000))) grandes.push({ im: im, ratio: ratio, tam: tam, util: Math.round(util) });
    }
    grandes.sort(function (a, b) { return (b.tam || 0) - (a.tam || 0); }).slice(0, 4).forEach(function (g) {
      add(1, 'imágenes', 'imggrande', 'Imagen más grande de lo necesario: ' + nom(g.im.currentSrc || g.im.src),
        'Mide ' + g.im.naturalWidth + '×' + g.im.naturalHeight + ' y se muestra a unos ' + g.util + ' px de ancho útil' + (g.tam ? ' · ' + fB(g.tam) : '') + '.', { w: g.tam || 1, nodo: idDe(g.im) });
    });
    if (auLcp && auLcp.lazy) add(2, 'imágenes', 'lcplazy', 'La imagen principal se carga en diferido', 'Tiene loading="lazy" pero es lo primero que se ve: retrasa el contenido principal.', { w: 5000, nodo: auLcp.id });
    var lejos = [], lejosTam = 0;
    for (k = 0; k < imgs.length; k++) {
      im = imgs[k];
      if (im.loading === 'lazy' || !im.complete) continue;
      var rc = im.getBoundingClientRect(), rs = byUrl[im.currentSrc], ts = rs ? pesoDe(rs) : 0;
      if (ts >= 100 * 1024 && rc.top + (window.pageYOffset || 0) > innerHeight * 2) { lejos.push(im); lejosTam += ts; }
    }
    if (lejos.length) add(lejosTam >= 300 * 1024 ? 1 : 0, 'imágenes', 'lejos', lejos.length + ' imágenes lejos de la pantalla se cargan ya (' + fB(lejosTam) + ')', 'Podrían cargarse al llegar a ellas (loading="lazy") y ahorrar datos al abrir.', { w: lejosTam, nodo: idDe(lejos[0]) });

    // ---- bloqueo de pintado, conexiones y recursos innecesarios
    var bloq = R.filter(function (r) { return r.rb === 'blocking'; });
    if (bloq.length) {
      var finB = 0;
      bloq.forEach(function (r) { if (r.fin > finB) finB = r.fin; });
      if (finB > 1000) {
        var nomB = bloq.slice().sort(function (a, b) { return b.fin - a.fin; }).slice(0, 3).map(function (r) { return nom(r.u) + (r.ter ? ' (' + sitioDe(r.hn) + ')' : ''); }).join(', ');
        add(finB > 2500 ? 2 : 1, 'velocidad', 'bloqueantes', bloq.length + ' recursos bloquean el pintado hasta ' + fT(finB), 'Hasta que llegan, la pantalla se queda en blanco: ' + nomB + '.', { w: finB });
      }
    }
    if (terceros.length >= 15) add(terceros.length >= 30 ? 2 : 1, 'conexiones', 'dominios', 'Muchos dominios distintos: ' + terceros.length + ' terceros', 'Cada dominio nuevo cuesta DNS + conexión + TLS antes de descargar nada.', { w: terceros.length * 100 });
    terceros.slice(0, 6).forEach(function (q) {
      if (q.n < 10 && q.tam < 300 * 1024 && q.js < 3) return;
      add(q.n >= 25 || q.tam >= 1024 * 1024 ? 2 : 1, 'conexiones', 'tercero', 'Tercero: ' + q.s + ' (' + q.n + ' peticiones' + (q.tam ? ', ' + fB(q.tam) : '') + ')',
        (q.js ? q.js + ' scripts · ' : '') + (q.op ? 'peso desconocido en ' + q.op + ' (el dominio no lo permite) · ' : '') + 'la más lenta tardó ' + fT(q.ms) + '.', { w: q.tam + q.n * 2000, host: q.s, cdn: q.cdn });
    });
    var h1 = {};
    R.forEach(function (r) { if (r.p === 'http/1.1') { h1[r.og] = (h1[r.og] || 0) + 1; } });
    Object.keys(h1).forEach(function (og) {
      if (h1[og] >= 8) add(1, 'conexiones', 'http1', h1[og] + ' peticiones por HTTP/1.1 a ' + og.replace(/^https?:\/\//, ''), 'Sin HTTP/2 las peticiones se hacen en tandas de 6: va más lento con muchos archivos.', { w: h1[og] * 300 });
    });
    var sinC = R.filter(function (r) { return (r.t === 'js' || r.t === 'css' || r.t === 'doc' || r.t === 'fetch') && r.dec >= 10240 && r.enc > 0 && r.enc >= r.dec * 0.95 && !AU_BIN.test(r.u.split('?')[0]); });
    if (sinC.length) {
      var sumC = 0;
      sinC.forEach(function (r) { sumC += r.dec; });
      add(sumC >= 100 * 1024 ? 1 : 0, 'recursos', 'sincomp', sinC.length + ' archivos de texto sin comprimir (' + fB(sumC) + ')',
        sinC.slice().sort(function (a, b) { return b.dec - a.dec; }).slice(0, 3).map(function (r) { return nom(r.u) + ' ' + fB(r.dec); }).join(', ') + '. Con gzip o brotli pesarían bastante menos.', { w: sumC });
    }
    var cuenta = {};
    R.forEach(function (r) { var c = cuenta[r.u] || (cuenta[r.u] = { r: r, n: 0, tam: 0 }); c.n++; c.tam += pesoDe(r); });
    Object.keys(cuenta).map(function (kk) { return cuenta[kk]; }).filter(function (c) { return c.n >= 2; }).sort(function (a, b) { return b.tam - a.tam; }).slice(0, 4).forEach(function (c) {
      var desp = c.tam - c.tam / c.n;
      add(c.r.t !== 'fetch' && desp >= 50 * 1024 ? 1 : 0, 'recursos', 'duplicado', 'Descargado ' + c.n + ' veces: ' + nom(c.r.u), (desp ? 'Se repiten ' + fB(desp) + ' · ' : '') + (c.r.hn || ''), { w: desp || c.n, host: c.r.ter ? sitioDe(c.r.hn) : '' });
    });
    var redS = 0, redN = 0;
    R.forEach(function (r) { if (r.red > 0) { redS += r.red; redN++; } });
    if (redN && redS >= 300) add(1, 'recursos', 'redir', redN + ' recursos pasan por redirecciones (' + fT(redS) + ')', 'Cada redirección es una ida y vuelta extra al servidor.', { w: redS });
    if (nodos >= 1500) add(nodos >= 3000 ? 2 : 1, 'diseño', 'dom', 'Página muy grande: ' + nodos + ' elementos', 'Más elementos = más memoria y cada cambio de diseño tarda más.', { w: nodos });

    hall.sort(function (a, b) { return (b.sev - a.sev) || ((b.w || 0) - (a.w || 0)); });
    var mas = function (a, n) { return a.slice(0, n).map(function (r) { return { u: r.u, t: r.t, tam: pesoDe(r), ms: r.ms, hn: r.hn, ter: r.ter ? 1 : 0 }; }); };
    return JSON.stringify({
      v: 1, url: location.href, host: host, ahora: Math.round(performance.now()), t0: AU_T0,
      parcial: (AU_T0 > 300 && auRt0 >= 250) ? 1 : 0,
      soporte: { loaf: AU_SOP.loaf ? 1 : 0, tareas: AU_SOP.tareas ? 1 : 0, saltos: AU_SOP.saltos ? 1 : 0, lcp: AU_SOP.lcp ? 1 : 0 },
      m: {
        lcp: lcp, fcp: fcp, ttfb: ttfb, cls: cls, tbt: tbt, dcl: nav ? Math.round(nav.domContentLoadedEventEnd) : 0, carga: nav ? Math.round(nav.loadEventEnd) : 0,
        nodos: nodos, peticiones: R.length + 1, peso: peso, opacos: opacos, dominios: Object.keys(origenes).length, terceros: terceros.length,
        sem: { lcp: lcp ? sem(lcp, 2500, 4000) : -1, fcp: fcp ? sem(fcp, 1800, 3000) : -1, ttfb: ttfb ? sem(ttfb, 800, 1800) : -1, cls: sem(cls, 0.1, 0.25), tbt: sem(tbt, 200, 600) }
      },
      lcpDet: lcpDet,
      tipos: Object.keys(porTipo).map(function (t) { return porTipo[t]; }).sort(function (a, b) { return b.tam - a.tam; }),
      terceros: terceros.slice(0, 12),
      pesados: mas(R.slice().sort(function (a, b) { return pesoDe(b) - pesoDe(a); }), 8),
      lentos: mas(R.filter(function (r) { return r.ms >= 800; }).sort(function (a, b) { return b.ms - a.ms; }), 6),
      tareas: { n: tareasN, max: tareaMax },
      h: hall.slice(0, 40)
    });
  }

  var api = {
    consola: consola, red: redLista, detalleRed: redDetalle, limpiarConsola: limpiarConsola, limpiarRed: limpiarRed,
    hijos: hijos, camino: camino, info: info, estilos: estilos,
    attr: attr, html: html, texto: texto, borrar: borrar, estiloAdd: estiloAdd, css: css, cssActual: cssActual,
    selGuardar: selGuardar, xpath: xpath, duplicar: duplicar, pausar: pausar,
    guardable: guardable, olvidar: olvidar, deshacerEstilos: deshacerEstilos, ajustarPx: ajustarPx,
    resaltar: resaltar, quitarResalte: quitarResalte, elegir: elegir, elegido: elegido,
    ev: ev, almacen: almacen, almSet: almSet, almDel: almDel, almVer: almVer,
    fuentes: fuentes, rend: rend, traer: traer, bd: bd, leer: leer, auditar: auditar
  };
  try {
    Object.defineProperty(window, '__optimoDev', { value: api, enumerable: false, configurable: false, writable: false });
  } catch (e) { }
})();
