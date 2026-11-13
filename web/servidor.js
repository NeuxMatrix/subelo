// Web de proyecto de Neux Matrix: ficha del proyecto con entrada (Google o email +
// contraseña), descargas, la app web si la hay y un panel con tareas, notas y
// documentos. Es la misma en todos los proyectos que no tienen web propia; lo que
// cambia de uno a otro está en proyecto.json.
//
// Quién entra (regla de Fer, 12/11/2026): hola@neuxmatrix.es siempre. Cualquier
// otra cuenta pregunta a la Torre (POST /interno/accesos por la red de Docker) y
// queda en espera hasta que Fer la apruebe en Torre › Sistema › Accesos.
//
// Sin base de datos: todo va en ficheros JSON dentro de DATOS_DIR (volumen).
import http from 'node:http';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFileSync, writeFileSync, renameSync, mkdirSync, existsSync, statSync, createReadStream, createWriteStream, unlinkSync, readdirSync } from 'node:fs';
import { join, resolve, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import bcrypt from 'bcryptjs';

const AQUI = dirname(fileURLToPath(import.meta.url));
const env = process.env;
export const config = {
  puerto: Number(env.PORT || 8080),
  baseUrl: String(env.BASE_URL || 'http://localhost:8080').replace(/\/$/, ''),
  secreto: env.SESSION_SECRET || '',
  datos: resolve(env.DATOS_DIR || join(AQUI, '..', 'datos')),
  descargas: resolve(env.DESCARGAS_DIR || join(AQUI, 'descargas')),
  appWeb: resolve(env.APP_WEB_DIR || join(AQUI, 'app-web')),
  googleId: env.GOOGLE_CLIENT_ID || '',
  googleSecreto: env.GOOGLE_CLIENT_SECRET || '',
  torre: String(env.TORRE_INTERNA || 'http://torre:8080').replace(/\/$/, ''),
  proyecto: JSON.parse(readFileSync(env.PROYECTO_JSON || join(AQUI, 'proyecto.json'), 'utf8')),
};
const P = config.proyecto;
export const DUENO = 'hola@neuxmatrix.es';
const SESION = 'sesion';
const ESTADO_GOOGLE = 'estado_google';
const DIAS_SESION = 30;
const MAX_DOC = 25 * 1024 * 1024;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const seguro = config.baseUrl.startsWith('https://');

if (!config.secreto || config.secreto.length < 32) {
  if (env.NODE_ENV === 'production') throw new Error('Falta SESSION_SECRET (al menos 32 caracteres)');
  config.secreto = randomBytes(32).toString('hex');
}

// ---------------- Ficheros de datos ----------------
mkdirSync(join(config.datos, 'documentos'), { recursive: true });
function leer(nombre, porDefecto) {
  const f = join(config.datos, nombre);
  if (!existsSync(f)) return structuredClone(porDefecto);
  try { return JSON.parse(readFileSync(f, 'utf8')); } catch { return structuredClone(porDefecto); }
}
function guardar(nombre, datos) {
  const f = join(config.datos, nombre);
  writeFileSync(`${f}.nuevo`, JSON.stringify(datos, null, 2));
  renameSync(`${f}.nuevo`, f);
}
const usuarios = () => leer('usuarios.json', { usuarios: [], enlaces: {}, olvidos: [] });
const panel = () => leer('panel.json', {
  tareas: (P.tareasIniciales || []).map((texto, i) => ({ id: `t${i + 1}`, texto, hecha: false, creada: Date.now() })),
  notas: P.notasIniciales || '',
  documentos: [],
});
const id = (n = 12) => randomBytes(n).toString('base64url');

// ---------------- Firmas y cookies ----------------
const firma = (v) => createHmac('sha256', config.secreto).update(v).digest('base64url');
function firmar(v) { return `${Buffer.from(v).toString('base64url')}.${firma(v)}`; }
function abrir(s) {
  if (!s || !s.includes('.')) return null;
  const [b, f] = s.split('.');
  const v = Buffer.from(b, 'base64url').toString();
  const a = Buffer.from(firma(v)); const c = Buffer.from(f);
  return a.length === c.length && timingSafeEqual(a, c) ? v : null;
}
function cookies(req) {
  const out = {};
  for (const p of String(req.headers.cookie || '').split(';')) {
    const i = p.indexOf('=');
    if (i > 0) out[p.slice(0, i).trim()] = decodeURIComponent(p.slice(i + 1).trim());
  }
  return out;
}
function ponerCookie(res, nombre, valor, segundos) {
  const partes = [`${nombre}=${encodeURIComponent(valor)}`, 'Path=/', 'HttpOnly', 'SameSite=Lax', `Max-Age=${segundos}`];
  if (seguro) partes.push('Secure');
  const previas = res.getHeader('Set-Cookie') || [];
  res.setHeader('Set-Cookie', [...previas, partes.join('; ')]);
}

function usuarioDe(req) {
  const v = abrir(cookies(req)[SESION]);
  if (!v) return null;
  let s; try { s = JSON.parse(v); } catch { return null; }
  if (!s.u || Date.now() - s.t > DIAS_SESION * 864e5) return null;
  const u = usuarios().usuarios.find((x) => x.id === s.u);
  if (!u || (u.version || 0) !== s.v || !(u.email === DUENO || u.aprobado)) return null;
  return u;
}
function abrirSesion(res, u) {
  ponerCookie(res, SESION, firmar(JSON.stringify({ u: u.id, v: u.version || 0, t: Date.now() })), DIAS_SESION * 86400);
}

// ---------------- La Torre decide quién entra ----------------
export async function accesoTorre(email, nombre, nota) {
  if (email === DUENO) return 'aprobado';
  try {
    const r = await fetch(`${config.torre}/interno/accesos`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ app: P.clave, email, nombre: [nombre, nota].filter(Boolean).join(' ') || undefined }),
      signal: AbortSignal.timeout(8000),
    });
    if (!r.ok) return 'error';
    const { estado } = await r.json();
    return ['aprobado', 'pendiente', 'rechazado'].includes(estado) ? estado : 'error';
  } catch {
    return 'error';
  }
}
/** Decide si la cuenta entra ahora. Devuelve null si entra o el código de error. */
async function decidir(u, nota) {
  if (u.email === DUENO) return null;
  const estado = await accesoTorre(u.email, u.nombre, nota);
  const datos = usuarios();
  const x = datos.usuarios.find((y) => y.id === u.id);
  if (estado === 'aprobado') { if (x) { x.aprobado = true; guardar('usuarios.json', datos); } u.aprobado = true; return null; }
  if (estado === 'rechazado') { if (x && x.aprobado) { x.aprobado = false; guardar('usuarios.json', datos); } return 'no_autorizado'; }
  if (estado === 'error' && u.aprobado) return null; // la Torre no contesta: quien ya estaba aprobado sigue entrando
  return estado === 'pendiente' ? 'pendiente' : 'torre';
}

