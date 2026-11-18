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
      agregar(cons, { t: nivel, m: txt, o: origen() });
      try { orig.log.call(console, MARCA + txt); } catch (e) { }
    };
    ['log', 'info', 'warn', 'error', 'debug'].forEach(function (nv) {
      if (!orig[nv]) return;
      console[nv] = function () {
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
        try { var e = nuevaRed('xhr', String(met).toUpperCase(), absoluta(String(url))); e.rq = {}; mapa.set(this, e); } catch (x) { }
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
    var partes = [];
    for (var x = n; x && x.nodeType === 1; x = x.parentNode) {
      var k = 1, s = x;
      while ((s = s.previousElementSibling)) { if (s.localName === x.localName) k++; }
      partes.unshift(x.localName + '[' + k + ']');
      if (x.id && document.getElementById(x.id) === x) { partes[0] = '*[@id="' + x.id.replace(/"/g, '') + '"]'; break; }
    }
    return '//' + partes.join('/');
  }
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
      if (valor === null || valor === undefined) n.removeAttribute(nombre); else n.setAttribute(nombre, valor);
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
      n.style.cssText = (n.style.cssText ? n.style.cssText + ';' : '') + decl;
      return 'ok';
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
    document.documentElement.appendChild(ov);
    return ov;
  }
  function pintar(n) {
    var o = overlay(), r = n.getBoundingClientRect();
    o.style.display = 'block';
    o.style.left = r.left + 'px'; o.style.top = r.top + 'px';
    o.style.width = r.width + 'px'; o.style.height = r.height + 'px';
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

  var api = {
    consola: consola, red: redLista, detalleRed: redDetalle, limpiarConsola: limpiarConsola, limpiarRed: limpiarRed,
    hijos: hijos, camino: camino, info: info, estilos: estilos,
    attr: attr, html: html, texto: texto, borrar: borrar, estiloAdd: estiloAdd, css: css, cssActual: cssActual,
    selGuardar: selGuardar, xpath: xpath, duplicar: duplicar,
    resaltar: resaltar, quitarResalte: quitarResalte, elegir: elegir, elegido: elegido,
    ev: ev, almacen: almacen, almSet: almSet, almDel: almDel, almVer: almVer,
    fuentes: fuentes, rend: rend, traer: traer, bd: bd, leer: leer
  };
  try {
    Object.defineProperty(window, '__optimoDev', { value: api, enumerable: false, configurable: false, writable: false });
  } catch (e) { }
})();
