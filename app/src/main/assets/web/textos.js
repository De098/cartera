/* Cartera: los textos de la página, en castellano (es) e inglés británico (en).
   Idioma: según el del móvil (el WebView sigue al sistema). Castellano si es es, ca, gl o eu (cualquier región);
   inglés en cualquier otro caso. Como Android al elegir res/values-*, se recorre la lista de idiomas del móvil y se
   toma el primero que sea de esos o inglés.
   Uso: t("clave", ...huecos). Un texto es una cadena con {0}, {1}… o una función (para plurales). */
"use strict";

const TEXTOS = {
  es: {
    app: "Cartera",
    dias: ["dom", "lun", "mar", "mié", "jue", "vie", "sáb"],
    meses: ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"],
    // fijos del HTML
    ajustes: "Ajustes",
    buscar_ph: "Buscar por nombre, número o nota",
    s_todo: "Todo", s_tarjetas: "Tarjetas", s_entradas: "Entradas", s_arch: "Archivadas",
    anadir: "＋ Añadir",
    volver: "‹ Volver", volver_sin: "Volver", cancelar_atras: "‹ Cancelar",
    cod_alt: "Código", prev_alt: "Vista previa del código",
    avisos_off_tarjeta: "Los avisos están desactivados: actívalos en los ajustes de Android para que esta tarjeta te avise.",
    avisos_off: "Los avisos están desactivados: actívalos en los ajustes de Android.",
    abrir_ajustes_avisos: "Abrir ajustes de notificaciones",
    editar: "Editar", ver_contenido: "Ver contenido del código", reconocer: "Reconocer fecha",
    a_inicio: "Añadir a la pantalla de inicio", archivar: "Archivar", borrar: "Borrar",
    nueva_tarjeta: "Nueva tarjeta", guardar: "Guardar",
    p_pregunta: "¿Es una entrada o un billete?", p_si: "Sí", p_no: "No, es una tarjeta",
    p_cuando: "¿Cuándo es? (se avisa ese día)", dia: "Día", hora_op: "Hora (opcional)",
    que_es: "¿Qué es?", tarjeta: "Tarjeta", entrada: "Entrada o billete",
    nombre: "Nombre", nombre_ph: "Supermercado, biblioteca, concierto…",
    codigo: "Código", escanear: "Escanear con la cámara", de_foto: "Leer de una foto",
    numero_codigo: "Número (o texto) del código", tipo_codigo: "Tipo de código",
    cuando_es: "Cuándo es", previo_label: "Aviso previo (además del aviso del propio día)",
    color: "Color", nota: "Nota", favorita_check: "Fijar arriba (también sale en el widget)",
    caduca_el: "Caduca el (opcional)", avisarme: "Avisarme:",
    adjuntos: "Adjuntos (imagen o PDF)", adjuntar: "Adjuntar imagen o PDF",
    aj_avisos: "Avisos",
    aj_avisos_info: "Las entradas avisan el día del evento y, si quieres, unos días antes. Las caducidades avisan los días que elijas.",
    aj_hora: "Hora de los avisos", aj_previo: "Aviso previo de las entradas nuevas",
    aj_cad: "Avisos de caducidad de las tarjetas nuevas",
    aj_importar_t: "Importar de otras apps",
    aj_importar_p: "Trae tus tarjetas de otra app. El formato se reconoce solo, mirando el archivo por dentro.",
    aj_importar_b: "Importar…",
    aj_importar_info: "Se puede importar de Catima (su copia .zip o .csv, sin contraseña), Loyalty Card Keychain, pases de Apple Wallet (.pkpass) y cualquier archivo .csv con una fila de cabecera.",
    aj_copia_t: "Copia de seguridad",
    aj_copia_p: "Guarda todo (tarjetas, entradas y sus adjuntos) en un archivo .zip, o recupéralo. Al recuperar no se borra nada.",
    aj_guardar_copia: "Guardar copia", aj_recuperar_copia: "Recuperar copia",
    aj_acerca_t: "Acerca de",
    aj_acerca_p: "Todo se queda en este móvil: la app no tiene permiso de internet.",
    aj_licencias: "Software libre (GPL-3.0). Los códigos se dibujan y se leen con ZXing (Apache 2.0). Tipografía Nunito (SIL OFL 1.1).",
    cancelar: "Cancelar", aceptar: "Aceptar",
    cont_titulo: "Contenido del código", copiar: "Copiar", cerrar: "Cerrar",
    // dinámicos
    err_leer: "No se han podido leer las tarjetas",
    err_generico: "No se ha podido",
    caducada: "Caducada", caduca_pronto: "Caduca pronto", qr: "Código QR",
    fijar: "Fijar arriba", quitar_fijo: "Quitar de arriba", opciones: "Opciones",
    restaurar: "Restaurar",
    vacio_busqueda: "No hay nada que coincida con la búsqueda.",
    vacio_arch: "No hay nada archivado. Las entradas y billetes se archivan solos cuando pasa el evento.",
    vacio_entradas: "No tienes entradas ni billetes. Pulsa «Añadir» y elige «Entrada o billete».",
    vacio_todo: "Todavía no tienes tarjetas. Pulsa «Añadir» para escanear la primera.",
    restaurada: "Restaurada", archivada: "Archivada", guardada: "Guardada",
    borrar_conf: "¿Borrar «{0}» para siempre?",
    borrar_conf2: "¿Borrar «{0}» para siempre? No se puede deshacer.",
    tipo_archivada: " · archivada",
    err_dibujar: "No se puede dibujar el código con ese número.",
    cuando: "Cuándo:",
    previo_info: n => `Aviso previo: ${n === 1 ? "1 día" : n + " días"} antes. `,
    avisa_dia: "Avisa también el propio día.",
    caduca_dos: "Caduca:",
    cad_ayer: "caducó ayer", cad_hace: n => `caducó hace ${n} días`, cad_hoy: "caduca hoy", cad_manana: "caduca mañana",
    cad_faltan: n => `faltan ${n} días`,
    adjunto: "Adjunto", pdf: "PDF", imagen: "Imagen",
    pagina_de: (p, n) => ` · página ${p} de ${n}`,
    sin_fecha: "No se ha reconocido ninguna fecha en este código. Usa «Ver contenido del código» para copiarlo.",
    solo_app: "Solo funciona dentro de la app",
    editar_titulo: "Editar",
    n_pags: n => ` (${n} pág.)`, quitar: "Quitar",
    pon_nombre: "Pon un nombre", pon_dia: "Pon el día del evento",
    leido: "Datos sacados del código: {0}. Revísalos y corrígelos si hace falta.",
    binario: (n, tipo, hex, b64) => `Binario, ${n} octetos. Tipo: ${tipo}\n\nHEXADECIMAL\n${hex}\n\nBASE64\n${b64}`,
    texto: (n, tipo, c) => `Texto (${n} caracteres). Tipo: ${tipo}\n\n${c}`,
    copiado: "Copiado", err_copiar: "No se ha podido copiar",
    tarjeta_no_esta: "Esa tarjeta ya no está", codigo_leido: "Código leído",
    // colores y plazos
    c_ninguno: "Sin color", c_rojo: "Rojo", c_naranja: "Naranja", c_amarillo: "Amarillo", c_verde: "Verde", c_azul: "Azul",
    c_morado: "Morado", c_marron: "Marrón", c_negro: "Negro",
    mismo_dia: "El mismo día", sin_previo: "Sin aviso previo",
    antes: n => n === 1 ? "1 día antes" : n === 7 ? "1 semana antes" : `${n} días antes`,
  },
  en: {
    app: "Cardholder",
    dias: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
    meses: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"],
    ajustes: "Settings",
    buscar_ph: "Search by name, number or note",
    s_todo: "All", s_tarjetas: "Cards", s_entradas: "Tickets", s_arch: "Archived",
    anadir: "＋ Add",
    volver: "‹ Back", volver_sin: "Back", cancelar_atras: "‹ Cancel",
    cod_alt: "Code", prev_alt: "Code preview",
    avisos_off_tarjeta: "Notifications are turned off. Turn them on in Android settings to get reminders for this card.",
    avisos_off: "Notifications are turned off. Turn them on in Android settings.",
    abrir_ajustes_avisos: "Open notification settings",
    editar: "Edit", ver_contenido: "View code contents", reconocer: "Detect date",
    a_inicio: "Add to home screen", archivar: "Archive", borrar: "Delete",
    nueva_tarjeta: "New card", guardar: "Save",
    p_pregunta: "Is this a ticket for a specific date?", p_si: "Yes, it's a ticket", p_no: "No, it's a card",
    p_cuando: "When is it? (you'll get a reminder that day)", dia: "Date", hora_op: "Time (optional)",
    que_es: "Type", tarjeta: "Card", entrada: "Ticket or pass",
    nombre: "Name", nombre_ph: "Supermarket, library, gig…",
    codigo: "Code", escanear: "Scan with camera", de_foto: "Read from a photo",
    numero_codigo: "Code number (or text)", tipo_codigo: "Code type",
    cuando_es: "When", previo_label: "Early reminder (as well as the one on the day)",
    color: "Colour", nota: "Note", favorita_check: "Pin to top (also shown in the widget)",
    caduca_el: "Expires on (optional)", avisarme: "Remind me:",
    adjuntos: "Attachments (image or PDF)", adjuntar: "Attach image or PDF",
    aj_avisos: "Notifications",
    aj_avisos_info: "Tickets remind you on the day of the event and, if you like, a few days before. Expiry reminders arrive on the days you choose.",
    aj_hora: "Reminder time", aj_previo: "Early reminder for new tickets",
    aj_cad: "Expiry reminders for new cards",
    aj_importar_t: "Import from other apps",
    aj_importar_p: "Bring over your cards from another app. The format is detected automatically from the file's contents.",
    aj_importar_b: "Import…",
    aj_importar_info: "You can import from Catima (its .zip or .csv backup, without a password), Loyalty Card Keychain, Apple Wallet passes (.pkpass) and any .csv file with a header row.",
    aj_copia_t: "Backup",
    aj_copia_p: "Save everything (cards, tickets and their attachments) to a .zip file, or restore it. Restoring never deletes anything.",
    aj_guardar_copia: "Save backup", aj_recuperar_copia: "Restore backup",
    aj_acerca_t: "About",
    aj_acerca_p: "Everything stays on this phone: the app has no internet permission.",
    aj_licencias: "Free software (GPL-3.0). Codes are drawn and read with ZXing (Apache 2.0). Typeface: Nunito (SIL OFL 1.1).",
    cancelar: "Cancel", aceptar: "OK",
    cont_titulo: "Code contents", copiar: "Copy", cerrar: "Close",
    err_leer: "Couldn't load your cards",
    err_generico: "Something went wrong",
    caducada: "Expired", caduca_pronto: "Expires soon", qr: "QR code",
    fijar: "Pin to top", quitar_fijo: "Unpin", opciones: "Options",
    restaurar: "Restore",
    vacio_busqueda: "Nothing matches your search.",
    vacio_arch: "Nothing archived. Tickets and passes are archived automatically once the event has passed.",
    vacio_entradas: "No tickets or passes yet. Tap Add and choose Ticket or pass.",
    vacio_todo: "No cards yet. Tap Add to scan your first one.",
    restaurada: "Restored", archivada: "Archived", guardada: "Saved",
    borrar_conf: "Delete “{0}” permanently?",
    borrar_conf2: "Delete “{0}” permanently? This can't be undone.",
    tipo_archivada: " · archived",
    err_dibujar: "That number can't be drawn as a code.",
    cuando: "When:",
    previo_info: n => `Early reminder: ${n === 1 ? "1 day" : n + " days"} before. `,
    avisa_dia: "You'll also be reminded on the day.",
    caduca_dos: "Expires:",
    cad_ayer: "expired yesterday", cad_hace: n => `expired ${n} days ago`, cad_hoy: "expires today", cad_manana: "expires tomorrow",
    cad_faltan: n => `${n} days left`,
    adjunto: "Attachment", pdf: "PDF", imagen: "Image",
    pagina_de: (p, n) => ` · page ${p} of ${n}`,
    sin_fecha: "No date found in this code. Use View code contents to copy it.",
    solo_app: "This only works inside the app",
    editar_titulo: "Edit",
    n_pags: n => n === 1 ? " (1 page)" : ` (${n} pages)`, quitar: "Remove",
    pon_nombre: "Add a name", pon_dia: "Add the event date",
    leido: "Details read from the code: {0}. Check them and correct anything that's wrong.",
    binario: (n, tipo, hex, b64) => `Binary, ${n} bytes. Type: ${tipo}\n\nHEX\n${hex}\n\nBASE64\n${b64}`,
    texto: (n, tipo, c) => `Text (${n} characters). Type: ${tipo}\n\n${c}`,
    copiado: "Copied", err_copiar: "Couldn't copy",
    tarjeta_no_esta: "That card is no longer there", codigo_leido: "Code read",
    c_ninguno: "No colour", c_rojo: "Red", c_naranja: "Orange", c_amarillo: "Yellow", c_verde: "Green", c_azul: "Blue",
    c_morado: "Purple", c_marron: "Brown", c_negro: "Black",
    mismo_dia: "On the day", sin_previo: "No early reminder",
    antes: n => n === 1 ? "1 day before" : n === 7 ? "1 week before" : `${n} days before`,
  },
};

