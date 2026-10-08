#!/usr/bin/env python3
"""
Gathers the per-country files built by build_network.py into place:

  web/data/transit/<CC>.txt     every country, served with the web viewer
                                and downloaded by the app when it is there
  web/data/transit/index.json   which countries there are, the box their
                                stations fall in, size and build date
  apps/android/app/src/main/assets/transit/IN.txt
                                India, bundled with the app (most journeys)

A country whose build failed keeps the file it had.

    python3 tools/transit/collect.py built/
"""
import json
import os
import shutil
import sys

WEB = "web/data/transit"
BUNDLED = {"IN": "apps/android/app/src/main/assets/transit"}


def summary(path):
    lats, lngs, built = [], [], ""
    metro = rail = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("# Transit") and "Built " in line:
                built = line.split("Built ")[1].split()[0]
            parts = line.rstrip("\n").split("|")
            if parts[0] in ("S", "T") and len(parts) >= 3:
                try:
                    lat, lng = float(parts[1]), float(parts[2])
                except ValueError:
                    continue
                if parts[0] == "S":
                    metro += 1
                else:
                    rail += 1
                lats.append(lat); lngs.append(lng)
    if not lats:
        return None
    return {"bbox": [round(min(lats) - 0.2, 2), round(min(lngs) - 0.2, 2), round(max(lats) + 0.2, 2), round(max(lngs) + 0.2, 2)],
            "stations": metro, "railway": rail, "bytes": os.path.getsize(path), "built": built}


def main(built):
    os.makedirs(WEB, exist_ok=True)
    for name in sorted(os.listdir(built)):
        if name.endswith(".txt") and len(name) == 6:
            shutil.copyfile(os.path.join(built, name), os.path.join(WEB, name))
    index = {}
    for name in sorted(os.listdir(WEB)):
        if name.endswith(".txt") and len(name) == 6:
            s = summary(os.path.join(WEB, name))
            if s:
                index[name[:2]] = s
    with open(os.path.join(WEB, "index.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"source": "OpenStreetMap contributors, ODbL 1.0", "countries": index}, f, indent=1, sort_keys=True)
        f.write("\n")
    for cc, folder in BUNDLED.items():
        src = os.path.join(WEB, f"{cc}.txt")
        if os.path.exists(src):
            os.makedirs(folder, exist_ok=True)
            shutil.copyfile(src, os.path.join(folder, f"{cc}.txt"))
    print(", ".join(f"{cc} {v['stations']}+{v['railway']}" for cc, v in index.items()))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "built")
