package io.github.de098.cartera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Imágenes y PDF adjuntos a una tarjeta (la entrada del concierto, el billete del tren…). Se copian a la carpeta
 * privada de la app: adjuntos/«id de la tarjeta»/«nombre». Un PDF se enseña página a página, convertido a imagen
 * con el PdfRenderer del sistema.
 */
object Adjuntos {
    private const val MAX_BYTES = 40L * 1024 * 1024   // 40 MB por adjunto
    private const val ANCHO_PDF = 1400                 // píxeles de ancho al dibujar una página de PDF

    fun archivo(id: String, f: String): File? =
        if (Nucleo.ID.matches(id) && Nucleo.ADJUNTO.matches(f)) File(File(Nucleo.adjuntos, id), f).takeIf { it.isFile } else null

    fun mime(f: String): String = when (f.substringAfterLast('.')) {
        "png" -> "image/png"; "jpg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "pdf" -> "application/pdf"; else -> "application/octet-stream"
    }

    private fun copiar(entrada: InputStream, destino: File) {
        destino.parentFile?.mkdirs()
        var total = 0L
        try {
            destino.outputStream().use { s ->
                val trozo = ByteArray(64 * 1024)
                while (true) {
                    val n = entrada.read(trozo)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw ErrorUsuario(Nucleo.s(R.string.err_adj_grande))
                    s.write(trozo, 0, n)
                }
            }
        } catch (e: Exception) { destino.delete(); throw e }
    }

    /** Copia el archivo elegido con el selector del sistema a los adjuntos de la tarjeta y devuelve su ficha. */
    fun anadir(c: Context, id: String, uri: Uri): JSONObject {
        if (!Nucleo.ID.matches(id)) throw ErrorUsuario(Nucleo.s(R.string.err_tarjeta_invalida))
        val cr = c.contentResolver
        val mime = cr.getType(uri) ?: ""
        var nombre = ""
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) nombre = it.getString(0) ?: ""
            }
        } catch (_: Exception) {}
        val dir = File(Nucleo.adjuntos, id)
        val clave = Nucleo.nuevoId().take(10)
        val ficha = JSONObject()
        val esPdf = mime == "application/pdf" || (mime.isEmpty() && nombre.lowercase().endsWith(".pdf"))
        if (esPdf) {
            val f = File(dir, "$clave.pdf")
            (cr.openInputStream(uri) ?: throw ErrorUsuario(Nucleo.s(R.string.err_no_se_lee))).use { copiar(it, f) }
            val paginas = try { paginas(f) } catch (_: Exception) { f.delete(); throw ErrorUsuario(Nucleo.s(R.string.err_pdf)) }
            ficha.put("f", f.name).put("tipo", "pdf").put("paginas", paginas)
        } else {
            val ext = when (mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "" }
            if (ext.isNotEmpty()) {
                val f = File(dir, "$clave.$ext")
                (cr.openInputStream(uri) ?: throw ErrorUsuario(Nucleo.s(R.string.err_no_se_lee))).use { copiar(it, f) }
                ficha.put("f", f.name)
            } else {
                // otro formato de imagen (p. ej. HEIC): se convierte a JPEG para que la página lo pueda enseñar
                val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
                var red = 1
                while (maxOf(medidas.outWidth, medidas.outHeight) / red > 3000) red *= 2
                val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = red }) }
                    ?: throw ErrorUsuario(Nucleo.s(R.string.err_no_imagen))
                val f = File(dir, "$clave.jpg")
                dir.mkdirs()
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                bmp.recycle()
                ficha.put("f", f.name)
            }
            ficha.put("tipo", "imagen").put("paginas", 1)
        }
        ficha.put("nombre", nombre.take(100)).put("rol", "")
        return ficha
    }

    /** Cuántas páginas tiene un PDF (lanza si no se puede abrir). */
    fun paginas(f: File): Int = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { it.pageCount }
    }

    /** Una página de un PDF dibujada como PNG sobre fondo blanco. */
    @Synchronized
    fun paginaPng(f: File, pagina: Int): ByteArray =
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                val p = r.openPage(pagina.coerceIn(0, r.pageCount - 1))
                try {
                    val alto = (ANCHO_PDF.toLong() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(ANCHO_PDF, alto, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)   // sin esto las partes sin pintar salen transparentes
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val s = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, s)
                    bmp.recycle()
                    s.toByteArray()
                } finally { p.close() }
            }
        }
}
