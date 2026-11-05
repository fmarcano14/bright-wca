# Bright WCA

Keeps your Wall Color Adaptation colors while raising brightness on Anker projectors (Google TV / Android TV). Tested on Cosmos 4K SE, firmware V14.

## Why
After a recent firmware update on some Anker Google TV projectors, Wall Color Adaptation fixes the colors but drops brightness a lot. Raising brightness turns the correction off and brings back a green tint. Bright WCA raises brightness while keeping the corrected colors. No root needed.

## Install
Easiest way, using the free **Downloader** app:
1. On the projector, install **Downloader** (by AFTVnews) from the Google Play Store.
2. Allow Downloader to install apps: Settings → Apps → Security & restrictions → Unknown sources → turn on **Downloader**.
3. Open Downloader, type the code **8579547** and press Go (or type the full address:
   `https://github.com/fmarcano14/bright-wca/releases/latest/download/bright-wca.apk`)
4. When it finishes, choose **Install**, then **Open**.

Or download `bright-wca.apk` from [Releases](https://github.com/fmarcano14/bright-wca/releases) and sideload it with adb.

## First run
Turn on **Wireless debugging** once (Developer options). The app grants itself the permission it needs; you can turn debugging off afterwards.

## Modes
- **A – Brightness:** keep your current colors, pick Low / Normal / High / Max.
- **B – Fix colors:** calibrate with a white sheet, or pick a tone and brightness by hand.

## Screenshots
| | |
|---|---|
| ![Chooser](screenshots/01-chooser.png) | ![Brightness presets](screenshots/02-brightness-presets.png) |
| ![Mode B](screenshots/03-mode-b-options.png) | ![Calibration](screenshots/04-calibration-steps.png) |
| ![Manual tone](screenshots/05-manual-tone.png) | ![Manual brightness](screenshots/06-manual-brightness.png) |
| ![Applied](screenshots/07-applied.png) | |

## Disclaimer
Unofficial. Use at your own risk. Not affiliated with or endorsed by Anker.

## License
MIT. `native/spake2` (SPAKE2 pairing) is LGPL-3.0, see its LICENSE.

---

## Español
Mantiene los colores de Wall Color Adaptation y sube el brillo en proyectores Anker. Probado en Cosmos 4K SE, firmware V14.

- **Por qué:** tras una actualización reciente del firmware en algunos proyectores Anker con Google TV, Wall Color Adaptation corrige los colores pero baja mucho el brillo; al subirlo se pierde la corrección y aparece un tono verde. Bright WCA sube el brillo sin perder esos colores, sin root.
- **Instalar (fácil):** instala **Downloader** (de AFTVnews) desde Google Play; en Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas, activa **Downloader**; abre Downloader, escribe el código **8579547** (o la dirección `https://github.com/fmarcano14/bright-wca/releases/latest/download/bright-wca.apk`), pulsa Go y luego **Instalar** y **Abrir**.
- **Primera vez:** activa **Depuración inalámbrica** una vez; la app se da el permiso sola.
- **Modos:** A sube el brillo sin cambiar colores; B ajusta colores con hoja blanca o a mano.
- **Aviso:** no oficial, úsala bajo tu propio riesgo, sin relación con Anker.
