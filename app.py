"""Homebase server: the small, honest backend for the family app.

Principles baked in: everything shared is visible to the person sharing it, they can pause it any time and the family sees "paused",
and there is no hidden mode. Parents never get silent access to a child's phone: location is shared by the app, check-ins, requests
("where are you?") can be declined, and quiet hours are the child's own setting."""

import hmac
import json
import math
import os
import re
import secrets
import time
from collections import defaultdict

from flask import Flask, g, jsonify, render_template, request, send_from_directory

import db
from db import delete, insert, select, update

BASE = os.path.dirname(os.path.abspath(__file__))
app = Flask(__name__)
import hubguard  # noqa: E402  (the owner's universal switches, kept in Cramly)
hubguard.install(app, "homebase")
app.config["MAX_CONTENT_LENGTH"] = 256 * 1024
VERSION_CODE = int(os.environ.get("APP_VERSION_CODE", "2"))
KEEP_LOCATIONS_DAYS = 7
CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
_hits = defaultdict(list)


def limited(bucket, limit, window=3600):
    now = time.time()
    hits = [t for t in _hits[bucket] if now - t < window] + [now]
    _hits[bucket] = hits
    return len(hits) > limit


def clean(text, n):
    return re.sub(r"\s+", " ", str(text or "")).strip()[:n]


def qint(name, default):
    """An optional whole-number query parameter."""
    v = str(request.args.get(name, ""))
    return int(v) if v.lstrip("-").isdigit() else default


def err(msg, code=400, **extra):
    return jsonify(error=msg, **extra), code


# ---------------------------------------------------------------- who is asking
def current():
    token = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    if not token:
        return None
    h = db.hash_token(token)
    m = db.one(select(db.members).where(db.members.c.token_hash == h))
    if m:
        return m
    d = db.one(select(db.devices).where(db.devices.c.token_hash == h))
    if d:
        db.run(update(db.devices).where(db.devices.c.id == d["id"]).values(last_seen=time.time()))
        return db.one(select(db.members).where(db.members.c.id == d["member_id"]))
    return None


def need(fn):
    def wrapped(*a, **k):
        m = current()
        if not m:
            return err("Sign in again.", 401)
        db.run(update(db.members).where(db.members.c.id == m["id"]).values(last_seen=time.time()))
        g.m = m
        return fn(*a, **k)
    wrapped.__name__ = fn.__name__
    return wrapped


def parent_only(fn):
    def wrapped(*a, **k):
        if g.m["role"] != "parent":
            return err("Only a parent can do that.", 403)
        return fn(*a, **k)
    wrapped.__name__ = fn.__name__
    return need(wrapped)


def family_members(fid):
    return db.many(select(db.members).where(db.members.c.family_id == fid).order_by(db.members.c.id))


def same_family(member_id):
    m = db.one(select(db.members).where(db.members.c.id == member_id)) if str(member_id).isdigit() else None
    return m if m and m["family_id"] == g.m["family_id"] else None


def push(member_id, kind, payload):
    db.run(insert(db.events).values(member_id=member_id, type=kind, payload=json.dumps(payload, ensure_ascii=False), created=time.time()))


def push_family(fid, kind, payload, skip=0):
    for m in family_members(fid):
        if m["id"] != skip:
            push(m["id"], kind, payload)


def sharing_state(m):
    """on / paused / off, with a pause that ends by itself."""
    if m["share_state"] == "paused" and m["paused_until"] and m["paused_until"] <= time.time():
        db.run(update(db.members).where(db.members.c.id == m["id"]).values(share_state="on", paused_until=0))
        m["share_state"], m["paused_until"] = "on", 0
    return m["share_state"]


def in_quiet(m, now=None):
    if m["quiet_from"] < 0 or m["quiet_to"] < 0:
        return False
    local = ((now or time.time()) / 60 - (m["tz_offset"] or 0)) % 1440
    a, b = m["quiet_from"], m["quiet_to"]
    return (a <= local < b) if a < b else (local >= a or local < b)


PERMS = {"location": "see where they are", "trail": "see their location trail", "battery": "see their battery level",
         "messages": "send them messages", "calls": "call them", "ping": 'ask "Where are you?"',
         "screentime": "see their screen time"}


def perms_of(m):
    """What parents may do with this member. Everything is allowed until the member turns it off."""
    try:
        off = json.loads(m["perms"] or "{}")
    except ValueError:
        off = {}
    return {k: off.get(k) is not False for k in PERMS}


def parent_may(sender, target, key):
    """Only a parent acting on a child is limited; the child's own choices are the limit."""
    return not (sender["role"] == "parent" and target["role"] == "child" and not perms_of(target)[key])


def denied(target, key):
    return err(f"{target['name']} has turned off: {PERMS[key]}.", 403, perm=key)


def status_view(m):
    """What someone says they are up to ("Studying", "Gaming"...). They set it themselves, the whole family sees it, and it ends by itself."""
    if (m["status_until"] or 0) > time.time() and (m["status_emoji"] or m["status_text"]):
        return {"emoji": m["status_emoji"] or "", "text": m["status_text"] or "", "until": m["status_until"]}
    return None


def member_view(m, viewer=None):
    state = sharing_state(m)
    perms = perms_of(m)
    gated = bool(viewer) and viewer["role"] == "parent" and m["role"] == "child" and viewer["id"] != m["id"]
    out = {"id": m["id"], "name": m["name"], "role": m["role"], "emoji": m["emoji"], "battery": m["battery"], "share": state,
           "paused_until": m["paused_until"] if state == "paused" else 0, "last_seen": m["last_seen"], "quiet": m["quiet_from"] >= 0,
           "location": None, "perms": perms, "lock": lock_view(m), "home": at_home(m), "blocks": block_views(m),
           "avatar": (f"/api/avatar/{m['id']}?v={int(m['avatar_at'] or 0)}" if m["avatar"] else None), "status": status_view(m)}
    if state == "on" and m["last_lat"] is not None and not (gated and not perms["location"]):
        out["location"] = {"lat": m["last_lat"], "lng": m["last_lng"], "acc": m["last_acc"], "at": m["last_at"]}
    if gated and not perms["location"]:
        out["share"] = "off"
    if gated and not perms["battery"]:
        out["battery"] = -1
    if m["role"] == "child" and viewer and (viewer["id"] == m["id"] or viewer["role"] == "parent"):
        # today's screen time, right on the card, so a parent does not have to open anything. If the child switched it off, say so plainly.
        if gated and not perms["screentime"]:
            out["screen"] = {"hidden": True, "today": 0, "top": ""}
        else:
            day = time.strftime("%Y-%m-%d", time.gmtime(time.time() - (m["tz_offset"] or 0) * 60))
            rows = db.many(select(db.screentime).where(db.screentime.c.member_id == m["id"], db.screentime.c.day == day).order_by(db.screentime.c.sec.desc()))
            out["screen"] = {"hidden": False, "today": sum(r["sec"] for r in rows), "top": (rows[0]["label"] or rows[0]["pkg"]) if rows else ""}
    if m["role"] == "child" and viewer and (viewer["id"] == m["id"] or viewer["role"] == "parent"):          # a linked computer that goes quiet is visible to the family
        out["devices"] = [{"id": d["id"], "name": d["name"], "kind": d["kind"], "last_seen": d["last_seen"], "status": d["status"] or "", "version": d["version"] or ""}
                          for d in db.many(select(db.devices).where(db.devices.c.member_id == m["id"]).order_by(db.devices.c.id))]
    return out


LOCK_MAX_DAY = 4 * 3600                                       # a child can be locked for at most four hours in a day


def _lock_end(r):
    return min(r["until"], r["ended"]) if r["ended"] else r["until"]


def day_window(m, ts):
    """The child's own calendar day around ts, from the timezone their device reports."""
    off = (m["tz_offset"] or 0) * 60
    start = (ts - off) - (ts - off) % 86400 + off
    return start, start + 86400


def locked_between(m, a, b, rows=None):
    rows = rows if rows is not None else db.many(select(db.locks).where(db.locks.c.member_id == m["id"], db.locks.c.until > a, db.locks.c.start < b))
    return sum(max(0, min(_lock_end(r), b) - max(r["start"], a)) for r in rows)


def active_lock(m, now=None):
    now = now or time.time()
    rows = db.many(select(db.locks).where(db.locks.c.member_id == m["id"], db.locks.c.start <= now, db.locks.c.until > now).order_by(db.locks.c.id.desc()))
    return next((r for r in rows if _lock_end(r) > now), None)


def home_place(fid):
    return db.one(select(db.places).where(db.places.c.family_id == fid, db.places.c.name == "Home"))


def at_home(m):
    """Is this person inside the family's Home area right now? (From their last arrival or departure, so it only shows while they share.)"""
    h = home_place(m["family_id"])
    if not h or m["share_state"] != "on":
        return False
    st = db.one(select(db.place_state).where(db.place_state.c.member_id == m["id"], db.place_state.c.place_id == h["id"]))
    return bool(st and st["inside"])


def lock_view(m):
    if m["role"] != "child":
        return None
    r = active_lock(m)
    if not r:
        return None
    by = db.one(select(db.members).where(db.members.c.id == r["by_id"]))
    return {"id": r["id"], "until": _lock_end(r), "by": r["by_id"], "by_name": by["name"] if by else "a parent"}


def lock_budget(m, now=None):
    now = now or time.time()
    a, b = day_window(m, now)
    used = locked_between(m, a, b)
    return {"used": int(used), "left": int(max(0, LOCK_MAX_DAY - used)), "max": LOCK_MAX_DAY}


def family_view(m):
    fam = db.one(select(db.families).where(db.families.c.id == m["family_id"]))
    return {"id": fam["id"], "name": fam["name"]}


