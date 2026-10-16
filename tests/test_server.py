import base64
import calendar
import datetime
import json
import os
import sys
import tempfile
import time
import types
import unittest
from unittest import mock

_tmp = tempfile.mkdtemp()
os.environ["DATABASE_URL"] = f"sqlite:///{os.path.join(_tmp, 'test.db')}"
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import app as server  # noqa: E402
import db  # noqa: E402


class Base(unittest.TestCase):
    def setUp(self):
        server._hits.clear()
        self.c = server.app.test_client()
        d = self.c.post("/api/family", json={"family": "The Vales", "name": "Mum", "emoji": "👩"}).get_json()
        self.mum = {"Authorization": f"Bearer {d['token']}"}
        self.mum_id = d["me"]["id"]
        code = self.c.post("/api/invite", headers=self.mum, json={"role": "child"}).get_json()["code"]
        k = self.c.post("/api/join", json={"code": code, "name": "Sam", "emoji": "🧒"}).get_json()
        self.kid = {"Authorization": f"Bearer {k['token']}"}
        self.kid_id = k["me"]["id"]

    def get(self, who, path):
        return self.c.get(path, headers=who)

    def post(self, who, path, **body):
        return self.c.post(path, headers=who, json=body)

    def events(self, who):
        d = self.get(who, "/api/events?after=0").get_json()
        return d["events"]

    def where(self, who, lat, lng, **extra):
        return self.post(who, "/api/location", lat=lat, lng=lng, acc=10, **extra)


class Family(Base):
    def test_a_code_works_once_and_expires(self):
        code = self.post(self.mum, "/api/invite", role="child").get_json()["code"]
        self.assertEqual(self.c.post("/api/join", json={"code": code, "name": "Kit"}).status_code, 200)
        self.assertEqual(self.c.post("/api/join", json={"code": code, "name": "Kit2"}).status_code, 400)
        self.assertEqual(self.c.post("/api/join", json={"code": "NOPE"}).status_code, 400)
        old = self.post(self.mum, "/api/invite", role="child").get_json()["code"]
        db.run(db.update(db.invites).where(db.invites.c.code == old).values(expires=time.time() - 5))
        self.assertEqual(self.c.post("/api/join", json={"code": old, "name": "Late"}).status_code, 400)

    def test_an_invite_can_be_looked_at_before_joining(self):
        code = self.post(self.mum, "/api/invite", role="child").get_json()["code"]
        d = self.c.get(f"/api/invite/{code.lower()}").get_json()
        self.assertEqual((d["role"], d["family"], d["invited_by"]), ("child", "The Vales", "Mum"))
        self.assertEqual(self.c.get("/api/invite/NOPE1234").status_code, 404)
        self.assertEqual(self.c.get(f"/api/invite/{code}").status_code, 200)               # looking does not use it up

    def test_children_cannot_invite_or_add_places(self):
        self.assertEqual(self.post(self.kid, "/api/invite", role="parent").status_code, 403)
        self.assertEqual(self.post(self.kid, "/api/places", name="Park", lat=1, lng=1).status_code, 403)
        self.assertEqual(self.c.get("/api/family").status_code, 401)

    def test_families_cannot_see_each_other(self):
        other = self.c.post("/api/family", json={"family": "Other", "name": "Zed"}).get_json()
        zed = {"Authorization": f"Bearer {other['token']}"}
        self.assertEqual(self.post(zed, "/api/messages", to=self.kid_id, body="hi").status_code, 404)
        self.assertEqual(self.get(zed, f"/api/history?member={self.kid_id}").status_code, 404)
        self.assertEqual(self.post(zed, "/api/ping", to=self.kid_id).status_code, 404)
        fam = self.get(zed, "/api/family").get_json()
        self.assertEqual([m["name"] for m in fam["members"]], ["Zed"])

    def test_the_family_sees_everyone_and_defaults_are_fair(self):
        fam = self.get(self.kid, "/api/family").get_json()
        by = {m["name"]: m for m in fam["members"]}
        self.assertEqual((by["Sam"]["share"], by["Mum"]["share"]), ("on", "on"))           # everyone starts on the map, and anyone can switch it off
        self.assertEqual(self.get(self.kid, "/api/me").get_json()["me"]["role"], "child")


