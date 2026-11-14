// INC MCD NETWORK leader API: DPCC President, General Secretary, District Presidents, Assembly Observers.
// POST JSON { action, ...params } with the x-session header from the app's login.
// Deploy with: supabase functions deploy leader --no-verify-jwt
import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-session",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

const PRESIDENT = "माननीय दिल्ली प्रदेश कांग्रेस कमेटी अध्यक्ष देवेन्द्र यादव जी";
const DAY = 86_400_000;
const norm = (v: unknown) => String(v ?? "").trim().toLowerCase();
const clean = (v: unknown, max = 2000) => {
  const t = String(v ?? "").trim().slice(0, max);
  return t || null;
};
const memberDistrict = (m: any) => String(m.district || m.zone || "").trim();
const grade = (s: number) => (s >= 75 ? "A" : s >= 60 ? "B" : s >= 45 ? "C" : "D");
const daysAgo = (iso: string | null) => (iso ? Math.floor((Date.now() - new Date(iso).getTime()) / DAY) : null);

/** Member status from app use: never logged in, active (2 days), slow (7 days), off. */
function status(m: any) {
  if (!m.first_login_at) return "never";
  const d = daysAgo(m.last_active_at ?? m.first_login_at) ?? 99;
  return d <= 2 ? "active" : d <= 7 ? "slow" : "inactive";
}

async function all(table: string, cols: string, q?: (x: any) => any) {
  const out: any[] = [];
  for (let from = 0; ; from += 1000) {
    let query = db.from(table).select(cols).range(from, from + 999);
    if (q) query = q(query);
    const { data, error } = await query;
    if (error) throw error;
    out.push(...(data ?? []));
    if (!data || data.length < 1000) break;
  }
  return out;
}

async function settings() {
  const { data } = await db.from("settings").select("key, value");
  return Object.fromEntries((data ?? []).map((r) => [r.key, r.value]));
}

async function leaderFromSession(token: string | null) {
  if (!token) return null;
  const { data } = await db.from("leader_sessions").select("mobile, leaders(*)").eq("token", token).maybeSingle();
  const l = data?.leaders as any;
  if (!l || !l.active) return null;
  await db.from("leaders").update({ last_active_at: new Date().toISOString() }).eq("mobile", l.mobile);
  return l;
}

/**
 * Everything the leader screens need, scoped to the leader: President and GS see all of Delhi,
 * a District President only their district, an Observer only their assembly.
 */
