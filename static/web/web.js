"use strict";
/* Homebase on a laptop: the same family, map, chat and calls as the Android app. Works offline: it keeps the last
   thing it saw, shows people at their last known spot, and sends your messages when the connection comes back. */
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const store = (k, v) => { try { v === undefined ? localStorage.removeItem(k) : localStorage.setItem(k, typeof v === "string" ? v : JSON.stringify(v)); } catch (e) { /* storage full or blocked */ } };
const load = (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } };
let avaPaint = 0;                                                       // avatar url -> blob url cache lives in S.av
function loadAva(url) {
  if (!url || S.av[url]) return;
  S.av[url] = "loading";
  fetch(url, { headers: { Authorization: "Bearer " + S.token } }).then(r => r.ok ? r.blob() : Promise.reject()).then(b => {
    S.av[url] = URL.createObjectURL(b); clearTimeout(avaPaint); avaPaint = setTimeout(() => { try { renderAll(); } catch (e) {} }, 60);
  }).catch(() => { S.av[url] = "fail"; });
}
function avaInner(m) { const u = m && m.avatar; if (u) { loadAva(u); if (S.av[u] && S.av[u] !== "loading" && S.av[u] !== "fail") return `<img class="avimg" src="${S.av[u]}" alt="">`; } return esc(m ? m.emoji : "🙂"); }
const EMOJI = ["🧒", "👦", "👧", "🧑", "😎", "🦊", "🐱", "🙂", "👩", "👨"];
const PERMS = [
  ["location", "See where I am", "Parents see you on the map. Off means they only see \"not sharing\"."],
  ["trail", "See my trail", "The line of where you have been in the last day."],
  ["battery", "See my battery", "So they know if your device is about to die."],
  ["messages", "Send me messages", "Your check-ins and SOS always reach them, and you can still message them."],
  ["calls", "Call me", "You can always call your parents."],
  ["ping", "Ask \"Where are you?\"", "A request you can answer or decline either way."],
  ["screentime", "See my screen time", "How long you spend playing or watching, and in which apps. Only that, never what you do in them."],
];
const PERM_NAMES = { location: "location", trail: "trail", battery: "battery", messages: "messages", calls: "calls", ping: "where-are-you", screentime: "screen time" };

const S = { av: {}, token: localStorage.getItem("hb_token") || "", me: 0, family: null, members: [], places: [], ice: [], thread: 0, msgs: {}, unread: {}, items: [],
  map: null, markers: {}, circles: [], trail: null, adding: false, cursor: -1, running: false, geo: null, lastPos: null, lastSent: 0, focusedOnce: false,
  online: navigator.onLine, open: 0, chatOpen: false, outbox: load("hb_out", []), savedAt: 0, tmp: -1, plans: [], polls: [], rewards: [], asks: [], bal: {}, board: [], streaks: {}, bonus: {} };

/* ---------- helpers ---------- */
async function api(path, method = "GET", body) {
  let r;
  try { r = await fetch(path, { method, headers: { "Content-Type": "application/json", ...(S.token ? { Authorization: "Bearer " + S.token } : {}) }, body: body ? JSON.stringify(body) : undefined }); }
  catch (e) { setOnline(false); throw Object.assign(new Error("You are offline."), { offline: true }); }
  setOnline(true);
  let d = {}; try { d = await r.json(); } catch (e) { /* empty body */ }
  if (r.status === 401 && S.token) { signOut(); throw Object.assign(new Error("Sign in again."), { status: 401 }); }
  if (!r.ok) throw Object.assign(new Error(d.error || "Something went wrong."), { status: r.status, data: d });
  return d;
}
function toast(t, ms = 3000) { const e = document.createElement("div"); e.className = "toast"; e.textContent = t; $("#toasts").append(e); setTimeout(() => e.remove(), ms); }
function ago(t) { const s = Math.max(0, Date.now() / 1000 - t); if (s < 60) return "just now"; if (s < 3600) return Math.round(s / 60) + " min ago"; if (s < 86400) return Math.round(s / 3600) + " h ago"; return Math.round(s / 86400) + " d ago"; }
function clock(t) { return new Date(t * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); }
const member = id => S.members.find(m => m.id === id);
const me = () => member(S.me) || { id: S.me, name: "", role: "child", perms: {}, share: "off", emoji: "🙂" };
const isParent = () => me().role === "parent";
function may(m, key) { return !(isParent() && m.role === "child" && m.perms && m.perms[key] === false); }
function isLive(m) { return S.online && !!m.location && Date.now() / 1000 - (m.last_seen || 0) < 150; }   // offline, nothing can be called live
function modal(html, onMount) { const o = $("#over"); o.innerHTML = `<div class="modal">${html}</div>`; o.classList.add("on"); o.onclick = e => { if (e.target === o) closeModal(); }; onMount && onMount(o.firstChild); }
function closeModal() { const o = $("#over"); o.classList.remove("on"); o.innerHTML = ""; }
function emojiPicker(list, sel) { return `<div class="emojis" id="emo">${list.map(e => `<button type="button" class="${e === sel ? "on" : ""}" data-e="${e}">${e}</button>`).join("")}</div>`; }
function pickEmoji(root, set) { root.querySelectorAll("#emo button").forEach(b => b.onclick = () => { root.querySelectorAll("#emo button").forEach(x => x.classList.toggle("on", x === b)); set(b.dataset.e); }); }

/* ---------- online / offline ---------- */
function setOnline(v) {
  if (S.online === v) return; S.online = v; paintOffline();
  if (v && S.token) { flushOutbox(); refreshFamily().catch(() => {}); refreshItems(); refreshPlans(); refreshPolls(); refreshRewards(); refreshBoard(); if (S.thread !== undefined) loadThread(S.thread, true); }
}
function paintOffline() {
  const el = $("#offline"); if (!el) return;
  el.classList.toggle("hidden", S.online);
  el.textContent = S.savedAt ? `📡 Offline · showing what was last seen at ${clock(S.savedAt / 1000)}` : "📡 You are offline";
  if ($("#msgIn")) renderComposer();
}
window.addEventListener("online", () => { S.online = false; setOnline(true); }); window.addEventListener("offline", () => setOnline(false));
function saveSnap() { S.savedAt = Date.now(); store("hb_snap", { me: S.me, family: S.family, members: S.members, places: S.places, items: S.items, msgs: S.msgs, at: S.savedAt }); }
function loadSnap() {
  const d = load("hb_snap", null); if (!d || !d.members) return false;
  Object.assign(S, { me: d.me, family: d.family, members: d.members, places: d.places || [], items: d.items || [], msgs: d.msgs || {}, savedAt: d.at || 0 });
  $("#famname").textContent = S.family ? "· " + S.family.name : ""; renderAll(); paintOffline(); return true;
}

/* ---------- theme ---------- */
/* the UI is white by default; anyone who had saved "dark" before is moved to white once, and can pick dark again from the menu */
try { if (!localStorage.getItem("hb_white")) { localStorage.setItem("hb_theme", "light"); localStorage.setItem("hb_map", "light"); localStorage.setItem("hb_white", "1"); } } catch (e) { /* private mode */ }
function applyTheme() { document.documentElement.dataset.theme = localStorage.getItem("hb_theme") || "light"; }
const mapDark = () => (localStorage.getItem("hb_map") || "light") === "dark";
applyTheme();

/* ---------- sign in ---------- */
function showAuth(view, ctx = {}) {
  $("#app").classList.add("hidden"); $("#auth").classList.remove("hidden");
  const box = $("#authbox");
  if (view === "welcome") {
    box.innerHTML = `<div class="logo">🏠</div><h1>Homebase</h1><p class="tag">Stay close. Stay yourself.<br>Your family on a map, with messages and calls. Honest about every bit of it, and you can always pause.</p>
      <div class="stack"><button class="btn solid" id="aCreate">Create a family</button><button class="btn" id="aJoin">I have an invite code</button><a class="link" href="/download" style="text-align:center">Get the Android app</a></div>`;
    $("#aCreate").onclick = () => showAuth("create"); $("#aJoin").onclick = () => showAuth("join");
  } else if (view === "create" || view === "join") {
    let emoji = ctx.emoji || (view === "create" ? "👩" : "🧒");
    box.innerHTML = `<h1>${view === "create" ? "Create your family" : "Join your family"}</h1><p class="tag" style="font-size:15px">${view === "create" ? "You will be a parent. You can invite everyone else after." : "Ask a parent to open Homebase, press Invite, and read you the code."}</p>
      ${view === "create" ? `<label class="l">Family name</label><input class="field" id="fFam" maxlength="40" placeholder="The Vales" value="${esc(ctx.family || "")}">` : `<label class="l">Invite code</label><input class="field" id="fCode" maxlength="12" placeholder="8 letters and numbers" style="letter-spacing:.2em;text-transform:uppercase" value="${esc(ctx.code || "")}">`}
      <label class="l">Your name</label><input class="field" id="fName" maxlength="40" value="${esc(ctx.name || "")}">
      <label class="l">Pick your picture</label>${emojiPicker(EMOJI, emoji)}
      <div class="err" id="aErr"></div>
      <div class="stack" style="margin-top:6px"><button class="btn solid" id="aGo">Continue</button><button class="link" id="aBack">Back</button></div>`;
    pickEmoji(box, e => emoji = e);
    $("#aBack").onclick = () => showAuth("welcome");
    $("#aGo").onclick = async () => {
      const name = $("#fName").value.trim(); if (!name) { $("#aErr").textContent = "Add your name first."; return; }
      try {
        if (view === "create") enter(await api("/api/family", "POST", { family: $("#fFam").value.trim() || "Our family", name, emoji }));
        else { const code = $("#fCode").value.trim(); if (!code) { $("#aErr").textContent = "Enter the invite code."; return; } showAuth("consent", { code, name, emoji, look: await api("/api/invite/" + encodeURIComponent(code)) }); }
      } catch (e) { $("#aErr").textContent = e.message; }
    };
  } else if (view === "consent") {
    const l = ctx.look;
    box.innerHTML = `<h1 style="font-size:28px">${esc(l.invited_by || "Someone")} invited you to ${esc(l.family)}</h1><p class="tag" style="font-size:15px">Read this first. You decide if you want to join.</p>
      <div style="display:grid;gap:10px;margin-top:16px">
      <div class="note yes"><h4>Your family will see</h4><ul><li>✓ Where you are, on a map, while sharing is on</li><li>✓ Your battery level</li><li>✓ Messages and check-ins you send them</li><li>✓ When you arrive at or leave a saved place. You get the same message.</li></ul></div>
      <div class="note no"><h4>Homebase will never</h4><ul><li>✕ Hide itself, or share anything in secret</li><li>✕ Read your other apps, browsing or private messages</li><li>✕ Switch on your microphone or camera by itself</li><li>✕ Lock your device or limit your screen time</li></ul></div>
      <div class="note"><h4>You are in control</h4><ul><li>• Pause sharing whenever you need. The family sees it is paused.</li><li>• "Where are you?" is a request: you can say not now.</li><li>• You choose what parents can do, from the menu.</li><li>• Leave any time and everything you shared is deleted.</li></ul></div></div>
      <div class="err" id="aErr"></div><div class="stack" style="margin-top:6px"><button class="btn solid" id="aYes">I understand, join</button><button class="link" id="aNo">Not now</button></div>`;
    $("#aNo").onclick = () => showAuth("welcome");
    $("#aYes").onclick = async () => { try { enter(await api("/api/join", "POST", { code: ctx.code, name: ctx.name, emoji: ctx.emoji })); } catch (e) { $("#aErr").textContent = e.message; } };
  }
}
function enter(d) { S.token = d.token; S.me = d.me.id; store("hb_token", d.token); history.replaceState(null, "", location.pathname); startApp(true); }
function signOut() { S.running = false; stopGeo(); ["hb_token", "hb_snap", "hb_out", "hb_geo"].forEach(k => store(k)); S.token = ""; location.reload(); }

/* ---------- app ---------- */
S.chores = []; S.chorePts = {}; S.weekPts = {};
async function startApp(fresh) {
  $("#auth").classList.add("hidden"); $("#app").classList.remove("hidden");
  initMap(); initChrome(); paintOffline();
  if (!loadSnap() && !fresh) { /* first run: nothing to show yet */ }
  try { await refreshFamily(); } catch (e) { if (!e.offline) return; }
  loadThread(0); refreshItems(); refreshChores(); refreshPlans(); refreshPolls(); refreshRewards(); refreshBoard(); flushOutbox();
  fetch("/api/config").then(r => r.json()).then(c => { S.latest = c.version_code || 0; renderSide(); }).catch(() => { /* offline: no hint */ });
  const ans = location.hash.match(/answer=(\d+)/); if (ans) { history.replaceState(null, "", location.pathname); setTimeout(() => window.Calls && window.Calls.answerNow(+ans[1]), 600); }
  if (!S.running) { S.running = true; eventLoop(); }
  setInterval(() => { if (!document.hidden && S.token && S.online) refreshFamily().catch(() => {}); }, 12000);
  setInterval(() => { if (S.token && S.online) flushOutbox(); }, 15000);
  if (me().share === "on" && localStorage.getItem("hb_geo") === "1") startGeo();
  if (window.innerWidth >= 1280 && !S.chatOpen) setChat(false);
}
async function refreshFamily() {
  const d = await api("/api/family");
  S.me = d.me; S.family = d.family; S.members = d.members; S.places = d.places; S.ice = d.ice_servers || [];
  $("#famname").textContent = "· " + d.family.name; saveSnap(); renderAll();
}
function renderAll() {
  paintLock();
  const parent = isParent(); $("#bAddPlace").classList.toggle("hidden", !parent); $("#bInvite").classList.toggle("hidden", !parent);
  renderSide(); renderMap(); renderThreads(); renderComposer(); renderMsgs();
}

/* ---------- people (the left panel) ---------- */
function statusLine(m) {
  let w;
  if (m.home) w = "🏠 At home";
  else if (m.share === "paused") w = "Location paused";
  else if (m.share === "off") w = "Not sharing location";
  else if (m.location) w = (isLive(m) ? "Live · " : "Last known · ") + ago(m.location.at);
  else w = "Waiting for a location";
  if (m.battery >= 0 && m.battery <= 100) w += " · 🔋 " + m.battery + "%";
  return w;
}
function offTags(m) { const off = Object.keys(PERM_NAMES).filter(k => m.perms && m.perms[k] === false).map(k => PERM_NAMES[k]); return off.length ? `<div class="offrow"><span class="chip warn">Switched off: ${esc(off.join(", "))}</span></div>` : ""; }
function youCard() {
  const m = me(), on = m.share === "on", paused = m.share === "paused";
  let hint;
  if (paused) hint = "Paused. Your family can see that it is paused.";
  else if (!on) hint = "Off. Nobody can see where you are.";
  else if (!S.geo) hint = "On, but this laptop is not sending its location yet.";
  else if (!S.lastPos) hint = "On. Waiting for your browser to find you…";
  else hint = "Live. Everyone in the family can see you on the map.";
  return `<section class="you"><div class="head"><div class="ava ${on ? "on" : paused ? "paused" : ""}">${avaInner(m)}</div><div><b>${esc(m.name)} (you)</b><br><small>${m.role === "parent" ? "Parent" : "Child"}</small></div></div>
    <div class="shareline"><div><b>Share my location</b><small>${hint}</small></div><button class="tg ${on ? "on" : ""}" id="tShare" aria-label="Share my location"></button></div>
    ${on && !S.geo ? `<button class="btn solid" id="bAllow">Allow this laptop to share</button>` : ""}
    ${notifyState() === "default" ? `<button class="btn" id="bNotify">🔔 Turn on desktop notifications for messages and calls</button>` : ""}
    ${on || paused ? `<div class="quick">Pause for <button class="btn sm" data-pause="15">15 min</button><button class="btn sm" data-pause="60">1 hour</button>${paused ? `<button class="btn sm" data-pause="0">Resume</button>` : ""}</div>` : ""}</section>`;
}
/* ---------- the left panel: five clearly labelled tabs instead of one long scroll ---------- */
const TABS = [["family", "👨‍👩‍👧", "Family"], ["me", "🙂", "Me"]];
const LEGACY_TAB = { people: "family", controls: "family", jobs: "family", places: "family" };
function curTab() { let t = S.tab || localStorage.getItem("hb_tab") || "family"; t = LEGACY_TAB[t] || t; return TABS.some(x => x[0] === t) ? t : "family"; }
function setTab(t) { S.tab = t; try { localStorage.setItem("hb_tab", t); } catch (e) { /* private mode */ } renderSide(); const bd = $("#side .tabbody"); if (bd) bd.scrollTop = 0; }