class Location(Base):
    def test_parents_see_a_child_who_is_sharing(self):
        self.where(self.kid, 51.5, -0.12, battery=80)
        kid = [m for m in self.get(self.mum, "/api/family").get_json()["members"] if m["name"] == "Sam"][0]
        self.assertEqual((kid["location"]["lat"], kid["battery"]), (51.5, 80))
        self.assertTrue(any(e["type"] == "location" for e in self.events(self.mum)))

    def test_pausing_stops_sharing_and_the_family_is_told(self):
        self.where(self.kid, 51.5, -0.12)
        r = self.post(self.kid, "/api/sharing", state="paused", minutes=30).get_json()
        self.assertGreater(r["paused_until"], time.time())
        self.assertFalse(self.where(self.kid, 51.6, -0.2).get_json()["shared"])             # the new position is not stored
        kid = [m for m in self.get(self.mum, "/api/family").get_json()["members"] if m["name"] == "Sam"][0]
        self.assertEqual((kid["share"], kid["location"]), ("paused", None))                 # parents see "paused", not a stale pin
        chat = self.get(self.mum, "/api/messages").get_json()["messages"]
        self.assertTrue(any("paused location sharing" in m["body"] for m in chat))          # nothing is silent
        db.run(db.update(db.members).where(db.members.c.id == self.kid_id).values(paused_until=time.time() - 1))
        self.assertTrue(self.where(self.kid, 51.7, -0.3).get_json()["shared"])              # it resumes by itself

    def test_history_rules(self):
        self.where(self.kid, 51.5, -0.12)
        self.assertEqual(len(self.get(self.mum, f"/api/history?member={self.kid_id}").get_json()["points"]), 1)
        self.assertEqual(len(self.get(self.kid, "/api/history").get_json()["points"]), 1)        # the child can see their own trail too
        self.assertEqual(self.get(self.kid, f"/api/history?member={self.mum_id}").status_code, 403)

    def test_you_can_delete_your_own_trail(self):
        self.where(self.kid, 51.5, -0.12)
        self.assertEqual(self.c.delete("/api/history", headers=self.kid).status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/history").get_json()["points"], [])
        kid = [m for m in self.get(self.mum, "/api/family").get_json()["members"] if m["name"] == "Sam"][0]
        self.assertIsNone(kid["location"])

    def test_places_tell_everyone_when_someone_arrives_and_leaves(self):
        pid = self.post(self.mum, "/api/places", name="School", emoji="🏫", lat=51.5, lng=-0.12, radius=150).get_json()["id"]
        self.assertTrue(any(e["type"] == "places" for e in self.events(self.kid)))               # the child is told a place was added
        self.where(self.kid, 51.52, -0.12)
        self.where(self.kid, 51.5001, -0.12)
        self.where(self.kid, 51.5002, -0.1201)                                                    # still inside: no second note
        self.where(self.kid, 51.53, -0.12)
        notes = [e["payload"]["kind"] for e in self.events(self.mum) if e["type"] == "place"]
        self.assertEqual(notes, ["arrive", "leave"])
        self.assertTrue(any(e["type"] == "place" for e in self.events(self.kid)))               # the child sees the same notices
        self.assertEqual(self.c.delete(f"/api/places/{pid}", headers=self.mum).status_code, 200)

    def test_low_battery_is_mentioned_once(self):
        self.where(self.kid, 51.5, -0.12, battery=14)
        self.where(self.kid, 51.5001, -0.12, battery=13)
        low = [e for e in self.events(self.mum) if e["type"] == "battery"]
        self.assertEqual(len(low), 1)
        self.where(self.kid, 51.5002, -0.12, battery=60)
        self.where(self.kid, 51.5003, -0.12, battery=10)
        self.assertEqual(len([e for e in self.events(self.mum) if e["type"] == "battery"]), 2)

    def test_bad_coordinates_are_refused(self):
        self.assertEqual(self.post(self.kid, "/api/location", lat=999, lng=0).status_code, 400)
        self.assertEqual(self.post(self.kid, "/api/location", lat="x", lng=0).status_code, 400)


class Talking(Base):
    def test_family_chat_and_direct_messages_stay_where_they_belong(self):
        self.post(self.mum, "/api/messages", to=0, body="Dinner at 7")
        self.post(self.mum, "/api/messages", to=self.kid_id, body="Just between us")
        everyone = [m["body"] for m in self.get(self.kid, "/api/messages?with=0").get_json()["messages"] if m["kind"] != "system"]
        direct = [m["body"] for m in self.get(self.kid, f"/api/messages?with={self.mum_id}").get_json()["messages"]]
        self.assertEqual((everyone, direct), (["Dinner at 7"], ["Just between us"]))
        self.assertEqual(self.post(self.mum, "/api/messages", body=" ").status_code, 400)

    def test_checkin_late_hearts_and_sos(self):
        r = self.post(self.kid, "/api/messages", kind="checkin", body="I'm home", lat=51.5, lng=-0.1, to=0).get_json()["message"]
        self.assertEqual((r["kind"], r["lat"]), ("checkin", 51.5))
        self.post(self.kid, "/api/messages", kind="late", body="Running 20 min late", to=0)
        self.post(self.kid, "/api/messages", kind="heart", body="", to=self.mum_id)
        self.post(self.kid, "/api/messages", kind="sos", lat=51.5, lng=-0.1, to=0)
        urgent = [e for e in self.events(self.mum) if e["type"] == "message" and e["payload"]["kind"] == "sos"]
        self.assertTrue(urgent[0]["payload"]["urgent"])
        self.assertEqual(urgent[0]["payload"]["body"], "I need help")

    def test_quiet_hours_make_messages_silent_but_not_sos(self):
        self.c.patch("/api/me", headers=self.kid, json={"quiet_from": 0, "quiet_to": 1439, "tz_offset": 0})
        self.post(self.mum, "/api/messages", to=self.kid_id, body="hello")
        self.post(self.mum, "/api/messages", kind="sos", to=self.kid_id)
        got = {e["payload"]["kind"]: e["payload"]["quiet"] for e in self.events(self.kid) if e["type"] == "message"}
        self.assertEqual((got["text"], got["sos"]), (True, False))

    def test_where_are_you_can_be_answered_or_declined(self):
        pid = self.post(self.mum, "/api/ping", to=self.kid_id).get_json()["id"]
        self.assertTrue(any(e["type"] == "ping" for e in self.events(self.kid)))
        self.post(self.kid, f"/api/ping/{pid}", accept=True, lat=51.5, lng=-0.1)
        res = [e["payload"] for e in self.events(self.mum) if e["type"] == "ping_result"]
        self.assertEqual((res[0]["status"], res[0]["lat"]), ("shared", 51.5))
        pid2 = self.post(self.mum, "/api/ping", to=self.kid_id).get_json()["id"]
        self.post(self.kid, f"/api/ping/{pid2}", accept=False)
        self.assertEqual([e["payload"]["status"] for e in self.events(self.mum) if e["type"] == "ping_result"], ["shared", "declined"])
        self.assertEqual(self.post(self.mum, f"/api/ping/{pid2}", accept=True).status_code, 404)        # only the person asked can answer

    def test_the_family_list(self):
        iid = self.post(self.mum, "/api/list", text="Milk").get_json()["id"]
        self.post(self.kid, f"/api/list/{iid}", done=True)
        items = self.get(self.mum, "/api/list").get_json()["items"]
        self.assertEqual((items[0]["text"], items[0]["done"], items[0]["done_by"]), ("Milk", True, self.kid_id))
        self.assertEqual(self.c.delete(f"/api/list/{iid}", headers=self.kid).status_code, 404)           # only the author or a parent removes it
        self.assertEqual(self.c.delete(f"/api/list/{iid}", headers=self.mum).status_code, 200)


class Calls(Base):
    def test_a_call_rings_connects_and_ends(self):
        r = self.post(self.mum, "/api/calls", to=self.kid_id, offer="v=0 offer").get_json()
        cid = r["id"]
        self.assertTrue(r["ice_servers"])
        ring = [e for e in self.events(self.kid) if e["type"] == "call"][0]["payload"]
        self.assertEqual((ring["id"], ring["name"], ring["offer"]), (cid, "Mum", "v=0 offer"))
        self.post(self.mum, f"/api/calls/{cid}/ice", candidate={"sdp": "a"})
        self.assertTrue(any(e["type"] == "ice" for e in self.events(self.kid)))
        self.post(self.kid, f"/api/calls/{cid}/answer", answer="v=0 answer")
        update = [e["payload"] for e in self.events(self.mum) if e["type"] == "call_update"][0]
        self.assertEqual((update["status"], update["answer"]), ("active", "v=0 answer"))
        self.post(self.mum, f"/api/calls/{cid}/end")
        self.assertEqual(self.get(self.kid, f"/api/calls/{cid}").get_json()["status"], "ended")

    def test_decline_busy_and_missed(self):
        cid = self.post(self.mum, "/api/calls", to=self.kid_id, offer="o").get_json()["id"]
        self.assertEqual(self.post(self.mum, "/api/calls", to=self.kid_id, offer="o").status_code, 409)   # already ringing
        self.post(self.kid, f"/api/calls/{cid}/decline")
        self.assertEqual(self.get(self.mum, f"/api/calls/{cid}").get_json()["status"], "declined")
        cid2 = self.post(self.mum, "/api/calls", to=self.kid_id, offer="o").get_json()["id"]
        db.run(db.update(db.calls).where(db.calls.c.id == cid2).values(created=time.time() - 60))
        self.assertEqual(self.get(self.mum, f"/api/calls/{cid2}").get_json()["status"], "missed")
        chat = self.get(self.kid, f"/api/messages?with={self.mum_id}").get_json()["messages"]
        self.assertTrue(any("Missed call" in m["body"] for m in chat))

    def test_quiet_hours_stop_calls_unless_it_is_urgent(self):
        self.c.patch("/api/me", headers=self.kid, json={"quiet_from": 0, "quiet_to": 1439, "tz_offset": 0})
        r = self.post(self.mum, "/api/calls", to=self.kid_id, offer="o")
        self.assertEqual((r.status_code, r.get_json()["quiet"]), (409, True))
        self.assertEqual(self.post(self.mum, "/api/calls", to=self.kid_id, offer="o", urgent=True).status_code, 200)

    def test_only_the_person_called_can_answer(self):
        cid = self.post(self.mum, "/api/calls", to=self.kid_id, offer="o").get_json()["id"]
        self.assertEqual(self.post(self.mum, f"/api/calls/{cid}/answer", answer="a").status_code, 404)


class Permissions(Base):
    """The child decides what parents may do, and the server holds everyone to it."""

    def off(self, **keys):
        return self.c.patch("/api/me", headers=self.kid, json={"perms": {k: False for k in keys}})

    def kid_view(self, who=None):
        d = self.get(who or self.mum, "/api/family").get_json()
        return next(m for m in d["members"] if m["id"] == self.kid_id)

    def test_everything_is_allowed_until_the_child_turns_it_off(self):
        self.assertTrue(all(self.kid_view()["perms"].values()))
        self.assertEqual(self.off(calls=True, ping=True).status_code, 200)
        v = self.kid_view()["perms"]
        self.assertEqual((v["calls"], v["ping"], v["messages"]), (False, False, True))
        self.c.patch("/api/me", headers=self.kid, json={"perms": {"calls": True}})        # and back on
        self.assertTrue(self.kid_view()["perms"]["calls"])

    def test_calls_asks_and_messages_are_refused_when_off(self):
        self.off(calls=True, ping=True, messages=True)
        self.assertEqual(self.post(self.mum, "/api/calls", to=self.kid_id, offer="v=0").status_code, 403)
        self.assertEqual(self.post(self.mum, "/api/ping", to=self.kid_id).status_code, 403)
        r = self.post(self.mum, "/api/messages", to=self.kid_id, kind="text", body="hi")
        self.assertEqual(r.status_code, 403)
        self.assertEqual(r.get_json()["perm"], "messages")
        # the child can always reach their parents, and check-ins and SOS are never blocked
        self.assertEqual(self.post(self.kid, "/api/messages", to=self.mum_id, kind="text", body="hi mum").status_code, 200)
        self.assertEqual(self.post(self.kid, "/api/calls", to=self.mum_id, offer="v=0").status_code, 200)

    def test_location_trail_and_battery_are_hidden_from_parents_when_off(self):
        self.where(self.kid, 51.5, -0.12, battery=64)
        self.assertEqual(self.kid_view()["location"]["lat"], 51.5)
        self.off(location=True, battery=True, trail=True)
        v = self.kid_view()
        self.assertEqual((v["location"], v["battery"], v["share"]), (None, -1, "off"))
        self.assertEqual(self.get(self.mum, f"/api/history?member={self.kid_id}").status_code, 403)
        mine = self.kid_view(self.kid)                                                      # the child still sees their own
        self.assertEqual(mine["location"]["lat"], 51.5)
        self.assertEqual(self.get(self.kid, "/api/history").status_code, 200)

    def test_a_parent_cannot_change_a_childs_permissions(self):
        self.c.patch("/api/me", headers=self.mum, json={"perms": {"calls": False}})
        self.assertTrue(self.kid_view()["perms"]["calls"])
        self.assertFalse(all(self.get(self.mum, "/api/me").get_json()["me"]["perms"].values()) is False and False)


class Misc(Base):
    def test_event_stream_hands_out_a_cursor_then_only_new_things(self):
        cur = self.get(self.mum, "/api/events?after=-1").get_json()["cursor"]
        self.post(self.kid, "/api/messages", body="hi", to=0)
        d = self.get(self.mum, f"/api/events?after={cur}&wait=2").get_json()
        self.assertEqual([e["type"] for e in d["events"]], ["message"])
        again = self.get(self.mum, f"/api/events?after={d['cursor']}").get_json()
        self.assertEqual(again["events"], [])

    def test_leaving_removes_what_you_shared_and_the_last_parent_removes_the_family(self):
        self.where(self.kid, 51.5, -0.12)
        self.assertEqual(self.c.delete("/api/me", headers=self.kid).status_code, 200)
        self.assertEqual(db.many(db.select(db.locations).where(db.locations.c.member_id == self.kid_id)), [])
        self.assertEqual(self.get(self.kid, "/api/me").status_code, 401)
        fid = db.one(db.select(db.members).where(db.members.c.id == self.mum_id))["family_id"]
        self.c.delete("/api/me", headers=self.mum)
        self.assertEqual(db.many(db.select(db.families).where(db.families.c.id == fid)), [])

    def test_the_website_and_health(self):
        self.assertEqual(self.c.get("/").status_code, 200)
        self.assertEqual(self.c.get("/health").get_json()["ok"], True)
        web = self.c.get("/app")                                              # the laptop version
        self.assertEqual(web.status_code, 200)
        self.assertIn(b"Homebase", web.data)
        web.close()
        sw = self.c.get("/sw.js")                                             # the offline worker, served from the root so it covers the site
        self.assertEqual((sw.status_code, sw.headers.get("Service-Worker-Allowed")), (200, "/"))
        sw.close()
        mf = self.c.get("/manifest.webmanifest")
        self.assertEqual((mf.status_code, mf.get_json()["start_url"]), (200, "/app"))
        mf.close()
        for asset in ("web.js", "calls.js", "web.css", "icon-192.png"):
            r = self.c.get("/static/web/" + asset)
            self.assertEqual(r.status_code, 200, asset)
            r.close()
        dl = self.c.get("/download")                                          # the app itself is served from static/homebase.apk
        self.assertEqual(dl.status_code, 200 if os.path.exists(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static", "homebase.apk")) else 404)
        dl.close()


class ScreenTime(Base):
    DAY = "2026-10-10"

    def send(self, who, **apps):
        return self.post(who, "/api/screentime", day=self.DAY, apps=[{"pkg": k, "label": k.title(), "cat": c, "sec": s} for k, (c, s) in apps.items()])

    def test_a_child_reports_and_a_parent_sees_totals_by_app(self):
        self.send(self.kid, roblox=("play", 1800), youtube=("watch", 900))
        d = self.get(self.mum, f"/api/screentime?member={self.kid_id}").get_json()
        self.assertEqual(d["days"][0]["total"], 2700)
        self.assertEqual([a["pkg"] for a in d["days"][0]["apps"]], ["roblox", "youtube"])          # biggest first

    def test_sending_again_replaces_the_running_total_instead_of_adding(self):
        self.send(self.kid, roblox=("play", 600))
        self.send(self.kid, roblox=("play", 1200))
        self.assertEqual(self.get(self.mum, f"/api/screentime?member={self.kid_id}").get_json()["days"][0]["total"], 1200)

    def test_the_child_can_turn_it_off_for_parents_but_still_sees_their_own(self):
        self.send(self.kid, roblox=("play", 600))
        self.c.patch("/api/me", headers=self.kid, json={"perms": {"screentime": False}})
        r = self.get(self.mum, f"/api/screentime?member={self.kid_id}")
        self.assertEqual((r.status_code, r.get_json()["perm"]), (403, "screentime"))
        self.assertEqual(self.get(self.kid, "/api/screentime").status_code, 200)

    def test_nonsense_is_cleaned_up_and_bad_days_refused(self):
        self.assertEqual(self.post(self.kid, "/api/screentime", day="today", apps=[]).status_code, 400)
        self.send(self.kid, x=("weird", 10 ** 9))
        a = self.get(self.kid, "/api/screentime").get_json()["days"][0]["apps"][0]
        self.assertEqual((a["cat"], a["sec"]), ("play", 86400))

    def test_every_twenty_minutes_the_childs_devices_get_a_friendly_nudge(self):
        self.send(self.kid, roblox=("play", 600))                                               # 10 min: nothing yet
        self.assertEqual([e for e in self.events(self.kid) if e["type"] == "nudge"], [])
        self.send(self.kid, roblox=("play", 900), youtube=("watch", 600))                       # 25 min together: the first nudge
        n = [e for e in self.events(self.kid) if e["type"] == "nudge"]
        self.assertEqual(len(n), 1)
        self.assertIn("25 minutes", n[0]["payload"]["text"])
        self.send(self.kid, roblox=("play", 1000), youtube=("watch", 600))                      # 26 min: not another one yet
        self.assertEqual(len([e for e in self.events(self.kid) if e["type"] == "nudge"]), 1)
        self.send(self.kid, roblox=("play", 1500), youtube=("watch", 1000))                     # 42 min: the second
        self.assertEqual(len([e for e in self.events(self.kid) if e["type"] == "nudge"]), 2)

    def test_the_nudges_include_a_chore_for_a_real_parent(self):
        texts = [server.nudge_text({"family_id": db.one(db.select(db.members).where(db.members.c.id == self.kid_id))["family_id"]}, i, 1200 * i) for i in range(1, 13)]
        self.assertTrue(any("Mum" in t and "water" in t for t in texts))                         # "Bring Mum a glass of water"
        self.assertTrue(any("bike" in t for t in texts) and any("book" in t for t in texts))

    def test_the_windows_tracker_is_made_for_the_child_with_their_own_key(self):
        r = self.get(self.kid, "/api/screentime/agent?format=ps1")
        text = r.get_data(as_text=True)
        self.assertEqual(r.status_code, 200)
        self.assertIn(self.kid["Authorization"].split()[1], text)
        self.assertNotIn("{{TOKEN}}", text)
        self.assertNotIn("{{SERVER}}", text)
        self.assertEqual(self.get(self.mum, "/api/screentime/agent").status_code, 403)         # parents are not tracked

    def test_the_download_is_a_double_clickable_cmd_that_carries_the_script(self):
        r = self.get(self.kid, "/api/screentime/agent")
        raw = r.get_data()
        self.assertIn("Homebase-ScreenTime.cmd", r.headers["Content-Disposition"])
        self.assertTrue(raw.startswith(b"@echo off\r\n"))                                              # a real batch file with Windows line endings
        self.assertNotIn(b"\n", raw.replace(b"\r\n", b""))
        self.assertIn(b"-ExecutionPolicy Bypass", raw)                                                 # so a blocked download still runs
        self.assertIn(b"\r\n##PS1\r\n", raw)                                                         # the marker the launcher cuts the script out at
        self.assertIn(self.kid["Authorization"].split()[1].encode(), raw)
        raw.decode("ascii")                                                                            # plain ASCII, nothing a console can mangle

    def test_the_tracker_posts_to_https_when_the_proxy_says_so(self):
        r = self.c.get("/api/screentime/agent?format=ps1", headers={**self.kid, "X-Forwarded-Proto": "https"})
        self.assertIn('$Server = "https://', r.get_data(as_text=True))

    def test_junk_numbers_do_not_crash_it(self):
        r = self.post(self.kid, "/api/screentime", day=self.DAY, apps=[{"pkg": "x", "sec": "lots"}, {"pkg": "y", "sec": "90"}, {"pkg": "z", "sec": None}])
        self.assertEqual(r.status_code, 200)
        self.assertEqual(self.post(self.mum, "/api/lock", member="abc", minutes=10).status_code, 404)

    def test_a_child_can_delete_it_and_nobody_else_can_read_it(self):
        self.send(self.kid, roblox=("play", 600))
        self.assertEqual(self.c.delete("/api/screentime", headers=self.kid).status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/screentime").get_json()["days"], [])
        self.assertEqual(self.get(self.kid, f"/api/screentime?member={self.mum_id}").status_code, 403)   # a child cannot read a parent's


class Locks(Base):
    def lock(self, minutes, who=None):
        return self.post(who or self.mum, "/api/lock", member=self.kid_id, minutes=minutes)

    def test_a_parent_locks_a_child_and_everyone_can_see_it(self):
        r = self.lock(60)
        self.assertEqual(r.status_code, 200)
        v = {m["id"]: m for m in self.get(self.kid, "/api/family").get_json()["members"]}[self.kid_id]
        self.assertEqual((v["lock"]["by_name"], round(v["lock"]["until"] - time.time()) // 60), ("Mum", 59 + 1))
        self.assertTrue(any(e["type"] == "member" and e["payload"].get("lock") for e in self.events(self.kid)))              # the child's devices are told at once
        said = self.get(self.kid, "/api/messages?with=0").get_json()
        self.assertTrue(any("locked Sam" in m["body"] for m in said["messages"]))                                          # and the family sees it in the chat

    def test_never_more_than_four_hours_a_day(self):
        self.assertEqual(self.lock(240).status_code, 200)
        self.post(self.mum, f"/api/lock/{self.kid_id}/release")                                  # unlocking straight away gives the time back
        self.assertEqual(self.lock(240).status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/lock").get_json()["left"] // 60, 0)
        self.post(self.mum, f"/api/lock/{self.kid_id}/release")
        self.assertEqual(self.lock(10).status_code, 200)

    def test_the_limit_is_enforced_after_an_early_release_too(self):
        self.lock(180)
        self.post(self.mum, f"/api/lock/{self.kid_id}/release")
        r = self.lock(200)                                                                        # about 0 used + 200 min is fine
        self.assertEqual(r.status_code, 200)
        self.post(self.mum, f"/api/lock/{self.kid_id}/release")
        self.assertEqual(self.lock(5).status_code, 200)

    def test_it_cannot_be_stacked_past_four_hours(self):
        self.lock(240)
        r = self.lock(30)
        self.assertEqual(r.status_code, 409)                                                      # already locked
        db.run(db.update(db.locks).where(db.locks.c.member_id == self.kid_id).values(start=time.time() - 4 * 3600 - 5, until=time.time() - 5))   # pretend it has ended: all 4 hours are used
        r = self.lock(30)
        self.assertEqual(r.status_code, 400)
        self.assertIn("4 hours", r.get_json()["error"])

    def test_only_a_parent_can_lock_and_a_child_can_ask_for_it_back(self):
        self.assertEqual(self.lock(30, self.kid).status_code, 403)
        self.assertEqual(self.post(self.kid, f"/api/lock/{self.kid_id}/release").status_code, 403)
        self.lock(30)
        self.assertEqual(self.post(self.kid, "/api/lock/ask").status_code, 200)
        asked = [e for e in self.events(self.mum) if e["type"] == "message" and "asked to be unlocked" in e["payload"]["body"]]
        self.assertTrue(asked and asked[-1]["payload"]["id"] > 0 and asked[-1]["payload"]["name"] == "Sam")      # a proper message, so every screen can show it
        self.assertEqual(self.lock(3).status_code, 400)                                           # too short

    def test_release_unlocks(self):
        self.lock(30)
        self.post(self.mum, f"/api/lock/{self.kid_id}/release")
        v = {m["id"]: m for m in self.get(self.kid, "/api/family").get_json()["members"]}[self.kid_id]
        self.assertIsNone(v["lock"])

    def test_homebase_asks_cramly_whether_a_key_is_the_owners(self):
        from unittest import mock
        ok = mock.MagicMock()
        ok.__enter__.return_value.status = 200
        with mock.patch("urllib.request.urlopen", return_value=ok) as call:
            server._owner_seen.clear()
            self.assertTrue(server.verify_owner("the-owners-real-key"))
            self.assertTrue(server.verify_owner("the-owners-real-key"))                          # remembered for a minute
            self.assertEqual(call.call_count, 1)
        with mock.patch("urllib.request.urlopen", side_effect=OSError("down")):
            self.assertFalse(server.verify_owner("another-long-wrong-key"))                      # if Cramly cannot be reached, nobody gets in
        self.assertFalse(server.verify_owner("short"))

    def test_the_owner_can_see_and_lift_any_lock_with_the_owner_key(self):
        self.lock(120)
        old = os.environ.get("HB_ADMIN_KEY")
        os.environ["HB_ADMIN_KEY"] = "owner-key-for-the-tests"
        try:
            self.assertEqual(self.c.get("/api/admin/locks").status_code, 403)
            h = {"X-Admin-Key": "owner-key-for-the-tests"}
            locks = self.c.get("/api/admin/locks", headers=h).get_json()["locks"]
            mine = max(locks, key=lambda x: x["id"])                                              # the newest is the one just made
            self.assertEqual((mine["child"], mine["by"]), ("Sam", "Mum"))
            self.assertEqual(self.c.post(f"/api/admin/locks/{mine['id']}/lift", headers=h).status_code, 200)
            self.assertNotIn(mine["id"], [x["id"] for x in self.c.get("/api/admin/locks", headers=h).get_json()["locks"]])
            v = {m["id"]: m for m in self.get(self.kid, "/api/family").get_json()["members"]}[self.kid_id]
            self.assertIsNone(v["lock"])                                                          # and the child is free
        finally:
            os.environ.pop("HB_ADMIN_KEY", None) if old is None else os.environ.__setitem__("HB_ADMIN_KEY", old)


class Home(Base):
    def view(self, who, mid):
        return {m["id"]: m for m in self.get(who, "/api/family").get_json()["members"]}[mid]

    def test_a_parent_sets_the_home_area_and_it_moves_when_set_again(self):
        self.assertEqual(self.post(self.kid, "/api/home", lat=1, lng=1).status_code, 403)
        a = self.post(self.mum, "/api/home", lat=51.5, lng=-0.12, radius=250).get_json()
        b = self.post(self.mum, "/api/home", lat=51.6, lng=-0.2).get_json()
        self.assertEqual(a["id"], b["id"])                                                       # one Home, not two
        places = self.get(self.mum, "/api/family").get_json()["places"]
        home = [p for p in places if p["name"] == "Home"]
        self.assertEqual((len(home), home[0]["emoji"], round(home[0]["lat"], 1)), (1, "🏠", 51.6))

    def test_people_show_as_at_home_only_while_inside_the_area(self):
        self.post(self.mum, "/api/home", lat=51.5, lng=-0.12, radius=200)
        self.assertFalse(self.view(self.mum, self.kid_id)["home"])
        self.where(self.kid, 51.5001, -0.1201)
        self.assertTrue(self.view(self.mum, self.kid_id)["home"])
        self.where(self.kid, 51.6, -0.3)                                                          # far away
        self.assertFalse(self.view(self.mum, self.kid_id)["home"])


class Rules(Base):
    def test_a_parent_sets_the_rules_and_everyone_can_read_what_applies_to_them(self):
        self.assertEqual(self.post(self.kid, "/api/rules", text="x").status_code, 403)
        self.post(self.mum, "/api/rules", text="Homework first. Phones off at dinner.", limits={str(self.kid_id): 90})
        m = self.get(self.mum, "/api/rules").get_json()
        self.assertEqual((m["text"], m["limits"][str(self.kid_id)], m["children"][0]["name"]), ("Homework first. Phones off at dinner.", 90, "Sam"))
        k = self.get(self.kid, "/api/rules").get_json()
        self.assertEqual((k["text"], k["limits"], k["children"]), ("Homework first. Phones off at dinner.", {str(self.kid_id): 90}, []))    # the child reads theirs

    def test_reaching_the_limit_nudges_the_child_and_tells_the_family_once(self):
        self.post(self.mum, "/api/rules", limits={str(self.kid_id): 30})
        sent = lambda sec: self.post(self.kid, "/api/screentime", day="2026-10-11", apps=[{"pkg": "roblox", "label": "Roblox", "cat": "play", "sec": sec}])
        sent(900)
        self.assertEqual([e for e in self.events(self.kid) if e["type"] == "nudge" and "limit" in e["payload"]["text"]], [])
        sent(1900)
        self.assertEqual(len([e for e in self.events(self.kid) if e["type"] == "nudge" and "daily limit of 30" in e["payload"]["text"]]), 1)
        sent(2500)
        self.assertEqual(len([e for e in self.events(self.kid) if e["type"] == "nudge" and "daily limit of 30" in e["payload"]["text"]]), 1)


class Chores(Base):
    def add(self, title="Take out the bins", points=10, to=0):
        return self.post(self.mum, "/api/chores", title=title, points=points, to=to or self.kid_id).get_json()["id"]

    def test_the_whole_life_of_a_job_and_the_points(self):
        cid = self.add()
        self.assertEqual(self.get(self.kid, "/api/chores").get_json()["chores"][0]["status"], "open")
        self.assertEqual(self.post(self.kid, f"/api/chores/{cid}/approve").status_code, 403)                  # a child cannot approve their own
        self.assertEqual(self.post(self.kid, f"/api/chores/{cid}/done").status_code, 200)
        self.assertEqual(self.get(self.mum, "/api/chores").get_json()["points"], {})                          # not yet: it needs a parent
        self.assertEqual(self.post(self.mum, f"/api/chores/{cid}/approve").status_code, 200)
        d = self.get(self.mum, "/api/chores").get_json()
        self.assertEqual((d["chores"][0]["status"], d["points"][str(self.kid_id)]), ("approved", 10))
        said = [m["body"] for m in self.get(self.kid, "/api/messages?with=0").get_json()["messages"]]
        self.assertTrue(any("finished: Take out the bins" in b for b in said) and any("+10 points" in b for b in said))   # the family sees every step

    def test_not_good_enough_sends_it_back_without_points(self):
        cid = self.add()
        self.post(self.kid, f"/api/chores/{cid}/done")
        self.post(self.mum, f"/api/chores/{cid}/approve", ok=False)
        c = self.get(self.mum, "/api/chores").get_json()
        self.assertEqual((c["chores"][0]["status"], c["points"]), ("open", {}))

    def test_only_parents_add_and_a_job_for_one_child_is_theirs(self):
        self.assertEqual(self.post(self.kid, "/api/chores", title="x").status_code, 403)
        self.assertEqual(self.post(self.mum, "/api/chores", title="").status_code, 400)
        self.assertEqual(self.post(self.mum, "/api/chores", title="x", to=self.mum_id).status_code, 404)    # must be a child
        cid = self.post(self.mum, "/api/chores", title="Any child", points=3).get_json()["id"]              # to nobody = any child
        self.assertEqual(self.post(self.kid, f"/api/chores/{cid}/done").status_code, 200)

    def test_junk_points_and_deleting(self):
        self.assertEqual(self.post(self.mum, "/api/chores", title="x", points="lots").status_code, 400)
        cid = self.post(self.mum, "/api/chores", title="x", points=9999).get_json()["id"]
        self.assertEqual(self.get(self.mum, "/api/chores").get_json()["chores"][0]["points"], 100)           # capped
        self.assertEqual(self.c.delete(f"/api/chores/{cid}", headers=self.kid).status_code, 403)
        self.assertEqual(self.c.delete(f"/api/chores/{cid}", headers=self.mum).status_code, 200)

    def test_the_weekly_summary_pulls_it_together(self):
        today = time.strftime("%Y-%m-%d", time.gmtime())
        self.post(self.kid, "/api/screentime", day=today, apps=[{"pkg": "roblox", "label": "Roblox", "cat": "play", "sec": 3000}, {"pkg": "yt", "label": "YouTube", "cat": "watch", "sec": 600}])
        cid = self.add(points=7)
        self.post(self.kid, f"/api/chores/{cid}/done"); self.post(self.mum, f"/api/chores/{cid}/approve")
        self.post(self.mum, "/api/lock", member=self.kid_id, minutes=30)
        d = self.get(self.mum, f"/api/summary?member={self.kid_id}").get_json()
        self.assertEqual((d["screen"]["total"], d["screen"]["top"][0]["label"], d["chores"], d["locks"]["minutes"]), (3600, "Roblox", {"done": 1, "points": 7}, 0))
        self.c.patch("/api/me", headers=self.kid, json={"perms": {"screentime": False}})
        self.assertIsNone(self.get(self.mum, f"/api/summary?member={self.kid_id}").get_json()["screen"])      # hidden when the child switched it off
        self.assertEqual(self.get(self.kid, "/api/summary").get_json()["screen"]["total"], 3600)             # but they still see their own


class Devices(Base):
    def claim(self, code, **extra):
        return self.c.post("/api/devices/claim", json={"code": code, "name": "Sam's laptop", **extra})

    def test_a_code_links_a_device_that_acts_as_the_person(self):
        code = self.post(self.kid, "/api/devices/code").get_json()["code"]
        r = self.claim(code)
        self.assertEqual(r.status_code, 200)
        dev = {"Authorization": "Bearer " + r.get_json()["token"]}
        self.assertEqual(self.post(dev, "/api/screentime", day="2026-10-12", apps=[{"pkg": "pc:roblox", "label": "Roblox", "cat": "play", "sec": 600}]).status_code, 200)
        self.assertEqual(self.get(self.mum, f"/api/screentime?member={self.kid_id}").get_json()["days"][0]["total"], 600)   # it counted as Sam
        self.assertEqual(self.claim(code).status_code, 400)                                                                # a code works once
        said = [m["body"] for m in self.get(self.mum, "/api/messages?with=0").get_json()["messages"]]
        self.assertTrue(any("linked a windows device" in b for b in said))                                                 # and the family is told

    def test_linked_computers_show_up_for_the_family_with_when_they_last_reported(self):
        code = self.post(self.kid, "/api/devices/code").get_json()["code"]
        dev = {"Authorization": "Bearer " + self.claim(code).get_json()["token"]}
        self.get(dev, "/api/lock")                                                                         # the computer checks in
        v = {m["id"]: m for m in self.get(self.mum, "/api/family").get_json()["members"]}[self.kid_id]
        self.assertEqual((v["devices"][0]["name"], v["devices"][0]["kind"]), ("Sam's laptop", "windows"))
        self.assertLess(time.time() - v["devices"][0]["last_seen"], 5)

    def test_wrong_and_old_codes_are_refused_and_a_device_can_be_removed(self):
        self.assertEqual(self.claim("000000").status_code, 400)
        code = self.post(self.kid, "/api/devices/code").get_json()["code"]
        dev = {"Authorization": "Bearer " + self.claim(code).get_json()["token"]}
        did = self.get(self.kid, "/api/devices").get_json()["devices"][0]["id"]
        self.assertEqual(self.c.delete(f"/api/devices/{did}", headers=self.kid).status_code, 200)
        self.assertEqual(self.get(dev, "/api/family").status_code, 401)                                                    # its key stops working at once


class WindowsDownload(Base):
    def test_the_program_is_served_as_a_download(self):
        r = self.c.get("/download/windows")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_data()[:2], b"MZ")                                                      # a real Windows program
        self.assertIn("HomebaseTracker.exe", r.headers["Content-Disposition"])


class DeviceStatus(Base):
    def test_a_computer_reports_what_it_is_doing_and_the_family_sees_it(self):
        code = self.post(self.kid, "/api/devices/code").get_json()["code"]
        dev = {"Authorization": "Bearer " + self.c.post("/api/devices/claim", json={"code": code, "name": "Laptop"}).get_json()["token"]}
        self.assertEqual(self.post(self.kid, "/api/devices/status", status="x").status_code, 403)                       # only a device
        self.post(dev, "/api/devices/status", status="counting Minecraft (play)", version="1.5")
        v = {m["id"]: m for m in self.get(self.mum, "/api/family").get_json()["members"]}[self.kid_id]
        self.assertEqual((v["devices"][0]["status"], v["devices"][0]["version"]), ("counting Minecraft (play)", "1.5"))


class ScreenOnTheCard(Base):
    def card(self, who):
        return {m["id"]: m for m in self.get(who, "/api/family").get_json()["members"]}[self.kid_id]["screen"]

    def test_a_parent_sees_todays_minutes_and_top_app_on_the_childs_card(self):
        today = time.strftime("%Y-%m-%d", time.gmtime())
        self.post(self.kid, "/api/screentime", day=today, apps=[{"pkg": "roblox", "label": "Roblox", "cat": "play", "sec": 2400}, {"pkg": "yt", "label": "YouTube", "cat": "watch", "sec": 600}])
        c = self.card(self.mum)
        self.assertEqual((c["hidden"], c["today"], c["top"]), (False, 3000, "Roblox"))
        self.assertEqual(self.card(self.kid)["today"], 3000)                                                  # the child sees the same number

    def test_when_the_child_hides_it_the_parent_is_told_so_not_shown_zero(self):
        today = time.strftime("%Y-%m-%d", time.gmtime())
        self.post(self.kid, "/api/screentime", day=today, apps=[{"pkg": "roblox", "label": "Roblox", "cat": "play", "sec": 2400}])
        self.c.patch("/api/me", headers=self.kid, json={"perms": {"screentime": False}})
        c = self.card(self.mum)
        self.assertEqual((c["hidden"], c["today"]), (True, 0))
        self.assertEqual(self.card(self.kid)["today"], 2400)                                                  # the child still sees their own

    def test_a_day_with_nothing_is_zero_not_missing(self):
        self.assertEqual((self.card(self.mum)["hidden"], self.card(self.mum)["today"], self.card(self.mum)["top"]), (False, 0, ""))


class AppReporting(Base):
    def test_a_device_reports_its_apps_for_the_picker(self):
        code = self.post(self.kid, "/api/devices/code").get_json()["code"]
        dev = {"Authorization": "Bearer " + self.c.post("/api/devices/claim", json={"code": code, "name": "Laptop"}).get_json()["token"]}
        self.assertEqual(self.post(self.kid, "/api/devices/apps", apps=[{"key": "roblox", "label": "Roblox"}]).status_code, 403)   # only a device
        self.post(dev, "/api/devices/apps", apps=[{"key": "roblox", "label": "Roblox"}, {"key": "chrome", "label": "Chrome"}, {"key": "x", "label": "too short"}])
        avail = self.get(self.mum, f"/api/blocks?member={self.kid_id}").get_json()["available"]
        self.assertEqual(sorted(a["key"] for a in avail), ["chrome", "roblox"])                         # the 1-letter key is dropped


class CallHandoff(Base):
    OFFER = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"

    def test_a_web_page_can_say_it_is_ringing_so_a_computer_does_not_ring_twice(self):
        cid = self.post(self.mum, "/api/calls", to=self.kid_id, offer=self.OFFER).get_json()["id"]
        self.assertFalse(self.get(self.kid, f"/api/calls/{cid}").get_json()["web_seen"])
        self.assertEqual(self.post(self.mum, f"/api/calls/{cid}/seen").status_code, 404)             # only the person being called
        self.assertEqual(self.post(self.kid, f"/api/calls/{cid}/seen").status_code, 200)
        self.assertTrue(self.get(self.kid, f"/api/calls/{cid}").get_json()["web_seen"])

    def test_the_person_being_called_can_fetch_the_call_to_answer_it_from_anywhere(self):
        cid = self.post(self.mum, "/api/calls", to=self.kid_id, offer=self.OFFER).get_json()["id"]
        d = self.get(self.kid, f"/api/calls/{cid}").get_json()
        self.assertEqual((d["status"], d["name"], d["from"], d["offer"]), ("ringing", "Mum", self.mum_id, self.OFFER))
        c = self.get(self.mum, f"/api/calls/{cid}").get_json()                                       # the caller does not get their own offer echoed back
        self.assertNotIn("offer", c)
        self.post(self.kid, f"/api/calls/{cid}/decline")
        self.assertNotIn("offer", self.get(self.kid, f"/api/calls/{cid}").get_json())               # and once it is over there is nothing to answer


class WindowsUpdate(Base):
    def test_the_newest_build_is_published_for_self_update(self):
        d = self.c.get("/api/windows/version").get_json()                                              # no sign-in needed: a computer asks before it is signed in
        self.assertGreater(d["build"], 0)
        self.assertGreater(d["size"], 20000)
        self.assertEqual(d["url"], "/download/windows")


class Grounding(Base):
    def ground(self, apps=("roblox", "Minecraft"), minutes=60, who=None):
        return self.post(who or self.mum, "/api/blocks", member=self.kid_id, apps=[{"key": a.lower(), "label": a} for a in apps], minutes=minutes)

    def test_a_parent_grounds_apps_and_the_child_and_family_see_it(self):
        self.assertEqual(self.ground().status_code, 200)
        mine = self.get(self.kid, "/api/blocks").get_json()["blocks"]
        self.assertEqual(sorted(b["key"] for b in mine), ["minecraft", "roblox"])
        said = [m["body"] for m in self.get(self.kid, "/api/messages?with=0").get_json()["messages"]]
        self.assertTrue(any("grounding Sam from" in b and "1 minute" in b for b in said))
        v = {m["id"]: m for m in self.get(self.kid, "/api/family").get_json()["members"]}[self.kid_id]
        self.assertEqual(len(v["blocks"]), 2)
        self.assertAlmostEqual(v["blocks"][0]["active_at"] - time.time(), 60, delta=5)                 # a minute's warning
        self.assertTrue(any(e["type"] == "ground_warn" for e in self.events(self.kid)))               # the device is warned

    def test_only_parents_ground_and_they_can_lift_it(self):
        self.assertEqual(self.ground(who=self.kid).status_code, 403)
        self.assertEqual(self.ground(minutes=2).status_code, 400)
        self.assertEqual(self.ground(apps=("x",)).status_code, 400)
        self.ground(apps=("roblox",))
        bid = self.get(self.kid, "/api/blocks").get_json()["blocks"][0]["id"]
        self.assertEqual(self.c.delete(f"/api/blocks/{bid}", headers=self.kid).status_code, 403)
        self.assertEqual(self.c.delete(f"/api/blocks/{bid}", headers=self.mum).status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/blocks").get_json()["blocks"], [])


class OwnerTools(Base):
    def setUp(self):
        super().setUp()
        os.environ["HB_ADMIN_KEY"] = "owner-key-for-the-tests"
        self.h = {"X-Admin-Key": "owner-key-for-the-tests"}

    def tearDown(self):
        os.environ.pop("HB_ADMIN_KEY", None)

    def test_the_owner_can_list_families_invite_announce_and_ground(self):
        self.assertEqual(self.c.get("/api/admin/families").status_code, 403)
        fams = self.c.get("/api/admin/families", headers=self.h).get_json()["families"]
        mine = next(f for f in fams if any(m["id"] == self.kid_id for m in f["members"]))
        inv = self.c.post("/api/admin/invite", headers=self.h, json={"family_id": mine["id"], "role": "child"}).get_json()
        j = self.c.post("/api/join", json={"code": inv["code"], "name": "Jo", "emoji": "x"})
        self.assertEqual(j.status_code, 200)                                                                  # the owner's code really joins the family
        self.assertEqual(self.c.post("/api/admin/announce", headers=self.h, json={"family_id": mine["id"], "text": "Pizza night"}).status_code, 200)
        said = [m["body"] for m in self.get(self.kid, "/api/messages?with=0").get_json()["messages"]]
        self.assertTrue(any("Announcement: Pizza night" in b for b in said))
        self.assertTrue(any(e["type"] == "message" and "Pizza night" in e["payload"]["body"] for e in self.events(self.kid)))   # and it pops up on their devices
        code = self.post(self.kid, "/api/devices/code").get_json()["code"]
        dev = self.c.post("/api/devices/claim", json={"code": code, "name": "Sam's laptop"}).get_json()["token"]
        fam = next(f for f in self.c.get("/api/admin/families", headers=self.h).get_json()["families"] if f["id"] == mine["id"])
        did = next(m for m in fam["members"] if m["id"] == self.kid_id)["device_list"][0]["id"]
        feed = self.c.get("/api/admin/activity", headers=self.h).get_json()["items"]
        self.assertTrue(any("linked a windows device" in i["text"] for i in feed))                          # the feed shows what happened
        self.assertEqual(self.c.delete(f"/api/admin/devices/{did}", headers=self.h).status_code, 200)       # the owner can cut a device off
        self.assertEqual(self.get({"Authorization": "Bearer " + dev}, "/api/family").status_code, 401)
        self.assertEqual(self.c.post("/api/admin/blocks", headers=self.h, json={"member_id": self.kid_id, "apps": ["tiktok"], "minutes": 30}).status_code, 200)
        bl = self.c.get("/api/admin/blocks", headers=self.h).get_json()["blocks"]
        mineb = next(b for b in bl if b["member_id"] == self.kid_id)
        self.assertEqual(self.c.delete(f"/api/admin/blocks/{mineb['id']}", headers=self.h).status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/blocks").get_json()["blocks"], [])


class Avatars(Base):
    PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    def test_set_get_and_clear_a_photo(self):
        self.assertIsNone({m["id"]: m for m in self.get(self.mum, "/api/family").get_json()["members"]}[self.mum_id]["avatar"])
        r = self.post(self.mum, "/api/avatar", image="data:image/png;base64," + self.PNG)
        self.assertEqual(r.status_code, 200)
        url = r.get_json()["avatar"]
        self.assertTrue(url.startswith(f"/api/avatar/{self.mum_id}"))
        img = self.get(self.mum, f"/api/avatar/{self.mum_id}")
        self.assertEqual((img.status_code, img.headers["Content-Type"]), (200, "image/png"))
        self.assertEqual({m["id"]: m for m in self.get(self.kid, "/api/family").get_json()["members"]}[self.mum_id]["avatar"][:len(url.split("?")[0])], url.split("?")[0])   # family sees it
        self.assertEqual(self.c.delete("/api/avatar", headers=self.mum).status_code, 200)
        self.assertIsNone({m["id"]: m for m in self.get(self.mum, "/api/family").get_json()["members"]}[self.mum_id]["avatar"])

    def test_junk_and_oversized_are_refused(self):
        self.assertEqual(self.post(self.mum, "/api/avatar", image="data:image/png;base64,not-base64!!").status_code, 400)
        self.assertEqual(self.post(self.mum, "/api/avatar", image="data:text/plain;base64,aGVsbG8=").status_code, 400)   # valid base64, not an image
        self.assertEqual(self.get(self.kid, f"/api/avatar/{self.mum_id}").status_code, 404)                              # none set


class Status(Base):
    """"What I am up to": set by the person, seen by the family, ends by itself."""

    def members(self, who):
        return {m["id"]: m for m in self.get(who, "/api/family").get_json()["members"]}

    def test_a_child_sets_a_status_and_the_family_sees_it(self):
        r = self.post(self.kid, "/api/status", emoji="📚", text="Studying", hours=2)
        self.assertEqual(r.status_code, 200)
        self.assertGreater(r.get_json()["status"]["until"], time.time() + 3600)
        for who in (self.mum, self.kid):
            st = self.members(who)[self.kid_id]["status"]
            self.assertEqual((st["emoji"], st["text"]), ("📚", "Studying"))
        self.assertIsNone(self.members(self.mum)[self.mum_id]["status"])
        self.assertTrue(any(e["type"] == "member" and e["payload"].get("id") == self.kid_id for e in self.events(self.mum)), "the family is told so the screen refreshes")

    def test_it_ends_by_itself_and_can_be_cleared(self):
        self.post(self.kid, "/api/status", emoji="🎮", text="", hours=1)
        db.run(db.update(db.members).where(db.members.c.id == self.kid_id).values(status_until=time.time() - 5))
        self.assertIsNone(self.members(self.mum)[self.kid_id]["status"])
        self.post(self.kid, "/api/status", emoji="🍽️", text="Dinner")
        self.assertIsNotNone(self.members(self.mum)[self.kid_id]["status"])
        self.assertEqual(self.c.delete("/api/status", headers=self.kid).status_code, 200)
        self.assertIsNone(self.members(self.mum)[self.kid_id]["status"])

    def test_it_needs_something_and_has_sane_limits(self):
        self.assertEqual(self.post(self.kid, "/api/status", emoji="", text="  ").status_code, 400)
        self.assertEqual(self.post(self.kid, "/api/status", emoji="🙂", hours="soon").status_code, 400)
        r = self.post(self.kid, "/api/status", emoji="🙂", text="x" * 200, hours=999).get_json()["status"]
        self.assertEqual(len(r["text"]), 40)
        self.assertLessEqual(r["until"], time.time() + 24 * 3600 + 5)
        self.assertEqual(self.c.post("/api/status", json={"emoji": "🙂"}).status_code, 401)

    def test_only_you_can_set_your_own_status_and_other_families_never_see_it(self):
        self.post(self.kid, "/api/status", emoji="😴", text="Sleeping")
        self.assertIsNone(self.members(self.mum)[self.mum_id]["status"])                            # a parent cannot set it for the child, there is no such route
        other = self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()
        seen = self.c.get("/api/family", headers={"Authorization": "Bearer " + other["token"]}).get_json()["members"]
        self.assertEqual([m["name"] for m in seen], ["Zed"])


class Reactions(Base):
    def make(self, who=None, **kw):
        return self.post(who or self.kid, "/api/messages", body="Home by five", **kw).get_json()["message"]["id"]

    def test_react_toggles_and_everyone_in_the_thread_is_told(self):
        mid = self.make()
        self.assertEqual(self.post(self.mum, f"/api/messages/{mid}/react", emoji="👍").get_json()["reactions"], {"👍": [self.mum_id]})
        self.assertEqual(self.post(self.kid, f"/api/messages/{mid}/react", emoji="👍").get_json()["reactions"], {"👍": [self.mum_id, self.kid_id]})
        shown = self.get(self.mum, "/api/messages").get_json()["messages"][-1]
        self.assertEqual(shown["reactions"]["👍"], [self.mum_id, self.kid_id])
        self.assertEqual(self.post(self.mum, f"/api/messages/{mid}/react", emoji="👍").get_json()["reactions"], {"👍": [self.kid_id]})
        self.assertEqual(self.post(self.kid, f"/api/messages/{mid}/react", emoji="👍").get_json()["reactions"], {})
        self.assertTrue(any(e["type"] == "reaction" and e["payload"]["id"] == mid for e in self.events(self.mum)))

    def test_only_the_offered_reactions_work(self):
        mid = self.make()
        for bad in ("🤬", "", "thumbs", None):
            self.assertEqual(self.post(self.mum, f"/api/messages/{mid}/react", emoji=bad).status_code, 400, bad)

    def test_a_private_chat_can_only_be_reacted_to_by_its_two_people(self):
        mid = self.make(to=self.mum_id)
        dad = self.c.post("/api/invite", headers=self.mum, json={"role": "parent"}).get_json()["code"]
        dad = {"Authorization": "Bearer " + self.c.post("/api/join", json={"code": dad, "name": "Dad"}).get_json()["token"]}
        self.assertEqual(self.post(dad, f"/api/messages/{mid}/react", emoji="❤️").status_code, 404)
        self.assertEqual(self.post(self.mum, f"/api/messages/{mid}/react", emoji="❤️").status_code, 200)
        self.assertFalse(any(e["type"] == "reaction" for e in self.events(dad)), "a third person is not told about a private chat")

    def test_system_messages_missing_messages_and_other_families_are_refused(self):
        system = db.one(db.select(db.messages).where(db.messages.c.kind == "system"))
        if system:
            self.assertEqual(self.post(self.mum, f"/api/messages/{system['id']}/react", emoji="👍").status_code, 404)
        self.assertEqual(self.post(self.mum, "/api/messages/99999/react", emoji="👍").status_code, 404)
        mid = self.make()
        other = self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()
        self.assertEqual(self.post({"Authorization": "Bearer " + other["token"]}, f"/api/messages/{mid}/react", emoji="👍").status_code, 404)
        self.assertEqual(self.c.post(f"/api/messages/{mid}/react", json={"emoji": "👍"}).status_code, 401)


class Plans(Base):
    def add(self, who=None, hours=2, **kw):
        body = {"title": "Dinner at Gran's", "emoji": "🍽️", "start": time.time() + hours * 3600, **kw}
        return self.post(who or self.kid, "/api/plans", **body)

    def listing(self, who):
        return self.get(who, "/api/plans").get_json()["plans"]

    def test_a_plan_is_shared_with_the_family_and_the_maker_is_already_in(self):
        pid = self.add().get_json()["id"]
        seen = self.listing(self.mum)
        self.assertEqual([(p["id"], p["title"], p["emoji"], p["in"], p["out"], p["mine"]) for p in seen], [(pid, "Dinner at Gran's", "🍽️", [self.kid_id], [], None)])
        self.assertEqual(self.listing(self.kid)[0]["mine"], "in")
        told = [e for e in self.events(self.mum) if e["type"] == "plan"]
        self.assertEqual((len(told), told[0]["payload"]["new"], told[0]["payload"]["name"]), (1, True, "Sam"))
        self.assertFalse(any(e["type"] == "plan" for e in self.events(self.kid)), "the person who made it is not buzzed about their own plan")

    def test_saying_yes_no_or_taking_it_back(self):
        pid = self.add().get_json()["id"]
        d = self.post(self.mum, f"/api/plans/{pid}/rsvp", answer="in").get_json()["plan"]
        self.assertEqual((sorted(d["in"]), d["out"], d["mine"]), (sorted([self.kid_id, self.mum_id]), [], "in"))
        d = self.post(self.mum, f"/api/plans/{pid}/rsvp", answer="out").get_json()["plan"]
        self.assertEqual((d["in"], d["out"], d["mine"]), ([self.kid_id], [self.mum_id], "out"))
        d = self.post(self.mum, f"/api/plans/{pid}/rsvp", answer="").get_json()["plan"]
        self.assertEqual((d["in"], d["out"], d["mine"]), ([self.kid_id], [], None))
        d = self.post(self.mum, f"/api/plans/{pid}/rsvp", answer="maybe").get_json()["plan"]
        self.assertEqual(d["mine"], None, "only in and out count")

    def test_a_plan_needs_a_name_and_a_sensible_time(self):
        self.assertEqual(self.add(title="  ").status_code, 400)
        self.assertEqual(self.add(start="soon").status_code, 400)
        self.assertEqual(self.add(start=None).status_code, 400)
        self.assertEqual(self.add(hours=24 * 365 * 3).status_code, 400)
        self.assertEqual(self.add(hours=-24 * 3).status_code, 400)
        self.assertEqual(self.add(hours=1).status_code, 200)

    def test_plans_are_in_date_order_and_old_ones_leave_the_list(self):
        late, soon, gone = self.add(hours=30, title="Match").get_json()["id"], self.add(hours=1, title="Dentist").get_json()["id"], self.add(hours=-5, title="Over").get_json()["id"]
        self.assertEqual([p["id"] for p in self.listing(self.mum)], [soon, late])
        self.assertNotIn(gone, [p["id"] for p in self.listing(self.mum)])

    def test_only_the_maker_or_a_parent_can_delete(self):
        mine = self.add(self.mum).get_json()["id"]
        self.assertEqual(self.c.delete(f"/api/plans/{mine}", headers=self.kid).status_code, 404)
        kids = self.add(self.kid).get_json()["id"]
        self.assertEqual(self.c.delete(f"/api/plans/{kids}", headers=self.mum).status_code, 200)
        self.assertEqual(self.c.delete(f"/api/plans/{mine}", headers=self.mum).status_code, 200)
        self.assertEqual(self.listing(self.mum), [])

    def test_another_family_sees_and_changes_nothing(self):
        pid = self.add().get_json()["id"]
        other = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertEqual(self.listing(other), [])
        self.assertEqual(self.post(other, f"/api/plans/{pid}/rsvp", answer="in").status_code, 404)
        self.assertEqual(self.c.delete(f"/api/plans/{pid}", headers=other).status_code, 404)
        self.assertEqual(self.c.get("/api/plans").status_code, 401)

    def test_a_plan_starting_soon_nudges_everyone_except_who_said_no_and_only_once(self):
        soon = self.add(hours=0.2, title="Leave for school").get_json()["id"]
        far = self.add(hours=5, title="Later").get_json()["id"]
        self.post(self.mum, f"/api/plans/{soon}/rsvp", answer="out")
        server._last_remind[0] = 0
        server.remind_plans()
        nudged = lambda who: [e["payload"]["id"] for e in self.events(who) if e["type"] == "plan_soon"]
        self.assertEqual(nudged(self.kid), [soon])
        self.assertEqual(nudged(self.mum), [], "who said they cannot come is left alone")
        self.assertNotIn(far, nudged(self.kid))
        server._last_remind[0] = 0
        server.remind_plans()
        self.assertEqual(nudged(self.kid), [soon], "one nudge per plan")


class Polls(Base):
    def ask(self, who=None, **kw):
        body = {"question": "What's for dinner?", "options": ["Pizza", "Tacos", "Soup"], **kw}
        return self.post(who or self.mum, "/api/polls", **body)

    def listing(self, who):
        return self.get(who, "/api/polls").get_json()["polls"]

    def test_ask_vote_change_your_mind_and_take_it_back(self):
        pid = self.ask().get_json()["id"]
        p = self.listing(self.kid)[0]
        self.assertEqual((p["question"], p["options"], p["votes"], p["mine"], p["closed"]), ("What's for dinner?", ["Pizza", "Tacos", "Soup"], [[], [], []], None, False))
        self.assertTrue(any(e["type"] == "poll" and e["payload"]["new"] for e in self.events(self.kid)))
        p = self.post(self.kid, f"/api/polls/{pid}/vote", option=1).get_json()["poll"]
        self.assertEqual((p["votes"], p["mine"]), ([[], [self.kid_id], []], 1))
        p = self.post(self.kid, f"/api/polls/{pid}/vote", option=2).get_json()["poll"]
        self.assertEqual((p["votes"], p["mine"]), ([[], [], [self.kid_id]], 2), "one vote each: it moves")
        p = self.post(self.kid, f"/api/polls/{pid}/vote", option=2).get_json()["poll"]
        self.assertEqual((p["votes"], p["mine"]), ([[], [], []], None), "tapping your answer again takes it back")
        self.post(self.kid, f"/api/polls/{pid}/vote", option=0)
        self.post(self.mum, f"/api/polls/{pid}/vote", option=0)
        self.assertEqual(self.listing(self.mum)[0]["votes"][0], [self.kid_id, self.mum_id])

    def test_a_question_needs_two_to_four_different_answers(self):
        self.assertEqual(self.ask(question=" ").status_code, 400)
        self.assertEqual(self.ask(options=["Only"]).status_code, 400)
        self.assertEqual(self.ask(options=["Same", "same", " SAME "]).status_code, 400)
        self.assertEqual(self.ask(options=list("abcde")).status_code, 400)
        self.assertEqual(self.ask(options="Pizza").status_code, 400)
        self.assertEqual(self.ask(options=["Yes", "", "No"]).status_code, 200, "blank answers are skipped")
        self.assertEqual(self.listing(self.mum)[0]["options"], ["Yes", "No"])

    def test_votes_must_be_one_of_the_answers(self):
        pid = self.ask().get_json()["id"]
        for bad in (-1, 3, 99, "1", None, True, 1.5):
            self.assertEqual(self.post(self.kid, f"/api/polls/{pid}/vote", option=bad).status_code, 400, bad)

    def test_closing_stops_votes_and_only_the_asker_or_a_parent_can_close_or_delete(self):
        pid = self.ask(self.kid).get_json()["id"]
        self.assertEqual(self.post(self.mum, f"/api/polls/{pid}/close").status_code, 200)
        self.assertTrue(self.listing(self.kid)[0]["closed"])
        self.assertEqual(self.post(self.kid, f"/api/polls/{pid}/vote", option=0).status_code, 400)
        theirs = self.ask(self.mum).get_json()["id"]
        self.assertEqual(self.post(self.kid, f"/api/polls/{theirs}/close").status_code, 404)
        self.assertEqual(self.c.delete(f"/api/polls/{theirs}", headers=self.kid).status_code, 404)
        self.assertEqual(self.c.delete(f"/api/polls/{theirs}", headers=self.mum).status_code, 200)
        self.assertEqual([p["id"] for p in self.listing(self.mum)], [pid])

    def test_open_questions_come_first_and_another_family_sees_nothing(self):
        old = self.ask(question="Old one").get_json()["id"]
        self.post(self.mum, f"/api/polls/{old}/close")
        new = self.ask(question="New one").get_json()["id"]
        self.assertEqual([p["id"] for p in self.listing(self.kid)], [new, old])
        other = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertEqual(self.listing(other), [])
        self.assertEqual(self.post(other, f"/api/polls/{new}/vote", option=0).status_code, 404)
        self.assertEqual(self.c.get("/api/polls").status_code, 401)


class Rewards(Base):
    def earn(self, kid_headers, points):
        cid = self.post(self.mum, "/api/chores", title="Tidy up", points=points).get_json()["id"]
        self.assertEqual(self.post(kid_headers, f"/api/chores/{cid}/done").status_code, 200)
        self.assertEqual(self.post(self.mum, f"/api/chores/{cid}/approve", ok=True).status_code, 200)

    def reward(self, title="Pick the film", cost=20, **kw):
        return self.post(self.mum, "/api/rewards", title=title, cost=cost, **kw)

    def info(self, who):
        return self.get(who, "/api/rewards").get_json()

    def test_points_are_spent_held_and_given_back(self):
        rid = self.reward().get_json()["id"]
        self.assertEqual(self.post(self.kid, f"/api/rewards/{rid}/redeem").status_code, 400, "no points yet")
        self.earn(self.kid, 30)
        self.assertEqual(self.info(self.kid)["balance"], {str(self.kid_id): 30})
        self.assertEqual((self.info(self.kid)["mine"], self.info(self.mum)["mine"]), (30, None), "a child is told their own balance, a parent has none")
        d = self.post(self.kid, f"/api/rewards/{rid}/redeem").get_json()
        self.assertEqual(d["balance"], 10)
        self.assertEqual(self.info(self.kid)["balance"][str(self.kid_id)], 10, "the points are held while a parent decides")
        asked = [e for e in self.events(self.mum) if e["type"] == "reward"]
        self.assertEqual((asked[0]["payload"]["status"], asked[0]["payload"]["name"], asked[0]["payload"]["cost"]), ("pending", "Sam", 20))
        self.assertEqual(self.post(self.mum, f"/api/redemptions/{d['id']}/decide", ok=False).get_json()["status"], "declined")
        self.assertEqual(self.info(self.kid)["balance"][str(self.kid_id)], 30, "a no gives the points back")
        again = self.post(self.kid, f"/api/rewards/{rid}/redeem").get_json()["id"]
        self.assertEqual(self.post(self.mum, f"/api/redemptions/{again}/decide", ok=True).get_json()["status"], "approved")
        self.assertEqual(self.info(self.kid)["balance"][str(self.kid_id)], 10, "a yes spends them")
        told = [e["payload"] for e in self.events(self.kid) if e["type"] == "reward"]
        self.assertEqual([(t["status"], t["title"]) for t in told], [("declined", "Pick the film"), ("approved", "Pick the film")])
        self.assertEqual(self.post(self.mum, f"/api/redemptions/{again}/decide", ok=True).status_code, 404, "already answered")

    def test_asking_twice_cannot_spend_the_same_points_twice(self):
        rid = self.reward().get_json()["id"]
        self.earn(self.kid, 30)
        self.assertEqual(self.post(self.kid, f"/api/rewards/{rid}/redeem").status_code, 200)
        r = self.post(self.kid, f"/api/rewards/{rid}/redeem")
        self.assertEqual(r.status_code, 400)
        self.assertIn("10 more", r.get_json()["error"])

    def test_at_most_three_waiting_and_a_child_can_take_a_request_back(self):
        rid = self.reward(cost=1).get_json()["id"]
        self.earn(self.kid, 50)
        ids = [self.post(self.kid, f"/api/rewards/{rid}/redeem").get_json()["id"] for _ in range(3)]
        self.assertEqual(self.post(self.kid, f"/api/rewards/{rid}/redeem").status_code, 400)
        self.assertEqual(self.c.delete(f"/api/redemptions/{ids[0]}", headers=self.kid).status_code, 200)
        self.assertEqual(self.info(self.kid)["balance"][str(self.kid_id)], 48)
        self.assertEqual(self.c.delete(f"/api/redemptions/{ids[0]}", headers=self.kid).status_code, 404)
        self.post(self.mum, f"/api/redemptions/{ids[1]}/decide", ok=True)
        self.assertEqual(self.c.delete(f"/api/redemptions/{ids[1]}", headers=self.kid).status_code, 404, "an answered request stays")

    def test_who_may_do_what(self):
        self.assertEqual(self.post(self.kid, "/api/rewards", title="Free money", cost=1).status_code, 403)
        rid = self.reward().get_json()["id"]
        self.assertEqual(self.post(self.mum, f"/api/rewards/{rid}/redeem").status_code, 403, "points belong to children")
        self.earn(self.kid, 30)
        qid = self.post(self.kid, f"/api/rewards/{rid}/redeem").get_json()["id"]
        self.assertEqual(self.post(self.kid, f"/api/redemptions/{qid}/decide", ok=True).status_code, 403, "a child cannot approve their own")
        self.assertEqual(self.c.delete(f"/api/rewards/{rid}", headers=self.kid).status_code, 403)
        self.assertEqual(self.c.delete(f"/api/rewards/{rid}", headers=self.mum).status_code, 200)
        self.assertEqual(self.info(self.mum)["rewards"], [])
        self.assertEqual(self.post(self.mum, f"/api/rewards/{rid}/redeem").status_code, 403)

    def test_a_reward_needs_a_name_and_a_sensible_cost(self):
        self.assertEqual(self.reward(title=" ").status_code, 400)
        for bad in (0, -5, 1001, "lots", None):
            self.assertEqual(self.reward(cost=bad).status_code, 400, bad)
        self.assertEqual(self.reward(cost=1000).status_code, 200)

    def test_a_sibling_does_not_see_the_other_childs_requests_but_a_parent_sees_all(self):
        code = self.post(self.mum, "/api/invite", role="child").get_json()["code"]
        lily = {"Authorization": "Bearer " + self.c.post("/api/join", json={"code": code, "name": "Lily"}).get_json()["token"]}
        rid = self.reward(cost=5).get_json()["id"]
        self.earn(self.kid, 10)
        self.post(self.kid, f"/api/rewards/{rid}/redeem")
        self.assertEqual(len(self.info(self.mum)["requests"]), 1)
        self.assertEqual(len(self.info(self.kid)["requests"]), 1)
        self.assertEqual(self.info(lily)["requests"], [])
        self.assertEqual(self.info(lily)["rewards"][0]["title"], "Pick the film")

    def test_another_family_cannot_touch_anything(self):
        rid = self.reward().get_json()["id"]
        self.earn(self.kid, 30)
        qid = self.post(self.kid, f"/api/rewards/{rid}/redeem").get_json()["id"]
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertEqual(self.info(zed), {"rewards": [], "requests": [], "balance": {}, "mine": None, "streaks": {}, "bonus": {}, "my_streak": None, "my_bonus": None})
        self.assertEqual(self.post(zed, f"/api/redemptions/{qid}/decide", ok=True).status_code, 404)
        self.assertEqual(self.c.delete(f"/api/rewards/{rid}", headers=zed).status_code, 404)
        self.assertEqual(self.c.get("/api/rewards").status_code, 401)


class Board(Base):
    def test_parents_write_everyone_reads(self):
        nid = self.post(self.mum, "/api/board", title="Wi-Fi", emoji="📶", body="Name: Vales\nPassword: tulips").get_json()["id"]
        for who in (self.mum, self.kid):
            item = self.get(who, "/api/board").get_json()["items"][0]
            self.assertEqual((item["id"], item["title"], item["emoji"], item["body"]), (nid, "Wi-Fi", "📶", "Name: Vales\nPassword: tulips"))
        self.assertEqual(self.post(self.kid, "/api/board", title="Mine").status_code, 403)
        self.assertEqual(self.c.delete(f"/api/board/{nid}", headers=self.kid).status_code, 403)
        self.assertTrue(any(e["type"] == "board" for e in self.events(self.kid)))

    def test_a_note_can_be_changed_and_removed(self):
        nid = self.post(self.mum, "/api/board", title="Doctor", body="Dr Hale 555-0101").get_json()["id"]
        self.assertEqual(self.post(self.mum, "/api/board", id=nid, title="Doctor", body="Dr Hale 555-0199").get_json()["id"], nid)
        items = self.get(self.kid, "/api/board").get_json()["items"]
        self.assertEqual((len(items), items[0]["body"]), (1, "Dr Hale 555-0199"))
        self.assertEqual(self.post(self.mum, "/api/board", id=999, title="Ghost").status_code, 404)
        self.assertEqual(self.c.delete(f"/api/board/{nid}", headers=self.mum).status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/board").get_json()["items"], [])

    def test_a_note_needs_a_title_and_there_is_a_limit(self):
        self.assertEqual(self.post(self.mum, "/api/board", title="  ", body="x").status_code, 400)
        for i in range(20):
            self.assertEqual(self.post(self.mum, "/api/board", title=f"Note {i}").status_code, 200)
        self.assertEqual(self.post(self.mum, "/api/board", title="One too many").status_code, 400)

    def test_another_family_sees_nothing(self):
        nid = self.post(self.mum, "/api/board", title="Wi-Fi", body="secret").get_json()["id"]
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertEqual(self.get(zed, "/api/board").get_json()["items"], [])
        self.assertEqual(self.post(zed, "/api/board", id=nid, title="Mine").status_code, 404)
        self.assertEqual(self.c.delete(f"/api/board/{nid}", headers=zed).status_code, 404)
        self.assertEqual(self.c.get("/api/board").status_code, 401)


class Pickup(Base):
    def test_a_pickup_request_goes_to_the_whole_family_with_where_they_are(self):
        r = self.post(self.kid, "/api/messages", kind="pickup", lat=51.5, lng=-0.12, to=self.mum_id).get_json()["message"]
        self.assertEqual((r["kind"], r["body"], r["to"], r["lat"], r["lng"]), ("pickup", "Can someone pick me up?", 0, 51.5, -0.12))
        seen = [e["payload"] for e in self.events(self.mum) if e["type"] == "message" and e["payload"]["kind"] == "pickup"]
        self.assertEqual((len(seen), seen[0]["name"], seen[0]["quiet"]), (1, "Sam", False))
        self.assertEqual(self.get(self.mum, "/api/messages").get_json()["messages"][-1]["kind"], "pickup")

    def test_a_pickup_gets_through_quiet_hours(self):
        minute = int(time.time() // 60) % 1440                          # a quiet window around "now" (tz_offset 0 means UTC minutes)
        db.run(db.update(db.members).where(db.members.c.id == self.mum_id).values(quiet_from=(minute - 30) % 1440, quiet_to=(minute + 30) % 1440, tz_offset=0))
        self.post(self.kid, "/api/messages", kind="text", body="hello")
        self.post(self.kid, "/api/messages", kind="pickup")
        flags = {e["payload"]["kind"]: e["payload"]["quiet"] for e in self.events(self.mum) if e["type"] == "message" and e["payload"]["kind"] in ("text", "pickup")}
        self.assertEqual(flags, {"text": True, "pickup": False})

    def test_a_reply_is_just_a_message(self):
        self.post(self.kid, "/api/messages", kind="pickup")
        self.assertEqual(self.post(self.mum, "/api/messages", body="On my way, about 10 minutes").status_code, 200)
        self.assertEqual([m["kind"] for m in self.get(self.kid, "/api/messages").get_json()["messages"]][-2:], ["pickup", "text"])


class RecurringJobs(Base):
    def make(self, repeat, title="Bins"):
        return self.post(self.mum, "/api/chores", title=title, points=5, repeat=repeat).get_json()["id"]

    def finish(self, cid):
        self.assertEqual(self.post(self.kid, f"/api/chores/{cid}/done").status_code, 200)
        self.assertEqual(self.post(self.mum, f"/api/chores/{cid}/approve", ok=True).status_code, 200)

    def jobs(self, who):
        return self.get(who, "/api/chores").get_json()["chores"]

    def test_a_repeating_job_comes_back_later_never_straight_away(self):
        cid = self.make("daily")
        self.finish(cid)
        self.assertEqual([c["status"] for c in self.jobs(self.kid) if c["title"] == "Bins"], ["approved"], "nothing new to farm today")
        waiting = [c for c in self.jobs(self.mum) if c["status"] == "open"]
        self.assertEqual((len(waiting), waiting[0]["repeats"]), (1, "daily"))
        self.assertGreater(waiting[0]["opens_at"], time.time(), "a parent can see it is waiting")
        self.assertEqual(self.post(self.kid, f"/api/chores/{waiting[0]['id']}/done").status_code, 404, "and it cannot be ticked off before it opens")
        db.run(db.update(db.chores).where(db.chores.c.id == waiting[0]["id"]).values(opens_at=time.time() - 5))
        again = [c for c in self.jobs(self.kid) if c["status"] == "open"]
        self.assertEqual(len(again), 1, "once it opens the child sees it")
        self.finish(again[0]["id"])
        self.assertEqual(len([c for c in self.jobs(self.mum) if c["status"] == "open"]), 1, "and it keeps coming back")

    def test_weekly_is_a_week_later_than_daily(self):
        d, w = self.make("daily", "Daily"), self.make("weekly", "Weekly")
        self.finish(d)
        self.finish(w)
        opens = {c["title"]: c["opens_at"] for c in self.jobs(self.mum) if c["status"] == "open"}
        self.assertAlmostEqual(opens["Weekly"] - opens["Daily"], 6 * 86400, delta=86400)

    def test_a_one_off_job_does_not_come_back_and_neither_does_one_sent_back(self):
        one = self.make("")
        self.finish(one)
        self.assertEqual(len([c for c in db.many(db.select(db.chores)) if c["created_by"] == self.mum_id]), 1, "a one-off job leaves no copy")
        again = self.make("daily", "Again")
        self.post(self.kid, f"/api/chores/{again}/done")
        self.post(self.mum, f"/api/chores/{again}/approve", ok=False)
        self.assertEqual(len([c for c in db.many(db.select(db.chores)) if c["title"] == "Again"]), 1, "'do it again' is the same job, not a new copy")

    def test_only_daily_and_weekly_are_accepted(self):
        cid = self.make("monthly")
        self.assertEqual(db.one(db.select(db.chores).where(db.chores.c.id == cid))["repeats"], "")


class Streaks(Base):
    def approved(self, days_ago):
        cid = self.post(self.mum, "/api/chores", title=f"Job {days_ago}", points=1).get_json()["id"]
        self.post(self.kid, f"/api/chores/{cid}/done")
        self.post(self.mum, f"/api/chores/{cid}/approve", ok=True)
        db.run(db.update(db.chores).where(db.chores.c.id == cid).values(approved_at=time.time() - days_ago * 86400))

    def streak(self):
        return self.get(self.kid, "/api/chores").get_json()["streaks"][str(self.kid_id)]

    def test_days_in_a_row_with_an_approved_job(self):
        self.assertEqual(self.streak(), 0)
        self.approved(0)
        self.assertEqual(self.streak(), 1)
        self.approved(1)
        self.approved(2)
        self.assertEqual(self.streak(), 3)

    def test_a_missed_day_breaks_it_but_yesterday_still_counts_until_today_ends(self):
        self.approved(0)
        self.approved(2)
        self.assertEqual(self.streak(), 1, "a gap yesterday ends the run")
        db.run(db.update(db.chores).values(approved_at=time.time() - 86400))
        self.assertEqual(self.streak(), 1, "yesterday only: still alive today")
        db.run(db.update(db.chores).values(approved_at=time.time() - 2 * 86400))
        self.assertEqual(self.streak(), 0, "two days ago only: over")

    def test_rewards_also_report_streaks_and_only_children_have_them(self):
        self.approved(0)
        d = self.get(self.mum, "/api/rewards").get_json()
        self.assertEqual(d["streaks"], {str(self.kid_id): 1})


class Bonus(Base):
    DAY = time.strftime("%Y-%m-%d")

    def earn(self, points):
        cid = self.post(self.mum, "/api/chores", title="Tidy", points=points).get_json()["id"]
        self.post(self.kid, f"/api/chores/{cid}/done")
        self.post(self.mum, f"/api/chores/{cid}/approve", ok=True)

    def play(self, seconds):
        self.post(self.kid, "/api/screentime", day=self.DAY, apps=[{"pkg": "com.game", "label": "Game", "cat": "play", "sec": seconds}])

    def limit_nudges(self):
        return [e["payload"]["text"] for e in self.events(self.kid) if e["type"] == "nudge" and "daily limit" in e["payload"]["text"]]

    def reward(self, minutes=30, cost=10, title="Extra game time"):
        return self.post(self.mum, "/api/rewards", title=title, cost=cost, minutes=minutes)

    def spend(self, rid, approve=True):
        qid = self.post(self.kid, f"/api/rewards/{rid}/redeem").get_json()["id"]
        return self.post(self.mum, f"/api/redemptions/{qid}/decide", ok=approve)

    def test_extra_game_time_moves_the_daily_limit(self):
        self.post(self.mum, "/api/rules", limits={str(self.kid_id): 60})
        rid = self.reward().get_json()["id"]
        self.earn(20)
        self.play(3000)
        self.assertEqual(self.limit_nudges(), [], "50 of 60 minutes: not there yet")
        self.assertEqual(self.spend(rid).status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/rewards").get_json()["bonus"], {str(self.kid_id): 30})
        mine = self.get(self.kid, "/api/rewards").get_json()
        self.assertEqual((mine["my_bonus"], mine["my_streak"]), (30, 1), "a child is told their own extra minutes and streak (for the Windows tray)")
        self.assertEqual(self.get(self.mum, "/api/rewards").get_json()["my_bonus"], None)
        self.play(3700)
        self.assertEqual(self.limit_nudges(), [], "past the old 60-minute limit but inside the new 90")
        self.play(5500)
        nudged = self.limit_nudges()
        self.assertEqual(len(nudged), 1)
        self.assertIn("90 minutes", nudged[0])

    def test_without_the_reward_the_original_limit_stands(self):
        self.post(self.mum, "/api/rules", limits={str(self.kid_id): 60})
        self.play(3700)
        self.assertIn("60 minutes", self.limit_nudges()[0])

    def test_a_no_gives_no_time_and_no_limit_means_nothing_to_extend(self):
        rid = self.reward().get_json()["id"]
        self.earn(40)
        self.assertEqual(self.spend(rid, approve=False).get_json()["status"], "declined")
        self.assertEqual(self.get(self.kid, "/api/rewards").get_json()["bonus"], {})
        self.assertEqual(self.spend(rid).status_code, 200)
        self.play(99999)
        self.assertEqual(self.limit_nudges(), [], "no limit is set, so there is no limit to reach")

    def test_the_family_chat_and_the_child_are_told_how_many_minutes(self):
        rid = self.reward().get_json()["id"]
        self.earn(20)
        self.spend(rid)
        told = [e["payload"] for e in self.events(self.kid) if e["type"] == "reward" and e["payload"]["status"] == "approved"]
        self.assertEqual(told[0]["minutes"], 30)
        notes = [m["body"] for m in self.get(self.mum, "/api/messages").get_json()["messages"] if "said yes" in m["body"]]
        self.assertIn("+30 minutes of screen time today", notes[0])

    def test_minutes_must_be_a_sensible_number(self):
        for bad in (-1, 241, "lots"):
            self.assertEqual(self.reward(minutes=bad).status_code, 400, bad)
        self.assertEqual(self.reward(minutes=240).status_code, 200)
        self.assertEqual(self.reward(minutes=0, title="Plain").status_code, 200)
        self.assertEqual(sorted(r["minutes"] for r in self.get(self.kid, "/api/rewards").get_json()["rewards"]), [0, 240])


class Birthdays(Base):
    def set(self, who, month, day):
        return self.post(who, "/api/birthday", month=month, day=day)

    def greetings(self, who):
        return [e["payload"] for e in self.events(who) if e["type"] == "birthday"]

    def fire(self, ts=None):
        server._last_bday[0] = 0
        if ts is None:
            server.remind_birthdays()
        else:
            with mock.patch.object(server.time, "time", return_value=ts):
                server.remind_birthdays()

    def test_set_show_and_clear(self):
        self.assertEqual(self.set(self.kid, 3, 14).get_json()["birthday"], "03-14")
        seen = {m["name"]: m["birthday"] for m in self.get(self.mum, "/api/family").get_json()["members"]}
        self.assertEqual((seen["Sam"], seen["Mum"]), ("03-14", None))
        self.assertIsNone(self.set(self.kid, 0, 0).get_json()["birthday"])

    def test_only_real_dates(self):
        for month, day in ((2, 30), (13, 1), (0, 5), (4, 31), (1, 0), ("x", 1)):
            self.assertEqual(self.set(self.kid, month, day).status_code, 400, (month, day))
        self.assertEqual(self.set(self.kid, 2, 29).status_code, 200)

    def test_the_family_hears_once_on_the_day(self):
        now = time.gmtime()
        self.set(self.kid, now.tm_mon, now.tm_mday)
        self.fire()
        told, own = self.greetings(self.mum), self.greetings(self.kid)
        self.assertEqual((len(told), told[0]["name"], told[0]["self"], own[0]["self"]), (1, "Sam", False, True))
        self.assertTrue(any("birthday today" in m["body"] for m in self.get(self.mum, "/api/messages").get_json()["messages"]))
        self.fire()
        self.assertEqual(len(self.greetings(self.mum)), 1, "once a year, not every five minutes")
        db.run(db.update(db.members).where(db.members.c.id == self.kid_id).values(bday_year=now.tm_year - 1))
        self.fire()
        self.assertEqual(len(self.greetings(self.mum)), 2, "and again next year")

    def test_nothing_on_other_days(self):
        t = time.gmtime(time.time() + 3 * 86400)
        self.set(self.kid, t.tm_mon, t.tm_mday)
        self.fire()
        self.assertEqual(self.greetings(self.mum), [])

    def test_a_leap_day_birthday_is_kept_on_the_28th_in_normal_years(self):
        self.set(self.kid, 2, 29)
        self.fire(calendar.timegm((2027, 2, 28, 12, 0, 0)))
        self.assertEqual(len(self.greetings(self.mum)), 1, "2027 has no 29 February")
        db.run(db.update(db.members).where(db.members.c.id == self.kid_id).values(bday_year=0))
        self.fire(calendar.timegm((2028, 2, 28, 12, 0, 0)))
        self.assertEqual(len(self.greetings(self.mum)), 1, "in a leap year the 28th is just a day")
        self.fire(calendar.timegm((2028, 2, 29, 12, 0, 0)))
        self.assertEqual(len(self.greetings(self.mum)), 2)

    def test_their_own_clock_decides_the_day(self):
        now = time.gmtime()
        self.set(self.kid, now.tm_mon, now.tm_mday)
        ts = calendar.timegm((now.tm_year, now.tm_mon, now.tm_mday, 1, 0, 0))             # 01:00 UTC on the day...
        db.run(db.update(db.members).where(db.members.c.id == self.kid_id).values(tz_offset=300))     # ...is still the evening before in UTC-5
        self.fire(ts)
        server._last_bday[0] = time.time() + 600                                        # reading events also runs the check, on the real clock: keep it out of this test
        self.assertEqual(self.greetings(self.mum), [], "not their birthday yet where they are")


class AppVersions(Base):
    def seen_by_mum(self, name="Sam"):
        return {m["name"]: m["app_version"] for m in self.get(self.mum, "/api/family").get_json()["members"]}[name]

    def test_the_android_app_reports_its_version_and_the_latest_is_kept(self):
        self.assertEqual(self.seen_by_mum(), 0, "unknown until the app says")
        self.c.get("/api/me", headers={**self.kid, "X-Homebase-Version": "4"})
        self.assertEqual(self.seen_by_mum(), 4)
        self.c.get("/api/me", headers={**self.kid, "X-Homebase-Version": "5"})
        self.assertEqual(self.seen_by_mum(), 5)
        for junk in ("abc", "-3", "0", "99999999", ""):
            self.c.get("/api/me", headers={**self.kid, "X-Homebase-Version": junk})
        self.assertEqual(self.seen_by_mum(), 5, "nonsense is ignored")


class UpdateNudge(Base):
    def nudge(self, who, member):
        return self.c.post(f"/api/members/{member}/update-nudge", headers=who)

    def test_a_parent_asks_once_with_the_link_and_only_that_person_hears(self):
        dad_code = self.post(self.mum, "/api/invite", role="parent").get_json()["code"]
        dad = {"Authorization": "Bearer " + self.c.post("/api/join", json={"code": dad_code, "name": "Dad"}).get_json()["token"]}
        self.assertEqual(self.nudge(self.mum, self.kid_id).status_code, 200)
        told = [e["payload"] for e in self.events(self.kid) if e["type"] == "message" and "/download" in e["payload"]["body"]]
        self.assertEqual((len(told), told[0]["name"], told[0]["kind"], told[0]["to"]), (1, "Mum", "text", self.kid_id))
        self.assertIn("Please update Homebase", told[0]["body"])
        self.assertFalse(any("/download" in e["payload"].get("body", "") for e in self.events(dad) if e["type"] == "message"), "nobody else is buzzed")
        thread = self.get(self.kid, f"/api/messages?with={self.mum_id}").get_json()["messages"]
        self.assertTrue(any("update Homebase" in m["body"] for m in thread), "it is in their chat too")

    def test_it_is_capped_so_it_cannot_become_spam(self):
        self.assertEqual(self.nudge(self.mum, self.kid_id).status_code, 200)
        for _ in range(5):
            self.assertEqual(self.nudge(self.mum, self.kid_id).status_code, 429)
        self.assertEqual(len([e for e in self.events(self.kid) if e["type"] == "message" and "/download" in e["payload"]["body"]]), 1)

    def test_who_may_ask_whom(self):
        self.assertEqual(self.nudge(self.kid, self.mum_id).status_code, 403, "a child cannot send these")
        self.assertEqual(self.nudge(self.mum, self.mum_id).status_code, 404, "not yourself")
        self.assertEqual(self.nudge(self.mum, 99999).status_code, 404)
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertEqual(self.nudge(zed, self.kid_id).status_code, 404, "another family cannot")
        self.assertEqual(self.c.post(f"/api/members/{self.kid_id}/update-nudge").status_code, 401)


class CalendarRange(Base):
    def plan_at(self, title, delta):
        fid = db.one(db.select(db.members).where(db.members.c.id == self.mum_id))["family_id"]
        now = time.time()
        db.run(db.insert(db.plans).values(family_id=fid, title=title, emoji="\U0001F4C5", start=now + delta, note="", created_by=self.mum_id, created=now, rsvp="{}", reminded=True))

    def titles(self, query=""):
        return [p["title"] for p in self.get(self.kid, "/api/plans" + query).get_json()["plans"]]

    def test_a_stretch_of_days_includes_the_past_and_nothing_outside_it(self):
        self.plan_at("Last week", -7 * 86400)
        self.plan_at("Tomorrow", 86400)
        self.plan_at("Next month", 40 * 86400)
        now = time.time()
        self.assertEqual(self.titles(f"?from={now - 10 * 86400}&to={now + 10 * 86400}"), ["Last week", "Tomorrow"])
        self.assertEqual(self.titles(f"?from={now + 30 * 86400}&to={now + 50 * 86400}"), ["Next month"])
        self.assertEqual(self.titles(), ["Tomorrow", "Next month"], "the normal list is still just what is coming up")

    def test_the_range_must_be_sensible_and_stays_in_the_family(self):
        now = time.time()
        for query in ("?from=x&to=y", f"?from={now}&to={now - 5}", f"?from={now}&to={now + 80 * 86400}"):
            self.assertEqual(self.get(self.kid, "/api/plans" + query).status_code, 400, query)
        self.plan_at("Ours", 86400)
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertEqual(self.get(zed, f"/api/plans?from={now - 86400}&to={now + 5 * 86400}").get_json()["plans"], [])


class ClearList(Base):
    def items(self, who):
        return [i["text"] for i in self.get(who, "/api/list").get_json()["items"]]

    def test_only_the_ticked_items_go_and_only_from_this_family(self):
        a, b, _ = (self.post(self.mum, "/api/list", text=t).get_json()["id"] for t in ("Milk", "Eggs", "Bread"))
        self.post(self.kid, f"/api/list/{a}", done=True)
        self.post(self.kid, f"/api/list/{b}", done=True)
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        z = self.post(zed, "/api/list", text="Zed's tea").get_json()["id"]
        self.post(zed, f"/api/list/{z}", done=True)
        self.assertEqual(self.post(self.kid, "/api/list/clear").get_json()["cleared"], 2)
        self.assertEqual(self.items(self.mum), ["Bread"])
        self.assertEqual(self.items(zed), ["Zed's tea"], "another family's list is not touched")
        self.assertEqual(self.post(self.kid, "/api/list/clear").get_json()["cleared"], 0)
        self.assertTrue(any(e["type"] == "list" and e["payload"].get("cleared") == 2 for e in self.events(self.mum)))
        self.assertEqual(self.c.post("/api/list/clear").status_code, 401)


class CalendarFile(Base):
    def test_plans_and_birthdays_come_out_as_a_calendar_file(self):
        self.post(self.mum, "/api/plans", title="Dinner, at Gran's; bring cake", emoji="\U0001F37D\ufe0f", start=time.time() + 86400, note="Bring a cake, candles; and a card")
        self.post(self.kid, "/api/birthday", month=3, day=14)
        self.post(self.mum, "/api/birthday", month=2, day=29)
        r = self.c.get("/api/calendar.ics", headers=self.kid)
        self.assertEqual((r.status_code, r.mimetype), (200, "text/calendar"))
        self.assertIn("attachment", r.headers["Content-Disposition"])
        text = r.get_data(as_text=True)
        lines = text.split("\r\n")
        self.assertEqual((lines[0], lines[-2], lines[-1]), ("BEGIN:VCALENDAR", "END:VCALENDAR", ""))
        self.assertEqual(text.count("BEGIN:VEVENT"), 3)
        self.assertEqual(text.count("END:VEVENT"), 3)
        flat = text.replace("\r\n ", "")                                  # undo the line folding
        self.assertIn("SUMMARY:\U0001F37D\ufe0f Dinner\\, at Gran's\\; bring cake", flat, "commas and semicolons are escaped")
        self.assertIn("DESCRIPTION:Bring a cake\\, candles\\; and a card", flat)
        self.assertIn(f"DTSTART;VALUE=DATE:{time.gmtime().tm_year}0314", flat)
        self.assertIn("DTSTART;VALUE=DATE:20240229", flat, "29 February sits in a leap year")
        self.assertIn("RRULE:FREQ=YEARLY", flat)
        self.assertTrue(all(len(line) <= 75 for line in lines), "long lines are folded")

    def test_each_family_gets_only_its_own_calendar(self):
        self.post(self.mum, "/api/plans", title="Private plan", start=time.time() + 86400)
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        other = self.c.get("/api/calendar.ics", headers=zed).get_data(as_text=True)
        self.assertNotIn("Private plan", other)
        self.assertEqual(other.count("BEGIN:VEVENT"), 0)
        self.assertEqual(self.c.get("/api/calendar.ics").status_code, 401)


class WebPush(Base):
    SUB = {"endpoint": "https://push.example.com/send/abc123", "keys": {"p256dh": "BPyub-" + "A" * 80, "auth": "authsecret123456"}}

    def setUp(self):
        super().setUp()
        server.PUSH_SYNC = True                                             # send on the spot so the test can look at what went out
        self.addCleanup(setattr, server, "PUSH_SYNC", False)
        self.sent = []
        patcher = mock.patch("pywebpush.webpush", side_effect=lambda sub, data, **kw: self.sent.append((sub, json.loads(data), kw)))
        patcher.start()
        self.addCleanup(patcher.stop)

    def subscribe(self, who, **over):
        return self.post(who, "/api/push/subscribe", subscription={**self.SUB, **over})

    def rows(self, endpoint=None):
        q = db.select(db.push_subs)
        return db.many(q.where(db.push_subs.c.endpoint == endpoint) if endpoint else q)

    def test_the_servers_key_is_made_once_and_is_a_real_public_key(self):
        a = self.get(self.kid, "/api/push/key").get_json()["key"]
        self.assertEqual(self.get(self.mum, "/api/push/key").get_json()["key"], a, "the same key for everyone, every time")
        raw = base64.urlsafe_b64decode(a + "=" * (-len(a) % 4))
        self.assertEqual((len(raw), raw[0]), (65, 4), "an uncompressed P-256 point, which is what browsers expect")
        self.assertEqual(self.c.get("/api/push/key").status_code, 401)

    def test_what_counts_as_a_usable_address(self):
        self.assertEqual(self.subscribe(self.kid, endpoint="http://insecure.example.com/x").status_code, 400)
        self.assertEqual(self.subscribe(self.kid, endpoint="").status_code, 400)
        self.assertEqual(self.subscribe(self.kid, keys={"p256dh": "x"}).status_code, 400)
        self.assertEqual(self.post(self.kid, "/api/push/subscribe").status_code, 400)
        self.assertEqual(self.subscribe(self.kid).status_code, 200)
        self.assertEqual(len(self.rows(self.SUB["endpoint"])), 1)
        self.assertEqual(self.c.post("/api/push/subscribe", json={"subscription": self.SUB}).status_code, 401)

    def test_a_device_belongs_to_one_person_and_only_they_can_remove_it(self):
        self.subscribe(self.kid)
        self.subscribe(self.mum)
        self.assertEqual([r["member_id"] for r in self.rows(self.SUB["endpoint"])], [self.mum_id], "it moved to whoever signed in last")
        self.post(self.kid, "/api/push/unsubscribe", endpoint=self.SUB["endpoint"])
        self.assertEqual(len(self.rows(self.SUB["endpoint"])), 1, "someone else cannot remove it")
        self.post(self.mum, "/api/push/unsubscribe", endpoint=self.SUB["endpoint"])
        self.assertEqual(self.rows(self.SUB["endpoint"]), [])

    def test_ten_devices_at_most_per_person(self):
        for i in range(12):
            self.subscribe(self.kid, endpoint=f"https://push.example.com/send/d{i}")
        mine = [r for r in self.rows() if r["member_id"] == self.kid_id]
        self.assertEqual(len(mine), 10)
        self.assertEqual({r["endpoint"].rsplit("/", 1)[1] for r in mine}, {f"d{i}" for i in range(2, 12)}, "the two oldest were dropped")

    def test_a_message_reaches_a_closed_app_signed_with_the_servers_key(self):
        self.subscribe(self.kid)
        self.post(self.mum, "/api/messages", body="Tea is ready", to=self.kid_id)
        self.assertEqual(len(self.sent), 1)
        sub, note, kw = self.sent[0]
        self.assertEqual((sub["endpoint"], note["title"], note["body"]), (self.SUB["endpoint"], "Mum", "Tea is ready"))
        self.assertTrue(kw["vapid_claims"]["sub"].startswith("https://"))
        self.assertEqual(kw["vapid_private_key"].__class__.__name__, "Vapid02")

    def test_quiet_hours_hold_back_chat_but_never_an_sos_or_a_pickup(self):
        minute = int(time.time() // 60) % 1440
        db.run(db.update(db.members).where(db.members.c.id == self.kid_id).values(quiet_from=(minute - 30) % 1440, quiet_to=(minute + 30) % 1440, tz_offset=0))
        self.subscribe(self.kid)
        self.post(self.mum, "/api/messages", body="Are you up?", to=self.kid_id)
        self.assertEqual(self.sent, [], "a normal message waits")
        self.post(self.mum, "/api/messages", kind="sos", body="Call me now")
        self.assertEqual(len(self.sent), 1)
        self.assertIn("needs help", self.sent[0][1]["title"])
        self.assertTrue(self.sent[0][1]["urgent"])

    def test_only_things_worth_a_lock_screen_alert_are_sent(self):
        self.subscribe(self.mum)
        self.where(self.kid, 51.5, -0.12)
        self.post(self.kid, "/api/status", emoji="\U0001F4DA", text="Studying", hours=1)
        self.assertEqual(self.sent, [], "locations and status changes are bookkeeping, not alerts")
        self.post(self.kid, "/api/messages", kind="pickup")
        self.post(self.kid, "/api/plans", title="Dinner", start=time.time() + 7200)
        self.assertEqual([n["title"].split(" ")[-1] for _, n, _ in self.sent][:1], ["pickup"])
        self.assertEqual(len(self.sent), 2)
        self.assertIn("planned something", self.sent[1][1]["title"])

    def test_nothing_is_sent_for_people_who_never_asked(self):
        self.post(self.mum, "/api/messages", body="Hello", to=self.kid_id)
        self.assertEqual(self.sent, [])

    def test_an_address_the_browser_says_is_gone_is_forgotten_but_other_errors_are_not(self):
        self.subscribe(self.kid)

        class Gone(Exception):
            response = types.SimpleNamespace(status_code=410)

        class Down(Exception):
            response = types.SimpleNamespace(status_code=500)

        with mock.patch("pywebpush.webpush", side_effect=Down("push service is down")):
            self.post(self.mum, "/api/messages", body="one", to=self.kid_id)
        self.assertEqual(len(self.rows(self.SUB["endpoint"])), 1, "a hiccup does not lose the address")
        with mock.patch("pywebpush.webpush", side_effect=Gone("gone")):
            self.post(self.mum, "/api/messages", body="two", to=self.kid_id)
        self.assertEqual(self.rows(self.SUB["endpoint"]), [], "410 means gone for good")

    def test_a_push_failure_never_breaks_the_request_that_caused_it(self):
        self.subscribe(self.kid)
        with mock.patch("pywebpush.webpush", side_effect=RuntimeError("network unreachable")):
            r = self.post(self.mum, "/api/messages", body="still delivered", to=self.kid_id)
        self.assertEqual(r.status_code, 200)
        self.assertTrue(any(e["type"] == "message" for e in self.events(self.kid)), "the in-app event is stored either way")


class IPhonePages(Base):
    def test_the_iphone_page_explains_the_steps_and_the_limits(self):
        r = self.c.get("/iphone")
        self.assertEqual(r.status_code, 200)
        text = r.get_data(as_text=True)
        for needle in ("Add to Home Screen", "Safari", "iOS 16.4", "/qr.svg", "does not allow"):
            self.assertIn(needle, text)
        self.assertNotIn("5b93ff", text.lower(), "no blue")

    def test_the_qr_code_opens_this_servers_app(self):
        r = self.c.get("/qr.svg", headers={"X-Forwarded-Proto": "https", "Host": "family.example.org"})
        self.assertEqual((r.status_code, r.mimetype), (200, "image/svg+xml"))
        self.assertIn("<svg", r.get_data(as_text=True))
        import segno
        expected = segno.make("https://family.example.org/app", error="m")
        self.assertEqual(r.get_data(as_text=True), self._svg(expected), "it encodes https://<this host>/app")

    @staticmethod
    def _svg(code):
        import io as _io
        buf = _io.BytesIO()
        code.save(buf, kind="svg", scale=8, border=2, dark="#17171a", light="#ffffff")
        return buf.getvalue().decode("utf-8")

    def test_the_app_page_is_ready_to_be_a_home_screen_app(self):
        page = self.c.get("/app").get_data(as_text=True)
        for needle in ("apple-mobile-web-app-capable", "apple-touch-icon", "apple-mobile-web-app-title", "manifest.webmanifest"):
            self.assertIn(needle, page)
        manifest = self.c.get("/manifest.webmanifest").get_json()
        self.assertEqual((manifest["display"], manifest["start_url"]), ("standalone", "/app"))
        self.assertEqual(self.c.get("/static/web/apple-touch-icon.png").status_code, 200)

    def test_the_service_worker_can_show_a_push(self):
        sw = self.c.get("/sw.js").get_data(as_text=True)
        self.assertIn('addEventListener("push"', sw)
        self.assertIn("showNotification", sw)


class Notices(Base):
    def notice_for(self, who):
        return self.get(who, "/api/family").get_json()["family"]["notice"]

    def test_a_parent_announces_and_everyone_sees_it_and_is_told(self):
        self.assertIsNone(self.notice_for(self.kid))
        self.assertEqual(self.post(self.mum, "/api/notice", text="Dinner at 6, everyone home", hours=3).status_code, 200)
        for who in (self.mum, self.kid):
            n = self.notice_for(who)
            self.assertEqual((n["text"], n["by"]), ("Dinner at 6, everyone home", "Mum"))
        self.assertGreater(self.notice_for(self.kid)["until"], time.time() + 2.9 * 3600)
        told = [e["payload"] for e in self.events(self.kid) if e["type"] == "notice"]
        self.assertEqual((len(told), told[0]["name"], told[0]["text"]), (1, "Mum", "Dinner at 6, everyone home"))
        self.assertFalse(any(e["type"] == "notice" for e in self.events(self.mum)), "the person who wrote it is not buzzed")
        self.assertTrue(any("Dinner at 6" in m["body"] for m in self.get(self.kid, "/api/messages").get_json()["messages"]), "it stays in the chat")

    def test_it_ends_by_itself_and_a_parent_can_clear_it(self):
        self.post(self.mum, "/api/notice", text="Shoes by the door")
        fid = db.one(db.select(db.members).where(db.members.c.id == self.mum_id))["family_id"]
        db.run(db.update(db.families).where(db.families.c.id == fid).values(notice_until=time.time() - 5))
        self.assertIsNone(self.notice_for(self.kid), "an old announcement is gone")
        self.post(self.mum, "/api/notice", text="New one")
        self.assertEqual(self.c.delete("/api/notice", headers=self.mum).status_code, 200)
        self.assertIsNone(self.notice_for(self.kid))

    def test_only_parents_and_only_real_text(self):
        self.assertEqual(self.post(self.kid, "/api/notice", text="Free pizza").status_code, 403)
        self.assertEqual(self.c.delete("/api/notice", headers=self.kid).status_code, 403)
        self.assertEqual(self.post(self.mum, "/api/notice", text="   ").status_code, 400)
        self.assertEqual(self.post(self.mum, "/api/notice", text="Hi", hours="lots").status_code, 400)
        self.assertEqual(self.c.post("/api/notice", json={"text": "x"}).status_code, 401)

    def test_the_length_of_time_is_kept_sensible_and_it_cannot_be_spammed(self):
        self.post(self.mum, "/api/notice", text="Long one", hours=5000)
        self.assertLessEqual(self.notice_for(self.kid)["until"], time.time() + 72 * 3600 + 5)
        self.post(self.mum, "/api/notice", text="Short one", hours=-4)
        self.assertGreaterEqual(self.notice_for(self.kid)["until"], time.time() + 0.9 * 3600)
        codes = [self.post(self.mum, "/api/notice", text=f"Note {i}").status_code for i in range(12)]
        self.assertEqual(codes[-1], 429)

    def test_another_family_never_sees_it(self):
        self.post(self.mum, "/api/notice", text="Private")
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertIsNone(self.notice_for(zed))


def in_days(n):
    return (datetime.date.today() + datetime.timedelta(days=n)).isoformat()


class Meals(Base):
    def add(self, who=None, **kw):
        return self.post(who or self.kid, "/api/meals", **{"day": in_days(3), "slot": "dinner", "title": "Chicken burger", **kw})

    def listing(self, who, query=""):
        return self.get(who, "/api/meals" + query).get_json()["meals"]

    def test_a_meal_with_its_recipe_is_shared_with_the_family(self):
        r = self.add(ingredients=["2 chicken breasts", "4 buns", " "], steps=["Flatten the chicken.", "Grill it."], servings=4, photo="https://img.example.com/burger.jpg",
                     source="https://platter.example.com/#/r/53085")
        self.assertEqual(r.status_code, 200)
        m = self.listing(self.mum)[0]
        self.assertEqual((m["title"], m["day"], m["slot"], m["servings"], m["via"]), ("Chicken burger", in_days(3), "dinner", 4, "homebase"))
        self.assertEqual((m["ingredients"], m["steps"]), (["2 chicken breasts", "4 buns"], ["Flatten the chicken.", "Grill it."]))
        self.assertEqual((m["photo"], m["source"]), ("https://img.example.com/burger.jpg", "https://platter.example.com/#/r/53085"))
        self.assertTrue(any(e["type"] == "meals" for e in self.events(self.mum)))

    def test_meals_come_back_in_day_order_inside_the_range_asked_for(self):
        self.add(day=in_days(20), title="Later")
        self.add(day=in_days(2), title="Soon")
        self.add(day=in_days(2), slot="lunch", title="Soup")
        self.assertEqual([m["title"] for m in self.listing(self.mum)], ["Soon", "Soup"], "the default window is the next two weeks")
        self.assertEqual([m["title"] for m in self.listing(self.mum, f"?from={in_days(10)}&to={in_days(30)}")], ["Later"])
        self.assertEqual(self.get(self.mum, f"/api/meals?from={in_days(0)}&to={in_days(120)}").status_code, 400, "more than ten weeks at once")
        self.assertEqual(self.get(self.mum, f"/api/meals?from={in_days(9)}&to={in_days(1)}").status_code, 400)

    def test_what_makes_a_meal_valid(self):
        for bad in ({"day": "tomorrow"}, {"day": in_days(-400)}, {"day": in_days(2000)}, {"title": "  "}, {"day": None}):
            self.assertEqual(self.add(**bad).status_code, 400, bad)
        self.add(slot="midnight feast", servings=500, photo="http://insecure.example.com/x.jpg", ingredients="not a list")
        m = self.listing(self.mum)[-1]
        self.assertEqual((m["slot"], m["servings"], m["photo"], m["ingredients"]), ("dinner", 99, "", []), "odd values are tidied, not trusted")

    def test_who_can_remove_a_meal(self):
        mine = self.add(self.kid).get_json()["id"]
        theirs = self.add(self.mum, title="Mum's pie").get_json()["id"]
        self.assertEqual(self.c.delete(f"/api/meals/{theirs}", headers=self.kid).status_code, 404)
        self.assertEqual(self.c.delete(f"/api/meals/{mine}", headers=self.kid).status_code, 200)
        self.assertEqual(self.c.delete(f"/api/meals/{theirs}", headers=self.mum).status_code, 200)
        self.assertEqual(self.listing(self.mum), [])

    def test_another_family_sees_and_changes_nothing(self):
        mid = self.add().get_json()["id"]
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertEqual(self.listing(zed), [])
        self.assertEqual(self.c.delete(f"/api/meals/{mid}", headers=zed).status_code, 404)
        self.assertEqual(self.c.get("/api/meals").status_code, 401)

    def test_the_meal_plan_is_in_the_calendar_file(self):
        self.add(title="Chicken burger", slot="dinner")
        text = self.c.get("/api/calendar.ics", headers=self.mum).get_data(as_text=True).replace("\r\n ", "")
        self.assertIn("Dinner: Chicken burger", text)
        self.assertIn("DTSTART;VALUE=DATE:" + in_days(3).replace("-", ""), text)


class LinkedApps(Base):
    ORIGIN = "http://127.0.0.1:5075"

    def link(self, name="Platter"):
        return self.post(self.mum, "/api/integrations", name=name)

    def ext(self, key, method, path, origin=None, **body):
        headers = {"Authorization": f"Bearer {key}"}
        if origin:
            headers["Origin"] = origin
        return getattr(self.c, method)(path, headers=headers, json=body or None)

    def test_a_parent_links_an_app_and_the_key_is_shown_once(self):
        made = self.link().get_json()
        self.assertTrue(made["key"].startswith("hbk_") and len(made["key"]) > 30)
        listed = self.get(self.mum, "/api/integrations").get_json()["integrations"]
        self.assertEqual([(i["name"], "key" in i) for i in listed], [("Platter", False)], "the list never shows a key")
        stored = db.one(db.select(db.integrations).where(db.integrations.c.id == made["id"]))
        self.assertNotIn(made["key"], json.dumps(stored), "only a fingerprint is kept")
        self.assertEqual(self.post(self.kid, "/api/integrations", name="Sneaky").status_code, 403)
        self.assertEqual(self.get(self.kid, "/api/integrations").status_code, 403)
        self.assertTrue(any("linked Platter" in m["body"] for m in self.get(self.kid, "/api/messages").get_json()["messages"]), "the family is told")

    def test_at_most_five(self):
        for i in range(5):
            self.assertEqual(self.link(f"App {i}").status_code, 200)
        self.assertEqual(self.link("One too many").status_code, 400)

    def test_the_app_can_check_in_put_meals_and_take_them_off(self):
        key = self.link().get_json()["key"]
        self.assertEqual(self.ext(key, "get", "/api/ext/ping").get_json(), {"family": "The Vales", "app": "Platter", "scopes": ["meals"]})
        body = {"ext_id": "platter:abc", "day": in_days(5), "slot": "dinner", "title": "Chicken burger", "ingredients": ["2 chicken breasts"], "steps": ["Grill it."], "servings": 4}
        self.assertEqual(self.ext(key, "post", "/api/ext/meals", **body).status_code, 200)
        self.assertEqual(self.ext(key, "post", "/api/ext/meals", **{**body, "day": in_days(6), "title": "Chicken burger (bigger)"}).status_code, 200)
        meals = self.get(self.kid, "/api/meals?from=" + in_days(0) + "&to=" + in_days(14)).get_json()["meals"]
        self.assertEqual([(m["title"], m["day"], m["via"], m["ext"]) for m in meals], [("Chicken burger (bigger)", in_days(6), "platter", "platter:abc")], "the same id updates, it does not pile up")
        self.assertEqual(self.ext(key, "delete", "/api/ext/meals/platter:abc").status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/meals?from=" + in_days(0) + "&to=" + in_days(14)).get_json()["meals"], [])
        self.assertEqual(self.ext(key, "post", "/api/ext/meals", day=in_days(1), title="No id").status_code, 400)

    def test_a_wrong_or_revoked_key_gets_nothing_and_a_key_cannot_reach_anything_else(self):
        made = self.link().get_json()
        for bad in ("hbk_wrong", "", "notakey", self.mum["Authorization"].split()[1]):
            self.assertEqual(self.ext(bad, "get", "/api/ext/ping").status_code, 401, bad)
        self.assertEqual(self.c.get("/api/family", headers={"Authorization": "Bearer " + made["key"]}).status_code, 401, "it is not a person's token")
        self.assertEqual(self.c.delete(f"/api/integrations/{made['id']}", headers=self.kid).status_code, 403)
        self.assertEqual(self.c.delete(f"/api/integrations/{made['id']}", headers=self.mum).status_code, 200)
        self.assertEqual(self.ext(made["key"], "get", "/api/ext/ping").status_code, 401, "revoked")

    def test_a_key_only_touches_its_own_family(self):
        key = self.link().get_json()["key"]
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.ext(key, "post", "/api/ext/meals", ext_id="platter:x", day=in_days(2), title="Ours")
        self.assertEqual(self.get(zed, "/api/meals").get_json()["meals"], [])
        self.assertEqual(len(self.get(self.mum, "/api/meals").get_json()["meals"]), 1)

    def test_only_the_linked_apps_own_address_may_call_it_from_a_browser(self):
        key = self.link().get_json()["key"]
        pre = self.c.options("/api/ext/meals", headers={"Origin": self.ORIGIN, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "authorization,content-type"})
        self.assertIn(pre.status_code, (200, 204), "a browser accepts either for a preflight")
        self.assertEqual(pre.headers["Access-Control-Allow-Origin"], self.ORIGIN)
        self.assertIn("Authorization", pre.headers["Access-Control-Allow-Headers"])
        ok = self.ext(key, "post", "/api/ext/meals", origin=self.ORIGIN, ext_id="platter:y", day=in_days(2), title="From the browser")
        self.assertEqual((ok.status_code, ok.headers["Access-Control-Allow-Origin"]), (200, self.ORIGIN))
        evil = self.ext(key, "get", "/api/ext/ping", origin="https://evil.example.com")
        self.assertNotIn("Access-Control-Allow-Origin", evil.headers, "another website cannot read the answer")
        self.assertNotIn("Access-Control-Allow-Origin", self.c.get("/api/meals", headers={**self.mum, "Origin": self.ORIGIN}).headers, "and the rest of the API stays closed to browsers from elsewhere")


class ScoutLink(Base):
    """Scout is linked with a key that may put places on the calendar, save them and share them, and nothing to do with meals."""
    SCOUT = "http://127.0.0.1:5055"
    link, ext = LinkedApps.link, LinkedApps.ext

    def scout(self):
        return self.link("Scout").get_json()

    def start(self, days=3):
        return time.time() + days * 86400

    def test_the_key_carries_what_it_may_do_and_a_scout_key_cannot_touch_the_meal_plan(self):
        made = self.scout()
        self.assertEqual(made["scopes"], ["plans", "places", "share"])
        self.assertEqual(self.link("Platter").get_json()["scopes"], ["meals"], "an app that is not Scout keeps to meals")
        self.assertEqual(self.ext(made["key"], "get", "/api/ext/ping").get_json()["scopes"], ["plans", "places", "share"])
        self.assertEqual(self.ext(made["key"], "post", "/api/ext/meals", ext_id="x", day=in_days(2), title="Nope").status_code, 403)
        self.assertEqual(self.ext(made["key"], "delete", "/api/ext/meals/x").status_code, 403)
        listed = self.get(self.mum, "/api/integrations").get_json()["integrations"]
        self.assertEqual(sorted(i["scopes"] for i in listed), [["meals"], ["plans", "places", "share"]])

    def test_a_platter_key_cannot_plan_save_or_share(self):
        key = self.link("Platter").get_json()["key"]
        for method, path, body in (("post", "/api/ext/plans", dict(ext_id="s:1", title="Cafe", start=self.start())), ("post", "/api/ext/places", dict(name="Cafe", lat=26.4, lng=50.1)),
                                   ("post", "/api/ext/share", dict(name="Cafe")), ("delete", "/api/ext/plans/s:1", {})):
            self.assertEqual(self.ext(key, method, path, **body).status_code, 403, path)
        self.assertEqual(self.ext("hbk_wrong", "post", "/api/ext/plans", ext_id="s:1", title="Cafe", start=self.start()).status_code, 401)

    def test_a_place_goes_on_the_calendar_keeps_its_spot_and_updates_instead_of_piling_up(self):
        key = self.scout()["key"]
        body = dict(ext_id="scout:abc", title="Dinner at Zaatar", emoji="\U0001F37D\ufe0f", start=self.start(), note="King Road, Khobar", lat=26.3, lng=50.2)
        first = self.ext(key, "post", "/api/ext/plans", **body)
        self.assertEqual(first.status_code, 200)
        shown = self.get(self.kid, "/api/plans").get_json()["plans"]
        self.assertEqual([(p["title"], p["note"], p["lat"], p["lng"], p["via"]) for p in shown], [("Dinner at Zaatar", "King Road, Khobar", 26.3, 50.2, "scout")])
        self.assertTrue(any(e["type"] == "plan" and e["payload"].get("new") and e["payload"]["name"] == "Scout" for e in self.events(self.kid)), "the family is told")
        again = self.ext(key, "post", "/api/ext/plans", **{**body, "title": "Lunch at Zaatar", "start": self.start(5)})
        self.assertEqual(again.get_json()["id"], first.get_json()["id"])
        shown = self.get(self.kid, "/api/plans").get_json()["plans"]
        self.assertEqual([p["title"] for p in shown], ["Lunch at Zaatar"], "the same place updates, it does not pile up")
        self.assertEqual(self.post(self.kid, f"/api/plans/{shown[0]['id']}/rsvp", answer="in").status_code, 200, "and the family can say they are in")
        self.assertEqual(self.ext(key, "delete", "/api/ext/plans/scout:abc").status_code, 200)
        self.assertEqual(self.get(self.kid, "/api/plans").get_json()["plans"], [])

    def test_a_plan_with_a_spot_shows_it_in_the_calendar_file(self):
        key = self.scout()["key"]
        self.ext(key, "post", "/api/ext/plans", ext_id="scout:ics", title="Picnic", start=self.start(), note="Corniche", lat=26.35, lng=50.21)
        text = self.c.get("/api/calendar.ics", headers=self.mum).get_data(as_text=True)
        self.assertIn("GEO:26.350000;50.210000", text)
        self.assertIn("LOCATION:Corniche", text)

    def test_a_plan_from_a_place_is_checked(self):
        key = self.scout()["key"]
        ok = dict(ext_id="scout:v", title="Cafe", start=self.start())
        self.assertEqual(self.ext(key, "post", "/api/ext/plans", **ok).status_code, 200, "the spot is optional")
        for bad in ({**ok, "ext_id": ""}, {**ok, "title": "  "}, {**ok, "start": "soon"}, {**ok, "start": time.time() - 10 * 86400}, {**ok, "start": time.time() + 5 * 365 * 86400}):
            self.assertEqual(self.ext(key, "post", "/api/ext/plans", **bad).status_code, 400, bad)
        odd = self.ext(key, "post", "/api/ext/plans", **{**ok, "ext_id": "scout:w", "lat": 999, "lng": 5}).get_json()["id"]
        row = [p for p in self.get(self.kid, "/api/plans").get_json()["plans"] if p["id"] == odd][0]
        self.assertEqual((row["lat"], row["lng"]), (None, None), "a spot that is not on Earth is dropped, the plan stays")

    def test_a_saved_place_tells_the_family_once_and_is_not_added_twice(self):
        key = self.scout()["key"]
        body = dict(name="Granny's house", emoji="\U0001F3E1", lat=26.4, lng=50.1)
        first = self.ext(key, "post", "/api/ext/places", **body)
        self.assertEqual(first.status_code, 200)
        self.assertEqual([p["name"] for p in self.get(self.kid, "/api/family").get_json()["places"]], ["Granny's house"])
        self.assertTrue(any("Scout saved the place Granny's house" in m["body"] for m in self.get(self.kid, "/api/messages").get_json()["messages"]))
        again = self.ext(key, "post", "/api/ext/places", **{**body, "name": "granny's house", "lat": 26.4003})
        self.assertEqual((again.get_json()["id"], again.get_json().get("existed")), (first.get_json()["id"], True))
        self.assertEqual(len(self.get(self.kid, "/api/family").get_json()["places"]), 1)
        self.assertEqual(self.ext(key, "post", "/api/ext/places", name="No spot").status_code, 400)
        self.assertEqual(self.ext(key, "post", "/api/ext/places", lat=26.4, lng=50.1).status_code, 400)

    def test_a_place_can_be_shared_to_the_family_chat_as_a_note_from_scout(self):
        key = self.scout()["key"]
        sent = self.ext(key, "post", "/api/ext/share", name="Pizza Hut", address="Prince Turki Road", lat=26.4, lng=50.1)
        self.assertEqual(sent.status_code, 200)
        msgs = [m for m in self.get(self.kid, "/api/messages").get_json()["messages"] if "Pizza Hut" in m["body"]]
        self.assertEqual([(m["kind"], m["body"], m["lat"], m["from"]) for m in msgs], [("system", "\U0001F9ED Scout: Pizza Hut, Prince Turki Road", 26.4, 0)])
        got = [e["payload"] for e in self.events(self.mum) if e["type"] == "message" and "Pizza Hut" in e["payload"]["body"]]
        self.assertEqual((len(got), got[0]["name"]), (1, "Scout"))
        self.assertEqual(self.ext(key, "post", "/api/ext/share").status_code, 400)

    def test_a_scout_key_only_touches_its_own_family(self):
        key = self.scout()["key"]
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.ext(key, "post", "/api/ext/plans", ext_id="scout:o", title="Ours", start=self.start())
        self.ext(key, "post", "/api/ext/places", name="Ours", lat=1.0, lng=1.0)
        self.assertEqual(self.get(zed, "/api/plans").get_json()["plans"], [])
        self.assertEqual(self.get(zed, "/api/family").get_json()["places"], [])

    def test_scout_may_call_from_a_browser_but_another_website_may_not(self):
        key = self.scout()["key"]
        ok = self.ext(key, "post", "/api/ext/plans", origin=self.SCOUT, ext_id="scout:c", title="Cafe", start=self.start())
        self.assertEqual((ok.status_code, ok.headers["Access-Control-Allow-Origin"]), (200, self.SCOUT))
        evil = self.ext(key, "get", "/api/ext/ping", origin="https://evil.example.com")
        self.assertNotIn("Access-Control-Allow-Origin", evil.headers)

    def test_old_keys_made_before_scopes_existed_still_mean_meals_only(self):
        made = self.link("Platter").get_json()
        db.run(db.update(db.integrations).where(db.integrations.c.id == made["id"]).values(scopes=None))
        self.assertEqual(self.ext(made["key"], "get", "/api/ext/ping").get_json()["scopes"], ["meals"])
        self.assertEqual(self.ext(made["key"], "post", "/api/ext/meals", ext_id="p:1", day=in_days(2), title="Still works").status_code, 200)
        self.assertEqual(self.ext(made["key"], "post", "/api/ext/plans", ext_id="p:1", title="No", start=self.start()).status_code, 403)


class FamilyGames(Base):
    """Start Alibi from the family chat: a one-time link opens the game, and the game posts its join code back, once."""
    ALIBI = "http://127.0.0.1:5085"

    def start(self, who=None, game="alibi"):
        return self.post(who or self.kid, "/api/games/start", game=game)

    def payload(self, url):
        frag = url.split("#family=")[1]
        return json.loads(base64.urlsafe_b64decode(frag + "=" * (-len(frag) % 4)))

    def announce(self, token, code="ABCD", origin=None, **extra):
        headers = {"Origin": origin} if origin else {}
        return self.c.post("/api/ext/game", json={"token": token, "code": code, **extra}, headers=headers)

    def test_anyone_in_the_family_can_start_one_and_gets_a_link_to_the_game(self):
        r = self.start()
        self.assertEqual(r.status_code, 200)
        d = r.get_json()
        self.assertTrue(d["url"].startswith(server.ALIBI_URL + "/#family="), d["url"])
        p = self.payload(d["url"])
        self.assertEqual((p["n"], p["f"]), ("Sam", "The Vales"))
        self.assertTrue(p["t"].startswith("hbg_") and len(p["t"]) > 20)
        self.assertTrue(p["b"].startswith("http"), "the game is told where to answer")
        self.assertNotIn(p["t"], json.dumps(db.one(db.select(db.game_links))), "only a fingerprint of the key is kept")

    def test_only_the_known_game_and_only_a_signed_in_person(self):
        self.assertEqual(self.start(game="evilgame").status_code, 400)
        self.assertEqual(self.start(game="").status_code, 400)
        self.assertEqual(self.c.post("/api/games/start", json={"game": "alibi"}).status_code, 401)

    def test_the_game_posts_its_code_and_the_family_chat_gets_a_join_message(self):
        token = self.payload(self.start().get_json()["url"])["t"]
        r = self.announce(token, "k7p2", origin=self.ALIBI)
        self.assertEqual((r.status_code, r.headers.get("Access-Control-Allow-Origin")), (200, self.ALIBI))
        msgs = [m for m in self.get(self.mum, "/api/messages").get_json()["messages"] if m["kind"] == "game"]
        self.assertEqual([m["body"] for m in msgs], [f"🕵️ Sam started a game of Alibi. Join with code K7P2: {server.ALIBI_URL}/#join/K7P2"])
        got = [e["payload"] for e in self.events(self.mum) if e["type"] == "message" and e["payload"]["kind"] == "game"]
        self.assertEqual((len(got), got[0]["name"]), (1, "Alibi"))
        note = server.web_note("message", got[0])
        self.assertTrue(note and "Join with code K7P2" in note["body"])

    def test_the_link_works_once_and_runs_out(self):
        token = self.payload(self.start().get_json()["url"])["t"]
        self.assertEqual(self.announce(token).status_code, 200)
        self.assertEqual(self.announce(token).status_code, 401, "once")
        again = self.payload(self.start().get_json()["url"])["t"]
        db.run(db.update(db.game_links).where(db.game_links.c.token_hash == db.hash_token(again)).values(expires=time.time() - 1))
        self.assertEqual(self.announce(again).status_code, 401, "ran out")
        self.assertEqual(len([m for m in self.get(self.mum, "/api/messages").get_json()["messages"] if m["kind"] == "game"]), 1)

    def test_a_bad_code_or_key_posts_nothing_and_does_not_use_up_the_link(self):
        token = self.payload(self.start().get_json()["url"])["t"]
        for bad in ("", "AB", "ABCDE", "AB!D", "<b>1", None):
            self.assertEqual(self.announce(token, bad).status_code, 400, bad)
        for wrong in ("hbg_wrong", "", "nope"):
            self.assertIn(self.announce(wrong).status_code, (400, 401), wrong)
        self.assertEqual(self.get(self.mum, "/api/messages").get_json()["messages"][-1]["kind"] != "game", True)
        self.assertEqual(self.announce(token).status_code, 200, "the link still works")

    def test_a_game_can_only_post_to_the_family_that_made_the_link(self):
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        token = self.payload(self.start(who=zed).get_json()["url"])["t"]
        self.announce(token, "ZZZZ")
        self.assertEqual([m for m in self.get(self.mum, "/api/messages").get_json()["messages"] if m["kind"] == "game"], [])
        self.assertEqual(len([m for m in self.get(zed, "/api/messages").get_json()["messages"] if m["kind"] == "game"]), 1)

    def test_nobody_can_forge_a_game_message_or_point_it_somewhere_else(self):
        forged = self.post(self.kid, "/api/messages", kind="game", body="Join: https://evil.example.com")
        self.assertEqual(forged.get_json()["message"]["kind"], "text", "a person's message can never be a game message, so it never gets a Join button")
        token = self.payload(self.start().get_json()["url"])["t"]
        self.announce(token, "ABCD", url="https://evil.example.com", game="evil", name="Boss")
        body = [m for m in self.get(self.mum, "/api/messages").get_json()["messages"] if m["kind"] == "game"][0]["body"]
        self.assertNotIn("evil", body)
        self.assertTrue(body.endswith(f"{server.ALIBI_URL}/#join/ABCD"))

    def test_the_browser_check_and_the_rate_limit(self):
        pre = self.c.options("/api/ext/game", headers={"Origin": self.ALIBI, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type"})
        self.assertIn(pre.status_code, (200, 204))
        self.assertEqual(pre.headers["Access-Control-Allow-Origin"], self.ALIBI)
        token = self.payload(self.start().get_json()["url"])["t"]
        evil = self.announce(token, "ABCD", origin="https://evil.example.com")
        self.assertNotIn("Access-Control-Allow-Origin", evil.headers)
        server._hits.clear()
        codes = [self.start().status_code for _ in range(12)]
        self.assertEqual(codes[:10], [200] * 10)
        self.assertEqual(codes[10:], [429, 429])


class GifMessages(Base):
    def send(self, body, who=None):
        return self.post(who or self.kid, "/api/messages", kind="gif", body=body)

    def test_a_built_in_sticker_can_be_sent(self):
        name = server.sticker_names()[0]
        r = self.send(f"/static/stickers/{name}.gif")
        self.assertEqual((r.status_code, r.get_json()["message"]["kind"], r.get_json()["message"]["body"]), (200, "gif", f"/static/stickers/{name}.gif"))
        got = [e["payload"] for e in self.events(self.mum) if e["type"] == "message" and e["payload"]["kind"] == "gif"]
        self.assertEqual((len(got), got[0]["name"]), (1, "Sam"))

    def test_links_from_known_hosts_are_accepted_and_a_giphy_page_becomes_its_picture(self):
        for good in ("https://media.giphy.com/media/abc123XYZ/giphy.gif", "https://media.tenor.com/xyz/cat.gif", "https://i.imgur.com/aBcDe.gif", "https://c.tenor.com/q/x.webp?width=200"):
            self.assertEqual(self.send(good).status_code, 200, good)
        page = self.send("https://giphy.com/gifs/cat-dancing-3o7abKhOpu0NwenH3O").get_json()["message"]["body"]
        self.assertEqual(page, "https://media.giphy.com/media/3o7abKhOpu0NwenH3O/giphy.gif")

    def test_everything_else_is_refused(self):
        for bad in ("http://media.giphy.com/media/a/giphy.gif", "https://evil.example.com/x.gif", "https://media.giphy.com.evil.example.com/x.gif", "https://tenor.com/view/cat-dance-gif-123456",
                    "https://media.giphy.com/media/a/page.html", "/static/stickers/nope.gif", "/static/stickers/../../app.py", "javascript:alert(1)", "", "x" * 500, "https://giphy.com/gifs/"):
            self.assertEqual(self.send(bad).status_code, 400, bad)

    def test_the_sticker_list_comes_from_the_folder(self):
        names = [s["name"] for s in self.c.get("/api/stickers").get_json()["stickers"]]
        self.assertEqual(names, server.sticker_names())
        self.assertGreaterEqual(len(names), 12)
        for sticker in self.c.get("/api/stickers").get_json()["stickers"][:3]:
            self.assertEqual(self.c.get(sticker["url"]).status_code, 200)

    def test_a_gif_is_an_alert_on_a_closed_app_too(self):
        self.assertEqual(server.web_note("message", {"kind": "gif", "name": "Sam", "from": 1})["body"], "\U0001F39E\ufe0f sent a GIF")


class MealExtras(Base):
    def meal(self, **kw):
        return self.post(self.mum, "/api/meals", **{"day": in_days(2), "title": "Chicken burger", "ingredients": ["2 chicken breasts", "4 buns", "Hot sauce"], **kw}).get_json()["id"]

    def test_who_is_cooking(self):
        mid = self.meal()
        self.assertEqual(self.post(self.kid, f"/api/meals/{mid}/cook", cook=self.kid_id).get_json()["cook"], self.kid_id)
        self.assertEqual(self.get(self.mum, "/api/meals").get_json()["meals"][0]["cook"], self.kid_id)
        self.assertEqual(self.post(self.kid, f"/api/meals/{mid}/cook", cook=self.mum_id).status_code, 403, "a child cannot name someone else")
        self.assertEqual(self.post(self.mum, f"/api/meals/{mid}/cook", cook=self.mum_id).status_code, 200, "a parent can name anyone")
        self.assertEqual(self.post(self.kid, f"/api/meals/{mid}/cook", cook=0).status_code, 403, "and nobody can take someone else's name off")
        self.assertEqual(self.post(self.mum, f"/api/meals/{mid}/cook", cook=0).get_json()["cook"], 0)
        self.assertEqual(self.post(self.mum, f"/api/meals/{mid}/cook", cook=99999).status_code, 404)

    def test_ingredients_go_on_the_family_list_once(self):
        mid = self.meal()
        self.post(self.kid, "/api/list", text="hot sauce")
        self.assertEqual(self.post(self.kid, f"/api/meals/{mid}/to-list").get_json()["added"], 2, "what is already waiting on the list is not doubled")
        self.assertEqual(sorted(i["text"] for i in self.get(self.mum, "/api/list").get_json()["items"]), ["2 chicken breasts", "4 buns", "hot sauce"])
        self.assertEqual(self.post(self.kid, f"/api/meals/{mid}/to-list").get_json()["added"], 0)
        self.assertEqual(self.post(self.kid, "/api/meals/99999/to-list").status_code, 404)

    def test_other_families_cannot_touch_a_meal(self):
        mid = self.meal()
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertEqual(self.post(zed, f"/api/meals/{mid}/cook", cook=0).status_code, 404)
        self.assertEqual(self.post(zed, f"/api/meals/{mid}/to-list").status_code, 404)

    def test_the_activity_feed_shows_what_happened_newest_first(self):
        self.post(self.mum, "/api/chores", title="Bins", points=5)
        self.post(self.kid, "/api/messages", kind="late", body="Running about 10 minutes late")
        self.post(self.kid, "/api/messages", body="a private chat does not belong in the feed", to=self.mum_id)
        items = self.get(self.mum, "/api/feed").get_json()["items"]
        self.assertEqual(items[0]["kind"], "late")
        self.assertEqual((items[0]["name"], items[0]["emoji"]), ("Sam", "\U0001F9D2"))
        self.assertTrue(any("added a job: Bins" in i["body"] for i in items))
        self.assertFalse(any("private chat" in i["body"] for i in items))
        self.assertEqual(self.c.get("/api/feed").status_code, 401)
        zed = {"Authorization": "Bearer " + self.c.post("/api/family", json={"family": "Elsewhere", "name": "Zed"}).get_json()["token"]}
        self.assertFalse(any("Bins" in i["body"] for i in self.get(zed, "/api/feed").get_json()["items"]))


class FriendGroups(Base):
    def setUp(self):
        super().setUp()
        d = self.c.post("/api/family", json={"family": "The Parks", "name": "Pat"}).get_json()
        self.pat, self.pat_id = {"Authorization": "Bearer " + d["token"]}, d["me"]["id"]
        code = self.post(self.pat, "/api/invite", role="child").get_json()["code"]
        k = self.c.post("/api/join", json={"code": code, "name": "Zoe", "emoji": "\U0001F467"}).get_json()
        self.zoe, self.zoe_id = {"Authorization": "Bearer " + k["token"]}, k["me"]["id"]

    def groups(self, who):
        return self.get(who, "/api/groups").get_json()

    def make(self, name="Football mates"):
        """Sam makes a group, Mum says yes, Zoe joins with the code, Pat says yes: both children are in, both families' parents can read."""
        made = self.post(self.kid, "/api/groups", name=name).get_json()
        self.post(self.mum, f"/api/groups/{made['id']}/approve", member=self.kid_id, ok=True)
        self.post(self.zoe, "/api/groups/join", code=made["code"])
        self.post(self.pat, f"/api/groups/{made['id']}/approve", member=self.zoe_id, ok=True)
        return made["id"], made["code"]

    def read(self, who, gid):
        return self.get(who, f"/api/groups/{gid}/messages")

    def say(self, who, gid, body, **kw):
        return self.post(who, f"/api/groups/{gid}/messages", body=body, **kw)

    # ---------------------------------------------------------------- joining needs a parent's yes
    def test_a_child_makes_a_group_and_waits_for_a_parent(self):
        made = self.post(self.kid, "/api/groups", name="Football mates").get_json()
        self.assertEqual(made["status"], "pending")
        mine = self.groups(self.kid)["groups"][0]
        self.assertEqual((mine["status"], mine["code"]), ("pending", None), "no code to hand out until a parent has said yes")
        ask = self.groups(self.mum)["requests"]
        self.assertEqual([(a["group_name"], a["name"]) for a in ask], [("Football mates", "Sam")])
        self.assertEqual(self.read(self.kid, made["id"]).status_code, 403)
        self.assertEqual(self.say(self.kid, made["id"], "hello").status_code, 403)
        self.assertTrue(any(e["type"] == "group_request" for e in self.events(self.mum)))
        self.assertEqual(self.post(self.mum, f"/api/groups/{made['id']}/approve", member=self.kid_id, ok=True).status_code, 200)
        self.assertEqual(self.groups(self.kid)["groups"][0]["status"], "active")
        self.assertEqual(len(self.groups(self.kid)["groups"][0]["code"]), 8)
        self.assertEqual(self.say(self.kid, made["id"], "hello").status_code, 200)

    def test_a_no_removes_the_request(self):
        made = self.post(self.kid, "/api/groups", name="Chess club").get_json()
        self.post(self.mum, f"/api/groups/{made['id']}/approve", member=self.kid_id, ok=False)
        self.assertEqual(self.groups(self.kid)["groups"], [])
        self.assertTrue(any(e["type"] == "group" and e["payload"].get("declined") for e in self.events(self.kid)))

    def test_a_friend_from_another_family_joins_with_the_code_and_her_parent_decides(self):
        made = self.post(self.kid, "/api/groups", name="Football mates").get_json()
        self.post(self.mum, f"/api/groups/{made['id']}/approve", member=self.kid_id, ok=True)
        joined = self.post(self.zoe, "/api/groups/join", code=" " + made["code"].lower().replace("", " ").strip() + " ").get_json()
        self.assertEqual((joined["status"], joined["name"]), ("pending", "Football mates"), "case and spaces in the code do not matter")
        self.assertEqual([a["name"] for a in self.groups(self.pat)["requests"]], ["Zoe"])
        self.assertEqual(self.groups(self.mum)["requests"], [], "only a child's own parents are asked")
        self.assertEqual(self.post(self.mum, f"/api/groups/{made['id']}/approve", member=self.zoe_id, ok=True).status_code, 404, "another family's parent cannot approve her")
        self.assertEqual(self.post(self.kid, f"/api/groups/{made['id']}/approve", member=self.zoe_id, ok=True).status_code, 403, "and a child cannot either")
        self.post(self.pat, f"/api/groups/{made['id']}/approve", member=self.zoe_id, ok=True)
        self.assertEqual(sorted(m["name"] for m in self.groups(self.zoe)["groups"][0]["members"]), ["Sam", "Zoe"])

    def test_wrong_codes_and_guessing(self):
        self.assertEqual(self.post(self.zoe, "/api/groups/join", code="NOPE1234").status_code, 404)
        self.assertEqual(self.post(self.zoe, "/api/groups/join", code="").status_code, 404)
        codes = [self.post(self.zoe, "/api/groups/join", code=f"AAAA{i:04d}").status_code for i in range(12)]
        self.assertEqual(codes[-1], 429, "guessing codes is slowed right down")

    # ---------------------------------------------------------------- parents can read, only members can write
    def test_every_childs_parents_can_read_the_group_but_cannot_write_in_it(self):
        gid, _ = self.make()
        self.say(self.kid, gid, "Training at 4?")
        for parent in (self.mum, self.pat):
            r = self.read(parent, gid).get_json()
            self.assertEqual((r["role"], r["messages"][-1]["body"]), ("overseer", "Training at 4?"))
            self.assertEqual(self.say(parent, gid, "Hi from a parent").status_code, 403)
        self.assertEqual(self.read(self.zoe, gid).get_json()["role"], "member")
        self.assertEqual([g["overseeing"] for g in self.groups(self.mum)["groups"]], [["Sam"]])
        self.assertEqual([g["overseeing"] for g in self.groups(self.pat)["groups"]], [["Zoe"]])

    def test_a_stranger_or_the_parent_of_a_child_who_is_not_in_it_sees_nothing(self):
        gid, _ = self.make()
        d = self.c.post("/api/family", json={"family": "Strangers", "name": "Sue"}).get_json()
        sue = {"Authorization": "Bearer " + d["token"]}
        self.assertEqual(self.read(sue, gid).status_code, 404)
        self.assertEqual(self.say(sue, gid, "hi").status_code, 404)
        self.assertEqual(self.groups(sue)["groups"], [])

    def test_family_chat_and_group_chat_are_separate(self):
        gid, _ = self.make()
        self.say(self.kid, gid, "only for the group")
        self.assertFalse(any("only for the group" in m["body"] for m in self.get(self.mum, "/api/messages").get_json()["messages"]))
        self.post(self.kid, "/api/messages", body="only for the family")
        self.assertNotIn("only for the family", [m["body"] for m in self.read(self.zoe, gid).get_json()["messages"]])

    # ---------------------------------------------------------------- the filter
    def test_rude_words_are_starred_out_and_the_right_people_see_the_right_things(self):
        gid, _ = self.make()
        r = self.say(self.kid, gid, "you are so dumb").get_json()
        self.assertEqual((r["body"], r["flagged"]), ("you are so d***", True))
        self.assertIn("Your parents are told", r["note"])
        zoe_sees = self.read(self.zoe, gid).get_json()["messages"][-1]
        self.assertEqual((zoe_sees["body"], zoe_sees["flagged"], zoe_sees["cats"]), ("you are so d***", True, ["insult"]))
        self.assertNotIn("original", zoe_sees)
        self.assertNotIn("flags", zoe_sees)
        mum_sees = self.read(self.mum, gid).get_json()["messages"][-1]
        self.assertEqual((mum_sees["original"], mum_sees["flags"]), ("you are so dumb", [{"word": "dumb", "cat": "insult"}]), "the sender's own parent sees what was really typed")
        pat_sees = self.read(self.pat, gid).get_json()["messages"][-1]
        self.assertEqual((pat_sees["flagged"], "original" in pat_sees, "flags" in pat_sees), (True, False, False), "another family's parent sees that it was flagged, not the words")

    def test_both_families_parents_are_told_about_a_flag_and_clean_messages_are_quiet(self):
        gid, _ = self.make()
        self.say(self.kid, gid, "may your pillow be hot on both sides")
        self.assertFalse(any(e["type"] == "group_flag" for e in self.events(self.mum) + self.events(self.pat)), "mild cheek is fine and nobody is bothered")
        self.assertFalse(self.read(self.zoe, gid).get_json()["messages"][-1]["flagged"])
        self.say(self.kid, gid, "stupid ref")
        for parent in (self.mum, self.pat):
            flags = [e["payload"] for e in self.events(parent) if e["type"] == "group_flag"]
            self.assertEqual((len(flags), flags[0]["name"], flags[0]["group"], flags[0]["cat"]), (1, "Sam", "Football mates", "insult"))
        zoe_events = [e["payload"] for e in self.events(self.zoe) if e["type"] == "gmessage"]
        self.assertEqual([m["body"] for m in zoe_events], ["may your pillow be hot on both sides", "s***** ref"], "friends get the censored text, never the original")
        self.assertFalse(any(e["type"] == "gmessage" for e in self.events(self.mum)), "parents are not buzzed about every chat")

    def test_a_slur_is_a_break_at_once_and_three_rude_messages_are_a_break_too(self):
        gid, _ = self.make()
        r = self.say(self.kid, gid, "you nga").get_json()
        self.assertIn("hurtful", r["note"])
        self.assertIn("10 minutes", r["note"])
        blocked = self.say(self.kid, gid, "sorry")
        self.assertEqual(blocked.status_code, 429)
        self.assertIn("short break", blocked.get_json()["error"])
        self.assertEqual(self.read(self.kid, gid).status_code, 200, "a break is only from writing")
        self.assertEqual(self.say(self.zoe, gid, "I never said anything wrong").status_code, 200, "and only for that person")
        for n in range(3):
            last = self.say(self.zoe, gid, f"idiot {n}")
        self.assertIn("15 minutes", last.get_json()["note"])
        self.assertEqual(self.say(self.zoe, gid, "ok").status_code, 429)

    def test_gifs_go_through_the_same_checks_as_everywhere_else(self):
        gid, _ = self.make()
        sticker = f"/static/stickers/{server.sticker_names()[0]}.gif"
        self.assertEqual(self.say(self.kid, gid, sticker, kind="gif").status_code, 200)
        self.assertEqual(self.say(self.kid, gid, "https://evil.example.com/x.gif", kind="gif").status_code, 400)
        last = self.read(self.zoe, gid).get_json()["messages"][-1]
        self.assertEqual((last["kind"], last["body"], last["flagged"]), ("gif", sticker, False))

    def test_empty_messages_and_group_names(self):
        gid, _ = self.make()
        self.assertEqual(self.say(self.kid, gid, "   ").status_code, 400)
        self.assertEqual(self.post(self.kid, "/api/groups", name="Dumb club").status_code, 400, "a group cannot be named something rude")
        self.assertEqual(self.post(self.kid, "/api/groups", name="  ").status_code, 400)

    # ---------------------------------------------------------------- taking someone out
    def test_a_parent_can_take_their_own_child_out_and_a_child_can_leave(self):
        gid, _ = self.make()
        self.assertEqual(self.c.delete(f"/api/groups/{gid}/members/{self.zoe_id}", headers=self.mum).status_code, 404, "not another family's child")
        self.assertEqual(self.c.delete(f"/api/groups/{gid}/members/{self.kid_id}", headers=self.kid).status_code, 403)
        self.assertEqual(self.c.delete(f"/api/groups/{gid}/members/{self.kid_id}", headers=self.mum).status_code, 200)
        self.assertEqual(self.read(self.kid, gid).status_code, 404)
        self.assertEqual(self.read(self.mum, gid).status_code, 404, "and Mum no longer oversees a group her child is not in")
        self.assertEqual(self.post(self.zoe, f"/api/groups/{gid}/leave").status_code, 200)
        self.assertEqual(self.read(self.zoe, gid).status_code, 404)
        self.assertEqual(db.one(db.select(db.friend_groups).where(db.friend_groups.c.id == gid)), None, "the last one out turns off the light")

    def test_a_parent_can_be_a_member_straight_away_and_there_is_a_limit_on_groups(self):
        made = self.post(self.mum, "/api/groups", name="Book club").get_json()
        self.assertEqual(made["status"], "active")
        self.assertEqual(self.say(self.mum, made["id"], "Chapter 3 next week").status_code, 200)
        for i in range(7):
            self.assertEqual(self.post(self.mum, "/api/groups", name=f"Group {i}").status_code, 200)
        self.assertEqual(self.post(self.mum, "/api/groups", name="One too many").status_code, 400)


class PhotoMessages(Base):
    JPEG = b"\xff\xd8\xff\xe0" + b"photo-bytes" * 20

    def upload(self, who=None, blob=None):
        import base64
        return self.post(who or self.kid, "/api/images", image="data:image/jpeg;base64," + base64.b64encode(blob or self.JPEG).decode())

    def test_a_photo_can_be_uploaded_sent_and_shown(self):
        up = self.upload()
        self.assertEqual(up.status_code, 200)
        url = up.get_json()["url"]
        self.assertRegex(url, r"^/api/img/[A-Za-z0-9_-]{16,}$")
        r = self.post(self.kid, "/api/messages", kind="image", body=url)
        self.assertEqual((r.status_code, r.get_json()["message"]["kind"], r.get_json()["message"]["body"]), (200, "image", url))
        got = [e["payload"] for e in self.events(self.mum) if e["type"] == "message" and e["payload"]["kind"] == "image"]
        self.assertEqual((len(got), got[0]["name"], got[0]["body"]), (1, "Sam", url))
        pic = self.c.get(url)                                           # the address is the key: no sign-in needed to show it
        self.assertEqual((pic.status_code, pic.mimetype, pic.data), (200, "image/jpeg", self.JPEG))
        pic.close()

    def test_only_a_real_photo_from_your_own_family_can_be_sent(self):
        url = self.upload().get_json()["url"]
        other = self.c.post("/api/family", json={"family": "The Parks", "name": "Pat"}).get_json()
        pat = {"Authorization": "Bearer " + other["token"]}
        self.assertEqual(self.post(pat, "/api/messages", kind="image", body=url).status_code, 400, "another family cannot use this photo")
        for bad in ("/api/img/not-a-photo", "https://evil.example.com/x.jpg", "/static/stickers/x.gif", "", "/api/img/"):
            self.assertEqual(self.post(self.kid, "/api/messages", kind="image", body=bad).status_code, 400, bad)
        self.assertEqual(self.c.get("/api/img/nothing-here").status_code, 404)

    def test_uploads_must_be_small_normal_photos(self):
        self.assertEqual(self.post(self.kid, "/api/images", image="@@not base64@@").status_code, 400)
        self.assertEqual(self.upload(blob=b"GIF89a" + b"x" * 50).status_code, 400, "only JPEG and PNG")
        self.assertEqual(self.upload(blob=b"\xff\xd8\xff" + b"x" * (server.IMAGE_LIMIT + 1)).status_code, 400, "too big")
        self.assertEqual(self.c.post("/api/images", json={"image": ""}).status_code, 401, "and you must be signed in")

    def test_a_child_can_switch_messages_off_for_parents_and_that_includes_photos(self):
        self.c.patch("/api/me", headers=self.kid, json={"perms": {"messages": False}})
        url = self.upload(self.mum).get_json()["url"]
        r = self.post(self.mum, "/api/messages", kind="image", body=url, to=self.kid_id)
        self.assertEqual(r.status_code, 403)

    def test_a_family_keeps_only_its_newest_photos(self):
        old = server.IMAGES_PER_FAMILY
        server.IMAGES_PER_FAMILY = 3
        try:
            urls = [self.upload().get_json()["url"] for _ in range(5)]
        finally:
            server.IMAGES_PER_FAMILY = old
        self.assertEqual([self.c.get(u).status_code for u in urls], [404, 404, 200, 200, 200])

    def test_a_photo_is_an_alert_on_a_closed_app_too(self):
        self.assertEqual(server.web_note("message", {"kind": "image", "name": "Sam", "from": 1})["body"], "\U0001F4F7 sent a photo")


if __name__ == "__main__":
    unittest.main()
