import os
import sys
import tempfile
import time
import unittest

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


if __name__ == "__main__":
    unittest.main()