async function overview(me: any) {
  const [master, members, stats, leaders, recent, reports, ratings] = await Promise.all([
    all("wards", "ward_no, ward_name, assembly, district"),
    all("members", "mobile, name, ward_no, ward_name, assembly, district, zone, first_login_at, last_active_at", (q) => q.eq("active", true)),
    all("member_stats", "mobile, points, videos, photos, shares, full_tasks"),
    all("leaders", "mobile, name, role, district, assembly, first_login_at, last_active_at", (q) => q.eq("active", true)),
    // Uploads and shares in the last 14 days, for week-on-week growth.
    Promise.all([
      all("media", "mobile, created_at", (q) => q.gte("created_at", new Date(Date.now() - 14 * DAY).toISOString())),
      all("share_events", "mobile, created_at", (q) => q.gte("created_at", new Date(Date.now() - 14 * DAY).toISOString())),
    ]).then(([a, b]) => [...a, ...b]),
    all("field_reports", "mobile, created_at", (q) => q.gte("created_at", new Date(Date.now() - 30 * DAY).toISOString())),
    all("member_ratings", "member_mobile, ground, connect, booth"),
  ]);
  const st = new Map(stats.map((s) => [s.mobile, s]));
  const rate = new Map<string, number>();
  for (const r of ratings) rate.set(r.member_mobile, Math.round(((r.ground || 0) + (r.connect || 0) + (r.booth || 0)) / 3 * 10) / 10);
  const thisWeek = new Map<string, number>(), lastWeek = new Map<string, number>();
  for (const e of recent) {
    const map = Date.now() - new Date(e.created_at).getTime() <= 7 * DAY ? thisWeek : lastWeek;
    map.set(e.mobile, (map.get(e.mobile) ?? 0) + 1);
  }

  const mem = members.map((m) => {
    const s = st.get(m.mobile) ?? {};
    return {
      mobile: m.mobile, name: m.name, ward_no: m.ward_no, ward_name: m.ward_name ?? null,
      assembly: String(m.assembly ?? "").trim(), district: memberDistrict(m),
      status: status(m), last_active_days: m.first_login_at ? daysAgo(m.last_active_at ?? m.first_login_at) : null,
      points: Number(s.points ?? 0), videos: Number(s.videos ?? 0), photos: Number(s.photos ?? 0), shares: Number(s.shares ?? 0),
      full_tasks: Number(s.full_tasks ?? 0), week: thisWeek.get(m.mobile) ?? 0, prev_week: lastWeek.get(m.mobile) ?? 0,
      rating: rate.get(m.mobile) ?? null,
    };
  });

  const share = (xs: any[], f: (x: any) => boolean) => (xs.length ? xs.filter(f).length / xs.length : 0);
  const reportsBy = new Map<string, any[]>();
  for (const r of reports) (reportsBy.get(r.mobile) ?? reportsBy.set(r.mobile, []).get(r.mobile)!).push(r);

  // Wards
  const wardMap = new Map<number, any[]>();
  for (const m of mem) (wardMap.get(m.ward_no) ?? wardMap.set(m.ward_no, []).get(m.ward_no)!).push(m);
  const maxAvg = Math.max(1, ...[...wardMap.values()].filter((ms) => ms.length).map((ms) => ms.reduce((a, m) => a + m.points, 0) / ms.length));
  const masterByNo = new Map(master.map((w) => [w.ward_no, w]));
  for (const w of master) if (!wardMap.has(w.ward_no)) wardMap.set(w.ward_no, []);
  const wards = [...wardMap.entries()].map(([no, ms]) => {
    const mw = masterByNo.get(no);
    if (!ms.length) return { no, name: mw?.ward_name ?? null, assembly: mw?.assembly ?? "", district: mw?.district ?? "", total: 0, active: 0, score: 0, level: "none" };
    const active = ms.filter((m) => m.status === "active").length;
    const avg = ms.reduce((a, m) => a + m.points, 0) / ms.length;
    const score = Math.round(100 * (0.6 * (active / ms.length) + 0.4 * Math.min(avg / maxAvg, 1)));
    return {
      no, name: ms[0].ward_name ?? mw?.ward_name ?? null, assembly: ms[0].assembly || (mw?.assembly ?? ""), district: ms[0].district || (mw?.district ?? ""), total: ms.length, active, score,
      level: active === 0 ? "none" : score >= 60 ? "strong" : score >= 40 ? "ok" : "weak",
    };
  }).sort((a, b) => b.score - a.score).map((w, i) => ({ ...w, rank: i + 1 }));

  // Assemblies and their observers
  const asmNames = [...new Set(mem.map((m) => m.assembly).filter(Boolean))];
  const observers = leaders.filter((l) => l.role === "observer");
  const assemblies = asmNames.map((name) => {
    const ms = mem.filter((m) => norm(m.assembly) === norm(name));
    const obs = observers.find((o) => norm(o.assembly) === norm(name)) ?? null;
    const reps = obs ? reportsBy.get(obs.mobile) ?? [] : [];
    const last = reps.length ? Math.min(...reps.map((r) => daysAgo(r.created_at) ?? 99)) : null;
    const activePct = share(ms, (m) => m.status === "active");
    const loginPct = share(ms, (m) => m.status !== "never");
    const reps7 = reps.filter((r) => (daysAgo(r.created_at) ?? 99) <= 7).length;
    const score = Math.round(100 * (0.4 * activePct + 0.25 * Math.min(reps7, 3) / 3 + 0.2 * loginPct + 0.15 * (last != null && last <= 7 ? 1 : 0)));
    const wardsHere = [...new Set(ms.map((m) => m.ward_no))];
    return {
      name, district: ms[0]?.district ?? "", total: ms.length, active: ms.filter((m) => m.status === "active").length,
      wards: wardsHere.length, wards_covered: wardsHere.filter((w) => ms.some((m) => m.ward_no === w && m.status === "active")).length,
      observer: obs ? { name: obs.name, mobile: obs.mobile, logged: !!obs.first_login_at } : null,
      reports_7: reps7, last_report_days: last, score, grade: grade(score),
    };
  }).sort((a, b) => b.score - a.score).map((a, i) => ({ ...a, rank: i + 1 }));

  // Districts and their presidents
  const distNames = [...new Set(mem.map((m) => m.district).filter(Boolean))];
  const dps = leaders.filter((l) => l.role === "dp");
  const districts = distNames.map((name) => {
    const ms = mem.filter((m) => norm(m.district) === norm(name));
    const as = assemblies.filter((a) => norm(a.district) === norm(name));
    const wardsHere = [...new Set(ms.map((m) => m.ward_no))];
    const coverage = wardsHere.length ? wardsHere.filter((w) => ms.some((m) => m.ward_no === w && m.status === "active")).length / wardsHere.length : 0;
    const tw = ms.reduce((a, m) => a + m.week, 0), lw = ms.reduce((a, m) => a + m.prev_week, 0);
    const growth = lw ? Math.round(((tw - lw) / lw) * 100) : tw ? 100 : 0;
    const avgObs = as.length ? as.reduce((a, x) => a + x.score, 0) / as.length : 0;
    const activePct = share(ms, (m) => m.status === "active");
    const score = Math.round(100 * (0.5 * activePct + 0.2 * coverage + 0.2 * avgObs / 100 + 0.1 * Math.max(0, Math.min(1, (growth + 50) / 100))));
    const dp = dps.find((d) => norm(d.district) === norm(name)) ?? null;
    return {
      name, total: ms.length, active: ms.filter((m) => m.status === "active").length, logged: ms.filter((m) => m.status !== "never").length,
      coverage: Math.round(coverage * 100), growth, score, grade: grade(score), assemblies: as.length,
      dp: dp ? { name: dp.name, mobile: dp.mobile, logged: !!dp.first_login_at } : null,
    };
  }).sort((a, b) => b.score - a.score).map((d, i) => ({ ...d, rank: i + 1 }));

  // Delhi totals
  const totals = {
    members: mem.length,
    logged: mem.filter((m) => m.status !== "never").length,
    active_today: mem.filter((m) => m.last_active_days === 0).length,
    active_week: mem.filter((m) => m.status === "active" || m.status === "slow").length,
    uploads_week: mem.reduce((a, m) => a + m.week, 0),
    points: mem.reduce((a, m) => a + m.points, 0),
    wards_none: wards.filter((w) => w.level === "none").length,
    observers_no_report: assemblies.filter((a) => a.observer && (a.last_report_days == null || a.last_report_days >= 7)).length,
  };
  const delhiScore = districts.length ? Math.round(districts.reduce((a, d) => a + d.score * d.total, 0) / Math.max(1, mem.length)) : 0;

  // Scope to the leader
  let scope = { members: mem, wards, assemblies, districts };
  if (me.role === "dp") {
    scope = {
      members: mem.filter((m) => norm(m.district) === norm(me.district)),
      wards: wards.filter((w) => norm(w.district) === norm(me.district)),
      assemblies: assemblies.filter((a) => norm(a.district) === norm(me.district)),
      districts: districts.filter((d) => norm(d.name) === norm(me.district)),
    };
  } else if (me.role === "observer") {
    scope = {
      members: mem.filter((m) => norm(m.assembly) === norm(me.assembly)),
      wards: wards.filter((w) => norm(w.assembly) === norm(me.assembly)),
      assemblies: assemblies.filter((a) => norm(a.name) === norm(me.assembly)),
      districts: districts.filter((d) => norm(d.name) === norm(me.district)),
    };
  }
  const gs = leaders.find((l) => l.role === "gs") ?? null;
  return {
    ...scope,
    all_district_count: districts.length, all_assembly_count: assemblies.length,
    district_ranks: districts.map((d) => ({ name: d.name, rank: d.rank, score: d.score })),
    totals, delhi_score: delhiScore, delhi_grade: grade(delhiScore),
    gs: gs ? { name: gs.name, mobile: gs.mobile } : null,
    suggestions: me.role === "gs" ? suggestions(districts, assemblies, mem) : [],
  };
}

