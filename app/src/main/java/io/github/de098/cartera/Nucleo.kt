package io.github.de098.cartera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Un fallo que se le puede contar a la persona tal cual (la página lo enseña en un aviso). */
class ErrorUsuario(mensaje: String) : Exception(mensaje)

/**
 * Datos y reglas comunes. Cada tarjeta (o entrada, o billete) es un archivo .json en tarjetas/ y sus imágenes o PDF
 * están en adjuntos/«id de la tarjeta»/, todo en la carpeta privada de la app.
 *
 * Las tarjetas se guardan como JSON suelto (no como una clase fija) a propósito: al guardar se conservan los campos que
 * esta versión no conoce, así las versiones siguientes (p. ej. fotos de cara y dorso de tarjetas sin código) pueden
 * añadir los suyos sin romper lo ya guardado. Campos de una tarjeta, todos opcionales salvo id y nombre:
 *   id, nombre, numero, tipo (clave de [FORMATOS]), codigo (si el código lleva algo distinto del número),
 *   clase ("tarjeta" | "entrada"), color (#rrggbb), nota, favorita,
 *   creada, uso (milisegundos), archivada, conservar (restaurada a mano: no se archiva sola),
 *   evento (AAAA-MM-DDTHH:mm), eventoHora (false = solo se sabe el día), previo (días de aviso previo, 0 = ninguno),
 *   caduca (AAAA-MM-DD), caducaAvisos (días antes en que avisa; 0 = el mismo día),
 *   bruto (octetos del código en base64, solo si es binario: billetes UIC…),
   adjuntos [{f, tipo ("imagen" | "pdf"), nombre, paginas, rol}]; «rol» está reservado para «frente» y «dorso».
 */
object Nucleo {
    val ID = Regex("[0-9a-f]{6,32}")
    /** Nombre de archivo de un adjunto: nunca lleva rutas. */
    val ADJUNTO = Regex("[a-z0-9]{4,32}\\.(png|jpg|webp|gif|pdf)")

    val ISO_MIN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Clave de ZXing → nombre que se enseña. «NINGUNO» = sin código: solo se enseña el número. */
    val FORMATOS = listOf(
        "QR_CODE" to "Código QR",
        "EAN_13" to "EAN-13",
        "EAN_8" to "EAN-8",
        "UPC_A" to "UPC-A",
        "CODE_128" to "Code 128",
        "CODE_39" to "Code 39",
        "AZTEC" to "Aztec",
        "PDF_417" to "PDF417",
        "DATA_MATRIX" to "Data Matrix",
        "UPC_E" to "UPC-E",
        "ITF" to "ITF (2 de 5 entrelazado)",
        "CODABAR" to "Codabar",
        "CODE_93" to "Code 93",
        "NINGUNO" to "Sin código (solo el número)",
    )

    lateinit var base: File

    val tarjetas get() = File(base, "tarjetas")
    val adjuntos get() = File(base, "adjuntos")

    fun iniciar(c: Context) {
        if (!::base.isInitialized) base = c.applicationContext.filesDir
    }

    fun nuevoId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    /** Escribe el archivo entero o nada: primero uno temporal y luego se renombra. */
    fun escribir(f: File, texto: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "." + f.name + ".tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(f)) { f.writeText(texto); tmp.delete() }
    }

    // ---------------------------------------------------------------- ajustes (ajustes.json)

    private val ajFile get() = File(base, "ajustes.json")

    /** Ajustes con sus valores por defecto: hora de los avisos, aviso previo de las entradas y de la caducidad. */
    @Synchronized
    fun ajustes(): JSONObject {
        val j = try { JSONObject(ajFile.readText()) } catch (_: Exception) { JSONObject() }
        val r = JSONObject()
        r.put("hora", j.optString("hora", "09:00").takeIf { Regex("\\d{2}:\\d{2}").matches(it) } ?: "09:00")
        r.put("previo", j.optInt("previo", 1).coerceIn(0, 30))
        r.put("caduca", j.optJSONArray("caduca") ?: JSONArray().put(30).put(0))
        return r
    }

    @Synchronized
    fun ajustesPoner(nuevos: JSONObject) {
        val j = ajustes()
        if (nuevos.has("hora")) {
            val h = nuevos.optString("hora")
            if (Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(h)) j.put("hora", h)
        }
        if (nuevos.has("previo")) j.put("previo", nuevos.optInt("previo").coerceIn(0, 30))
        if (nuevos.has("caduca")) j.put("caduca", Tarjetas.diasAviso(nuevos.optJSONArray("caduca")))
        escribir(ajFile, j.toString(1))
    }

    fun horaAvisos(): java.time.LocalTime =
        try { java.time.LocalTime.parse(ajustes().optString("hora")) } catch (_: Exception) { java.time.LocalTime.of(9, 0) }
}
