/* Cartera: la página. Pide los datos a la app con fetch("/cartera/…") (local.js los lleva al puente «Cartera»);
   lo que necesita del sistema (cámara, galería, archivos, brillo…) lo pide al puente, y las respuestas llegan a
   window.alNativo({tipo: …}). Todo en el móvil, sin internet. */
"use strict";

const $ = s => document.querySelector(s);
const puente = window.Cartera || null;   // null si la página se abre fuera de la app (pruebas)

// ---------------------------------------------------------------- utilidades

const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
const norm = s => String(s || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
const DIAS = ["dom", "lun", "mar", "mié", "jue", "vie", "sáb"];
const MESES = ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"];
const COLORES = ["", "#e01b24", "#ff7800", "#f6d32d", "#2ec27e", "#3584e4", "#9141ac", "#986a44", "#241f31"];
const NOMBRES_COLOR = {"": "Sin color", "#e01b24": "Rojo", "#ff7800": "Naranja", "#f6d32d": "Amarillo", "#2ec27e": "Verde",
  "#3584e4": "Azul", "#9141ac": "Morado", "#986a44": "Marrón", "#241f31": "Negro"};
const OPC_CADUCA = [[0, "El mismo día"], [1, "1 día antes"], [7, "1 semana antes"], [15, "15 días antes"],
  [30, "30 días antes"], [60, "60 días antes"], [90, "90 días antes"]];
const OPC_PREVIO = [[0, "Sin aviso previo"], [1, "1 día antes"], [2, "2 días antes"], [3, "3 días antes"], [7, "1 semana antes"]];

function genId() {
  const b = new Uint8Array(8);
  crypto.getRandomValues(b);
  return Array.from(b, x => x.toString(16).padStart(2, "0")).join("");
}

/** Letra blanca u oscura según lo claro que sea el fondo (para que siempre se lea bien). */
function contraste(hex) {
  const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex || "");
  if (!m) return "#241f31";
  const l = (0.299 * parseInt(m[1], 16) + 0.587 * parseInt(m[2], 16) + 0.114 * parseInt(m[3], 16)) / 255;
  return l > 0.6 ? "#241f31" : "#ffffff";
}

function fechaLarga(iso) {   // "2026-12-14" → "lun 14 dic 2026"
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return `${DIAS[new Date(y, m - 1, d).getDay()]} ${d} ${MESES[m - 1]} ${y}`;
}

function textoEvento(t) {
  if (!t.evento) return "";
  const hora = t.eventoHora !== false && t.evento.length >= 16 ? ", " + t.evento.slice(11, 16) : "";
  return fechaLarga(t.evento) + hora;
}

function textoCaduca(t) {
  if (!t.caduca) return "";
  const d = t._dias;
  let r = fechaLarga(t.caduca);
  if (typeof d === "number") {
    if (d < 0) r += d === -1 ? " (caducó ayer)" : ` (caducó hace ${-d} días)`;
    else if (d === 0) r += " (caduca hoy)";
    else r += d === 1 ? " (caduca mañana)" : ` (faltan ${d} días)`;
  }
  return r;
}

async function api(ruta, metodo, cuerpo) {
  const r = await fetch(ruta, {method: metodo || "GET", body: cuerpo == null ? undefined : (typeof cuerpo === "string" ? cuerpo : JSON.stringify(cuerpo)), cache: "no-store"});
  const t = await r.text();
  if (!r.ok) throw new Error(t || "No se ha podido");
  return t;
}

let tTost = 0;
function avisar(msj) {
  const t = $("#toast");
  t.textContent = msj; t.hidden = false;
  clearTimeout(tTost);
  tTost = setTimeout(() => { t.hidden = true; }, 4500);
}

