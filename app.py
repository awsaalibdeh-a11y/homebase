"""Homebase server: the small, honest backend for the family app.

Principles baked in: everything shared is visible to the person sharing it, they can pause it any time and the family sees "paused",
and there is no hidden mode. Parents never get silent access to a child's phone: location is shared by the app, check-ins, requests
("where are you?") can be declined, and quiet hours are the child's own setting."""

import calendar
import datetime
import hmac
import io
import json
import math
import os
import re
import secrets
import threading
import time
from collections import defaultdict

from flask import Flask, redirect, g, jsonify, render_template, request, send_from_directory

import db
from db import delete, insert, select, update
from moderation import moderate, worst

BASE = os.path.dirname(os.path.abspath(__file__))
app = Flask(__name__)
from flask_compress import Compress  # noqa: E402  (gzip pages, JSON and scripts: Render's free plan counts bandwidth)
Compress(app)
import hubguard  # noqa: E402  (the owner's universal switches, kept in Cramly)
hubguard.install(app, "homebase")
app.config["MAX_CONTENT_LENGTH"] = 1536 * 1024                      # room for one chat photo (a JPEG up to 900 KB is about 1.2 MB as text); everything else is tiny
VERSION_CODE = int(os.environ.get("APP_VERSION_CODE", "18"))      # what Android compares (a plain counter)
VERSION_NAME = os.environ.get("APP_VERSION_NAME", "6.4")           # what people see: a whole number is a big update, a decimal is a small one
CHANGELOG = [                                                         # newest first; shown on the install and update page
    {"v": "6.4", "date": "24 Oct 2026", "title": "Mellow rests at night", "items": [
        "From midnight to 6 am Mellow rests so it stays free: background updates, location and screen-time sends wait until 6 am.",
        "SOS and anything you do yourself still go through at night, and Mellow works normally while it is open on your screen.",
        "A parent lock that is already running at midnight keeps working through the night.",
        "Smaller and lighter: the app download and updates come from a faster download server.",
    ]},
    {"v": "6.3", "date": "18 Oct 2026", "title": "Quran together, with Noor", "items": [
        "New in More → Family: Quran (Noor). See what everyone in the family read in Noor, the Quran app: their streak, today's ayahs, last ayah, memorized surahs and recitation scores.",
        "Parents can give reading, memorizing and revising tasks for any surah and ayahs. They show up in Noor, and the family hears when one is done, with the recitation score.",
        "Link Noor to your Mellow account in one tap, so a new phone gets your Noor progress back.",
    ]},
    {"v": "6.2", "date": "18 Oct 2026", "title": "The map really loads now", "items": [
        "Fixed the map staying blank: it opened far away and slid across to your family, downloading hundreds of map pieces on the way, so yours never arrived. It now opens straight on your family.",
        "New people are no longer told \"Homebase has a new name\" when they never used Homebase.",
        "The welcome screen shows the Mellow logo.",
    ]},
    {"v": "6.1", "date": "17 Oct 2026", "title": "The map works again", "items": [
        "Fixed the map showing \"API key required\": the map style used since 4.0 started asking for a paid key, so Mellow is back on OpenStreetMap, with a night version for the dark look.",
        "The share-my-trip page uses the same free map.",
        "Fixed calls saying \"busy\" for ever after a call dropped: the phone now tells Mellow when a call drops, and an old stuck call is cleared when someone calls again.",
    ]},
    {"v": "6.0", "date": "17 Oct 2026", "big": True, "title": "Calmer, tidier, and updates in one tap", "items": [
        "A calm new look with no busy lines, and Dark is now the main look (White is still in Settings).",
        "More is organised into four tabs: Me, Family, Safety and Settings, with tidy tiles instead of a long list.",
        "Updates download and install inside the app: tap Update now, then Install. No browser or Downloads folder.",
        "Mellow asks for your birthday and year once, shows ages on family cards, and says how old someone is turning.",
        "A smoother five-second opening animation.",
        "On Windows: video calls in the web app, Pocket money, Album, Kudos, Homework, Moods, Countdowns, Weather, Safe Walk and Share my trip in the web app, a new Mellow icon, and the Windows tracker is now called Mellow (it updates itself).",
    ]},
    {"v": "5.0", "date": "17 Oct 2026", "big": True, "title": "The biggest update yet: 20 new things", "items": [
        "Safe Walk: share live until you get there; the family is told if you stop moving or are late.",
        "Tell me when they get there: a parent is told when a child reaches a place, or if they have not by a time.",
        "Speed alerts for new drivers, with the limit shown to the child.",
        "Find my phone: make your phone (or your child's) ring loudly, even on silent.",
        "Share my trip: a live-location link for someone outside the family that runs out by itself.",
        "Day timeline: where someone arrived and left today.",
        "Pocket money wallet with requests, and savings goals.",
        "Mood check-ins, with a private option; parents hear if a child is having a hard day.",
        "Kudos and the star of the week, plus badges you earn from what you already do.",
        "Family photo album (photos from the family chat go in by themselves) and voice notes in chat.",
        "Homework tracker with reminders, family countdowns, a trusted adults list, and bedtime wind-down reminders.",
        "Weather for Home on the Home tab, a home-screen family widget, and SOS, Critical Check-In, zone answers and safety alerts in the web app too.",
    ]},
    {"v": "4.0", "date": "17 Oct 2026", "big": True, "title": "The humongous update: safety, zones, a new look and a welcome animation", "items": [
        "SOS: a big button on Home and a home-screen widget. It sounds a loud alarm on parents' phones and sends live location every 10 seconds for 30 minutes, even if sharing was paused. \"I'm OK\" ends it.",
        "Critical Check-In: a parent can make a child's phone ring through silent until they tap I'm OK. The child can switch this off in What parents can do, and parents see that.",
        "Zones: children can ask for a place (school, Grandma's) and a parent says yes. Everyone is told when someone arrives or leaves.",
        "Running late works out an ETA from where you are to Home and shows it on the map.",
        "Safety alerts: a message in Mellow that looks like bullying, self-harm, adult content or grooming alerts a child's parents (only that message, never a log of everything).",
        "Safe browsing: a family web filter (adult content and malware) that also works in private tabs, with its on or off status shown to parents.",
        "Parents are told when a child's phone goes offline for 20 minutes, and when it is back.",
        "Battery saver: location is sent every 15 minutes under 20% battery (every 2 minutes otherwise); SOS always sends live.",
        "A clearer map, a bolder background, and tidier calls and chats.",
    ]},
    {"v": "3.0", "date": "16 Oct 2026", "big": True, "title": "Recipes, fun, and calls that connect", "items": [
        "Search recipes inside Mellow (Home, Plan, and the web app). Pick one and it goes on the meal plan with its ingredients and method; anyone can open it in Platter.",
        "Children can suggest recipes and meals. Parents get a notice and say yes or not now.",
        "Fun: whose turn is it, coin, dice, would you rather, send a compliment, surprise dinner.",
        "Fixed calls failing to connect: the call details were being cut short once video was added.",
    ]},
    {"v": "2.3", "date": "16 Oct 2026", "title": "Works with no signal", "items": [
        "Messages, check-ins and SOS written offline are kept and sent by themselves when the signal comes back.",
        "Plans, meals and the family list are saved on the phone so the tabs are never empty.",
        "Emergency card (works offline, saved on the phone only) and a Pocket with a break timer and private notes.",
    ]},
    {"v": "2.2", "date": "16 Oct 2026", "title": "Link Platter and Scout", "items": [
        "A popup for parents that explains how to install and link Platter and Scout, with clearer steps in More.",
        "Platter and Scout now call the family app Mellow.",
    ]},
    {"v": "2.1", "date": "16 Oct 2026", "title": "Welcome, and Mellow on Windows", "items": [
        "A one-time welcome popup on Android and the web.",
        "The web version (for Windows) is now Mellow too: new name, black and white contour look, new icons.",
    ]},
    {"v": "2.0", "date": "16 Oct 2026", "big": True, "title": "Homebase becomes Mellow", "items": [
        "New name, and a black and white look on a contour-line background (White is the main look, Dark is in Settings).",
        "Five tabs: Home, Map, Plan, Chat and More. Messages laid out like a phone's own messages app.",
        "Meals with recipes and who is cooking, friend groups with a mild-words filter, stickers and GIFs that move, family games, Directions on plans.",
        "Photos in messages, a new call screen, video calls, and profile pictures taken in the app with a face check.",
    ]},
]
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
        seen = {"last_seen": time.time()}
        v = request.headers.get("X-Homebase-Version", "")
        if v.isdigit() and 0 < int(v) < 100000 and int(v) != (m.get("app_version") or 0):
            seen["app_version"] = int(v)
        db.run(update(db.members).where(db.members.c.id == m["id"]).values(**seen))
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


EVENTS = threading.Condition()                                         # waiting /api/events requests sleep here until push() wakes them


def push(member_id, kind, payload):
    db.run(insert(db.events).values(member_id=member_id, type=kind, payload=json.dumps(payload, ensure_ascii=False), created=time.time()))
    with EVENTS:
        EVENTS.notify_all()
    try:                                                              # a phone or browser with the app closed still hears about the things that matter
        note = web_note(kind, payload)
        if note:
            send_web_push(member_id, note)
    except Exception:
        pass


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
         "screentime": "see their screen time", "critical": "ring through silent with a Critical Check-In"}


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
           "avatar": (f"/api/avatar/{m['id']}?v={int(m['avatar_at'] or 0)}" if m["avatar"] else None), "status": status_view(m), "birthday": m["birthday"] or None, "app_version": m.get("app_version") or 0,
           "age": age_of(m["birthday"], m.get("birth_year") or 0)}
    now = time.time()
    if (m.get("sos_until") or 0) > now:
        out["sos"] = True
    if (m.get("eta_until") or 0) > now and m.get("eta_min"):
        out["eta"] = {"min": m["eta_min"], "until": m["eta_until"]}
    if m["role"] == "child" and viewer and (viewer["id"] == m["id"] or viewer["role"] == "parent"):
        out["safe_dns"] = m.get("safe_dns") or ""
        out["speed_limit"] = m.get("speed_limit") or 0
        out["bedtime"] = {"from": m.get("bed_from", -1) if m.get("bed_from") is not None else -1, "to": m.get("bed_to", -1) if m.get("bed_to") is not None else -1}
    if m.get("walk_to") and (m.get("walk_until") or 0) > now - 3 * 3600:
        wp = db.one(select(db.places).where(db.places.c.id == m["walk_to"]))
        if wp:
            out["walk"] = {"place": wp["name"], "emoji": wp["emoji"], "until": m["walk_until"], "started": m.get("walk_started") or 0}
    if viewer and viewer["id"] == m["id"] and (m.get("critical_at") or 0) > now - 3600:
        by = db.one(select(db.members).where(db.members.c.id == m["critical_by"])) if m.get("critical_by") else None
        out["critical"] = {"at": m["critical_at"], "by": by["name"] if by else "A parent"}
    if (state == "on" or out.get("sos")) and m["last_lat"] is not None and not (gated and not perms["location"] and not out.get("sos")):
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
    notice = None
    if fam["notice"] and (fam["notice_until"] or 0) > time.time():
        by = db.one(select(db.members).where(db.members.c.id == fam["notice_by"]))
        notice = {"text": fam["notice"], "by": by["name"] if by else "A parent", "until": fam["notice_until"]}
    return {"id": fam["id"], "name": fam["name"], "notice": notice}


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
    if "safe_dns" in b:                                                  # what the phone reports about its private DNS filter: a host name, or "off"
        vals["safe_dns"] = clean(b.get("safe_dns"), 80) or "off"
        vals["safe_dns_at"] = time.time()
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
                   places=[{**{k: p[k] for k in ("id", "name", "emoji", "lat", "lng", "radius")}, "pending": bool(p.get("pending"))} for p in places], ice_servers=ice_servers(), version_code=VERSION_CODE)


@app.get("/api/config")
def config():
    return jsonify(ice_servers=ice_servers(), version_code=VERSION_CODE, version_name=VERSION_NAME, notes=CHANGELOG[0]["items"], apk_url=f"{request.host_url.rstrip('/')}/update")


# ---------------------------------------------------------------- sharing a location (always the owner's choice)
def metres(lat1, lng1, lat2, lng2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lng2 - lng1) / 2) ** 2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(a)))


def say(fid, from_id, body, kind="system", lat=None, lng=None, to_id=0):
    return db.run(insert(db.messages).values(family_id=fid, from_id=from_id, to_id=to_id, kind=kind, body=body, lat=lat, lng=lng, created=time.time()))


def check_places(m, lat, lng, acc):
    for p in db.many(select(db.places).where(db.places.c.family_id == m["family_id"])):
        if p.get("pending"):
            continue
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
        db.run(insert(db.visits).values(family_id=m["family_id"], member_id=m["id"], place=p["name"], emoji=p["emoji"], kind="arrive" if arrived else "leave", at=time.time()))
        if arrived:
            place_reached(m, p)
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
    now = time.time()
    sos = (m.get("sos_until") or 0) > now
    battery = int(b["battery"]) if str(b.get("battery", "")).lstrip("-").isdigit() else -1
    if state != "on" and not sos:
        return jsonify(ok=True, shared=False, share=state, **send_every(battery, False))
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
    if sos or moved > 25 or now - (m["last_at"] or 0) > 60:
        push_family(m["family_id"], "location", {"member": m["id"], "lat": lat, "lng": lng, "acc": acc, "at": now, "battery": battery, "sos": sos}, skip=m["id"])
    check_places(m, lat, lng, acc)
    if int(now) % 50 == 0:
        db.run(delete(db.locations).where(db.locations.c.at < now - KEEP_LOCATIONS_DAYS * 86400))
    check_speed(m, float(b.get("speed") or 0))
    return jsonify(ok=True, shared=True, **send_every(battery, sos, bool(m.get("walk_to"))))


def send_every(battery, sos, walking=False):
    """How often the phone should send its location: every 10 seconds during an SOS, every 15 minutes when the battery is under 20%, otherwise every 2 minutes."""
    if sos:
        return {"interval": 10, "mode": "sos"}
    if walking:
        return {"interval": 15, "mode": "sos"}
    if 0 <= battery < 20:
        return {"interval": 900, "mode": "saver"}
    return {"interval": 120, "mode": "normal"}


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


# ---------------------------------------------------------------- photos in the family chat
IMAGE_LIMIT, IMAGES_PER_FAMILY = 900 * 1024, 600                      # the phone shrinks a photo to about 200 KB first; this is the ceiling
IMAGE_MAGIC = {b"\xff\xd8\xff": "image/jpeg", b"\x89PNG": "image/png"}


@app.post("/api/images")
@need
def upload_image():
    """A photo for the family chat. It is kept under a long random address, and only a message in this family points at it. The oldest ones go once a family has 300."""
    if limited(f"img:{g.m['id']}", 40):
        return err("Slow down a little.", 429)
    raw = str((request.get_json(silent=True) or {}).get("image") or "")
    try:
        blob = _b64.b64decode(raw.split(",", 1)[-1], validate=True)
    except Exception:
        return err("That photo did not come through. Try another one.")
    if not blob or len(blob) > IMAGE_LIMIT:
        return err("That photo is too big. Pick a smaller one.")
    mime = next((m for magic, m in IMAGE_MAGIC.items() if blob.startswith(magic)), None)
    if not mime:
        return err("That is not a photo Homebase can use (use a normal JPEG or PNG).")
    fid, t = g.m["family_id"], db.message_images.c
    token = secrets.token_urlsafe(16)
    db.run(insert(db.message_images).values(token=token, family_id=fid, from_id=g.m["id"], mime=mime, data=_b64.b64encode(blob).decode(), created=time.time()))
    old = db.many(select(t.id).where(t.family_id == fid).order_by(t.id.desc()).offset(IMAGES_PER_FAMILY))
    if old:
        db.run(delete(db.message_images).where(t.id.in_([r["id"] for r in old])))
    return jsonify(url=f"/api/img/{token}")


@app.get("/api/img/<token>")
def get_image(token):
    """The picture behind a photo message. The address is the key (it cannot be guessed), so a plain <img> tag or an image loader can show it."""
    row = db.one(select(db.message_images).where(db.message_images.c.token == token[:40]))
    if not row:
        return err("No photo.", 404)
    return app.response_class(_b64.b64decode(row["data"]), mimetype=row["mime"], headers={"Cache-Control": "private, max-age=604800"})


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
            "done_by": c["done_by"], "done_at": c["done_at"], "approved_at": c["approved_at"], "created": c["created"],
            "repeats": c.get("repeats") or "", "opens_at": c.get("opens_at") or 0}