/** People the General Secretary could call today: 5 observers and 5 ward members, each with the reason. Suggestions, not a task. */
function suggestions(_districts: any[], assemblies: any[], mem: any[]) {
  const obs: any[] = [], mems: any[] = [], seen = new Set<string>();
  const add = (list: any[], group: string, mobile: string | undefined, name: string, role: string, why: string) => {
    if (!mobile || seen.has(mobile) || list.length >= 5) return;
    seen.add(mobile); list.push({ mobile, name, role, why, group });
  };
  const withObs = assemblies.filter((a) => a.observer);
  withObs.slice().sort((a, b) => (b.last_report_days ?? 99) - (a.last_report_days ?? 99)).slice(0, 3)
    .forEach((a) => add(obs, "observer", a.observer.mobile, a.observer.name, `ऑब्ज़र्वर, ${a.name}`, a.last_report_days == null ? "अब तक कोई फ़ील्ड रिपोर्ट नहीं" : `${a.last_report_days} दिन से कोई फ़ील्ड रिपोर्ट नहीं`));
  withObs.slice().reverse().forEach((a) => add(obs, "observer", a.observer.mobile, a.observer.name, `ऑब्ज़र्वर, ${a.name}`, `रैंक ${a.rank}/${assemblies.length}, सदस्य ${a.active}/${a.total} एक्टिव`));
  mem.filter((m) => m.points >= 200 && (m.last_active_days ?? 0) >= 4).sort((a, b) => b.points - a.points).slice(0, 2)
    .forEach((m) => add(mems, "member", m.mobile, m.name, `वार्ड ${m.ward_no}`, `पहले ${m.points} अंक, अब ${m.last_active_days} दिन से बंद`));
  mem.filter((m) => m.status === "inactive").forEach((m) => add(mems, "member", m.mobile, m.name, `वार्ड ${m.ward_no}`, `${m.last_active_days} दिन से ऐप नहीं खोला`));
  mem.filter((m) => m.status === "never").forEach((m) => add(mems, "member", m.mobile, m.name, `वार्ड ${m.ward_no}`, "ऐप में अब तक लॉगिन नहीं किया"));
  return [...obs, ...mems];
}

