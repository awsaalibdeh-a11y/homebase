/* Mellow 2: five sections (Home, Map, Plan, Chat, More), the meal plan, friend groups, stickers and GIFs, linked apps like Platter, the activity feed and search.
   It loads after web.js and builds on it: the same state (S), the same helpers (api, modal, toast, esc, ...). */
Object.assign(S, { meals: [], groups: [], requests: [], links: [], feed: [], stickers: null, cal: null, gdlg: null, gtimer: 0, risk: [] });
const PLATTER_URL = localStorage.getItem("hb_platter") || "https://platter-87b0.onrender.com";
const SCOUT_URL = localStorage.getItem("hb_scout") || "https://scout-15t6.onrender.com";
const ALIBI_URL = localStorage.getItem("hb_alibi") || "https://alibi-d9tn.onrender.com";
const FAMILY_GAMES = [{ id: "alibi", emoji: "🕵️", name: "Alibi", sub: "A murder party for the whole family: one of you is the killer. 4 to 8 players, and bots fill any empty chairs." }];
/** Where a "Join the game" button goes: only ever to the game's own address, with the person's name so they do not have to type it. */
function gameJoinHref(url) {
  const base = ALIBI_URL + "/#join/";
  if (typeof url !== "string" || !url.startsWith(base) || !/^[A-Z0-9]{4}$/.test(url.slice(base.length))) return "";
  const me = (typeof member === "function" && member(S.me)) || null;
  return url + (me && me.name ? "/" + encodeURIComponent(me.name) : "");
}
function gameDialog() {
  modal(`<h2>🎮 Start a family game</h2><div class="sub">It opens the game on this device as the host, and the family chat gets the code and a Join button.</div>
    ${FAMILY_GAMES.map(g => `<div class="item"><span class="stemo">${g.emoji}</span><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${esc(g.name)}</b><small>${esc(g.sub)}</small></span><button class="btn solid" data-game="${g.id}" type="button">Start</button></div>`).join("")}
    <div class="foot"><button class="btn" id="gX" type="button">Close</button></div>`, root => {
    root.querySelector("#gX").onclick = closeModal;
    root.querySelectorAll("[data-game]").forEach(b => b.onclick = () => startFamilyGame(b.dataset.game));
  });
}
async function startFamilyGame(id) {
  const tab = window.open("", "_blank");                              // opened now, inside the tap, so a phone does not block it
  try {
    const d = await api("/api/games/start", "POST", { game: id });
    if (tab) { tab.location.href = d.url; closeModal(); return; }
    modal(`<h2>🎮 ${esc(d.game)} is ready</h2><div class="sub">Your browser blocked the new tab. Open the game here, then everyone is told in the family chat.</div>
      <div class="foot" style="justify-content:flex-start"><a class="btn solid" href="${esc(d.url)}" target="_blank" rel="noopener">Open ${esc(d.game)}</a></div><div class="foot"><button class="btn" id="gX2" type="button">Close</button></div>`, root => { root.querySelector("#gX2").onclick = closeModal; });
  } catch (e) { if (tab) tab.close(); toast(e.message); }
}
const LINKABLE = { Platter: { url: () => PLATTER_URL, icon: "🍽️", what: "Recipes you plan there appear in the family meal plan, with ingredients and method." },
                   Scout: { url: () => SCOUT_URL, icon: "🧭", what: "Put a place on the family calendar, save it as a family place, or share it in the chat." } };
const SLOT_ICON = { breakfast: "🥣", lunch: "🥪", dinner: "🍽️", snack: "🍎" }, SLOT_ORDER = ["breakfast", "lunch", "dinner", "snack"];
const dayKey = d => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`, todayKey = () => dayKey(new Date());
const cap = s => (s ? s[0].toUpperCase() + s.slice(1) : "");
function dayLabel(k) {
  const t = todayKey(), tm = dayKey(new Date(Date.now() + 864e5));
  return k === t ? "Today" : k === tm ? "Tomorrow" : new Date(k + "T12:00:00").toLocaleDateString([], { weekday: "long", day: "numeric", month: "short" });
}
const mavatar = m => { const dot = m.share === "on" && m.location ? "on" : m.share === "paused" ? "paused" : ""; return `<span class="ava ${dot}">${avaInner(m)}</span>`; };

/* ---------- the five sections ---------- */
function sectionHtml(t) { return t === "home" ? homeHtml() : t === "map" ? mapSectionHtml() : t === "plan" ? planSectionHtml() : t === "chat" ? chatSectionHtml() : moreHtml(); }

function requestsHtml() {
  if (!isParent() || !S.requests.length) return "";
  return `<div class="reqs">${S.requests.map(r => `<div class="req"><span class="pl-e">👥</span><div class="pl-m"><b>${esc(r.name)} wants to join ${esc(r.group_name)}</b><small>A friend group. You will be able to read it.</small></div><div class="pl-btns"><button class="btn sm" data-act="grpno" data-id="${r.group}" data-m="${r.member}">Not now</button><button class="btn sm solid" data-act="grpok" data-id="${r.group}" data-m="${r.member}">Yes</button></div></div>`).join("")}</div>`;
}
function tonightHtml() {
  const k = todayKey(), meals = S.meals.filter(m => m.day === k).sort((a, b) => SLOT_ORDER.indexOf(a.slot) - SLOT_ORDER.indexOf(b.slot));
  if (!meals.length) return `<section class="tonight empty"><span>🍽️</span><div><b>What are we eating?</b><small>Nothing planned today. <a href="#" data-act="addmeal">Add a meal</a>, or plan one from Platter.</small></div></section>`;
  return `<section class="tonight">${meals.map(m => `<button type="button" class="tn-row" data-act="${m.ingredients.length || m.steps.length || m.source ? "meal" : "plan_tab"}" data-id="${m.id}"><span>${SLOT_ICON[m.slot] || "🍽️"}</span><div><b>${cap(m.slot)}: ${esc(m.title)}</b><small>${m.cook ? "🍳 " + esc(m.cook === S.me ? "You are cooking" : nameOf(m.cook) + " is cooking") : "Nobody has said they will cook it yet"}</small></div></button>`).join("")}</section>`;
}
function quickActionsHtml() {
  const q = (act, e, t) => `<button type="button" class="qa" data-act="${act}"><i>${e}</i><span>${t}</span></button>`;
  return `<div class="qas">${q("qok", "✅", "I'm OK")}${q("qlate", "⏰", "Running late")}${q("pickup", "🚗", "Pick me up")}${q("addplan", "📅", "Add a plan")}${q("addmeal", "🍽️", "Add a meal")}${q("addpoll", "🗳️", "Ask the family")}${q("game", "🎮", "Family game")}${isParent() ? q("announce", "📢", "Announce") : q("status", "🙂", "My status")}${q("search", "🔎", "Search")}</div>`;
}
function peopleStripHtml() {
  return `<div class="strip">${S.members.map(m => `<button type="button" class="sp" data-act="pickmap" data-id="${m.id}">${mavatar(m)}<small>${esc(m.id === S.me ? "You" : m.name)}</small></button>`).join("")}</div>`;
}
function homeHtml() { return setupCardHtml() + sosHtml() + requestsHtml() + zoneAsksHtml() + todayHtml() + weatherHtml() + peopleStripHtml() + countdownsHtml() + tonightHtml() + quickActionsHtml() + plansHtml(); }

/* ---------- 4.0 and 5.0 safety on the web: SOS, Critical Check-In, find my phone, zones, safety alerts ---------- */
function sosHtml() {
  const me = member(S.me) || {}, out = [];
  if (me.critical) out.push(`<section class="tonight" style="border-color:var(--bad)"><span>🚨</span><div><b>${esc(me.critical.by)} needs you to check in</b><small>Tap I'm OK so they know.</small></div><button class="btn sm solid" data-act="imok">I'm OK</button></section>`);
  S.members.filter(m => m.sos && m.id !== S.me).forEach(m => out.push(`<section class="tonight" style="background:var(--bad);color:#fff"><span>🚨</span><div><b>${esc(m.name)} sent an SOS</b><small style="color:#fff">${m.location ? "Live location · " + esc(ago(m.location.at)) : "Waiting for a location…"}</small></div><button class="btn sm" data-act="pickmap" data-id="${m.id}">Show on map</button></section>`));
  if (me.sos) out.push(`<section class="tonight"><span>🚨</span><div><b>Your SOS is on</b><small>Your family sees you live.</small></div><button class="btn sm solid" data-act="imok">I'm safe now</button></section>`);
  S.members.filter(m => m.walk && m.id !== S.me).forEach(m => out.push(`<section class="tonight"><span>🚶</span><div><b>${esc(m.name)} is on a Safe Walk to ${esc(m.walk.place)}</b><small>Live until they get there.</small></div><button class="btn sm" data-act="pickmap" data-id="${m.id}">Follow</button></section>`));
  return out.join("");
}
function zoneAsksHtml() {
  if (!isParent()) return "";
  return S.places.filter(p => p.pending).map(p => `<div class="req"><span class="pl-e">${esc(p.emoji)}</span><div class="pl-m"><b>A zone was asked for: ${esc(p.name)}</b><small>${p.radius} m around</small></div><div class="pl-btns"><button class="btn sm" data-act="zoneno" data-id="${p.id}">Not now</button><button class="btn sm solid" data-act="zoneyes" data-id="${p.id}">Yes</button></div></div>`).join("");
}
function kidToolsHtml() {
  if (!isParent()) return "";
  const kids = S.members.filter(m => m.role === "child");
  if (!kids.length) return "";
  const dns = v => !v ? "Safe browsing: not reported" : v === "off" || v === "automatic" ? "Safe browsing: off" : /cleanbrowsing|family/.test(v) ? "🛡️ Safe browsing on" : "Another filter";
  return `<section class="calcard"><h3 style="margin-bottom:8px">🛡️ Safety tools</h3>${kids.map(k => `<div class="item" style="flex-wrap:wrap;gap:6px"><span style="flex:1;min-width:160px"><b style="display:block;font-weight:600">${esc(k.name)}</b><small>${esc(dns(k.safe_dns))}${k.eta ? " · 🏃 about " + k.eta.min + " min from home" : ""}${k.speed_limit ? " · 🚗 alert above " + k.speed_limit + " km/h" : ""}</small></span>
    <button class="btn sm" data-act="crit" data-id="${k.id}" ${k.perms && k.perms.critical === false ? "disabled title=\"They switched this off\"" : ""}>🚨 Critical Check-In</button><button class="btn sm" data-act="ring" data-id="${k.id}">📱 Ring</button></div>`).join("")}
    ${S.risk.length ? `<div class="msec" style="margin-top:10px">Safety alerts</div>${S.risk.slice(0, 6).map(a => `<div class="item" style="${a.seen ? "" : "background:color-mix(in srgb,var(--bad) 10%,var(--surface))"}"><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">⚠️ ${esc(a.name)}: ${esc(a.what)}</b><small>${a.role === "sent" ? "Sent by " + esc(a.name) : "Sent to " + esc(a.name) + " by " + esc(a.other)} in ${esc(a.where)} · ${esc(ago(a.at))}</small><small style="display:block;color:var(--ink)">“${esc(a.snippet)}”</small></span>${a.seen ? "" : `<button class="btn sm" data-act="riskseen" data-id="${a.id}">Seen</button>`}</div>`).join("")}` : '<p class="tabhelp">No safety alerts. Mellow checks messages in Mellow for bullying, self-harm, adult content and grooming, and shows only the message that matched.</p>'}</section>`;
}
async function refreshRisk() { if (!isParent()) return; try { S.risk = (await api("/api/risk")).alerts; renderSide(); } catch (e) { /* offline */ } }
function mapSectionHtml() {
  const hasHome = S.places.some(p => p.name === "Home");
  return peopleHtml() + (isParent() ? `<button class="btn" data-act="invite">＋ Invite someone</button>` : "")
    + accordion("places", "📍", "Places and Home", hasHome ? S.places.length + " saved" : "Set the Home area", placesHtml());
}

