"""Render the legacy launcher icons (mipmap-<density>/ic_launcher.png) with Pillow."""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "app", "res")

BG = (14, 16, 19, 255)
BORDER = (51, 51, 58, 255)
FG = (255, 255, 255, 255)

FONTS = [
    r"C:\Windows\Fonts\arialbd.ttf",
    r"C:\Windows\Fonts\segoeuib.ttf",
    r"C:\Windows\Fonts\arial.ttf",
]
DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
SS = 8  # supersample factor


def pick_font() -> str:
    for path in FONTS:
        if os.path.exists(path):
            return path
    raise SystemExit("no truetype font found")


def render(size: int, font_path: str) -> Image.Image:
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle(
        [0, 0, s - 1, s - 1],
        radius=int(s * 0.22),
        fill=BG,
        outline=BORDER,
        width=max(1, int(s * 0.014)),
    )

    wanted = s * 0.58
    probe = ImageFont.truetype(font_path, 1000)
    left, top, right, bottom = draw.textbbox((0, 0), "00", font=probe, stroke_width=0)
    text_w = right - left
    text_h = bottom - top
    pt = max(1, int(1000 * wanted / text_w))
    font = ImageFont.truetype(font_path, pt)
    left, top, right, bottom = draw.textbbox((0, 0), "00", font=font)
    draw.text(((s - (right - left)) / 2 - left, (s - (bottom - top)) / 2 - top), "00",
              font=font, fill=FG)
    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    font_path = pick_font()
    for density, size in DENSITIES:
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        target = os.path.join(folder, "ic_launcher.png")
        render(size, font_path).save(target)
        print("wrote", target)


if __name__ == "__main__":
    main()