/* the small collapsible rows under the people (Jobs, Places, Rules): closed by default, remembered */
S.acc = (() => { try { return JSON.parse(localStorage.getItem("hb_acc") || "{}"); } catch (e) { return {}; } })();
function toggleAcc(id) { S.acc[id] = !S.acc[id]; try { localStorage.setItem("hb_acc", JSON.stringify(S.acc)); } catch (e) { /* ignore */ } renderSide(); }
function accordion(id, icon, title, sub, body, alert) {
  const open = !!S.acc[id];
  return `<div class="acc ${open ? "open" : ""}"><button class="acchead" data-acc="${id}" aria-expanded="${open}"><span class="accl"><span class="acci">${icon}</span><b>${title}</b>${alert ? `<i class="tb">${alert}</i>` : ""}</span><small>${sub}</small><span class="accv">▾</span></button>${open ? `<div class="accbody">${body}</div>` : ""}</div>`;
}

function jobsAlert() { return S.chores.filter(c => isParent() ? c.status === "done" : (c.status === "open" && (!c.to || c.to === S.me))).length; }
function tabBadge(t) {
  if (t === "family") { const kids = S.members.filter(m => m.role === "child"); const on = isParent() && kids.some(m => m.lock || (m.blocks || []).length); const n = jobsAlert() + pollAlert() + rewardsAlert(); return n ? `<i class="tb">${n}</i>` : on ? '<i class="tb dot"></i>' : ""; }
  if (t === "me") { const m = me(); return m && m.role === "child" && (m.lock || (m.blocks || []).length) ? '<i class="tb dot"></i>' : ""; }
  return "";
}
function tabsBar() {
  const t = curTab();
  return `<nav class="stabs two" role="tablist" aria-label="Sections">${TABS.map(([k, e, n]) => `<button role="tab" class="${t === k ? "on" : ""}" aria-selected="${t === k}" data-tab="${k}"><span class="ti">${e}</span><span class="tn">${n}</span>${tabBadge(k)}</button>`).join("")}</nav>`;
}

function tile(act, id, emoji, title, sub, off) {
  return `<button class="tile" data-act="${act}" data-id="${id}" ${off ? "disabled" : ""}><span class="te">${emoji}</span><b>${title}</b><small>${sub}</small></button>`;
}
/* a parent's controls for one child: the tiles that show right inside that child's card */
function kidTiles(m) {
  const lock = m.lock ? `Locked until ${clock(m.lock.until)}` : "Lock their devices", gr = (m.blocks || []).length ? `${m.blocks.length} grounded now` : "Block certain apps";
  const dv = m.devices || [], dtxt = dv.length ? dv.map(d => esc(d.name)).join(", ") : "None linked yet";
  const more = !!(S.more && S.more[m.id]);
  return `<div class="tiles">
      ${tile("screen", m.id, "⏱️", "Screen time", screenSub(m), !may(m, "screentime"))}
      ${tile("lock", m.id, "🔒", "Lock device", lock)}
    </div>
    <button class="morebtn" data-act="more" data-id="${m.id}" aria-expanded="${more}">${more ? "Fewer controls ▴" : "More controls ▾  ground apps, rules, computers, this week"}</button>
    ${more ? `<div class="tiles">
      ${tile("ground", m.id, "🚫", "Ground apps", gr)}
      ${tile("rules", m.id, "📋", "Rules and limit", "House rules, daily limit")}
      ${tile("devices", m.id, "💻", "Computers", dtxt)}
      ${tile("week", m.id, "📊", "This week", "Summary")}
    </div>` : ""}`;
}

function peopleHtml() {
  const others = S.members.filter(m => m.id !== S.me);
  if (!S.openSet && isParent()) { const k = others.find(m => m.role === "child"); if (k) S.open = k.id; S.openSet = true; }      // a parent lands with the first child already open, so the controls are right there
  const rows = others.map(m => {
    const open = S.open === m.id, dot = m.share === "on" && m.location ? "on" : m.share === "paused" ? "paused" : "";
    const acts = open ? `<div class="pacts"><button class="btn sm" data-act="msg" data-id="${m.id}" ${may(m, "messages") ? "" : "disabled"}>💬 Message</button><button class="btn sm" data-act="call" data-id="${m.id}" ${may(m, "calls") ? "" : "disabled"}>📞 Call</button><button class="btn sm" data-act="ping" data-id="${m.id}" ${may(m, "ping") ? "" : "disabled"}>📍 Where?</button><button class="btn sm" data-act="trail" data-id="${m.id}" ${may(m, "trail") ? "" : "disabled"}>🧭 Trail</button>${isParent() ? `<button class="btn sm" data-act="updnudge" data-id="${m.id}" title="Sends one message with the download link">📲 Ask to update</button>` : ""}</div>${m.role === "child" && isParent() ? `<div class="ctlwrap">${kidTiles(m)}</div>` : (m.role === "child" ? `<div class="ctlwrap"><p class="tabhelp">${esc(screenLine(m).replace(/<[^>]+>/g, "")) || ""}</p></div>` : "")}` : "";
    const chip = m.lock ? `<span class="chip warn">🔒 until ${clock(m.lock.until)}</span>` : (m.blocks || []).length ? `<span class="chip warn">🚫 ${m.blocks.length} grounded</span>` : `<span class="chip">${m.role === "parent" ? "Parent" : "Child"}</span>`;
    return `<div class="person ${open ? "open" : ""}"><button class="prow" data-act="pick" data-id="${m.id}"><span class="ava ${dot}">${avaInner(m)}</span><span class="pinfo"><b>${esc(m.name)}</b>${statusChip(m)}<small>${esc(statusLine(m))}</small>${screenLine(m)}${deviceLine(m)}${oldAppLine(m)}</span>${chip}</button>${offTags(m)}${acts}</div>`;
  }).join("");
  return rows || `<div class="empty">Nobody else is here yet. ${isParent() ? "Press <b>Invite</b> at the top to add someone." : ""}</div>`;
}

function placesHtml() {
  const hasHome = S.places.some(p => p.name === "Home");
  const places = S.places.map(p => `<div class="place"><span>${esc(p.emoji)} ${esc(p.name)} <small>${p.radius} m</small></span>${isParent() ? `<button class="btn sm" data-act="delplace" data-id="${p.id}">Remove</button>` : ""}</div>`).join("") || `<div class="empty">No saved places yet.${isParent() ? " Press <b>＋ Place</b> under the map." : ""}</div>`;
  return (isParent() ? `<button class="btn ${hasHome ? "" : "solid"}" id="bHome" style="width:100%">🏠 ${hasHome ? "Move the Home area" : "Set the Home area"}</button>` : (hasHome ? "" : '<div class="empty">A parent can set the Home area.</div>')) + places;
}
function jobsHtml() {
  const items = S.items.map(i => `<label class="item ${i.done ? "done" : ""}"><input type="checkbox" data-act="tick" data-id="${i.id}" ${i.done ? "checked" : ""}><span style="flex:1">${esc(i.text)}</span><button class="link" data-act="delitem" data-id="${i.id}" title="Delete" aria-label="Delete">✕</button></label>`).join("");
  const keep = $("#itemIn") && document.activeElement === $("#itemIn") ? $("#itemIn").value : "";
  return `${choresHtml()}<div class="sechead"><h3>Family list</h3></div>${items || '<div class="empty">Nothing on the list.</div>'}${S.items.some(i => i.done) ? '<button class="link" data-act="clearitems" style="text-align:left">Clear ticked items</button>' : ""}
    <form id="itemForm" style="display:flex;gap:8px"><input class="field" id="itemIn" placeholder="Add to the list" maxlength="120" autocomplete="off" value="${esc(keep)}"><button class="btn" type="submit">Add</button></form>`;
}

/* ---------- "what I'm up to", and the Today card ---------- */
const STATUS = [["🏠", "At home"], ["📚", "Studying"], ["🎮", "Gaming"], ["🍽️", "Eating"], ["🚌", "On my way"], ["⚽", "Out playing"], ["😴", "Sleeping"], ["🧘", "Chilling"]];
const statusChip = m => (m.status ? `<span class="stat">${esc(m.status.emoji || "💬")} ${esc(m.status.text || "")}</span>` : "");
function todayHtml() {
  const ms = S.members, live = ms.filter(isLive).length, home = ms.filter(m => m.home).length, st = me().status;
  const bits = [`${ms.length} ${ms.length === 1 ? "person" : "people"}`, live ? `${live} live on the map` : "nobody live right now"]; if (home) bits.push(`${home} at home`);
  const bd = nextBirthday(); if (bd) bits.push(bd);
  const nx = (S.plans || [])[0]; if (nx) bits.push(`next: ${nx.emoji} ${esc(nx.title)}, ${whenTxt(nx.start).toLowerCase()}`);
  return `<section class="today"><div><b>${esc(S.family ? S.family.name : "Your family")}</b><small>${bits.join(" · ")}</small></div>
    <div class="tbtns"><button class="btn sm" data-act="status">${st ? esc(st.emoji || "💬") + " " + esc(st.text || "My status") : "🙂 What I'm up to"}</button><button class="btn sm" data-act="chatall">💬 Message the family</button><button class="btn sm" data-act="fit">📍 Show everyone</button></div></section>`;
}
function statusCardHtml() {
  const st = me().status;
  return `<section class="you"><div class="shareline"><div><b>What I'm up to</b><small>${st ? esc((st.emoji || "") + " " + (st.text || "")) + " · ends " + clock(st.until) : "Tell your family in one tap. It goes away by itself."}</small></div><button class="btn sm" data-act="status">${st ? "Change" : "Set"}</button></div></section>`;
}
async function setStatus(emoji, text, hours) {
  try { await api("/api/status", "POST", { emoji, text, hours }); closeModal(); await refreshFamily(); toast("Your family can see it now"); } catch (e) { toast(e.message); }
}
function statusDialog() {
  const cur = me().status; let hours = 3;
  modal(`<h2>What are you up to?</h2><div class="sub">Your family sees this for a few hours, then it goes away by itself. Only you can change it.</div>
    <div class="sts" style="margin-top:14px">${STATUS.map(([e, t]) => `<button type="button" data-e="${e}" data-t="${esc(t)}" class="${cur && cur.emoji === e && cur.text === t ? "on" : ""}"><i>${e}</i>${esc(t)}</button>`).join("")}</div>
    <label class="l">Or write your own</label><div style="display:flex;gap:8px"><input class="field" id="stIn" maxlength="40" placeholder="For example: at football" autocomplete="off"><button class="btn solid" id="stSet" type="button">Set</button></div>
    <label class="l">For how long</label><div class="seg" id="stHrs">${[[1, "1 hour"], [3, "3 hours"], [24, "All day"]].map(([h, l]) => `<button type="button" class="btn ${h === 3 ? "sel" : ""}" data-h="${h}">${l}</button>`).join("")}</div>
    <div class="foot">${cur ? '<button class="btn bad" id="stClear" type="button">Clear it</button>' : ""}<button class="btn" id="stX" type="button">Close</button></div>`, root => {
    root.querySelectorAll("[data-h]").forEach(b => b.onclick = () => { hours = +b.dataset.h; root.querySelectorAll("[data-h]").forEach(x => x.classList.toggle("sel", x === b)); });
    root.querySelectorAll(".sts [data-e]").forEach(b => b.onclick = () => setStatus(b.dataset.e, b.dataset.t, hours));
    const own = () => { const v = root.querySelector("#stIn").value.trim(); if (v) setStatus("💬", v, hours); };
    root.querySelector("#stSet").onclick = own; root.querySelector("#stIn").onkeydown = e => { if (e.key === "Enter") { e.preventDefault(); own(); } };
    root.querySelector("#stX").onclick = closeModal;
    const cl = root.querySelector("#stClear"); if (cl) cl.onclick = async () => { try { await api("/api/status", "DELETE"); closeModal(); await refreshFamily(); } catch (e) { toast(e.message); } };
  });
}

/* the Family tab: people first (their controls open right inside each card), then three small rows */
/* ---------- rewards: what chore points can buy ---------- */
async function refreshRewards() { try { const d = await api("/api/rewards"); S.rewards = d.rewards; S.asks = d.requests; S.bal = d.balance; S.bonus = d.bonus || {}; if (d.streaks) S.streaks = d.streaks; renderSide(); } catch (e) { /* keep what we have */ } }
const myBal = () => (S.bal || {})[S.me] || 0;
const rewardsAlert = () => (isParent() ? (S.asks || []).filter(a => a.status === "pending").length : 0);
function rewardsHtml() {
  const parent = isParent(), kids = S.members.filter(m => m.role === "child"), item = (a, mid, side) => `<div class="item"><span class="stemo">${esc(a.emoji)}</span><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${esc(a.title)}</b><small>${mid}</small></span>${side}</div>`;
  const bal = `<div class="board">${parent ? kids.map(k => `<span class="chip">${esc(k.emoji)} ${esc(k.name)} · ${(S.bal || {})[k.id] || 0} points${S.streaks[k.id] ? " · 🔥 " + S.streaks[k.id] : ""}${S.bonus[k.id] ? " · ⏱️ +" + S.bonus[k.id] + " min today" : ""}</span>`).join("") : `<span class="chip">⭐ You have ${myBal()} points</span>${S.streaks[S.me] ? `<span class="chip">🔥 ${S.streaks[S.me]} ${S.streaks[S.me] === 1 ? "day" : "days"} in a row</span>` : ""}${S.bonus[S.me] ? `<span class="chip">⏱️ +${S.bonus[S.me]} min screen time today</span>` : ""}`}</div>`;
  const pend = (S.asks || []).filter(a => a.status === "pending").map(a => parent
    ? item(a, `${esc((member(a.member) || {}).name || "Someone")} · ${a.cost} points`, `<button class="btn sm" data-rw="no" data-id="${a.id}">Not now</button><button class="btn sm solid" data-rw="yes" data-id="${a.id}">Yes</button>`)
    : item(a, `Waiting for a parent · ${a.cost} points held`, `<button class="btn sm" data-rw="cancel" data-id="${a.id}">Take back</button>`)).join("");
  const rows = (S.rewards || []).map(r => item(r, `${r.cost} points${r.minutes ? " · +" + r.minutes + " min screen time" : ""}`, parent ? `<button class="link" data-rw="del" data-id="${r.id}" title="Delete" aria-label="Delete reward">✕</button>`
    : `<button class="btn sm ${myBal() >= r.cost ? "solid" : ""}" data-rw="ask" data-id="${r.id}" ${myBal() >= r.cost ? "" : "disabled"}>${myBal() >= r.cost ? "Ask for it" : "Need " + (r.cost - myBal())}</button>`)).join("");
  const past = (S.asks || []).filter(a => a.status !== "pending").slice(0, 4).map(a => `<div class="item done"><span class="stemo">${esc(a.emoji)}</span><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${esc(a.title)}</b><small>${a.status === "approved" ? "✓ Yes" : "Not now"} · ${esc((member(a.member) || {}).name || "")}</small></span></div>`).join("");
  const form = parent ? `<form id="rewardForm" class="choreform"><input class="field" name="title" placeholder="Add a reward, e.g. Pick the film" maxlength="60" autocomplete="off"><div class="choreopts"><input class="field" name="cost" type="number" min="1" max="1000" value="20" aria-label="Points it costs"><input class="field" name="minutes" type="number" min="0" max="240" placeholder="+ min" aria-label="Extra screen time in minutes (optional)" title="Extra screen time in minutes (optional). If a daily limit is set, a yes adds these minutes to today."><button class="btn" type="submit">Add</button></div></form>` : "";
  return bal + (pend ? `<div class="sechead"><h3>${parent ? "Waiting for you" : "Your requests"}</h3></div>${pend}` : "") + `<div class="sechead"><h3>Rewards</h3></div>`
    + (rows || `<div class="empty">${parent ? "Add something points can buy: a film pick, extra game time, an ice cream." : "No rewards yet. Ask a parent to add some."}</div>`) + (past ? `<div class="sechead"><h3>Recently</h3></div>${past}` : "") + form;
}
async function rewardAct(act, id) {
  try {
    if (act === "ask") { await api(`/api/rewards/${id}/redeem`, "POST"); toast("Asked. A parent will answer."); }
    else if (act === "yes" || act === "no") await api(`/api/redemptions/${id}/decide`, "POST", { ok: act === "yes" });
    else if (act === "cancel") await api(`/api/redemptions/${id}`, "DELETE");
    else if (act === "del") { if (!confirm("Delete this reward?")) return; await api(`/api/rewards/${id}`, "DELETE"); }
    refreshRewards();
  } catch (e) { toast(e.message); refreshRewards(); }
}

