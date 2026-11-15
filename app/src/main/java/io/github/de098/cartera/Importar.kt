package io.github.de098.cartera

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** Lo que se le cuenta a la persona al terminar. «resumen» = varias líneas (se enseñan en una ventana, no en un aviso corto). */
class Resultado(val mensaje: String, val resumen: Boolean = false, val abrir: String? = null, val conFechas: Boolean = false)

/** Una imagen que acompaña a una tarjeta importada (se guarda como adjunto). */
class Imagen(val nombre: String, val rol: String, val ext: String, val abrir: () -> InputStream)

/** Una tarjeta leída de otra app, antes de guardarla. «tipo»: "" = no dice, null = dice uno que no se puede dibujar. */
class Ficha(
    val nombre: String, val numero: String, val codigo: String = "", val tipo: String? = "", val nota: String = "",
    val color: String = "", val caduca: String = "", val evento: Fecha? = null, val favorita: Boolean = false,
    val archivada: Boolean = false, val uso: Long = 0, val imagenes: List<Imagen> = emptyList(),
    val sinNumeroVale: Boolean = false,   // un pase sin código también es una tarjeta (con su imagen)
)

/**
 * Una importación en marcha: guarda las fichas (sin duplicar las que ya están: mismo nombre y número) y cuenta lo que
 * pasa para el resumen final.
 */
class Lote(var origen: String) {
    var nuevas = 0; var repetidas = 0; var sinDibujo = 0; var omitidas = 0; var ilegibles = 0
    var conFechas = false
    val ids = mutableListOf<String>()   // las importadas y las que ya estaban (para abrir la tarjeta)

    private fun clave(nombre: String, numero: String, codigo: String, extra: String = "") =
        (nombre + "|" + numero.ifEmpty { codigo.ifEmpty { extra } }).lowercase()
    private val yaEstan: MutableMap<String, String> by lazy {
        val m = mutableMapOf<String, String>()
        for (j in Tarjetas.todas()) m[clave(j.optString("nombre"), j.optString("numero"), j.optString("codigo"))] = j.optString("id")
        m
    }

    fun anadir(f: Ficha) {
        val nombre = f.nombre.trim().take(80)
        val numero = f.numero.trim()
        if (numero.isEmpty() && f.codigo.isEmpty() && !f.sinNumeroVale) { omitidas++; return }
        val k = clave(nombre, numero, f.codigo, (f.evento?.let { it.dia.toString() + it.hora } ?: "") + f.caduca)
        yaEstan[k]?.let { repetidas++; ids += it; return }

        var tipo = f.tipo ?: "NINGUNO"
        val contenido = f.codigo.ifEmpty { numero }
        if (f.tipo == null) sinDibujo++
        else if (tipo != "NINGUNO" && tipo.isNotEmpty() && (contenido.isEmpty() || Codigos.validar(tipo, contenido) != null)) { tipo = "NINGUNO"; sinDibujo++ }
        else if (tipo.isEmpty()) tipo = "NINGUNO"

        val id = Nucleo.nuevoId()
        val j = JSONObject().put("id", id).put("nombre", nombre).put("numero", numero).put("tipo", tipo)
            .put("nota", f.nota).put("color", f.color).put("favorita", f.favorita).put("caduca", f.caduca)
        if (f.evento != null) {
            j.put("clase", "entrada")
                .put("evento", f.evento.dia.atTime(f.evento.hora ?: java.time.LocalTime.MIDNIGHT).format(Nucleo.ISO_MIN))
                .put("eventoHora", f.evento.hora != null)
        } else j.put("clase", "tarjeta")
        if (f.codigo.isNotEmpty() && tipo != "NINGUNO") j.put("codigo", f.codigo)
        j.put("caducaAvisos", Nucleo.ajustes().optJSONArray("caduca"))

        val adj = JSONArray()
        for (im in f.imagenes) {
            val archivo = File(File(Nucleo.adjuntos, id), Nucleo.nuevoId().take(10) + "." + im.ext)
            try {
                archivo.parentFile?.mkdirs()
                im.abrir().use { i -> archivo.outputStream().use { o -> i.copyTo(o) } }
                adj.put(JSONObject().put("f", archivo.name).put("tipo", "imagen").put("paginas", 1).put("nombre", im.nombre).put("rol", im.rol))
            } catch (_: Exception) { archivo.delete() }
        }
        j.put("adjuntos", adj)

        try {
            Tarjetas.guardar(j)
            val estado = JSONObject().put("uso", f.uso)
            if (f.archivada) estado.put("archivada", true).put("conservar", true)
            Tarjetas.completarImportada(id, estado)
            yaEstan[k] = id
            ids += id
            nuevas++
            if (f.caduca.isNotEmpty() || f.evento != null) conFechas = true
        } catch (_: Exception) {
            File(Nucleo.adjuntos, id).deleteRecursively()
            ilegibles++
        }
    }

