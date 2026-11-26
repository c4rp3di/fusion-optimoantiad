function(o){
  try{
    var ID='__optimoLectura';
    if(window[ID]&&window[ID].abierto){ window[ID].cerrar(); return 'cerrado'; }

    // ---------------------------------------------------------------- extracción del artículo
    var NEG=/(^|[\s_-])(comments?|comment-list|disqus|sidebar|footer|nav|navbar|menu|masthead|banner|ads?|advert\w*|sponsor\w*|promo\w*|share\w*|social\w*|related|recommend\w*|newsletter|subscribe|cookie\w*|consent|popup|modal|widget|breadcrumbs?|pagination|toolbar|outbrain|taboola)([\s_-]|$)/i;
    var POS=/article|post|entry|story|content|text|body|main|blog|read/i;
    var DESCARTA={SCRIPT:1,STYLE:1,NOSCRIPT:1,NAV:1,ASIDE:1,FOOTER:1,FORM:1,IFRAME:1,BUTTON:1,SVG:1,CANVAS:1,VIDEO:1,AUDIO:1,INPUT:1,SELECT:1,TEXTAREA:1,OBJECT:1,EMBED:1,TEMPLATE:1,DIALOG:1};
    var EN_LINEA={SPAN:1,FONT:1,U:1,MARK:1,SMALL:1,ABBR:1,CITE:1,DEL:1,INS:1,S:1,Q:1,LABEL:1,TIME:1,BDI:1,BDO:1,DFN:1,KBD:1,SAMP:1,VAR:1,DATA:1,WBR:1,BIG:1,TT:1,STRIKE:1,ACRONYM:1};
    var PERMITE={P:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,UL:1,OL:1,LI:1,BLOCKQUOTE:1,PRE:1,CODE:1,STRONG:1,B:1,EM:1,I:1,BR:1,FIGURE:1,FIGCAPTION:1,A:1,IMG:1,TABLE:1,THEAD:1,TBODY:1,TR:1,TH:1,TD:1,SUB:1,SUP:1,HR:1};
    function txt(n){ return (n.textContent||'').replace(/\s+/g,' ').trim(); }
    function visible(n){ try{ var s=getComputedStyle(n); return !(s.display==='none'||s.visibility==='hidden'); }catch(e){ return true; } }
    function cls(n){ return (typeof n.className==='string'?n.className:'')+' '+(n.id||''); }
    function dens(n){ var t=txt(n).length; if(!t) return 0; var l=0; var as=n.querySelectorAll('a'); for(var i=0;i<as.length;i++) l+=txt(as[i]).length; return l/t; }
    function peso(n){ var c=cls(n), w=0; if(NEG.test(c)) w-=25; if(POS.test(c)) w+=10; if(n.tagName==='ARTICLE') w+=10; return w; }

    var mapa=new Map();
    function propio(n){ var s=''; for(var c=n.firstChild;c;c=c.nextSibling){ if(c.nodeType===3) s+=c.nodeValue; } return s.replace(/\s+/g,' ').trim(); }
    var ps=document.querySelectorAll('p,pre,blockquote,td,div');
    for(var i=0;i<ps.length;i++){
      var p=ps[i], esDiv=(p.tagName==='DIV'), t=esDiv?propio(p):txt(p);
      if(t.length<25||!visible(p)) continue;
      var pad=esDiv?p:p.parentElement; if(!pad) continue;
      var s=1+(t.split(',').length-1)+Math.min(Math.floor(t.length/100),3);
      var cs=[pad,pad.parentElement];
      for(var k=0;k<2;k++){
        var c=cs[k];
        if(!c||c===document.documentElement) continue;
        if(!mapa.has(c)) mapa.set(c,peso(c));
        mapa.set(c,mapa.get(c)+(k===0?s:s/2));
      }
    }
    var mejor=null, mp=0;
    mapa.forEach(function(v,c){ var pt=v*(1-dens(c)); if(pt>mp){ mp=pt; mejor=c; } });
    if(!mejor) return 'vacio';
    // Sube al contenedor si este solo es un trozo (el padre no añade texto extra).
    for(var u=0;u<4;u++){
      var pa=mejor.parentElement;
      if(!pa||pa===document.body||pa===document.documentElement||NEG.test(cls(pa))) break;
      if(txt(pa).length<1.25*txt(mejor).length) mejor=pa; else break;
    }

    function meta(n){ var m=document.querySelector('meta['+n+']'); return m?(m.getAttribute('content')||'').trim():''; }
    var h1=mejor.querySelector('h1')||document.querySelector('h1');
    var titulo=(h1?txt(h1):'')||meta('property="og:title"')||document.title||'';
    var autor=meta('name="author"')||meta('property="article:author"');
    if(!autor){ var ra=document.querySelector('[rel="author"]'); if(ra) autor=txt(ra); }
    if(autor.length>60) autor='';
    var tm=document.querySelector('time[datetime]');
    var fecha='';
    if(tm){ try{ fecha=new Date(tm.getAttribute('datetime')).toLocaleDateString(); if(fecha==='Invalid Date') fecha=''; }catch(e){} }

    var h1Usado=false;
    function tieneBloques(n){ return !!n.querySelector('p,div,ul,ol,h1,h2,h3,h4,h5,h6,blockquote,pre,table,figure,section,article'); }
    function urlHttp(u){ return /^https?:\/\//i.test(u||''); }
    function copiar(n,dest){
      for(var c=n.firstChild;c;c=c.nextSibling){
        if(c.nodeType===3){ dest.appendChild(document.createTextNode(c.nodeValue)); continue; }
        if(c.nodeType!==1) continue;
        var tag=c.tagName;
        if(DESCARTA[tag]||c.getAttribute('aria-hidden')==='true'||c.hidden||!visible(c)) continue;
        if(tag==='TIME'&&!c.closest('p,li,blockquote,td')) continue;
        var tl=txt(c).length;
        if(NEG.test(cls(c))&&tl<800) continue;
        if(tag!=='A'&&tag!=='LI'&&tag!=='IMG'&&tl>0&&tl<400&&dens(c)>0.6&&c.querySelector('a')&&(tag==='UL'||tag==='OL'||tag==='DIV'||tag==='SECTION'||tag==='P')) continue;
        if(tag==='H1'&&!h1Usado&&txt(c)===titulo){ h1Usado=true; continue; }
        if(tag==='IMG'){
          var src=c.currentSrc||c.src||'';
          if(!urlHttp(src)) src=c.getAttribute('data-src')||c.getAttribute('data-original')||c.getAttribute('data-lazy-src')||'';
          if(!urlHttp(src)) continue;
          var nw=c.naturalWidth||0, cw=c.clientWidth||0;
          if((nw>0&&nw<80)||(cw>0&&cw<60)) continue;
          var im=document.createElement('img'); im.src=src; im.alt=c.getAttribute('alt')||''; im.loading='lazy';
          dest.appendChild(im); continue;
        }
        if(PERMITE[tag]){
          var el=document.createElement(tag.toLowerCase());
          if(tag==='A'){ var h=c.href; if(urlHttp(h)){ el.href=h; el.rel='noopener'; } }
          copiar(c,el);
          if(tag==='BR'||tag==='HR'){ dest.appendChild(el); continue; }
          if(!el.querySelector('img')&&!(el.textContent||'').trim()) continue;
          dest.appendChild(el);
        } else if(EN_LINEA[tag]||tieneBloques(c)){
          copiar(c,dest);
        } else if(tl>0){
          var pp=document.createElement('p'); copiar(c,pp);
          if((pp.textContent||'').trim()) dest.appendChild(pp);
        }
      }
    }
    var cuerpo=document.createElement('div');
    copiar(mejor,cuerpo);
    var texto=(cuerpo.textContent||'').replace(/\s+/g,' ').trim();
    if(texto.length<200) return 'vacio';
    var palabras=texto.split(' ').length;
    var minutos=Math.max(1,Math.round(palabras/200));

    // ---------------------------------------------------------------- vista de lectura (Shadow DOM: aislada del CSS de la página)
    var TEMAS=['claro','sepia','oscuro','negro'];
    var INTER=[1.45,1.65,1.9];
    var tam=Math.min(32,Math.max(14,parseInt(o&&o.tam,10)||19));
    var tema=TEMAS.indexOf(o&&o.tema)>=0?o.tema:'claro';
    var fuente=(o&&o.fuente==='sans')?'sans':'serif';
    var inter=Math.min(2,Math.max(0,parseInt(o&&o.inter,10)));
    if(isNaN(inter)) inter=1;

    var CSS=[
      ':host{all:initial}',
      '.lect{position:fixed;left:0;top:0;right:0;bottom:0;overflow:auto;-webkit-overflow-scrolling:touch;background:var(--bg);color:var(--fg);font-size:var(--tam);line-height:var(--inter);}',
      '.lect[data-t="claro"]{--bg:#ffffff;--fg:#202124;--mut:#5f6368;--ac:#00796b;--bar:#f1f3f4;--bor:#dadce0}',
      '.lect[data-t="sepia"]{--bg:#f4ecd8;--fg:#3b2f1e;--mut:#7a6a4f;--ac:#8a4b08;--bar:#eadfc4;--bor:#d9ccaa}',
      '.lect[data-t="oscuro"]{--bg:#1e1f22;--fg:#d8d8d8;--mut:#9aa0a6;--ac:#4dd0c4;--bar:#2a2c30;--bor:#3c4043}',
      '.lect[data-t="negro"]{--bg:#000000;--fg:#cfcfcf;--mut:#8a8a8a;--ac:#4dd0c4;--bar:#121212;--bor:#2a2a2a}',
      '.lect[data-f="serif"]{font-family:Georgia,"Times New Roman",serif}',
      '.lect[data-f="sans"]{font-family:system-ui,-apple-system,Roboto,"Segoe UI",sans-serif}',
      '.bar{position:sticky;top:0;z-index:2;display:flex;gap:4px;align-items:center;padding:6px 8px;background:var(--bar);border-bottom:1px solid var(--bor);font-family:system-ui,sans-serif;font-size:16px;line-height:1}',
      '.bar button{min-width:44px;height:40px;border:0;border-radius:10px;background:transparent;color:var(--fg);font-size:17px;font-family:inherit}',
      '.bar button:active{background:var(--bor)}',
      '.bar .sp{flex:1}',
      '.art{max-width:42em;margin:0 auto;padding:18px 18px 80px}',
      'h1.t{font-size:1.5em;line-height:1.25;margin:.4em 0 .3em}',
      '.meta{color:var(--mut);font-size:.8em;margin-bottom:1.4em;font-family:system-ui,sans-serif}',
      '.art p,.art li,.art blockquote,.art pre,.art table{margin:0 0 1em}',
      '.art h1,.art h2,.art h3,.art h4,.art h5,.art h6{line-height:1.3;margin:1.4em 0 .5em}',
      '.art h2{font-size:1.3em}.art h3{font-size:1.15em}',
      '.art a{color:var(--ac)}',
      '.art img{max-width:100%;height:auto;display:block;margin:1em auto;border-radius:6px}',
      '.art figure{margin:1em 0}.art figcaption{color:var(--mut);font-size:.8em;text-align:center}',
      '.art blockquote{border-left:3px solid var(--ac);padding-left:1em;color:var(--mut)}',
      '.art pre{overflow:auto;background:var(--bar);padding:.8em;border-radius:8px;font-size:.85em}',
      '.art code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em}',
      '.art table{border-collapse:collapse;display:block;overflow:auto}',
      '.art td,.art th{border:1px solid var(--bor);padding:4px 8px}',
      '.art hr{border:0;border-top:1px solid var(--bor)}'
    ].join('\n');

    var host=document.createElement('div');
    host.setAttribute('data-optimo','lectura');
    var raiz=host.attachShadow({mode:'open'});
    try{
      var hoja=new CSSStyleSheet(); hoja.replaceSync(CSS); raiz.adoptedStyleSheets=[hoja];
    }catch(e){
      var st=document.createElement('style'); st.textContent=CSS; raiz.appendChild(st);
    }
    var lect=document.createElement('div'); lect.className='lect';
    function aplicar(){
      lect.setAttribute('data-t',tema); lect.setAttribute('data-f',fuente);
      lect.style.setProperty('--tam',tam+'px'); lect.style.setProperty('--inter',String(INTER[inter]));
    }
    function guardar(){ console.log('optimo-lectura:prefs:'+JSON.stringify({tam:tam,tema:tema,fuente:fuente,inter:inter})); }
    function boton(txt0,tit,fn){
      var b=document.createElement('button'); b.type='button'; b.textContent=txt0; b.title=tit; b.setAttribute('aria-label',tit);
      b.addEventListener('click',function(ev){ ev.preventDefault(); fn(b); }); return b;
    }
    var bar=document.createElement('div'); bar.className='bar';
    var bVoz=boton('🔊','Leer en voz alta',function(){ console.log('optimo-lectura:voz'); });
    bar.appendChild(boton('✕','Salir del modo lectura',function(){ api.cerrar(); }));
    var sp=document.createElement('span'); sp.className='sp'; bar.appendChild(sp);
    bar.appendChild(boton('A−','Letra más pequeña',function(){ tam=Math.max(14,tam-2); aplicar(); guardar(); }));
    bar.appendChild(boton('A+','Letra más grande',function(){ tam=Math.min(32,tam+2); aplicar(); guardar(); }));
    bar.appendChild(boton('Aa','Cambiar tipo de letra',function(){ fuente=fuente==='serif'?'sans':'serif'; aplicar(); guardar(); }));
    bar.appendChild(boton('↕','Interlineado',function(){ inter=(inter+1)%INTER.length; aplicar(); guardar(); }));
    bar.appendChild(boton('◐','Cambiar fondo',function(){ tema=TEMAS[(TEMAS.indexOf(tema)+1)%TEMAS.length]; aplicar(); guardar(); }));
    bar.appendChild(bVoz);
    lect.appendChild(bar);

    var art=document.createElement('div'); art.className='art';
    var ht=document.createElement('h1'); ht.className='t'; ht.textContent=titulo; art.appendChild(ht);
    var partes=[]; if(autor) partes.push(autor); if(fecha) partes.push(fecha); partes.push(location.hostname.replace(/^www\./,'')); partes.push(minutos+' min de lectura');
    var mt=document.createElement('div'); mt.className='meta'; mt.textContent=partes.join(' · '); art.appendChild(mt);
    art.appendChild(cuerpo);
    lect.appendChild(art);
    raiz.appendChild(lect);
    aplicar();

    var vistas=document.querySelectorAll('meta[name="viewport"]'), vistaAnt=[], vistaNueva=null;
    for(var vi=0;vi<vistas.length;vi++){ vistaAnt.push(vistas[vi].getAttribute('content')); vistas[vi].setAttribute('content','width=device-width, initial-scale=1'); }
    if(!vistas.length){
      vistaNueva=document.createElement('meta'); vistaNueva.name='viewport'; vistaNueva.content='width=device-width, initial-scale=1';
      (document.head||document.documentElement).appendChild(vistaNueva);
    }
    var ant=document.documentElement.style.overflow;
    document.documentElement.style.overflow='hidden';
    host.style.setProperty('position','fixed'); host.style.setProperty('left','0'); host.style.setProperty('top','0');
    host.style.setProperty('right','0'); host.style.setProperty('bottom','0'); host.style.setProperty('z-index','2147483647');
    (document.body||document.documentElement).appendChild(host);

    var api={
      abierto:true,
      cerrar:function(){
        api.abierto=false;
        try{ host.remove(); }catch(e){}
        document.documentElement.style.overflow=ant;
        try{
          if(vistaNueva) vistaNueva.remove();
          for(var vj=0;vj<vistas.length;vj++){ if(vistaAnt[vj]===null) vistas[vj].removeAttribute('content'); else vistas[vj].setAttribute('content',vistaAnt[vj]); }
        }catch(e){}
        console.log('optimo-lectura:cerrar');
      },
      voz:function(activa){ bVoz.textContent=activa?'⏹':'🔊'; },
      texto:function(){
        var partes2=[titulo];
        var bl=cuerpo.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,figcaption');
        for(var j=0;j<bl.length;j++){ var tt=(bl[j].textContent||'').replace(/\s+/g,' ').trim(); if(tt) partes2.push(tt); }
        return JSON.stringify({lang:document.documentElement.lang||'',texto:partes2.join('\n')});
      }
    };
    window[ID]=api;
    return 'ok';
  }catch(e){ return 'error:'+e; }
}
