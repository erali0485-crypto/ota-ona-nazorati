// Supabase Edge Function: device-ingest
// Deploy with verify_jwt=false. This function validates a per-device token itself.
// SUPABASE_SERVICE_ROLE_KEY must remain a server-side secret.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const db = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false } });

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};
async function sha256Hex(value: string) {
  const data = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function reply(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), { status, headers: cors });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return reply(405, { error: "POST only" });
  try {
    const auth = req.headers.get("authorization") || "";
    const match = auth.match(/^Bearer\s+([A-Za-z0-9_-]{32,160})$/);
    if (!match) return reply(401, { error: "Device token required" });
    const tokenHash = await sha256Hex(match[1]);
    const { data: device, error: deviceError } = await db
      .from("pc_devices")
      .select("id, owner_id, consent_status")
      .eq("device_token_hash", tokenHash)
      .maybeSingle();
    if (deviceError || !device) return reply(401, { error: "Invalid device token" });
    if (device.consent_status !== "granted") return reply(403, { error: "Device is not enrolled with consent" });

    const body = await req.json();
    const kind = String(body.kind || "");
    const now = new Date().toISOString();

    if (kind === "heartbeat") {
      const { error } = await db.from("pc_devices").update({
        last_seen_at: now,
        updated_at: now,
        app_version: typeof body.app_version === "string" ? body.app_version.slice(0, 40) : null,
      }).eq("id", device.id);
      if (error) throw error;
      return reply(200, { ok: true, device_id: device.id, server_time: now });
    }

    if (kind === "report") {
      const health = body.health;
      if (!health || typeof health !== "object" || Array.isArray(health)) return reply(400, { error: "health object required" });
      const safeHealth: Record<string, unknown> = {};
      for (const key of ["battery_percent", "battery_charging", "network_type", "os_version", "app_version", "storage_free_mb", "online"]) {
        const value = health[key];
        if (["string", "number", "boolean"].includes(typeof value)) safeHealth[key] = value;
      }
      const { error: reportError } = await db.from("pc_reports").insert({
        owner_id: device.owner_id, device_id: device.id, reported_at: now, health: safeHealth,
      });
      if (reportError) throw reportError;
      const { error: seenError } = await db.from("pc_devices").update({ last_seen_at: now, updated_at: now }).eq("id", device.id);
      if (seenError) throw seenError;
      return reply(201, { ok: true, device_id: device.id, server_time: now });
    }

    if (kind === "event") {
      const eventType = String(body.event_type || "status").slice(0, 50);
      const summary = String(body.summary || "").trim().slice(0, 500);
      if (!summary) return reply(400, { error: "summary required" });
      const { error } = await db.from("pc_events").insert({
        owner_id: device.owner_id, device_id: device.id, event_type: eventType, summary, occurred_at: now,
      });
      if (error) throw error;
      await db.from("pc_devices").update({ last_seen_at: now, updated_at: now }).eq("id", device.id);
      return reply(201, { ok: true, device_id: device.id, server_time: now });
    }
    return reply(400, { error: "kind must be heartbeat, report, or event" });
  } catch (error) {
    console.error("device-ingest failed", error);
    return reply(500, { error: "Internal server error" });
  }
});
