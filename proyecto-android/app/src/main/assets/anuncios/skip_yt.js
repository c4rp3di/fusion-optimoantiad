(function(){
  var BT=['.ytp-skip-ad-button','.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-ad-skip-button-container button'];
  var tocado=false, mudoAntes=false, ociosos=0;
  // Cadencia adaptable (menos CPU y batería): 500 ms con la app delante y un anuncio a la vista; 2 s en segundo plano
  // (Optimo marca __optimoBg al salir); y se espacia hasta 1,5 s cuando llevan un rato sin aparecer anuncios.
  function espera(){
    if(window.__optimoBg) return 2000;
    if(tocado) return 500;
    return ociosos>20 ? 1500 : 500;
  }
  function tic(){
    var siguiente=espera();
    try{
      if(!document.hidden){
        var p=document.querySelector('.html5-video-player');
        var anuncio=!!p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting'));
        var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
        if(anuncio&&v){
          ociosos=0;
          if(!tocado){ tocado=true; mudoAntes=v.muted; }
          v.muted=true;
          try{ v.playbackRate=16; }catch(e){}
          if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.2){ v.currentTime=v.duration; }
          for(var i=0;i<BT.length;i++){ var b=document.querySelector(BT[i]); if(b) b.click(); }
          var c=document.querySelector('.ytp-ad-overlay-close-button'); if(c) c.click();
          siguiente=500;
        } else {
          ociosos++;
          if(tocado&&v){
            tocado=false; v.muted=mudoAntes;
            try{ v.playbackRate=1; }catch(e){}
          }
        }
        siguiente=window.__optimoBg?2000:(tocado?500:(ociosos>20?1500:500));
      }
    }catch(e){}
    setTimeout(tic,siguiente);
  }
  setTimeout(tic,500);
})();