let resolverDlg = null;
function preguntar(texto, boton) {
  $("#dlgtxt").textContent = texto;
  $("#dlgsi").textContent = boton || "Aceptar";
  $("#dlg").hidden = false;
  return new Promise(res => { resolverDlg = res; });
}
/** Un mensaje con su botón «Aceptar» (el resumen de una importación: varias líneas que no caben en un aviso corto). */
function informar(texto) {
  $("#dlgtxt").textContent = texto;
  $("#dlgsi").textContent = "Aceptar";
  $("#dlgsi").classList.remove("peligro");
  $("#dlgno").hidden = true;
  $("#dlgbots").classList.add("uno");
  $("#dlg").hidden = false;
  return new Promise(res => { resolverDlg = res; });
}
function cerrarDlg(si) {
  $("#dlg").hidden = true;
  $("#dlgno").hidden = false; $("#dlgbots").classList.remove("uno"); $("#dlgsi").classList.add("peligro");
  if (resolverDlg) { const r = resolverDlg; resolverDlg = null; r(si); }
}
$("#dlgsi").onclick = () => cerrarDlg(true);
$("#dlgno").onclick = () => cerrarDlg(false);

function bloquear() {
  document.documentElement.classList.toggle("bloq", ["#ver", "#editar", "#ajustes"].some(s => !$(s).hidden));
}

// ---------------------------------------------------------------- datos y lista

let datos = {tarjetas: [], formatos: [], ajustes: {hora: "09:00", previo: 1, caduca: [30, 0]}};
let cargado = false;
let seg = "todo";
let pendienteAbrir = null;
let avisosOk = true;    // ¿pueden salir avisos? (si no, se enseña un aviso con un botón hacia los ajustes de Android)
let actual = null;      // tarjeta que se está viendo
let ed = null;          // tarjeta que se está editando (copia de trabajo)
let edNueva = false;    // el editor es de una tarjeta nueva
let edNumeroOrig = "";  // el número tal como estaba al abrir el editor

async function cargar() {
  try {
    datos = JSON.parse(await api("/cartera/datos"));
    cargado = true;
    if (!$("#etipo").options.length) llenarSelects();
  } catch (e) { avisar("No se han podido leer las tarjetas"); return; }
  pintarLista();
  if (actual && !$("#ver").hidden) {   // la que se está viendo, al día
    const n = datos.tarjetas.find(t => t.id === actual.id);
    if (n) { actual = n; } else cerrarVer();
  }
  if (pendienteAbrir) { const id = pendienteAbrir; pendienteAbrir = null; abrirDesdeAviso(id); }
}

function visibles() {
  let l = datos.tarjetas.filter(t => seg === "arch" ? t.archivada : !t.archivada);
  if (seg === "tarjeta") l = l.filter(t => t.clase !== "entrada");
  if (seg === "entrada") l = l.filter(t => t.clase === "entrada");
  const q = norm($("#buscar").value.trim());
  if (q) l = l.filter(t => norm(t.nombre + " " + t.numero + " " + t.nota).includes(q));
  // el orden de la app: favoritas arriba y luego las usadas hace menos; las entradas, por fecha del evento
  if (seg === "entrada") l.sort((a, b) => (a.evento || "").localeCompare(b.evento || ""));
  else if (seg === "arch") l.sort((a, b) => (b.evento || "").localeCompare(a.evento || "") || (b.uso || 0) - (a.uso || 0));
  return l;
}

function filaHtml(t) {
  const col = t.color || "#c9d1d8";
  const ins = [];
  if (t.clase === "entrada" && t.evento) ins.push(`<span class="ins">${esc(textoEvento(t))}</span>`);
  if (t._caduca === "caducada") ins.push('<span class="ins mal">Caducada</span>');
  else if (t._caduca === "pronto") ins.push('<span class="ins pronto">Caduca pronto</span>');
  const sub = t.tipo === "QR_CODE" ? "Código QR" : (t.numero || "");
  const der = t.archivada
    ? `<div class="acc"><button data-rest>Restaurar</button><button data-borrar class="peligro">Borrar</button></div>`
    : `<button class="estrella" data-fav aria-label="${t.favorita ? "Quitar de favoritas" : "Marcar como favorita"}">${t.favorita ? "★" : "☆"}</button>`;
  return `<div class="fila" data-id="${esc(t.id)}" role="button" tabindex="0"><span class="banda" style="background:${esc(col)}"></span>` +
    `<div class="cuerpo"><div class="nombre">${esc(t.nombre)}</div>` +
    (sub ? `<div class="sub">${esc(sub)}</div>` : "") +
    (ins.length ? `<div class="insignias">${ins.join("")}</div>` : "") + `</div>${der}</div>`;
}

