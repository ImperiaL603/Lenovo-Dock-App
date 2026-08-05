# Lenovo Dock

Turns an old Android tablet into an always-on desk clock and Spotify display.

- Full-screen clock with day, time, date, next alarm and running timers
- Alarms and timers, scheduled natively so they survive reboots
- Spotify now-playing: album art, scrolling time-synced lyrics, transport controls
- Video wallpapers with an optional daily rotation
- Sleep timer with an optional volume fade-out
- Auto-dim from the ambient light sensor, themes, album-colour tinting

Built for a Lenovo TB-X306X (1280x800, Android 10, 2GB RAM). It is a native
Android shell around a WebView front-end.

## How it works

A `NotificationListenerService` reads Spotify's media session through
`MediaSessionManager` — track, position, album art and playback state — and pushes
it into a WebView that renders the interface. Transport buttons go back the same
way. Lyrics are fetched natively from [lrclib.net](https://lrclib.net), with
NetEase as a fallback for tracks lrclib has no timings for.

Nothing here touches the Spotify Web API, and it holds no Spotify credentials.

## Download

**[Latest release](https://github.com/ImperiaL603/Lenovo-Dock-App/releases/latest)** — grab the `.apk` and open it on the tablet.

Android will ask permission to install from an unknown source the first time; allow
it for whichever app you opened the file with. Updates install straight over the top,
no uninstall needed.

## Requirements

- Android 10 or newer, landscape
- Spotify installed, on the same device
- Notification access, so the media session can be read:

```
adb shell cmd notification allow_listener com.lenovodock.app/com.lenovodock.app.MediaListenerService
```

## Build

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases

Tagging is what publishes. `.github/workflows/release.yml` builds a signed APK and
attaches it to a GitHub Release:

```
git tag v0.2.0
git push origin v0.2.0
```

The tag sets `versionName` and the run number sets `versionCode`, so nothing needs
committing to cut a release.

It needs four repository secrets (Settings → Secrets and variables → Actions).
Generate a key once and keep the `.jks` somewhere safe — losing it means never being
able to update an installed copy again:

```
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias lenovodock

base64 -w0 release.jks > release.jks.base64   # Linux/macOS
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) > release.jks.base64   # PowerShell
```

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `release.jks.base64` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `lenovodock` |
| `KEY_PASSWORD` | key password |

To build a signed APK locally instead, put a `keystore.properties` in the repo root
(it is gitignored):

```
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=lenovodock
keyPassword=...
```

## Wallpapers

Wallpapers are read off the device rather than bundled, so adding one needs no
code change:

```
adb push my-wallpaper.mp4 /sdcard/Android/data/com.lenovodock.app/files/wallpapers/
```

## Licence

Copyright (C) 2026 Imperial

This program is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but **WITHOUT ANY
WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this
program. If not, see <https://www.gnu.org/licenses/>.

## No affiliation, and no responsibility

This project is not affiliated with, endorsed by, or connected to Spotify AB,
Lenovo, lrclib or NetEase. All trademarks belong to their respective owners.

It is provided as is. The author accepts no responsibility for what anyone else
does with it, including modified or redistributed copies. If you fork it, you are
responsible for your own use of it and for complying with the terms of any service
it talks to.

## Third-party assets

Some files in this repository are not the author's own work and are **not** covered
by the licence above. Check their terms before redistributing:

- `app/src/main/assets/web/assets/fonts/Anurati-Regular.otf` — Anurati, by Emmeran
  Richard. Free for personal use; verify its terms before shipping it anywhere.
- `app/src/main/assets/web/assets/video/endless-summer-horizon.1920x1080.mp4` —
  sample wallpaper, provenance unverified.
- Quicksand is pulled from Google Fonts at runtime (SIL Open Font License).