    fun resultado(abrirSiUna: Boolean = false): Resultado {
        if (nuevas == 0 && repetidas == 0 && omitidas == 0 && ilegibles == 0)
            return Resultado(Nucleo.s(R.string.res_ninguna))
        val l = mutableListOf<String>()
        l += if (nuevas == 0) Nucleo.s(R.string.res_nada_nuevo)
             else Nucleo.n(R.plurals.res_importadas, nuevas, origen, nuevas)
        if (repetidas > 0) l += Nucleo.n(R.plurals.res_repetidas, repetidas)
        if (sinDibujo > 0) l += Nucleo.n(R.plurals.res_sin_dibujo, sinDibujo)
        if (omitidas > 0) l += Nucleo.n(R.plurals.res_omitidas, omitidas)
        if (ilegibles > 0) l += Nucleo.n(R.plurals.res_ilegibles, ilegibles)
        val abrir = if (abrirSiUna && ids.size == 1) ids[0] else null
        return Resultado(l.joinToString("\n"), resumen = true, abrir = abrir, conFechas = conFechas)
    }
}

/**
 * Importar de otras apps: se mira el CONTENIDO del archivo, nunca su nombre.
 *   .zip con catima.csv → Catima · con copia.json de Cartera → copia propia · con pass.json → pase .pkpass ·
 *   con varios .pkpass → .pkpasses · texto con cabecera → CSV (Catima antiguo, Loyalty Card Keychain o genérico).
 * Un .zip cifrado con contraseña (Catima permite exportar así) no se puede abrir sin librerías externas: se avisa.
 */
object Importar {
    private const val MAX_TEXTO = 50L * 1024 * 1024

    @Synchronized
    fun archivo(f: File, abrirSiUna: Boolean = false): Resultado {
        val cab = ByteArray(4096)
        val n = f.inputStream().use { it.read(cab).coerceAtLeast(0) }
        if (n >= 4 && cab[0] == 'P'.code.toByte() && cab[1] == 'K'.code.toByte() && (cab[2].toInt() == 3 || cab[2].toInt() == 5)) return zip(f, abrirSiUna)
        if ((0 until n).any { cab[it].toInt() == 0 } && !(n >= 2 && (cab[0] == 0xFF.toByte() || cab[0] == 0xFE.toByte())))
            return Resultado(noReconozco())
        if (f.length() > MAX_TEXTO) return Resultado(Nucleo.s(R.string.err_importar_enorme))
        return texto(Csv.decodificar(f.readBytes()))
    }

    /** Mensaje de «archivo no reconocido» (en el idioma del móvil). */
    fun noReconozco(): String = Nucleo.s(R.string.err_no_reconozco)

    private fun texto(t: String): Resultado {
        val lote = Lote("CSV")
        val error = TablaCsv.importar(lote, t) { emptyList() }
        return if (error != null) Resultado(error) else lote.resultado()
    }

    private fun zip(f: File, abrirSiUna: Boolean): Resultado {
        if (zipCifrado(f)) return Resultado(Nucleo.s(R.string.err_cifrado))
        val z = try { ZipFile(f) } catch (_: Exception) { return Resultado(Nucleo.s(R.string.err_zip)) }
        z.use {
            val nombres = z.entries().asSequence().map { it.name }.toList()
            fun hay(fin: String) = nombres.any { it.equals(fin, ignoreCase = true) }
            return when {
                hay("catima.csv") -> Catima.importar(z)
                hay("copia.json") -> Copia.importarCartera(z)
                nombres.any { it.equals("pass.json", true) || it.endsWith("/pass.json", true) } || nombres.any { it.endsWith(".pkpass", true) } ->
                    Pkpass.importarZip(z, abrirSiUna)
                nombres.count { it.endsWith(".csv", true) } == 1 -> {
                    val e = z.entries().asSequence().first { it.name.endsWith(".csv", true) }
                    texto(Csv.decodificar(z.getInputStream(e).readBytes()))
                }
                else -> Resultado(noReconozco())
            }
        }
    }

    // ---------------------------------------------------------------- zip cifrado

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    /** Mira el directorio central del .zip: ¿alguna entrada va cifrada (marca de cifrado o método 99 de AES)? */
    fun zipCifrado(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { r ->
            val largo = r.length()
            val n = minOf(largo, 65557L).toInt()
            val fin = ByteArray(n)
            r.seek(largo - n); r.readFully(fin)
            var i = n - 22
            while (i >= 0 && u32(fin, i) != 0x06054b50L) i--
            if (i < 0) return false
            val tam = u32(fin, i + 12); val pos = u32(fin, i + 16)
            if (pos == 0xFFFFFFFFL || tam > 16L * 1024 * 1024) return false
            val dc = ByteArray(tam.toInt())
            r.seek(pos); r.readFully(dc)
            var p = 0
            var cifrado = false
            while (p + 46 <= dc.size && u32(dc, p) == 0x02014b50L) {
                if ((u16(dc, p + 8) and 1) != 0 || u16(dc, p + 10) == 99) { cifrado = true; break }
                p += 46 + u16(dc, p + 28) + u16(dc, p + 30) + u16(dc, p + 32)
            }
            cifrado
        }
    } catch (_: Exception) { false }
}
