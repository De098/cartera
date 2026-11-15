package io.github.de098.cartera

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Copia de seguridad en un solo .zip: copia.json (qué app y qué versión del formato), tarjetas/«id».json, los
 * adjuntos en adjuntos/«id»/«archivo» y ajustes.json. Importar AÑADE: no borra nada; lo que tenga el mismo id se
 * sustituye. Si le llega un archivo de otra app (Catima, pases de Wallet, CSV…) lo detecta y lo importa igual: ver [Importar].
 */
object Copia {
    private const val APP = "io.github.de098.cartera"
    private val TARJETA = Regex("tarjetas/([0-9a-f]{6,32})\\.json")
    private val ADJUNTO = Regex("adjuntos/([0-9a-f]{6,32})/([a-z0-9]{4,32}\\.(?:png|jpg|webp|gif|pdf))")

    fun nombreArchivo(): String = "cartera-copia-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}.zip"

    private fun texto(z: ZipOutputStream, nombre: String, t: String) {
        z.putNextEntry(ZipEntry(nombre)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry()
    }

    /** Escribe la copia en la salida que dé el sistema (el selector de «Guardar como»). */
    @Synchronized
    fun exportar(salida: OutputStream) {
        ZipOutputStream(salida).use { z ->
            texto(z, "copia.json", JSONObject().put("app", APP).put("formato", 1)
                .put("fecha", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))).toString(1))
            texto(z, "ajustes.json", Nucleo.ajustes().toString(1))
            Nucleo.tarjetas.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".")) texto(z, "tarjetas/${f.name}", f.readText())
            }
            Nucleo.adjuntos.listFiles()?.sortedBy { it.name }?.forEach { d ->
                if (!d.isDirectory || !Nucleo.ID.matches(d.name)) return@forEach
                d.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    if (f.isFile && Nucleo.ADJUNTO.matches(f.name)) {
                        z.putNextEntry(ZipEntry("adjuntos/${d.name}/${f.name}"))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
        }
    }

    /** Importa lo que sea (copia de Cartera o de otra app): el formato se detecta por el contenido, ver [Importar]. */
    fun importar(archivo: File): Resultado = Importar.archivo(archivo)

    /** Una copia de Cartera (ya abierta): añade sus tarjetas y adjuntos. */
    @Synchronized
    fun importarCartera(z: ZipFile): Resultado {
        val cab = z.getEntry("copia.json")?.let {
            try { JSONObject(z.getInputStream(it).bufferedReader(Charsets.UTF_8).readText()) } catch (_: Exception) { null }
        }
        if (cab == null || cab.optString("app") != APP) return Resultado(Importar.noReconozco())
        if (cab.optInt("formato") > 1) return Resultado(Nucleo.s(R.string.err_copia_nueva))

        var tarjetas = 0
        var conFechas = false
        // primero los adjuntos (las tarjetas los comprueban al guardarse)
        for (e in z.entries()) {
            val m = ADJUNTO.matchEntire(e.name) ?: continue
            val destino = File(File(Nucleo.adjuntos, m.groupValues[1]), m.groupValues[2])
            destino.parentFile?.mkdirs()
            z.getInputStream(e).use { i -> destino.outputStream().use { o -> i.copyTo(o) } }
        }
        for (e in z.entries()) {
            val m = TARJETA.matchEntire(e.name) ?: continue
            val j = try { JSONObject(z.getInputStream(e).bufferedReader(Charsets.UTF_8).readText()) } catch (_: Exception) { continue }
            if (j.optString("id") != m.groupValues[1] || j.optString("nombre").isEmpty()) continue
            // se pasa por guardar() para comprobar todo; conserva «archivada», «uso» y demás, que guardar() no toma de fuera
            try {
                val id = Tarjetas.guardar(j)
                Tarjetas.completarImportada(id, j)
                tarjetas++
                if (j.optString("caduca").isNotEmpty() || j.optString("evento").isNotEmpty()) conFechas = true
            } catch (_: Exception) { /* una tarjeta mala no estropea el resto */ }
        }
        return Resultado(if (tarjetas == 0) Nucleo.s(R.string.copia_vacia) else Nucleo.n(R.plurals.copia_recuperadas, tarjetas), conFechas = conFechas)
    }
}
