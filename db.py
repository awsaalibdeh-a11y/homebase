"""Homebase's database: families, members, shared locations, messages, calls and the little event queue that wakes the apps up.
SQLite while developing, Postgres on the server (DATABASE_URL)."""

import hashlib
import os
import time

from sqlalchemy import (Boolean, Column, Float, Integer, MetaData, String, Table, Text, create_engine, delete, func, insert, select, update)

BASE = os.path.dirname(os.path.abspath(__file__))
url = os.environ.get("DATABASE_URL", f"sqlite:///{os.path.join(BASE, 'homebase.db')}")
if url.startswith("postgres://"):
    url = url.replace("postgres://", "postgresql://", 1)
if url.startswith("postgresql://"):                                    # name the driver we install; newer SQLAlchemy defaults to psycopg 3
    url = url.replace("postgresql://", "postgresql+psycopg2://", 1)
engine = create_engine(url, pool_pre_ping=True, **({} if url.startswith("sqlite") else {"pool_size": 5, "max_overflow": 10}))
meta = MetaData()

families = Table("families", meta,
    Column("id", Integer, primary_key=True), Column("name", String(60)), Column("created", Float), Column("rules", Text, default=""),   # rules: the house rules text
    Column("notice", String(200), default=""), Column("notice_until", Float, default=0), Column("notice_by", Integer, default=0))   # a parent's announcement, which ends by itself
members = Table("members", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("name", String(40)),
    Column("role", String(8)),                                     # parent | child
    Column("emoji", String(8), default="🙂"), Column("token_hash", String(64), unique=True), Column("created", Float),
    Column("last_seen", Float, default=0), Column("battery", Integer, default=-1),
    Column("share_state", String(8), default="on"),                # on | paused | off  (the child always chooses)
    Column("paused_until", Float, default=0),
    Column("quiet_from", Integer, default=-1), Column("quiet_to", Integer, default=-1),      # minutes after midnight, -1 = none
    Column("tz_offset", Integer, default=0),                       # minutes, as the phone reports it
    Column("low_sent", Boolean, default=False),
    Column("rules", Text, default=""),                                # JSON {"limit": minutes} a parent set for this child
    Column("perms", Text, default=""),                               # JSON {key: false} of what parents may NOT do; the child edits it
    Column("last_lat", Float), Column("last_lng", Float), Column("last_acc", Float), Column("last_at", Float, default=0),
    Column("avatar", Text, default=""), Column("avatar_at", Float, default=0),
    Column("status_emoji", String(8), default=""), Column("status_text", String(40), default=""), Column("status_until", Float, default=0),   # "what I am up to", set by the person, ends by itself
    Column("birthday", String(5), default=""), Column("bday_year", Integer, default=0), Column("app_version", Integer, default=0),
    Column("safe_dns", String(80), default=""), Column("safe_dns_at", Float, default=0), Column("eta_min", Integer, default=0), Column("eta_until", Float, default=0),
    Column("sos_until", Float, default=0), Column("offline_told", Boolean, default=False), Column("critical_at", Float, default=0), Column("critical_by", Integer, default=0),
    Column("walk_to", Integer, default=0), Column("walk_until", Float, default=0), Column("walk_started", Float, default=0), Column("walk_told", String(20), default=""),
    Column("speed_limit", Integer, default=0), Column("speed_strikes", Integer, default=0), Column("speed_told", Float, default=0),
    Column("bed_from", Integer, default=-1), Column("bed_to", Integer, default=-1), Column("bed_told", String(10), default=""))                     # "MM-DD" the person chose; the year we last said happy birthday
invites = Table("invites", meta,
    Column("code", String(12), primary_key=True), Column("family_id", Integer), Column("role", String(8)), Column("created_by", Integer),
    Column("expires", Float), Column("used", Boolean, default=False))
locations = Table("locations", meta,
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("lat", Float), Column("lng", Float),
    Column("acc", Float), Column("speed", Float), Column("at", Float, index=True))
places = Table("places", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("name", String(40)), Column("emoji", String(8), default="📍"),
    Column("lat", Float), Column("lng", Float), Column("radius", Integer, default=150), Column("created_by", Integer), Column("created", Float),
    Column("pending", Boolean, default=False))                        # a child asked for this zone and a parent has not said yes yet
