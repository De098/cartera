package io.github.de098.cartera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.PlanarYUVLuminanceSource

/**
 * Escáner de códigos con la cámara: la API Camera2 de Android dibuja la vista previa y entrega los fotogramas, y
 * ZXing ([Codigos]) los lee. No hay CameraX ni nada de fuera. Se pide el permiso de la cámara aquí mismo, al abrirse.
 * Devuelve a MainActivity «numero» (el texto leído) y «formato» (clave del tipo). En vertical siempre.
 */
class EscanerActivity : Activity() {
    private lateinit var contenedor: FrameLayout
    private lateinit var textura: TextureView
    private var botonLinterna: TextView? = null

    private var camara: CameraDevice? = null
    private var sesion: CameraCaptureSession? = null
    private var lector: ImageReader? = null
    private var peticion: CaptureRequest.Builder? = null
    private var tamano: Size? = null
    private var orientacionSensor = 90
    private var linterna = false

    private var hiloCamara: HandlerThread? = null
    private var hiloLectura: HandlerThread? = null
    private var manejadorCamara: Handler? = null
    private var manejadorLectura: Handler? = null

    @Volatile private var ocupado = false
    @Volatile private var terminado = false
    private var fotograma = 0

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val fondo = getColor(R.color.fondo)
        val texto = getColor(R.color.texto)
        val colorBorde = getColor(R.color.borde)

        contenedor = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        textura = TextureView(this)
        contenedor.addView(textura, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contenedor.addView(Visor(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contenedor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> ajustarTextura() }

        val letra = try { resources.getFont(R.font.nunito) } catch (_: Exception) { Typeface.DEFAULT }
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        val ayuda = TextView(this).apply {
            text = getString(R.string.esc_pon_codigo)
            setTextColor(texto); textSize = 17f; typeface = Typeface.create(letra, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        fun boton(t: String, accion: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(texto); textSize = 16f; typeface = Typeface.create(letra, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(2), colorBorde); cornerRadius = dp(12).toFloat() }
            setOnClickListener { accion() }
        }
        val filaBotones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        val bLinterna = boton(getString(R.string.esc_linterna)) { alternarLinterna() }
        botonLinterna = bLinterna
        bLinterna.visibility = View.GONE   // solo si el móvil tiene flash (se sabe al abrir la cámara)
        val bCancelar = boton(getString(R.string.esc_cancelar)) { terminar(null, null, null) }
        val lpBoton = { LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(6), 0, dp(6), 0) } }
        filaBotones.addView(bLinterna, lpBoton())
        filaBotones.addView(bCancelar, lpBoton())

        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(fondo)
            setPadding(dp(16), dp(12), dp(16), dp(14))
            addView(ayuda, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(filaBotones, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(fondo)
            addView(contenedor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // de borde a borde en Android 15+: dejar sitio a las barras del sistema
        raiz.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val s = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(s.left, s.top, s.right, s.bottom)
                WindowInsets.CONSUMED
            } else ins
        }
        setContentView(raiz)

        textura.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { abrirCamara() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
    }

    @Deprecated("Activity sin AndroidX: así llega la respuesta al permiso")
    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, permisos, resultados)
        if (codigo != 7) return
        if (resultados.isNotEmpty() && resultados[0] == PackageManager.PERMISSION_GRANTED) { if (textura.isAvailable) abrirCamara() }
        else terminar(null, null, getString(R.string.esc_sin_permiso))
    }

    override fun onResume() {
        super.onResume()
        hiloCamara = HandlerThread("camara").also { it.start(); manejadorCamara = Handler(it.looper) }
        hiloLectura = HandlerThread("lectura").also { it.start(); manejadorLectura = Handler(it.looper) }
        if (textura.isAvailable) abrirCamara()
    }

    override fun onPause() {
        cerrarCamara()
        hiloCamara?.quitSafely(); hiloLectura?.quitSafely()
        hiloCamara = null; hiloLectura = null; manejadorCamara = null; manejadorLectura = null
        super.onPause()
    }

    // ---------------------------------------------------------------- cámara

