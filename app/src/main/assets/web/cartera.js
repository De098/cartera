/* Cartera: la página. Pide los datos a la app con fetch("/cartera/…") (local.js los lleva al puente «Cartera»);
   lo que necesita del sistema (cámara, galería, archivos, brillo…) lo pide al puente, y las respuestas llegan a
   window.alNativo({tipo: …}). Todo en el móvil, sin internet. */
"use strict";

const $ = s => document.querySelector(s);
const puente = window.Cartera || null;   // null si la página se abre fuera de la app (pruebas)

// ---------------------------------------------------------------- utilidades

const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
const norm = s => String(s || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
const DIAS = TEXTOS[IDIOMA].dias;     // textos.js: nombres de días y meses en el idioma elegido
const MESES = TEXTOS[IDIOMA].meses;
const COLORES = ["", "#e01b24", "#ff7800", "#f6d32d", "#2ec27e", "#3584e4", "#9141ac", "#986a44", "#241f31"];
const NOMBRES_COLOR = {"": tr("c_ninguno"), "#e01b24": tr("c_rojo"), "#ff7800": tr("c_naranja"), "#f6d32d": tr("c_amarillo"), "#2ec27e": tr("c_verde"),
  "#3584e4": tr("c_azul"), "#9141ac": tr("c_morado"), "#986a44": tr("c_marron"), "#241f31": tr("c_negro")};
const OPC_CADUCA = [[0, tr("mismo_dia")], ...[1, 7, 15, 30, 60, 90].map(d => [d, tr("antes", d)])];
const OPC_PREVIO = [[0, tr("sin_previo")], ...[1, 2, 3, 7].map(d => [d, tr("antes", d)])];

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

function fechaLarga(iso) {   // "2026-12-14" → "lun 14 dic 2026" / "Mon 14 Dec 2026"
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
    if (d < 0) r += ` (${d === -1 ? tr("cad_ayer") : tr("cad_hace", -d)})`;
    else if (d === 0) r += ` (${tr("cad_hoy")})`;
    else r += ` (${d === 1 ? tr("cad_manana") : tr("cad_faltan", d)})`;
  }
  return r;
}

async function api(ruta, metodo, cuerpo) {
  const r = await fetch(ruta, {method: metodo || "GET", body: cuerpo == null ? undefined : (typeof cuerpo === "string" ? cuerpo : JSON.stringify(cuerpo)), cache: "no-store"});
  const t = await r.text();
  if (!r.ok) throw new Error(t || tr("err_generico"));
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
  $("#dlgsi").textContent = boton || tr("aceptar");
  $("#dlg").hidden = false;
  return new Promise(res => { resolverDlg = res; });
}
/** Un mensaje con su botón «Aceptar» (el resumen de una importación: varias líneas que no caben en un aviso corto). */
function informar(texto) {
  $("#dlgtxt").textContent = texto;
  $("#dlgsi").textContent = tr("aceptar");
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
  } catch (e) { avisar(tr("err_leer")); return; }
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

// chincheta de Recordatorios (viewBox 48x48)
const CHINCHETA = '<svg class="chincheta" viewBox="0 0 48 48" aria-hidden="true"><line x1="24" y1="30" x2="24" y2="43" stroke="#78858F" stroke-width="3.4" stroke-linecap="round"/><path d="M17,7 H31 L29,17 L35,26 H13 L19,17 Z" fill="#81D4FA" stroke="#78858F" stroke-width="3.2" stroke-linejoin="round"/></svg>';

