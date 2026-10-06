"""Builds every OpenPump launcher-icon file from one drawing.

The icon is "The O" in the Crisp-core neon style: the O from the OpenPump logo, a dashed
red ceiling, the amber pressure line under it and the lime "now" dot, drawn as a sharp
bright core over a tight glow.

Android's vector format cannot blur, so the colour layer is rasterised to PNG at every
density. The themed (monochrome) layer needs no glow and stays a vector.

Run from the repo root:  python docs/brand/openpump-icon/make_icons.py
Needs Google Chrome (headless) for rasterising; the SVGs are written regardless.
"""
import os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
RES  = os.path.join(ROOT, 'app', 'src', 'main', 'res')
CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'

GROUND = '#05070a'
A, R, L = '#F0A63C', '#ff5c52', '#C8FF3D'
TRACE = 'M29,60 H40 L46,40 L55,70 L60,54 H69'
RC = 'stroke-linecap="round" stroke-linejoin="round" fill="none"'

def layer(ring_w, line_w, ring_c, line_c, ceil_c, dot_c, dot_r, op=1, extra=''):
    return (f'<g opacity="{op}" {extra}>'
            f'<circle cx="50" cy="50" r="35" {RC} stroke="{ring_c}" stroke-width="{ring_w}"/>'
            f'<path d="M29,33 H71" stroke="{ceil_c}" stroke-width="{max(2.5, line_w*.55):.1f}" stroke-dasharray="4 3.5"/>'
            f'<path d="{TRACE}" {RC} stroke="{line_c}" stroke-width="{line_w}"/>'
            f'<circle cx="70" cy="54" r="{dot_r}" fill="{dot_c}"/></g>')

DEFS = ('<defs><filter id="glow" x="-40%" y="-40%" width="180%" height="180%">'
        '<feGaussianBlur stdDeviation="1.6"/></filter></defs>')
# the mark itself, in a 100x100 box
MARK = (layer(11, 8, '#9fb4ff', A, R, L, 7.5, op=.55, extra='filter="url(#glow)"')
        + layer(7, 5, '#f4f7ff', '#ffd79a', '#ff8a82', '#e9ffb0', 5.5))

def place(size, scale, cx=None, cy=None):
    cx = size/2 if cx is None else cx; cy = size/2 if cy is None else cy
    return f'<g transform="translate({cx-50*scale:.2f} {cy-50*scale:.2f}) scale({scale})">{MARK}</g>'

def svg(vb, body):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vb} {vb}" width="{vb}" height="{vb}">{DEFS}{body}</svg>'

# Adaptive foreground: 108dp canvas, transparent, the O inside the 66dp safe circle.
FOREGROUND = svg(108, place(108, .56))
# Full icon on its own tile (legacy launchers, Play Store, docs).
TILE = svg(128, f'<rect x="0.5" y="0.5" width="127" height="127" rx="28" fill="{GROUND}" stroke="#141a22"/>' + place(128, .92))
# Legacy round icon.
ROUND = svg(128, f'<circle cx="64" cy="64" r="63.5" fill="{GROUND}"/>' + place(128, .84))

def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f: f.write(text)

def raster(svg_text, px, out_png):
    """Screenshot the SVG at exactly px x px with a transparent background."""
    os.makedirs(os.path.dirname(out_png), exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, 'p.html')
        with open(page, 'w', encoding='utf-8') as f:
            f.write('<!doctype html><html><head><style>html,body{margin:0;background:transparent}'
                    f'svg{{display:block;width:{px}px;height:{px}px}}</style></head><body>{svg_text}</body></html>')
        shot = os.path.join(tmp, 's.png')
        subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars',
                        '--default-background-color=00000000', f'--window-size={px},{px}',
                        f'--screenshot={shot}', 'file:///' + page.replace('\\', '/')],
                       check=True, capture_output=True)
        os.replace(shot, out_png)

def main():
    write(os.path.join(HERE, 'foreground.svg'), FOREGROUND)
    write(os.path.join(HERE, 'icon.svg'), TILE)
    write(os.path.join(HERE, 'icon-round.svg'), ROUND)
    if not os.path.exists(CHROME):
        print('Chrome not found: SVGs written, PNGs skipped'); return 1
    dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
    for d, k in dens.items():
        raster(FOREGROUND, round(108*k), os.path.join(RES, f'mipmap-{d}', 'ic_launcher_foreground.png'))
        raster(TILE,       round(48*k),  os.path.join(RES, f'mipmap-{d}', 'ic_launcher.png'))
        raster(ROUND,      round(48*k),  os.path.join(RES, f'mipmap-{d}', 'ic_launcher_round.png'))
    raster(TILE, 512, os.path.join(HERE, 'icon-512.png'))
    print('icons written')
    return 0

if __name__ == '__main__':
    sys.exit(main())