def ice_servers():
    servers = [{"urls": ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"]}]
    urls = [u.strip() for u in os.environ.get("TURN_URLS", "").split(",") if u.strip()]
    if urls:
        servers.append({"urls": urls, "username": os.environ.get("TURN_USER", ""), "credential": os.environ.get("TURN_PASS", "")})
    return servers


def new_member(fid, name, role, emoji):
    token = secrets.token_urlsafe(32)
    mid = db.run(insert(db.members).values(family_id=fid, name=clean(name, 40) or "Me", role=role, emoji=clean(emoji, 8) or ("🧒" if role == "child" else "🧑"),
                                           token_hash=db.hash_token(token), created=time.time(), share_state="on"))                  # everyone starts on the map; each person still chooses what their device sends
    return token, db.one(select(db.members).where(db.members.c.id == mid))


# ---------------------------------------------------------------- families, invites, joining
@app.post("/api/family")
def create_family():
    if limited(f"family:{request.remote_addr}", 10):
        return err("Too many tries. Wait a bit.", 429)
    b = request.get_json(silent=True) or {}
    fid = db.run(insert(db.families).values(name=clean(b.get("family"), 60) or "Our family", created=time.time()))
    token, m = new_member(fid, b.get("name"), "parent", b.get("emoji"))
    return jsonify(token=token, me=member_view(m), family=family_view(m))


@app.post("/api/invite")
@parent_only
def make_invite():
    role = "parent" if (request.get_json(silent=True) or {}).get("role") == "parent" else "child"
    code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
    db.run(insert(db.invites).values(code=code, family_id=g.m["family_id"], role=role, created_by=g.m["id"], expires=time.time() + 86400, used=False))
    return jsonify(code=code, role=role, expires=time.time() + 86400)


@app.get("/api/invite/<code>")
def look_at_invite(code):
    """What a code is for, so the app can explain it (and ask for consent) before anyone joins."""
    if limited(f"look:{request.remote_addr}", 60):
        return err("Too many tries. Wait a bit.", 429)
    inv = db.one(select(db.invites).where(db.invites.c.code == re.sub(r"[^A-Za-z0-9]", "", code).upper()))
    if not inv or inv["used"] or inv["expires"] < time.time():
        return err("That code is not valid any more. Ask for a new one.", 404)
    fam = db.one(select(db.families).where(db.families.c.id == inv["family_id"]))
    inviter = db.one(select(db.members).where(db.members.c.id == inv["created_by"]))
    return jsonify(role=inv["role"], family=fam["name"], invited_by=inviter["name"] if inviter else "")


@app.post("/api/join")
def join():
    if limited(f"join:{request.remote_addr}", 20):
        return err("Too many tries. Wait a bit.", 429)
    b = request.get_json(silent=True) or {}
    code = re.sub(r"[^A-Za-z0-9]", "", str(b.get("code") or "")).upper()
    inv = db.one(select(db.invites).where(db.invites.c.code == code)) if code else None
    if not inv or inv["used"] or inv["expires"] < time.time():
        return err("That code is not valid any more. Ask for a new one.", 400)
    token, m = new_member(inv["family_id"], b.get("name"), inv["role"], b.get("emoji"))
    db.run(update(db.invites).where(db.invites.c.code == code).values(used=True))
    db.run(insert(db.messages).values(family_id=m["family_id"], from_id=m["id"], to_id=0, kind="system", body=f"{m['name']} joined the family", created=time.time()))
    push_family(m["family_id"], "member", {"id": m["id"], "joined": True}, skip=m["id"])
    return jsonify(token=token, me=member_view(m), family=family_view(m))


@app.get("/api/me")
@need
def get_me():
    m = g.m
    return jsonify(me=member_view(m), family=family_view(m), quiet={"from": m["quiet_from"], "to": m["quiet_to"]}, tz=m["tz_offset"])


@app.patch("/api/me")
@need
def patch_me():
    b, vals = request.get_json(silent=True) or {}, {}
    if clean(b.get("name"), 40):
        vals["name"] = clean(b["name"], 40)
    if "emoji" in b:
        vals["emoji"] = clean(b["emoji"], 8) or g.m["emoji"]
    for k in ("quiet_from", "quiet_to"):
        if k in b:
            vals[k] = max(-1, min(1439, int(b[k]))) if str(b[k]).lstrip("-").isdigit() else -1
    if "tz_offset" in b and str(b["tz_offset"]).lstrip("-").isdigit():
        vals["tz_offset"] = max(-840, min(840, int(b["tz_offset"])))
    if isinstance(b.get("perms"), dict):
        off = {k: False for k, v in {**perms_of(g.m), **{k: bool(v) for k, v in b["perms"].items() if k in PERMS}}.items() if not v}
        vals["perms"] = json.dumps(off)
    if vals:
        db.run(update(db.members).where(db.members.c.id == g.m["id"]).values(**vals))
        push_family(g.m["family_id"], "member", {"id": g.m["id"]}, skip=g.m["id"])
    return jsonify(ok=True)


@app.delete("/api/me")
@need
def leave():
    """Leaving removes you and everything you shared. The last parent leaving removes the whole family."""
    m = g.m
    for t in (db.locations,):
        db.run(delete(t).where(t.c.member_id == m["id"]))
    db.run(delete(db.events).where(db.events.c.member_id == m["id"]))
    db.run(delete(db.members).where(db.members.c.id == m["id"]))
    left = family_members(m["family_id"])
    if not any(x["role"] == "parent" for x in left):
        for x in left:
            db.run(delete(db.locations).where(db.locations.c.member_id == x["id"]))
            db.run(delete(db.events).where(db.events.c.member_id == x["id"]))
            db.run(delete(db.members).where(db.members.c.id == x["id"]))
        for t in (db.messages, db.places, db.lists, db.calls, db.pings):
            db.run(delete(t).where(t.c.family_id == m["family_id"]))
        db.run(delete(db.invites).where(db.invites.c.family_id == m["family_id"]))
        db.run(delete(db.families).where(db.families.c.id == m["family_id"]))
    else:
        push_family(m["family_id"], "member", {"id": m["id"], "left": True})
    return jsonify(ok=True)


@app.get("/api/family")
@need
def get_family():
    m = g.m
    places = db.many(select(db.places).where(db.places.c.family_id == m["family_id"]).order_by(db.places.c.id))
    return jsonify(family=family_view(m), me=m["id"], members=[member_view(x, m) for x in family_members(m["family_id"])],
                   places=[{k: p[k] for k in ("id", "name", "emoji", "lat", "lng", "radius")} for p in places], ice_servers=ice_servers(), version_code=VERSION_CODE)


@app.get("/api/config")
def config():
    return jsonify(ice_servers=ice_servers(), version_code=VERSION_CODE, apk_url=f"{request.host_url.rstrip('/')}/update")


# ---------------------------------------------------------------- sharing a location (always the owner's choice)
def metres(lat1, lng1, lat2, lng2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lng2 - lng1) / 2) ** 2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(a)))


def say(fid, from_id, body, kind="system", lat=None, lng=None, to_id=0):
    return db.run(insert(db.messages).values(family_id=fid, from_id=from_id, to_id=to_id, kind=kind, body=body, lat=lat, lng=lng, created=time.time()))


def check_places(m, lat, lng, acc):
    for p in db.many(select(db.places).where(db.places.c.family_id == m["family_id"])):
        st = db.one(select(db.place_state).where(db.place_state.c.member_id == m["id"], db.place_state.c.place_id == p["id"]))
        inside, d = bool(st and st["inside"]), metres(lat, lng, p["lat"], p["lng"])
        arrived, left = (not inside and d <= p["radius"]), (inside and d > p["radius"] + max(acc, 30))
        if not (arrived or left):
            continue
        if st:
            db.run(update(db.place_state).where(db.place_state.c.member_id == m["id"], db.place_state.c.place_id == p["id"]).values(inside=arrived))
        else:
            db.run(insert(db.place_state).values(member_id=m["id"], place_id=p["id"], inside=arrived))
        verb = "arrived at" if arrived else "left"
        say(m["family_id"], m["id"], f"{m['name']} {verb} {p['name']}")
        push_family(m["family_id"], "place", {"member": m["id"], "name": m["name"], "place": p["name"], "emoji": p["emoji"], "kind": "arrive" if arrived else "leave"})


@app.post("/api/location")
@need
def post_location():
    m, b = g.m, request.get_json(silent=True) or {}
    try:
        lat, lng, acc = float(b["lat"]), float(b["lng"]), float(b.get("acc") or 0)
    except (KeyError, TypeError, ValueError):
        return err("Send lat and lng.")
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        return err("That is not a real place.")
    state = sharing_state(m)
    if state != "on":
        return jsonify(ok=True, shared=False, share=state)
    now = time.time()
    battery = int(b["battery"]) if str(b.get("battery", "")).lstrip("-").isdigit() else -1
    moved = metres(m["last_lat"], m["last_lng"], lat, lng) if m["last_lat"] is not None else 1e9
    db.run(insert(db.locations).values(member_id=m["id"], lat=lat, lng=lng, acc=acc, speed=float(b.get("speed") or 0), at=now))
    vals = dict(last_lat=lat, last_lng=lng, last_acc=acc, last_at=now, battery=battery)
    if battery >= 30 and m["low_sent"]:
        vals["low_sent"] = False
    if 0 <= battery <= 15 and not m["low_sent"]:
        vals["low_sent"] = True
        say(m["family_id"], m["id"], f"{m['name']}'s battery is low ({battery}%)")
        push_family(m["family_id"], "battery", {"member": m["id"], "name": m["name"], "battery": battery}, skip=m["id"])
    db.run(update(db.members).where(db.members.c.id == m["id"]).values(**vals))
    if moved > 25 or now - (m["last_at"] or 0) > 60:
        push_family(m["family_id"], "location", {"member": m["id"], "lat": lat, "lng": lng, "acc": acc, "at": now, "battery": battery}, skip=m["id"])
    check_places(m, lat, lng, acc)
    if int(now) % 50 == 0:
        db.run(delete(db.locations).where(db.locations.c.at < now - KEEP_LOCATIONS_DAYS * 86400))
    return jsonify(ok=True, shared=True)


@app.post("/api/sharing")
@need
def set_sharing():
    m, b = g.m, request.get_json(silent=True) or {}
    state = b.get("state")
    if state not in ("on", "off", "paused"):
        return err("Choose on, off or paused.")
    minutes = max(1, min(1440, int(b["minutes"]))) if str(b.get("minutes", "")).isdigit() else 60
    until = time.time() + minutes * 60 if state == "paused" else 0
    db.run(update(db.members).where(db.members.c.id == m["id"]).values(share_state=state, paused_until=until))
    text = {"on": f"{m['name']} is sharing location", "off": f"{m['name']} turned location sharing off",
            "paused": f"{m['name']} paused location sharing for {minutes} min"}[state]
    say(m["family_id"], m["id"], text)                      # the family always sees it: nothing is silent
    push_family(m["family_id"], "member", {"id": m["id"], "share": state, "until": until}, skip=m["id"])
    return jsonify(ok=True, share=state, paused_until=until)


