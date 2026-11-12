package io.github.de098.cartera

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Pases de Apple Wallet: un .pkpass es un .zip con pass.json (y imágenes); un .pkpasses son varios .pkpass juntos.
 * De cada pase se toma:
 *  - nombre: organizationName, si no logoText, si no description (y la descripción va a la nota si no es el nombre)
 *  - código: barcodes[0] o barcode (message + format: PKBarcodeFormatQR / PDF417 / Aztec / Code128); si trae altText,
 *    ese es el «número» que se enseña y el código lleva el mensaje; si no, el mensaje es el número (si es corto)
 *  - fecha: relevantDate → día del evento (solo si es eventTicket o boardingPass: entonces es una «entrada»);
 *    expirationDate → caducidad
 *  - color: backgroundColor, «rgb(r, g, b)»
 *  - imágenes: strip, logo y thumbnail (si vienen) como adjuntos
 * La firma del pase NO se comprueba (no hace falta: no se envía a ningún sitio).
 */
object Pkpass {
    private const val MAX_ENTRADA = 20L * 1024 * 1024
    private val IMAGENES = listOf("strip" to "Imagen", "logo" to "Logo", "thumbnail" to "Miniatura")

    private fun leerTope(i: InputStream): ByteArray {
        val s = ByteArrayOutputStream()
        val b = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = i.read(b)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRADA) throw IllegalStateException("grande")
            s.write(b, 0, n)
        }
        return s.toByteArray()
    }

    /** Solo se guardan los archivos útiles (pass.json, las tres imágenes y los .pkpass de un .pkpasses), en la raíz o en una carpeta. */
    private fun util(nombre: String): Boolean {
        val n = nombre.lowercase()
        if (n.count { it == '/' } > 1) return false
        val ultimo = n.substringAfterLast('/')
        return ultimo.endsWith(".pkpass") || ultimo == "pass.json" || IMAGENES.any { (b, _) -> ultimo == "$b.png" || ultimo == "$b@2x.png" || ultimo == "$b@3x.png" }
    }

    fun importarZip(z: ZipFile, abrirSiUna: Boolean): Resultado {
        val lote = Lote("Apple Wallet")
        val archivos = mutableMapOf<String, ByteArray>()
        try {
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val nombre = e.name
                if (util(nombre)) {
                    try { archivos[nombre] = z.getInputStream(e).use { leerTope(it) } } catch (_: Exception) { lote.ilegibles++ }
                }
            }
        } catch (_: Exception) { return Resultado("Ese archivo ZIP está dañado o no se puede abrir.") }
        procesar(lote, archivos, 0)
        return lote.resultado(abrirSiUna)
    }

    /** Un nivel: o es un pase (pass.json) o es un conjunto de pases (.pkpass dentro). */
    private fun procesar(lote: Lote, archivos: Map<String, ByteArray>, nivel: Int) {
        // si el pase vino dentro de una carpeta (una sola), se usan los archivos de esa carpeta
        val raiz = archivos.keys.firstOrNull { it.equals("pass.json", true) }
        if (raiz != null) { pase(lote, archivos, ""); return }
        val carpeta = archivos.keys.firstOrNull { it.endsWith("/pass.json", true) }?.substringBeforeLast('/')
        if (carpeta != null) { pase(lote, archivos, "$carpeta/"); return }
        for ((nombre, bytes) in archivos) {
            if (!nombre.endsWith(".pkpass", true) || nivel > 0) continue
            val interior = mutableMapOf<String, ByteArray>()
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zi ->
                    while (true) {
                        val e = zi.nextEntry ?: break
                        if (!e.isDirectory && util(e.name) && !e.name.endsWith(".pkpass", true)) interior[e.name] = leerTope(zi)
                    }
                }
            } catch (_: Exception) { lote.ilegibles++; continue }
            if (interior.isEmpty()) { lote.ilegibles++; continue }
            procesar(lote, interior, nivel + 1)
        }
    }

    private fun pase(lote: Lote, archivos: Map<String, ByteArray>, pre: String) {
        val j = try { JSONObject(Csv.decodificar(archivos[pre + "pass.json"] ?: archivos.entries.first { it.key.equals(pre + "pass.json", true) }.value)) }
                catch (_: Exception) { lote.ilegibles++; return }
        lote.anadir(aFicha(j) { n -> archivos[pre + n] ?: archivos.entries.firstOrNull { it.key.equals(pre + n, true) }?.value })
    }

    private fun texto(j: JSONObject, k: String) = j.optString(k, "").trim()

    /** Convierte el pass.json en una ficha. «imagen» da los bytes de un archivo del pase por su nombre (o null). */
    fun aFicha(j: JSONObject, imagen: (String) -> ByteArray?): Ficha {
        val org = texto(j, "organizationName"); val desc = texto(j, "description"); val logo = texto(j, "logoText")
        val nombre = org.ifEmpty { logo.ifEmpty { desc.ifEmpty { "Pase de Wallet" } } }

        val tipoPase = listOf("eventTicket", "boardingPass", "coupon", "storeCard", "generic").firstOrNull { j.has(it) } ?: ""
        val codigoJ = j.optJSONArray("barcodes")?.optJSONObject(0) ?: j.optJSONObject("barcode")
        val mensaje = codigoJ?.optString("message", "") ?: ""
        val alt = codigoJ?.optString("altText", "")?.trim() ?: ""
        val formato = codigoJ?.optString("format", "") ?: ""
        val tipo: String? = if (mensaje.isEmpty()) "" else Csv.tipo(formato)
        // número que se enseña: el texto alternativo, o el propio mensaje si es corto; si es largo (un QR de un billete), solo el código
        val numero = alt.ifEmpty { if (mensaje.length <= 60 && '\n' !in mensaje) mensaje else "" }
        val codigo = if (mensaje.isNotEmpty() && mensaje != numero) mensaje else ""

        var evento: Fecha? = null
        if (tipoPase == "eventTicket" || tipoPase == "boardingPass") evento = Csv.fecha(texto(j, "relevantDate"))
        val cad = Csv.fecha(texto(j, "expirationDate"))

        val imgs = mutableListOf<Imagen>()
        for ((base, rotulo) in IMAGENES) {
            val n = listOf("$base.png", "$base@2x.png", "$base@3x.png").firstOrNull { imagen(it)?.isNotEmpty() == true } ?: continue
            val b = imagen(n)!!
            imgs += Imagen(rotulo, "", "png") { ByteArrayInputStream(b) }
        }
        val nota = if (desc.isNotEmpty() && desc != nombre) desc else ""
        return Ficha(nombre = nombre, numero = numero, codigo = codigo, tipo = tipo, nota = nota, color = Csv.color(texto(j, "backgroundColor")),
            caduca = cad?.dia?.toString() ?: "", evento = evento, imagenes = imgs, sinNumeroVale = true)
    }
}
