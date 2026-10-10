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
VERSION_CODE = int(os.environ.get("APP_VERSION_CODE", "1"))
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
    return db.one(select(db.members).where(db.members.c.token_hash == db.hash_token(token))) if token else None


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


def member_view(m, viewer=None):
    state = sharing_state(m)
    perms = perms_of(m)
    gated = bool(viewer) and viewer["role"] == "parent" and m["role"] == "child" and viewer["id"] != m["id"]
    out = {"id": m["id"], "name": m["name"], "role": m["role"], "emoji": m["emoji"], "battery": m["battery"], "share": state,
           "paused_until": m["paused_until"] if state == "paused" else 0, "last_seen": m["last_seen"], "quiet": m["quiet_from"] >= 0,
           "location": None, "perms": perms, "lock": lock_view(m), "home": at_home(m)}
    if state == "on" and m["last_lat"] is not None and not (gated and not perms["location"]):
        out["location"] = {"lat": m["last_lat"], "lng": m["last_lng"], "acc": m["last_acc"], "at": m["last_at"]}
    if gated and not perms["location"]:
        out["share"] = "off"
    if gated and not perms["battery"]:
        out["battery"] = -1
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
    return jsonify(ice_servers=ice_servers(), version_code=VERSION_CODE, apk_url=f"{request.host_url.rstrip('/')}/download")


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


# ---------------------------------------------------------------- lock a child's devices for a while (a parent's button, four hours a day at most)
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
    target = same_family(int(b.get("member") or 0))
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
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} asked to be unlocked early", kind="text")
    push_family(g.m["family_id"], "message", {"id": 0, "from": g.m["id"], "to": 0, "kind": "text", "body": f"{g.m['name']} asked to be unlocked early", "name": g.m["name"], "created": time.time()}, skip=g.m["id"])
    return jsonify(ok=True)


# the owner's override, from Cramly Control: see every lock and lift any of them
def owner_ok():
    want = os.environ.get("HB_ADMIN_KEY", "")
    return len(want) >= 12 and hmac.compare_digest(want.encode(), request.headers.get("X-Admin-Key", "").encode())


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
        sec = max(0, min(86400, int(a.get("sec") or 0)))
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


@app.get("/api/screentime/agent")
@need
def screentime_agent():
    """The Windows screen-time tracker, made for this child (it carries their own key, so it is only for them)."""
    if g.m["role"] != "child":
        return err("Screen time is for children's devices.", 403)
    token = (request.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
    src = open(os.path.join(BASE, "static", "windows", "screentime.ps1"), encoding="utf-8").read()
    body = src.replace("{{SERVER}}", request.host_url.rstrip("/")).replace("{{TOKEN}}", token)
    return app.response_class("\ufeff" + body, mimetype="text/plain", headers={"Content-Disposition": 'attachment; filename="Homebase-ScreenTime.ps1"', "Cache-Control": "no-store"})


@app.delete("/api/screentime")
@need
def delete_screentime():
    """Your screen time is yours: delete it any time."""
    db.run(delete(db.screentime).where(db.screentime.c.member_id == g.m["id"]))
    return jsonify(ok=True)


# ---------------------------------------------------------------- messages: texts, check-ins, running late, SOS, hearts
KINDS = ("text", "checkin", "late", "sos", "heart")


def msg_view(r):
    return {"id": r["id"], "from": r["from_id"], "to": r["to_id"], "kind": r["kind"], "body": r["body"], "lat": r["lat"], "lng": r["lng"], "at": r["created"]}


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
    return jsonify(id=cid, status=c["status"], answer=c["answer"] if c["caller_id"] == g.m["id"] else None)


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