@app.get("/api/history")
@need
def history():
    target = same_family(qint("member", g.m["id"]))
    if not target:
        return err("Not found.", 404)
    if target["id"] != g.m["id"] and not (g.m["role"] == "parent" and target["role"] == "child"):
        return err("You can only see your own trail, or a child's if you are a parent.", 403)
    if not parent_may(g.m, target, "trail"):
        return denied(target, "trail")
    hours = max(1, min(24, qint("hours", 12)))
    rows = db.many(select(db.locations).where(db.locations.c.member_id == target["id"], db.locations.c.at > time.time() - hours * 3600).order_by(db.locations.c.at))
    if len(rows) > 300:
        step = len(rows) / 300
        rows = [rows[int(i * step)] for i in range(300)]
    return jsonify(member=target["id"], points=[{"lat": r["lat"], "lng": r["lng"], "at": r["at"]} for r in rows])


@app.delete("/api/history")
@need
def delete_history():
    """Your trail is yours: delete it any time."""
    db.run(delete(db.locations).where(db.locations.c.member_id == g.m["id"]))
    db.run(update(db.members).where(db.members.c.id == g.m["id"]).values(last_lat=None, last_lng=None, last_acc=None, last_at=0))
    push_family(g.m["family_id"], "member", {"id": g.m["id"]}, skip=g.m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- profile pictures (a small photo, stored as the member chooses)
import base64 as _b64

AVATAR_MAGIC = {b"\xff\xd8\xff": "image/jpeg", b"\x89PNG": "image/png"}


@app.post("/api/avatar")
@need
def set_avatar():
    """Set your own photo. The browser shrinks it to a small square first; we keep it small."""
    b = request.get_json(silent=True) or {}
    raw = str(b.get("image") or "")
    if not raw:                                                            # empty = remove it
        db.run(update(db.members).where(db.members.c.id == g.m["id"]).values(avatar="", avatar_at=time.time()))
        push_family(g.m["family_id"], "member", {"id": g.m["id"], "avatar": None})
        return jsonify(ok=True, avatar=None)
    data = raw.split(",", 1)[-1]                                           # drop a data: prefix if present
    try:
        blob = _b64.b64decode(data, validate=True)
    except (ValueError, Exception):
        return err("That image did not come through. Try another photo.")
    if len(blob) > 200 * 1024:
        return err("That photo is too big. Pick a smaller one.")
    if not any(blob.startswith(magic) for magic in AVATAR_MAGIC):
        return err("That is not a photo Homebase can use (use a normal JPEG or PNG).")
    db.run(update(db.members).where(db.members.c.id == g.m["id"]).values(avatar=_b64.b64encode(blob).decode(), avatar_at=time.time()))
    row = db.one(select(db.members).where(db.members.c.id == g.m["id"]))
    push_family(g.m["family_id"], "member", {"id": g.m["id"], "avatar": f"/api/avatar/{g.m['id']}?v={int(row['avatar_at'])}"})
    return jsonify(ok=True, avatar=f"/api/avatar/{g.m['id']}?v={int(row['avatar_at'])}")


@app.delete("/api/avatar")
@need
def clear_avatar():
    db.run(update(db.members).where(db.members.c.id == g.m["id"]).values(avatar="", avatar_at=time.time()))
    push_family(g.m["family_id"], "member", {"id": g.m["id"], "avatar": None})
    return jsonify(ok=True)


@app.get("/api/avatar/<int:member_id>")
@need
def get_avatar(member_id):
    target = same_family(member_id)
    if not target or not target["avatar"]:
        return err("No photo.", 404)
    try:
        blob = _b64.b64decode(target["avatar"])
    except Exception:
        return err("No photo.", 404)
    mime = next((m for magic, m in AVATAR_MAGIC.items() if blob.startswith(magic)), "image/jpeg")
    return app.response_class(blob, mimetype=mime, headers={"Cache-Control": "private, max-age=86400"})


# ---------------------------------------------------------------- linking a computer or tablet with a 6-digit code (no scripts, no copying keys)
@app.post("/api/devices/code")
@need
def device_code():
    if limited(f"devcode:{g.m['id']}", 20):
        return err("Too many codes. Wait a little.", 429)
    db.run(delete(db.pair_codes).where(db.pair_codes.c.expires < time.time()))
    code = "".join(secrets.choice("0123456789") for _ in range(6))
    db.run(insert(db.pair_codes).values(code=code, member_id=g.m["id"], expires=time.time() + 900))
    return jsonify(code=code, expires_in=900)


@app.post("/api/devices/claim")
def device_claim():
    """The Windows tracker (or any linked device) trades the code for its own key. The code works once and lasts 15 minutes."""
    if limited(f"devclaim:{request.remote_addr}", 20):
        return err("Too many tries. Wait a little.", 429)
    b = request.get_json(silent=True) or {}
    code = re.sub(r"\D", "", str(b.get("code") or ""))
    row = db.one(select(db.pair_codes).where(db.pair_codes.c.code == code)) if code else None
    if not row or row["expires"] < time.time():
        return err("That code is wrong or has run out. Make a new one in Homebase.", 400)
    db.run(delete(db.pair_codes).where(db.pair_codes.c.code == code))
    m = db.one(select(db.members).where(db.members.c.id == row["member_id"]))
    token = secrets.token_urlsafe(32)
    db.run(insert(db.devices).values(member_id=m["id"], token_hash=db.hash_token(token), name=clean(b.get("name"), 60) or "Computer",
                                     kind=clean(b.get("kind"), 12) or "windows", created=time.time(), last_seen=time.time()))
    say(m["family_id"], m["id"], f"{m['name']} linked a {clean(b.get('kind'), 12) or 'windows'} device: {clean(b.get('name'), 60) or 'Computer'}")     # nothing is silent
    return jsonify(token=token, member={"id": m["id"], "name": m["name"], "role": m["role"]})


def apps_for(member_id):
    """The apps seen across this child's linked computers, for the grounding picker. Deduped by key, best label kept."""
    seen = {}
    for d in db.many(select(db.devices).where(db.devices.c.member_id == member_id)):
        try:
            for a in json.loads(d["apps"] or "[]"):
                key = str(a.get("key") or "").lower()[:60]
                if len(key) >= 2:
                    seen.setdefault(key, str(a.get("label") or key)[:60])
        except (ValueError, AttributeError):
            pass
    return [{"key": k, "label": v} for k, v in sorted(seen.items(), key=lambda kv: kv[1].lower())]


@app.post("/api/devices/apps")
@need
def report_apps():
    """The Windows tracker sends the list of apps on this computer, so a parent can tick them instead of typing."""
    token = (request.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
    dev = db.one(select(db.devices).where(db.devices.c.token_hash == db.hash_token(token)))
    if not dev:
        return err("Only a linked device reports apps.", 403)
    apps = [a for a in (request.get_json(silent=True) or {}).get("apps", []) if isinstance(a, dict)][:200]
    clean_apps = [{"key": str(a.get("key") or "").lower()[:60], "label": str(a.get("label") or "")[:60]} for a in apps if len(str(a.get("key") or "")) >= 2]
    db.run(update(db.devices).where(db.devices.c.id == dev["id"]).values(apps=json.dumps(clean_apps[:200])))
    return jsonify(ok=True, count=len(clean_apps))


@app.post("/api/devices/status")
@need
def device_status():
    """The tracker's heartbeat: what it sees right now (e.g. "counting Minecraft"), so the family can tell it is alive and working."""
    token = (request.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
    dev = db.one(select(db.devices).where(db.devices.c.token_hash == db.hash_token(token)))
    if not dev:
        return err("Only a linked device reports status.", 403)
    b = request.get_json(silent=True) or {}
    db.run(update(db.devices).where(db.devices.c.id == dev["id"]).values(status=clean(b.get("status"), 150), version=clean(b.get("version"), 12), last_seen=time.time()))
    return jsonify(ok=True)


@app.get("/api/devices")
@need
def list_devices():
    rows = db.many(select(db.devices).where(db.devices.c.member_id == g.m["id"]).order_by(db.devices.c.id))
    return jsonify(devices=[{"id": d["id"], "name": d["name"], "kind": d["kind"], "created": d["created"], "last_seen": d["last_seen"]} for d in rows])


@app.delete("/api/devices/<int:did>")
@need
def remove_device(did):
    d = db.one(select(db.devices).where(db.devices.c.id == did, db.devices.c.member_id == g.m["id"]))
    if not d:
        return err("Not found.", 404)
    db.run(delete(db.devices).where(db.devices.c.id == did))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} removed a device: {d['name']}")
    return jsonify(ok=True)


# ---------------------------------------------------------------- grounding: block certain apps on a child's laptop or tablet for a while
def active_blocks(member_id, now=None):
    now = now or time.time()
    rows = db.many(select(db.blocks).where(db.blocks.c.member_id == member_id, db.blocks.c.until > now).order_by(db.blocks.c.id))
    return [r for r in rows if not r["ended"] or r["ended"] > now]


GROUND_WARN = 60                                               # a minute's warning to finish the game or video before it is blocked


def block_views(m):
    if m["role"] != "child":
        return []
    return [{"id": r["id"], "key": r["key"], "label": r["label"], "until": r["until"], "active_at": r["created"] + GROUND_WARN} for r in active_blocks(m["id"])]


def make_blocks(by, target, apps, minutes):
    now = time.time()
    labels = []
    for a in apps[:12]:
        key = clean((a or {}).get("key") if isinstance(a, dict) else a, 60).lower()
        label = clean((a or {}).get("label") if isinstance(a, dict) else a, 60) or key
        if len(key) < 2:
            continue
        db.run(insert(db.blocks).values(member_id=target["id"], family_id=target["family_id"], key=key, label=label, by_id=by["id"] if by else 0, created=now, until=now + minutes * 60))
        labels.append(label)
    if labels:
        who = by["name"] if by else "The owner"
        until = time.strftime("%H:%M", time.gmtime(now + minutes * 60 - (target["tz_offset"] or 0) * 60))
        say(target["family_id"], by["id"] if by else target["id"], f"{who} is grounding {target['name']} from {', '.join(labels)} in 1 minute, until {until}")
        push(target["id"], "ground_warn", {"apps": labels, "seconds": GROUND_WARN})
        push_family(target["family_id"], "member", {"id": target["id"], "blocks": True})
    return labels


@app.get("/api/blocks")
@need
def get_blocks():
    target = same_family(qint("member", g.m["id"]))
    if not target or target["role"] != "child":
        return err("Not found.", 404)
    if target["id"] != g.m["id"] and g.m["role"] != "parent":
        return err("Only you or a parent can see this.", 403)
    return jsonify(blocks=block_views(target), available=apps_for(target["id"]), now=time.time())


@app.post("/api/blocks")
@parent_only
def add_blocks():
    b = request.get_json(silent=True) or {}
    target = same_family(int(b["member"])) if str(b.get("member", "")).isdigit() else None
    if not target or target["role"] != "child":
        return err("Pick a child.", 404)
    try:
        minutes = int(b.get("minutes") or 0)
    except (TypeError, ValueError):
        return err("How many minutes?")
    if minutes < 5:
        return err("Ground for at least 5 minutes.")
    apps = b.get("apps") if isinstance(b.get("apps"), list) else []
    labels = make_blocks(g.m, target, apps, min(minutes, 24 * 60))
    if not labels:
        return err("Pick at least one app (a name of two letters or more).")
    return jsonify(ok=True, apps=labels)


@app.delete("/api/blocks/<int:bid>")
@parent_only
def lift_block(bid):
    r = db.one(select(db.blocks).where(db.blocks.c.id == bid, db.blocks.c.family_id == g.m["family_id"]))
    if not r:
        return err("Not found.", 404)
    db.run(update(db.blocks).where(db.blocks.c.id == bid).values(ended=time.time()))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} lifted the grounding from {r['label']}")
    push_family(g.m["family_id"], "member", {"id": r["member_id"], "blocks": True})
    return jsonify(ok=True)


# ---------------------------------------------------------------- lock a child's devices for a while (a parent's button, four hours a day at most)
# ---------------------------------------------------------------- chores: a parent sets a job, a child ticks it off, a parent approves it for points
def chore_view(c):
    return {"id": c["id"], "title": c["title"], "emoji": c["emoji"], "points": c["points"], "to": c["to_id"], "status": c["status"],
            "done_by": c["done_by"], "done_at": c["done_at"], "approved_at": c["approved_at"], "created": c["created"]}


def chore_points(fid, since=0):
    pts = {}
    for c in db.many(select(db.chores).where(db.chores.c.family_id == fid, db.chores.c.status == "approved", db.chores.c.approved_at >= since)):
        pts[c["done_by"]] = pts.get(c["done_by"], 0) + c["points"]
    return pts


def own_chore(cid):
    return db.one(select(db.chores).where(db.chores.c.id == cid, db.chores.c.family_id == g.m["family_id"]))


@app.get("/api/chores")
@need
def get_chores():
    rows = db.many(select(db.chores).where(db.chores.c.family_id == g.m["family_id"]).order_by(db.chores.c.id.desc()))
    week = time.time() - 7 * 86400
    open_rows = [c for c in rows if c["status"] != "approved" or c["approved_at"] >= week][:60]
    return jsonify(chores=[chore_view(c) for c in open_rows], points=chore_points(g.m["family_id"]), week=chore_points(g.m["family_id"], week))


@app.post("/api/chores")
@parent_only
def add_chore():
    b = request.get_json(silent=True) or {}
    title = clean(b.get("title"), 80)
    if not title:
        return err("What is the job?")
    try:
        pts = max(0, min(100, int(b.get("points") if b.get("points") not in (None, "") else 5)))
    except (TypeError, ValueError):
        return err("Points should be a number.")
    to = int(b["to"]) if str(b.get("to", "")).isdigit() else 0
    if to and not (same_family(to) and same_family(to)["role"] == "child"):
        return err("Pick a child.", 404)
    if len(db.many(select(db.chores.c.id).where(db.chores.c.family_id == g.m["family_id"], db.chores.c.status != "approved"))) >= 40:
        return err("That is plenty of jobs (40). Approve or delete some first.")
    cid = db.run(insert(db.chores).values(family_id=g.m["family_id"], title=title, emoji=clean(b.get("emoji"), 8) or "🧹", points=pts, to_id=to, created_by=g.m["id"], created=time.time()))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} added a job: {title} ({pts} points)")
    push_family(g.m["family_id"], "chores", {"id": cid})
    return jsonify(id=cid)