place_state = Table("place_state", meta,
    Column("member_id", Integer, primary_key=True), Column("place_id", Integer, primary_key=True), Column("inside", Boolean, default=False))
messages = Table("messages", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("from_id", Integer), Column("to_id", Integer, default=0),   # 0 = the whole family
    Column("kind", String(10), default="text"),                    # text | checkin | late | sos | heart | system
    Column("body", Text), Column("lat", Float), Column("lng", Float), Column("created", Float, index=True),
    Column("reactions", Text, default=""))                         # JSON {"emoji": [member ids]}
events = Table("events", meta,
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("type", String(20)), Column("payload", Text), Column("created", Float))
calls = Table("calls", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer), Column("caller_id", Integer), Column("callee_id", Integer),
    Column("status", String(10), default="ringing"),               # ringing | active | declined | ended | missed
    Column("urgent", Boolean, default=False), Column("created", Float), Column("answered", Float, default=0), Column("ended", Float, default=0),
    Column("offer", Text), Column("answer", Text), Column("web_seen", Boolean, default=False))
pings = Table("pings", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer), Column("from_id", Integer), Column("to_id", Integer),
    Column("status", String(10), default="pending"),               # pending | shared | declined
    Column("created", Float), Column("lat", Float), Column("lng", Float))
lists = Table("lists", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("text", String(120)), Column("done", Boolean, default=False),
    Column("added_by", Integer), Column("done_by", Integer, default=0), Column("created", Float))
screentime = Table("screentime", meta,                                # time spent actively playing or watching, per child per day per app
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("day", String(10), index=True),
    Column("pkg", String(120)), Column("label", String(60)), Column("cat", String(10), default="play"),   # play | watch | social
    Column("sec", Integer, default=0), Column("updated", Float))
locks = Table("locks", meta,                                          # a parent locks a child's devices for a while (at most 4 hours a day)
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("by_id", Integer), Column("family_id", Integer, index=True),
    Column("start", Float), Column("until", Float), Column("ended", Float, default=0),                # ended = lifted early (0 = not lifted)
    Column("lifted_by", String(12), default=""), Column("reason", String(120), default=""))          # lifted_by: parent | owner
chores = Table("chores", meta,                                          # jobs a parent sets, a child ticks off, a parent approves for points
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("title", String(80)), Column("emoji", String(8), default="🧹"),
    Column("points", Integer, default=5), Column("to_id", Integer, default=0),                                  # 0 = any child
    Column("created_by", Integer), Column("created", Float),
    Column("status", String(10), default="open"),                                                               # open | done | approved
    Column("done_by", Integer, default=0), Column("done_at", Float, default=0), Column("approved_by", Integer, default=0), Column("approved_at", Float, default=0),
    Column("repeats", String(8), default=""),                                                                    # "" | daily | weekly: approving it opens a fresh copy later
    Column("opens_at", Float, default=0))                                                                        # a repeating job waits until then (0 = open now)
devices = Table("devices", meta,                                          # a computer or tablet linked to a person with a 6-digit code; it has its own key
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("token_hash", String(64), unique=True),
    Column("name", String(60), default=""), Column("kind", String(12), default="windows"), Column("created", Float), Column("last_seen", Float, default=0), Column("apps", Text, default=""), Column("status", String(160), default=""), Column("version", String(12), default=""))
pair_codes = Table("pair_codes", meta,
    Column("code", String(8), primary_key=True), Column("member_id", Integer), Column("expires", Float))
blocks = Table("blocks", meta,                                            # "grounded" apps: blocked on a child's laptop or tablet until a time
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("family_id", Integer, index=True),
    Column("key", String(60)), Column("label", String(60)), Column("by_id", Integer, default=0),                      # by_id 0 = the owner
    Column("created", Float), Column("until", Float), Column("ended", Float, default=0))
plans = Table("plans", meta,                                              # the family calendar: something on a day, and who says "I'm in"
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("title", String(60)), Column("emoji", String(8), default="📅"),
    Column("start", Float, index=True), Column("note", String(120), default=""), Column("created_by", Integer), Column("created", Float),
    Column("rsvp", Text, default=""),                                  # JSON {member id: "in" | "out"}
    Column("reminded", Boolean, default=False),                        # the "starts soon" nudge went out
    Column("lat", Float), Column("lng", Float),                        # a plan made from a place (Scout) knows where it is
    Column("ext_id", String(80), default="", index=True), Column("via", String(20), default=""))   # which linked app made it, and its own id for it
