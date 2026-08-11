"use strict";

/**
 * TripPulse Cloud Functions (docs/spec/30, 40, 52, 117, 130).
 *
 * Two responsibilities, both optional to the core app (the client works fully
 * without them; these add server-side guarantees):
 *
 *  1. cleanupExpiredTrips — scheduled revocation. Reads are already gated on
 *     meta/expiresAt by the database rules, so an expired trip is already
 *     inaccessible; this job additionally purges the live state and raw
 *     location history to honour the privacy-first retention default, while
 *     retaining the event log (timeline, incidents, SOS) per policy.
 *
 *  2. onTripEvent — fan-out. When the owner appends an SOS/arrival/overnight
 *     event, push a minimal high-/normal-priority message to the trip topic so
 *     viewers are alerted even when the app is not in the foreground.
 */

const { onValueCreated } = require("firebase-functions/v2/database");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

// Event types that warrant a viewer notification. Content is deliberately
// minimal — no medication names, note text, or precise coordinates.
const NOTIFY = {
  SOS_ACTIVATED: { title: "🚨 SOS alert", body: "The driver has raised an emergency alert.", priority: "high" },
  ARRIVAL_DETECTED: { title: "Trip update", body: "The driver has arrived.", priority: "normal" },
  TRIP_COMPLETED: { title: "Trip completed", body: "The trip has ended.", priority: "normal" },
  OVERNIGHT_CONFIRMED: { title: "Overnight rest", body: "The driver is stopping overnight.", priority: "normal" },
};

exports.onTripEvent = onValueCreated("/trips/{accessKey}/events/{eventId}", async (event) => {
  const accessKey = event.params.accessKey;
  const val = (event.data && event.data.val()) || {};
  const type = val.type;
  const cfg = NOTIFY[type];
  if (!cfg) return;

  const message = {
    topic: `trip_${accessKey}`,
    data: {
      type: String(type),
      eventTime: String(val.eventTime || Date.now()),
    },
    android: { priority: cfg.priority === "high" ? "high" : "normal" },
    notification: { title: cfg.title, body: cfg.body },
  };

  try {
    await admin.messaging().send(message);
    logger.info(`Sent ${type} notification for trip ${accessKey}`);
  } catch (e) {
    logger.error(`FCM send failed for ${type}/${accessKey}`, e);
  }
});

exports.cleanupExpiredTrips = onSchedule("every 60 minutes", async () => {
  const now = Date.now();
  const db = admin.database();

  const snap = await db.ref("trips").orderByChild("meta/expiresAt").endAt(now).get();
  if (!snap.exists()) {
    logger.info("No expired trips to purge.");
    return;
  }

  const updates = {};
  let count = 0;
  snap.forEach((child) => {
    const key = child.key;
    const meta = child.child("meta").val() || {};
    if (typeof meta.expiresAt === "number" && meta.expiresAt < now && !meta.purgedAt) {
      updates[`trips/${key}/currentState`] = null;
      updates[`trips/${key}/locations`] = null;
      updates[`trips/${key}/meta/purgedAt`] = now;
      count += 1;
    }
  });

  if (count > 0) {
    await db.ref().update(updates);
    logger.info(`Purged live data for ${count} expired trip(s).`);
  }
});
