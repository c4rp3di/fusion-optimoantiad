package com.optimo.navegador

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout

/** Lo que el panel de bloqueo necesita de MainActivity. */
interface AnfitrionBloqueo {
    fun actualizarListas(forzarCompilar: Boolean)
    fun aplicarTecnicas()
    fun reinstalarAnuncios()
    fun avisar(texto: String)
    fun volverAProteccion()
}

/**
 * Listas de bloqueo, técnicas contra anuncios, reglas añadidas (ocultar, estilos, permisos) y dominios bloqueados a mano,
 * como páginas del panel lateral. Se llega desde Protección.
 */
class PanelBloqueo(
    private val act: Activity,
    private val paleta: Paleta,
    private val ajustes: Ajustes,
    private val g: AnfitrionBloqueo,
    inicial: String = LISTAS,
    private val sitio: String? = null
) {
    companion object {
        const val LISTAS = "listas"
        const val TECNICAS = "tecnicas"
        const val REGLAS = "reglas"
        const val DOMINIOS = "dominios"
    }

    private val hoja = Hoja(act, paleta, false, true)
    private val ui = Handler(Looper.getMainLooper())
    private val pila = ArrayList<String>()
    private var pagina = inicial
    private var consulta = ""
    private val pendientes = HashSet<String>()
    private lateinit var zonaReglas: LinearLayout

    fun mostrar() {
        pendientes.clear()
        pendientes.addAll(ajustes.listasActivas())
        pintar()
        hoja.interceptarAtras {
            atras()
            true
        }
        hoja.mostrar()
    }

    private fun repintar() {
        ui.post { pintar() }
    }

    private fun ir(p: String) {
        pila.add(pagina)
        pagina = p
        consulta = ""
        pintar()
    }

    private fun atras() {
        if (pila.isNotEmpty()) {
            pagina = pila.removeAt(pila.size - 1)
            consulta = ""
            pintar()
        } else {
            hoja.cerrar()
            g.volverAProteccion()
        }
    }

    private fun titulo(t: String) {
        hoja.barraTitulo(t, { atras() }, { hoja.cerrar() })
    }

    private fun confirmar(titulo: String, mensaje: String, boton: String, accion: () -> Unit) {
        Dialogos.confirmar(act, paleta, false, titulo, mensaje, boton, true, accion)
    }

    private fun pintar() {
        hoja.limpiar()
        when (pagina) {
            TECNICAS -> pintarTecnicas()
            REGLAS -> pintarReglas()
            DOMINIOS -> pintarDominios()
            else -> pintarListas()
        }
    }

    // ---------------------------------------------------------------- listas

    private fun pintarListas() {
        titulo("Listas de bloqueo")
        hoja.nota(ListasManager.resumen())
        hoja.fila("Mis dominios bloqueados", "Los que bloqueas tú a mano", ReglasUsuario.listaDominios().size.toString(), icono = "🚫") { ir(DOMINIOS) }

        hoja.seccion("Listas disponibles")
        for (l in ListasManager.todas()) {
            hoja.interruptor(l.nombre, l.tam.toString(), pendientes.contains(l.id)) { v ->
                if (v) pendientes.add(l.id) else pendientes.remove(l.id)
                repintar()
            }
        }
        if (pendientes != ajustes.listasActivas()) {
            hoja.boton("Aplicar cambios y actualizar") {
                ajustes.ponerListasActivas(HashSet(pendientes))
                g.actualizarListas(true)
                repintar()
            }
        }

        hoja.seccion("Mis listas")
        val propias = ajustes.propias()
        if (propias.isEmpty()) hoja.nota("Puedes añadir listas de dominios o de reglas propias con su dirección https.")
        for (p in propias) {
            hoja.fila(p.nombre, null, "Quitar", aviso = true) {
                confirmar("Quitar lista", "Se quitará «" + p.nombre + "» y se actualizará el bloqueo.", "Quitar") {
                    ajustes.quitarPropia(p.id)
                    pendientes.remove(p.id)
                    g.actualizarListas(true)
                    repintar()
                }
            }
        }
        hoja.fila("Añadir lista por dirección (https)…", icono = "＋") {
            Dialogos.texto(act, paleta, false, "Añadir lista por URL", "https://") { u ->
                val lista = ajustes.agregarPropia(u)
                if (lista == null) {
                    g.avisar("URL no válida: debe empezar por https://")
                } else {
                    pendientes.add(lista.id)
                    g.actualizarListas(true)
                }
                repintar()
            }
        }

        hoja.seccion("Actualización")
        hoja.fila("Actualizar ahora", "Descarga de nuevo las listas activas", icono = "↻") { g.actualizarListas(false) }
        hoja.interruptor("Actualizar solo con Wi-Fi", "Para no gastar datos móviles", ajustes.soloWifi) { v ->
            ajustes.soloWifi = v
            repintar()
        }
    }

    // ---------------------------------------------------------------- técnicas

    private fun pintarTecnicas() {
        titulo("Técnicas contra anuncios")
        hoja.nota("Se aplican al recargar la página. Si un sitio funciona mal, prueba a desactivar alguna.")
        for (t in MotorAnuncios.TECNICAS) {
            hoja.interruptor(t.nombre, null, ajustes.tecnica(t.id)) { v ->
                ajustes.ponerTecnica(t.id, v)
                g.aplicarTecnicas()
            }
        }
    }

    // ---------------------------------------------------------------- reglas

    private fun pintarReglas() {
        titulo(if (sitio == null) "Reglas añadidas" else "Reglas de " + sitio)
        hoja.campo("Buscar en las reglas", consulta) { t ->
            consulta = t
            pintarListaReglas()
        }
        zonaReglas = hoja.zona()
        pintarListaReglas()
    }

    private fun pintarListaReglas() {
        hoja.llenar(zonaReglas) {
            val q = consulta.trim().lowercase()
            val ocultar = ReglasUsuario.listaOcultar().filter { (sitio == null || it.host == sitio) && (q.isEmpty() || (it.host + " " + it.sel).lowercase().contains(q)) }
            val estilos = ReglasUsuario.listaCss().filter { (sitio == null || it.host == sitio) && (q.isEmpty() || (it.host + " " + it.nombre).lowercase().contains(q)) }
            val permisos = ReglasUsuario.listaPermisos().filter { (sitio == null || it.sitio == sitio) && (q.isEmpty() || (it.sitio + " " + it.dominio).lowercase().contains(q)) }
            val nDominios = if (sitio == null) ReglasUsuario.listaDominios().size else 0
            if (ocultar.isEmpty() && estilos.isEmpty() && permisos.isEmpty() && nDominios == 0) {
                hoja.nota(if (q.isEmpty()) "Aún no has añadido reglas. Usa «Ocultar un elemento» en Aspecto o mantén pulsado un anuncio." else "Ninguna regla coincide.")
                return@llenar
            }
            if (ocultar.isNotEmpty()) {
                hoja.seccion("Ocultar elementos · " + ocultar.size)
                for (r in ocultar) {
                    hoja.fila(r.sel, r.host, "Quitar", aviso = true) {
                        confirmar("Quitar regla", "Dejará de ocultarse «" + r.sel + "» en " + r.host + ".", "Quitar") {
                            ReglasUsuario.quitarOcultar(r)
                            g.reinstalarAnuncios()
                            g.avisar("Regla quitada. Recarga las páginas abiertas")
                            repintar()
                        }
                    }
                }
            }
            if (estilos.isNotEmpty()) {
                hoja.seccion("Estilos · " + estilos.size)
                for (r in estilos) {
                    hoja.fila(r.nombre, r.host, "Quitar", aviso = true) {
                        confirmar("Quitar estilo", "Se quitará el estilo «" + r.nombre + "» de " + r.host + ".", "Quitar") {
                            ReglasUsuario.quitarCss(r)
                            g.reinstalarAnuncios()
                            g.avisar("Estilo quitado. Recarga las páginas abiertas")
                            repintar()
                        }
                    }
                }
            }
            if (permisos.isNotEmpty()) {
                hoja.seccion("Dominios permitidos · " + permisos.size)
                for (r in permisos) {
                    hoja.fila(r.dominio, if (r.sitio == ReglasUsuario.TODOS) "En todos los sitios" else "En " + r.sitio, "Quitar", aviso = true) {
                        ReglasUsuario.quitarPermiso(r)
                        g.reinstalarAnuncios()
                        g.avisar("Permiso quitado. Recarga las páginas abiertas")
                        repintar()
                    }
                }
            }
            if (nDominios > 0) {
                hoja.seccion("Dominios bloqueados")
                hoja.fila("Mis dominios bloqueados", null, nDominios.toString(), icono = "🚫") { ir(DOMINIOS) }
            }
        }
    }

    // ---------------------------------------------------------------- dominios

    private fun pintarDominios() {
        titulo("Mis dominios bloqueados")
        val lista = ReglasUsuario.listaDominios()
        hoja.nota(if (lista.isEmpty()) "Aún no has bloqueado ninguno." else "Toca uno para desbloquearlo.")
        hoja.fila("Añadir un dominio…", "Por ejemplo, anuncios.example.com", icono = "＋") {
            Dialogos.texto(act, paleta, false, "Bloquear un dominio", "") { t ->
                val n = UtilDominio.normalizar(t)?.removePrefix("www.")
                if (n != null && ReglasUsuario.agregarDominio(n)) g.avisar("Bloqueado: " + n) else g.avisar("Dominio no válido o ya estaba bloqueado")
                repintar()
            }
        }
        for (d in lista) {
            hoja.fila(d, null, "Desbloquear", aviso = true, icono = "🚫") {
                confirmar("Desbloquear " + d, "Volverá a poder cargarse, salvo que lo bloquee una de las listas activas.", "Desbloquear") {
                    ReglasUsuario.quitarDominio(d)
                    g.avisar("Desbloqueado: " + d)
                    repintar()
                }
            }
        }
        if (lista.isNotEmpty()) {
            hoja.boton("Desbloquear todos…", true) {
                confirmar("Desbloquear todos", "Se quitarán tus " + lista.size + " dominios bloqueados. Las reglas de ocultar elementos y de estilo no cambian.", "Desbloquear") {
                    for (d in ReglasUsuario.listaDominios()) ReglasUsuario.quitarDominio(d)
                    g.avisar("Dominios desbloqueados")
                    repintar()
                }
            }
        }
    }
}
