#!/usr/bin/env python3
"""
Builds Koode's offline metro and rail station data from OpenStreetMap.

    python3 tools/transit/build_network.py --fetch            # ask Overpass
    python3 tools/transit/build_network.py --routes r.json --stops s.json --rail t.json

Writes two files the app reads from its assets (and the web viewer from
web/data):

  metro_network.txt   every metro / light-rail / monorail station, and every
                      line as its stations in order with the distance along
                      the track between each pair
  rail_stations.txt   railway stations and halts, for naming where a train
                      stage began or ended

Why along the track: a metro ride is a few phone fixes far apart, and the
straight lines between them cut every bend. The track is what was ridden,
so it is what a ride is measured by.

Data © OpenStreetMap contributors, ODbL 1.0. Standard library only.
"""
import argparse
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

OVERPASS = os.environ.get("OVERPASS_URL", "https://overpass-api.de/api/interpreter")
AREA = '["ISO3166-1"="{cc}"][admin_level=2]'

Q_ROUTES = '[out:json][timeout:300];area{area}->.a;rel(area.a)["route"~"^(subway|light_rail|monorail)$"];out body geom;'
Q_STOPS = ('[out:json][timeout:300];area{area}->.a;rel(area.a)["route"~"^(subway|light_rail|monorail)$"]->.r;'
           'node(r.r)->.stops;(nw(area.a)["railway"="station"]["station"~"subway|light_rail|monorail"];'
           'nw(area.a)["public_transport"="station"]["subway"="yes"];'
           'nw(area.a)["public_transport"="station"]["light_rail"="yes"];)->.st;(.stops;.st;);out center tags;')
Q_RAIL = ('[out:json][timeout:300];area{area}->.a;(nw(area.a)["railway"~"^(station|halt)$"]'
          '["station"!~"subway|light_rail|monorail"]["usage"!~"tourism"];);out center tags;')

# A line under construction is drawn in OpenStreetMap long before anyone rides it.
UNBUILT = re.compile(r"\b(u/c|under construction|proposed|planned)\b", re.I)
# Words that are about the building, not the place: "Habsiguda Metro Station" is
# Habsiguda. A bus or railway station is part of the name ("MG Bus Station").
SUFFIX = re.compile(r"\s*(\([^)]*\b(interchange|line|metro|station|platform)\b[^)]*\)|\[.*?\]|(?<!\bbus)(?<!\brailway)\s+(metro\s+station|metro|station|stn\.?)|rrts)\s*$", re.I)
# "Nadaprabhu Kempegowda Station, Majestic": riders say the part after the comma.
ALIAS = re.compile(r"^.*\b(station|stn\.?)\s*,\s*(.+)$", re.I)

# Along-track figures outside these bounds mean the track was not mapped
# cleanly between two stops; the straight line, a little longer, is used.
RATIO_MIN, RATIO_MAX, FALLBACK = 1.0, 1.5, 1.08


def overpass(query, attempts=4):
    data = urllib.parse.urlencode({"data": query}).encode()
    for i in range(attempts):
        try:
            req = urllib.request.Request(OVERPASS, data=data, headers={"User-Agent": "Koode transit builder (github.com/PrashobhPaul/Koode)"})
            with urllib.request.urlopen(req, timeout=330) as r:
                return json.load(r)
        except Exception as e:  # Overpass answers 429/504 when busy; wait and ask again
            print(f"overpass attempt {i + 1} failed: {e}", file=sys.stderr)
            time.sleep(30 * (i + 1))
    raise SystemExit("Overpass did not answer")


