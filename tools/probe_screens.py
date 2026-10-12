"""Probe a scoreboard screenshot for the expected rendering.

usage: python tools/probe_screens.py <png> [png ...]

Checks the things a widget dump cannot: that each side paints its own panel colour, that the glyphs
use the correct contrast colour, that the divider sits on the content midpoint, that the score digits
are centred in their halves, and that the + / - tiles really are the green and red they claim to be.
"""
import sys
from PIL import Image

BG = (14, 16, 19)
HOME_BG = (75, 128, 95)
HOME_TEXT = (255, 255, 255)
AWAY_BG = (153, 99, 91)
AWAY_TEXT = (255, 232, 164)
PLUS_TILE = (27, 107, 57)
MINUS_TILE = (133, 46, 33)
TOL = 22


def near(a, b):
    return all(abs(x - y) <= TOL for x, y in zip(a, b))


def scan(im, box, colour):
    x0, y0, x1, y1 = box
    return [(x, y) for y in range(y0, y1, 2) for x in range(x0, x1, 2)
            if near(im.getpixel((x, y)), colour)]


def bbox(pts):
    if not pts:
        return "NONE"
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return f"x[{min(xs)}..{max(xs)}] y[{min(ys)}..{max(ys)}]"


def divider(im, w, h):
    """Longest run of background-coloured pixels on the middle row, ignoring the outer quarters
    (display cutouts and system bars are painted the same colour)."""
    row = h // 2
    lo, hi = w // 4, (3 * w) // 4
    best, run, start = (0, None), 0, None
    for x in range(lo, hi):
        if near(im.getpixel((x, row)), BG):
            if run == 0:
                start = x
            run += 1
        else:
            if run > best[0]:
                best = (run, start)
            run = 0
    if run > best[0]:
        best = (run, start)
    return best


def content_span(im, w, h):
    """Column span occupied by the two panels - the app window, which is inset by the status and
    navigation bars, so the framebuffer midpoint is the wrong reference."""
    row = h // 2
    cols = [x for x in range(w)
            if near(im.getpixel((x, row)), HOME_BG) or near(im.getpixel((x, row)), AWAY_BG)]
    return (min(cols), max(cols)) if cols else None


def main() -> None:
    for path in sys.argv[1:]:
        im = Image.open(path).convert("RGB")
        w, h = im.size
        print(f"\n{path}  {w}x{h}  ({'landscape' if w > h else 'portrait'})")

        span = content_span(im, w, h)
        run, start = divider(im, w, h)
        if start is None or span is None:
            print("  divider: none found (expected a background-coloured seam between the panels)")
        else:
            want = (span[0] + span[1]) // 2
            print(f"  app content columns {span[0]}..{span[1]} (midpoint {want}); "
                  f"divider x {start}..{start + run - 1} -> off by {start - want} px")

        mid = start if start is not None else w // 2
        for label, x0, x1, panel, text in (("HOME", 0, mid, HOME_BG, HOME_TEXT),
                                           ("AWAY", mid, w, AWAY_BG, AWAY_TEXT)):
            panel_px = len(scan(im, (x0, int(h * 0.55), x1, h), panel))
            glyphs = scan(im, (x0, int(h * 0.08), x1, int(h * 0.52)), text)
            print(f"  {label}: panel px {panel_px:6}  glyphs {bbox(glyphs)}")

        band = (0, int(h * 0.6), w, h)
        print(f"  step tiles: green {len(scan(im, band, PLUS_TILE)):6} px   "
              f"red {len(scan(im, band, MINUS_TILE)):6} px")


if __name__ == "__main__":
    main()
