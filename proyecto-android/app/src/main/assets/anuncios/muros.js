function(){
  try{
    var vw=window.innerWidth, vh=window.innerHeight;
    function esc(s){ return (window.CSS&&CSS.escape)?CSS.escape(s):String(s).replace(/[^a-zA-Z0-9_-]/g,'\\$&'); }
    function cuenta(s){ try{ return document.querySelectorAll(s).length; }catch(e){ return 0; } }
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
    var halladas=[];
    function candidato(el){
      if(el.hasAttribute&&el.hasAttribute('data-optimo')) return null;
      var r=el.getBoundingClientRect();
      if(r.width<vw*0.5||r.height<vh*0.35) return null;
      var s=getComputedStyle(el);
      if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity)===0) return null;
      var z=parseInt(s.zIndex,10); if(isNaN(z)) z=0;
      var modal=el.getAttribute('role')==='dialog'||el.getAttribute('aria-modal')==='true';
      var fija=s.position==='fixed', alta=s.position==='absolute'&&z>=100;
      if(!(fija||alta||modal)) return null;
      var cubre=(Math.min(r.right,vw)-Math.max(r.left,0))*(Math.min(r.bottom,vh)-Math.max(r.top,0))/(vw*vh);
      return {el:el,r:r,z:z,fuerte:cubre>=0.8||z>=1000};
    }
    function recorre(raiz,anfitrion){
      var els=raiz.querySelectorAll('*');
      for(var i=0;i<els.length;i++){
        var el=els[i];
        if(el.shadowRoot) recorre(el.shadowRoot,anfitrion||el);
        if(el===document.body||el===document.documentElement) continue;
        var c=candidato(el);
        if(c){ c.anfitrion=anfitrion||el; halladas.push(c); }
      }
    }
    recorre(document,null);
    // Capas en un Shadow DOM cerrado o sin tamaño propio: lo que está encima en varios puntos de la pantalla.
    var pts=[[.5,.5],[.25,.25],[.75,.25],[.25,.75],[.75,.75]];
    var tops=pts.map(function(q){ return document.elementFromPoint(vw*q[0],vh*q[1]); });
    var t0=tops[0];
    if(t0&&t0!==document.body&&t0!==document.documentElement&&tops.every(function(t){ return t===t0; })&&
       !halladas.some(function(h){ return h.anfitrion===t0; })&&!(t0.hasAttribute&&t0.hasAttribute('data-optimo'))){
      var s0=getComputedStyle(t0), r0=t0.getBoundingClientRect();
      if(s0.position==='fixed'||s0.position==='absolute'||r0.width*r0.height<vw*vh*0.2){
        var mult=/^(video|canvas|img|svg)$/i.test(t0.tagName);
        halladas.push({el:t0,anfitrion:t0,r:r0,z:parseInt(s0.zIndex,10)||0,fuerte:!mult});
      }
    }
    // Avisos «Adblocker detectado» que no son una capa fija: tarjetas dentro del flujo de la página. Se buscan por su texto.
    var RX=/ad[\s-]?block(er)?|bloqueador(es)? de (anuncios|publicidad)|ad[\s-]?blocker/i;
    function txt(n){ return (n.textContent||'').replace(/\s+/g,' ').trim(); }
    try{
      var tw=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_TEXT,null), nodo, vistosTxt=[];
      while((nodo=tw.nextNode())){
        if(!RX.test(nodo.nodeValue)) continue;
        var el0=nodo.parentElement;
        if(!el0||/^(SCRIPT|STYLE|NOSCRIPT|TITLE|OPTION)$/.test(el0.tagName)) continue;
        var r1=el0.getBoundingClientRect();
        if(r1.width===0||r1.height===0) continue;
        var st1=getComputedStyle(el0);
        if(st1.display==='none'||st1.visibility==='hidden') continue;
        var cur=el0, len=txt(cur).length;
        for(var dd=0;dd<8;dd++){
          var pa=cur.parentElement;
          if(!pa||pa===document.body||pa===document.documentElement) break;
          var pl=txt(pa).length;
          if(pl>1500||pl>len*4+400) break;
          cur=pa; len=pl;
        }
        // Baja al envoltorio más ajustado que siga teniendo todo el texto (sin arrastrar logotipos ni márgenes).
        for(var dn=0;dn<8;dn++){
          var hijos=[], hh=cur.children;
          for(var hi=0;hi<hh.length;hi++){ if(txt(hh[hi]).length>0) hijos.push(hh[hi]); }
          if(hijos.length===1&&txt(hijos[0]).length>=len-5){ cur=hijos[0]; len=txt(cur).length; } else break;
        }
        if(vistosTxt.indexOf(cur)>=0) continue;
        vistosTxt.push(cur);
        var rc=cur.getBoundingClientRect();
        if(rc.height<80||rc.width<vw*0.4) continue;
        var tarjeta=len<1500&&!!cur.querySelector('button,a,[role="button"]');
        halladas.push({el:cur,anfitrion:cur,r:rc,z:0,fuerte:tarjeta});
      }
    }catch(e){}
    // Una misma capa encontrada por dos caminos cuenta una sola vez.
    var unicas=[];
    halladas.forEach(function(c){
      var igual=unicas.filter(function(u){ return u.anfitrion===c.anfitrion; })[0];
      if(igual){ igual.fuerte=igual.fuerte||c.fuerte; } else unicas.push(c);
    });
    halladas=unicas;
    // Si una capa contiene a otra, basta con la exterior.
    var res=[], usados={};
    halladas.forEach(function(c){
      var dentro=halladas.some(function(o){ return o!==c&&o.anfitrion!==c.anfitrion&&o.anfitrion.contains(c.anfitrion); })||
                 halladas.some(function(o){ return o!==c&&o.el===c.anfitrion; });
      if(dentro) return;
      var ref=c.anfitrion;
      var sel=ruta(ref,false); if(cuenta(sel)>6) sel=ruta(ref,true);
      if(!sel||usados[sel]) return;
      usados[sel]=1;
      var tx=(ref.textContent||'').replace(/\s+/g,' ').trim().slice(0,70);
      res.push({sel:sel,tag:ref.tagName.toLowerCase(),w:Math.round(c.r.width),h:Math.round(c.r.height),z:c.z,fuerte:c.fuerte,texto:tx});
    });
    function bloq(el){ try{ return getComputedStyle(el).overflowY==='hidden'; }catch(e){ return false; } }
    return JSON.stringify({muros:res,scrollBloqueado:bloq(document.documentElement)||bloq(document.body)});
  }catch(e){ return null; }
}