@app.post("/api/chores/<int:cid>/done")
@need
def chore_done(cid):
    c = own_chore(cid)
    if not c or c["status"] != "open":
        return err("That job is not open.", 404)
    if g.m["role"] != "child" or (c["to_id"] and c["to_id"] != g.m["id"]):
        return err("This job is for someone else.", 403)
    db.run(update(db.chores).where(db.chores.c.id == cid).values(status="done", done_by=g.m["id"], done_at=time.time()))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} finished: {c['title']}. A parent can approve it.")
    push_family(g.m["family_id"], "chores", {"id": cid})
    return jsonify(ok=True)


@app.post("/api/chores/<int:cid>/approve")
@parent_only
def chore_approve(cid):
    c = own_chore(cid)
    if not c or c["status"] != "done":
        return err("Nothing to approve.", 404)
    ok = bool((request.get_json(silent=True) or {}).get("ok", True))
    if ok:
        db.run(update(db.chores).where(db.chores.c.id == cid).values(status="approved", approved_by=g.m["id"], approved_at=time.time()))
        who = same_family(c["done_by"])
        say(g.m["family_id"], g.m["id"], f"{g.m['name']} approved {who['name'] if who else 'the job'}'s job: {c['title']} (+{c['points']} points)")
    else:                                                          # not quite right: back to the list
        db.run(update(db.chores).where(db.chores.c.id == cid).values(status="open", done_by=0, done_at=0))
        say(g.m["family_id"], g.m["id"], f"{g.m['name']} asked for {c['title']} to be done again")
    push_family(g.m["family_id"], "chores", {"id": cid})
    return jsonify(ok=True)


@app.delete("/api/chores/<int:cid>")
@parent_only
def chore_delete(cid):
    if not own_chore(cid):
        return err("Not found.", 404)
    db.run(delete(db.chores).where(db.chores.c.id == cid))
    push_family(g.m["family_id"], "chores", {"id": cid})
    return jsonify(ok=True)


# ---------------------------------------------------------------- the week at a glance, for a child (and the parents who may see it)
@app.get("/api/summary")
@need
def week_summary():
    target = same_family(qint("member", g.m["id"]))
    if not target or target["role"] != "child":
        return err("Not found.", 404)
    if target["id"] != g.m["id"] and g.m["role"] != "parent":
        return err("Only you or a parent can see this.", 403)
    now = time.time()
    since_day = time.strftime("%Y-%m-%d", time.gmtime(now - 7 * 86400))
    can_screen = parent_may(g.m, target, "screentime")
    out = {"member": target["id"], "name": target["name"], "screen": None, "locks": {"count": 0, "minutes": 0}, "chores": {"done": 0, "points": 0}}
    if can_screen:
        rows = db.many(select(db.screentime).where(db.screentime.c.member_id == target["id"], db.screentime.c.day >= since_day))
        days, apps = {}, {}
        for r in rows:
            days[r["day"]] = days.get(r["day"], 0) + r["sec"]
            apps[r["label"] or r["pkg"]] = apps.get(r["label"] or r["pkg"], 0) + r["sec"]
        total = sum(days.values())
        top = sorted(apps.items(), key=lambda kv: -kv[1])[:3]
        out["screen"] = {"total": total, "avg": int(total / max(1, len(days))), "days": len(days), "top": [{"label": k, "sec": v} for k, v in top],
                         "busiest": max(days.items(), key=lambda kv: kv[1])[0] if days else None}
    lk = db.many(select(db.locks).where(db.locks.c.member_id == target["id"], db.locks.c.until > now - 7 * 86400))
    out["locks"] = {"count": len(lk), "minutes": int(locked_between(target, now - 7 * 86400, now, lk) // 60)}
    week = time.time() - 7 * 86400
    mine = [c for c in db.many(select(db.chores).where(db.chores.c.family_id == target["family_id"], db.chores.c.status == "approved", db.chores.c.done_by == target["id"], db.chores.c.approved_at >= week))]
    out["chores"] = {"done": len(mine), "points": sum(c["points"] for c in mine)}
    return jsonify(out)


# ---------------------------------------------------------------- family rules: house rules and a daily play/watch limit per child
def limit_of(m):
    try:
        return max(0, int(json.loads(m["rules"] or "{}").get("limit") or 0))
    except (ValueError, TypeError, AttributeError):
        return 0


@app.get("/api/rules")
@need
def get_rules():
    """Everyone can read the rules that apply to them. Only parents change them."""
    fam = db.one(select(db.families).where(db.families.c.id == g.m["family_id"]))
    kids = [m for m in family_members(g.m["family_id"]) if m["role"] == "child"]
    limits = {m["id"]: limit_of(m) for m in kids if g.m["role"] == "parent" or m["id"] == g.m["id"]}
    return jsonify(text=fam["rules"] or "", limits=limits, children=[{"id": m["id"], "name": m["name"], "emoji": m["emoji"]} for m in kids] if g.m["role"] == "parent" else [])


@app.post("/api/rules")
@parent_only
def set_rules():
    b = request.get_json(silent=True) or {}
    if "text" in b:
        db.run(update(db.families).where(db.families.c.id == g.m["family_id"]).values(rules=str(b.get("text") or "").strip()[:1500]))
    for key, val in (b.get("limits") or {}).items():
        kid = same_family(int(key)) if str(key).isdigit() else None
        if not kid or kid["role"] != "child":
            continue
        try:
            mins = max(0, min(720, int(val or 0)))
        except (TypeError, ValueError):
            return err("Minutes should be a number.")
        db.run(update(db.members).where(db.members.c.id == kid["id"]).values(rules=json.dumps({"limit": mins})))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} updated the family rules")                  # the family always sees it: nothing is silent
    push_family(g.m["family_id"], "member", {"id": g.m["id"], "rules": True})
    return jsonify(ok=True)


@app.get("/api/lock")
@need
def get_lock():
    target = same_family(qint("member", g.m["id"]))
    if not target or target["role"] != "child":
        return err("Not found.", 404)
    if target["id"] != g.m["id"] and g.m["role"] != "parent":
        return err("Only you or a parent can see this.", 403)
    return jsonify(lock=lock_view(target), now=time.time(), **lock_budget(target))


@app.post("/api/lock")
@need
def make_lock():
    if g.m["role"] != "parent":
        return err("Only a parent can lock a device.", 403)
    b = request.get_json(silent=True) or {}
    target = same_family(int(b["member"])) if str(b.get("member", "")).isdigit() else None
    if not target or target["role"] != "child":
        return err("Pick a child.", 404)
    try:
        minutes = int(b.get("minutes") or 0)
    except (TypeError, ValueError):
        return err("How many minutes?")
    if minutes < 5:
        return err("Lock for at least 5 minutes.")
    now = time.time()
    if active_lock(target, now):
        return err(f"{target['name']} is already locked. Unlock first to start a new one.", 409)
    until = now + min(minutes, 240) * 60
    # the four-hour limit is per day, so check every day this lock touches
    a, e = day_window(target, now)
    while a < until:
        mine = max(0, min(until, e) - max(now, a))
        if locked_between(target, a, e) + mine > LOCK_MAX_DAY + 1:
            left = max(0, LOCK_MAX_DAY - locked_between(target, a, e))
            return err(f"{target['name']} can only be locked for 4 hours a day. {int(left // 60)} minutes are left for that day.", 400, left=int(left))
        a, e = e, e + 86400
    lid = db.run(insert(db.locks).values(member_id=target["id"], by_id=g.m["id"], family_id=g.m["family_id"], start=now, until=until, reason=str(b.get("reason") or "")[:120]))
    clock = time.strftime("%H:%M", time.gmtime(until - (target["tz_offset"] or 0) * 60))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} locked {target['name']}'s devices until {clock}")        # the family always sees it: nothing is silent
    push_family(g.m["family_id"], "member", {"id": target["id"], "lock": {"until": until}})
    return jsonify(ok=True, until=until, **lock_budget(target))


