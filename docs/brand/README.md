# Brand

Two names, one family.

- **OpenPE** is the project. Its logo has the pressure line inside the O and a ruler under
  the name.
- **OpenPump** is the app, and it's only about pumping. Its logo keeps the same O but drops
  the ruler. Under the name sit the pressure line and the dashed red ceiling it never
  crosses.

## Files

| File | Use |
|---|---|
| `openpe-wordmark-dark.png`, `-light.png` | OpenPE logo on dark and light backgrounds. |
| `openpump-wordmark-dark.png`, `-light.png` | OpenPump logo on dark and light backgrounds. |
| `avatar-512.png` | The OpenPE O, square and full bleed, for a GitHub profile picture. GitHub crops it to a circle. |
| `social-preview.png` | 1280×640, the card shown when the repo link is shared. Upload it under **Settings › General › Social preview**. |
| `social-preview.html` | Source of that card. |
| `icon.svg` | The app icon on its tile. Also the site favicon (`docs/site/favicon.svg`). |
| `mark.svg` | The OpenPump O on its own, no tile. |
| `openpump-icon/` | The Android launcher icon: its drawing, and `make_icons.py`, which builds every density. |

`make_brand.py` rebuilds everything in this folder except `openpump-icon/`:

```
python docs/brand/make_brand.py
```

It needs Google Chrome, which it runs headless to render the files. The wordmarks ship as
PNG because GitHub blocks web fonts inside README images. For a README that follows the
reader's theme:

```html
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/brand/openpump-wordmark-dark.png">
  <img src="docs/brand/openpump-wordmark-light.png" alt="OpenPump" width="330">
</picture>
```

## The app icon

"The O" in the Crisp-core neon style: the O from the OpenPump logo, the dashed red
ceiling, the amber pressure line and the lime "now" dot. The lines are drawn as a sharp
core with the glow behind them, not blurred. Android vectors can't blur, so the colour
layer ships as PNGs at every density. The themed layer (Android 13+) is a plain vector in
`app/src/main/res/drawable/ic_launcher_monochrome.xml`. Change it together with the
drawing.

## Colour

| Colour | Hex | Meaning in the app |
|---|---|---|
| Ground | `#0b0e13` (icon `#05070a`) | background |
| Amber | `#F0A63C` | the pressure line, what was commanded |
| Lime | `#C8FF3D` | now, go, the live point (`#79ad00` on white) |
| Red | `#ff5c52` | the ceiling, anything critical |
| White | `#eef1f5` | the O, the letters |

## Type

Archivo (800, slightly condensed) for the names and headings, Public Sans for text, and
Roboto Mono for figures.
