"""Download an image and install it as the app's launcher icon.

Usage: python scripts/set_icon.py <image-url-or-path>
Replaces the default adaptive globe icon with PNGs for every density.
"""
import io
import os
import sys
import urllib.request

from PIL import Image

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def load(src):
    if os.path.exists(src):
        return Image.open(src)
    req = urllib.request.Request(src, headers={"User-Agent": "url-to-apk"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return Image.open(io.BytesIO(r.read()))


def main():
    img = load(sys.argv[1]).convert("RGBA")
    # Centre-crop to a square
    side = min(img.size)
    left, top = (img.width - side) // 2, (img.height - side) // 2
    img = img.crop((left, top, left + side, top + side))

    for density, px in SIZES.items():
        out_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        img.resize((px, px), Image.LANCZOS).save(os.path.join(out_dir, "ic_launcher.png"))

    adaptive = os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml")
    if os.path.exists(adaptive):
        os.remove(adaptive)
    print("Icon installed for", ", ".join(SIZES))


if __name__ == "__main__":
    main()
