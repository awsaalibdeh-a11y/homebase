# Homebase

A family app for Android (location, messages, calls that ring) that is built to never feel controlling.

- `app.py`, `db.py`: the server (Flask, SQLite locally, Postgres via `DATABASE_URL`). Messages and calls reach phones through a long-poll event queue, so no Firebase or Google account is needed.
- `android/`: the app (Kotlin, Jetpack Compose, WebRTC audio calls, OpenStreetMap). Build with `./gradlew assembleDebug -PhomebaseServer=https://your-server`.
- `static/homebase.apk`: the build served from `/download`.
- `static/web/`: Homebase on a laptop, served at `/app` (same accounts, map, chat, check-ins, permissions and WebRTC calls, in a browser).

## Principles
- Everything shared is visible on the child's screen. There is no hidden mode.
- The child can pause sharing (the family sees "paused"), set quiet hours, decline "Where are you?", and switch off any parent permission from **Me > What parents can do**. The server enforces it.
- Never: read other apps or private messages, switch on the mic or camera by itself, lock the device, or limit screen time.
- Location is kept 7 days, and anyone can delete their own history.
- SOS and "I'm OK" check-ins always reach the family.

## Optional environment variables
`DATABASE_URL`, `TURN_URLS` / `TURN_USER` / `TURN_PASS` (helps calls on mobile data), `APP_VERSION_CODE`.