    @SuppressLint("MissingPermission")   // el permiso se comprueba justo antes
    private fun abrirCamara() {
        if (camara != null || terminado) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val hilo = manejadorCamara ?: return
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull() ?: return terminar(null, null, getString(R.string.esc_sin_camara))
            val car = cm.getCameraCharacteristics(id)
            orientacionSensor = car.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val mapa = car.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return terminar(null, null, getString(R.string.esc_camara_no))
            // un tamaño que sirva a la vez para la vista previa y para leer, sin pasar de ~2 Mpx
            val paraVista = mapa.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
            val paraLeer = mapa.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val t = paraLeer.filter { it in paraVista && it.width <= 1920 && it.height <= 1440 }.maxByOrNull { it.width * it.height }
                ?: paraLeer.minByOrNull { it.width * it.height } ?: return terminar(null, null, getString(R.string.esc_camara_no))
            tamano = t
            textura.surfaceTexture?.setDefaultBufferSize(t.width, t.height)
            runOnUiThread { ajustarTextura(); if (car.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) botonLinterna?.visibility = View.VISIBLE }
            val af = car.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true

            lector = ImageReader.newInstance(t.width, t.height, ImageFormat.YUV_420_888, 2).also { r ->
                r.setOnImageAvailableListener({ lee ->
                    val img = (try { lee.acquireLatestImage() } catch (_: Exception) { null }) ?: return@setOnImageAvailableListener
                    if (ocupado || terminado) { img.close(); return@setOnImageAvailableListener }
                    ocupado = true
                    val w = img.width; val h = img.height
                    val datos = ByteArray(w * h)
                    try {
                        val p = img.planes[0]
                        val buf = p.buffer; val paso = p.rowStride
                        for (fila in 0 until h) { buf.position(fila * paso); buf.get(datos, fila * w, w) }
                    } catch (_: Exception) { img.close(); ocupado = false; return@setOnImageAvailableListener }
                    img.close()
                    val m = manejadorLectura
                    if (m == null) ocupado = false else m.post { leer(datos, w, h) }
                }, hilo)
            }

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    camara = c
                    try {
                        val superficie = Surface(textura.surfaceTexture)
                        val b = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(superficie); b.addTarget(lector!!.surface)
                        if (af) b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        peticion = b
                        @Suppress("DEPRECATION")
                        c.createCaptureSession(listOf(superficie, lector!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (camara == null) return
                                sesion = s
                                try { s.setRepeatingRequest(b.build(), null, hilo) } catch (_: Exception) {}
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { terminar(null, null, getString(R.string.esc_camara_fallo)) }
                        }, hilo)
                    } catch (_: Exception) { terminar(null, null, getString(R.string.esc_camara_fallo)) }
                }
                override fun onDisconnected(c: CameraDevice) { c.close(); camara = null }
                override fun onError(c: CameraDevice, error: Int) { c.close(); camara = null; terminar(null, null, getString(R.string.esc_camara_fallo)) }
            }, hilo)
        } catch (_: CameraAccessException) {
            terminar(null, null, getString(R.string.esc_camara_fallo))
        } catch (_: Exception) {
            terminar(null, null, getString(R.string.esc_camara_fallo))
        }
    }

    private fun cerrarCamara() {
        try { sesion?.close() } catch (_: Exception) {}
        try { camara?.close() } catch (_: Exception) {}
        try { lector?.close() } catch (_: Exception) {}
        sesion = null; camara = null; lector = null; peticion = null
        linterna = false
    }

    /** La vista previa se estira para llenar el recuadro sin deformarse (recorta lo que sobra). */
    private fun ajustarTextura() {
        val t = tamano ?: return
        val cw = contenedor.width; val ch = contenedor.height
        if (cw == 0 || ch == 0) return
        val vertical = orientacionSensor % 180 == 90
        val iw = (if (vertical) t.height else t.width).toFloat()
        val ih = (if (vertical) t.width else t.height).toFloat()
        val k = maxOf(cw / iw, ch / ih)
        val ancho = (iw * k).toInt(); val alto = (ih * k).toInt()
        val lp = textura.layoutParams as FrameLayout.LayoutParams
        if (lp.width != ancho || lp.height != alto) {
            lp.width = ancho; lp.height = alto; lp.gravity = Gravity.CENTER
            textura.layoutParams = lp
        }
    }

    private fun alternarLinterna() {
        val b = peticion ?: return
        val s = sesion ?: return
        linterna = !linterna
        b.set(CaptureRequest.FLASH_MODE, if (linterna) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        try { s.setRepeatingRequest(b.build(), null, manejadorCamara) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- lectura

    /** Gira la imagen (solo luminosidad) para que quede derecha según la orientación del sensor. */
    private fun girar(d: ByteArray, w: Int, h: Int, grados: Int): Triple<ByteArray, Int, Int> {
        val r = ByteArray(d.size)
        return when (grados) {
            90 -> { for (y in 0 until h) for (x in 0 until w) r[x * h + (h - 1 - y)] = d[y * w + x]; Triple(r, h, w) }
            270 -> { for (y in 0 until h) for (x in 0 until w) r[(w - 1 - x) * h + y] = d[y * w + x]; Triple(r, h, w) }
            180 -> { for (i in d.indices) r[d.size - 1 - i] = d[i]; Triple(r, w, h) }
            else -> Triple(d, w, h)
        }
    }

    private fun leer(datos: ByteArray, w: Int, h: Int) {
        try {
            if (terminado) return
            val (d, ancho, alto) = girar(datos, w, h, orientacionSensor)
            val fuente = PlanarYUVLuminanceSource(d, ancho, alto, 0, 0, ancho, alto, false)
            fotograma++
            // un fotograma sí y otro no, también en negativo (códigos claros sobre fondo oscuro)
            val r = Codigos.leer(if (fotograma % 4 == 3) fuente.invert() else fuente)
            if (r != null) runOnUiThread { terminar(r.texto, r.tipo, null, r.bytes) }
        } catch (_: Exception) {
        } finally { ocupado = false }
    }

    private fun terminar(numero: String?, formato: String?, error: String?, bruto: ByteArray? = null) {
        if (terminado) return
        terminado = true
        if (numero != null) setResult(RESULT_OK, Intent().putExtra("numero", numero).putExtra("formato", formato).putExtra("bruto", bruto))
        else setResult(RESULT_CANCELED, Intent().apply { if (error != null) putExtra("error", error) })
        if (Looper.myLooper() == Looper.getMainLooper()) finish() else runOnUiThread { finish() }
    }

    /** El recuadro donde poner el código: oscurece lo de alrededor. */
    private class Visor(c: Context) : View(c) {
        private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 4f * c.resources.displayMetrics.density }
        override fun onDraw(canvas: Canvas) {
            val ancho = width * 0.78f
            val alto = ancho * 0.7f
            val r = RectF((width - ancho) / 2, (height - alto) / 2, (width + ancho) / 2, (height + alto) / 2)
            val radio = 14f * resources.displayMetrics.density
            canvas.save()
            val hueco = android.graphics.Path().apply { addRoundRect(r, radio, radio, android.graphics.Path.Direction.CW) }
            canvas.clipOutPath(hueco)
            canvas.drawColor(0x99000000.toInt())
            canvas.restore()
            canvas.drawRoundRect(r, radio, radio, borde)
        }
    }
}
