"use strict";
/* Homebase for laptops: the same family, map, chat and calls as the Android app, in a browser. */
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));
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
  map: null, markers: {}, circles: [], trail: null, adding: false, cursor: -1, running: false, geo: null, lastPos: null, lastSent: 0, focusedOnce: false };

/* ---------- helpers ---------- */
async function api(path, method = "GET", body) {
  const r = await fetch(path, { method, headers: { "Content-Type": "application/json", ...(S.token ? { Authorization: "Bearer " + S.token } : {}) }, body: body ? JSON.stringify(body) : undefined });
  let d = {}; try { d = await r.json(); } catch (e) { /* empty body */ }
  if (r.status === 401 && S.token) { signOut(); throw Object.assign(new Error("Sign in again."), { status: 401 }); }
  if (!r.ok) throw Object.assign(new Error(d.error || "Something went wrong."), { status: r.status, data: d });
  return d;
}
function toast(t, ms = 2800) { const e = document.createElement("div"); e.className = "toast"; e.textContent = t; $("#toasts").append(e); setTimeout(() => e.remove(), ms); }
function ago(t) { const s = Math.max(0, Date.now() / 1000 - t); if (s < 60) return "just now"; if (s < 3600) return Math.round(s / 60) + " min ago"; if (s < 86400) return Math.round(s / 3600) + " h ago"; return Math.round(s / 86400) + " d ago"; }
function clock(t) { return new Date(t * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); }
const me = () => S.members.find(m => m.id === S.me) || { id: S.me, name: "", role: "child", perms: {} };
const member = id => S.members.find(m => m.id === id);
const isParent = () => me().role === "parent";
function may(m, key) { return !(isParent() && m.role === "child" && m.perms && m.perms[key] === false); }
function modal(html, onMount) { const o = $("#over"); o.innerHTML = `<div class="modal">${html}</div>`; o.classList.add("on"); o.onclick = e => { if (e.target === o) closeModal(); }; onMount && onMount(o.firstChild); }
function closeModal() { const o = $("#over"); o.classList.remove("on"); o.innerHTML = ""; }
function emojiPicker(sel) { return `<div class="emojis" id="emo">${EMOJI.map(e => `<button type="button" class="${e === sel ? "on" : ""}" data-e="${e}">${e}</button>`).join("")}</div>`; }
function pickEmoji(root, set) { root.querySelectorAll("#emo button").forEach(b => b.onclick = () => { root.querySelectorAll("#emo button").forEach(x => x.classList.toggle("on", x === b)); set(b.dataset.e); }); }

