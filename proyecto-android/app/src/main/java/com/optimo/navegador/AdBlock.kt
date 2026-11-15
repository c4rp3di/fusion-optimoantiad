package com.optimo.navegador

/**
 * Bloqueador basico de anuncios y rastreadores por dominio: se aplica a los subrecursos
 * (nunca a la pagina principal) comparando el host y sus dominios padre con una lista cerrada.
 * La lista integrada (abajo) se completa con las listas descargadas de ListasManager.
 * No toca el contenido de la pagina ni bloquea anuncios servidos desde el propio dominio.
 */
object AdBlock {

    private val DOMINIOS: Set<String> = hashSetOf(
        // Redes publicitarias
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "2mdn.net", "adnxs.com", "adsrvr.org", "advertising.com", "criteo.com", "criteo.net",
        "outbrain.com", "taboola.com", "taboolasyndication.com", "rubiconproject.com", "pubmatic.com",
        "openx.net", "casalemedia.com", "smartadserver.com", "adform.net", "bidswitch.net",
        "contextweb.com", "lijit.com", "sharethrough.com", "teads.tv", "zedo.com", "serving-sys.com",
        "yieldmo.com", "33across.com", "media.net", "popads.net", "propellerads.com", "exoclick.com",
        "adcolony.com", "applovin.com", "mopub.com", "inmobi.com", "chartboost.com", "revcontent.com",
        "mgid.com", "adblade.com", "richaudience.com", "tapad.com", "adtechus.com", "ads.yahoo.com",
        "amazon-adsystem.com", "ads.linkedin.com", "ads.twitter.com", "ads-api.twitter.com",
        // Verificacion y medicion publicitaria
        "moatads.com", "adsafeprotected.com", "doubleverify.com", "scorecardresearch.com",
        "quantserve.com", "quantcount.com", "mathtag.com",
        // Rastreo y perfilado
        "google-analytics.com", "bluekai.com", "krxd.net", "demdex.net", "everesttech.net",
        "adsymptotic.com", "rlcdn.com", "id5-sync.com", "liadm.com", "agkn.com", "crwdcntrl.net",
        "ml314.com", "analytics.twitter.com", "hotjar.com", "clarity.ms"
    )

    val TOTAL: Int get() = DOMINIOS.size

    /** True si el host (o alguno de sus dominios padre) esta en la lista. */
    fun bloquea(host: String): Boolean {
        var h = host.lowercase()
        if (h.endsWith(".")) h = h.substring(0, h.length - 1)
        // Dominios que el usuario bloqueó a mano: ganan a todo lo demás.
        if (ReglasUsuario.bloqueaDominio(h)) return true
        // Listas descargadas (OISD, HaGeZi…): una excepción permite, un dominio listado bloquea.
        when (ListasManager.filtro.consultar(h)) {
            FiltroListas.PERMITIDO -> return false
            FiltroListas.BLOQUEADO -> return true
        }
        while (true) {
            if (DOMINIOS.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (h.indexOf('.') < 0) return false
        }
    }
}
