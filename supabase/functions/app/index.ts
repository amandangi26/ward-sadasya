// INC MCD NETWORK app API. One Edge Function, POST JSON { action, ...params }.
// Deploy with: supabase functions deploy app --no-verify-jwt
import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-session",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

// Today's date in India (UTC+5:30), as YYYY-MM-DD.
const istToday = () => new Date(Date.now() + 5.5 * 3600 * 1000).toISOString().slice(0, 10);

const publicUrl = (bucket: string, path: string) => db.storage.from(bucket).getPublicUrl(path).data.publicUrl;

async function memberFromSession(token: string | null) {
  if (!token) return null;
  const { data } = await db.from("sessions").select("mobile, members(*)").eq("token", token).maybeSingle();
  if (!data || !data.members) return null;
  const m = data.members as unknown as Record<string, unknown>;
  if (!m.active) return null;
  await db.from("members").update({ last_active_at: new Date().toISOString() }).eq("mobile", data.mobile);
  return m;
}

async function settings() {
  const { data } = await db.from("settings").select("key, value");
  const s: Record<string, string> = {};
  for (const r of data ?? []) s[r.key] = r.value ?? "";
  return s;
}

// FCM topic names: letters, digits and _ only.
const slug = (v: unknown) => String(v ?? "").trim().toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");
const memberDistrict = (m: Record<string, unknown>) => String(m.district || m.zone || "").trim();
const sameDistrict = (a: unknown, m: Record<string, unknown>) =>
  String(a ?? "").trim().toLowerCase() === memberDistrict(m).toLowerCase();

const PLATFORMS = ["x", "facebook", "instagram", "whatsapp"];

// Delhi's 7 Lok Sabha seats, for the profile form.
const LOK_SABHA = [
  "चांदनी चौक (Chandni Chowk)", "उत्तर पूर्वी दिल्ली (North East Delhi)", "पूर्वी दिल्ली (East Delhi)", "नई दिल्ली (New Delhi)",
  "उत्तर पश्चिम दिल्ली (North West Delhi)", "पश्चिमी दिल्ली (West Delhi)", "दक्षिणी दिल्ली (South Delhi)",
];

const PLATFORM_LABEL: Record<string, string> = { facebook: "Facebook", instagram: "Instagram", x: "X (Twitter)", whatsapp: "WhatsApp" };

/** Notification title and text for a task, built from the task itself. Keep in step with taskNotice() in admin/src/lib.js. */
function taskNotice(t: Record<string, any>) {
  const desc = String(t.description ?? "").trim();
  if (t.kind === "share") {
    const key = ["facebook", "instagram", "x", "whatsapp"].find((k) => t[`link_${k}`]) ?? "facebook";
    return { title: `नई पोस्ट: ${PLATFORM_LABEL[key]} पर शेयर करें`, body: desc ? `${t.title} · ${desc}`.slice(0, 240) : String(t.title) };
  }
  return { title: `नया टास्क: ${t.title}`.slice(0, 120), body: desc ? desc.slice(0, 240) : "2 फोटो और 2 वीडियो अपलोड करें, आज रात 11:59 तक।" };
}

const digits10 = (v: unknown) => {
  const d = String(v ?? "").replace(/\D/g, "").slice(-10);
  return /^[6-9]\d{9}$/.test(d) ? d : null;
};
const clean = (v: unknown, max = 120) => {
  const t = String(v ?? "").trim().slice(0, max);
  return t ? t : null;
};

// Points. Keep in step with the member_stats view in schema.sql, which is the source of truth for totals.
// 100 per share (per post and platform), 100 per task done in full, 100 per approved own task.
const POINTS = { share: 100, task: 100, own_task: 100 };
// Every task (and every own task) needs exactly this many uploads.
const NEED = { video: 2, photo: 2 };

