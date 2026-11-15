package io.github.de098.cartera

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Lo que se hace con las tarjetas: leer, guardar, borrar, archivar, y los avisos que les tocan (el día del evento,
 * un aviso previo y la caducidad). Las tarjetas son JSON suelto: ver [Nucleo].
 */
object Tarjetas {
    /** Un evento con hora se archiva a las 3 h de empezar; uno sin hora, al acabar el día. */
    val GRACIA_EVENTO: Duration = Duration.ofHours(3)
    private val COLOR = Regex("#[0-9a-fA-F]{6}")
    private val FECHA = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** Un aviso que hay que programar: «clave» es única por tarjeta y motivo. */
    class Aviso(val clave: String, val cuando: LocalDateTime, val titulo: String, val texto: String,
                val tarjeta: String, val canal: String)

    private fun archivo(id: String) = File(Nucleo.tarjetas, "$id.json")

    fun leer(id: String): JSONObject? {
        if (!Nucleo.ID.matches(id)) return null
        return try { JSONObject(archivo(id).readText()) } catch (_: Exception) { null }
    }

    fun todas(): List<JSONObject> {
        val r = mutableListOf<JSONObject>()
        Nucleo.tarjetas.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile && f.name.endsWith(".json") && !f.name.startsWith("."))
                try { r += JSONObject(f.readText()) } catch (_: Exception) {}
        }
        return r
    }

    /** Días de aviso: solo enteros de 0 a 365, sin repetir y de mayor a menor (30, 7, 0). */
    fun diasAviso(a: JSONArray?): JSONArray {
        val l = mutableSetOf<Int>()
        if (a != null) for (i in 0 until a.length()) { val d = a.optInt(i, -1); if (d in 0..365) l += d }
        return JSONArray(l.sortedDescending())
    }

    private fun normalizarEvento(s: String): String? = try {
        if (s.length == 10) LocalDate.parse(s).atStartOfDay().format(Nucleo.ISO_MIN)
        else LocalDateTime.parse(s).format(Nucleo.ISO_MIN)
    } catch (_: Exception) { null }

    private fun fechaHora(s: String): LocalDateTime? = try { LocalDateTime.parse(s) } catch (_: Exception) { null }

    private fun fechaValida(s: String): Boolean = FECHA.matches(s) && try { LocalDate.parse(s); true } catch (_: Exception) { false }

    /**
     * Guarda una tarjeta nueva o una que ya existe (por su id) y devuelve su id. Solo se tocan los campos que llegan;
     * el resto (también los que esta versión no conoce) se conserva. Lanza [ErrorUsuario] si algo no vale.
     */
    @Synchronized
    fun guardar(e: JSONObject): String {
        val idIn = e.optString("id")
        val id = if (Nucleo.ID.matches(idIn)) idIn else Nucleo.nuevoId()
        val antes = leer(id)
        val j = if (antes != null) JSONObject(antes.toString()) else JSONObject().put("creada", System.currentTimeMillis())
        j.put("id", id)
        j.put("formato", 1)

        val nombre = e.optString("nombre", j.optString("nombre")).trim().take(80)
        if (nombre.isEmpty()) throw ErrorUsuario(Nucleo.s(R.string.err_pon_nombre))
        j.put("nombre", nombre)
        val clase = if (e.optString("clase", j.optString("clase")) == "entrada") "entrada" else "tarjeta"
        j.put("clase", clase)

        val numero = e.optString("numero", j.optString("numero")).trim().take(1000)
        j.put("numero", numero)
        if (e.has("codigo")) {
            val cod = e.optString("codigo").take(4000).let { if (e.optString("bruto").isEmpty()) it.trim() else it }
            if (cod.isEmpty()) j.remove("codigo") else j.put("codigo", cod)
        }
        // octetos en bruto del código (base64), si es binario: sirven para verlos y volver a reconocer la fecha
        if (e.has("bruto")) {
            val br = e.optString("bruto").take(8000)
            if (br.isEmpty()) j.remove("bruto") else j.put("bruto", br)
        }
        var tipo = e.optString("tipo", j.optString("tipo"))
        if (tipo !in Nucleo.CLAVES) tipo = "NINGUNO"
        if (j.optString("codigo").ifEmpty { numero }.isEmpty()) tipo = "NINGUNO"
        j.put("tipo", tipo)
        if (tipo != "NINGUNO") {
            val fallo = Codigos.validar(tipo, j.optString("codigo").ifEmpty { numero }, j.has("bruto"))
            if (fallo != null) throw ErrorUsuario(fallo)
        }

        val color = e.optString("color", j.optString("color"))
        j.put("color", if (COLOR.matches(color)) color.lowercase() else "")
        j.put("nota", e.optString("nota", j.optString("nota")).trim().take(2000))
        if (e.has("favorita")) j.put("favorita", e.optBoolean("favorita"))

        if (clase == "entrada") {
            val ev = normalizarEvento(e.optString("evento", j.optString("evento")))
                ?: throw ErrorUsuario(Nucleo.s(R.string.err_pon_dia))
            j.put("evento", ev)
            j.put("eventoHora", if (e.has("eventoHora")) e.optBoolean("eventoHora") else j.optBoolean("eventoHora", true))
            j.put("previo", (if (e.has("previo")) e.optInt("previo") else j.optInt("previo", Nucleo.ajustes().optInt("previo"))).coerceIn(0, 30))
        } else {
            j.remove("evento"); j.remove("eventoHora"); j.remove("previo")
        }

        val caduca = e.optString("caduca", j.optString("caduca"))
        if (caduca.isNotEmpty() && !fechaValida(caduca)) throw ErrorUsuario(Nucleo.s(R.string.err_fecha_cad))
        j.put("caduca", caduca)
        j.put("caducaAvisos", diasAviso(
            if (e.has("caducaAvisos")) e.optJSONArray("caducaAvisos") else j.optJSONArray("caducaAvisos") ?: Nucleo.ajustes().optJSONArray("caduca")))

        // adjuntos: solo los que existen de verdad; los archivos que sobran se borran
        val lista = JSONArray()
        val dir = File(Nucleo.adjuntos, id)
        val fuente = if (e.has("adjuntos")) e.optJSONArray("adjuntos") else j.optJSONArray("adjuntos")
        val buenos = mutableSetOf<String>()
        if (fuente != null) for (i in 0 until fuente.length()) {
            val a = fuente.optJSONObject(i) ?: continue
            val f = a.optString("f")
            if (!Nucleo.ADJUNTO.matches(f) || !File(dir, f).isFile || f in buenos) continue
            buenos += f
            lista.put(JSONObject().put("f", f)
                .put("tipo", if (f.endsWith(".pdf")) "pdf" else "imagen")
                .put("nombre", a.optString("nombre").take(100))
                .put("paginas", a.optInt("paginas", 1).coerceAtLeast(1))
                .put("rol", a.optString("rol").take(20)))
        }
        j.put("adjuntos", lista)
        dir.listFiles()?.forEach { if (it.name !in buenos) it.delete() }

        // si cambia el día del evento, vuelve a su sitio: ya no está archivada ni «conservada»
        if (antes != null && antes.optString("evento") != j.optString("evento")) {
            j.put("archivada", false); j.put("conservar", false)
        }
        Nucleo.escribir(archivo(id), j.toString(1))
        return id
    }

    /** Lo que reconoce [Billetes] del código, como JSON para la página (nombre, evento, eventoHora, tipo, resumen), o null. */
    fun reconocerCodigo(texto: String?, bytes: ByteArray?): JSONObject? {
        val d = Billetes.analizar(texto, bytes) ?: return null
        val ev = if (d.hora != null) d.fecha.atTime(d.hora).format(Nucleo.ISO_MIN) else d.fecha.toString()
        return JSONObject().put("tipo", d.tipo).put("nombre", d.nombre).put("evento", ev)
            .put("eventoHora", d.hora != null).put("resumen", d.resumen)
    }

    /** «Reconocer fecha» en una tarjeta ya guardada: vuelve a pasar el analizador por su código. */
    fun reconocer(id: String): JSONObject? {
        val j = leer(id) ?: return null
        val bytes = j.optString("bruto").takeIf { it.isNotEmpty() }?.let { try { java.util.Base64.getDecoder().decode(it) } catch (_: Exception) { null } }
        return reconocerCodigo(j.optString("codigo").ifEmpty { j.optString("numero") }, bytes)
    }

    @Synchronized
    fun borrar(id: String) {
        if (!Nucleo.ID.matches(id)) return
        archivo(id).delete()
        File(Nucleo.adjuntos, id).deleteRecursively()
    }

    @Synchronized
    private fun cambiar(id: String, cambio: (JSONObject) -> Unit) {
        val j = leer(id) ?: return
        cambio(j)
        Nucleo.escribir(archivo(id), j.toString(1))
    }

    /** Tras guardar una tarjeta venida de una copia: recupera su estado (uso, archivada…) y los campos que esta versión no conoce. */
    fun completarImportada(id: String, origen: JSONObject) = cambiar(id) { destino ->
        for (k in listOf("creada", "uso", "archivada", "conservar")) if (origen.has(k)) destino.put(k, origen.get(k))
        for (k in origen.keys()) if (!destino.has(k)) destino.put(k, origen.get(k))
    }

    fun marcarUso(id: String) = cambiar(id) { it.put("uso", System.currentTimeMillis()) }
    fun poner(id: String, campo: String, valor: Boolean) = cambiar(id) { it.put(campo, valor) }

    /** Archivar a mano: se queda archivada aunque el evento no haya pasado. */
    fun archivar(id: String) = cambiar(id) { it.put("archivada", true) }

    /** Restaurar: vuelve a la lista y ya no se archiva sola (hasta que se cambie el día del evento). */
    fun restaurar(id: String) = cambiar(id) { it.put("archivada", false); it.put("conservar", true) }

    private fun vencida(j: JSONObject, ahora: LocalDateTime): Boolean {
        if (j.optString("clase") != "entrada") return false
        val ev = try { LocalDateTime.parse(j.optString("evento")) } catch (_: Exception) { return false }
        val fin = if (j.optBoolean("eventoHora", true)) ev.plus(GRACIA_EVENTO) else ev.toLocalDate().plusDays(1).atStartOfDay()
        return ahora.isAfter(fin)
    }

    /** Las entradas y billetes cuyo evento ya ha pasado se archivan solos. */
    @Synchronized
    fun archivarVencidas() {
        val ahora = LocalDateTime.now()
        for (j in todas()) {
            if (j.optBoolean("archivada") || j.optBoolean("conservar") || !vencida(j, ahora)) continue
            j.put("archivada", true)
            Nucleo.escribir(archivo(j.optString("id")), j.toString(1))
        }
    }

    /** Carpetas de adjuntos de tarjetas que ya no existen (p. ej. se empezó a añadir una y no se guardó). */
    fun limpiarHuerfanos() {
        val ayer = System.currentTimeMillis() - 24 * 3600 * 1000L
        Nucleo.adjuntos.listFiles()?.forEach { d ->
            if (d.isDirectory && !archivo(d.name).exists() && d.lastModified() < ayer) d.deleteRecursively()
        }
    }

    /** Lo que pide la página al abrirse: tarjetas ordenadas (favoritas, luego las usadas hace menos), formatos y ajustes. */
    fun datosJson(): String {
        archivarVencidas()
        val hoy = LocalDate.now()
        val orden = compareByDescending<JSONObject> { it.optBoolean("favorita") }
            .thenByDescending { it.optLong("uso") }
            .thenByDescending { it.optLong("creada") }
        val a = JSONArray()
        for (j in todas().sortedWith(orden)) {
            val c = j.optString("caduca")
            if (fechaValida(c)) {
                val dias = ChronoUnit.DAYS.between(hoy, LocalDate.parse(c)).toInt()
                val avisos = j.optJSONArray("caducaAvisos")
                var umbral = 30
                if (avisos != null) for (i in 0 until avisos.length()) umbral = maxOf(umbral, avisos.optInt(i))
                j.put("_dias", dias)
                j.put("_caduca", if (dias < 0) "caducada" else if (dias <= umbral) "pronto" else "")
            }
            a.put(j)
        }
        return JSONObject()
            .put("tarjetas", a)
            .put("formatos", JSONArray(Nucleo.FORMATOS.map { JSONArray().put(it.first).put(it.second) }))
            .put("ajustes", Nucleo.ajustes())
            .toString()
    }

    /** Los avisos de todas las tarjetas que no están archivadas (los que ya pasaron los quita quien los programa). */
    fun avisos(): List<Aviso> {
        val r = mutableListOf<Aviso>()
        val hora = Nucleo.horaAvisos()
        for (j in todas()) {
            if (j.optBoolean("archivada")) continue
            val id = j.optString("id")
            val nombre = j.optString("nombre")
            val ev: LocalDateTime? = if (j.optString("clase") == "entrada") fechaHora(j.optString("evento")) else null
            if (ev != null) {
                val conHora = j.optBoolean("eventoHora", true)
                val hm = ev.format(Nucleo.HM)
                // el día del evento: a la hora de los avisos, o una hora antes si el evento es más temprano
                var dia = ev.toLocalDate().atTime(hora)
                if (conHora && dia.isAfter(ev.minusHours(1))) dia = ev.minusHours(1)
                r += Aviso("$id:ev", dia, "${Nucleo.s(R.string.aviso_hoy)}: $nombre",
                    if (conHora) Nucleo.s(R.string.aviso_hoy_hora, hm) else Nucleo.s(R.string.aviso_hoy_sin_hora), id, "entradas")
                val previo = j.optInt("previo", 0)
                if (previo > 0) {
                    val cuando = if (previo == 1) Nucleo.s(R.string.aviso_manana) else Nucleo.n(R.plurals.aviso_en_dias, previo)
                    r += Aviso("$id:pre", ev.toLocalDate().minusDays(previo.toLong()).atTime(hora),
                        "$cuando: $nombre",
                        if (conHora) Nucleo.s(R.string.aviso_texto_hora, cuando, hm) else Nucleo.s(R.string.aviso_texto_sin_hora, cuando), id, "entradas")
                }
            }
            val c = j.optString("caduca")
            val avisos = j.optJSONArray("caducaAvisos")
            if (fechaValida(c) && avisos != null) {
                val fecha = LocalDate.parse(c)
                for (i in 0 until avisos.length()) {
                    val d = avisos.optInt(i, -1)
                    if (d < 0) continue
                    val texto = when (d) {
                        0 -> Nucleo.s(R.string.aviso_cad_hoy)
                        1 -> Nucleo.s(R.string.aviso_cad_manana)
                        else -> Nucleo.n(R.plurals.aviso_cad_en, d)
                    }
                    r += Aviso("$id:cad$d", fecha.minusDays(d.toLong()).atTime(hora), "$texto: $nombre",
                        Nucleo.s(R.string.aviso_cad_fecha, "${fecha.dayOfMonth}/${fecha.monthValue}/${fecha.year}"), id, "caducidad")
                }
            }
        }
        return r.sortedBy { it.cuando }
    }
}
