function(x,y,nivel){
  try{
    function quitar(){ var m=window.__optimoMarca; if(m){ try{ m.el.style.outline=m.o; m.el.style.outlineOffset=m.oo; }catch(e){} window.__optimoMarca=null; } }
    function esc(s){ return (window.CSS&&CSS.escape)?CSS.escape(s):String(s).replace(/[^a-zA-Z0-9_-]/g,'\\$&'); }
    function cuenta(s){ try{ return document.querySelectorAll(s).length; }catch(e){ return 0; } }
    quitar();
    var el=document.elementFromPoint(x,y);
    if(document.elementsFromPoint&&(!el||el===document.body||el===document.documentElement)){
      var pila=document.elementsFromPoint(x,y);
      for(var q=0;q<pila.length;q++){ if(pila[q]!==document.body&&pila[q]!==document.documentElement){ el=pila[q]; break; } }
    }
    if(!el||el===document.body||el===document.documentElement) return null;
    for(var i=0;i<nivel;i++){
      var p=el.parentElement;
      if(!p||p===document.body||p===document.documentElement) break;
      el=p;
    }
    var padre=el.parentElement;
    var hayPadre=!!(padre&&padre!==document.body&&padre!==document.documentElement);

    // Selector por id/clases estables; posicional si el anterior coincide con demasiados elementos.
    function ruta(n0,posicional){
      var p=[], m=n0;
      for(var d=0; m&&m.nodeType===1&&m!==document.body&&m!==document.documentElement&&d<(posicional?12:5); d++, m=m.parentElement){
        var t=m.tagName.toLowerCase();
        if(m.id&&!/\d{4,}/.test(m.id)){ p.unshift(t+'#'+esc(m.id)); break; }
        var cl=posicional?[]:(typeof m.className==='string'?m.className:'').trim().split(/\s+/).filter(function(c){ return c&&!/\d{3,}/.test(c); }).slice(0,2);
        if(cl.length){ t+='.'+cl.map(esc).join('.'); }
        else { var k=1, s=m; while((s=s.previousElementSibling)){ if(s.tagName===m.tagName) k++; } t+=':nth-of-type('+k+')'; }
        p.unshift(t);
        if(!posicional&&cuenta(p.join('>'))===1) break;
      }
      return p.join('>');
    }
    var sel=ruta(el,false);
    if(cuenta(sel)>6) sel=ruta(el,true);
    if(!sel) return null;

    // Resalta lo elegido mientras el cuadro de opciones está abierto (solo estilo CSSOM, no toca el HTML).
    window.__optimoMarca={el:el,o:el.style.outline,oo:el.style.outlineOffset};
    el.style.outline='3px solid #FF5722';
    el.style.outlineOffset='-3px';

    var r=el.getBoundingClientRect();
    var tx=(el.textContent||'').replace(/\s+/g,' ').trim().slice(0,60);
    return JSON.stringify({sel:sel,tag:el.tagName.toLowerCase(),w:Math.round(r.width),h:Math.round(r.height),n:cuenta(sel),hayPadre:hayPadre,texto:tx});
  }catch(e){ return null; }
}