// Iconos SVG de las opciones (estilo de las demás apps: contorno gris, relleno azul claro)
const ICO = (() => {
  const G = "#78858F", C = "#81D4FA", AC = "#29B6F6";
  const svg48 = c => `<svg class="ic-svg" viewBox="0 0 48 48" aria-hidden="true">${c}</svg>`;
  const hoja = (y = 12) => `<rect x="9" y="${y}" width="30" height="${40 - y}" rx="5" fill="#FFFFFF" stroke="${G}" stroke-width="3.4"/>`;
  const anillas = `<path d="M17,13 V8 a2.6,2.6 0 1 1 5.2,0 V10 M27,13 V8 a2.6,2.6 0 1 1 5.2,0 V10" fill="none" stroke="${G}" stroke-width="3" stroke-linecap="round"/>`;
  const l = (x1, y, x2, c = C, w = 3) => `<line x1="${x1}" y1="${y}" x2="${x2}" y2="${y}" stroke="${c}" stroke-width="${w}" stroke-linecap="round"/>`;
  let d = "";
  for (let k = 0; k < 8; k++) for (const [g, r] of [[-14, 15], [-8, 19.5], [8, 19.5], [14, 15]]) {
    const t = (k * 45 + g) * Math.PI / 180;
    d += (d ? " L" : "M") + (24 + r * Math.cos(t)).toFixed(1) + "," + (24 + r * Math.sin(t)).toFixed(1);
  }
  return {
    editar: svg48(`<g transform="rotate(45 24 24)"><rect x="19" y="6" width="10" height="27" rx="2" fill="${AC}" stroke="${G}" stroke-width="3"/><path d="M19,33 L24,43 L29,33 Z" fill="#FFFFFF" stroke="${G}" stroke-width="3" stroke-linejoin="round"/><line x1="19" y1="12" x2="29" y2="12" stroke="${G}" stroke-width="3"/></g>`),
    ver: svg48(`<path d="M5,24 Q24,6 43,24 Q24,42 5,24 Z" fill="#FFFFFF" stroke="${G}" stroke-width="3.4" stroke-linejoin="round"/><circle cx="24" cy="24" r="6.5" fill="${C}"/><circle cx="24" cy="24" r="2.8" fill="${AC}"/>`),
    cal: svg48(hoja(14) + l(15, 21, 33) + `<g stroke="${C}" stroke-width="2.6" stroke-linecap="round"><line x1="19.5" y1="26" x2="19.5" y2="38"/><line x1="28.5" y1="26" x2="28.5" y2="38"/></g>` + l(14.5, 29, 33.5, C, 2.6) + l(14.5, 35, 33.5, C, 2.6) + `<rect x="21.5" y="30.5" width="5" height="4" rx="1" fill="${AC}"/>` + anillas),
    // Provisional: D quiere cambiar este icono más adelante
    casa: svg48(`<path d="M11,22 V39 a3,3 0 0 0 3,3 H34 a3,3 0 0 0 3,-3 V22" fill="#FFFFFF" stroke="${G}" stroke-width="3.4" stroke-linejoin="round"/><path d="M6,24 L24,8 L42,24" fill="none" stroke="${G}" stroke-width="3.6" stroke-linecap="round" stroke-linejoin="round"/><rect x="20" y="29" width="8" height="13" rx="1.5" fill="${AC}"/><path d="M15,25.5 L24,17.5 L33,25.5" fill="none" stroke="${C}" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>`),
    archivar: svg48(`<rect x="10" y="18" width="28" height="23" rx="4" fill="#FFFFFF" stroke="${G}" stroke-width="3.4"/><rect x="6" y="9" width="36" height="10" rx="3" fill="${C}" stroke="${G}" stroke-width="3.2"/><line x1="20" y1="26" x2="28" y2="26" stroke="${AC}" stroke-width="3.4" stroke-linecap="round"/>` + l(15, 33, 33, C, 2.6)),
    borrar: svg48(`<path d="M12,14 L14.5,40 a3,3 0 0 0 3,2.7 H30.5 a3,3 0 0 0 3,-2.7 L36,14" fill="#FFFFFF" stroke="${G}" stroke-width="3.4" stroke-linejoin="round"/>` + l(8, 13, 40, G, 3.4) + `<path d="M19,13 V8 H29 V13" fill="none" stroke="${G}" stroke-width="3.2" stroke-linejoin="round"/><g stroke="${C}" stroke-width="2.8" stroke-linecap="round"><line x1="20" y1="20" x2="20.8" y2="35"/><line x1="28" y1="20" x2="27.2" y2="35"/></g>`),
    rueda: svg48(`<path d="${d} Z" fill="#FFFFFF" stroke="${G}" stroke-width="3.2" stroke-linejoin="round"/><circle cx="24" cy="24" r="6" fill="none" stroke="${C}" stroke-width="3.2"/>`),
  };
})();
let abierta = null;   // id de la tarjeta con el desplegable de opciones abierto

