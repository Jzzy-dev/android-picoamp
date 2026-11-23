# PicoAmp for Android (Google TV, phones and tablets)

[PICOAMP](https://github.com/Jzzy-dev/msx-picoplay-picoamp), the MSX2 music and internet radio player
for the PicoVerse 2350 cartridge, on a Chromecast with Google TV (any Google TV / Android TV), and on
Android phones and tablets. It is [PicoAmp for the web](https://github.com/Jzzy-dev/web-picoamp) in an
app: the same program (C compiled to WebAssembly) that draws every page cell for cell as on the MSX,
with what an app can do and a page cannot.

## What the app adds to PicoAmp for the web

* **The TV's remote.** On Google TV the remote works PicoAmp:
  * on the player a white **frame** shows where the arrows are: **Up/Down** move it between the
    remote's own row, the seek bar, the picture (spectrum, scope, cover), the list and the control
    row, **OK** goes in, **Back** comes out:
    * the **remote's own row** (Up at the top): **Menu**, **Help**, **Sound** (full, or light: the
      stations without Web Audio, no spectrum or equalizer, for a TV too slow for it) and **Exit**;
      Left/Right choose, OK does it;
    * the **seek bar**: Left/Right go 10 s back or on (a track; a station cannot be wound);
    * the **picture**: Left/Right switch spectrum, scope and cover; OK on the cover shows it big
      (full screen; on spectrum or scope: the full screen pictures);
    * the **list**: the arrows and OK as ever (choose, open, play); Back goes up the folders;
      **OK held** on a station adds it to the favourites (in the favourites: takes it out);
    * the **control row**: Left/Right choose previous, 10 s back, play, pause, stop, 10 s on, next,
      shuffle, repeat, EQ or the volume, and OK does it. On EQ the arrows set the equalizer (OK or
      Back closes it); on the volume Left/Right set the **TV's volume** (OK or Back: done);
  * **OK held** elsewhere (or a remote's menu key, or Menu in the top row) opens the **menu**:
    Internet radio, Sonos, Search stations, Clock, Weather, News, Markets, Pictures and the help.
    The arrows choose, OK opens, Back closes it;
  * on the other pages the arrows and OK are their keys, Back goes back, and their key rows can be
    chosen word by word (a frame; Left/Right, OK; Up or Back leaves the row):
    * the **clock**: Down; place and alarm open the TV's on-screen keyboard;
    * the **markets**: Left held (1 Europe, 2 Americas, 3 Asia-Pacific, C, W, T, $/ESC); Right held
      the index's members, Up held the prices now, Down held the order. On a chart: Down held frames
      its headlines (OK: its news), Down again the key row;
    * the **news**: Down held. In My feeds OK held on the right adds the feed, on the left removes it;
  * **Back** with the frame out of everything (and the list at its top) leaves the app (the music
    stops with it; Home leaves it playing);
  * **play/pause, next, previous, fast forward and rewind** keys, where a remote has them, and
    channel up/down as next and previous.
* **The volume is the TV's.** PicoAmp plays at full and its volume is the device's: the TV's volume
  keys show on the player, and the player's volume sets it (a Chromecast whose volume is the TV's,
  by HDMI-CEC: up and down there).
* **Sharp covers.** The covers are drawn in their own resolution over the MSX's, on the player and
  full screen.
* **No relay needed:** the stations' song titles, the markets and the news come straight from their
  sites (a page needs the relay for them; an app does not).
* **Sonos without a PC:** the Sonos entry finds the rooms of the house itself and is their screen and
  remote (song, time, cover, play/pause, next, previous, seeking, shuffle, repeat, volume and mute),
  as PicoAmp for Windows does for the web.
* **http stations** play too.
* The screen stays on while PicoAmp shows (PicoAmp has its own screensaver: the pictures).

On a phone or a tablet it is PicoAmp for the web as it is (touch: tap and the wheel's place on the
volume as the mouse); a layout made for touch is to come.

Not yet: picking a music folder or a pictures folder (the radio, Sonos and the rest work), and the
music playing on after the app is closed with Back.

## Install it (sideload)

1. The newest release's APK is always at
   **`https://github.com/Jzzy-dev/android-picoamp/releases/latest/download/PicoAmp.apk`**
   (every build of a push is also under the **Actions** tab → *Artifacts*, a zip, for a GitHub login).
2. **Google TV:** Settings → System → About → click *Android TV OS build* seven times (you are a
   developer now). Then either:
   * install the *Downloader* app (by AFTVnews) from the Play Store, allow it under Settings → Apps →
     Security & restrictions → Unknown sources, and type the address above in it: it downloads and
     installs the APK; or
   * with `adb`: Settings → System → Developer options → USB debugging (and *Wireless debugging* on
     newer ones), then on a PC `adb connect <the TV's address>` and `adb install -r PicoAmp-NN.apk`.
3. **A phone or tablet:** open the APK on it and allow the installation from that app.

PicoAmp then shows in the apps row of Google TV (with its banner) and in the phone's app list.
A newer APK installs over the older one and keeps the settings and lists (every build is signed
with the same key, `keystore/sideload.jks`, for sideloading only; a Play Store release would get its
own key).

## Building

GitHub Actions builds the APK on every push (`.github/workflows/build.yml`): it takes PicoAmp for the
web (`WEB_REF`, a branch of web-picoamp), builds its WebAssembly with wasi-sdk, copies its `web/` into
the app's assets and runs Gradle. By hand:

```sh
git clone https://github.com/Jzzy-dev/web-picoamp ../web-picoamp
make -C ../web-picoamp WASI_SDK=/path/to/wasi-sdk
mkdir -p app/src/main/assets/web && cp -r ../web-picoamp/web/. app/src/main/assets/web/
gradle assembleRelease        # Android SDK 35, JDK 17 -> app/build/outputs/apk/release/app-release.apk
```

| File | What |
|------|------|
| `MainActivity.kt` | The WebView with the page from the app's assets, the remote's keys (`picoamp.tvKey(...)` and `picoamp.tv(...)` in the page), the volume |
| `Relay.kt` | `/relay/?url=`: the page's relay in the app |
| `SonosBridge.kt` | `/sonos/...`: the Sonos bridge in the app (as PicoAmp for Windows has it) |

## Credits and licence

By **JZZY & Claude**, 2026, after PICOAMP for the MSX PicoVerse 2350 by **Cristiano Goncalves (The Retro
Hacker)**. Licensed as the PicoVerse project: Creative Commons Attribution-NonCommercial-ShareAlike 4.0
(see PicoAmp for the web).
