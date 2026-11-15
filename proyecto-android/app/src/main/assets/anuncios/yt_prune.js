(function(){
  var K=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
  function prune(o){
    if(o&&typeof o==='object'){
      for(var i=0;i<K.length;i++){ if(K[i] in o){ try{ delete o[K[i]]; }catch(e){} } }
      if(o.playerResponse) prune(o.playerResponse);
      if(o.response) prune(o.response);
    }
    return o;
  }
  var pj=JSON.parse;
  JSON.parse=function(){ return prune(pj.apply(this,arguments)); };
  if(window.Response&&Response.prototype){
    var rj=Response.prototype.json;
    Response.prototype.json=function(){ return rj.apply(this,arguments).then(prune); };
    var rt=Response.prototype.text;
    Response.prototype.text=function(){
      return rt.apply(this,arguments).then(function(t){
        try{ if(t&&t.charAt(0)==='{'&&t.indexOf('adPlacements')>=0) return JSON.stringify(prune(pj(t))); }catch(e){}
        return t;
      });
    };
  }
  var v;
  try{ Object.defineProperty(window,'ytInitialPlayerResponse',{get:function(){return v;},set:function(x){ v=prune(x); },configurable:true}); }catch(e){}
})();