/** Opciones del desplegable: [acción, icono, texto, clase]; null = raya separadora. */
function opcionesHtml(t) {
  const c = contenido(t);
  const o = [
    ["fijar", CHINCHETA.replace("chincheta","chincheta-op"), t.favorita ? tr("quitar_fijo") : tr("fijar")],
    ["editar", ICO.editar, tr("editar")],
  ];
  if (c) o.push(["contenido", ICO.ver, tr("ver_contenido")], ["reconocer", ICO.cal, tr("reconocer")]);
  o.push(["inicio", ICO.casa, tr("a_inicio")], null, ["archivar", ICO.archivar, tr("archivar")], ["borrar", ICO.borrar, tr("borrar"), "peligro"]);
  return o.map(x => x ? `<button data-op="${x[0]}" class="${x[3] || ""}"><span class="ic">${x[1]}</span>${esc(x[2])}</button>` : '<hr>').join("");
}

function filaHtml(t, hueco) {
  const col = t.color || "#c9d1d8";
  const ins = [];
  if (t.clase === "entrada" && t.evento) ins.push(`<span class="ins">${esc(textoEvento(t))}</span>`);
  if (t._caduca === "caducada") ins.push(`<span class="ins mal">${esc(tr("caducada"))}</span>`);
  else if (t._caduca === "pronto") ins.push(`<span class="ins pronto">${esc(tr("caduca_pronto"))}</span>`);
  const sub = t.tipo === "QR_CODE" ? tr("qr") : (t.numero || "");
  const der = t.archivada
    ? `<div class="acc"><button data-rest>${esc(tr("restaurar"))}</button><button data-borrar class="peligro">${esc(tr("borrar"))}</button></div>`
    : `<button class="flecha" data-flecha aria-label="${esc(tr("opciones"))}" aria-expanded="${abierta === t.id}">▾</button>`;
  const cls = "fila" + (t.favorita && !t.archivada ? " fijada" : "") + (abierta === t.id && !t.archivada ? " abierta" : "") + (hueco ? " hueco" : "");
  return `<div class="${cls}" data-id="${esc(t.id)}"><div class="fila-in"><div class="fila-fila" role="button" tabindex="0">` +
    `<span class="banda" style="background:${esc(col)}"></span>` +
    `<div class="cuerpo"><div class="nombre">${esc(t.nombre)}</div>` +
    (sub ? `<div class="sub">${esc(sub)}</div>` : "") +
    (ins.length ? `<div class="insignias">${ins.join("")}</div>` : "") + `</div>${der}</div>` +
    (t.archivada ? "" : `<div class="opciones">${opcionesHtml(t)}</div>`) + `</div>` +
    (t.favorita && !t.archivada ? CHINCHETA : "") + `</div>`;
}

function pintarLista() {
  const l = visibles();
  // hueco entre el grupo de fijadas (arriba) y el resto, si hay de los dos
  let nf = 0;
  while (nf < l.length && l[nf].favorita && !l[nf].archivada) nf++;
  if (abierta && !l.some(t => t.id === abierta && !t.archivada)) abierta = null;
  $("#lista").innerHTML = l.map((t, i) => filaHtml(t, nf > 0 && nf < l.length && i === nf - 1)).join("");
  const v = $("#vacio");
  v.hidden = l.length > 0;
  if (!l.length) {
    if ($("#buscar").value.trim()) v.textContent = tr("vacio_busqueda");
    else if (seg === "arch") v.textContent = tr("vacio_arch");
    else if (seg === "entrada") v.textContent = tr("vacio_entradas");
    else v.textContent = tr("vacio_todo");
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
  const op = e.target.closest("[data-op]");
  if (e.target.closest("[data-flecha]")) {
    e.stopPropagation();
    const abrir = abierta !== id;
    abierta = abrir ? id : null;
    document.querySelectorAll("#lista .fila.abierta").forEach(x => {
      x.classList.remove("abierta");
      const fl = x.querySelector("[data-flecha]");
      if (fl) fl.setAttribute("aria-expanded", "false");
    });
    if (abrir) {
      f.classList.add("abierta");
      f.querySelector("[data-flecha]").setAttribute("aria-expanded", "true");
    }
  } else if (op) {
    e.stopPropagation();
    await accionTarjeta(op.dataset.op, t);
  } else if (e.target.closest("[data-rest]")) {
    try { await api("/cartera/restaurar", "POST", id); await cargar(); avisar(tr("restaurada")); } catch (err) { avisar(err.message); }
  } else if (e.target.closest("[data-borrar]")) {
    if (await preguntar(tr("borrar_conf", t.nombre), tr("borrar"))) {
      try { await api("/cartera/borrar", "POST", id); await cargar(); } catch (err) { avisar(err.message); }
    }
  } else if (e.target.closest(".fila-fila")) abrirVer(id);
});

