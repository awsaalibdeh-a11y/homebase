"use strict";
/* Calls on the laptop: WebRTC audio and video, using the same signalling as the Android app so a laptop and a phone can ring each other.
   Every call carries a video channel that starts empty; turning the camera on just fills it, and a tiny side channel says "camera on/off". */
window.Calls = (() => {
  const el = document.getElementById("call"), audio = document.getElementById("remote");
  const C = { pc: null, id: 0, peer: null, incoming: false, status: "", stream: null, remoteSet: false, pendingIce: [], localIce: [], offer: "", timeout: 0, t0: 0, tick: 0, muted: false, vtx: null, dc: null, cam: null, camOn: false, remoteCam: false, remoteVideo: null, wantVideo: false };
  let ac = null, ringIv = 0;

  function beep() {
    try {
      ac = ac || new (window.AudioContext || window.webkitAudioContext)();
      [440, 480].forEach(f => { const o = ac.createOscillator(), g = ac.createGain(); o.frequency.value = f; g.gain.value = .07; o.connect(g); g.connect(ac.destination); o.start(); o.stop(ac.currentTime + .8); });
    } catch (e) { /* no audio */ }
  }
  const ringStart = () => { beep(); ringIv = setInterval(beep, 2600); };
  const ringStop = () => { clearInterval(ringIv); ringIv = 0; };

  const ICON = {
    call: '<svg viewBox="0 0 24 24"><path d="M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z"/></svg>',
    end: '<svg viewBox="0 0 24 24"><path d="M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .4-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71-.29L.29 13.08a.97.97 0 0 1-.29-.7c0-.28.11-.53.29-.71C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67c.18.18.29.43.29.71 0 .28-.11.53-.29.71l-2.48 2.48c-.18.18-.43.29-.71.29-.27 0-.52-.11-.7-.28a11.3 11.3 0 0 0-2.67-1.85.99.99 0 0 1-.56-.9v-3.1A15.3 15.3 0 0 0 12 9z"/></svg>',
    mic: '<svg viewBox="0 0 24 24"><path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11z"/></svg>',
    cam: '<svg viewBox="0 0 24 24"><path d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z"/></svg>',
    micoff: '<svg viewBox="0 0 24 24"><path d="M19 11h-1.7c0 .74-.16 1.43-.43 2.05l1.23 1.23A6.9 6.9 0 0 0 19 11zM15 11.16V5a3 3 0 0 0-5.94-.6L15 10.34v.82zM4.27 3 3 4.27l6 6V11a3 3 0 0 0 4.2 2.75l1.67 1.67A5 5 0 0 1 7 11H5a7 7 0 0 0 6 6.92V21h2v-3.08c.9-.13 1.74-.45 2.49-.9L19.73 21 21 19.73 4.27 3z"/></svg>',
  };
  const mmss = t => String(Math.floor(t / 60)).padStart(2, "0") + ":" + String(t % 60).padStart(2, "0");
  function render() {
    if (!C.status) { el.classList.remove("on"); el.innerHTML = ""; return; }
    const p = C.peer || {}, ringing = C.status === "ringing" || C.status === "calling" || C.status === "connecting", live = C.status === "active";
    const label = { calling: "Calling…", ringing: "Incoming Mellow call", connecting: "Connecting…", active: mmss(Math.floor((Date.now() - (C.t0 || Date.now())) / 1000)) }[C.status] || "";
    const act = (id, cls, icon, text, aria) => `<div class="act"><button class="cb ${cls}" id="${id}" aria-label="${aria || text}">${ICON[icon]}</button><span>${text}</span></div>`;
    const video = C.remoteCam && C.remoteVideo;
    el.innerHTML = `${video ? '<video id="rv" class="rvid" autoplay playsinline></video>' : ""}${C.camOn ? '<video id="lv" class="lvid" autoplay playsinline muted></video>' : ""}<div class="callwrap ${video ? "onvideo" : ""}" role="dialog" aria-label="Mellow call">
      <div class="who">${C.incoming ? "Mellow call" : "Calling from Mellow"}</div>
      <div class="avawrap ${ringing ? "ring" : ""} ${live ? "live" : ""}"><i class="rg"></i><i class="rg b"></i><i class="rg live"></i><div class="av">${esc(p.emoji || "🙂")}</div></div>
      <h2>${esc(p.name || "")}</h2><div class="stt ${live ? "live" : ""}" id="cst">${label}</div>
      ${live ? '<div class="bars" aria-hidden="true"><i></i><i></i><i></i><i></i></div>' : ""}
      <div class="acts">${C.status === "ringing" ? act("cNo", "end big", "end", "Decline") + act("cAns", "accept big", "call", "Answer")
        : act("cMute", C.muted ? "on" : "", C.muted ? "micoff" : "mic", C.muted ? "Muted" : "Mute", "Mute") + act("cEnd", "end big", "end", live ? "End" : "Cancel", "Hang up") + act("cCam", C.camOn ? "on" : "", "cam", C.camOn ? "Camera on" : "Camera", "Camera")}</div></div>`;
    el.classList.add("on");
    const b = id => el.querySelector(id);
    if (b("#cAns")) { b("#cAns").onclick = accept; b("#cAns").focus(); } if (b("#cNo")) b("#cNo").onclick = decline; if (b("#cEnd")) b("#cEnd").onclick = hangup;
    if (b("#cCam")) b("#cCam").onclick = () => camera(!C.camOn);
    const rv = b("#rv"); if (rv && C.remoteVideo) { rv.srcObject = C.remoteVideo; rv.play && rv.play().catch(() => {}); }
    const lv = b("#lv"); if (lv && C.cam) { lv.srcObject = C.cam; lv.play && lv.play().catch(() => {}); }
    if (b("#cMute")) b("#cMute").onclick = () => { C.muted = !C.muted; C.stream && C.stream.getAudioTracks().forEach(t => t.enabled = !C.muted); render(); };
  }
  function setStatus(s) {
    if (C.status === s) return; C.status = s;
    clearInterval(C.tick);
    if (s === "active") { C.t0 = Date.now(); C.tick = setInterval(() => { const e = document.getElementById("cst"); if (e) e.textContent = mmss(Math.floor((Date.now() - C.t0) / 1000)); }, 1000); ringStop(); clearTimeout(C.timeout); }
    render();
  }
  function cleanup() {
    clearTimeout(C.timeout); clearInterval(C.tick); ringStop();
    if (C.stream) C.stream.getTracks().forEach(t => t.stop());
    if (C.cam) C.cam.getTracks().forEach(t => t.stop());
    if (C.pc) { try { C.pc.close(); } catch (e) { /* closed */ } }
    audio.srcObject = null;
    Object.assign(C, { pc: null, id: 0, peer: null, incoming: false, status: "", stream: null, remoteSet: false, pendingIce: [], localIce: [], offer: "", timeout: 0, muted: false, vtx: null, dc: null, cam: null, camOn: false, remoteCam: false, remoteVideo: null, wantVideo: false });
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
    pc.ontrack = e => {
      if (e.track.kind === "video") { C.remoteVideo = new MediaStream([e.track]); render(); return; }
      audio.srcObject = new MediaStream([e.track]); audio.play && audio.play().catch(() => {});
    };
    // the side channel both apps open with the same number, so no extra handshake is needed: "cam:1" / "cam:0"
    try {
      C.dc = pc.createDataChannel("mellow", { negotiated: true, id: 7 });
      C.dc.onopen = () => sayCam();
      C.dc.onmessage = e => { if (String(e.data).startsWith("cam:")) { C.remoteCam = e.data === "cam:1"; render(); } };
    } catch (e) { C.dc = null; }
    pc.onconnectionstatechange = () => { if (pc.connectionState === "connected") setStatus("active"); else if (pc.connectionState === "failed") { api(`/api/calls/${C.id}/end`, "POST").catch(() => {}); finish("The call lost its connection"); } };
    return pc;
  }
  async function mic() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error("Calls need a secure page and a microphone.");
    try { return await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }); }
    catch (e) { throw new Error("Allow the microphone to make or take calls."); }
  }
  const sayCam = () => { try { if (C.dc && C.dc.readyState === "open") C.dc.send(C.camOn ? "cam:1" : "cam:0"); } catch (e) { /* closed */ } };
  function videoSender() {
    if (C.vtx) return C.vtx;
    const t = C.pc && C.pc.getTransceivers().find(x => x.receiver && x.receiver.track && x.receiver.track.kind === "video");
    if (t) { try { t.direction = "sendrecv"; } catch (e) { /* fixed */ } C.vtx = t; }
    return C.vtx;
  }
  /** Turns the camera on or off. The video channel is already there, so the picture just starts or stops. */
  async function camera(on) {
    if (!C.pc) return;
    const tx = videoSender();
    if (!tx) { toast("This call cannot carry video (the other app is too old)."); return; }
    if (on) {
      try { C.cam = await navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 480 } }); }
      catch (e) { toast("Allow the camera to turn video on."); return; }
      await tx.sender.replaceTrack(C.cam.getVideoTracks()[0]);
    } else {
      await tx.sender.replaceTrack(null);
      if (C.cam) C.cam.getTracks().forEach(t => t.stop());
      C.cam = null;
    }
    C.camOn = on; sayCam(); render();
  }
  const flushIce = () => { C.localIce.splice(0).forEach(c => api(`/api/calls/${C.id}/ice`, "POST", { candidate: c }).catch(() => {})); };
  async function drainRemote() { C.remoteSet = true; for (const c of C.pendingIce.splice(0)) { try { await C.pc.addIceCandidate(c); } catch (e) { /* stale */ } } }

  async function start(m, urgent = false) {
    if (!m) return;
    const wantVideo = C.wantVideo;
    if (C.status) { toast("You are already on a call."); return; }
    C.peer = m; C.incoming = false; C.wantVideo = wantVideo; setStatus("calling");
    try {
      C.stream = await mic(); C.pc = newPC(); C.stream.getTracks().forEach(t => C.pc.addTrack(t, C.stream));
      C.vtx = C.pc.addTransceiver("video", { direction: "sendrecv" });
      const offer = await C.pc.createOffer(); await C.pc.setLocalDescription(offer);
      const r = await api("/api/calls", "POST", { to: m.id, offer: offer.sdp, urgent });
      C.id = r.id; flushIce();
      if (C.wantVideo) camera(true);
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
    setStatus("ringing"); ringStart(); api(`/api/calls/${p.id}/seen`, "POST").catch(() => {}); notify(`${p.name} is calling`, "Open Mellow to answer");
    C.timeout = setTimeout(() => { if (C.status === "ringing") finish("Missed call from " + p.name); }, 46000);
  }
  async function accept() {
    if (C.status !== "ringing") return; ringStop(); clearTimeout(C.timeout); setStatus("connecting");
    try {
      C.stream = await mic(); C.pc = newPC(); C.stream.getTracks().forEach(t => C.pc.addTrack(t, C.stream));
      await C.pc.setRemoteDescription({ type: "offer", sdp: C.offer }); await drainRemote();
      videoSender();
      const ans = await C.pc.createAnswer(); await C.pc.setLocalDescription(ans);
      await api(`/api/calls/${C.id}/answer`, "POST", { answer: ans.sdp }); flushIce();
    } catch (e) { api(`/api/calls/${C.id}/decline`, "POST").catch(() => {}); finish(e.message || "Could not answer"); }
  }
  function decline() { if (C.id) api(`/api/calls/${C.id}/decline`, "POST").catch(() => {}); finish(); }
  function hangup() { if (C.id) api(`/api/calls/${C.id}/end`, "POST").catch(() => {}); finish(); }

  // Pick up a call that is already ringing (the Windows ringing window opens the app at #answer=ID): fetch it, then answer straight away
  async function answerNow(id) {
    try {
      const d = await api("/api/calls/" + id);
      if (d.status !== "ringing" || !d.offer) return toast("That call is over.");
      if (C.status) return;
      C.id = id; C.peer = { id: d.from, name: d.name, emoji: d.emoji }; C.incoming = true; C.offer = d.offer;
      setStatus("ringing"); await accept();
    } catch (e) { toast("Could not pick up the call."); }
  }

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
  function startVideo(m) { C.wantVideo = true; return start(m, false); }
  return { start, startVideo, onEvent, answerNow };
})();
