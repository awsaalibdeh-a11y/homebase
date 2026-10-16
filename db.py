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
    Column("id", Integer, primary_key=True), Column("name", String(60)), Column("created", Float), Column("rules", Text, default=""))   # rules: the house rules text
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
    Column("status_emoji", String(8), default=""), Column("status_text", String(40), default=""), Column("status_until", Float, default=0))   # "what I am up to", set by the person, ends by itself
invites = Table("invites", meta,
    Column("code", String(12), primary_key=True), Column("family_id", Integer), Column("role", String(8)), Column("created_by", Integer),
    Column("expires", Float), Column("used", Boolean, default=False))
locations = Table("locations", meta,
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("lat", Float), Column("lng", Float),
    Column("acc", Float), Column("speed", Float), Column("at", Float, index=True))
places = Table("places", meta,
    Column("id", Integer, primary_key=True), Column("family_id", Integer, index=True), Column("name", String(40)), Column("emoji", String(8), default="📍"),
    Column("lat", Float), Column("lng", Float), Column("radius", Integer, default=150), Column("created_by", Integer), Column("created", Float))
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
    Column("done_by", Integer, default=0), Column("done_at", Float, default=0), Column("approved_by", Integer, default=0), Column("approved_at", Float, default=0))
devices = Table("devices", meta,                                          # a computer or tablet linked to a person with a 6-digit code; it has its own key
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("token_hash", String(64), unique=True),
    Column("name", String(60), default=""), Column("kind", String(12), default="windows"), Column("created", Float), Column("last_seen", Float, default=0), Column("apps", Text, default=""), Column("status", String(160), default=""), Column("version", String(12), default=""))
pair_codes = Table("pair_codes", meta,
    Column("code", String(8), primary_key=True), Column("member_id", Integer), Column("expires", Float))
blocks = Table("blocks", meta,                                            # "grounded" apps: blocked on a child's laptop or tablet until a time
    Column("id", Integer, primary_key=True), Column("member_id", Integer, index=True), Column("family_id", Integer, index=True),
    Column("key", String(60)), Column("label", String(60)), Column("by_id", Integer, default=0),                      # by_id 0 = the owner
    Column("created", Float), Column("until", Float), Column("ended", Float, default=0))
meta.create_all(engine)
try:                                                                  # older databases get the new column
    with engine.begin() as _c:
        _c.exec_driver_sql("ALTER TABLE members ADD COLUMN perms TEXT DEFAULT ''")
except Exception:
    pass
for _sql in ("ALTER TABLE members ADD COLUMN rules TEXT DEFAULT ''", "ALTER TABLE families ADD COLUMN rules TEXT DEFAULT ''", "ALTER TABLE devices ADD COLUMN apps TEXT DEFAULT ''", "ALTER TABLE devices ADD COLUMN status VARCHAR(160) DEFAULT ''", "ALTER TABLE devices ADD COLUMN version VARCHAR(12) DEFAULT ''", "ALTER TABLE calls ADD COLUMN web_seen BOOLEAN DEFAULT FALSE", "ALTER TABLE members ADD COLUMN avatar TEXT DEFAULT ''", "ALTER TABLE members ADD COLUMN avatar_at FLOAT DEFAULT 0",
            "ALTER TABLE members ADD COLUMN status_emoji VARCHAR(8) DEFAULT ''", "ALTER TABLE members ADD COLUMN status_text VARCHAR(40) DEFAULT ''",
            "ALTER TABLE members ADD COLUMN status_until FLOAT DEFAULT 0", "ALTER TABLE messages ADD COLUMN reactions TEXT DEFAULT ''"):
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
