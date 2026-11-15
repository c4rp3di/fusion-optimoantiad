(function(){
  var w=window; w.google=w.google||{};
  function lenient(o){ return new Proxy(o,{get:function(t,p){ if(p in t) return t[p]; if(p==='then') return undefined; return function(){ return undefined; }; }}); }
  function Err(){ this.getErrorCode=function(){return 1009}; this.getVastErrorCode=function(){return 1009}; this.getMessage=function(){return 'AntiAds: sin anuncios'}; this.getType=function(){return 'adError'}; this.getInnerError=function(){return null}; this.toString=function(){return 'AdError 1009'}; }
  function Ev(t){ this.type=t; this.getError=function(){return new Err()}; this.getAd=function(){return null}; this.getAdData=function(){return {}}; this.getUserRequestContext=function(){return null}; }
  function Em(){ this._l={}; }
  Em.prototype.addEventListener=function(t,f){ (this._l[t]=this._l[t]||[]).push(f); };
  Em.prototype.removeEventListener=function(t,f){ var a=this._l[t]||[], i=a.indexOf(f); if(i>=0) a.splice(i,1); };
  Em.prototype._fire=function(t){ var a=(this._l[t]||[]).slice(), e=new Ev(t); for(var i=0;i<a.length;i++){ try{ a[i](e); }catch(x){} } };
  function Settings(){}
  Settings.prototype=lenient({});
  function Loader(){ Em.call(this); }
  Loader.prototype=Object.create(Em.prototype);
  Loader.prototype.requestAds=function(){ var s=this; setTimeout(function(){ s._fire('adError'); },0); };
  Loader.prototype.contentComplete=function(){};
  Loader.prototype.destroy=function(){};
  Loader.prototype.getSettings=function(){ return new Settings(); };
  function Container(){}
  Container.prototype.initialize=function(){}; Container.prototype.destroy=function(){};
  function Plain(){}
  w.google.ima=lenient({
    AdDisplayContainer:Container, AdsLoader:Loader, AdsRequest:Plain, AdsRenderingSettings:Plain, ImaSdkSettings:Settings,
    settings:new Settings(), VERSION:'3.0.0',
    AdsManagerLoadedEvent:{Type:{ADS_MANAGER_LOADED:'adsManagerLoaded'}},
    AdErrorEvent:{Type:{AD_ERROR:'adError'}},
    AdEvent:{Type:{ALL_ADS_COMPLETED:'allAdsCompleted',CONTENT_PAUSE_REQUESTED:'contentPauseRequested',CONTENT_RESUME_REQUESTED:'contentResumeRequested',LOADED:'loaded',STARTED:'started',COMPLETE:'complete',SKIPPED:'skip',AD_ERROR:'adError'}},
    ViewMode:{NORMAL:'normal',FULLSCREEN:'fullscreen'},
    AdError:{ErrorCode:{VAST_EMPTY_RESPONSE:1009,UNKNOWN_AD_RESPONSE:1010}}
  });
})();

