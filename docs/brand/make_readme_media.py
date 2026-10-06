"""Builds the two moving pictures the README uses.

  docs/brand/readme-trace.svg   a pressure trace that draws itself under the header: the pull,
                                the hold under the dashed red ceiling, the drop, and the lime
                                "now" dot. CSS animation inside the SVG, so GitHub plays it;
                                it holds still for anyone who asked for reduced motion.
  docs/brand/screens.gif        the first four screens (today, the run, library, trainer),
                                cross-fading in a phone frame.

Run from the repo root:  python docs/brand/make_readme_media.py   (needs Pillow)
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
IMG = os.path.join(ROOT, 'docs', 'site', 'img')

TRACE_SVG = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 720 90" width="720" height="90" role="img" aria-label="A pressure trace: it pulls up, holds under a dashed red ceiling, drops, and pulls again">
<style>
  .t { fill: none; stroke: #F0A63C; stroke-width: 3.5; stroke-linecap: round; stroke-linejoin: round;
       stroke-dasharray: 1100; stroke-dashoffset: 1100; animation: draw 6s ease-in-out infinite; }
  .c { stroke: #ff5c52; stroke-width: 2; stroke-dasharray: 7 6; opacity: .75; }
  .d { fill: #C8FF3D; opacity: 0; animation: dot 6s ease-in-out infinite; }
  @keyframes draw { 0% { stroke-dashoffset: 1100; } 70% { stroke-dashoffset: 0; } 100% { stroke-dashoffset: 0; } }
  @keyframes dot  { 0%, 66% { opacity: 0; } 72%, 94% { opacity: 1; } 100% { opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .t { animation: none; stroke-dashoffset: 0; } .d { animation: none; opacity: 1; } }
</style>
<line class="c" x1="10" y1="16" x2="710" y2="16"/>
<path class="t" d="M10,78 H40 L90,26 H250 L268,70 H300 L350,26 H520 L538,70 H570 L620,26 H700"/>
<circle class="d" cx="700" cy="26" r="6"/>
</svg>
'''

def phone(shot, w=300):
    """A screenshot in a simple dark phone frame."""
    im = Image.open(os.path.join(IMG, shot)).convert('RGB')
    h = int(im.height * w / im.width)
    im = im.resize((w, h), Image.LANCZOS)
    pad = 10
    frame = Image.new('RGB', (w + 2 * pad, h + 2 * pad), '#0b0e13')
    mask = Image.new('L', im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=22, fill=255)
    frame.paste(im, (pad, pad), mask)
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle((1, 1, frame.width - 2, frame.height - 2), radius=30, outline='#2a3340', width=3)
    return frame

def main():
    with open(os.path.join(HERE, 'readme-trace.svg'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(TRACE_SVG)
    shots = ['today.png', 'run.png', 'library.png', 'trainer.png']
    phones = [phone(s) for s in shots]
    W = max(p.width for p in phones); H = max(p.height for p in phones)
    canvas = lambda: Image.new('RGB', (W, H), '#0d1117')
    def placed(p):
        c = canvas(); c.paste(p, ((W - p.width) // 2, (H - p.height) // 2)); return c
    frames, durations = [], []
    for i, p in enumerate(phones):
        cur, nxt = placed(p), placed(phones[(i + 1) % len(phones)])
        frames.append(cur); durations.append(2200)                 # hold each screen
        for k in range(1, 7):                                      # then cross-fade
            frames.append(Image.blend(cur, nxt, k / 7)); durations.append(70)
    pal = [f.quantize(colors=128, method=Image.MEDIANCUT) for f in frames]
    pal[0].save(os.path.join(HERE, 'screens.gif'), save_all=True, append_images=pal[1:],
                duration=durations, loop=0, optimize=True, disposal=1)
    print('readme media written')

if __name__ == '__main__':
    main()
