#!/usr/bin/env python3
"""
Builds Koode's offline transit data for one country from OpenStreetMap.

    python3 tools/transit/build_network.py --fetch --country IN --out web/data/transit/IN.txt
    python3 tools/transit/build_network.py --routes r.json --stops s.json --rail t.json --out IN.txt

One file per country, read by the app (India bundled, other countries
downloaded when the phone is there) and by the web viewer:

  S|lat|lng|name|network|kind   a metro station (kind M) or a water-metro /
                                ferry terminal (kind W)
  L|network|line|colour|a b:m c:m ...
                                a line as its stations in order, with the
                                distance in metres along the track (or the
                                boat's course) from each to the next
  T|lat|lng|name                a railway station, for naming where a train
                                stage began or ended

Why along the track: a ride is a few phone fixes far apart, and the
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

# The public Overpass servers, tried in turn: one that is busy answers 504.
OVERPASS = [u for u in os.environ.get("OVERPASS_URL", "").split() if u] or [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]
AREA = '["ISO3166-1"="{cc}"]'
# Countries whose boundary is too costly for Overpass to search within (the
# UK's takes in every island and its territorial sea; France's its overseas
# territories) are searched by a box instead: south, west, north, east.
BOXES = {"GB": "49.8,-8.7,60.9,1.9", "FR": "41.3,-5.3,51.2,9.7"}


def scoped(query, cc):
    """A query template for one country: within its boundary, or its box."""
    if cc in BOXES:
        return query.replace("area{area}->.a;", "").replace("(area.a)", "(" + BOXES[cc] + ")")
    return query.replace("{area}", AREA.format(cc=cc))
ROUTES = "^(subway|light_rail|monorail|ferry)$"

# Metro lines and ferries are asked for apart: a country's sea ferries carry
# long courses that can make one query too big, and a ferry query that fails
# must not cost the country its metro.
Q_ROUTES = '[out:json][timeout:300];area{area}->.a;rel(area.a)["route"~"^(subway|light_rail|monorail)$"];out body geom;'
Q_FERRIES = '[out:json][timeout:300];area{area}->.a;rel(area.a)["route"="ferry"];out body geom;'
Q_STOPS = ('[out:json][timeout:300];area{area}->.a;rel(area.a)["route"~"' + ROUTES + '"]->.r;'
           '(node(r.r);way(r.r)["public_transport"];way(r.r)["amenity"="ferry_terminal"];)->.stops;'
           '(nw(area.a)["railway"="station"]["station"~"subway|light_rail|monorail"];'
           'nw(area.a)["public_transport"="station"]["subway"="yes"];'
           'nw(area.a)["public_transport"="station"]["light_rail"="yes"];'
           'nw(area.a)["amenity"="ferry_terminal"];)->.st;(.stops;.st;);out center tags;')
Q_RAIL = ('[out:json][timeout:300];area{area}->.a;(nw(area.a)["railway"~"^(station|halt)$"]'
          '["station"!~"subway|light_rail|monorail"]["usage"!~"tourism"];);out center tags;')

# A line under construction is drawn in OpenStreetMap long before anyone rides it.
UNBUILT = re.compile(r"\b(u/c|under construction|proposed|planned)\b", re.I)
# Words that are about the building, not the place: "Habsiguda Metro Station" is
# Habsiguda, "Vyttila Water Metro Terminal" is Vyttila. A bus or railway station
# is part of the name ("MG Bus Station").
SUFFIX = re.compile(
    r"\s*(\([^)]*\b(interchange|line|metro|station|platform)\b[^)]*\)|\[.*?\]"
    r"|\s+water\s+metro(\s+(terminal|station|jetty|stop))?"
    r"|(?<!\bbus)(?<!\brailway)\s+(metro\s+station|metro|station|stn\.?)|rrts)\s*$", re.I)
# "Nadaprabhu Kempegowda Station, Majestic": riders say the part after the comma.
ALIAS = re.compile(r"^.*\b(station|stn\.?)\s*,\s*(.+)$", re.I)

# Along-track figures outside these bounds mean the track was not mapped
# cleanly between two stops; the straight line, a little longer, is used.
# A boat's course bends round headlands and islands, so it is allowed more.
RATIO_MIN, FALLBACK = 1.0, 1.08
RATIO_MAX = {"M": 1.5, "W": 2.5}

# Stops of one name this close are one station: both directions' platforms,
# the lines meeting at an interchange. Kept tight so stations a block apart
# on different lines (New York's two 23rd Streets) stay two stations; the
# app links those by the walk between them.
SAME_NAME_M = 200.0
SAME_SPOT_M = 40.0

# A rebuild this much smaller than the file it replaces is refused: a city
# does not lose a fifth of its stations between two builds.
SHRINK_LIMIT = 0.8


def overpass(query, attempts=6, required=True):
    data = urllib.parse.urlencode({"data": query}).encode()
    for i in range(attempts):
        url = OVERPASS[i % len(OVERPASS)]
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": "Koode transit builder (github.com/PrashobhPaul/Koode)"})
            with urllib.request.urlopen(req, timeout=360) as r:
                answer = json.load(r)
            # A query that ran out of time or memory still answers 200, with
            # whatever it had found so far and a remark saying so. Half an
            # answer is not an answer: ask again.
            remark = str(answer.get("remark", ""))
            if "error" in remark.lower() or "timed out" in remark.lower():
                raise RuntimeError("partial answer: " + remark[:200])
            return answer
        except Exception as e:  # a busy server answers 429/504; ask the next, then wait
            print(f"overpass attempt {i + 1} ({url}) failed: {e}", file=sys.stderr)
            if (i + 1) % len(OVERPASS) == 0:
                time.sleep(60 * ((i + 1) // len(OVERPASS)))
    if required:
        raise SystemExit("Overpass did not answer")
    return None


def metres(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def along_track(a, b, segments, kind="M"):
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
    return round(total) if RATIO_MIN <= ratio <= RATIO_MAX.get(kind, 1.5) else round(L * FALLBACK)


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
    if c:
        return c["lat"], c["lon"]
    g = [p for p in el.get("geometry") or [] if p]
    if g:
        return sum(p["lat"] for p in g) / len(g), sum(p["lon"] for p in g) / len(g)
    return None


def clean(s):
    return re.sub(r"[|\r\n]+", " ", s).strip()


def build_network(routes, stops):
    """Stations (lat, lon, name, network, kind) and lines ("L|…" rows)."""
    tags = {}
    features = []  # named stations and terminals: (lat, lon, name, kind)
    for el in stops.get("elements", []):
        t = el.get("tags", {})
        tags[(el["type"], el["id"])] = t
        c = centre(el)
        n = name_of(t)
        if not c or not n:
            continue
        if t.get("amenity") == "ferry_terminal" or t.get("ferry") == "yes":
            features.append((c[0], c[1], n, "W"))
        elif t.get("railway") == "station" or t.get("public_transport") == "station":
            features.append((c[0], c[1], n, "M"))

    def stop_name(ref, lat, lon, kind):
        n = name_of(tags.get(ref, {}))
        if n:
            return n
        best = min(((metres(lat, lon, f[0], f[1]), f[2]) for f in features if f[3] == kind), default=None)
        return best[1] if best and best[0] <= 350 else ""

    lines = []  # (network, name, colour, kind, [(stop_index, metres_from_previous)])
    stop_list = []  # (lat, lon, name, network, kind)
    stop_at = {}
    for rel in routes.get("elements", []):
        if rel.get("type") != "relation":
            continue
        t = rel.get("tags", {})
        kind = "W" if t.get("route") == "ferry" else "M"
        network = (t.get("network") or (t.get("operator") if kind == "W" else "") or "").strip()
        title = t.get("name", "")
        if not network or UNBUILT.search(title) or t.get("state") in ("proposed", "construction"):
            continue
        members = rel.get("members", [])
        segments = []
        for m in members:
            if m.get("type") == "way" and m.get("role", "") == "" and m.get("geometry"):
                g = [p for p in m["geometry"] if p]
                segments += [(g[i]["lat"], g[i]["lon"], g[i + 1]["lat"], g[i + 1]["lon"]) for i in range(len(g) - 1)]
        # Stops are the stop positions; a route mapped only with platforms
        # (as many ferries are) is read from those instead.
        picked = [m for m in members if m.get("role", "").startswith("stop")]
        if len(picked) < 2:
            picked = [m for m in members if m.get("role", "").startswith("platform")]
        seq, prev = [], None
        for m in picked:
            c = centre(m)
            if not c:
                continue
            ref = (m["type"], m["ref"])
            if ref not in stop_at:
                stop_at[ref] = len(stop_list)
                stop_list.append((c[0], c[1], stop_name(ref, c[0], c[1], kind), network, kind))
            here = (c[0], c[1])
            if seq and stop_at[ref] == seq[-1][0]:
                continue
            seq.append((stop_at[ref], 0 if prev is None else along_track(prev, here, segments, kind)))
            prev = here
        if len(seq) >= 2:
            line = re.sub(r"\s*[(:].*$", "", title).strip() or t.get("ref", "") or network
            lines.append((network, clean(line), clean(t.get("colour", "")), kind, seq))

    # Stops become stations: the stop positions of both directions, and of
    # every line through an interchange, share the station's name. A metro
    # station and a ferry terminal of one name stay two places.
    parent = list(range(len(stop_list)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def near(i, j, m):
        a, b = stop_list[i], stop_list[j]
        if abs(a[0] - b[0]) > m / 100000.0 or abs(a[1] - b[1]) > m / 50000.0:
            return False
        return metres(a[0], a[1], b[0], b[1]) <= m

    by_key = {}
    for i, (lat, lon, n, _, kind) in enumerate(stop_list):
        if n:
            by_key.setdefault((kind, key_of(n)), []).append(i)
    for members in by_key.values():
        for x, i in enumerate(members):
            for j in members[x + 1:]:
                if near(i, j, SAME_NAME_M):
                    parent[find(j)] = find(i)
    order = sorted(range(len(stop_list)), key=lambda i: stop_list[i][0])
    for x, i in enumerate(order):
        for j in order[x + 1:]:
            if stop_list[j][0] - stop_list[i][0] > 0.001:
                break
            if stop_list[i][4] == stop_list[j][4] and near(i, j, SAME_SPOT_M):
                parent[find(j)] = find(i)

    groups = {}
    for i in range(len(stop_list)):
        groups.setdefault(find(i), []).append(i)

    stations = []
    station_of = {}
    for root in sorted(groups, key=lambda r: (stop_list[r][4], stop_list[r][3], key_of(stop_list[r][2]), stop_list[r][0], stop_list[r][1])):
        idx = groups[root]
        kind = stop_list[root][4]
        # A stop shared by a metro (or water metro) and an ordinary service
        # is named and labelled as the metro's: "Vypin Water Metro".
        nets = [stop_list[i][3] for i in idx]
        metro = [n for n in nets if re.search(r"m[eé]tro", n, re.I)]
        network = max(sorted(set(metro or nets)), key=nets.count)
        names = [stop_list[i][2] for i in idx if stop_list[i][2] and stop_list[i][3] == network] or \
                [stop_list[i][2] for i in idx if stop_list[i][2]]
        name = base_name(max(sorted(set(names)), key=names.count)) if names else ""
        lat = sum(stop_list[i][0] for i in idx) / len(idx)
        lon = sum(stop_list[i][1] for i in idx) / len(idx)
        # The station building, when mapped and named alike, marks the place better.
        same = [f for f in features if f[3] == kind and key_of(f[2]) == key_of(name) and metres(lat, lon, f[0], f[1]) <= 300] if name else []
        if same:
            f = min(same, key=lambda f: metres(lat, lon, f[0], f[1]))
            lat, lon = f[0], f[1]
        sid = len(stations)
        stations.append((lat, lon, clean(name), clean(network), kind))
        for i in idx:
            station_of[i] = sid

    out_lines = []
    for network, name, colour, kind, seq in sorted(lines, key=lambda l: (l[3], l[0], l[1], l[4][0][0])):
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


def render(cc, stations, lines, rail, date):
    out = [f"# Transit for Koode, {cc}. Data (c) OpenStreetMap contributors, ODbL 1.0. Built {date} by tools/transit/build_network.py",
           "# S|lat|lng|name|network|kind(M metro, W water metro/ferry)   L|network|line|colour|station station:metres ...   T|lat|lng|railway station"]
    out += [f"S|{lat:.5f}|{lon:.5f}|{n}|{net}|{kind}" for lat, lon, n, net, kind in stations]
    out += lines
    out += [f"T|{lat:.5f}|{lon:.5f}|{n}" for lat, lon, n in rail]
    return "\n".join(out) + "\n"


def carry_ferries(text, offset):
    """
    The ferry terminals and lines of an earlier file, numbered from [offset]:
    kept when the ferry query found nothing this time, so a busy server
    does not take Kochi's Water Metro off the map.
    """
    old = [l.rstrip("\n").split("|") for l in (text or "").splitlines()]
    s_rows = [r for r in old if r[0] == "S"]
    new_id, stations = {}, []
    for i, r in enumerate(s_rows):
        if len(r) >= 6 and r[5] == "W":
            new_id[i] = offset + len(stations)
            stations.append((float(r[1]), float(r[2]), r[3], r[4], "W"))
    lines = []
    for r in old:
        if r[0] != "L" or len(r) < 5:
            continue
        cells = [c.split(":") for c in r[4].split()]
        if cells and all(int(c[0]) in new_id for c in cells):
            moved = [":".join([str(new_id[int(c[0])])] + c[1:]) for c in cells]
            lines.append("|".join(r[:4] + [" ".join(moved)]))
    return stations, lines


def count_stations(path):
    """(metro and ferry stations, railway stations) in a country's file."""
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
        return sum(1 for l in lines if l.startswith("S|")), sum(1 for l in lines if l.startswith("T|"))
    except OSError:
        return 0, 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true", help="query Overpass")
    ap.add_argument("--country", default="IN")
    ap.add_argument("--routes"); ap.add_argument("--stops"); ap.add_argument("--rail")
    ap.add_argument("--out", required=True, help="the country's file")
    ap.add_argument("--previous", help="the file it replaces; a much smaller result is refused")
    a = ap.parse_args()

    load = lambda p: json.load(open(p, encoding="utf-8"))
    q = lambda t: scoped(t, a.country)
    if a.fetch:
        routes = overpass(q(Q_ROUTES))
        ferries = overpass(q(Q_FERRIES), attempts=3, required=False)
        if ferries is None:
            print(f"{a.country}: the ferry query failed; keeping the ferries already known", file=sys.stderr)
        routes = {"elements": routes.get("elements", []) + (ferries or {}).get("elements", [])}
    else:
        routes = load(a.routes)
    stops = overpass(q(Q_STOPS)) if a.fetch else load(a.stops)
    rail = overpass(q(Q_RAIL)) if a.fetch else (load(a.rail) if a.rail else {"elements": []})

    stations, lines = build_network(routes, stops)
    if a.fetch and ferries is None and a.previous and os.path.exists(a.previous):
        with open(a.previous, encoding="utf-8") as f:
            kept, kept_lines = carry_ferries(f.read(), len(stations))
        stations, lines = stations + kept, lines + kept_lines
    rows = build_rail(rail)
    # Each kind is checked on its own: thousands of railway stations must
    # not hide a metro list that came back with most of its cities missing.
    metro_before, rail_before = count_stations(a.previous) if a.previous else (0, 0)
    if len(stations) + len(rows) == 0:
        raise SystemExit(f"{a.country}: nothing found; not writing")
    for what, now, before in (("metro and ferry", len(stations), metro_before), ("railway", len(rows), rail_before)):
        if before >= 20 and now < before * SHRINK_LIMIT:
            raise SystemExit(f"{a.country}: {now} {what} stations against {before} before; not writing (is OpenStreetMap or Overpass broken?)")
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(render(a.country, stations, lines, rows, time.strftime("%Y-%m-%d")))
    kinds = {k: sum(1 for s in stations if s[4] == k) for k in "MW"}
    print(f"{a.country}: {kinds['M']} metro stations, {kinds['W']} ferry terminals, {len(lines)} lines, {len(rows)} railway stations")


if __name__ == "__main__":
    main()
