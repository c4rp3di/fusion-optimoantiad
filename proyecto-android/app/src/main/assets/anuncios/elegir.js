function(x,y){
  try{
    var vw=window.innerWidth, vh=window.innerHeight;
    var el=document.elementFromPoint(x,y);
    if(!el||el===document.body||el===document.documentElement) return null;
    function esc(s){ return (window.CSS&&CSS.escape)?CSS.escape(s):String(s).replace(/[^a-zA-Z0-9_-]/g,'\\$&'); }
    function cuenta(s){ try{ return document.querySelectorAll(s).length; }catch(e){ return 0; } }
    function host(u){ try{ return new URL(u,location.href).hostname; }catch(e){ return ''; } }
    function pequeno(n){ var r=n.getBoundingClientRect(); return r.width>0&&r.height>0&&r.width*r.height<=0.6*vw*vh; }
    function meta(n){ return (n.id||'')+' '+(typeof n.className==='string'?n.className:'')+' '+(n.getAttribute('data-ad-slot')||n.getAttribute('data-ad-unit')||''); }
    var AD=/(^|[\s_-])(ad|ads|adv|advert|advertisement|banner|sponsor|sponsored|publicidad|promo|dfp|gpt|taboola|outbrain)([\s_-]|$)|adsbygoogle|doubleclick|google_ads|adslot/i;

    // El contenedor de anuncio más externo (pero no enorme) que rodea lo tocado.
    var obj=null, n=el;
    for(var d=0; n&&n!==document.body&&n!==document.documentElement&&d<8; d++, n=n.parentElement){
      if((n.tagName==='IFRAME'||n.tagName==='INS'||AD.test(meta(n)))&&pequeno(n)) obj=n;
    }
    if(!obj){ obj=el; if(!pequeno(obj)) return null; }

    // Dominio del recurso (iframe, imagen o vídeo), si es de otro sitio.
    var src='';
    if(obj.tagName==='IFRAME') src=obj.src||'';
    if(!src) src=el.currentSrc||el.src||'';
    if(!src&&obj.querySelector){ var q=obj.querySelector('iframe[src],img[src],video[src],source[src]'); if(q) src=q.currentSrc||q.src||''; }
    var dom=host(src);
    if(dom===location.hostname) dom='';

    // Selector: por clases/id si es estable; posicional si el anterior coincide con demasiados elementos.
    function ruta(n0,posicional){
      var p=[], m=n0;
      for(var d=0; m&&m.nodeType===1&&m!==document.body&&m!==document.documentElement&&d<(posicional?12:5); d++, m=m.parentElement){
        var t=m.tagName.toLowerCase();
        if(m.id&&!/\d{4,}/.test(m.id)){ p.unshift(t+'#'+esc(m.id)); break; }
        var cl=posicional?[]:(typeof m.className==='string'?m.className:'').trim().split(/\s+/).filter(function(c){ return c&&!/\d{3,}/.test(c); }).slice(0,2);
        if(cl.length){ t+='.'+cl.map(esc).join('.'); }
        else { var i=1, s=m; while((s=s.previousElementSibling)){ if(s.tagName===m.tagName) i++; } t+=':nth-of-type('+i+')'; }
        p.unshift(t);
        if(!posicional&&cuenta(p.join('>'))===1) break;
      }
      return p.join('>');
    }
    var sel='';
    if(obj.tagName==='IFRAME'){ var h2=host(obj.src||''); if(h2&&h2!==location.hostname) sel='iframe[src*="'+h2+'"]'; }
    if(!sel) sel=ruta(obj,false);
    if(cuenta(sel)>6) sel=ruta(obj,true);
    var r=obj.getBoundingClientRect();
    return JSON.stringify({sel:sel,dom:dom,tag:obj.tagName.toLowerCase(),w:Math.round(r.width),h:Math.round(r.height),n:cuenta(sel)});
  }catch(e){ return null; }
}
