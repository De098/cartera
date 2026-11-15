package io.github.de098.cartera

/**
 * Pasa un CSV con cabecera a tarjetas. Sirve para el CSV de Catima (cualquier versión), el de Loyalty Card Keychain
 * y cualquier otro: las columnas se reconocen por su nombre, sin importar mayúsculas, tildes ni el idioma (castellano
 * o inglés). El separador puede ser coma, punto y coma o tabulador.
 *
 * Los CSV de Catima 2.x traen varias tablas separadas por una línea vacía (versión, grupos, tarjetas, tarjeta↔grupo):
 * se usa la que tiene nombre y número.
 */
object TablaCsv {
    private val NOMBRE = listOf("store", "nombre", "name", "tienda", "comercio", "shop", "merchant", "empresa", "company", "brand", "marca",
        "establecimiento", "title", "titulo", "cardname", "nombretarjeta", "storename", "programa", "tarjeta", "card")
    private val NUMERO = listOf("cardid", "cardnumber", "numerodetarjeta", "numerotarjeta", "number", "numero", "nrotarjeta", "num", "nro", "no",
        "code", "codigo", "memberid", "member", "numerodesocio", "numerosocio", "socio", "nsocio", "cardno")
    private val CONTENIDO = listOf("barcodeid", "barcode", "codigodebarras", "codigobarras", "contenido", "valor", "value", "message", "mensaje")
    private val TIPO = listOf("barcodetype", "tipo", "format", "formato", "type", "tipodecodigo", "tipocodigo", "barcodeformat", "symbology", "simbologia")
    private val NOTA = listOf("note", "notes", "nota", "notas", "comment", "comments", "comentario", "comentarios", "description", "descripcion", "observaciones")
    private val CADUCA = listOf("expiry", "expirydate", "expiration", "expirationdate", "expires", "expdate", "caducidad", "fechadecaducidad",
        "fechacaducidad", "caduca", "vencimiento", "validuntil", "validhasta", "validthru", "validto", "validez")
    private val EVENTO = listOf("event", "eventdate", "evento", "fechaevento", "fechadelevento", "relevantdate")
    private val COLOR = listOf("headercolor", "color", "colour", "backgroundcolor", "bgcolor", "colorfondo", "colordefondo")
    private val FAVORITA = listOf("starstatus", "favorite", "favourite", "favorita", "favorito", "star", "estrella")
    private val ARCHIVADA = listOf("archive", "archived", "archivada", "archivado")
    private val USO = listOf("lastused", "ultimouso")
    // columnas que solo tiene Catima (el de Loyalty Card Keychain no las trae)
    private val SOLO_CATIMA = listOf("validfrom", "balance", "balancetype", "barcodeid", "barcodeencoding", "starstatus", "lastused", "archive")

    /** El error de «no hay columna con el número» (para que [Catima] lo distinga sin depender del idioma). */
    fun errColumnas(): String = Nucleo.s(R.string.err_csv_columnas)

    /** Devuelve null si ha ido bien (lo importado queda en [lote]) o el motivo por el que no se puede. */
    fun importar(lote: Lote, texto: String, imagenes: (String) -> List<Imagen>): String? {
        val sep = Csv.separador(texto)
        val tablas = mutableListOf<List<List<String>>>()
        var actual = mutableListOf<List<String>>()
        for (f in Csv.leer(texto, sep)) {
            if (f.all { it.isBlank() }) { if (actual.isNotEmpty()) { tablas += actual; actual = mutableListOf() } }
            else actual += f
        }
        if (actual.isNotEmpty()) tablas += actual
        if (tablas.isEmpty()) return Nucleo.s(R.string.err_csv_vacio)

        val conVersion = tablas[0].size == 1 && tablas[0][0].size == 1 && tablas[0][0][0].trim().matches(Regex("\\d{1,5}"))

        fun cabecera(t: List<List<String>>) = t[0].map { Csv.clave(it) }
        fun tiene(cab: List<String>, l: List<String>) = l.any { it in cab }
        val tabla = tablas.firstOrNull { t -> cabecera(t).let { tiene(it, NOMBRE) && tiene(it, NUMERO + CONTENIDO) } }
            ?: tablas.firstOrNull { t -> cabecera(t).let { tiene(it, NUMERO + CONTENIDO) && !(it.size == 2 && "groupid" in it) } }
            ?: return errColumnas()

        val cab = cabecera(tabla)
        fun idx(l: List<String>): Int { for (n in l) { val i = cab.indexOf(n); if (i >= 0) return i }; return -1 }
        val cNombre = idx(NOMBRE); val cNumero = idx(NUMERO); val cCont = idx(CONTENIDO); val cTipo = idx(TIPO); val cNota = idx(NOTA)
        val cCad = idx(CADUCA); val cEv = idx(EVENTO); val cColor = idx(COLOR); val cFav = idx(FAVORITA); val cArch = idx(ARCHIVADA)
        val cUso = idx(USO); val cId = cab.indexOf("id")

        if (lote.origen == "CSV") {
            lote.origen = when {
                conVersion || tiene(cab, SOLO_CATIMA) -> "Catima"
                "store" in cab && "cardid" in cab && "headertextcolor" in cab -> "Loyalty Card Keychain"
                else -> "CSV"
            }
        }

        fun col(f: List<String>, i: Int) = if (i in f.indices) f[i].trim() else ""
        for (f in tabla.drop(1)) {
            var numero = col(f, cNumero)
            val cont = col(f, cCont)
            var codigo = ""
            if (numero.isEmpty()) numero = cont else if (cont.isNotEmpty() && cont != numero) codigo = cont
            val nombre = col(f, cNombre).ifEmpty { if (cNombre < 0 && (numero.isNotEmpty())) Nucleo.s(R.string.tarjeta_sin_nombre) else "" }
            if (nombre.isEmpty() && numero.isEmpty()) continue
            if (nombre.isEmpty()) { lote.omitidas++; continue }

            val crudo = col(f, cTipo)
            val tipo: String? = if (crudo.isEmpty() || crudo.equals("null", true)) "" else Csv.tipo(crudo)
            val ev = if (cEv >= 0) Csv.fecha(col(f, cEv)) else null
            val cad = Csv.fecha(col(f, cCad))
            lote.anadir(Ficha(
                nombre = nombre, numero = numero, codigo = codigo, tipo = tipo, nota = col(f, cNota), color = Csv.color(col(f, cColor)),
                caduca = cad?.dia?.toString() ?: "", evento = ev, favorita = Csv.verdad(col(f, cFav)), archivada = Csv.verdad(col(f, cArch)),
                uso = (col(f, cUso).toLongOrNull() ?: 0L).let { if (it in 1 until 100_000_000_000L) it * 1000 else maxOf(it, 0) },
                imagenes = if (cId >= 0) imagenes(col(f, cId)) else emptyList(),
            ))
        }
        return null
    }
}
