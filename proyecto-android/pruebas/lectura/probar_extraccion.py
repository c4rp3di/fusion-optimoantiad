import sys, json
from playwright.sync_api import sync_playwright
JS=open(sys.argv[1],encoding="utf-8").read().strip()
LONG=" ".join(["Esta es una frase de ejemplo bastante larga para el artículo, con comas, y más texto."]*6)
PAGES={
"spans":'<html><body><nav>menu <a href="/">inicio</a></nav><article><h1>Titulo</h1><p>Hola <span style="color:red">mundo rojo</span> y <a href="/x"><span>enlace con span</span></a> siguiente. '+LONG+'</p><p>'+LONG+'</p><p>'+LONG+'</p></article></body></html>',
"divbr":'<html><body><div id="menu"><a href="/">a</a> <a href="/b">b</a></div><div class="post">'+ ("<br>".join([LONG]*4)) +'</div></body></html>',
"sinviewport":'<html><body><article><h1>T</h1><p>'+LONG+'</p><p>'+LONG+'</p><p>'+LONG+'</p></article></body></html>',
"conviewport":'<html><head><meta name="viewport" content="width=1280"></head><body><article><h1>T</h1><p>'+LONG+'</p><p>'+LONG+'</p></article></body></html>',
}
with sync_playwright() as pw:
    b=pw.chromium.launch()
    ctx=b.new_context(viewport={"width":412,"height":800},is_mobile=True,has_touch=True,device_scale_factor=2.6)
    for nombre,html in PAGES.items():
        pg=ctx.new_page()
        pg.route("http://sitio.test/**",lambda r,q=None,h=html: r.fulfill(status=200,content_type="text/html",body=h))
        pg.goto("http://sitio.test/"+nombre)
        pre=pg.evaluate("[innerWidth, visualViewport.scale]")
        r=pg.evaluate("("+JS+")({tam:19,tema:'claro',fuente:'serif',inter:1})")
        pg.wait_for_timeout(300)
        info=pg.evaluate("""()=>{const h=document.querySelector('[data-optimo=lectura]'); if(!h) return null;
          const art=h.shadowRoot.querySelector('.art'); const ps=[...art.querySelectorAll('p')].map(p=>p.outerHTML.slice(0,160));
          return {nP:ps.length, nested:art.querySelectorAll('p p, a p').length, first:ps.slice(0,3), w:innerWidth, scale:visualViewport.scale}}""")
        print(nombre,"->",r,"pre(innerWidth,scale)=",pre)
        print("   ",json.dumps(info,ensure_ascii=False))
        pg.close()
    b.close()
