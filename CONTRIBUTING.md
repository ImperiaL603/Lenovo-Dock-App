# Building and releasing

## How it works

A native Android shell around a WebView front-end.

A `NotificationListenerService` reads Spotify's media session through
`MediaSessionManager` — track, position, album art, playback state — and pushes it
into a WebView that renders the interface. Transport buttons travel back the same
way. Lyrics are fetched natively from [lrclib.net](https://lrclib.net), with NetEase
as a fallback for tracks lrclib has words but no timings for; fetching them natively
is what sidesteps CORS from the page's `file://` origin.

Nothing touches the Spotify Web API and no Spotify credentials are held anywhere.

Layout:

```
app/src/main/java/com/lenovodock/app/   native — media session, alarms, lyrics, storage
app/src/main/assets/web/                the interface — html, css, js
```

## Build

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then grant notification access, which the media session reader needs:

```
adb shell cmd notification allow_listener com.lenovodock.app/com.lenovodock.app.MediaListenerService
```

Debug logging is filtered out on some vendor ROMs. If `adb logcat -s LenovoDock`
shows nothing while music is playing:

```
adb shell setprop persist.log.tag.LenovoDock D
```

then restart the app.

## Releases

Pushing a tag is what publishes. `.github/workflows/release.yml` builds a signed APK
and attaches it to a GitHub Release:

```
git tag v0.2.0
git push origin v0.2.0
```

The tag sets `versionName` and the workflow run number sets `versionCode`, so cutting
a release needs no commit.

### Signing

The workflow needs four repository secrets, under
**Settings → Secrets and variables → Actions**.

Generate a key once and keep the `.jks` somewhere safe — lose it and you can never
update an installed copy again, because Android rejects an APK signed with a
different key:

```
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias lenovodock

base64 -w0 release.jks > release.jks.base64                                          # Linux/macOS
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content release.jks.base64 -NoNewline   # PowerShell
```

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `release.jks.base64` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `lenovodock` |
| `KEY_PASSWORD` | key password |

To build a signed APK locally, put a `keystore.properties` in the repo root — it is
gitignored, along with `*.jks`, `*.jks.*` and `*.base64`:

```
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=lenovodock
keyPassword=...
```

Without either, `assembleRelease` produces an **unsigned** APK rather than failing,
so a fresh clone still builds. An unsigned APK will not install.

## Notes for contributors

- Debug and release builds are signed with different keys, so a device cannot hold
  both. Swapping means uninstalling, which wipes alarms, timers and settings.
- `versionCode` comes from the workflow run number. It must only ever increase —
  renaming the workflow file resets it, which would make future releases uninstallable
  over older ones.
- No wallpapers ship with the app. The folder on device is the single source of truth
  for which exist, and the app copies chosen videos into it.
