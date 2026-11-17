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