function pintarLista() {
  const l = visibles();
  $("#lista").innerHTML = l.map(filaHtml).join("");
  const v = $("#vacio");
  v.hidden = l.length > 0;
  if (!l.length) {
    if ($("#buscar").value.trim()) v.textContent = "No hay nada que coincida con la búsqueda.";
    else if (seg === "arch") v.textContent = "No hay nada archivado. Las entradas y billetes se archivan solos cuando pasa el evento.";
    else if (seg === "entrada") v.textContent = "No tienes entradas ni billetes. Pulsa «Añadir» y elige «Entrada o billete».";
    else v.textContent = "Todavía no tienes tarjetas. Pulsa «Añadir» para escanear la primera.";
  }
}

$("#seg").addEventListener("click", e => {
  const b = e.target.closest("button[data-s]");
  if (!b) return;
  seg = b.dataset.s;
  document.querySelectorAll("#seg button").forEach(x => x.classList.toggle("on", x === b));
  pintarLista();
});
$("#buscar").addEventListener("input", pintarLista);

$("#lista").addEventListener("click", async e => {
  const f = e.target.closest(".fila");
  if (!f) return;
  const id = f.dataset.id;
  const t = datos.tarjetas.find(x => x.id === id);
  if (!t) return;
  if (e.target.closest("[data-fav]")) {
    e.stopPropagation();
    try {
      await api("/cartera/favorita", "POST", {id, valor: !t.favorita});
      await cargar();
    } catch (err) { avisar(err.message); }
  } else if (e.target.closest("[data-rest]")) {
    try { await api("/cartera/restaurar", "POST", id); await cargar(); avisar("Restaurada"); } catch (err) { avisar(err.message); }
  } else if (e.target.closest("[data-borrar]")) {
    if (await preguntar(`¿Borrar «${t.nombre}» para siempre?`, "Borrar")) {
      try { await api("/cartera/borrar", "POST", id); await cargar(); } catch (err) { avisar(err.message); }
    }
  } else abrirVer(id);
});

// ---------------------------------------------------------------- ver una tarjeta

const contenido = t => t.codigo || t.numero || "";
const urlCodigo = (tipo, c, bin) => `/cartera/img/codigo.svg?t=${encodeURIComponent(tipo)}&n=${encodeURIComponent(c)}${bin ? "&b=1" : ""}`;
const urlAdjunto = (id, f, p) => `/cartera/img/adjunto?id=${encodeURIComponent(id)}&f=${encodeURIComponent(f)}&p=${p || 0}`;

function abrirVer(id) {
  const t = datos.tarjetas.find(x => x.id === id);
  if (!t) return;
  actual = t;
  pintarVer();
  $("#ver").hidden = false;
  $("#ver").scrollTop = 0;
  bloquear();
  if (puente) puente.pantallaCodigo(true);   // brillo al máximo y pantalla encendida mientras se ve
  api("/cartera/usar", "POST", id).catch(() => {});
}

function cerrarVer() {
  $("#ver").hidden = true;
  actual = null;
  bloquear();
  if (puente) puente.pantallaCodigo(false);   // brillo y pantalla como estaban
  cargar();
}