/** Lo que hacen las opciones del desplegable de cada tarjeta. */
async function accionTarjeta(op, t) {
  try {
    if (op === "fijar") {
      await api("/cartera/favorita", "POST", {id: t.id, valor: !t.favorita});
      await cargar();
    } else if (op === "editar") abrirEditor(t);
    else if (op === "contenido") abrirContenido(t);
    else if (op === "reconocer") {
      const r = await api("/cartera/reconocer", "POST", t.id);
      if (r === "no") { avisar(tr("sin_fecha")); return; }
      abrirEditor(t);
      aplicarReconocido(JSON.parse(r));
    } else if (op === "inicio") {
      if (puente) puente.anadirAcceso(t.id); else avisar(tr("solo_app"));
    } else if (op === "archivar") {
      await api(t.archivada ? "/cartera/restaurar" : "/cartera/archivar", "POST", t.id);
      avisar(t.archivada ? tr("restaurada") : tr("archivada"));
      await cargar();
    } else if (op === "borrar") {
      if (!(await preguntar(tr("borrar_conf2", t.nombre), tr("borrar")))) return;
      await api("/cartera/borrar", "POST", t.id);
      await cargar();
    }
  } catch (err) { avisar(err.message); }
}

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
  $("#vtipo").textContent = (t.clase === "entrada" ? tr("entrada") : tr("tarjeta")) + (t.archivada ? tr("tipo_archivada") : "");
  const c = contenido(t);
  const hay = t.tipo && t.tipo !== "NINGUNO" && c;
  $("#vcodigo").hidden = !hay;
  const img = $("#vimg"), err = $("#verr");
  err.hidden = true; img.hidden = false;
  img.onerror = () => { img.hidden = true; err.hidden = false; err.textContent = tr("err_dibujar"); };
  if (hay) img.src = urlCodigo(t.tipo, c, !!t.bruto);
  const n = $("#vnum");
  n.textContent = t.numero || "";
  n.hidden = !t.numero;
  n.classList.toggle("largo", (t.numero || "").length > 20);

  const info = [];
  if (t.clase === "entrada" && t.evento) {
    info.push(`<p><b>${esc(tr("cuando"))}</b> ${esc(textoEvento(t))}</p>`);
    info.push(`<p class="suave">${t.previo > 0 ? esc(tr("previo_info", t.previo)) : ""}${esc(tr("avisa_dia"))}</p>`);
  }
  if (t.caduca) {
    const cls = t._caduca === "caducada" ? "ins mal" : t._caduca === "pronto" ? "ins pronto" : "";
    info.push(`<p><b>${esc(tr("caduca_dos"))}</b> ${esc(textoCaduca(t))} ${cls ? `<span class="${cls}">${esc(t._caduca === "caducada" ? tr("caducada") : tr("caduca_pronto"))}</span>` : ""}</p>`);
  }
  if (t.nota) info.push(`<p class="nota">${esc(t.nota)}</p>`);
  $("#vinfo").innerHTML = info.join("");
  $("#vinfo").hidden = !info.length;
  pintarAvisosMal();

  const adj = [];
  for (const a of t.adjuntos || []) {
    const paginas = a.tipo === "pdf" ? Math.max(1, a.paginas || 1) : 1;
    for (let p = 0; p < paginas; p++)
      adj.push(`<figure><img loading="lazy" alt="${esc(a.nombre || tr("adjunto"))}" src="${urlAdjunto(t.id, a.f, p)}">` +
        `<figcaption>${esc(a.nombre || (a.tipo === "pdf" ? tr("pdf") : tr("imagen")))}${paginas > 1 ? esc(tr("pagina_de", p + 1, paginas)) : ""}</figcaption></figure>`);
  }
  $("#vadj").innerHTML = adj.join("");
}

