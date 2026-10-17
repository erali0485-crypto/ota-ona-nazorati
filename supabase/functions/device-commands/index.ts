// OTA-ONA NAZORATI — remote device command queue.
// Deploy with verify_jwt=false; this function validates a per-device bearer token itself.
// Never expose SUPABASE_SERVICE_ROLE_KEY to the browser or device.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const db = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false } });
const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};
async function sha256Hex(value: string) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function reply(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), { status, headers });
}
Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return reply(405, { error: "POST only" });
  try {
    const match = (req.headers.get("authorization") || "").match(/^Bearer\s+([A-Za-z0-9_-]{32,160})$/);
    if (!match) return reply(401, { error: "Device token required" });
    const tokenHash = await sha256Hex(match[1]);
    const { data: device, error: deviceError } = await db
      .from("pc_devices").select("id, owner_id, consent_status")
      .eq("device_token_hash", tokenHash).maybeSingle();
    if (deviceError || !device) return reply(401, { error: "Invalid device token" });
    if (device.consent_status !== "granted") return reply(403, { error: "Device consent is not granted" });

    const body = await req.json();
    const kind = String(body.kind || "");
    const now = new Date().toISOString();

    if (kind === "poll") {
      const { data: pending, error } = await db.from("pc_commands")
        .select("id, action, message, created_at")
        .eq("device_id", device.id).eq("owner_id", device.owner_id)
        .eq("status", "pending").eq("action", "lock_workstation")
        .order("created_at", { ascending: true }).limit(1).maybeSingle();
      if (error) throw error;
      if (!pending) return reply(200, { ok: true, command: null, server_time: now });
      const { data: claimed, error: claimError } = await db.from("pc_commands")
        .update({ status: "delivered", delivered_at: now })
        .eq("id", pending.id).eq("device_id", device.id).eq("status", "pending")
        .select("id, action, message, created_at").maybeSingle();
      if (claimError) throw claimError;
      if (!claimed) return reply(200, { ok: true, command: null, server_time: now });
      return reply(200, { ok: true, command: claimed, server_time: now });
    }

    if (kind === "ack") {
      const commandId = String(body.command_id || "");
      const result = String(body.result || "");
      const summary = String(body.summary || "").slice(0, 300);
      if (!/^[0-9a-f-]{36}$/i.test(commandId)) return reply(400, { error: "Valid command_id required" });
      if (!["acknowledged", "failed"].includes(result)) return reply(400, { error: "result must be acknowledged or failed" });
      const { data: updated, error } = await db.from("pc_commands")
        .update({ status: result, acknowledged_at: now, message: summary || "lock_workstation result" })
        .eq("id", commandId).eq("device_id", device.id).eq("owner_id", device.owner_id)
        .eq("action", "lock_workstation").eq("status", "delivered")
        .select("id, status, acknowledged_at").maybeSingle();
      if (error) throw error;
      if (!updated) return reply(409, { error: "Command not found, already processed, or not assigned to this device" });
      await db.from("pc_events").insert({
        owner_id: device.owner_id, device_id: device.id,
        event_type: result === "acknowledged" ? "remote_lock_completed" : "remote_lock_failed",
        summary: summary || (result === "acknowledged" ? "Remote lock requested" : "Remote lock failed"),
        occurred_at: now,
      });
      return reply(200, { ok: true, command: updated, server_time: now });
    }
    return reply(400, { error: "kind must be poll or ack" });
  } catch (error) {
    console.error("device-commands failed", error);
    return reply(500, { error: "Internal server error" });
  }
});
