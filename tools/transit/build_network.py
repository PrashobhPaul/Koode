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
  R|lat|lng|name|network        a station on a mapped railway line
  Q|network|line|colour|class|a b:m ...
                                a railway line, as L but numbering the R
                                rows, with its speed class: H high-speed
                                (Shinkansen, TGV, ICE, AVE, Eurostar,
                                Acela), X long-distance and limited express,
                                L everything else; followed by the speed,
                                km/h, its trains keep between stops in that
                                country ("H270" in Japan, "H190" in Germany)
  B|lat|lng|name                a bus or coach station, for naming where a
                                bus stage began or ended

Apps that know only S, L and T rows skip the others.

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
# Railway lines: the routes without their geometry, then each track way once
# (a busy track is shared by dozens of services; asking per route repeats it).
Q_TRAINS = ('[out:json][timeout:300];area{area}->.a;rel(area.a)["route"="train"]->.r;'
            '.r out body;way(r.r);out geom;node(r.r);out body;')
# Ferries mapped as a way and not a route (most sea crossings), with the
# terminals at their ends, wherever those are: Dover's crossing ends in Calais.
Q_FERRY_WAYS = ('[out:json][timeout:300];area{area}->.a;way(area.a)["route"="ferry"]->.f;.f out body geom;'
                'nw(around.f:1500)["amenity"="ferry_terminal"];out center tags;')
Q_BUS = '[out:json][timeout:300];area{area}->.a;nw(area.a)["amenity"="bus_station"];out center tags;'
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
RATIO_MAX = {"M": 1.5, "W": 2.5, "R": 1.6}

# How fast a railway line runs, for the time a ride takes: high-speed, long
# distance and limited express, everything else.
HIGH_SPEED = re.compile(r"shinkansen|新幹線|\bTGV\b|inoui|ouigo|\bICE\b|\bAVE\b|avlo|iryo|eurostar|thalys|frecciarossa|"
                        r"\bitalo\b|acela|\bKTX\b|\bSRT\b|高铁|\bCRH\b|lyria|high[- ]speed", re.I)
EXPRESS = re.compile(r"intercity|inter-city|\bI[CR]E?\b|\bEC\b|\bEN\b|express|特急|limited|amtrak|railjet|nightjet|"
                     r"night train|sleeper|\bTEE\b|eurocity|euronight|\bTER\b(?!.*\bomnibus)", re.I)

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


# How fast each class of train runs between stops, km/h, where it differs
# from the usual: a Shinkansen or a TGV holds 270 for hours, an ICE shares
# much of its way with slower trains, the Acela most of its.
CRUISE = {"H": 220, "X": 110, "L": 55}
CRUISE_BY_COUNTRY = {
    "JP": {"H": 270, "X": 100}, "FR": {"H": 270, "X": 130}, "ES": {"H": 260}, "IT": {"H": 250},
    "CN": {"H": 280}, "KR": {"H": 250}, "TW": {"H": 260}, "DE": {"H": 190, "X": 130}, "AT": {"H": 170},
    "CH": {"H": 150, "X": 100}, "BE": {"H": 230}, "NL": {"H": 200}, "GB": {"H": 200, "X": 140},
    "SE": {"H": 160}, "US": {"H": 120, "X": 90, "L": 60}, "CA": {"X": 90}, "IN": {"H": 130, "X": 75, "L": 45},
}


def cruise(cc, klass):
    return CRUISE_BY_COUNTRY.get(cc, {}).get(klass, CRUISE[klass])


def rail_class(t):
    """H, X or L: see the Q rows."""
    service = t.get("service", "")
    if service == "high_speed" or t.get("highspeed") == "yes":
        return "H"
    words = " ".join(t.get(k, "") for k in ("name", "name:en", "network", "brand", "ref", "operator"))
    if HIGH_SPEED.search(words):
        return "H"
    if service in ("long_distance", "night") or EXPRESS.search(words):
        return "X"
    return "L"


def inflate(answer):
    """
    Routes asked for without geometry (Q_TRAINS) in the shape asked with it:
    each member way given its track, each member node its place. Returns the
    routes and the member nodes (the stops, with their names).
    """
    els = (answer or {}).get("elements", [])
    ways = {e["id"]: e for e in els if e.get("type") == "way"}
    nodes = {e["id"]: e for e in els if e.get("type") == "node"}
    rels = []
    for e in els:
        if e.get("type") != "relation":
            continue
        members = []
        for m in e.get("members", []):
            m = dict(m)
            if m.get("type") == "way" and m.get("ref") in ways:
                m["geometry"] = ways[m["ref"]].get("geometry")
            elif m.get("type") == "node" and m.get("ref") in nodes:
                m["lat"], m["lon"] = nodes[m["ref"]]["lat"], nodes[m["ref"]]["lon"]
            members.append(m)
        rels.append(dict(e, members=members))
    return {"elements": rels}, {"elements": list(nodes.values()) + [w for w in ways.values() if w.get("tags", {}).get("public_transport")]}


