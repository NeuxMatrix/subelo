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

- **Mapa con puntos en vez de miniaturas.** Cada sitio con fotos es un punto redondo con el número de fotos (morado lo tuyo, naranja lo del otro). Al pulsarlo se abre su carpeta (en el móvil, en «Mis fotos por carpetas»). Si en ese sitio hay fotos de varias carpetas, sale abajo una lista para elegir cuál abrir, con «▶ Ver» para ver solo las fotos de ese sitio. Un grupo de puntos lejanos se acerca al pulsarlo. También se ha arreglado que en la web del móvil al tocar el mapa se moviera la página y no se abriera nada. Hay que actualizar el box y la app del móvil (el móvil usa su propia copia del mapa).
- **Lo que ocupa cada usuario en el disco («💾»).** La tele (tarjeta «03 // disco»), el móvil (arriba, junto al box) y la web («⚙ Ajustes» › «💾 Espacio del disco», o pulsando «Libre … de …» arriba) enseñan cuánto ocupa la carpeta de Fer y la de Julss, con cuántas fotos y vídeos hay, lo demás del disco y lo libre. El box lo cuenta de fondo: la primera vez tarda un poco en un disco grande, y luego se pone al día un minuto después de subir o borrar algo (y cada media hora). Lo que el otro añade en una carpeta que le compartes cuenta como tuyo, porque está en tu carpeta.
- **Copia de seguridad en Amazon Fotos («☁»).**
  - **Hacer backup de una carpeta**: en «Mis fotos por carpetas» (móvil) o «Mis fotos» (web), entra en la carpeta y pulsa «☁» › «Hacer backup en Amazon». El box sube la carpeta entera, con sus subcarpetas, a «Súbelo Backup/Fer/…» (o «…/Julss/…») de tu Amazon Fotos, y lo que añadas después también. Repasa cada 30 minutos y poco después de cada subida.
  - **No sale en el Echo ni en ningún aparato de Amazon**: las copias nunca se meten en un álbum y cada foto se oculta en Amazon Fotos nada más subirla (si Amazon no deja ocultarlas, Súbelo para las copias y lo avisa) (los «Recuerdos diarios», «Tal día como hoy» y Alexa por voz tiran de toda la biblioteca, no solo de los álbumes). Para estar del todo seguro, en el Echo y en el Fire TV deja apagados «Recuerdos diarios» y «Tal día como hoy».
  - **Cada uno con su cuenta de Amazon**: Amazon Fotos no tiene perfiles, así que si Fer y Julss usan la misma cuenta cada uno vería lo del otro. Si los dos entran en Súbelo con la misma cuenta (ahora o alguna vez), Súbelo no hace copias en ella hasta que cada uno use la suya, o hasta que el dueño le cambie la contraseña en Amazon y pulse «… ya no puede entrar» (☁ en la carpeta). Lo que ya se copió en una cuenta compartida se queda allí, en «Súbelo Backup/<usuario>»: para borrarlo, «Dejar de hacer copia» en cada carpeta. Si alguien entra en esa cuenta solo con la app de Amazon, sin pasar por Súbelo, Súbelo no lo puede saber. Julss puede tener fotos ilimitadas en su propia cuenta si Fer la invita a su «Archivo familiar» (con «añadir subidas al Archivo familiar» apagado).
  - **Aunque ya estuviera en Amazon**: si una foto ya está en tu Amazon Fotos (por ejemplo, subida por la app de Amazon), Súbelo sube otra copia dentro de su carpeta, oculta. Si Amazon no lo deja, se queda donde estaba (y como ya se veía, no se oculta).
  - **Es una copia, no un espejo**: si borras algo del disco, en Amazon se queda. Si cambias una foto, se sube otra vez con otro nombre. Si alguien borra en la web de Amazon una carpeta que sigue con «☁», se vuelve a subir.
  - **Dejar de copiar la borra de Amazon**: «☁» › «Dejar de hacer copia» (o, en el móvil, mantener pulsada la carpeta en «☁ Copias en Amazon», estando en casa) lleva lo copiado de esa carpeta a la papelera de Amazon Fotos, donde se puede recuperar durante 90 días. En el disco no se toca nada. Lo de una carpeta que ya se borró del disco también se puede borrar así, desde «☁ Copias en Amazon». Las fotos que también usa el Echo se quedan. Si dejas de copiar sin sesión de Amazon, o te cambias de cuenta antes de que acabe, se borra en cuanto el box tenga una sesión de aquella cuenta (la tuya o la del otro, si la comparte).
  - **Dónde se ven las carpetas en Amazon**: la app de Amazon Fotos no enseña carpetas, solo la web (amazon.es/photos, en su vista de carpetas), y como las fotos van ocultas, en la app solo salen entre las ocultas. Lo más cómodo es «☁ Copias en Amazon», en la app de Súbelo.
  - **Fuera de casa**: en el móvil, «☁ Copias en Amazon» pide tu contraseña de Súbelo y enseña tus carpetas con copia directamente desde Amazon, sin el box. La primera vez hay que abrirlo en casa (para comprobar la contraseña) y entrar en Amazon desde ahí; la sesión de Amazon se guarda cifrada en el móvil y se borra al cerrar la sesión de Súbelo o al pulsar «Desconectar».
  - Usa la misma conexión con Amazon que «📺 Fotos al Echo» (sin API oficial de Amazon: puede dejar de ir si Amazon cambia su web). Con Prime, las fotos no gastan espacio; los vídeos y otros archivos sí (5 GB).
- **2.18: mapa común entre Fer y Julss, y ubicar carpetas enteras.**
  - **¿Solo yo o con Julss?** Al ubicar fotos o carpetas con «📍» (web y móvil), en vez de «Guardar aquí» salen dos botones: «✓ Solo yo» (solo sale en tu mapa) y «✓ Con Julss» (sale también en el mapa de Julss). Para Julss igual, con «✓ Con Fer». El otro solo puede verlas desde su mapa: no puede borrarlas, moverlas ni cambiarlas de sitio, ni ver el resto de la carpeta.
  - **Ubicar una carpeta entera**: elige la carpeta (o varias) y pulsa «📍». Las fotos de dentro, también las de sus subcarpetas, que no tienen GPS van a ese sitio; las que lo tienen se quedan donde se hicieron. Si la carpeta es «con Julss», todo lo de dentro sale en los dos mapas.
  - **El mapa de cada uno** enseña lo suyo, lo que el otro ha ubicado «con» él y las fotos de las carpetas que el otro le comparte (👥). Las del otro salen en naranja; arriba se filtra con «👥 Todas / Mías / De Julss».
  - Hay que actualizar el box (en la tele) **y** el móvil. Si el móvil dice «El box tiene una versión antigua», falta actualizar el box.
- **2.17: ajustes en la web, favoritos, ubicación a mano, fotos más grandes y app para el ordenador.**
  - **📍 Ubicación** (web y móvil): elige una o varias fotos o vídeos tuyos (o ábrelos en el visor) y pulsa «📍». Marca el sitio en el mapa, búscalo por nombre o pega unas coordenadas o un enlace de Google Maps, y «✓ Guardar aquí». Desde el visor del Mapa, «📍 Cambiar sitio» corrige una foto mal colocada. No se toca el archivo: el box lo apunta y el mapa lo usa en lugar del GPS de la foto («↺ Quitar la puesta a mano» lo deshace). Si mueves o renombras la foto, la ubicación la sigue.
  - **Zoom con el ratón** en el visor de la web: la rueda acerca y aleja donde está el ratón, arrastrando te mueves por la foto, y un clic acerca o vuelve a verla entera (también las teclas + − y 0).
  - **Mapa**: la foto que abres desde el mapa ya cabe en la pantalla y se cierra con «✕ Cerrar», Esc o un clic fuera; «📁 Carpeta» abre su carpeta.
  - En lo que te comparten sin permiso de borrar, el visor lo dice («🔒 De Julss: solo Julss puede borrarla»).
  - **⚙ Ajustes** (web): cambiar tu contraseña (pide la actual; puedes cerrar también la sesión de los móviles), poner, cambiar o quitar la llave de Google, ver lo que compartes, el modo oscuro y la versión del box.
  - **⭐ Favoritos** (web y móvil): elige fotos o vídeos y pulsa «⭐ Favorito» (en el visor, la estrella). Salen juntos en la carpeta «⭐ Favoritos» sin copiar nada: cada foto sigue en su carpeta, y si la mueves o le cambias el nombre el favorito la sigue. Vale también con lo que te comparten. En Favoritos, «Quitar de favoritos» nunca borra la foto.
  - **Fotos más grandes en la web**: tamaño de las miniaturas (S, M, L), «▭ Enteras» para verlas sin recortar, y en el visor clic para ampliar y ⛶ para pantalla completa.
  - **Buscar, filtrar y ordenar** (web): por nombre, solo fotos, solo vídeos o solo favoritos, y ordenar por nombre, fecha o tamaño. «Descargar» baja solo lo que se ve.
  - **Google Fotos desde la web**: al pegar la dirección de la página que no carga, sigue solo.
  - **Como app en el ordenador**: nueva dirección `https://<ip-del-box>:8766` con un certificado que instalas una vez (ver «6. Desde un ordenador»). Así Chrome o Edge la pueden instalar como app sin avisos.
- **Web del ordenador renovada** (2.16): ahora hace lo mismo que la app del móvil, con cuatro apartados arriba: **Mis fotos** (carpetas con portada y número de fotos, subir, arrastrar, elegir para descargar, mandar al Echo, renombrar, mover, borrar y compartir), **Mapa**, **Echo** (estado, cambiar de álbum, repasar, desconectar) y **Google Fotos** (traer fotos eligiéndolas en Google). La contraseña se vuelve a pedir tras 30 minutos sin usarla, a las 12 horas, al cerrar el navegador y si el box se reinicia.
- **Subir álbumes enteros** (móvil, «▤ Subir álbumes enteros»): eliges uno o varios álbumes de la galería (Cámara, WhatsApp, Descargas…) y cada uno va a su propia carpeta del disco, dentro de la carpeta de destino. Lo que ya estaba subido se salta, así que repetirlo solo sube lo nuevo. En Android 14, si al dar permiso elegiste «Seleccionar fotos», solo se ven esas: cámbialo a «Permitir todo».
- **Mis fotos por carpetas** (móvil): cada carpeta muestra una foto de portada y cuántas fotos tiene.
- **Compartir carpetas entre Fer y Julss**: en una carpeta tuya pulsa «👥» (móvil) o «👥 Compartir» (web) y elige si el otro solo puede verla y descargar, o también añadir fotos. Lo que te comparten aparece en tu carpeta como «👥 Compartido conmigo». Borrar, mover y renombrar solo puede el dueño. Si mueves o renombras una carpeta compartida, sigue compartida; si la borras, deja de estarlo.
- **Borrar, mover y renombrar** fotos, vídeos y carpetas: en el móvil, mantén pulsado para elegir (luego toca para añadir más) y usa «🗑 Borrar», «↦ Mover» o «✎»; en el visor también hay 🗑 y ↦. En la web, marca las casillas (también en las carpetas). Siempre pide confirmación; borrar no se puede deshacer.
- **Huella o contraseña al abrir** (móvil, apartado «04 // Seguridad»): activa «Pedir huella o contraseña al abrir Súbelo» y escribe tu contraseña de Súbelo (la de entrar como Fer o Julss; si el box está a mano se comprueba con él, y si no, se escribe dos veces). Desde entonces la app pide la huella o esa contraseña al abrirla y al volver tras más de 1 minuto fuera. Funciona también fuera de casa: el móvil guarda solo una huella cifrada de la contraseña, nunca la contraseña. Tras 5 fallos hay que esperar. Si se olvida, «He olvidado la contraseña» cierra la sesión y hay que volver a entrar en el box. La subida automática sigue funcionando aunque la app esté bloqueada.
- **Traer de Google Fotos** (móvil y box): en la pantalla principal pulsa «⬇ Traer de Google Fotos», elige la carpeta del disco, elige la cuenta de Google y da permiso (se abre el navegador) y elige las fotos en el selector de Google Fotos; para un álbum, búscalo por su nombre. El box las descarga directamente al disco con su fecha original; las que ya estén (mismo nombre y tamaño) se saltan. Google no deja que otras apps vean todas tus fotos, solo las que eliges cada vez (máximo 2000 por tanda), y **les quita la ubicación**, así que estas no salen en el mapa (para conservarla, usa takeout.google.com). Súbelo no guarda ningún permiso de Google: solo vive en el box mientras se copian.
  - Antes, una vez: Google exige que cada app tenga su propia «llave» (cliente OAuth). En console.cloud.google.com crea un proyecto, habilita «Google Photos Picker API», rellena la pantalla de consentimiento en «Google Auth Platform» (tipo Externo, y en «Público» añade como usuarios de prueba todas las cuentas de Google de las que quieras traer fotos, las tuyas y la de Julss) y en «Clientes» crea uno de tipo «Aplicación de escritorio». Da igual con qué cuenta de Google la crees. Pega su ID y su secreto en la app. La llave se guarda en la memoria interna del box y sirve para los dos usuarios.
- **Fotos al Echo** (móvil y box): en la pantalla principal pulsa «📺 Fotos al Echo», entra en tu cuenta de Amazon (la página es la de Amazon) y elige el álbum que enseña tu Echo, o pega su enlace de compartir. Desde entonces, todo lo que haya en tu carpeta «Echo» del disco el box lo sube a Amazon Photos (carpeta «Súbelo Echo») y lo añade a ese álbum; si quitas una foto de la carpeta, se quita del álbum. Para meter fotos: en «Mis fotos por carpetas» mantén pulsado, elige y pulsa «📺 Enviar … al Echo», o en el visor «📺 Al Echo» (también en carpetas compartidas; los vídeos se saltan). Puedes compartir tu carpeta «Echo» con Julss en modo «subir» para que también pueda mandar fotos. El box repasa la carpeta cada 5 minutos y en cuanto cambia algo.
  - Ojo: Amazon no tiene una forma oficial de hacer esto. Súbelo usa la misma conexión que la web de Amazon Photos, así que puede dejar de funcionar si Amazon la cambia. Tu sesión de Amazon (no tu contraseña) se guarda en la memoria interna del box (nunca en el disco), cifrada si el box tiene Android 6 o más, y, también cifrada, en tu móvil para ver las copias fuera de casa; se borra con «Desconectar Amazon» (y del móvil al cerrar tu sesión de Súbelo). Si caduca, la app te avisa para volver a entrar.
- **Mapa de mis fotos** (móvil y web, botón «◉ Mapa de fotos»): cada foto aparece donde se hizo, agrupadas por zonas en puntos con el número de fotos (al pulsar uno se abre su carpeta), con filtro por año. La ubicación sale del GPS que guarda la cámara; las fotos hechas con la ubicación de la cámara apagada no salen. La primera vez el box tarda un poco en leer todas las fotos; después solo mira las nuevas. El fondo del mapa (OpenStreetMap) necesita internet.

## Actualizaciones automáticas (GitHub)

Cada vez que se sube un cambio de las apps a la rama `main` del repositorio, GitHub Actions (`.github/workflows/publicar.yml`) compila y firma los dos APK y los publica como una *release* `v<número>`. Las apps lo comprueban solas (como mucho una vez por hora):

- **Móvil:** aparece una tarjeta naranja «Nueva versión» con «Actualizar ahora».
- **Box:** el botón «Buscar actualizaciones» pasa a «⬆ Instalar versión …».

Android pide confirmar la instalación (y la primera vez, permitir «instalar apps desconocidas» para Súbelo). Solo acepta APK firmados con la misma clave, así que nadie puede colar una versión falsa. Tras actualizar, el servidor del box arranca solo.

Secretos del repositorio (Settings › Secrets and variables › Actions): `KEYSTORE_B64` (el contenido de `firma/KEYSTORE_B64.txt`) y `KEYSTORE_PASS` (la contraseña de la firma).

## Versión 1.3: usuarios Fer y Julss

Al abrir la app del móvil o la web sale «¿Quién eres?» con dos botones, **Fer** y **Julss**. La primera vez que pulsas tu nombre inventas tu contraseña; después la escribes para entrar. El móvil recuerda la sesión (botón «Cerrar sesión»); la web la vuelve a pedir tras 30 minutos sin usarla (botón «Salir»). Cada uno solo ve su carpeta del disco: `Fer/` y `Julss/` dentro de la carpeta elegida en el box. El PIN desaparece.

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

La primera vez el navegador dirá que «la conexión no es privada». Es normal: el box usa un certificado propio que no firma ninguna empresa. Pulsa «Configuración avanzada» › «Continuar a …». La conexión va cifrada igualmente. La contraseña se vuelve a pedir tras 30 minutos sin usar la web, a las 12 horas, al cerrar el navegador o si el box se reinicia (botón «Salir» para cerrarla antes).

Arriba hay cuatro apartados:

- **Mis fotos**: carpetas con su portada y cuántas fotos tienen. Clic en una foto o vídeo para verlo en grande (flechas del teclado o deslizar para pasar). «Subir fotos», «Subir carpeta» (mantiene sus subcarpetas) o **arrastra** fotos y carpetas a la ventana. Marca las casillas (o «Elegir todo») y abajo sale la barra para **Descargar**, **📺 Al Echo**, **Renombrar**, **Mover** y **Borrar**; el visor tiene los mismos botones. «Carpeta .zip» baja la carpeta entera y «👥 Compartir» la comparte con el otro usuario.
- **Mapa**: el mismo mapa de fotos que en el móvil.
- **Echo**: si Amazon está conectado, cuántas fotos hay en el álbum, «Repasar ahora», «Cambiar de álbum» y «Desconectar Amazon». Para entrar en Amazon la primera vez hace falta la app del móvil (Amazon solo deja iniciar sesión en su propia página).
- **Google Fotos**: «Elegir fotos» pregunta la carpeta y abre Google en otra pestaña. Elige la cuenta y da permiso; Google te lleva a una página que no carga (empieza por `http://127.0.0.1`), es normal: copia esa dirección y pégala en Súbelo (o pulsa «📋 Pegar»): sigue solo. Se abre el selector de Google Fotos y el box copia lo que elijas.
- **⚙ Ajustes**: tu contraseña, la llave de Google, lo que compartes, el modo oscuro, la versión del box y cómo usar Súbelo como app del ordenador.

### Como app en el ordenador (sin avisos de «no es privada»)

Si instalas la web como app (Chrome o Edge › ⋮ › «Instalar Súbelo») usando la dirección del puerto 8765, el aviso del certificado hace que se abra fuera de la app. Para eso está la dirección del puerto **8766**, con un certificado que tu ordenador aprende a reconocer una sola vez:

1. En la web, entra en **⚙ Ajustes › 💻 Como app en el ordenador** y pulsa «Descargar el certificado» (`subelo-certificado.crt`).
2. Ábrelo › «Instalar certificado…» › «Usuario actual» › «Colocar todos los certificados en el siguiente almacén» › «Examinar» › **«Entidades de certificación raíz de confianza»** › Aceptar › Siguiente › Finalizar. Windows avisa de que vas a instalar un certificado: comprueba que la **huella** que enseña coincide con la de Ajustes y con la que sale en la tele («CERTIFICADO DEL ORDENADOR») y pulsa «Sí».
3. Cierra Chrome del todo y abre `https://<ip-del-box>:8766` (la sale en la tele y en Ajustes). Ya no hay aviso.
4. Chrome/Edge › ⋮ › «Transmitir, guardar y compartir» › «Instalar página como aplicación» (o «Aplicaciones › Instalar Súbelo»). Borra antes la app vieja si la hiciste con el puerto 8765.

Conviene reservar en el router la IP del box (DHCP estática) para que la dirección no cambie. Si cambia, el certificado sigue valiendo (el box rehace el suyo con la IP nueva), pero la app instalada apunta a la dirección vieja.

## 7. ¿Y fuera de casa?

Tal como está, solo funciona con el móvil u ordenador conectados a la red de casa (es lo más seguro). Lo que tenga copia en Amazon («☁») sí se puede ver fuera de casa desde el móvil, en «☁ Copias en Amazon». Si quieres acceder desde fuera, la forma sencilla y segura es instalar **Tailscale** (gratis) en el box, el móvil y el ordenador: en la app Súbelo pulsa «Poner IP a mano» y escribe la IP de Tailscale del box (100.x.x.x); en el ordenador usa `https://100.x.x.x:8765`. Nunca abras el puerto en el router.

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
- **Certificado del ordenador (puerto 8766).** El box crea su propia autoridad de certificación, guardada solo en su memoria interna. Está limitada (en el propio certificado) a direcciones de casa (`192.168.x.x`, `10.x.x.x`, `172.16–31.x.x`) y a `subelo.local`: aunque alguien la robara, no le serviría para hacerse pasar por ninguna web de Internet. Instálala solo en tus ordenadores. El móvil sigue usando el puerto 8765 con su código del box, sin cambios.
- **Cambiar la contraseña** (⚙ Ajustes en la web) pide la actual, cierra las demás sesiones de la web y, si lo marcas, también las de los móviles.
- **Web con sesión segura.** La contraseña no aparece en ninguna dirección ni en el historial; el navegador guarda una cookie protegida (solo HTTPS, inaccesible para otras webs) que caduca tras 30 minutos sin usarla, a las 12 horas, al cerrar el navegador o al reiniciar el box. Todo lo que no sea la pantalla de entrada pide sesión.
- Solo funciona dentro de tu red; no hay nada abierto a Internet. **No abras el puerto 8765 en el router.** Para usarlo desde fuera, Tailscale.
- Solo el dueño de una carpeta puede borrar, mover o renombrar lo que hay en ella (lo borrado no va a una papelera), y nadie puede salirse de la carpeta del disco.
- **Traer de Google Fotos** solo lee las fotos que eliges en el selector de Google, y el permiso se olvida al terminar la copia; la llave de Google (cliente OAuth) se guarda en la memoria interna del box.
- **Fotos al Echo y las copias en Amazon («☁»)** son lo único que sale de casa: el box se conecta a Amazon Fotos con la sesión de Amazon de cada usuario (en la memoria interna del box, cifrada desde Android 6, borrable con «Desconectar Amazon») y sube solo tu carpeta «Echo» y las carpetas que marques con «☁».
- **Fer y Julss, cada uno con su cuenta de Amazon.** Amazon Fotos no tiene perfiles: quien entra con una cuenta lo ve todo. Si los dos Súbelo están (o han estado) conectados a la misma cuenta, el box no hace copias y lo avisa; cambiar o quitar la cuenta de Amazon pide la contraseña de Súbelo. Para que la segunda cuenta tenga también fotos ilimitadas, quien tenga Prime puede invitarla desde Amazon Fotos a tu «Archivo familiar» (Family Vault) y dejad los dos **apagado** «añadir subidas al Archivo familiar»: si no, todo lo subido se vería en el archivo compartido.
- **Resetear una contraseña desde la tele** también desconecta el Amazon de ese usuario, para que quien ponga la contraseña nueva no herede su sesión.

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
- API por HTTPS (token en la cabecera `X-Subelo-Token`, o cookie de sesión, tras `POST /api/login` con `user` y `password`; `POST /api/logout`; `GET /api/users` sin sesión). Las rutas son relativas a la carpeta del usuario: `GET /api/info`, `GET /api/list?path=`, `GET /api/folders?path=`, `POST /api/mkdir?path=`, `GET /api/exists?path=&name=`, `PUT /api/upload?path=&name=&size=&mtime=`, `GET /api/file?path=&name=[&download=1]` (admite Range), `GET /api/thumb?path=&name=`, `GET|POST /api/zip?path=[&name=…]`, `GET|POST /api/geo` (`path`, `name`… de fotos o carpetas, `lat`, `lon` y `con=<el otro usuario>` para que salga también en su mapa, o `clear=1`; ubicación puesta a mano, guardada en el box), `GET /api/places` (lo que sale en el mapa; lo que el otro comparte en el mapa se pide por la ruta de solo lectura `🗺 Mapa común/<dueño>/<carpeta>`), `POST /api/fav` (`path`, `name`…, `on=1|0`; la carpeta virtual `⭐ Favoritos` se lista como cualquier otra), `POST /api/password` (`password`, `newPassword`, `phones=1`), `GET /api/box` (versión, actualización y datos del certificado del ordenador), `GET /api/usage` (lo que ocupa cada usuario: `users` con `bytes`, `files`, `photos`, `videos`…, más `free`, `total`, `others` y `counting`). Sin sesión: `GET /subelo-certificado.crt` (la autoridad del ordenador) y `GET /manifest.webmanifest`. El puerto 8766 sirve lo mismo que el 8765 con el certificado del ordenador.
