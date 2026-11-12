# Cartera

Tarjetas de socio y fidelización, entradas y billetes con su código, que funcionan **enteros en el móvil**: sin cuenta,
sin servidor y sin permiso de internet. Android 10 o superior. Licencia GPL-3.0. Hermana de
[Recordatorios](https://github.com/De098/recordatorios).

- **Tarjetas con código**: nombre, número, tipo de código (QR, EAN-13, EAN-8, UPC-A, Code 128, Code 39, Aztec, PDF417,
  Data Matrix y algunos más), color, nota y favoritas. Lista con buscador: favoritas arriba y luego las usadas hace menos.
  Al abrir una, el código sale grande con el número debajo, el brillo al máximo y la pantalla sin apagarse; al cerrarla
  se restaura todo.
- **Tres formas de añadir**: escanear con la cámara (el permiso se pide en ese momento), leer el código de una foto de la
  galería, o teclear el número y elegir el tipo.
- **Entradas y billetes**: fecha y hora del evento, adjunto (imagen o PDF) y/o código. Avisan el día del evento y un
  día antes (configurable). Cuando pasa el evento se archivan solos (restaurables y borrables).
- **Caducidades**: cualquier tarjeta puede caducar; avisos configurables (por defecto 30 días antes y el mismo día) y
  distintivos «Caduca pronto» y «Caducada».
- **Widget de favoritas** (pulsar una abre su código) y **acceso directo** a una tarjeta en la pantalla de inicio.
- **Copia de seguridad** en un `.zip` (tarjetas y adjuntos) y **recuperación de copias de Catima** (su `.zip` de exportación).

## Cómo está hecha

Kotlin con una sola dependencia, [ZXing](https://github.com/zxing/zxing) `core` (Java puro, sin red). Nada de AndroidX,
CameraX, ML Kit ni Play Services: la cámara usa la API Camera2 de Android. La interfaz es una página web empaquetada en
`app/src/main/assets/web` (`cartera.html`, `cartera.js`) que se enseña en un `WebView` sin acceso a la red; sus peticiones
(`/cartera/…`) las contesta la propia app (`Rutas.kt`), y el código dibujado y los adjuntos los sirve a las etiquetas `img`
la propia app también.

| Archivo | Qué hace |
|---|---|
| `Nucleo.kt` | carpeta de datos, lista de formatos de código, ajustes (`ajustes.json`) y descripción del modelo de datos |
| `Tarjetas.kt` | leer, guardar, borrar, archivar; archivado automático de entradas; avisos que toca programar |
| `Codigos.kt` | dibujar códigos como SVG y leerlos de una foto, con ZXing |
| `Adjuntos.kt` | copiar imágenes y PDF a la carpeta privada; páginas de un PDF como imagen (`PdfRenderer`) |
| `EscanerActivity.kt` | escáner con Camera2 + ZXing, con el permiso de la cámara pedido al abrirse |
| `Rutas.kt` | puente entre la página y las clases anteriores (datos, guardar, borrar…, e imágenes) |
| `Programador.kt`, `Avisos.kt` | alarmas exactas locales, canales de notificación, reprogramado al encender el móvil |
| `Copia.kt`, `Catima.kt` | copia `.zip` de la app e importación de la de Catima (`catima.csv` + imágenes) |
| `Widgets.kt` | el widget de favoritas |
| `MainActivity.kt` | el `WebView`, el puente con el sistema (cámara, galería, archivos, brillo, accesos directos) |

Los datos están en la carpeta privada de la app: `tarjetas/«id».json` (uno por tarjeta, con los campos que no conoce esta
versión conservados al guardar) y `adjuntos/«id»/…`. No hay copia de seguridad en la nube (`allowBackup=false`).

Permisos: `CAMERA` (solo al pulsar «Escanear»), `POST_NOTIFICATIONS`, `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` y
`RECEIVE_BOOT_COMPLETED` (avisos). **No** tiene `INTERNET`.

## Compilar

```
./gradlew assembleDebug      # APK de pruebas
./gradlew assembleRelease    # sin firmar (F-Droid firma con su clave)
```

Hace falta JDK 17 o superior y el SDK de Android 36.

## Licencias

Código: GPL-3.0 (`LICENSE`). ZXing (`com.google.zxing:core`): Apache 2.0. Tipografía Nunito: SIL OFL 1.1
(`app/src/main/assets/web/nunito-LICENCIA.txt`).
