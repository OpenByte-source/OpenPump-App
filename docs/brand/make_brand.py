"""Builds the OpenPE / OpenPump brand files from one set of drawings.

Outputs (docs/brand/):
  openpe-wordmark-dark.png   OpenPE logo for dark backgrounds (white letters)
  openpe-wordmark-light.png  OpenPE logo for light backgrounds (ink letters)
  openpump-wordmark-dark.png / -light.png   the same for OpenPump
  avatar-512.png             the OpenPE O, for the GitHub account
  social-preview.png         1280x640 card: the app icon and the name
  social-preview.html        its source
  mark.svg                   the OpenPump O on its own
  icon.svg                   the app icon (copied from openpump-icon/icon.svg)
and docs/site/favicon.svg (the app icon).

The wordmarks use Archivo from Google Fonts and are rasterised with headless Chrome, which
fetches the font. They ship as PNG because GitHub blocks web fonts inside README images.

Run from the repo root:  python docs/brand/make_brand.py
"""
import os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'
A, L, R, G, W, INK = '#F0A63C', '#C8FF3D', '#ff5c52', '#0b0e13', '#eef1f5', '#10151d'
RC = 'fill="none" stroke-linecap="round" stroke-linejoin="round"'
FONTS = ('<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wdth,wght@62..125,400..900'
         '&family=Public+Sans:wght@400;500&display=block">')

# --- the two O's, in a 100x100 box -------------------------------------------------------
def pe_O(ring, dot=L):   # OpenPE: the pressure line in the O
    return (f'<circle cx="50" cy="50" r="35" {RC} stroke="{ring}" stroke-width="17"/>'
            f'<path d="M29,56 H40 L46,35 L55,69 L60,52 H69" {RC} stroke="{A}" stroke-width="7"/>'
            f'<circle cx="70" cy="52" r="6" fill="{dot}"/>')
def pump_O(ring, dot=L): # OpenPump: the line under a dashed red ceiling
    return (f'<circle cx="50" cy="50" r="35" {RC} stroke="{ring}" stroke-width="17"/>'
            f'<path d="M29,33 H71" stroke="{R}" stroke-width="3.5" stroke-dasharray="4 3.5"/>'
            f'<path d="M29,60 H40 L46,40 L55,70 L60,54 H69" {RC} stroke="{A}" stroke-width="6.5"/>'
            f'<circle cx="70" cy="54" r="5.5" fill="{dot}"/>')

def wordmark_html(brand, theme):
    ink = W if theme == 'dark' else INK
    minor = '#4a5463' if theme == 'dark' else '#9aa3af'
    base = '#3a4453' if theme == 'dark' else '#c3cad4'
    dot = L if theme == 'dark' else '#79ad00'   # lime washes out on white; a deeper green keeps the 'now'
    O = pe_O(ink, dot) if brand == 'pe' else pump_O(ink, dot)
    tail = 'PE' if brand == 'pe' else 'Pump'
    if brand == 'pe':   # the ruler under the name
        under = (f'<div class="rule" style="border-bottom:5px solid {base};background:'
                 f'repeating-linear-gradient(to right,{minor} 0 5px,transparent 5px 20px) bottom/100% 15px no-repeat,'
                 f'repeating-linear-gradient(to right,{A} 0 5px,transparent 5px 100px) bottom/100% 32px no-repeat"><i></i></div>')
    else:               # the ceiling and the trace under the name
        under = (f'<div class="plotw"><svg class="plot" viewBox="0 0 240 26" preserveAspectRatio="xMinYMid meet">'
                 f'<path d="M2,3 H236" stroke="{R}" stroke-width="2" stroke-dasharray="6 5"/>'
                 f'<path d="M2,20 H70 L84,8 L98,23 L106,15 H232" fill="none" stroke="{A}" stroke-width="3" '
                 f'stroke-linejoin="round" stroke-linecap="round"/></svg><i style="top:calc(58% - 17px)"></i></div>')
    return f'''<!doctype html><html><head><meta charset="utf-8">{FONTS}<style>
html,body{{margin:0;background:transparent}}
.lg{{display:inline-flex;flex-direction:column;gap:18px;padding:40px;color:{ink};line-height:1;
  font-family:'Archivo',sans-serif;font-weight:800;font-stretch:90%;letter-spacing:-.01em;font-size:120px}}
.lg b{{font-weight:inherit;color:{A}}}
.word{{display:flex;align-items:baseline}}
.gl{{width:.86em;height:.86em;margin-right:.03em;position:relative;top:.05em;flex:none}}
.rule{{position:relative;height:32px}}
.rule i,.plotw i{{position:absolute;right:-20px;bottom:-20px;width:36px;height:36px;border-radius:50%;background:{dot}}}
.plotw{{position:relative;width:0;min-width:100%}}
.plot{{display:block;width:100%;height:auto;overflow:visible}}
</style></head><body><div class="lg"><div class="word"><svg class="gl" viewBox="0 0 100 100" overflow="visible">{O}</svg>
<span>pen<b>{tail}</b></span></div>{under}</div></body></html>'''