/** Leaders a task at this level and target reaches. */
function audienceOf(t: any, leaders: any[]) {
  return leaders.filter((l) =>
    l.role === t.level &&
    (!t.target_mobile || l.mobile === t.target_mobile) &&
    (!t.target_district || norm(l.district) === norm(t.target_district)) &&
    (!t.target_assembly || norm(l.assembly) === norm(t.target_assembly)));
}

/** Puts an approved broadcast into each recipient's inbox. */
async function deliver(b: any, sender: any) {
  const leaders = await all("leaders", "mobile, role, district, assembly", (q) => q.eq("active", true));
  let to: string[] = [];
  if (b.audience === "member" || b.audience === "all") {
    const ms = await all("members", "mobile, district, zone, assembly", (q) => q.eq("active", true));
    to.push(...ms.filter((m) =>
      (!b.district || norm(memberDistrict(m)) === norm(b.district)) &&
      (!b.assembly || norm(m.assembly) === norm(b.assembly))).map((m) => m.mobile));
  }
  for (const role of ["gs", "dp", "observer"]) {
    if (b.audience === role || b.audience === "all" || (b.audience === "leaders")) {
      to.push(...leaders.filter((l) => l.role === role &&
        (!b.district || norm(l.district) === norm(b.district)) &&
        (!b.assembly || norm(l.assembly) === norm(b.assembly))).map((l) => l.mobile));
    }
  }
  to = [...new Set(to)].filter((m) => m !== sender.mobile);
  const roleName: Record<string, string> = { president: PRESIDENT, gs: "महासचिव", dp: "ज़िला अध्यक्ष", observer: "विधानसभा ऑब्ज़र्वर" };
  const from_name = sender.role === "president" ? PRESIDENT : `${sender.name} (${roleName[sender.role]})`;
  for (let i = 0; i < to.length; i += 500) {
    await db.from("inbox").insert(to.slice(i, i + 500).map((m) => ({
      to_mobile: m, from_name, from_role: sender.role, kind: "broadcast", text: b.text, audio_path: b.audio_path, broadcast_id: b.id,
    })));
  }
  return to.length;
}