def _lift(target, by_role, who):
    r = active_lock(target)
    if not r:
        return False
    now = time.time()
    db.run(update(db.locks).where(db.locks.c.id == r["id"]).values(ended=now, lifted_by=by_role))
    say(target["family_id"], target["id"], f"{who} unlocked {target['name']}'s devices")
    push_family(target["family_id"], "member", {"id": target["id"], "lock": None})
    return True


@app.post("/api/lock/<int:member_id>/release")
@need
def release_lock(member_id):
    if g.m["role"] != "parent":
        return err("Only a parent can unlock a device.", 403)
    target = same_family(member_id)
    if not target or target["role"] != "child":
        return err("Not found.", 404)
    return jsonify(ok=True, lifted=_lift(target, "parent", g.m["name"]), **lock_budget(target))


@app.post("/api/lock/ask")
@need
def ask_unlock():
    """A locked child can always ask for their device back, or send an SOS (the messages route). Nobody is shut out of reaching their family."""
    if g.m["role"] != "child":
        return err("Only children are locked.", 403)
    if limited(f"ask:{g.m['id']}", 6):
        return err("You already asked. They will see it.", 429)
    mid = say(g.m["family_id"], g.m["id"], f"{g.m['name']} asked to be unlocked early", kind="text")
    row = db.one(select(db.messages).where(db.messages.c.id == mid))
    for x in family_members(g.m["family_id"]):
        if x["id"] != g.m["id"]:
            push(x["id"], "message", {**msg_view(row), "name": g.m["name"], "emoji": g.m["emoji"], "quiet": False, "urgent": False})
    return jsonify(ok=True)


# the owner's override, from Cramly Control: see every lock and lift any of them
_owner_seen = {}                                               # sha256 of a key Cramly accepted -> when (a minute of memory, so a click is not a round trip)


def verify_owner(key):
    """Is this the owner's key? Homebase holds no owner secret of its own: Cramly (which does) answers."""
    mine = os.environ.get("HB_ADMIN_KEY", "")                      # optional: a local override for development
    if len(mine) >= 12:
        return hmac.compare_digest(mine.encode(), key.encode())
    if len(key) < 12:
        return False
    import hashlib
    import urllib.request
    h = hashlib.sha256(key.encode()).hexdigest()
    if time.time() - _owner_seen.get(h, 0) < 60:
        return True
    try:
        req = urllib.request.Request(os.environ.get("HUB_URL", "https://cramly-twz5.onrender.com").rstrip("/") + "/api/uni/verify", headers={"X-Admin-Key": key})
        with urllib.request.urlopen(req, timeout=45) as r:           # 45 s: a sleeping free server needs a moment
            ok = r.status == 200
    except Exception:
        return False
    if ok:
        _owner_seen[h] = time.time()
    return ok


def owner_ok():
    return verify_owner(request.headers.get("X-Admin-Key", ""))


def admin_gate():
    return None if owner_ok() else err("Wrong key.", 403)


@app.get("/api/admin/families")
def admin_families():
    if (bad := admin_gate()):
        return bad
    out = []
    for f in db.many(select(db.families).order_by(db.families.c.id.desc())):
        ms = family_members(f["id"])
        out.append({"id": f["id"], "name": f["name"], "created": f["created"], "members": [{"id": m["id"], "name": m["name"], "role": m["role"], "emoji": m["emoji"], "last_seen": m["last_seen"],
                    "devices": len(db.many(select(db.devices.c.id).where(db.devices.c.member_id == m["id"]))), "device_list": [{"id": d["id"], "name": d["name"], "kind": d["kind"], "last_seen": d["last_seen"], "status": d["status"] or "", "version": d["version"] or ""} for d in db.many(select(db.devices).where(db.devices.c.member_id == m["id"]))],
                    "lock": lock_view(m), "blocks": block_views(m), "apps": apps_for(m["id"]) if m["role"] == "child" else []} for m in ms]})
    return jsonify(families=out, now=time.time())


@app.post("/api/admin/invite")
def admin_invite():
    if (bad := admin_gate()):
        return bad
    b = request.get_json(silent=True) or {}
    fam = db.one(select(db.families).where(db.families.c.id == int(b["family_id"]))) if str(b.get("family_id", "")).isdigit() else None
    if not fam:
        return err("No such family.", 404)
    role = "parent" if b.get("role") == "parent" else "child"
    code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
    db.run(insert(db.invites).values(code=code, family_id=fam["id"], role=role, created_by=0, expires=time.time() + 86400, used=False))
    return jsonify(code=code, role=role, family=fam["name"], expires=time.time() + 86400)


@app.post("/api/admin/announce")
def admin_announce():
    if (bad := admin_gate()):
        return bad
    b = request.get_json(silent=True) or {}
    text = clean(b.get("text"), 500)
    if not text:
        return err("Write the announcement first.")
    fams = [f for f in db.many(select(db.families))] if b.get("family_id") in ("*", None, "") else [f for f in db.many(select(db.families).where(db.families.c.id == int(b["family_id"])))] if str(b.get("family_id", "")).isdigit() else []
    if not fams:
        return err("No such family.", 404)
    for f in fams:
        mid = say(f["id"], 0, f"📢 Announcement: {text}", kind="system")
        row = db.one(select(db.messages).where(db.messages.c.id == mid))
        for m in family_members(f["id"]):
            push(m["id"], "message", {**msg_view(row), "name": "Homebase", "emoji": "📢", "quiet": False, "urgent": False})
    return jsonify(ok=True, families=len(fams))


@app.get("/api/admin/blocks")
def admin_blocks():
    if (bad := admin_gate()):
        return bad
    now = time.time()
    out = []
    for r in db.many(select(db.blocks).where(db.blocks.c.until > now)):
        if r["ended"] and r["ended"] <= now:
            continue
        kid = db.one(select(db.members).where(db.members.c.id == r["member_id"]))
        out.append({"id": r["id"], "child": kid["name"] if kid else "?", "label": r["label"], "until": r["until"], "member_id": r["member_id"]})
    return jsonify(blocks=out, now=now)


@app.post("/api/admin/blocks")
def admin_add_block():
    if (bad := admin_gate()):
        return bad
    b = request.get_json(silent=True) or {}
    kid = db.one(select(db.members).where(db.members.c.id == int(b["member_id"]))) if str(b.get("member_id", "")).isdigit() else None
    if not kid or kid["role"] != "child":
        return err("Pick a child.", 404)
    try:
        minutes = max(5, min(24 * 60, int(b.get("minutes") or 60)))
    except (TypeError, ValueError):
        return err("How many minutes?")
    labels = make_blocks(None, kid, b.get("apps") if isinstance(b.get("apps"), list) else [], minutes)
    return jsonify(ok=bool(labels), apps=labels)


@app.delete("/api/admin/blocks/<int:bid>")
def admin_lift_block(bid):
    if (bad := admin_gate()):
        return bad
    r = db.one(select(db.blocks).where(db.blocks.c.id == bid))
    if not r:
        return err("No such block.", 404)
    db.run(update(db.blocks).where(db.blocks.c.id == bid).values(ended=time.time()))
    push_family(r["family_id"], "member", {"id": r["member_id"], "blocks": True})
    return jsonify(ok=True)


@app.delete("/api/admin/devices/<int:did>")
def admin_remove_device(did):
    if (bad := admin_gate()):
        return bad
    d = db.one(select(db.devices).where(db.devices.c.id == did))
    if not d:
        return err("No such device.", 404)
    m = db.one(select(db.members).where(db.members.c.id == d["member_id"]))
    db.run(delete(db.devices).where(db.devices.c.id == did))
    if m:
        say(m["family_id"], m["id"], f"The owner removed {m['name']}'s device: {d['name']}")
    return jsonify(ok=True)


@app.get("/api/admin/activity")
def admin_activity():
    """The latest things that happened in any family (check-ins, locks, jobs, grounding, linked devices...), newest first."""
    if (bad := admin_gate()):
        return bad
    names = {f["id"]: f["name"] for f in db.many(select(db.families))}
    who = {m["id"]: m["name"] for m in db.many(select(db.members))}
    rows = db.many(select(db.messages).where(db.messages.c.kind.in_(["system", "sos", "late", "checkin"])).order_by(db.messages.c.id.desc()).limit(60))
    return jsonify(items=[{"at": r["created"], "family": names.get(r["family_id"], "?"), "kind": r["kind"], "who": who.get(r["from_id"], ""), "text": r["body"]} for r in rows])


