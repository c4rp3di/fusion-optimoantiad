import http.server, socketserver, time, os, io, random, threading
from PIL import Image

def png(w, h, ruido=True):
    random.seed(w * h)
    im = Image.frombytes('RGB', (w, h), bytes(random.getrandbits(8) for _ in range(w * h * 3))) if ruido else Image.new('RGB', (w, h), (200, 30, 30))
    b = io.BytesIO(); im.save(b, 'PNG'); return b.getvalue()

BIG = png(520, 300)          # ~470 KB (ruido)
HUGE = png(1400, 900)        # grande, se muestra pequeña
SMALL = png(40, 40, False)
HEAVY_JS = (b"/*" + b"x" * 620000 + b"*/\nwindow.__pesado=1;\n")
BUSY_JS = b"var t=Date.now();while(Date.now()-t<450){}\n"


class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _ok(self, ct, data, cors=False):
        self.send_response(200); self.send_header('Content-Type', ct); self.send_header('Content-Length', str(len(data)))
        if cors: self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        p = self.path.split('?')[0]
        if p == '/':
            terc = ''.join('<script async src="http://127.0.0.1:%d/ter%d.js"></script>' % (self.server.server_port, i) for i in range(12))
            html = ('''<!doctype html><html><head><meta charset="utf-8"><title>Prueba</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="/slow.css">
<script src="/heavy.js"></script>
<script src="/dup.js"></script><script src="/dup.js"></script>
</head><body>
<h1>Pagina de prueba</h1>
<img id="principal" src="/big.png?p=1" loading="lazy" width="520" height="300">
<img id="grande" src="/huge.png" style="width:200px">
<div id="contenido"><p>Texto de relleno</p><p>Mas texto</p></div>
<script src="/busy.js"></script>
<script>
setTimeout(function(){var b=document.createElement('div');b.id='banner';b.style.cssText='height:180px;background:#fc0';b.textContent='Banner tardio';document.body.insertBefore(b,document.body.firstChild);},400);
setTimeout(function(){var i=document.createElement('img');i.id='tardia';i.src='/late.png';document.getElementById('contenido').insertBefore(i,document.getElementById('contenido').firstChild);},900);
setTimeout(function(){var t=Date.now();while(Date.now()-t<420){}},2800);
setTimeout(function(){fetch('/big.png?p=1',{cache:'reload'});fetch('/big.png?p=1',{cache:'reload'});},1000);
</script>''' + terc + '</body></html>').encode()
            self._ok('text/html; charset=utf-8', html)
        elif p == '/slow.css': time.sleep(1.6); self._ok('text/css', b'body{font-family:sans-serif}' + b' ' * 12000)
        elif p == '/heavy.js': self._ok('application/javascript', HEAVY_JS)
        elif p == '/dup.js': self._ok('application/javascript', b'window.__d=(window.__d||0)+1;' + b' ' * 60000)
        elif p == '/busy.js': self._ok('application/javascript', BUSY_JS)
        elif p == '/big.png': self._ok('image/png', BIG)
        elif p == '/huge.png': self._ok('image/png', HUGE)
        elif p == '/late.png': time.sleep(0.1); self._ok('image/png', SMALL)
        elif p.startswith('/ter'): self._ok('application/javascript', b'/* tercero */', cors=False)
        else: self.send_response(404); self.end_headers()

class S(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True
if __name__ == '__main__':
    S(('127.0.0.1', 8765), H).serve_forever()
