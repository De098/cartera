/* La página pide /cartera/… como si hubiera un servidor; aquí lo contesta la propia app
   (puente «Cartera» de MainActivity). Todo se queda en el móvil: no hay internet. */
(() => {
  const f0 = window.fetch.bind(window);
  const API = /^\/cartera\/(datos|ajustes|ajustes-guardar|valido|reconocer|guardar|borrar|usar|favorita|archivar|restaurar)$/;
  window.fetch = async (u, o = {}) => {
    const url = new URL(typeof u === "string" ? u : u.url, location.href);
    if (!API.test(url.pathname) || !window.Cartera) return f0(u, o);
    const r = JSON.parse(Cartera.pedir(o.method || "GET", url.pathname + url.search,
                                       o.body == null ? "" : String(o.body)));
    return new Response(r.cuerpo, {status: r.estado, headers: {"Content-Type": r.tipo}});
  };
})();