// Topic and tone of a message to the President, from keywords. Keep in step with analyse() in admin/src/ToPresident.jsx.
const TOPICS: [string, string[]][] = [
  ["पानी", ["पानी", "water", "जल", "टैंकर", "सीवर", "sewer", "नाली", "drain"]],
  ["सड़क", ["सड़क", "सडक", "road", "गड्ढ", "pothole", "गली", "फुटपाथ"]],
  ["बिजली", ["बिजली", "light", "electric", "बिल", "bill", "लाइट"]],
  ["सफ़ाई", ["कूड़ा", "कचरा", "सफाई", "सफ़ाई", "garbage", "waste", "गंदगी", "dustbin"]],
  ["प्रदूषण", ["प्रदूषण", "pollution", "धुआं", "smog", "हवा", "aqi"]],
  ["स्वास्थ्य", ["अस्पताल", "hospital", "दवा", "डॉक्टर", "doctor", "clinic", "मोहल्ला", "डेंगू", "dengue", "health"]],
  ["शिक्षा", ["स्कूल", "school", "फीस", "fees", "टीचर", "शिक्षा", "education"]],
  ["पार्टी और संगठन", ["पार्टी", "संगठन", "कार्यकर्ता", "ब्लॉक", "अध्यक्ष", "ऑब्ज़र्वर", "observer", "party", "टिकट", "ticket", "चुनाव", "election"]],
  ["ऐप", ["ऐप", "app", "अपलोड", "upload", "अंक", "points", "लॉगिन", "login", "वीडियो", "video"]],
  ["नौकरी और रोज़गार", ["नौकरी", "रोजगार", "रोज़गार", "job", "वेंडर", "रेहड़ी", "दुकान", "महंगाई", "price"]],
];
const NEG = ["समस्या", "परेशान", "नाराज", "नाराज़", "शिकायत", "गुस्सा", "खराब", "ख़राब", "नहीं मिल", "धोखा", "बंद", "problem", "angry", "bad", "issue", "complaint", "not working"];
const POS = ["धन्यवाद", "शुक्रिया", "अच्छा", "बधाई", "साथ हैं", "thanks", "thank", "good", "great", "सुझाव", "suggest"];
const TAG_HI: Record<string, string> = { critical: "बहुत ज़रूरी", concern: "चिंता / समस्या", negative: "नाराज़गी", positive: "अच्छी बात", neutral: "सामान्य", voice: "वॉइस नोट" };
function tagsOf(m: any) {
  const t = String(m.text ?? "").toLowerCase();
  const topics = TOPICS.filter(([, words]) => words.some((w) => t.includes(w.toLowerCase()))).map(([hi]) => hi);
  const neg = NEG.filter((w) => t.includes(w)).length, pos = POS.filter((w) => t.includes(w)).length;
  const tone = m.admin_tag || (!t ? "voice" : neg > pos ? "concern" : pos > neg ? "positive" : "neutral");
  return { tone, tone_label: TAG_HI[tone] ?? tone, topics };
}

