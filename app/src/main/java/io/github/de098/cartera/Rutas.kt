package io.github.de098.cartera

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream

/**
 * Lo que la web de la app pide con fetch("/cartera/…"): aquí lo contesta la propia app, sin servidor ni internet.
 * local.js lo llama a través del puente «Cartera». Las imágenes (el código dibujado, los adjuntos) no pasan por
 * aquí: las pide la página con una etiqueta img y las contesta [recurso] a través de MainActivity.
 */
object Rutas {
    const val ORIGEN = "https://appassets.androidplatform.net"
    private const val JSON = "application/json; charset=utf-8"
    private const val TEXTO = "text/plain; charset=utf-8"

    /** Contesta una petición y devuelve {estado, tipo, cuerpo} como texto JSON. */
    @Synchronized
    fun pedir(c: Context, metodo: String, ruta: String, cuerpo: String): String {
        Nucleo.iniciar(c)
        val u = Uri.parse(ruta)
        val path = u.path ?: ""
        var estado = 200
        var tipo = JSON
        var texto: String
        var cambia = false
        try {
            when {
                metodo == "GET" && path == "/cartera/datos" -> texto = Tarjetas.datosJson()
                metodo == "GET" && path == "/cartera/ajustes" -> texto = Nucleo.ajustes().toString()
                metodo == "POST" && path == "/cartera/ajustes-guardar" -> {
                    Nucleo.ajustesPoner(JSONObject(cuerpo)); texto = "ok"; tipo = TEXTO; cambia = true
                }
                metodo == "POST" && path == "/cartera/valido" -> {
                    val j = JSONObject(cuerpo)
                    texto = Codigos.validar(j.optString("tipo"), j.optString("contenido")) ?: "ok"; tipo = TEXTO
                }
                metodo == "POST" && path == "/cartera/guardar" -> { texto = Tarjetas.guardar(JSONObject(cuerpo)); tipo = TEXTO; cambia = true }
                metodo == "POST" && path == "/cartera/borrar" -> { Tarjetas.borrar(cuerpo.trim()); texto = "ok"; tipo = TEXTO; cambia = true }
                metodo == "POST" && path == "/cartera/usar" -> { Tarjetas.marcarUso(cuerpo.trim()); texto = "ok"; tipo = TEXTO; cambia = true }
                metodo == "POST" && path == "/cartera/favorita" -> {
                    val j = JSONObject(cuerpo)
                    Tarjetas.poner(j.optString("id"), "favorita", j.optBoolean("valor")); texto = "ok"; tipo = TEXTO; cambia = true
                }
                metodo == "POST" && path == "/cartera/archivar" -> { Tarjetas.archivar(cuerpo.trim()); texto = "ok"; tipo = TEXTO; cambia = true }
                metodo == "POST" && path == "/cartera/restaurar" -> { Tarjetas.restaurar(cuerpo.trim()); texto = "ok"; tipo = TEXTO; cambia = true }
                else -> { estado = 404; tipo = TEXTO; texto = "no" }
            }
        } catch (e: ErrorUsuario) {
            estado = 400; tipo = TEXTO; texto = e.message ?: "No se ha podido"
        } catch (e: Exception) {
            estado = 500; tipo = TEXTO; texto = "Error (${e.javaClass.simpleName})"
        }
        if (cambia) Programador.pronto(c)   // avisos y widget al día por detrás: la página no espera
        return JSONObject().put("estado", estado).put("tipo", tipo).put("cuerpo", texto).toString()
    }

    /**
     * Imágenes que pide la página con img:
     *   /cartera/img/codigo.svg?t=TIPO&n=CONTENIDO        el código dibujado
     *   /cartera/img/adjunto?id=ID&f=ARCHIVO&p=PÁGINA     un adjunto (una imagen tal cual; un PDF, página a página)
     * Devuelve el tipo y los datos, o null si no existe.
     */
    fun recurso(c: Context, path: String, query: Map<String, String>): Pair<String, InputStream>? {
        Nucleo.iniciar(c)
        return try {
            when (path) {
                "/cartera/img/codigo.svg" -> {
                    val svg = Codigos.svg(query["t"] ?: "", query["n"] ?: "")
                    "image/svg+xml" to ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8))
                }
                "/cartera/img/adjunto" -> {
                    val f = Adjuntos.archivo(query["id"] ?: "", query["f"] ?: "") ?: return null
                    if (f.name.endsWith(".pdf")) "image/png" to ByteArrayInputStream(Adjuntos.paginaPng(f, (query["p"] ?: "0").toIntOrNull() ?: 0))
                    else Adjuntos.mime(f.name) to FileInputStream(f)
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