def avatar_svg():
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">'
            f'<rect width="128" height="128" fill="{G}"/><g transform="translate(18 18) scale(.92)">{pe_O(W)}</g></svg>')

def social_html(icon_svg):
    return f'''<!doctype html>
<!-- Source of social-preview.png (1280x640). Rebuilt by docs/brand/make_brand.py. -->
<html><head><meta charset="utf-8">{FONTS}<style>
html,body{{margin:0;width:1280px;height:640px;overflow:hidden;background:#05070a}}
body{{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;
  background:radial-gradient(ellipse 60% 70% at 50% 42%,#111826 0%,#05070a 70%)}}
.ic{{width:300px;height:300px}} .ic svg{{width:100%;height:100%;display:block}}
.nm{{font-family:'Archivo',sans-serif;font-weight:800;font-stretch:88%;font-size:64px;color:{W};letter-spacing:-.01em;margin-top:8px}}
.sub{{font-family:'Public Sans',sans-serif;font-size:24px;color:#9ba5b2}}
</style></head><body><div class="ic">{icon_svg}</div><div class="nm">OpenPump</div>
<div class="sub">the open-source pump app</div></body></html>'''

# --- rendering ------------------------------------------------------------------------------
def shoot(html, w, h, out, transparent=True, trim=False, pad=0):
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, 'p.html'); shot = os.path.join(tmp, 's.png')
        with open(page, 'w', encoding='utf-8') as f: f.write(html)
        args = [CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', f'--window-size={w},{h}',
                '--virtual-time-budget=9000', f'--screenshot={shot}', 'file:///' + page.replace('\\', '/')]
        if transparent: args.insert(4, '--default-background-color=00000000')
        subprocess.run(args, check=True, capture_output=True)
        if trim:
            from PIL import Image
            im = Image.open(shot).convert('RGBA'); box = im.getchannel('A').getbbox()
            im = im.crop(box)
            if pad:
                c = Image.new('RGBA', (im.width + 2*pad, im.height + 2*pad), (0, 0, 0, 0)); c.paste(im, (pad, pad)); im = c
            im.save(out)
        else:
            shutil.copyfile(shot, out)

def main():
    if not os.path.exists(CHROME):
        print('Chrome not found'); return 1
    for brand in ('pe', 'pump'):
        for theme in ('dark', 'light'):
            name = ('openpe' if brand == 'pe' else 'openpump') + f'-wordmark-{theme}.png'
            shoot(wordmark_html(brand, theme), 1400, 520, os.path.join(HERE, name), trim=True, pad=8)
    av = avatar_svg().replace('<svg ', '<svg style="display:block;width:512px;height:512px" ', 1)
    shoot('<!doctype html><html><body style="margin:0">' + av + '</body></html>',
          512, 512, os.path.join(HERE, 'avatar-512.png'), transparent=False)
    icon = open(os.path.join(HERE, 'openpump-icon', 'icon.svg'), encoding='utf-8').read()
    html = social_html(icon)
    with open(os.path.join(HERE, 'social-preview.html'), 'w', encoding='utf-8', newline='\n') as f: f.write(html)
    shoot(html, 1280, 640, os.path.join(HERE, 'social-preview.png'), transparent=False)
    with open(os.path.join(HERE, 'mark.svg'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100"><title>OpenPump</title>{pump_O(W)}</svg>')
    shutil.copyfile(os.path.join(HERE, 'openpump-icon', 'icon.svg'), os.path.join(HERE, 'icon.svg'))
    shutil.copyfile(os.path.join(HERE, 'openpump-icon', 'icon.svg'), os.path.join(ROOT, 'docs', 'site', 'favicon.svg'))
    print('brand files written'); return 0

if __name__ == '__main__':
    sys.exit(main())