const signed = async (path: string | null) =>
  path ? (await db.storage.from("uploads").createSignedUrl(path, 3600)).data?.signedUrl ?? null : null;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  let body: Record<string, any> = {};
  try { body = await req.json(); } catch { return json({ error: "bad_json" }, 400); }
  const action = String(body.action ?? "");

  try {
    const me = await leaderFromSession(req.headers.get("x-session"));
    if (!me) return json({ error: "unauthorized" }, 401);
    const isTop = me.role === "president" || me.role === "gs";

    if (action === "me") {
      const s = await settings();
      const unread = (await db.from("inbox").select("id", { count: "exact", head: true }).eq("to_mobile", me.mobile).is("read_at", null)).count ?? 0;
      return json({
        leader: me, unread_inbox: unread, helpline: s.helpline ?? null,
        app_update: {
          version_code: Number(s.app_version_code || 1), version_name: s.app_version_name || "1.0",
          download_url: s.app_download_url || "https://github.com/aadiliftekhar143-cmd/inc-mcd-network/releases/latest/download/app-release.apk",
          notes: s.app_update_notes || "नया वर्शन उपलब्ध है।", mandatory: s.app_update_mandatory === "true",
        },
      });
    }

    if (action === "overview") return json(await overview(me));

    // ---- Tasks ----
    if (action === "tasks") {
      const leaders = await all("leaders", "mobile, name, role, district, assembly", (q) => q.eq("active", true));
      const done = await all("leader_task_done", "task_id, mobile");
      const doneBy = new Map<number, Set<string>>();
      for (const d of done) (doneBy.get(d.task_id) ?? doneBy.set(d.task_id, new Set()).get(d.task_id)!).add(d.mobile);
      const active = await all("leader_tasks", "*", (q) => q.eq("status", "active").order("created_at", { ascending: false }));
      const withAudio = async (t: any) => ({ ...t, audio_url: await signed(t.audio_path) });
      const mine = await Promise.all(active.filter((t) => t.level === me.role && audienceOf(t, [me]).length)
        .map(async (t) => ({ ...(await withAudio(t)), from: PRESIDENT, done: doneBy.get(t.id)?.has(me.mobile) ?? false })));
      const res: any = { mine };
      // Progress of every task in the leader's reach (President and GS see all; a DP sees the tasks they gave).
      const visible = isTop ? active : me.role === "dp" ? active.filter((t) => t.created_by_mobile === me.mobile) : [];
      res.progress = visible.map((t) => {
        const who = audienceOf(t, leaders);
        const d = doneBy.get(t.id) ?? new Set();
        return {
          id: t.id, title: t.title, level: t.level, due_date: t.due_date, created_at: t.created_at,
          total: who.length, done: who.filter((l) => d.has(l.mobile)).length,
          left: who.filter((l) => !d.has(l.mobile)).map((l) => ({ mobile: l.mobile, name: l.name, district: l.district, assembly: l.assembly })),
        };
      });
      if (me.role === "president") {
        const pending = await all("leader_tasks", "*", (q) => q.eq("status", "pending").order("created_at", { ascending: false }));
        res.pending = await Promise.all(pending.map(withAudio));
      }
      if (me.role !== "president") {
        res.created = await all("leader_tasks", "id, title, level, status, target_district, target_assembly, created_at",
          (q) => q.eq("created_by_mobile", me.mobile).order("created_at", { ascending: false }).limit(50));
      }
      return json(res);
    }

    if (action === "task_done") {
      const id = Number(body.task_id);
      await db.from("leader_task_done").upsert({ task_id: id, mobile: me.mobile });
      return json({ ok: true });
    }

    if (action === "task_create") {
      const title = clean(body.title, 300);
      if (!title) return json({ error: "title_required" }, 400);
      const level = String(body.level ?? "");
      const allowed: Record<string, string[]> = { president: ["gs", "dp"], gs: ["dp", "observer"], dp: ["observer"], observer: ["observer"] };
      if (!(allowed[me.role] ?? []).includes(level)) return json({ error: "not_allowed" }, 403);
      const row: any = {
        level, title, description: clean(body.description), audio_path: clean(body.audio_path, 300),
        due_date: /^\d{4}-\d{2}-\d{2}$/.test(body.due_date ?? "") ? body.due_date : null,
        target_district: clean(body.target_district, 120), target_assembly: clean(body.target_assembly, 120),
        target_mobile: clean(body.target_mobile, 10), created_by: me.role, created_by_mobile: me.mobile,
      };
      // President: goes out at once. GS: waits for the President. DP: straight to own observers. Observer: an idea for the admin.
      if (me.role === "president") row.status = "active";
      else if (me.role === "gs") row.status = "pending";
      else if (me.role === "dp") { row.status = "active"; row.target_district = me.district; }
      else { row.status = "suggested"; row.target_assembly = me.assembly; }
      if (me.role === "dp" && body.suggest) { row.status = "suggested"; }
      const { data, error } = await db.from("leader_tasks").insert(row).select("*").single();
      if (error) throw error;
      return json({ ok: true, task: data });
    }

    if (action === "task_review") {
      if (me.role !== "president") return json({ error: "not_allowed" }, 403);
      const ok = !!body.approve;
      await db.from("leader_tasks").update({ status: ok ? "active" : "rejected", approved_at: ok ? new Date().toISOString() : null })
        .eq("id", Number(body.task_id)).eq("status", "pending");
      return json({ ok: true });
    }

    // ---- Uploads for voice notes, photos and videos ----
    if (action === "upload_url") {
      const kind = ["photo", "video", "audio"].includes(body.kind) ? body.kind : "photo";
      const ext = String(body.ext ?? "bin").replace(/[^a-z0-9]/gi, "").slice(0, 5) || "bin";
      if (body.size && Number(body.size) > 50 * 1024 * 1024) return json({ error: "file_too_large", max_mb: 50 }, 400);
      const path = `leaders/${me.mobile}/${kind}-${Date.now()}-${Math.floor(Math.random() * 1e4)}.${ext}`;
      const { data, error } = await db.storage.from("uploads").createSignedUploadUrl(path);
      if (error) throw error;
      return json({ path, signed_url: data.signedUrl });
    }
    const ownPath = (p: unknown) => typeof p === "string" && p.startsWith(`leaders/${me.mobile}/`) ? p : null;

    // ---- Field reports (Observers write them; leaders above read them) ----
    if (action === "report_submit") {
      const media = (Array.isArray(body.media) ? body.media : []).filter((m: any) => ownPath(m?.path)).map((m: any) => ({ kind: m.kind, path: m.path }));
      const note = clean(body.note), audio = ownPath(body.audio_path);
      if (!note && !audio && !media.length) return json({ error: "empty" }, 400);
      const taskId = body.task_id ? Number(body.task_id) : null;
      await db.from("field_reports").insert({
        mobile: me.mobile, task_id: taskId, note, audio_path: audio, media,
        lat: typeof body.lat === "number" ? body.lat : null, lng: typeof body.lng === "number" ? body.lng : null,
      });
      if (taskId) await db.from("leader_task_done").upsert({ task_id: taskId, mobile: me.mobile });
      return json({ ok: true });
    }
    if (action === "reports") {
      const leaders = await all("leaders", "mobile, name, role, district, assembly");
      const byMobile = new Map(leaders.map((l) => [l.mobile, l]));
      let rows = await all("field_reports", "*", (q) => q.order("created_at", { ascending: false }).limit(200));
      if (me.role === "dp") rows = rows.filter((r) => norm(byMobile.get(r.mobile)?.district) === norm(me.district));
      if (me.role === "observer") rows = rows.filter((r) => r.mobile === me.mobile);
      const limit = Math.min(Number(body.limit) || 50, 100);
      const out = await Promise.all(rows.slice(0, limit).map(async (r) => ({
        ...r, from: byMobile.get(r.mobile) ?? null, audio_url: await signed(r.audio_path),
        media: await Promise.all((r.media ?? []).map(async (m: any) => ({ ...m, url: await signed(m.path) }))),
      })));
      return json({ reports: out });
    }

    // ---- Messages and voice notes down the chain ----
    if (action === "broadcast") {
      const text = clean(body.text), audio = ownPath(body.audio_path);
      if (!text && !audio) return json({ error: "empty" }, 400);
      const allowed: Record<string, string[]> = {
        president: ["all", "gs", "dp", "observer", "member"], gs: ["dp", "observer", "member"],
        dp: ["observer", "member"], observer: ["member"],
      };
      const audience = String(body.audience ?? "");
      if (!(allowed[me.role] ?? []).includes(audience)) return json({ error: "not_allowed" }, 403);
      const row: any = {
        from_mobile: me.mobile, from_role: me.role, audience, text, audio_path: audio,
        district: me.role === "dp" ? me.district : clean(body.district, 120),
        assembly: me.role === "observer" ? me.assembly : clean(body.assembly, 120),
        // District President and Observer messages are checked by the admin first; they are not told.
        status: me.role === "dp" || me.role === "observer" ? "pending" : "approved",
      };
      const { data, error } = await db.from("leader_broadcasts").insert(row).select("*").single();
      if (error) throw error;
      let sent = 0;
      if (data.status === "approved") sent = await deliver(data, me);
      return json({ ok: true, sent });
    }
    if (action === "sent") {
      const rows = await all("leader_broadcasts", "id, audience, text, audio_path, created_at", (q) => q.eq("from_mobile", me.mobile).order("created_at", { ascending: false }).limit(50));
      return json({ sent: await Promise.all(rows.map(async (r) => ({ ...r, audio_url: await signed(r.audio_path) }))) });
    }

    // ---- President's personal message, kudos or motivation to one person (their inbox) ----
    if (action === "personal_message") {
      if (me.role !== "president") return json({ error: "not_allowed" }, 403);
      const to = String(body.to_mobile ?? "").replace(/\D/g, "").slice(-10);
      const text = clean(body.text), audio = ownPath(body.audio_path);
      if (!/^[6-9]\d{9}$/.test(to) || (!text && !audio)) return json({ error: "bad_request" }, 400);
      const kind = ["kudos", "motivate"].includes(body.kind) ? body.kind : "personal";
      await db.from("inbox").insert({ to_mobile: to, from_name: PRESIDENT, from_role: "president", kind, text, audio_path: audio });
      return json({ ok: true });
    }
    if (action === "inbox") {
      const rows = await all("inbox", "*", (q) => q.eq("to_mobile", me.mobile).order("created_at", { ascending: false }).limit(100));
      await db.from("inbox").update({ read_at: new Date().toISOString() }).eq("to_mobile", me.mobile).is("read_at", null);
      return json({ messages: await Promise.all(rows.map(async (r) => ({ ...r, audio_url: await signed(r.audio_path) }))) });
    }

    // ---- Messages people sent to the President (President sees them; Observers can send) ----
    if (action === "to_president") {
      const text = clean(body.text), audio = ownPath(body.audio_path);
      const media = (Array.isArray(body.media) ? body.media : []).filter((m: any) => ownPath(m?.path)).slice(0, 6)
        .map((m: any) => ({ kind: m.kind === "video" ? "video" : "photo", path: m.path }));
      if (!text && !audio && !media.length) return json({ error: "empty" }, 400);
      await db.from("president_messages").insert({
        mobile: me.mobile, sender_role: me.role, sender_name: me.name, assembly: me.assembly, district: me.district, text, audio_path: audio, media,
      });
      return json({ ok: true });
    }
    if (action === "president_inbox") {
      if (me.role !== "president") return json({ error: "not_allowed" }, 403);
      const rows = await all("president_messages", "*", (q) => q.order("created_at", { ascending: false }).limit(100));
      return json({ messages: await Promise.all(rows.map(async (r) => ({
        ...r, ...tagsOf(r), audio_url: await signed(r.audio_path),
        media: await Promise.all((r.media ?? []).map(async (m: any) => ({ ...m, url: await signed(m.path) }))),
      }))) });
    }

    // ---- Calls (dialer opens on the phone; the result is saved here) ----
    if (action === "call_log") {
      await db.from("leader_calls").insert({
        by_mobile: me.mobile, to_mobile: String(body.to_mobile ?? "").slice(-10), to_name: clean(body.to_name, 120),
        to_role: clean(body.to_role, 60), outcome: clean(body.outcome, 60) ?? "कॉल की",
      });
      return json({ ok: true });
    }
    if (action === "my_calls") {
      return json({ calls: await all("leader_calls", "*", (q) => q.eq("by_mobile", me.mobile).order("created_at", { ascending: false }).limit(100)) });
    }

    // ---- Observer rates a member on the ground ----
    if (action === "rate") {
      if (me.role !== "observer") return json({ error: "not_allowed" }, 403);
      const v = (x: unknown) => { const n = Number(x); return n >= 1 && n <= 5 ? n : null; };
      await db.from("member_ratings").upsert({
        observer_mobile: me.mobile, member_mobile: String(body.member_mobile), ground: v(body.ground), connect: v(body.connect),
        booth: v(body.booth), updated_at: new Date().toISOString(),
      });
      return json({ ok: true });
    }
    if (action === "my_ratings") {
      return json({ ratings: await all("member_ratings", "*", (q) => q.eq("observer_mobile", me.mobile)) });
    }

    return json({ error: "unknown_action" }, 400);
  } catch (e) {
    console.error(e);
    return json({ error: "server_error", detail: String(e) }, 500);
  }
});
