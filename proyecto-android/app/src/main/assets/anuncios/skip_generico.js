(function(){
  var RE=/^\s*(skip( ad| ads)?|saltar( anuncio| publicidad)?|omitir( anuncio)?|close ad|cerrar anuncio)\s*[>»]*\s*$/i;
  var AD=/(^|[\s_-])(ad|ads|advert|preroll|pre-roll|vast|ima|sponsor)([\s_-]|$)/i;
  var ociosos=0;
  // Recorre solo la página visible y se espacia (1 s → 4 s) cuando lleva rato sin encontrar botones de «saltar anuncio»;
  // vuelve a 1 s en cuanto el DOM cambia de forma notable.
  var nodos=0, vigilante=null;
  try{
    vigilante=new MutationObserver(function(m){ nodos+=m.length; });
    vigilante.observe(document.documentElement,{childList:true,subtree:true});
  }catch(e){}
  function tic(){
    var siguiente=1000;
    try{
      if(!document.hidden){
        if(nodos>30){ ociosos=0; }
        nodos=0;
        var hallado=false;
        var bs=document.querySelectorAll('button,a,div[role=button],span[role=button]');
        for(var i=0;i<bs.length;i++){
          var b=bs[i], t=(b.textContent||b.getAttribute('aria-label')||'').trim();
          if(!t||t.length>24||!RE.test(t)) continue;
          var n=b, enAnuncio=false;
          for(var d=0;n&&d<6;d++,n=n.parentElement){ if(AD.test((n.className&&n.className.toString())||'')||AD.test(n.id||'')){ enAnuncio=true; break; } }
          if(enAnuncio){ b.click(); hallado=true; }
        }
        if(hallado) ociosos=0; else ociosos++;
        siguiente=window.__optimoBg?2000:(ociosos>30?4000:(ociosos>10?2000:1000));
      } else {
        siguiente=3000;
      }
    }catch(e){}
    setTimeout(tic,siguiente);
  }
  setTimeout(tic,1000);
})();
