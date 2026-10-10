"use strict";
/* Homebase on a laptop: the same family, map, chat and calls as the Android app. Works offline: it keeps the last
   thing it saw, shows people at their last known spot, and sends your messages when the connection comes back. */
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const store = (k, v) => { try { v === undefined ? localStorage.removeItem(k) : localStorage.setItem(k, typeof v === "string" ? v : JSON.stringify(v)); } catch (e) { /* storage full or blocked */ } };
const load = (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } };
const EMOJI = ["🧒", "👦", "👧", "🧑", "😎", "🦊", "🐱", "🙂", "👩", "👨"];
const PERMS = [
  ["location", "See where I am", "Parents see you on the map. Off means they only see \"not sharing\"."],
  ["trail", "See my trail", "The line of where you have been in the last day."],
  ["battery", "See my battery", "So they know if your device is about to die."],
  ["messages", "Send me messages", "Your check-ins and SOS always reach them, and you can still message them."],
  ["calls", "Call me", "You can always call your parents."],
  ["ping", "Ask \"Where are you?\"", "A request you can answer or decline either way."],
];
const PERM_NAMES = { location: "location", trail: "trail", battery: "battery", messages: "messages", calls: "calls", ping: "where-are-you" };

const S = { token: localStorage.getItem("hb_token") || "", me: 0, family: null, members: [], places: [], ice: [], thread: 0, msgs: {}, unread: {}, items: [],
  map: null, markers: {}, circles: [], trail: null, adding: false, cursor: -1, running: false, geo: null, lastPos: null, lastSent: 0, focusedOnce: false,
  online: navigator.onLine, open: 0, chatOpen: false, outbox: load("hb_out", []), savedAt: 0, tmp: -1 };

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
  if (v && S.token) { flushOutbox(); refreshFamily().catch(() => {}); refreshItems(); if (S.thread !== undefined) loadThread(S.thread, true); }
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
function applyTheme() { const t = localStorage.getItem("hb_theme") || "auto"; if (t === "auto") document.documentElement.removeAttribute("data-theme"); else document.documentElement.dataset.theme = t; }
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
async function startApp(fresh) {
  $("#auth").classList.add("hidden"); $("#app").classList.remove("hidden");
  initMap(); initChrome(); paintOffline();
  if (!loadSnap() && !fresh) { /* first run: nothing to show yet */ }
  try { await refreshFamily(); } catch (e) { if (!e.offline) return; }
  loadThread(0); refreshItems(); flushOutbox();
  if (!S.running) { S.running = true; eventLoop(); }
  setInterval(() => { if (!document.hidden && S.token && S.online) refreshFamily().catch(() => {}); }, 12000);
  setInterval(() => { if (S.token && S.online) flushOutbox(); }, 15000);
  if (me().share === "on" && localStorage.getItem("hb_geo") === "1") startGeo();
  document.addEventListener("click", () => { if ("Notification" in window && Notification.permission === "default") Notification.requestPermission(); }, { once: true });
  if (window.innerWidth >= 1280 && !S.chatOpen) setChat(false);
}
async function refreshFamily() {
  const d = await api("/api/family");
  S.me = d.me; S.family = d.family; S.members = d.members; S.places = d.places; S.ice = d.ice_servers || [];
  $("#famname").textContent = "· " + d.family.name; saveSnap(); renderAll();
}
function renderAll() {
  const parent = isParent(); $("#bAddPlace").classList.toggle("hidden", !parent); $("#bInvite").classList.toggle("hidden", !parent);
  renderSide(); renderMap(); renderThreads(); renderComposer(); renderMsgs();
}

