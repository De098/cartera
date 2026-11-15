package io.github.de098.cartera

import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.Inflater

/**
 * Reconoce lo que lleva el código de un billete para rellenar solos el nombre, el día y la hora:
 *  - tarjetas de embarque IATA BCBP (texto que empieza por «M1»…): vuelo, origen, destino y día (sin año ni hora);
 *  - billetes de tren UIC 918.3 (Aztec binario «#UT»): registros U_HEAD (fecha de emisión) y U_TLAY (texto con fecha, hora y estaciones). El registro U_FLEX (FCB) no se descodifica todavía.
 * Si no lo reconoce devuelve null y no hace nada. No comprueba firmas ni guarda datos personales (el nombre del
 * pasajero no se usa nunca).
 */
object Billetes {
    /** Lo reconocido: «tipo» es «avion» o «tren»; «hora» puede faltar; «resumen» es la línea que se enseña a la persona. */
    class Datos(val tipo: String, val nombre: String, val fecha: LocalDate, val hora: LocalTime?, val resumen: String)


    private fun fechaTexto(f: LocalDate, h: LocalTime?): String =
        "${f.dayOfMonth} ${Nucleo.meses()[f.monthValue - 1]} ${f.year}" + (h?.let { Nucleo.s(R.string.billete_a_las, "%02d:%02d".format(it.hour, it.minute)) } ?: "")

    /**
     * `texto` es lo leído por ZXing y `bytes` los octetos en bruto (si el código era binario). `hoy` solo se
     * cambia en las pruebas.
     */
    fun analizar(texto: String?, bytes: ByteArray?, hoy: LocalDate = LocalDate.now()): Datos? {
        try {
            val b = bytes ?: texto?.takeIf { it.all { c -> c.code < 256 } }?.toByteArray(Charsets.ISO_8859_1)
            if (b != null && b.size > 20 && b[0] == '#'.code.toByte() && b[1] == 'U'.code.toByte() && b[2] == 'T'.code.toByte())
                uic(b, hoy)?.let { return it }
            val t = texto ?: bytes?.toString(Charsets.ISO_8859_1) ?: return null
            return bcbp(t, hoy)
        } catch (_: Exception) { return null }
    }

    // ---------------------------------------------------------------- IATA BCBP

    /** El año que deja el día del año más cerca en el futuro (o hasta 2 días en el pasado). */
    fun fechaDeDiaJuliano(dia: Int, hoy: LocalDate): LocalDate? {
        val limite = hoy.minusDays(2)
        val c = (hoy.year - 1..hoy.year + 2).mapNotNull { a ->
            if (dia in 1..(if (java.time.Year.isLeap(a.toLong())) 366 else 365)) LocalDate.ofYearDay(a, dia) else null
        }
        return c.filter { !it.isBefore(limite) }.minOrNull() ?: c.maxOrNull()
    }

    private fun bcbp(t: String, hoy: LocalDate): Datos? {
        if (t.length < 58 || t[0] != 'M' || t[1] !in '1'..'9') return null
        val origen = t.substring(30, 33); val destino = t.substring(33, 36)
        if (!origen.all { it in 'A'..'Z' } || !destino.all { it in 'A'..'Z' }) return null
        val aerolinea = t.substring(36, 39).trim()
        val numero = t.substring(39, 44).trim()
        val dia = t.substring(44, 47).toIntOrNull() ?: return null
        if (aerolinea.isEmpty() || !aerolinea.all { it.isLetterOrDigit() } || numero.isEmpty() || !numero[0].isDigit()) return null
        val fecha = fechaDeDiaJuliano(dia, hoy) ?: return null
        val sinCeros = numero.trimStart('0').ifEmpty { "0" }
        val vuelo = aerolinea + (if (sinCeros[0].isDigit()) sinCeros else "0$sinCeros")
        return Datos("avion", Nucleo.s(R.string.billete_vuelo, vuelo, origen, destino), fecha, null,
            Nucleo.s(R.string.billete_vuelo_resumen, vuelo, origen, destino, fechaTexto(fecha, null)))
    }

    // ---------------------------------------------------------------- UIC 918.3

    private fun ascii(b: ByteArray, de: Int, hasta: Int): String? =
        if (de < 0 || hasta > b.size || hasta < de) null else String(b, de, hasta - de, Charsets.ISO_8859_1)