// ---------------- Límite de intentos ----------------
const intentos = new Map();
function demasiados(req, cupo = 20) {
  const ip = String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
  const ahora = Date.now();
  const lista = (intentos.get(ip) || []).filter((t) => ahora - t < 15 * 60e3);
  lista.push(ahora);
  intentos.set(ip, lista);
  return lista.length > cupo;
}

// ---------------- HTML ----------------
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ERRORES = {
  datos: 'Email o contraseña incorrectos.',
  pendiente: 'Tu cuenta está pendiente: Fer tiene que aprobarla. Vuelve a entrar cuando te avise.',
  no_autorizado: 'Esta cuenta no tiene acceso a esta web.',
  torre: 'Ahora mismo no se puede comprobar el acceso. Prueba dentro de un rato.',
  google: 'No se pudo entrar con Google. Prueba otra vez.',
  google_off: 'El acceso con Google todavía no está configurado.',
  intentos: 'Demasiados intentos. Espera unos minutos.',
  existe: 'Ya hay una cuenta con ese email. Entra con ella.',
  dueno: 'Esa cuenta entra con «Continuar con Google».',
  email: 'Ese email no parece válido.',
  corta: 'La contraseña necesita al menos 10 caracteres.',
  nombre: 'Pon tu nombre.',
  enlace: 'Este enlace ya no vale. Pide otro.',
  actual: 'La contraseña actual no es correcta.',
};
const AVISOS = {
  olvido: 'Si hay una cuenta con ese email, Fer ya tiene el aviso y te pasará un enlace para poner otra contraseña. Si es una cuenta de Google, entra con «Continuar con Google».',
  creada: 'Cuenta creada. Fer tiene que aprobarla: vuelve a entrar cuando te avise.',
  contrasena: 'Contraseña guardada.',
  salir: 'Has cerrado la sesión.',
  todo: 'Sesión cerrada en todos los dispositivos.',
};
function pagina(titulo, cuerpo, { usuario = null, estrecha = false } = {}) {
  const color = /^#[0-9a-f]{6}$/i.test(P.color || '') ? P.color : '#3b5bdb';
  return `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow"><meta name="color-scheme" content="light dark">
<title>${esc(titulo)}</title>
<style>
:root{--fondo:#f4f5f8;--caja:#fff;--borde:#dfe3ea;--tinta:#151922;--suave:#5d6575;--marca:${color};--ok:#127a52;--mal:#b42318}
@media (prefers-color-scheme:dark){:root{--fondo:#0f1218;--caja:#181c24;--borde:#2a303c;--tinta:#e9ecf2;--suave:#9aa3b2}}
*{box-sizing:border-box}body{margin:0;background:var(--fondo);color:var(--tinta);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
a{color:var(--marca)}header{background:var(--caja);border-bottom:1px solid var(--borde)}
.barra{max-width:980px;margin:0 auto;padding:12px 16px;display:flex;gap:12px;align-items:center;justify-content:space-between}
.marca{display:flex;gap:10px;align-items:center;font-weight:700;text-decoration:none;color:var(--tinta)}
.punto{width:14px;height:14px;border-radius:4px;background:var(--marca)}
main{max-width:${estrecha ? '420px' : '980px'};margin:0 auto;padding:24px 16px 48px}
.caja{background:var(--caja);border:1px solid var(--borde);border-radius:12px;padding:20px;margin-bottom:16px}
h1{font-size:1.6rem;margin:0 0 4px}h2{font-size:1.1rem;margin:0 0 12px}.suave{color:var(--suave)}
.rejilla{display:grid;gap:16px;grid-template-columns:repeat(auto-fit,minmax(280px,1fr))}
label{display:block;font-size:.9rem;margin:10px 0 4px}input,textarea{width:100%;padding:10px 12px;border:1px solid var(--borde);border-radius:8px;background:var(--fondo);color:var(--tinta);font:inherit}
textarea{min-height:160px;resize:vertical}
.boton{display:inline-flex;align-items:center;justify-content:center;gap:8px;padding:10px 16px;border-radius:8px;border:1px solid var(--marca);background:var(--marca);color:#fff;font:inherit;font-weight:600;cursor:pointer;text-decoration:none}
.boton.claro{background:transparent;color:var(--marca)}.boton.ancho{width:100%}.boton.peq{padding:4px 10px;font-size:.85rem}
.boton.google{background:#fff;color:#1f1f1f;border-color:#dadce0}
.aviso{padding:10px 12px;border-radius:8px;margin-bottom:12px;background:color-mix(in srgb,var(--ok) 12%,transparent);border:1px solid color-mix(in srgb,var(--ok) 40%,transparent)}
.error{background:color-mix(in srgb,var(--mal) 10%,transparent);border-color:color-mix(in srgb,var(--mal) 40%,transparent)}
.separa{display:flex;align-items:center;gap:8px;color:var(--suave);margin:16px 0;font-size:.9rem}.separa:before,.separa:after{content:"";flex:1;border-top:1px solid var(--borde)}
details{margin-top:12px}summary{cursor:pointer;color:var(--marca)}
ul.lista{list-style:none;margin:0;padding:0}ul.lista li{display:flex;gap:8px;align-items:center;justify-content:space-between;padding:8px 0;border-bottom:1px solid var(--borde)}
ul.lista li:last-child{border-bottom:0}.hecha{text-decoration:line-through;color:var(--suave)}
.fila{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.fila input{flex:1;min-width:160px}
form.en-linea{display:inline}.chip{font-size:.8rem;padding:2px 8px;border-radius:99px;border:1px solid var(--borde);color:var(--suave)}
code{background:var(--fondo);padding:2px 6px;border-radius:4px;word-break:break-all}
</style></head><body>
<header><div class="barra"><a class="marca" href="/"><span class="punto"></span>${esc(P.nombre)}</a>
${usuario ? `<nav class="fila"><a href="/cuenta">${esc(usuario.nombre || usuario.email)}</a><form class="en-linea" method="post" action="/salir"><button class="boton claro peq">Cerrar sesión</button></form></nav>` : ''}
</div></header>
<main>${cuerpo}</main></body></html>`;
}
function mensajes(q) {
  const e = ERRORES[q.get('error')]; const a = AVISOS[q.get('aviso')];
  return `${e ? `<div class="aviso error">${esc(e)}</div>` : ''}${a ? `<div class="aviso">${esc(a)}</div>` : ''}`;
}
const googleOn = () => Boolean(config.googleId && config.googleSecreto);

