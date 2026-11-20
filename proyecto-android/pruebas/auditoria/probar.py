import json, os, subprocess, time, sys
from playwright.sync_api import sync_playwright
srv = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'servidor.py')])
time.sleep(1.2)
agente = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets', 'devtools', 'agente.js'), encoding='utf-8').read().replace('__NONCE__', 'abc123')
EXE = os.environ.get('CHROMIUM')  # ruta a un Chromium; si no, el que trae Playwright
def resumen(j):
    d = json.loads(j)
    print('métricas:', json.dumps(d['m'], ensure_ascii=False))
    print('lcpDet:', d['lcpDet'], '| soporte:', d['soporte'], '| parcial:', d['parcial'], 't0:', d['t0'])
    print('tipos:', [(t['t'], t['n'], t['tam']) for t in d['tipos']])
    print('terceros:', d['terceros'][:3])
    for h in d['h']:
        print(' [%d] %-10s %s\n      %s %s' % (h['sev'], h['id'], h['t'], h['d'], ('nodo=%s' % h['nodo']) if h.get('nodo') else ''))
    return d
try:
    with sync_playwright() as p:
        b = p.chromium.launch(executable_path=EXE, args=['--no-sandbox']) if EXE else p.chromium.launch(args=['--no-sandbox'])
        for modo in ('inicio', 'tarde'):
            print('\n================', modo)
            ctx = b.new_context(viewport={'width': 390, 'height': 800}, device_scale_factor=3)
            pg = ctx.new_page()
            errs = []
            pg.on('pageerror', lambda e: errs.append(str(e)))
            if modo == 'inicio': pg.add_init_script(agente)
            pg.goto('http://localhost:8765/', wait_until='load')
            pg.wait_for_timeout(5600)
            if modo == 'tarde': pg.evaluate(agente); pg.wait_for_timeout(300)
            t0 = time.time()
            j = pg.evaluate('window.__optimoDev.auditar()')
            print('auditar() tardó %.0f ms, %d bytes' % ((time.time() - t0) * 1000, len(j)))
            resumen(j)
            print('errores de página:', errs)
            ctx.close()
        b.close()
finally:
    srv.terminate()
