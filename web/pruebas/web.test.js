// Pruebas sin internet: una Torre falsa decide los accesos.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const estados = new Map([['amiga@ejemplo.com', 'aprobado']]);
const pedidas = [];
let torre, web, base;

before(async () => {
  torre = http.createServer((req, res) => {
    let b = ''; req.on('data', (c) => { b += c; });
    req.on('end', () => { const j = JSON.parse(b); pedidas.push(j); res.end(JSON.stringify({ estado: estados.get(j.email) || 'pendiente' })); });
  }).listen(0);
  const dir = mkdtempSync(join(tmpdir(), 'web-proyecto-'));
  mkdirSync(join(dir, 'descargas')); writeFileSync(join(dir, 'descargas', 'app.apk'), 'APK');
  mkdirSync(join(dir, 'app-web')); writeFileSync(join(dir, 'app-web', 'index.html'), '<p>app</p>');
  writeFileSync(join(dir, 'proyecto.json'), JSON.stringify({ clave: 'prueba', nombre: 'Prueba', descripcion: 'x', appWeb: { titulo: 'App' }, descargas: [{ titulo: 'APK', archivo: 'app.apk' }], tareasIniciales: ['Primera'] }));
  Object.assign(process.env, { DATOS_DIR: join(dir, 'datos'), DESCARGAS_DIR: join(dir, 'descargas'), APP_WEB_DIR: join(dir, 'app-web'), PROYECTO_JSON: join(dir, 'proyecto.json'), TORRE_INTERNA: `http://127.0.0.1:${torre.address().port}`, SESSION_SECRET: 'x'.repeat(40), BASE_URL: 'http://127.0.0.1' });
  const { crearServidor } = await import('../servidor.js');
  web = crearServidor().listen(0);
  base = `http://127.0.0.1:${web.address().port}`;
});
after(() => { web.close(); torre.close(); });

const post = (ruta, datos, cookie = '') => fetch(base + ruta, { method: 'POST', redirect: 'manual', headers: { origin: base, 'content-type': 'application/x-www-form-urlencoded', cookie }, body: new URLSearchParams(datos) });
const galleta = (r) => (r.headers.getSetCookie().find((c) => c.startsWith('sesion=') && !c.startsWith('sesion=;')) || '').split(';')[0];

test('salud y sin sesión manda a entrar', async () => {
  assert.equal((await (await fetch(base + '/salud')).json()).app, 'prueba');
  const r = await fetch(base + '/', { redirect: 'manual' });
  assert.equal(r.headers.get('location'), '/entrar');
  assert.equal((await fetch(base + '/descargar/app.apk', { redirect: 'manual' })).status, 303);
});

test('nadie puede registrarse con el email de Fer', async () => {
  const r = await post('/registro', { nombre: 'X', email: 'hola@neuxmatrix.es', contrasena: 'unaclavelarga' });
  assert.equal(r.headers.get('location'), '/entrar?error=dueno');
});

test('registro de alguien desconocido queda pendiente en la Torre', async () => {
  const r = await post('/registro', { nombre: 'Pepe', email: 'pepe@ejemplo.com', contrasena: 'unaclavelarga' });
  assert.equal(r.headers.get('location'), '/entrar?aviso=creada');
  assert.equal(pedidas.at(-1).app, 'prueba');
  const r2 = await post('/entrar', { email: 'pepe@ejemplo.com', contrasena: 'unaclavelarga' });
  assert.equal(r2.headers.get('location'), '/entrar?error=pendiente');
  assert.equal(galleta(r2), '');
});

test('aprobada en la Torre entra, ve la ficha, la app y la descarga', async () => {
  const r = await post('/registro', { nombre: 'Amiga', email: 'amiga@ejemplo.com', contrasena: 'unaclavelarga' });
  assert.equal(r.headers.get('location'), '/');
  const c = galleta(r);
  const ficha = await (await fetch(base + '/', { headers: { cookie: c } })).text();
  assert.match(ficha, /Primera/);
  assert.equal(await (await fetch(base + '/app/', { headers: { cookie: c } })).text(), '<p>app</p>');
  assert.equal(await (await fetch(base + '/descargar/app.apk', { headers: { cookie: c } })).text(), 'APK');
  assert.equal((await fetch(base + '/app/../servidor.js', { headers: { cookie: c } })).status, 404);
});

test('contraseña mal, sin origen y cerrar en todos', async () => {
  assert.equal((await post('/entrar', { email: 'amiga@ejemplo.com', contrasena: 'otra cosa' })).headers.get('location'), '/entrar?error=datos');
  const c = galleta(await post('/entrar', { email: 'amiga@ejemplo.com', contrasena: 'unaclavelarga' }));
  const sinOrigen = await fetch(base + '/panel/tareas', { method: 'POST', headers: { cookie: c }, body: 'texto=x' });
  assert.equal(sinOrigen.status, 403);
  await post('/panel/tareas', { texto: 'Segunda' }, c);
  assert.match(await (await fetch(base + '/', { headers: { cookie: c } })).text(), /Segunda/);
  await post('/cuenta/cerrar-todo', {}, c);
  assert.equal((await fetch(base + '/', { redirect: 'manual', headers: { cookie: c } })).status, 303);
});

test('documentos: subir, bajar y borrar', async () => {
  const c = galleta(await post('/entrar', { email: 'amiga@ejemplo.com', contrasena: 'unaclavelarga' }));
  const r = await fetch(base + '/panel/documentos?nombre=' + encodeURIComponent('contrato.txt'), { method: 'PUT', headers: { cookie: c, origin: base }, body: 'hola' });
  assert.equal(r.status, 201);
  const { id } = await r.json();
  assert.equal(await (await fetch(`${base}/panel/documentos/${id}`, { headers: { cookie: c } })).text(), 'hola');
  await post(`/panel/documentos/${id}/borrar`, {}, c);
  assert.equal((await fetch(`${base}/panel/documentos/${id}`, { headers: { cookie: c } })).status, 404);
});

test('la Torre rechaza después: ya no entra', async () => {
  estados.set('amiga@ejemplo.com', 'rechazado');
  assert.equal((await post('/entrar', { email: 'amiga@ejemplo.com', contrasena: 'unaclavelarga' })).headers.get('location'), '/entrar?error=no_autorizado');
});