@app.get("/api/admin/locks")
def admin_locks():
    if not owner_ok():
        return err("Wrong key.", 403)
    now = time.time()
    rows = db.many(select(db.locks).where(db.locks.c.until > now, db.locks.c.start <= now))
    out = []
    for r in rows:
        if _lock_end(r) <= now:
            continue
        kid, by = db.one(select(db.members).where(db.members.c.id == r["member_id"])), db.one(select(db.members).where(db.members.c.id == r["by_id"]))
        fam = db.one(select(db.families).where(db.families.c.id == r["family_id"]))
        out.append({"id": r["id"], "child": kid["name"] if kid else "?", "by": by["name"] if by else "?", "family": fam["name"] if fam else "?", "until": _lock_end(r), "start": r["start"]})
    return jsonify(locks=out, now=now)


@app.post("/api/admin/locks/<int:lid>/lift")
def admin_lift(lid):
    if not owner_ok():
        return err("Wrong key.", 403)
    r = db.one(select(db.locks).where(db.locks.c.id == lid))
    kid = r and db.one(select(db.members).where(db.members.c.id == r["member_id"]))
    if not kid:
        return err("No such lock.", 404)
    return jsonify(ok=True, lifted=_lift(kid, "owner", "The owner"))


# ---------------------------------------------------------------- screen time: only active playing or watching, never idle or study time
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
CATS = ("play", "watch", "social")


NUDGE_EVERY = 20 * 60                                          # a friendly nudge for every 20 minutes of actual playing or watching
NUDGES = ["Take a break and ride your bike 🚲", "Grab a book and read for a while 📖", "Bring {parent} a glass of water 💧",
          "Stretch, then get some fresh air outside 🌳", "Ask {parent} if there is something small you can help with 🤝", "Tidy your room for five minutes 🧹",
          "Get yourself a glass of water 💧", "Message or call someone you love 💛", "Draw, build or make something ✏️", "Kick a ball around outside ⚽",
          "Make {parent} a cup of tea or a snack 🍵", "Go for a short walk 🚶"]


def nudge_text(m, count, total):
    parents = [p["name"] for p in family_members(m["family_id"]) if p["role"] == "parent"]
    who = parents[count % len(parents)] if parents else "someone at home"
    mins = total // 60
    return f"{mins} minutes of playing or watching today. " + NUDGES[(count - 1) % len(NUDGES)].format(parent=who)


def day_total(member_id, day):
    return sum(r["sec"] for r in db.many(select(db.screentime).where(db.screentime.c.member_id == member_id, db.screentime.c.day == day)))


