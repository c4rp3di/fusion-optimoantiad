(function(){
  var RE=/^\s*(skip( ad| ads)?|saltar( anuncio| publicidad)?|omitir( anuncio)?|close ad|cerrar anuncio)\s*[>»]*\s*$/i;
  var AD=/(^|[\s_-])(ad|ads|advert|preroll|pre-roll|vast|ima|sponsor)([\s_-]|$)/i;
  setInterval(function(){
    if(document.hidden) return;
    try{
      var bs=document.querySelectorAll('button,a,div[role=button],span[role=button]');
      for(var i=0;i<bs.length;i++){
        var b=bs[i], t=(b.textContent||b.getAttribute('aria-label')||'').trim();
        if(!t||t.length>24||!RE.test(t)) continue;
        var n=b, enAnuncio=false;
        for(var d=0;n&&d<6;d++,n=n.parentElement){ if(AD.test((n.className&&n.className.toString())||'')||AD.test(n.id||'')){ enAnuncio=true; break; } }
        if(enAnuncio) b.click();
      }
    }catch(e){}
  },1000);
})();