/* ---------- sign in ---------- */
function showAuth(view, ctx = {}) {
  $("#app").classList.add("hidden"); $("#auth").classList.remove("hidden");
  const box = $("#authbox");
  if (view === "welcome") {
    box.innerHTML = `<h1 class="silver">Homebase</h1><p class="tag">Stay close. Stay yourself.</p>
      <p class="muted" style="margin-top:14px;line-height:1.5">Location, messages and calls for your family, now on your laptop. Honest about every bit of it. Nothing is hidden, and you can always pause.</p>
      <div class="stack"><button class="btn solid" id="aCreate">Create a family (parent)</button><button class="btn" id="aJoin">Join with a code</button><a class="link" href="/download">Get the Android app</a></div>`;
    $("#aCreate").onclick = () => showAuth("create"); $("#aJoin").onclick = () => showAuth("join");
  } else if (view === "create" || view === "join") {
    let emoji = ctx.emoji || (view === "create" ? "👩" : "🧒");
    box.innerHTML = `<h1 class="silver" style="font-size:40px">${view === "create" ? "Create your family" : "Join your family"}</h1>
      <p class="muted" style="margin-top:8px">${view === "create" ? "You will be a parent. You can invite everyone else after." : "Ask a parent to open Homebase, tap Invite, and read you the code."}</p>
      ${view === "create" ? `<label class="l">Family name</label><input class="field" id="fFam" maxlength="40" placeholder="The Vales" value="${esc(ctx.family || "")}">` : `<label class="l">Invite code</label><input class="field" id="fCode" maxlength="12" placeholder="8 letters and numbers" style="letter-spacing:.2em;text-transform:uppercase" value="${esc(ctx.code || "")}">`}
      <label class="l">Your name</label><input class="field" id="fName" maxlength="40" value="${esc(ctx.name || "")}">
      <label class="l">Pick your picture</label>${emojiPicker(emoji)}
      <div class="err" id="aErr"></div>
      <div class="stack" style="margin-top:8px"><button class="btn solid" id="aGo">Continue</button><button class="link" id="aBack">Back</button></div>`;
    pickEmoji(box, e => emoji = e);
    $("#aBack").onclick = () => showAuth("welcome");
    $("#aGo").onclick = async () => {
      const name = $("#fName").value.trim(); if (!name) { $("#aErr").textContent = "Add your name first."; return; }
      try {
        if (view === "create") { const d = await api("/api/family", "POST", { family: $("#fFam").value.trim() || "Our family", name, emoji }); enter(d); }
        else { const code = $("#fCode").value.trim(); if (!code) { $("#aErr").textContent = "Enter the invite code."; return; } const look = await api("/api/invite/" + encodeURIComponent(code)); showAuth("consent", { code, name, emoji, look }); }
      } catch (e) { $("#aErr").textContent = e.message; }
    };
  } else if (view === "consent") {
    const l = ctx.look;
    box.innerHTML = `<h1 class="silver" style="font-size:34px">${esc(l.invited_by || "Someone")} invited you to ${esc(l.family)}</h1><p class="muted" style="margin:8px 0 14px">Read this first. You decide if you want to join.</p>
      <div style="display:grid;gap:10px">
      <div class="card yes"><h4>Your family will see</h4><ul><li>✓ Where you are, on a map, while sharing is on</li><li>✓ Your battery level</li><li>✓ Messages and check-ins you send them</li><li>✓ When you arrive at or leave a saved place. You get the same message.</li></ul></div>
      <div class="card no"><h4>Homebase will never</h4><ul><li>✕ Hide itself, or share anything in secret</li><li>✕ Read your other apps, browsing or private messages</li><li>✕ Switch on your microphone or camera by itself</li><li>✕ Lock your device or limit your screen time</li></ul></div>
      <div class="card"><h4>You are in control</h4><ul><li>• Pause sharing whenever you need. The family sees it is paused.</li><li>• "Where are you?" is a request: you can say not now.</li><li>• You choose what parents can do, from the Me menu.</li><li>• Leave any time and everything you shared is deleted.</li></ul></div></div>
      <div class="err" id="aErr"></div>
      <div class="stack" style="margin-top:8px"><button class="btn solid" id="aYes">I understand, join</button><button class="link" id="aNo">Not now</button></div>`;
    $("#aNo").onclick = () => showAuth("welcome");
    $("#aYes").onclick = async () => { try { enter(await api("/api/join", "POST", { code: ctx.code, name: ctx.name, emoji: ctx.emoji })); } catch (e) { $("#aErr").textContent = e.message; } };
  }
}
function enter(d) { S.token = d.token; S.me = d.me.id; localStorage.setItem("hb_token", d.token); history.replaceState(null, "", location.pathname); startApp(); }
function signOut() { S.running = false; stopGeo(); localStorage.removeItem("hb_token"); S.token = ""; location.reload(); }

/* ---------- app ---------- */
async function startApp() {
  $("#auth").classList.add("hidden"); $("#app").classList.remove("hidden");
  initMap(); initChrome();
  try { await refreshFamily(); } catch (e) { return; }
  loadThread(0); refreshItems();
  if (!S.running) { S.running = true; eventLoop(); }
  setInterval(() => { if (!document.hidden && S.token) refreshFamily().catch(() => {}); }, 12000);
  if (localStorage.getItem("hb_geo") === "1") startGeo();
  document.addEventListener("click", () => { if ("Notification" in window && Notification.permission === "default") Notification.requestPermission(); }, { once: true });
}

async function refreshFamily() {
  const d = await api("/api/family");
  S.me = d.me; S.family = d.family; S.members = d.members; S.places = d.places; S.ice = d.ice_servers || [];
  $("#famname").textContent = "· " + d.family.name;
  const parent = isParent();
  $("#bAddPlace").classList.toggle("hidden", !parent); $("#bInvite").classList.toggle("hidden", !parent);
  renderSide(); renderMap(); renderThreads(); renderComposer();
}

function statusLine(m) {
  let w = m.share === "paused" ? "Location paused" : m.share === "off" ? "Not sharing location" : m.location ? "Seen " + ago(m.location.at) : "Waiting for a location";
  if (m.battery >= 0 && m.battery <= 100) w += " · 🔋 " + m.battery + "%";
  return w;
}
function offTag(m) { const off = Object.keys(PERM_NAMES).filter(k => m.perms && m.perms[k] === false).map(k => PERM_NAMES[k]); return off.length ? `<span class="tagoff">off: ${esc(off.join(", "))}</span>` : ""; }