/* ---------- people (the left panel) ---------- */
function statusLine(m) {
  let w;
  if (m.share === "paused") w = "Location paused";
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
  return `<section class="you"><div class="head"><div class="ava ${on ? "on" : paused ? "paused" : ""}">${esc(m.emoji)}</div><div><b>${esc(m.name)} (you)</b><br><small>${m.role === "parent" ? "Parent" : "Child"}</small></div></div>
    <div class="shareline"><div><b>Share my location</b><small>${hint}</small></div><button class="tg ${on ? "on" : ""}" id="tShare" aria-label="Share my location"></button></div>
    ${on && !S.geo ? `<button class="btn solid" id="bAllow">Allow this laptop to share</button>` : ""}
    ${on || paused ? `<div class="quick">Pause for <button class="btn sm" data-pause="15">15 min</button><button class="btn sm" data-pause="60">1 hour</button>${paused ? `<button class="btn sm" data-pause="0">Resume</button>` : ""}</div>` : ""}</section>`;
}
function renderSide() {
  const others = S.members.filter(m => m.id !== S.me);
  const people = others.map(m => {
    const open = S.open === m.id, dot = m.share === "on" && m.location ? "on" : m.share === "paused" ? "paused" : "";
    const acts = open ? `<div class="pacts"><button class="btn sm" data-act="msg" data-id="${m.id}" ${may(m, "messages") ? "" : "disabled"}>Message</button><button class="btn sm" data-act="call" data-id="${m.id}" ${may(m, "calls") ? "" : "disabled"}>Call</button><button class="btn sm" data-act="ping" data-id="${m.id}" ${may(m, "ping") ? "" : "disabled"}>Where?</button><button class="btn sm" data-act="trail" data-id="${m.id}" ${may(m, "trail") ? "" : "disabled"}>Trail</button></div>` : "";
    return `<div class="person ${open ? "open" : ""}"><button class="prow" data-act="pick" data-id="${m.id}"><span class="ava ${dot}">${esc(m.emoji)}</span><span class="pinfo"><b>${esc(m.name)}</b><small>${esc(statusLine(m))}</small></span><span class="chip">${m.role === "parent" ? "Parent" : "Child"}</span></button>${offTags(m)}${acts}</div>`;
  }).join("") || `<div class="empty">Nobody else is here yet. ${isParent() ? "Press Invite to add someone." : ""}</div>`;
  const places = S.places.map(p => `<div class="place"><span>${esc(p.emoji)} ${esc(p.name)} <small>${p.radius} m</small></span>${isParent() ? `<button class="btn sm" data-act="delplace" data-id="${p.id}">Remove</button>` : ""}</div>`).join("") || `<div class="empty">No saved places yet.${isParent() ? " Press ＋ Place on the map." : ""}</div>`;
  const items = S.items.map(i => `<label class="item ${i.done ? "done" : ""}"><input type="checkbox" data-act="tick" data-id="${i.id}" ${i.done ? "checked" : ""}><span style="flex:1">${esc(i.text)}</span><button class="link" data-act="delitem" data-id="${i.id}" title="Delete" aria-label="Delete">✕</button></label>`).join("");
  const keep = $("#itemIn") && document.activeElement === $("#itemIn") ? $("#itemIn").value : "";
  $("#side").innerHTML = youCard() + `<div class="sechead"><h3>People</h3></div>${people}<div class="sechead"><h3>Saved places</h3></div>${places}<div class="sechead"><h3>Family list</h3></div>${items || '<div class="empty">Nothing on the list.</div>'}
    <form id="itemForm" style="display:flex;gap:8px"><input class="field" id="itemIn" placeholder="Add to the list" maxlength="120" autocomplete="off" value="${esc(keep)}"><button class="btn" type="submit">Add</button></form>`;
  if (keep) { const i = $("#itemIn"); i.focus(); i.setSelectionRange(keep.length, keep.length); }
}

