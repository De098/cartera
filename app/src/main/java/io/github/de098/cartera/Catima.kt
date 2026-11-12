package io.github.de098.cartera

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Copia de Catima (su .zip de exportación): trae catima.csv, con una fila por tarjeta, y a veces las imágenes de cada
 * una. El CSV lo lee [TablaCsv] (nombre = store, número = cardid, contenido del código si es otro = barcodeid, tipo =
 * barcodetype, nota, color = headercolor, caducidad = expiry, favorita = starstatus, archivada = archive, último uso =
 * lastused) y aquí se añaden las imágenes de frente y dorso como adjuntos con rol «frente» y «dorso».
 * Un .csv suelto de Catima (formato 1, sin versión, o 2) o de Loyalty Card Keychain lo trata [Importar] sin pasar por aquí.
 * Las tarjetas que ya están (mismo nombre y número) no se repiten.
 */
object Catima {
    private val IMAGEN = Regex("(?i)(?:.*/)?(?:card_)?(\\d+)_(front|back)\\.(png|jpe?g|webp)")

    fun importar(z: ZipFile): Resultado {
        val csv = z.entries().asSequence().firstOrNull { it.name.equals("catima.csv", ignoreCase = true) }
            ?: return Resultado("Esa copia de Catima no trae catima.csv")
        val texto = Csv.decodificar(z.getInputStream(csv).readBytes())

        // imágenes por tarjeta de Catima: id → [(frente|back, entrada)]
        val imagenes = mutableMapOf<String, MutableList<Pair<String, ZipEntry>>>()
        for (e in z.entries()) {
            val m = IMAGEN.matchEntire(e.name) ?: continue
            imagenes.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2].lowercase() to e)
        }
        val lote = Lote("Catima")
        val error = TablaCsv.importar(lote, texto) { id ->
            imagenes[id].orEmpty().map { (lado, e) ->
                val ext = e.name.substringAfterLast('.').lowercase().let { if (it == "jpeg") "jpg" else it }
                Imagen(if (lado == "front") "Frente" else "Dorso", if (lado == "front") "frente" else "dorso", ext) { z.getInputStream(e) }
            }
        }
        return if (error != null) Resultado(if (error.startsWith("No he reconocido")) "No se ha encontrado ninguna tarjeta en esa copia de Catima" else error)
        else lote.resultado()
    }
}
