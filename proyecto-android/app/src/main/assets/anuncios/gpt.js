(function(){
  var w=window, q=(w.googletag&&w.googletag.cmd)||[], chain;
  chain=new Proxy(function(){},{
    get:function(t,p){ if(p==='then'||p===Symbol.toPrimitive) return undefined; if(p==='toString') return function(){return ''}; return chain; },
    apply:function(){ return chain; },
    construct:function(){ return chain; }
  });
  var cmd={push:function(){ for(var i=0;i<arguments.length;i++){ try{ if(typeof arguments[i]==='function') arguments[i].call(w); }catch(e){} } return 0; }};
  var g={cmd:cmd,apiReady:true,pubadsReady:true,_loaded_:true,
    pubads:function(){return chain}, companionAds:function(){return chain}, content:function(){return chain},
    defineSlot:function(){return chain}, defineOutOfPageSlot:function(){return chain}, sizeMapping:function(){return chain},
    enableServices:function(){}, display:function(){}, destroySlots:function(){return true}, openConsole:function(){},
    getVersion:function(){return 'antiads-stub'}, setAdIframeTitle:function(){}};
  w.googletag=new Proxy(g,{get:function(t,p){ if(p in t) return t[p]; if(p==='then') return undefined; return chain; }});
  for(var i=0;i<q.length;i++){ cmd.push(q[i]); }
})();