/* ---------- the family board: Wi-Fi, the doctor, allergies, who to call ---------- */
const BOARD_EMOJI = ["📌", "📶", "🩺", "💊", "☎️", "🏠", "🔑", "🚗"];
async function refreshBoard() { try { S.board = (await api("/api/board")).items; renderSide(); } catch (e) { /* keep what we have */ } }
function boardHtml() {
  const parent = isParent();
  const rows = (S.board || []).map(n => `<div class="note"><div class="pl-top"><span class="pl-e">${esc(n.emoji)}</span><div class="pl-m"><b>${esc(n.title)}</b></div>${parent ? `<button class="link" data-bd="edit" data-id="${n.id}">Edit</button>` : ""}</div><div class="notebody">${esc(n.body)}</div></div>`).join("");
  return (rows || `<div class="empty">${parent ? "Keep what everyone needs in one place: the Wi-Fi, the doctor, allergies, who to call." : "Nothing here yet."}</div>`)
    + (parent ? `<button class="btn solid" data-bd="add" style="width:100%">＋ Add a note</button><p class="tabhelp">Everyone in the family can read these. Keep bank details and PINs out.</p>` : "");
}
function boardDialog(n) {
  let emoji = n ? n.emoji : "📌";
  modal(`<h2>${n ? "Edit the note" : "Add a note"}</h2><div class="sub">Everyone in the family can read it.</div>
    <label class="l">Title</label><input class="field" id="bdT" maxlength="40" placeholder="For example: Wi-Fi" autocomplete="off" value="${esc(n ? n.title : "")}">
    <label class="l">Picture</label>${emojiPicker(BOARD_EMOJI, emoji)}
    <label class="l">What it says</label><textarea class="field" id="bdB" maxlength="600" rows="5" placeholder="Name: ...&#10;Password: ...">${esc(n ? n.body : "")}</textarea>
    <div class="foot">${n ? '<button class="btn bad" id="bdDel" type="button">Delete</button>' : ""}<button class="btn" id="bdX" type="button">Cancel</button><button class="btn solid" id="bdOk" type="button">Save</button></div>`, root => {
    pickEmoji(root, e => { emoji = e; });
    root.querySelector("#bdX").onclick = closeModal; root.querySelector("#bdT").focus();
    root.querySelector("#bdOk").onclick = async () => {
      const title = root.querySelector("#bdT").value.trim(); if (!title) return toast("Give the note a title");
      try { await api("/api/board", "POST", { id: n ? n.id : undefined, title, emoji, body: root.querySelector("#bdB").value }); closeModal(); toast("Saved"); refreshBoard(); } catch (e) { toast(e.message); }
    };
    const del = root.querySelector("#bdDel"); if (del) del.onclick = async () => { if (!confirm("Delete this note?")) return; try { await api("/api/board/" + n.id, "DELETE"); closeModal(); refreshBoard(); } catch (e) { toast(e.message); } };
  });
}