// Delhi's 70 Vidhan Sabha seats, for the profile form.
const ASSEMBLIES = ["Narela", "Burari", "Timarpur", "Adarsh Nagar", "Badli", "Rithala", "Bawana", "Mundka", "Kirari",
  "Sultanpur Majra", "Nangloi Jat", "Mangol Puri", "Rohini", "Shalimar Bagh", "Shakur Basti", "Tri Nagar", "Wazirpur",
  "Model Town", "Sadar Bazar", "Chandni Chowk", "Matia Mahal", "Ballimaran", "Karol Bagh", "Patel Nagar", "Moti Nagar",
  "Madipur", "Rajouri Garden", "Hari Nagar", "Tilak Nagar", "Janakpuri", "Vikaspuri", "Uttam Nagar", "Dwarka", "Matiala",
  "Najafgarh", "Bijwasan", "Palam", "Delhi Cantt", "Rajinder Nagar", "New Delhi", "Jangpura", "Kasturba Nagar",
  "Malviya Nagar", "R K Puram", "Mehrauli", "Chhatarpur", "Deoli", "Ambedkar Nagar", "Sangam Vihar", "Greater Kailash",
  "Kalkaji", "Tughlakabad", "Badarpur", "Okhla", "Trilokpuri", "Kondli", "Patparganj", "Laxmi Nagar", "Vishwas Nagar",
  "Krishna Nagar", "Gandhi Nagar", "Shahdara", "Seemapuri", "Rohtas Nagar", "Seelampur", "Ghonda", "Babarpur",
  "Gokalpur", "Mustafabad", "Karawal Nagar"].map((n, i) => `${i + 1} - ${n}`);

const DOWNLOAD_URL = "https://github.com/aadiliftekhar143-cmd/inc-mcd-network/releases/latest/download/app-release.apk";

/** Anonymous rank in ward, Vidhan Sabha, district and all Delhi (no names). Areas come from the admin's member list. */
async function memberRanks(mobile: string) {
  const { data: stats } = await db.from("member_stats").select("mobile, points").range(0, 9999);
  const { data: rows } = await db.from("members").select("mobile, ward_no, assembly, district, zone").eq("active", true).range(0, 9999);
  const pts = new Map((stats ?? []).map((x) => [x.mobile, Number(x.points || 0)]));
  const me = (rows ?? []).find((m) => m.mobile === mobile);
  if (!me) return null;
  const rankIn = (pred: (m: any) => boolean) => {
    const group = (rows ?? []).filter(pred);
    const mine = pts.get(mobile) ?? 0;
    return { rank: group.filter((m) => (pts.get(m.mobile) ?? 0) > mine).length + 1, total: group.length };
  };
  const dist = memberDistrict(me).toLowerCase();
  const asm = String(me.assembly ?? "").trim().toLowerCase();
  return {
    ward: rankIn((m) => m.ward_no === me.ward_no),
    assembly: asm ? rankIn((m) => String(m.assembly ?? "").trim().toLowerCase() === asm) : null,
    district: dist ? rankIn((m) => memberDistrict(m).toLowerCase() === dist) : null,
    delhi: rankIn(() => true),
  };
}

async function memberStats(mobile: string) {
  const { data } = await db.from("member_stats").select("points, tasks_done, videos, photos, shares, full_tasks, own_tasks").eq("mobile", mobile).maybeSingle();
  const { data: own } = await db.from("member_tasks").select("status").eq("mobile", mobile);
  return {
    points: Number(data?.points ?? 0),
    tasks_done: Number(data?.tasks_done ?? 0),
    videos: Number(data?.videos ?? 0),
    photos: Number(data?.photos ?? 0),
    shares: Number(data?.shares ?? 0),
    full_tasks: Number(data?.full_tasks ?? 0),
    own_tasks: Number(data?.own_tasks ?? 0),
    own_total: (own ?? []).length,
    own_pending: (own ?? []).filter((x) => x.status === "pending").length,
  };
}

