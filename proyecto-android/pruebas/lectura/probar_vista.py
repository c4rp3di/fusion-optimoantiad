import sys
from playwright.sync_api import sync_playwright
JS=open(sys.argv[1],encoding="utf-8").read().strip()
L="Esta es una frase de ejemplo bastante larga para el articulo, con comas, y mas texto. "*6
DESK="""(function(){var m=document.querySelectorAll('meta[name="viewport"]'); if(!m.length){var x=document.createElement('meta');x.name='viewport';(document.head||document.documentElement).appendChild(x);m=[x];} for(var i=0;i<m.length;i++) m[i].setAttribute('content','width=1280');})()"""
HTML={"resp":'<html><head><meta charset=utf-8><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><article><p>'+L+'</p><p>'+L+'</p></article></body></html>',
"nada":'<html><head><meta charset=utf-8></head><body><article><p>'+L+'</p><p>'+L+'</p></article></body></html>',
"divbr":'<html><head><meta charset=utf-8></head><body><div class="post">'+"<br>".join([L]*4)+'</div></body></html>'}
with sync_playwright() as pw:
    b=pw.chromium.launch()
    ctx=b.new_context(viewport={"width":412,"height":800},is_mobile=True,has_touch=True,device_scale_factor=2.6)
    for n,h in HTML.items():
        pg=ctx.new_page(); pg.route("http://s.test/**",lambda r,q=None,h=h: r.fulfill(status=200,content_type="text/html; charset=utf-8",body=h))
        pg.goto("http://s.test/"+n)
        w0=pg.evaluate("innerWidth")
        pg.evaluate(DESK); pg.wait_for_timeout(200); w1=pg.evaluate("innerWidth")
        r=pg.evaluate("("+JS+")({tam:19})"); pg.wait_for_timeout(200)
        w2=pg.evaluate("[innerWidth, visualViewport.scale]")
        tl=pg.evaluate("document.querySelector('[data-optimo=lectura]').shadowRoot.querySelector('.art').textContent.length")
        pg.evaluate("window.__optimoLectura.cerrar()"); pg.wait_for_timeout(200)
        w3=pg.evaluate("innerWidth"); met=pg.evaluate("[...document.querySelectorAll('meta[name=viewport]')].map(m=>m.content)")
        print(n,"| inicial",w0,"| escritorio",w1,"| lectura",r,w2,"texto",tl,"| tras cerrar",w3,met)
        pg.close()
    b.close()