function pintarVer() {
  const t = actual;
  const col = t.color || "#e3e7eb";
  const cab = $("#vcab");
  cab.style.background = col; cab.style.color = contraste(col);
  $("#vnombre").textContent = t.nombre;
  $("#vtipo").textContent = (t.clase === "entrada" ? "Entrada o billete" : "Tarjeta") + (t.archivada ? " · archivada" : "");
  const c = contenido(t);
  const hay = t.tipo && t.tipo !== "NINGUNO" && c;
  $("#vcodigo").hidden = !hay;
  const img = $("#vimg"), err = $("#verr");
  err.hidden = true; img.hidden = false;
  img.onerror = () => { img.hidden = true; err.hidden = false; err.textContent = "No se puede dibujar el código con ese número."; };
  if (hay) img.src = urlCodigo(t.tipo, c, !!t.bruto);
  const n = $("#vnum");
  n.textContent = t.numero || "";
  n.hidden = !t.numero;
  n.classList.toggle("largo", (t.numero || "").length > 20);
  $("#vcontenido").hidden = !c;
  $("#vreconocer").hidden = !c;
  $("#vfav").textContent = t.favorita ? "★ Favorita" : "☆ Favorita";

  const info = [];
  if (t.clase === "entrada" && t.evento) {
    info.push(`<p><b>Cuándo:</b> ${esc(textoEvento(t))}</p>`);
    info.push(`<p class="suave">${t.previo > 0 ? `Aviso previo: ${t.previo === 1 ? "1 día" : t.previo + " días"} antes. ` : ""}Avisa también el propio día.</p>`);
  }
  if (t.caduca) {
    const cls = t._caduca === "caducada" ? "ins mal" : t._caduca === "pronto" ? "ins pronto" : "";
    info.push(`<p><b>Caduca:</b> ${esc(textoCaduca(t))} ${cls ? `<span class="${cls}">${t._caduca === "caducada" ? "Caducada" : "Caduca pronto"}</span>` : ""}</p>`);
  }
  if (t.nota) info.push(`<p class="nota">${esc(t.nota)}</p>`);
  $("#vinfo").innerHTML = info.join("");
  $("#vinfo").hidden = !info.length;
  pintarAvisosMal();

  const adj = [];
  for (const a of t.adjuntos || []) {
    const paginas = a.tipo === "pdf" ? Math.max(1, a.paginas || 1) : 1;
    for (let p = 0; p < paginas; p++)
      adj.push(`<figure><img loading="lazy" alt="${esc(a.nombre || "Adjunto")}" src="${urlAdjunto(t.id, a.f, p)}">` +
        `<figcaption>${esc(a.nombre || (a.tipo === "pdf" ? "PDF" : "Imagen"))}${paginas > 1 ? ` · página ${p + 1} de ${paginas}` : ""}</figcaption></figure>`);
  }
  $("#vadj").innerHTML = adj.join("");
  $("#varch").textContent = t.archivada ? "Restaurar" : "Archivar";
}

$("#vvolver").onclick = cerrarVer;
$("#vfav").onclick = async () => {
  const t = actual;
  try {
    await api("/cartera/favorita", "POST", {id: t.id, valor: !t.favorita});
    t.favorita = !t.favorita;
    pintarVer();
  } catch (e) { avisar(e.message); }
};
$("#veditar").onclick = () => abrirEditor(actual);
$("#vcontenido").onclick = () => abrirContenido(actual);
$("#vreconocer").onclick = async () => {
  const t = actual;
  try {
    const r = await api("/cartera/reconocer", "POST", t.id);
    if (r === "no") { avisar("No se ha reconocido ninguna fecha en este código. Usa «Ver contenido del código» para copiarlo."); return; }
    abrirEditor(t);
    aplicarReconocido(JSON.parse(r));
  } catch (e) { avisar(e.message); }
};
$("#vinicio").onclick = () => { if (puente) puente.anadirAcceso(actual.id); else avisar("Solo funciona dentro de la app"); };
$("#varch").onclick = async () => {
  const t = actual;
  try {
    await api(t.archivada ? "/cartera/restaurar" : "/cartera/archivar", "POST", t.id);
    avisar(t.archivada ? "Restaurada" : "Archivada");
    cerrarVer();
  } catch (e) { avisar(e.message); }
};
$("#vborrar").onclick = async () => {
  const t = actual;
  if (!(await preguntar(`¿Borrar «${t.nombre}» para siempre? No se puede deshacer.`, "Borrar"))) return;
  try { await api("/cartera/borrar", "POST", t.id); cerrarVer(); } catch (e) { avisar(e.message); }
};

// ---------------------------------------------------------------- añadir y editar

function pintarChecks(cont, opciones, seleccion) {
  const todas = opciones.slice();
  for (const d of seleccion) if (!todas.some(o => o[0] === d)) todas.push([d, `${d} días antes`]);
  todas.sort((a, b) => a[0] - b[0]);
  cont.innerHTML = todas.map(([d, n]) =>
    `<label class="check"><input type="checkbox" data-d="${d}" ${seleccion.includes(d) ? "checked" : ""}> ${esc(n)}</label>`).join("");
}
const leerChecks = cont => Array.from(cont.querySelectorAll("input:checked"), i => Number(i.dataset.d)).sort((a, b) => b - a);

