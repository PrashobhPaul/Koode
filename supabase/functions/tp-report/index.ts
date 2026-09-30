// tp-report — the approved journey report's private storage, via short-lived
// signed URLs. The bucket is private; nobody reads or writes it directly.
//
//   {"action":"upload", accessKey, ownerToken, sha256, bytes}
//       The traveller's phone, after approving the journey. The owner token
//       and the approval are verified in Postgres (tp_report_begin), which
//       reserves the next version and a random object path. Returns a signed
//       upload URL (relative to the project URL) valid for two hours. The
//       phone uploads the PDF and then calls tp_report_publish, which checks
//       the file exists and announces it to followers.
//   {"action":"download", accessKey} | {"action":"download", tripId, viewerToken}
//       A follower. Access is checked exactly like reading the journey
//       (passcode, or an approved device). Returns a 10-minute signed URL.
//   {"action":"gc"}  with x-tp-push-secret
//       The database sweeper: deletes report files of journeys that are gone.
//
// Only the journey timeline report is ever stored. The traveller's expense
// report never leaves the phone. SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY
// are provided by the platform; nothing else is configured here.

import { createClient } from "jsr:@supabase/supabase-js@2";

const db = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

const BUCKET = "journey-reports";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** The signed URL without its host: the app prefixes its own project URL. */
function relative(url: string): string {
  const u = new URL(url);
  return u.pathname + u.search;
}

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

async function upload(body: Record<string, unknown>): Promise<Response> {
  const { data, error } = await db.rpc("tp_report_begin", {
    p_access_key: str(body.accessKey),
    p_owner_token: str(body.ownerToken),
    p_sha256: str(body.sha256).toLowerCase(),
    p_bytes: Number(body.bytes) || 0,
  });
  if (error) return json({ error: "server" }, 500);
  const res = data as { version?: number; path?: string; error?: string } | null;
  if (!res || res.error || !res.path) return json({ error: res?.error ?? "DENIED" }, 403);

  const signed = await db.storage.from(BUCKET).createSignedUploadUrl(res.path);
  if (signed.error || !signed.data) return json({ error: "storage" }, 500);
  return json({ version: res.version, path: res.path, uploadUrl: relative(signed.data.signedUrl) });
}

async function download(body: Record<string, unknown>): Promise<Response> {
  const { data, error } = await db.rpc("tp_report_locate", {
    p_access_key: str(body.accessKey) || null,
    p_trip_id: str(body.tripId) || null,
    p_viewer_token: str(body.viewerToken) || null,
  });
  if (error) return json({ error: "server" }, 500);
  const rep = data as { version: number; path: string; sha256: string; bytes: number } | null;
  if (!rep) return json({ error: "NOT_AVAILABLE" }, 404);

  const signed = await db.storage.from(BUCKET).createSignedUrl(rep.path, 600);
  if (signed.error || !signed.data) return json({ error: "storage" }, 500);
  return json({
    version: rep.version, sha256: rep.sha256, bytes: rep.bytes,
    downloadUrl: relative(signed.data.signedUrl),
  });
}

async function gc(secret: string): Promise<Response> {
  const { data } = await db.rpc("tp_report_trash_take", { p_secret: secret });
  const items = (data ?? null) as { bucket: string; path: string }[] | null;
  if (items === null) return json({ error: "unauthorised" }, 401);
  if (items.length === 0) return json({ removed: 0 });
  const paths = items.filter((i) => i.bucket === BUCKET).map((i) => i.path);
  const { error } = await db.storage.from(BUCKET).remove(paths);
  if (error) return json({ error: "storage" }, 500);
  await db.rpc("tp_report_trash_clear", { p_secret: secret, p_paths: items.map((i) => i.path) });
  return json({ removed: paths.length });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad request" }, 400);
  }
  switch (body.action) {
    case "upload":
      return await upload(body);
    case "download":
      return await download(body);
    case "gc":
      return await gc(req.headers.get("x-tp-push-secret") ?? "");
    default:
      return json({ error: "unknown action" }, 400);
  }
});
