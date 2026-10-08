"""python3 -m unittest tools/transit/test_build_network.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import build_network as b  # noqa: E402


def track(points):
    return {"type": "way", "ref": 1, "role": "", "geometry": [{"lat": la, "lon": lo} for la, lo in points]}


def stop(nid, lat, lon):
    return {"type": "node", "ref": nid, "role": "stop", "lat": lat, "lon": lon}


# A line that bows north between A and C: A (0,0) → bend (0.01, 0.02) → C (0, 0.04).
BEND = [(17.40, 78.50), (17.405, 78.51), (17.41, 78.52), (17.405, 78.53), (17.40, 78.54)]


def routes():
    out = {"elements": [
        {"type": "relation", "id": 1, "tags": {"network": "Test Metro", "name": "Blue Line (A → C)", "colour": "blue"},
         "members": [stop(11, 17.40, 78.50), stop(12, 17.41, 78.52), stop(13, 17.40, 78.54), track(BEND)]},
        {"type": "relation", "id": 2, "tags": {"network": "Test Metro", "name": "Blue Line (C → A)", "colour": "blue"},
         "members": [stop(23, 17.40002, 78.54), stop(22, 17.41002, 78.52), stop(21, 17.40002, 78.50), track(list(reversed(BEND)))]},
        {"type": "relation", "id": 3, "tags": {"network": "Test Metro", "name": "Red Line (u/c)"},
         "members": [stop(31, 17.30, 78.40), stop(32, 17.31, 78.41)]},
    ]}
    return out


def stops():
    return {"elements": [
        {"type": "node", "id": 11, "lat": 17.40, "lon": 78.50, "tags": {"name": "Alpha"}},
        {"type": "node", "id": 12, "lat": 17.41, "lon": 78.52, "tags": {}},
        {"type": "node", "id": 13, "lat": 17.40, "lon": 78.54, "tags": {"name": "Charlie Metro Station"}},
        {"type": "node", "id": 21, "lat": 17.40002, "lon": 78.50, "tags": {"name": "Alpha"}},
        {"type": "node", "id": 22, "lat": 17.41002, "lon": 78.52, "tags": {"name:en": "Bravo", "name": "బ్రావో"}},
        {"type": "node", "id": 23, "lat": 17.40002, "lon": 78.54, "tags": {"name": "Charlie"}},
        {"type": "way", "id": 99, "center": {"lat": 17.4101, "lon": 78.5201}, "tags": {"railway": "station", "name": "Bravo Metro Station"}},
    ]}


class BuildTest(unittest.TestCase):
    def test_stations_merge_directions_and_take_clean_names(self):
        stations, lines = b.build_network(routes(), stops())
        self.assertEqual(sorted(s[2] for s in stations), ["Alpha", "Bravo", "Charlie"])
        self.assertEqual(len(lines), 2)  # the line under construction is left out

    def test_distance_follows_the_track_not_the_straight_line(self):
        stations, lines = b.build_network(routes(), stops())
        cells = lines[0].split("|")[4].split()
        hops = [int(c.split(":")[1]) for c in cells[1:]]
        names = [stations[int(c.split(":")[0])][2] for c in cells]
        self.assertIn(names, (["Alpha", "Bravo", "Charlie"], ["Charlie", "Bravo", "Alpha"]))
        straight = b.metres(17.40, 78.50, 17.41, 78.52)
        for h in hops:
            self.assertGreaterEqual(h, round(straight) - 1)
            self.assertLess(h, straight * 1.1)

    def test_a_badly_mapped_stretch_falls_back_above_the_straight_line(self):
        straight = b.metres(17.40, 78.50, 17.40, 78.54)
        m = b.along_track((17.40, 78.50), (17.40, 78.54), [])  # no track at all
        self.assertAlmostEqual(m, straight * b.FALLBACK, delta=straight * 0.005)

    def test_base_name(self):
        self.assertEqual(b.base_name("Habsiguda Metro Station"), "Habsiguda")
        self.assertEqual(b.base_name("Ameerpet (Interchange)"), "Ameerpet")
        self.assertEqual(b.base_name("MG Road"), "MG Road")
        self.assertEqual(b.base_name("Mahatma Gandhi Bus Station"), "Mahatma Gandhi Bus Station")
        self.assertEqual(b.base_name("Krantivira Sangolli Rayanna Railway Station"), "Krantivira Sangolli Rayanna Railway Station")
        self.assertEqual(b.base_name("Nadaprabhu Kempegowda Station, Majestic"), "Majestic")
        self.assertEqual(b.base_name("Sir M. Visvesvaraya Stn., Central College"), "Central College")
        self.assertEqual(b.base_name("Dahisar (East) [Line 2]"), "Dahisar (East)")
        self.assertEqual(b.base_name("Kochi Metro"), "Kochi")

    def test_a_water_metro_is_its_own_kind_and_keeps_its_terminal_names(self):
        routes = {"elements": [
            {"type": "relation", "id": 7, "tags": {"route": "ferry", "network": "Kochi Water Metro", "name": "High Court - Vypin"},
             "members": [{"type": "node", "ref": 71, "role": "stop", "lat": 9.9840, "lon": 76.2770},
                         {"type": "node", "ref": 72, "role": "stop", "lat": 9.9790, "lon": 76.2440},
                         {"type": "way", "ref": 5, "role": "", "geometry": [{"lat": 9.9840, "lon": 76.2770}, {"lat": 9.9860, "lon": 76.2600}, {"lat": 9.9790, "lon": 76.2440}]}]},
            {"type": "relation", "id": 8, "tags": {"route": "subway", "network": "Kochi Metro", "name": "Line 1"},
             "members": [stop(81, 9.9845, 76.2775), stop(82, 9.9900, 76.2900)]},
        ]}
        stops = {"elements": [
            {"type": "node", "id": 71, "lat": 9.9840, "lon": 76.2770, "tags": {"name": "High Court Water Metro Terminal"}},
            {"type": "node", "id": 72, "lat": 9.9790, "lon": 76.2440, "tags": {"name": "Vypin"}},
            {"type": "node", "id": 81, "lat": 9.9845, "lon": 76.2775, "tags": {"name": "High Court"}},
            {"type": "node", "id": 82, "lat": 9.9900, "lon": 76.2900, "tags": {"name": "Somewhere"}},
        ]}
        stations, lines = b.build_network(routes, stops)
        kinds = sorted((s[2], s[4]) for s in stations)
        self.assertIn(("High Court", "W"), kinds)
        self.assertIn(("High Court", "M"), kinds)  # same name, still two places
        ferry = [l for l in lines if "Water Metro" in l][0]
        hop = int(ferry.split("|")[4].split()[1].split(":")[1])
        self.assertGreater(hop, b.metres(9.9840, 76.2770, 9.9790, 76.2440))  # the boat's course, not the chord

    def test_same_name_stations_blocks_apart_stay_apart(self):
        # New York has an "86 St" on Lexington and another on Second Avenue.
        routes = {"elements": [
            {"type": "relation", "id": 1, "tags": {"route": "subway", "network": "NYC Subway", "name": "4"},
             "members": [stop(1, 40.7795, -73.9556), stop(2, 40.7850, -73.9510)]},
            {"type": "relation", "id": 2, "tags": {"route": "subway", "network": "NYC Subway", "name": "Q"},
             "members": [stop(3, 40.7777, -73.9516), stop(4, 40.7840, -73.9470)]},
        ]}
        stops = {"elements": [
            {"type": "node", "id": 1, "lat": 40.7795, "lon": -73.9556, "tags": {"name": "86 St"}},
            {"type": "node", "id": 2, "lat": 40.7850, "lon": -73.9510, "tags": {"name": "96 St"}},
            {"type": "node", "id": 3, "lat": 40.7777, "lon": -73.9516, "tags": {"name": "86 St"}},
            {"type": "node", "id": 4, "lat": 40.7840, "lon": -73.9470, "tags": {"name": "96 St"}},
        ]}
        stations, _ = b.build_network(routes, stops)
        self.assertEqual(sum(1 for s in stations if s[2] == "86 St"), 2)

    def test_a_boxed_country_is_searched_by_its_box(self):
        q = b.scoped(b.Q_ROUTES, "GB")
        self.assertNotIn("area", q)
        self.assertIn("rel(49.8,-8.7,60.9,1.9)", q)
        self.assertIn('area["ISO3166-1"="IN"]', b.scoped(b.Q_ROUTES, "IN"))
        self.assertIn("(area.a)", b.scoped(b.Q_STOPS, "IN"))

    def test_metro_and_railway_are_counted_apart(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as f:
            f.write("# x\nS|1|2|A|N|M\nS|1|2|B|N|M\nT|1|2|C\n")
        self.assertEqual(b.count_stations(f.name), (2, 1))
        self.assertEqual(b.count_stations("/nonexistent"), (0, 0))

    def test_ferries_are_kept_when_their_query_fails(self):
        before = ("# x\nS|17|78|A|Hyd Metro|M\nS|9.98|76.27|High Court|Kochi Water Metro|W\n"
                  "S|9.97|76.24|Fort Kochi|Kochi Water Metro|W\nL|Hyd Metro|Blue|blue|0 0:10\n"
                  "L|Kochi Water Metro|HC-FK|teal|1 2:3690\nT|1|2|C\n")
        stations, lines = b.carry_ferries(before, 5)
        self.assertEqual([s[2] for s in stations], ["High Court", "Fort Kochi"])
        self.assertTrue(all(s[4] == "W" for s in stations))
        self.assertEqual(lines, ["L|Kochi Water Metro|HC-FK|teal|5 6:3690"])
        self.assertEqual(b.carry_ferries(None, 0), ([], []))

    def test_railway_lines_are_classed_by_speed(self):
        self.assertEqual(b.rail_class({"name": "東海道新幹線 のぞみ"}), "H")
        self.assertEqual(b.rail_class({"service": "high_speed", "name": "x"}), "H")
        self.assertEqual(b.rail_class({"name": "TGV inOui 6201: Paris → Lyon"}), "H")
        self.assertEqual(b.rail_class({"name": "Limited Express Azusa", "network": "JR East"}), "X")
        self.assertEqual(b.rail_class({"service": "long_distance", "name": "Coast Starlight"}), "X")
        self.assertEqual(b.rail_class({"name": "Yamanote Line", "network": "JR East"}), "L")

    def train_answer(self):
        # Two services over one track: the track comes once, both routes use it.
        return {"elements": [
            {"type": "relation", "id": 1, "tags": {"route": "train", "network": "JR Central", "name": "Tokaido Shinkansen Nozomi"},
             "members": [{"type": "node", "ref": 11, "role": "stop"}, {"type": "way", "ref": 5, "role": ""},
                         {"type": "node", "ref": 12, "role": "stop"}]},
            {"type": "relation", "id": 2, "tags": {"route": "train", "network": "JR Central", "name": "Tokaido Shinkansen Kodama"},
             "members": [{"type": "node", "ref": 12, "role": "stop"}, {"type": "way", "ref": 5, "role": ""},
                         {"type": "node", "ref": 11, "role": "stop"}]},
            {"type": "way", "id": 5, "nodes": [100, 101, 102],
             "geometry": [{"lat": 35.681, "lon": 139.767}, {"lat": 35.40, "lon": 139.20}, {"lat": 35.171, "lon": 136.882}]},
            {"type": "node", "id": 11, "lat": 35.681, "lon": 139.767, "tags": {"name": "東京", "name:en": "Tokyo"}},
            {"type": "node", "id": 12, "lat": 35.171, "lon": 136.882, "tags": {"name:en": "Nagoya"}},
        ]}

    def test_railway_lines_measure_along_the_track_with_their_class(self):
        routes, stops = b.inflate(self.train_answer())
        self.assertEqual(len(routes["elements"]), 2)
        self.assertTrue(all(m.get("geometry") or "lat" in m for r in routes["elements"] for m in r["members"]))
        stations, lines = b.build_network(routes, stops, station_kind="R")
        self.assertEqual(sorted(s[2] for s in stations), ["Nagoya", "Tokyo"])
        self.assertTrue(all(s[4] == "R" for s in stations))
        # High-speed, at a Shinkansen's pace in Japan; the default elsewhere.
        self.assertTrue(all(l.startswith("Q|JR Central|") and "|H220|" in l for l in lines))
        self.assertTrue(all("|H270|" in l for l in b.build_network(routes, stops, station_kind="R", cc="JP")[1]))
        metres = int(lines[0].split()[-1].split(":")[1])
        straight = b.metres(35.681, 139.767, 35.171, 136.882)
        self.assertGreater(metres, straight)

    def test_a_train_stop_at_a_platform_is_named_after_its_station(self):
        answer = self.train_answer()
        for e in answer["elements"]:
            if e.get("id") == 11 and e["type"] == "node":
                e["tags"] = {"name": "Tokyo - Track 14"}
        routes, stops = b.inflate(answer)
        rail = {"elements": [{"type": "node", "id": 900, "lat": 35.6812, "lon": 139.7671, "tags": {"name": "Tokyo"}}]}
        stations, _ = b.build_network(routes, stops, station_kind="R", rail=rail)
        self.assertIn("Tokyo", [s[2] for s in stations])
        self.assertEqual(b.PLATFORM.sub("", "Paris Gare du Nord - Voie 10"), "Paris Gare du Nord")
        self.assertEqual(b.PLATFORM.sub("", "München Hbf Gleis 27-36"), "München Hbf")
        self.assertEqual(b.PLATFORM.sub("", "München Hbf Gleis 27-36, Starnberger Bahnhof"), "München Hbf")
        self.assertEqual(b.PLATFORM.sub("", "Berlin Hauptbahnhof (Tief)"), "Berlin Hauptbahnhof")
        self.assertEqual(b.PLATFORM.sub("", "Via Roma"), "Via Roma")

    def test_a_ferry_mapped_as_ways_runs_terminal_to_terminal_across_a_border(self):
        answer = {"elements": [
            {"type": "way", "id": 7, "nodes": [1, 2], "tags": {"route": "ferry", "name": "Dover - Calais", "operator": "P&O"},
             "geometry": [{"lat": 51.124, "lon": 1.330}, {"lat": 51.05, "lon": 1.60}]},
            {"type": "way", "id": 8, "nodes": [2, 3], "tags": {"route": "ferry", "name": "Dover - Calais"},
             "geometry": [{"lat": 51.05, "lon": 1.60}, {"lat": 50.968, "lon": 1.865}]},
            {"type": "node", "id": 50, "lat": 51.126, "lon": 1.335, "tags": {"amenity": "ferry_terminal", "name": "Port of Dover"}},
            {"type": "node", "id": 51, "lat": 50.966, "lon": 1.861, "tags": {"amenity": "ferry_terminal", "name": "Port de Calais"}},
        ]}
        routes, terminals = b.ferry_crossings(answer)
        self.assertEqual(len(routes), 1)
        stations, lines = b.build_network({"elements": routes}, {"elements": terminals})
        self.assertEqual(sorted(s[2] for s in stations), ["Port de Calais", "Port of Dover"])
        self.assertTrue(all(s[4] == "W" for s in stations))
        self.assertEqual(len(lines), 1)
        self.assertGreater(int(lines[0].split()[-1].split(":")[1]), 40_000)
        # A crossing without a terminal at one end is left out.
        self.assertEqual(b.ferry_crossings({"elements": answer["elements"][:3]})[0], [])

    def test_new_rows_are_counted_and_kept_apart(self):
        import tempfile
        text = b.render("JP", [(35.0, 139.0, "A", "Toei", "M")], [], [(35.1, 139.1, "C")], "2026-10-08",
                        ([(35.68, 139.77, "Tokyo", "JR")], ["Q|JR|Yamanote|green|L|0 0:0"]), [(35.69, 139.70, "Shinjuku Expressway Bus Terminal")])
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as f:
            f.write(text)
        self.assertEqual(b.count_rows(f.name), {"M": 1, "W": 0, "R": 1, "T": 1, "B": 1})
        # A station that came back without its name does not count.
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as g:
            g.write("S|1|2||Hyderabad Metro|M\nS|1|2|Habsiguda|Hyderabad Metro|M\n")
        self.assertEqual(b.count_rows(g.name)["M"], 1)
        self.assertEqual(b.carry_rows(text, "R", "Q"), ["R|35.68000|139.77000|Tokyo|JR", "Q|JR|Yamanote|green|L|0 0:0"])
        # Apps that know only S, L and T rows skip the others wherever they are.
        kinds = [l[0] for l in text.splitlines() if not l.startswith("#")]
        self.assertEqual(kinds, ["S", "R", "Q", "T", "B"])

    def test_rail_dedupes_node_and_way_of_one_station(self):
        rows = b.build_rail({"elements": [
            {"type": "node", "id": 1, "lat": 17.4337, "lon": 78.5016, "tags": {"name": "Secunderabad Junction"}},
            {"type": "way", "id": 2, "center": {"lat": 17.4339, "lon": 78.5019}, "tags": {"name": "Secunderabad Junction"}},
            {"type": "node", "id": 3, "lat": 17.3897, "lon": 78.4987, "tags": {"name": "Kacheguda"}},
        ]})
        self.assertEqual([r[2] for r in rows], ["Kacheguda", "Secunderabad Junction"])


if __name__ == "__main__":
    unittest.main()