$("#vvolver").onclick = cerrarVer;

// ---------------------------------------------------------------- añadir y editar

function pintarChecks(cont, opciones, seleccion) {
  const todas = opciones.slice();
  for (const d of seleccion) if (!todas.some(o => o[0] === d)) todas.push([d, tr("antes", d)]);
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
    `<div class="adjfila"><span>${a.tipo === "pdf" ? tr("pdf") : tr("imagen")}: ${esc(a.nombre || a.f)}${a.tipo === "pdf" ? esc(tr("n_pags", a.paginas || 1)) : ""}</span>` +
    `<button type="button" data-q="${i}" class="peligro">${esc(tr("quitar"))}</button></div>`).join("");
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
  $("#etitulo").textContent = nueva ? tr("nueva_tarjeta") : tr("editar_titulo");
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
$("#escanear").onclick = () => { if (puente) puente.escanear(); else avisar(tr("solo_app")); };
$("#egaleria").onclick = () => { if (puente) puente.galeria(); else avisar(tr("solo_app")); };
$("#eadjuntar").onclick = () => { if (puente) puente.adjuntar(ed.id); else avisar(tr("solo_app")); };
$("#ecancelar").onclick = cerrarEditor;

async function guardar() {
  const nombre = $("#enombre").value.trim();
  if (!nombre) { avisar(tr("pon_nombre")); $("#enombre").focus(); return; }
  const o = {
    id: ed.id, clase: ed.clase, nombre, numero: $("#enumero").value.trim(), tipo: $("#etipo").value,
    codigo: ed.codigo || "", bruto: ed.bruto || "", color: ed.color || "", nota: $("#enota").value, favorita: $("#efav").checked,
    caduca: $("#ecaduca").value || "", caducaAvisos: leerChecks($("#ecadchecks")), adjuntos: ed.adjuntos,
  };
  if (ed.clase === "entrada") {
    const dia = $("#efecha").value, hora = $("#ehora").value;
    if (!dia) { avisar(tr("pon_dia")); $("#efecha").focus(); return; }
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
    } else if (!seg.startsWith("arch")) avisar(tr("guardada"));
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
  l.textContent = tr("leido", r.resumen);
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
    txt = tr("binario", b.length, t.tipo, filas.join("\n"), t.bruto);
  } else txt = tr("texto", c.length, t.tipo, c);
  textoCopiable = txt;
  $("#conttxt").textContent = textoCopiable;
  $("#cont").hidden = false;
}
$("#contcerrar").onclick = () => { $("#cont").hidden = true; };
$("#contcopiar").onclick = async () => {
  try {
    if (puente && puente.copiar) puente.copiar(textoCopiable);
    else await navigator.clipboard.writeText(textoCopiable);
    avisar(tr("copiado"));
  } catch (e) { avisar(tr("err_copiar")); }
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
$("#bajustes").innerHTML = ICO.rueda;   // rueda SVG (conserva el aria-label)
$("#bajustes").onclick = abrirAjustes;
$("#ajvolver").onclick = cerrarAjustes;
$("#ajexportar").onclick = () => { if (puente) puente.exportar(); else avisar(tr("solo_app")); };
$("#ajotras").onclick = () => { if (puente) puente.importarOtras(); else avisar(tr("solo_app")); };
$("#ajimportar").onclick = () => { if (puente) puente.importar(); else avisar(tr("solo_app")); };

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
  if (!datos.tarjetas.some(t => t.id === id)) { avisar(tr("tarjeta_no_esta")); return; }
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
    avisar(tr("codigo_leido"));
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

traducirPagina();   // textos fijos del HTML en el idioma elegido (textos.js)
refrescarAvisos();
cargar();