@app.post("/api/screentime")
@need
def post_screentime():
    """The phone sends today's running totals (it has already left out idle time, a locked screen and study or work apps)."""
    b = request.get_json(silent=True) or {}
    day = str(b.get("day") or "")
    if not DAY_RE.match(day):
        return err("Which day?")
    apps = [a for a in (b.get("apps") or []) if isinstance(a, dict)][:80]
    now = time.time()
    before = day_total(g.m["id"], day)
    for a in apps:
        pkg = str(a.get("pkg") or "")[:120]
        if not pkg:
            continue
        try:
            sec = max(0, min(86400, int(float(a.get("sec") or 0))))
        except (TypeError, ValueError):
            sec = 0
        cat = a.get("cat") if a.get("cat") in CATS else "play"
        label = str(a.get("label") or pkg.rsplit(".", 1)[-1])[:60]
        row = db.one(select(db.screentime).where(db.screentime.c.member_id == g.m["id"], db.screentime.c.day == day, db.screentime.c.pkg == pkg))
        if row:
            db.run(update(db.screentime).where(db.screentime.c.id == row["id"]).values(sec=sec, label=label, cat=cat, updated=now))
        else:
            db.run(insert(db.screentime).values(member_id=g.m["id"], day=day, pkg=pkg, label=label, cat=cat, sec=sec, updated=now))
    after = day_total(g.m["id"], day)
    lim = limit_of(g.m) * 60
    if g.m["role"] == "child" and lim and before < lim <= after:                                  # reached the daily limit a parent set
        push(g.m["id"], "nudge", {"text": f"You have reached your daily limit of {lim // 60} minutes of playing and watching. Time for something else 🌳", "total": after})
        say(g.m["family_id"], g.m["id"], f"{g.m['name']} reached the daily limit of {lim // 60} minutes of playing and watching")
    elif g.m["role"] == "child" and after // NUDGE_EVERY > before // NUDGE_EVERY and after - before < 3600:      # crossed another 20 minutes (not a big catch-up upload)
        push(g.m["id"], "nudge", {"text": nudge_text(g.m, after // NUDGE_EVERY, after), "total": after})
    old = time.strftime("%Y-%m-%d", time.gmtime(now - 35 * 86400))                       # kept for a month, then deleted
    db.run(delete(db.screentime).where(db.screentime.c.member_id == g.m["id"], db.screentime.c.day < old))
    return jsonify(ok=True)


@app.get("/api/screentime")
@need
def get_screentime():
    target = same_family(qint("member", g.m["id"]))
    if not target:
        return err("Not found.", 404)
    if target["id"] != g.m["id"] and not (g.m["role"] == "parent" and target["role"] == "child"):
        return err("You can only see your own screen time, or a child's if you are a parent.", 403)
    if not parent_may(g.m, target, "screentime"):
        return denied(target, "screentime")
    days = max(1, min(30, qint("days", 7)))
    since = time.strftime("%Y-%m-%d", time.gmtime(time.time() - (days + 1) * 86400))
    rows = db.many(select(db.screentime).where(db.screentime.c.member_id == target["id"], db.screentime.c.day >= since).order_by(db.screentime.c.day.desc(), db.screentime.c.sec.desc()))
    by_day = {}
    for r in rows:
        d = by_day.setdefault(r["day"], {"day": r["day"], "total": 0, "apps": []})
        d["total"] += r["sec"]
        d["apps"].append({"pkg": r["pkg"], "label": r["label"], "cat": r["cat"], "sec": r["sec"]})
    return jsonify(member=target["id"], name=target["name"], days=list(by_day.values())[:days],
                   updated=max([r["updated"] for r in rows], default=0))


CMD_HEAD = """@echo off
rem Reopens itself in a window that stays open, so nothing can vanish before it is read.
if not "%~1"=="stay" if not defined HB_NO_STAY (
  cmd /k ""%~f0" stay"
  exit /b
)
setlocal
title Homebase screen time
echo.
echo   Homebase screen time
echo   Setting up. This window stays open so you can read everything.
echo.
set "DIR=%APPDATA%\Homebase"
if not exist "%DIR%" mkdir "%DIR%"
set "PS=%DIR%\Homebase-ScreenTime.ps1"
set "LOG=%DIR%\setup.log"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$t=[IO.File]::ReadAllText('%~f0'); $m='#'+'#PS1'; $i=$t.IndexOf($m); if($i -lt 0){throw 'Could not read the installer.'}; $j=$t.IndexOf([char]10,$i); [IO.File]::WriteAllText('%PS%', $t.Substring($j+1), (New-Object Text.UTF8Encoding($true)))" 2>>"%LOG%"
if errorlevel 1 goto :fail
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS%" 2>>"%LOG%"
if errorlevel 1 goto :fail
echo.
echo   All done. You can close this window now.
pause
exit /b 0
:fail
echo.
echo   Something went wrong. Take a photo of this window and show a parent.
echo   Details are saved in %LOG%
echo.
type "%LOG%" 2>nul
echo.
pause
exit /b 1
##PS1
"""


@app.get("/api/screentime/agent")
@need
def screentime_agent():
    """The Windows screen-time tracker, made for this child (it carries their own key, so it is only for them).
    Default is a .cmd you can double-click (it works around Windows blocking downloaded scripts); ?format=ps1 gives the bare script."""
    if g.m["role"] != "child":
        return err("Screen time is for children's devices.", 403)
    token = (request.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
    src = open(os.path.join(BASE, "static", "windows", "screentime.ps1"), encoding="utf-8").read()
    scheme = (request.headers.get("X-Forwarded-Proto") or request.scheme).split(",")[0].strip()
    server = f"{scheme}://{request.host}"
    body = src.replace("{{SERVER}}", server).replace("{{TOKEN}}", token)
    if request.args.get("format") == "ps1":
        return app.response_class("\ufeff" + body, mimetype="text/plain", headers={"Content-Disposition": 'attachment; filename="Homebase-ScreenTime.ps1"', "Cache-Control": "no-store"})
    text = (CMD_HEAD + body).replace("\r\n", "\n").replace("\n", "\r\n")                  # batch files need Windows line endings
    return app.response_class(text, mimetype="application/octet-stream", headers={"Content-Disposition": 'attachment; filename="Homebase-ScreenTime.cmd"', "Cache-Control": "no-store"})


@app.get("/api/windows/version")
def windows_version():
    """The newest Windows tracker build. A running tracker compares this to its own build and updates itself."""
    try:
        build = int(open(os.path.join(BASE, "windows", "BUILD")).read().strip())
    except (OSError, ValueError):
        build = 0
    exe = os.path.join(BASE, "windows", "HomebaseTracker.exe")
    return jsonify(build=build, size=os.path.getsize(exe) if os.path.exists(exe) else 0, url="/download/windows")


@app.get("/download/windows")
def download_windows():
    """The Windows tracker: one program for everyone. It links itself with the 6-digit code from Homebase."""
    return send_from_directory(os.path.join(BASE, "windows"), "HomebaseTracker.exe", as_attachment=True, mimetype="application/octet-stream", max_age=0)


@app.delete("/api/screentime")
@need
def delete_screentime():
    """Your screen time is yours: delete it any time."""
    db.run(delete(db.screentime).where(db.screentime.c.member_id == g.m["id"]))
    return jsonify(ok=True)


# ---------------------------------------------------------------- messages: texts, check-ins, running late, SOS, hearts
KINDS = ("text", "checkin", "late", "sos", "heart")


REACTIONS = ("👍", "❤️", "😂", "😮", "🙏", "🎉")


def reactions_of(r):
    """{emoji: [member ids]} for a message row, ignoring anything that is not one of the offered reactions."""
    try:
        d = json.loads(r.get("reactions") or "{}")
    except (ValueError, AttributeError):
        d = {}
    return {e: [int(i) for i in ids if isinstance(i, int)] for e, ids in (d.items() if isinstance(d, dict) else []) if e in REACTIONS and isinstance(ids, list) and ids}


def msg_view(r):
    return {"id": r["id"], "from": r["from_id"], "to": r["to_id"], "kind": r["kind"], "body": r["body"], "lat": r["lat"], "lng": r["lng"], "at": r["created"],
            "reactions": reactions_of(r)}


@app.post("/api/messages")
@need
def send_message():
    m, b = g.m, request.get_json(silent=True) or {}
    kind = b.get("kind") if b.get("kind") in KINDS else "text"
    body = clean(b.get("body"), 600)
    to_id = int(b.get("to")) if str(b.get("to", "")).isdigit() else 0
    if to_id and not same_family(to_id):
        return err("Not found.", 404)
    if kind == "text" and not body:
        return err("Write something first.")
    if to_id and kind in ("text", "heart") and not parent_may(m, same_family(to_id), "messages"):
        return denied(same_family(to_id), "messages")
    if limited(f"msg:{m['id']}", 120):
        return err("Slow down a little.", 429)
    lat = float(b["lat"]) if kind in ("checkin", "sos") and b.get("lat") is not None else None
    lng = float(b["lng"]) if kind in ("checkin", "sos") and b.get("lng") is not None else None
    if kind == "sos":
        body = body or "I need help"
    mid = say(m["family_id"], m["id"], body, kind=kind, lat=lat, lng=lng, to_id=to_id)
    row = db.one(select(db.messages).where(db.messages.c.id == mid))
    for x in family_members(m["family_id"]):
        if x["id"] == m["id"] or (to_id and x["id"] != to_id):
            continue
        if kind in ("text", "heart") and not parent_may(m, x, "messages"):
            continue
        push(x["id"], "message", {**msg_view(row), "name": m["name"], "emoji": m["emoji"], "quiet": in_quiet(x) and kind != "sos", "urgent": kind == "sos"})
    return jsonify(message=msg_view(row))


@app.get("/api/messages")
@need
def get_messages():
    m = g.m
    with_id, after = qint("with", 0), qint("after", 0)
    t = db.messages
    if with_id:
        scope = ((t.c.from_id == m["id"]) & (t.c.to_id == with_id)) | ((t.c.from_id == with_id) & (t.c.to_id == m["id"]))
    else:
        scope = t.c.to_id == 0
    rows = db.many(select(t).where(t.c.family_id == m["family_id"], t.c.id > after, scope).order_by(t.c.id.desc()).limit(80))
    return jsonify(messages=[msg_view(r) for r in reversed(rows)])


@app.post("/api/messages/<int:mid>/react")
@need
def react(mid):
    """Tap a reaction on a message; tap it again to take it back. Only people who can see the message can react to it."""
    m, b = g.m, request.get_json(silent=True) or {}
    emoji = b.get("emoji")
    if emoji not in REACTIONS:
        return err("Pick one of the reactions.")
    if limited(f"react:{m['id']}", 240):
        return err("Slow down a little.", 429)
    row = db.one(select(db.messages).where(db.messages.c.id == mid, db.messages.c.family_id == m["family_id"]))
    if not row or row["kind"] == "system" or (row["to_id"] and m["id"] not in (row["from_id"], row["to_id"])):
        return err("Not found.", 404)
    d = reactions_of(row)
    ids = d.get(emoji, [])
    ids = [i for i in ids if i != m["id"]] if m["id"] in ids else ids + [m["id"]]
    if ids:
        d[emoji] = ids
    else:
        d.pop(emoji, None)
    db.run(update(db.messages).where(db.messages.c.id == mid).values(reactions=json.dumps(d, ensure_ascii=False)))
    payload = {"id": mid, "from": row["from_id"], "to": row["to_id"], "reactions": d}
    for x in family_members(m["family_id"]):
        if not row["to_id"] or x["id"] in (row["from_id"], row["to_id"]):
            push(x["id"], "reaction", payload)
    return jsonify(reactions=d)


# ---------------------------------------------------------------- "what I am up to": a few words the person chooses, for a few hours
@app.post("/api/status")
@need
def set_status():
    m, b = g.m, request.get_json(silent=True) or {}
    emoji, text = clean(b.get("emoji"), 8), clean(b.get("text"), 40)
    if not (emoji or text):
        return err("Pick something, or write a few words.")
    try:
        hours = float(b["hours"]) if b.get("hours") not in (None, "") else 3.0
    except (TypeError, ValueError):
        return err("For how long?")
    until = time.time() + max(0.25, min(24.0, hours)) * 3600
    db.run(update(db.members).where(db.members.c.id == m["id"]).values(status_emoji=emoji, status_text=text, status_until=until))
    push_family(m["family_id"], "member", {"id": m["id"], "status": True}, skip=m["id"])
    return jsonify(status={"emoji": emoji, "text": text, "until": until})


@app.delete("/api/status")
@need
def clear_status():
    m = g.m
    db.run(update(db.members).where(db.members.c.id == m["id"]).values(status_emoji="", status_text="", status_until=0))
    push_family(m["family_id"], "member", {"id": m["id"], "status": False}, skip=m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- "where are you?": a request the other person can answer or decline
@app.post("/api/ping")
@need
def ask_where():
    m, b = g.m, request.get_json(silent=True) or {}
    target = same_family(b.get("to"))
    if not target or target["id"] == m["id"]:
        return err("Not found.", 404)
    if not parent_may(m, target, "ping"):
        return denied(target, "ping")
    if limited(f"ping:{m['id']}:{target['id']}", 6):
        return err("You asked a few times already. Give them a moment.", 429)
    pid = db.run(insert(db.pings).values(family_id=m["family_id"], from_id=m["id"], to_id=target["id"], status="pending", created=time.time()))
    push(target["id"], "ping", {"id": pid, "from": m["id"], "name": m["name"], "emoji": m["emoji"], "quiet": in_quiet(target)})
    return jsonify(id=pid)


@app.post("/api/ping/<int:pid>")
@need
def answer_ping(pid):
    m, b = g.m, request.get_json(silent=True) or {}
    p = db.one(select(db.pings).where(db.pings.c.id == pid, db.pings.c.to_id == m["id"]))
    if not p or p["status"] != "pending":
        return err("Not found.", 404)
    if b.get("accept") and b.get("lat") is not None:
        lat, lng = float(b["lat"]), float(b["lng"])
        db.run(update(db.pings).where(db.pings.c.id == pid).values(status="shared", lat=lat, lng=lng))
        push(p["from_id"], "ping_result", {"id": pid, "member": m["id"], "name": m["name"], "status": "shared", "lat": lat, "lng": lng})
    else:
        db.run(update(db.pings).where(db.pings.c.id == pid).values(status="declined"))
        push(p["from_id"], "ping_result", {"id": pid, "member": m["id"], "name": m["name"], "status": "declined"})
    return jsonify(ok=True)


# ---------------------------------------------------------------- places (everyone sees them, and gets told when one is added)
@app.post("/api/places")
@parent_only
def add_place():
    m, b = g.m, request.get_json(silent=True) or {}
    try:
        lat, lng = float(b["lat"]), float(b["lng"])
    except (KeyError, TypeError, ValueError):
        return err("Pick a spot on the map.")
    name = clean(b.get("name"), 40)
    if not name:
        return err("Give the place a name.")
    if db.many(select(db.places.c.id).where(db.places.c.family_id == m["family_id"])).__len__() >= 20:
        return err("That is plenty of places (20).")
    radius = max(50, min(1000, int(b["radius"]))) if str(b.get("radius", "")).isdigit() else 150
    pid = db.run(insert(db.places).values(family_id=m["family_id"], name=name, emoji=clean(b.get("emoji"), 8) or "📍", lat=lat, lng=lng, radius=radius, created_by=m["id"], created=time.time()))
    say(m["family_id"], m["id"], f"{m['name']} added the place {name}. Everyone is told when someone arrives or leaves.")
    push_family(m["family_id"], "places", {"id": pid, "name": name, "by": m["name"]})
    return jsonify(id=pid)


@app.post("/api/home")
@parent_only
def set_home():
    """The Home area: one special place per family. Setting it again moves it."""
    m, b = g.m, request.get_json(silent=True) or {}
    try:
        lat, lng = float(b["lat"]), float(b["lng"])
    except (KeyError, TypeError, ValueError):
        return err("Pick a spot, or share your location first.")
    radius = max(50, min(1000, int(b["radius"]))) if str(b.get("radius", "")).isdigit() else 200
    h = home_place(m["family_id"])
    if h:
        db.run(update(db.places).where(db.places.c.id == h["id"]).values(lat=lat, lng=lng, radius=radius, emoji="🏠"))
        for mm in family_members(m["family_id"]):                      # everyone starts "away" from the new spot until they are seen near it
            db.run(delete(db.place_state).where(db.place_state.c.member_id == mm["id"], db.place_state.c.place_id == h["id"]))
        pid = h["id"]
    else:
        pid = db.run(insert(db.places).values(family_id=m["family_id"], name="Home", emoji="🏠", lat=lat, lng=lng, radius=radius, created_by=m["id"], created=time.time()))
    say(m["family_id"], m["id"], f"{m['name']} set the Home area ({radius} m). Everyone is told when someone gets home or leaves.")
    push_family(m["family_id"], "places", {"id": pid, "name": "Home", "by": m["name"]})
    return jsonify(id=pid, radius=radius)


@app.delete("/api/places/<int:pid>")
@parent_only
def remove_place(pid):
    p = db.one(select(db.places).where(db.places.c.id == pid, db.places.c.family_id == g.m["family_id"]))
    if not p:
        return err("Not found.", 404)
    db.run(delete(db.places).where(db.places.c.id == pid))
    db.run(delete(db.place_state).where(db.place_state.c.place_id == pid))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} removed the place {p['name']}")
    push_family(g.m["family_id"], "places", {"id": pid, "removed": True})
    return jsonify(ok=True)


# ---------------------------------------------------------------- the family list
@app.get("/api/list")
@need
def get_list():
    rows = db.many(select(db.lists).where(db.lists.c.family_id == g.m["family_id"]).order_by(db.lists.c.done, db.lists.c.id.desc()).limit(100))
    return jsonify(items=[{"id": r["id"], "text": r["text"], "done": bool(r["done"]), "by": r["added_by"], "done_by": r["done_by"]} for r in rows])


@app.post("/api/list")
@need
def add_item():
    text = clean((request.get_json(silent=True) or {}).get("text"), 120)
    if not text:
        return err("Write the item first.")
    iid = db.run(insert(db.lists).values(family_id=g.m["family_id"], text=text, done=False, added_by=g.m["id"], created=time.time()))
    push_family(g.m["family_id"], "list", {"id": iid}, skip=g.m["id"])
    return jsonify(id=iid)


@app.post("/api/list/<int:iid>")
@need
def tick_item(iid):
    r = db.one(select(db.lists).where(db.lists.c.id == iid, db.lists.c.family_id == g.m["family_id"]))
    if not r:
        return err("Not found.", 404)
    done = bool((request.get_json(silent=True) or {}).get("done"))
    db.run(update(db.lists).where(db.lists.c.id == iid).values(done=done, done_by=g.m["id"] if done else 0))
    push_family(g.m["family_id"], "list", {"id": iid}, skip=g.m["id"])
    return jsonify(ok=True)


@app.delete("/api/list/<int:iid>")
@need
def drop_item(iid):
    r = db.one(select(db.lists).where(db.lists.c.id == iid, db.lists.c.family_id == g.m["family_id"]))
    if not r or not (r["added_by"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    db.run(delete(db.lists).where(db.lists.c.id == iid))
    push_family(g.m["family_id"], "list", {"id": iid}, skip=g.m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- the event stream: the apps hold this open and it wakes them
@app.get("/api/events")
@need
def event_stream():
    m = g.m
    after = qint("after", -1)
    if after < 0:
        top = db.one(select(db.events.c.id).where(db.events.c.member_id == m["id"]).order_by(db.events.c.id.desc()).limit(1))
        return jsonify(cursor=top["id"] if top else 0, events=[])
    wait = min(25, max(0, qint("wait", 0)))
    deadline, rows = time.time() + wait, []
    while True:
        expire_calls()
        rows = db.many(select(db.events).where(db.events.c.member_id == m["id"], db.events.c.id > after).order_by(db.events.c.id).limit(100))
        if rows or time.time() >= deadline:
            break
        time.sleep(0.6)
    if int(time.time()) % 40 == 0:
        db.run(delete(db.events).where(db.events.c.created < time.time() - 2 * 86400))
    return jsonify(cursor=rows[-1]["id"] if rows else after, events=[{"id": r["id"], "type": r["type"], "payload": json.loads(r["payload"]), "at": r["created"]} for r in rows])


# ---------------------------------------------------------------- calls: the server only passes the two phones' handshake notes along
RING_SECONDS = 45


def expire_calls():
    """A call nobody answered in 45 seconds becomes a missed call, with a note in the family chat."""
    for c in db.many(select(db.calls).where(db.calls.c.status == "ringing", db.calls.c.created < time.time() - RING_SECONDS)):
        db.run(update(db.calls).where(db.calls.c.id == c["id"]).values(status="missed", ended=time.time()))
        caller = db.one(select(db.members).where(db.members.c.id == c["caller_id"]))
        if caller:
            say(c["family_id"], c["caller_id"], f"Missed call from {caller['name']}", to_id=c["callee_id"])
        for who in (c["caller_id"], c["callee_id"]):
            push(who, "call_update", {"id": c["id"], "status": "missed"})


def my_call(cid):
    c = db.one(select(db.calls).where(db.calls.c.id == cid))
    return c if c and g.m["id"] in (c["caller_id"], c["callee_id"]) else None


def busy(member_id):
    live = db.many(select(db.calls).where(db.calls.c.status.in_(("ringing", "active")), (db.calls.c.caller_id == member_id) | (db.calls.c.callee_id == member_id)))
    return any(c["status"] == "active" or c["created"] > time.time() - RING_SECONDS for c in live)


@app.post("/api/calls")
@need
def start_call():
    m, b = g.m, request.get_json(silent=True) or {}
    callee = same_family(b.get("to"))
    offer = str(b.get("offer") or "")[:20000]
    if not callee or callee["id"] == m["id"] or not offer:
        return err("Pick someone to call.")
    if not parent_may(m, callee, "calls"):
        return denied(callee, "calls")
    urgent = bool(b.get("urgent"))
    if in_quiet(callee) and not urgent:
        return err(f"{callee['name']} has quiet hours on right now. You can ring anyway if it is important.", 409, quiet=True)
    if busy(callee["id"]) or busy(m["id"]):
        return err(f"{callee['name']} is on another call.", 409, busy=True)
    cid = db.run(insert(db.calls).values(family_id=m["family_id"], caller_id=m["id"], callee_id=callee["id"], status="ringing", urgent=urgent, created=time.time(), offer=offer))
    push(callee["id"], "call", {"id": cid, "from": m["id"], "name": m["name"], "emoji": m["emoji"], "offer": offer, "urgent": urgent})
    return jsonify(id=cid, ice_servers=ice_servers())


@app.get("/api/calls/<int:cid>")
@need
def get_call(cid):
    expire_calls()
    c = my_call(cid)
    if not c:
        return err("Not found.", 404)
    out = {"id": cid, "status": c["status"], "answer": c["answer"] if c["caller_id"] == g.m["id"] else None, "web_seen": bool(c["web_seen"])}
    if c["callee_id"] == g.m["id"] and c["status"] == "ringing":                 # the person being called can pick the call up from anywhere (e.g. the ringing window on a computer)
        who = db.one(select(db.members).where(db.members.c.id == c["caller_id"]))
        out.update(offer=c["offer"], **{"from": c["caller_id"]}, name=who["name"] if who else "Someone", emoji=who["emoji"] if who else "🙂", urgent=bool(c["urgent"]))
    return jsonify(out)


@app.post("/api/calls/<int:cid>/seen")
@need
def call_seen(cid):
    """A Homebase web page says it is ringing for this call, so a computer's own ringing window does not ring a second time."""
    c = my_call(cid)
    if not c or c["callee_id"] != g.m["id"]:
        return err("Not found.", 404)
    db.run(update(db.calls).where(db.calls.c.id == cid).values(web_seen=True))
    return jsonify(ok=True)


@app.post("/api/calls/<int:cid>/answer")
@need
def answer_call(cid):
    c = my_call(cid)
    answer = str((request.get_json(silent=True) or {}).get("answer") or "")[:20000]
    if not c or c["callee_id"] != g.m["id"] or c["status"] != "ringing" or not answer:
        return err("That call is over.", 404)
    db.run(update(db.calls).where(db.calls.c.id == cid).values(status="active", answered=time.time(), answer=answer))
    push(c["caller_id"], "call_update", {"id": cid, "status": "active", "answer": answer})
    return jsonify(ok=True)


@app.post("/api/calls/<int:cid>/decline")
@need
def decline_call(cid):
    c = my_call(cid)
    if not c or c["callee_id"] != g.m["id"] or c["status"] != "ringing":
        return err("That call is over.", 404)
    db.run(update(db.calls).where(db.calls.c.id == cid).values(status="declined", ended=time.time()))
    push(c["caller_id"], "call_update", {"id": cid, "status": "declined"})
    return jsonify(ok=True)


@app.post("/api/calls/<int:cid>/end")
@need
def end_call(cid):
    c = my_call(cid)
    if not c or c["status"] not in ("ringing", "active"):
        return jsonify(ok=True)
    status = "missed" if c["status"] == "ringing" else "ended"
    db.run(update(db.calls).where(db.calls.c.id == cid).values(status=status, ended=time.time()))
    other = c["callee_id"] if g.m["id"] == c["caller_id"] else c["caller_id"]
    if status == "missed":
        say(c["family_id"], c["caller_id"], f"Missed call from {g.m['name']}", to_id=c["callee_id"])
    push(other, "call_update", {"id": cid, "status": status})
    return jsonify(ok=True)


@app.post("/api/calls/<int:cid>/ice")
@need
def call_ice(cid):
    c = my_call(cid)
    cand = (request.get_json(silent=True) or {}).get("candidate")
    if not c or c["status"] not in ("ringing", "active") or not isinstance(cand, dict):
        return jsonify(ok=True)
    other = c["callee_id"] if g.m["id"] == c["caller_id"] else c["caller_id"]
    push(other, "ice", {"id": cid, "candidate": cand})
    return jsonify(ok=True)


# ---------------------------------------------------------------- the website: what Homebase is, and the app download
@app.get("/")
def home():
    has_apk = os.path.exists(os.path.join(BASE, "static", "homebase.apk"))
    return render_template("index.html", has_apk=has_apk, version=VERSION_CODE)


@app.get("/app")
def web_app():
    """Homebase on a laptop: the same family, map, chat and calls in a browser."""
    return send_from_directory(os.path.join(BASE, "static", "web"), "index.html")


@app.get("/sw.js")
def service_worker():
    """The offline worker lives at the root so it can cover the whole site."""
    resp = send_from_directory(os.path.join(BASE, "static", "web"), "sw.js", mimetype="text/javascript")
    resp.headers["Service-Worker-Allowed"] = "/"
    resp.headers["Cache-Control"] = "no-cache"
    return resp


@app.get("/manifest.webmanifest")
def web_manifest():
    return send_from_directory(os.path.join(BASE, "static", "web"), "manifest.webmanifest", mimetype="application/manifest+json")


@app.get("/update")
def update_page():
    return render_template("update.html", version=VERSION_CODE)


@app.get("/download")
def download():
    path = os.path.join(BASE, "static")
    if not os.path.exists(os.path.join(path, "homebase.apk")):
        return err("The app file is not uploaded yet.", 404)
    return send_from_directory(path, "homebase.apk", as_attachment=True, mimetype="application/vnd.android.package-archive")


@app.get("/health")
def health():
    return jsonify(ok=True, version=VERSION_CODE, db=db.engine.dialect.name)


@app.after_request
def fresh_web_client(resp):
    if request.path == "/app" or request.path.startswith("/static/web/"):
        resp.headers["Cache-Control"] = "no-cache"
    return resp


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5135)), debug=False, threaded=True)
