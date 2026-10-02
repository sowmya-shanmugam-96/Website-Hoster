"""Download an audio file and install it as the app's alarm notification sound.

Usage: python scripts/set_sound.py <audio-url-or-path>
Accepts .mp3, .ogg or .wav and installs it as app/src/main/res/raw/alarm_sound.<ext>.
"""
import glob
import os
import sys
import urllib.parse
import urllib.request

RAW = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
EXTENSIONS = (".mp3", ".ogg", ".wav")


def load(src):
    if os.path.exists(src):
        with open(src, "rb") as f:
            return f.read(), src
    req = urllib.request.Request(src, headers={"User-Agent": "url-to-apk"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read(), urllib.parse.urlparse(src).path


def main():
    data, name = load(sys.argv[1])
    ext = os.path.splitext(name)[1].lower()
    if ext not in EXTENSIONS:
        sys.exit(f"Alarm sound must be one of {', '.join(EXTENSIONS)}, got: {sys.argv[1]}")
    if not data:
        sys.exit(f"Alarm sound is empty: {sys.argv[1]}")

    os.makedirs(RAW, exist_ok=True)
    for old in glob.glob(os.path.join(RAW, "alarm_sound.*")):
        os.remove(old)
    with open(os.path.join(RAW, "alarm_sound" + ext), "wb") as f:
        f.write(data)
    print(f"Alarm sound installed as res/raw/alarm_sound{ext} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