function llenarSelects() {
  $("#etipo").innerHTML = (datos.formatos || []).map(([k, n]) => `<option value="${esc(k)}">${esc(n)}</option>`).join("");
  const opts = OPC_PREVIO.map(([d, n]) => `<option value="${d}">${esc(n)}</option>`).join("");
  $("#eprevio").innerHTML = opts;
  $("#ajprevio").innerHTML = opts;
}

function pintarColores() {
  $("#ecolores").innerHTML = COLORES.map(c =>
    `<button type="button" data-c="${c}" class="${ed.color === c ? "on" : ""}" aria-label="${NOMBRES_COLOR[c]}" title="${NOMBRES_COLOR[c]}" ` +
    `style="background:${c || "#fff"};color:${contraste(c || "#ffffff")}">${c ? (ed.color === c ? "✓" : "") : "∅"}</button>`).join("");
}

function pintarClase() {
  document.querySelectorAll("#eclase button").forEach(b => b.classList.toggle("on", b.dataset.c === ed.clase));
  $("#eevento").hidden = ed.clase !== "entrada";
}

function pintarAdjuntos() {
  $("#eadj").innerHTML = (ed.adjuntos || []).map((a, i) =>
    `<div class="adjfila"><span>${a.tipo === "pdf" ? "PDF" : "Imagen"}: ${esc(a.nombre || a.f)}${a.tipo === "pdf" ? ` (${a.paginas || 1} pág.)` : ""}</span>` +
    `<button type="button" data-q="${i}" class="peligro">Quitar</button></div>`).join("");
}

function abrirEditor(t) {
  const aj = datos.ajustes || {previo: 1, caduca: [30, 0]};
  const nueva = !t;
  ed = nueva
    ? {id: genId(), clase: seg === "entrada" ? "entrada" : "tarjeta", nombre: "", numero: "", codigo: "", tipo: "CODE_128", color: "", nota: "",
       favorita: false, caduca: "", caducaAvisos: (aj.caduca || [30, 0]).slice(), previo: aj.previo, evento: "", eventoHora: true, adjuntos: []}
    : JSON.parse(JSON.stringify(t));
  ed.adjuntos = ed.adjuntos || [];
  ed.caducaAvisos = ed.caducaAvisos || (aj.caduca || []).slice();
  edNumeroOrig = ed.numero || "";
  edNueva = nueva;
  $("#eleido").hidden = true; $("#epregunta").hidden = true; $("#pcuando").hidden = true;
  $("#etitulo").textContent = nueva ? "Nueva tarjeta" : "Editar";
  $("#enombre").value = ed.nombre || "";
  $("#enumero").value = ed.numero || "";
  $("#etipo").value = ed.tipo || "NINGUNO";
  $("#enota").value = ed.nota || "";
  $("#efav").checked = !!ed.favorita;
  $("#ecaduca").value = ed.caduca || "";
  pintarChecks($("#ecadchecks"), OPC_CADUCA, ed.caducaAvisos);
  $("#ecadavisos").hidden = !ed.caduca;
  $("#efecha").value = ed.evento ? ed.evento.slice(0, 10) : "";
  $("#ehora").value = ed.evento && ed.eventoHora !== false && ed.evento.length >= 16 ? ed.evento.slice(11, 16) : "";
  $("#eprevio").value = String(ed.previo != null ? ed.previo : aj.previo);
  pintarClase(); pintarColores(); pintarAdjuntos();
  vistaPrevia();
  $("#editar").hidden = false;
  $("#editar").scrollTop = 0;
  bloquear();
}

function cerrarEditor() {
  $("#editar").hidden = true;
  ed = null;
  bloquear();
}