/* ---------- map ---------- */
function initMap() {
  if (S.map) return;
  S.map = L.map("map", { zoomControl: false, attributionControl: true }).setView([24, 20], 2);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(S.map);
  S.map.on("click", e => { if (S.adding) addPlaceDialog(e.latlng); });
  new ResizeObserver(() => S.map.invalidateSize()).observe($("#map")); paintLayer();
}
const paintLayer = () => $("#map").classList.toggle("dark", mapDark());
function pad() { const w = window.innerWidth; return w < 820 ? { paddingTopLeft: [30, 90], paddingBottomRight: [30, 40 + window.innerHeight * .52] } : { paddingTopLeft: [420, 100], paddingBottomRight: [S.chatOpen ? 450 : 60, 90] }; }
function pinIcon(m) {
  const stale = !isLive(m) && Date.now() / 1000 - m.location.at > 900;
  const label = esc(m.name) + (stale ? ` · ${ago(m.location.at)}` : "");
  return L.divIcon({ className: "", iconSize: [50, 50], iconAnchor: [25, 25], html: `<div class="pin ${stale ? "old" : ""} ${m.role === "parent" ? "parent" : ""}">${esc(m.emoji)}</div><div class="pinname">${label}</div>` });
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
  S.places.forEach(p => { S.circles.push(L.circle([p.lat, p.lng], { radius: p.radius, color: "#2f6df6", weight: 2, opacity: .7, fillColor: "#2f6df6", fillOpacity: .1 }).addTo(S.map).bindTooltip(p.emoji + " " + p.name)); });
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
    S.trail = L.polyline(d.points.map(p => [p.lat, p.lng]), { color: "#2f6df6", weight: 4, opacity: .85, dashArray: "2 8" }).addTo(S.map);
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
function renderComposer() {
  const t = member(S.thread), blocked = t && !may(t, "messages");
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
  return `<div class="b ${mine ? "me" : ""} ${cls} ${m.pending ? "pending" : ""}" ${m.lat != null ? `data-lat="${m.lat}" data-lng="${m.lng}"` : ""}>${S.thread === 0 && !mine ? `<div class="who">${esc(who)}</div>` : ""}${body}<small>${m.pending ? "⏳ waiting to send" : clock(m.at)}</small></div>`;
}
function renderMsgs() {
  const list = S.msgs[S.thread] || [], box = $("#msgs"); if (!box) return;
  box.innerHTML = list.length ? list.map(bubble).join("") : `<div class="empty" style="margin:auto;text-align:center">No messages yet.<br>Say hello 👋</div>`;
  box.scrollTop = box.scrollHeight;
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
  const theme = localStorage.getItem("hb_theme") || "auto", layer = localStorage.getItem("hb_map") || "light";
  const perms = m.role === "child" ? `<div class="msec">What parents can do</div><div class="sub">You decide. Switch something off and Homebase stops it straight away. Your parents see that it is off.</div>` +
    PERMS.map(([k, t, why]) => `<div class="sw"><div><b>${t}</b><span>${why}</span></div><button class="tg ${m.perms[k] !== false ? "on" : ""}" data-perm="${k}" aria-label="${t}"></button></div>`).join("") : "";
  modal(`<h2>${esc(m.emoji)} ${esc(m.name)}</h2><div class="sub">${m.role === "parent" ? "Parent" : "Child"} · ${esc(S.family?.name || "")}</div>
    <div class="msec">Appearance</div><div class="seg" id="segTheme">${[["auto", "Match my computer"], ["light", "Light"], ["dark", "Dark"]].map(([v, t]) => `<button class="btn sm ${theme === v ? "sel" : ""}" data-v="${v}">${t}</button>`).join("")}</div>
    <div class="seg" id="segMap">${[["light", "Light map"], ["dark", "Dark map"]].map(([v, t]) => `<button class="btn sm ${layer === v ? "sel" : ""}" data-v="${v}">${t}</button>`).join("")}</div>
    ${perms}
    <div class="msec">Quiet hours</div><div class="sub">Messages stay quiet and calls do not ring. An urgent "ring anyway" still works.</div>
    <div style="display:flex;gap:10px;align-items:center"><input class="field" type="time" id="qFrom" value="${hm(q.from)}"><span class="muted">to</span><input class="field" type="time" id="qTo" value="${hm(q.to)}"><button class="btn sm" id="qSave">Save</button><button class="btn sm" id="qOff">Off</button></div>
    <div class="msec">Your data</div><div class="seg"><button class="btn sm" id="mHist">Delete my location history</button><a class="btn sm" href="/download">Android app</a>${window.__installPrompt ? '<button class="btn sm" id="mInstall">Install on this computer</button>' : ""}</div>
    <div class="foot" style="justify-content:space-between"><button class="btn bad" id="mLeave">Leave family</button><span><button class="btn" id="mOut">Sign out</button> <button class="btn solid" id="mDone">Done</button></span></div>`, root => {
    root.querySelectorAll("#segTheme [data-v]").forEach(b => b.onclick = () => { store("hb_theme", b.dataset.v); applyTheme(); root.querySelectorAll("#segTheme .btn").forEach(x => x.classList.toggle("sel", x === b)); });
    root.querySelectorAll("#segMap [data-v]").forEach(b => b.onclick = () => { store("hb_map", b.dataset.v); paintLayer(); root.querySelectorAll("#segMap .btn").forEach(x => x.classList.toggle("sel", x === b)); });
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
  notify(`${p.name} asked where you are`, "Open Homebase to answer");
  modal(`<h2>${esc(p.emoji)} ${esc(p.name)} asked where you are</h2><div class="sub">You can share your location this once, or say not now. Either way is fine.</div><div class="foot"><button class="btn" id="pNo">Not now</button><button class="btn solid" id="pYes">Share my location</button></div>`, root => {
    root.querySelector("#pNo").onclick = async () => { closeModal(); await api("/api/ping/" + p.id, "POST", { accept: false }).catch(() => {}); };
    root.querySelector("#pYes").onclick = async () => { closeModal();
      try { const pos = await new Promise((ok, no) => navigator.geolocation.getCurrentPosition(ok, no, { timeout: 8000 })); await api("/api/ping/" + p.id, "POST", { accept: true, lat: pos.coords.latitude, lng: pos.coords.longitude }); toast("Shared"); }
      catch (e) { await api("/api/ping/" + p.id, "POST", { accept: false }).catch(() => {}); toast("Could not get your location, so it was declined."); } };
  });
}

/* ---------- events (messages, calls and so on, the moment they happen) ---------- */
function notify(title, body) { if (document.hidden && "Notification" in window && Notification.permission === "granted") { try { new Notification(title, { body, icon: "/static/web/icon-192.png" }); } catch (e) { /* ignore */ } } }
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
      if (p.kind !== "system" && !p.quiet) { notify(p.name || "Homebase", p.body || ""); if (p.kind === "sos") toast("🆘 " + (p.name || "Someone") + " needs help", 7000); }
      saveSnap(); break; }
    case "member": case "places": refreshFamily(); break;
    case "list": refreshItems(); break;
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
  $("#thr").onclick = e => { const b = e.target.closest("[data-th]"); if (b) loadThread(+b.dataset.th); };
  $("#composer").onsubmit = async e => { e.preventDefault(); const v = $("#msgIn").value.trim(); if (!v) return; $("#msgIn").value = ""; try { await sendMsg("text", v); } catch (err) { toast(err.message); $("#msgIn").value = v; } };
  $("#msgs").onclick = e => { const b = e.target.closest("[data-lat]"); if (b) { S.map.flyTo([+b.dataset.lat, +b.dataset.lng], 16); L.popup().setLatLng([+b.dataset.lat, +b.dataset.lng]).setContent("📍 Here").openOn(S.map); } };
  $("#side").onclick = async e => {
    const pause = e.target.closest("[data-pause]"); if (pause) { const v = +pause.dataset.pause; return setSharing(v ? "paused" : "on", v); }
    if (e.target.closest("#tShare")) { return setSharing(me().share === "on" ? "off" : "on"); }
    if (e.target.closest("#bAllow")) { return startGeo(); }
    const el = e.target.closest("[data-act]"); if (!el) return; const id = +el.dataset.id, m = member(id), act = el.dataset.act;
    if (act === "pick") { S.open = S.open === id ? 0 : id; renderSide(); if (S.open) focusMember(id); }
    else if (act === "msg") { setChat(true); loadThread(id); }
    else if (act === "call") window.Calls && window.Calls.start(m);
    else if (act === "ping") askWhere(m);
    else if (act === "trail") showTrail(id);
    else if (act === "delplace") { if (confirm("Remove this place?")) { await api("/api/places/" + id, "DELETE").catch(err => toast(err.message)); refreshFamily(); } }
    else if (act === "delitem") { await api("/api/list/" + id, "DELETE").catch(err => toast(err.message)); refreshItems(); }
  };
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
