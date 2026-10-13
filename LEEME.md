# Súbelo: tu box Sagemcom como disco de fotos de casa

```
 Móvil (app «Súbelo»)  ──Wi-Fi de casa──►  Box Sagemcom (app «Súbelo Servidor»)  ──USB──►  Disco duro
                                                         ▲
                              Navegador de un PC ────────┘  (https://IP-del-box:8765)
```

- **Súbelo Servidor** (en el box): servidor que recibe las fotos y las guarda en el disco USB. Se anuncia solo en la red, se arranca al encender el box y muestra en la tele su dirección y los usuarios.
- **Súbelo** (en el móvil): subes fotos y vídeos a la carpeta que quieras, ves todo lo que hay en el disco y te descargas al móvil lo que quieras. Opcional: **subida automática** de las fotos nuevas cuando estás en el Wi-Fi de casa.
- **Ordenador** (cualquier navegador): ves el disco con miniaturas, subes fotos o carpetas enteras (también arrastrándolas) y descargas lo que quieras (una a una o en .zip).

No hace falta root ni Internet: todo va por tu red local.

## Novedades: álbumes, carpetas con portada y mapa

- **Subir álbumes enteros** (móvil, «▤ Subir álbumes enteros»): eliges uno o varios álbumes de la galería (Cámara, WhatsApp, Descargas…) y cada uno va a su propia carpeta del disco, dentro de la carpeta de destino. Lo que ya estaba subido se salta, así que repetirlo solo sube lo nuevo. En Android 14, si al dar permiso elegiste «Seleccionar fotos», solo se ven esas: cámbialo a «Permitir todo».
- **Mis fotos por carpetas** (móvil): cada carpeta muestra una foto de portada y cuántas fotos tiene.
- **Compartir carpetas entre Fer y Julss**: en una carpeta tuya pulsa «👥» (móvil) o «👥 Compartir» (web) y elige si el otro solo puede verla y descargar, o también añadir fotos. Lo que te comparten aparece en tu carpeta como «👥 Compartido conmigo». Borrar, mover y renombrar solo puede el dueño. Si mueves o renombras una carpeta compartida, sigue compartida; si la borras, deja de estarlo.
- **Borrar, mover y renombrar** fotos, vídeos y carpetas: en el móvil, mantén pulsado para elegir (luego toca para añadir más) y usa «🗑 Borrar», «↦ Mover» o «✎»; en el visor también hay 🗑 y ↦. En la web, marca las casillas (también en las carpetas). Siempre pide confirmación; borrar no se puede deshacer.
- **Huella o contraseña al abrir** (móvil, apartado «04 // Seguridad»): activa «Pedir huella o contraseña al abrir Súbelo» y escribe tu contraseña de Súbelo (la de entrar como Fer o Julss; se comprueba con el box, así que hay que estar en el Wi-Fi de casa). Desde entonces la app pide la huella o esa contraseña al abrirla y al volver tras más de 1 minuto fuera. Funciona también fuera de casa: el móvil guarda solo una huella cifrada de la contraseña, nunca la contraseña. Tras 5 fallos hay que esperar. Si se olvida, «He olvidado la contraseña» cierra la sesión y hay que volver a entrar en el box. La subida automática sigue funcionando aunque la app esté bloqueada.
- **Enviar al Echo** (móvil): en «Mis fotos por carpetas» mantén pulsado para elegir fotos y pulsa «📺 Enviar … al Echo», o en el visor «📺 Al Echo». Se guardan en el móvil en Imágenes › Subelo › Echo (la subida automática no las vuelve a subir) y se abren en Amazon Photos, que las sube a tu cuenta. Amazon no deja que otra app las meta en un álbum, así que allí se añaden al álbum que muestra el Echo. Si Amazon Photos no está instalada, sale el menú normal de compartir. También funciona en las carpetas que te han compartido.
- **Mapa de mis fotos** (móvil y web, botón «◉ Mapa de fotos»): cada foto aparece donde se hizo, agrupadas por zonas, con filtro por año. La ubicación sale del GPS que guarda la cámara; las fotos hechas con la ubicación de la cámara apagada no salen. La primera vez el box tarda un poco en leer todas las fotos; después solo mira las nuevas. El fondo del mapa (OpenStreetMap) necesita internet.

## Actualizaciones automáticas (GitHub)

Cada vez que se sube un cambio de las apps a la rama `main` del repositorio, GitHub Actions (`.github/workflows/publicar.yml`) compila y firma los dos APK y los publica como una *release* `v<número>`. Las apps lo comprueban solas (como mucho una vez por hora):

- **Móvil:** aparece una tarjeta naranja «Nueva versión» con «Actualizar ahora».
- **Box:** el botón «Buscar actualizaciones» pasa a «⬆ Instalar versión …».

Android pide confirmar la instalación (y la primera vez, permitir «instalar apps desconocidas» para Súbelo). Solo acepta APK firmados con la misma clave, así que nadie puede colar una versión falsa. Tras actualizar, el servidor del box arranca solo.

Secretos del repositorio (Settings › Secrets and variables › Actions): `KEYSTORE_B64` (el contenido de `firma/KEYSTORE_B64.txt`) y `KEYSTORE_PASS` (la contraseña de la firma).

## Versión 1.3: usuarios Fer y Julss

Al abrir la app del móvil o la web sale «¿Quién eres?» con dos botones, **Fer** y **Julss**. La primera vez que pulsas tu nombre inventas tu contraseña; después la escribes para entrar. El móvil recuerda la sesión (botón «Cerrar sesión») y la web 30 días (botón «Salir»). Cada uno solo ve su carpeta del disco: `Fer/` y `Julss/` dentro de la carpeta elegida en el box. El PIN desaparece.

Si ya habías subido algo antes de la 1.3, está fuera de esas carpetas: muévelo dentro de `Fer/` o `Julss/` desde un ordenador con el disco conectado.

## Versión 1.2: modo oscuro

Las tres partes tienen un botón para cambiar entre modo claro y oscuro: **☾ Oscuro / ☀ Claro** arriba a la derecha en la app del móvil y en la web, y **☾ Modo oscuro** en la lista de acciones del box. Cada aparato recuerda lo que elijas (la web, en cada navegador). Mientras no elijas nada, el móvil y la web siguen el ajuste claro/oscuro del sistema; el box empieza en claro. Las capturas están en `diseno/` (las que acaban en `-oscuro`).

## Versión 1.1: nuevo diseño

Las tres partes (box, móvil y web) usan ahora el mismo estilo brutalista: morado `#7B2FF7`, naranja `#FF6B1A`, bordes negros gruesos, sombras duras y tipografías Archivo Black, Space Grotesk y Space Mono (incluidas en las apps, no se descargan de internet). Para actualizar, instala los dos APK nuevos encima de los anteriores: se conservan la carpeta y el emparejamiento.

## Archivos

| Archivo | Para qué |
|---|---|
| `apk/Subelo-Servidor-box.apk` | Instalar en el box Sagemcom |
| `apk/Subelo-movil.apk` | Instalar en el móvil (Android 7 o superior) |
| `box/`, `movil/` | Código fuente (Java, sin librerías externas) |
| `firma/subelo.jks` | Firma de los APK. No va al repositorio: está en los secretos de GitHub. Guárdala: sin ella no podrás actualizar sin desinstalar |
| `compilar-sin-android-studio.sh` | Script con el que se generaron los APK |
| `diseno/` | Logo y capturas del diseño (versión 1.1, estilo brutalista morado y naranja) |

---

## 1. Prepara el disco duro

- Lo más compatible es formatearlo en **exFAT** (o FAT32). Muchos box Android solo leen NTFS pero no escriben en él.
- Si el disco ya tiene datos en NTFS, pruébalo primero: si el box no puede escribir, la app te lo dirá.
- Si es un disco de 2,5" sin alimentación propia y el box no lo enciende bien, usa un hub USB con alimentación.

## 2. Instala «Súbelo Servidor» en el box

Android TV no deja instalar APK desde el navegador, así que elige una de estas formas:

**Opción A: con un pendrive (la más fácil)**
1. Copia `Subelo-Servidor-box.apk` a un pendrive (o al propio disco duro).
2. En el box instala desde Google Play un explorador de archivos, por ejemplo **X-plore File Manager** o **File Commander**.
3. Conecta el pendrive, abre el explorador, busca el APK y ábrelo.
4. Android te pedirá permitir «instalar apps desconocidas» para ese explorador: acéptalo y vuelve atrás para instalar.

**Opción B: con la app «Send files to TV»** (en el móvil y en el box, desde Google Play): envías el APK del móvil al box y lo abres.

**Opción C: con ADB desde un ordenador**
1. En el box: Ajustes › Preferencias del dispositivo › Información › pulsa 7 veces en «Compilación» para activar las opciones de desarrollador.
2. Ajustes › Preferencias del dispositivo › Opciones de desarrollador › activa «Depuración USB» o «Depuración por red».
3. En el ordenador: `adb connect IP-DEL-BOX:5555` y luego `adb install Subelo-Servidor-box.apk`. Acepta el aviso que sale en la tele.

> Algunos box de operador (Movistar, Orange…) bloquean instalar apps de fuera de Google Play. Si no aparece la opción de «apps desconocidas», el box no lo permite.

## 3. Configura el box

1. Conecta el disco al USB del box.
2. Abre **Súbelo Servidor** (está en la fila de apps de la tele).
3. Pulsa **«Elegir carpeta del disco USB»**. Si el box trae el selector de Android, entra en el disco y elige una carpeta. Si no lo trae (muchos box de TV), sale la lista **«Disco USB»** con los discos detectados:
   - «Usar …»: el disco permite escribir; listo.
   - «Dar permiso de todos los archivos» (Android 11 o superior): actívalo para Súbelo Servidor y vuelve; la lista se reabre sola. Si el box no tiene esa pantalla, se puede dar con ADB: `adb shell appops set --uid es.subelo.box MANAGE_EXTERNAL_STORAGE allow`.
   - «Usar la carpeta de Súbelo en …»: funciona siempre, pero se BORRA si desinstalas la app del box (actualizar no la borra). Úsala solo si lo anterior no va.
   - Si el disco no aparece o dice «se puede escribir: NO» con el permiso dado, suele ser un disco NTFS: muchos box solo lo leen. Formatéalo en exFAT.
4. Apunta el **código del box** (por ejemplo `3F2A-91C0`) y la dirección (`https://192.168.x.x:8765`) que aparecen en pantalla.
5. Recomendado:
   - Desactiva la suspensión del box: Ajustes › Preferencias del dispositivo › Energía / Ahorro de energía › «Nunca». Si el box se duerme, no recibe fotos.
   - Conéctalo por cable de red si puedes (más rápido que el Wi-Fi).
   - En el router, reserva una IP fija para el box (DHCP estático). No es obligatorio: el móvil lo busca solo.

El servidor arranca solo cada vez que se enciende el box.

## 4. Instala «Súbelo» en el móvil

1. Pasa `Subelo-movil.apk` al móvil (por ejemplo enviándotelo por correo, WhatsApp o Google Drive) y ábrelo.
2. Android te pedirá permitir instalar apps desde esa app (Chrome, Archivos…): acéptalo.
3. Google Play Protect avisará de que es una app desconocida: pulsa «Más detalles › Instalar de todos modos».

## 5. Uso en el móvil

1. Con el móvil en el **Wi-Fi de casa**, abre Súbelo y pulsa **«Buscar el box»**. Comprueba que el **código del box** que sale en el móvil es el mismo que el de la tele, pulsa tu nombre (Fer o Julss) y escribe tu contraseña. La primera vez, la inventas tú.
   (Si no lo encuentra, pulsa «Poner IP a mano» y escribe la IP de la tele.)
2. **«Elegir carpeta del disco»**: navega por las carpetas del disco, crea las que quieras y pulsa «Usar esta carpeta».
   Opcional: «Organizar en subcarpetas por año y mes» (`Fotos/2026/10/...`).
3. **«SUBIR FOTOS Y VÍDEOS»**: eliges en la galería y se suben en segundo plano con una notificación de progreso.
   También puedes, desde la galería, **Compartir › Súbelo**.
4. **Subida automática**: activa el interruptor y elige:
   - «Solo las nuevas desde ahora», o
   - «Todas, también las que ya tengo» (la primera vez puede tardar mucho; se queda subiendo con notificación).
   A partir de ahí, cada foto nueva se copia sola al disco en cuanto el móvil está en un Wi-Fi (y el box responde). Si estás fuera, se suben al volver a casa (se revisa cada hora).
   - «Solo fotos de la cámara» evita subir capturas de pantalla e imágenes de WhatsApp.

5. **«VER EL DISCO DEL BOX»**: navegas por el disco con miniaturas.
   - Toca una foto para verla a pantalla completa (desliza para pasar) o un vídeo para reproducirlo directamente desde el box.
   - **«⬆ Subir aquí»** sube fotos a la carpeta que estás viendo.
   - **«⬇ Descargar todo»** baja al móvil todo lo de esa carpeta. Mantén pulsada una foto para seleccionar solo algunas.
   - Lo descargado aparece en la galería, en la carpeta **Subelo** (Imágenes/Subelo, Películas/Subelo o Descargas/Subelo). La subida automática nunca vuelve a subir esa carpeta.

Las fotos que ya están en el disco (mismo nombre y tamaño) no se vuelven a subir, y lo que ya está en el móvil no se vuelve a descargar. Si hay otra distinta con el mismo nombre, se guarda como `IMG_1234 (1).jpg`. Se conserva la ubicación GPS de las fotos si das el permiso de «ubicación de fotos» al activar la subida automática.

## 6. Desde un ordenador

Abre en el navegador (Chrome, Edge, Firefox, Safari) la dirección que sale en la tele, por ejemplo `https://192.168.1.50:8765` y entra con tu usuario. Si escribes la dirección sin `https://`, el box te redirige solo. Guárdala en favoritos.

La primera vez el navegador dirá que «la conexión no es privada». Es normal: el box usa un certificado propio que no firma ninguna empresa. Pulsa «Configuración avanzada» › «Continuar a …». La conexión va cifrada igualmente. El navegador recuerda la sesión 30 días (botón «Salir» para cerrarla).

- **Ver**: navegas por las carpetas con miniaturas. Clic en una foto o vídeo para verlo en grande; flechas del teclado para pasar.
- **Subir**: «Subir fotos», «Subir una carpeta» (mantiene sus subcarpetas) o **arrastra** fotos y carpetas a la ventana.
- **Descargar**: marca las casillas y pulsa «Descargar seleccionadas», o «Descargar esta carpeta (.zip)». Desde el visor, «Descargar» baja la foto suelta.

## 7. ¿Y fuera de casa?

Tal como está, solo funciona con el móvil u ordenador conectados a la red de casa (es lo más seguro). Si quieres acceder desde fuera, la forma sencilla y segura es instalar **Tailscale** (gratis) en el box, el móvil y el ordenador: en la app Súbelo pulsa «Poner IP a mano» y escribe la IP de Tailscale del box (100.x.x.x); en el ordenador usa `https://100.x.x.x:8765`. Nunca abras el puerto en el router.

---

## Problemas frecuentes

| Problema | Solución |
|---|---|
| El móvil no encuentra el box | Mismo Wi-Fi (no la red de invitados), app del box abierta con «Servidor ACTIVO». Prueba «Poner IP a mano». |
| «No se puede acceder a este sitio» / «ha rechazado la conexión» | El servidor no está escuchando en esa dirección. Mira la tele: debe decir **● ACTIVO** (si no, pulsa «Iniciar servidor» y lee el recuadro rojo si aparece) y usa exactamente la IP que sale ahí, que puede no ser la que crees. |
| Olvidé mi contraseña | En la tele, «Borrar contraseña de Fer/Julss». Al entrar de nuevo pondrás otra. Tus fotos no se tocan. |
| «Demasiadas contraseñas incorrectas» | Espera 5 minutos y vuelve a intentarlo. |
| «Este box no es el que emparejaste» | Si has reinstalado la app del box o cambiado de box, su código cambia: pulsa «Buscar el box» y empareja de nuevo comprobando el código. |
| «El disco USB no está disponible» | Disco desconectado o dormido. Reconéctalo y, si hace falta, vuelve a elegir la carpeta en el box. |
| Se sube muy lento | Usa cable de red en el box y Wi-Fi de 5 GHz en el móvil. |
| La subida automática no salta | Ajustes del móvil › Apps › Súbelo › Batería › «Sin restricciones». Algunas marcas (Xiaomi, Huawei, Samsung) cierran apps en segundo plano. |
| Error al escribir en el disco | Probablemente está en NTFS: formatéalo en exFAT. |
| No salen miniaturas de algunas fotos | El box no sabe leer ese formato (por ejemplo HEIC en box antiguos). La foto se puede descargar igual. |
| El navegador solo descarga un archivo | Al descargar varias se baja un .zip; si el navegador lo bloquea, permite las descargas para esa dirección. |

## Seguridad

- **Todo va cifrado (HTTPS).** El box crea su propio certificado la primera vez. Nadie en tu Wi-Fi puede ver las fotos ni las contraseñas mientras viajan.
- **El móvil reconoce a su box.** Al emparejar memoriza su «código del box» (la huella del certificado). Después solo habla con ese box: si otro aparato intenta hacerse pasar por él, la app se niega y te avisa. Por eso conviene comprobar que el código del móvil y el de la tele coinciden al emparejar.
- **Dos usuarios, Fer y Julss, cada uno con su contraseña y su carpeta.** Cada uno pone su contraseña la primera vez que entra y solo ve su carpeta del disco (`Fer/` o `Julss/`); el otro no puede verla ni con la dirección exacta. Las contraseñas se guardan cifradas (PBKDF2 con sal). Tras 5 contraseñas incorrectas, ese aparato queda bloqueado 5 minutos (y si hay muchos fallos en toda la red, se bloquea todo 10 minutos). Desde la tele se puede borrar una contraseña olvidada; eso cierra sus sesiones.
- **Ojo con la primera vez:** quien pulse primero un nombre sin contraseña la elige. Entrad los dos cuanto antes.
- **Web con sesión segura.** La contraseña no aparece en ninguna dirección ni en el historial; el navegador guarda una cookie protegida (solo HTTPS, inaccesible para otras webs).
- Solo funciona dentro de tu red; no hay nada abierto a Internet. **No abras el puerto 8765 en el router.** Para usarlo desde fuera, Tailscale.
- No se puede borrar nada desde el móvil ni desde la web, y nadie puede salirse de la carpeta del disco.

Lo que **no** cubre: el disco no está cifrado (quien se lo lleve, se lleva las fotos) y solo hay una copia (si el disco falla, se pierde). Para lo segundo, lo recomendable es tener otra copia en otro disco o en la nube.

## Compilar tú mismo

- **Android Studio**: abre la carpeta `subelo` como proyecto. Tiene dos módulos: `box` y `movil`. Usa la misma firma (`firma/subelo.jks`), así que lo que compiles actualiza las apps instaladas sin desinstalar.
- **Sin Android Studio** (Linux): mira las instrucciones al principio de `compilar-sin-android-studio.sh`.

## Cómo funciona por dentro (por si quieres tocarlo)

- Box: `HttpServer.java` (servidor HTTP propio, puerto 8765), `SafStore.java` (escribe en la carpeta elegida con el selector de Android), `FileStore.java` (escribe en una ruta directa), `ServerService.java` (servicio permanente + anuncio mDNS `_subelo._tcp`), `BootReceiver.java` (arranque automático).
- Móvil: `Discovery.java` (busca el box por mDNS), `BoxClient.java` (API HTTP), `UploadService.java` (subidas con notificación), `AutoUploadJob.java` (subida automática con JobScheduler), `Uploader.java` (lógica común).
- Box también: `Thumbs.java` (miniaturas con caché), `assets/index.html` (la web del ordenador).
- Móvil también: `ExploreActivity.java` (explorador del disco), `ViewerActivity.java` (visor), `Downloader.java` (guardar en la galería).
- Box también: `Tls.java` (certificado propio y HTTPS). Móvil también: `VideoProxy.java` (pasarela interna para ver vídeos cifrados).
- API por HTTPS (token en la cabecera `X-Subelo-Token`, o cookie de sesión, tras `POST /api/login` con `user` y `password`; `POST /api/logout`; `GET /api/users` sin sesión). Las rutas son relativas a la carpeta del usuario: `GET /api/info`, `GET /api/list?path=`, `GET /api/folders?path=`, `POST /api/mkdir?path=`, `GET /api/exists?path=&name=`, `PUT /api/upload?path=&name=&size=&mtime=`, `GET /api/file?path=&name=[&download=1]` (admite Range), `GET /api/thumb?path=&name=`, `GET|POST /api/zip?path=[&name=…]`.