function renderSide() {
  const mine = S.me;
  const people = S.members.map(m => {
    const self = m.id === mine;
    const dot = m.share === "on" && m.location ? "on" : m.share === "paused" ? "paused" : "";
    const acts = self ? "" : `<div class="acts">
        <button class="btn sm" data-act="msg" data-id="${m.id}" ${may(m, "messages") ? "" : "disabled"}>Message</button>
        <button class="btn sm" data-act="call" data-id="${m.id}" ${may(m, "calls") ? "" : "disabled"}>Call</button>
        <button class="btn sm" data-act="ping" data-id="${m.id}" ${may(m, "ping") ? "" : "disabled"}>Where?</button>
        <button class="btn sm" data-act="trail" data-id="${m.id}" ${may(m, "trail") ? "" : "disabled"}>Trail</button></div>`;
    return `<div class="mem"><div class="row" data-act="focus" data-id="${m.id}"><div class="av">${esc(m.emoji)}<i class="dot ${dot}"></i></div>
      <div><b>${esc(m.name)}${self ? " (you)" : ""}</b> <span class="muted" style="font-size:12px">${m.role === "parent" ? "Parent" : "Child"}</span><div class="st">${esc(statusLine(m))}</div>${offTag(m)}</div></div>${acts}</div>`;
  }).join("");
  const places = S.places.map(p => `<div class="place"><span>${esc(p.emoji)} ${esc(p.name)} <span class="muted">· ${p.radius} m</span></span>${isParent() ? `<button class="btn sm" data-act="delplace" data-id="${p.id}">Remove</button>` : ""}</div>`).join("") || `<div class="muted" style="font-size:13px">No saved places yet. ${isParent() ? "Add one with ＋ Place on the map." : ""}</div>`;
  const items = S.items.map(i => `<label class="item ${i.done ? "done" : ""}"><input type="checkbox" data-act="tick" data-id="${i.id}" ${i.done ? "checked" : ""}><span style="flex:1">${esc(i.text)}</span><button class="link" data-act="delitem" data-id="${i.id}" title="Delete">✕</button></label>`).join("");
  $("#side").innerHTML = `<div class="sec"><h3>People</h3></div>${people}
    <div class="sec"><h3>Saved places</h3></div>${places}
    <div class="sec"><h3>Family list</h3></div>${items || '<div class="muted" style="font-size:13px">Nothing on the list.</div>'}
    <form id="itemForm" style="display:flex;gap:8px"><input class="field" id="itemIn" placeholder="Add to the list" maxlength="120" autocomplete="off"><button class="btn" type="submit">Add</button></form>`;
}

