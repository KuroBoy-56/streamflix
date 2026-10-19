# VOD Pro para Roku

Versión para Roku de la app Android (rama `main`). Está escrita en BrightScript/SceneGraph,
el lenguaje de los canales de Roku, y usa el mismo panel, las mismas cuentas y la misma nube
que la app Android.

## Instalar en un Roku (sin publicar en la tienda)

1. En el control del Roku, en la pantalla de inicio, pulsa:
   **Inicio ×3, Arriba ×2, Derecha, Izquierda, Derecha, Izquierda, Derecha**.
2. Activa el **modo desarrollador**, acepta y elige una contraseña. El Roku se reinicia y
   muestra su dirección IP, por ejemplo `http://192.168.1.50`.
3. En una computadora de la misma red, abre esa dirección en el navegador. El usuario es
   `rokudev` y la contraseña es la que elegiste.
4. Pulsa **Upload**, elige `roku/dist/VODPro-roku.zip` y luego **Install with zip**.
5. El canal se abre solo y queda en la pantalla de inicio del Roku como "VOD Pro".

Para generar el zip de nuevo después de cambiar algo: `sh roku/build.sh`.

Para publicarlo en la tienda de canales de Roku, sube ese mismo zip en el
[Developer Dashboard de Roku](https://developer.roku.com) (necesitas una cuenta de desarrollador).

## Qué hace igual que en Android

| Android | Roku |
|---|---|
| Inicio de sesión con usuario y contraseña, o con la MAC vinculada (campos vacíos) | Igual (`flixlat_api.php`, mismo token SHA-256 con la hora de Panamá) |
| La MAC aparece en el login | Igual. En Roku la MAC sale del identificador del canal en ese equipo, así que es distinta a la del celular: hay que vincularla en el panel |
| Verificación de la línea cada 30 min (`check_security.php`): cuenta vencida o bloqueada cierra la sesión; 7 días sin verificar también | Igual |
| Aviso de vencimiento (`ntf_api.php`) tras el splash | Se muestra como tarjeta con el texto de la página (Roku no tiene navegador) |
| Actualización obligatoria del panel | No aplica en Roku (es un APK para Android): se ignora. El canal se actualiza subiendo el zip nuevo |
| Marca del distribuidor: logo, fondo, splash, nombre y nota en Ajustes; se actualiza cada 5 min (`config.json` / `config_<APP ID>.json`) | Igual |
| Perfiles "¿Quién está viendo?" con PIN, avatar, crear, renombrar y eliminar (`profiles.php`) | Igual (botón * edita un perfil) |
| Favoritos, historial y "Continuar viendo" sincronizados por perfil (`sync_userdata.php`) | Igual, con el mismo formato: lo que ves en Roku aparece en Android y al revés (proveedor TMDb) |
| Catálogo TMDb con la clave del panel: tendencias, populares, anime y por plataforma; películas y series por género; ficha con reparto y recomendaciones; temporadas y episodios; búsqueda; filmografía de cada actor | Igual |
| En Vivo estilo TiviMate: carpetas por categoría y país, canales, vista previa y guía (EPG) | Igual |
| Líneas de canales del panel, URL personal y línea por defecto; sin duplicados ni canales para adultos; si una señal falla pasa sola a otra línea | Igual |
| Caché de canales: la segunda vez abre al instante con la copia guardada y la lista se actualiza aparte si tiene más de 30 min | Igual |
| En pantalla completa, lista de canales de la categoría para cambiar sin salir | Igual: OK o ◀ abre la lista, ▲▼ cambia de canal |
| En Vivo desaparece si el panel no le asignó canales a la cuenta | Igual |
| Logo y fondo según el distribuidor (marca blanca del administrador) | Igual. El icono del canal en el menú del Roku es fijo: Roku no deja cambiarlo desde la app |
| Reporte de errores al panel (`log_error.php`) | Igual |
| Idioma | Ajustes → **Idioma del audio**: Español latino (por defecto) o Inglés. En español latino, si el título no trae audio en español se activan los subtítulos en español. Títulos y sinopsis de TMDb en español latino |
| Cerrar sesión, sincronizar ahora | Igual (Ajustes) |

## Diferencias que hay que conocer

- **Servidores de video (búsqueda automática, como Android).** Al reproducir, el canal busca el
  título en **Cuevana**, **PoseidonHD2** y **CineCalidad** (los dos primeros por el ID exacto de
  TMDb) y arma la lista de servidores en el idioma de Ajustes:
  - Español latino: servidores latinos → VixSrc (audio o subtítulos en español) → subtitulados → castellano.
  - Inglés: VixSrc → servidores con audio original.
  El reproductor prueba uno por uno; si un video falla pasa solo al siguiente desde el mismo
  minuto. Lee StreamWish, VidHide/FileLions, Voe, DoodStream, Streamtape, Ok.ru, MixDrop, Uqload,
  Goodstream y similares. **No** se pueden abrir en Roku: Filemoon (exige una firma del
  navegador), Netu/Waaw y YouTube. PelisPlus y SoloLatino tampoco, porque exigen un navegador
  real para pasar Cloudflare.
- **Proveedores del panel.** Los que no son de películas/series en español (Pluto TV, Vavoo...)
  no se cargaron en Roku. En Vivo usa las líneas de canales del panel.
- **Marca del distribuidor.** Logo, fondo, splash, nombre y nota cambian solos según la cuenta
  (administrador con marca blanca o dueño), igual que en Android. El icono y el nombre del canal
  en el menú principal del Roku vienen dentro del paquete y Roku no deja cambiarlos desde la app:
  para un distribuidor con su propio icono se genera un zip aparte con su logo y nombre.
- **Guía de canales.** Se leen guías XMLTV `.xml` de hasta unos 40 MB. Las guías `.gz` no se
  leen, porque Roku no descomprime archivos.
- **Ajustes propios de Android** (temas, tamaño de pantalla, subtítulos externos, idioma de la
  interfaz, tráileres de YouTube) no están. Los subtítulos que trae el video se activan desde
  el menú * del Roku si hace falta cambiarlos a mano. La interfaz está en español.

## Estructura

```
roku/
  manifest                 nombre, versión e iconos del canal
  source/                  lógica (main, utilidades, API del panel, TMDb, canales, nube)
  components/              pantallas SceneGraph (login, perfiles, inicio, ficha, episodios,
                           buscar, en vivo, ajustes, reproductor) y tarjetas
  images/                  iconos, splash y gráficos
  tests/selftest.brs       pruebas de la lógica de canales y textos (no van en el zip)
  build.sh                 genera dist/VODPro-roku.zip
```

Pruebas sin un Roku (necesita Node.js):

```
cd roku
npx brs tests/selftest.brs source/Utils.brs source/Live.brs source/Api.brs source/Catalog.brs source/Playback.brs source/Nodes.brs source/UserData.brs
cat tests/extract_test.brs source/Utils.brs source/Live.brs source/Playback.brs source/Sources.brs source/Extract.brs source/Api.brs source/Catalog.brs > /tmp/t.brs
npx -p brs-node brs-cli -n /tmp/t.brs
```