function paginaEntrar(q) {
  const google = googleOn()
    ? `<a class="boton google ancho" href="/api/auth/oauth/google"><svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true"><path fill="#EA4335" d="M24 9.5c3.5 0 6.6 1.2 9 3.6l6.7-6.7C35.6 2.4 30.2 0 24 0 14.6 0 6.6 5.4 2.6 13.3l7.8 6C12.3 13.5 17.7 9.5 24 9.5z"/><path fill="#4285F4" d="M46.5 24.5c0-1.6-.1-3.1-.4-4.5H24v9h12.7c-.6 3-2.3 5.5-4.8 7.2l7.5 5.8c4.4-4 7.1-10 7.1-17.5z"/><path fill="#FBBC05" d="M10.4 28.7c-.5-1.4-.8-3-.8-4.7s.3-3.3.8-4.7l-7.8-6C1 16.5 0 20.1 0 24s1 7.5 2.6 10.7l7.8-6z"/><path fill="#34A853" d="M24 48c6.5 0 11.9-2.1 15.9-5.8l-7.5-5.8c-2.1 1.4-4.8 2.3-8.4 2.3-6.3 0-11.7-4-13.6-9.6l-7.8 6C6.6 42.6 14.6 48 24 48z"/></svg>Continuar con Google</a>
       <div class="separa">o con tu email</div>`
    : '';
  return pagina(`Entrar · ${P.nombre}`, `
<div class="caja">
  <h1>${esc(P.nombre)}</h1>
  <p class="suave">Web privada. Entra con tu cuenta.</p>
  ${mensajes(q)}
  ${google}
  <form method="post" action="/entrar">
    <label for="e">Email</label><input id="e" name="email" type="email" autocomplete="email" required>
    <label for="c">Contraseña</label><input id="c" name="contrasena" type="password" autocomplete="current-password" required>
    <p><button class="boton ancho">Entrar</button></p>
  </form>
  <details><summary>He olvidado mi contraseña</summary>
    <form method="post" action="/olvido"><label for="oe">Tu email</label><input id="oe" name="email" type="email" required>
    <p><button class="boton claro ancho">Avisar para poner otra</button></p></form>
  </details>
  <details><summary>Crear una cuenta</summary>
    <form method="post" action="/registro">
      <label for="rn">Nombre</label><input id="rn" name="nombre" autocomplete="name" required>
      <label for="re">Email</label><input id="re" name="email" type="email" autocomplete="email" required>
      <label for="rc">Contraseña (mínimo 10 caracteres)</label><input id="rc" name="contrasena" type="password" minlength="10" autocomplete="new-password" required>
      <p><button class="boton claro ancho">Crear cuenta</button></p>
      <p class="suave">Las cuentas nuevas esperan a que Fer las apruebe.</p>
    </form>
  </details>
</div>`, { estrecha: true });
}

