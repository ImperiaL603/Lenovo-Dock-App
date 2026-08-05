# Lenovo Dock

Turns an old Android tablet into an always-on desk clock and Spotify display.

- Full-screen clock with the day, time, date, your next alarm and any running timers
- Alarms and timers that survive a reboot
- Spotify now-playing: album art, scrolling time-synced lyrics, and playback controls
- Video wallpapers, with an optional daily rotation
- Sleep timer that can fade the volume down before stopping
- Screen dims itself in a dark room
- Themes, or tint the whole screen from the album cover

Made for a Lenovo TB-X306X (1280×800, Android 10), but it should run on any landscape
tablet with Android 10 or newer.

## Install

**[Download the latest release](https://github.com/ImperiaL603/Lenovo-Dock-App/releases/latest)** and open the `.apk` on your tablet.

Android will warn you that it's from an unknown source — allow it for whichever app
you opened the file with. Later versions install straight over the top, so you never
lose your settings.

## First-time setup

The app needs one permission to read what Spotify is playing. Nothing works in
Spotify mode until you grant it.

**Settings → Apps → Special app access → Notification access → turn on Lenovo Dock**

The exact wording varies by tablet — look for "Notification access" or "Device &
app notifications".

That's the only setup step. Spotify mode then appears on its own whenever music
starts, and returns to the clock about 30 seconds after it stops.

## Adding wallpapers

**No wallpapers come with the app** — you'll see a dark gradient behind the clock
until you add one.

Open the gear icon, then **Wallpaper → Add +**. Pick any video from your gallery or
files. It gets copied into the app, so you can safely delete the original afterwards.

To remove one, press **Remove**, tap the wallpapers you want gone, then **Delete**.

`.mp4`, `.webm` and `.m4v` all work. **Daily cycle** rotates through them, one a day.

## Settings

Everything lives behind the gear icon:

| Section | What's in it |
|---|---|
| **Wallpaper** | Add, remove, and the daily rotation |
| **Appearance** | Size of the clock, the Spotify screen and the lyrics, plus themes |
| **Display** | Auto-dim, and how dark it's allowed to get |
| **Audio** | Mute Spotify's ads |
| **Sleep** | Stop the music after a set time, optionally fading out first |

The `+` button on the clock screen is where alarms and timers live.

## Good to know

- Set your tablet's screen timeout to **Never**, or it'll sleep while Spotify is in
  the foreground.
- Lyrics come from [lrclib.net](https://lrclib.net). Not every track has them, and
  some community-uploaded ones are timed slightly wrong — the `+`/`−` control on the
  Spotify screen nudges them into place, per song.
- The app holds no Spotify login and never sees your account. It only reads what the
  Spotify app on the same tablet is already playing.

## About

I am a solo Vibe Coding Dev, still in school (so don't expect fast responses).

I made this app as it was useful to me and published it on git cause other people
might find it useful as well.

## Contributing

Build instructions and how releases are made are in
[CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Copyright (C) 2026 Imperial

This program is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but **WITHOUT ANY
WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this
program. If not, see <https://www.gnu.org/licenses/>.

## No affiliation, and no responsibility

This project is not affiliated with, endorsed by, or connected to Spotify AB, Lenovo,
lrclib or NetEase. All trademarks belong to their respective owners.

It is provided as is. The author accepts no responsibility for what anyone else does
with it, including modified or redistributed copies. If you fork it, you are
responsible for your own use of it and for complying with the terms of any service
it talks to.

## Third-party assets

Some files in this repository are not the author's own work and are **not** covered
by the licence above. Check their terms before redistributing:

- `app/src/main/assets/web/assets/fonts/Anurati-Regular.otf` — Anurati, by Emmeran
  Richard. Free for personal use; verify its terms before shipping it anywhere.
- Quicksand is pulled from Google Fonts at runtime (SIL Open Font License).

No wallpapers are distributed with this project.
