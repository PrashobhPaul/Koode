// tp-push — fans one journey event out to every follower's phone via FCM.
//
// Called by the database (pg_net), never by apps: the tp_events insert
// trigger and the one-minute sweeper POST {"eventId"} with the shared secret
// in `x-tp-push-secret`. The secret is verified inside Postgres by
// tp_push_claim, which is also the idempotency gate — an event is handed out
// only if it is new, or unacknowledged and its last attempt is over a minute
// old — so a replayed or duplicated call can never cause a double send.
//
// Delivery: a high-priority FCM *data* message per registered device. High
// priority wakes Koode even when it is closed; the app builds the
// notification itself (same wording and ids as its timeline, de-duplicated
// on the device). A 24h TTL lets FCM hold messages for phones that are
// offline. Tokens FCM reports as dead are pruned; transient failures leave
// the event unacknowledged so the sweeper retries it (up to 6 attempts).
//
// Configuration: the Firebase service-account JSON, either as the Edge
// Function secret FCM_SERVICE_ACCOUNT or in Vault as 'fcm_service_account'.
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided by the platform.

import { createClient } from "jsr:@supabase/supabase-js@2";

type ServiceAccount = { project_id: string; client_email: string; private_key: string };
type Claim = {
  eventId: string;
  accessKey: string;
  tripId: string | null;
  eventTime: number;
  event: { type?: string; payload?: Record<string, unknown> };
  tokens: string[];
};

const db = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

let account: ServiceAccount | null = null;
let oauth: { token: string; expiresAt: number } | null = null;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

async function serviceAccount(secret: string): Promise<ServiceAccount | null> {
  if (account) return account;
  let raw: unknown = Deno.env.get("FCM_SERVICE_ACCOUNT") ?? null;
  if (!raw) {
    const { data } = await db.rpc("tp_push_fcm_account", { p_secret: secret });
    raw = data;
  }
  if (!raw) return null;
  const parsed = (typeof raw === "string" ? JSON.parse(raw) : raw) as ServiceAccount;
  if (!parsed.project_id || !parsed.client_email || !parsed.private_key) return null;
  account = parsed;
  return account;
}

function base64url(input: Uint8Array | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** OAuth access token for FCM v1, minted from the service account (cached ~1h). */
async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (oauth && oauth.expiresAt - 120 > now) return oauth.token;

  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = `${header}.${claims}`;

  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)),
  );

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${base64url(signature)}`,
    }),
  });
  if (!res.ok) throw new Error(`oauth ${res.status}: ${await res.text()}`);
  const body = await res.json();
  oauth = { token: body.access_token, expiresAt: now + (body.expires_in ?? 3600) };
  return oauth.token;
}

/** FCM says this token will never work again: remove it. */
function isDeadToken(status: number, body: string): boolean {
  return status === 404 || body.includes("UNREGISTERED") ||
    (status === 400 && body.includes("registration token"));
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  const secret = req.headers.get("x-tp-push-secret") ?? "";
  let eventId = "";
  try {
    eventId = String((await req.json()).eventId ?? "");
  } catch {
    return json({ error: "bad request" }, 400);
  }
  if (!secret || !eventId) return json({ error: "unauthorised" }, 401);

  // Credentials first: without them nothing is claimed, so every event stays
  // eligible for the sweeper until push is configured.
  const sa = await serviceAccount(secret).catch(() => null);
  if (!sa) return json({ status: "fcm-not-configured" });

  const { data, error } = await db.rpc("tp_push_claim", { p_event_id: eventId, p_secret: secret });
  if (error) return json({ error: error.message }, 500);
  const claim = data as Claim | null;
  if (!claim) return json({ status: "skipped" });

  const tokens = claim.tokens ?? [];
  const message = {
    kind: "tp_event",
    eventId: claim.eventId,
    accessKey: claim.accessKey,
    tripId: claim.tripId ?? "",
    eventTime: String(claim.eventTime),
    type: claim.event?.type ?? "",
    payload: JSON.stringify(claim.event?.payload ?? {}),
  };

  const dead: string[] = [];
  let sent = 0;
  let failed = 0;
  let bearer = "";
  try {
    bearer = await accessToken(sa);
  } catch (e) {
    // Leave the event unacknowledged; the sweeper retries.
    console.error("tp-push oauth failed", e);
    return json({ error: "oauth" }, 502);
  }

  await Promise.all(tokens.map(async (token) => {
    try {
      const res = await fetch(
        `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
        {
          method: "POST",
          headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
          body: JSON.stringify({
            message: { token, data: message, android: { priority: "HIGH", ttl: "86400s" } },
          }),
        },
      );
      if (res.ok) {
        sent++;
        return;
      }
      const body = await res.text();
      if (isDeadToken(res.status, body)) dead.push(token);
      else {
        failed++;
        console.error("tp-push send failed", res.status, body.slice(0, 300));
      }
    } catch (e) {
      failed++;
      console.error("tp-push send error", e);
    }
  }));

  await db.rpc("tp_push_done", {
    p_event_id: eventId,
    p_secret: secret,
    p_dead: dead,
    p_complete: failed === 0,
  });
  return json({ sent, failed, dead: dead.length });
});