polls = Table("polls", meta,                                              # a question for the family, one vote each
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("question", String(100)),
    Column("options", Text), Column("votes", Text, default=""),         # options: JSON list of strings; votes: JSON {member id: option number}
    Column("created_by", Integer), Column("created", Float), Column("closed", Boolean, default=False))
rewards = Table("rewards", meta,                                          # what chore points can buy, set by a parent
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("title", String(60)), Column("emoji", String(8), default="🎁"),
    Column("cost", Integer, default=20), Column("created_by", Integer), Column("created", Float),
    Column("minutes", Integer, default=0))                              # extra screen time today when a parent says yes (0 = none)
redemptions = Table("redemptions", meta,                                  # a child asks to spend points; a parent says yes or no
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer, index=True),
    Column("title", String(60)), Column("emoji", String(8), default="🎁"), Column("cost", Integer),                  # a copy, so a later change to the reward does not rewrite history
    Column("status", String(10), default="pending"),                    # pending | approved | declined
    Column("created", Float), Column("decided_by", Integer, default=0), Column("decided_at", Float, default=0),
    Column("minutes", Integer, default=0))
bonus = Table("bonus", meta,                                              # extra screen-time minutes a reward earned, for one day
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("day", String(10), index=True), Column("minutes", Integer), Column("created", Float))
board = Table("board", meta,                                              # the family board: what everyone should be able to find (Wi-Fi, the doctor, allergies)
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("title", String(40)), Column("emoji", String(8), default="📌"),
    Column("body", Text, default=""), Column("updated_by", Integer), Column("updated", Float))
push_subs = Table("push_subs", meta,                                      # a browser or home-screen app that asked for alerts while it is closed
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("endpoint", Text, index=False),
    Column("p256dh", Text), Column("auth", Text), Column("created", Float), Column("ua", String(80), default=""))
settings = Table("settings", meta,                                        # small things the server keeps for itself (its push keys)
    Column("key", String(40), primary_key=True), Column("value", Text))
meals = Table("meals", meta,                                              # the family meal plan: what we are cooking on which day (typed in, or sent by a linked app like Platter)
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("day", String(10), index=True),
    Column("slot", String(10), default="dinner"), Column("title", String(120)), Column("photo", String(400), default=""), Column("source", String(400), default=""),
    Column("servings", Integer, default=0), Column("ingredients", Text, default=""), Column("steps", Text, default=""),            # JSON lists of text
    Column("ext_id", String(80), default="", index=True), Column("via", String(20), default="homebase"), Column("added_by", Integer, default=0), Column("created", Float),
    Column("cook", Integer, default=0),                                  # who said they will cook it (0 = nobody yet)
    Column("suggested", Boolean, default=False))                         # a child put it forward and a parent has not said yes yet
integrations = Table("integrations", meta,                                # an app the family linked (Platter): its own revocable key that can only touch the meal plan
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("name", String(40)), Column("key_hash", String(64), unique=True),
    Column("created_by", Integer), Column("created", Float), Column("last_used", Float, default=0),
    Column("scopes", String(60), default="meals"))                         # what the key may do: meals | plans | places | share
game_links = Table("game_links", meta,                                    # a one-time link for starting a family game (Alibi) from the chat: the game uses it once to post its code
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer),
    Column("token_hash", String(64), unique=True), Column("game", String(12)), Column("created", Float), Column("expires", Float), Column("used", Boolean, default=False))
friend_groups = Table("friend_groups", meta,                              # a group of friends: separate from the family, but a child's parents can read it
    Column("id", Integer, primary_key=True), Column("name", String(40)), Column("emoji", String(8), default="\U0001F465"), Column("code", String(10), unique=True),
    Column("created_by", Integer), Column("created", Float))
friend_members = Table("friend_members", meta,
    Column("id", Integer, primary_key=True), Column("group_id", Integer, index=True), Column("member_id", Integer, index=True),
    Column("status", String(10), default="active"),                        # active | pending (a child waiting for a parent to say yes)
    Column("joined", Float), Column("muted_until", Float, default=0))
friend_messages = Table("friend_messages", meta,
    Column("id", Integer, primary_key=True), Column("group_id", Integer, index=True), Column("from_id", Integer), Column("kind", String(6), default="text"),
    Column("body", Text), Column("original", Text, default=""), Column("flags", Text, default=""), Column("created", Float, index=True))