/* ---------- map ---------- */
function initMap() {
  if (S.map) return;
  S.map = L.map("map", { zoomControl: false, attributionControl: true }).setView([24, 20], 2);
  L.control.zoom({ position: "bottomright" }).addTo(S.map);
  new ResizeObserver(() => S.map.invalidateSize()).observe($("#mapwrap"));
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(S.map);
  S.map.on("click", e => { if (S.adding) addPlaceDialog(e.latlng); });
}
function pinIcon(m) {
  const old = m.location && Date.now() / 1000 - m.location.at > 900;
  return L.divIcon({ className: "", iconSize: [44, 44], iconAnchor: [22, 22], html: `<div class="pin ${old ? "old" : ""} ${m.id === S.me ? "me" : ""}">${esc(m.emoji)}</div><div class="pinname">${esc(m.name)}</div>` });
}
function renderMap() {
  const seen = new Set();
  S.members.forEach(m => {
    if (!m.location) return;
    seen.add(m.id);
    const ll = [m.location.lat, m.location.lng];
    if (S.markers[m.id]) { S.markers[m.id].setLatLng(ll).setIcon(pinIcon(m)); } else { S.markers[m.id] = L.marker(ll, { icon: pinIcon(m) }).addTo(S.map); }
  });
  Object.keys(S.markers).forEach(id => { if (!seen.has(+id)) { S.map.removeLayer(S.markers[id]); delete S.markers[id]; } });
  S.circles.forEach(c => S.map.removeLayer(c)); S.circles = [];
  S.places.forEach(p => { S.circles.push(L.circle([p.lat, p.lng], { radius: p.radius, color: "#fff", weight: 1, opacity: .6, fillColor: "#fff", fillOpacity: .06 }).addTo(S.map).bindTooltip(p.emoji + " " + p.name)); });
  if (!S.focusedOnce && seen.size) { fitAll(); S.focusedOnce = true; }
}
function fitAll() {
  const pts = Object.values(S.markers).map(m => m.getLatLng());
  if (!pts.length) { toast("Nobody is sharing a location yet."); return; }
  if (pts.length === 1) S.map.setView(pts[0], 15); else S.map.fitBounds(L.latLngBounds(pts), { padding: [70, 70], maxZoom: 16 });
}
function focusMember(id) { const mk = S.markers[id]; if (mk) { setTab("map"); S.map.flyTo(mk.getLatLng(), Math.max(S.map.getZoom(), 15)); } else toast(member(id)?.share === "off" ? "They are not sharing location." : "No location yet."); }
async function showTrail(id) {
  if (S.trail) { S.map.removeLayer(S.trail); S.trail = null; }
  try {
    const d = await api("/api/history?member=" + id + "&hours=24");
    if (!d.points.length) { toast("No trail in the last day."); return; }
    S.trail = L.polyline(d.points.map(p => [p.lat, p.lng]), { color: "#fff", weight: 3, opacity: .8, dashArray: "2 6" }).addTo(S.map);
    setTab("map"); S.map.fitBounds(S.trail.getBounds(), { padding: [70, 70], maxZoom: 16 }); toast("Last 24 hours. Press Trail again to hide it.");
  } catch (e) { toast(e.message); }
}
function addPlaceDialog(ll) {
  S.adding = false; $("#bAddPlace").textContent = "＋ Place"; document.querySelector(".hint")?.remove();
  let emoji = "📍";
  modal(`<h2>New place</h2><div class="sub">The family gets a message when someone arrives or leaves.</div><label class="l">Name</label><input class="field" id="pName" maxlength="40" placeholder="School">
    <label class="l">Icon</label><div class="emojis" id="emo"></div>
    <label class="l">Size</label><select class="field" id="pRad"><option value="100">Small (100 m)</option><option value="150" selected>Normal (150 m)</option><option value="300">Large (300 m)</option></select>
    <div class="err" id="pErr"></div><div class="foot"><button class="btn" id="pCancel">Cancel</button><button class="btn solid" id="pSave">Save place</button></div>`, root => {
    root.querySelector("#emo").innerHTML = ["📍", "🏫", "🏠", "⚽", "🏋️", "☕", "🏥", "🛒"].map(e => `<button type="button" class="${e === emoji ? "on" : ""}" data-e="${e}">${e}</button>`).join("");
    pickEmoji(root, e => emoji = e);
    root.querySelector("#pCancel").onclick = closeModal;
    root.querySelector("#pSave").onclick = async () => {
      const name = root.querySelector("#pName").value.trim(); if (!name) { root.querySelector("#pErr").textContent = "Give the place a name."; return; }
      try { await api("/api/places", "POST", { name, emoji, lat: ll.lat, lng: ll.lng, radius: +root.querySelector("#pRad").value }); closeModal(); refreshFamily(); toast("Place saved"); } catch (e) { root.querySelector("#pErr").textContent = e.message; }
    };
  });
}

