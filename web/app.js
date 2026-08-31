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
    DESTINATION_CHANGED: ['🧭', 'Destination changed'],
    STOP_STARTED: ['🅿', 'Stopped'],
    STOP_ENDED: ['▶', 'On the move again'],
    LONG_STOP: ['⏳', 'Long stop'],
    ROUTE_DEVIATION: ['↩', 'Off the usual route'],
    ROUTE_REJOINED: ['↪', 'Back on route'],
    ARRIVAL_DETECTED: ['📍', 'Reached the destination'],
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
    SIM_CHANGED: ['⚠️', 'A different SIM is in this phone'],
    BOARDED: ['🎫', 'Boarded'],
    TRANSIT_HALTED: ['⏸', 'Halted'],
    TRANSIT_RESUMED: ['▶', 'Moving again'],
    DEBOARDED: ['🚶', 'Got off'],
    LEG_STARTED: ['🧭', 'Next stage started'],
    LEG_COMPLETED: ['✅', 'Stage completed']
  };

  var MEAL_LABELS = {
    BREAKFAST: 'Breakfast', LUNCH: 'Lunch', DINNER: 'Dinner', SNACK: 'Snack'
  };

  /** Prefers whatever the event itself said, exactly as the app does. */
  function describeEvent(e) {
    var type = e.type || 'EVENT';
    var known = EVENT_LABELS[type] || ['•', type.toLowerCase().replace(/_/g, ' ')];
    var payload = e.payload || {};
    if (type === 'FOOD_REPORTED' && MEAL_LABELS[payload.meal]) {
      return [known[0], MEAL_LABELS[payload.meal]];
    }
    if (payload.text) return [known[0], payload.text];
    return known;
  }

  // ---- map ---------------------------------------------------------------

  var map = null;
  var layers = { route: null, travelled: null, start: null, end: null, live: null };
  var fitted = false;

  function ensureMap() {
    if (map) return map;
    map = L.map('map', { zoomControl: false, attributionControl: true });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);
    map.setView([20.5937, 78.9629], 4);
    return map;
  }

  /** Start: a ringed node. Modern, and clearly not the destination. */
  function startIcon() {
    return L.divIcon({
      className: '',
      html: '<div style="width:22px;height:22px;border-radius:50%;border:3px solid #2DD4BF;' +
            'background:rgba(45,212,191,0.25);box-shadow:0 0 0 5px rgba(45,212,191,0.15)"></div>',
      iconSize: [22, 22], iconAnchor: [11, 11]
    });
  }

  /** Destination: a pennant on a mast, standing on the ground. */
  function endIcon() {
    return L.divIcon({
      className: '',
      html: '<svg width="34" height="40" viewBox="0 0 34 40">' +
            '<line x1="8" y1="38" x2="8" y2="6" stroke="#F59E0B" stroke-width="3" stroke-linecap="round"/>' +
            '<path d="M9 6 L31 12 L9 19 Z" fill="#F59E0B"/>' +
            '<circle cx="8" cy="38" r="5" fill="rgba(245,158,11,0.35)"/>' +
            '<circle cx="8" cy="38" r="2.5" fill="#F59E0B"/></svg>',
      iconSize: [34, 40], iconAnchor: [8, 38]
    });
  }

  /** The traveller: a blue dot with a breathing halo (CSS-animated). */
  function liveIcon() {
    return L.divIcon({
      className: '',
      html: '<div class="live-dot"><i></i></div>',
      iconSize: [16, 16], iconAnchor: [8, 8]
    });
  }

  function setLayer(name, layer) {
    if (layers[name]) map.removeLayer(layers[name]);
    layers[name] = layer || null;
    if (layer) layer.addTo(map);
  }

  function drawJourney(origin, destination, travelled, current) {
    ensureMap();
    if (travelled && travelled.length > 1) {
      setLayer('travelled', L.polyline(travelled, {
        color: '#38BDF8', weight: 5, opacity: 0.95, lineJoin: 'round', lineCap: 'round'
      }));
    }
    if (origin) setLayer('start', L.marker(origin, { icon: startIcon(), title: 'Start' }));
    if (destination) setLayer('end', L.marker(destination, { icon: endIcon(), title: 'Destination' }));
    if (current) setLayer('live', L.marker(current, { icon: liveIcon(), title: 'Where they are' }));

    if (!fitted) {
      var points = (travelled || []).slice();
      if (origin) points.push(origin);
      if (destination) points.push(destination);
      if (current) points.push(current);
      if (points.length > 1) { map.fitBounds(L.latLngBounds(points).pad(0.18)); fitted = true; }
      else if (points.length === 1) { map.setView(points[0], 13); fitted = true; }
    }
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

    var threshold = state.deviationActive ? DARK_GRACE_MS : DARK_UNEXPLAINED_MS;
    return {
      dark: true,
      reason: flat ? 'BATTERY_DIED' : 'SIGNAL_LOST',
      concerning: !flat && elapsed >= threshold,
      since: last, elapsed: elapsed, battery: battery
    };
  }

  function darkHeadline(a, who) {
    if (!a.dark) return null;
    if (a.reason === 'SIM_SWAPPED') return who + "'s phone has a different SIM in it";
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
      return 'A different SIM is in the phone. Koode keeps reporting over any ' +
        'network it can reach, so updates may continue. The last known position is saved.';
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
    var sos = state && state.sosActive === true;

    // ---- headline. Never says "ended" unless the traveller ended it. ----
    var card = $('health');
    var dot = card.querySelector('.dot');
    var headlineEl = card.querySelector('.headline');
    card.className = 'card hero';
    headlineEl.className = 'headline ok';
    dot.className = 'dot';

    var who = owner || 'They';
    var dark = assessDarkness(state, ended);

    var headline;
    if (sos) {
      headline = 'SOS active';
      card.className = 'card hero danger';
      headlineEl.className = 'headline danger';
    } else if (ended) {
      headline = 'Journey ended safely';
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
      if (state.deviationActive) notes.push('Off the usual route');
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
    if (ended) text('eta', 'Arrived safely 🎉');
    else if (state && state.etaMode === 'OVERNIGHT_PENDING') text('eta', 'Resting overnight');
    else if (state && state.etaLikely) {
      text('eta', clockWithDay(state.etaLow || state.etaLikely) + ' – ' + clock(state.etaHigh || state.etaLikely));
    } else text('eta', 'Calculating…');

    var progress = Math.round(((state && state.progress) || 0) * 100);
    $('progress-bar').style.width = Math.max(0, Math.min(100, progress)) + '%';
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
    (events || [])
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

  async function startWatching(accessKey) {
    hide($('signin'));
    show($('journey'));

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
    $('play').addEventListener('click', togglePlayback);
    $('speed').addEventListener('click', cycleSpeed);

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