function tamano(n) { return n > 1048576 ? `${(n / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`; }
function fecha(t) { return new Date(t).toLocaleString('es-ES', { timeZone: 'Europe/Madrid', dateStyle: 'short', timeStyle: 'short' }); }
function descargasDisponibles() {
  return (P.descargas || []).map((d) => {
    const f = join(config.descargas, d.archivo);
    return existsSync(f) ? { ...d, bytes: statSync(f).size, fecha: statSync(f).mtimeMs } : { ...d, falta: true };
  });
}
function paginaFicha(u, q) {
  const datos = panel();
  const secciones = (P.secciones || []).map((s) => `<div class="caja"><h2>${esc(s.titulo)}</h2>${(Array.isArray(s.texto) ? s.texto : [s.texto]).map((t) => `<p>${esc(t)}</p>`).join('')}</div>`).join('');
  const appWeb = P.appWeb && existsSync(join(config.appWeb, 'index.html'))
    ? `<div class="caja"><h2>${esc(P.appWeb.titulo || 'La app')}</h2><p>${esc(P.appWeb.texto || '')}</p><a class="boton" href="/app/">${esc(P.appWeb.boton || 'Abrir la app')}</a></div>` : '';
  const desc = descargasDisponibles();
  const descargas = desc.length ? `<div class="caja"><h2>Descargas</h2><ul class="lista">${desc.map((d) => `<li><div><strong>${esc(d.titulo)}</strong><div class="suave">${esc(d.texto || '')}${d.falta ? ' · Todavía no hay versión publicada.' : ` · ${tamano(d.bytes)} · ${fecha(d.fecha)}`}</div></div>${d.falta ? '' : `<a class="boton peq" href="/descargar/${encodeURIComponent(d.archivo)}">Descargar</a>`}</li>`).join('')}</ul></div>` : '';
  const enlaces = (P.enlaces || []).length ? `<div class="caja"><h2>Enlaces</h2><ul class="lista">${P.enlaces.map((e) => `<li><a href="${esc(e.url)}" rel="noopener" target="_blank">${esc(e.titulo)}</a><span class="suave">${esc(e.texto || '')}</span></li>`).join('')}</ul></div>` : '';
  const tareas = `<div class="caja"><h2>Tareas</h2>
    <form method="post" action="/panel/tareas" class="fila"><input name="texto" placeholder="Nueva tarea" maxlength="300" required><button class="boton peq">Añadir</button></form>
    <ul class="lista">${datos.tareas.map((t) => `<li><form class="en-linea" method="post" action="/panel/tareas/${esc(t.id)}/hecha"><label class="fila" style="margin:0"><input type="checkbox" style="width:auto" ${t.hecha ? 'checked' : ''} onchange="this.form.submit()"><span class="${t.hecha ? 'hecha' : ''}">${esc(t.texto)}</span></label></form><form class="en-linea" method="post" action="/panel/tareas/${esc(t.id)}/borrar"><button class="boton claro peq" title="Borrar">✕</button></form></li>`).join('') || '<li class="suave">Sin tareas.</li>'}</ul></div>`;
  const notas = `<div class="caja"><h2>Notas</h2><form method="post" action="/panel/notas"><textarea name="texto" maxlength="50000">${esc(datos.notas)}</textarea><p><button class="boton peq">Guardar notas</button></p></form></div>`;
  const documentos = `<div class="caja"><h2>Documentos</h2>
    <div class="fila"><input type="file" id="subir" multiple><span class="suave" id="estado">Máximo 25 MB por archivo.</span></div>
    <ul class="lista" style="margin-top:8px">${datos.documentos.map((d) => `<li><div><a href="/panel/documentos/${esc(d.id)}">${esc(d.nombre)}</a><div class="suave">${tamano(d.bytes)} · ${fecha(d.subido)} · ${esc(d.por || '')}</div></div><form class="en-linea" method="post" action="/panel/documentos/${esc(d.id)}/borrar" onsubmit="return confirm('¿Borrar este documento?')"><button class="boton claro peq">Borrar</button></form></li>`).join('') || '<li class="suave">Todavía no hay documentos.</li>'}</ul>
    <script>
    document.getElementById('subir').addEventListener('change', async (e) => {
      const est = document.getElementById('estado');
      for (const f of e.target.files) {
        if (f.size > ${MAX_DOC}) { est.textContent = f.name + ' pasa de 25 MB'; continue; }
        est.textContent = 'Subiendo ' + f.name + '…';
        const r = await fetch('/panel/documentos?nombre=' + encodeURIComponent(f.name), { method: 'PUT', body: f, headers: { 'content-type': f.type || 'application/octet-stream' } });
        if (!r.ok) { est.textContent = 'No se pudo subir ' + f.name; return; }
      }
      location.reload();
    });
    </script></div>`;
  const panelHtml = P.panel === false ? '' : `<div class="rejilla">${tareas}${notas}</div>${documentos}`;
  return pagina(P.nombre, `${mensajes(q)}
<div class="caja"><h1>${esc(P.nombre)}</h1>${P.subtitulo ? `<p class="suave" style="margin:0 0 8px">${esc(P.subtitulo)}</p>` : ''}<p>${esc(P.descripcion || '')}</p></div>
${appWeb}${descargas}${secciones}${panelHtml}${enlaces}`, { usuario: u });
}

