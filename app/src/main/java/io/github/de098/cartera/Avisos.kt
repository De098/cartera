package io.github.de098.cartera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Avisos con el nombre y el logo de la app. Los dispara una alarma local (ver [Programador]) con estos extras:
 * titulo, texto, tarjeta (id), clave (única por tarjeta y motivo) y canal (entradas | caducidad).
 * Al pulsar el aviso se abre esa tarjeta.
 */
object Avisos {
    const val ACCION = "io.github.de098.cartera.AVISO"

    // canal → nombre visible en Ajustes de Android (se puede elegir sonido/vibración de cada uno)
    private val CANALES = linkedMapOf(
        "entradas" to "Entradas y billetes",
        "caducidad" to "Caducidades",
    )

    fun crearCanales(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        for ((id, nombre) in CANALES)
            nm.createNotificationChannel(NotificationChannel(id, nombre, NotificationManager.IMPORTANCE_HIGH))
    }

    fun mostrar(c: Context, i: Intent) {
        Nucleo.iniciar(c)
        val tarjeta = i.getStringExtra("tarjeta") ?: return
        // si mientras tanto se ha borrado o archivado, ya no hay nada que avisar
        val j = Tarjetas.leer(tarjeta) ?: return
        if (j.optBoolean("archivada")) return
        crearCanales(c)
        val canal = i.getStringExtra("canal")?.takeIf { it in CANALES } ?: "entradas"
        val clave = i.getStringExtra("clave") ?: tarjeta
        val id = clave.hashCode()

        val abrir = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ID, tarjeta)
        }
        val pi = PendingIntent.getActivity(c, id, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, canal)
            .setSmallIcon(R.drawable.ic_aviso)
            .setColor(c.getColor(R.color.acento))
            .setContentTitle(i.getStringExtra("titulo")?.takeIf { it.isNotBlank() } ?: j.optString("nombre", "Cartera"))
            .setContentText(i.getStringExtra("texto") ?: "")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(if (canal == "entradas") Notification.CATEGORY_EVENT else Notification.CATEGORY_REMINDER)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

/** Salta la alarma: enseña el aviso. */
class AvisoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Avisos.ACCION) return
        Avisos.mostrar(c, i)
    }
}