/* ---------- meals ---------- */
async function refreshMeals() { try { S.meals = (await api("/api/meals")).meals; renderSide(); } catch (e) { /* keep the last list */ } }
function mealCard(m) {
  const cook = m.cook ? (m.cook === S.me ? "You are cooking" : nameOf(m.cook) + " is cooking") : "", canDel = isParent() || m.by === S.me;
  const has = m.ingredients.length || m.steps.length || m.source;
  return `<div class="meal"><div class="ml-top">${m.photo ? `<img class="ml-ph" src="${esc(m.photo)}" alt="" loading="lazy">` : `<span class="ml-e">${SLOT_ICON[m.slot] || "🍽️"}</span>`}<div class="ml-m"><b>${esc(m.title)}</b><small>${cap(m.slot)}${m.servings ? " · serves " + m.servings : ""}${m.via && m.via !== "homebase" ? " · from " + esc(cap(m.via)) : ""}</small>${cook ? `<small class="cook">🍳 ${esc(cook)}</small>` : ""}${m.suggested ? `<small class="cook">💡 Suggested by ${esc(nameOf(m.by))}</small>` : ""}</div>${canDel ? `<button class="link" data-act="delmeal" data-id="${m.id}" title="Remove" aria-label="Remove this meal">✕</button>` : ""}</div>
    <div class="pl-btns">${m.suggested ? (isParent() ? `<button class="btn sm solid" data-act="mealyes" data-id="${m.id}">Yes, plan it</button><button class="btn sm" data-act="mealno" data-id="${m.id}">Not now</button>` : "<small>A parent will say yes or not now.</small>") : ""}${has ? `<button class="btn sm" data-act="meal" data-id="${m.id}">Recipe</button>` : ""}<button class="btn sm ${m.cook === S.me ? "sel" : ""}" data-act="cook" data-id="${m.id}">${m.cook === S.me ? "✓ I'm cooking" : "I'll cook"}</button>${m.ingredients.length ? `<button class="btn sm" data-act="meallist" data-id="${m.id}">＋ To the list</button>` : ""}</div></div>`;
}
function mealsHtml() {
  const by = {}; S.meals.forEach(m => (by[m.day] = by[m.day] || []).push(m));
  const days = Object.keys(by).filter(k => k >= todayKey()).sort().slice(0, 7);
  const body = days.map(k => `<div class="mday"><h4>${esc(dayLabel(k))}</h4>${by[k].sort((a, b) => SLOT_ORDER.indexOf(a.slot) - SLOT_ORDER.indexOf(b.slot)).map(mealCard).join("")}</div>`).join("");
  return `<div class="sechead"><h3>🍽️ Meal plan</h3><span class="secbtns"><button class="btn sm" data-act="recipes">🔎 Recipes</button><button class="btn sm" data-act="addmeal">＋ Meal</button></span></div>${body || `<div class="empty">Nothing planned yet. Add a meal, or link Platter (More, Linked apps) and plan recipes from there.</div>`}`;
}
function mealDialog(id) {
  const m = S.meals.find(x => x.id === id) || (S.cal && S.cal.meals.find(x => x.id === id)); if (!m) return;
  modal(`<h2>${SLOT_ICON[m.slot] || "🍽️"} ${esc(m.title)}</h2><div class="sub">${esc(dayLabel(m.day))} · ${cap(m.slot)}${m.servings ? " · serves " + m.servings : ""}${m.via && m.via !== "homebase" ? " · from " + esc(cap(m.via)) : ""}</div>
    ${m.photo ? `<img class="mphoto" src="${esc(m.photo)}" alt="">` : ""}
    ${m.ingredients.length ? `<div class="msec">Ingredients</div><ul class="ing">${m.ingredients.map(i => `<li>${esc(i)}</li>`).join("")}</ul>` : ""}
    ${m.steps.length ? `<div class="msec">Method</div><ol class="steps">${m.steps.map(i => `<li>${esc(i)}</li>`).join("")}</ol>` : ""}
    ${m.source ? `<p class="sub"><a href="${esc(m.source)}" target="_blank" rel="noopener">Open the full recipe${m.via === "platter" ? " in Platter" : ""} ↗</a></p>` : ""}
    <div class="foot">${m.ingredients.length ? '<button class="btn" id="mdList" type="button">＋ Put the ingredients on the list</button>' : ""}<button class="btn solid" id="mdX" type="button">Close</button></div>`, root => {
    root.querySelector("#mdX").onclick = closeModal; const l = root.querySelector("#mdList"); if (l) l.onclick = () => mealToList(id);
  });
}
async function mealToList(id) { try { const d = await api(`/api/meals/${id}/to-list`, "POST"); toast(d.added ? `Added ${d.added} to the family list` : "Everything is already on the list"); refreshItems(); } catch (e) { toast(e.message); } }
function addMealDialog(day) {
  modal(`<h2>🍽️ Add a meal</h2><div class="sub">Everyone in the family sees what we are having. Recipes from Platter appear here by themselves.</div>
    <label class="l">What are we eating?</label><input class="field" id="mlT" maxlength="120" placeholder="For example: Chicken burger" autocomplete="off">
    <label class="l">When</label><div style="display:flex;gap:8px"><input class="field" type="date" id="mlD" value="${day || todayKey()}"><select class="field" id="mlS">${["dinner", "lunch", "breakfast", "snack"].map(s => `<option value="${s}">${SLOT_ICON[s]} ${cap(s)}</option>`).join("")}</select></div>
    <div class="foot"><button class="btn" id="mlX" type="button">Cancel</button><button class="btn solid" id="mlOk" type="button">Add it</button></div>`, root => {
    root.querySelector("#mlX").onclick = closeModal; root.querySelector("#mlT").focus();
    root.querySelector("#mlOk").onclick = async () => {
      const title = root.querySelector("#mlT").value.trim(); if (!title) return toast("What are we eating?");
      try { await api("/api/meals", "POST", { title, day: root.querySelector("#mlD").value, slot: root.querySelector("#mlS").value }); closeModal(); toast("Meal added"); refreshMeals(); loadCal(); } catch (e) { toast(e.message); }
    };
  });
}

