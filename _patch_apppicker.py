s = open("db.py", encoding="utf-8").read()
sub_old = 'Column("name", String(60), default=""), Column("kind", String(12), default="windows"), Column("created", Float), Column("last_seen", Float, default=0))'
assert sub_old in s
s = s.replace(sub_old, 'Column("name", String(60), default=""), Column("kind", String(12), default="windows"), Column("created", Float), Column("last_seen", Float, default=0), Column("apps", Text, default=""))', 1)
open("db.py", "w", encoding="utf-8").write(s)

s = open("app.py", encoding="utf-8").read()


def sub(old, new):
    global s
    assert old in s, old[:70]
    s = s.replace(old, new, 1)


# migration for older databases
sub('''for _sql in ("ALTER TABLE members ADD COLUMN rules TEXT DEFAULT ''", "ALTER TABLE families ADD COLUMN rules TEXT DEFAULT ''"):''',
    '''for _sql in ("ALTER TABLE members ADD COLUMN rules TEXT DEFAULT ''", "ALTER TABLE families ADD COLUMN rules TEXT DEFAULT ''", "ALTER TABLE devices ADD COLUMN apps TEXT DEFAULT ''"):''')

# a device reports the apps on its computer; merged across a child's devices for the picker
sub('''@app.get("/api/devices")
@need
def list_devices():''', '''def apps_for(member_id):
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


@app.get("/api/devices")
@need
def list_devices():''')

# the grounding list includes the available apps
sub('''    return jsonify(blocks=block_views(target), now=time.time())


@app.post("/api/blocks")''', '''    return jsonify(blocks=block_views(target), available=apps_for(target["id"]), now=time.time())


@app.post("/api/blocks")''')

# admin families: include each child's available apps
sub('''"lock": lock_view(m), "blocks": block_views(m)} for m in ms]})''',
    '''"lock": lock_view(m), "blocks": block_views(m), "apps": apps_for(m["id"]) if m["role"] == "child" else []} for m in ms]})''')
open("app.py", "w", encoding="utf-8").write(s)

t = open("tests/test_server.py", encoding="utf-8").read()
t = t.replace('''class Grounding(Base):''', '''class AppReporting(Base):
    def test_a_device_reports_its_apps_for_the_picker(self):
        code = self.post(self.kid, "/api/devices/code").get_json()["code"]
        dev = {"Authorization": "Bearer " + self.c.post("/api/devices/claim", json={"code": code, "name": "Laptop"}).get_json()["token"]}
        self.assertEqual(self.post(self.kid, "/api/devices/apps", apps=[{"key": "roblox", "label": "Roblox"}]).status_code, 403)   # only a device
        self.post(dev, "/api/devices/apps", apps=[{"key": "roblox", "label": "Roblox"}, {"key": "chrome", "label": "Chrome"}, {"key": "x", "label": "too short"}])
        avail = self.get(self.mum, f"/api/blocks?member={self.kid_id}").get_json()["available"]
        self.assertEqual(sorted(a["key"] for a in avail), ["chrome", "roblox"])                         # the 1-letter key is dropped
        fam = next(f for f in self.get(self.mum, "/api/family").get_json()["members"] if f["id"] == self.kid_id)
        self.assertEqual(len(fam.get("blocks", [])), 0)


class Grounding(Base):''', 1)
open("tests/test_server.py", "w", encoding="utf-8").write(t)
print("ok")