/* ---------- birthdays: you choose the date, your family hears about it on the day ---------- */
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const bdayText = md => { const [m, d] = String(md).split("-").map(Number); return d + " " + MONTHS[m - 1]; };
function daysToBday(md) {
  const [m, d] = md.split("-").map(Number), now = new Date(), today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const leap = y => (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0, on = y => (m === 2 && d === 29 && !leap(y) ? new Date(y, 1, 28) : new Date(y, m - 1, d));
  let next = on(today.getFullYear()); if (next < today) next = on(today.getFullYear() + 1);
  return Math.round((next - today) / 86400000);
}
function nextBirthday() {
  const soon = S.members.filter(m => m.birthday).map(m => [m, daysToBday(m.birthday)]).filter(x => x[1] <= 30).sort((a, b) => a[1] - b[1])[0];
  if (!soon) return "";
  const [m, n] = soon; return n === 0 ? `🎂 it's ${esc(m.name)}'s birthday today!` : `🎂 ${esc(m.name)}'s birthday is ${n === 1 ? "tomorrow" : "in " + n + " days"}`;
}
function birthdayCardHtml() {
  const b = me().birthday;
  return `<section class="you"><div class="shareline"><div><b>🎂 My birthday</b><small>${b ? esc(bdayText(b)) + ". Your family hears about it on the day." : "Pick the day and your family hears about it. It is your choice."}</small></div><button class="btn sm" data-act="birthday">${b ? "Change" : "Set"}</button></div></section>`;
}
function birthdayDialog() {
  const cur = me().birthday ? me().birthday.split("-").map(Number) : [new Date().getMonth() + 1, new Date().getDate()];
  modal(`<h2>🎂 My birthday</h2><div class="sub">Your family sees it and hears about it on the day. You choose it, and you can remove it any time.</div>
    <label class="l">Month and day</label><div style="display:flex;gap:8px"><select class="field" id="bdM">${MONTHS.map((n, i) => `<option value="${i + 1}" ${cur[0] === i + 1 ? "selected" : ""}>${n}</option>`).join("")}</select><select class="field" id="bdD" style="max-width:96px">${Array.from({ length: 31 }, (_, i) => `<option value="${i + 1}" ${cur[1] === i + 1 ? "selected" : ""}>${i + 1}</option>`).join("")}</select></div>
    <div class="foot">${me().birthday ? '<button class="btn bad" id="bdRm" type="button">Remove it</button>' : ""}<button class="btn" id="bdNo" type="button">Cancel</button><button class="btn solid" id="bdSave" type="button">Save</button></div>`, root => {
    const save = async (month, day) => { try { await api("/api/birthday", "POST", { month, day }); closeModal(); await refreshFamily(); toast(month ? "Saved" : "Removed"); } catch (e) { toast(e.message); } };
    root.querySelector("#bdNo").onclick = closeModal; root.querySelector("#bdSave").onclick = () => save(+root.querySelector("#bdM").value, +root.querySelector("#bdD").value);
    const rm = root.querySelector("#bdRm"); if (rm) rm.onclick = () => save(0, 0);
  });
}

/* ---------- "can someone pick me up?" ---------- */
async function pickupNow() {
  if (!S.lastPos && navigator.geolocation) { try { const p = await new Promise((ok, no) => navigator.geolocation.getCurrentPosition(ok, no, { timeout: 4000 })); S.lastPos = { lat: p.coords.latitude, lng: p.coords.longitude }; } catch (e) { /* send without a place */ } }
  try { await sendMsg("pickup", "Can someone pick me up?", where(), 0); if (S.online) toast("Sent to the family 🚗"); } catch (e) { toast(e.message); }
}
function pickupCardHtml() {
  return `<section class="you"><div class="shareline"><div><b>🚗 Need a pickup?</b><small>Tell the family. If you are sharing your location they see where you are.</small></div><button class="btn sm" data-act="pickup">Ask</button></div></section>`;
}

/* ---------- plans: the family calendar ---------- */
const PLAN_EMOJI = ["📅", "🎂", "⚽", "🍽️", "🎬", "🏥", "✈️", "📚", "🛒", "🎉"];
function whenTxt(t) {
  const d = new Date(t * 1000), now = new Date(), day0 = x => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime(), diff = Math.round((day0(d) - day0(now)) / 86400000);
  if (t * 1000 < Date.now()) return "Happening now";
  if (diff === 0) return "Today · " + clock(t);
  if (diff === 1) return "Tomorrow · " + clock(t);
  if (diff > 1 && diff < 7) return d.toLocaleDateString([], { weekday: "long" }) + " · " + clock(t);
  return d.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" }) + " · " + clock(t);
}
const nameOf = id => (id === S.me ? "You" : (member(id) || {}).name || "Someone");
function planHtml(p) {
  const going = p.in.map(nameOf).join(", "), mine = p.mine, canDel = p.by === S.me || isParent();
  return `<div class="plan ${p.start * 1000 < Date.now() ? "now" : ""}"><div class="pl-top"><span class="pl-e">${esc(p.emoji)}</span><div class="pl-m"><b>${esc(p.title)}</b><small>${whenTxt(p.start)}${p.note ? " · " + esc(p.note) : ""}</small></div>${canDel ? `<button class="link" data-act="delplan" data-id="${p.id}" title="Delete" aria-label="Delete this plan">✕</button>` : ""}</div>
    <div class="pl-who">${going ? "👍 " + esc(going) : "Nobody has said yes yet"}${p.out.length ? ` · can't: ${esc(p.out.map(nameOf).join(", "))}` : ""}</div>
    <div class="pl-btns"><button class="btn sm ${mine === "in" ? "sel" : ""}" data-act="rsvp" data-id="${p.id}" data-a="${mine === "in" ? "" : "in"}">${mine === "in" ? "✓ I'm in" : "I'm in"}</button><button class="btn sm ${mine === "out" ? "sel" : ""}" data-act="rsvp" data-id="${p.id}" data-a="${mine === "out" ? "" : "out"}">${mine === "out" ? "✓ Can't" : "Can't"}</button></div></div>`;
}
function plansHtml() {
  const list = S.plans.slice(0, 5);
  return `<div class="sechead"><h3>📅 Coming up</h3><span class="secbtns"><button class="btn sm" data-act="calendar">🗓️ Month</button><button class="btn sm" data-act="addplan">＋ Plan</button></span></div>${list.map(planHtml).join("") || '<div class="empty">Nothing planned yet. Add a dinner, a match or a trip so everyone knows.</div>'}${S.plans.length > 5 ? `<p class="tabhelp">${S.plans.length - 5} more after these.</p>` : ""}`;
}
async function refreshPlans() { try { S.plans = (await api("/api/plans")).plans; renderSide(); } catch (e) { /* keep the last list */ } }
async function rsvp(id, answer) { try { const d = await api(`/api/plans/${id}/rsvp`, "POST", { answer }); S.plans = S.plans.map(p => p.id === id ? d.plan : p); renderSide(); } catch (e) { toast(e.message); } }
function planDialog(day) {
  const t = new Date(Date.now() + 3600e3), p2 = n => String(n).padStart(2, "0");
  const dflt = day || `${t.getFullYear()}-${p2(t.getMonth() + 1)}-${p2(t.getDate())}`, tm = day ? "18:00" : `${p2(t.getHours())}:00`;
  let emoji = "📅";
  modal(`<h2>Add a plan</h2><div class="sub">Everyone in the family sees it and can say if they are coming.</div>
    <label class="l">What is it?</label><input class="field" id="plT" maxlength="60" placeholder="For example: Dinner at Grandma's" autocomplete="off">
    <label class="l">Pick a picture</label>${emojiPicker(PLAN_EMOJI, emoji)}
    <label class="l">When</label><div style="display:flex;gap:8px"><input class="field" type="date" id="plD" value="${dflt}"><input class="field" type="time" id="plH" value="${tm}"></div>
    <label class="l">A note (optional)</label><input class="field" id="plN" maxlength="120" placeholder="Bring a jacket" autocomplete="off">
    <div class="foot"><button class="btn" id="plX" type="button">Cancel</button><button class="btn solid" id="plOk" type="button">Add it</button></div>`, root => {
    pickEmoji(root, e => { emoji = e; });
    root.querySelector("#plX").onclick = closeModal; root.querySelector("#plT").focus();
    root.querySelector("#plOk").onclick = async () => {
      const title = root.querySelector("#plT").value.trim(), when = new Date(root.querySelector("#plD").value + "T" + (root.querySelector("#plH").value || "09:00"));
      if (!title) return toast("Give the plan a name first");
      if (isNaN(when)) return toast("Pick a day and a time");
      try { await api("/api/plans", "POST", { title, emoji, start: when.getTime() / 1000, note: root.querySelector("#plN").value.trim() }); closeModal(); toast("Plan added"); refreshPlans(); } catch (e) { toast(e.message); }
    };
  });
}

/* ---------- the family calendar: a month at a glance, past and future ---------- */
const DOW = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"], pad2 = n => String(n).padStart(2, "0");
async function openCalendar(y, m, sel) {
  const today = new Date(); y = y ?? today.getFullYear(); m = m ?? today.getMonth();
  if (m < 0) { m = 11; y--; } if (m > 11) { m = 0; y++; }
  const from = new Date(y, m, 1).getTime() / 1000, to = new Date(y, m + 1, 1).getTime() / 1000;
  let plans = []; try { plans = (await api(`/api/plans?from=${from}&to=${to}`)).plans; } catch (e) { toast(e.message); }
  const days = new Date(y, m + 1, 0).getDate(), lead = (new Date(y, m, 1).getDay() + 6) % 7, isNow = today.getFullYear() === y && today.getMonth() === m;
  if (sel == null) sel = isNow ? today.getDate() : 1;
  const byDay = {}, bdays = {};
  plans.forEach(p => { const d = new Date(p.start * 1000).getDate(); (byDay[d] = byDay[d] || []).push(p); });
  S.members.forEach(mm => { if (!mm.birthday) return; let [bm, bd] = mm.birthday.split("-").map(Number); if (bm !== m + 1) return; if (bd > days) bd = days; (bdays[bd] = bdays[bd] || []).push(mm); });
  const cells = Array.from({ length: lead }, () => '<span class="cd off"></span>').concat(Array.from({ length: days }, (_, i) => {
    const d = i + 1, n = (byDay[d] || []).length, b = (bdays[d] || []).length;
    return `<button type="button" class="cd ${isNow && d === today.getDate() ? "today" : ""} ${d === sel ? "sel" : ""}" data-d="${d}" aria-label="${d} ${MONTHS[m]}${n ? ", " + n + (n === 1 ? " plan" : " plans") : ""}"><b>${d}</b><i>${b ? "🎂" : ""}${n ? "●".repeat(Math.min(n, 3)) : ""}</i></button>`;
  })).join("");
  const list = (byDay[sel] || []).map(p => `<div class="plan"><div class="pl-top"><span class="pl-e">${esc(p.emoji)}</span><div class="pl-m"><b>${esc(p.title)}</b><small>${clock(p.start)}${p.note ? " · " + esc(p.note) : ""}</small></div></div><div class="pl-who">${p.in.length ? "👍 " + esc(p.in.map(nameOf).join(", ")) : "Nobody has said yes yet"}${p.out.length ? ` · can't: ${esc(p.out.map(nameOf).join(", "))}` : ""}</div><div class="pl-btns"><button class="btn sm ${p.mine === "in" ? "sel" : ""}" data-cr="in" data-id="${p.id}">${p.mine === "in" ? "✓ I'm in" : "I'm in"}</button><button class="btn sm ${p.mine === "out" ? "sel" : ""}" data-cr="out" data-id="${p.id}">${p.mine === "out" ? "✓ Can't" : "Can't"}</button></div></div>`).join("")
    + (bdays[sel] || []).map(mm => `<div class="plan"><div class="pl-top"><span class="pl-e">🎂</span><div class="pl-m"><b>${esc(mm.name)}'s birthday</b></div></div></div>`).join("");
  modal(`<div class="calhead"><button type="button" class="btn sm" data-nav="-1" aria-label="Previous month">‹</button><h2>${MONTHS[m]} ${y}</h2><button type="button" class="btn sm" data-nav="1" aria-label="Next month">›</button></div>
    <div class="cal">${DOW.map(d => `<span class="cw">${d}</span>`).join("")}${cells}</div>
    <div class="callist"><div class="msec">${sel} ${MONTHS[m]}</div>${list || '<div class="empty">Nothing on this day.</div>'}</div>
    <button type="button" class="link" id="calIcs" style="margin-top:8px;text-align:left" title="Downloads a file that Google, Apple and Outlook calendar understand">⬇ Add the family calendar to Google, Apple or Outlook</button>
    <div class="foot"><button class="btn" id="calX" type="button">Close</button><button class="btn solid" id="calAdd" type="button">＋ Plan on this day</button></div>`, root => {
    root.querySelector("#calX").onclick = closeModal;
    root.querySelector("#calIcs").onclick = async () => {
      try {
        const r = await fetch("/api/calendar.ics", { headers: { Authorization: "Bearer " + S.token } });
        if (!r.ok) throw new Error("Could not make the calendar file.");
        const url = URL.createObjectURL(await r.blob()), a = document.createElement("a"); a.href = url; a.download = "homebase-family.ics"; document.body.append(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 5000); toast("Saved. Open the file to add it to your calendar.", 5000);
      } catch (e) { toast(e.message); }
    };
    root.querySelectorAll("[data-nav]").forEach(b => b.onclick = () => openCalendar(y, m + +b.dataset.nav));
    root.querySelectorAll("[data-d]").forEach(b => b.onclick = () => openCalendar(y, m, +b.dataset.d));
    root.querySelectorAll("[data-cr]").forEach(b => b.onclick = async () => { const id = +b.dataset.id, was = plans.find(p => p.id === id); try { await api(`/api/plans/${id}/rsvp`, "POST", { answer: was && was.mine === b.dataset.cr ? "" : b.dataset.cr }); refreshPlans(); openCalendar(y, m, sel); } catch (e) { toast(e.message); } });
    root.querySelector("#calAdd").onclick = () => { closeModal(); planDialog(`${y}-${pad2(m + 1)}-${pad2(sel)}`); };
  });
}

/* ---------- "please update Homebase": one message with the link (a parent can send one every half hour, never a pile of pop-ups) ---------- */
async function askUpdate(id) { const m = member(id); try { await api(`/api/members/${id}/update-nudge`, "POST"); toast(`Asked ${m ? m.name : "them"} to update`); } catch (e) { toast(e.message); } }
const oldAppLine = m => (isParent() && m.id !== S.me && m.app_version && S.latest && m.app_version < S.latest ? '<small class="oldapp">📲 Android app is out of date</small>' : "");

/* ---------- polls: a question for the family ---------- */
const pollAlert = () => S.polls.filter(p => !p.closed && p.mine === null).length;
function pollHtml(p) {
  const total = p.votes.reduce((a, v) => a + v.length, 0), top = Math.max(0, ...p.votes.map(v => v.length)), canMod = p.by === S.me || isParent();
  const opts = p.options.map((o, i) => {
    const n = p.votes[i].length, pc = total ? Math.round(100 * n / total) : 0, who = p.votes[i].map(nameOf).join(", ");
    return `<button type="button" class="opt ${p.mine === i ? "mine" : ""} ${p.closed && n === top && n > 0 ? "win" : ""}" data-act="vote" data-id="${p.id}" data-o="${i}" ${p.closed ? "disabled" : ""}><i style="width:${pc}%"></i><span class="ot">${esc(o)}</span><span class="on2">${n ? n + (who ? " · " + esc(who) : "") : ""}</span></button>`;
  }).join("");
  return `<div class="poll ${p.closed ? "closed" : ""}"><div class="pl-top"><div class="pl-m"><b>${esc(p.question)}</b><small>${nameOf(p.by) === "You" ? "You asked" : esc(nameOf(p.by)) + " asked"} · ${total} ${total === 1 ? "vote" : "votes"}${p.closed ? " · closed" : ""}</small></div>${canMod ? `<button class="link" data-act="delpoll" data-id="${p.id}" title="Delete" aria-label="Delete this vote">✕</button>` : ""}</div>${opts}
    ${canMod && !p.closed ? `<div class="pl-btns"><button class="btn sm" data-act="closepoll" data-id="${p.id}">Close the vote</button></div>` : ""}</div>`;
}
function pollsHtml() {
  return `${S.polls.map(pollHtml).join("") || '<div class="empty">Ask the family something: pizza or tacos, which film, what time.</div>'}<button class="btn solid" data-act="addpoll" style="width:100%">＋ Ask the family</button>`;
}
async function refreshPolls() { try { S.polls = (await api("/api/polls")).polls; renderSide(); } catch (e) { /* keep the last list */ } }
async function vote(id, option) { try { const d = await api(`/api/polls/${id}/vote`, "POST", { option }); S.polls = S.polls.map(p => p.id === id ? d.poll : p); renderSide(); } catch (e) { toast(e.message); } }
function pollDialog() {
  modal(`<h2>Ask the family</h2><div class="sub">Everyone gets one vote. You can close it when you have an answer.</div>
    <label class="l">Your question</label><input class="field" id="poQ" maxlength="100" placeholder="For example: What's for dinner?" autocomplete="off">
    <label class="l">Answers (two to four)</label>${[1, 2, 3, 4].map(i => `<input class="field poo" maxlength="40" placeholder="Answer ${i}${i > 2 ? " (optional)" : ""}" autocomplete="off" style="margin-bottom:6px">`).join("")}
    <div class="foot"><button class="btn" id="poX" type="button">Cancel</button><button class="btn solid" id="poOk" type="button">Ask</button></div>`, root => {
    root.querySelector("#poX").onclick = closeModal; root.querySelector("#poQ").focus();
    root.querySelector("#poOk").onclick = async () => {
      const question = root.querySelector("#poQ").value.trim(), options = [...root.querySelectorAll(".poo")].map(i => i.value.trim()).filter(Boolean);
      if (!question) return toast("Write the question first");
      if (options.length < 2) return toast("Give at least two answers");
      try { await api("/api/polls", "POST", { question, options }); closeModal(); toast("Asked"); S.acc.polls = true; refreshPolls(); } catch (e) { toast(e.message); }
    };
  });
}

function familyHtml() {
  const n = jobsAlert(), hasHome = S.places.some(p => p.name === "Home"), pa = pollAlert(), ra = rewardsAlert();
  return todayHtml() + plansHtml() + peopleHtml()
    + (isParent() ? `<button class="btn" data-act="invite">＋ Invite someone</button>` : "")
    + accordion("rewards", "🎁", "Rewards", isParent() ? (ra ? ra + " to answer" : "What points can buy") : myBal() + " points to spend", rewardsHtml(), ra)
    + accordion("board", "📌", "Family board", (S.board || []).length ? S.board.length + (S.board.length === 1 ? " note" : " notes") : "Wi-Fi, the doctor, who to call", boardHtml())
    + accordion("polls", "🗳️", "Family votes", pa ? pa + " waiting for your vote" : (S.polls.length ? S.polls.length + " asked" : "Ask the family anything"), pollsHtml(), pa)
    + accordion("jobs", "✅", "Jobs and list", n ? (isParent() ? n + " to approve" : n + " to do") : "Points and the family list", jobsHtml(), n)
    + accordion("places", "📍", "Places and Home", hasHome ? S.places.length + " saved" : "Set the Home area", placesHtml())
    + accordion("rules", "📋", "Family rules", "What the family agreed", `<p class="tabhelp">${isParent() ? "Write the house rules and set each child's daily limit." : "Read what your family agreed."}</p><button class="btn solid" data-act="rules" data-id="0">${isParent() ? "Edit the rules" : "Read the rules"}</button>`);
}

/* the Me tab: your own switches, then (for a child) everything about your own screen time in one place */
function myControlsHtml() {
  const m = me(); if (!m || m.role !== "child") return "";
  const bl = m.blocks || [];
  return `<div class="sechead"><h3>My screen time and controls</h3></div>
    <div class="tiles">
      ${tile("screen", m.id, "⏱️", "My screen time", screenSub(m))}
      ${tile("myperms", m.id, "🎚️", "What parents can do", "You choose")}
      ${tile("rules", m.id, "📋", "Family rules", "What you agreed")}
      ${tile("devices", m.id, "💻", "My computers", (m.devices || []).length ? (m.devices || []).map(d => esc(d.name)).join(", ") : "Link a Windows computer")}
    </div>
    ${m.lock ? `<div class="note-card warn">🔒 Locked by ${esc(m.lock.by_name)} until ${clock(m.lock.until)}</div>` : ""}
    ${bl.length ? `<div class="note-card warn"><b>🚫 Grounded apps</b><br>${bl.map(b => esc(b.label) + " until " + clock(b.until)).join("<br>")}</div>` : ""}`;
}

function renderSide() {
  const t = curTab(), keep = $("#itemIn") && document.activeElement === $("#itemIn") ? $("#itemIn").value : "";
  const body = t === "me" ? youCard() + statusCardHtml() + pickupCardHtml() + birthdayCardHtml() + myControlsHtml() : familyHtml();
  const scroll = $("#side .tabbody") ? $("#side .tabbody").scrollTop : 0;
  $("#side").innerHTML = tabsBar() + `<div class="tabbody">${body}</div>`;
  const bd = $("#side .tabbody"); if (bd) bd.scrollTop = scroll;
  if (keep) { const i = $("#itemIn"); if (i) { i.focus(); i.setSelectionRange(keep.length, keep.length); } }
}

/* ---------- map ---------- */
function initMap() {
  if (S.map) return;
  S.map = L.map("map", { zoomControl: false, attributionControl: true }).setView([24, 20], 2);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(S.map);
  S.map.on("click", e => { if (S.homing) { S.homing = false; saveHome(e.latlng.lat, e.latlng.lng, S.homeRadius || 200); return; } if (S.adding) addPlaceDialog(e.latlng); });
  new ResizeObserver(() => S.map.invalidateSize()).observe($("#map")); paintLayer();
}
const paintLayer = () => $("#map").classList.toggle("dark", mapDark());
function pad() { const w = window.innerWidth; return w < 820 ? { paddingTopLeft: [30, 90], paddingBottomRight: [30, 40 + window.innerHeight * .52] } : { paddingTopLeft: [420, 100], paddingBottomRight: [S.chatOpen ? 450 : 60, 90] }; }
function pinIcon(m) {
  const stale = !isLive(m) && Date.now() / 1000 - m.location.at > 900;
  const label = esc(m.name) + (stale ? ` · ${ago(m.location.at)}` : "");
  return L.divIcon({ className: "", iconSize: [50, 50], iconAnchor: [25, 25], html: `<div class="pin ${stale ? "old" : ""} ${m.role === "parent" ? "parent" : ""}">${avaInner(m)}</div>${m.status && m.status.emoji ? `<span class="pst" title="${esc(m.status.text || "")}">${esc(m.status.emoji)}</span>` : ""}<div class="pinname">${label}</div>` });
}
function renderMap() {
  if (!S.map) return;
  const seen = new Set();
  S.members.forEach(m => { // everyone with a location is on the map, parents too, and people who are offline stay at their last known spot
    if (!m.location) return; seen.add(m.id);
    const ll = [m.location.lat, m.location.lng];
    if (S.markers[m.id]) S.markers[m.id].setLatLng(ll).setIcon(pinIcon(m)); else { S.markers[m.id] = L.marker(ll, { icon: pinIcon(m) }).addTo(S.map); S.markers[m.id].on("click", () => { S.open = m.id; renderSide(); }); }
  });
  Object.keys(S.markers).forEach(id => { if (!seen.has(+id)) { S.map.removeLayer(S.markers[id]); delete S.markers[id]; } });
  S.circles.forEach(c => S.map.removeLayer(c)); S.circles = [];
  S.places.forEach(p => { S.circles.push(L.circle([p.lat, p.lng], { radius: p.radius, color: p.name === "Home" ? "#1fa971" : "#e6b866", weight: p.name === "Home" ? 3 : 2, opacity: .8, fillColor: p.name === "Home" ? "#1fa971" : "#e6b866", fillOpacity: p.name === "Home" ? .14 : .1 }).addTo(S.map).bindTooltip(p.emoji + " " + p.name)); });
  if (!S.focusedOnce && seen.size) { fitAll(); S.focusedOnce = true; }
}
function fitAll() {
  const pts = Object.values(S.markers).map(m => m.getLatLng());
  if (!pts.length) { toast("Nobody has a location yet. Turn on \"Share my location\" to be the first."); return; }
  if (pts.length === 1) S.map.setView(pts[0], 15); else S.map.fitBounds(L.latLngBounds(pts), { ...pad(), maxZoom: 16 });
}
function focusMember(id) { const mk = S.markers[id], m = member(id); if (mk) S.map.flyTo(mk.getLatLng(), Math.max(S.map.getZoom(), 15)); else toast(m && m.share === "paused" ? `${m.name} paused location sharing.` : m && m.share === "off" ? `${m.name} is not sharing location.` : "No location yet."); }
async function showTrail(id) {
  if (S.trail) { S.map.removeLayer(S.trail); S.trail = null; }
  try {
    const d = await api("/api/history?member=" + id + "&hours=24");
    if (!d.points.length) { toast("No trail in the last day."); return; }
    S.trail = L.polyline(d.points.map(p => [p.lat, p.lng]), { color: "#e6b866", weight: 4, opacity: .85, dashArray: "2 8" }).addTo(S.map);
    S.map.fitBounds(S.trail.getBounds(), { ...pad(), maxZoom: 16 }); toast("Last 24 hours. Press Trail again to hide it.");
  } catch (e) { toast(e.message); }
}
function addPlaceDialog(ll) {
  S.adding = false; $("#bAddPlace").textContent = "＋ Place"; let emoji = "📍";
  modal(`<h2>New place</h2><div class="sub">The family gets a message when someone arrives or leaves.</div><label class="l">Name</label><input class="field" id="pName" maxlength="40" placeholder="School">
    <label class="l">Icon</label>${emojiPicker(["📍", "🏫", "🏠", "⚽", "🏋️", "☕", "🏥", "🛒"], emoji)}<label class="l">Size</label><select class="field" id="pRad"><option value="100">Small (100 m)</option><option value="150" selected>Normal (150 m)</option><option value="300">Large (300 m)</option></select>
    <div class="err" id="pErr"></div><div class="foot"><button class="btn" id="pCancel">Cancel</button><button class="btn solid" id="pSave">Save place</button></div>`, root => {
    pickEmoji(root, e => emoji = e); root.querySelector("#pCancel").onclick = closeModal;
    root.querySelector("#pSave").onclick = async () => {
      const name = root.querySelector("#pName").value.trim(); if (!name) { root.querySelector("#pErr").textContent = "Give the place a name."; return; }
      try { await api("/api/places", "POST", { name, emoji, lat: ll.lat, lng: ll.lng, radius: +root.querySelector("#pRad").value }); closeModal(); refreshFamily(); toast("Place saved"); } catch (e) { root.querySelector("#pErr").textContent = e.message; }
    };
  });
}

/* ---------- chat ---------- */
function threadName(id) { return id === 0 ? "Family" : (member(id)?.name || "Chat"); }
function setChat(open) { S.chatOpen = open; $("#chat").classList.toggle("hidden", !open); $("#chatFab").classList.toggle("hidden", open); if (open) { S.unread[S.thread] = 0; renderThreads(); renderMsgs(); setTimeout(() => $("#msgIn").focus(), 50); } }
function renderThreads() {
  const ids = [0, ...S.members.filter(m => m.id !== S.me).map(m => m.id)];
  $("#thr").innerHTML = ids.map(id => `<button data-th="${id}" class="${S.thread === id ? "on" : ""}">${esc(threadName(id))}${S.unread[id] ? `<span class="n">${S.unread[id]}</span>` : ""}</button>`).join("");
  const total = Object.values(S.unread).reduce((a, b) => a + b, 0), n = $("#fabN"); n.textContent = total; n.classList.toggle("hidden", !total);
  document.title = (total ? `(${total}) ` : "") + "Homebase";
}
const QUICK = ["On my way 🚶", "Home soon 🏠", "OK 👍", "Call me 📞", "Thank you ❤️"];
function renderComposer() {
  const t = member(S.thread), blocked = t && !may(t, "messages"), q = $("#qr");
  if (q) { q.classList.toggle("hidden", !!blocked); q.innerHTML = QUICK.map(x => `<button type="button" data-q="${esc(x)}">${esc(x)}</button>`).join(""); }
  $("#msgIn").disabled = !!blocked; $("#msgIn").placeholder = blocked ? `${t.name} turned messages off` : S.online ? "Write a message" : "Offline. It will send when you are back online";
  $("#composer button").disabled = !!blocked;
}
async function loadThread(id, quiet) {
  S.thread = id; if (S.chatOpen || !quiet) S.unread[id] = 0; renderThreads(); renderComposer();
  try { const d = await api("/api/messages" + (id ? "?with=" + id : "")); S.msgs[id] = [...d.messages, ...(S.msgs[id] || []).filter(m => m.pending)]; saveSnap(); } catch (e) { S.msgs[id] = S.msgs[id] || []; }
  renderMsgs();
}
function bubble(m) {
  const mine = m.from === S.me, who = member(m.from)?.name || "";
  if (m.kind === "system") return `<div class="b sys">${esc(m.body)}</div>`;
  let body = esc(m.body), cls = "";
  if (m.kind === "heart") body = '<span style="font-size:28px">❤️</span>';
  if (m.kind === "checkin") { cls = "loc"; body = "✅ " + esc(m.body || "I'm OK") + (m.lat != null ? " · 📍 tap to see where" : ""); }
  if (m.kind === "late") body = "⏰ " + esc(m.body);
  if (m.kind === "sos") { cls = "sos loc"; body = "🆘 <b>" + esc(m.body || "I need help") + "</b>" + (m.lat != null ? " · 📍 tap to see where" : ""); }
  if (m.kind === "pickup") { cls = "loc"; body = "🚗 <b>" + esc(m.body || "Can someone pick me up?") + "</b>" + (m.lat != null ? " · 📍 tap to see where" : "") + (mine || m.pending ? "" : `<div class="pk">${[5, 10, 20, 30].map(n => `<button type="button" data-pk="${n}">On my way · ${n} min</button>`).join("")}</div>`); }
  const rx = Object.entries(m.reactions || {}).map(([e, ids]) => `<button type="button" data-rx="${e}" class="${ids.includes(S.me) ? "mine" : ""}" aria-label="${e} ${ids.length}">${e} ${ids.length}</button>`).join("");
  return `<div class="b ${mine ? "me" : ""} ${cls} ${m.pending ? "pending" : ""}" data-mid="${m.id}" ${m.lat != null ? `data-lat="${m.lat}" data-lng="${m.lng}"` : ""}>${S.thread === 0 && !mine ? `<div class="who">${esc(who)}</div>` : ""}${body}${rx ? `<div class="rx">${rx}</div>` : ""}<small>${m.pending ? "⏳ waiting to send" : clock(m.at)}</small>${m.pending || m.id < 0 ? "" : '<button type="button" class="rb" aria-label="React to this message">🙂</button>'}</div>`;
}
function renderMsgs(keepScroll) {
  const list = S.msgs[S.thread] || [], box = $("#msgs"); if (!box) return;
  const top = box.scrollTop;
  box.innerHTML = list.length ? list.map(bubble).join("") : `<div class="empty" style="margin:auto;text-align:center">No messages yet.<br>Say hello 👋</div>`;
  box.scrollTop = keepScroll ? top : box.scrollHeight;
}
const REACTIONS = ["👍", "❤️", "😂", "😮", "🙏", "🎉"];
async function react(mid, emoji) {
  try { const d = await api(`/api/messages/${mid}/react`, "POST", { emoji }); setReactions(mid, d.reactions); } catch (e) { toast(e.message); }
}
function setReactions(mid, reactions) {
  Object.values(S.msgs).forEach(list => list.forEach(m => { if (m.id === mid) m.reactions = reactions; }));
  if (S.chatOpen) renderMsgs(true);
}
/* a message that cannot be sent right now waits in the outbox and goes the moment the connection is back */
async function sendMsg(kind, body, extra = {}, thread = S.thread) {
  try { const d = await api("/api/messages", "POST", { to: thread, kind, body, ...extra }); (S.msgs[thread] = S.msgs[thread] || []).push(d.message); }
  catch (e) {
    if (!e.offline) throw e;
    const id = S.tmp--; S.outbox.push({ id, thread, kind, body, extra }); store("hb_out", S.outbox);
    (S.msgs[thread] = S.msgs[thread] || []).push({ id, from: S.me, to: thread, kind, body, lat: extra.lat ?? null, lng: extra.lng ?? null, at: Date.now() / 1000, pending: true });
    toast(kind === "sos" ? "No connection. Your SOS will send the moment you are back online." : "Offline. It will send when you are back online.", 4500);
  }
  if (thread === S.thread) renderMsgs();
}
let flushing = false;
async function flushOutbox() {
  if (flushing || !S.outbox.length || !S.online) return; flushing = true;
  try {
    while (S.outbox.length) {
      const o = S.outbox[0];
      try { await api("/api/messages", "POST", { to: o.thread, kind: o.kind, body: o.body, ...o.extra }); S.outbox.shift(); store("hb_out", S.outbox); }
      catch (e) { if (e.offline) break; S.outbox.shift(); store("hb_out", S.outbox); toast("A queued message was refused: " + e.message, 5000); }
    }
    if (!S.outbox.length) { Object.keys(S.msgs).forEach(t => { S.msgs[t] = (S.msgs[t] || []).filter(m => !m.pending); }); await loadThread(S.thread, true); }
  } finally { flushing = false; }
}

/* ---------- check-ins, SOS, late ---------- */
const where = () => S.lastPos ? { lat: S.lastPos.lat, lng: S.lastPos.lng } : {};
async function checkIn() {
  if (!S.lastPos && navigator.geolocation) { try { const p = await new Promise((ok, no) => navigator.geolocation.getCurrentPosition(ok, no, { timeout: 4000 })); S.lastPos = { lat: p.coords.latitude, lng: p.coords.longitude }; } catch (e) { /* send without a place */ } }
  try { await sendMsg("checkin", "I'm OK", where(), 0); if (S.online) toast("Sent to the family"); } catch (e) { toast(e.message); }
}
function lateDialog() {
  modal(`<h2>How late will you be?</h2><div class="seg">${[10, 20, 30, 60].map(m => `<button class="btn" data-m="${m}">About ${m === 60 ? "an hour" : m + " min"}</button>`).join("")}</div>`, root => {
    root.querySelectorAll("[data-m]").forEach(b => b.onclick = async () => { closeModal(); try { await sendMsg("late", `Running about ${b.dataset.m} minutes late`, {}, 0); if (S.online) toast("Sent"); } catch (e) { toast(e.message); } });
  });
}
function initSos() {
  const b = $("#bSos"); let t = 0; const stop = () => { clearTimeout(t); b.style.opacity = ""; };
  b.addEventListener("pointerdown", () => { toast("Keep holding for 2 seconds…", 1800); b.style.opacity = ".6"; t = setTimeout(async () => { stop(); try { await sendMsg("sos", "I need help", where(), 0); if (S.online) toast("SOS sent to everyone"); } catch (e) { toast(e.message); } }, 2000); });
  ["pointerup", "pointerleave", "pointercancel"].forEach(ev => b.addEventListener(ev, stop));
}

/* ---------- list ---------- */
async function refreshItems() { try { S.items = (await api("/api/list")).items; saveSnap(); renderSide(); } catch (e) { /* keep the last list */ } }

/* ---------- sharing this laptop's location ---------- */
function startGeo() {
  if (!navigator.geolocation || S.geo != null) return;
  S.geo = navigator.geolocation.watchPosition(async pos => {
    const first = !S.lastPos; S.lastPos = { lat: pos.coords.latitude, lng: pos.coords.longitude }; if (first) renderSide();
    if (Date.now() - S.lastSent < 20000) return; S.lastSent = Date.now();
    let battery = -1; try { battery = Math.round((await navigator.getBattery()).level * 100); } catch (e) { /* not supported */ }
    api("/api/location", "POST", { lat: pos.coords.latitude, lng: pos.coords.longitude, acc: pos.coords.accuracy || 0, speed: pos.coords.speed || 0, battery }).catch(() => { S.lastSent = 0; });
  }, err => { toast(err.code === 1 ? "Location is blocked for this site. Allow it in your browser's address bar." : "Could not get this laptop's location."); stopGeo(); store("hb_geo", "0"); renderSide(); }, { enableHighAccuracy: false, maximumAge: 15000, timeout: 25000 });
  store("hb_geo", "1"); renderSide();
}
function stopGeo() { if (S.geo != null && navigator.geolocation) navigator.geolocation.clearWatch(S.geo); S.geo = null; S.lastPos = null; }
async function setSharing(state, minutes) {
  try {
    await api("/api/sharing", "POST", state === "paused" ? { state, minutes } : { state });
    if (state === "on") startGeo(); else { stopGeo(); store("hb_geo", "0"); }
    await refreshFamily(); toast(state === "on" ? "You are on the map" : state === "paused" ? `Paused for ${minutes} minutes` : "Location sharing is off");
  } catch (e) { toast(e.message); }
}

/* ---------- menu: settings, permissions, quiet hours, invite ---------- */
async function meDialog() {
  let q = { from: -1, to: -1 }; try { q = (await api("/api/me")).quiet; } catch (e) { /* offline */ }
  const m = me(), hm = v => v < 0 ? "" : String(Math.floor(v / 60)).padStart(2, "0") + ":" + String(v % 60).padStart(2, "0");
  const theme = localStorage.getItem("hb_theme") || "light", layer = localStorage.getItem("hb_map") || "light";
  const perms = m.role === "child" ? `<div class="msec">What parents can do</div><div class="sub">You decide. Switch something off and Homebase stops it straight away. Your parents see that it is off.</div>` +
    PERMS.map(([k, t, why]) => `<div class="sw"><div><b>${t}</b><span>${why}</span></div><button class="tg ${m.perms[k] !== false ? "on" : ""}" data-perm="${k}" aria-label="${t}"></button></div>`).join("") : "";
  modal(`<h2>${esc(m.emoji)} ${esc(m.name)}</h2><div class="sub">${m.role === "parent" ? "Parent" : "Child"} · ${esc(S.family?.name || "")}</div>
    <div class="msec">Photo</div>
    <div class="pfp-row"><span class="ava big" id="pfpPrev">${avaInner(m)}</span>
      <div class="pfp-btns"><button class="btn sm" id="pfpPick" type="button">Choose a photo</button>${m.avatar ? '<button class="btn sm" id="pfpClr" type="button">Remove</button>' : ""}<input type="file" id="pfpFile" accept="image/*" hidden></div></div>
    <div class="msec">Appearance</div><div class="seg" id="segTheme">${[["auto", "Match my computer"], ["light", "Light"], ["dark", "Dark"]].map(([v, t]) => `<button class="btn sm ${theme === v ? "sel" : ""}" data-v="${v}">${t}</button>`).join("")}</div>
    <div class="seg" id="segMap">${[["light", "Light map"], ["dark", "Dark map"]].map(([v, t]) => `<button class="btn sm ${layer === v ? "sel" : ""}" data-v="${v}">${t}</button>`).join("")}</div>
    ${perms}
    <div class="msec">Quiet hours</div><div class="sub">Messages stay quiet and calls do not ring. An urgent "ring anyway" still works.</div>
    <div style="display:flex;gap:10px;align-items:center"><input class="field" type="time" id="qFrom" value="${hm(q.from)}"><span class="muted">to</span><input class="field" type="time" id="qTo" value="${hm(q.to)}"><button class="btn sm" id="qSave">Save</button><button class="btn sm" id="qOff">Off</button></div>
    <div class="msec">Your data</div><div class="seg"><button class="btn sm" id="mHist">Delete my location history</button><a class="btn sm" href="/download">Android app</a>${window.__installPrompt ? '<button class="btn sm" id="mInstall">Install on this computer</button>' : ""}</div>
    <div class="foot" style="justify-content:space-between"><button class="btn bad" id="mLeave">Leave family</button><span><button class="btn" id="mOut">Sign out</button> <button class="btn solid" id="mDone">Done</button></span></div>`, root => {
    root.querySelectorAll("#segTheme [data-v]").forEach(b => b.onclick = () => { store("hb_theme", b.dataset.v); applyTheme(); root.querySelectorAll("#segTheme .btn").forEach(x => x.classList.toggle("sel", x === b)); });
    root.querySelectorAll("#segMap [data-v]").forEach(b => b.onclick = () => { store("hb_map", b.dataset.v); paintLayer(); root.querySelectorAll("#segMap .btn").forEach(x => x.classList.toggle("sel", x === b)); });
    const pfpFile = root.querySelector("#pfpFile");
    root.querySelector("#pfpPick").onclick = () => pfpFile.click();
    pfpFile.onchange = async () => { const f = pfpFile.files[0]; if (!f) return; if (!f.type.startsWith("image/")) return toast("Pick an image."); try { const url = await uploadAvatar(f); S.av[url] = undefined; root.querySelector("#pfpPrev").innerHTML = ""; const img = new Image(); img.className = "avimg"; const blob = await (await fetch(url, { headers: { Authorization: "Bearer " + S.token } })).blob(); img.src = URL.createObjectURL(blob); root.querySelector("#pfpPrev").append(img); toast("Photo saved"); refreshFamily(); } catch (e) { toast(e.message); } };
    const pfpClr = root.querySelector("#pfpClr"); if (pfpClr) pfpClr.onclick = async () => { try { await api("/api/avatar", "DELETE"); toast("Photo removed"); closeModal(); refreshFamily(); } catch (e) { toast(e.message); } };
    root.querySelectorAll("[data-perm]").forEach(b => b.onclick = async () => { const on = !b.classList.contains("on"); b.classList.toggle("on", on); try { await api("/api/me", "PATCH", { perms: { [b.dataset.perm]: on } }); refreshFamily(); } catch (e) { b.classList.toggle("on", !on); toast(e.message); } });
    const mins = v => { const [h, mm] = v.split(":").map(Number); return h * 60 + mm; };
    root.querySelector("#qSave").onclick = async () => { const a = root.querySelector("#qFrom").value, b = root.querySelector("#qTo").value; if (!a || !b) { toast("Pick both times."); return; } try { await api("/api/me", "PATCH", { quiet_from: mins(a), quiet_to: mins(b), tz_offset: new Date().getTimezoneOffset() }); toast("Quiet hours saved"); } catch (e) { toast(e.message); } };
    root.querySelector("#qOff").onclick = async () => { try { await api("/api/me", "PATCH", { quiet_from: -1, quiet_to: -1 }); toast("Quiet hours off"); root.querySelector("#qFrom").value = root.querySelector("#qTo").value = ""; } catch (e) { toast(e.message); } };
    root.querySelector("#mHist").onclick = async () => { if (confirm("Delete everything Homebase saved about where you have been?")) { await api("/api/history", "DELETE").then(() => toast("Deleted")).catch(e => toast(e.message)); } };
    root.querySelector("#mLeave").onclick = async () => { if (confirm("Leave the family? Everything you shared is deleted.")) { await api("/api/me", "DELETE").catch(e => toast(e.message)); signOut(); } };
    root.querySelector("#mOut").onclick = signOut; root.querySelector("#mDone").onclick = closeModal;
    const inst = root.querySelector("#mInstall"); if (inst) inst.onclick = async () => { window.__installPrompt.prompt(); window.__installPrompt = null; closeModal(); };
  });
}
function inviteDialog() {
  modal(`<h2>Invite someone</h2><div class="sub">The code works once and lasts 24 hours.</div><label class="l">They are a…</label>
    <div class="seg" id="segRole"><button class="btn sel" data-r="child">Child</button><button class="btn" data-r="parent">Parent</button></div><div id="inv"></div><div class="foot"><button class="btn solid" id="iClose">Done</button></div>`, root => {
    root.querySelector("#iClose").onclick = closeModal;
    const make = async role => {
      root.querySelectorAll("[data-r]").forEach(x => x.classList.toggle("sel", x.dataset.r === role));
      try { const d = await api("/api/invite", "POST", { role }); const link = location.origin + "/app#join=" + d.code;
        root.querySelector("#inv").innerHTML = `<div class="big">${esc(d.code)}</div><div class="seg" style="justify-content:center;margin-top:12px"><button class="btn sm" id="cCode">Copy code</button><button class="btn sm" id="cLink">Copy link</button></div>`;
        root.querySelector("#cCode").onclick = () => { navigator.clipboard?.writeText(d.code); toast("Code copied"); }; root.querySelector("#cLink").onclick = () => { navigator.clipboard?.writeText(link); toast("Link copied"); };
      } catch (e) { root.querySelector("#inv").innerHTML = `<div class="err">${esc(e.message)}</div>`; } };
    root.querySelectorAll("[data-r]").forEach(b => b.onclick = () => make(b.dataset.r)); make("child");
  });
}

/* ---------- where are you? ---------- */
async function askWhere(m) { try { await api("/api/ping", "POST", { to: m.id }); toast(`Asked ${m.name}. They can answer or say not now.`); } catch (e) { toast(e.message); } }
function pingDialog(p) {
  notify(`${p.name} asked where you are`, "Open Homebase to answer", { tag: "ping", urgent: true });
  modal(`<h2>${esc(p.emoji)} ${esc(p.name)} asked where you are</h2><div class="sub">You can share your location this once, or say not now. Either way is fine.</div><div class="foot"><button class="btn" id="pNo">Not now</button><button class="btn solid" id="pYes">Share my location</button></div>`, root => {
    root.querySelector("#pNo").onclick = async () => { closeModal(); await api("/api/ping/" + p.id, "POST", { accept: false }).catch(() => {}); };
    root.querySelector("#pYes").onclick = async () => { closeModal();
      try { const pos = await new Promise((ok, no) => navigator.geolocation.getCurrentPosition(ok, no, { timeout: 8000 })); await api("/api/ping/" + p.id, "POST", { accept: true, lat: pos.coords.latitude, lng: pos.coords.longitude }); toast("Shared"); }
      catch (e) { await api("/api/ping/" + p.id, "POST", { accept: false }).catch(() => {}); toast("Could not get your location, so it was declined."); } };
  });
}

/* ---------- events (messages, calls and so on, the moment they happen) ---------- */
function notifyState() { return "Notification" in window ? Notification.permission : "unsupported"; }
async function askNotify() {
  if (notifyState() === "unsupported") return toast("This browser cannot show notifications.");
  const r = await Notification.requestPermission(); renderSide();
  if (r === "granted") { toast("Notifications are on"); notify("Homebase", "You will see messages and calls like this.", { force: true }); } else toast("Notifications are blocked. You can allow them from the lock icon in the address bar.");
}
/** A desktop notification like any other app: it shows for new messages, calls and requests, and clicking it opens that conversation.
 *  It stays quiet only when you are already looking at that conversation. */
async function notify(title, body, o = {}) {
  if (notifyState() !== "granted") return;
  if (!o.force && document.hasFocus() && !document.hidden && (o.thread === undefined || (S.chatOpen && o.thread === S.thread))) return;
  const opts = { body, icon: "/static/web/icon-192.png", badge: "/static/web/icon-192.png", tag: o.tag || "hb", renotify: true, requireInteraction: !!o.urgent, data: { thread: o.thread } };
  try { const reg = "serviceWorker" in navigator && await navigator.serviceWorker.getRegistration(); if (reg && reg.showNotification) return reg.showNotification(title, opts); } catch (e) { /* fall back */ }
  try { const n = new Notification(title, opts); n.onclick = () => { window.focus(); if (o.thread !== undefined) openThreadFromNotice(o.thread); n.close(); }; } catch (e) { /* ignore */ }
}
function openThreadFromNotice(th) { setChat(true); loadThread(th); }
if ("serviceWorker" in navigator) navigator.serviceWorker.addEventListener("message", e => { if (e.data && e.data.type === "open-thread") { window.focus(); if (S.token) openThreadFromNotice(e.data.thread); } });
async function eventLoop() {
  if (S.cursor < 0) { try { S.cursor = (await api("/api/events?after=-1")).cursor; } catch (e) { S.cursor = load("hb_cursor", 0); } }
  while (S.running && S.token) {
    if (!S.online) { await sleep(2500); try { await fetch("/health", { cache: "no-store" }); setOnline(true); } catch (e) { /* still offline */ } continue; }
    try {
      const d = await api(`/api/events?after=${S.cursor}&wait=25`); S.cursor = d.cursor; store("hb_cursor", S.cursor);
      for (const ev of d.events) { try { handleEvent(ev); } catch (e) { console.error(e); } }
    } catch (e) { if (e.status === 401) return; await sleep(3000); }
  }
}
function handleEvent(ev) {
  const p = ev.payload;
  switch (ev.type) {
    case "message": {
      const th = p.to === 0 ? 0 : p.from, list = (S.msgs[th] = S.msgs[th] || []);
      if (!list.some(x => x.id === p.id)) list.push(p);
      if (th === S.thread) renderMsgs();
      if (th !== S.thread || document.hidden || !S.chatOpen) { S.unread[th] = (S.unread[th] || 0) + 1; renderThreads(); }
      if (p.kind !== "system" && !p.quiet) {
        notify((p.kind === "sos" ? "🆘 " : p.kind === "pickup" ? "🚗 " : "") + (p.name || "Homebase"), p.body || "", { thread: th, tag: "msg-" + th, urgent: p.kind === "sos" || p.kind === "pickup" });
        if (p.kind === "sos") toast("🆘 " + (p.name || "Someone") + " needs help", 7000);
        else if (p.kind === "pickup") toast("🚗 " + (p.name || "Someone") + " needs a pickup", 9000);
        else if (th !== S.thread || !S.chatOpen) toast("💬 " + (p.name || "Someone") + ": " + String(p.body || "").slice(0, 80), 5000);
      }
      saveSnap(); break; }
    case "member": case "places": refreshFamily(); break;
    case "reaction": setReactions(p.id, p.reactions || {}); break;
    case "ground_warn": { const names = (p.apps || []).join(", "); toast("⏳ " + names + " will be grounded in 1 minute — finish up", 14000); notify("Finish up", names + " will be grounded in a minute.", { tag: "groundwarn", force: true }); break; }
    case "nudge": toast("💡 " + p.text, 12000); notify("Time for a break?", p.text, { tag: "nudge", force: false, thread: undefined }); break;
    case "list": refreshItems(); break;
    case "rewards": refreshRewards(); break;
    case "reward": {
      refreshRewards();
      const t = p.status === "pending" ? [`🎁 ${p.name} asked for: ${p.title} (${p.cost} points)`, p.name + " asked for a reward"] : p.status === "approved" ? [`🎁 ${p.name} said yes: ${p.title}${p.minutes ? " (+" + p.minutes + " min screen time today)" : ""}`, "Reward approved"] : [`${p.name} said not now to: ${p.title}`, "Reward not now"];
      toast(t[0], 7000); notify(t[1], `${p.title} · ${p.cost} points`, { tag: "reward-" + p.id + p.status, force: false }); break; }
    case "board": refreshBoard(); break;
    case "birthday": { refreshFamily(); const t = p.self ? `🎂 Happy birthday, ${p.name}!` : `🎂 It's ${p.name}'s birthday today!`; toast(t, 9000); notify(t, p.self ? "Your family is thinking of you." : "Send them some love.", { tag: "bday-" + p.id, force: false }); break; }
    case "plan": refreshPlans(); if (p.new) { toast(`${p.emoji || "📅"} ${p.name} planned: ${p.title}`, 5000); notify(p.name + " added a plan", `${p.title} · ${whenTxt(p.start)}`, { tag: "plan-" + p.id, force: false }); } break;
    case "plan_soon": toast(`${p.emoji || "📅"} ${p.title} starts soon · ${clock(p.start)}`, 9000); notify("Starting soon", `${p.title} · ${clock(p.start)}`, { tag: "plan-soon-" + p.id, force: true }); break;
    case "poll": refreshPolls(); if (p.new) { toast(`🗳️ ${p.name} asks: ${p.question}`, 6000); notify(p.name + " asks the family", p.question, { tag: "poll-" + p.id, force: false }); } break;
    case "chores": refreshChores(); break;
    case "location": { const m = member(p.member); if (m) { m.location = { lat: p.lat, lng: p.lng, acc: p.acc, at: p.at }; m.last_seen = Date.now() / 1000; m.battery = p.battery ?? m.battery; renderMap(); renderSide(); } break; }
    case "battery": { const m = member(p.member); if (m) { m.battery = p.battery; renderSide(); } toast(`🔋 ${p.name} has ${p.battery}% battery`); break; }
    case "place": toast(`${p.emoji} ${p.name} ${p.kind === "arrive" ? "arrived at" : "left"} ${p.place}`, 5000); break;
    case "ping": pingDialog(p); break;
    case "ping_result": if (p.status === "shared") { toast(`📍 ${p.name} shared their location`); refreshFamily().then(() => focusMember(p.member)); } else toast(`${p.name} said not now`); break;
    case "call": case "call_update": case "ice": window.Calls && window.Calls.onEvent(ev.type, p); break;
  }
}

/* ---------- wiring ---------- */
let chromeReady = false;
function initChrome() {
  if (chromeReady) return; chromeReady = true;
  $("#bOk").onclick = checkIn; $("#bLate").onclick = lateDialog; $("#bMe").onclick = meDialog; $("#bInvite").onclick = inviteDialog; initSos();
  $("#bFit").onclick = fitAll; $("#bZoomIn").onclick = () => S.map.zoomIn(); $("#bZoomOut").onclick = () => S.map.zoomOut();
  $("#bLayer").onclick = () => { store("hb_map", mapDark() ? "light" : "dark"); paintLayer(); };
  $("#bAddPlace").onclick = () => { S.adding = !S.adding; $("#bAddPlace").textContent = S.adding ? "Click the map…" : "＋ Place"; if (S.adding) toast("Click the map where the place is", 4000); };
  $("#chatFab").onclick = () => setChat(true); $("#chatX").onclick = () => setChat(false);
  $("#qr").onclick = e => { const b = e.target.closest("[data-q]"); if (b) sendMsg("text", b.dataset.q).catch(err => toast(err.message)); };
  $("#thr").onclick = e => { const b = e.target.closest("[data-th]"); if (b) loadThread(+b.dataset.th); };
  $("#composer").onsubmit = async e => { e.preventDefault(); const v = $("#msgIn").value.trim(); if (!v) return; $("#msgIn").value = ""; try { await sendMsg("text", v); } catch (err) { toast(err.message); $("#msgIn").value = v; } };
  $("#msgs").onclick = e => {
    const pick = e.target.closest(".rpick button"); if (pick) { react(+pick.closest(".b").dataset.mid, pick.dataset.e); return; }
    const chip = e.target.closest("[data-rx]"); if (chip) { react(+chip.closest(".b").dataset.mid, chip.dataset.rx); return; }
    const pk = e.target.closest("[data-pk]"); if (pk) { sendMsg("text", `On my way, about ${pk.dataset.pk} minutes 🚗`, {}, 0).then(() => toast("Sent")).catch(err => toast(err.message)); return; }
    const rb = e.target.closest(".rb"); if (rb) { const bub = rb.closest(".b"), had = bub.querySelector(".rpick"); document.querySelectorAll(".rpick").forEach(x => x.remove()); if (!had) bub.insertAdjacentHTML("beforeend", `<div class="rpick">${REACTIONS.map(x => `<button type="button" data-e="${x}" aria-label="React ${x}">${x}</button>`).join("")}</div>`); return; }
    document.querySelectorAll(".rpick").forEach(x => x.remove());
    const b = e.target.closest("[data-lat]"); if (b) { S.map.flyTo([+b.dataset.lat, +b.dataset.lng], 16); L.popup().setLatLng([+b.dataset.lat, +b.dataset.lng]).setContent("📍 Here").openOn(S.map); } };
  $("#side").onclick = async e => {
    const tb = e.target.closest("[data-tab]"); if (tb) return setTab(tb.dataset.tab);
    const ac = e.target.closest("[data-acc]"); if (ac) return toggleAcc(ac.dataset.acc);
    const tc = e.target.closest('[data-act="tocontrols"]'); if (tc) return setTab("controls");
    const pause = e.target.closest("[data-pause]"); if (pause) { const v = +pause.dataset.pause; return setSharing(v ? "paused" : "on", v); }
    if (e.target.closest("#tShare")) { return setSharing(me().share === "on" ? "off" : "on"); }
    if (e.target.closest("#bAllow")) { return startGeo(); }
    if (e.target.closest("#bNotify")) { return askNotify(); }
    if (e.target.closest("#bHome")) { return homeDialog(); }
    if (e.target.closest("#bScreen")) { return screenDialog(S.me); }
    if (e.target.closest("#bRules")) { return rulesDialog(); }
    const el = e.target.closest("[data-act]"); if (!el) return; const id = +el.dataset.id, m = member(id), act = el.dataset.act;
    if (act === "pick") { S.open = S.open === id ? 0 : id; renderSide(); if (S.open) focusMember(id); }
    else if (act === "msg") { setChat(true); loadThread(id); }
    else if (act === "call") window.Calls && window.Calls.start(m);
    else if (act === "ping") askWhere(m);
    else if (act === "trail") showTrail(id);
    else if (act === "screen") screenDialog(id);
    else if (act === "rules") rulesDialog();
    else if (act === "week") screenDialog(id);
    else if (act === "devices") devicesDialog(id);
    else if (act === "myperms") meDialog();
    else if (act === "lock") lockDialog(id);
    else if (act === "ground") groundDialog(id);
    else if (act === "status") statusDialog();
    else if (act === "chatall") { setChat(true); loadThread(0); }
    else if (act === "fit") fitAll();
    else if (act === "invite") inviteDialog();
    else if (act === "more") { S.more = S.more || {}; S.more[id] = !S.more[id]; renderSide(); }
    else if (act === "delplace") { if (confirm("Remove this place?")) { await api("/api/places/" + id, "DELETE").catch(err => toast(err.message)); refreshFamily(); } }
    else if (act === "delitem") { await api("/api/list/" + id, "DELETE").catch(err => toast(err.message)); refreshItems(); }
    else if (act === "clearitems") { await api("/api/list/clear", "POST").catch(err => toast(err.message)); refreshItems(); }
    else if (act === "pickup") pickupNow();
    else if (act === "birthday") birthdayDialog();
    else if (act === "addplan") planDialog();
    else if (act === "calendar") openCalendar();
    else if (act === "updnudge") askUpdate(id);
    else if (act === "rsvp") rsvp(id, el.dataset.a);
    else if (act === "delplan") { if (confirm("Delete this plan?")) { await api("/api/plans/" + id, "DELETE").catch(err => toast(err.message)); refreshPlans(); } }
    else if (act === "addpoll") pollDialog();
    else if (act === "vote") vote(id, +el.dataset.o);
    else if (act === "closepoll") { await api(`/api/polls/${id}/close`, "POST").catch(err => toast(err.message)); refreshPolls(); }
    else if (act === "delpoll") { if (confirm("Delete this vote?")) { await api("/api/polls/" + id, "DELETE").catch(err => toast(err.message)); refreshPolls(); } }
  };
  $("#side").addEventListener("click", async e => {
    const el = e.target.closest("[data-chore]"); if (!el) return; const id = el.dataset.id, act = el.dataset.chore;
    try {
      if (act === "done") await api(`/api/chores/${id}/done`, "POST");
      else if (act === "ok") await api(`/api/chores/${id}/approve`, "POST", { ok: true });
      else if (act === "redo") await api(`/api/chores/${id}/approve`, "POST", { ok: false });
      else if (act === "del") { if (!confirm("Delete this job?")) return; await api(`/api/chores/${id}`, "DELETE"); }
      refreshChores();
    } catch (err) { toast(err.message); }
  });
  $("#side").addEventListener("click", e => {
    const rw = e.target.closest("[data-rw]"); if (rw) return rewardAct(rw.dataset.rw, +rw.dataset.id);
    const bd = e.target.closest("[data-bd]"); if (bd) return boardDialog(bd.dataset.bd === "edit" ? (S.board || []).find(n => n.id === +bd.dataset.id) : null);
  });
  $("#side").addEventListener("submit", async e => {
    if (e.target.id !== "rewardForm") return; e.preventDefault();
    const f = e.target, title = f.title.value.trim(); if (!title) return;
    try { await api("/api/rewards", "POST", { title, cost: +f.cost.value || 0, minutes: +f.minutes.value || 0 }); f.title.value = ""; toast("Reward added"); refreshRewards(); } catch (err) { toast(err.message); }
  });
  $("#side").addEventListener("submit", async e => {
    if (e.target.id !== "choreForm") return; e.preventDefault();
    const f = e.target, title = f.title.value.trim(); if (!title) return;
    try { await api("/api/chores", "POST", { title, points: +f.points.value || 0, to: +f.to.value || 0, repeat: f.repeat.value }); f.title.value = ""; toast("Job added"); refreshChores(); } catch (err) { toast(err.message); }
  });
  $("#side").onchange = async e => { const el = e.target.closest('[data-act="tick"]'); if (el) { await api("/api/list/" + el.dataset.id, "POST", { done: el.checked }).catch(err => toast(err.message)); refreshItems(); } };
  $("#side").onsubmit = async e => { if (e.target.id !== "itemForm") return; e.preventDefault(); const v = $("#itemIn").value.trim(); if (!v) return; try { await api("/api/list", "POST", { text: v }); refreshItems(); } catch (err) { toast(err.message); } };
  document.addEventListener("keydown", e => { if (e.key === "Escape") { closeModal(); if (S.adding) $("#bAddPlace").click(); } });
}
document.addEventListener("visibilitychange", () => { if (!document.hidden && S.token) { if (S.chatOpen) S.unread[S.thread] = 0; renderThreads(); } });
window.addEventListener("beforeinstallprompt", e => { e.preventDefault(); window.__installPrompt = e; });
if ("serviceWorker" in navigator) window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));
window.addEventListener("load", () => {
  const m = location.hash.match(/join=([A-Za-z0-9]+)/);
  if (S.token) startApp(); else if (m) showAuth("join", { code: m[1].toUpperCase() }); else showAuth("welcome");
});


