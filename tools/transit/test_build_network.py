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
        stations, lines = b.build_metro(routes(), stops())
        self.assertEqual(sorted(s[2] for s in stations), ["Alpha", "Bravo", "Charlie"])
        self.assertEqual(len(lines), 2)  # the line under construction is left out

    def test_distance_follows_the_track_not_the_straight_line(self):
        stations, lines = b.build_metro(routes(), stops())
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

    def test_rail_dedupes_node_and_way_of_one_station(self):
        rows = b.build_rail({"elements": [
            {"type": "node", "id": 1, "lat": 17.4337, "lon": 78.5016, "tags": {"name": "Secunderabad Junction"}},
            {"type": "way", "id": 2, "center": {"lat": 17.4339, "lon": 78.5019}, "tags": {"name": "Secunderabad Junction"}},
            {"type": "node", "id": 3, "lat": 17.3897, "lon": 78.4987, "tags": {"name": "Kacheguda"}},
        ]})
        self.assertEqual([r[2] for r in rows], ["Kacheguda", "Secunderabad Junction"])


if __name__ == "__main__":
    unittest.main()
