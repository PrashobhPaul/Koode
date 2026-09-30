/*
 * Koode — browser viewer.
 *
 * The point of this page: a parent who will never install an app can still
 * open a link and know their child is safe. So it is a single static page with
 * no build step, no framework and no account — it derives the same capability
 * the Android app derives, and calls the same read-only RPCs.
 *
 * Security model, unchanged from the app:
 *   accessKey = SHA-256("<journeyId>:<passcode>")
 * computed here with WebCrypto. The passcode itself is never transmitted, and
 * the server only ever sees a hash that grants read access while the journey is
 * live. Nothing on this page can write anything.
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

  var getMeta = function (key) { return rpc('tp_get_meta', { p_access_key: key }); };
  var getState = function (key) { return rpc('tp_get_state', { p_access_key: key }); };
  var getEvents = function (key) { return rpc('tp_get_events', { p_access_key: key, p_since: 0 }); };
  var serverReachable = function () { return rpc('tp_now', {}); };

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

  function km(metres) {
    var v = (metres || 0) / 1000;
    return v >= 100 ? Math.round(v) + ' km' : v.toFixed(1) + ' km';
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
  var NOT_IN_TIMELINE = ['WELLBEING_NUDGE', 'JOURNEY_UPDATE', 'HALT_SUGGESTED',
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
    var latest = {};
    events.forEach(function (e) {
      var id = e.payload && e.payload.breakId;
      if (e.type === 'BREAK_CHECKPOINT' && id) latest[id] = Math.max(latest[id] || 0, e.eventTime || 0);
    });
    var kept = {};
    return events.filter(function (e) {
      var id = e.payload && e.payload.breakId;
      if (e.type === 'BREAK_CHECKPOINT_SKIPPED') return false;
      if (NOT_IN_TIMELINE.indexOf(e.type) >= 0) return false;
      // A stage that is also a travel-mode change reads once, as the change.
      if (e.type === 'LEG_STARTED' && e.payload && e.payload.announcedAs) return false;
      if (e.type === 'BREAK_CHECKPOINT' && id) {
        if ((e.eventTime || 0) !== latest[id] || kept[id]) return false;
        kept[id] = true; return true;
      }
      return !(id && FOLDED.indexOf(e.type) >= 0);
    });
  }

  function describeEvent(e) {
    var type = e.type || 'EVENT';
    var p0 = e.payload || {};
    if (type === 'BREAK_CHECKPOINT' && p0.breakId && p0.countsAsBreak !== false) return ['✅', describeBreak(p0)];
    if (type === 'TRIP_STARTED' && p0.startedEarlier) {
      var km = (p0.estimatedDistanceBeforeTrackingM || 0) / 1000;
      return ['🚗', km >= 1 ? 'Journey started · about ' + Math.round(km) + ' km before tracking began (estimated)' : 'Journey started · logged later'];
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
      current: current,
      mode: st.mode || (latest.meta && latest.meta.transportMode) || 'CAR',
      moving: st.status === 'DRIVING',
      live: freshnessOf(st) === 'live',
      playback: playback.playing
    });
  }

  /** Mode chip over the map, and the vehicle riding the progress track. */
  function renderMode(meta, state, progressPct) {
    var mode = (state && state.mode) || (meta && meta.transportMode) || 'CAR';
    var info = window.KoodeMap.MODES[mode] || window.KoodeMap.MODES.CAR;
    text('mode-pill', info[0] + ' ' + info[1]);
    var v = $('ride-vehicle');
    if (!v) return;
    v.textContent = info[0];
    // Most road and sea vehicle emoji face left; turn them toward the flag.
    v.className = 'ride-vehicle' +
      (['CAR', 'CAB', 'BUS', 'BIKE', 'SHIP'].indexOf(mode) >= 0 ? ' flip' : '') +
      (mode === 'FLIGHT' ? ' level' : '') +
      (state && state.status === 'DRIVING' && freshnessOf(state) === 'live' ? ' moving' : '');
    v.style.left = 'calc(' + Math.max(0, Math.min(100, progressPct)) + '% - 14px)';
  }

  // ---- playback ----------------------------------------------------------

  var playback = { path: [], times: [], cursor: 0, playing: false, speedIndex: 0, timer: null };

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
      if (playback.times[i]) text('play-time', clockWithDay(playback.times[i]));
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
        : lastAt ? 'Last updated ' + ago(lastAt)
          : 'Waiting for the first update.');

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
      .map(function (e) { return e.eventTime; });

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
    text('covered', km(state && state.distanceCoveredM) + ' completed');
    text('remaining', km(state && state.distanceRemainingM) + ' to go');

    // ---- wellbeing ----
    text('food', state && state.foodAt ? 'Last logged ' + ago(state.foodAt) : 'Not logged yet');
    text('water', state && state.waterAt ? 'Last logged ' + ago(state.waterAt) : 'Not logged yet');
    var stopped = state && ['STOPPED', 'LONG_STOP', 'POSSIBLE_STOP', 'OVERNIGHT'].indexOf(state.status) >= 0;
    text('rest', stopped ? 'Stopped now'
      : (state && state.lastBreakEndAt) ? 'Last break ' + ago(state.lastBreakEndAt) : 'No break yet');
    text('battery', state && typeof state.battery === 'number' ? state.battery + '%' : '—');

    // ---- timeline ----
    var list = $('timeline');
    list.innerHTML = '';
    condenseBreaks(events || [])
      .slice()
      .sort(function (a, b) { return (b.eventTime || 0) - (a.eventTime || 0); })
      .slice(0, 40)
      .forEach(function (e) {
        var parts = describeEvent(e);
        var li = document.createElement('li');
        li.innerHTML = '<span>' + parts[0] + '</span><span>' + escapeHtml(parts[1]) +
          '</span><span class="when">' + clock(e.eventTime || Date.now()) + '</span>';
        list.appendChild(li);
      });
  }

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
  async function openVerifiedReport(accessKey) {
    hide($('verified-error'));
    var popup = window.open('', '_blank');
    try {
      var base = CFG.SUPABASE_URL.replace(/\/$/, '');
      var res = await fetch(base + '/functions/v1/tp-report', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', apikey: CFG.SUPABASE_ANON_KEY },
        body: JSON.stringify({ action: 'download', accessKey: accessKey })
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

  async function startWatching(accessKey) {
    hide($('signin'));
    show($('journey'));
    $('verified-report').onclick = function () { openVerifiedReport(accessKey); };

    var tick = async function () {
      var meta = await getMeta(accessKey);
      var state = await getState(accessKey);
      var events = (await getEvents(accessKey)) || [];
      // A failed read leaves the last known picture on screen rather than
      // wiping it: silence is not news.
      if (meta || state) render(meta || latest.meta, state || latest.state, events.length ? events : latest.events);
      setTimeout(tick, pollIntervalMs(state, events));
    };
    tick();
  }

  // ---- sign-in wiring ----------------------------------------------------

  function updateSubmitState() {
    var ok = $('code').value.length === CODE_LENGTH && $('passcode').value.length === PASSCODE_LENGTH;
    $('watch').disabled = !ok;
  }

  function showSignInError(message) {
    var el = $('signin-error');
    el.textContent = message;
    show(el);
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
    window.KoodeMap.init('map');
    $('follow').addEventListener('click', function () { window.KoodeMap.toggleFollow(); });
    $('play').addEventListener('click', togglePlayback);
    $('speed').addEventListener('click', cycleSpeed);
    $('report').addEventListener('click', openSafetyReport);

    $('watch').addEventListener('click', async function () {
      hide($('signin-error'));
      $('watch').disabled = true;
      $('watch').textContent = 'Connecting…';

      var journeyId = PREFIX + digitsOnly($('code').value, CODE_LENGTH);
      var passcode = digitsOnly($('passcode').value, PASSCODE_LENGTH);
      var key = await accessKeyFor(journeyId, passcode);
      var meta = await getMeta(key);

      if (!meta) {
        // Tell the two failure modes apart before blaming the viewer.
        var reachable = await serverReachable();
        showSignInError(reachable
          ? "That journey number and passcode don't match a live journey. Check both with the traveller."
          : "Couldn't reach Koode just now. Check your internet connection and try again.");
        $('watch').disabled = false;
        $('watch').textContent = 'Watch the journey';
        return;
      }

      // Keep the credentials in the URL fragment (never sent to a server), so
      // a bookmark or a refresh resumes without retyping anything.
      history.replaceState(null, '', '#' + digitsOnly($('code').value) + '-' + passcode);
      startWatching(key);
    });

    // Resume from a bookmarked link.
    var hash = (location.hash || '').replace('#', '');
    if (hash.indexOf('-') > 0) {
      var parts = hash.split('-');
      var code = digitsOnly(parts[0], CODE_LENGTH);
      var pass = digitsOnly(parts[1], PASSCODE_LENGTH);
      if (code.length === CODE_LENGTH && pass.length === PASSCODE_LENGTH) {
        $('code').value = code;
        $('passcode').value = pass;
        updateSubmitState();
        accessKeyFor(PREFIX + code, pass).then(startWatching);
      }
    }
  });
})();