/* ---------- chat ---------- */
function threadName(id) { return id === 0 ? "Family" : (member(id)?.name || "Chat"); }
function renderThreads() {
  const ids = [0, ...S.members.filter(m => m.id !== S.me).map(m => m.id)];
  $("#thr").innerHTML = ids.map(id => `<button data-th="${id}" class="${S.thread === id ? "on" : ""}">${esc(threadName(id))}${S.unread[id] ? `<span class="n">${S.unread[id]}</span>` : ""}</button>`).join("");
  const total = Object.values(S.unread).reduce((a, b) => a + b, 0);
  document.title = (total ? `(${total}) ` : "") + "Homebase";
  const tb = $('#tabs [data-t="chat"]'); if (tb) tb.textContent = total ? `Chat (${total})` : "Chat";
}
function renderComposer() {
  const t = member(S.thread); const blocked = t && !may(t, "messages");
  $("#msgIn").disabled = !!blocked; $("#msgIn").placeholder = blocked ? `${t.name} turned messages off` : "Write a message";
  $("#composer button").disabled = !!blocked;
}
async function loadThread(id) {
  S.thread = id; S.unread[id] = 0; renderThreads(); renderComposer();
  try { const d = await api("/api/messages" + (id ? "?with=" + id : "")); S.msgs[id] = d.messages; } catch (e) { S.msgs[id] = S.msgs[id] || []; }
  renderMsgs();
}
function bubble(m) {
  const mineMsg = m.from === S.me, who = member(m.from)?.name || "";
  if (m.kind === "system") return `<div class="b sys">${esc(m.body)}</div>`;
  let body = esc(m.body), cls = "";
  if (m.kind === "heart") body = '<span style="font-size:26px">❤️</span>';
  if (m.kind === "checkin") { cls = "loc"; body = "✅ " + esc(m.body || "I'm OK") + (m.lat != null ? " · 📍 tap to see where" : ""); }
  if (m.kind === "late") body = "⏰ " + esc(m.body);
  if (m.kind === "sos") { cls = "sos loc"; body = "🆘 <b>" + esc(m.body || "I need help") + "</b>" + (m.lat != null ? " · 📍 tap to see where" : ""); }
  return `<div class="b ${mineMsg ? "me" : ""} ${cls}" ${m.lat != null ? `data-lat="${m.lat}" data-lng="${m.lng}"` : ""}>${S.thread === 0 && !mineMsg ? `<small style="margin:0 0 3px">${esc(who)}</small>` : ""}${body}<small>${clock(m.at)}</small></div>`;
}
function renderMsgs() {
  const list = S.msgs[S.thread] || [], box = $("#msgs");
  box.innerHTML = list.length ? list.map(bubble).join("") : `<div class="empty">No messages yet.<br>Say hello 👋</div>`;
  box.scrollTop = box.scrollHeight;
}
async function sendMsg(kind, body, extra = {}, thread = S.thread) {
  const d = await api("/api/messages", "POST", { to: thread, kind, body, ...extra });
  (S.msgs[thread] = S.msgs[thread] || []).push(d.message); if (thread === S.thread) renderMsgs();
}

/* ---------- check-ins, SOS, late ---------- */
function where() { return S.lastPos ? { lat: S.lastPos.lat, lng: S.lastPos.lng } : {}; }
async function checkIn() {
  if (!S.lastPos && navigator.geolocation) { try { const p = await new Promise((ok, no) => navigator.geolocation.getCurrentPosition(ok, no, { timeout: 4000 })); S.lastPos = { lat: p.coords.latitude, lng: p.coords.longitude }; } catch (e) { /* send without a place */ } }
  try { await sendMsg("checkin", "I'm OK", where(), 0); toast("Sent to the family"); } catch (e) { toast(e.message); }
}
function lateDialog() {
  modal(`<h2>How late will you be?</h2><div class="foot" style="justify-content:flex-start">${[10, 20, 30, 60].map(m => `<button class="btn" data-m="${m}">About ${m === 60 ? "an hour" : m + " min"}</button>`).join("")}</div>`, root => {
    root.querySelectorAll("[data-m]").forEach(b => b.onclick = async () => { closeModal(); try { await sendMsg("late", `Running about ${b.dataset.m} minutes late`, {}, 0); toast("Sent"); } catch (e) { toast(e.message); } });
  });
}
function initSos() {
  const b = $("#bSos"); let t = 0;
  const stop = () => { clearTimeout(t); b.style.opacity = ""; };
  b.addEventListener("pointerdown", () => { toast("Keep holding for 2 seconds…", 1800); b.style.opacity = ".6"; t = setTimeout(async () => { stop(); try { await sendMsg("sos", "I need help", where(), 0); toast("SOS sent to everyone"); } catch (e) { toast(e.message); } }, 2000); });
  ["pointerup", "pointerleave", "pointercancel"].forEach(ev => b.addEventListener(ev, stop));
}

/* ---------- list ---------- */
async function refreshItems() { try { S.items = (await api("/api/list")).items; renderSide(); } catch (e) { /* ignore */ } }

/* ---------- location sharing from this laptop ---------- */
function startGeo() {
  if (!navigator.geolocation || S.geo != null) return;
  S.geo = navigator.geolocation.watchPosition(async pos => {
    S.lastPos = { lat: pos.coords.latitude, lng: pos.coords.longitude };
    if (Date.now() - S.lastSent < 20000) return; S.lastSent = Date.now();
    let battery = -1; try { battery = Math.round((await navigator.getBattery()).level * 100); } catch (e) { /* not supported */ }
    api("/api/location", "POST", { lat: pos.coords.latitude, lng: pos.coords.longitude, acc: pos.coords.accuracy || 0, speed: pos.coords.speed || 0, battery }).catch(() => {});
  }, () => { toast("Could not get this laptop's location."); stopGeo(); localStorage.setItem("hb_geo", "0"); }, { enableHighAccuracy: false, maximumAge: 15000, timeout: 20000 });
}
function stopGeo() { if (S.geo != null && navigator.geolocation) navigator.geolocation.clearWatch(S.geo); S.geo = null; }