/* ---------- screen time: only active playing or watching, and which apps ---------- */
const fmtDur = sec => { const m = Math.floor(sec / 60); return m >= 60 ? `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")} min` : m >= 1 ? `${m} min` : sec > 0 ? "under a minute" : "0 min"; };
const CAT = { play: ["🎮", "Playing", "var(--accent)"], watch: ["📺", "Watching", "#e5484d"], social: ["💬", "Scrolling", "#c9a8f0"] };
async function screenDialog(id) {
  const mine = id === S.me, m = member(id) || me();
  let d; try { d = await api("/api/screentime?member=" + id + "&days=7"); } catch (e) { return toast(e.message); }
  const days = [...Array(7)].map((_, i) => { const dt = new Date(Date.now() - i * 864e5), key = dt.getFullYear() + "-" + String(dt.getMonth() + 1).padStart(2, "0") + "-" + String(dt.getDate()).padStart(2, "0"); return d.days.find(x => x.day === key) || { day: key, total: 0, apps: [] }; });
  let pick = 0;
  const label = i => i === 0 ? "Today" : i === 1 ? "Yesterday" : new Date(days[i].day + "T12:00").toLocaleDateString([], { weekday: "long" });
  const draw = root => {
    const sel = days[pick], max = Math.max(1, ...days.map(x => x.total)), top = Math.max(1, ...sel.apps.map(a => a.sec));
    root.querySelector("#stBody").innerHTML = `<div class="sub">${label(pick)}</div><div class="stbig">${fmtDur(sel.total)}</div>
      <div class="stbars">${[6, 5, 4, 3, 2, 1, 0].map(i => `<button class="stbar ${i === pick ? "sel" : ""}" data-i="${i}" aria-label="${label(i)}: ${fmtDur(days[i].total)}"><i style="height:${8 + 72 * days[i].total / max}px"></i><span>${new Date(days[i].day + "T12:00").toLocaleDateString([], { weekday: "narrow" })}</span></button>`).join("")}</div>
      <div class="msec">Apps</div>${sel.apps.length ? sel.apps.map(a => { const c = CAT[a.cat] || CAT.play; return `<div class="starow"><span class="stemo">${c[0]}</span><div style="flex:1"><div class="stline"><b>${esc(a.label || a.pkg)}</b><span>${fmtDur(a.sec)}</span></div><div class="sttrack"><i style="width:${100 * a.sec / top}%;background:${c[2]}"></i></div><small>${c[1]}</small></div></div>`; }).join("")
      : `<div class="empty">${mine ? "Nothing counted for this day yet." + (pick === 0 && whyNothing(m) ? "<br><small>" + esc(whyNothing(m)) + "</small>" : "") : esc(m.name) + " may not have turned tracking on yet. It is their choice."}</div>`}`;
    root.querySelectorAll(".stbar").forEach(b => b.onclick = () => { pick = +b.dataset.i; draw(root); });
  };
  let sm = null; try { sm = await api("/api/summary?member=" + id); } catch (e) { /* not available */ }
  const weekCard = sm ? `<div class="weekcard"><b>This week</b><div class="weekgrid">${sm.screen ? `<div><span>${fmtDur(sm.screen.total)}</span><small>playing and watching</small></div><div><span>${fmtDur(sm.screen.avg)}</span><small>a day on average</small></div>` : ""}<div><span>${sm.chores.done}</span><small>jobs done · ${sm.chores.points} points</small></div><div><span>${sm.locks.minutes ? fmtDur(sm.locks.minutes * 60) : "none"}</span><small>locked</small></div></div>${sm.screen && sm.screen.top.length ? `<small>Most time: ${sm.screen.top.map(t => esc(t.label) + " " + fmtDur(t.sec)).join(" · ")}</small>` : ""}</div>` : "";
  modal(`<h2>${mine ? "Your" : esc(m.name) + "'s"} screen time</h2><div class="sub">Only time spent actively playing or watching. Not when away, idle, or on homework and work.</div>${weekCard}<div id="stBody"></div>
    ${mine ? `<div class="msec">Track this computer</div><div class="sub">Windows: a small program counts your playing and watching here. Android: turn it on in the Homebase app, in Me.</div><div class="seg"><button class="btn sm" id="stAgent">Get the Windows tracker</button><button class="btn sm" id="stDel">Delete my screen time</button></div>` : ""}
    <div class="foot"><button class="btn solid" id="stDone">Done</button></div>`, root => {
    draw(root); root.querySelector("#stDone").onclick = closeModal;
    const a = root.querySelector("#stAgent"); if (a) a.onclick = agentDialog;
    const x = root.querySelector("#stDel"); if (x) x.onclick = async () => { if (confirm("Delete all your saved screen time?")) { await api("/api/screentime", "DELETE").then(() => { toast("Deleted"); closeModal(); }).catch(e => toast(e.message)); } };
  });
}
async function agentDialog() {
  let code = "", err = "";
  try { code = (await api("/api/devices/code", "POST")).code; } catch (e) { err = e.message; }
  modal(`<h2>🖥️ Link a Windows computer</h2><div class="sub">A small program counts only the time you spend <b>playing or watching</b> (games, YouTube, Netflix, TikTok). Not when you are away, locked, or doing homework. It also follows parent locks and grounded apps.</div>
    <div class="msec">1 · Download</div><div class="seg"><a class="btn solid" href="/download/windows" id="agDl">Download HomebaseTracker.exe</a></div>
    <div class="sub">If Windows says it protected your PC, press <b>More info</b>, then <b>Run anyway</b>. That warning is normal for a new small program.</div>
    <div class="msec">2 · Open it and type this code</div>${err ? `<div class="err">${esc(err)}</div>` : `<div class="big">${esc(code)}</div><div class="sub" style="text-align:center">The code works once and lasts 15 minutes.</div>`}
    <div class="msec">What it sees</div><div class="sub">Which program is in front, its window title (to tell YouTube from a homework tab) and how long since you used the mouse or keyboard. Never your screen, files, passwords or what you type. It shows an icon near the clock, and the family is told when it is linked.</div>
    <div class="foot"><button class="btn" id="agNew">New code</button><button class="btn solid" id="agX">Done</button></div>`, root => {
    root.querySelector("#agX").onclick = closeModal; root.querySelector("#agNew").onclick = agentDialog;
  });
}


