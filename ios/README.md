# VOD Pro para iPhone y iPad

Versión de iOS de la app de celular de Android. Usa el mismo panel, las mismas cuentas, el
mismo token de validación y la misma nube por perfil (Mi lista y Continuar viendo se comparten
con Android y Roku).

## Cómo obtener la app

Cada vez que se sube un cambio a la rama `ios`, GitHub compila la app en un Mac:

1. En GitHub abre **Actions → iOS** y entra a la última ejecución en verde.
2. Descarga el artefacto **VODPro-iOS** (trae `VODPro-sin-firmar.ipa`).
3. Instálalo firmándolo con tu Apple ID usando **Sideloadly** o **AltStore** (Windows o Mac),
   o súbelo a tu cuenta de Apple Developer para TestFlight.

También se puede abrir en Xcode (Mac):

```
brew install xcodegen
cd ios
xcodegen generate
open VODPro.xcodeproj
```

## Qué incluye (igual que Android)

| Android | iOS |
| --- | --- |
| Login con usuario y contraseña, o con la MAC vinculada (campos vacíos) | Igual. La "MAC" sale del identificador del equipo guardado en el llavero (no cambia al reinstalar) |
| Token `sha256(usuario + MAC + hora de Panamá + clave)` en el login y en la verificación | Igual |
| Verificación de la línea cada 30 min; 7 días sin verificar cierra la sesión | Igual |
| Marca del distribuidor (logo, fondo, splash, nombre, nota) cada 5 min | Igual |
| Aviso de vencimiento en una página web sobre la app | Igual (con `Android.closeModal()`) |
| Perfiles "¿Quién está viendo?" con PIN, avatar, crear, renombrar y eliminar | Igual |
| Nube por perfil (`sync_userdata.php`) | Igual: mismos registros e ids que Android |
| Inicio con destacados, Películas, Series, Favoritos, Buscar, ficha, reparto | Igual |
| Búsqueda automática de servidores (Cuevana, PoseidonHD2, CineCalidad, VixSrc) | Igual, en el idioma de Ajustes |
| En Vivo por categorías y países, guía, señales alternativas | Igual |
| Reporte de errores al panel | Igual |

**No se incluye:** la actualización obligatoria (es un APK de Android) ni la verificación de
firma del APK.

## Pruebas de la lógica

Se corren en el Mac de GitHub y también en Linux:

```
cd ios
swift test
```
