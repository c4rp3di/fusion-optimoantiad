(function(){
  var BT=['.ytp-skip-ad-button','.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-ad-skip-button-container button'];
  var tocado=false, mudoAntes=false;
  setInterval(function(){
    if(document.hidden) return;
    try{
      var p=document.querySelector('.html5-video-player');
      var anuncio=!!p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting'));
      var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
      if(anuncio&&v){
        if(!tocado){ tocado=true; mudoAntes=v.muted; }
        v.muted=true;
        try{ v.playbackRate=16; }catch(e){}
        if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.2){ v.currentTime=v.duration; }
        for(var i=0;i<BT.length;i++){ var b=document.querySelector(BT[i]); if(b) b.click(); }
        var c=document.querySelector('.ytp-ad-overlay-close-button'); if(c) c.click();
      } else if(tocado&&v){
        tocado=false; v.muted=mudoAntes;
        try{ v.playbackRate=1; }catch(e){}
      }
    }catch(e){}
  },500);
})();