/* ---------- me, permissions, quiet hours, invite ---------- */
async function meDialog() {
  let q = { from: -1, to: -1 }; try { q = (await api("/api/me")).quiet; } catch (e) { /* ignore */ }
  const m = me(), hm = v => v < 0 ? "" : String(Math.floor(v / 60)).padStart(2, "0") + ":" + String(v % 60).padStart(2, "0");
  const share = m.share, geo = localStorage.getItem("hb_geo") === "1";
  const perms = m.role === "child" ? `<div class="sec" style="margin-top:18px"><h3>What parents can do</h3></div><div class="sub">You decide. Switch something off and Homebase stops it straight away. Your parents see that it is off.</div>` +
    PERMS.map(([k, t, why]) => `<div class="sw"><div><b>${t}</b><span>${why}</span></div><button class="tg ${m.perms[k] !== false ? "on" : ""}" data-perm="${k}" aria-label="${t}"></button></div>`).join("") : "";
  modal(`<h2>${esc(m.emoji)} ${esc(m.name)}</h2><div class="sub">${m.role === "parent" ? "Parent" : "Child"} · ${esc(S.family?.name || "")}</div>
    <div class="sec" style="margin-top:14px"><h3>Sharing my location</h3></div>
    <div class="foot" style="justify-content:flex-start;margin-top:8px">${[["on", "On"], ["15", "Pause 15 min"], ["60", "Pause 1 h"], ["off", "Off"]].map(([v, t]) => `<button class="btn sm ${v === share ? "solid" : ""}" data-share="${v}">${t}</button>`).join("")}</div>
    <div class="sw"><div><b>Use this laptop's location</b><span>Your browser asks first. Without this, the map only shows phones.</span></div><button class="tg ${geo ? "on" : ""}" id="tGeo" aria-label="Use this laptop's location"></button></div>
    ${perms}
    <div class="sec" style="margin-top:18px"><h3>Quiet hours</h3></div><div class="sub">Messages stay quiet and calls do not ring. An urgent "ring anyway" still works.</div>
    <div style="display:flex;gap:10px;align-items:center"><input class="field" type="time" id="qFrom" value="${hm(q.from)}"><span class="muted">to</span><input class="field" type="time" id="qTo" value="${hm(q.to)}"><button class="btn sm" id="qSave">Save</button><button class="btn sm" id="qOff">Off</button></div>
    <div class="foot" style="justify-content:space-between;margin-top:22px"><div><button class="btn sm" id="mHist">Delete my location history</button> <a class="btn sm" href="/download">Android app</a></div><div><button class="btn sm bad" id="mLeave">Leave family</button> <button class="btn sm" id="mOut">Sign out</button></div></div>`, root => {
    root.querySelectorAll("[data-share]").forEach(b => b.onclick = async () => {
      const v = b.dataset.share; try { await api("/api/sharing", "POST", v === "on" || v === "off" ? { state: v } : { state: "paused", minutes: +v }); await refreshFamily(); toast("Updated. The family can see it."); closeModal(); } catch (e) { toast(e.message); } });
    root.querySelector("#tGeo").onclick = e => { const on = !e.target.classList.contains("on"); e.target.classList.toggle("on", on); localStorage.setItem("hb_geo", on ? "1" : "0"); on ? startGeo() : stopGeo(); };
    root.querySelectorAll("[data-perm]").forEach(b => b.onclick = async () => {
      const on = !b.classList.contains("on"); b.classList.toggle("on", on);
      try { await api("/api/me", "PATCH", { perms: { [b.dataset.perm]: on } }); refreshFamily(); } catch (e) { b.classList.toggle("on", !on); toast(e.message); } });
    const mins = v => { const [h, mm] = v.split(":").map(Number); return h * 60 + mm; };
    root.querySelector("#qSave").onclick = async () => { const a = root.querySelector("#qFrom").value, b = root.querySelector("#qTo").value; if (!a || !b) { toast("Pick both times."); return; } try { await api("/api/me", "PATCH", { quiet_from: mins(a), quiet_to: mins(b), tz_offset: new Date().getTimezoneOffset() }); toast("Quiet hours saved"); refreshFamily(); } catch (e) { toast(e.message); } };
    root.querySelector("#qOff").onclick = async () => { try { await api("/api/me", "PATCH", { quiet_from: -1, quiet_to: -1 }); toast("Quiet hours off"); root.querySelector("#qFrom").value = root.querySelector("#qTo").value = ""; refreshFamily(); } catch (e) { toast(e.message); } };
    root.querySelector("#mHist").onclick = async () => { if (confirm("Delete everything Homebase saved about where you have been?")) { await api("/api/history", "DELETE").catch(e => toast(e.message)); toast("Deleted"); } };
    root.querySelector("#mLeave").onclick = async () => { if (confirm("Leave the family? Everything you shared is deleted.")) { await api("/api/me", "DELETE").catch(e => toast(e.message)); signOut(); } };
    root.querySelector("#mOut").onclick = signOut;
  });
}
function inviteDialog() {
  modal(`<h2>Invite someone</h2><div class="sub">The code works once and lasts 24 hours.</div><label class="l">They are a…</label>
    <div class="foot" style="justify-content:flex-start;margin-top:0"><button class="btn solid" data-r="child">Child</button><button class="btn" data-r="parent">Parent</button></div><div id="inv"></div><div class="foot"><button class="btn" id="iClose">Close</button></div>`, root => {
    root.querySelector("#iClose").onclick = closeModal;
    root.querySelectorAll("[data-r]").forEach(b => b.onclick = async () => {
      root.querySelectorAll("[data-r]").forEach(x => x.classList.toggle("solid", x === b));
      try { const d = await api("/api/invite", "POST", { role: b.dataset.r }); const link = location.origin + "/app#join=" + d.code;
        root.querySelector("#inv").innerHTML = `<div class="big">${esc(d.code)}</div><div class="foot" style="justify-content:center"><button class="btn sm" id="cCode">Copy code</button><button class="btn sm" id="cLink">Copy link</button></div>`;
        root.querySelector("#cCode").onclick = () => { navigator.clipboard?.writeText(d.code); toast("Code copied"); }; root.querySelector("#cLink").onclick = () => { navigator.clipboard?.writeText(link); toast("Link copied"); };
      } catch (e) { toast(e.message); } });
    root.querySelector('[data-r="child"]').click();
  });
}

