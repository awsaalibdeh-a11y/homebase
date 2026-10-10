"use strict";
/* Calls on the laptop: WebRTC audio, using the same signalling as the Android app so a laptop and a phone can ring each other. */
window.Calls = (() => {
  const el = document.getElementById("call"), audio = document.getElementById("remote");
  const C = { pc: null, id: 0, peer: null, incoming: false, status: "", stream: null, remoteSet: false, pendingIce: [], localIce: [], offer: "", timeout: 0, t0: 0, tick: 0, muted: false };
  let ac = null, ringIv = 0;

  function beep() {
    try {
      ac = ac || new (window.AudioContext || window.webkitAudioContext)();
      [440, 480].forEach(f => { const o = ac.createOscillator(), g = ac.createGain(); o.frequency.value = f; g.gain.value = .07; o.connect(g); g.connect(ac.destination); o.start(); o.stop(ac.currentTime + .8); });
    } catch (e) { /* no audio */ }
  }
  const ringStart = () => { beep(); ringIv = setInterval(beep, 2600); };
  const ringStop = () => { clearInterval(ringIv); ringIv = 0; };

  function render() {
    if (!C.status) { el.classList.remove("on"); el.innerHTML = ""; return; }
    const p = C.peer || {}, ringing = C.status === "ringing" || C.status === "calling";
    const label = { calling: "Calling…", ringing: "Incoming Homebase call", connecting: "Connecting…", active: "00:00" }[C.status] || "";
    el.innerHTML = `<div class="av ${ringing ? "ring" : ""}">${esc(p.emoji || "🙂")}</div><h2>${esc(p.name || "")}</h2><div class="stt" id="cst">${label}</div>
      <div class="acts">${C.status === "ringing" ? `<button class="btn accept" id="cAns" aria-label="Answer">📞</button><button class="btn end" id="cNo" aria-label="Decline">✕</button>`
        : `${C.status === "active" ? `<button class="btn" id="cMute" aria-label="Mute">${C.muted ? "🔇" : "🎙️"}</button>` : ""}<button class="btn end" id="cEnd" aria-label="Hang up">✕</button>`}</div>`;
    el.classList.add("on");
    const b = id => el.querySelector(id);
    if (b("#cAns")) b("#cAns").onclick = accept; if (b("#cNo")) b("#cNo").onclick = decline; if (b("#cEnd")) b("#cEnd").onclick = hangup;
    if (b("#cMute")) b("#cMute").onclick = () => { C.muted = !C.muted; C.stream && C.stream.getAudioTracks().forEach(t => t.enabled = !C.muted); render(); };
  }
  function setStatus(s) {
    if (C.status === s) return; C.status = s;
    clearInterval(C.tick);
    if (s === "active") { C.t0 = Date.now(); C.tick = setInterval(() => { const e = document.getElementById("cst"); if (e) { const t = Math.floor((Date.now() - C.t0) / 1000); e.textContent = String(Math.floor(t / 60)).padStart(2, "0") + ":" + String(t % 60).padStart(2, "0"); } }, 1000); ringStop(); clearTimeout(C.timeout); }
    render();
  }
  function cleanup() {
    clearTimeout(C.timeout); clearInterval(C.tick); ringStop();
    if (C.stream) C.stream.getTracks().forEach(t => t.stop());
    if (C.pc) { try { C.pc.close(); } catch (e) { /* closed */ } }
    audio.srcObject = null;
    Object.assign(C, { pc: null, id: 0, peer: null, incoming: false, status: "", stream: null, remoteSet: false, pendingIce: [], localIce: [], offer: "", timeout: 0, muted: false });
    render();
  }
  function finish(msg) { cleanup(); if (msg) toast(msg, 4000); }

  function newPC() {
    const pc = new RTCPeerConnection({ iceServers: S.ice.length ? S.ice : [{ urls: "stun:stun.l.google.com:19302" }] });
    pc.onicecandidate = e => {
      if (!e.candidate) return;
      const c = { sdpMid: e.candidate.sdpMid || "", sdpMLineIndex: e.candidate.sdpMLineIndex ?? 0, sdp: e.candidate.candidate };
      if (C.id) api(`/api/calls/${C.id}/ice`, "POST", { candidate: c }).catch(() => {}); else C.localIce.push(c);
    };
    pc.ontrack = e => { audio.srcObject = e.streams[0]; audio.play && audio.play().catch(() => {}); };
    pc.onconnectionstatechange = () => { if (pc.connectionState === "connected") setStatus("active"); else if (pc.connectionState === "failed") { api(`/api/calls/${C.id}/end`, "POST").catch(() => {}); finish("The call lost its connection"); } };
    return pc;
  }
  async function mic() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error("Calls need a secure page and a microphone.");
    try { return await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }); }
    catch (e) { throw new Error("Allow the microphone to make or take calls."); }
  }
  const flushIce = () => { C.localIce.splice(0).forEach(c => api(`/api/calls/${C.id}/ice`, "POST", { candidate: c }).catch(() => {})); };
  async function drainRemote() { C.remoteSet = true; for (const c of C.pendingIce.splice(0)) { try { await C.pc.addIceCandidate(c); } catch (e) { /* stale */ } } }

  async function start(m, urgent = false) {
    if (!m) return;
    if (C.status) { toast("You are already on a call."); return; }
    C.peer = m; C.incoming = false; setStatus("calling");
    try {
      C.stream = await mic(); C.pc = newPC(); C.stream.getTracks().forEach(t => C.pc.addTrack(t, C.stream));
      const offer = await C.pc.createOffer(); await C.pc.setLocalDescription(offer);
      const r = await api("/api/calls", "POST", { to: m.id, offer: offer.sdp, urgent });
      C.id = r.id; flushIce();
      C.timeout = setTimeout(() => { if (C.status === "calling") { api(`/api/calls/${C.id}/end`, "POST").catch(() => {}); finish(`${m.name} did not answer`); } }, 48000);
    } catch (e) {
      cleanup();
      if (e.status === 409 && e.data && e.data.quiet) { if (confirm(e.message + "\n\nRing anyway?")) return start(m, true); return; }
      toast(e.message || "Could not start the call", 4500);
    }
  }
  async function incoming(p) {
    try { if ((await api("/api/calls/" + p.id)).status !== "ringing") return; } catch (e) { return; }
    if (C.status) { api(`/api/calls/${p.id}/decline`, "POST").catch(() => {}); return; }
    C.id = p.id; C.peer = { id: p.from, name: p.name, emoji: p.emoji }; C.incoming = true; C.offer = p.offer;
    setStatus("ringing"); ringStart(); notify(`${p.name} is calling`, "Open Homebase to answer");
    C.timeout = setTimeout(() => { if (C.status === "ringing") finish("Missed call from " + p.name); }, 46000);
  }
  async function accept() {
    if (C.status !== "ringing") return; ringStop(); clearTimeout(C.timeout); setStatus("connecting");
    try {
      C.stream = await mic(); C.pc = newPC(); C.stream.getTracks().forEach(t => C.pc.addTrack(t, C.stream));
      await C.pc.setRemoteDescription({ type: "offer", sdp: C.offer }); await drainRemote();
      const ans = await C.pc.createAnswer(); await C.pc.setLocalDescription(ans);
      await api(`/api/calls/${C.id}/answer`, "POST", { answer: ans.sdp }); flushIce();
    } catch (e) { api(`/api/calls/${C.id}/decline`, "POST").catch(() => {}); finish(e.message || "Could not answer"); }
  }
  function decline() { if (C.id) api(`/api/calls/${C.id}/decline`, "POST").catch(() => {}); finish(); }
  function hangup() { if (C.id) api(`/api/calls/${C.id}/end`, "POST").catch(() => {}); finish(); }

  async function onEvent(type, p) {
    if (type === "call") return incoming(p);
    if (p.id !== C.id) return;
    if (type === "ice") {
      const c = p.candidate || {}; if (!c.sdp) return;
      const ice = { candidate: c.sdp, sdpMid: c.sdpMid || null, sdpMLineIndex: c.sdpMLineIndex ?? 0 };
      if (C.pc && C.remoteSet) { try { await C.pc.addIceCandidate(ice); } catch (e) { /* stale */ } } else C.pendingIce.push(ice);
    } else if (type === "call_update") {
      if (p.status === "active" && p.answer && !C.incoming && C.pc && !C.remoteSet) { setStatus("connecting"); try { await C.pc.setRemoteDescription({ type: "answer", sdp: p.answer }); await drainRemote(); } catch (e) { finish("Could not connect the call"); } }
      else if (p.status === "declined") finish(`${(C.peer || {}).name || "They"} is busy right now`);
      else if (p.status === "missed") finish(C.incoming ? "Missed call" : `${(C.peer || {}).name || "They"} did not answer`);
      else if (p.status === "ended") finish("Call ended");
    }
  }
  return { start, onEvent };
})();