def ferry_crossings(answer):
    """
    Ferries mapped as ways, as routes from terminal to terminal: ways joined
    end to end where only two meet, each end matched to a terminal within
    1.5 km. Returns routes in the shape of Q_FERRIES' and the terminals.
    """
    els = (answer or {}).get("elements", [])
    ways = [e for e in els if e.get("type") == "way" and e.get("tags", {}).get("route") == "ferry"
            and len(e.get("geometry") or []) >= 2 and len(e.get("nodes") or []) >= 2
            and not UNBUILT.search(e.get("tags", {}).get("name", ""))]
    terminals = [e for e in els if e.get("tags", {}).get("amenity") == "ferry_terminal" and name_of(e.get("tags", {})) and centre(e)]
    ends = {}
    for w in ways:
        for nid in (w["nodes"][0], w["nodes"][-1]):
            ends.setdefault(nid, []).append(w)
    used, routes = set(), []
    for w in sorted(ways, key=lambda w: w["id"]):
        if w["id"] in used:
            continue
        used.add(w["id"])
        pts = [(p["lat"], p["lon"]) for p in w["geometry"] if p]
        first, last, t = w["nodes"][0], w["nodes"][-1], w.get("tags", {})
        grew = True
        while grew:
            grew = False
            for at_end in (True, False):
                nid = last if at_end else first
                meet = ends.get(nid, [])
                others = [o for o in meet if o["id"] not in used]
                if len(meet) != 2 or len(others) != 1:
                    continue
                o = others[0]
                used.add(o["id"])
                op = [(p["lat"], p["lon"]) for p in o["geometry"] if p]
                forward = o["nodes"][0] == nid if at_end else o["nodes"][-1] == nid
                if not forward:
                    op = op[::-1]
                if at_end:
                    pts += op[1:]
                    last = o["nodes"][-1] if o["nodes"][0] == nid else o["nodes"][0]
                else:
                    pts = op[:-1] + pts
                    first = o["nodes"][0] if o["nodes"][-1] == nid else o["nodes"][-1]
                grew = True

        def terminal(p):
            best = min(((metres(p[0], p[1], *centre(x)), x) for x in terminals), default=None, key=lambda b: b[0])
            return best[1] if best and best[0] <= 1500 else None
        a, b = terminal(pts[0]), terminal(pts[-1])
        if not a or not b or a is b:
            continue
        member = lambda x: {"type": x["type"], "ref": x["id"], "role": "stop", "lat": centre(x)[0], "lon": centre(x)[1]}
        routes.append({"type": "relation", "id": -w["id"],
                       "tags": {"route": "ferry", "name": t.get("name", ""),
                                "network": t.get("network") or t.get("operator") or t.get("name") or "ferry"},
                       "members": [member(a), {"type": "way", "ref": w["id"], "role": "",
                                               "geometry": [{"lat": la, "lon": lo} for la, lo in pts]}, member(b)]})
    return routes, terminals


# Platforms and tracks are where a train stops, not what the station is called.
PLATFORM = re.compile(r"\s*[-–,]?\s*\b(voie|gleis|quai|platform|track|bahnsteig|binario|spoor|hall)\s*\d+[\w\s\-–]*(,.*)?$"
                      r"|\s*\((tief|oben|unten|lower level|upper level|rer)\)\s*$", re.I)


