(function () {
  // Recoge los enlaces de la página (anchors y direcciones escritas en el HTML) con el texto que los rodea.
  // La clasificación y los nombres los pone Optimo (Enlaces.kt). No modifica la página y no deja nada global.
  try {
    var MAX = 5000;
    var vistos = {};
    var items = [];

    function limpio(s, n) {
      s = (s || '').replace(/\s+/g, ' ').trim();
      return s.length > n ? s.slice(0, n) : s;
    }

    function anadir(u, t, a, c, h) {
      if (!u || items.length >= MAX || vistos[u]) return;
      vistos[u] = 1;
      items.push({ u: u, t: limpio(t, 200), a: limpio(a, 200), c: limpio(c, 160), h: limpio(h, 120) });
    }

    function contexto(el) {
      var n = el.closest && el.closest('li,tr,dd,dt,p,article,figure,td,div');
      if (!n) return '';
      var s = n.textContent || '';
      return s.length > 1500 ? '' : s;
    }

    // Encabezados en orden del documento: como los enlaces también van en orden, basta un puntero que solo avanza.
    var hs = document.querySelectorAll('h1,h2,h3,h4');
    var j = -1;
    function encabezado(a) {
      while (j + 1 < hs.length && (hs[j + 1].compareDocumentPosition(a) & 4)) j++;
      return j >= 0 ? (hs[j].textContent || '') : '';
    }

    var as = document.querySelectorAll('a[href]');
    for (var i = 0; i < as.length && items.length < MAX; i++) {
      var a = as[i];
      var href = a.href;
      var bruto = a.getAttribute('href') || '';
      if (typeof href !== 'string' || !href || bruto.charAt(0) === '#') continue;
      var pre = href.slice(0, 7).toLowerCase();
      if (pre !== 'magnet:' && pre.slice(0, 4) !== 'http') continue;
      anadir(href, a.innerText || a.textContent, a.title || a.getAttribute('aria-label') || '', contexto(a), encabezado(a));
    }

    // Magnets y enlaces de Mega escritos como texto o dentro de atributos/scripts.
    var html = document.documentElement.outerHTML || '';
    if (html.length > 4000000) html = html.slice(0, 4000000);
    var re = /(magnet:\?xt=urn:bt(?:ih|mh):[^\s"'<>\\]+|https?:\/\/(?:www\.)?mega\.(?:nz|io|co\.nz)\/[^\s"'<>\\]+)/gi;
    var m;
    while ((m = re.exec(html)) && items.length < MAX) {
      var u = m[1].replace(/&amp;/g, '&').replace(/&#0?38;/g, '&').replace(/[.,;:!?)\]}]+$/, '');
      anadir(u, '', '', '', '');
    }

    return JSON.stringify({ titulo: limpio(document.title, 200), url: location.href, items: items });
  } catch (e) {
    return null;
  }
})()