let tPrev = 0;
function vistaPrevia() {
  clearTimeout(tPrev);
  tPrev = setTimeout(async () => {
    if (!ed) return;
    const tipo = $("#etipo").value;
    const c = ed.codigo || $("#enumero").value.trim();
    const bin = !!ed.bruto;
    const msj = $("#eprevmsj");
    if (tipo === "NINGUNO" || !c) { $("#eprev").hidden = true; msj.hidden = true; return; }
    try {
      const r = await api("/cartera/valido", "POST", {tipo, contenido: c, bin});
      if (!ed) return;
      if (r === "ok") {
        $("#eprevimg").src = urlCodigo(tipo, c, bin);
        $("#eprev").hidden = false;
        msj.hidden = true;
      } else {
        $("#eprev").hidden = true;
        msj.className = "msj-mal"; msj.textContent = r; msj.hidden = false;
      }
    } catch (e) { $("#eprev").hidden = true; }
  }, 250);
}

$("#enumero").addEventListener("input", () => {
  if ($("#enumero").value.trim() !== edNumeroOrig) { ed.codigo = ""; ed.bruto = ""; }   // otro número: el código ya no lleva lo de antes
  vistaPrevia();
});
$("#etipo").addEventListener("change", vistaPrevia);
$("#eclase").addEventListener("click", e => {
  const b = e.target.closest("button[data-c]");
  if (!b) return;
  ed.clase = b.dataset.c;
  pintarClase();
});
$("#ecolores").addEventListener("click", e => {
  const b = e.target.closest("button[data-c]");
  if (!b) return;
  ed.color = b.dataset.c;
  pintarColores();
});
$("#ecaduca").addEventListener("input", () => { $("#ecadavisos").hidden = !$("#ecaduca").value; });
$("#eadj").addEventListener("click", e => {
  const b = e.target.closest("button[data-q]");
  if (!b) return;
  ed.adjuntos.splice(Number(b.dataset.q), 1);
  pintarAdjuntos();
});
$("#escanear").onclick = () => { if (puente) puente.escanear(); else avisar("Solo funciona dentro de la app"); };
$("#egaleria").onclick = () => { if (puente) puente.galeria(); else avisar("Solo funciona dentro de la app"); };
$("#eadjuntar").onclick = () => { if (puente) puente.adjuntar(ed.id); else avisar("Solo funciona dentro de la app"); };
$("#ecancelar").onclick = cerrarEditor;

async function guardar() {
  const nombre = $("#enombre").value.trim();
  if (!nombre) { avisar("Pon un nombre"); $("#enombre").focus(); return; }
  const o = {
    id: ed.id, clase: ed.clase, nombre, numero: $("#enumero").value.trim(), tipo: $("#etipo").value,
    codigo: ed.codigo || "", bruto: ed.bruto || "", color: ed.color || "", nota: $("#enota").value, favorita: $("#efav").checked,
    caduca: $("#ecaduca").value || "", caducaAvisos: leerChecks($("#ecadchecks")), adjuntos: ed.adjuntos,
  };
  if (ed.clase === "entrada") {
    const dia = $("#efecha").value, hora = $("#ehora").value;
    if (!dia) { avisar("Pon el día del evento"); $("#efecha").focus(); return; }
    o.evento = hora ? `${dia}T${hora}` : dia;
    o.eventoHora = !!hora;
    o.previo = Number($("#eprevio").value) || 0;
  }
  const veniaDeVer = !$("#ver").hidden;
  try {
    const id = await api("/cartera/guardar", "POST", o);
    // con fecha de evento o de caducidad hacen falta los avisos: el permiso se pide ahora, y solo la primera vez
    if ((o.evento || o.caduca) && puente) { try { puente.pedirAvisos(); } catch (e) {} }
    cerrarEditor();
    await cargar();
    if (veniaDeVer) {
      const n = datos.tarjetas.find(t => t.id === id);
      if (n) { actual = n; pintarVer(); } else cerrarVer();
    } else if (!seg.startsWith("arch")) avisar("Guardada");
  } catch (e) { avisar(e.message); }
}
$("#eguardar").onclick = guardar;
$("#eguardar2").onclick = guardar;
$("#bnuevo").onclick = () => abrirEditor(null);

// ---------------------------------------------------------------- lo que lleva el código: reconocido, pregunta rápida y contenido