/* ---------- where are you? ---------- */
async function askWhere(m) { try { await api("/api/ping", "POST", { to: m.id }); toast(`Asked ${m.name}. They can answer or say not now.`); } catch (e) { toast(e.message); } }
function pingDialog(p) {
  notify(`${p.name} asked where you are`, "Open Homebase to answer");
  modal(`<h2>${esc(p.emoji)} ${esc(p.name)} asked where you are</h2><div class="sub">You can share your location this once, or say not now. Either way is fine.</div>
    <div class="foot"><button class="btn" id="pNo">Not now</button><button class="btn solid" id="pYes">Share my location</button></div>`, root => {
    root.querySelector("#pNo").onclick = async () => { closeModal(); await api("/api/ping/" + p.id, "POST", { accept: false }).catch(() => {}); };
    root.querySelector("#pYes").onclick = async () => {
      closeModal();
      try { const pos = await new Promise((ok, no) => navigator.geolocation.getCurrentPosition(ok, no, { timeout: 8000 })); await api("/api/ping/" + p.id, "POST", { accept: true, lat: pos.coords.latitude, lng: pos.coords.longitude }); toast("Shared"); }
      catch (e) { await api("/api/ping/" + p.id, "POST", { accept: false }).catch(() => {}); toast("Could not get your location, so it was declined."); } };
  });
}