/* ---------- the calendar, inline: plans, meals and birthdays on one month ---------- */
function calInit() { const t = new Date(); S.cal = { y: t.getFullYear(), m: t.getMonth(), sel: t.getDate(), plans: [], meals: [] }; loadCal(); }
async function loadCal() {
  if (!S.cal) return; const { y, m } = S.cal, from = new Date(y, m, 1).getTime() / 1000, to = new Date(y, m + 1, 1).getTime() / 1000;
  const [p, ml] = await Promise.all([api(`/api/plans?from=${from}&to=${to}`).catch(() => null), api(`/api/meals?from=${dayKey(new Date(y, m, 1))}&to=${dayKey(new Date(y, m + 1, 0))}`).catch(() => null)]);
  if (S.cal && S.cal.y === y && S.cal.m === m) { if (p) S.cal.plans = p.plans; if (ml) S.cal.meals = ml.meals; renderSide(); }
}
function calInlineHtml() {
  if (!S.cal) calInit();
  const { y, m, sel } = S.cal, today = new Date(), isNow = today.getFullYear() === y && today.getMonth() === m;
  const days = new Date(y, m + 1, 0).getDate(), lead = (new Date(y, m, 1).getDay() + 6) % 7, byDay = {}, mealDay = {}, bdays = {};
  S.cal.plans.forEach(p => { const d = new Date(p.start * 1000).getDate(); (byDay[d] = byDay[d] || []).push(p); });
  S.cal.meals.forEach(ml => { const d = +ml.day.slice(8); (mealDay[d] = mealDay[d] || []).push(ml); });
  S.members.forEach(mm => { if (!mm.birthday) return; let [bm, bd] = mm.birthday.split("-").map(Number); if (bm !== m + 1) return; if (bd > days) bd = days; (bdays[bd] = bdays[bd] || []).push(mm); });
  const cells = Array.from({ length: lead }, () => '<span class="cd off"></span>').concat(Array.from({ length: days }, (_, i) => {
    const d = i + 1, n = (byDay[d] || []).length;
    return `<button type="button" class="cd ${isNow && d === today.getDate() ? "today" : ""} ${d === sel ? "sel" : ""}" data-act="calday" data-id="${d}"><b>${d}</b><i>${bdays[d] ? "🎂" : ""}${mealDay[d] ? "🍽️" : ""}${n ? "●".repeat(Math.min(n, 3)) : ""}</i></button>`;
  })).join("");
  const dk = `${y}-${pad2(m + 1)}-${pad2(sel)}`;
  const plans = (byDay[sel] || []).map(p => `<div class="plan"><div class="pl-top"><span class="pl-e">${esc(p.emoji)}</span><div class="pl-m"><b>${esc(p.title)}</b><small>${clock(p.start)}${p.note ? " · " + esc(p.note) : ""}</small></div></div><div class="pl-who">${p.in.length ? "👍 " + esc(p.in.map(nameOf).join(", ")) : "Nobody has said yes yet"}${p.out.length ? ` · can't: ${esc(p.out.map(nameOf).join(", "))}` : ""}</div><div class="pl-btns"><button class="btn sm ${p.mine === "in" ? "sel" : ""}" data-act="rsvp" data-id="${p.id}" data-a="${p.mine === "in" ? "" : "in"}">${p.mine === "in" ? "✓ I'm in" : "I'm in"}</button><button class="btn sm ${p.mine === "out" ? "sel" : ""}" data-act="rsvp" data-id="${p.id}" data-a="${p.mine === "out" ? "" : "out"}">${p.mine === "out" ? "✓ Can't" : "Can't"}</button></div></div>`).join("");
  const meals = (mealDay[sel] || []).sort((a, b) => SLOT_ORDER.indexOf(a.slot) - SLOT_ORDER.indexOf(b.slot)).map(mealCard).join("");
  const bd = (bdays[sel] || []).map(mm => `<div class="plan"><div class="pl-top"><span class="pl-e">🎂</span><div class="pl-m"><b>${esc(mm.name)}'s birthday</b></div></div></div>`).join("");
  return `<section class="calcard"><div class="calhead"><button type="button" class="btn sm" data-act="calnav" data-id="-1" aria-label="Previous month">‹</button><h3>${MONTHS[m]} ${y}</h3><button type="button" class="btn sm" data-act="calnav" data-id="1" aria-label="Next month">›</button></div>
    <div class="cal">${DOW.map(d => `<span class="cw">${d}</span>`).join("")}${cells}</div>
    <div class="callist"><div class="msec">${sel} ${MONTHS[m]}</div>${plans}${meals}${bd}${plans || meals || bd ? "" : '<div class="empty">Nothing on this day.</div>'}</div>
    <div class="pl-btns" style="margin-top:10px"><button class="btn sm" data-act="calplan" data-id="${dk}">＋ Plan</button><button class="btn sm" data-act="calmeal" data-id="${dk}">＋ Meal</button><button class="btn sm" data-act="ics">⬇ Add to Google, Apple or Outlook</button></div></section>`;
}
function planSectionHtml() {
  const pa = pollAlert();
  return calInlineHtml() + mealsHtml() + plansHtml() + accordion("polls", "🗳️", "Family votes", pa ? pa + " waiting for your vote" : (S.polls.length ? S.polls.length + " asked" : "Ask the family anything"), pollsHtml(), pa);
}

/* ---------- stickers and GIFs ---------- */
async function loadStickers() { if (!S.stickers) { try { S.stickers = (await api("/api/stickers")).stickers; } catch (e) { S.stickers = []; } } return S.stickers; }
const trayHtml = st => `<div class="stk">${st.map(s => `<button type="button" data-u="${esc(s.url)}" aria-label="${esc(s.name)}"><img src="${esc(s.url)}" alt="" loading="lazy"></button>`).join("")}</div>
  <div class="stkurl"><input class="field" data-gurl placeholder="Or paste a GIF link (Giphy, Tenor, Imgur)" autocomplete="off"><button class="btn sm solid" type="button" data-ggo>Send</button></div>`;
function bindTray(root, send) {
  root.querySelectorAll(".stk [data-u]").forEach(b => b.onclick = () => send(b.dataset.u));
  const go = root.querySelector("[data-ggo]"), url = root.querySelector("[data-gurl]");
  if (go) go.onclick = () => { const v = url.value.trim(); if (v) send(v); }; if (url) url.onkeydown = e => { if (e.key === "Enter") { e.preventDefault(); go.click(); } };
}
async function gifPicker() {
  const st = await loadStickers();
  modal(`<h2>🎞️ Send a sticker or GIF</h2>${trayHtml(st)}<div class="foot"><button class="btn" id="gpX" type="button">Close</button></div>`, root => {
    root.querySelector("#gpX").onclick = closeModal;
    bindTray(root, async url => { try { await sendMsg("gif", url); closeModal(); } catch (e) { toast(e.message); } });
  });
}

/* ---------- friend groups: separate from the family, but a child's parents can read them ---------- */
async function refreshGroups() { try { const d = await api("/api/groups"); S.groups = d.groups; S.requests = d.requests; renderSide(); if (S.gdlg) groupFetch(true); } catch (e) { /* keep what we have */ } }
function groupsHtml() {
  const rows = S.groups.map(g => {
    const tag = g.status === "pending" ? '<span class="chip warn">Waiting for a parent</span>' : !g.status ? `<span class="chip">👀 ${esc(g.overseeing.join(", "))} is in it</span>` : "";
    const last = g.last ? `${esc(g.last.name)}: ${esc(g.last.body)}` : (g.status === "pending" ? "A parent has to say yes first." : "No messages yet.");
    return `<button type="button" class="grow2" data-act="group" data-id="${g.id}" ${g.status === "pending" ? "disabled" : ""}><span class="pl-e">${esc(g.emoji)}</span><span class="pl-m"><b>${esc(g.name)}</b><small>${last}</small>${tag}</span></button>`;
  }).join("");
  return `<div class="sechead"><h3>👥 Friend groups</h3><span class="secbtns"><button class="btn sm" data-act="grpjoin">Join</button><button class="btn sm" data-act="grpnew">＋ New</button></span></div>
    <p class="tabhelp">Groups of friends, separate from the family. ${isParent() ? "You can read the groups your children are in." : "Your parents can read your groups, and rude words are hidden and reported."}</p>${rows || '<div class="empty">No friend groups yet. Make one, or join a friend\'s with their code.</div>'}`;
}
function chatSectionHtml() {
  const ids = [0, ...S.members.filter(m => m.id !== S.me).map(m => m.id)];
  const rows = ids.map(id => {
    const last = (S.msgs[id] || []).filter(x => x.kind !== "system").slice(-1)[0], n = S.unread[id] || 0, m = id ? member(id) : null;
    return `<button type="button" class="thrrow" data-act="openthread" data-id="${id}">${m ? mavatar(m) : '<span class="ava">👨‍👩‍👧</span>'}<span class="pl-m"><b>${esc(threadName(id))}</b><small>${last ? esc(last.kind === "gif" ? "🎞️ GIF" : last.kind === "image" ? "📷 Photo" : last.body || "") : "Say hello"}</small></span>${n ? `<i class="tb2">${n}</i>` : ""}</button>`;
  }).join("");
  return requestsHtml() + `<div class="sechead"><h3>💬 Family</h3></div>${rows}` + groupsHtml();
}

const GROUP_RULES = "Your parents can read this group. Rude words, insults and slurs are hidden and reported to them.";
async function openGroup(id) {
  S.gdlg = { id, after: 0, messages: [], role: "", group: null };
  modal(`<div class="gwrap"><div class="ghead"><h2 id="gT">…</h2><div class="sub" id="gS"></div></div><div class="gnote" id="gN"></div><div class="gmsgs" id="gM"></div><div id="gTray" class="hidden"></div>
    <form class="gcomp" id="gF"><button class="btn" type="button" id="gGif" aria-label="Stickers and GIFs">🎞️</button><input class="field" id="gIn" maxlength="600" placeholder="Write to the group" autocomplete="off"><button class="btn solid" type="submit">Send</button></form>
    <div class="foot" style="justify-content:space-between"><button class="btn sm" id="gInfo" type="button">Members and code</button><button class="btn" id="gX" type="button">Close</button></div></div>`, root => {
    root.querySelector("#gX").onclick = closeModal; root.querySelector("#gInfo").onclick = groupInfo;
    root.querySelector("#gF").onsubmit = async e => { e.preventDefault(); const i = root.querySelector("#gIn"), v = i.value.trim(); if (v) { i.value = ""; await groupSend(v, "text"); } };
    root.querySelector("#gGif").onclick = async () => { const tray = root.querySelector("#gTray"); if (!tray.classList.toggle("hidden")) { tray.innerHTML = trayHtml(await loadStickers()); bindTray(tray, u => { tray.classList.add("hidden"); groupSend(u, "gif"); }); } };
  });
  await groupFetch(true); clearInterval(S.gtimer); S.gtimer = setInterval(() => groupFetch(false), 4000);
}
async function groupFetch(full) {
  const d = S.gdlg; if (!d) return;
  try {
    const r = await api(`/api/groups/${d.id}/messages` + (full ? "" : "?after=" + d.after)); if (!S.gdlg || S.gdlg.id !== d.id) return;
    d.role = r.role; d.group = r.group; if (full) d.messages = r.messages; else r.messages.forEach(m => { if (!d.messages.some(x => x.id === m.id)) d.messages.push(m); });
    d.after = d.messages.reduce((a, m) => Math.max(a, m.id), 0); groupRender();
  } catch (e) { if (full) { toast(e.message); closeModal(); } }
}
function groupRender() {
  const d = S.gdlg, root = $("#gM"); if (!d || !root || !d.group) return;
  $("#gT").textContent = d.group.emoji + " " + d.group.name; $("#gS").textContent = d.group.members.map(m => m.name).join(", ");
  $("#gN").innerHTML = d.role === "overseer" ? "👀 You are reading this as a parent. Only its members can write in it." : "👀 " + esc(GROUP_RULES);
  const parentView = isParent();
  root.innerHTML = d.messages.map(m => {
    const mine = m.from === S.me, body = m.kind === "gif" ? `<img class="gif" src="${esc(m.body)}" alt="GIF" loading="lazy">` : esc(m.body);
    const warn = m.flagged ? `<div class="gflag">⚠️ ${m.flags ? "Contained: " + esc(m.flags.map(f => f.word + " (" + f.cat + ")").join(", ")) + (m.original ? `. ${esc(m.name)} typed: “${esc(m.original)}”` : "") : "A rude word was hidden and reported."}</div>` : "";
    return `<div class="gm ${mine ? "me" : ""} ${m.flagged ? "flag" : ""} ${m.kind === "gif" ? "gifm" : ""}">${mine ? "" : `<div class="who">${esc(m.name)}</div>`}${body}${warn}<small>${clock(m.at)}</small></div>`;
  }).join("") || '<div class="empty" style="margin:auto">No messages yet. Say hello 👋</div>';
  root.scrollTop = root.scrollHeight;
  const ro = d.role !== "member"; $("#gIn").disabled = ro; $("#gF button.btn.solid").disabled = ro; $("#gGif").disabled = ro; $("#gIn").placeholder = ro ? "Only members can write" : "Write to the group";
  void parentView;
}
async function groupSend(body, kind) {
  const d = S.gdlg; if (!d) return;
  try { const r = await api(`/api/groups/${d.id}/messages`, "POST", { body, kind }); if (r.note) toast(r.note, 8000); await groupFetch(false); } catch (e) { toast(e.message, 6000); }
}
function groupInfo() {
  const d = S.gdlg; if (!d || !d.group) return; const g = S.groups.find(x => x.id === d.id) || {}, kids = S.members.filter(m => m.role === "child").map(m => m.id);
  modal(`<h2>${esc(d.group.emoji)} ${esc(d.group.name)}</h2><div class="sub">${esc(GROUP_RULES)}</div>
    ${g.code ? `<div class="msec">Invite code</div><div class="codebox"><b>${esc(g.code)}</b><button class="btn sm" id="giCopy" type="button">Copy</button></div><p class="sub">Friends type this in Join. A child needs a parent to say yes before they can read or write.</p>` : ""}
    <div class="msec">Members</div>${d.group.members.map(m => `<div class="item"><span class="stemo">${esc(m.emoji)}</span><span style="flex:1"><b style="font-weight:600">${esc(m.name)}</b></span>${isParent() && kids.includes(m.id) ? `<button class="btn sm" data-gi="rm" data-m="${m.id}" type="button">Take out</button>` : ""}</div>`).join("")}
    <div class="foot"><button class="btn bad" id="giLeave" type="button">${d.role === "member" ? "Leave the group" : "Close"}</button><button class="btn solid" id="giBack" type="button">Back</button></div>`, root => {
    const back = () => openGroup(d.id); root.querySelector("#giBack").onclick = back;
    root.querySelector("#giLeave").onclick = async () => { if (d.role !== "member") return closeModal(); if (!confirm("Leave this group?")) return; try { await api(`/api/groups/${d.id}/leave`, "POST"); closeModal(); refreshGroups(); } catch (e) { toast(e.message); } };
    const c = root.querySelector("#giCopy"); if (c) c.onclick = () => { navigator.clipboard && navigator.clipboard.writeText(g.code).then(() => toast("Copied")); };
    root.querySelectorAll("[data-gi=rm]").forEach(b => b.onclick = async () => { if (!confirm("Take this child out of the group?")) return; try { await api(`/api/groups/${d.id}/members/${b.dataset.m}`, "DELETE"); closeModal(); refreshGroups(); } catch (e) { toast(e.message); } });
  });
}
function newGroupDialog() {
  let emoji = "👥";
  modal(`<h2>👥 New friend group</h2><div class="sub">${isParent() ? "Friends join with a code." : "A parent will be asked to say yes first, and your parents can read the group. Rude words are hidden and reported."}</div>
    <label class="l">Name</label><input class="field" id="ngN" maxlength="40" placeholder="For example: Football mates" autocomplete="off">
    <label class="l">Picture</label>${emojiPicker(["👥", "⚽", "🎮", "🎨", "🎵", "📚", "🏊", "🚲"], emoji)}
    <div class="foot"><button class="btn" id="ngX" type="button">Cancel</button><button class="btn solid" id="ngOk" type="button">Make it</button></div>`, root => {
    pickEmoji(root, e => { emoji = e; }); root.querySelector("#ngX").onclick = closeModal; root.querySelector("#ngN").focus();
    root.querySelector("#ngOk").onclick = async () => { const name = root.querySelector("#ngN").value.trim(); if (!name) return toast("Give the group a name"); try { const d = await api("/api/groups", "POST", { name, emoji }); closeModal(); toast(d.status === "pending" ? "Made. A parent has to say yes before you can use it." : "Group made. Share its code with friends."); refreshGroups(); } catch (e) { toast(e.message); } };
  });
}
function joinGroupDialog() {
  modal(`<h2>Join a friend group</h2><div class="sub">Type the code a friend gave you. ${isParent() ? "" : "A parent will be asked to say yes first."}</div>
    <label class="l">Code</label><input class="field" id="jgC" maxlength="12" placeholder="ABCD1234" autocomplete="off" style="text-transform:uppercase;letter-spacing:.12em">
    <div class="foot"><button class="btn" id="jgX" type="button">Cancel</button><button class="btn solid" id="jgOk" type="button">Join</button></div>`, root => {
    root.querySelector("#jgX").onclick = closeModal; root.querySelector("#jgC").focus();
    root.querySelector("#jgOk").onclick = async () => { try { const d = await api("/api/groups/join", "POST", { code: root.querySelector("#jgC").value }); closeModal(); toast(d.status === "pending" ? `Asked. A parent has to say yes before you can join ${d.name}.` : `You are in ${d.name}`); refreshGroups(); } catch (e) { toast(e.message); } };
  });
}

/* ---------- linked apps (Platter), activity and search ---------- */
async function refreshLinks() { if (!isParent()) return; try { S.links = (await api("/api/integrations")).integrations; renderSide(); } catch (e) { /* not a parent, or offline */ } }
function linkedHtml() {
  if (!isParent()) return '<p class="tabhelp">A parent can link apps like Platter and Scout, so recipes and places you pick there show up for the family.</p>';
  const have = n => S.links.some(l => l.name === n);
  return `<p class="tabhelp">Link <b>Platter</b> to get recipes on the family meal plan, and <b>Scout</b> to put places on the calendar. Each linked app can only do the one thing it is for. You can unlink it any time.</p>
    <ol class="tabhelp" style="padding-left:20px;display:grid;gap:4px"><li>Open the app and install it (the browser menu has Install or Add to Home Screen).</li><li>Tap <b>Link</b> below. Mellow makes a private link.</li><li>Open that link on the device where you use the app. It connects by itself.</li></ol>
    ${S.links.map(l => `<div class="item"><span class="stemo">${(LINKABLE[l.name] || {}).icon || "🔗"}</span><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${esc(l.name)}</b><small>${l.last_used ? "Used " + ago(l.last_used) : "Not used yet"}</small></span><button class="btn sm" data-act="unlink" data-id="${l.id}">Unlink</button></div>`).join("")}
    <p class="tabhelp">🕵️ <b>Alibi</b> needs no linking: tap 🎮 in the family chat to start a game for everyone.</p>
    ${Object.keys(LINKABLE).filter(n => !have(n)).map(n => `<button class="btn solid" data-act="linkapp" data-id="${n}" style="width:100%;margin-top:6px">${LINKABLE[n].icon} Link ${n}</button>`).join("")}`;
}
async function linkApp(name) {
  const app = LINKABLE[name]; if (!app) return;
  try {
    const d = await api("/api/integrations", "POST", { name }), json = JSON.stringify({ b: location.origin, k: d.key, f: S.family ? S.family.name : "" });
    const code = btoa(unescape(encodeURIComponent(json))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""), url = `${app.url()}/#/homebase?c=${code}`;
    modal(`<h2>${app.icon} Link ${name}</h2><div class="sub">${esc(app.what)} Open the link on the device where you use ${name}. It connects by itself. Anyone with this link can use it on your family's behalf, so only share it with yourself.</div>
      <div class="foot" style="justify-content:flex-start"><a class="btn solid" href="${esc(url)}" target="_blank" rel="noopener">Open ${name} and connect</a><button class="btn" id="lpCopy" type="button">Copy the link</button></div>
      <div class="foot"><button class="btn" id="lpX" type="button">Done</button></div>`, root => {
      root.querySelector("#lpX").onclick = closeModal; root.querySelector("#lpCopy").onclick = () => { navigator.clipboard && navigator.clipboard.writeText(url).then(() => toast("Link copied")); };
    });
    refreshLinks();
  } catch (e) { toast(e.message); }
}
async function refreshFeed() { try { S.feed = (await api("/api/feed")).items; renderSide(); } catch (e) { /* offline */ } }
const feedIcon = it => it.kind === "sos" ? "🚨" : it.kind === "late" ? "⏰" : it.kind === "checkin" ? "✅" : it.kind === "pickup" ? "🚗" : /arrived|left/.test(it.body) ? "📍" : /job|approved|finished/.test(it.body) ? "✅" : /reward/.test(it.body) ? "🎁" : /plan/.test(it.body) ? "📅" : /joined|linked/.test(it.body) ? "👋" : "•";
function feedHtml() {
  return S.feed.length ? S.feed.slice(0, 40).map(it => `<div class="feed"><span>${feedIcon(it)}</span><div><b>${esc(it.body)}</b><small>${esc(ago(it.at))}</small></div></div>`).join("") : '<div class="empty">Nothing has happened yet.</div>';
}
const MORE_TABS = [["me", "Me"], ["family", "Family"], ["safety", "Safety"], ["settings", "Settings"]];
function moreHtml() {
  const tab = S.moreTab || (S.moreTab = (() => { try { return localStorage.getItem("hb_more") || "me"; } catch (e) { return "me"; } })());
  const bar = `<div class="mtabs" style="display:flex;flex-wrap:nowrap;gap:4px;padding:4px;border-radius:14px;background:var(--surface2);margin-bottom:12px">${MORE_TABS.map(([k, n]) => `<button type="button" class="btn sm ${tab === k ? "solid" : ""}" style="flex:1 1 0;min-width:0;padding:8px 2px;font-size:13px" data-act="moretab" data-id="${k}">${n}</button>`).join("")}</div>`;
  if (tab === "me") return bar + setupCardHtml() + youCard() + statusCardHtml() + moodCardHtml() + pickupCardHtml() + birthdayCardHtml() + myControlsHtml();
  if (tab === "safety") return bar + safetyTabHtml();
  if (tab === "settings") return bar + settingsTabHtml();
  return bar + familyTabHtml();
}
function familyTabHtml() {
  const n = jobsAlert(), ra = rewardsAlert();
  return accordion("noor", "📖", "Quran (Noor)", noorSub(), noorHtml(), noorAlert())
    + accordion("wallet", "💰", "Pocket money", walletSub(), walletHtml())
    + accordion("album", "📸", "Album", (S.album || []).length ? S.album.length + " photos" : "Family photos", albumHtml())
    + accordion("kudos", "⭐", "Kudos and badges", S.kudos && S.kudos.star ? "Star of the week: " + S.kudos.star.name : "Say thanks", kudosHtml())
    + accordion("homework", "📚", "Homework", (S.homework || []).filter(h => !h.done).length + " to do", homeworkHtml())
    + accordion("rewards", "🎁", "Rewards", isParent() ? (ra ? ra + " to answer" : "What points can buy") : myBal() + " points to spend", rewardsHtml(), ra)
    + accordion("jobs", "✅", "Jobs and list", n ? (isParent() ? n + " to approve" : n + " to do") : "Points and the family list", jobsHtml(), n)
    + accordion("board", "📌", "Family board", (S.board || []).length ? S.board.length + (S.board.length === 1 ? " note" : " notes") : "Wi-Fi, the doctor, who to call", boardHtml())
    + accordion("rules", "📋", "Family rules", "What the family agreed", `<p class="tabhelp">${isParent() ? "Write the house rules and set each child's daily limit." : "Read what your family agreed."}</p><button class="btn solid" data-act="rules" data-id="0">${isParent() ? "Edit the rules" : "Read the rules"}</button>`)
    + accordion("links", "🔗", "Linked apps", S.links.length ? S.links.length + " linked" : "Platter and more", linkedHtml())
    + accordion("feed", "🕓", "Activity", "What has been happening", feedHtml());
}
function settingsTabHtml() {
  return `<section class="calcard"><h3>Mellow</h3><p class="tabhelp">The web version is always the newest: it updates by itself. On Android, the app offers updates and installs them in one tap.</p>
    <div class="morebtns"><button class="btn" data-act="search">🔎 Search</button><button class="btn" data-act="menu">⚙️ Settings and look</button>${isParent() ? '<button class="btn" data-act="invite">＋ Invite someone</button>' : ""}<a class="btn" href="/update#log" target="_blank" rel="noopener">📜 Update log</a></div></section>`;
}
function safetyTabHtml() {
  const t = (S.trusted || []).map(p => `<div class="item"><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${esc(p.name)}</b><small>${esc([p.relation, p.phone].filter(Boolean).join(" · "))}</small></span><a class="btn sm" href="tel:${esc(p.phone)}">Call</a>${isParent() ? `<button class="btn sm" data-act="trustdel" data-id="${p.id}">✕</button>` : ""}</div>`).join("");
  return `<section class="calcard"><h3>🛟 Safety</h3><div class="morebtns"><button class="btn" data-act="walk">🚶 Safe Walk</button><button class="btn" data-act="trip">🔗 Share my trip</button><button class="btn" data-act="ringme">📱 Find my phone</button></div></section>`
    + kidToolsHtml()
    + `<section class="calcard"><h3>🧑‍🤝‍🧑 Trusted adults</h3>${t || '<div class="empty">Nobody yet.</div>'}${isParent() ? '<button class="btn sm" data-act="trustadd" style="margin-top:8px">＋ Add someone</button>' : ""}</section>`;
}

/* ---------- 5.0 on the web: pocket money, album, kudos, homework, mood, countdowns, weather, safe walk, trip ---------- */
async function refreshV5() {
  const get = async (path, key, pick) => { try { const d = await api(path); S[key] = pick(d); } catch (e) { /* offline or not allowed */ } };
  await Promise.all([get("/api/wallet", "wallet", d => d.wallets), get("/api/album", "album", d => d.photos), get("/api/kudos", "kudos", d => d),
    get("/api/badges", "badges", d => d.badges), get("/api/homework", "homework", d => d.items), get("/api/trusted", "trusted", d => d.people),
    get("/api/countdowns", "countdowns", d => d.countdowns), get("/api/weather", "weather", d => d), get("/api/noor/family", "noor", d => d)]);
  renderSide();
}
const money = c => (c < 0 ? "-" : "") + (Math.abs(c) / 100).toLocaleString(undefined, { style: "currency", currency: "GBP" }).replace("£", "") ;
function walletSub() { const w = S.wallet || []; if (!w.length) return "Wallets and goals"; return isParent() ? w.map(x => x.name + " " + money(x.balance)).join(" · ") : money(w[0].balance) + " to spend"; }
function walletHtml() {
  return (S.wallet || []).map(w => `<div class="item" style="display:block"><b style="font-weight:600">${esc(isParent() ? w.name : "My wallet")}: ${money(w.balance)}</b>
    ${w.entries.filter(e => e.status === "pending").map(e => `<div class="pl-btns" style="margin-top:6px"><small style="flex:1">${e.cents < 0 ? "Wants to spend" : "Asks for"} ${money(Math.abs(e.cents))}${e.note ? " · " + esc(e.note) : ""}</small>${isParent() ? `<button class="btn sm" data-act="walletno" data-id="${e.id}">Not now</button><button class="btn sm solid" data-act="walletyes" data-id="${e.id}">Yes</button>` : "<small>waiting</small>"}</div>`).join("")}
    ${w.goals.map(g => `<div style="margin-top:6px"><small>${esc(g.emoji)} ${esc(g.title)} · ${money(g.saved)} of ${money(g.target)}</small><div style="height:8px;border-radius:4px;background:var(--surface2)"><div style="height:8px;border-radius:4px;background:var(--accent);width:${Math.min(100, Math.round(g.saved * 100 / Math.max(1, g.target)))}%"></div></div></div>`).join("")}
    ${isParent() ? `<div class="pl-btns" style="margin-top:8px"><button class="btn sm solid" data-act="walletadd" data-id="${w.member}">＋ Add money</button></div>` : '<div class="pl-btns" style="margin-top:8px"><button class="btn sm" data-act="walletask">Ask a parent</button></div>'}</div>`).join("") || '<div class="empty">Nothing yet.</div>';
}
function albumHtml() {
  const a = S.album || [];
  return `<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:4px">${a.slice(0, 24).map(p => `<a href="${esc(p.url)}" target="_blank" rel="noopener"><img src="${esc(p.url)}" alt="" loading="lazy" style="width:100%;aspect-ratio:1;object-fit:cover;border-radius:8px"></a>`).join("")}</div>${a.length ? "" : '<div class="empty">No photos yet. Photos sent in the family chat land here.</div>'}
    <label class="btn sm" style="margin-top:8px;cursor:pointer">＋ Add a photo<input type="file" accept="image/*" data-albumup hidden></label>`;
}
function kudosHtml() {
  const k = S.kudos || {}, b = S.badges || [];
  return `${k.star ? `<p class="tabhelp">⭐ Star of the week: <b>${esc(k.star.name)}</b> (${k.star.count} kudos)</p>` : ""}
    <div class="pl-btns">${S.members.filter(m => m.id !== S.me).map(m => `<button class="btn sm" data-act="kudo" data-id="${m.id}">⭐ ${esc(m.name)}</button>`).join("")}</div>
    <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin-top:10px">${b.map(x => `<div class="item" style="display:block;text-align:center;opacity:${x.earned ? 1 : .55}"><div style="font-size:24px">${x.earned ? x.emoji : "🔒"}</div><small>${esc(x.name)}<br>${x.earned ? "Earned" : x.have + "/" + x.goal}</small></div>`).join("")}</div>
    ${(k.recent || []).slice(0, 5).map(r => `<small style="display:block">${esc(r.emoji)} ${esc(r.from)} → ${esc(r.to)}${r.reason ? ": " + esc(r.reason) : ""}</small>`).join("")}`;
}
function homeworkHtml() {
  return (S.homework || []).map(h => `<div class="item"><input type="checkbox" ${h.done ? "checked" : ""} data-hw="${h.id}"><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${esc(h.title)}</b><small>${esc([isParent() ? h.name : "", h.subject, "due " + dayLabel(h.due)].filter(Boolean).join(" · "))}</small></span></div>`).join("")
    + `<button class="btn sm" data-act="hwadd" style="margin-top:6px">＋ Add homework</button>`;
}
/* ---------- Quran (Noor): everyone's reading, and tasks parents give ---------- */
const SURAHS_EN = ["Al-Fatihah","Al-Baqarah","Ali 'Imran","An-Nisa","Al-Ma'idah","Al-An'am","Al-A'raf","Al-Anfal","At-Tawbah","Yunus","Hud","Yusuf","Ar-Ra'd","Ibrahim","Al-Hijr","An-Nahl","Al-Isra","Al-Kahf","Maryam","Taha","Al-Anbiya","Al-Hajj","Al-Mu'minun","An-Nur","Al-Furqan","Ash-Shu'ara","An-Naml","Al-Qasas","Al-'Ankabut","Ar-Rum","Luqman","As-Sajdah","Al-Ahzab","Saba","Fatir","Ya-Sin","As-Saffat","Sad","Az-Zumar","Ghafir","Fussilat","Ash-Shura","Az-Zukhruf","Ad-Dukhan","Al-Jathiyah","Al-Ahqaf","Muhammad","Al-Fath","Al-Hujurat","Qaf","Adh-Dhariyat","At-Tur","An-Najm","Al-Qamar","Ar-Rahman","Al-Waqi'ah","Al-Hadid","Al-Mujadilah","Al-Hashr","Al-Mumtahanah","As-Saff","Al-Jumu'ah","Al-Munafiqun","At-Taghabun","At-Talaq","At-Tahrim","Al-Mulk","Al-Qalam","Al-Haqqah","Al-Ma'arij","Nuh","Al-Jinn","Al-Muzzammil","Al-Muddaththir","Al-Qiyamah","Al-Insan","Al-Mursalat","An-Naba","An-Nazi'at","'Abasa","At-Takwir","Al-Infitar","Al-Mutaffifin","Al-Inshiqaq","Al-Buruj","At-Tariq","Al-A'la","Al-Ghashiyah","Al-Fajr","Al-Balad","Ash-Shams","Al-Layl","Ad-Duha","Ash-Sharh","At-Tin","Al-'Alaq","Al-Qadr","Al-Bayyinah","Az-Zalzalah","Al-'Adiyat","Al-Qari'ah","At-Takathur","Al-'Asr","Al-Humazah","Al-Fil","Quraysh","Al-Ma'un","Al-Kawthar","Al-Kafirun","An-Nasr","Al-Masad","Al-Ikhlas","Al-Falaq","An-Nas"];
const surahName = n => SURAHS_EN[n - 1] || ("Surah " + n);
const KIND_WORD = { read: "Read", memorize: "Memorize", revise: "Revise" };
function noorAlert() { const n = S.noor; if (!n) return 0; return n.tasks.filter(t => !t.done && t.to_id === S.me).length; }
function noorSub() {
  const n = S.noor; if (!n) return "Read the Quran together";
  const linked = n.members.filter(m => m.linked).length, open = n.tasks.filter(t => !t.done).length;
  return `${linked} of ${n.members.length} using Noor${open ? " · " + open + " task" + (open === 1 ? "" : "s") + " open" : ""}`;
}
function noorHtml() {
  const n = S.noor; if (!n) return '<div class="empty">Loading…</div>';
  const ago2 = t => t ? ago(t) : "";
  const meLinked = (n.members.find(m => m.id === S.me) || {}).linked;
  const people = n.members.map(m => { const s = m.stats || {}, last = s.last && s.last.s ? `${surahName(s.last.s)} ${s.last.s}:${s.last.a}` : "";
    return `<div class="item" style="display:block"><b style="font-weight:600">${esc(m.emoji || "")} ${esc(m.name)}</b>${m.linked ? `<small style="float:right">${esc(ago2(m.synced))}</small>
      <small style="display:block">🔥 ${s.streak || 0} days · ${s.today || 0} ayahs today${s.khatma ? ` · khatma ${s.khatma.done || 0}/${s.khatma.days || 0}` : ""}${s.hifz ? ` · ${s.hifz.surahs || 0} surahs memorized` : ""}</small>
      ${last ? `<small style="display:block">Last read: ${esc(last)}</small>` : ""}${s.recite ? `<small style="display:block">Last recitation score: ${s.recite.score}%</small>` : ""}` : `<small style="display:block">Not using Noor yet</small>`}</div>`; }).join("");
  const open = n.tasks.filter(t => !t.done), done = n.tasks.filter(t => t.done).slice(0, 6);
  const task = t => `<div class="item"><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${KIND_WORD[t.kind] || "Read"} ${esc(surahName(t.surah))} ${t.from}–${t.upto}</b>
    <small>${esc([isParent() ? "for " + t.to_name : "from " + t.by, t.note, t.due ? "by " + t.due : "", t.done ? "done " + ago2(t.done_at) + (t.score >= 0 ? " · " + t.score + "%" : "") : ""].filter(Boolean).join(" · "))}</small></span>
    ${!t.done ? `<a class="btn sm" href="${esc(n.noor_url)}/#/s/${t.surah}/${t.from}" target="_blank" rel="noopener">Open</a>${t.to_id === S.me ? `<button class="btn sm solid" data-act="noordone" data-id="${t.id}">Done</button>` : ""}` : "✅"}
    ${isParent() && !t.done ? `<button class="btn sm" data-act="noordel" data-id="${t.id}">✕</button>` : ""}</div>`;
  return `<p class="tabhelp">${meLinked ? "Your Noor is linked." : "Link Noor, the Quran app, to share your reading with the family and get tasks."}</p>
    <div class="pl-btns"><button class="btn sm ${meLinked ? "" : "solid"}" data-act="noorlink">${meLinked ? "Link Noor again" : "📖 Link Noor"}</button>${isParent() ? '<button class="btn sm solid" data-act="noortask">＋ Give a reading task</button>' : ""}</div>
    <h4 style="margin:12px 0 4px">Tasks</h4>${open.map(task).join("") || '<div class="empty">No open tasks.</div>'}${done.length ? '<h4 style="margin:12px 0 4px">Finished</h4>' + done.map(task).join("") : ""}
    <h4 style="margin:12px 0 4px">Who read what</h4>${people}`;
}
async function noorAct(act, id) {
  const reload = async () => { try { S.noor = await api("/api/noor/family"); } catch (e) { /* offline */ } renderSide(); };
  if (act === "noorlink") {
    try { const r = await api("/api/noor/code", "POST"); window.open(r.url, "_blank", "noopener"); toast("Noor opened. Keep this page open; your link is ready."); } catch (e) { toast(e.message); }
    return true;
  }
  if (act === "noortask") {
    const kids = S.members.filter(m => m.id !== S.me);
    const who = prompt("Who is it for? Type the name:\n" + S.members.map(m => m.name).join(", "), kids[0] ? kids[0].name : ""); if (who === null) return true;
    const m = S.members.find(x => x.name.toLowerCase() === who.trim().toLowerCase()); if (!m) { toast("Nobody called " + who); return true; }
    const kind = (prompt("Read, memorize or revise?", "read") || "").trim().toLowerCase(); if (!kind) return true;
    const sq = (prompt("Which surah? (a number 1-114 or a name, like Al-Mulk)", "67") || "").trim(); if (!sq) return true;
    let s = /^\d+$/.test(sq) ? +sq : SURAHS_EN.findIndex(x => x.toLowerCase().replace(/[^a-z]/g, "") === sq.toLowerCase().replace(/[^a-z]/g, "")) + 1;
    if (!(s >= 1 && s <= 114)) { toast("I couldn't find that surah"); return true; }
    const range = (prompt(`Which ayahs of ${surahName(s)}? (like 1-10, or leave empty for all)`, "") || "").trim();
    const [a, b] = range ? range.split(/[-–]/).map(x => parseInt(x)) : [1, 999];
    const note = prompt("A note? (optional)", "") || "";
    try { await api("/api/noor/tasks", "POST", { to: m.id, kind: kind.startsWith("mem") ? "memorize" : kind.startsWith("rev") ? "revise" : "read", surah: s, from: a || 1, to_ayah: b || a || 999, note }); toast("Task sent to " + m.name); } catch (e) { toast(e.message); }
    await reload(); return true;
  }
  if (act === "noordel") { if (confirm("Remove this task?")) { try { await api(`/api/noor/tasks/${id}`, "DELETE"); } catch (e) { toast(e.message); } await reload(); } return true; }
  if (act === "noordone") { try { await api(`/api/noor/tasks/${id}/done`, "POST"); toast("Well done!"); } catch (e) { toast(e.message); } await reload(); return true; }
  return false;
}
const FACES = ["", "😢", "😕", "😐", "🙂", "😄"];
function moodCardHtml() { return `<section class="calcard"><h3>How are you feeling?</h3><div class="pl-btns">${[1, 2, 3, 4, 5].map(v => `<button class="btn" style="font-size:22px" data-act="mood" data-id="${v}">${FACES[v]}</button>`).join("")}</div><small>Your family sees it, unless you keep it private in the app.</small></section>`; }
function weatherHtml() {
  const w = S.weather; if (!w) return "";
  return `<section class="tonight"><span>${esc(w.icon)}</span><div><b>${w.temp}° · ${esc(w.words)}</b><small>At ${esc(w.where)} · ${w.days.slice(0, 3).map(d => esc(d.icon) + " " + d.max + "°/" + d.min + "°").join("  ")}</small></div></section>`;
}
function countdownsHtml() {
  const c = S.countdowns || []; if (!c.length) return "";
  return `<section class="calcard"><h3>⏳ Counting down</h3>${c.slice(0, 4).map(x => { const d = Math.ceil((x.at - Date.now() / 1000) / 86400); return `<div class="item"><span style="flex:1">${esc(x.emoji)} ${esc(x.title)}</span><b>${d <= 0 ? "Today!" : d === 1 ? "Tomorrow" : d + " days"}</b></div>`; }).join("")}</section>`;
}
async function v5Act(act, id, el) {
  if (act && act.startsWith("noor")) return noorAct(act, id);
  const ask = (q, d) => { const v = prompt(q, d || ""); return v === null ? null : v.trim(); };
  const run = async (fn, done) => { try { await fn(); if (done) toast(done); refreshV5(); } catch (e) { toast(e.message); } };
  if (act === "moretab") { S.moreTab = el.dataset.id; try { localStorage.setItem("hb_more", S.moreTab); } catch (e) { /* ignore */ } renderSide(); return true; }
  if (act === "walletadd") { const v = ask("How much? (use a minus to take some off)"); if (v) await run(() => api("/api/wallet", "POST", { member: id, amount: parseFloat(v), note: ask("What for? (optional)") || "" }), "Saved"); return true; }
  if (act === "walletask") { const v = ask("How much do you want to spend? (or put + in front to ask for money)"); if (v) await run(() => api("/api/wallet/request", "POST", { amount: v.startsWith("+") ? parseFloat(v) : -Math.abs(parseFloat(v)), note: ask("What for?") || "" }), "Asked"); return true; }
  if (act === "walletyes" || act === "walletno") { await run(() => api(`/api/wallet/${id}/answer`, "POST", { ok: act === "walletyes" }), "Answered"); return true; }
  if (act === "kudo") { const r = ask("What for? (optional)"); if (r !== null) await run(() => api("/api/kudos", "POST", { to: id, emoji: "⭐", reason: r }), "Kudos sent ⭐"); return true; }
  if (act === "hwadd") { const t = ask("What is the homework?"); if (!t) return true; const due = ask("Due date (YYYY-MM-DD)", todayKey()); let member; if (isParent()) { const kids = S.members.filter(m => m.role === "child"); member = kids.length === 1 ? kids[0].id : (kids.find(k => k.name.toLowerCase() === (ask("For whom? " + kids.map(k => k.name).join(", ")) || "").toLowerCase()) || {}).id; }
    await run(() => api("/api/homework", "POST", { title: t, due, member }), "Added"); return true; }
  if (act === "mood") { await run(() => api("/api/mood", "POST", { mood: id, note: "" }), "Thanks for sharing 💛"); return true; }
  if (act === "trustadd") { const n = ask("Name"); const p = n && ask("Phone number"); if (n && p) await run(() => api("/api/trusted", "POST", { name: n, phone: p, relation: ask("Who are they? (optional)") || "" }), "Added"); return true; }
  if (act === "trustdel") { await run(() => api(`/api/trusted/${id}`, "DELETE"), "Removed"); return true; }
  if (act === "ringme") { await run(() => api(`/api/members/${S.me}/ring`, "POST"), "Your phones will ring"); return true; }
  if (act === "trip") { const h = ask("Share your live location for how many hours? (1 to 8)", "1"); if (!h) return true; try { const d = await api("/api/trip", "POST", { hours: parseInt(h, 10) || 1 }); prompt("Send this link. It stops working by itself:", d.url); } catch (e) { toast(e.message); } return true; }
  if (act === "walk") {
    const places = S.places.filter(p => !p.pending); if (!places.length) { toast("Add a zone first, like Home."); return true; }
    const name = ask("Walking to? " + places.map(p => p.name).join(", "), (places.find(p => p.name === "Home") || places[0]).name); const p = places.find(x => x.name.toLowerCase() === (name || "").toLowerCase());
    if (p) await run(() => api("/api/walk", "POST", { place: p.id, minutes: parseInt(ask("About how many minutes?", "20"), 10) || 20 }), "Safe Walk started. Keep this page open on your phone, or use the app."); return true;
  }
  return false;
}
document.addEventListener("change", async e => {
  const t = e.target;
  if (t && t.dataset && t.dataset.hw) { try { await api(`/api/homework/${t.dataset.hw}`, "POST", { done: t.checked }); refreshV5(); } catch (err) { toast(err.message); } }
  if (t && t.matches && t.matches("[data-albumup]") && t.files && t.files[0]) {
    try {
      const f = t.files[0], img = await new Promise((ok, bad) => { const r = new FileReader(); r.onload = () => { const i = new Image(); i.onload = () => ok(i); i.onerror = bad; i.src = r.result; }; r.readAsDataURL(f); });
      const k = Math.min(1, 1600 / Math.max(img.width, img.height)), c = document.createElement("canvas"); c.width = img.width * k; c.height = img.height * k; c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
      const up = await api("/api/images", "POST", { image: c.toDataURL("image/jpeg", 0.82) }); await api("/api/album", "POST", { url: up.url, caption: "" }); toast("Added to the album"); refreshV5();
    } catch (err) { toast(err.message || "Could not add it"); }
  }
});
function searchDialog() {
  modal(`<h2>🔎 Search Mellow</h2><input class="field" id="sqIn" placeholder="People, plans, meals, notes, jobs, groups…" autocomplete="off"><div id="sqOut" class="sq-out"></div><div class="foot"><button class="btn" id="sqX" type="button">Close</button></div>`, root => {
    const out = root.querySelector("#sqOut"), go = (tab, fn) => () => { closeModal(); setTab(tab); if (fn) fn(); };
    const run = () => {
      const q = root.querySelector("#sqIn").value.trim().toLowerCase(); if (q.length < 2) { out.innerHTML = '<div class="empty">Type at least two letters.</div>'; return; }
      const has = (...f) => f.some(x => String(x || "").toLowerCase().includes(q)), res = [];
      S.members.filter(m => has(m.name)).forEach(m => res.push(["🙂", m.name, m.role === "parent" ? "Parent" : "Child", go("map", () => { S.open = m.id; renderSide(); focusMember(m.id); })]));
      S.plans.filter(p => has(p.title, p.note)).forEach(p => res.push([p.emoji, p.title, whenTxt(p.start), go("plan")]));
      S.meals.filter(m => has(m.title, (m.ingredients || []).join(" "))).forEach(m => res.push([SLOT_ICON[m.slot] || "🍽️", m.title, dayLabel(m.day), go("plan", () => mealDialog(m.id))]));
      (S.board || []).filter(n => has(n.title, n.body)).forEach(n => res.push([n.emoji, n.title, "Family board", go("more")]));
      S.items.filter(i => has(i.text)).forEach(i => res.push(["🛒", i.text, "Family list", go("more")]));
      S.chores.filter(c => has(c.title)).forEach(c => res.push([c.emoji, c.title, "Job", go("more")]));
      S.polls.filter(p => has(p.question)).forEach(p => res.push(["🗳️", p.question, "Vote", go("plan")]));
      (S.rewards || []).filter(r => has(r.title)).forEach(r => res.push([r.emoji, r.title, "Reward", go("more")]));
      S.places.filter(p => has(p.name)).forEach(p => res.push([p.emoji, p.name, "Place", go("map")]));
      S.groups.filter(g => g.status !== "pending" && has(g.name)).forEach(g => res.push([g.emoji, g.name, "Friend group", () => { closeModal(); setTab("chat"); if (g.status) openGroup(g.id); }]));
      out.innerHTML = res.length ? res.slice(0, 30).map((r, i) => `<button type="button" class="sq-row" data-i="${i}"><span>${esc(r[0])}</span><b>${esc(r[1])}</b><small>${esc(r[2])}</small></button>`).join("") : '<div class="empty">Nothing found.</div>';
      out.querySelectorAll("[data-i]").forEach(b => b.onclick = res[+b.dataset.i][3]);
    };
    root.querySelector("#sqIn").oninput = run; root.querySelector("#sqX").onclick = closeModal; root.querySelector("#sqIn").focus(); run();
  });
}

/* ---------- wiring ---------- */
/* ---------- search recipes (the library comes with Mellow; a child suggests, a parent says yes; anyone can open it in Platter) ---------- */
const recipeSearch = (q, random) => api("/api/recipes?q=" + encodeURIComponent(q || "") + (random ? "&random=1" : ""));
function recipeDialog(day) {
  modal(`<h2>🔎 Search recipes</h2><div class="sub">${isParent() ? "Pick one and add it to the meal plan." : "Pick one and suggest it. A parent says yes."}</div>
    <div style="display:flex;gap:8px"><input class="field" id="rcQ" placeholder="Chicken, pasta, soup…" autocomplete="off"><button class="btn solid" id="rcGo" type="button">Search</button></div>
    <div class="foot" style="justify-content:flex-start"><button class="btn sm" id="rcRand" type="button">🎲 Surprise me</button></div>
    <div id="rcOut" class="sq-out"></div><div class="foot"><button class="btn" id="rcX" type="button">Close</button></div>`, root => {
    const out = root.querySelector("#rcOut");
    const show = async random => {
      out.innerHTML = '<div class="empty">Looking…</div>';
      try {
        const d = await recipeSearch(root.querySelector("#rcQ").value.trim(), random);
        out.innerHTML = d.recipes.length ? d.recipes.map(r => `<div class="item"><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${esc(r.title)}</b><small>${r.min ? r.min + " min · " : ""}${r.serves ? "serves " + r.serves : ""}</small></span><a class="btn sm" href="${esc(r.platter)}" target="_blank" rel="noopener">Platter</a><button class="btn sm solid" data-add="${esc(r.id)}" type="button">${isParent() ? "Add" : "Suggest"}</button></div>`).join("") : '<div class="empty">No recipes found.</div>';
        out.querySelectorAll("[data-add]").forEach(b => b.onclick = async () => { try { const x = await api("/api/meals", "POST", { recipe: b.dataset.add, day: day || todayKey(), slot: "dinner" }); toast(x.suggested ? "Suggested. A parent will see it." : "Added to the meal plan"); refreshMeals(); loadCal(); } catch (e) { toast(e.message); } });
      } catch (e) { out.innerHTML = '<div class="empty">Could not load recipes.</div>'; }
    };
    root.querySelector("#rcGo").onclick = () => show(false); root.querySelector("#rcQ").onkeydown = e => { if (e.key === "Enter") show(false); };
    root.querySelector("#rcRand").onclick = () => show(true); root.querySelector("#rcX").onclick = closeModal; show(true);
  });
}
async function hb2Act(act, id, el) {
  if (await v5Act(act, id, el)) return;
  if (act === "imok") { try { await api("/api/messages", "POST", { kind: "checkin", body: "I'm OK", to: 0 }); await api("/api/critical/ack", "POST"); toast("Your family knows you are OK"); } catch (e) { toast(e.message); } return; }
  if (act === "crit") { try { await api(`/api/members/${id}/critical`, "POST"); toast("Their phone will ring until they tap I'm OK"); } catch (e) { toast(e.message); } return; }
  if (act === "ring") { try { await api(`/api/members/${id}/ring`, "POST"); toast("Ringing their phone"); } catch (e) { toast(e.message); } return; }
  if (act === "zoneyes" || act === "zoneno") { try { await api(`/api/places/${id}/approve`, "POST", { ok: act === "zoneyes" }); toast(act === "zoneyes" ? "Zone added" : "Answered"); } catch (e) { toast(e.message); } return; }
  if (act === "riskseen") { try { await api(`/api/risk/${id}/seen`, "POST"); refreshRisk(); } catch (e) { toast(e.message); } return; }
  if (act === "recipes") return recipeDialog();
  if (act === "mealyes" || act === "mealno") { try { await api(`/api/meals/${id}/approve`, "POST", { ok: act === "mealyes" }); refreshMeals(); loadCal(); } catch (e) { toast(e.message); } return; }
  if (act === "qok") return checkIn();
  if (act === "qlate") return lateDialog();
  if (act === "addpoll") return pollDialog();
  if (act === "search") return searchDialog();
  if (act === "menu") return meDialog();
  if (act === "plan_tab") return setTab("plan");
  if (act === "pickmap") { S.open = id; setTab("map"); return focusMember(id); }
  if (act === "openthread") { setChat(true); return loadThread(id); }
  if (act === "addmeal") return addMealDialog();
  if (act === "meal") return mealDialog(id);
  if (act === "cook") { const m = S.meals.find(x => x.id === id) || (S.cal && S.cal.meals.find(x => x.id === id)); try { await api(`/api/meals/${id}/cook`, "POST", { cook: m && m.cook === S.me ? 0 : S.me }); refreshMeals(); loadCal(); } catch (e) { toast(e.message); } return; }
  if (act === "meallist") return mealToList(id);
  if (act === "delmeal") { if (confirm("Remove this meal?")) { await api("/api/meals/" + id, "DELETE").catch(e => toast(e.message)); refreshMeals(); loadCal(); } return; }
  if (act === "calday") { if (S.cal) { S.cal.sel = id; renderSide(); } return; }
  if (act === "calnav") { if (S.cal) { let m = S.cal.m + id, y = S.cal.y; if (m < 0) { m = 11; y--; } if (m > 11) { m = 0; y++; } S.cal = { y, m, sel: 1, plans: [], meals: [] }; renderSide(); loadCal(); } return; }
  if (act === "calplan") return planDialog(el.dataset.id);
  if (act === "calmeal") return addMealDialog(el.dataset.id);
  if (act === "ics") return downloadIcs();
  if (act === "group") return openGroup(id);
  if (act === "grpnew") return newGroupDialog();
  if (act === "grpjoin") return joinGroupDialog();
  if (act === "grpok" || act === "grpno") { try { await api(`/api/groups/${id}/approve`, "POST", { member: +el.dataset.m, ok: act === "grpok" }); refreshGroups(); } catch (e) { toast(e.message); } return; }
  if (act === "game") return gameDialog();
  if (act === "linkapp") return linkApp(el.dataset.id);
  if (act === "unlink") { if (confirm("Unlink this app? It will not be able to add meals any more.")) { await api("/api/integrations/" + id, "DELETE").catch(e => toast(e.message)); refreshLinks(); } return; }
}
async function downloadIcs() {
  try {
    const r = await fetch("/api/calendar.ics", { headers: { Authorization: "Bearer " + S.token } }); if (!r.ok) throw new Error("Could not make the calendar file.");
    const url = URL.createObjectURL(await r.blob()), a = document.createElement("a"); a.href = url; a.download = "homebase-family.ics"; document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000); toast("Saved. Open the file to add it to your calendar.", 5000);
  } catch (e) { toast(e.message); }
}
function hb2Event(type, p) {
  if (type === "risk_alert") { refreshRisk(); toast(`⚠️ Safety alert for ${p.name}: ${p.what}`, 10000); notify("⚠️ Safety alert for " + p.name, "A message looked like " + p.what + ".", { tag: "risk-" + p.id, force: true }); return; }
  if (type === "zone_request") { toast(`📍 ${p.name} asks for a zone: ${p.place}`, 8000); return; }
  if (type === "critical") { toast(`🚨 ${p.name} needs you to check in. Tap I'm OK on Home.`, 15000); notify("🚨 " + p.name + " needs you to check in", "Open Mellow and tap I'm OK.", { tag: "critical", force: true }); return; }
  if (["critical_ok", "offline", "online", "walk", "walk_done", "walk_late", "walk_still", "expect_ok", "expect_late", "speeding", "kudos", "wallet", "wallet_request", "homework_due", "bedtime"].includes(type)) {
    const title = p.title || (type === "critical_ok" ? `✅ ${p.name} is OK` : type === "offline" ? `📵 ${p.name}'s phone went offline` : type === "online" ? `✅ ${p.name}'s phone is back` : "");
    if (title) { toast(title + (p.text ? " · " + p.text : ""), 8000); notify(title, p.text || "", { tag: type + "-" + (p.member || ""), force: /late|still|speeding|offline/.test(type) }); }
    return;
  }
  if (type === "meals") { refreshMeals(); loadCal(); return; }
  if (type === "meal_suggest") { refreshMeals(); toast(`💡 ${p.name} suggests ${p.title}`, 7000); return; }
  if (type === "meal_ok") { refreshMeals(); toast(p.approved ? `✅ ${p.name} said yes to ${p.title}` : `${p.name} said not now to ${p.title}`, 6000); return; }
  if (type === "group") { refreshGroups(); if (p.approved) toast(`✅ A parent said yes. You are in ${p.group}.`, 6000); if (p.declined) toast("A parent said not now.", 6000); return; }
  if (type === "group_request") { refreshGroups(); toast(`👥 ${p.name} wants to join ${p.group}`, 8000); notify("👥 " + p.name + " wants to join " + p.group, "Open Mellow to say yes or not now.", { tag: "greq-" + p.gid, force: true }); return; }
  if (type === "group_flag") { refreshGroups(); toast(`⚠️ ${p.name}'s message in ${p.group} was censored (${p.cat} language)`, 10000); notify("⚠️ " + p.name + "'s message was censored", "In " + p.group + ". Open Mellow to read it.", { tag: "gflag-" + p.id, force: true }); if (S.gdlg && S.gdlg.id === p.gid) groupFetch(false); return; }
  if (type === "gmessage") {
    if (S.gdlg && S.gdlg.id === p.gid) { groupFetch(false); return; }
    refreshGroups(); toast(`👥 ${p.name} in ${p.group}: ${p.kind === "gif" ? "🎞️ GIF" : String(p.body).slice(0, 80)}`, 5000); notify(p.name + " · " + p.group, p.kind === "gif" ? "Sent a GIF" : p.body, { tag: "grp-" + p.gid });
  }
}
/* a one-time welcome after the rename: shown once per browser */
/* a one-time popup for a parent who has not linked Platter or Scout yet: what they are, how to install and link them */
function linkPrompt() {
  if (!isParent() || !S.links) return;
  const need = Object.keys(LINKABLE).filter(n => !S.links.some(l => l.name === n));
  if (!need.length) return;
  try { if (localStorage.getItem("mellow_linkprompt_1")) return; } catch (e) { return; }
  const seen = () => { try { localStorage.setItem("mellow_linkprompt_1", "1"); } catch (e) { /* private window */ } };
  modal(`<h2>Link Platter and Scout</h2><div class="sub">Two sister apps work with Mellow. Install each one, link it once, and it shares with your family.</div>
    ${need.map(n => `<div class="item"><span class="stemo">${LINKABLE[n].icon}</span><span style="flex:1;min-width:0"><b style="display:block;font-weight:600">${n}</b><small>${esc(LINKABLE[n].what)}</small></span></div>
      <div class="foot" style="justify-content:flex-start;gap:8px;flex-wrap:wrap"><a class="btn sm" href="${esc(LINKABLE[n].url())}" target="_blank" rel="noopener">1 · Open ${n} and install it</a><button class="btn sm solid" data-lk="${n}" type="button">2 · Link ${n}</button></div>`).join("")}
    <p class="sub">Linking makes a private link. Open it on the device where you use the app, in the same browser or app, and it connects by itself. You can unlink any time in More, then Linked apps.</p>
    <div class="foot"><button class="btn" id="lpLater" type="button">Not now</button></div>`, root => {
    root.querySelector("#lpLater").onclick = () => { seen(); closeModal(); };
    root.querySelectorAll("[data-lk]").forEach(b => b.onclick = () => { seen(); linkApp(b.dataset.lk); });
  });
}
function mellowWelcome() {
  try { if (localStorage.getItem("mellow_welcome_1")) return; } catch (e) { return; }
  modal(`<h2>Welcome to Mellow</h2><div class="sub">Homebase has a new name and a calmer look. Your family, messages, plans and settings are all still here.</div>
    <ul class="ing"><li>A black and white look on a contour-line background, with a Dark version in the menu</li><li>Five sections: Home, Map, Plan, Chat and More</li><li>Meals, friend groups, stickers and GIFs, family games</li></ul>
    <p class="sub">If you open Mellow in a different browser, sign in again with an invite code: your sign-in is kept in the browser you used.</p>
    <div class="foot"><button class="btn solid" id="mwOk" type="button">Got it</button></div>`, root => {
    root.querySelector("#mwOk").onclick = () => { try { localStorage.setItem("mellow_welcome_1", "1"); } catch (e) { /* private window */ } closeModal(); setTimeout(linkPrompt, 400); };
  });
}
function hb2Boot() {
  refreshRisk(); refreshV5();
  setTimeout(mellowWelcome, 600);
  setTimeout(() => { const o = $("#over"); if (!(o && o.classList.contains("on"))) linkPrompt(); }, 3500);
  refreshMeals(); refreshGroups(); refreshLinks(); refreshFeed(); loadStickers();
  if (!hb2Boot.done) {
    hb2Boot.done = true;
    const _close = closeModal; closeModal = function () { S.gdlg = null; clearInterval(S.gtimer); _close(); };         // a group conversation stops polling when its window closes
    const gm = $("#gameBtn"); if (gm) gm.onclick = gameDialog; const gb = $("#gifBtn"); if (gb) gb.onclick = gifPicker; const sb = $("#bSearch"); if (sb) sb.onclick = searchDialog;
  }
}

/* Noor asks to link: Mellow makes a one-time code and sends you back to Noor with it */
(function noorLinkFromHash() {
  if (location.hash !== "#noorlink") return;
  const go = async (tries) => {
    if (!S.token) { if (tries === 0) toast("Sign in to Mellow, then Noor will be linked."); if (tries < 120) setTimeout(() => go(tries + 1), 1500); return; }
    try { const r = await api("/api/noor/code", "POST"); history.replaceState(null, "", location.pathname); location.href = r.url; } catch (e) { toast(e.message); }
  };
  setTimeout(() => go(0), 600);
})();