/* ---------- lock a child's devices for a while (a parent's button, four hours a day at most) ---------- */
const LOCK_CHOICES = [[15, "15 min"], [30, "30 min"], [60, "1 hour"], [90, "1½ hours"], [120, "2 hours"], [180, "3 hours"], [240, "4 hours"]];
async function lockDialog(id) {
  const m = member(id); let d;
  try { d = await api("/api/lock?member=" + id); } catch (e) { return toast(e.message); }
  const left = Math.floor(d.left / 60), lk = d.lock;
  modal(`<h2>🔒 Lock ${esc(m.name)}'s devices</h2>
    <div class="sub">${lk ? `Locked by ${esc(lk.by_name)} until <b>${clock(lk.until)}</b>.` : "Their phone and computer show a lock screen for as long as you choose. They can still send an SOS or ask to be unlocked."}</div>
    <div class="sub"><b>${left >= 60 ? Math.floor(left / 60) + " h " + (left % 60 ? left % 60 + " min" : "") : left + " min"}</b> of lock time left today (the most is 4 hours a day).</div>
    ${lk ? `<div class="foot"><button class="btn" id="lkX">Close</button><button class="btn solid" id="lkOff">Unlock now</button></div>` :
      `<div class="seg" id="lkSeg">${LOCK_CHOICES.map(([mn, t]) => `<button class="btn ${mn > left ? "" : ""}" data-m="${mn}" ${mn > left ? "disabled" : ""}>${t}</button>`).join("")}</div>
       <div class="foot"><button class="btn" id="lkX">Cancel</button></div>`}`, root => {
    root.querySelector("#lkX").onclick = closeModal;
    const off = root.querySelector("#lkOff"); if (off) off.onclick = async () => { try { await api("/api/lock/" + id + "/release", "POST"); toast(m.name + " is unlocked"); closeModal(); refreshFamily(); } catch (e) { toast(e.message); } };
    root.querySelectorAll("#lkSeg [data-m]").forEach(b => b.onclick = async () => {
      try { const r = await api("/api/lock", "POST", { member: id, minutes: +b.dataset.m }); toast(`${m.name} is locked until ${clock(r.until)}`); closeModal(); refreshFamily(); } catch (e) { toast(e.message); } });
  });
}
const WAIT_IDEAS = ["Ride your bike 🚲", "Read a book 📖", "Bring someone a glass of water 💧", "Go outside for some fresh air 🌳", "Tidy up a little 🧹", "Draw or build something ✏️"];
let lockTimer = 0;
/** On a child's laptop: a full-screen lock while a parent has locked it. It always offers a way to ask for early release and to send an SOS. */
function paintLock() {
  const m = S.me && me(), lk = m && m.role === "child" && m.lock && m.lock.until > Date.now() / 1000 ? m.lock : null;
  let ov = document.getElementById("lockov");
  if (!lk) { if (ov) ov.remove(); clearInterval(lockTimer); return; }
  if (!ov) {
    ov = document.createElement("div"); ov.id = "lockov"; ov.setAttribute("role", "alertdialog"); ov.setAttribute("aria-label", "Device locked"); document.body.append(ov);
    ov.innerHTML = `<div class="lockbox"><div class="lockico">🔒</div><h1>Locked</h1><p id="lkWho"></p><div class="lockleft" id="lkLeft"></div><p class="lockidea" id="lkIdea"></p>
      <div class="seg" style="justify-content:center"><button class="btn" id="lkAsk">Ask to unlock early</button><button class="btn bad" id="lkSos">🆘 I need help</button></div></div>`;
    ov.querySelector("#lkIdea").textContent = "While you wait: " + WAIT_IDEAS[Math.floor(Math.random() * WAIT_IDEAS.length)];
    ov.querySelector("#lkAsk").onclick = async e => { try { await api("/api/lock/ask", "POST"); e.target.textContent = "Asked. They will see it."; e.target.disabled = true; } catch (err) { toast(err.message); } };
    ov.querySelector("#lkSos").onclick = async () => { if (confirm("Send an SOS to your family?")) { try { await sendMsg("sos", "I need help", where(), 0); toast("SOS sent to everyone"); } catch (e) { toast(e.message); } } };
  }
  ov.querySelector("#lkWho").textContent = `${lk.by_name} locked this device until ${clock(lk.until)}.`;
  clearInterval(lockTimer);
  const tick = () => { const s = Math.max(0, Math.round(lk.until - Date.now() / 1000)); const el = document.getElementById("lkLeft"); if (el) el.textContent = s >= 3600 ? `${Math.floor(s / 3600)} h ${Math.floor(s % 3600 / 60)} min left` : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")} left`; if (s <= 0) { clearInterval(lockTimer); refreshFamily().catch(() => {}); } };
  tick(); lockTimer = setInterval(tick, 1000);
}


/* ---------- the Home area: one special place per family ---------- */
async function saveHome(lat, lng, radius) {
  try { await api("/api/home", "POST", { lat, lng, radius }); toast("🏠 Home area saved"); closeModal(); await refreshFamily(); S.map.flyTo([lat, lng], 17); } catch (e) { toast(e.message); }
}
function homeDialog() {
  S.homeRadius = 200;
  modal(`<h2>🏠 Home area</h2><div class="sub">Everyone is told when someone gets home or leaves, and each person shows <b>At home</b> while inside this area.</div>
    <div class="msec">How big</div><div class="seg" id="hmSeg">${[[100, "Small, 100 m"], [200, "Normal, 200 m"], [400, "Big, 400 m"]].map(([r, t]) => `<button class="btn ${r === 200 ? "sel" : ""}" data-r="${r}">${t}</button>`).join("")}</div>
    <div class="msec">Where</div><div class="seg"><button class="btn solid" id="hmHere">Use this computer's location</button><button class="btn" id="hmPick">Click on the map</button></div>
    <div class="foot"><button class="btn" id="hmX">Cancel</button></div>`, root => {
    root.querySelector("#hmX").onclick = closeModal;
    root.querySelectorAll("#hmSeg [data-r]").forEach(b => b.onclick = () => { S.homeRadius = +b.dataset.r; root.querySelectorAll("#hmSeg .btn").forEach(x => x.classList.toggle("sel", x === b)); });
    root.querySelector("#hmPick").onclick = () => { closeModal(); S.homing = true; toast("Click the map where home is", 6000); };
    root.querySelector("#hmHere").onclick = () => navigator.geolocation ? navigator.geolocation.getCurrentPosition(p => saveHome(p.coords.latitude, p.coords.longitude, S.homeRadius), () => toast("Could not get this computer's location. Click the map instead."), { timeout: 10000 }) : toast("Click the map instead.");
  });
}


/* ---------- rules: house rules and a daily play/watch limit per child (parents edit, children read) ---------- */
async function rulesDialog() {
  let d; try { d = await api("/api/rules"); } catch (e) { return toast(e.message); }
  const parent = isParent(), mine = d.limits[S.me];
  modal(`<h2>📋 Family rules</h2><div class="sub">${parent ? "Everyone in the family can read these. Changing them is told to the family." : "These are your family's rules. Your parents set them."}</div>
    ${parent ? `<textarea class="field" id="ruText" rows="6" maxlength="1500" placeholder="e.g. Homework before games. Phones off at dinner. Bed at 9.">${esc(d.text)}</textarea>
      <div class="msec">Daily playing and watching limit</div><div class="sub">You and they get a friendly nudge when the limit is reached, and the family is told. Leave empty for no limit.</div>
      ${d.children.map(c => `<div class="sw"><div><b>${esc(c.emoji)} ${esc(c.name)}</b></div><span><input class="field" type="number" min="0" max="720" step="5" data-kid="${c.id}" value="${d.limits[c.id] || ""}" placeholder="minutes" style="width:110px"> min</span></div>`).join("") || '<div class="empty">No children in the family yet.</div>'}`
      : `<div class="rulesbox">${d.text ? esc(d.text).replace(/\n/g, "<br>") : "<i>No rules written yet.</i>"}</div>${mine ? `<div class="sub"><b>Your daily limit:</b> ${mine} minutes of playing and watching.</div>` : ""}`}
    <div class="foot"><button class="btn" id="ruX">${parent ? "Cancel" : "Close"}</button>${parent ? '<button class="btn solid" id="ruSave">Save</button>' : ""}</div>`, root => {
    root.querySelector("#ruX").onclick = closeModal;
    const save = root.querySelector("#ruSave"); if (save) save.onclick = async () => {
      const limits = {}; root.querySelectorAll("[data-kid]").forEach(i => { limits[i.dataset.kid] = +i.value || 0; });
      try { await api("/api/rules", "POST", { text: root.querySelector("#ruText").value, limits }); toast("Rules saved"); closeModal(); } catch (e) { toast(e.message); } };
  });
}


/* ---------- jobs: a parent sets them, a child ticks them off, a parent approves them for points ---------- */
async function refreshChores() { try { const d = await api("/api/chores"); S.chores = d.chores; S.chorePts = d.points; S.weekPts = d.week; S.streaks = d.streaks || {}; renderSide(); } catch (e) { /* offline: keep what we have */ } }
function choresHtml() {
  const kids = S.members.filter(m => m.role === "child"), parent = isParent(), mine = me();
  const row = c => {
    const who = c.done_by ? member(c.done_by) : null, forMe = !c.to || c.to === S.me, to = c.to ? member(c.to) : null;
    let act = "";
    if (c.status === "open" && mine.role === "child" && forMe) act = `<button class="btn sm solid" data-chore="done" data-id="${c.id}">Done</button>`;
    else if (c.status === "done" && parent) act = `<button class="btn sm" data-chore="redo" data-id="${c.id}">Redo</button><button class="btn sm solid" data-chore="ok" data-id="${c.id}">Approve</button>`;
    const state = c.status === "done" ? `<small>${esc(who ? who.name : "Someone")} finished it${parent ? "" : ". Waiting for a parent"}</small>` : c.status === "approved" ? `<small>✓ ${esc(who ? who.name : "")} · +${c.points}</small>` : `<small>${c.opens_at > Date.now() / 1000 ? "Back " + whenTxt(c.opens_at) : (to ? "For " + esc(to.name) : "Any child")} · ${c.points} points${c.repeats ? " · 🔁 " + (c.repeats === "weekly" ? "every week" : "every day") : ""}</small>`;
    return `<div class="item ${c.status === "approved" ? "done" : ""}"><span class="stemo">${esc(c.emoji)}</span><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${esc(c.title)}</b>${state}</span>${act}${parent && c.status !== "approved" ? `<button class="link" data-chore="del" data-id="${c.id}" title="Delete" aria-label="Delete job">✕</button>` : ""}</div>`;
  };
  const board = kids.length ? `<div class="board">${kids.map(k => `<span class="chip">${esc(k.emoji)} ${esc(k.name)} · ${S.weekPts[k.id] || 0} this week · ${S.chorePts[k.id] || 0} total${S.streaks[k.id] ? " · 🔥 " + S.streaks[k.id] : ""}</span>`).join("")}</div>` : "";
  const form = parent ? `<form id="choreForm" class="choreform"><input class="field" name="title" placeholder="Add a job, e.g. Take out the bins" maxlength="80" autocomplete="off"><div class="choreopts"><input class="field" name="points" type="number" min="0" max="100" value="5" aria-label="Points"><select class="field" name="to" aria-label="Who is it for"><option value="0">Any child</option>${kids.map(k => `<option value="${k.id}">${esc(k.name)}</option>`).join("")}</select><select class="field" name="repeat" aria-label="How often"><option value="">Once</option><option value="daily">Every day</option><option value="weekly">Every week</option></select><button class="btn" type="submit">Add</button></div></form>` : "";
  return board + (S.chores.map(row).join("") || `<div class="empty">${parent ? "No jobs yet. Add one below." : "No jobs right now."}</div>`) + form;
}


/* ---------- grounding: block certain apps on a child's laptop or tablet for a while ---------- */
const GROUND_PRESETS = ["Roblox", "Minecraft", "Fortnite", "YouTube", "TikTok", "Netflix", "Discord", "Steam"];
async function groundDialog(id) {
  const m = member(id); let active = [], avail = [];
  try { const d = await api("/api/blocks?member=" + id); active = d.blocks; avail = d.available || []; } catch (e) { return toast(e.message); }
  // real apps the computer reported, plus a few common ones; picked by key so the match is exact
  const byKey = {}; avail.forEach(a => byKey[a.key] = a.label); GROUND_PRESETS.forEach(n => { const k = n.toLowerCase(); if (!byKey[k]) byKey[k] = n; });
  const names = Object.entries(byKey).map(([key, label]) => ({ key, label })).sort((a, b) => a.label.localeCompare(b.label)), picked = new Set();
  modal(`<h2>🚫 Ground apps for ${esc(m.name)}</h2><div class="sub">Pick apps to lock on their laptop and tablet for a while. They are closed (or a grounded website is minimised) and ${esc(m.name)} is told why. The family sees it in the chat.</div>
    ${active.length ? `<div class="msec">Grounded now</div>${active.map(b => `<div class="sw"><div><b>${esc(b.label)}</b><span>until ${clock(b.until)}</span></div><button class="btn sm" data-lift="${b.id}">Lift</button></div>`).join("")}` : ""}
    <div class="msec">Choose apps ${avail.length ? `<small class="muted">(${avail.length} on their computer)</small>` : `<small class="muted">(link a device to see their apps)</small>`}</div><div class="seg grapps" id="grApps">${names.map(n => `<button class="btn sm" data-app="${esc(n.key)}" data-label="${esc(n.label)}">${esc(n.label)}</button>`).join("")}</div>
    <input class="field" id="grOther" placeholder="Another app or website name" maxlength="40" style="margin-top:10px">
    <div class="msec">For how long</div><div class="seg" id="grLen">${[[30, "30 min"], [60, "1 hour"], [120, "2 hours"], [240, "4 hours"], [720, "12 hours"], [1440, "Until tomorrow"]].map(([n, t]) => `<button class="btn sm ${n === 60 ? "sel" : ""}" data-m="${n}">${t}</button>`).join("")}</div>
    <div class="foot"><button class="btn" id="grX">Close</button><button class="btn solid" id="grGo">Ground them</button></div>`, root => {
    let mins = 60;
    root.querySelector("#grX").onclick = closeModal;
    root.querySelectorAll("#grApps [data-app]").forEach(b => b.onclick = () => { const n = b.dataset.app; picked.has(n) ? picked.delete(n) : picked.add(n); b.classList.toggle("sel", picked.has(n)); });
    root.querySelectorAll("#grLen [data-m]").forEach(b => b.onclick = () => { mins = +b.dataset.m; root.querySelectorAll("#grLen .btn").forEach(x => x.classList.toggle("sel", x === b)); });
    root.querySelectorAll("[data-lift]").forEach(b => b.onclick = async () => { try { await api("/api/blocks/" + b.dataset.lift, "DELETE"); toast("Lifted"); closeModal(); refreshFamily(); } catch (e) { toast(e.message); } });
    root.querySelector("#grGo").onclick = async () => {
      const other = root.querySelector("#grOther").value.trim();
      const list = [...[...picked].map(key => ({ key, label: byKey[key] || key })), ...(other ? [{ key: other.toLowerCase(), label: other }] : [])];
      if (!list.length) return toast("Pick at least one app.");
      try { await api("/api/blocks", "POST", { member: id, minutes: mins, apps: list }); toast(m.name + " is grounded from " + list.map(a => a.label).join(", ")); closeModal(); refreshFamily(); } catch (e) { toast(e.message); } };
  });
}


/* ---------- linked computers: when each last reported, so a switched-off tracker is noticed ---------- */
function deviceLine(m) {
  const ds = m.devices || []; if (!ds.length) return "";
  const now = Date.now() / 1000;
  return ds.map(d => { const quiet = now - d.last_seen > 20 * 60; return `<small class="${quiet ? "devwarn" : ""}">${d.kind === "windows" ? "💻" : "📱"} ${esc(d.name)} · ${d.last_seen ? (quiet ? "quiet for " : "reported ") + ago(d.last_seen) : "not reported yet"}${!quiet && d.status ? " · " + esc(d.status) : ""}</small>`; }).join("");
}


/* ---------- profile photo: shrink to a small square in the browser, then upload ---------- */
function uploadAvatar(file) {
  return new Promise((resolve, reject) => {
    const img = new Image(), url = URL.createObjectURL(file);
    img.onload = async () => {
      URL.revokeObjectURL(url);
      const S2 = 256, c = document.createElement("canvas"); c.width = S2; c.height = S2;
      const ctx = c.getContext("2d"), s = Math.min(img.width, img.height), sx = (img.width - s) / 2, sy = (img.height - s) / 2;
      ctx.drawImage(img, sx, sy, s, s, 0, 0, S2, S2);
      let data = c.toDataURL("image/jpeg", 0.82);
      if (data.length > 180 * 1024) data = c.toDataURL("image/jpeg", 0.6);
      try { const r = await api("/api/avatar", "POST", { image: data }); resolve(r.avatar); } catch (e) { reject(e); }
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("That image could not be read.")); };
    img.src = url;
  });
}


