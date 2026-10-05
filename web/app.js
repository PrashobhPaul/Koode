/*
 * Koode — browser viewer.
 *
 * The point of this page: a parent who will never install an app can still
 * open a link and know their child is safe. So it is a single static page with
 * no build step, no framework and no account — it derives the same capability
 * the Android app derives, and calls the same read-only RPCs.
 *
 * Two ways in, exactly as in the app:
 *
 *   With the passcode   accessKey = SHA-256("<journeyId>:<passcode>"), computed
 *                       here with WebCrypto. The passcode itself is never sent;
 *                       the server only ever sees a hash that grants read access
 *                       while the journey is live.
 *   With your name      no passcode: this browser asks to follow under a name
 *                       (tp_request_join) with a random token it keeps for
 *                       itself, waits for the traveller to approve it by name,
 *                       then reads with that token (the *_t RPCs).
 *
 * Either way nothing on this page can write to the journey.
 *
 * The rule that governs every message below: a journey is over only when its
 * traveller ended it. A failed fetch means we could not reach the service; it
 * never means the journey finished.
 */
(function () {
  'use strict';

  var CFG = window.KOODE_CONFIG || {};
  var PREFIX = 'TP-';
  var CODE_LENGTH = 8;
  var PASSCODE_LENGTH = 6;
  var PLAYBACK_SPEEDS = [5, 10, 20, 30];

  // ---- tiny DOM helpers --------------------------------------------------
  function $(id) { return document.getElementById(id); }
  function show(el) { el.classList.remove('hidden'); }
  function hide(el) { el.classList.add('hidden'); }
  function text(id, value) { var el = $(id); if (el) el.textContent = value; }

  // ---- credentials -------------------------------------------------------

  function digitsOnly(raw, max) {
    var out = (raw || '').replace(/\D/g, '');
    return max ? out.slice(0, max) : out;
  }

  /** SHA-256 hex — the same material the Android app hashes. */
  async function accessKeyFor(journeyId, passcode) {
    var material = journeyId.trim().toUpperCase() + ':' + passcode.trim().toUpperCase();
    var digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(material));
    return Array.prototype.map
      .call(new Uint8Array(digest), function (b) { return b.toString(16).padStart(2, '0'); })
      .join('');
  }

  // ---- backend -----------------------------------------------------------

  async function rpc(fn, args) {
    if (!CFG.SUPABASE_URL || !CFG.SUPABASE_ANON_KEY) return null;
    try {
      var res = await fetch(CFG.SUPABASE_URL.replace(/\/$/, '') + '/rest/v1/rpc/' + fn, {
        method: 'POST',
        headers: {
          apikey: CFG.SUPABASE_ANON_KEY,
          Authorization: 'Bearer ' + CFG.SUPABASE_ANON_KEY,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(args)
      });
      if (!res.ok) return null;
      var body = await res.text();
      if (!body || body === 'null') return null;
      return JSON.parse(body);
    } catch (e) {
      return null;
    }
  }

  var serverReachable = function () { return rpc('tp_now', {}); };

  /**
   * The journey's readers for one credential: the access key (passcode) or
   * this browser's approved token. Everything above this line reads through
   * one of these and never cares which.
   *   cred = { kind: 'key', key }  |  { kind: 'token', tripId, token }
   */
  function readerFor(cred) {
    if (cred.kind === 'key') {
      return {
        meta: function () { return rpc('tp_get_meta', { p_access_key: cred.key }); },
        state: function () { return rpc('tp_get_state', { p_access_key: cred.key }); },
        events: function () { return rpc('tp_get_events', { p_access_key: cred.key, p_since: 0 }); },
        report: function () { return { action: 'download', accessKey: cred.key }; }
      };
    }
    var args = { p_trip_id: cred.tripId, p_viewer_token: cred.token };
    return {
      meta: function () { return rpc('tp_get_meta_t', args); },
      state: function () { return rpc('tp_get_state_t', args); },
      events: function () { return rpc('tp_get_events_t', { p_trip_id: cred.tripId, p_viewer_token: cred.token, p_since: 0 }); },
      report: function () { return { action: 'download', tripId: cred.tripId, viewerToken: cred.token }; }
    };
  }

  // ---- this browser's identity when it follows by name --------------------
  // One random token per browser, like the app's per-device token: the
  // traveller approves "Amma", and this token is what "Amma" means afterwards.
  // Kept in localStorage so a refresh, or tomorrow's journey, needs no new
  // approval. Nothing about it identifies the person to anyone else.

  function store(key, value) {
    try {
      if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value);
    } catch (e) { /* private mode: approvals simply don't survive a reload */ }
  }
  function load(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }

  function viewerToken() {
    var t = load('koode.viewerToken');
    if (t && t.length >= 32) return t;
    var bytes = new Uint8Array(24);
    crypto.getRandomValues(bytes);
    t = Array.prototype.map.call(bytes, function (b) { return b.toString(16).padStart(2, '0'); }).join('');
    store('koode.viewerToken', t);
    return t;
  }

  var requestJoin = function (tripId, name) {
    return rpc('tp_request_join', { p_trip_id: tripId, p_viewer_token: viewerToken(), p_viewer_name: name });
  };
  var joinStatus = function (tripId) {
    return rpc('tp_join_status', { p_trip_id: tripId, p_viewer_token: viewerToken() });
  };

  // ---- formatting --------------------------------------------------------

  function clock(ms) {
    return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function clockWithDay(ms) {
    var d = new Date(ms);
    var sameDay = d.toDateString() === new Date().toDateString();
    return sameDay ? clock(ms) : d.toLocaleString([], {
      weekday: 'short', hour: '2-digit', minute: '2-digit'
    });
  }

  function ago(ms) {
    var s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return s + ' sec ago';
    if (s < 3600) return Math.round(s / 60) + ' min ago';
    var h = Math.floor(s / 3600);
    return h + 'h ' + Math.round((s % 3600) / 60) + 'm ago';
  }

  /**
   * A distance in the traveller's own units (the journey says which), and at
   * sea on a cruise in nautical miles, as the ship itself measures it.
   */
  function dist(metres, mode) {
    var m = metres || 0;
    function fmt(v, unit) { return (v >= 100 ? Math.round(v) : v.toFixed(1)) + ' ' + unit; }
    if (mode === 'SHIP') return fmt(m / 1852, 'nmi');
    var units = (latest.meta && latest.meta.units) || 'METRIC';
    return units === 'IMPERIAL' ? fmt(m / 1609.344, 'mi') : fmt(m / 1000, 'km');
  }
  function modeNow() {
    var st = latest.state || {};
    return st.mode || (latest.meta && latest.meta.transportMode) || 'CAR';
  }

  var EVENT_LABELS = {
    TRIP_STARTED: ['🚦', 'Journey started'],
    TRIP_PAUSED: ['⏸', 'Journey paused'],
    TRIP_RESUMED: ['▶', 'Journey resumed'],
    TRIP_COMPLETED: ['🏁', 'Journey ended'],
    JOURNEY_REPORT_AVAILABLE: ['📄', 'The verified journey report is ready'],
    DESTINATION_CHANGED: ['🧭', 'Destination changed'],
    STOP_STARTED: ['🅿', 'Stopped'],
    STOP_ENDED: ['▶', 'On the move again'],
    LONG_STOP: ['⏳', 'Long stop'],
    TOLL_CROSSED: ['🛣', 'Toll crossed'],
    ARRIVAL_DETECTED: ['📍', 'Arrived near the destination'],
    BREAK_CHECKPOINT: ['✅', 'Break logged'],
    WATER_REPORTED: ['💧', 'Water'],
    FOOD_REPORTED: ['🍛', 'Food'],
    TEA_COFFEE_REPORTED: ['☕', 'Tea / coffee'],
    SNACK_REPORTED: ['🍪', 'Snack'],
    TOILET_REPORTED: ['🚻', 'Toilet'],
    REST_REPORTED: ['😴', 'Rest'],
    FUEL_STOP: ['⛽', 'Refuelled'],
    CHARGE_STOP: ['🔌', 'Charged'],
    OVERNIGHT_CONFIRMED: ['🌙', 'Overnight stay'],
    MORNING_RESUME: ['🌅', 'Back on the road'],
    QUICK_NOTE: ['📝', 'Note'],
    PASSENGER_JOINED: ['👤', 'Passenger joined'],
    PASSENGER_LEFT: ['👋', 'Passenger left'],
    VEHICLE_ISSUE: ['🔧', 'Vehicle issue'],
    SOS_ACTIVATED: ['🚨', 'SOS activated'],
    SOS_RESOLVED: ['✅', 'SOS resolved'],
    BATTERY_LOW: ['🔋', 'Phone battery low'],
    DEVICE_SHUTDOWN: ['🔌', 'Phone switched off'],
    DEVICE_BACK_ONLINE: ['🔆', 'Phone back online'],
    SIM_CHANGED: ['⚠️', 'The SIM in this phone was changed or removed'],
    BOARDED: ['🎫', 'Boarded'],
    TRANSIT_HALTED: ['⏸', 'Halted'],
    TRANSIT_RESUMED: ['▶', 'Moving again'],
    DEBOARDED: ['🚶', 'Got off'],
    LEG_STARTED: ['🧭', 'Next stage started'],
    LEG_COMPLETED: ['✅', 'Stage completed'],
    WELLBEING_ALERT: ['💬', 'Wellbeing update'],
    JOURNEY_AUTO_CLOSED: ['🏁', 'Journey closed automatically'],
    TRAVELLER_CONFIRMED_SAFE: ['💚', 'Confirmed arriving safely'],
    HALT_CONFIRMED: ['🛏', 'Halting'],
    HALT_CANCELLED: ['▶', 'Halt cancelled'],
    HALT_RESUMED: ['🌅', 'Resumed after the halt'],
    TRAVEL_MODE_CHANGED: ['🔁', 'Travel mode changed'],
    PLANNED_HALT_CREATED: ['🗓', 'Halt planned'],
    PLANNED_HALT_CHANGED: ['🗓', 'Planned halt changed'],
    PLANNED_HALT_CANCELLED: ['🗓', 'Planned halt cancelled'],
    ETA_SIGNIFICANTLY_CHANGED: ['🕒', 'Arrival time changed'],
    JOURNEY_PLAN_REVISED: ['🧭', 'Journey plan updated']
  };

  /**
   * The traveller's own coaching (suggestions, reminders, "taking a break",
   * the halt question) and the periodic update are notifications, not
   * timeline entries — the same rule as the app.
   */
  var NOT_IN_TIMELINE = ['TIMELINE_EDIT', 'WELLBEING_NUDGE', 'JOURNEY_UPDATE', 'HALT_SUGGESTED',
    'JOURNEY_CLOSE_PROMPTED', 'JOURNEY_REOPENED', 'JOURNEY_CLOSED', 'JOURNEY_REVIEW_STARTED',
    'JOURNEY_ANALYTICS_APPROVED', 'JOURNEY_FINALIZED', 'TRAVEL_EXPENSES_APPROVED',
    'WATER_NUDGE', 'WATER_REMINDER', 'WATER_ACKNOWLEDGED',
    'FOOD_NUDGE', 'FOOD_REMINDER', 'FOOD_ACKNOWLEDGED',
    'BREAK_NUDGE', 'BREAK_REMINDER', 'BREAK_ACKNOWLEDGED'];

  var MEAL_LABELS = {
    BREAKFAST: 'Breakfast', LUNCH: 'Lunch', DINNER: 'Dinner', SNACK: 'Snack'
  };

  /** Prefers whatever the event itself said, exactly as the app does. */
  /** "Break near Kurnool · 18 min · 💧 🍪 🚻" — same wording as the app. */
  function describeBreak(p) {
    var parts = [p.place ? 'Break near ' + p.place : 'Break'];
    if (p.open) parts.push('ongoing');
    else if (p.durationS >= 60) parts.push(Math.floor(p.durationS / 60) + ' min');
    var items = [];
    if (p.water) items.push('💧'); if (p.food) items.push('🍛'); if (p.tea) items.push('☕');
    if (p.snack) items.push('🍪'); if (p.toilet) items.push('🚻'); if (p.rest) items.push('😴');
    if (p.fuel) items.push('⛽'); if (p.charge) items.push('🔌');
    if (items.length) parts.push(items.join(' '));
    return parts.join(' · ');
  }

  /**
   * One entry per break: a break is updated as items join it (same breakId),
   * so keep only its latest version and fold its items into that one line.
   */
  var FOLDED = ['WATER_REPORTED', 'FOOD_REPORTED', 'TEA_COFFEE_REPORTED', 'SNACK_REPORTED',
    'TOILET_REPORTED', 'REST_REPORTED', 'FUEL_STOP', 'CHARGE_STOP'];
  function condenseBreaks(events) {
    var latest = {}, removed = {};
    events.forEach(function (e) {
      var id = e.payload && e.payload.breakId;
      if (e.type === 'BREAK_CHECKPOINT' && id && (e.eventTime || 0) >= (latest[id] || 0)) {
        latest[id] = e.eventTime || 0;
        // The traveller took the break back: its latest version says so.
        removed[id] = !!e.payload.removed;
      }
    });
    // The traveller's corrections to other entries (TIMELINE_EDIT): the
    // latest per target wins; removed targets vanish, re-timed ones show
    // the corrected time (see shownTime).
    var edits = {}, editAt = {};
    events.forEach(function (e) {
      if (e.type !== 'TIMELINE_EDIT' || !e.payload || !e.payload.targetEventId) return;
      var t = e.payload.targetEventId;
      if ((e.eventTime || 0) >= (editAt[t] || 0)) { editAt[t] = e.eventTime || 0; edits[t] = e.payload; }
    });
    var kept = {};
    return events.filter(function (e) {
      var id = e.payload && e.payload.breakId;
      if (e.type === 'BREAK_CHECKPOINT_SKIPPED') return false;
      if (NOT_IN_TIMELINE.indexOf(e.type) >= 0) return false;
      // A stage that is also a travel-mode change reads once, as the change.
      if (e.type === 'LEG_STARTED' && e.payload && e.payload.announcedAs) return false;
      var edit = e.eventId && edits[e.eventId];
      if (edit && edit.removed) return false;
      if (e.type === 'BREAK_CHECKPOINT' && id) {
        if ((e.eventTime || 0) !== latest[id] || kept[id] || removed[id]) return false;
        kept[id] = true; return true;
      }
      if (id && removed[id] && FOLDED.indexOf(e.type) >= 0) return false;
      return !(id && FOLDED.indexOf(e.type) >= 0);
    }).map(function (e) {
      var edit = e.eventId && edits[e.eventId];
      if (edit && edit.atMs) {
        var p = {}; for (var k in (e.payload || {})) p[k] = e.payload[k]; p.atMs = edit.atMs;
        var c = {}; for (var k2 in e) c[k2] = e[k2]; c.payload = p; return c;
      }
      return e;
    });
  }

  /**
   * When an entry happened, as opposed to when it was logged. A break logged
   * after the night's halt still sits at dinner time; a halt confirmed in the
   * morning still began the night before.
   */
  function shownTime(e) {
    var p = e.payload || {};
    if (p.atMs) return p.atMs;
    if (e.type === 'BREAK_CHECKPOINT' && p.startMs) return p.startMs;
    if (e.type === 'HALT_CONFIRMED' && p.sinceMs) return p.sinceMs;
    return e.eventTime || 0;
  }

  function describeEvent(e) {
    var type = e.type || 'EVENT';
    var p0 = e.payload || {};
    if (type === 'BREAK_CHECKPOINT' && p0.breakId && p0.countsAsBreak !== false) return ['✅', describeBreak(p0)];
    if (type === 'TRIP_STARTED' && p0.startedEarlier) {
      var before = p0.estimatedDistanceBeforeTrackingM || 0;
      return ['🚗', before >= 1000 ? 'Journey started · about ' + dist(before, modeNow()) + ' before tracking began (estimated)' : 'Journey started · logged later'];
    }
    var known = EVENT_LABELS[type] || ['•', type.toLowerCase().replace(/_/g, ' ')];
    var payload = e.payload || {};
    if (type === 'FOOD_REPORTED' && MEAL_LABELS[payload.meal]) {
      return [known[0], MEAL_LABELS[payload.meal]];
    }
    if (payload.text) return [known[0], payload.text];
    return known;
  }

  // ---- map ---------------------------------------------------------------
  // MapLibre with the traveller's 3D vehicle lives in map3d.js; this is the
  // one call the rest of the page makes into it.

  function drawJourney(origin, destination, travelled, current) {
    var st = latest.state || {};
    window.KoodeMap.draw({
      origin: origin,
      destination: destination,
      trail: travelled || [],
      trailTimes: playback.times,
      stages: playback.stages,
      current: current,
      mode: st.mode || (latest.meta && latest.meta.transportMode) || 'CAR',
      moving: st.status === 'DRIVING',
      live: freshnessOf(st) === 'live',
      playback: playback.playing
    });
  }

  /**
   * When each stage began and how it was travelled, from the journey's own
   * record: a stage start, or a change of mode (which older builds recorded
   * alone). Before the first change it went by what it changed from.
   */
  function stagesOf(events) {
    var starts = [];
    var firstChange = null;
    events.slice().sort(function (a, b) { return (a.eventTime || 0) - (b.eventTime || 0); }).forEach(function (e) {
      var p = e.payload || {};
      if (e.type === 'LEG_STARTED' && p.mode) starts.push({ fromMs: e.eventTime || 0, mode: p.mode });
      if (e.type === 'TRAVEL_MODE_CHANGED' && p.toMode) {
        if (!firstChange) firstChange = e;
        starts.push({ fromMs: e.eventTime || 0, mode: p.toMode });
      }
    });
    if (firstChange && firstChange.payload.fromMode &&
        !starts.some(function (s) { return s.fromMs < (firstChange.eventTime || 0); })) {
      starts.unshift({ fromMs: 0, mode: firstChange.payload.fromMode });
    }
    return starts;
  }

  /** The same pictures the app shows for each way of travelling (art/). */
  var MODE_ART = { CAR: 'car', BIKE: 'bike', CAB: 'cab', AUTO: 'auto', BUS: 'bus', METRO: 'metro', TRAIN: 'train', SHIP: 'cruise', FERRY: 'ferry', FLIGHT: 'flight', CYCLE: 'cycle', WALK: 'walk' };
  /**
   * A timeline entry's picture — the same table as the app (domain/Pictures.kt):
   * a logged meal is the plate of food, a break that included a meal is the
   * restaurant it was taken at, getting off is the walker. Tea keeps its cup.
   */
  var EVENT_ART = {
    FUEL_STOP: 'fuel', CHARGE_STOP: 'fuel',
    FOOD_REPORTED: 'food', FOOD_ACKNOWLEDGED: 'food',
    TOILET_REPORTED: 'toilet', WATER_REPORTED: 'water', WATER_ACKNOWLEDGED: 'water', REST_REPORTED: 'rest',
    OVERNIGHT_CONFIRMED: 'stay', HALT_CONFIRMED: 'stay', DEBOARDED: 'walk'
  };
  /** One picture for a break, by its main reason. */
  function breakArt(p) {
    if (p.food || p.tea || p.snack) return 'restaurant';
    if (p.fuel || p.charge) return 'fuel';
    if (p.rest) return 'rest';
    if (p.toilet) return 'toilet';
    if (p.water) return 'water';
    return 'rest';
  }
  function eventArt(e) {
    return e.type === 'BREAK_CHECKPOINT' ? breakArt(e.payload || {}) : EVENT_ART[e.type];
  }
  /** Pictures drawn facing left, mirrored so the vehicle faces the flag. */
  var ART_FACES_LEFT = { BUS: true, METRO: true, AUTO: true };

  /** Mode chip over the map, and the vehicle riding the progress track. */
  function renderMode(meta, state, progressPct) {
    var mode = (state && state.mode) || (meta && meta.transportMode) || 'CAR';
    var info = window.KoodeMap.MODES[mode] || window.KoodeMap.MODES.CAR;
    var art = MODE_ART[mode];
    var pill = $('mode-pill');
    if (pill) {
      pill.innerHTML = '';
      if (art) {
        var pi = document.createElement('img');
        pi.src = 'art/' + art + '.webp'; pi.alt = '';
        pill.appendChild(pi);
      } else {
        pill.appendChild(document.createTextNode(info[0] + ' '));
      }
      pill.appendChild(document.createTextNode(info[1]));
    }
    var moving = state && state.status === 'DRIVING' && freshnessOf(state) === 'live';
    var left = 'calc(' + Math.max(0, Math.min(100, progressPct)) + '% - ';
    var v = $('ride-vehicle');
    var img = $('ride-img');
    if (art && img) {
      img.src = 'art/' + art + '.webp';
      img.className = 'ride-img' + (ART_FACES_LEFT[mode] ? ' flip' : '') + (moving ? ' moving' : '');
      img.style.left = left + '22px)';
      show(img); if (v) hide(v);
      return;
    }
    if (img) hide(img);
    if (!v) return;
    show(v);
    v.textContent = info[0];
    // Most road and sea vehicle emoji face left; turn them toward the flag.
    v.className = 'ride-vehicle' +
      (['CAR', 'CAB', 'BUS', 'BIKE', 'SHIP'].indexOf(mode) >= 0 ? ' flip' : '') +
      (mode === 'FLIGHT' ? ' level' : '') +
      (moving ? ' moving' : '');
    v.style.left = left + '14px)';
  }

  // ---- playback ----------------------------------------------------------

  var playback = { path: [], times: [], stages: [], cursor: 0, playing: false, speedIndex: 0, timer: null };

  function stopPlayback() {
    playback.playing = false;
    if (playback.timer) { clearInterval(playback.timer); playback.timer = null; }
    $('play').textContent = '▶';
    text('play-time', '');
  }

  function togglePlayback() {
    if (playback.playing) { stopPlayback(); render(latest.meta, latest.state, latest.events); return; }
    if (playback.path.length < 2) return;
    if (playback.cursor >= playback.path.length - 1) playback.cursor = 0;
    playback.playing = true;
    $('play').textContent = '⏸';
    // 60 ms frames, with speed deciding how many recorded points each consumes —
    // the same model the app's map uses, so both replay at the same rate.
    playback.timer = setInterval(function () {
      var step = PLAYBACK_SPEEDS[playback.speedIndex] * 0.06;
      playback.cursor = Math.min(playback.path.length - 1, playback.cursor + step);
      var i = Math.floor(playback.cursor);
      drawJourney(playback.path[0], latest.destination, playback.path.slice(0, i + 1), playback.path[i]);
      if (playback.times[i] && playback.times[i] < Number.MAX_SAFE_INTEGER) text('play-time', clockWithDay(playback.times[i]));
      if (playback.cursor >= playback.path.length - 1) stopPlayback();
    }, 60);
  }

  function cycleSpeed() {
    playback.speedIndex = (playback.speedIndex + 1) % PLAYBACK_SPEEDS.length;
    $('speed').textContent = PLAYBACK_SPEEDS[playback.speedIndex] + '×';
  }

  // ---- rendering ---------------------------------------------------------

  var latest = { meta: null, state: null, events: [], destination: null };

  function freshnessOf(state) {
    if (!state) return 'unknown';
    var last = state.lastLocationAt || state.updatedAt;
    if (!last) return 'unknown';
    var ageS = (Date.now() - last) / 1000;
    if (ageS <= 60) return 'live';
    if (ageS <= 300) return 'recent';
    if (ageS <= 900) return 'stale';
    return 'quiet';
  }

  // ---------------------------------------------------------------------
  // The safety report
  //
  // A print-friendly page the browser can save as PDF — the web has no PDF
  // library here and needs none. Deliberately the same content and the same
  // wording as the app's PDF (data/export/JourneyDocuments.lastKnownPosition),
  // because a family may hold one from a phone and one from a browser and the
  // two must not disagree.
  // ---------------------------------------------------------------------

  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c];
    });
  }

  function reportRow(label, value) {
    if (value == null || value === '') return '';
    return '<tr><td>' + esc(label) + '</td><td>' + esc(value) + '</td></tr>';
  }

  function openSafetyReport() {
    var meta = latest.meta || {};
    var state = latest.state || {};
    var device = meta.device || {};
    var who = meta.ownerName || 'The traveller';
    var dark = assessDarkness(state, endedByOwner(state, latest.events));

    var lat = state.lat, lng = state.lng;
    var pos = (lat != null && lng != null)
      ? reportRow('Latitude', lat.toFixed(6)) +
        reportRow('Longitude', lng.toFixed(6)) +
        reportRow('Coordinates', lat.toFixed(6) + ', ' + lng.toFixed(6)) +
        reportRow('Accurate to within', state.accuracy != null ? Math.round(state.accuracy) + ' m' : null) +
        reportRow('Recorded at', state.lastLocationAt ? new Date(state.lastLocationAt).toLocaleString() : null)
      : '<tr><td>Position</td><td>No location was recorded</td></tr>';

    var circumstances =
      reportRow('Assessment', dark.dark ? darkHeadline(dark, who) : who + ' was reporting normally') +
      reportRow('Battery at last report', state.battery != null ? state.battery + '%' : null) +
      reportRow('Last contact', (state.lastLocationAt || state.updatedAt)
        ? new Date(state.lastLocationAt || state.updatedAt).toLocaleString() : null) +
      reportRow('SIM changed at', state.simChangedAt ? new Date(state.simChangedAt).toLocaleString() : null);

    var dev =
      reportRow('Phone', [device.manufacturer, device.model].filter(Boolean).join(' ')) +
      reportRow('Model number', device.model) +
      reportRow('Android', device.androidRelease ? device.androidRelease + ' (API ' + device.androidSdk + ')' : null) +
      reportRow('Security patch', device.securityPatch) +
      reportRow('Public IP at last contact', device.publicIp) +
      reportRow('Local IP', device.localIp) +
      reportRow('Android ID', device.androidId) +
      reportRow('Koode install ID', device.installId) +
      reportRow('IMEI', device.imeiNote || 'Not available on this device') +
      reportRow('Hardware MAC', device.macNote || 'Not available on this device');

    var lines = (latest.events || [])
      .slice().sort(function (a, b) { return (b.eventTime || 0) - (a.eventTime || 0); })
      .slice(0, 40).reverse()
      .map(function (e) {
        var d = describeEvent(e);
        return '<tr><td>' + esc(new Date(e.eventTime).toLocaleString()) + '</td><td>' +
          esc(d.emoji + ' ' + d.label) + '</td></tr>';
      }).join('');

    var html =
      '<!doctype html><html><head><meta charset="utf-8"><title>Koode safety report</title>' +
      '<style>body{font:14px system-ui,sans-serif;margin:32px;color:#111}' +
      'h1{font-size:20px;margin:0 0 4px}h2{font-size:15px;margin:24px 0 6px;border-bottom:1px solid #ccc;padding-bottom:3px}' +
      '.sub{color:#555;margin:0 0 16px}table{border-collapse:collapse;width:100%}' +
      'td{padding:4px 8px;vertical-align:top}td:first-child{color:#555;width:40%}' +
      '.note{color:#555;font-size:12px;margin-top:6px}@media print{button{display:none}}</style></head><body>' +
      '<h1>Last known position</h1>' +
      '<p class="sub">' + esc(who) + ' — ' + esc(meta.origin || 'Start') + ' to ' + esc(meta.destination || 'Destination') + '</p>' +
      '<p class="sub">Prepared ' + new Date().toLocaleString() + '. Times are the phone\'s local time.</p>' +
      '<h2>Last known position</h2><table>' + pos + '</table>' +
      '<h2>How reporting stopped</h2><table>' + circumstances + '</table>' +
      '<p class="note">' + esc(darkDetail(dark)) + '</p>' +
      '<h2>The device</h2><table>' + dev + '</table>' +
      '<p class="note">Identifiers Android permits an ordinary app to read. IMEI and the hardware ' +
      'MAC are withheld by the operating system, not by Koode; a subpoena to the carrier or ' +
      'manufacturer, using the public IP and the times above, is how those are recovered.</p>' +
      '<h2>The hours before</h2><table>' + lines + '</table>' +
      '<p style="margin-top:24px"><button onclick="window.print()">Save as PDF / print</button></p>' +
      '</body></html>';

    var w = window.open('', '_blank');
    if (!w) { alert('Please allow pop-ups to open the safety report.'); return; }
    w.document.write(html);
    w.document.close();
  }

  // ---------------------------------------------------------------------
  // Going dark
  //
  // A deliberate mirror of domain/Darkness.kt. The thresholds and the wording
  // are duplicated rather than shared because this page has no build step and
  // no dependency on the app — but they must not drift, so any change to one
  // belongs in the other in the same commit.
  //
  // The constraint is the same here as there: a powered-off phone cannot
  // report anything. This runs in the family's browser, which is exactly why
  // it still works when the traveller's phone does not.
  // ---------------------------------------------------------------------

  var FLAT_BATTERY_PCT = 15;
  var DARK_GRACE_MS = 12 * 60 * 1000;
  var DARK_UNEXPLAINED_MS = 45 * 60 * 1000;

  function assessDarkness(state, closed) {
    var quiet = { dark: false, reason: 'NONE', concerning: false, since: null, elapsed: 0, battery: null };
    if (!state || closed) return quiet;

    var battery = typeof state.battery === 'number' ? state.battery : null;
    var flat = battery !== null && battery <= FLAT_BATTERY_PCT;
    var now = Date.now();

    if (state.simChangedAt) {
      return {
        dark: true, reason: 'SIM_SWAPPED', concerning: true,
        since: state.simChangedAt, elapsed: now - state.simChangedAt, battery: battery
      };
    }
    // An explicit goodbye outranks the clock: we have been told the device is
    // off, so there is nothing to wait out.
    if (state.wentDarkAt) {
      return {
        dark: true,
        reason: flat ? 'BATTERY_DIED' : 'POWERED_OFF',
        concerning: !flat,
        since: state.wentDarkAt, elapsed: now - state.wentDarkAt, battery: battery
      };
    }

    var last = state.lastLocationAt || state.updatedAt;
    if (!last) return quiet;
    var elapsed = now - last;
    if (elapsed < DARK_GRACE_MS) return quiet;

    var threshold = DARK_UNEXPLAINED_MS;
    return {
      dark: true,
      reason: flat ? 'BATTERY_DIED' : 'SIGNAL_LOST',
      concerning: !flat && elapsed >= threshold,
      since: last, elapsed: elapsed, battery: battery
    };
  }

  function darkHeadline(a, who) {
    if (!a.dark) return null;
    if (a.reason === 'SIM_SWAPPED') return who + "'s phone had its SIM changed or removed";
    if (a.reason === 'BATTERY_DIED') return who + "'s phone ran out of battery";
    if (a.reason === 'POWERED_OFF') {
      return a.concerning
        ? who + "'s phone was switched off with battery remaining"
        : who + "'s phone was switched off";
    }
    return a.concerning ? 'No word from ' + who : who + "'s phone is out of signal";
  }

  function darkDetail(a) {
    if (a.reason === 'BATTERY_DIED') {
      return 'The battery was at ' + a.battery + '% at the last update. ' +
        'The last known position is saved.';
    }
    if (a.reason === 'POWERED_OFF') {
      return 'The phone reported switching off' +
        (a.battery !== null ? ' with ' + a.battery + '% battery left' : '') +
        '. The last known position is saved.';
    }
    if (a.reason === 'SIM_SWAPPED') {
      return 'The SIM was changed or removed. Koode never needed it to report — it keeps ' +
        'working over any network it can reach — so this is a record of tampering, not a ' +
        'loss of tracking. The last known position is saved.';
    }
    return 'The phone has not been able to reach us. It may simply be out of ' +
      'coverage. The last known position is saved.';
  }

  function endedByOwner(state, events) {
    if (state && state.endedByOwner === true) return true;
    if (state && state.status === 'COMPLETED') return true;
    return (events || []).some(function (e) { return e.type === 'TRIP_COMPLETED'; });
  }

  function render(meta, state, events) {
    latest.meta = meta; latest.state = state; latest.events = events || [];

    var owner = meta && meta.ownerName;
    text('who', owner ? owner + "'s journey" : 'Journey');
    text('route', ((meta && meta.origin) || '—') + ' → ' + ((meta && meta.destination) || '—'));

    var ended = endedByOwner(state, events);
    var freshness = freshnessOf(state);
    var reportReady = (events || []).some(function (e) { return e.type === 'JOURNEY_REPORT_AVAILABLE'; });
    (reportReady ? show : hide)($('verified-card'));
    var sos = state && state.sosActive === true;

    // ---- headline. Never says "ended" unless the traveller ended it. ----
    var card = $('health');
    var dot = card.querySelector('.dot');
    var headlineEl = card.querySelector('.headline');
    card.className = 'card hero';
    headlineEl.className = 'headline ok';
    dot.className = 'dot';

    var who = owner || 'They';
    var dark = assessDarkness(state, ended || !!(state && state.wrappingUp));

    var headline;
    if (sos) {
      headline = 'SOS active';
      card.className = 'card hero danger';
      headlineEl.className = 'headline danger';
    } else if (ended) {
      headline = 'Journey ended';
    } else if (state && state.wrappingUp) {
      // Closed, and under the traveller's review: not live, not "ended" yet.
      headline = 'Wrapping up the journey';
    } else if (!state) {
      headline = 'Getting the first update…';
    } else if (dark.dark) {
      // Says which kind of silence this is, because "switched off with 74%
      // battery" and "ran out of battery" are different things to be told.
      headline = darkHeadline(dark, who);
      card.className = 'card hero ' + (dark.concerning ? 'danger' : 'warn');
      headlineEl.className = 'headline ' + (dark.concerning ? 'danger' : 'warn');
    } else if (freshness === 'quiet') {
      headline = "Haven't heard for a while";
      card.className = 'card hero warn';
      headlineEl.className = 'headline warn';
    } else if (state.status === 'ARRIVED') {
      // Detected, not declared: the journey is still theirs to close.
      headline = 'Reached ' + ((meta && meta.destination) || 'the destination');
      dot.className = 'dot live';
    } else {
      headline = 'Journey progressing normally';
      dot.className = 'dot live';
    }
    text('headline-text', headline);

    var reasons = $('reasons');
    reasons.innerHTML = '';
    if (!ended && state) {
      var notes = [];
      if (typeof state.battery === 'number' && state.battery <= 25) {
        notes.push("Traveller's phone battery is at " + state.battery + '%');
      }
      if (dark.dark) {
        notes.push(darkDetail(dark));
        if (dark.since) notes.push('Last heard from ' + ago(dark.since) + '.');
        if (dark.concerning) {
          notes.push(
            'Koode is still watching and will show anything new the moment it ' +
            'arrives. This journey stays open until they close it themselves.'
          );
        }
      } else if (freshness === 'quiet') {
        notes.push('This is about the signal, not about them — the journey is still open.');
      }
      notes.forEach(function (n) {
        var li = document.createElement('li');
        li.textContent = n;
        reasons.appendChild(li);
      });
    }

    var lastAt = state && (state.lastLocationAt || state.updatedAt);
    text('last-update',
      ended ? 'The traveller ended this journey.'
        : (state && state.wrappingUp) ? 'Tracking has stopped. The journey report follows once the traveller has reviewed it.'
          : lastAt ? 'Last updated ' + ago(lastAt)
            : 'Waiting for the first update — this is about the signal, not about them.');

    // ---- map ----
    var origin = meta && meta.originLat != null ? [meta.originLat, meta.originLng] : null;
    var destination = meta && meta.destLat != null ? [meta.destLat, meta.destLng] : null;
    var current = state && state.lat != null ? [state.lat, state.lng] : null;
    latest.destination = destination;

    var path = (events || [])
      .filter(function (e) { return e.lat != null && e.lng != null; })
      .sort(function (a, b) { return (a.eventTime || 0) - (b.eventTime || 0); })
      .map(function (e) { return [e.lat, e.lng]; });
    if (current) path.push(current);

    playback.path = path;
    playback.times = (events || [])
      .filter(function (e) { return e.lat != null && e.lng != null; })
      .map(function (e) { return e.eventTime; })
      .sort(function (a, b) { return (a || 0) - (b || 0); });
    if (current) playback.times.push(Number.MAX_SAFE_INTEGER);
    playback.stages = stagesOf(events || []);

    if (!playback.playing) drawJourney(origin, destination, path, current);
    $('play').disabled = path.length < 2;

    // ---- arrival + progress ----
    if (ended) text('eta', 'Journey complete');
    else if (state && state.etaMode === 'OVERNIGHT_PENDING') text('eta', 'Halting — a new estimate follows when they set off');
    else if (state && state.etaLikely) {
      text('eta', clockWithDay(state.etaLow || state.etaLikely) + ' – ' + clock(state.etaHigh || state.etaLikely));
    } else text('eta', 'Calculating…');

    var progress = Math.round(((state && state.progress) || 0) * 100);
    $('progress-bar').style.width = Math.max(0, Math.min(100, progress)) + '%';
    renderMode(meta, state, progress);
    text('covered', dist(state && state.distanceCoveredM, modeNow()) + ' completed');
    text('remaining', dist(state && state.distanceRemainingM, modeNow()) + ' to go');

    // ---- wellbeing ----
    text('food', state && state.foodAt ? 'Last logged ' + ago(state.foodAt) : 'Not logged yet');
    text('water', state && state.waterAt ? 'Last logged ' + ago(state.waterAt) : 'Not logged yet');
    text('toilet', state && state.toiletAt ? 'Last stop ' + ago(state.toiletAt) : 'Not logged yet');
    var stopped = state && ['STOPPED', 'LONG_STOP', 'POSSIBLE_STOP', 'OVERNIGHT'].indexOf(state.status) >= 0;
    text('rest', stopped ? 'Stopped now'
      : (state && state.lastBreakEndAt) ? 'Last break ' + ago(state.lastBreakEndAt) : 'No break yet');
    text('battery', state && typeof state.battery === 'number' ? state.battery + '%' : '—');

    renderStory(state && state.story, events || []);

    // ---- timeline ----
    var list = $('timeline');
    list.innerHTML = '';
    condenseBreaks(events || [])
      .slice()
      .sort(function (a, b) { return shownTime(b) - shownTime(a); })
      .slice(0, 40)
      .forEach(function (e) {
        var parts = describeEvent(e);
        var li = document.createElement('li');
        var ev = eventArt(e);
        var lead = ev ? '<img class="ev" src="art/' + ev + '.webp" alt="">' : '<span>' + parts[0] + '</span>';
        li.innerHTML = lead + '<span>' + escapeHtml(parts[1]) +
          '</span><span class="when">' + clock(shownTime(e) || Date.now()) + '</span>';
        list.appendChild(li);
      });
    var tollNote = $('toll-note');
    var offline = (events || []).some(function (e) { return e.type === 'DEVICE_BACK_ONLINE'; });
    var tolls = (events || []).some(function (e) { return e.type === 'TOLL_CROSSED'; });
    if (tolls && offline) { tollNote.textContent = TOLL_NOTE; show(tollNote); } else hide(tollNote);
  }

  /**
   * The story so far, as the traveller's phone tells it: the same words the
   * journey report carries, retold every few minutes and pushed with the
   * live state. Nothing is composed here; the page only lays it out.
   */
  var PHASE_COLOR = { DRIVING: '#2dd4bf', STOPPED: '#f6c66b', HALT: '#38bdf8', OFFLINE: '#6f8a99' };
  var PHASE_LABEL = { DRIVING: 'Moving', STOPPED: 'Stopped', HALT: 'Halt', OFFLINE: 'Out of contact' };
  var GLYPH = { toll: '🛣', tea: '☕', snack: '🍪', offline: '📵', phone: '📱', sos: '🆘', note: '💬', pin: '📍', flag: '🏁', clock: '🕒', stopped: '🅿', people: '👥', road: '🛣' };
  var storyExpanded = false;
  function renderStory(story, events) {
    var card = $('story-card');
    if (!story || !story.paragraphs || !story.paragraphs.length) { hide(card); return; }
    show(card);
    var paras = story.paragraphs;
    var box = $('story-text');
    box.innerHTML = '';
    (storyExpanded ? paras : paras.slice(0, 1)).forEach(function (p, i) {
      var el = document.createElement('p'); el.className = i === 0 ? 'lead' : ''; el.textContent = p; box.appendChild(el);
    });
    if (paras.length > 1) {
      var more = document.createElement('button'); more.type = 'button'; more.className = 'more';
      more.textContent = storyExpanded ? 'Show less' : 'Read the whole story';
      more.onclick = function () { storyExpanded = !storyExpanded; renderStory(story, events); };
      box.appendChild(more);
    }
    var chips = $('story-highlights');
    chips.innerHTML = '';
    (story.highlights || []).forEach(function (h) {
      var c = document.createElement('span'); c.className = 'chip';
      var lead = h.picture ? '<img src="art/' + encodeURIComponent(h.picture) + '.webp" alt="">' : '<span class="g">' + (GLYPH[h.glyph] || '•') + '</span>';
      c.innerHTML = lead + '<span><b></b><small></small></span>';
      c.querySelector('b').textContent = h.title || '';
      c.querySelector('small').textContent = h.detail || '';
      chips.appendChild(c);
    });
    var segs = story.segments || [];
    var wrap = $('story-strip-wrap');
    if (segs.length) {
      show(wrap);
      var end = segs[segs.length - 1][1];
      var from = Math.max(segs[0][0], end - 24 * 36e5);
      var span = Math.max(1, end - from);
      var svg = $('story-strip');
      var W = 600, barY = 2, barH = 20;
      var parts = ['<rect x="0" y="' + barY + '" width="' + W + '" height="' + barH + '" rx="10" fill="#0e2231"/>'];
      segs.forEach(function (sg) {
        var a = Math.max(sg[0], from), b = Math.min(sg[1], end);
        if (b <= a) return;
        var x = (a - from) / span * W, w = Math.max(0.5, (b - a) / span * W);
        parts.push('<rect x="' + x.toFixed(1) + '" y="' + barY + '" width="' + w.toFixed(1) + '" height="' + barH + '" fill="' + (PHASE_COLOR[sg[2]] || '#6f8a99') + '"/>');
      });
      // hour ticks
      var t = new Date(from); t.setMinutes(0, 0, 0);
      var hours = span / 36e5, step = hours <= 8 ? 1 : hours <= 14 ? 2 : 3;
      for (; t.getTime() <= end; t.setHours(t.getHours() + 1)) {
        if (t.getTime() >= from && t.getHours() % step === 0) {
          var tx = ((t.getTime() - from) / span * W).toFixed(1);
          parts.push('<line x1="' + tx + '" y1="' + (barY + barH + 2) + '" x2="' + tx + '" y2="' + (barY + barH + 7) + '" stroke="#6f8a99" stroke-width="1.5"/>');
        }
      }
      parts.push('<line x1="' + W + '" y1="0" x2="' + W + '" y2="' + (barY + barH + 4) + '" stroke="#f59e0b" stroke-width="3" stroke-linecap="round"/>');
      svg.innerHTML = parts.join('');
      text('strip-label', hours >= 23 ? 'The last 24 hours' : 'So far today');
      text('strip-from', clock(from)); text('strip-to', clock(end));
      var present = {}; segs.forEach(function (sg) { present[sg[2]] = true; });
      $('strip-legend').innerHTML = ['DRIVING', 'STOPPED', 'HALT', 'OFFLINE'].filter(function (k) { return present[k] || k === 'DRIVING'; })
        .map(function (k) { return '<span><i style="background:' + PHASE_COLOR[k] + '"></i>' + PHASE_LABEL[k] + '</span>'; }).join('');
    } else hide(wrap);
    var note = $('story-note');
    if (story.tollsMayBeMissing) { note.textContent = TOLL_NOTE; show(note); } else hide(note);
  }
  var TOLL_NOTE = "Toll plazas are noticed from the phone's position. For a stretch the phone was out of contact, the plazas on the road between where it fell silent and where it came back are counted and marked as worked out.";

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // ---- polling -----------------------------------------------------------

  /*
   * The interval scales with what is actually happening. A page left open on a
   * kitchen tablet for a twelve-hour train journey should not hit the service
   * every five seconds — and once the traveller has ended the journey there is
   * nothing left to learn.
   */
  function pollIntervalMs(state, events) {
    if (endedByOwner(state, events)) return 300000;
    if (!state) return 20000;
    var idle = ['OVERNIGHT', 'PAUSED', 'STOPPED', 'LONG_STOP', 'ARRIVED'].indexOf(state.status) >= 0;
    return idle ? 60000 : 20000;
  }

  // The traveller's approved report: a short-lived signed link from tp-report,
  // for exactly the people who can read the journey.
  async function openVerifiedReport(reader) {
    hide($('verified-error'));
    var popup = window.open('', '_blank');
    try {
      var base = CFG.SUPABASE_URL.replace(/\/$/, '');
      var res = await fetch(base + '/functions/v1/tp-report', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', apikey: CFG.SUPABASE_ANON_KEY },
        body: JSON.stringify(reader.report())
      });
      var body = res.ok ? await res.json() : null;
      if (!body || !body.downloadUrl) throw new Error('unavailable');
      if (popup) popup.location = base + body.downloadUrl; else location.href = base + body.downloadUrl;
    } catch (e) {
      if (popup) popup.close();
      $('verified-error').textContent = "Couldn't open the report just now. Try again in a moment.";
      show($('verified-error'));
    }
  }

  var watching = null;

  async function startWatching(cred) {
    if (watching) return;
    var reader = readerFor(cred);
    watching = reader;
    hide($('signin'));
    show($('journey'));
    window.scrollTo(0, 0);
    $('verified-report').onclick = function () { openVerifiedReport(reader); };

    var tick = async function () {
      var meta = await reader.meta();
      var state = await reader.state();
      var events = (await reader.events()) || [];
      // A failed read leaves the last known picture on screen rather than
      // wiping it: silence is not news.
      if (meta || state) render(meta || latest.meta, state || latest.state, events.length ? events : latest.events);
      setTimeout(tick, pollIntervalMs(state, events));
    };
    tick();
  }

  // ---- sign-in -------------------------------------------------------------
  //
  // Mirrors the app's Follow screen: the journey number is required; with a
  // complete passcode you're in straight away, without one your name goes to
  // the traveller and this page waits for their yes.

  var waiting = { tripId: null, timer: null };

  function cleanName(raw) {
    return (raw || '').replace(/\s+/g, ' ').trim().slice(0, 40);
  }

  function updateSubmitState() {
    var code = $('code').value, pass = $('passcode').value, name = cleanName($('name').value);
    var withPass = pass.length === PASSCODE_LENGTH;
    var ok = code.length === CODE_LENGTH && (withPass || (pass.length === 0 && name.length > 0));
    $('watch').disabled = !ok;
    $('watch').textContent = withPass || pass.length > 0 ? 'Watch the journey' : 'Ask to follow';
    text('passcode-hint',
      pass.length === 0 ? "Leave this empty and we'll ask the traveller to let you in by name."
        : withPass ? "Ready — you'll go straight in."
          : PASSCODE_LENGTH + ' digits, or leave it empty.');
    text('code-hint', code.length === CODE_LENGTH ? 'Looks right.' : CODE_LENGTH + ' digits — numbers only, the TP- is already there.');
  }

  function showSignInError(message) {
    var el = $('signin-error');
    el.textContent = message;
    show(el);
  }

  function setBusy(busy, label) {
    $('watch').disabled = busy;
    if (label) $('watch').textContent = label;
    if (!busy) updateSubmitState();
  }

  function stopWaiting() {
    if (waiting.timer) { clearTimeout(waiting.timer); waiting.timer = null; }
    waiting.tripId = null;
    hide($('waiting'));
    show($('watch'));
  }

  /** The traveller said yes: remember it for this journey and open it. */
  function approved(code) {
    store('koode.approved.' + code, '1');
    store('koode.pending.' + code, null);
    history.replaceState(null, '', '#j=' + code);
    stopWaiting();
    startWatching({ kind: 'token', tripId: PREFIX + code, token: viewerToken() });
  }

  function denied(code) {
    store('koode.pending.' + code, null);
    stopWaiting();
    showSignInError("The traveller didn't approve this request. Ask them for the passcode, or try again.");
  }

  /** Waits for the traveller's answer, asking every few seconds. */
  function waitForApproval(code, name) {
    var tripId = PREFIX + code;
    waiting.tripId = tripId;
    store('koode.pending.' + code, name);
    text('waiting-name', name || 'Someone');
    hide($('watch'));
    show($('waiting'));
    var poll = async function () {
      if (waiting.tripId !== tripId) return;
      var status = await joinStatus(tripId);
      if (waiting.tripId !== tripId) return;
      if (status === 'APPROVED') { approved(code); return; }
      if (status === 'DENIED') { denied(code); return; }
      if (status === 'NOT_FOUND') {
        store('koode.pending.' + code, null);
        stopWaiting();
        showSignInError('That journey is no longer live.');
        return;
      }
      waiting.timer = setTimeout(poll, 4000);
    };
    waiting.timer = setTimeout(poll, 4000);
  }

  async function askToFollow(code, name) {
    var status = await requestJoin(PREFIX + code, name);
    if (status === 'APPROVED') { approved(code); return; }
    if (status === 'DENIED') { denied(code); return; }
    if (status === 'PENDING') { setBusy(false); waitForApproval(code, name); return; }
    var reachable = status === 'NOT_FOUND' || await serverReachable();
    showSignInError(reachable
      ? "There's no live journey with that number. Check it with the traveller."
      : "Couldn't reach Koode just now. Check your internet connection and try again.");
    setBusy(false);
  }

  async function watchWithPasscode(code, passcode) {
    var key = await accessKeyFor(PREFIX + code, passcode);
    var meta = await readerFor({ kind: 'key', key: key }).meta();
    if (!meta) {
      // Tell the two failure modes apart before blaming the viewer.
      var reachable = await serverReachable();
      showSignInError(reachable
        ? "That journey number and passcode don't match a live journey. Check both with the traveller."
        : "Couldn't reach Koode just now. Check your internet connection and try again.");
      setBusy(false);
      return;
    }
    // Keep the credentials in the URL fragment (never sent to a server), so
    // a bookmark or a refresh resumes without retyping anything.
    history.replaceState(null, '', '#' + code + '-' + passcode);
    startWatching({ kind: 'key', key: key });
  }

  /**
   * A follow link, as the app writes it: `#<code>-<passcode>` or
   * `#j=<code>&p=<passcode>` (the passcode may be absent). The fragment is
   * never sent to a server. `?j=…&p=…` is accepted too, for links typed by hand.
   */
  function parseLink() {
    var frag = (location.hash || '').replace(/^#/, '');
    var q = (location.search || '').replace(/^\?/, '');
    var out = { code: '', passcode: '' };
    function fromParams(str) {
      var p = {};
      str.split('&').forEach(function (kv) {
        var i = kv.indexOf('='); if (i > 0) p[decodeURIComponent(kv.slice(0, i))] = decodeURIComponent(kv.slice(i + 1));
      });
      if (p.j) { out.code = digitsOnly(p.j, CODE_LENGTH); out.passcode = digitsOnly(p.p || '', PASSCODE_LENGTH); return true; }
      return false;
    }
    if (frag.indexOf('=') >= 0 && fromParams(frag)) return out;
    if (frag.indexOf('-') > 0) {
      var parts = frag.split('-');
      out.code = digitsOnly(parts[0], CODE_LENGTH); out.passcode = digitsOnly(parts[1], PASSCODE_LENGTH);
      return out;
    }
    if (frag) { out.code = digitsOnly(frag, CODE_LENGTH); return out; }
    if (q) fromParams(q);
    return out;
  }

  /** Picks up where this browser left off: a link, an approval, or a request still pending. */
  async function resume() {
    var link = parseLink();
    if (link.code.length !== CODE_LENGTH) return;
    $('code').value = link.code;
    $('passcode').value = link.passcode;
    updateSubmitState();
    if (link.passcode.length === PASSCODE_LENGTH) {
      setBusy(true, 'Connecting…');
      await watchWithPasscode(link.code, link.passcode);
      return;
    }
    var tripId = PREFIX + link.code;
    if (load('koode.approved.' + link.code)) {
      var meta = await readerFor({ kind: 'token', tripId: tripId, token: viewerToken() }).meta();
      if (meta) { startWatching({ kind: 'token', tripId: tripId, token: viewerToken() }); return; }
      store('koode.approved.' + link.code, null);
    }
    var pendingName = load('koode.pending.' + link.code);
    if (pendingName) {
      $('name').value = pendingName;
      var status = await joinStatus(tripId);
      if (status === 'APPROVED') { approved(link.code); return; }
      if (status === 'PENDING') { waitForApproval(link.code, pendingName); return; }
      store('koode.pending.' + link.code, null);
    }
    $('name').focus();
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('code').addEventListener('input', function (e) {
      e.target.value = digitsOnly(e.target.value, CODE_LENGTH);
      updateSubmitState();
    });
    $('passcode').addEventListener('input', function (e) {
      e.target.value = digitsOnly(e.target.value, PASSCODE_LENGTH);
      updateSubmitState();
    });
    $('name').addEventListener('input', updateSubmitState);
    window.KoodeMap.init('map');
    $('follow').addEventListener('click', function () { window.KoodeMap.toggleFollow(); });
    $('play').addEventListener('click', togglePlayback);
    $('speed').addEventListener('click', cycleSpeed);
    $('report').addEventListener('click', openSafetyReport);
    $('cancel-wait').addEventListener('click', function () {
      var code = digitsOnly($('code').value, CODE_LENGTH);
      store('koode.pending.' + code, null);
      stopWaiting();
      updateSubmitState();
    });

    $('watch').addEventListener('click', async function () {
      hide($('signin-error'));
      var code = digitsOnly($('code').value, CODE_LENGTH);
      var passcode = digitsOnly($('passcode').value, PASSCODE_LENGTH);
      var name = cleanName($('name').value);
      setBusy(true, passcode ? 'Connecting…' : 'Asking…');
      if (passcode.length === PASSCODE_LENGTH) await watchWithPasscode(code, passcode);
      else await askToFollow(code, name);
    });

    updateSubmitState();
    resume();
  });
})();