async function tasksForDate(mobile: string, date: string) {
  const { data: tasks } = await db.from("tasks").select("*").eq("task_date", date).order("sort");
  const ids = (tasks ?? []).map((t) => t.id);
  const { data: media } = ids.length
    ? await db.from("media").select("task_id, kind").eq("mobile", mobile).in("task_id", ids)
    : { data: [] };
  const { data: done } = ids.length
    ? await db.from("completions").select("task_id").eq("mobile", mobile).in("task_id", ids)
    : { data: [] };
  const { data: shares } = ids.length
    ? await db.from("share_events").select("task_id, platform").eq("mobile", mobile).in("task_id", ids)
    : { data: [] };
  const doneSet = new Set((done ?? []).map((d) => d.task_id));
  return (tasks ?? []).map((t) => {
    const mine = (media ?? []).filter((m) => m.task_id === t.id);
    return {
      ...t,
      videos: mine.filter((m) => m.kind === "video").length,
      photos: mine.filter((m) => m.kind === "photo").length,
      done: doneSet.has(t.id),
      shared: (shares ?? []).filter((x) => x.task_id === t.id).map((x) => x.platform),
    };
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  let body: Record<string, any> = {};
  try { body = await req.json(); } catch { return json({ error: "bad_json" }, 400); }
  const action = body.action as string;

  try {
    // ---- Login: registered mobile number only, no OTP. Leaders first, then ward members. ----
    if (action === "login") {
      const mobile = String(body.mobile ?? "").replace(/\D/g, "").slice(-10);
      if (!/^[6-9]\d{9}$/.test(mobile)) return json({ error: "invalid_mobile" }, 400);
      // One number can hold two profiles: a leader post (e.g. Observer of one assembly) and a ward member.
      const { data: ld } = await db.from("leaders").select("*").eq("mobile", mobile).eq("active", true).maybeSingle();
      const { data: m } = await db.from("members").select("*").eq("mobile", mobile).eq("active", true).maybeSingle();
      if (!ld && !m) return json({ error: "not_registered", helpline: (await settings()).helpline }, 404);
      const profiles: any[] = [];
      if (ld) {
        const { data: ls, error: le } = await db.from("leader_sessions").insert({ mobile }).select("token").single();
        if (le) throw le;
        if (!ld.first_login_at) await db.from("leaders").update({ first_login_at: new Date().toISOString() }).eq("mobile", mobile);
        profiles.push({ role: ld.role, token: ls.token, name: ld.name, district: ld.district, assembly: ld.assembly });
      }
      if (m) {
        const { data: s, error } = await db.from("sessions").insert({ mobile }).select("token").single();
        if (error) throw error;
        if (!m.first_login_at) await db.from("members").update({ first_login_at: new Date().toISOString() }).eq("mobile", mobile);
        profiles.push({ role: "member", token: s.token, name: m.name, ward_no: m.ward_no, ward_name: m.ward_name, district: memberDistrict(m), assembly: m.assembly });
      }
      // token / role / leader / member keep older app versions working: they open the first profile.
      return json({ token: profiles[0].token, role: profiles[0].role, leader: ld ?? undefined, member: m ?? { name: ld.name, mobile, ward_no: 0 }, profiles });
    }

    const member = await memberFromSession(req.headers.get("x-session"));
    if (!member) return json({ error: "unauthorized" }, 401);
    const mobile = member.mobile as string;

    // ---- Home: tasks for a date, latest leader message, social links ----
    if (action === "home") {
      const today = istToday();
      const asked = /^\d{4}-\d{2}-\d{2}$/.test(body.date ?? "") ? body.date : today;
      // Future dates stay locked until that day arrives in India, whatever the phone's clock says.
      const date = asked > today ? today : asked;
      const { data: dates } = await db.from("tasks").select("task_date").order("task_date");
      const days = [...new Set((dates ?? []).map((d) => d.task_date))];
      const tasks = await tasksForDate(mobile, date);
      const { data: msg } = await db.from("leader_messages").select("*").lte("published_at", new Date().toISOString())
        .order("published_at", { ascending: false }).limit(1).maybeSingle();
      let leader = null;
      if (msg) {
        const { data: seen } = await db.from("message_views").select("message_id").eq("mobile", mobile).eq("message_id", msg.id).maybeSingle();
        leader = { ...msg, url: publicUrl("leader", msg.path), seen: !!seen };
      }
      const { data: allAnn } = await db.from("announcements").select("*")
        .order("created_at", { ascending: false }).limit(20);
      const announcements = (allAnn ?? []).filter((a) =>
        (a.ward_no == null || a.ward_no === member.ward_no) &&
        ((!a.district && !a.zone) || sameDistrict(a.district || a.zone, member))
      );

      const s = await settings();
      return json({
        member, today, date, days, tasks, leader, announcements,
        stats: await memberStats(mobile),
        points_rule: { ...POINTS, need_video: NEED.video, need_photo: NEED.photo },
        unread_inbox: (await db.from("inbox").select("id", { count: "exact", head: true }).eq("to_mobile", mobile).is("read_at", null)).count ?? 0,
        social: { facebook: s.social_facebook, x: s.social_x, instagram: s.social_instagram },
        dpcc_social: { facebook: s.dpcc_facebook, x: s.dpcc_x, instagram: s.dpcc_instagram },
        helpline: s.helpline,
        max_upload_mb: Number(s.max_upload_mb || 50),
        app_update: {
          version_code: Number(s.app_version_code || 1),
          version_name: s.app_version_name || "1.0",
          download_url: s.app_download_url || DOWNLOAD_URL,
          notes: s.app_update_notes || "नया वर्शन उपलब्ध है।",
          mandatory: s.app_update_mandatory === "true",
        },
      });
    }

    // ---- Get a signed URL to upload one file straight to storage ----
    if (action === "upload_url") {
      const taskId = Number(body.task_id);
      const kind = body.kind === "photo" ? "photo" : "video";
      const ext = String(body.ext ?? (kind === "video" ? "mp4" : "jpg")).replace(/[^a-z0-9]/gi, "").slice(0, 5) || "bin";
      const { data: t } = await db.from("tasks").select("*").eq("id", taskId).maybeSingle();
      if (!t || t.kind !== "upload") return json({ error: "no_task" }, 404);
      if (t.task_date > istToday()) return json({ error: "not_open_yet" }, 400);
      const { count } = await db.from("media").select("id", { count: "exact", head: true })
        .eq("mobile", mobile).eq("task_id", taskId).eq("kind", kind);
      const limit = kind === "video" ? NEED.video : NEED.photo;
      if ((count ?? 0) >= limit) return json({ error: "limit_reached" }, 400);

      const s = await settings();
      const maxMb = Number(s.max_upload_mb || 50);
      if (body.size && Number(body.size) > maxMb * 1024 * 1024) {
        return json({ error: "file_too_large", max_mb: maxMb }, 400);
      }

      const path = `${t.task_date}/${taskId}/${member.ward_no}/${mobile}/${kind}-${Date.now()}.${ext}`;
      const { data, error } = await db.storage.from("uploads").createSignedUploadUrl(path);
      if (error) throw error;
      return json({ path, signed_url: data.signedUrl, max_mb: maxMb });
    }

    // ---- Register a file after it has uploaded ----
    if (action === "attach") {
      const taskId = Number(body.task_id);
      if (!taskId || isNaN(taskId)) return json({ error: "no_task" }, 400);
      const kind = body.kind === "photo" ? "photo" : "video";
      const path = String(body.path ?? "");
      if (!path.includes(`/${mobile}/`)) return json({ error: "bad_path" }, 400);
      await db.from("media").insert({ mobile, task_id: taskId, kind, path, size_bytes: body.size ?? null });
      const tasks = await db.from("tasks").select("task_date").eq("id", taskId).maybeSingle();
      if (!tasks.data) return json({ error: "no_task" }, 404);
      const list = await tasksForDate(mobile, tasks.data.task_date);
      return json({ task: list.find((x) => x.id === taskId) });
    }

    // ---- Remove a file uploaded by mistake (the latest photo or video of that task) ----
    if (action === "remove_media") {
      const taskId = Number(body.task_id);
      const kind = body.kind === "photo" ? "photo" : "video";
      const { data: last } = await db.from("media").select("id, path").eq("mobile", mobile).eq("task_id", taskId).eq("kind", kind)
        .order("created_at", { ascending: false }).limit(1).maybeSingle();
      if (!last) return json({ error: "nothing_to_remove" }, 404);
      await db.from("media").delete().eq("id", last.id);
      await db.storage.from("uploads").remove([last.path]);
      // The task is no longer complete in full, so its tick (and its 100 points) goes until the file is replaced.
      await db.from("completions").delete().eq("mobile", mobile).eq("task_id", taskId);
      const { data: t } = await db.from("tasks").select("task_date").eq("id", taskId).maybeSingle();
      const list = t ? await tasksForDate(mobile, t.task_date) : [];
      return json({ task: list.find((x) => x.id === taskId) ?? null });
    }

    // ---- Tick: only when at least 1 video is uploaded ----
    if (action === "complete") {
      const taskId = Number(body.task_id);
      if (!taskId || isNaN(taskId)) return json({ error: "no_task" }, 400);
      const { data: media } = await db.from("media").select("kind").eq("mobile", mobile).eq("task_id", taskId);
      const videos = (media ?? []).filter((m) => m.kind === "video").length;
      const photos = (media ?? []).filter((m) => m.kind === "photo").length;
      if (videos < NEED.video || photos < NEED.photo) return json({ error: "need_more", videos, photos, need: NEED }, 400);
      const { data: before } = await db.from("completions").select("video_count, photo_count").eq("mobile", mobile).eq("task_id", taskId).maybeSingle();
      await db.from("completions").upsert({ mobile, task_id: taskId, video_count: videos, photo_count: photos, completed_at: new Date().toISOString() });
      // 100 points the first time the task is complete in full (2 photos and 2 videos).
      const wasFull = before && before.video_count >= NEED.video && before.photo_count >= NEED.photo;
      const earned = wasFull ? 0 : POINTS.task;
      return json({ ok: true, earned, stats: await memberStats(mobile) });
    }

    // ---- Share button tapped (X, Facebook, Instagram, WhatsApp): +100 points once per task and platform ----
    if (action === "share") {
      const taskId = Number(body.task_id);
      const platform = String(body.platform ?? "").toLowerCase();
      if (!taskId || !PLATFORMS.includes(platform)) return json({ error: "bad_share" }, 400);
      const { data: t } = await db.from("tasks").select("id, task_date").eq("id", taskId).maybeSingle();
      if (!t) return json({ error: "no_task" }, 404);
      if (t.task_date > istToday()) return json({ error: "not_open_yet" }, 400);
      const { data: existing } = await db.from("share_events").select("platform").eq("mobile", mobile).eq("task_id", taskId).eq("platform", platform).maybeSingle();
      if (!existing) await db.from("share_events").insert({ mobile, task_id: taskId, platform, action: String(body.share_action ?? "open").slice(0, 20) });
      return json({ ok: true, earned: existing ? 0 : POINTS.share, stats: await memberStats(mobile) });
    }

    // ---- Register this phone for instant (Firebase) notifications ----
    if (action === "register_push") {
      const token = String(body.token ?? "").trim();
      if (token.length < 20) return json({ error: "bad_token" }, 400);
      await db.from("push_tokens").upsert({ token, mobile, updated_at: new Date().toISOString() });
      const topics = ["all", `ward_${member.ward_no}`];
      const d = slug(memberDistrict(member));
      if (d) topics.push(`district_${d}`);
      return json({ ok: true, topics });
    }

    // ---- Profile: what the member submitted, what is approved, and the dropdown choices ----
    if (action === "profile") {
      const { data: reqs } = await db.from("profile_requests").select("*").eq("mobile", mobile)
        .order("submitted_at", { ascending: false }).limit(1);
      const latest = reqs?.[0] ?? null;
      const { data: approved } = await db.from("member_profiles").select("*").eq("mobile", mobile).maybeSingle();
      // The member sees their own latest photo straight away, even before approval.
      const photoPath = latest?.photo_path || approved?.photo_path || null;
      let photo_url = null;
      if (photoPath) {
        const { data: signed } = await db.storage.from("uploads").createSignedUrl(photoPath, 3600);
        photo_url = signed?.signedUrl ?? null;
      }
      if (body.lite) return json({ latest, approved, photo_url });
      const { data: master } = await db.from("wards").select("*").order("ward_no");
      if (master && master.length) {
        const u = (xs: unknown[]) => [...new Set(xs.map((x) => String(x ?? "").trim()).filter(Boolean))];
        return json({
          latest, approved, photo_url,
          defaults: {
            full_name: member.name, ward_no: member.ward_no, assembly: member.assembly ?? null,
            district: memberDistrict(member) || null, lok_sabha: member.lok_sabha ?? null, contact_mobile: mobile,
          },
          options: {
            wards: master.map((w) => ({ no: w.ward_no, name: w.ward_name, assembly: w.assembly })),
            assemblies: u(master.map((w) => w.assembly)).sort((a, b) => a.localeCompare(b)),
            districts: u(master.map((w) => w.district)).sort((a, b) => a.localeCompare(b)),
            lok_sabha: u(master.map((w) => w.lok_sabha)).sort((a, b) => a.localeCompare(b)),
            // For narrowing each list by the choice above it: Lok Sabha > district > assembly > ward
            tree: master.map((w) => ({ w: w.ward_no, a: w.assembly, d: w.district, l: w.lok_sabha })),
          },
        });
      }
      const { data: rows } = await db.from("members").select("ward_no, ward_name, assembly, district, zone").range(0, 9999);
      const wardNames = new Map<number, string>();
      for (const r of rows ?? []) if (r.ward_no && r.ward_name && !wardNames.has(r.ward_no)) wardNames.set(r.ward_no, r.ward_name);
      const uniq = (xs: unknown[]) => [...new Set(xs.map((x) => String(x ?? "").trim()).filter(Boolean))].sort((a, b) => a.localeCompare(b));
      return json({
        latest, approved, photo_url,
        defaults: {
          full_name: member.name, ward_no: member.ward_no, assembly: member.assembly ?? null,
          district: memberDistrict(member) || null, contact_mobile: mobile,
        },
        options: {
          wards: Array.from({ length: 250 }, (_, i) => ({ no: i + 1, name: wardNames.get(i + 1) ?? null })),
          assemblies: ASSEMBLIES,
          districts: uniq((rows ?? []).map((r) => r.district || r.zone)),
          lok_sabha: LOK_SABHA,
        },
      });
    }

    // ---- Signed URL to upload a square profile photo ----
    if (action === "profile_photo_url") {
      const ext = String(body.ext ?? "jpg").replace(/[^a-z0-9]/gi, "").slice(0, 5) || "jpg";
      if (body.size && Number(body.size) > 8 * 1024 * 1024) return json({ error: "file_too_large", max_mb: 8 }, 400);
      const path = `profiles/${mobile}/photo-${Date.now()}.${ext}`;
      const { data, error } = await db.storage.from("uploads").createSignedUploadUrl(path);
      if (error) throw error;
      return json({ path, signed_url: data.signedUrl });
    }

    // ---- Save profile: goes in as a request until an admin approves it ----
    if (action === "profile_save") {
      const full_name = clean(body.full_name, 80);
      if (!full_name) return json({ error: "name_required" }, 400);
      const isDate = (v: unknown) => /^\d{4}-\d{2}-\d{2}$/.test(String(v ?? ""));
      if (!isDate(body.dob)) return json({ error: "dob_required" }, 400);
      if (!["male", "female", "other"].includes(String(body.gender))) return json({ error: "gender_required" }, 400);
      if (typeof body.married !== "boolean") return json({ error: "married_required" }, 400);
      if (body.married && !isDate(body.anniversary)) return json({ error: "anniversary_required" }, 400);
      const link = (v: unknown) => {
        const t = String(v ?? "").trim().slice(0, 300);
        return t ? (/^https?:\/\//i.test(t) ? t : `https://${t}`) : null;
      };
      const ward = Number(body.ward_no);
      const fam = body.family_members === "" || body.family_members == null ? null : Number(body.family_members);
      if (fam != null && (!Number.isInteger(fam) || fam < 0 || fam > 50)) return json({ error: "bad_family" }, 400);
      if (body.contact_mobile && !digits10(body.contact_mobile)) return json({ error: "bad_mobile" }, 400);
      if (body.alt_mobile && !digits10(body.alt_mobile)) return json({ error: "bad_alt_mobile" }, 400);
      const photo = clean(body.photo_path, 300);
      if (photo && !photo.startsWith(`profiles/${mobile}/`)) return json({ error: "bad_path" }, 400);
      const row = {
        mobile, full_name,
        details: clean(body.details, 500),
        ward_no: ward >= 1 && ward <= 250 ? ward : null,
        assembly: clean(body.assembly), lok_sabha: clean(body.lok_sabha), district: clean(body.district),
        family_members: fam,
        contact_mobile: digits10(body.contact_mobile), alt_mobile: digits10(body.alt_mobile),
        photo_path: photo,
        dob: body.dob, gender: body.gender, married: body.married, anniversary: body.married ? body.anniversary : null,
        link_facebook: link(body.link_facebook), link_instagram: link(body.link_instagram), link_x: link(body.link_x),
        status: "pending", submitted_at: new Date().toISOString(),
      };
      // One open request per member: a new save replaces the one still waiting.
      const { data: open } = await db.from("profile_requests").select("id, photo_path").eq("mobile", mobile).eq("status", "pending")
        .order("submitted_at", { ascending: false }).limit(1);
      if (!row.photo_path && open?.[0]?.photo_path) row.photo_path = open[0].photo_path;
      if (!row.photo_path) {
        const { data: ap } = await db.from("member_profiles").select("photo_path").eq("mobile", mobile).maybeSingle();
        row.photo_path = ap?.photo_path ?? null;
      }
      const res = open?.[0]
        ? await db.from("profile_requests").update(row).eq("id", open[0].id).select("*").single()
        : await db.from("profile_requests").insert(row).select("*").single();
      if (res.error) throw res.error;
      return json({ ok: true, request: res.data });
    }

    // ---- My rank (anonymous) ----
    if (action === "ranks") return json({ ranks: await memberRanks(mobile), stats: await memberStats(mobile) });

    // ---- Own tasks: work the member did on their own; 2 photos and 2 videos; 100 points once approved ----
    if (action === "own_upload_url") {
      const kind = body.kind === "photo" ? "photo" : body.kind === "audio" ? "audio" : "video";
      const ext = String(body.ext ?? (kind === "video" ? "mp4" : kind === "audio" ? "m4a" : "jpg")).replace(/[^a-z0-9]/gi, "").slice(0, 5) || "bin";
      if (body.size && Number(body.size) > 50 * 1024 * 1024) return json({ error: "file_too_large", max_mb: 50 }, 400);
      const folder = body.purpose === "president" ? "to-president" : "own-tasks";
      const path = `${folder}/${mobile}/${kind}-${Date.now()}.${ext}`;
      const { data, error } = await db.storage.from("uploads").createSignedUploadUrl(path);
      if (error) throw error;
      return json({ path, signed_url: data.signedUrl });
    }
    if (action === "own_task_submit") {
      const title = clean(body.title, 160);
      if (!title) return json({ error: "title_required" }, 400);
      const media = Array.isArray(body.media) ? body.media : [];
      const ok = media.filter((m: any) => typeof m?.path === "string" && m.path.startsWith(`own-tasks/${mobile}/`));
      const v = ok.filter((m: any) => m.kind === "video").length, ph = ok.filter((m: any) => m.kind === "photo").length;
      if (v < NEED.video || ph < NEED.photo) return json({ error: "need_more", videos: v, photos: ph, need: NEED }, 400);
      const { data, error } = await db.from("member_tasks").insert({
        mobile, title, description: clean(body.description, 1000),
        media: ok.map((m: any) => ({ kind: m.kind, path: m.path })),
      }).select("*").single();
      if (error) throw error;
      return json({ ok: true, task: data });
    }
    if (action === "own_tasks") {
      const { data } = await db.from("member_tasks").select("id, title, description, status, created_at, reviewed_at")
        .eq("mobile", mobile).order("created_at", { ascending: false }).limit(50);
      return json({ tasks: data ?? [] });
    }

    // ---- Message to the DPCC President (text and/or voice note) ----
    if (action === "president_message") {
      const text = clean(body.text, 2000);
      const audio = clean(body.audio_path, 300);
      if (audio && !audio.startsWith(`to-president/${mobile}/`)) return json({ error: "bad_path" }, 400);
      const media = (Array.isArray(body.media) ? body.media : [])
        .filter((m: any) => typeof m?.path === "string" && m.path.startsWith(`to-president/${mobile}/`))
        .slice(0, 6).map((m: any) => ({ kind: m.kind === "video" ? "video" : "photo", path: m.path }));
      if (!text && !audio && !media.length) return json({ error: "empty" }, 400);
      await db.from("president_messages").insert({
        mobile, sender_role: "member", sender_name: member.name, ward_no: member.ward_no,
        assembly: member.assembly ?? null, district: memberDistrict(member) || null, text, audio_path: audio, media,
      });
      return json({ ok: true });
    }

    // ---- Posters and banners ----
    if (action === "posters") {
      const { data } = await db.from("posters").select("*").order("created_at", { ascending: false }).limit(100);
      return json({ posters: (data ?? []).map((p) => ({ ...p, url: publicUrl("posters", p.path) })) });
    }

    // ---- Personal inbox (one way, from the DPCC President) ----
    if (action === "inbox") {
      const { data } = await db.from("inbox").select("*").eq("to_mobile", mobile).order("created_at", { ascending: false }).limit(100);
      const items = await Promise.all((data ?? []).map(async (m) => {
        let audio_url = null;
        if (m.audio_path) audio_url = (await db.storage.from("uploads").createSignedUrl(m.audio_path, 3600)).data?.signedUrl ?? null;
        return { ...m, audio_url };
      }));
      await db.from("inbox").update({ read_at: new Date().toISOString() }).eq("to_mobile", mobile).is("read_at", null);
      return json({ messages: items });
    }

    // ---- Leader messages ----
    if (action === "messages") {
      const { data: msgs } = await db.from("leader_messages").select("*").lte("published_at", new Date().toISOString())
        .order("published_at", { ascending: false });
      const { data: seen } = await db.from("message_views").select("message_id").eq("mobile", mobile);
      const seenSet = new Set((seen ?? []).map((s) => s.message_id));
      return json({ messages: (msgs ?? []).map((m) => ({ ...m, url: publicUrl("leader", m.path), seen: seenSet.has(m.id) })) });
    }
    if (action === "message_seen") {
      await db.from("message_views").upsert({ mobile, message_id: Number(body.id) });
      return json({ ok: true });
    }

    // ---- Reminder check, called by the phone periodically and on-demand ----
    if (action === "poll") {
      const today = istToday();
      const tasks = await tasksForDate(mobile, today);
      const pending = tasks.filter((t) => t.kind === "upload" && !t.done).length;
      const since = body.since ? String(body.since) : new Date(Date.now() - 24 * 3600 * 1000).toISOString();

      // Find any tasks created for today after 'since'
      const { data: newTasks } = await db.from("tasks")
        .select("id, kind, title, description, video_slots, link_x, link_facebook, link_instagram, link_whatsapp, task_date, created_at")
        .eq("task_date", today)
        .gt("created_at", since)
        .order("created_at", { ascending: false });

      const { data: ann } = await db.from("announcements").select("*").gt("created_at", since).order("created_at");
      const mine = (ann ?? []).filter((a) =>
        (a.ward_no == null || a.ward_no === member.ward_no) &&
        ((!a.district && !a.zone) || sameDistrict(a.district || a.zone, member)) &&
        (!a.only_pending || pending > 0));
      const { data: msg } = await db.from("leader_messages").select("id, title, published_at")
        .gt("published_at", since).lte("published_at", new Date().toISOString()).order("published_at", { ascending: false }).limit(1);
      const { data: newInbox } = await db.from("inbox").select("id, kind, text").eq("to_mobile", mobile).gt("created_at", since).limit(5);
      const s = await settings();
      return json({
        pending,
        new_inbox: newInbox ?? [],
        total: tasks.filter((t) => t.kind === "upload").length,
        new_tasks: (newTasks ?? []).map((t) => ({ id: t.id, title: t.title, kind: t.kind, notify: taskNotice(t) })),
        announcements: mine,
        new_message: msg?.[0] ?? null,
        reminder_hours: s.reminder_hours,
        now: new Date().toISOString(),
        app_update: {
          version_code: Number(s.app_version_code || 1),
          version_name: s.app_version_name || "1.0",
          download_url: s.app_download_url || DOWNLOAD_URL,
          notes: s.app_update_notes || "नया वर्शन उपलब्ध है।",
          mandatory: s.app_update_mandatory === "true",
        },
      });
    }

    return json({ error: "unknown_action" }, 400);
  } catch (e) {
    console.error(e);
    return json({ error: "server_error", detail: String(e) }, 500);
  }
});
