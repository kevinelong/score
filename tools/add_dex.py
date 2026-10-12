"""Add compiled classes.dex into the resource APK produced by aapt2 link (in place)."""
import sys
import zipfile


def main() -> None:
    src, dst, dexs = sys.argv[1], sys.argv[2], sys.argv[3:]
    with zipfile.ZipFile(src) as zin:
        entries = [(i, zin.read(i.filename)) for i in zin.infolist()]

    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info, data in entries:
            if info.filename in ("classes.dex", "META-INF/MANIFEST.MF") or info.filename.startswith("META-INF/"):
                continue
            clone = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            clone.compress_type = info.compress_type
            clone.external_attr = info.external_attr
            clone.internal_attr = info.internal_attr
            clone.create_system = info.create_system
            zout.writestr(clone, data)
        for dex in dexs:
            name = "classes.dex" if dex.endswith("classes.dex") else dex.split("/")[-1]
            # fixed timestamp (the DOS epoch aapt2 uses): keeps rebuilds byte-identical, so a
            # published hash identifies the build rather than the moment it ran
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(dex, "rb") as handle:
                zout.writestr(info, handle.read())
    print("wrote", dst, "with", len(dexs), "dex file(s)")


if __name__ == "__main__":
    main()
