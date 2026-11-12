package io.github.de098.cartera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * Códigos de barras y QR con ZXing (Java puro, sin red): dibujarlos (como SVG, que la página enseña con una etiqueta
 * img) y leerlos de una foto. La cámara en directo está en [EscanerActivity].
 */
object Codigos {
    /** Los formatos que se pueden dibujar (todos menos «NINGUNO»), por su clave. */
    private val FORMATOS: Map<String, BarcodeFormat> = Nucleo.FORMATOS
        .filter { it.first != "NINGUNO" }
        .associate { it.first to BarcodeFormat.valueOf(it.first) }

    /** Códigos de una sola fila de barras: se dibujan estirando la fila hacia abajo. */
    private val UNIDIMENSIONALES = setOf(
        BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF, BarcodeFormat.CODABAR)

    /** Los que sabe leer el escáner (los mismos que sabe dibujar). */
    val LECTURA: List<BarcodeFormat> = FORMATOS.values.toList()

    /** Clave del formato leído, o «NINGUNO» si es uno que no se puede dibujar de vuelta. */
    fun claveDe(f: BarcodeFormat?): String = if (f != null && FORMATOS.containsKey(f.name)) f.name else "NINGUNO"

    private fun ayuda(clave: String): String = when (clave) {
        "EAN_13" -> "El EAN-13 lleva 13 cifras (o 12 y se calcula la última)."
        "EAN_8" -> "El EAN-8 lleva 8 cifras (o 7 y se calcula la última)."
        "UPC_A" -> "El UPC-A lleva 12 cifras (o 11 y se calcula la última)."
        "UPC_E" -> "El UPC-E lleva 8 cifras (empieza por 0 o 1) o 7."
        "ITF" -> "El ITF lleva solo cifras y en cantidad par."
        "CODE_39" -> "El Code 39 admite letras MAYÚSCULAS, cifras y - . \$ / + % y espacio."
        "CODABAR" -> "El Codabar lleva cifras y una letra A-D al principio y al final."
        "CODE_128" -> "El Code 128 admite letras, cifras y signos sin tildes."
        "DATA_MATRIX", "AZTEC", "PDF_417", "QR_CODE" -> "Ese texto no se puede escribir en ese tipo de código."
        else -> "Ese número no vale para ese tipo de código."
    }

    /** null si el número se puede dibujar con ese tipo; si no, por qué no (en castellano). */
    fun validar(clave: String, contenido: String): String? {
        if (contenido.isEmpty()) return "Falta el número"
        return try { matriz(clave, contenido); null } catch (_: Exception) { "Ese número no vale para ${nombre(clave)}. ${ayuda(clave)}" }
    }

    private fun nombre(clave: String) = Nucleo.FORMATOS.firstOrNull { it.first == clave }?.second ?: clave

    private fun matriz(clave: String, contenido: String): BitMatrix {
        val f = FORMATOS[clave] ?: throw ErrorUsuario("Tipo de código desconocido")
        val pistas = mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
        return MultiFormatWriter().encode(contenido, f, 0, 0, pistas)
    }

    /** El código dibujado como SVG (negro sobre blanco, con su margen en blanco). Lanza si no se puede. */
    fun svg(clave: String, contenido: String): String {
        val f = FORMATOS[clave] ?: throw ErrorUsuario("Tipo de código desconocido")
        val m = try { matriz(clave, contenido) } catch (e: Exception) { throw ErrorUsuario("No se puede dibujar ese código") }
        val w = m.width
        val unidim = f in UNIDIMENSIONALES
        val alto = if (unidim) maxOf(30, (w * 0.4).toInt()) else m.height
        val mx = if (unidim) 10 else 4
        val my = if (unidim) 4 else 4
        val d = StringBuilder()
        for (y in 0 until m.height) {
            var x = 0
            while (x < w) {
                if (!m[x, y]) { x++; continue }
                var fin = x
                while (fin < w && m[fin, y]) fin++
                val largo = fin - x
                if (unidim) d.append("M$x,0h${largo}v${alto}h-${largo}z") else d.append("M$x,${y}h${largo}v1h-${largo}z")
                x = fin
            }
            if (unidim) break   // una fila basta: se estira
        }
        val ancho = w + 2 * mx
        val altoTotal = alto + 2 * my
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"${-mx} ${-my} $ancho $altoTotal\" " +
            "shape-rendering=\"crispEdges\"><rect x=\"${-mx}\" y=\"${-my}\" width=\"$ancho\" height=\"$altoTotal\" fill=\"#fff\"/>" +
            "<path fill=\"#000\" d=\"$d\"/></svg>"
    }

    /** Un código leído: el texto y la clave de su tipo. */
    class Leido(val texto: String, val tipo: String)

    private fun lector() = MultiFormatReader().apply {
        setHints(mapOf<DecodeHintType, Any>(DecodeHintType.POSSIBLE_FORMATS to LECTURA, DecodeHintType.TRY_HARDER to true))
    }

    /** Intenta leer un código de esa imagen en claro y en negativo, con dos binarizadores. null si no hay. */
    fun leer(fuente: LuminanceSource): Leido? {
        val intentos = listOf<() -> BinaryBitmap>(
            { BinaryBitmap(HybridBinarizer(fuente)) },
            { BinaryBitmap(GlobalHistogramBinarizer(fuente)) },
            { BinaryBitmap(HybridBinarizer(fuente.invert())) },
        )
        for (i in intentos) {
            val r = try { lector().decodeWithState(i()) } catch (_: Exception) { null }
            if (r != null) return Leido(r.text, claveDe(r.barcodeFormat))
        }
        return null
    }

    /** Lee el código de una foto o captura de la galería (el sistema da el archivo; no hace falta permiso de almacenamiento). */
    fun leerImagen(cr: ContentResolver, uri: Uri): Leido? {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
        if (medidas.outWidth <= 0) return null
        var reduccion = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / reduccion > 2400) reduccion *= 2
        val opciones = BitmapFactory.Options().apply { inSampleSize = reduccion }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opciones) } ?: return null
        try {
            leerBitmap(bmp)?.let { return it }
            // a veces una foto muy grande se lee mejor más pequeña
            val lado = maxOf(bmp.width, bmp.height)
            for (objetivo in listOf(1400, 800)) {
                if (lado <= objetivo) continue
                val k = objetivo.toFloat() / lado
                val chico = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                val r = leerBitmap(chico)
                if (chico !== bmp) chico.recycle()
                if (r != null) return r
            }
            return null
        } finally { bmp.recycle() }
    }

    private fun leerBitmap(b: Bitmap): Leido? {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return leer(RGBLuminanceSource(b.width, b.height, px))
    }
}