/** Rellena el formulario con lo que la app ha sacado del código (no se guarda hasta que la persona pulse Guardar). */
function aplicarReconocido(r) {
  ed.clase = "entrada";
  pintarClase();
  $("#efecha").value = r.evento.slice(0, 10);
  $("#ehora").value = r.eventoHora ? r.evento.slice(11, 16) : "";
  if (!$("#enombre").value.trim()) $("#enombre").value = r.nombre;
  const l = $("#eleido");
  l.textContent = "Datos sacados del código: " + r.resumen + ". Revísalos y corrígelos si hace falta.";
  l.hidden = false;
  $("#epregunta").hidden = true;
}

/** El código no lleva fecha: se pregunta si es una entrada o un billete, y si sí, se piden ya el día y la hora. */
function preguntaRapida() {
  $("#eleido").hidden = true;
  $("#pcuando").hidden = true;
  $("#epregunta").hidden = false;
}
$("#psi").onclick = () => {
  ed.clase = "entrada";
  pintarClase();
  $("#pfecha").value = $("#efecha").value;
  $("#phora").value = $("#ehora").value;
  $("#pcuando").hidden = false;
  $("#pfecha").focus();
};
$("#pno").onclick = () => { $("#epregunta").hidden = true; };
$("#pfecha").addEventListener("input", () => { $("#efecha").value = $("#pfecha").value; });
$("#phora").addEventListener("input", () => { $("#ehora").value = $("#phora").value; });

let textoCopiable = "";
/** El contenido del código en bruto: el texto y, si es binario, en hexadecimal con una vista previa. */
function abrirContenido(t) {
  const c = contenido(t);
  let txt;
  if (t.bruto) {
    let b = "";
    try { b = atob(t.bruto); } catch (e) {}
    const filas = [];
    for (let i = 0; i < b.length; i += 16) {
      const tr = b.slice(i, i + 16);
      filas.push(i.toString(16).padStart(4, "0") + "  " + Array.from(tr, ch => ch.charCodeAt(0).toString(16).padStart(2, "0")).join(" ").padEnd(47) +
        "  " + Array.from(tr, ch => { const k = ch.charCodeAt(0); return k >= 32 && k < 127 ? ch : "·"; }).join(""));
    }
    txt = `Binario, ${b.length} octetos. Tipo: ${t.tipo}\n\nHEXADECIMAL\n${filas.join("\n")}\n\nBASE64\n${t.bruto}`;
  } else txt = `Texto (${c.length} caracteres). Tipo: ${t.tipo}\n\n${c}`;
  textoCopiable = txt;
  $("#conttxt").textContent = textoCopiable;
  $("#cont").hidden = false;
}
$("#contcerrar").onclick = () => { $("#cont").hidden = true; };
$("#contcopiar").onclick = async () => {
  try {
    if (puente && puente.copiar) puente.copiar(textoCopiable);
    else await navigator.clipboard.writeText(textoCopiable);
    avisar("Copiado");
  } catch (e) { avisar("No se ha podido copiar"); }
};

// ---------------------------------------------------------------- avisos desactivados

/** Enseña (o esconde) el aviso de «avisos desactivados» en la tarjeta que se ve, si tiene fecha, y en Ajustes. */
function pintarAvisosMal() {
  const t = actual;
  $("#vavisos").hidden = avisosOk || !t || t.archivada || !(t.evento || t.caduca);
  $("#ajavisosmal").hidden = avisosOk;
}
function refrescarAvisos() {
  try { if (puente) avisosOk = !!puente.avisosActivos(); } catch (e) {}
  pintarAvisosMal();
}
for (const b of document.querySelectorAll(".bavisos")) b.onclick = () => { if (puente) puente.ajustesAvisos(); };

// ---------------------------------------------------------------- ajustes

function abrirAjustes() {
  const a = datos.ajustes || {};
  $("#ajhora").value = a.hora || "09:00";
  $("#ajprevio").value = String(a.previo != null ? a.previo : 1);
  pintarChecks($("#ajcad"), OPC_CADUCA, a.caduca || [30, 0]);
  try { $("#ajversion").textContent = puente ? puente.version() : ""; } catch (e) {}
  refrescarAvisos();
  $("#ajustes").hidden = false;
  $("#ajustes").scrollTop = 0;
  bloquear();
}
function cerrarAjustes() { $("#ajustes").hidden = true; bloquear(); }