function paginaCuenta(u, q, extra = '') {
  const datos = usuarios();
  const dueno = u.email === DUENO;
  const gente = dueno ? `<div class="caja"><h2>Cuentas</h2>
    <p class="suave">Quién puede entrar lo decides en la Torre (Sistema › Accesos). Aquí puedes dar a alguien un enlace de un solo uso para poner otra contraseña, o borrar su cuenta.</p>
    <ul class="lista">${datos.usuarios.filter((x) => x.email !== DUENO).map((x) => {
      const olvido = datos.olvidos.find((o) => o.id === x.id);
      return `<li><div><strong>${esc(x.nombre || x.email)}</strong> <span class="chip">${x.aprobado ? 'aprobada' : 'pendiente'}</span>${olvido ? ' <span class="chip">pidió otra contraseña</span>' : ''}<div class="suave">${esc(x.email)}${x.google ? ' · Google' : ''}${x.hash ? ' · contraseña' : ''}</div></div>
      <div class="fila"><form class="en-linea" method="post" action="/cuenta/usuarios/${esc(x.id)}/enlace"><button class="boton claro peq">Enlace de contraseña</button></form>
      <form class="en-linea" method="post" action="/cuenta/usuarios/${esc(x.id)}/borrar" onsubmit="return confirm('¿Borrar esta cuenta?')"><button class="boton claro peq">Borrar</button></form></div></li>`;
    }).join('') || '<li class="suave">Solo entras tú.</li>'}</ul></div>` : '';
  return pagina(`Tu cuenta · ${P.nombre}`, `${mensajes(q)}${extra}
<div class="caja"><h1>Tu cuenta</h1><p class="suave">${esc(u.email)}${u.google ? ' · entras con Google' : ''}</p>
  <h2 style="margin-top:16px">${u.hash ? 'Cambiar la contraseña' : 'Poner una contraseña'}</h2>
  <form method="post" action="/cuenta/contrasena">
    ${u.hash ? '<label for="a">Contraseña actual</label><input id="a" name="actual" type="password" autocomplete="current-password" required>' : ''}
    <label for="n">Nueva contraseña (mínimo 10 caracteres)</label><input id="n" name="nueva" type="password" minlength="10" autocomplete="new-password" required>
    <p><button class="boton peq">Guardar</button></p>
  </form>
  <form method="post" action="/cuenta/cerrar-todo"><button class="boton claro peq">Cerrar sesión en todos los dispositivos</button></form>
</div>${gente}`, { usuario: u });
}

// ---------------- Utilidades HTTP ----------------
function enviar(res, codigo, cuerpo, tipo = 'text/html; charset=utf-8') {
  res.writeHead(codigo, { 'content-type': tipo });
  res.end(cuerpo);
}
function redirigir(res, a) { res.writeHead(303, { location: a }); res.end(); }
function leerCuerpo(req, max = 100 * 1024) {
  return new Promise((ok, mal) => {
    let n = 0; const trozos = [];
    req.on('data', (c) => { n += c.length; if (n > max) { mal(new Error('grande')); req.destroy(); } else trozos.push(c); });
    req.on('end', () => ok(Buffer.concat(trozos)));
    req.on('error', mal);
  });
}
async function formulario(req) { return new URLSearchParams((await leerCuerpo(req)).toString()); }
/** Las peticiones que cambian algo tienen que venir de esta misma web. */
function mismoOrigen(req) {
  const o = req.headers.origin || req.headers.referer;
  if (!o) return false;
  try { return new URL(o).host === req.headers.host; } catch { return false; }
}
const TIPOS = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ico': 'image/x-icon', '.apk': 'application/vnd.android.package-archive', '.pdf': 'application/pdf' };
function servirFichero(res, f, extraCabeceras = {}) {
  if (!existsSync(f) || !statSync(f).isFile()) return enviar(res, 404, 'No existe', 'text/plain; charset=utf-8');
  res.writeHead(200, { 'content-type': TIPOS[extname(f).toLowerCase()] || 'application/octet-stream', 'content-length': statSync(f).size, 'cache-control': 'private, no-cache', ...extraCabeceras });
  createReadStream(f).pipe(res);
}
function dentro(base, ruta) {
  const f = resolve(base, `.${ruta}`);
  return f === base || f.startsWith(base + '/') ? f : null;
}

