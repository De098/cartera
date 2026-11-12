package io.github.de098.cartera

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDateTime

/** Widget «Favoritas»: la lista de las tarjetas favoritas; al pulsar una se abre directamente su código. */
object Widgets {
    /** Repinta todos los widgets (al cambiar algo en la app y cada vez que se ponen al día los avisos). */
    fun actualizarTodos(c: Context) {
        val am = AppWidgetManager.getInstance(c)
        val ids = am.getAppWidgetIds(ComponentName(c, WFavoritas::class.java))
        for (wid in ids) favoritas(c, am, wid)
    }

    private fun favoritas(c: Context, am: AppWidgetManager, wid: Int) {
        val v = RemoteViews(c.packageName, R.layout.w_favoritas)
        // la lista la rellena ServicioFavoritas; la uri distinta por widget evita que Android confunda dos widgets
        v.setRemoteAdapter(R.id.lista, Intent(c, ServicioFavoritas::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid).setData(Uri.parse("cartera://favoritas/$wid")))
        v.setEmptyView(R.id.lista, R.id.vacio)
        // cada fila trae su id de tarjeta (setOnClickFillInIntent) y se completa con esta plantilla
        v.setPendingIntentTemplate(R.id.lista, PendingIntent.getActivity(c, 9300 + wid,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        // el título abre la app
        v.setOnClickPendingIntent(R.id.titulo, PendingIntent.getActivity(c, 9200 + wid,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        am.updateAppWidget(wid, v)
        am.notifyAppWidgetViewDataChanged(wid, R.id.lista)
    }

    /** Las favoritas que no están archivadas, en el orden de la lista de la app. */
    fun listaFavoritas(): List<Fila> {
        val orden = compareByDescending<org.json.JSONObject> { it.optLong("uso") }.thenByDescending { it.optLong("creada") }
        return Tarjetas.todas().filter { it.optBoolean("favorita") && !it.optBoolean("archivada") }.sortedWith(orden).map {
            val sub = if (it.optString("clase") == "entrada" && it.optString("evento").isNotEmpty()) {
                try {
                    val e = LocalDateTime.parse(it.optString("evento"))
                    "${e.dayOfMonth}/${e.monthValue}/${e.year}" + if (it.optBoolean("eventoHora", true)) " " + e.format(Nucleo.HM) else ""
                } catch (_: Exception) { "" }
            } else it.optString("numero")
            Fila(it.optString("id"), it.optString("nombre"), sub,
                try { Color.parseColor(it.optString("color")) } catch (_: Exception) { 0xFF9AA4AD.toInt() })
        }
    }

    class Fila(val id: String, val nombre: String, val sub: String, val color: Int)
}

class WFavoritas : AppWidgetProvider() {
    override fun onUpdate(c: Context, am: AppWidgetManager, ids: IntArray) {
        Nucleo.iniciar(c)
        Widgets.actualizarTodos(c)
    }
}

/** Da las filas de la lista del widget. */
class ServicioFavoritas : RemoteViewsService() {
    override fun onGetViewFactory(i: Intent): RemoteViewsFactory = FabricaFavoritas(applicationContext)
}

private class FabricaFavoritas(val c: Context) : RemoteViewsService.RemoteViewsFactory {
    private var filas: List<Widgets.Fila> = emptyList()
    override fun onCreate() { Nucleo.iniciar(c) }
    override fun onDataSetChanged() { filas = try { Widgets.listaFavoritas() } catch (_: Exception) { emptyList() } }
    override fun onDestroy() {}
    override fun getCount() = filas.size
    override fun getViewAt(pos: Int): RemoteViews {
        val f = filas.getOrNull(pos) ?: return RemoteViews(c.packageName, R.layout.w_favorita_fila)
        return RemoteViews(c.packageName, R.layout.w_favorita_fila).apply {
            setTextViewText(R.id.t, f.nombre)
            setTextViewText(R.id.s, f.sub)
            setViewVisibility(R.id.s, if (f.sub.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
            setInt(R.id.barra, "setColorFilter", f.color)
            setOnClickFillInIntent(R.id.fila, Intent().putExtra(MainActivity.EXTRA_ID, f.id))
        }
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(pos: Int) = pos.toLong()
    override fun hasStableIds() = false
}