/** Idioma de la página: "es" o "en". */
const IDIOMA = (() => {
  const SIMILARES = ["es", "ca", "gl", "eu"];
  const lista = (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || "en"]);
  for (const l of lista) {
    const base = String(l || "").toLowerCase().split(/[-_]/)[0];
    if (SIMILARES.includes(base)) return "es";
    if (base === "en") return "en";
  }
  return "en";
})();
document.documentElement.lang = IDIOMA === "es" ? "es" : "en-GB";   // en-GB: fechas con día primero y semana desde el lunes

/** Texto de la clave en el idioma elegido (si falta, el castellano; si no, la propia clave). */
function t(clave, ...args) {
  let v = TEXTOS[IDIOMA][clave];
  if (v === undefined) v = TEXTOS.es[clave];
  if (v === undefined) return clave;
  if (typeof v === "function") return v(...args);
  return v.replace(/\{(\d+)\}/g, (_, i) => args[i] === undefined ? "" : args[i]);
}

/** Rellena los textos fijos del HTML: data-t (contenido), data-t-placeholder, data-t-title, data-t-aria, data-t-alt. */
function traducirPagina() {
  for (const e of document.querySelectorAll("[data-t]")) e.textContent = t(e.dataset.t);
  for (const e of document.querySelectorAll("[data-t-placeholder]")) e.placeholder = t(e.dataset.tPlaceholder);
  for (const e of document.querySelectorAll("[data-t-title]")) e.title = t(e.dataset.tTitle);
  for (const e of document.querySelectorAll("[data-t-aria]")) e.setAttribute("aria-label", t(e.dataset.tAria));
  for (const e of document.querySelectorAll("[data-t-alt]")) e.alt = t(e.dataset.tAlt);
}

/** Lo mismo que t(): en cartera.js «t» casi siempre es una tarjeta y taparía a la función. */
const tr = t;