/* ---------- computers and tablets linked to a person ---------- */
function devicesDialog(id) {
  const m = member(id) || me(), mine = id === S.me, ds = m.devices || [], now = Date.now() / 1000;
  modal(`<h2>💻 ${mine ? "My computers" : esc(m.name) + "'s computers"}</h2><div class="sub">${ds.length ? "These count screen time, follow locks and grounded apps, and show pop-ups." : "No computer is linked yet."}</div>
    ${ds.map(d => { const quiet = now - d.last_seen > 20 * 60; return `<div class="sw"><div><b>${d.kind === "windows" ? "💻" : "📱"} ${esc(d.name)}</b><span class="${quiet ? "devwarn" : ""}">${d.last_seen ? (quiet ? "Quiet for " : "Reported ") + ago(d.last_seen) : "Not reported yet"}${d.status && !quiet ? " · " + esc(d.status) : ""}${d.version ? " · " + esc(d.version) : ""}</span></div>${mine ? `<button class="btn sm bad" data-rm="${d.id}">Remove</button>` : ""}</div>`; }).join("")}
    <div class="foot">${mine ? '<button class="btn solid" id="dvAdd">Link a Windows computer</button>' : ""}<button class="btn" id="dvX">Close</button></div>`, root => {
    root.querySelector("#dvX").onclick = closeModal;
    const add = root.querySelector("#dvAdd"); if (add) add.onclick = () => { closeModal(); agentDialog(); };
    root.querySelectorAll("[data-rm]").forEach(b => b.onclick = async () => { if (!confirm("Remove this computer? Its tracker stops working.")) return; try { await api("/api/devices/" + b.dataset.rm, "DELETE"); toast("Removed"); closeModal(); refreshFamily(); } catch (e) { toast(e.message); } });
  });
}