risk_alerts = Table("risk_alerts", meta,                               # a message in Mellow that looked like bullying, self-harm, adult content or grooming
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer, index=True), Column("cat", String(20)),
    Column("role", String(10)), Column("other", String(40), default=""), Column("where", String(60), default=""), Column("snippet", Text, default=""),
    Column("created", Float, index=True), Column("seen", Boolean, default=False))
album = Table("album", meta,                                         # the family photo album: photos from the chat land here, and anyone can add more
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("token", String(40)), Column("by", Integer), Column("caption", String(120), default=""),
    Column("created", Float))
wallet = Table("wallet", meta,                                       # pocket money: a ledger in cents; a child's request waits as "pending"
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer, index=True), Column("cents", Integer),
    Column("note", String(80), default=""), Column("by", Integer), Column("status", String(10), default="done"), Column("created", Float))
goals = Table("goals", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer, index=True), Column("title", String(60)),
    Column("emoji", String(8), default="🎯"), Column("target", Integer), Column("saved", Integer, default=0), Column("created", Float))
moods = Table("moods", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer, index=True), Column("day", String(10)),
    Column("mood", Integer), Column("note", String(200), default=""), Column("private", Boolean, default=False), Column("created", Float))
expects = Table("expects", meta,                                     # "tell me when they get there"
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer, index=True), Column("place_id", Integer),
    Column("by_at", Float), Column("set_by", Integer), Column("done", Boolean, default=False), Column("told", Boolean, default=False), Column("created", Float))
visits = Table("visits", meta,                                       # arrivals and departures, for the day timeline
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer, index=True), Column("place", String(40)),
    Column("emoji", String(8), default="📍"), Column("kind", String(6)), Column("at", Float, index=True))
trips = Table("trips", meta,                                         # a live-location link for someone outside the family; it runs out
    Column("id", Integer, primary_key=True), Column("family_id", Integer), Column("member_id", Integer, index=True), Column("token_hash", String(64), index=True),
    Column("expires", Float), Column("created", Float))
kudos = Table("kudos", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("from_id", Integer), Column("to_id", Integer, index=True),
    Column("emoji", String(8), default="⭐"), Column("reason", String(120), default=""), Column("created", Float, index=True))
countdowns = Table("countdowns", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("title", String(60)), Column("emoji", String(8), default="⏳"),
    Column("at", Float), Column("by", Integer), Column("created", Float))
trusted = Table("trusted", meta,                                     # grown-ups a child can call if they cannot reach a parent
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("name", String(40)), Column("phone", String(30)),
    Column("relation", String(30), default=""), Column("by", Integer), Column("created", Float))
homework = Table("homework", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("member_id", Integer, index=True), Column("subject", String(30), default=""),
    Column("title", String(120)), Column("due", String(10)), Column("done", Boolean, default=False), Column("reminded", Boolean, default=False), Column("by", Integer), Column("created", Float))
message_images = Table("message_images", meta,                       # photos sent in family chats: a small JPEG or PNG, found by a long random token
    Column("id", Integer, primary_key=True), Column("token", String(40), unique=True, index=True), Column("family_id", Integer, index=True),
    Column("from_id", Integer), Column("mime", String(16)), Column("data", Text), Column("created", Float))
meta.create_all(engine)
try:                                                                  # older databases get the new column
    with engine.begin() as _c:
        _c.exec_driver_sql("ALTER TABLE members ADD COLUMN perms TEXT DEFAULT ''")
except Exception:
    pass