    private fun descomprimir(b: ByteArray, de: Int, hasta: Int): ByteArray? {
        if (de >= hasta) return null
        val inf = Inflater()
        return try {
            inf.setInput(b, de, hasta - de)
            val sal = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                sal.write(buf, 0, n)
            }
            if (sal.size() > 0) sal.toByteArray() else null
        } catch (_: Exception) { null } finally { inf.end() }
    }

    private class Registro(val id: String, val version: Int, val datos: ByteArray)

    private fun registros(d: ByteArray): List<Registro> {
        val r = mutableListOf<Registro>()
        var p = 0
        while (p + 12 <= d.size) {
            val id = ascii(d, p, p + 6) ?: break
            val v = ascii(d, p + 6, p + 8)?.toIntOrNull() ?: break
            val l = ascii(d, p + 8, p + 12)?.toIntOrNull() ?: break
            if (l < 12 || p + l > d.size) break
            r += Registro(id, v, d.copyOfRange(p + 12, p + l))
            p += l
        }
        return r
    }

    private fun uic(b: ByteArray, hoy: LocalDate): Datos? {
        val version = ascii(b, 3, 5)?.toIntOrNull() ?: return null
        // cabecera: «#UT», versión (2), empresa (4), clave (5), firma (50 en la 01, 64 en la 02), longitud (4) y datos zlib
        val firmas = (listOfNotNull(if (version == 1) 50 else if (version == 2) 64 else null) + listOf(64, 50)).distinct()
        var datos: ByteArray? = null
        for (f in firmas) {
            val o = 14 + f
            val largo = ascii(b, o, o + 4)?.toIntOrNull()
            val fin = if (largo != null && largo > 0 && o + 4 + largo <= b.size) o + 4 + largo else b.size
            datos = descomprimir(b, o + 4, fin)
            if (datos != null && registros(datos).isNotEmpty()) break
            datos = null
        }
        val regs = registros(datos ?: return null)
        // fecha de emisión (U_HEAD: empresa 4, id 20, fecha DDMMAAAAHHMM)
        var emision: LocalDate? = null
        regs.firstOrNull { it.id == "U_HEAD" }?.let { h ->
            val s = String(h.datos, Charsets.ISO_8859_1)
            if (s.length >= 36 && s.substring(24, 36).all { it.isDigit() })
                emision = try { LocalDate.of(s.substring(28, 32).toInt(), s.substring(26, 28).toInt(), s.substring(24, 26).toInt()) } catch (_: Exception) { null }
        }
        regs.firstOrNull { it.id == "U_TLAY" }?.let { t -> capa(t, emision ?: hoy)?.let { return it } }
        return null
    }

    /** U_TLAY: diseño de texto (RCT2/PLAI): registros fila-columna-alto-ancho-formato-largo-texto. */
    private fun capa(r: Registro, emision: LocalDate): Datos? {
        val d = r.datos
        if (d.size < 8) return null
        var n = ascii(d, 4, 8)?.toIntOrNull() ?: return null
        var p = 8
        val textos = mutableListOf<String>()
        while (n-- > 0 && p + 13 <= d.size) {
            val l = ascii(d, p + 9, p + 13)?.toIntOrNull() ?: break
            if (p + 13 + l > d.size) break
            textos += String(d, p + 13, l, Charsets.UTF_8).trim()
            p += 13 + l
        }
        val fecha = Regex("(?<!\\d)(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{4}|\\d{2}))?(?!\\d)")
        val hora = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
        var f: LocalDate? = null
        var h: LocalTime? = null
        var iFecha = -1
        for ((i, t) in textos.withIndex()) {
            val m = fecha.find(t) ?: continue
            val dd = m.groupValues[1].toInt(); val mm = m.groupValues[2].toInt()
            val aa = m.groupValues[3].let { if (it.isEmpty()) null else if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            f = try {
                if (aa != null) LocalDate.of(aa, mm, dd)
                else (emision.year..emision.year + 1).map { LocalDate.of(it, mm, dd) }.firstOrNull { !it.isBefore(emision.minusDays(2)) }
            } catch (_: Exception) { null }
            if (f != null) { iFecha = i; break }
        }
        if (f == null) return null
        for (t in textos.drop(iFecha).take(4)) {
            val m = hora.find(t) ?: continue
            h = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()); break
        }
        var ruta: String? = null
        val flecha = Regex("^(\\p{L}[\\p{L} .'-]{2,}?)\\s*(?:->|→|=>|>| - )\\s*(\\p{L}[\\p{L} .'-]{2,})$")
        for (t in textos) {
            val m = flecha.find(t) ?: continue
            ruta = m.groupValues[1].trim() + " → " + m.groupValues[2].trim(); break
        }
        val nombre = if (ruta != null) Nucleo.s(R.string.billete_tren, ruta) else Nucleo.s(R.string.billete_tren_sin_ruta)
        val resumen = if (ruta != null) Nucleo.s(R.string.billete_tren_resumen, ruta, fechaTexto(f, h))
                      else Nucleo.s(R.string.billete_tren_resumen_sin_ruta, fechaTexto(f, h))
        return Datos("tren", nombre.take(80), f, h, resumen)
    }
}
