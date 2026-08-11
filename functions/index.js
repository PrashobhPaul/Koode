"use strict";

/**
 * TripPulse Cloud Functions (docs/spec/30, 40, 52, 117, 130).
 *
 * Two responsibilities, both optional to the core app (the client works fully
 * without them; these add server-side guarantees):
 *
 *  1. cleanupExpiredTrips — scheduled destruction. Reads are already gated on
 *     meta/expiresAt by the database rules, so the moment a trip expires
 *     (30 minutes after the driver reaches the destination) viewers lose
 *     access; this job then deletes the whole trip node — meta, live state,
 *     events and locations — so the trip id is fully destroyed server-side.
 *
 *  2. onTripEvent — fan-out. When the owner appends a start/SOS/arrival/
 *     overnight event, push a minimal high-/normal-priority message to the
 *     trip topic so viewers are alerted even when the app is backgrounded.
 */

const { onValueCreated } = require("firebase-functions/v2/database");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

// Event types that warrant a viewer notification. Content is deliberately
// minimal — no medication names, note text, or precise coordinates.
const NOTIFY = {
  TRIP_STARTED: { title: "Trip started", body: "The driver has started the trip.", priority: "normal" },
  SOS_ACTIVATED: { title: "🚨 SOS alert", body: "The driver has raised an emergency alert.", priority: "high" },
  ARRIVAL_DETECTED: { title: "Destination reached", body: "The driver has reached the destination.", priority: "normal" },
  TRIP_COMPLETED: { title: "Trip completed", body: "The trip has ended. Access expires 30 minutes after arrival.", priority: "normal" },
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

exports.cleanupExpiredTrips = onSchedule("every 10 minutes", async () => {
  const now = Date.now();
  const db = admin.database();

  const snap = await db.ref("trips").orderByChild("meta/expiresAt").endAt(now).get();
  if (!snap.exists()) {
    logger.info("No expired trips to destroy.");
    return;
  }

  const updates = {};
  let count = 0;
  snap.forEach((child) => {
    const meta = child.child("meta").val() || {};
    if (typeof meta.expiresAt === "number" && meta.expiresAt < now) {
      // Full destruction: the trip id and everything under it is gone.
      updates[`trips/${child.key}`] = null;
      count += 1;
    }
  });

  if (count > 0) {
    await db.ref().update(updates);
    logger.info(`Destroyed ${count} expired trip(s).`);
  }
});