for _sql in ("ALTER TABLE members ADD COLUMN rules TEXT DEFAULT ''", "ALTER TABLE families ADD COLUMN rules TEXT DEFAULT ''", "ALTER TABLE devices ADD COLUMN apps TEXT DEFAULT ''", "ALTER TABLE devices ADD COLUMN status VARCHAR(160) DEFAULT ''", "ALTER TABLE devices ADD COLUMN version VARCHAR(12) DEFAULT ''", "ALTER TABLE calls ADD COLUMN web_seen BOOLEAN DEFAULT FALSE", "ALTER TABLE members ADD COLUMN avatar TEXT DEFAULT ''", "ALTER TABLE members ADD COLUMN avatar_at FLOAT DEFAULT 0",
            "ALTER TABLE members ADD COLUMN status_emoji VARCHAR(8) DEFAULT ''", "ALTER TABLE members ADD COLUMN status_text VARCHAR(40) DEFAULT ''",
            "ALTER TABLE members ADD COLUMN status_until FLOAT DEFAULT 0", "ALTER TABLE messages ADD COLUMN reactions TEXT DEFAULT ''",
            "ALTER TABLE chores ADD COLUMN repeats VARCHAR(8) DEFAULT ''", "ALTER TABLE chores ADD COLUMN opens_at FLOAT DEFAULT 0",
            "ALTER TABLE rewards ADD COLUMN minutes INTEGER DEFAULT 0", "ALTER TABLE redemptions ADD COLUMN minutes INTEGER DEFAULT 0",
            "ALTER TABLE members ADD COLUMN birthday VARCHAR(5) DEFAULT ''", "ALTER TABLE members ADD COLUMN bday_year INTEGER DEFAULT 0",
            "ALTER TABLE members ADD COLUMN app_version INTEGER DEFAULT 0",
            "ALTER TABLE families ADD COLUMN notice VARCHAR(200) DEFAULT ''", "ALTER TABLE families ADD COLUMN notice_until FLOAT DEFAULT 0", "ALTER TABLE families ADD COLUMN notice_by INTEGER DEFAULT 0",
            "ALTER TABLE plans ADD COLUMN lat FLOAT", "ALTER TABLE plans ADD COLUMN lng FLOAT", "ALTER TABLE plans ADD COLUMN ext_id VARCHAR(80) DEFAULT ''",
            "ALTER TABLE meals ADD COLUMN suggested BOOLEAN DEFAULT FALSE",
            "ALTER TABLE members ADD COLUMN walk_to INTEGER DEFAULT 0", "ALTER TABLE members ADD COLUMN walk_until FLOAT DEFAULT 0", "ALTER TABLE members ADD COLUMN walk_started FLOAT DEFAULT 0",
            "ALTER TABLE members ADD COLUMN walk_told VARCHAR(20) DEFAULT ''", "ALTER TABLE members ADD COLUMN speed_limit INTEGER DEFAULT 0", "ALTER TABLE members ADD COLUMN speed_strikes INTEGER DEFAULT 0",
            "ALTER TABLE members ADD COLUMN speed_told FLOAT DEFAULT 0", "ALTER TABLE members ADD COLUMN bed_from INTEGER DEFAULT -1", "ALTER TABLE members ADD COLUMN bed_to INTEGER DEFAULT -1",
            "ALTER TABLE members ADD COLUMN bed_told VARCHAR(10) DEFAULT ''", "ALTER TABLE places ADD COLUMN pending BOOLEAN DEFAULT FALSE",
            "ALTER TABLE members ADD COLUMN safe_dns VARCHAR(80) DEFAULT ''", "ALTER TABLE members ADD COLUMN safe_dns_at FLOAT DEFAULT 0",
            "ALTER TABLE members ADD COLUMN eta_min INTEGER DEFAULT 0", "ALTER TABLE members ADD COLUMN eta_until FLOAT DEFAULT 0",
            "ALTER TABLE members ADD COLUMN sos_until FLOAT DEFAULT 0", "ALTER TABLE members ADD COLUMN offline_told BOOLEAN DEFAULT FALSE",
            "ALTER TABLE members ADD COLUMN critical_at FLOAT DEFAULT 0", "ALTER TABLE members ADD COLUMN critical_by INTEGER DEFAULT 0", "ALTER TABLE plans ADD COLUMN via VARCHAR(20) DEFAULT ''", "ALTER TABLE integrations ADD COLUMN scopes VARCHAR(60) DEFAULT 'meals'"):
    try:
        with engine.begin() as _c:
            _c.exec_driver_sql(_sql)
    except Exception:
        pass


def run(stmt):
    with engine.begin() as c:
        r = c.execute(stmt)
        return r.inserted_primary_key[0] if r.is_insert and r.inserted_primary_key else None


def one(stmt):
    with engine.connect() as c:
        r = c.execute(stmt).mappings().first()
        return dict(r) if r else None


def many(stmt):
    with engine.connect() as c:
        return [dict(r) for r in c.execute(stmt).mappings().all()]


def hash_token(token):
    return hashlib.sha256(token.encode()).hexdigest()