// ---------------- Rutas ----------------
export async function atender(req, res) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'same-origin');
  const url = new URL(req.url, config.baseUrl);
  const ruta = url.pathname;
  const q = url.searchParams;
  const metodo = req.method;

  if (ruta === '/salud') return enviar(res, 200, JSON.stringify({ ok: true, app: P.clave, google: googleOn() }), 'application/json');
  if (metodo !== 'GET' && metodo !== 'HEAD' && !mismoOrigen(req)) return enviar(res, 403, 'Origen no permitido', 'text/plain; charset=utf-8');

  // --- Entrar, registrarse, olvido ---
  if (ruta === '/entrar' && metodo === 'GET') return enviar(res, 200, paginaEntrar(q));
  if (ruta === '/entrar' && metodo === 'POST') {
    if (demasiados(req)) return redirigir(res, '/entrar?error=intentos');
    const f = await formulario(req);
    const email = String(f.get('email') || '').trim().toLowerCase();
    const u = usuarios().usuarios.find((x) => x.email === email);
    if (!u || !u.hash || !(await bcrypt.compare(String(f.get('contrasena') || '').slice(0, 200), u.hash))) return redirigir(res, '/entrar?error=datos');
    const no = await decidir(u);
    if (no) return redirigir(res, `/entrar?error=${no}`);
    abrirSesion(res, u);
    return redirigir(res, '/');
  }
  if (ruta === '/registro' && metodo === 'POST') {
    if (demasiados(req, 10)) return redirigir(res, '/entrar?error=intentos');
    const f = await formulario(req);
    const nombre = String(f.get('nombre') || '').trim().slice(0, 80);
    const email = String(f.get('email') || '').trim().toLowerCase().slice(0, 200);
    const contrasena = String(f.get('contrasena') || '');
    if (!nombre) return redirigir(res, '/entrar?error=nombre');
    if (!EMAIL_RE.test(email)) return redirigir(res, '/entrar?error=email');
    if (contrasena.length < 10 || contrasena.length > 200) return redirigir(res, '/entrar?error=corta');
    // La cuenta de Fer solo se crea con Google: así nadie puede quedarse con ella poniendo su email.
    if (email === DUENO) return redirigir(res, '/entrar?error=dueno');
    const datos = usuarios();
    if (datos.usuarios.some((x) => x.email === email)) return redirigir(res, '/entrar?error=existe');
    const u = { id: id(), email, nombre, hash: await bcrypt.hash(contrasena, 12), google: false, aprobado: false, version: 0, creado: Date.now() };
    datos.usuarios.push(u);
    guardar('usuarios.json', datos);
    const no = await decidir(u, '(registro con contraseña: email sin comprobar)');
    if (no === 'pendiente' || no === 'torre') return redirigir(res, '/entrar?aviso=creada');
    if (no) return redirigir(res, `/entrar?error=${no}`);
    abrirSesion(res, u);
    return redirigir(res, '/');
  }
  if (ruta === '/olvido' && metodo === 'POST') {
    if (demasiados(req, 10)) return redirigir(res, '/entrar?error=intentos');
    const email = String((await formulario(req)).get('email') || '').trim().toLowerCase();
    const datos = usuarios();
    const u = datos.usuarios.find((x) => x.email === email);
    if (u && u.email !== DUENO && !datos.olvidos.some((o) => o.id === u.id)) {
      datos.olvidos.push({ id: u.id, cuando: Date.now() });
      guardar('usuarios.json', datos);
    }
    // Misma respuesta exista o no la cuenta.
    return redirigir(res, '/entrar?aviso=olvido');
  }
  const mRest = ruta.match(/^\/restablecer\/([A-Za-z0-9_-]{16,64})$/);
  if (mRest) {
    const datos = usuarios();
    const enl = datos.enlaces[mRest[1]];
    if (!enl || enl.caduca < Date.now()) return enviar(res, 400, pagina('Enlace caducado', `<div class="caja"><div class="aviso error">${ERRORES.enlace}</div><a href="/entrar">Volver</a></div>`, { estrecha: true }));
    if (metodo === 'GET') {
      return enviar(res, 200, pagina('Nueva contraseña', `<div class="caja"><h1>Nueva contraseña</h1>${mensajes(q)}<form method="post"><label for="n">Contraseña (mínimo 10 caracteres)</label><input id="n" name="nueva" type="password" minlength="10" autocomplete="new-password" required><p><button class="boton ancho">Guardar y entrar</button></p></form></div>`, { estrecha: true }));
    }
    const nueva = String((await formulario(req)).get('nueva') || '');
    if (nueva.length < 10 || nueva.length > 200) return redirigir(res, `${ruta}?error=corta`);
    const u = datos.usuarios.find((x) => x.id === enl.id);
    delete datos.enlaces[mRest[1]];
    if (!u) { guardar('usuarios.json', datos); return redirigir(res, '/entrar?error=enlace'); }
    u.hash = await bcrypt.hash(nueva, 12);
    u.version = (u.version || 0) + 1;
    datos.olvidos = datos.olvidos.filter((o) => o.id !== u.id);
    guardar('usuarios.json', datos);
    const no = await decidir(u);
    if (no) return redirigir(res, `/entrar?error=${no}`);
    abrirSesion(res, u);
    return redirigir(res, '/');
  }

  // --- Google ---
  if (ruta === '/api/auth/oauth/google') {
    if (!googleOn()) return redirigir(res, '/entrar?error=google_off');
    const estado = id(18);
    ponerCookie(res, ESTADO_GOOGLE, firmar(estado), 600);
    const g = new URL('https://accounts.google.com/o/oauth2/v2/auth');
    g.search = new URLSearchParams({ client_id: config.googleId, redirect_uri: `${config.baseUrl}/api/auth/oauth/google/callback`, response_type: 'code', scope: 'openid email profile', state: estado, prompt: 'select_account' }).toString();
    return redirigir(res, g.toString());
  }
  if (ruta === '/api/auth/oauth/google/callback') {
    const esperado = abrir(cookies(req)[ESTADO_GOOGLE]);
    ponerCookie(res, ESTADO_GOOGLE, '', 0);
    if (!q.get('code') || !q.get('state') || q.get('state') !== esperado) return redirigir(res, '/entrar?error=google');
    try {
      const t = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ code: q.get('code'), client_id: config.googleId, client_secret: config.googleSecreto, redirect_uri: `${config.baseUrl}/api/auth/oauth/google/callback`, grant_type: 'authorization_code' }),
      });
      if (!t.ok) throw new Error(`token ${t.status}`);
      const { access_token: token } = await t.json();
      const p = await fetch('https://openidconnect.googleapis.com/v1/userinfo', { headers: { authorization: `Bearer ${token}` } });
      if (!p.ok) throw new Error(`perfil ${p.status}`);
      const perfil = await p.json();
      const email = String(perfil.email || '').trim().toLowerCase();
      if (!EMAIL_RE.test(email) || perfil.email_verified === false) throw new Error('email sin verificar');
      const datos = usuarios();
      let u = datos.usuarios.find((x) => x.email === email);
      if (!u) {
        // Se apunta aunque quede pendiente: así nadie puede crear después esa cuenta con contraseña.
        u = { id: id(), email, nombre: String(perfil.name || email.split('@')[0]).slice(0, 80), hash: null, google: true, aprobado: email === DUENO, version: 0, creado: Date.now() };
        datos.usuarios.push(u);
      } else u.google = true;
      guardar('usuarios.json', datos);
      const no = await decidir(u);
      if (no) return redirigir(res, `/entrar?error=${no}`);
      abrirSesion(res, u);
      return redirigir(res, '/');
    } catch (e) {
      console.error('[google]', e.message);
      return redirigir(res, '/entrar?error=google');
    }
  }

  // --- A partir de aquí, solo con sesión ---
  const u = usuarioDe(req);
  if (!u) {
    if (metodo === 'GET' && !ruta.startsWith('/panel/')) return redirigir(res, '/entrar');
    return enviar(res, 401, 'Sin sesión', 'text/plain; charset=utf-8');
  }

  if (ruta === '/salir' && metodo === 'POST') { ponerCookie(res, SESION, '', 0); return redirigir(res, '/entrar?aviso=salir'); }
  if (ruta === '/' && metodo === 'GET') return enviar(res, 200, paginaFicha(u, q));

  if (ruta.startsWith('/app/') && metodo === 'GET' && P.appWeb) {
    const f = dentro(config.appWeb, decodeURIComponent(ruta.slice(4)) === '/' ? '/index.html' : decodeURIComponent(ruta.slice(4)));
    return f ? servirFichero(res, f) : enviar(res, 404, 'No existe', 'text/plain; charset=utf-8');
  }
  if (ruta === '/app' && P.appWeb) return redirigir(res, '/app/');
  const mDesc = ruta.match(/^\/descargar\/([^/]+)$/);
  if (mDesc && metodo === 'GET') {
    const d = (P.descargas || []).find((x) => x.archivo === decodeURIComponent(mDesc[1]));
    if (!d) return enviar(res, 404, 'No existe', 'text/plain; charset=utf-8');
    return servirFichero(res, join(config.descargas, d.archivo), { 'content-disposition': `attachment; filename="${d.archivo}"` });
  }

  // --- Cuenta ---
  if (ruta === '/cuenta' && metodo === 'GET') return enviar(res, 200, paginaCuenta(u, q));
  if (ruta === '/cuenta/contrasena' && metodo === 'POST') {
    const f = await formulario(req);
    const datos = usuarios(); const x = datos.usuarios.find((y) => y.id === u.id);
    if (x.hash && !(await bcrypt.compare(String(f.get('actual') || '').slice(0, 200), x.hash))) return redirigir(res, '/cuenta?error=actual');
    const nueva = String(f.get('nueva') || '');
    if (nueva.length < 10 || nueva.length > 200) return redirigir(res, '/cuenta?error=corta');
    x.hash = await bcrypt.hash(nueva, 12);
    x.version = (x.version || 0) + 1;
    guardar('usuarios.json', datos);
    abrirSesion(res, x);
    return redirigir(res, '/cuenta?aviso=contrasena');
  }
  if (ruta === '/cuenta/cerrar-todo' && metodo === 'POST') {
    const datos = usuarios(); const x = datos.usuarios.find((y) => y.id === u.id);
    x.version = (x.version || 0) + 1;
    guardar('usuarios.json', datos);
    ponerCookie(res, SESION, '', 0);
    return redirigir(res, '/entrar?aviso=todo');
  }
  const mUsu = ruta.match(/^\/cuenta\/usuarios\/([A-Za-z0-9_-]+)\/(enlace|borrar)$/);
  if (mUsu && metodo === 'POST') {
    if (u.email !== DUENO) return enviar(res, 403, 'Solo Fer', 'text/plain; charset=utf-8');
    const datos = usuarios();
    const x = datos.usuarios.find((y) => y.id === mUsu[1] && y.email !== DUENO);
    if (!x) return redirigir(res, '/cuenta');
    if (mUsu[2] === 'borrar') {
      datos.usuarios = datos.usuarios.filter((y) => y.id !== x.id);
      datos.olvidos = datos.olvidos.filter((o) => o.id !== x.id);
      for (const [k, v] of Object.entries(datos.enlaces)) if (v.id === x.id) delete datos.enlaces[k];
      guardar('usuarios.json', datos);
      return redirigir(res, '/cuenta');
    }
    const token = id(24);
    for (const [k, v] of Object.entries(datos.enlaces)) if (v.id === x.id || v.caduca < Date.now()) delete datos.enlaces[k];
    datos.enlaces[token] = { id: x.id, caduca: Date.now() + 24 * 3600e3 };
    guardar('usuarios.json', datos);
    const enlace = `${config.baseUrl}/restablecer/${token}`;
    return enviar(res, 200, paginaCuenta(u, q, `<div class="aviso">Pásale este enlace a ${esc(x.nombre || x.email)} (vale 24 horas y una sola vez):<br><code>${esc(enlace)}</code></div>`));
  }

  // --- Panel: tareas, notas y documentos ---
  if (ruta.startsWith('/panel/') && P.panel !== false) {
    if (ruta === '/panel/tareas' && metodo === 'POST') {
      const texto = String((await formulario(req)).get('texto') || '').trim().slice(0, 300);
      if (texto) { const d = panel(); d.tareas.push({ id: id(6), texto, hecha: false, creada: Date.now() }); guardar('panel.json', d); }
      return redirigir(res, '/');
    }
    const mTarea = ruta.match(/^\/panel\/tareas\/([A-Za-z0-9_-]+)\/(hecha|borrar)$/);
    if (mTarea && metodo === 'POST') {
      await leerCuerpo(req);
      const d = panel();
      if (mTarea[2] === 'borrar') d.tareas = d.tareas.filter((t) => t.id !== mTarea[1]);
      else { const t = d.tareas.find((x) => x.id === mTarea[1]); if (t) t.hecha = !t.hecha; }
      guardar('panel.json', d);
      return redirigir(res, '/');
    }
    if (ruta === '/panel/notas' && metodo === 'POST') {
      const d = panel(); d.notas = String((await formulario(req)).get('texto') || '').slice(0, 50000); guardar('panel.json', d);
      return redirigir(res, '/');
    }
    if (ruta === '/panel/documentos' && metodo === 'PUT') {
      const nombre = String(q.get('nombre') || 'documento').replace(/[\\/\0\r\n"]/g, '_').slice(0, 150) || 'documento';
      if (Number(req.headers['content-length'] || 0) > MAX_DOC) return enviar(res, 413, 'Pasa de 25 MB', 'text/plain; charset=utf-8');
      const doc = { id: id(), nombre, bytes: 0, subido: Date.now(), por: u.nombre || u.email };
      const f = join(config.datos, 'documentos', doc.id);
      await new Promise((ok, mal) => {
        const out = createWriteStream(f);
        req.on('data', (c) => { doc.bytes += c.length; if (doc.bytes > MAX_DOC) { req.destroy(); out.destroy(); mal(new Error('grande')); } });
        req.pipe(out); out.on('finish', ok); out.on('error', mal); req.on('error', mal);
      }).catch(() => { try { unlinkSync(f); } catch { /* ya no está */ } doc.bytes = -1; });
      if (doc.bytes < 0) return enviar(res, 413, 'Pasa de 25 MB', 'text/plain; charset=utf-8');
      const d = panel(); d.documentos.unshift(doc); guardar('panel.json', d);
      return enviar(res, 201, JSON.stringify({ ok: true, id: doc.id }), 'application/json');
    }
    const mDoc = ruta.match(/^\/panel\/documentos\/([A-Za-z0-9_-]+)(\/borrar)?$/);
    if (mDoc) {
      const d = panel();
      const doc = d.documentos.find((x) => x.id === mDoc[1]);
      if (!doc) return enviar(res, 404, 'No existe', 'text/plain; charset=utf-8');
      if (!mDoc[2] && metodo === 'GET') {
        const tipo = TIPOS[extname(doc.nombre).toLowerCase()];
        const enLinea = tipo && /^(image\/(png|jpeg|webp)|application\/pdf)$/.test(tipo);
        return servirFichero(res, join(config.datos, 'documentos', doc.id), {
          'content-type': enLinea ? tipo : 'application/octet-stream',
          'content-disposition': `${enLinea ? 'inline' : 'attachment'}; filename*=UTF-8''${encodeURIComponent(doc.nombre)}`,
          'content-security-policy': "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'",
        });
      }
      if (mDoc[2] && metodo === 'POST') {
        await leerCuerpo(req);
        try { unlinkSync(join(config.datos, 'documentos', doc.id)); } catch { /* ya no estaba */ }
        d.documentos = d.documentos.filter((x) => x.id !== doc.id); guardar('panel.json', d);
        return redirigir(res, '/');
      }
    }
  }
  return enviar(res, 404, pagina('No existe', '<div class="caja"><h1>Esta página no existe</h1><a href="/">Volver</a></div>', { usuario: u }));
}

export function crearServidor() {
  return http.createServer((req, res) => {
    atender(req, res).catch((e) => {
      console.error('[error]', e.message);
      if (!res.headersSent) enviar(res, e.message === 'grande' ? 413 : 500, 'Algo ha fallado', 'text/plain; charset=utf-8');
      else res.end();
    });
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  // Documentos huérfanos (subida cortada a medias): se borran al arrancar.
  const conocidos = new Set(panel().documentos.map((d) => d.id));
  for (const f of readdirSync(join(config.datos, 'documentos'))) if (!conocidos.has(f)) { try { unlinkSync(join(config.datos, 'documentos', f)); } catch { /* nada */ } }
  crearServidor().listen(config.puerto, () => console.log(`${P.nombre} en ${config.baseUrl} (puerto ${config.puerto}, Google ${googleOn() ? 'sí' : 'no'})`));
}
