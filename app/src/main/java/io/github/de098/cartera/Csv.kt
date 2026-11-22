package io.github.de098.cartera

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Un día y, si se sabe, la hora. */
class Fecha(val dia: LocalDate, val hora: LocalTime?)

/**
 * Lo común a todas las importaciones de texto: leer un CSV (con la coma, el punto y coma o el tabulador como
 * separador, comillas dobles y saltos de línea dentro de las comillas), decodificar el texto, y entender fechas,
 * colores y tipos de código escritos de muchas maneras.
 */
object Csv {
    /** Lee un CSV y da sus filas. */
    fun leer(t: String, sep: Char = ','): List<List<String>> {
        val filas = mutableListOf<List<String>>()
        var fila = mutableListOf<String>()
        val campo = StringBuilder()
        var comillas = false
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (comillas) {
                if (ch == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') { campo.append('"'); i++ } else comillas = false
                } else campo.append(ch)
            } else when {
                ch == '"' -> comillas = true
                ch == sep -> { fila.add(campo.toString()); campo.setLength(0) }
                ch == '\r' -> {}
                ch == '\n' -> { fila.add(campo.toString()); campo.setLength(0); filas.add(fila); fila = mutableListOf() }
                else -> campo.append(ch)
            }
            i++
        }
        if (campo.isNotEmpty() || fila.isNotEmpty()) { fila.add(campo.toString()); filas.add(fila) }
        return filas
    }

    /** El separador más probable: el que más veces sale (fuera de comillas) en la primera línea que tenga alguno. */
    fun separador(t: String): Char {
        for (linea in t.lineSequence().take(40)) {
            val cuenta = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
            var comillas = false
            for (ch in linea) {
                if (ch == '"') comillas = !comillas
                else if (!comillas && ch in cuenta) cuenta[ch] = cuenta.getValue(ch) + 1
            }
            val mejor = cuenta.maxByOrNull { it.value }!!
            if (mejor.value > 0) return mejor.key
        }
        return ','
    }

    /** Bytes → texto: UTF-8 (con o sin marca), UTF-16 con marca, y si no es UTF-8 válido, Windows-1252. */
    fun decodificar(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val sinMarca = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, sinMarca, b.size - sinMarca)).toString()
        } catch (_: Exception) { String(b, sinMarca, b.size - sinMarca, charset("windows-1252")) }
    }

    /** Nombre de columna para comparar: minúsculas, sin tildes y solo letras y cifras («Número de tarjeta» → «numerodetarjeta»). */
    fun clave(s: String): String =
        java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]"), "")

    fun verdad(v: String): Boolean = v.trim().lowercase() in setOf("1", "true", "yes", "si", "sí", "y", "s", "x", "verdadero", "oui", "o", "vrai", "cert", "ja", "j", "wahr", "vero", "sim")

    // ---------------------------------------------------------------- fechas

    private val DMA = Regex("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})(?:[ T,]+(\\d{1,2}):(\\d{2}))?(?::\\d{2})?$")
    private val AMD = Regex("^(\\d{4})[/.](\\d{1,2})[/.](\\d{1,2})(?:[ T,]+(\\d{1,2}):(\\d{2}))?(?::\\d{2})?$")
    private val MA = Regex("^(\\d{1,2})[/-](\\d{4}|\\d{2})$")

    private fun anio(s: String) = if (s.length == 2) 2000 + s.toInt() else s.toInt()

    /** Entiende fechas de muchas formas: ISO (con o sin hora y zona), 31/12/2026, 31-12-26, 12/2026 (fin de mes), 20261231, milisegundos. */
    fun fecha(v0: String): Fecha? {
        val v = v0.trim()
        if (v.isEmpty()) return null
        try {
            if (v.all { it.isDigit() }) {
                if (v.length == 8) {
                    val d = LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
                    return Fecha(d, null)
                }
                val n = v.toLong()
                if (n <= 0) return null
                val ms = if (n < 100_000_000_000L) n * 1000 else n
                val l = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()
                return Fecha(l.toLocalDate(), l.toLocalTime())
            }
            val iso = v.replace(Regex("^(\\d{4}-\\d{2}-\\d{2}) (?=\\d)"), "$1T")
            if (iso.length > 10 && iso[10] == 'T') {
                val norm = iso.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
                try {
                    val o = OffsetDateTime.parse(norm)
                    // con «Z» no se sabe dónde es: la hora del móvil; con un desfase, la hora tal como está escrita
                    val l = if (o.offset == ZoneOffset.UTC) o.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() else o.toLocalDateTime()
                    return Fecha(l.toLocalDate(), l.toLocalTime())
                } catch (_: Exception) {}
                try { val l = LocalDateTime.parse(norm); return Fecha(l.toLocalDate(), l.toLocalTime()) } catch (_: Exception) {}
                try { return Fecha(LocalDate.parse(norm.substring(0, 10)), null) } catch (_: Exception) {}
                return null
            }
            if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(iso)) return Fecha(LocalDate.parse(iso), null)
            DMA.matchEntire(v)?.let { m ->
                val d = LocalDate.of(anio(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                val h = if (m.groupValues[4].isNotEmpty()) LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt()) else null
                return Fecha(d, h)
            }
            AMD.matchEntire(v)?.let { m ->
                val d = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                val h = if (m.groupValues[4].isNotEmpty()) LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt()) else null
                return Fecha(d, h)
            }
            MA.matchEntire(v)?.let { m ->
                return Fecha(LocalDate.of(anio(m.groupValues[2]), m.groupValues[1].toInt(), 1).let { it.withDayOfMonth(it.lengthOfMonth()) }, null)
            }
        } catch (_: Exception) {}
        return null
    }

    // ---------------------------------------------------------------- colores

    /** «#rrggbb», «rrggbb», «#rgb», «#aarrggbb», «rgb(1, 2, 3)» o un número de Android con signo (los de Catima) → «#rrggbb», o «». */
    fun color(v0: String): String {
        val v = v0.trim().lowercase()
        if (v.isEmpty() || v == "null") return ""
        Regex("^rgba?\\(\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})").find(v)?.let { m ->
            val (r, g, b) = m.destructured
            return String.format("#%02x%02x%02x", r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }
        if (Regex("^-?\\d+$").matches(v) && (v.length != 6 || v.startsWith("-"))) {   // número de Android (los de Catima); 6 cifras = hexadecimal
            val n = v.toLongOrNull() ?: return ""
            return String.format("#%06x", n.toInt() and 0xFFFFFF)
        }
        val h = v.removePrefix("#")
        if (!Regex("^[0-9a-f]+$").matches(h)) return ""
        return when (h.length) {
            3 -> "#" + h.map { "$it$it" }.joinToString("")
            6 -> "#$h"
            8 -> "#" + h.substring(2)
            else -> ""
        }
    }

    // ---------------------------------------------------------------- tipos de código

    private val TIPOS: Map<String, String> by lazy {
        val m = mutableMapOf<String, String>()
        for (k in Nucleo.CLAVES) if (k != "NINGUNO") m[k.replace("_", "")] = k
        m["QR"] = "QR_CODE"; m["QRCODE"] = "QR_CODE"; m["CODIGOQR"] = "QR_CODE"
        m["EAN"] = "EAN_13"; m["EAN13"] = "EAN_13"; m["EAN8"] = "EAN_8"
        m["UPC"] = "UPC_A"; m["UPCA"] = "UPC_A"; m["UPCE"] = "UPC_E"
        m["CODE128"] = "CODE_128"; m["CODE39"] = "CODE_39"; m["CODE93"] = "CODE_93"
        m["PDF417"] = "PDF_417"; m["DATAMATRIX"] = "DATA_MATRIX"
        m["ITF14"] = "ITF"; m["INTERLEAVED2OF5"] = "ITF"; m["I25"] = "ITF"; m["2OF5INTERLEAVED"] = "ITF"
        m["PKBARCODEFORMATQR"] = "QR_CODE"; m["PKBARCODEFORMATPDF417"] = "PDF_417"
        m["PKBARCODEFORMATAZTEC"] = "AZTEC"; m["PKBARCODEFORMATCODE128"] = "CODE_128"
        m
    }

    /** Clave del tipo de código (como las de [Nucleo.FORMATOS]) para un nombre escrito de cualquier forma; null si no se sabe dibujar. */
    fun tipo(texto: String): String? = TIPOS[texto.uppercase().replace(Regex("[^A-Z0-9]"), "")]
}