def next_midnight(tz_offset, now=None):
    """The next midnight on someone's clock (tz_offset is minutes, as the phone reports it: UTC minus local)."""
    now = now or time.time()
    local_day = int((now / 60 - (tz_offset or 0)) // 1440)
    return ((local_day + 1) * 1440 + (tz_offset or 0)) * 60


def chore_streaks(fid):
    """For each child: how many days in a row (counting today, or yesterday if today is not done yet) have at least one approved job."""
    now, kids = time.time(), {m["id"]: m for m in family_members(fid) if m["role"] == "child"}
    days = {i: set() for i in kids}
    for c in db.many(select(db.chores).where(db.chores.c.family_id == fid, db.chores.c.status == "approved", db.chores.c.approved_at >= now - 60 * 86400)):
        k = kids.get(c["done_by"])
        if k:
            days[k["id"]].add(int((c["approved_at"] / 60 - (k["tz_offset"] or 0)) // 1440))
    out = {}
    for i, ds in days.items():
        today = int((now / 60 - (kids[i]["tz_offset"] or 0)) // 1440)
        n, d = 0, (today if today in ds else today - 1)
        while d in ds:
            n, d = n + 1, d - 1
        out[i] = n
    return out


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
    now = time.time()
    open_rows = [c for c in rows if (c["status"] != "approved" or c["approved_at"] >= week) and (g.m["role"] == "parent" or (c["opens_at"] or 0) <= now)][:60]
    return jsonify(chores=[chore_view(c) for c in open_rows], points=chore_points(g.m["family_id"]), week=chore_points(g.m["family_id"], week), streaks=chore_streaks(g.m["family_id"]))


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
    repeats = b.get("repeat") if b.get("repeat") in ("daily", "weekly") else ""
    cid = db.run(insert(db.chores).values(family_id=g.m["family_id"], title=title, emoji=clean(b.get("emoji"), 8) or "🧹", points=pts, to_id=to, created_by=g.m["id"], created=time.time(), repeats=repeats, opens_at=0))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} added a job: {title} ({pts} points{', ' + repeats if repeats else ''})")
    push_family(g.m["family_id"], "chores", {"id": cid})
    return jsonify(id=cid)


@app.post("/api/chores/<int:cid>/done")
@need
def chore_done(cid):
    c = own_chore(cid)
    if not c or c["status"] != "open":
        return err("That job is not open.", 404)
    if (c["opens_at"] or 0) > time.time():
        return err("That job is not open yet.", 404)
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
        if c.get("repeats") in ("daily", "weekly"):                    # it comes back: tomorrow (daily) or a week from today (weekly), never straight away, so the points cannot be farmed
            back = next_midnight((who or {}).get("tz_offset", 0)) + (6 * 86400 if c["repeats"] == "weekly" else 0)
            db.run(insert(db.chores).values(family_id=c["family_id"], title=c["title"], emoji=c["emoji"], points=c["points"], to_id=c["to_id"], created_by=c["created_by"],
                                            created=time.time(), repeats=c["repeats"], opens_at=back))
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
def kid_today(m):
    """The day the child's own device is on right now (devices send their own date), else the day on the server by their clock."""
    row = db.one(select(db.screentime.c.day, db.screentime.c.updated).where(db.screentime.c.member_id == m["id"]).order_by(db.screentime.c.updated.desc()).limit(1))
    if row and time.time() - (row["updated"] or 0) < 6 * 3600:
        return row["day"]
    return time.strftime("%Y-%m-%d", time.gmtime(time.time() - (m["tz_offset"] or 0) * 60))


def bonus_minutes(member_id, day):
    return sum(r["minutes"] for r in db.many(select(db.bonus).where(db.bonus.c.member_id == member_id, db.bonus.c.day == day)))


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
    rows = db.many(select(db.messages).where(db.messages.c.kind.in_(["system", "sos", "late", "checkin", "pickup"])).order_by(db.messages.c.id.desc()).limit(60))
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
    base = limit_of(g.m)
    lim = (base + (bonus_minutes(g.m["id"], day) if base else 0)) * 60                       # a reward's extra minutes count, but only when a limit is set
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
KINDS = ("text", "checkin", "late", "sos", "heart", "pickup", "gif", "image", "voice")


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
    if kind == "gif":
        body = gif_url(body)
        if not body:
            return err("That is not a GIF link we can show. Pick a sticker, or paste a link ending in .gif from Giphy, Tenor or Imgur.")
    if kind == "image":                                                # a photo uploaded to /api/images by someone in this family
        token = body.rsplit("/", 1)[-1] if body.startswith("/api/img/") else ""
        if not token or not db.one(select(db.message_images.c.id).where(db.message_images.c.token == token, db.message_images.c.family_id == m["family_id"])):
            return err("That photo is not available. Send it again.")
        body = f"/api/img/{token}"
    if kind == "voice":                                                # a voice note uploaded to /api/audio by someone in this family
        token = body.rsplit("/", 1)[-1] if body.startswith("/api/img/") else ""
        row_ = db.one(select(db.message_images).where(db.message_images.c.token == token, db.message_images.c.family_id == m["family_id"])) if token else None
        if not row_ or not str(row_["mime"]).startswith("audio/"):
            return err("That voice note is not available. Record it again.")
        body = f"/api/img/{token}"
    if to_id and kind in ("text", "heart", "gif", "image", "voice") and not parent_may(m, same_family(to_id), "messages"):
        return denied(same_family(to_id), "messages")
    if limited(f"msg:{m['id']}", 120):
        return err("Slow down a little.", 429)
    lat = float(b["lat"]) if kind in ("checkin", "sos", "pickup", "late") and b.get("lat") is not None else None
    lng = float(b["lng"]) if kind in ("checkin", "sos", "pickup", "late") and b.get("lng") is not None else None
    if kind == "late":
        body = late_with_eta(m, body, lat, lng)
    if kind == "sos":
        db.run(update(db.members).where(db.members.c.id == m["id"]).values(sos_until=time.time() + SOS_MINUTES * 60))
    if kind == "checkin":                                              # "I'm OK" ends an SOS and answers a Critical Check-In
        db.run(update(db.members).where(db.members.c.id == m["id"]).values(sos_until=0, critical_at=0))
        if m.get("critical_at"):
            for p in family_members(m["family_id"]):
                if p["role"] == "parent":
                    push(p["id"], "critical_ok", {"name": m["name"], "member": m["id"]})
    if kind == "sos":
        body = body or "I need help"
    if kind == "pickup":                                               # "come and get me": goes to the whole family, with where I am if I chose to send it
        body = body or "Can someone pick me up?"
        to_id = 0
    mid = say(m["family_id"], m["id"], body, kind=kind, lat=lat, lng=lng, to_id=to_id)
    row = db.one(select(db.messages).where(db.messages.c.id == mid))
    if kind == "image" and not to_id:                                 # family-chat photos go into the album by themselves (private ones do not)
        db.run(insert(db.album).values(family_id=m["family_id"], token=body.rsplit("/", 1)[-1], by=m["id"], caption="", created=time.time()))
    support = None
    if kind == "text":
        if to_id:
            kids = [same_family(to_id)] if same_family(to_id) and same_family(to_id)["role"] == "child" else []
        else:
            kids = [x for x in family_members(m["family_id"]) if x["role"] == "child" and x["id"] != m["id"]]
        support = risk_check(m, body, "the family chat" if not to_id else "a private message", kids)
    for x in family_members(m["family_id"]):
        if x["id"] == m["id"] or (to_id and x["id"] != to_id):
            continue
        if kind in ("text", "heart", "gif", "image", "voice") and not parent_may(m, x, "messages"):
            continue
        push(x["id"], "message", {**msg_view(row), "name": m["name"], "emoji": m["emoji"], "quiet": in_quiet(x) and kind not in ("sos", "pickup"), "urgent": kind == "sos"})
    return jsonify(message=msg_view(row), support=support)


# ---------------------------------------------------------------- "running late" works out how far from Home you are
SOS_MINUTES = 30


def late_with_eta(m, body, lat, lng):
    """With a location, "late" says roughly how long until home: the distance to the Home place (with a bit added for roads) at walking pace, or at the speed the phone is moving."""
    home = home_place(m["family_id"])
    if lat is None or lng is None or not home:
        return body or "Running late"
    dist = metres(lat, lng, home["lat"], home["lng"])
    last = db.one(select(db.locations).where(db.locations.c.member_id == m["id"], db.locations.c.at > time.time() - 300).order_by(db.locations.c.at.desc()).limit(1))
    speed = last["speed"] if last and (last["speed"] or 0) >= 1.5 else 1.4                 # metres a second: walking unless the phone says it is moving faster
    mins = max(1, math.ceil(dist * 1.3 / speed / 60)) if dist > (home["radius"] or 150) else 0
    if not mins:
        return body or "Running late, but almost home"
    db.run(update(db.members).where(db.members.c.id == m["id"]).values(eta_min=mins, eta_until=time.time() + mins * 60 + 600))
    push_family(m["family_id"], "member", {"id": m["id"]}, skip=m["id"])
    where = f"about {mins} min from home ({dist / 1000:.1f} km)"
    return f"{body} · {where}" if body else f"Running late · {where}"


# ---------------------------------------------------------------- safety alerts: phrases that look like bullying, self-harm, adult content or grooming
RISK_PATTERNS = {
    "self_harm": r"\b(kill(ing)? myself|want(ed)? to die|end (it all|my life)|suicid\w*|self[- ]?harm\w*|cut(ting)? myself|hurt(ing)? myself|no reason to live|better off (dead|without me)|don'?t want to (live|be here))\b",
    "bullying": r"\b(kill yourself|kys|go die|nobody (likes|wants) you|everyone hates you|you'?re (so )?(ugly|fat|worthless|pathetic|a loser|disgusting)|you should (die|disappear))\b",
    "adult": r"\b(nudes?|send (me )?(nudes|pics of you|a pic of you)|sexy (pic|photo)s?|naked|porn\w*|onlyfans|sext\w*)\b",
    "grooming": r"\b(don'?t tell (your )?(mum|mom|dad|parents|anyone)|(our|a) (little )?secret|delete (this|these|our|the) (chat|messages?|texts?)|are you (home )?alone|how old are you|meet (me )?(up )?(alone|in person|somewhere)|what'?s your (home )?address|i'?ll buy you|(add|move|switch|talk) (me )?(on|to) (snap(chat)?|whatsapp|insta(gram)?|telegram|discord|kik))\b",
}
RISK_NAMES = {"self_harm": "signs of self-harm", "bullying": "bullying", "adult": "adult content", "grooming": "grooming patterns"}
SUPPORT = ("It sounds like things might be really hard right now. You are not alone. Please talk to a parent or another adult you trust. "
           "If you are in danger right now, call your local emergency number. You can also call or text a free helpline (for example 988 in the US and Canada, or Childline 0800 1111 in the UK).")


def risk_scan(text):
    t = str(text or "").lower()
    return [cat for cat, pat in RISK_PATTERNS.items() if re.search(pat, t)]


def risk_check(sender, text, where, kids_involved):
    """Tells a child's parents when a message to or from that child looks dangerous. Only the message that matched is shown to them, never a log of everything.
    Returns a kind support note for a child who wrote something that sounds like self-harm."""
    cats = risk_scan(text)
    if not cats:
        return None
    now = time.time()
    people = []
    if sender["role"] == "child":
        people.append((sender, "sent", ""))
    for k in kids_involved:
        if k and k["id"] != sender["id"] and k["role"] == "child":
            people.append((k, "received", sender["name"]))
    for kid, role, other in people:
        for cat in cats:
            if cat == "self_harm" and role != "sent":
                continue                                               # someone else's worries are theirs; they are told by their own family
            t = db.risk_alerts.c
            recent = db.one(select(db.risk_alerts).where(t.member_id == kid["id"], t.cat == cat, t.created > now - 600))
            aid = db.run(insert(db.risk_alerts).values(family_id=kid["family_id"], member_id=kid["id"], cat=cat, role=role, other=other[:40], where=where[:60],
                                                       snippet=str(text)[:280], created=now))
            if recent:
                continue                                               # one alert at a time for the same thing
            for p in family_members(kid["family_id"]):
                if p["role"] == "parent":
                    push(p["id"], "risk_alert", {"id": aid, "member": kid["id"], "name": kid["name"], "cat": cat, "what": RISK_NAMES[cat], "role": role, "other": other, "where": where})
    return SUPPORT if sender["role"] == "child" and "self_harm" in cats else None


@app.get("/api/risk")
@parent_only
def risk_list():
    t = db.risk_alerts.c
    rows = db.many(select(db.risk_alerts).where(t.family_id == g.m["family_id"]).order_by(t.id.desc()).limit(60))
    names = {x["id"]: x["name"] for x in family_members(g.m["family_id"])}
    return jsonify(alerts=[{"id": r["id"], "member": r["member_id"], "name": names.get(r["member_id"], ""), "cat": r["cat"], "what": RISK_NAMES.get(r["cat"], r["cat"]),
                            "role": r["role"], "other": r["other"], "where": r["where"], "snippet": r["snippet"], "at": r["created"], "seen": bool(r["seen"])} for r in rows])


@app.post("/api/risk/<int:aid>/seen")
@parent_only
def risk_seen(aid):
    db.run(update(db.risk_alerts).where(db.risk_alerts.c.id == aid, db.risk_alerts.c.family_id == g.m["family_id"]).values(seen=True))
    return jsonify(ok=True)


# ---------------------------------------------------------------- Critical Check-In: a parent's request that rings through silent until the child taps "I'm OK"
@app.post("/api/members/<int:mid>/critical")
@parent_only
def critical_checkin(mid):
    kid = same_family(mid)
    if not kid or kid["role"] != "child":
        return err("Not found.", 404)
    if not parent_may(g.m, kid, "critical"):
        return denied(kid, "critical")
    if limited(f"critical:{g.m['id']}", 6):
        return err("Slow down a little.", 429)
    db.run(update(db.members).where(db.members.c.id == mid).values(critical_at=time.time(), critical_by=g.m["id"]))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} sent {kid['name']} a Critical Check-In")
    push(mid, "critical", {"name": g.m["name"], "by": g.m["id"]})
    return jsonify(ok=True)


@app.post("/api/critical/ack")
@need
def critical_ack():
    m = g.m
    if not m.get("critical_at"):
        return jsonify(ok=True, was=False)
    db.run(update(db.members).where(db.members.c.id == m["id"]).values(critical_at=0))
    for p in family_members(m["family_id"]):
        if p["role"] == "parent":
            push(p["id"], "critical_ok", {"name": m["name"], "member": m["id"]})
    return jsonify(ok=True, was=True)


@app.post("/api/sos/clear")
@need
def sos_clear():
    db.run(update(db.members).where(db.members.c.id == g.m["id"]).values(sos_until=0))
    push_family(g.m["family_id"], "member", {"id": g.m["id"]}, skip=g.m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- a phone that goes quiet: the parents are told once, and again when it is back
OFFLINE_AFTER, _last_offline = 20 * 60, [0.0]


def check_offline():
    now = time.time()
    if now - _last_offline[0] < 60:
        return
    _last_offline[0] = now
    t = db.members.c
    for kid in db.many(select(db.members).where(t.role == "child", t.app_version > 0)):
        seen = kid["last_seen"] or 0
        if not kid["offline_told"] and now - OFFLINE_AFTER > seen > now - 86400:
            db.run(update(db.members).where(t.id == kid["id"]).values(offline_told=True))
            for p in family_members(kid["family_id"]):
                if p["role"] == "parent":
                    push(p["id"], "offline", {"member": kid["id"], "name": kid["name"], "minutes": int((now - seen) // 60), "battery": kid["battery"]})
        elif kid["offline_told"] and seen > now - 300:
            db.run(update(db.members).where(t.id == kid["id"]).values(offline_told=False))
            for p in family_members(kid["family_id"]):
                if p["role"] == "parent":
                    push(p["id"], "online", {"member": kid["id"], "name": kid["name"]})


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
@need
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
    child = m["role"] != "parent"
    pid = db.run(insert(db.places).values(family_id=m["family_id"], name=name, emoji=clean(b.get("emoji"), 8) or "📍", lat=lat, lng=lng, radius=radius, created_by=m["id"], created=time.time(),
                                          pending=child))
    if child:                                                          # a child asks; a parent says yes before anyone is told about arrivals there
        for p in family_members(m["family_id"]):
            if p["role"] == "parent":
                push(p["id"], "zone_request", {"id": pid, "name": m["name"], "place": name})
        push_family(m["family_id"], "places", {"id": pid, "pending": True})
        return jsonify(id=pid, pending=True)
    say(m["family_id"], m["id"], f"{m['name']} added the place {name}. Everyone is told when someone arrives or leaves.")
    push_family(m["family_id"], "places", {"id": pid, "name": name, "by": m["name"]})
    return jsonify(id=pid, pending=False)


@app.post("/api/places/<int:pid>/approve")
@parent_only
def approve_place(pid):
    p = db.one(select(db.places).where(db.places.c.id == pid, db.places.c.family_id == g.m["family_id"]))
    if not p or not p["pending"]:
        return err("Nothing to answer.", 404)
    ok = bool((request.get_json(silent=True) or {}).get("ok", True))
    asker = same_family(p["created_by"])
    if ok:
        db.run(update(db.places).where(db.places.c.id == pid).values(pending=False))
        say(g.m["family_id"], g.m["id"], f"{g.m['name']} added the place {p['name']}" + (f" ({asker['name']} asked)" if asker else "") + ". Everyone is told when someone arrives or leaves.")
    else:
        db.run(delete(db.places).where(db.places.c.id == pid))
    if asker:
        push(asker["id"], "zone_ok", {"place": p["name"], "approved": ok, "name": g.m["name"]})
    push_family(g.m["family_id"], "places", {"id": pid}, skip=g.m["id"])
    return jsonify(ok=True, approved=ok)


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


@app.post("/api/list/clear")
@need
def clear_done_items():
    """Takes everything that has been ticked off the list. Items still to do are never touched."""
    t = db.lists.c
    n = len(db.many(select(t.id).where(t.family_id == g.m["family_id"], t.done == True)))  # noqa: E712
    if n:
        db.run(delete(db.lists).where(t.family_id == g.m["family_id"], t.done == True))  # noqa: E712
        push_family(g.m["family_id"], "list", {"cleared": n}, skip=g.m["id"])
    return jsonify(ok=True, cleared=n)


@app.delete("/api/list/<int:iid>")
@need
def drop_item(iid):
    r = db.one(select(db.lists).where(db.lists.c.id == iid, db.lists.c.family_id == g.m["family_id"]))
    if not r or not (r["added_by"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    db.run(delete(db.lists).where(db.lists.c.id == iid))
    push_family(g.m["family_id"], "list", {"id": iid}, skip=g.m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- plans: the family calendar
PLAN_GRACE = 3 * 3600          # a plan stays on the list for a few hours after it starts
PLAN_SOON = 30 * 60            # "starts soon" nudge
_last_remind = [0.0]


def json_dict(text):
    try:
        d = json.loads(text or "{}")
    except (ValueError, TypeError):
        return {}
    return d if isinstance(d, dict) else {}


def plan_view(r, me):
    rs = {str(k): v for k, v in json_dict(r["rsvp"]).items() if v in ("in", "out")}
    return {"id": r["id"], "title": r["title"], "emoji": r["emoji"], "start": r["start"], "note": r["note"], "by": r["created_by"],
            "in": [int(k) for k, v in rs.items() if v == "in" and k.isdigit()], "out": [int(k) for k, v in rs.items() if v == "out" and k.isdigit()], "mine": rs.get(str(me)),
            "lat": r.get("lat"), "lng": r.get("lng"), "via": r.get("via") or ""}


@app.get("/api/plans")
@need
def get_plans():
    q = db.plans.c
    if request.args.get("from") and request.args.get("to"):               # the calendar: one stretch of days, past or future
        try:
            a, b = float(request.args["from"]), float(request.args["to"])
        except ValueError:
            return err("Pick a range.")
        if not 0 < b - a <= 70 * 86400:
            return err("Pick up to two months at a time.")
        rows = db.many(select(db.plans).where(q.family_id == g.m["family_id"], q.start >= a, q.start < b).order_by(q.start).limit(300))
    else:
        rows = db.many(select(db.plans).where(q.family_id == g.m["family_id"], q.start >= time.time() - PLAN_GRACE).order_by(q.start).limit(60))
    return jsonify(plans=[plan_view(r, g.m["id"]) for r in rows])


@app.post("/api/plans")
@need
def add_plan():
    d = request.get_json(silent=True) or {}
    title = clean(d.get("title"), 60)
    if not title:
        return err("Give the plan a name first.")
    try:
        start = float(d.get("start"))
    except (TypeError, ValueError):
        return err("Pick a day and a time.")
    now = time.time()
    if not (now - 86400 <= start <= now + 2 * 365 * 86400):
        return err("That day is too far away.")
    fid = g.m["family_id"]
    if len(db.many(select(db.plans.c.id).where(db.plans.c.created_by == g.m["id"], db.plans.c.created > now - 3600))) >= 20:
        return err("Slow down a little.", 429)
    db.run(delete(db.plans).where(db.plans.c.family_id == fid, db.plans.c.start < now - 60 * 86400))
    pid = db.run(insert(db.plans).values(family_id=fid, title=title, emoji=clean(d.get("emoji"), 8) or "📅", start=start, note=clean(d.get("note"), 120), created_by=g.m["id"],
                                         created=now, rsvp=json.dumps({str(g.m["id"]): "in"}), reminded=False))
    push_family(fid, "plan", {"id": pid, "new": True, "name": g.m["name"], "title": title, "emoji": clean(d.get("emoji"), 8) or "📅", "start": start}, skip=g.m["id"])
    return jsonify(id=pid)


@app.post("/api/plans/<int:pid>/rsvp")
@need
def rsvp_plan(pid):
    r = db.one(select(db.plans).where(db.plans.c.id == pid, db.plans.c.family_id == g.m["family_id"]))
    if not r:
        return err("Not found.", 404)
    answer = (request.get_json(silent=True) or {}).get("answer")
    rs = json_dict(r["rsvp"])
    if answer in ("in", "out"):
        rs[str(g.m["id"])] = answer
    else:
        rs.pop(str(g.m["id"]), None)
    db.run(update(db.plans).where(db.plans.c.id == pid).values(rsvp=json.dumps(rs)))
    push_family(g.m["family_id"], "plan", {"id": pid}, skip=g.m["id"])
    return jsonify(plan=plan_view({**r, "rsvp": json.dumps(rs)}, g.m["id"]))


@app.delete("/api/plans/<int:pid>")
@need
def drop_plan(pid):
    r = db.one(select(db.plans).where(db.plans.c.id == pid, db.plans.c.family_id == g.m["family_id"]))
    if not r or not (r["created_by"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    db.run(delete(db.plans).where(db.plans.c.id == pid))
    push_family(g.m["family_id"], "plan", {"id": pid, "removed": True}, skip=g.m["id"])
    return jsonify(ok=True)


def remind_plans():
    """A plan that starts within half an hour tells everyone who has not said "can't". Runs from the event stream, at most every 20 seconds."""
    now = time.time()
    if now - _last_remind[0] < 20:
        return
    _last_remind[0] = now
    for r in db.many(select(db.plans).where(db.plans.c.reminded == False, db.plans.c.start <= now + PLAN_SOON, db.plans.c.start > now - 600)):  # noqa: E712
        db.run(update(db.plans).where(db.plans.c.id == r["id"]).values(reminded=True))
        out = {k for k, v in json_dict(r["rsvp"]).items() if v == "out"}
        for m in family_members(r["family_id"]):
            if str(m["id"]) not in out:
                push(m["id"], "plan_soon", {"id": r["id"], "title": r["title"], "emoji": r["emoji"], "start": r["start"]})


# ---------------------------------------------------------------- polls: a question for the family
def poll_view(r, me):
    try:
        opts = [str(o) for o in json.loads(r["options"] or "[]")]
    except (ValueError, TypeError):
        opts = []
    who = [[] for _ in opts]
    mine = None
    for k, v in json_dict(r["votes"]).items():
        if k.isdigit() and isinstance(v, int) and 0 <= v < len(opts):
            who[v].append(int(k))
            if int(k) == me:
                mine = v
    return {"id": r["id"], "question": r["question"], "options": opts, "votes": who, "mine": mine, "by": r["created_by"], "closed": bool(r["closed"]), "at": r["created"]}


@app.get("/api/polls")
@need
def get_polls():
    rows = db.many(select(db.polls).where(db.polls.c.family_id == g.m["family_id"]).order_by(db.polls.c.closed, db.polls.c.id.desc()).limit(20))
    return jsonify(polls=[poll_view(r, g.m["id"]) for r in rows])


@app.post("/api/polls")
@need
def add_poll():
    d = request.get_json(silent=True) or {}
    question = clean(d.get("question"), 100)
    opts = []
    for o in (d.get("options") if isinstance(d.get("options"), list) else []):
        o = clean(o, 40)
        if o and o.lower() not in [x.lower() for x in opts]:
            opts.append(o)
    if not question:
        return err("Write the question first.")
    if not 2 <= len(opts) <= 4:
        return err("Give two to four different answers.")
    fid, now = g.m["family_id"], time.time()
    if len(db.many(select(db.polls.c.id).where(db.polls.c.created_by == g.m["id"], db.polls.c.created > now - 3600))) >= 10:
        return err("Slow down a little.", 429)
    db.run(delete(db.polls).where(db.polls.c.family_id == fid, db.polls.c.created < now - 30 * 86400))
    pid = db.run(insert(db.polls).values(family_id=fid, question=question, options=json.dumps(opts, ensure_ascii=False), votes="{}", created_by=g.m["id"], created=now, closed=False))
    push_family(fid, "poll", {"id": pid, "new": True, "name": g.m["name"], "question": question}, skip=g.m["id"])
    return jsonify(id=pid)


@app.post("/api/polls/<int:pid>/vote")
@need
def vote_poll(pid):
    r = db.one(select(db.polls).where(db.polls.c.id == pid, db.polls.c.family_id == g.m["family_id"]))
    if not r:
        return err("Not found.", 404)
    if r["closed"]:
        return err("This vote is closed.")
    n = len(json.loads(r["options"] or "[]"))
    opt = (request.get_json(silent=True) or {}).get("option")
    if not isinstance(opt, int) or isinstance(opt, bool) or not 0 <= opt < n:
        return err("Pick one of the answers.")
    votes = json_dict(r["votes"])
    if votes.get(str(g.m["id"])) == opt:
        votes.pop(str(g.m["id"]), None)                                  # tap your answer again to take the vote back
    else:
        votes[str(g.m["id"])] = opt
    db.run(update(db.polls).where(db.polls.c.id == pid).values(votes=json.dumps(votes)))
    push_family(g.m["family_id"], "poll", {"id": pid}, skip=g.m["id"])
    return jsonify(poll=poll_view({**r, "votes": json.dumps(votes)}, g.m["id"]))


@app.post("/api/polls/<int:pid>/close")
@need
def close_poll(pid):
    r = db.one(select(db.polls).where(db.polls.c.id == pid, db.polls.c.family_id == g.m["family_id"]))
    if not r or not (r["created_by"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    db.run(update(db.polls).where(db.polls.c.id == pid).values(closed=True))
    push_family(g.m["family_id"], "poll", {"id": pid}, skip=g.m["id"])
    return jsonify(ok=True)


@app.delete("/api/polls/<int:pid>")
@need
def drop_poll(pid):
    r = db.one(select(db.polls).where(db.polls.c.id == pid, db.polls.c.family_id == g.m["family_id"]))
    if not r or not (r["created_by"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    db.run(delete(db.polls).where(db.polls.c.id == pid))
    push_family(g.m["family_id"], "poll", {"id": pid, "removed": True}, skip=g.m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- rewards: what chore points can buy
def spent_points(fid):
    """Points a child has spent or is holding for a request a parent has not answered yet."""
    s = {}
    for r in db.many(select(db.redemptions).where(db.redemptions.c.family_id == fid, db.redemptions.c.status.in_(["pending", "approved"]))):
        s[r["member_id"]] = s.get(r["member_id"], 0) + r["cost"]
    return s


def balances(fid):
    earned, spent = chore_points(fid), spent_points(fid)
    return {i: earned.get(i, 0) - spent.get(i, 0) for i in set(earned) | set(spent)}


def reward_view(r):
    return {"id": r["id"], "title": r["title"], "emoji": r["emoji"], "cost": r["cost"], "minutes": r["minutes"] or 0}


def redemption_view(r):
    return {"id": r["id"], "member": r["member_id"], "title": r["title"], "emoji": r["emoji"], "cost": r["cost"], "minutes": r["minutes"] or 0, "status": r["status"], "at": r["created"], "decided_at": r["decided_at"]}


@app.get("/api/rewards")
@need
def get_rewards():
    fid, m = g.m["family_id"], g.m
    rewards = db.many(select(db.rewards).where(db.rewards.c.family_id == fid).order_by(db.rewards.c.cost, db.rewards.c.id))
    asks = db.many(select(db.redemptions).where(db.redemptions.c.family_id == fid).order_by(db.redemptions.c.id.desc()).limit(40))
    if m["role"] != "parent":                                          # a child sees their own requests, a parent sees everyone's
        asks = [r for r in asks if r["member_id"] == m["id"]]
    bal = balances(fid)
    kids = [k for k in family_members(fid) if k["role"] == "child" and (m["role"] == "parent" or k["id"] == m["id"])]
    extra = {}                                                          # extra screen-time minutes each child has earned today
    for k in kids:
        n = bonus_minutes(k["id"], kid_today(k))
        if n:
            extra[k["id"]] = n
    streaks = chore_streaks(fid)
    child = m["role"] == "child"
    return jsonify(rewards=[reward_view(r) for r in rewards], requests=[redemption_view(r) for r in asks[:25]], balance=bal, mine=bal.get(m["id"], 0) if child else None,
                   streaks=streaks, bonus=extra, my_streak=streaks.get(m["id"], 0) if child else None, my_bonus=extra.get(m["id"], 0) if child else None)


@app.post("/api/rewards")
@parent_only
def add_reward():
    b = request.get_json(silent=True) or {}
    title = clean(b.get("title"), 60)
    if not title:
        return err("What is the reward?")
    try:
        cost = int(b.get("cost"))
    except (TypeError, ValueError):
        return err("Say how many points it costs.")
    if not 1 <= cost <= 1000:
        return err("A reward costs between 1 and 1000 points.")
    try:
        minutes = int(b.get("minutes") or 0)
    except (TypeError, ValueError):
        return err("Minutes should be a number.")
    if not 0 <= minutes <= 240:
        return err("Extra screen time can be up to 240 minutes.")
    fid = g.m["family_id"]
    if len(db.many(select(db.rewards.c.id).where(db.rewards.c.family_id == fid))) >= 30:
        return err("That is plenty of rewards (30). Delete some first.")
    rid = db.run(insert(db.rewards).values(family_id=fid, title=title, emoji=clean(b.get("emoji"), 8) or "🎁", cost=cost, minutes=minutes, created_by=g.m["id"], created=time.time()))
    push_family(fid, "rewards", {"id": rid})
    return jsonify(id=rid)


@app.delete("/api/rewards/<int:rid>")
@parent_only
def drop_reward(rid):
    if not db.one(select(db.rewards).where(db.rewards.c.id == rid, db.rewards.c.family_id == g.m["family_id"])):
        return err("Not found.", 404)
    db.run(delete(db.rewards).where(db.rewards.c.id == rid))
    push_family(g.m["family_id"], "rewards", {"id": rid})
    return jsonify(ok=True)


@app.post("/api/rewards/<int:rid>/redeem")
@need
def redeem_reward(rid):
    m = g.m
    if m["role"] != "child":
        return err("Points belong to children.", 403)
    r = db.one(select(db.rewards).where(db.rewards.c.id == rid, db.rewards.c.family_id == m["family_id"]))
    if not r:
        return err("Not found.", 404)
    have = balances(m["family_id"]).get(m["id"], 0)
    if have < r["cost"]:
        return err(f"You need {r['cost'] - have} more points for that.")
    if len(db.many(select(db.redemptions.c.id).where(db.redemptions.c.member_id == m["id"], db.redemptions.c.status == "pending"))) >= 3:
        return err("You already have three waiting. Ask a parent to answer them first.")
    qid = db.run(insert(db.redemptions).values(family_id=m["family_id"], member_id=m["id"], title=r["title"], emoji=r["emoji"], cost=r["cost"], minutes=r["minutes"] or 0, status="pending", created=time.time()))
    say(m["family_id"], m["id"], f"{m['name']} asked for a reward: {r['title']} ({r['cost']} points)")
    for x in family_members(m["family_id"]):
        if x["role"] == "parent":
            push(x["id"], "reward", {"id": qid, "status": "pending", "name": m["name"], "title": r["title"], "emoji": r["emoji"], "cost": r["cost"], "minutes": r["minutes"] or 0})
    push_family(m["family_id"], "rewards", {"id": qid}, skip=m["id"])
    return jsonify(id=qid, balance=have - r["cost"])


@app.post("/api/redemptions/<int:qid>/decide")
@parent_only
def decide_redemption(qid):
    r = db.one(select(db.redemptions).where(db.redemptions.c.id == qid, db.redemptions.c.family_id == g.m["family_id"]))
    if not r or r["status"] != "pending":
        return err("Nothing to answer.", 404)
    ok = bool((request.get_json(silent=True) or {}).get("ok", True))
    status = "approved" if ok else "declined"
    db.run(update(db.redemptions).where(db.redemptions.c.id == qid).values(status=status, decided_by=g.m["id"], decided_at=time.time()))
    kid = same_family(r["member_id"])
    minutes = (r["minutes"] or 0) if ok else 0
    if minutes and kid:                                                # "extra game time": the day's limit grows by that many minutes
        db.run(insert(db.bonus).values(member_id=kid["id"], day=kid_today(kid), minutes=minutes, created=time.time()))
    spent = f"{r['cost']} points spent" + (f", +{minutes} minutes of screen time today" if minutes else "")
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} {'said yes to' if ok else 'said not now to'} {kid['name'] if kid else 'a'} reward: {r['title']}" + (f" ({spent})" if ok else ""))
    push(r["member_id"], "reward", {"id": qid, "status": status, "name": g.m["name"], "title": r["title"], "emoji": r["emoji"], "cost": r["cost"], "minutes": minutes})
    push_family(g.m["family_id"], "rewards", {"id": qid}, skip=g.m["id"])
    return jsonify(ok=True, status=status)


@app.delete("/api/redemptions/<int:qid>")
@need
def cancel_redemption(qid):
    r = db.one(select(db.redemptions).where(db.redemptions.c.id == qid, db.redemptions.c.family_id == g.m["family_id"]))
    if not r or r["member_id"] != g.m["id"] or r["status"] != "pending":
        return err("Not found.", 404)
    db.run(delete(db.redemptions).where(db.redemptions.c.id == qid))
    push_family(g.m["family_id"], "rewards", {"id": qid}, skip=g.m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- the family board: things everyone should be able to find
def board_view(r):
    return {"id": r["id"], "title": r["title"], "emoji": r["emoji"], "body": r["body"], "at": r["updated"]}


@app.get("/api/board")
@need
def get_board():
    rows = db.many(select(db.board).where(db.board.c.family_id == g.m["family_id"]).order_by(db.board.c.id))
    return jsonify(items=[board_view(r) for r in rows])


@app.post("/api/board")
@parent_only
def save_board():
    """A parent adds a note, or changes one (send its id)."""
    b = request.get_json(silent=True) or {}
    title = clean(b.get("title"), 40)
    body = str(b.get("body") or "").replace("\r", "").strip()[:600]
    if not title:
        return err("Give the note a title.")
    fid, emoji, now = g.m["family_id"], clean(b.get("emoji"), 8) or "📌", time.time()
    if str(b.get("id", "")).isdigit():
        r = db.one(select(db.board).where(db.board.c.id == int(b["id"]), db.board.c.family_id == fid))
        if not r:
            return err("Not found.", 404)
        db.run(update(db.board).where(db.board.c.id == r["id"]).values(title=title, emoji=emoji, body=body, updated_by=g.m["id"], updated=now))
        bid = r["id"]
    else:
        if len(db.many(select(db.board.c.id).where(db.board.c.family_id == fid))) >= 20:
            return err("That is plenty of notes (20). Delete some first.")
        bid = db.run(insert(db.board).values(family_id=fid, title=title, emoji=emoji, body=body, updated_by=g.m["id"], updated=now))
    push_family(fid, "board", {"id": bid}, skip=g.m["id"])
    return jsonify(id=bid)


@app.delete("/api/board/<int:bid>")
@parent_only
def drop_board(bid):
    if not db.one(select(db.board).where(db.board.c.id == bid, db.board.c.family_id == g.m["family_id"])):
        return err("Not found.", 404)
    db.run(delete(db.board).where(db.board.c.id == bid))
    push_family(g.m["family_id"], "board", {"id": bid}, skip=g.m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- GIFs in the chat
STICKER_DIR = os.path.join(BASE, "static", "stickers")
GIF_HOSTS = ("giphy.com", "tenor.com", "imgur.com", "wikimedia.org")           # where a pasted link may point (never an unknown site: it would learn who looked)


def sticker_names():
    try:
        return sorted(f[:-4] for f in os.listdir(STICKER_DIR) if f.endswith(".gif"))
    except OSError:
        return []


def gif_url(text):
    """The address to store for a GIF message, or None. A sticker of ours, or an https link to a GIF on a known host (a Giphy page link is turned into its picture)."""
    text = str(text or "").strip()
    if text.startswith("/static/stickers/") and text.endswith(".gif") and text[len("/static/stickers/"):-4] in sticker_names():
        return text
    m = re.match(r"^https://([a-z0-9.-]+)(/[^\s]*)?$", text, re.I)
    if not m or len(text) > 400:
        return None
    host, path = m.group(1).lower(), (m.group(2) or "")
    if not any(host == h or host.endswith("." + h) for h in GIF_HOSTS):
        return None
    if host in ("giphy.com", "www.giphy.com") and path.startswith("/gifs/"):                 # a page link: the last part of it ends with the picture's id
        gid = path.split("?")[0].rstrip("/").rsplit("-", 1)[-1].rsplit("/", 1)[-1]
        return f"https://media.giphy.com/media/{gid}/giphy.gif" if re.fullmatch(r"[A-Za-z0-9]{6,30}", gid) else None
    return text if re.search(r"\.(gif|webp)(\?[^\s]*)?$", path, re.I) else None


@app.get("/api/stickers")
def get_stickers():
    return jsonify(stickers=[{"name": n, "url": f"/static/stickers/{n}.gif"} for n in sticker_names()])


# ---------------------------------------------------------------- friend groups: separate from the family, but a child's parents can read them
GROUP_LIMIT, GROUP_SIZE = 8, 20
MUTES = {"slur": 10 * 60, "strikes": 15 * 60}                          # a slur is a 10 minute break at once; three rude messages in ten minutes is 15


def group_row(gid):
    return db.one(select(db.friend_groups).where(db.friend_groups.c.id == gid))


def membership(gid, member_id):
    t = db.friend_members.c
    return db.one(select(db.friend_members).where(t.group_id == gid, t.member_id == member_id))


def kids_in_my_family(m):
    return [k for k in family_members(m["family_id"]) if k["role"] == "child"] if m["role"] == "parent" else []


def overseen_children(gid, m):
    """The children of this parent's family who are in the group (so the parent may read it)."""
    kids = {k["id"]: k for k in kids_in_my_family(m)}
    if not kids:
        return []
    t = db.friend_members.c
    return [kids[r["member_id"]] for r in db.many(select(t).where(t.group_id == gid, t.status == "active")) if r["member_id"] in kids]


def group_access(gid, m):
    """("member" | "overseer" | "pending" | None, the membership row or the children being overseen)."""
    mine = membership(gid, m["id"])
    if mine:
        return ("member" if mine["status"] == "active" else "pending"), mine
    kids = overseen_children(gid, m)
    return ("overseer", kids) if kids else (None, None)


def group_people(gid):
    t = db.friend_members.c
    ids = [r["member_id"] for r in db.many(select(t).where(t.group_id == gid, t.status == "active").order_by(t.id))]
    people = {x["id"]: x for x in db.many(select(db.members).where(db.members.c.id.in_(ids)))} if ids else {}
    return [{"id": i, "name": people[i]["name"], "emoji": people[i]["emoji"], "role": people[i]["role"]} for i in ids if i in people]


def tell_parents(child, kind, payload):
    for p in family_members(child["family_id"]):
        if p["role"] == "parent":
            push(p["id"], kind, payload)


def group_message_view(r, viewer, people):
    sender = people.get(r["from_id"]) or {}
    flags = json.loads(r["flags"] or "[]") if r["flags"] else []
    out = {"id": r["id"], "from": r["from_id"], "name": sender.get("name", ""), "emoji": sender.get("emoji", ""), "kind": r["kind"], "body": r["body"], "at": r["created"], "flagged": bool(flags)}
    if flags:
        out["cats"] = sorted({f["cat"] for f in flags})
        if viewer["role"] == "parent" and sender.get("family_id") == viewer["family_id"]:       # only the sender's own parents see which words, and what was really typed
            out["flags"], out["original"] = flags, r["original"]
    return out


@app.get("/api/groups")
@need
def list_groups():
    m, t = g.m, db.friend_members.c
    mine = {r["group_id"]: r for r in db.many(select(t).where(t.member_id == m["id"]))}
    overseen = {}
    for kid in kids_in_my_family(m):
        for r in db.many(select(t).where(t.member_id == kid["id"], t.status == "active")):
            overseen.setdefault(r["group_id"], []).append(kid["name"])
    out = []
    for gid in sorted(set(mine) | set(overseen)):
        grp = group_row(gid)
        if not grp:
            continue
        status = mine[gid]["status"] if gid in mine else None
        last = db.one(select(db.friend_messages).where(db.friend_messages.c.group_id == gid).order_by(db.friend_messages.c.id.desc()).limit(1)) if status != "pending" else None
        people = group_people(gid)
        out.append({"id": gid, "name": grp["name"], "emoji": grp["emoji"], "status": status, "code": grp["code"] if status == "active" else None, "overseeing": overseen.get(gid, []),
                    "members": people, "last": ({"body": last["body"] if last["kind"] == "text" else "\U0001F39E\ufe0f GIF", "at": last["created"],
                                               "name": next((x["name"] for x in people if x["id"] == last["from_id"]), "")} if last else None)})
    asks = []
    for kid in kids_in_my_family(m):
        for r in db.many(select(t).where(t.member_id == kid["id"], t.status == "pending")):
            grp = group_row(r["group_id"])
            if grp:
                asks.append({"group": grp["id"], "group_name": grp["name"], "member": kid["id"], "name": kid["name"], "emoji": kid["emoji"]})
    return jsonify(groups=out, requests=asks)


def join_group(grp, m):
    """Adds someone to a group. A child waits as 'pending' until one of their parents says yes."""
    t = db.friend_members.c
    have = membership(grp["id"], m["id"])
    if have:
        return have["status"], None
    if len(db.many(select(t.id).where(t.member_id == m["id"]))) >= GROUP_LIMIT:
        return None, f"That is plenty of groups ({GROUP_LIMIT}). Leave one first."
    if len(db.many(select(t.id).where(t.group_id == grp["id"]))) >= GROUP_SIZE:
        return None, "That group is full."
    status = "active" if m["role"] == "parent" else "pending"
    db.run(insert(t_members()).values(group_id=grp["id"], member_id=m["id"], status=status, joined=time.time()))
    if status == "pending":
        tell_parents(m, "group_request", {"gid": grp["id"], "group": grp["name"], "name": m["name"], "emoji": m["emoji"]})
    else:
        for x in group_people(grp["id"]):
            if x["id"] != m["id"]:
                push(x["id"], "group", {"gid": grp["id"]})
    return status, None


def t_members():
    return db.friend_members


@app.post("/api/groups")
@need
def make_group():
    b = request.get_json(silent=True) or {}
    name = clean(b.get("name"), 40)
    if not name:
        return err("Give the group a name.")
    if moderate(name)[1]:
        return err("Pick a friendlier name.")
    if len(db.many(select(db.friend_members.c.id).where(db.friend_members.c.member_id == g.m["id"]))) >= GROUP_LIMIT:
        return err(f"That is plenty of groups ({GROUP_LIMIT}). Leave one first.")
    code = secrets.token_hex(4).upper()
    gid = db.run(insert(db.friend_groups).values(name=name, emoji=clean(b.get("emoji"), 8) or "\U0001F465", code=code, created_by=g.m["id"], created=time.time()))
    status, problem = join_group(group_row(gid), g.m)
    return jsonify(id=gid, code=code, status=status)


@app.post("/api/groups/join")
@need
def join_by_code():
    if limited(f"gjoin:{g.m['id']}", 10):
        return err("Slow down a little.", 429)
    code = re.sub(r"[^A-Za-z0-9]", "", str((request.get_json(silent=True) or {}).get("code") or "")).upper()
    grp = db.one(select(db.friend_groups).where(db.friend_groups.c.code == code)) if code else None
    if not grp:
        return err("That code does not match a group.", 404)
    status, problem = join_group(grp, g.m)
    if problem:
        return err(problem)
    return jsonify(id=grp["id"], status=status, name=grp["name"])


@app.post("/api/groups/<int:gid>/approve")
@parent_only
def approve_member(gid):
    b = request.get_json(silent=True) or {}
    kid = same_family(int(b["member"])) if str(b.get("member", "")).isdigit() else None
    row = membership(gid, kid["id"]) if kid and kid["role"] == "child" else None
    grp = group_row(gid)
    if not row or row["status"] != "pending" or not grp:
        return err("Nothing to answer.", 404)
    if bool(b.get("ok", True)):
        db.run(update(db.friend_members).where(db.friend_members.c.id == row["id"]).values(status="active", joined=time.time()))
        push(kid["id"], "group", {"gid": gid, "approved": True, "group": grp["name"], "name": g.m["name"]})
        for x in group_people(gid):
            if x["id"] != kid["id"]:
                push(x["id"], "group", {"gid": gid})
    else:
        db.run(delete(db.friend_members).where(db.friend_members.c.id == row["id"]))
        push(kid["id"], "group", {"gid": gid, "declined": True, "group": grp["name"]})
    return jsonify(ok=True)


@app.post("/api/groups/<int:gid>/leave")
@need
def leave_group(gid):
    row = membership(gid, g.m["id"])
    if not row:
        return err("Not found.", 404)
    db.run(delete(db.friend_members).where(db.friend_members.c.id == row["id"]))
    if not db.many(select(db.friend_members.c.id).where(db.friend_members.c.group_id == gid)):
        db.run(delete(db.friend_messages).where(db.friend_messages.c.group_id == gid))
        db.run(delete(db.friend_groups).where(db.friend_groups.c.id == gid))
    return jsonify(ok=True)


@app.delete("/api/groups/<int:gid>/members/<int:mid>")
@parent_only
def remove_member(gid, mid):
    """A parent takes their own child out of a group."""
    kid = same_family(mid)
    row = membership(gid, mid) if kid and kid["role"] == "child" else None
    if not row:
        return err("Not found.", 404)
    db.run(delete(db.friend_members).where(db.friend_members.c.id == row["id"]))
    push(kid["id"], "group", {"gid": gid, "removed": True})
    return jsonify(ok=True)


@app.get("/api/groups/<int:gid>/messages")
@need
def group_messages(gid):
    role, _ = group_access(gid, g.m)
    if role == "pending":
        return err("A parent needs to say yes first.", 403)
    if role not in ("member", "overseer"):
        return err("Not found.", 404)
    t = db.friend_messages.c
    rows = db.many(select(db.friend_messages).where(t.group_id == gid, t.id > qint("after", 0)).order_by(t.id.desc()).limit(80))
    ids = {r["from_id"] for r in rows}
    people = {x["id"]: x for x in db.many(select(db.members).where(db.members.c.id.in_(list(ids))))} if ids else {}
    grp = group_row(gid)
    return jsonify(role=role, group={"id": gid, "name": grp["name"], "emoji": grp["emoji"], "members": group_people(gid)}, messages=[group_message_view(r, g.m, people) for r in reversed(rows)])


@app.post("/api/groups/<int:gid>/messages")
@need
def send_group_message(gid):
    role, mine = group_access(gid, g.m)
    if role == "pending":
        return err("A parent needs to say yes first.", 403)
    if role == "overseer":
        return err("You can read this group, but only its members can write in it.", 403)
    if role != "member":
        return err("Not found.", 404)
    now = time.time()
    if (mine["muted_until"] or 0) > now:
        return err(f"You are on a short break from this group for {int(((mine['muted_until'] - now) // 60) + 1)} more minutes.", 429)
    if limited(f"gmsg:{g.m['id']}", 120):
        return err("Slow down a little.", 429)
    b = request.get_json(silent=True) or {}
    kind = "gif" if b.get("kind") == "gif" else "text"
    flags, original = [], ""
    if kind == "gif":
        body = gif_url(b.get("body"))
        if not body:
            return err("That is not a GIF link we can show. Pick a sticker, or paste a link ending in .gif from Giphy, Tenor or Imgur.")
    else:
        raw = clean(b.get("body"), 600)
        if not raw:
            return err("Write something first.")
        body, flags = moderate(raw)
        original = raw if flags else ""
    mid = db.run(insert(db.friend_messages).values(group_id=gid, from_id=g.m["id"], kind=kind, body=body, original=original, flags=json.dumps(flags) if flags else "", created=now))
    grp = group_row(gid)
    people = group_people(gid)
    support = None
    if kind == "text":
        members = [db.one(select(db.members).where(db.members.c.id == x["id"])) for x in people]
        support = risk_check(g.m, original or body, f"the group {grp['name']}", [x for x in members if x])
    note = None
    if flags:
        top = worst(flags)
        recent = [r for r in db.many(select(db.friend_messages).where(db.friend_messages.c.from_id == g.m["id"], db.friend_messages.c.created > now - 600, db.friend_messages.c.flags != ""))]
        pause = MUTES["slur"] if top == "slur" else MUTES["strikes"] if len(recent) >= 3 else 0
        if pause:
            db.run(update(db.friend_members).where(db.friend_members.c.id == mine["id"]).values(muted_until=now + pause))
        note = ("Your message was changed because it had " + {"slur": "a hurtful", "profanity": "a rude", "insult": "an unkind"}[top] + " word. Your parents are told."
                + (f" You are on a break from this group for {pause // 60} minutes." if pause else ""))
        for kid_parent in family_members(g.m["family_id"]):                      # the sender's own parents, always
            pass
        flag = {"gid": gid, "group": grp["name"], "name": g.m["name"], "cat": top, "id": mid, "paused": bool(pause)}
        told = set()
        for x in people:                                                          # every parent of every child in the group hears about a flag
            owner = db.one(select(db.members).where(db.members.c.id == x["id"]))
            if owner and owner["role"] == "child":
                for p in family_members(owner["family_id"]):
                    if p["role"] == "parent" and p["id"] not in told and p["id"] != g.m["id"]:
                        told.add(p["id"])
                        push(p["id"], "group_flag", flag)
    event = {"gid": gid, "group": grp["name"], "id": mid, "from": g.m["id"], "name": g.m["name"], "emoji": g.m["emoji"], "kind": kind, "body": body, "flagged": bool(flags)}
    for x in people:
        if x["id"] != g.m["id"]:
            push(x["id"], "gmessage", event)
    return jsonify(id=mid, body=body, flagged=bool(flags), note=note, support=support)


# ---------------------------------------------------------------- the family meal plan, and apps (like Platter) that put recipes on it
SLOTS = ("breakfast", "lunch", "dinner", "snack")
EXT_ORIGINS = [o.strip() for o in os.environ.get("EXT_ORIGINS", "https://platter-87b0.onrender.com,http://127.0.0.1:5075,http://localhost:5075,https://scout-15t6.onrender.com,http://127.0.0.1:5055,http://localhost:5055,https://alibi-d9tn.onrender.com,http://127.0.0.1:5085,http://localhost:5085").split(",") if o.strip()]
APP_SCOPES = {"Platter": "meals", "Scout": "plans,places,share"}          # what each known app may do; anything else gets the meal plan only


def clean_day(value):
    try:
        d = datetime.date.fromisoformat(str(value or ""))
    except ValueError:
        return None
    today = datetime.date.today()
    return d.isoformat() if today - datetime.timedelta(days=90) <= d <= today + datetime.timedelta(days=800) else None


def clean_lines(value, n=80, width=300):
    return [re.sub(r"\s+", " ", str(x)).strip()[:width] for x in (value if isinstance(value, list) else []) if str(x).strip()][:n]


def clean_https(value, n=400):
    v = str(value or "").strip()
    return v[:n] if v.startswith("https://") and len(v) <= n else ""


def meal_view(r):
    def lines(text):
        try:
            d = json.loads(text or "[]")
        except ValueError:
            return []
        return d if isinstance(d, list) else []
    return {"id": r["id"], "day": r["day"], "slot": r["slot"], "title": r["title"], "photo": r["photo"] or "", "source": r["source"] or "", "servings": r["servings"] or 0,
            "ingredients": lines(r["ingredients"]), "steps": lines(r["steps"]), "via": r["via"], "by": r["added_by"], "ext": r["ext_id"] or "", "cook": r["cook"] or 0,
            "suggested": bool(r.get("suggested"))}


def save_meal(fid, who, via, b, ext_id="", suggested=False):
    """Adds a meal, or (for a linked app that sends the same id again) updates it. Returns (meal row id, error text)."""
    day, title = clean_day(b.get("day")), clean(b.get("title"), 120)
    if not day:
        return None, "Pick a day."
    if not title:
        return None, "What are we eating?"
    slot = b.get("slot") if b.get("slot") in SLOTS else "dinner"
    try:
        servings = max(0, min(99, int(b.get("servings") or 0)))
    except (TypeError, ValueError):
        servings = 0
    values = dict(day=day, slot=slot, title=title, photo=clean_https(b.get("photo")), source=clean_https(b.get("source")), servings=servings,
                  ingredients=json.dumps(clean_lines(b.get("ingredients")), ensure_ascii=False), steps=json.dumps(clean_lines(b.get("steps"), 60, 1200), ensure_ascii=False))
    t = db.meals.c
    if ext_id:
        old = db.one(select(db.meals).where(t.family_id == fid, t.ext_id == ext_id))
        if old:
            db.run(update(db.meals).where(t.id == old["id"]).values(**values))
            return old["id"], None
    if len(db.many(select(t.id).where(t.family_id == fid))) >= 600:
        return None, "That is a lot of meals planned already. Remove some old ones first."
    return db.run(insert(db.meals).values(family_id=fid, ext_id=ext_id, via=via, added_by=who, created=time.time(), suggested=suggested, **values)), None


@app.get("/api/meals")
@need
def get_meals():
    today = datetime.date.today()
    a = clean_day(request.args.get("from")) or today.isoformat()
    b = clean_day(request.args.get("to")) or (today + datetime.timedelta(days=14)).isoformat()
    if not a <= b or (datetime.date.fromisoformat(b) - datetime.date.fromisoformat(a)).days > 70:
        return err("Pick up to ten weeks at a time.")
    t = db.meals.c
    rows = db.many(select(db.meals).where(t.family_id == g.m["family_id"], t.day >= a, t.day <= b).order_by(t.day, t.id).limit(400))
    return jsonify(meals=[meal_view(r) for r in rows])


PLATTER_URL = os.environ.get("PLATTER_URL", "https://platter-87b0.onrender.com").rstrip("/")
_recipes = {}


def recipe_library():
    """Platter's recipe library (kept with Mellow, so searching needs no other server). Loaded once."""
    if "all" not in _recipes:
        try:
            with open(os.path.join(BASE, "data", "recipes.json"), encoding="utf-8") as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {"tags": [], "recipes": []}
        _recipes["tags"] = d["tags"]
        _recipes["all"] = d["recipes"]
        _recipes["by_id"] = {r["id"]: r for r in d["recipes"]}
    return _recipes


def recipe_view(r):
    return {**r, "platter": f"{PLATTER_URL}/#/t/{r['tag']}/r/{r['id']}"}


@app.get("/api/recipes")
@need
def search_recipes():
    """Search the recipe library by name, ingredient or tag. ?random=1 picks a few at random ("surprise me")."""
    lib = recipe_library()
    q = re.sub(r"\s+", " ", str(request.args.get("q") or "")).strip().lower()[:60]
    tag = str(request.args.get("tag") or "")[:40]
    pool = [r for r in lib["all"] if not tag or r["tag"] == tag]
    if request.args.get("random"):
        import random
        return jsonify(recipes=[recipe_view(r) for r in random.sample(pool, min(6, len(pool)))], tags=lib["tags"])
    if q:
        words = q.split(" ")
        scored = []
        for r in pool:
            title, ing = r["title"].lower(), " ".join(r["ing"]).lower()
            if all(w in title or w in ing or w in r["tag"] for w in words):
                scored.append((sum(3 for w in words if w in title) + sum(1 for w in words if w in ing), r))
        pool = [r for _, r in sorted(scored, key=lambda x: -x[0])]
    return jsonify(recipes=[recipe_view(r) for r in pool[:30]], tags=lib["tags"])


@app.post("/api/meals")
@need
def add_meal():
    b = request.get_json(silent=True) or {}
    via = "homebase"
    if b.get("recipe"):                                                # a recipe picked from the library: its title, photo, ingredients and method come with it
        r = recipe_library()["by_id"].get(str(b.get("recipe")))
        if not r:
            return err("That recipe was not found.", 404)
        b = {"day": b.get("day"), "slot": b.get("slot"), "title": r["title"], "photo": r["img"] if r["img"].startswith("https://") else "", "servings": r["serves"],
             "ingredients": r["ing"], "steps": r["steps"], "source": recipe_view(r)["platter"]}
        via = "platter"
    child = g.m["role"] != "parent"
    mid, problem = save_meal(g.m["family_id"], g.m["id"], via, b, suggested=child)
    if problem:
        return err(problem)
    if child:                                                          # a child can suggest; the parents are told and can say yes or not now
        for p in family_members(g.m["family_id"]):
            if p["role"] == "parent":
                push(p["id"], "meal_suggest", {"id": mid, "name": g.m["name"], "emoji": g.m["emoji"], "title": clean(b.get("title"), 120)})
    push_family(g.m["family_id"], "meals", {"id": mid}, skip=g.m["id"])
    return jsonify(id=mid, suggested=child)


@app.post("/api/meals/<int:mid>/approve")
@parent_only
def approve_meal(mid):
    """A parent says yes (the meal is planned) or not now (it is removed) to a child's suggestion."""
    r = db.one(select(db.meals).where(db.meals.c.id == mid, db.meals.c.family_id == g.m["family_id"]))
    if not r or not r["suggested"]:
        return err("Nothing to answer.", 404)
    ok = bool((request.get_json(silent=True) or {}).get("ok", True))
    if ok:
        db.run(update(db.meals).where(db.meals.c.id == mid).values(suggested=False))
    else:
        db.run(delete(db.meals).where(db.meals.c.id == mid))
    if r["added_by"] and r["added_by"] != g.m["id"] and same_family(r["added_by"]):
        push(r["added_by"], "meal_ok", {"id": mid, "title": r["title"], "approved": ok, "name": g.m["name"]})
    push_family(g.m["family_id"], "meals", {"id": mid}, skip=g.m["id"])
    return jsonify(ok=True, approved=ok)


@app.delete("/api/meals/<int:mid>")
@need
def drop_meal(mid):
    r = db.one(select(db.meals).where(db.meals.c.id == mid, db.meals.c.family_id == g.m["family_id"]))
    if not r or not (g.m["role"] == "parent" or r["added_by"] == g.m["id"]):
        return err("Not found.", 404)
    db.run(delete(db.meals).where(db.meals.c.id == mid))
    push_family(g.m["family_id"], "meals", {"id": mid}, skip=g.m["id"])
    return jsonify(ok=True)


@app.post("/api/meals/<int:mid>/cook")
@need
def set_cook(mid):
    """"I'll cook it". Anyone can put their own name on a meal or take it off; a parent can name anyone."""
    r = db.one(select(db.meals).where(db.meals.c.id == mid, db.meals.c.family_id == g.m["family_id"]))
    if not r:
        return err("Not found.", 404)
    who = (request.get_json(silent=True) or {}).get("cook")
    who = int(who) if str(who or "").isdigit() else 0
    if who and not same_family(who):
        return err("Not found.", 404)
    if g.m["role"] != "parent" and who not in (0, g.m["id"]):
        return err("You can put your own name on it, or take it off.", 403)
    if g.m["role"] != "parent" and not who and r["cook"] not in (0, g.m["id"]):
        return err("Someone else is cooking that one.", 403)
    db.run(update(db.meals).where(db.meals.c.id == mid).values(cook=who))
    push_family(g.m["family_id"], "meals", {"id": mid}, skip=g.m["id"])
    return jsonify(ok=True, cook=who)


@app.post("/api/meals/<int:mid>/to-list")
@need
def meal_to_list(mid):
    """Puts a meal's ingredients on the family shopping list (skipping what is already waiting there)."""
    r = db.one(select(db.meals).where(db.meals.c.id == mid, db.meals.c.family_id == g.m["family_id"]))
    if not r:
        return err("Not found.", 404)
    fid, t = g.m["family_id"], db.lists.c
    have = {row["text"].lower() for row in db.many(select(t.text).where(t.family_id == fid, t.done == False))}  # noqa: E712
    added = 0
    for line in meal_view(r)["ingredients"]:
        text = clean(line, 120)
        if text and text.lower() not in have and len(have) < 300:
            db.run(insert(db.lists).values(family_id=fid, text=text, done=False, added_by=g.m["id"], created=time.time()))
            have.add(text.lower())
            added += 1
    if added:
        push_family(fid, "list", {"meal": mid}, skip=g.m["id"])
    return jsonify(ok=True, added=added)


@app.get("/api/feed")
@need
def activity_feed():
    """What has been happening in the family: arrivals, jobs, rewards, plans, check-ins, alerts."""
    t = db.messages.c
    rows = db.many(select(t).where(t.family_id == g.m["family_id"], t.to_id == 0, t.kind.in_(["system", "sos", "late", "checkin", "pickup"])).order_by(t.id.desc()).limit(60))
    people = {m["id"]: m for m in family_members(g.m["family_id"])}
    return jsonify(items=[{"id": r["id"], "kind": r["kind"], "body": r["body"], "at": r["created"], "from": r["from_id"], "name": (people.get(r["from_id"]) or {}).get("name", ""),
                           "emoji": (people.get(r["from_id"]) or {}).get("emoji", "")} for r in rows])


@app.get("/api/integrations")
@parent_only
def list_integrations():
    rows = db.many(select(db.integrations).where(db.integrations.c.family_id == g.m["family_id"]).order_by(db.integrations.c.id))
    return jsonify(integrations=[{"id": r["id"], "name": r["name"], "created": r["created"], "last_used": r["last_used"] or 0, "scopes": (r["scopes"] or "meals").split(",")} for r in rows])


@app.post("/api/integrations")
@parent_only
def add_integration():
    """Links an app. The key is shown once, here; only a fingerprint of it is kept, and it can only do what that app is meant to (Platter: the meal plan; Scout: plans, places and sharing)."""
    name = clean((request.get_json(silent=True) or {}).get("name"), 40) or "Platter"
    fid = g.m["family_id"]
    if len(db.many(select(db.integrations.c.id).where(db.integrations.c.family_id == fid))) >= 5:
        return err("That is plenty of linked apps (5). Remove one first.")
    key = "hbk_" + secrets.token_urlsafe(24)
    scopes = APP_SCOPES.get(name, "meals")
    iid = db.run(insert(db.integrations).values(family_id=fid, name=name, key_hash=db.hash_token(key), created_by=g.m["id"], created=time.time(), scopes=scopes))
    say(fid, g.m["id"], f"{g.m['name']} linked {name} to the family meal plan" if scopes == "meals" else f"{g.m['name']} linked {name} to Homebase")
    return jsonify(id=iid, name=name, key=key, scopes=scopes.split(","))


@app.delete("/api/integrations/<int:iid>")
@parent_only
def drop_integration(iid):
    r = db.one(select(db.integrations).where(db.integrations.c.id == iid, db.integrations.c.family_id == g.m["family_id"]))
    if not r:
        return err("Not found.", 404)
    db.run(delete(db.integrations).where(db.integrations.c.id == iid))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} unlinked {r['name']}")
    return jsonify(ok=True)


def ext_app():
    """The linked app a request comes from (by its key), or None."""
    token = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    if not token.startswith("hbk_"):
        return None
    row = db.one(select(db.integrations).where(db.integrations.c.key_hash == db.hash_token(token)))
    if row and time.time() - (row["last_used"] or 0) > 60:
        db.run(update(db.integrations).where(db.integrations.c.id == row["id"]).values(last_used=time.time()))
    return row


def ext_guard(scope):
    """(the linked app, None) when the key is good and allowed to do this, else (None, the answer to send back)."""
    row = ext_app()
    if not row:
        return None, err("This link is not valid any more. Link it again from Homebase.", 401)
    if scope not in (row["scopes"] or "meals").split(","):
        return None, err("This link is not allowed to do that.", 403)
    return row, None


def ext_cors(resp):
    origin = request.headers.get("Origin", "")
    if request.path.startswith("/api/ext/") and origin in EXT_ORIGINS:
        resp.headers["Access-Control-Allow-Origin"] = origin
        resp.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type"
        resp.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
        resp.headers["Access-Control-Max-Age"] = "600"
        resp.headers["Vary"] = "Origin"
    return resp


app.after_request(ext_cors)


@app.route("/api/ext/<path:rest>", methods=["OPTIONS"])
def ext_preflight(rest):
    return app.response_class("", status=204)


@app.get("/api/ext/ping")
def ext_ping():
    row = ext_app()
    if not row:
        return err("This link is not valid any more. Link it again from Homebase.", 401)
    fam = db.one(select(db.families).where(db.families.c.id == row["family_id"]))
    return jsonify(family=fam["name"], app=row["name"], scopes=(row["scopes"] or "meals").split(","))


@app.post("/api/ext/meals")
def ext_put_meal():
    row, bad = ext_guard("meals")
    if bad:
        return bad
    if limited(f"ext:{row['id']}", 900):
        return err("Slow down a little.", 429)
    b = request.get_json(silent=True) or {}
    ext_id = clean(b.get("ext_id"), 80)
    if not ext_id:
        return err("Say which meal this is.")
    mid, problem = save_meal(row["family_id"], 0, row["name"].lower()[:20], b, ext_id)
    if problem:
        return err(problem)
    push_family(row["family_id"], "meals", {"id": mid})
    return jsonify(id=mid)


@app.delete("/api/ext/meals/<path:ext_id>")
def ext_drop_meal(ext_id):
    row, bad = ext_guard("meals")
    if bad:
        return bad
    t = db.meals.c
    mine = db.one(select(db.meals).where(t.family_id == row["family_id"], t.ext_id == ext_id))
    if mine:
        db.run(delete(db.meals).where(t.id == mine["id"]))
        push_family(row["family_id"], "meals", {"id": mine["id"]})
    return jsonify(ok=True)


def ext_point(b):
    """A latitude and longitude from a request, or None when they are missing or not on Earth."""
    try:
        lat, lng = float(b["lat"]), float(b["lng"])
    except (KeyError, TypeError, ValueError):
        return None
    return (lat, lng) if -90 <= lat <= 90 and -180 <= lng <= 180 else None


@app.post("/api/ext/plans")
def ext_put_plan():
    """Scout (or another linked app) puts a place on the family calendar. The same ext_id updates the plan instead of adding another."""
    row, bad = ext_guard("plans")
    if bad:
        return bad
    if limited(f"ext:{row['id']}", 900):
        return err("Slow down a little.", 429)
    b = request.get_json(silent=True) or {}
    ext_id, title = clean(b.get("ext_id"), 80), clean(b.get("title"), 60)
    if not ext_id or not title:
        return err("Say which place this is and give it a name.")
    try:
        start = float(b.get("start"))
    except (TypeError, ValueError):
        return err("Pick a day and a time.")
    now = time.time()
    if not (now - 86400 <= start <= now + 2 * 365 * 86400):
        return err("That day is too far away.")
    point = ext_point(b) or (None, None)
    fid, emoji = row["family_id"], clean(b.get("emoji"), 8) or "\U0001F4CD"
    values = dict(title=title, emoji=emoji, start=start, note=clean(b.get("note"), 120), lat=point[0], lng=point[1], via=row["name"].lower()[:20])
    mine = db.one(select(db.plans).where(db.plans.c.family_id == fid, db.plans.c.ext_id == ext_id))
    if mine:
        db.run(update(db.plans).where(db.plans.c.id == mine["id"]).values(**values, reminded=False))
        pid = mine["id"]
    else:
        pid = db.run(insert(db.plans).values(family_id=fid, created_by=0, created=now, rsvp="{}", reminded=False, ext_id=ext_id, **values))
    push_family(fid, "plan", {"id": pid, "new": not mine, "name": row["name"], "title": title, "emoji": emoji, "start": start})
    return jsonify(id=pid)


@app.delete("/api/ext/plans/<path:ext_id>")
def ext_drop_plan(ext_id):
    row, bad = ext_guard("plans")
    if bad:
        return bad
    t = db.plans.c
    mine = db.one(select(db.plans).where(t.family_id == row["family_id"], t.ext_id == ext_id))
    if mine:
        db.run(delete(db.plans).where(t.id == mine["id"]))
        push_family(row["family_id"], "plan", {"id": mine["id"]})
    return jsonify(ok=True)


@app.post("/api/ext/places")
def ext_put_place():
    """Saves a place for the family (everyone is told when someone arrives or leaves it, same as a place added in Homebase). One per name and spot."""
    row, bad = ext_guard("places")
    if bad:
        return bad
    if limited(f"extp:{row['id']}", 30):
        return err("Slow down a little.", 429)
    b = request.get_json(silent=True) or {}
    point, name = ext_point(b), clean(b.get("name"), 40)
    if not point or not name:
        return err("Say where the place is and give it a name.")
    fid, t = row["family_id"], db.places.c
    have = db.many(select(db.places).where(t.family_id == fid))
    for p in have:
        if p["name"].lower() == name.lower() and metres(point[0], point[1], p["lat"], p["lng"]) < 200:
            return jsonify(id=p["id"], existed=True)
    if len(have) >= 20:
        return err("That is plenty of places (20).")
    radius = max(50, min(1000, int(b["radius"]))) if str(b.get("radius", "")).isdigit() else 150
    pid = db.run(insert(db.places).values(family_id=fid, name=name, emoji=clean(b.get("emoji"), 8) or "\U0001F4CD", lat=point[0], lng=point[1], radius=radius, created_by=row["created_by"], created=time.time()))
    say(fid, row["created_by"], f"{row['name']} saved the place {name}. Everyone is told when someone arrives or leaves.")
    push_family(fid, "places", {"id": pid, "name": name, "by": row["name"]})
    return jsonify(id=pid)


@app.post("/api/ext/share")
def ext_share():
    """Shows a place in the family chat (as a note from the app, not as a person), with its spot so a tap shows where."""
    row, bad = ext_guard("share")
    if bad:
        return bad
    if limited(f"exts:{row['id']}", 30, 900):
        return err("Slow down a little.", 429)
    b = request.get_json(silent=True) or {}
    name, address = clean(b.get("name"), 60), clean(b.get("address"), 100)
    if not name:
        return err("Say which place to share.")
    point = ext_point(b) or (None, None)
    body = f"\U0001F9ED {row['name']}: {name}" + (f", {address}" if address else "")
    mid = say(row["family_id"], 0, body, kind="system", lat=point[0], lng=point[1])
    msg = db.one(select(db.messages).where(db.messages.c.id == mid))
    for m in family_members(row["family_id"]):
        push(m["id"], "message", {**msg_view(msg), "name": row["name"], "emoji": "🧭", "quiet": False, "urgent": False})
    return jsonify(id=mid)


# ---------------------------------------------------------------- family games: start one from the family chat, and it tells the chat how to join
ALIBI_URL = os.environ.get("ALIBI_URL", "https://alibi-d9tn.onrender.com").rstrip("/")
FAMILY_GAMES = {"alibi": {"name": "Alibi", "emoji": "\U0001F575\ufe0f", "url": ALIBI_URL}}       # the only places a game message may point to
GAME_LINK_LIFE = 2 * 3600


def site_origin():
    scheme = (request.headers.get("X-Forwarded-Proto") or request.scheme).split(",")[0].strip()
    return f"{scheme}://{request.host}"


@app.post("/api/games/start")
@need
def start_family_game():
    """Someone taps the game button in the chat. They get a link that opens the game, and the game uses its one-time key to post the join code."""
    game_id = str((request.get_json(silent=True) or {}).get("game") or "").lower()
    game = FAMILY_GAMES.get(game_id)
    if not game:
        return err("No such game.")
    if limited(f"game:{g.m['id']}", 10):
        return err("Slow down a little.", 429)
    token = "hbg_" + secrets.token_urlsafe(18)
    now = time.time()
    db.run(insert(db.game_links).values(family_id=g.m["family_id"], member_id=g.m["id"], token_hash=db.hash_token(token), game=game_id, created=now, expires=now + GAME_LINK_LIFE, used=False))
    fam = db.one(select(db.families).where(db.families.c.id == g.m["family_id"]))
    payload = {"b": site_origin(), "t": token, "n": g.m["name"], "f": fam["name"] if fam else ""}
    code = _b64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b"=").decode()
    return jsonify(url=f"{game['url']}/#family={code}", game=game["name"], expires=now + GAME_LINK_LIFE)


@app.post("/api/ext/game")
def ext_game_started():
    """The game says: I am open, here is my code. Works once per link, only for the family that made it, and the join address is built here, never taken from the caller."""
    b = request.get_json(silent=True) or {}
    token, code = str(b.get("token") or ""), str(b.get("code") or "").upper()
    if not token.startswith("hbg_") or not re.fullmatch(r"[A-Z0-9]{4}", code):
        return err("This game link is not valid.")
    t = db.game_links.c
    row = db.one(select(db.game_links).where(t.token_hash == db.hash_token(token)))
    if not row or row["used"] or row["expires"] < time.time():
        return err("This game link has run out. Start the game again from Homebase.", 401)
    member = db.one(select(db.members).where(db.members.c.id == row["member_id"], db.members.c.family_id == row["family_id"]))
    game = FAMILY_GAMES.get(row["game"])
    if not member or not game:
        return err("This game link is not valid any more.", 401)
    db.run(update(db.game_links).where(t.id == row["id"]).values(used=True))
    body = f"{game['emoji']} {member['name']} started a game of {game['name']}. Join with code {code}: {game['url']}/#join/{code}"
    mid = say(row["family_id"], 0, body, kind="game")
    msg = db.one(select(db.messages).where(db.messages.c.id == mid))
    for m in family_members(row["family_id"]):
        push(m["id"], "message", {**msg_view(msg), "name": game["name"], "emoji": game["emoji"], "quiet": False, "urgent": False})
    return jsonify(ok=True)


# ======================================================================= Mellow 5.0
def kids_of(m):
    return [k for k in family_members(m["family_id"]) if k["role"] == "child"]


def parents_of(fid):
    return [p for p in family_members(fid) if p["role"] == "parent"]


def tell_parents_of(member, kind, title, text, **extra):
    for p in parents_of(member["family_id"]):
        if p["id"] != member["id"]:
            push(p["id"], kind, {"member": member["id"], "name": member["name"], "title": title, "text": text, **extra})


def local_now(m):
    """The person's own clock: (YYYY-MM-DD, minutes since midnight)."""
    t = time.time() - (m.get("tz_offset") or 0) * 60
    return time.strftime("%Y-%m-%d", time.gmtime(t)), int((t % 86400) // 60)


def money(c):
    return f"{'-' if c < 0 else ''}{abs(c) // 100}.{abs(c) % 100:02d}"


# ---- photo album
@app.get("/api/album")
@need
def get_album():
    t, im = db.album.c, db.message_images.c
    rows = db.many(select(db.album).where(t.family_id == g.m["family_id"]).order_by(t.id.desc()).limit(200))
    have = {r["token"] for r in db.many(select(im.token).where(im.family_id == g.m["family_id"]))}
    names = {x["id"]: x["name"] for x in family_members(g.m["family_id"])}
    return jsonify(photos=[{"id": r["id"], "url": f"/api/img/{r['token']}", "by": r["by"], "name": names.get(r["by"], ""), "caption": r["caption"], "at": r["created"]}
                           for r in rows if r["token"] in have])


@app.post("/api/album")
@need
def add_album():
    b = request.get_json(silent=True) or {}
    url = str(b.get("url") or "")
    token = url.rsplit("/", 1)[-1] if url.startswith("/api/img/") else ""
    img = db.one(select(db.message_images).where(db.message_images.c.token == token, db.message_images.c.family_id == g.m["family_id"])) if token else None
    if not img or not str(img["mime"]).startswith("image/"):
        return err("Upload the photo first.")
    pid = db.run(insert(db.album).values(family_id=g.m["family_id"], token=token, by=g.m["id"], caption=clean(b.get("caption"), 120), created=time.time()))
    push_family(g.m["family_id"], "album", {"id": pid, "name": g.m["name"]}, skip=g.m["id"])
    return jsonify(id=pid)


@app.delete("/api/album/<int:pid>")
@need
def drop_album(pid):
    r = db.one(select(db.album).where(db.album.c.id == pid, db.album.c.family_id == g.m["family_id"]))
    if not r or not (r["by"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    db.run(delete(db.album).where(db.album.c.id == pid))
    return jsonify(ok=True)


# ---- voice notes (stored like photos, under a long random address)
AUDIO_LIMIT = 1024 * 1024


@app.post("/api/audio")
@need
def upload_audio():
    if limited(f"aud:{g.m['id']}", 40):
        return err("Slow down a little.", 429)
    raw = str((request.get_json(silent=True) or {}).get("audio") or "")
    try:
        blob = _b64.b64decode(raw.split(",", 1)[-1], validate=True)
    except Exception:
        return err("That voice note did not come through.")
    if not blob or len(blob) > AUDIO_LIMIT:
        return err("That voice note is too long. Keep it under a minute.")
    mime = "audio/mp4" if blob[4:8] == b"ftyp" else "audio/ogg" if blob.startswith(b"OggS") else "audio/amr" if blob.startswith(b"#!AMR") else None
    if not mime:
        return err("That is not a voice note Mellow can play.")
    token = secrets.token_urlsafe(16)
    db.run(insert(db.message_images).values(token=token, family_id=g.m["family_id"], from_id=g.m["id"], mime=mime, data=_b64.b64encode(blob).decode(), created=time.time()))
    return jsonify(url=f"/api/img/{token}")


# ---- pocket money and savings goals
def balance(member_id):
    t = db.wallet.c
    return sum(r["cents"] for r in db.many(select(t.cents).where(t.member_id == member_id, t.status == "done")))


@app.get("/api/wallet")
@need
def get_wallet():
    m = g.m
    ids = [x["id"] for x in kids_of(m)] if m["role"] == "parent" else [m["id"]]
    t, gt = db.wallet.c, db.goals.c
    names = {x["id"]: x["name"] for x in family_members(m["family_id"])}
    out = []
    for mid in ids:
        rows = db.many(select(db.wallet).where(t.member_id == mid).order_by(t.id.desc()).limit(40))
        out.append({"member": mid, "name": names.get(mid, ""), "balance": balance(mid),
                    "entries": [{"id": r["id"], "cents": r["cents"], "note": r["note"], "status": r["status"], "by": names.get(r["by"], ""), "at": r["created"]} for r in rows],
                    "goals": [{"id": x["id"], "title": x["title"], "emoji": x["emoji"], "target": x["target"], "saved": x["saved"]}
                              for x in db.many(select(db.goals).where(gt.member_id == mid).order_by(gt.id))]})
    return jsonify(wallets=out)


def cents_of(v):
    try:
        return int(round(float(v) * 100))
    except (TypeError, ValueError):
        return None


@app.post("/api/wallet")
@parent_only
def wallet_add():
    """A parent adds pocket money (or takes some off, with a minus)."""
    b = request.get_json(silent=True) or {}
    kid = same_family(b.get("member"))
    c = cents_of(b.get("amount"))
    if not kid or kid["role"] != "child":
        return err("Pick a child.")
    if not c or abs(c) > 100000:
        return err("Pick an amount.")
    db.run(insert(db.wallet).values(family_id=g.m["family_id"], member_id=kid["id"], cents=c, note=clean(b.get("note"), 80) or ("Pocket money" if c > 0 else "Spent"), by=g.m["id"], status="done", created=time.time()))
    push(kid["id"], "wallet", {"title": f"\U0001F4B0 {g.m['name']} {'added' if c > 0 else 'took off'} {money(abs(c))}", "text": clean(b.get("note"), 80)})
    return jsonify(ok=True, balance=balance(kid["id"]))


@app.post("/api/wallet/request")
@need
def wallet_request():
    """A child asks to spend some of their money (or asks for some). It waits for a parent."""
    b = request.get_json(silent=True) or {}
    c = cents_of(b.get("amount"))
    if g.m["role"] == "parent" or not c or abs(c) > 100000:
        return err("Pick an amount.")
    wid = db.run(insert(db.wallet).values(family_id=g.m["family_id"], member_id=g.m["id"], cents=c, note=clean(b.get("note"), 80) or ("Spend" if c < 0 else "Asked for"), by=g.m["id"], status="pending", created=time.time()))
    tell_parents_of(g.m, "wallet_request", f"\U0001F4B0 {g.m['name']} asks: {money(c)}", clean(b.get("note"), 80), id=wid)
    return jsonify(id=wid)


@app.post("/api/wallet/<int:wid>/answer")
@parent_only
def wallet_answer(wid):
    r = db.one(select(db.wallet).where(db.wallet.c.id == wid, db.wallet.c.family_id == g.m["family_id"]))
    if not r or r["status"] != "pending":
        return err("Nothing to answer.", 404)
    ok = bool((request.get_json(silent=True) or {}).get("ok", True))
    if ok and r["cents"] < 0 and balance(r["member_id"]) + r["cents"] < 0:
        return err("There is not enough money in the wallet for that.")
    db.run(update(db.wallet).where(db.wallet.c.id == wid).values(status="done" if ok else "declined"))
    push(r["member_id"], "wallet", {"title": f"\U0001F4B0 {g.m['name']} said {'yes' if ok else 'not now'}", "text": r["note"]})
    return jsonify(ok=True, balance=balance(r["member_id"]))


@app.post("/api/goals")
@need
def add_goal():
    b = request.get_json(silent=True) or {}
    title, c = clean(b.get("title"), 60), cents_of(b.get("target"))
    if not title or not c or c <= 0:
        return err("Name the goal and how much it costs.")
    gid = db.run(insert(db.goals).values(family_id=g.m["family_id"], member_id=g.m["id"], title=title, emoji=clean(b.get("emoji"), 8) or "\U0001F3AF", target=c, created=time.time()))
    return jsonify(id=gid)


@app.post("/api/goals/<int:gid>/save")
@need
def save_to_goal(gid):
    """Moves money from your wallet into a goal (or back out, with a minus)."""
    r = db.one(select(db.goals).where(db.goals.c.id == gid, db.goals.c.member_id == g.m["id"]))
    c = cents_of((request.get_json(silent=True) or {}).get("amount"))
    if not r or not c:
        return err("Not found.", 404)
    if c > 0 and balance(g.m["id"]) < c:
        return err("There is not enough in your wallet.")
    if c < 0 and r["saved"] + c < 0:
        return err("There is not that much saved.")
    db.run(update(db.goals).where(db.goals.c.id == gid).values(saved=r["saved"] + c))
    db.run(insert(db.wallet).values(family_id=g.m["family_id"], member_id=g.m["id"], cents=-c, note=("Saved for " if c > 0 else "Took back from ") + r["title"], by=g.m["id"], status="done", created=time.time()))
    if c > 0 and r["saved"] < r["target"] <= r["saved"] + c:
        tell_parents_of(g.m, "kudos", f"\U0001F3AF {g.m['name']} reached a goal", r["title"])
    return jsonify(ok=True, saved=r["saved"] + c, balance=balance(g.m["id"]))


@app.delete("/api/goals/<int:gid>")
@need
def drop_goal(gid):
    r = db.one(select(db.goals).where(db.goals.c.id == gid, db.goals.c.member_id == g.m["id"]))
    if not r:
        return err("Not found.", 404)
    if r["saved"]:
        db.run(insert(db.wallet).values(family_id=g.m["family_id"], member_id=g.m["id"], cents=r["saved"], note="Back from " + r["title"], by=g.m["id"], status="done", created=time.time()))
    db.run(delete(db.goals).where(db.goals.c.id == gid))
    return jsonify(ok=True)


# ---- mood check-ins
MOOD_FACES = {1: "\U0001F622", 2: "\U0001F615", 3: "\U0001F610", 4: "\U0001F642", 5: "\U0001F604"}


@app.post("/api/mood")
@need
def post_mood():
    b = request.get_json(silent=True) or {}
    mood = int(b["mood"]) if str(b.get("mood", "")).isdigit() and 1 <= int(b["mood"]) <= 5 else 0
    if not mood:
        return err("Pick how you feel.")
    day, _ = local_now(g.m)
    t = db.moods.c
    db.run(delete(db.moods).where(t.member_id == g.m["id"], t.day == day))
    db.run(insert(db.moods).values(family_id=g.m["family_id"], member_id=g.m["id"], day=day, mood=mood, note=clean(b.get("note"), 200), private=bool(b.get("private")), created=time.time()))
    if mood <= 2 and not b.get("private") and g.m["role"] == "child":
        tell_parents_of(g.m, "kudos", f"{MOOD_FACES[mood]} {g.m['name']} is having a hard day", "Maybe check in with them.")
    return jsonify(ok=True)


@app.get("/api/moods")
@need
def get_moods():
    """The last two weeks. A private day shows that they checked in, never how they felt."""
    m = g.m
    who = same_family(request.args.get("member")) if request.args.get("member") else m
    if not who or (who["id"] != m["id"] and m["role"] != "parent"):
        return err("Not found.", 404)
    t = db.moods.c
    rows = db.many(select(db.moods).where(t.member_id == who["id"]).order_by(t.day.desc()).limit(14))
    mine = who["id"] == m["id"]
    return jsonify(name=who["name"], days=[{"day": r["day"], "mood": r["mood"] if (mine or not r["private"]) else 0, "note": r["note"] if (mine or not r["private"]) else "",
                                            "private": bool(r["private"])} for r in rows])


# ---- safe walk: live until you get there
@app.post("/api/walk")
@need
def start_walk():
    b = request.get_json(silent=True) or {}
    p = db.one(select(db.places).where(db.places.c.id == b.get("place"), db.places.c.family_id == g.m["family_id"])) if str(b.get("place", "")).isdigit() else None
    minutes = max(5, min(240, int(b["minutes"]))) if str(b.get("minutes", "")).isdigit() else 20
    if not p:
        return err("Pick where you are going.")
    now = time.time()
    db.run(update(db.members).where(db.members.c.id == g.m["id"]).values(walk_to=p["id"], walk_until=now + minutes * 60, walk_started=now, walk_told=""))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} started a safe walk to {p['name']} (about {minutes} min). The family can follow it live.")
    push_family(g.m["family_id"], "walk", {"member": g.m["id"], "name": g.m["name"], "title": f"\U0001F6B6 {g.m['name']} is walking to {p['name']}", "text": f"About {minutes} minutes. Follow it on the map."}, skip=g.m["id"])
    return jsonify(ok=True, until=now + minutes * 60)


@app.post("/api/walk/stop")
@need
def stop_walk():
    end_walk(g.m, "stopped")
    return jsonify(ok=True)


def end_walk(m, how, place=None):
    if not m.get("walk_to"):
        return
    db.run(update(db.members).where(db.members.c.id == m["id"]).values(walk_to=0, walk_until=0, walk_told=""))
    text = f"{m['name']} got to {place['name']} safely" if how == "arrived" and place else f"{m['name']} ended their safe walk"
    say(m["family_id"], m["id"], text)
    push_family(m["family_id"], "walk_done", {"member": m["id"], "name": m["name"], "title": "\u2705 " + text, "text": ""}, skip=m["id"])


def place_reached(m, p):
    if m.get("walk_to") == p["id"]:
        end_walk(m, "arrived", p)
    t = db.expects.c
    for e in db.many(select(db.expects).where(t.member_id == m["id"], t.place_id == p["id"], t.done == False)):  # noqa: E712
        db.run(update(db.expects).where(t.id == e["id"]).values(done=True))
        push(e["set_by"], "expect_ok", {"member": m["id"], "name": m["name"], "title": f"\u2705 {m['name']} got to {p['name']}", "text": "Just now, " + (
            "on time" if time.time() <= e["by_at"] else "a little late")})


# ---- "tell me when they get there"
@app.post("/api/expect")
@parent_only
def expect():
    b = request.get_json(silent=True) or {}
    kid = same_family(b.get("member"))
    p = db.one(select(db.places).where(db.places.c.id == b.get("place"), db.places.c.family_id == g.m["family_id"])) if str(b.get("place", "")).isdigit() else None
    minutes = max(5, min(720, int(b["minutes"]))) if str(b.get("minutes", "")).isdigit() else 30
    if not kid or not p:
        return err("Pick who and where.")
    if not parent_may(g.m, kid, "location"):
        return denied(kid, "location")
    eid = db.run(insert(db.expects).values(family_id=g.m["family_id"], member_id=kid["id"], place_id=p["id"], by_at=time.time() + minutes * 60, set_by=g.m["id"], created=time.time()))
    push(kid["id"], "kudos", {"title": f"\U0001F4CD {g.m['name']} will be told when you get to {p['name']}", "text": f"Expected within {minutes} minutes."})
    return jsonify(id=eid)


# ---- speed alerts for a new driver (a parent sets a limit; the child sees it)
@app.post("/api/members/<int:mid>/speed")
@parent_only
def set_speed_limit(mid):
    kid = same_family(mid)
    if not kid or kid["role"] != "child":
        return err("Not found.", 404)
    v = (request.get_json(silent=True) or {}).get("limit")
    limit = max(0, min(200, int(v))) if str(v or "0").isdigit() else 0
    db.run(update(db.members).where(db.members.c.id == mid).values(speed_limit=limit, speed_strikes=0))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} set a speed alert for {kid['name']} at {limit} km/h" if limit else f"{g.m['name']} turned off {kid['name']}'s speed alert")
    push_family(g.m["family_id"], "member", {"id": mid})
    return jsonify(ok=True, limit=limit)


def check_speed(m, speed_ms):
    limit = m.get("speed_limit") or 0
    if not limit:
        return
    kmh = speed_ms * 3.6
    strikes = (m.get("speed_strikes") or 0) + 1 if kmh > limit else 0
    db.run(update(db.members).where(db.members.c.id == m["id"]).values(speed_strikes=strikes))
    if strikes >= 2 and time.time() - (m.get("speed_told") or 0) > 600:            # two readings in a row, then at most every 10 minutes
        db.run(update(db.members).where(db.members.c.id == m["id"]).values(speed_told=time.time()))
        tell_parents_of(m, "speeding", f"\U0001F697 {m['name']} is going {int(kmh)} km/h", f"Over the {limit} km/h alert.")


# ---- find my phone: make a phone ring loudly (your own, or your child's)
@app.post("/api/members/<int:mid>/ring")
@need
def ring_phone(mid):
    who = same_family(mid)
    if not who:
        return err("Not found.", 404)
    if who["id"] != g.m["id"] and not (g.m["role"] == "parent" and who["role"] == "child" and parent_may(g.m, who, "critical")) and not (g.m["role"] == "parent" and who["role"] == "parent"):
        return err("You can ring your own phone, or your child's.", 403)
    if limited(f"ring:{g.m['id']}", 8):
        return err("Slow down a little.", 429)
    push(mid, "ring", {"member": mid, "name": g.m["name"], "title": "\U0001F4F1 Find my phone", "text": f"{g.m['name']} is looking for this phone. Tap Found it to stop."})
    return jsonify(ok=True)


# ---- weather for the family's Home (Open-Meteo: free, no key, no account)
_weather = {}


def fetch_weather(lat, lng):
    import urllib.request
    url = (f"https://api.open-meteo.com/v1/forecast?latitude={lat:.3f}&longitude={lng:.3f}&current=temperature_2m,weather_code,wind_speed_10m"
           "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max&forecast_days=3&timezone=auto")
    with urllib.request.urlopen(url, timeout=6) as r:
        return json.loads(r.read().decode())


def weather_words(code):
    c = int(code or 0)
    return ("\u2600\ufe0f", "Clear") if c == 0 else ("\U0001F324\ufe0f", "Mostly sunny") if c in (1, 2) else ("\u2601\ufe0f", "Cloudy") if c == 3 else ("\U0001F32B\ufe0f", "Fog") if c in (45, 48) \
        else ("\U0001F326\ufe0f", "Drizzle") if 51 <= c <= 57 else ("\U0001F327\ufe0f", "Rain") if 61 <= c <= 67 or 80 <= c <= 82 else ("\u2744\ufe0f", "Snow") if 71 <= c <= 77 or c in (85, 86) \
        else ("\u26c8\ufe0f", "Thunderstorm") if c >= 95 else ("\U0001F324\ufe0f", "Mixed")


@app.get("/api/weather")
@need
def get_weather():
    h = home_place(g.m["family_id"])
    lat, lng = (h["lat"], h["lng"]) if h else (g.m["last_lat"], g.m["last_lng"])
    if lat is None:
        return err("Set a Home place to see the weather.", 404)
    key = f"{lat:.2f},{lng:.2f}"
    hit = _weather.get(key)
    if not hit or time.time() - hit[0] > 1200:
        try:
            hit = (time.time(), fetch_weather(lat, lng))
        except Exception:
            if not hit:
                return err("The weather is not available right now.", 503)
        _weather[key] = hit
    d = hit[1]
    cur, daily = d.get("current") or {}, d.get("daily") or {}
    icon, words = weather_words(cur.get("weather_code"))
    days = []
    for i, day in enumerate(daily.get("time") or []):
        di, dw = weather_words((daily.get("weather_code") or [0])[i])
        days.append({"day": day, "icon": di, "words": dw, "max": round((daily.get("temperature_2m_max") or [0])[i]), "min": round((daily.get("temperature_2m_min") or [0])[i]),
                     "rain": (daily.get("precipitation_probability_max") or [0])[i]})
    return jsonify(where="Home" if h else "Here", temp=round(cur.get("temperature_2m") or 0), icon=icon, words=words, wind=round(cur.get("wind_speed_10m") or 0), days=days)


# ---- the day timeline: where someone arrived and left
@app.get("/api/timeline")
@need
def timeline():
    who = same_family(request.args.get("member")) if request.args.get("member") else g.m
    if not who:
        return err("Not found.", 404)
    if who["id"] != g.m["id"] and not parent_may(g.m, who, "trail"):
        return denied(who, "trail")
    t = db.visits.c
    rows = db.many(select(db.visits).where(t.member_id == who["id"], t.at > time.time() - 86400).order_by(t.at))
    return jsonify(name=who["name"], items=[{"place": r["place"], "emoji": r["emoji"], "kind": r["kind"], "at": r["at"]} for r in rows])


# ---- a trail recorded with no internet: the phone keeps its positions and sends them when it is back online
@app.post("/api/locations")
@need
def post_locations():
    m = g.m
    if sharing_state(m) != "on" and not ((m.get("sos_until") or 0) > time.time()):
        return jsonify(ok=True, saved=0)
    now, saved, latest = time.time(), 0, None
    for pt in ((request.get_json(silent=True) or {}).get("points") or [])[:500]:
        try:
            lat, lng, at = float(pt["lat"]), float(pt["lng"]), float(pt["at"])
        except (KeyError, TypeError, ValueError):
            continue
        if not (-90 <= lat <= 90 and -180 <= lng <= 180 and now - 86400 < at <= now + 60):
            continue
        db.run(insert(db.locations).values(member_id=m["id"], lat=lat, lng=lng, acc=float(pt.get("acc") or 0), speed=float(pt.get("speed") or 0), at=min(at, now)))
        saved += 1
        if latest is None or at > latest[2]:
            latest = (lat, lng, at)
    if latest and latest[2] > (m["last_at"] or 0):
        db.run(update(db.members).where(db.members.c.id == m["id"]).values(last_lat=latest[0], last_lng=latest[1], last_at=min(latest[2], now)))
        push_family(m["family_id"], "location", {"member": m["id"], "lat": latest[0], "lng": latest[1], "acc": 0, "at": min(latest[2], now), "battery": m["battery"]}, skip=m["id"])
    return jsonify(ok=True, saved=saved)


# ---- share my trip with someone outside the family: a link that runs out
@app.post("/api/trip")
@need
def make_trip():
    hours = max(1, min(8, int((request.get_json(silent=True) or {}).get("hours") or 1)))
    token = "trip_" + secrets.token_urlsafe(18)
    db.run(delete(db.trips).where(db.trips.c.member_id == g.m["id"]))
    db.run(insert(db.trips).values(family_id=g.m["family_id"], member_id=g.m["id"], token_hash=db.hash_token(token), expires=time.time() + hours * 3600, created=time.time()))
    say(g.m["family_id"], g.m["id"], f"{g.m['name']} shared a live-location link for {hours} h with someone outside the family")
    return jsonify(url=f"{site_origin()}/trip/{token}", expires=time.time() + hours * 3600)


@app.delete("/api/trip")
@need
def stop_trip():
    db.run(delete(db.trips).where(db.trips.c.member_id == g.m["id"]))
    return jsonify(ok=True)


def trip_member(token):
    r = db.one(select(db.trips).where(db.trips.c.token_hash == db.hash_token(token)))
    if not r or r["expires"] < time.time():
        return None, None
    return r, db.one(select(db.members).where(db.members.c.id == r["member_id"]))


@app.get("/trip/<token>")
def trip_page(token):
    r, m = trip_member(token)
    return render_template("trip.html", ok=bool(m), name=m["name"] if m else "", token=token)


@app.get("/api/trip/<token>")
def trip_data(token):
    r, m = trip_member(token)
    if not m:
        return err("This link has run out.", 404)
    live = (sharing_state(m) == "on" or (m.get("sos_until") or 0) > time.time()) and m["last_lat"] is not None
    return jsonify(name=m["name"], expires=r["expires"], lat=m["last_lat"] if live else None, lng=m["last_lng"] if live else None, at=m["last_at"] if live else None)


# ---- kudos and the star of the week
@app.post("/api/kudos")
@need
def give_kudos():
    b = request.get_json(silent=True) or {}
    to = same_family(b.get("to"))
    if not to or to["id"] == g.m["id"]:
        return err("Pick someone to thank.")
    if limited(f"kudos:{g.m['id']}", 20):
        return err("Slow down a little.", 429)
    reason = clean(b.get("reason"), 120)
    emoji = clean(b.get("emoji"), 8) or "\u2b50"
    db.run(insert(db.kudos).values(family_id=g.m["family_id"], from_id=g.m["id"], to_id=to["id"], emoji=emoji, reason=reason, created=time.time()))
    push(to["id"], "kudos", {"title": f"{emoji} Kudos from {g.m['name']}", "text": reason})
    say(g.m["family_id"], g.m["id"], f"{emoji} {g.m['name']} gave {to['name']} kudos" + (f": {reason}" if reason else ""))
    return jsonify(ok=True)


@app.get("/api/kudos")
@need
def list_kudos():
    t = db.kudos.c
    week = time.time() - 7 * 86400
    rows = db.many(select(db.kudos).where(t.family_id == g.m["family_id"]).order_by(t.id.desc()).limit(40))
    names = {x["id"]: x["name"] for x in family_members(g.m["family_id"])}
    counts = {}
    for r in db.many(select(db.kudos).where(t.family_id == g.m["family_id"], t.created > week)):
        counts[r["to_id"]] = counts.get(r["to_id"], 0) + 1
    star = max(counts, key=counts.get) if counts else 0
    return jsonify(star={"member": star, "name": names.get(star, ""), "count": counts.get(star, 0)} if star else None, week={str(k): v for k, v in counts.items()},
                   recent=[{"from": names.get(r["from_id"], ""), "to": names.get(r["to_id"], ""), "emoji": r["emoji"], "reason": r["reason"], "at": r["created"]} for r in rows])


# ---- badges: earned from what people already do
@app.get("/api/badges")
@need
def badges():
    who = same_family(request.args.get("member")) if request.args.get("member") else g.m
    if not who:
        return err("Not found.", 404)
    mid = who["id"]
    def n(table, *cond):
        return len(db.many(select(table.c.id).where(*cond)))
    chores_done = n(db.chores, db.chores.c.done_by == mid, db.chores.c.status == "approved") if hasattr(db, "chores") else 0
    checkins = n(db.messages, db.messages.c.from_id == mid, db.messages.c.kind == "checkin")
    kudos_got = n(db.kudos, db.kudos.c.to_id == mid)
    kudos_gave = n(db.kudos, db.kudos.c.from_id == mid)
    moods = n(db.moods, db.moods.c.member_id == mid)
    goals_done = len([x for x in db.many(select(db.goals).where(db.goals.c.member_id == mid)) if x["saved"] >= x["target"]])
    walks = n(db.messages, db.messages.c.from_id == mid, db.messages.c.kind == "system", db.messages.c.body.like("%got to % safely%"))
    meals = n(db.meals, db.meals.c.added_by == mid)
    hw = n(db.homework, db.homework.c.member_id == mid, db.homework.c.done == True)  # noqa: E712
    list_ = [("helper", "\U0001F9F9", "Helper", "Finish 10 jobs", chores_done, 10), ("touch", "\U0001F4AC", "Always in touch", "Check in 20 times", checkins, 20),
             ("favourite", "\u2b50", "Family favourite", "Get 5 kudos", kudos_got, 5), ("kind", "\U0001F49B", "Kind heart", "Give 5 kudos", kudos_gave, 5),
             ("feelings", "\U0001F308", "Feelings friend", "Share your mood 7 times", moods, 7), ("saver", "\U0001F437", "Super saver", "Reach a savings goal", goals_done, 1),
             ("walker", "\U0001F6B6", "Safe walker", "Finish 3 safe walks", walks, 3), ("chef", "\U0001F468\u200d\U0001F373", "Chef in training", "Plan or suggest 5 meals", meals, 5),
             ("scholar", "\U0001F4DA", "Homework hero", "Finish 10 homework tasks", hw, 10)]
    return jsonify(name=who["name"], badges=[{"id": i, "emoji": e, "name": nm, "how": how, "have": min(v, goal), "goal": goal, "earned": v >= goal} for i, e, nm, how, v, goal in list_])


# ---- countdowns
@app.get("/api/countdowns")
@need
def get_countdowns():
    t = db.countdowns.c
    db.run(delete(db.countdowns).where(t.family_id == g.m["family_id"], t.at < time.time() - 86400))
    return jsonify(countdowns=[{"id": r["id"], "title": r["title"], "emoji": r["emoji"], "at": r["at"], "by": r["by"]}
                               for r in db.many(select(db.countdowns).where(t.family_id == g.m["family_id"]).order_by(t.at).limit(30))])


@app.post("/api/countdowns")
@need
def add_countdown():
    b = request.get_json(silent=True) or {}
    title = clean(b.get("title"), 60)
    try:
        at = float(b.get("at"))
    except (TypeError, ValueError):
        at = 0
    if not title or not (time.time() < at < time.time() + 3 * 365 * 86400):
        return err("Name it and pick a day in the future.")
    cid = db.run(insert(db.countdowns).values(family_id=g.m["family_id"], title=title, emoji=clean(b.get("emoji"), 8) or "\u23f3", at=at, by=g.m["id"], created=time.time()))
    push_family(g.m["family_id"], "countdowns", {"id": cid}, skip=g.m["id"])
    return jsonify(id=cid)


@app.delete("/api/countdowns/<int:cid>")
@need
def drop_countdown(cid):
    r = db.one(select(db.countdowns).where(db.countdowns.c.id == cid, db.countdowns.c.family_id == g.m["family_id"]))
    if not r or not (r["by"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    db.run(delete(db.countdowns).where(db.countdowns.c.id == cid))
    return jsonify(ok=True)


# ---- trusted adults
@app.get("/api/trusted")
@need
def get_trusted():
    return jsonify(people=[{"id": r["id"], "name": r["name"], "phone": r["phone"], "relation": r["relation"]}
                           for r in db.many(select(db.trusted).where(db.trusted.c.family_id == g.m["family_id"]).order_by(db.trusted.c.id))])


@app.post("/api/trusted")
@parent_only
def add_trusted():
    b = request.get_json(silent=True) or {}
    name, phone = clean(b.get("name"), 40), re.sub(r"[^0-9+ ()-]", "", str(b.get("phone") or ""))[:30]
    if not name or len(re.sub(r"\D", "", phone)) < 3:
        return err("Add a name and a phone number.")
    if len(db.many(select(db.trusted.c.id).where(db.trusted.c.family_id == g.m["family_id"]))) >= 10:
        return err("That is plenty (10).")
    tid = db.run(insert(db.trusted).values(family_id=g.m["family_id"], name=name, phone=phone, relation=clean(b.get("relation"), 30), by=g.m["id"], created=time.time()))
    push_family(g.m["family_id"], "trusted", {"id": tid}, skip=g.m["id"])
    return jsonify(id=tid)


@app.delete("/api/trusted/<int:tid>")
@parent_only
def drop_trusted(tid):
    db.run(delete(db.trusted).where(db.trusted.c.id == tid, db.trusted.c.family_id == g.m["family_id"]))
    return jsonify(ok=True)


# ---- homework
@app.get("/api/homework")
@need
def get_homework():
    m = g.m
    ids = [x["id"] for x in kids_of(m)] if m["role"] == "parent" else [m["id"]]
    t = db.homework.c
    names = {x["id"]: x["name"] for x in family_members(m["family_id"])}
    rows = db.many(select(db.homework).where(t.member_id.in_(ids), (t.done == False) | (t.created > time.time() - 7 * 86400)).order_by(t.due, t.id)) if ids else []  # noqa: E712
    return jsonify(items=[{"id": r["id"], "member": r["member_id"], "name": names.get(r["member_id"], ""), "subject": r["subject"], "title": r["title"], "due": r["due"], "done": bool(r["done"])} for r in rows])


@app.post("/api/homework")
@need
def add_homework():
    b = request.get_json(silent=True) or {}
    who = same_family(b.get("member")) if g.m["role"] == "parent" and b.get("member") else g.m
    title, due = clean(b.get("title"), 120), clean_day(b.get("due"))
    if not who or who["role"] != "child":
        return err("Homework is for a child.")
    if not title or not due:
        return err("What is it, and when is it due?")
    hid = db.run(insert(db.homework).values(family_id=g.m["family_id"], member_id=who["id"], subject=clean(b.get("subject"), 30), title=title, due=due, by=g.m["id"], created=time.time()))
    if who["id"] != g.m["id"]:
        push(who["id"], "homework_due", {"title": f"\U0001F4DA {g.m['name']} added homework", "text": f"{title} · due {due}"})
    return jsonify(id=hid)


@app.post("/api/homework/<int:hid>")
@need
def tick_homework(hid):
    r = db.one(select(db.homework).where(db.homework.c.id == hid, db.homework.c.family_id == g.m["family_id"]))
    if not r or not (r["member_id"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    done = bool((request.get_json(silent=True) or {}).get("done", True))
    db.run(update(db.homework).where(db.homework.c.id == hid).values(done=done))
    if done and r["member_id"] == g.m["id"]:
        tell_parents_of(g.m, "kudos", f"\U0001F4DA {g.m['name']} finished homework", r["title"])
    return jsonify(ok=True)


@app.delete("/api/homework/<int:hid>")
@need
def drop_homework(hid):
    r = db.one(select(db.homework).where(db.homework.c.id == hid, db.homework.c.family_id == g.m["family_id"]))
    if not r or not (r["member_id"] == g.m["id"] or g.m["role"] == "parent"):
        return err("Not found.", 404)
    db.run(delete(db.homework).where(db.homework.c.id == hid))
    return jsonify(ok=True)


# ---- bedtime wind-down (a reminder, never a lock)
@app.post("/api/members/<int:mid>/bedtime")
@need
def set_bedtime(mid):
    who = same_family(mid)
    if not who or not (who["id"] == g.m["id"] or (g.m["role"] == "parent" and who["role"] == "child")):
        return err("Not found.", 404)
    b = request.get_json(silent=True) or {}
    f = int(b["from"]) if str(b.get("from", "")).lstrip("-").isdigit() else -1
    t_ = int(b["to"]) if str(b.get("to", "")).lstrip("-").isdigit() else -1
    f, t_ = (max(-1, min(1439, f)), max(-1, min(1439, t_)))
    db.run(update(db.members).where(db.members.c.id == mid).values(bed_from=f, bed_to=t_, bed_told=""))
    if who["id"] != g.m["id"]:
        push(mid, "bedtime", {"title": "\U0001F319 Bedtime set", "text": f"{g.m['name']} set a wind-down reminder for you."})
    push_family(g.m["family_id"], "member", {"id": mid})
    return jsonify(ok=True)


# ---- the checks that run from the event stream (at most once a minute)
_last_watch = [0.0]


def check_watchers():
    now = time.time()
    if now - _last_watch[0] < 60:
        return
    _last_watch[0] = now
    t = db.members.c
    # safe walks that are late, or have stopped moving
    for m in db.many(select(db.members).where(t.walk_to > 0)):
        p = db.one(select(db.places).where(db.places.c.id == m["walk_to"]))
        if not p:
            continue
        told = m["walk_told"] or ""
        if now > (m["walk_until"] or 0) + 300 and "late" not in told:
            db.run(update(db.members).where(t.id == m["id"]).values(walk_told=told + "late"))
            tell_parents_of(m, "walk_late", f"\u26a0\ufe0f {m['name']} has not got to {p['name']} yet", "Their safe walk is taking longer than expected. Check the map or call them.")
        recent = db.many(select(db.locations).where(db.locations.c.member_id == m["id"], db.locations.c.at > now - 600).order_by(db.locations.c.at))
        if len(recent) >= 3 and now - (m["walk_started"] or now) > 600 and "still" not in told:
            spread = max(metres(recent[0]["lat"], recent[0]["lng"], r["lat"], r["lng"]) for r in recent)
            if spread < 40:
                db.run(update(db.members).where(t.id == m["id"]).values(walk_told=told + "still"))
                tell_parents_of(m, "walk_still", f"\u26a0\ufe0f {m['name']} has stopped moving", f"On their safe walk to {p['name']}, for 10 minutes.")
    # expected arrivals that did not happen
    e = db.expects.c
    for x in db.many(select(db.expects).where(e.done == False, e.told == False, e.by_at < now)):  # noqa: E712
        db.run(update(db.expects).where(e.id == x["id"]).values(told=True))
        kid = db.one(select(db.members).where(t.id == x["member_id"]))
        p = db.one(select(db.places).where(db.places.c.id == x["place_id"]))
        if kid and p:
            push(x["set_by"], "expect_late", {"member": kid["id"], "name": kid["name"], "title": f"\u26a0\ufe0f {kid['name']} is not at {p['name']} yet", "text": "You asked to be told. Check the map or call them."})
    # bedtime reminders, in each child's own time
    for m in db.many(select(db.members).where(t.bed_from >= 0)):
        day, minute = local_now(m)
        if 0 <= minute - m["bed_from"] < 10 and (m["bed_told"] or "") != day:
            db.run(update(db.members).where(t.id == m["id"]).values(bed_told=day))
            push(m["id"], "bedtime", {"title": "\U0001F319 Time to wind down", "text": "Screens away soon. Sleep well."})
    # homework due tomorrow
    h = db.homework.c
    tomorrow = (datetime.date.today() + datetime.timedelta(days=1)).isoformat()
    for x in db.many(select(db.homework).where(h.done == False, h.reminded == False, h.due <= tomorrow)):  # noqa: E712
        db.run(update(db.homework).where(h.id == x["id"]).values(reminded=True))
        push(x["member_id"], "homework_due", {"title": "\U0001F4DA Homework due soon", "text": f"{x['title']} · due {x['due']}"})


# ---------------------------------------------------------------- web push: alerts on an iPhone (or any browser) while the app is closed
PUSH_SUB = os.environ.get("VAPID_SUB", "https://homebase-family.onrender.com")
PUSH_SYNC = False                                                       # tests send straight away instead of on a thread
_vapid = {}


def vapid_pair():
    """The server's own push keys. Made once and kept in the database, so nobody has to create or paste anything."""
    if "pair" in _vapid:
        return _vapid["pair"]
    row = db.one(select(db.settings).where(db.settings.c.key == "vapid"))
    if not row:
        from cryptography.hazmat.primitives import serialization
        from py_vapid import Vapid
        v = Vapid()
        v.generate_keys()
        pem = v.private_pem().decode()
        pub = _b64.urlsafe_b64encode(v.public_key.public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)).rstrip(b"=").decode()
        try:
            db.run(insert(db.settings).values(key="vapid", value=json.dumps({"pem": pem, "public": pub})))
        except Exception:                                                # another process saved its keys first: use those
            pass
        row = db.one(select(db.settings).where(db.settings.c.key == "vapid"))
    _vapid["pair"] = json.loads(row["value"])
    return _vapid["pair"]


def web_note(kind, p):
    """What to say on the lock screen for an event, or None for the quiet bookkeeping ones (locations, refreshes)."""
    name = p.get("name") or "Homebase"
    if kind == "message":
        mk = p.get("kind")
        if mk == "system" or (p.get("quiet") and mk not in ("sos", "pickup")):
            return None
        if mk == "sos":
            return {"title": f"\U0001F6A8 {name} needs help", "body": p.get("body") or "Open Homebase to see where they are.", "tag": "sos", "urgent": True}
        if mk == "pickup":
            return {"title": f"\U0001F697 {name} needs a pickup", "body": p.get("body") or "Open Homebase to see where they are.", "tag": "pickup", "urgent": True}
        if mk == "heart":
            return {"title": f"\u2764\ufe0f {name}", "body": "is thinking of you", "tag": f"msg-{p.get('from')}"}
        if mk == "gif":
            return {"title": name, "body": "\U0001F39E\ufe0f sent a GIF", "tag": f"msg-{p.get('from')}"}
        if mk == "voice":
            return {"title": name, "body": "\U0001F3A4 sent a voice note", "tag": f"msg-{p.get('from')}"}
        if mk == "image":
            return {"title": name, "body": "\U0001F4F7 sent a photo", "tag": f"msg-{p.get('from')}"}
        if mk == "game":
            return {"title": f"\U0001F3AE {name}", "body": p.get("body") or "A family game has started. Open Homebase to join.", "tag": "game"}
        return {"title": name, "body": p.get("body") or "", "tag": f"msg-{p.get('from')}"}
    if kind == "call":
        return {"title": f"\U0001F4DE {name} is calling", "body": "Open Homebase to answer", "tag": f"call-{p.get('id')}", "urgent": True}
    if kind == "ping":
        return {"title": f"\U0001F4CD {name} asks where you are", "body": "Open Homebase to answer, or say not now.", "tag": f"ping-{p.get('id')}"}
    if kind in ("walk_late", "walk_still", "expect_late", "speeding", "ring", "walk", "walk_done", "expect_ok", "kudos", "bedtime", "homework_due", "wallet_request"):
        return {"title": p.get("title") or name, "body": p.get("text") or "", "tag": f"{kind}-{p.get('member') or p.get('id') or ''}", "urgent": kind in ("walk_late", "walk_still", "expect_late", "speeding", "ring")}
    if kind == "risk_alert":
        return {"title": f"\u26a0\ufe0f Safety alert for {p.get('name')}", "body": f"A message looked like {p.get('what')}. Open Mellow to see it.", "tag": f"risk-{p.get('id')}", "urgent": True}
    if kind == "zone_request":
        return {"title": f"\U0001F4CD {name} asks for a zone", "body": p.get("place") or "", "tag": f"zone-{p.get('id')}"}
    if kind == "offline":
        return {"title": f"\U0001F4F5 {name}'s phone went offline", "body": f"Not heard from for {p.get('minutes')} minutes" + (f" (battery was {p.get('battery')}%)" if (p.get("battery") or -1) >= 0 else ""), "tag": f"off-{p.get('member')}"}
    if kind == "online":
        return {"title": f"\u2705 {name}'s phone is back online", "body": "", "tag": f"off-{p.get('member')}"}
    if kind == "critical":
        return {"title": f"\U0001F6A8 {name} needs you to check in", "body": "Open Mellow and tap I'm OK.", "tag": "critical", "urgent": True}
    if kind == "critical_ok":
        return {"title": f"\u2705 {name} is OK", "body": "They answered your Critical Check-In.", "tag": f"crit-{p.get('member')}"}
    if kind == "meal_suggest":
        return {"title": f"\U0001F4A1 {name} suggests a meal", "body": p.get("title") or "", "tag": f"meal-{p.get('id')}"}
    if kind == "plan_soon":
        return {"title": f"{p.get('emoji') or ''} Starting soon".strip(), "body": p.get("title") or "", "tag": f"plan-{p.get('id')}"}
    if kind == "plan" and p.get("new"):
        return {"title": f"\U0001F4C5 {name} planned something", "body": p.get("title") or "", "tag": f"plan-{p.get('id')}"}
    if kind == "poll" and p.get("new"):
        return {"title": f"\U0001F5F3\ufe0f {name} asks the family", "body": p.get("question") or "", "tag": f"poll-{p.get('id')}"}
    if kind == "reward":
        status = p.get("status")
        title = {"pending": f"\U0001F381 {name} asked for a reward", "approved": f"\U0001F381 {name} said yes", "declined": f"{name} said not now"}.get(status)
        if title:
            extra = f" (+{p['minutes']} min screen time today)" if status == "approved" and p.get("minutes") else ""
            return {"title": title, "body": (p.get("title") or "") + extra, "tag": f"reward-{p.get('id')}"}
    if kind == "birthday":
        return {"title": f"\U0001F382 Happy birthday, {name}!" if p.get("self") else f"\U0001F382 It's {name}'s birthday", "body": "Your family is thinking of you." if p.get("self") else "Send them some love today.", "tag": f"bday-{p.get('id')}"}
    if kind == "nudge":
        return {"title": "Time for a break?", "body": p.get("text") or "", "tag": "nudge"}
    if kind == "gmessage":
        return {"title": f"{name} \u00b7 {p.get('group') or 'friends'}", "body": p.get("body") if p.get("kind") != "gif" else "\U0001F39E\ufe0f sent a GIF", "tag": f"group-{p.get('gid')}"}
    if kind == "group_flag":
        return {"title": f"\u26a0\ufe0f {name}'s message was censored", "body": f"In {p.get('group')}: {p.get('cat') or 'rude'} language. Open Homebase to read it.", "tag": f"flag-{p.get('gid')}", "urgent": True}
    if kind == "group_request":
        return {"title": f"\U0001F465 {name} wants to join {p.get('group')}", "body": "Open Homebase to say yes or not now.", "tag": f"request-{p.get('gid')}"}
    if kind == "notice" and p.get("text"):
        return {"title": f"\U0001F4E2 {name}", "body": p["text"], "tag": "notice"}
    return None


def send_web_push(member_id, note):
    subs = db.many(select(db.push_subs).where(db.push_subs.c.member_id == member_id))
    if not subs:
        return
    if PUSH_SYNC:
        _deliver(subs, note)
    else:
        threading.Thread(target=_deliver, args=(subs, note), daemon=True).start()


def _deliver(subs, note):
    try:
        import pywebpush
        from py_vapid import Vapid
        key = Vapid.from_pem(vapid_pair()["pem"].encode())
    except Exception:
        return
    payload = json.dumps(note, ensure_ascii=False)
    for sub in subs:
        try:
            pywebpush.webpush({"endpoint": sub["endpoint"], "keys": {"p256dh": sub["p256dh"], "auth": sub["auth"]}}, payload, vapid_private_key=key,
                              vapid_claims={"sub": PUSH_SUB}, ttl=3600, timeout=10)
        except Exception as e:
            code = getattr(getattr(e, "response", None), "status_code", 0)
            if code in (404, 410):                                      # the browser says this address is gone for good
                db.run(delete(db.push_subs).where(db.push_subs.c.id == sub["id"]))


@app.get("/api/push/key")
@need
def push_key():
    return jsonify(key=vapid_pair()["public"])


@app.post("/api/push/subscribe")
@need
def push_subscribe():
    sub = (request.get_json(silent=True) or {}).get("subscription") or {}
    keys = sub.get("keys") or {}
    endpoint, p256dh, auth = str(sub.get("endpoint") or ""), str(keys.get("p256dh") or ""), str(keys.get("auth") or "")
    if not endpoint.startswith("https://") or len(endpoint) > 700 or not p256dh or not auth or len(p256dh) > 200 or len(auth) > 100:
        return err("That browser did not give a usable address for alerts.")
    t = db.push_subs.c
    db.run(delete(db.push_subs).where(t.endpoint == endpoint))               # a device belongs to one person at a time
    mine = db.many(select(t.id).where(t.member_id == g.m["id"]).order_by(t.id))
    for old in mine[:max(0, len(mine) - 9)]:                                 # keep their ten newest devices
        db.run(delete(db.push_subs).where(t.id == old["id"]))
    db.run(insert(db.push_subs).values(member_id=g.m["id"], endpoint=endpoint, p256dh=p256dh, auth=auth, created=time.time(), ua=clean(request.headers.get("User-Agent"), 80)))
    return jsonify(ok=True)


@app.post("/api/push/unsubscribe")
@need
def push_unsubscribe():
    endpoint = str((request.get_json(silent=True) or {}).get("endpoint") or "")
    db.run(delete(db.push_subs).where(db.push_subs.c.endpoint == endpoint, db.push_subs.c.member_id == g.m["id"]))
    return jsonify(ok=True)


# ---------------------------------------------------------------- the calendar as a file other calendars understand
def ics_text(value):
    return str(value or "").replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n")


def ics_fold(line):
    """Lines longer than 75 characters continue on the next line, which starts with a space."""
    out, first = [], True
    while len(line) > 75:
        out.append(line[:75] if first else " " + line[:74])
        line, first = (line[75:] if first else line[74:]), False
    out.append(line if first else " " + line)
    return out


@app.get("/api/calendar.ics")
@need
def calendar_file():
    fid, now = g.m["family_id"], time.time()
    fam = db.one(select(db.families).where(db.families.c.id == fid))
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(now))
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Homebase//Family//EN", "CALSCALE:GREGORIAN", "METHOD:PUBLISH", "X-WR-CALNAME:" + ics_text(f"{fam['name']} (Homebase)")]
    for r in db.many(select(db.plans).where(db.plans.c.family_id == fid, db.plans.c.start >= now - 86400, db.plans.c.start < now + 365 * 86400).order_by(db.plans.c.start).limit(500)):
        lines += ["BEGIN:VEVENT", f"UID:plan-{r['id']}@homebase", f"DTSTAMP:{stamp}", "DTSTART:" + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(r["start"])),
                  "DTEND:" + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(r["start"] + 3600)), "SUMMARY:" + ics_text(f"{r['emoji']} {r['title']}".strip())]
        if r["note"]:
            lines.append("DESCRIPTION:" + ics_text(r["note"]))
        if r["lat"] is not None and r["lng"] is not None:
            lines += [f"GEO:{r['lat']:.6f};{r['lng']:.6f}", "LOCATION:" + ics_text(r["note"] or r["title"])]
        lines.append("END:VEVENT")
    today = datetime.date.today()
    for r in db.many(select(db.meals).where(db.meals.c.family_id == fid, db.meals.c.day >= (today - datetime.timedelta(days=1)).isoformat(), db.meals.c.day <= (today + datetime.timedelta(days=365)).isoformat()).order_by(db.meals.c.day).limit(500)):
        lines += ["BEGIN:VEVENT", f"UID:meal-{r['id']}@homebase", f"DTSTAMP:{stamp}", "DTSTART;VALUE=DATE:" + r["day"].replace("-", ""),
                  "SUMMARY:" + ics_text(f"\U0001F37D\ufe0f {r['slot'].capitalize()}: {r['title']}"), "END:VEVENT"]
    year = time.gmtime(now).tm_year
    for m in family_members(fid):
        if not m["birthday"]:
            continue
        month, day = m["birthday"].split("-")
        y = 2024 if m["birthday"] == "02-29" else year                      # a leap year, so 29 February is a real date; calendars handle the other years
        lines += ["BEGIN:VEVENT", f"UID:birthday-{m['id']}@homebase", f"DTSTAMP:{stamp}", f"DTSTART;VALUE=DATE:{y}{month}{day}", "RRULE:FREQ=YEARLY",
                  "SUMMARY:" + ics_text(f"\U0001F382 {m['name']}'s birthday"), "END:VEVENT"]
    lines.append("END:VCALENDAR")
    body = "\r\n".join(part for line in lines for part in ics_fold(line)) + "\r\n"
    return app.response_class(body, mimetype="text/calendar", headers={"Content-Disposition": 'attachment; filename="homebase-family.ics"', "Cache-Control": "no-store"})


# ---------------------------------------------------------------- announcements: a parent tells the whole family something, and it ends by itself
@app.post("/api/notice")
@parent_only
def set_notice():
    b = request.get_json(silent=True) or {}
    text = clean(b.get("text"), 200)
    if not text:
        return err("Write what you want the family to know.")
    try:
        hours = max(1, min(72, int(b.get("hours") or 24)))
    except (TypeError, ValueError):
        return err("Hours should be a number.")
    if limited(f"notice:{g.m['id']}", 10):
        return err("Slow down a little.", 429)
    fid = g.m["family_id"]
    db.run(update(db.families).where(db.families.c.id == fid).values(notice=text, notice_until=time.time() + hours * 3600, notice_by=g.m["id"]))
    say(fid, g.m["id"], f"\U0001F4E2 {g.m['name']}: {text}")                    # it stays in the chat history too
    push_family(fid, "notice", {"text": text, "name": g.m["name"], "emoji": g.m["emoji"]}, skip=g.m["id"])
    return jsonify(ok=True)


@app.delete("/api/notice")
@parent_only
def clear_notice():
    fid = g.m["family_id"]
    db.run(update(db.families).where(db.families.c.id == fid).values(notice="", notice_until=0, notice_by=0))
    push_family(fid, "notice", {"cleared": True}, skip=g.m["id"])
    return jsonify(ok=True)


# ---------------------------------------------------------------- "please update Homebase": one clear message, not a pile of pop-ups
@app.post("/api/members/<int:mid>/update-nudge")
@parent_only
def update_nudge(mid):
    target = same_family(mid)
    if not target or target["id"] == g.m["id"]:
        return err("Not found.", 404)
    if not parent_may(g.m, target, "messages"):
        return denied(target, "messages")
    if limited(f"updnudge:{target['id']}", 1, window=1800):
        return err(f"{target['name']} was asked a moment ago. Give it half an hour.", 429)
    scheme = (request.headers.get("X-Forwarded-Proto") or request.scheme).split(",")[0].strip()
    body = f"\U0001F4F2 Please update Homebase. On Android open {scheme}://{request.host}/download and install it. Windows computers update by themselves."
    msg_id = say(g.m["family_id"], g.m["id"], body, kind="text", to_id=target["id"])
    row = db.one(select(db.messages).where(db.messages.c.id == msg_id))
    push(target["id"], "message", {**msg_view(row), "name": g.m["name"], "emoji": g.m["emoji"], "quiet": in_quiet(target), "urgent": False})
    return jsonify(ok=True)


# ---------------------------------------------------------------- birthdays: the person chooses the date, the whole family hears about it on the day
MONTH_DAYS = (31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
_last_bday = [0.0]


@app.post("/api/birthday")
@need
def set_birthday():
    b = request.get_json(silent=True) or {}
    try:
        month, day = int(b.get("month") or 0), int(b.get("day") or 0)
        year = int(b.get("year") or 0)
    except (TypeError, ValueError):
        return err("Pick a month and a day.")
    this_year = datetime.date.today().year
    if year and not (this_year - 110 <= year <= this_year):
        return err("That year does not look right.")
    if month == 0 and day == 0:
        value = ""
    elif 1 <= month <= 12 and 1 <= day <= MONTH_DAYS[month - 1]:
        value = f"{month:02d}-{day:02d}"
    else:
        return err("That is not a real date.")
    vals = dict(birthday=value, bday_year=0)
    if year or not value:
        vals["birth_year"] = year if value else 0
    db.run(update(db.members).where(db.members.c.id == g.m["id"]).values(**vals))
    push_family(g.m["family_id"], "member", {"id": g.m["id"], "birthday": value or None}, skip=g.m["id"])
    return jsonify(birthday=value or None, age=age_of(value, vals.get("birth_year", g.m.get("birth_year") or 0)))


def age_of(birthday, year):
    """How old someone is today, from their birthday ("MM-DD") and birth year, or None if we do not know."""
    if not birthday or not year:
        return None
    today = datetime.date.today()
    month, day = int(birthday[:2]), int(birthday[3:])
    return today.year - year - (1 if (today.month, today.day) < (month, day) else 0)


def remind_birthdays():
    """On someone's birthday (by their own clock) everyone hears about it, once a year. Runs from the event stream, at most every 5 minutes."""
    now = time.time()
    if now - _last_bday[0] < 300:
        return
    _last_bday[0] = now
    for m in db.many(select(db.members).where(db.members.c.birthday != "")):
        local = time.gmtime(now - (m["tz_offset"] or 0) * 60)
        target = "02-28" if m["birthday"] == "02-29" and not calendar.isleap(local.tm_year) else m["birthday"]
        if target != time.strftime("%m-%d", local) or m["bday_year"] == local.tm_year:
            continue
        db.run(update(db.members).where(db.members.c.id == m["id"]).values(bday_year=local.tm_year))
        say(m["family_id"], m["id"], f"\U0001F382 It's {m['name']}'s birthday today!")
        for x in family_members(m["family_id"]):
            push(x["id"], "birthday", {"id": m["id"], "name": m["name"], "emoji": m["emoji"], "self": x["id"] == m["id"]})


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
    housekeeping()
    while True:
        rows = db.many(select(db.events).where(db.events.c.member_id == m["id"], db.events.c.id > after).order_by(db.events.c.id).limit(100))
        if rows or time.time() >= deadline:
            break
        # sleep until something is pushed (or 5 s as a safety net), instead of asking the database every 0.6 s:
        # every query goes out to Neon, and Render's free plan counts that traffic
        with EVENTS:
            EVENTS.wait(min(5, max(.05, deadline - time.time())))
        housekeeping()
    if int(time.time()) % 40 == 0:
        db.run(delete(db.events).where(db.events.c.created < time.time() - 2 * 86400))
    return jsonify(cursor=rows[-1]["id"] if rows else after, events=[{"id": r["id"], "type": r["type"], "payload": json.loads(r["payload"]), "at": r["created"]} for r in rows])


_house = {"at": 0.0}
_house_lock = threading.Lock()


def housekeeping():
    """Missed calls, plan and birthday reminders, offline and watcher checks: at most every 15 s for the whole server."""
    if time.time() - _house["at"] < 15 or not _house_lock.acquire(blocking=False):
        return
    try:
        _house["at"] = time.time()
        expire_calls()
        remind_plans()
        remind_birthdays()
        check_offline()
        check_watchers()
    finally:
        _house_lock.release()


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


STALE_CALL = 3 * 3600                                                 # an "active" call this old was never hung up properly


def busy(member_id):
    live = db.many(select(db.calls).where(db.calls.c.status.in_(("ringing", "active")), (db.calls.c.caller_id == member_id) | (db.calls.c.callee_id == member_id)))
    now = time.time()
    return any((c["status"] == "active" and (c["answered"] or c["created"]) > now - STALE_CALL) or (c["status"] == "ringing" and c["created"] > now - RING_SECONDS) for c in live)


def clear_stuck_calls(caller_id, callee_id):
    """A call that dropped without anyone hanging up stays "active" and makes people look busy forever.
    Starting a new call clears what cannot be real: the caller's own old calls (they are not on a call if they are placing a new one),
    and any old call between the same two people."""
    t = db.calls.c
    now = time.time()
    for c in db.many(select(db.calls).where(t.status.in_(("ringing", "active")), (t.caller_id == caller_id) | (t.callee_id == caller_id) | (t.caller_id == callee_id) | (t.callee_id == callee_id))):
        pair = {c["caller_id"], c["callee_id"]} == {caller_id, callee_id}
        mine = caller_id in (c["caller_id"], c["callee_id"])
        if c["status"] == "ringing" and c["created"] > now - RING_SECONDS:
            continue                                                  # a call that is really ringing right now is left alone
        stale = c["status"] == "active" and (c["answered"] or c["created"]) < now - STALE_CALL
        if pair or mine or stale:
            db.run(update(db.calls).where(t.id == c["id"]).values(status="ended", ended=now))
            for who in (c["caller_id"], c["callee_id"]):
                push(who, "call_update", {"id": c["id"], "status": "ended"})


@app.post("/api/calls")
@need
def start_call():
    m, b = g.m, request.get_json(silent=True) or {}
    callee = same_family(b.get("to"))
    offer = str(b.get("offer") or "")[:100000]
    if not callee or callee["id"] == m["id"] or not offer:
        return err("Pick someone to call.")
    if not parent_may(m, callee, "calls"):
        return denied(callee, "calls")
    urgent = bool(b.get("urgent"))
    if in_quiet(callee) and not urgent:
        return err(f"{callee['name']} has quiet hours on right now. You can ring anyway if it is important.", 409, quiet=True)
    clear_stuck_calls(m["id"], callee["id"])
    if busy(callee["id"]):
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
    answer = str((request.get_json(silent=True) or {}).get("answer") or "")[:100000]
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
    return render_template("index.html", has_apk=has_apk, version=VERSION_CODE, version_name=VERSION_NAME)


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
    return render_template("update.html", version=VERSION_CODE, version_name=VERSION_NAME, changelog=CHANGELOG)


@app.get("/iphone")
def iphone_page():
    """How to put Homebase on an iPhone or iPad (a Home Screen app, with alerts), and what Apple does not allow."""
    return render_template("iphone.html", version=VERSION_CODE)


@app.get("/qr.svg")
def qr_svg():
    """A QR code that opens Homebase, to point a phone camera at. Made for whatever address this server is reached on."""
    try:
        import segno
    except ImportError:
        return err("QR codes are not available here.", 404)
    scheme = (request.headers.get("X-Forwarded-Proto") or request.scheme).split(",")[0].strip()
    buf = io.BytesIO()
    segno.make(f"{scheme}://{request.host}/app", error="m").save(buf, kind="svg", scale=8, border=2, dark="#17171a", light="#ffffff")
    return app.response_class(buf.getvalue(), mimetype="image/svg+xml", headers={"Cache-Control": "public, max-age=3600"})


APK_URL = os.environ.get("APK_URL", "https://huggingface.co/datasets/Death242/downloads/resolve/main/mellow.apk")


@app.get("/download")
def download():
    """The Android app. It is 55 MB, so it is served from Hugging Face: Render's free plan only includes 5 GB a month."""
    if APK_URL:
        return redirect(APK_URL, 302)
    path = os.path.join(BASE, "static")
    if not os.path.exists(os.path.join(path, "homebase.apk")):
        return err("The app file is not uploaded yet.", 404)
    return send_from_directory(path, "homebase.apk", as_attachment=True, mimetype="application/vnd.android.package-archive")


# ---------------------------------------------------------------- Noor, the Quran app: linked accounts, reading progress, tasks from parents
NOOR_URL = os.environ.get("NOOR_URL", "https://death242-noor.static.hf.space").rstrip("/")             # Noor's pages are static on Hugging Face now
NOOR_ORIGINS = {NOOR_URL, "https://death242-noor.static.hf.space", "https://noor-quran-xuzt.onrender.com", "http://localhost:5185", "http://127.0.0.1:5185"}
SURAH_AYAHS = [7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6]


def noor_member():
    """The member a Noor key belongs to, and the link row; or (None, None)."""
    token = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    if not token.startswith("hbn_"):
        return None, None
    row = db.one(select(db.noor_links).where(db.noor_links.c.token_hash == db.hash_token(token)))
    if not row:
        return None, None
    m = db.one(select(db.members).where(db.members.c.id == row["member_id"]))
    if not m:
        return None, None
    if time.time() - (row["last_used"] or 0) > 60:
        db.run(update(db.noor_links).where(db.noor_links.c.id == row["id"]).values(last_used=time.time()))
    return m, row


def noor_need(fn):
    def wrapped(*a, **k):
        m, row = noor_member()
        if not m:
            return err("This Noor link is not valid any more. Link Noor again from Mellow.", 401)
        g.m, g.noor = m, row
        return fn(*a, **k)
    wrapped.__name__ = fn.__name__
    return wrapped


@app.after_request
def noor_cors(resp):
    origin = request.headers.get("Origin", "")
    if request.path.startswith("/api/noor-app/") and origin in NOOR_ORIGINS:
        resp.headers["Access-Control-Allow-Origin"] = origin
        resp.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type"
        resp.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
        resp.headers["Access-Control-Max-Age"] = "600"
        resp.headers["Vary"] = "Origin"
    return resp


@app.route("/api/noor-app/<path:rest>", methods=["OPTIONS"])
def noor_preflight(rest):
    return app.response_class("", status=204)


def task_view(t, names):
    return {"id": t["id"], "kind": t["kind"], "surah": t["surah"], "from": t["ayah_from"], "upto": t["ayah_to"], "note": t["note"] or "", "due": t["due"] or "",
            "by": names.get(t["by_id"], ""), "by_id": t["by_id"], "to_id": t["to_id"], "to_name": names.get(t["to_id"], ""), "created": t["created"],
            "done": bool(t["done_at"]), "done_at": t["done_at"] or 0, "score": t["score"] if t["score"] is not None else -1}


def noor_family(m):
    """Everyone in the family with their Noor progress (if linked), and the family's reading tasks."""
    people = family_members(m["family_id"])
    names = {p["id"]: p["name"] for p in people}
    links = {r["member_id"]: r for r in db.many(select(db.noor_links).where(db.noor_links.c.family_id == m["family_id"]))}
    out = []
    for p in people:
        r = links.get(p["id"])
        try:
            st = json.loads(r["stats"]) if r and r["stats"] else {}
        except ValueError:
            st = {}
        out.append({"id": p["id"], "name": p["name"], "emoji": p["emoji"], "role": p["role"], "linked": bool(r), "synced": (r or {}).get("synced") or 0, "stats": st})
    rows = db.many(select(db.noor_tasks).where(db.noor_tasks.c.family_id == m["family_id"]).order_by(db.noor_tasks.c.id.desc()).limit(80))
    if m["role"] != "parent":
        rows = [t for t in rows if t["to_id"] == m["id"] or t["by_id"] == m["id"]]
    return {"members": out, "tasks": [task_view(t, names) for t in rows], "me": m["id"], "role": m["role"], "noor_url": NOOR_URL, "khatma": khatma_view(m["family_id"], names)}


def current_khatma(fid):
    return db.one(select(db.noor_khatmas).where(db.noor_khatmas.c.family_id == fid).order_by(db.noor_khatmas.c.id.desc()).limit(1))


def khatma_view(fid, names):
    k = current_khatma(fid)
    if not k:
        return None
    parts = {r["juz"]: r for r in db.many(select(db.noor_juz).where(db.noor_juz.c.khatma_id == k["id"]))}
    juz = [{"juz": j, "who": (parts.get(j) or {}).get("member_id") or 0, "name": names.get((parts.get(j) or {}).get("member_id") or 0, ""),
            "done": bool((parts.get(j) or {}).get("done_at"))} for j in range(1, 31)]
    return {"id": k["id"], "created": k["created"], "done_at": k["done_at"] or 0, "by": names.get(k["by_id"], ""), "juz": juz, "finished": sum(1 for x in juz if x["done"])}


def khatma_act(m, d):
    """start | take | drop | done | undo, on the family's khatma."""
    act, fid = d.get("action"), m["family_id"]
    k = current_khatma(fid)
    if act == "start":
        if k and not k["done_at"]:
            return err("Your family already has a khatma going.")
        db.run(insert(db.noor_khatmas).values(family_id=fid, by_id=m["id"], created=time.time()))
        say(fid, 0, f"\U0001F4D6 {m['name']} started a family khatma: pick a juz in Noor or Mellow")
        return jsonify(ok=True)
    if not k or k["done_at"]:
        return err("Start a family khatma first.")
    j = int(d.get("juz") or 0) if str(d.get("juz") or "").isdigit() else 0
    if not 1 <= j <= 30:
        return err("Pick a juz from 1 to 30.")
    row = db.one(select(db.noor_juz).where(db.noor_juz.c.khatma_id == k["id"], db.noor_juz.c.juz == j))
    mine = row and row["member_id"] == m["id"]
    if act == "take":
        if row and row["member_id"] and not mine:
            return err("Someone already took that juz.")
        if not row:
            db.run(insert(db.noor_juz).values(khatma_id=k["id"], juz=j, member_id=m["id"]))
        return jsonify(ok=True)
    if act == "drop":
        if row and (mine or m["role"] == "parent") and not row["done_at"]:
            db.run(delete(db.noor_juz).where(db.noor_juz.c.id == row["id"]))
        return jsonify(ok=True)
    if act in ("done", "undo"):
        if not row:
            db.run(insert(db.noor_juz).values(khatma_id=k["id"], juz=j, member_id=m["id"]))
            row = db.one(select(db.noor_juz).where(db.noor_juz.c.khatma_id == k["id"], db.noor_juz.c.juz == j))
        elif row["member_id"] and not mine and m["role"] != "parent":
            return err("That juz is someone else's.")
        db.run(update(db.noor_juz).where(db.noor_juz.c.id == row["id"]).values(done_at=time.time() if act == "done" else 0))
        if act == "done":
            left = 30 - len([r for r in db.many(select(db.noor_juz).where(db.noor_juz.c.khatma_id == k["id"])) if r["done_at"]])
            if left == 0:
                db.run(update(db.noor_khatmas).where(db.noor_khatmas.c.id == k["id"]).values(done_at=time.time()))
                say(fid, 0, "\U0001F389 The family finished a whole khatma of the Quran together. Masha'Allah!")
            elif left in (20, 10, 5, 1):
                say(fid, 0, f"\U0001F4D6 Family khatma: {30 - left} of 30 juz read, {left} to go")
        return jsonify(ok=True)
    return err("Unknown action.")


@app.post("/api/noor/khatma")
@need
def noor_khatma_member():
    return khatma_act(g.m, request.get_json(silent=True) or {})


@app.post("/api/noor-app/khatma")
@noor_need
def noor_khatma_app():
    return khatma_act(g.m, request.get_json(silent=True) or {})


def make_task(m, d):
    if m["role"] != "parent":
        return None, err("Only a parent can give reading tasks.", 403)
    who = db.one(select(db.members).where(db.members.c.id == int(d.get("to") or 0))) if str(d.get("to") or "").isdigit() else None
    if not who or who["family_id"] != m["family_id"]:
        return None, err("Pick someone in your family.")
    kind = d.get("kind") if d.get("kind") in ("read", "memorize", "revise") else "read"
    s = int(d.get("surah") or 0) if str(d.get("surah") or "").isdigit() else 0
    if not 1 <= s <= 114:
        return None, err("Pick a surah.")
    n = SURAH_AYAHS[s - 1]
    a = max(1, min(n, int(d.get("from") or 1) if str(d.get("from") or "1").isdigit() else 1))
    b = max(a, min(n, int(d.get("to_ayah") or n) if str(d.get("to_ayah") or n).isdigit() else n))
    open_ = db.many(select(db.noor_tasks).where(db.noor_tasks.c.family_id == m["family_id"], db.noor_tasks.c.done_at == 0))
    if len(open_) >= 60:
        return None, err("There are a lot of open tasks. Finish or remove some first.")
    due = str(d.get("due") or "")[:10]
    tid = db.run(insert(db.noor_tasks).values(family_id=m["family_id"], by_id=m["id"], to_id=who["id"], kind=kind, surah=s, ayah_from=a, ayah_to=b,
                                              note=str(d.get("note") or "")[:200], due=due if re.match(r"^\d{4}-\d{2}-\d{2}$", due) else "", created=time.time()))
    verb = {"read": "read", "memorize": "memorize", "revise": "revise"}[kind]
    push(who["id"], "noor_task", {"id": tid, "name": m["name"], "kind": kind, "surah": s, "from": a, "to": b})
    say(m["family_id"], 0, f"\U0001F4D6 {m['name']} asked {who['name']} to {verb} surah {s}, ayahs {a}-{b}")
    return tid, None


def finish_task(m, tid, score=-1):
    t = db.one(select(db.noor_tasks).where(db.noor_tasks.c.id == tid))
    if not t or t["family_id"] != m["family_id"] or (t["to_id"] != m["id"] and m["role"] != "parent"):
        return err("That task is not yours.", 404)
    if t["done_at"]:
        return jsonify(ok=True)
    score = score if isinstance(score, int) and 0 <= score <= 100 else -1
    db.run(update(db.noor_tasks).where(db.noor_tasks.c.id == tid).values(done_at=time.time(), score=score))
    who = db.one(select(db.members).where(db.members.c.id == t["to_id"]))
    verb = {"read": "read", "memorize": "memorized", "revise": "revised"}.get(t["kind"], "read")
    say(m["family_id"], 0, f"\u2705 {who['name']} {verb} surah {t['surah']}, ayahs {t['ayah_from']}-{t['ayah_to']}" + (f" (score {score}%)" if score >= 0 else ""))
    push(t["by_id"], "noor_done", {"id": tid, "name": who["name"]})
    return jsonify(ok=True)


# --- from Mellow (the family's own sign-in)
@app.post("/api/noor/code")
@need
def noor_code():
    """A one-time code that links Noor to this account. The page that opens Noor is built here, so the code only ever goes to Noor."""
    if limited(f"noorcode:{g.m['id']}", 20):
        return err("Slow down a little.", 429)
    code = secrets.token_urlsafe(18)
    db.run(insert(db.noor_codes).values(member_id=g.m["id"], code_hash=db.hash_token(code), expires=time.time() + 600))
    return jsonify(code=code, url=f"{NOOR_URL}/#/account?c={code}", short=code[:8])


@app.get("/api/noor/family")
@need
def noor_family_get():
    return jsonify(noor_family(g.m))


@app.post("/api/noor/tasks")
@need
def noor_task_add():
    tid, bad = make_task(g.m, request.get_json(silent=True) or {})
    return bad if bad else jsonify(ok=True, id=tid)


@app.delete("/api/noor/tasks/<int:tid>")
@parent_only
def noor_task_del(tid):
    t = db.one(select(db.noor_tasks).where(db.noor_tasks.c.id == tid))
    if not t or t["family_id"] != g.m["family_id"]:
        return err("Not found.", 404)
    db.run(delete(db.noor_tasks).where(db.noor_tasks.c.id == tid))
    return jsonify(ok=True)


@app.post("/api/noor/tasks/<int:tid>/done")
@need
def noor_task_done(tid):
    return finish_task(g.m, tid)


@app.delete("/api/noor/link")
@need
def noor_unlink_mine():
    db.run(delete(db.noor_links).where(db.noor_links.c.member_id == g.m["id"]))
    return jsonify(ok=True)


# --- from Noor (its own key, made from the one-time code)
@app.post("/api/noor-app/exchange")
def noor_exchange():
    d = request.get_json(silent=True) or {}
    code = str(d.get("code") or "")
    if limited(f"noorx:{request.remote_addr}", 30):
        return err("Slow down a little.", 429)
    c = db.one(select(db.noor_codes).where(db.noor_codes.c.code_hash == db.hash_token(code))) if code else None
    if not c or c["used"] or c["expires"] < time.time():
        return err("This link has expired. Open Mellow and link Noor again.", 400)
    db.run(update(db.noor_codes).where(db.noor_codes.c.id == c["id"]).values(used=True))
    m = db.one(select(db.members).where(db.members.c.id == c["member_id"]))
    if not m:
        return err("That account is gone.", 400)
    db.run(delete(db.noor_links).where(db.noor_links.c.member_id == m["id"]))          # one Noor per person: linking again replaces the old one
    token = "hbn_" + secrets.token_urlsafe(32)
    db.run(insert(db.noor_links).values(member_id=m["id"], family_id=m["family_id"], token_hash=db.hash_token(token), created=time.time(), last_used=time.time()))
    fam = db.one(select(db.families).where(db.families.c.id == m["family_id"]))
    say(m["family_id"], 0, f"\U0001F4D6 {m['name']} linked Noor, the Quran app")
    return jsonify(token=token, me={"id": m["id"], "name": m["name"], "emoji": m["emoji"], "role": m["role"]}, family=fam["name"] if fam else "")


@app.get("/api/noor-app/family")
@noor_need
def noor_app_family():
    fam = db.one(select(db.families).where(db.families.c.id == g.m["family_id"]))
    out = noor_family(g.m)
    out.update(family=fam["name"] if fam else "", my={"id": g.m["id"], "name": g.m["name"], "emoji": g.m["emoji"], "role": g.m["role"]})
    return jsonify(out)


@app.post("/api/noor-app/sync")
@noor_need
def noor_app_sync():
    """Noor sends a short summary for the family to see, and (optionally) its saved data, so a new phone can pick up where the old one stopped."""
    d = request.get_json(silent=True) or {}
    stats = d.get("stats") if isinstance(d.get("stats"), dict) else {}
    keep = {k: stats[k] for k in ("streak", "best", "today", "day", "week", "last", "khatma", "hifz", "recite", "total", "goal") if k in stats}
    vals = {"stats": json.dumps(keep, ensure_ascii=False)[:4000], "synced": time.time()}
    if isinstance(d.get("data"), (dict, list)):
        blob = json.dumps(d["data"], ensure_ascii=False)
        if len(blob) > 300_000:
            return err("Too much to save.", 413)
        vals["data"] = blob
    db.run(update(db.noor_links).where(db.noor_links.c.id == g.noor["id"]).values(**vals))
    return jsonify(ok=True)


@app.get("/api/noor-app/data")
@noor_need
def noor_app_data():
    try:
        return jsonify(data=json.loads(g.noor["data"]) if g.noor["data"] else None)
    except ValueError:
        return jsonify(data=None)


@app.post("/api/noor-app/tasks")
@noor_need
def noor_app_task_add():
    tid, bad = make_task(g.m, request.get_json(silent=True) or {})
    return bad if bad else jsonify(ok=True, id=tid)


@app.delete("/api/noor-app/tasks/<int:tid>")
@noor_need
def noor_app_task_del(tid):
    t = db.one(select(db.noor_tasks).where(db.noor_tasks.c.id == tid))
    if g.m["role"] != "parent" or not t or t["family_id"] != g.m["family_id"]:
        return err("Not allowed.", 403)
    db.run(delete(db.noor_tasks).where(db.noor_tasks.c.id == tid))
    return jsonify(ok=True)


@app.post("/api/noor-app/tasks/<int:tid>/done")
@noor_need
def noor_app_task_done(tid):
    d = request.get_json(silent=True) or {}
    return finish_task(g.m, tid, d.get("score") if isinstance(d.get("score"), int) else -1)


@app.delete("/api/noor-app/link")
@noor_need
def noor_app_unlink():
    db.run(delete(db.noor_links).where(db.noor_links.c.id == g.noor["id"]))
    return jsonify(ok=True)


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
