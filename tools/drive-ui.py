"""Drive the installed app on a device and read its widget tree back.

    python tools/drive-ui.py dump
    python tools/drive-ui.py tap     "<text or resource-id substring>"
    python tools/drive-ui.py swipe   "<text or resource-id substring>" <dy>     # negative = up
    python tools/drive-ui.py hold    "<text or resource-id substring>" <ms>     # for the rename dwell
    python tools/drive-ui.py shot    <name>                                     # -> verify/<name>.png

Complements tools/verify-ui.sh: that script runs the standalone scoreboard trace, this one is for
driving a specific screen by hand (linked-match mode, dialogs) where the target is discovered from the
dump rather than hardcoded. Serial and adb path can be overridden with ADB and SERIAL.
"""
import os
import re
import subprocess
import sys

ADB = os.environ.get("ADB", r"C:/Users/kevin/score/toolchain/sdk/platform-tools/adb.exe")
SERIAL = os.environ.get("SERIAL", "emulator-5554")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "build")
LOCAL = os.path.join(OUT, "ui-drive.xml")


def dump_tree():
    os.makedirs(OUT, exist_ok=True)
    subprocess.run([ADB, "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/ui-drive.xml"],
                   capture_output=True)
    subprocess.run([ADB, "-s", SERIAL, "pull", "/sdcard/ui-drive.xml", LOCAL], capture_output=True)
    with open(LOCAL, encoding="utf-8") as handle:
        return handle.read()


def find(xml, pattern):
    """Centre of the first node whose text or resource id contains / equals the pattern."""
    for tag in re.findall(r"<node[^>]*>", xml):
        d = dict(re.findall(r'([\w-]+)="([^"]*)"', tag))
        rid = (d.get("resource-id") or "").replace("com.kevin.score:id/", "")
        if pattern in (d.get("text") or "") or pattern == rid:
            m = re.findall(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", d.get("bounds", ""))
            if m:
                x0, y0, x1, y1 = map(int, m[0])
                return (x0 + x1) // 2, (y0 + y1) // 2
    return None


def show(xml):
    rows = []
    for tag in re.findall(r"<node[^>]*>", xml):
        d = dict(re.findall(r'([\w-]+)="([^"]*)"', tag))
        text = d.get("text") or ""
        rid = (d.get("resource-id") or "").replace("com.kevin.score:id/", "")
        if text or rid:
            rows.append(f"  {(d.get('class') or '').split('.')[-1]:12} {rid:16} {text!r}")
    print("\n".join(rows))


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if cmd == "dump":
        show(dump_tree())
        return
    if cmd == "shot":
        name = sys.argv[2]
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        subprocess.run([ADB, "-s", SERIAL, "shell", "screencap", "-p", "/sdcard/s.png"],
                       capture_output=True)
        subprocess.run([ADB, "-s", SERIAL, "pull", "/sdcard/s.png",
                        os.path.join(root, "verify", name + ".png")], capture_output=True)
        print("captured verify/%s.png" % name)
        return

    pattern = sys.argv[2]
    point = find(dump_tree(), pattern)
    if point is None:
        print("not found: %r" % pattern)
        sys.exit(1)
    x, y = point
    if cmd == "tap":
        subprocess.run([ADB, "-s", SERIAL, "shell", "input", "tap", str(x), str(y)])
        print("tapped %r at (%d,%d)" % (pattern, x, y))
    elif cmd == "swipe":
        dy = int(sys.argv[3])
        subprocess.run([ADB, "-s", SERIAL, "shell", "input", "swipe",
                        str(x), str(y), str(x), str(y + dy), "250"])
        print("swiped %r at (%d,%d) by %d" % (pattern, x, y, dy))
    elif cmd == "hold":
        ms = sys.argv[3]
        subprocess.run([ADB, "-s", SERIAL, "shell", "input", "swipe",
                        str(x), str(y), str(x), str(y), ms])
        print("held %r for %sms" % (pattern, ms))
    else:
        raise SystemExit("unknown command " + cmd)


if __name__ == "__main__":
    main()