def build_network(routes, stops, station_kind="M", rail=None, cc=""):
    """
    Stations (lat, lon, name, network, kind) and lines ("L|…" rows; "Q|…"
    rows for railway lines, [station_kind] R, whose named stations [rail]
    also are).
    """
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
        elif t.get("railway") in ("station", "halt") or t.get("public_transport") == "station":
            features.append((c[0], c[1], n, station_kind))
    for el in (rail or {}).get("elements", []):
        c, n = centre(el), name_of(el.get("tags", {}))
        if c and n:
            features.append((c[0], c[1], n, station_kind))

    # Stations by 0.01-degree square, so each stop looks only at its neighbours.
    grid = {}
    for f in features:
        grid.setdefault((int(f[0] * 100), int(f[1] * 100)), []).append(f)

    def near_features(lat, lon, kind):
        y, x = int(lat * 100), int(lon * 100)
        return (f for dy in (-1, 0, 1) for dx in (-1, 0, 1) for f in grid.get((y + dy, x + dx), ()) if f[3] == kind)

    def stop_name(ref, lat, lon, kind):
        n = name_of(tags.get(ref, {}))
        best = min(((metres(lat, lon, f[0], f[1]), f[2]) for f in near_features(lat, lon, kind)), default=None)
        # A train stops at a platform ("Gleis 27-36"); riders name the station.
        if kind == "R" and best and best[0] <= 400:
            return PLATFORM.sub("", best[1]).strip() or best[1]
        if n:
            return PLATFORM.sub("", n).strip() or n
        return best[1] if best and best[0] <= 350 else ""

    lines = []  # (network, name, colour, kind, [(stop_index, metres_from_previous)])
    stop_list = []  # (lat, lon, name, network, kind)
    stop_at = {}
    for rel in routes.get("elements", []):
        if rel.get("type") != "relation":
            continue
        t = rel.get("tags", {})
        kind = {"ferry": "W", "train": "R"}.get(t.get("route"), "M")
        network = (t.get("network") or (t.get("operator") if kind != "M" else "") or ("rail" if kind == "R" else "")).strip()
        title = (t.get("name:en") if kind == "R" else None) or t.get("name", "")
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
            klass = rail_class(t) if kind == "R" else ""
            lines.append((network, clean(line), clean(t.get("colour", "")), kind, seq, f"{klass}{cruise(cc, klass)}" if klass else ""))

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
    for network, name, colour, kind, seq, klass in sorted(lines, key=lambda l: (l[3], l[0], l[1], l[4][0][0])):
        cells, last, carry = [], None, 0
        for stop, m in seq:
            s = station_of[stop]
            if s == last:
                carry += m
                continue
            cells.append(str(s) if last is None else f"{s}:{m + carry}")
            last, carry = s, 0
        if len(cells) >= 2:
            if kind == "R":
                out_lines.append(f"Q|{network}|{name}|{colour}|{klass}|{' '.join(cells)}")
            else:
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


def render(cc, stations, lines, rail, date, trains=((), ()), buses=()):
    out = [f"# Transit for Koode, {cc}. Data (c) OpenStreetMap contributors, ODbL 1.0. Built {date} by tools/transit/build_network.py",
           "# S|lat|lng|name|network|kind(M metro, W water metro/ferry)   L|network|line|colour|station station:metres ...   T|lat|lng|railway station",
           "# R|lat|lng|name|network railway line station   Q|network|line|colour|class(H high-speed, X express, L local)|R-station R-station:metres ...   B|lat|lng|bus or coach station"]
    out += [f"S|{lat:.5f}|{lon:.5f}|{n}|{net}|{kind}" for lat, lon, n, net, kind in stations]
    out += lines
    out += [r if isinstance(r, str) else f"R|{r[0]:.5f}|{r[1]:.5f}|{r[2]}|{r[3]}" for r in trains[0]]
    out += list(trains[1])
    out += [f"T|{lat:.5f}|{lon:.5f}|{n}" for lat, lon, n in rail]
    out += [f"B|{lat:.5f}|{lon:.5f}|{n}" for lat, lon, n in buses]
    return "\n".join(out) + "\n"


def carry_ferries(text, offset):
    """
    The ferry terminals and lines of an earlier file, numbered from [offset]:
    kept when a ferry query found nothing this time, so a busy server does
    not take Kochi's Water Metro off the map.
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


def carry_rows(text, *kinds):
    """An earlier file's rows of [kinds], as they were (R and Q rows number only each other)."""
    return [l for l in (text or "").splitlines() if l.split("|", 1)[0] in kinds]


def count_rows(path):
    """
    How many of each thing a country's file has: M, W, R, T and B. Only
    named ones count: a station with no name is no use to anyone, and a
    busy server can answer the lines in full and the station names in part
    (India's metros came back nameless once).
    """
    n = dict.fromkeys("MWRTB", 0)
    try:
        with open(path, encoding="utf-8") as f:
            for l in f:
                r = l.rstrip("\n").split("|")
                if len(r) < 4 or not r[3].strip():
                    continue
                if r[0] == "S":
                    n["W" if r[-1] == "W" else "M"] += 1
                elif r[0] in "RTB" and len(r[0]) == 1:
                    n[r[0]] += 1
    except OSError:
        pass
    return n