/* ---------- today's screen time on a child's card ---------- */
function screenLine(m) {
  const s = m.screen; if (!s) return "";
  if (s.hidden) return `<small class="devwarn">⏱ ${esc(m.name)} has switched screen time off</small>`;
  return `<small>⏱ ${fmtDur(s.today)} today${s.top ? " · most on " + esc(s.top) : ""}</small>`;
}
/* A zero is never a mystery: say what this person's freshest computer is doing (or that it has gone quiet, e.g. after a crash or reboot). */
function whyNothing(m, short) {
  const ds = (m.devices || []).filter(d => d.last_seen).sort((a, b) => b.last_seen - a.last_seen); if (!ds.length) return "";
  const d = ds[0], quiet = Date.now() / 1000 - d.last_seen > 20 * 60, gap = ago(d.last_seen).replace(" ago", "");
  if (quiet) return short ? `${d.name} quiet for ${gap}` : `${d.name} has not reported for ${gap}. Homebase may not be running on it (a restart can stop it).`;
  return short ? `${d.name} is on` : `${d.name} is on${d.status ? " (" + d.status + ")" : ""}. It counts only while a game or video is playing.`;
}
function screenSub(m) { const s = m.screen; if (!s) return "Minutes and apps"; if (s.hidden) return "Switched off by " + esc(m.name); if (s.today) return `Today ${fmtDur(s.today)}${s.top ? " · " + esc(s.top) : ""}`; const w = whyNothing(m, true); return "Nothing counted today" + (w ? " · " + esc(w) : ""); }
