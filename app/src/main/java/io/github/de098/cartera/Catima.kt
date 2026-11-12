package io.github.de098.cartera

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Importar la copia de Catima (su .zip de exportación): trae catima.csv, con una fila por tarjeta, y a veces las imágenes
 * de cada una. Se importan el nombre (store), el número (cardid), el contenido del código si es otro (barcodeid), el
 * tipo de código (barcodetype), la nota, el color, la caducidad, favorita (starstatus), archivada, el último uso y,
 * si están, las imágenes de frente y dorso (como adjuntos con rol «frente» y «dorso»).
 * Las tarjetas que ya están (mismo nombre y número) no se repiten.
 */
object Catima {
    private val IMAGEN = Regex("(?i)(?:.*/)?(?:card_)?(\\d+)_(front|back)\\.(png|jpe?g|webp)")

    /** Lee un CSV (comillas dobles, comas y saltos de línea dentro de las comillas) y da sus filas. */
    fun leerCsv(t: String): List<List<String>> {
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
            } else when (ch) {
                '"' -> comillas = true
                ',' -> { fila.add(campo.toString()); campo.setLength(0) }
                '\r' -> {}
                '\n' -> { fila.add(campo.toString()); campo.setLength(0); filas.add(fila); fila = mutableListOf() }
                else -> campo.append(ch)
            }
            i++
        }
        if (campo.isNotEmpty() || fila.isNotEmpty()) { fila.add(campo.toString()); filas.add(fila) }
        return filas
    }

    private fun color(v: String): String {
        val n = v.toLongOrNull() ?: return ""
        return String.format("#%06x", n.toInt() and 0xFFFFFF)
    }

    private fun fecha(v: String): String {
        val n = v.toLongOrNull() ?: return ""
        if (n <= 0) return ""
        val ms = if (n < 100_000_000_000L) n * 1000 else n
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

    private fun milis(v: String): Long {
        val n = v.toLongOrNull() ?: return 0
        return if (n in 1 until 100_000_000_000L) n * 1000 else maxOf(n, 0)
    }

    fun importar(z: ZipFile): String {
        val csv = z.entries().asSequence().firstOrNull { it.name.equals("catima.csv", ignoreCase = true) }
            ?: return "Esa copia de Catima no trae catima.csv"
        val texto = z.getInputStream(csv).bufferedReader(Charsets.UTF_8).readText().removePrefix("﻿")

        // el CSV son varias tablas separadas por una fila vacía (grupos, tarjetas…): la de las tarjetas lleva «store»
        val tablas = mutableListOf<List<List<String>>>()
        var actual = mutableListOf<List<String>>()
        for (f in leerCsv(texto)) {
            if (f.size == 1 && f[0].isEmpty()) { if (actual.isNotEmpty()) { tablas += actual; actual = mutableListOf() } }
            else actual += f
        }
        if (actual.isNotEmpty()) tablas += actual
        val tabla = tablas.firstOrNull { t -> t[0].any { it.trim().equals("store", ignoreCase = true) } }
            ?: return "No se ha encontrado ninguna tarjeta en esa copia de Catima"
        val cab = tabla[0].map { it.trim().lowercase() }
        fun col(f: List<String>, n: String): String { val i = cab.indexOf(n); return if (i in f.indices) f[i].trim() else "" }

        // imágenes por tarjeta de Catima: id → [(frente|back, entrada)]
        val imagenes = mutableMapOf<String, MutableList<Pair<String, ZipEntry>>>()
        for (e in z.entries()) {
            val m = IMAGEN.matchEntire(e.name) ?: continue
            imagenes.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2].lowercase() to e)
        }

        val yaEstan = Tarjetas.todas().map { (it.optString("nombre") + "|" + it.optString("numero")).lowercase() }.toMutableSet()
        var nuevas = 0; var repetidas = 0; var sinDibujo = 0
        for (f in tabla.drop(1)) {
            val nombre = col(f, "store")
            if (nombre.isEmpty()) continue
            val numero = col(f, "cardid")
            if (!yaEstan.add((nombre + "|" + numero).lowercase())) { repetidas++; continue }

            val tipoCat = col(f, "barcodetype").uppercase()
            var tipo = if (Nucleo.FORMATOS.any { it.first == tipoCat && it.first != "NINGUNO" }) tipoCat else "NINGUNO"
            val barras = col(f, "barcodeid")
            val codigo = if (barras.isNotEmpty() && barras != numero) barras else ""
            if (tipo != "NINGUNO" && Codigos.validar(tipo, codigo.ifEmpty { numero }) != null) { tipo = "NINGUNO"; sinDibujo++ }
            else if (tipo == "NINGUNO" && tipoCat.isNotEmpty() && tipoCat != "NULL") sinDibujo++

            val id = Nucleo.nuevoId()
            val j = JSONObject().put("id", id).put("nombre", nombre).put("clase", "tarjeta").put("numero", numero)
                .put("tipo", tipo).put("nota", col(f, "note")).put("color", color(col(f, "headercolor")))
                .put("favorita", col(f, "starstatus") == "1").put("caduca", fecha(col(f, "expiry")))
            if (codigo.isNotEmpty() && tipo != "NINGUNO") j.put("codigo", codigo)
            j.put("caducaAvisos", Nucleo.ajustes().optJSONArray("caduca"))

            // imágenes de frente y dorso, si vienen (se guardan como adjuntos)
            val adj = JSONArray()
            for ((lado, e) in imagenes[col(f, "_id")].orEmpty()) {
                val ext = e.name.substringAfterLast('.').lowercase().let { if (it == "jpeg") "jpg" else it }
                val archivo = File(File(Nucleo.adjuntos, id), Nucleo.nuevoId().take(10) + "." + ext)
                try {
                    archivo.parentFile?.mkdirs()
                    z.getInputStream(e).use { i -> archivo.outputStream().use { o -> i.copyTo(o) } }
                    adj.put(JSONObject().put("f", archivo.name).put("tipo", "imagen").put("paginas", 1)
                        .put("nombre", if (lado == "front") "Frente" else "Dorso").put("rol", if (lado == "front") "frente" else "dorso"))
                } catch (_: Exception) { archivo.delete() }
            }
            j.put("adjuntos", adj)

            try {
                Tarjetas.guardar(j)
                val estado = JSONObject().put("uso", milis(col(f, "lastused")))
                if (col(f, "archive") == "1") estado.put("archivada", true).put("conservar", true)
                Tarjetas.completarImportada(id, estado)
                nuevas++
            } catch (_: Exception) { File(Nucleo.adjuntos, id).deleteRecursively() }
        }
        var msj = if (nuevas == 0) "Esa copia de Catima no tenía nada nuevo" else "Recuperado de Catima: $nuevas tarjeta${if (nuevas > 1) "s" else ""}"
        if (repetidas > 0) msj += ". $repetidas ya estaban"
        if (sinDibujo > 0) msj += ". ${if (sinDibujo > 1) "$sinDibujo tarjetas tenían" else "Una tarjeta tenía"} un tipo de código que aquí no se puede dibujar: se enseña solo el número"
        return msj
    }
}