def metres(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def along_track(a, b, segments):
    """
    Track length between stops a and b: every track segment near the line
    a→b, clipped to the stretch between them. Works because between two
    neighbouring stations a line runs one way; a figure that says otherwise
    is replaced by the straight line (see RATIO_*).
    """
    alat, alon = a
    kx, ky = math.cos(math.radians(alat)) * 111320.0, 110540.0
    bx, by = (b[1] - alon) * kx, (b[0] - alat) * ky
    L = math.hypot(bx, by)
    if L < 50:
        return round(L)
    ux, uy = bx / L, by / L
    width = max(250.0, 0.6 * L)
    total = 0.0
    for (lat1, lon1, lat2, lon2) in segments:
        x1, y1 = (lon1 - alon) * kx, (lat1 - alat) * ky
        x2, y2 = (lon2 - alon) * kx, (lat2 - alat) * ky
        if max(abs(x1 * uy - y1 * ux), abs(x2 * uy - y2 * ux)) >= width:
            continue
        t1, t2 = x1 * ux + y1 * uy, x2 * ux + y2 * uy
        if abs(t2 - t1) < 0.01:
            continue
        inside = max(0.0, min(max(t1, t2), L) - max(min(t1, t2), 0.0))
        total += math.hypot(x2 - x1, y2 - y1) * inside / abs(t2 - t1)
    ratio = total / L
    return round(total) if RATIO_MIN <= ratio <= RATIO_MAX else round(L * FALLBACK)


def name_of(tags):
    return (tags.get("name:en") or tags.get("name") or "").strip()


def base_name(name):
    n = re.sub(r"\s+", " ", name).strip()
    m = ALIAS.match(n)
    if m:
        n = m.group(2).strip()
    for _ in range(3):
        m = SUFFIX.sub("", n).strip()
        if m == n or not m:
            break
        n = m
    return n


def key_of(name):
    return re.sub(r"[^a-z0-9]", "", base_name(name).lower())


def centre(el):
    if "lat" in el:
        return el["lat"], el["lon"]
    c = el.get("center")
    return (c["lat"], c["lon"]) if c else None


def clean(s):
    return re.sub(r"[|\r\n]+", " ", s).strip()


def build_metro(routes, stops):
    tags = {}
    features = []  # named station features: (lat, lon, name)
    for el in stops.get("elements", []):
        t = el.get("tags", {})
        if el["type"] == "node":
            tags[el["id"]] = t
        c = centre(el)
        n = name_of(t)
        if c and n and (t.get("railway") == "station" or t.get("public_transport") == "station"):
            features.append((c[0], c[1], n))

    def stop_name(nid, lat, lon):
        n = name_of(tags.get(nid, {}))
        if n:
            return n
        best = min(((metres(lat, lon, f[0], f[1]), f[2]) for f in features), default=None)
        return best[1] if best and best[0] <= 350 else ""

    lines = []  # (network, name, colour, [(stop_index, metres_from_previous)])
    stop_list = []  # (lat, lon, name, network)
    stop_at = {}
    for rel in routes.get("elements", []):
        if rel.get("type") != "relation":
            continue
        t = rel.get("tags", {})
        network = t.get("network", "").strip()
        title = t.get("name", "")
        if not network or UNBUILT.search(title) or t.get("state") in ("proposed", "construction"):
            continue
        members = rel.get("members", [])
        segments = []
        for m in members:
            if m.get("type") == "way" and m.get("role", "") == "" and m.get("geometry"):
                g = [p for p in m["geometry"] if p]
                segments += [(g[i]["lat"], g[i]["lon"], g[i + 1]["lat"], g[i + 1]["lon"]) for i in range(len(g) - 1)]
        seq = []
        prev = None
        for m in members:
            if m.get("type") != "node" or not m.get("role", "").startswith("stop") or "lat" not in m:
                continue
            nid = m["ref"]
            if nid not in stop_at:
                stop_at[nid] = len(stop_list)
                stop_list.append((m["lat"], m["lon"], stop_name(nid, m["lat"], m["lon"]), network))
            here = (m["lat"], m["lon"])
            seq.append((stop_at[nid], 0 if prev is None else along_track(prev, here, segments)))
            prev = here
        if len(seq) >= 2:
            line = re.sub(r"\s*[(:].*$", "", title).strip() or t.get("ref", "") or network
            lines.append((network, clean(line), clean(t.get("colour", "")), seq))

    # Stops become stations: the stop positions of both directions, and of
    # every line through an interchange, share the station's name.
    parent = list(range(len(stop_list)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    by_key = {}
    for i, (lat, lon, n, _) in enumerate(stop_list):
        if n:
            by_key.setdefault(key_of(n), []).append(i)
    for members in by_key.values():
        for i in members:
            for j in members:
                if i < j and metres(stop_list[i][0], stop_list[i][1], stop_list[j][0], stop_list[j][1]) <= 700:
                    parent[find(j)] = find(i)
    for i in range(len(stop_list)):
        for j in range(i + 1, len(stop_list)):
            if metres(stop_list[i][0], stop_list[i][1], stop_list[j][0], stop_list[j][1]) <= 40:
                parent[find(j)] = find(i)

    groups = {}
    for i in range(len(stop_list)):
        groups.setdefault(find(i), []).append(i)

    stations = []  # (lat, lon, name, network)
    station_of = {}
    for root in sorted(groups, key=lambda r: (stop_list[r][3], key_of(stop_list[r][2]), stop_list[r][0])):
        idx = groups[root]
        names = [stop_list[i][2] for i in idx if stop_list[i][2]]
        name = base_name(max(set(names), key=names.count)) if names else ""
        lat = sum(stop_list[i][0] for i in idx) / len(idx)
        lon = sum(stop_list[i][1] for i in idx) / len(idx)
        # The station building, when mapped and named alike, marks the place better.
        same = [f for f in features if key_of(f[2]) == key_of(name) and metres(lat, lon, f[0], f[1]) <= 400] if name else []
        if same:
            f = min(same, key=lambda f: metres(lat, lon, f[0], f[1]))
            lat, lon = f[0], f[1]
        sid = len(stations)
        stations.append((lat, lon, clean(name), clean(stop_list[root][3])))
        for i in idx:
            station_of[i] = sid

    out_lines = []
    for network, name, colour, seq in sorted(lines, key=lambda l: (l[0], l[1], l[3][0][0])):
        cells, last, carry = [], None, 0
        for stop, m in seq:
            s = station_of[stop]
            if s == last:
                carry += m
                continue
            cells.append(str(s) if last is None else f"{s}:{m + carry}")
            last, carry = s, 0
        if len(cells) >= 2:
            out_lines.append(f"L|{network}|{name}|{colour}|{' '.join(cells)}")
    return stations, out_lines


def build_rail(rail):
    seen = {}
    for el in rail.get("elements", []):
        c = centre(el)
        n = clean(name_of(el.get("tags", {})))
        if not c or not n:
            continue
        k = (key_of(n), round(c[0], 2), round(c[1], 2))
        seen.setdefault(k, (round(c[0], 5), round(c[1], 5), n))
    return sorted(seen.values(), key=lambda r: (r[2], r[0], r[1]))


HEADER = "# {what} for Koode. Data (c) OpenStreetMap contributors, ODbL 1.0. Built {date} by tools/transit/build_network.py\n"


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true", help="query Overpass")
    ap.add_argument("--country", default="IN")
    ap.add_argument("--routes"); ap.add_argument("--stops"); ap.add_argument("--rail")
    ap.add_argument("--out", nargs="+", default=["apps/android/app/src/main/assets", "web/data"])
    ap.add_argument("--min-stations", type=int, default=300, help="refuse to write a smaller network")
    a = ap.parse_args()

    area = AREA.format(cc=a.country)
    load = lambda p: json.load(open(p, encoding="utf-8"))
    routes = overpass(Q_ROUTES.format(area=area)) if a.fetch else load(a.routes)
    stops = overpass(Q_STOPS.format(area=area)) if a.fetch else load(a.stops)
    rail = (overpass(Q_RAIL.format(area=area)) if a.fetch else load(a.rail)) if (a.fetch or a.rail) else None

    stations, lines = build_metro(routes, stops)
    if len(stations) < a.min_stations:
        raise SystemExit(f"only {len(stations)} metro stations; not writing (is OpenStreetMap or Overpass broken?)")
    date = time.strftime("%Y-%m-%d")
    metro = HEADER.format(what="Metro stations and lines", date=date)
    metro += "# S|lat|lng|name|network   L|network|line|colour|station station:metres-along-track ...\n"
    metro += "".join(f"S|{lat:.5f}|{lon:.5f}|{n}|{net}\n" for lat, lon, n, net in stations)
    metro += "".join(l + "\n" for l in lines)
    for d in a.out:
        write(os.path.join(d, "metro_network.txt"), metro)
    print(f"metro: {len(stations)} stations, {len(lines)} lines")

    if rail is not None:
        rows = build_rail(rail)
        text = HEADER.format(what="Railway stations", date=date) + "# lat|lng|name\n"
        text += "".join(f"{lat:.5f}|{lon:.5f}|{n}\n" for lat, lon, n in rows)
        write(os.path.join(a.out[0], "rail_stations.txt"), text)
        print(f"rail: {len(rows)} stations")


if __name__ == "__main__":
    main()
