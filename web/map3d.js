/*
 * Koode — 3D journey map for the browser viewer.
 *
 * MapLibre GL JS on free OpenFreeMap vector tiles (no key, no account). The
 * traveller's vehicle matches the app: a car, cab or bus is drawn from real
 * views of it (top-down and turned to the heading, or from behind when the
 * camera rides along); every other mode is extruded 3D solids — the SAME
 * models, from the SAME numbers, as the app's Vehicle3D.kt.
 *
 * Honesty rule shared with the app: the vehicle glides from the previous fix
 * to the new one and stops there. It never runs ahead of the last real fix.
 * For flights, the path to the destination is drawn dashed and labelled as an
 * estimate, never as a live track.
 *
 * Exposes window.KoodeMap = { init, draw, toggleFollow }.
 * Coordinates in and out of draw() are [lat, lng] arrays, like app.js.
 */
(function () {
  'use strict';

  var STYLE_URL = 'https://tiles.openfreemap.org/styles/dark';
  var CAR_PX = 44;
  var FOLLOW_TILT = 58;
  var FOLLOW_ZOOM = 15.5;
  var GLIDE_MS = 1100;

  // ---- models: keep in step with apps/android/.../ui/map/Vehicle3D.kt ----
  var GLASS = '#1E2B38', TYRE = '#141414', LIGHT = '#FFF3C4', TAIL = '#C1121F',
    WHITE = '#F4F6F8', METAL = '#C8D2DC';

  function box(cx, cy, len, wid, base, top, color) {
    var hx = len / 2, hy = wid / 2;
    return { o: [[cx + hx, cy - hy], [cx + hx, cy + hy], [cx - hx, cy + hy], [cx - hx, cy - hy]], b: base, t: top, c: color };
  }
  function poly(o, base, top, color) { return { o: o, b: base, t: top, c: color }; }
  function ngon(cx, cy, r, n, base, top, color) {
    var o = [];
    for (var i = 0; i < n; i++) { var a = 2 * Math.PI * i / n; o.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]); }
    return { o: o, b: base, t: top, c: color };
  }
  function mirrorY(o) { return o.map(function (p) { return [p[0], -p[1]]; }).reverse(); }
  function rect(len, wid) { return box(0, 0, len, wid, 0, 0, '').o; }

  function car(paint, roofSign) {
    var parts = [
      box(0, 0, 1.0, 0.44, 0.05, 0.17, paint),
      box(-0.06, 0, 0.52, 0.40, 0.17, 0.29, GLASS),
      box(-0.08, 0, 0.40, 0.36, 0.29, 0.31, paint),
      box(0.495, -0.14, 0.012, 0.09, 0.09, 0.13, LIGHT),
      box(0.495, 0.14, 0.012, 0.09, 0.09, 0.13, LIGHT),
      box(-0.495, -0.15, 0.012, 0.09, 0.10, 0.14, TAIL),
      box(-0.495, 0.15, 0.012, 0.09, 0.10, 0.14, TAIL)
    ];
    [0.31, -0.31].forEach(function (x) { [0.225, -0.225].forEach(function (y) { parts.push(box(x, y, 0.17, 0.05, 0, 0.11, TYRE)); }); });
    if (roofSign) parts.push(box(-0.08, 0, 0.12, 0.20, 0.31, 0.37, '#1D1D1D'));
    return { parts: parts, scale: 1.0, ground: [rect(1.08, 0.52)], groundColor: '#000000', cruise: 0 };
  }

  var BIKE = {
    parts: [
      box(0.34, 0, 0.28, 0.06, 0, 0.26, TYRE), box(-0.34, 0, 0.28, 0.06, 0, 0.26, TYRE),
      box(0.05, 0, 0.50, 0.14, 0.18, 0.30, '#3E63DD'), box(-0.15, 0, 0.28, 0.16, 0.28, 0.33, '#222222'),
      box(0.22, 0, 0.04, 0.36, 0.34, 0.37, '#222222'), box(-0.10, 0, 0.20, 0.26, 0.33, 0.62, '#34495E'),
      ngon(-0.06, 0, 0.09, 8, 0.62, 0.74, WHITE)
    ],
    scale: 0.62, ground: [rect(1.0, 0.3)], groundColor: '#000000', cruise: 0
  };

  var BUS = (function () {
    var paint = '#E07A1F';
    var parts = [
      box(0, 0, 1.0, 0.21, 0.03, 0.15, paint), box(0, 0, 0.97, 0.214, 0.15, 0.23, GLASS),
      box(0, 0, 1.0, 0.21, 0.23, 0.28, paint), box(-0.10, 0, 0.25, 0.12, 0.28, 0.30, '#D9DEE3'),
      box(0.502, 0, 0.008, 0.19, 0.10, 0.23, GLASS), box(0.502, 0, 0.008, 0.15, 0.235, 0.27, '#FFB000')
    ];
    [0.33, -0.30].forEach(function (x) { [0.108, -0.108].forEach(function (y) { parts.push(box(x, y, 0.10, 0.02, 0, 0.07, TYRE)); }); });
    return { parts: parts, scale: 1.55, ground: [rect(1.04, 0.26)], groundColor: '#000000', cruise: 0 };
  })();

  var TRAIN = (function () {
    var paint = '#2B4C7E', stripe = '#F2C14E';
    var parts = [box(0, 0.028, 1.08, 0.006, 0, 0.004, '#6B6B6B'), box(0, -0.028, 1.08, 0.006, 0, 0.004, '#6B6B6B')];
    [0.34, 0, -0.34].forEach(function (cx) {
      parts.push(box(cx, 0, 0.32, 0.075, 0.012, 0.095, paint));
      parts.push(box(cx, 0, 0.322, 0.077, 0.045, 0.060, stripe));
      parts.push(box(cx, 0, 0.30, 0.060, 0.095, 0.105, METAL));
    });
    parts.push(poly([[0.5, -0.0375], [0.53, -0.02], [0.54, 0], [0.53, 0.02], [0.5, 0.0375]], 0.012, 0.085, paint));
    parts.push(box(0.49, 0, 0.02, 0.07, 0.06, 0.085, GLASS));
    return { parts: parts, scale: 2.3, ground: [rect(1.06, 0.1)], groundColor: '#000000', cruise: 0 };
  })();

  var FLIGHT = (function () {
    var fuselage = [[0.5, 0], [0.44, 0.045], [0.30, 0.055], [-0.38, 0.05], [-0.5, 0.02],
      [-0.5, -0.02], [-0.38, -0.05], [0.30, -0.055], [0.44, -0.045]];
    var wing = [[0.10, 0.05], [-0.12, 0.5], [-0.20, 0.5], [-0.08, 0.05]];
    var tail = [[-0.38, 0.03], [-0.48, 0.19], [-0.52, 0.19], [-0.47, 0.03]];
    return {
      parts: [
        poly(fuselage, 0, 0.10, WHITE), poly(wing, 0.03, 0.05, METAL), poly(mirrorY(wing), 0.03, 0.05, METAL),
        ngon(0.02, 0.2, 0.035, 8, 0, 0.045, '#8A96A3'), ngon(0.02, -0.2, 0.035, 8, 0, 0.045, '#8A96A3'),
        poly(tail, 0.07, 0.085, METAL), poly(mirrorY(tail), 0.07, 0.085, METAL),
        box(-0.43, 0, 0.14, 0.016, 0.10, 0.26, '#2F6FED'), box(0.43, 0, 0.04, 0.06, 0.07, 0.102, GLASS)
      ],
      scale: 1.7, ground: [fuselage, wing, mirrorY(wing)], groundColor: '#000000', cruise: 0.9
    };
  })();

  var SHIP = (function () {
    var hull = [[0.5, 0], [0.36, 0.11], [-0.44, 0.12], [-0.5, 0.09], [-0.5, -0.09], [-0.44, -0.12], [0.36, -0.11]];
    function scaled(k) { return hull.map(function (p) { return [p[0] * k, p[1] * k]; }); }
    return {
      parts: [
        poly(scaled(1.012), 0, 0.02, '#B23A48'), poly(hull, 0.02, 0.07, '#1F3B57'), poly(scaled(0.95), 0.07, 0.075, '#D9C8A9'),
        box(0.14, 0, 0.20, 0.14, 0.075, 0.12, '#2E86AB'), box(-0.22, 0, 0.34, 0.17, 0.075, 0.17, WHITE),
        box(-0.26, 0, 0.20, 0.14, 0.17, 0.24, WHITE), box(-0.155, 0, 0.012, 0.13, 0.20, 0.225, GLASS),
        ngon(-0.30, 0, 0.035, 10, 0.24, 0.31, '#E4572E'), ngon(-0.30, 0, 0.036, 10, 0.31, 0.335, '#1A1A1A')
      ],
      scale: 1.9,
      ground: [[[-0.45, 0.10], [-1.4, 0.42], [-1.4, 0.30], [-0.5, 0], [-1.4, -0.30], [-1.4, -0.42], [-0.45, -0.10]]],
      groundColor: '#FFFFFF', cruise: 0
    };
  })();

  var MODELS = { CAR: car('#E5484D', false), CAB: car('#F5C518', true), AUTO: car('#F5C518', true), BIKE: BIKE, BUS: BUS, TRAIN: TRAIN, METRO: TRAIN, FLIGHT: FLIGHT, SHIP: SHIP };
  function model(mode) { return MODELS[mode] || MODELS.CAR; }

  // ---- geometry --------------------------------------------------------------

  function ring(outline, lat, lng, bearing, lengthM) {
    var b = bearing * Math.PI / 180, sinB = Math.sin(b), cosB = Math.cos(b);
    var lngScale = 111320 * Math.max(0.01, Math.cos(lat * Math.PI / 180));
    var pts = outline.map(function (p) {
      var east = (p[0] * sinB + p[1] * cosB) * lengthM;
      var north = (p[0] * cosB - p[1] * sinB) * lengthM;
      return [lng + east / lngScale, lat + north / 111320];
    });
    pts.push(pts[0]);
    return pts;
  }

  function place(mode, pos, bearing, mpp, airborne) {
    var m = model(mode);
    var lengthM = Math.min(80000, Math.max(3, mpp * CAR_PX * m.scale));
    var lift = airborne ? m.cruise * lengthM : 0;
    var solids = m.parts.map(function (part) {
      return {
        type: 'Feature',
        properties: { c: part.c, b: Math.max(0, part.b * lengthM + lift), h: Math.max(0.1, part.t * lengthM + lift) },
        geometry: { type: 'Polygon', coordinates: [ring(part.o, pos[0], pos[1], bearing, lengthM)] }
      };
    });
    var ground = m.ground.map(function (o) {
      return { type: 'Feature', properties: { c: m.groundColor }, geometry: { type: 'Polygon', coordinates: [ring(o, pos[0], pos[1], bearing, lengthM)] } };
    });
    return { solids: solids, ground: ground };
  }

  function bearingOf(a, b) {
    var la1 = a[0] * Math.PI / 180, la2 = b[0] * Math.PI / 180, dL = (b[1] - a[1]) * Math.PI / 180;
    var y = Math.sin(dL) * Math.cos(la2);
    var x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dL);
    return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
  }
  /** The heading of the last stretch of the trail into [to]: the way it was going on arrival here. */
  function trailHeading(trail, to) {
    for (var i = trail.length - 1; i >= 0; i--) {
      if (distM(trail[i], to) > 15) return bearingOf(trail[i], to);
    }
    return state.bearing;
  }
  function lerpBearing(from, to, t) { var d = ((to - from + 540) % 360) - 180; return (from + d * t + 360) % 360; }
  function distM(a, b) {
    var R = 6371000, dLat = (b[0] - a[0]) * Math.PI / 180, dLng = (b[1] - a[1]) * Math.PI / 180;
    var h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(a[0] * Math.PI / 180) * Math.cos(b[0] * Math.PI / 180) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return 2 * R * Math.asin(Math.sqrt(h));
  }
  function greatCircle(a, b, n) {
    n = n || 64;
    var r = Math.PI / 180, la1 = a[0] * r, lo1 = a[1] * r, la2 = b[0] * r, lo2 = b[1] * r;
    var d = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin((la2 - la1) / 2), 2) +
      Math.cos(la1) * Math.cos(la2) * Math.pow(Math.sin((lo2 - lo1) / 2), 2)));
    if (d < 1e-9) return [[a[1], a[0]], [b[1], b[0]]];
    var out = [];
    for (var i = 0; i <= n; i++) {
      var f = i / n, A = Math.sin((1 - f) * d) / Math.sin(d), B = Math.sin(f * d) / Math.sin(d);
      var x = A * Math.cos(la1) * Math.cos(lo1) + B * Math.cos(la2) * Math.cos(lo2);
      var y = A * Math.cos(la1) * Math.sin(lo1) + B * Math.cos(la2) * Math.sin(lo2);
      var z = A * Math.sin(la1) + B * Math.sin(la2);
      out.push([Math.atan2(y, x) / r, Math.atan2(z, Math.sqrt(x * x + y * y)) / r]);
    }
    return out;
  }

  // ---- map -------------------------------------------------------------------

  var map = null, ready = false, pending = null;
  var state = { pos: null, bearing: 0, mode: 'CAR', airborne: false, follow: false, userChose: false, framed: false, glide: null, live: false };
  var EMPTY = { type: 'FeatureCollection', features: [] };

  function fc(features) { return { type: 'FeatureCollection', features: features }; }
  function line(latlngs) {
    return latlngs.length < 2 ? EMPTY : fc([{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: latlngs.map(function (p) { return [p[1], p[0]]; }) } }]);
  }
  function point(p) { return p ? fc([{ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [p[1], p[0]] } }]) : EMPTY; }
  function setData(id, data) { var s = map && map.getSource(id); if (s) s.setData(data); }

  function mpp(lat) { return 40075016.686 * Math.cos(lat * Math.PI / 180) / (512 * Math.pow(2, map.getZoom())); }

  // Every mode on the ground or water is drawn from real views of it, like
  // the app's VehicleMarker: top-down and turned to the heading in the
  // overview, from behind when the camera rides along. The metro and the
  // train are seen only from above; a walker always stands, from behind when
  // heading up the screen and from the front when heading down it. Only a
  // flight is its 3D model.
  var VIEWS = {
    CAR: { key: 'car', len: 50, rear: 46 }, CAB: { key: 'cab', len: 50, rear: 46 },
    AUTO: { key: 'auto', len: 40, rear: 38 }, BIKE: { key: 'bike', len: 40, rear: 32 },
    CYCLE: { key: 'cycle', len: 38, rear: 22 }, BUS: { key: 'bus', len: 78, rear: 54 },
    METRO: { key: 'metro', len: 96, topOnly: true }, TRAIN: { key: 'train', len: 104, topOnly: true },
    SHIP: { key: 'ship', len: 66, rear: 62 }, WALK: { key: 'walk', upright: 46 }
  };
  var vehicle = null;

  function viewKind(v, cameraBearing, pitch) {
    var diff = ((state.bearing - cameraBearing + 540) % 360) - 180;
    if (v.upright) return Math.abs(diff) <= 90 ? 'rear' : 'front';
    if (v.topOnly) return 'top';
    return pitch >= 30 && Math.abs(diff) <= 40 ? 'rear' : 'top';
  }

  function renderVehicle() {
    if (!ready || !state.pos) return;
    var v = VIEWS[state.mode];
    if (v) {
      setData('kd-vehicle', EMPTY);
      setData('kd-ground', EMPTY);
      var kind = viewKind(v, map.getBearing(), map.getPitch());
      if (!vehicle) {
        var el = document.createElement('div');
        el.className = 'kd-veh';
        el.innerHTML = '<img alt="" draggable="false">';
        vehicle = new maplibregl.Marker({ element: el }).setLngLat([state.pos[1], state.pos[0]]).addTo(map);
      }
      var el2 = vehicle.getElement();
      var img = el2.querySelector('img');
      var src = 'art/map-' + v.key + '-' + kind + '.webp';
      if (img.getAttribute('src') !== src) img.setAttribute('src', src);
      el2.classList.toggle('top', kind === 'top');
      el2.classList.toggle('rear', kind !== 'top');
      img.style.height = kind === 'top' ? v.len + 'px' : (v.upright ? v.upright + 'px' : '');
      img.style.width = kind !== 'top' && !v.upright ? v.rear + 'px' : '';
      if (kind === 'top') {
        vehicle.setRotationAlignment('map').setPitchAlignment('map').setRotation(state.bearing);
        vehicle.setOffset([0, 0]);
      } else {
        vehicle.setRotationAlignment('viewport').setPitchAlignment('viewport').setRotation(0);
        vehicle.setOffset([0, -Math.round(v.upright ? v.upright * 0.45 : v.rear * 0.25)]);
      }
      vehicle.setLngLat([state.pos[1], state.pos[0]]);
    } else {
      if (vehicle) { vehicle.remove(); vehicle = null; }
      var placed = place(state.mode, state.pos, state.bearing, mpp(state.pos[0]), state.airborne);
      setData('kd-vehicle', fc(placed.solids));
      setData('kd-ground', fc(placed.ground));
    }
    setData('kd-halo', point(state.pos));
  }

  function heroPadding() {
    var el = document.getElementById('map');
    var h = el ? el.clientHeight : 400;
    return { top: Math.min(140, h * 0.28), bottom: Math.min(120, h * 0.24), left: 20, right: 20 };
  }

  function followCamera(entering) {
    if (!state.pos) return;
    // A jump while the camera eases in would stop it part-way, at whatever
    // zoom it had reached -- from the overview, that is the whole world.
    if (!entering && Date.now() < (state.easingUntil || 0)) return;
    var opts = {
      center: [state.pos[1], state.pos[0]], bearing: state.bearing, pitch: FOLLOW_TILT,
      zoom: entering ? FOLLOW_ZOOM : map.getZoom(), padding: heroPadding()
    };
    if (entering) { map.easeTo(Object.assign({ duration: 900 }, opts)); state.easingUntil = Date.now() + 950; }
    else map.jumpTo(opts);
  }

  function frameAll(points, animate) {
    var pts = points.filter(Boolean);
    if (pts.length === 0) return;
    if (pts.length === 1) { map.jumpTo({ center: [pts[0][1], pts[0][0]], zoom: 13, pitch: 0, bearing: 0 }); return; }
    var b = new maplibregl.LngLatBounds([pts[0][1], pts[0][0]], [pts[0][1], pts[0][0]]);
    pts.forEach(function (p) { b.extend([p[1], p[0]]); });
    // fitBounds keeps the current tilt, so level the camera explicitly: the
    // overview is a flat map of the whole journey.
    // The follow camera leaves its own padding and tilt on the map, and a
    // fit adds to them, so measure the fit level and unpadded, then ease
    // there from wherever the camera is now.
    var none = { top: 0, bottom: 0, left: 0, right: 0 };
    var from = { center: map.getCenter(), zoom: map.getZoom(), bearing: map.getBearing(), pitch: map.getPitch(), padding: map.getPadding() };
    map.jumpTo({ pitch: 0, bearing: 0, padding: none });
    var cam = map.cameraForBounds(b, { padding: heroPadding(), maxZoom: 15 });
    map.jumpTo(from);
    if (!cam) return;
    map.easeTo({ center: cam.center, zoom: Math.min(15, cam.zoom), bearing: 0, pitch: 0, padding: none, duration: animate ? 800 : 0 });
  }

  function updateFollowButton() {
    var btn = document.getElementById('follow');
    if (btn) btn.textContent = state.follow ? '🗺  Overview' : '🧭  Follow in 3D';
  }

  function setFollow(on, byUser) {
    if (byUser) state.userChose = true;
    if (state.follow === on) return;
    state.follow = on;
    updateFollowButton();
    if (!ready) return;
    if (on) followCamera(true);
    else if (pending) frameAll([pending.origin, pending.destination, pending.current].concat(pending.trail || []), true);
  }

  function install() {
    ['kd-trail', 'kd-arc', 'kd-origin', 'kd-dest', 'kd-halo', 'kd-ground', 'kd-vehicle'].forEach(function (id) {
      map.addSource(id, { type: 'geojson', data: EMPTY });
    });
    map.addLayer({ id: 'kd-trail-casing', type: 'line', source: 'kd-trail', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#07131D', 'line-width': 8.5, 'line-opacity': 0.8 } });
    map.addLayer({ id: 'kd-trail-layer', type: 'line', source: 'kd-trail', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#38BDF8', 'line-width': 5 } });
    map.addLayer({ id: 'kd-arc-layer', type: 'line', source: 'kd-arc', paint: { 'line-color': '#38BDF8', 'line-width': 3, 'line-opacity': 0.85, 'line-dasharray': [1.6, 1.6] } });
    map.addLayer({ id: 'kd-ground-layer', type: 'fill', source: 'kd-ground', paint: { 'fill-color': ['get', 'c'], 'fill-opacity': 0.22 } });
    map.addLayer({ id: 'kd-halo-layer', type: 'circle', source: 'kd-halo', paint: { 'circle-color': '#38BDF8', 'circle-radius': 14, 'circle-opacity': 0, 'circle-pitch-alignment': 'map' } });
    map.addLayer({ id: 'kd-origin-layer', type: 'circle', source: 'kd-origin', paint: { 'circle-color': '#2DD4BF', 'circle-radius': 7, 'circle-stroke-color': '#fff', 'circle-stroke-width': 3, 'circle-pitch-alignment': 'map' } });
    map.addLayer({ id: 'kd-dest-halo', type: 'circle', source: 'kd-dest', paint: { 'circle-color': '#F59E0B', 'circle-radius': 15, 'circle-opacity': 0.3, 'circle-pitch-alignment': 'map' } });
    map.addLayer({ id: 'kd-dest-layer', type: 'circle', source: 'kd-dest', paint: { 'circle-color': '#F59E0B', 'circle-radius': 7, 'circle-stroke-color': '#fff', 'circle-stroke-width': 3, 'circle-pitch-alignment': 'map' } });
    map.addLayer({ id: 'kd-vehicle-layer', type: 'fill-extrusion', source: 'kd-vehicle', paint: { 'fill-extrusion-color': ['get', 'c'], 'fill-extrusion-base': ['get', 'b'], 'fill-extrusion-height': ['get', 'h'], 'fill-extrusion-opacity': 1 } });
  }

  function pulse(t) {
    if (ready && map.getLayer('kd-halo-layer')) {
      if (state.live && state.pos) {
        var p = (t % 1600) / 1600;
        map.setPaintProperty('kd-halo-layer', 'circle-radius', 10 + 26 * p);
        map.setPaintProperty('kd-halo-layer', 'circle-opacity', 0.38 * (1 - p));
      } else {
        map.setPaintProperty('kd-halo-layer', 'circle-opacity', 0);
      }
    }
    window.requestAnimationFrame(pulse);
  }

  function init(containerId) {
    if (map || !window.maplibregl) return;
    try {
      map = new maplibregl.Map({
        container: containerId, style: STYLE_URL, center: [78.9629, 20.5937], zoom: 4.2,
        attributionControl: { compact: true }
      });
    } catch (e) {
      // A decade-old laptop without WebGL still gets the journey, the arrival
      // time and the timeline — just not the map.
      map = null;
      var el = document.getElementById(containerId);
      if (el) {
        el.innerHTML = '<p class="map-unavailable">The map needs WebGL, which this browser has turned off. ' +
          'Everything else on this page still works.</p>';
      }
      return;
    }
    map.on('load', function () {
      install();
      ready = true;
      map.on('rotate', renderVehicle);
      map.on('pitch', renderVehicle);
      map.on('zoom', renderVehicle);
      if (pending) draw(pending);
    });
    // Moving the map by hand leaves Follow, as navigation apps do.
    ['dragstart', 'rotatestart', 'pitchstart'].forEach(function (ev) {
      map.on(ev, function (e) { if (e.originalEvent && state.follow) setFollow(false, false); });
    });
    map.on('zoom', renderVehicle);
    // The journey section starts hidden; resize the map the moment it appears.
    if (window.ResizeObserver) {
      new ResizeObserver(function () { map.resize(); }).observe(document.getElementById(containerId));
    }
    window.requestAnimationFrame(pulse);
    updateFollowButton();
  }

  /**
   * opts: { origin, destination, trail, current, mode, moving, live, playback }
   */
  function draw(opts) {
    pending = opts;
    if (!ready || !map) return;
    var mode = opts.mode || 'CAR';
    state.mode = mode;
    state.live = !!opts.live && !opts.playback;
    state.airborne = mode === 'FLIGHT' && (!!opts.moving || !!opts.playback);

    var trail = opts.trail || [];
    setData('kd-trail', line(trail));
    setData('kd-origin', point(opts.origin));
    setData('kd-dest', point(opts.destination));
    var arcFrom = opts.current || opts.origin;
    setData('kd-arc', mode === 'FLIGHT' && arcFrom && opts.destination
      ? fc([{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: greatCircle(arcFrom, opts.destination) } }])
      : EMPTY);
    var note = document.getElementById('flight-note');
    if (note) note.classList.toggle('hidden', !(mode === 'FLIGHT' && opts.destination));

    if (opts.current && !state.userChose && !state.follow && !opts.playback) {
      state.follow = true; updateFollowButton(); state.framedPending = true;
    }
    if (!state.framed) {
      state.framed = true;
      if (!state.follow) frameAll([opts.origin, opts.destination, opts.current].concat(trail), false);
    }

    var to = opts.current;
    if (!to) return;
    var from = state.pos || to;
    var toBearing = trail.length >= 2 && opts.playback
      ? bearingOf(trail[Math.max(0, trail.length - 4)], trail[trail.length - 1])
      : (distM(from, to) > 8 ? bearingOf(from, to) : (state.pos ? state.bearing : trailHeading(trail, to)));
    var fromBearing = state.bearing;

    if (state.glide) { window.cancelAnimationFrame(state.glide); state.glide = null; }
    if (opts.playback || !state.pos) {
      state.pos = to; state.bearing = toBearing;
      renderVehicle();
      if (state.follow) followCamera(!!state.framedPending);
      state.framedPending = false;
      return;
    }
    var start = null;
    function step(ts) {
      if (start === null) start = ts;
      var t = Math.min(1, (ts - start) / GLIDE_MS), e = t * t * (3 - 2 * t);
      state.pos = [from[0] + (to[0] - from[0]) * e, from[1] + (to[1] - from[1]) * e];
      state.bearing = lerpBearing(fromBearing, toBearing, e);
      renderVehicle();
      if (state.follow) followCamera(false);
      state.glide = t < 1 ? window.requestAnimationFrame(step) : null;
    }
    state.glide = window.requestAnimationFrame(step);
  }

  window.KoodeMap = {
    init: init,
    draw: draw,
    toggleFollow: function () { setFollow(!state.follow, true); },
    /** Geometry only, for parity checks against the app's Vehicle3D.kt. */
    _place: place,
    MODES: {
      CAR: ['🚗', 'Car'], BIKE: ['🏍', 'Bike'], CAB: ['🚕', 'Cab'], AUTO: ['🛺', 'Auto'], BUS: ['🚌', 'Bus'],
      TRAIN: ['🚆', 'Train'], METRO: ['🚇', 'Metro'], FLIGHT: ['✈️', 'Flight'], SHIP: ['🚢', 'Ship'],
      CYCLE: ['🚲', 'Cycle'], WALK: ['🚶', 'Walking']
    }
  };
})();
