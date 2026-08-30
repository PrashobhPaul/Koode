/*
 * Backend configuration for the browser viewer.
 *
 * These are the same two values the Android app is built with. The anon key is
 * public by design: it can only reach the RPC functions in
 * supabase/schema.sql, and every one of those enforces its own access rules.
 * Knowing this key grants nothing without a journey's credentials.
 *
 * Kept in its own file so the Pages workflow can regenerate it from
 * apps/android/supabase.properties and the two never drift apart.
 */
window.KOODE_CONFIG = {
  SUPABASE_URL: "https://hpijaryujcjtuhghongx.supabase.co",
  SUPABASE_ANON_KEY: "sb_publishable_GPc7-0jGmwTuQblzBMn03w_32X3Y0ns"
};