async function guardarAjustes() {
  const a = {hora: $("#ajhora").value || "09:00", previo: Number($("#ajprevio").value) || 0, caduca: leerChecks($("#ajcad"))};
  datos.ajustes = a;
  try { await api("/cartera/ajustes-guardar", "POST", a); } catch (e) { avisar(e.message); }
}
for (const s of ["#ajhora", "#ajprevio"]) $(s).addEventListener("change", guardarAjustes);
$("#ajcad").addEventListener("change", guardarAjustes);
$("#bajustes").onclick = abrirAjustes;
$("#ajvolver").onclick = cerrarAjustes;
$("#ajexportar").onclick = () => { if (puente) puente.exportar(); else avisar("Solo funciona dentro de la app"); };
$("#ajotras").onclick = () => { if (puente) puente.importarOtras(); else avisar("Solo funciona dentro de la app"); };
$("#ajimportar").onclick = () => { if (puente) puente.importar(); else avisar("Solo funciona dentro de la app"); };

// ---------------------------------------------------------------- lo que llega de la app y el botón atrás

function cerrarTodo() {
  if (!$("#dlg").hidden) cerrarDlg(false);
  $("#cont").hidden = true;
  if (!$("#editar").hidden) cerrarEditor();
  if (!$("#ajustes").hidden) cerrarAjustes();
  if (!$("#ver").hidden) cerrarVer();
}

async function abrirDesdeAviso(id, recargar) {
  if (recargar) await cargar();   // por si la tarjeta es nueva desde la última vez
  cerrarTodo();
  if (!datos.tarjetas.some(t => t.id === id)) { avisar("Esa tarjeta ya no está"); return; }
  const t = datos.tarjetas.find(x => x.id === id);
  if (t.archivada) { seg = "arch"; document.querySelectorAll("#seg button").forEach(b => b.classList.toggle("on", b.dataset.s === "arch")); pintarLista(); }
  abrirVer(id);
}

window.alNativo = m => {
  if (!m) return;
  if (m.tipo === "codigo") {
    if (!ed) return;
    ed.codigo = ""; ed.bruto = "";
    if (m.bruto) {   // código binario (billete UIC…): no cabe en el número; se guarda aparte y se dibuja desde ahí
      ed.bruto = m.bruto; ed.codigo = m.numero || "";
      $("#enumero").value = "";
    } else $("#enumero").value = m.numero || "";
    edNumeroOrig = $("#enumero").value;   // viene del código: no es «otro número»
    if ((datos.formatos || []).some(f => f[0] === m.formato)) $("#etipo").value = m.formato;
    vistaPrevia();
    avisar("Código leído");
    if (m.reconocido) aplicarReconocido(m.reconocido);
    else if (edNueva && ed.clase !== "entrada") preguntaRapida();
  } else if (m.tipo === "adjunto") {
    if (!ed || m.tarjeta !== ed.id) return;
    ed.adjuntos.push(m.adjunto);
    pintarAdjuntos();
  } else if (m.tipo === "aviso") {
    avisar(m.msj);
  } else if (m.tipo === "resumen") {
    informar(m.msj);
  } else if (m.tipo === "avisos") {
    avisosOk = !!m.activos;
    pintarAvisosMal();
  } else if (m.tipo === "abrir") {
    if (cargado) abrirDesdeAviso(m.id, true); else pendienteAbrir = m.id;
  } else if (m.tipo === "recargar") {
    if (cargado) cargar();
  }
};

/** Botón atrás de Android: cierra lo último que se abrió. Devuelve true si había algo que cerrar. */
window.atras = () => {
  if (!$("#dlg").hidden) { cerrarDlg(false); return true; }
  if (!$("#cont").hidden) { $("#cont").hidden = true; return true; }
  if (!$("#editar").hidden) { cerrarEditor(); return true; }
  if (!$("#ajustes").hidden) { cerrarAjustes(); return true; }
  if (!$("#ver").hidden) { cerrarVer(); return true; }
  return false;
};

// ---------------------------------------------------------------- arranque

refrescarAvisos();
cargar();