/* ---------- events (messages, calls, and so on, the moment they happen) ---------- */
function notify(title, body) { if (document.hidden && "Notification" in window && Notification.permission === "granted") { try { new Notification(title, { body }); } catch (e) { /* ignore */ } } }
async function eventLoop() {
  if (S.cursor < 0) { try { S.cursor = (await api("/api/events?after=-1")).cursor; } catch (e) { S.cursor = 0; } }
  while (S.running && S.token) {
    try {
      const d = await api(`/api/events?after=${S.cursor}&wait=25`);
      S.cursor = d.cursor;
      for (const ev of d.events) { try { handleEvent(ev); } catch (e) { console.error(e); } }
    } catch (e) { if (e.status === 401) return; await sleep(3000); }
  }
}
function handleEvent(ev) {
  const p = ev.payload;
  switch (ev.type) {
    case "message": {
      const th = p.to === 0 ? 0 : p.from; const list = (S.msgs[th] = S.msgs[th] || []);
      if (!list.some(x => x.id === p.id)) list.push(p);
      if (th === S.thread) renderMsgs();
      if (th !== S.thread || document.hidden) { S.unread[th] = (S.unread[th] || 0) + 1; renderThreads(); }
      if (p.kind !== "system" && !p.quiet) { notify(p.name || "Homebase", p.body || ""); if (p.kind === "sos") toast("🆘 " + (p.name || "Someone") + " needs help", 6000); }
      break; }
    case "member": case "places": refreshFamily(); break;
    case "list": refreshItems(); break;
    case "location": { const m = member(p.member); if (m) { m.location = { lat: p.lat, lng: p.lng, acc: p.acc, at: p.at }; m.battery = p.battery ?? m.battery; renderMap(); renderSide(); } break; }
    case "battery": { const m = member(p.member); if (m) { m.battery = p.battery; renderSide(); } toast(`🔋 ${p.name} has ${p.battery}% battery`); break; }
    case "place": toast(`${p.emoji} ${p.name} ${p.kind === "arrive" ? "arrived at" : "left"} ${p.place}`, 5000); break;
    case "ping": pingDialog(p); break;
    case "ping_result": if (p.status === "shared") { toast(`📍 ${p.name} shared their location`); refreshFamily().then(() => focusMember(p.member)); } else toast(`${p.name} said not now`); break;
    case "call": case "call_update": case "ice": window.Calls && window.Calls.onEvent(ev.type, p); break;
  }
}

/* ---------- wiring ---------- */
function setTab(t) {
  $("#layout").dataset.tab = t; document.querySelectorAll("#tabs button").forEach(b => b.classList.toggle("on", b.dataset.t === t));
  if (t === "map") setTimeout(() => S.map && S.map.invalidateSize(), 50);
}
let chromeReady = false;
function initChrome() {
  if (chromeReady) return; chromeReady = true;
  $("#bOk").onclick = checkIn; $("#bLate").onclick = lateDialog; $("#bMe").onclick = meDialog; $("#bInvite").onclick = inviteDialog; initSos();
  $("#bFit").onclick = fitAll;
  const paintMap = () => $("#mapwrap").classList.toggle("dark", localStorage.getItem("hb_map") === "dark"); paintMap();
  $("#bTheme").onclick = () => { localStorage.setItem("hb_map", localStorage.getItem("hb_map") === "dark" ? "light" : "dark"); paintMap(); };
  $("#bAddPlace").onclick = () => {
    S.adding = !S.adding; $("#bAddPlace").textContent = S.adding ? "Cancel" : "＋ Place"; document.querySelector(".hint")?.remove();
    if (S.adding) { const h = document.createElement("div"); h.className = "hint"; h.textContent = "Click the map where the place is"; $("#mapwrap").append(h); setTab("map"); }
  };
  $("#tabs").onclick = e => { const b = e.target.closest("[data-t]"); if (b) { setTab(b.dataset.t); if (b.dataset.t === "chat") { S.unread[S.thread] = 0; renderThreads(); } } };
  $("#thr").onclick = e => { const b = e.target.closest("[data-th]"); if (b) loadThread(+b.dataset.th); };
  $("#composer").onsubmit = async e => { e.preventDefault(); const v = $("#msgIn").value.trim(); if (!v) return; $("#msgIn").value = ""; try { await sendMsg("text", v); } catch (err) { toast(err.message); $("#msgIn").value = v; } };
  $("#msgs").onclick = e => { const b = e.target.closest("[data-lat]"); if (b) { setTab("map"); S.map.flyTo([+b.dataset.lat, +b.dataset.lng], 16); L.popup().setLatLng([+b.dataset.lat, +b.dataset.lng]).setContent("📍 Here").openOn(S.map); } };
  $("#side").onclick = async e => {
    const el = e.target.closest("[data-act]"); if (!el) return; const id = +el.dataset.id, m = member(id), act = el.dataset.act;
    if (act === "focus") focusMember(id);
    else if (act === "msg") { setTab("chat"); loadThread(id); $("#msgIn").focus(); }
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

document.addEventListener("visibilitychange", () => { if (!document.hidden && S.token) { S.unread[S.thread] = 0; renderThreads(); } });
window.addEventListener("load", () => {
  const m = location.hash.match(/join=([A-Za-z0-9]+)/);
  if (S.token) startApp(); else if (m) showAuth("join", { code: m[1].toUpperCase() }); else showAuth("welcome");
});