def count_stations(path):
    """(metro and ferry stations, railway stations) in a country's file."""
    n = count_rows(path)
    return n["M"] + n["W"], n["T"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true", help="query Overpass")
    ap.add_argument("--country", default="IN")
    ap.add_argument("--routes"); ap.add_argument("--stops"); ap.add_argument("--rail")
    ap.add_argument("--trains"); ap.add_argument("--ferry-ways"); ap.add_argument("--buses")
    ap.add_argument("--out", required=True, help="the country's file")
    ap.add_argument("--previous", help="the file it replaces; a much smaller result is refused")
    a = ap.parse_args()

    load = lambda p: json.load(open(p, encoding="utf-8")) if p else None
    q = lambda t: scoped(t, a.country)
    previous = ""
    if a.previous and os.path.exists(a.previous):
        with open(a.previous, encoding="utf-8") as f:
            previous = f.read()

    if a.fetch:
        routes = overpass(q(Q_ROUTES))
        ferries = overpass(q(Q_FERRIES), attempts=3, required=False)
        ferry_ways = overpass(q(Q_FERRY_WAYS), attempts=3, required=False)
        stops = overpass(q(Q_STOPS))
        rail = overpass(q(Q_RAIL))
        trains = overpass(q(Q_TRAINS), attempts=4, required=False)
        buses = overpass(q(Q_BUS), attempts=3, required=False)
    else:
        routes, stops = load(a.routes), load(a.stops)
        rail = load(a.rail) or {"elements": []}
        ferries, ferry_ways = {"elements": []}, load(a.ferry_ways) or {"elements": []}
        trains, buses = load(a.trains) or {"elements": []}, load(a.buses) or {"elements": []}

    # Ferries: both kinds of mapping or neither, so a half-failed pair does
    # not leave every crossing doubled with the ones kept from before.
    keep_ferries = ferries is None or ferry_ways is None
    if keep_ferries:
        print(f"{a.country}: a ferry query failed; keeping the ferries already known", file=sys.stderr)
        elements = routes.get("elements", [])
    else:
        crossings, terminals = ferry_crossings(ferry_ways)
        elements = routes.get("elements", []) + ferries.get("elements", []) + crossings
        stops = {"elements": stops.get("elements", []) + terminals}
    stations, lines = build_network({"elements": elements}, stops)
    if keep_ferries:
        kept, kept_lines = carry_ferries(previous, len(stations))
        stations, lines = stations + kept, lines + kept_lines
    rows = build_rail(rail)

    if trains is None:
        print(f"{a.country}: the railway lines query failed; keeping the lines already known", file=sys.stderr)
        rail_net = (carry_rows(previous, "R"), carry_rows(previous, "Q"))
    else:
        train_routes, train_stops = inflate(trains)
        rail_net = build_network(train_routes, train_stops, station_kind="R", rail=rail, cc=a.country)
        rail_net = ([(la, lo, n, net) for la, lo, n, net, _ in rail_net[0]], rail_net[1])
    if buses is None:
        print(f"{a.country}: the bus station query failed; keeping the ones already known", file=sys.stderr)
        bus_rows = [tuple([float(r[1]), float(r[2]), r[3]]) for r in (l.split("|") for l in carry_rows(previous, "B")) if len(r) >= 4]
    else:
        bus_rows = build_rail(buses)

    # Each kind is checked on its own: thousands of railway stations must
    # not hide a metro list that came back with most of its cities missing.
    text = render(a.country, stations, lines, rows, time.strftime("%Y-%m-%d"), rail_net, bus_rows)
    if len(stations) + len(rows) == 0:
        raise SystemExit(f"{a.country}: nothing found; not writing")
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    now, before = count_rows(a.out), count_rows(a.previous) if a.previous else dict.fromkeys("MWRTB", 0)
    names = {"M": "metro", "W": "ferry", "R": "railway line", "T": "railway", "B": "bus"}
    for k in "MWRTB":
        if before[k] >= 20 and now[k] < before[k] * SHRINK_LIMIT:
            os.remove(a.out)
            raise SystemExit(f"{a.country}: {now[k]} {names[k]} stations against {before[k]} before; not writing (is OpenStreetMap or Overpass broken?)")
    print(f"{a.country}: {now['M']} metro stations, {now['W']} ferry terminals, {len(lines)} lines; "
          f"{now['R']} stations on {len(rail_net[1])} railway lines; {now['T']} railway and {now['B']} bus stations")


if __name__ == "__main__":
    main()
