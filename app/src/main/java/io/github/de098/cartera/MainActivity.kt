package io.github.de098.cartera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * Cartera. La interfaz es una página web empaquetada en la app (assets/web/cartera.html) en un WebView sin acceso a la
 * red. Todo se queda en el móvil: la app no tiene permiso de internet y las peticiones de la página (/cartera/…) las
 * contesta [Rutas] a través del puente «Cartera». Lo que necesita al sistema (cámara, galería, archivos, brillo,
 * accesos directos) lo pide la página a este puente, y las respuestas vuelven con window.alNativo({tipo: …}).
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_ID = "tarjeta"   // id de la tarjeta que hay que abrir (avisos, widget, accesos directos)
        private val HOST = Uri.parse(Rutas.ORIGEN).host
        private const val PEDIR_EXPORTAR = 41
        private const val PEDIR_IMPORTAR = 42
        private const val PEDIR_ESCANEAR = 43
        private const val PEDIR_GALERIA = 44
        private const val PEDIR_ADJUNTO = 45
        private const val PEDIR_OTRAS = 46
        private const val PERMISO_AVISOS = 1
        private const val PREFS = "cartera"
        private const val PREF_PREGUNTADO = "avisosPreguntado"   // ya se pidió el permiso de avisos una vez: no se repite
        private const val MAX_COPIA = 500L * 1024 * 1024   // 500 MB
    }

    private lateinit var web: WebView
    private lateinit var raiz: LinearLayout
    private var lista = false                           // la página terminó de cargar
    private val pendientes = mutableListOf<String>()    // JS que espera a que la página esté lista
    private var codigoAbierto = false                   // la página enseña un código: brillo al máximo y pantalla encendida
    private var primerPlano = false
    private var tarjetaAdjunto = ""                     // a qué tarjeta va el adjunto que se está eligiendo

    /** Lo que la página puede pedirle a la app. */
    private inner class Puente {
        /** Factor de letra de Android; la página lo aplica como zoom CSS (como Recordatorios). */
        @JavascriptInterface
        fun escala(): Float = resources.configuration.fontScale

        /** Contesta /cartera/…: devuelve {estado, tipo, cuerpo}. */
        @JavascriptInterface
        fun pedir(metodo: String, ruta: String, cuerpo: String): String =
            Rutas.pedir(this@MainActivity, metodo, ruta, cuerpo)

        /** «Copiar» del contenido del código: al portapapeles. */
        @JavascriptInterface
        fun copiar(texto: String) = runOnUiThread {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.clip_etiqueta), texto))
        }

        /** «Escanear»: la cámara (EscanerActivity pide el permiso en ese momento). */
        @JavascriptInterface
        fun escanear() = runOnUiThread {
            try { startActivityForResult(Intent(this@MainActivity, EscanerActivity::class.java), PEDIR_ESCANEAR) }
            catch (_: Exception) { aviso(getString(R.string.err_camara)) }
        }

        /** «Desde una foto»: el selector del sistema (sin permiso de almacenamiento). */
        @JavascriptInterface
        fun galeria() = runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            try { startActivityForResult(i, PEDIR_GALERIA) } catch (_: Exception) { aviso(getString(R.string.err_fotos)) }
        }

        /** «Adjuntar imagen o PDF» a la tarjeta con ese id. */
        @JavascriptInterface
        fun adjuntar(id: String) = runOnUiThread {
            tarjetaAdjunto = id
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            try { startActivityForResult(i, PEDIR_ADJUNTO) } catch (_: Exception) { aviso(getString(R.string.err_archivos)) }
        }

        /** Con un código a la vista: brillo al máximo y pantalla sin apagarse; al cerrarlo, todo como estaba. */
        @JavascriptInterface
        fun pantallaCodigo(si: Boolean) = runOnUiThread { codigoAbierto = si; aplicarPantalla() }

        /** «Añadir a la pantalla de inicio»: acceso directo a esa tarjeta. */
        @JavascriptInterface
        fun anadirAcceso(id: String) = runOnUiThread { pedirAcceso(id) }

        /** Ajustes → guardar una copia (el sistema pregunta dónde). */
        @JavascriptInterface
        fun exportar() = runOnUiThread {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, Copia.nombreArchivo())
            try { startActivityForResult(i, PEDIR_EXPORTAR) } catch (_: Exception) { aviso(getString(R.string.err_guardar_aqui)) }
        }

        /** Ajustes → importar de otras apps (Catima, Loyalty Card Keychain, Wallet, CSV): cualquier archivo; el formato se detecta por el contenido. */
        @JavascriptInterface
        fun importarOtras() = runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try { startActivityForResult(i, PEDIR_OTRAS) } catch (_: Exception) { aviso(getString(R.string.err_archivos)) }
        }

        /** ¿Pueden salir avisos? (permiso concedido y notificaciones de la app no desactivadas). */
        @JavascriptInterface
        fun avisosActivos(): Boolean = hayAvisos()

        /** Se ha guardado una tarjeta con fecha: si hace falta, se pide el permiso de avisos (solo la primera vez). */
        @JavascriptInterface
        fun pedirAvisos() = runOnUiThread { pedirPermisoAvisos() }

        /** Abre los ajustes de notificaciones de Cartera en Android. */
        @JavascriptInterface
        fun ajustesAvisos() = runOnUiThread {
            val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            try { startActivity(i) } catch (_: Exception) { aviso(getString(R.string.err_ajustes_avisos)) }
        }

        /** Ajustes → recuperar una copia de Cartera (si el archivo es de otra app, también se detecta e importa). */
        @JavascriptInterface
        fun importar() = runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            try { startActivityForResult(i, PEDIR_IMPORTAR) } catch (_: Exception) { aviso(getString(R.string.err_archivos)) }
        }

        /** Versión de la app, la que pone el build (versionName). */
        @JavascriptInterface
        fun version(): String =
            try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        Nucleo.iniciar(this)

        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.fondo))
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false)
            // la letra nunca crece sola (solo puede encoger); el aumento lo hace la página con zoom CSS (ver escala())
            settings.textZoom = minOf(100, Math.round(resources.configuration.fontScale * 100))
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            setBackgroundColor(getColor(R.color.fondo))
            webViewClient = Cliente()
            addJavascriptInterface(Puente(), "Cartera")
        }
        raiz.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // pantalla completa (de borde a borde) en todas las versiones (Android 15+ ya lo hace solo):
        // las barras transparentes con el color de la app y se deja hueco para la barra de estado, la de gestos y el teclado
        bordeABorde()
        raiz.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val s = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(s.left, s.top, s.right, s.bottom)
                WindowInsets.CONSUMED
            } else huecoAntiguo(v, ins)
        }
        setContentView(raiz)

        // gesto atrás (Android 13+; en los anteriores entra por onBackPressed)
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { atras() }

        web.loadUrl(Rutas.ORIGEN + "/cartera")
        abrirDesde(intent)
        Avisos.crearCanales(this)   // el permiso de avisos NO se pide aquí: ver pedirPermisoAvisos()
        Programador.programarTrabajo(this)
    }

    /** Dibujar detrás de las barras de estado y de gestos, transparentes; siempre con iconos oscuros (tema claro). */
    @Suppress("DEPRECATION")
    private fun bordeABorde() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val claras = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(claras, claras)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
    }

    /** Android 10: hueco para las barras y el teclado (este ya va incluido en estas medidas). */
    @Suppress("DEPRECATION")
    private fun huecoAntiguo(v: View, ins: WindowInsets): WindowInsets {
        v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
        return ins.consumeSystemWindowInsets()
    }

    /** Atrás: primero lo de la página (cerrar la tarjeta, el formulario…); si ya no hay nada, la app pasa a segundo plano. */
    private fun atras() {
        if (!lista) { moveTaskToBack(true); return }
        web.evaluateJavascript("window.atras ? atras() : false") { r -> if (r != "true") moveTaskToBack(true) }
    }

    @Deprecated("Android 10-12: el gesto atrás llega por aquí")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = atras()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        abrirDesde(intent)
    }

    override fun onResume() {
        super.onResume()
        primerPlano = true
        aplicarPantalla()
        thread(name = "programador") { Programador.ahora(this) }   // al abrir la app: avisos y widget al día
        enviar(JSONObject().put("tipo", "recargar"))               // por si algo se archivó mientras tanto
        enviar(JSONObject().put("tipo", "avisos").put("activos", hayAvisos()))   // por si se cambió en los ajustes de Android
    }

    override fun onPause() {
        primerPlano = false
        aplicarPantalla()   // brillo y pantalla encendida solo mientras se ve la app
        super.onPause()
    }

    /** Brillo al máximo y pantalla sin apagarse solo mientras la página enseña un código y la app se ve. */
    private fun aplicarPantalla() {
        val activo = codigoAbierto && primerPlano
        val a = window.attributes
        a.screenBrightness = if (activo) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = a
        if (activo) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Aviso, widget o acceso directo pulsado: abrir esa tarjeta. */
    private fun abrirDesde(i: Intent?) {
        i ?: return
        val id = i.getStringExtra(EXTRA_ID)?.takeIf { Nucleo.ID.matches(it) }
        i.removeExtra(EXTRA_ID)
        if (id != null) enviar(JSONObject().put("tipo", "abrir").put("id", id))

        // un pase de Wallet abierto desde otra app (correo, navegador, archivos…): se importa y se enseña
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            i.action = Intent.ACTION_MAIN; i.data = null; i.removeExtra(Intent.EXTRA_STREAM)   // para no importarlo dos veces
            thread(name = "archivos") { importarDe(uri, true) }
        }
    }

    /** Copia el archivo a la caché (con tope de tamaño), lo importa y se lo cuenta a la página. «fuera»: vino de otra app. */
    private fun importarDe(uri: Uri, fuera: Boolean) {
        val tmp = File(cacheDir, "importar.tmp")
        try {
            contentResolver.openInputStream(uri)!!.use { entrada ->
                tmp.outputStream().use { salida ->
                    // a trozos, con tope por si eligen un archivo enorme
                    val trozo = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = entrada.read(trozo)
                        if (n < 0) break
                        total += n
                        if (total > MAX_COPIA) throw ErrorUsuario(getString(R.string.err_archivo_grande))
                        salida.write(trozo, 0, n)
                    }
                }
            }
            val r = Importar.archivo(tmp, abrirSiUna = fuera)
            Programador.ahora(this)
            val tipo = if (r.resumen && r.abrir == null) "resumen" else "aviso"
            enviar(JSONObject().put("tipo", tipo).put("msj", if (tipo == "aviso") r.mensaje.lineSequence().first() else r.mensaje))
            enviar(JSONObject().put("tipo", "recargar"))
            if (r.abrir != null) enviar(JSONObject().put("tipo", "abrir").put("id", r.abrir))
            if (r.conFechas) runOnUiThread { pedirPermisoAvisos() }
        } catch (e: ErrorUsuario) {
            enviar(JSONObject().put("tipo", "aviso").put("msj", e.message ?: getString(R.string.err_generico)))
        } catch (_: Exception) {
            enviar(JSONObject().put("tipo", "aviso").put("msj", getString(R.string.err_leer_archivo)))
        } finally { tmp.delete() }
    }

    /** Manda un mensaje a la página (window.alNativo); si aún no ha cargado, espera. */
    /** Un código leído para la página: el texto, el tipo, los octetos en bruto (base64, si es binario) y lo que reconoce [Billetes]. */
    private fun mensajeCodigo(texto: String, formato: String, bytes: ByteArray?): JSONObject {
        val o = JSONObject().put("tipo", "codigo").put("numero", texto).put("formato", formato)
        if (bytes != null) o.put("bruto", java.util.Base64.getEncoder().encodeToString(bytes))
        try { Tarjetas.reconocerCodigo(texto, bytes)?.let { o.put("reconocido", it) } } catch (_: Exception) {}
        return o
    }

    private fun enviar(o: JSONObject) {
        val js = "window.alNativo && alNativo($o)"
        runOnUiThread { if (lista) web.evaluateJavascript(js, null) else pendientes += js }
    }

    /** Sirve la página y sus archivos desde assets/web: /cartera → cartera.html, /cartera/x.js → x.js… */
    private fun servir(path: String): WebResourceResponse {
        val p = path.trimEnd('/')
        val nombre = when {
            p == "/cartera" -> "cartera.html"
            p.startsWith("/cartera/") -> p.substringAfterLast('/')
            else -> ""
        }
        if (nombre.isNotEmpty() && ".." !in nombre) try {
            val mime = when (nombre.substringAfterLast('.', "")) {
                "html" -> "text/html"; "js" -> "text/javascript"; "svg" -> "image/svg+xml"
                "png" -> "image/png"; "woff2" -> "font/woff2"; "txt" -> "text/plain"; else -> "application/octet-stream"
            }
            val texto = mime.startsWith("text/") || mime == "image/svg+xml"
            return WebResourceResponse(mime, if (texto) "utf-8" else null, assets.open("web/$nombre"))
        } catch (_: Exception) {}
        return noEncontrado()
    }

    private fun noEncontrado() =
        WebResourceResponse("text/plain", "utf-8", 404, "No encontrado", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private inner class Cliente : WebViewClient() {
        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
            if (r.url.host != HOST) return WebResourceResponse("text/plain", "utf-8", 404, "Sin red", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            val path = r.url.path ?: ""
            if (path.startsWith("/cartera/img/")) {
                // el código dibujado y los adjuntos (los pide la página con img)
                val q = r.url.queryParameterNames.associateWith { r.url.getQueryParameter(it) ?: "" }
                val rec = Rutas.recurso(this@MainActivity, path, q) ?: return noEncontrado()
                return WebResourceResponse(rec.first, null, 200, "OK", mapOf("Cache-Control" to "no-store"), rec.second)
            }
            return servir(path)
        }

        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            // dentro de la app no se navega a ningún sitio; enlaces de fuera (si los hubiera): al navegador
            if (r.url.host == HOST) return false
            try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}
            return true
        }

        override fun onPageFinished(v: WebView, url: String) {
            lista = true
            for (js in pendientes) v.evaluateJavascript(js, null)
            pendientes.clear()
        }
    }

    private fun aviso(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- acceso directo en la pantalla de inicio

    private fun pedirAcceso(id: String) {
        val j = Tarjetas.leer(id) ?: return
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) { aviso(getString(R.string.err_acceso_no)); return }
        val nombre = j.optString("nombre")
        val abrir = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_ID, id)
        val info = ShortcutInfo.Builder(this, "tarjeta-$id")
            .setShortLabel(nombre.take(24)).setLongLabel(nombre)
            .setIcon(Icon.createWithAdaptiveBitmap(iconoAcceso(nombre, j.optString("color"))))
            .setIntent(abrir).build()
        try { sm.requestPinShortcut(info, null) } catch (_: Exception) { aviso(getString(R.string.err_acceso)) }
    }

    /** Icono del acceso directo: una tarjeta blanca con la inicial, sobre el color de la tarjeta. */
    private fun iconoAcceso(nombre: String, color: String): Bitmap {
        val t = 432   // 108 dp a 4x: el icono adaptativo recorta los bordes, lo importante va en el centro
        val fondo = try { Color.parseColor(color) } catch (_: Exception) { 0xFF3584E4.toInt() }
        val b = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(fondo)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        val r = RectF(t * 0.24f, t * 0.32f, t * 0.76f, t * 0.68f)
        c.drawRoundRect(r, 24f, 24f, p)
        p.color = Color.argb(255, 36, 31, 49)
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = t * 0.22f
        p.textAlign = Paint.Align.CENTER
        val inicial = nombre.trim().take(1).uppercase().ifEmpty { "?" }
        c.drawText(inicial, t / 2f, r.centerY() + p.textSize * 0.35f, p)
        return b
    }

    // ---------------------------------------------------------------- lo que devuelve el sistema

    @Deprecated("Activity sin AndroidX: es la forma de recibir el resultado")
    override fun onActivityResult(pedido: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(pedido, resultado, datos)
        if (pedido == PEDIR_ESCANEAR) {
            if (resultado == RESULT_OK && datos != null) {
                enviar(mensajeCodigo(datos.getStringExtra("numero") ?: "", datos.getStringExtra("formato") ?: "NINGUNO",
                    datos.getByteArrayExtra("bruto")))
            } else datos?.getStringExtra("error")?.let { aviso(it) }
            return
        }
        val uri = datos?.data
        if (resultado != RESULT_OK || uri == null) return
        thread(name = "archivos") {
            try {
                when (pedido) {
                    PEDIR_GALERIA -> {
                        val r = Codigos.leerImagen(contentResolver, uri)
                        if (r == null) enviar(JSONObject().put("tipo", "aviso").put("msj", getString(R.string.msj_sin_codigo)))
                        else enviar(mensajeCodigo(r.texto, r.tipo, r.bytes))
                    }
                    PEDIR_ADJUNTO -> {
                        val ficha = Adjuntos.anadir(this, tarjetaAdjunto, uri)
                        enviar(JSONObject().put("tipo", "adjunto").put("tarjeta", tarjetaAdjunto).put("adjunto", ficha))
                    }
                    PEDIR_EXPORTAR -> {
                        contentResolver.openOutputStream(uri, "wt")!!.use { Copia.exportar(it) }
                        enviar(JSONObject().put("tipo", "aviso").put("msj", getString(R.string.msj_copia_guardada)))
                    }
                    PEDIR_IMPORTAR, PEDIR_OTRAS -> importarDe(uri, false)
                }
            } catch (e: ErrorUsuario) {
                enviar(JSONObject().put("tipo", "aviso").put("msj", e.message ?: getString(R.string.err_generico)))
            } catch (_: Exception) {
                val msj = when (pedido) {
                    PEDIR_EXPORTAR -> getString(R.string.err_guardar_copia)
                    PEDIR_IMPORTAR, PEDIR_OTRAS -> getString(R.string.err_leer_archivo)
                    PEDIR_ADJUNTO -> getString(R.string.err_adjuntar)
                    else -> getString(R.string.err_leer_imagen)
                }
                enviar(JSONObject().put("tipo", "aviso").put("msj", msj))
            }
        }
    }

    /** Avisos posibles: permiso concedido (Android 13+) y notificaciones de la app no desactivadas. */
    private fun hayAvisos(): Boolean =
        (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

    /**
     * El permiso de avisos solo se pide cuando hace falta: al guardar o importar una tarjeta con fecha de evento o de
     * caducidad, y solo la primera vez. Si se deniega no se vuelve a pedir: la página enseña un aviso con un botón
     * hacia los ajustes de notificaciones de Android.
     */
    private fun pedirPermisoAvisos() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean(PREF_PREGUNTADO, false)) {
            prefs.edit().putBoolean(PREF_PREGUNTADO, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISO_AVISOS)
        } else enviar(JSONObject().put("tipo", "avisos").put("activos", hayAvisos()))
    }

    @Deprecated("Activity sin AndroidX: es la forma de recibir el resultado")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(pedido: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(pedido, permisos, resultados)
        if (pedido == PERMISO_AVISOS) enviar(JSONObject().put("tipo", "avisos").put("activos", hayAvisos()))
    }
}
