# Samsung TV Remote

A simple, phone-based remote for Samsung Tizen smart TVs, built so that anyone (including
parents who just want to watch something) can use it without hunting through menus.

<p>
  <img src="docs/remote.png" width="220" alt="Main remote screen">
  <img src="docs/youtube-search.png" width="220" alt="YouTube search with a video playing on the TV">
  <img src="docs/more-buttons.png" width="220" alt="More buttons sheet">
</p>

## Features

- **Search YouTube on the phone, play on the TV.** Type or speak, pick a video from the results,
  and it starts on the TV - no on-screen letter grid. Works with
  [TizenTube](https://github.com/reisxd/TizenTube) (ad-free YouTube via TizenBrew).
- **Voice search** through the phone's speech recognition.
- **Full remote**: D-pad with OK, Back, Home, Mute, Volume, Play/Pause, plus Source, Menu, Info,
  Exit, Channel, Rewind and Forward in a "More" sheet. Arrows, volume and channel repeat while held.
- **Phone volume buttons** control the TV volume while the app is open.
- **Now playing**: see what YouTube is playing on the TV, pause it and seek from the phone.
- **Favourites and recent searches**, plus *Play next* / *Add to queue* on any result.
- **Volume level** shown as a number (read from the TV).
- **Works with the app closed**: buttons in the notification bar, a Quick Settings tile and a
  home-screen widget.
- **Finds the TV automatically** on Wi-Fi (no IP address needed), with manual entry as a fallback.
- **No remote? setup guide**, with experimental Power/arrows/OK infrared controls on phones
  that have a supported built-in IR emitter.
- **Recovers changed TV addresses**, matching the saved device ID or MAC instead of a TV name.
- **Routes TV controls over Wi-Fi**, even when mobile data is the phone's default connection.
- **Power** off over the network. Power on uses Wake-on-LAN (needs *Power On with Mobile* on the
  TV; not yet verified over Wi-Fi standby).
- **YouTube shortcut** that opens YouTube on the TV.
- Large labelled buttons, dark calm design, no vibration.

Tested on a 2022 Samsung UA75BU8000 (Tizen 6.5) with a Galaxy S9+ (Android 10).

## Two versions

| | **Google Play** ("Remote for Samsung TV") | **GitHub** ("TV Remote") |
|---|---|---|
| Remote, apps, volume, widget, notification | ✓ | ✓ |
| YouTube search | Opens the official YouTube app (cast from there) | In the app, plays straight on the TV |
| Now playing, favourites, queue | - | ✓ |
| TizenBrew / TizenTube support | - | ✓ |

The GitHub version uses YouTube's unofficial web and TV-link APIs. The Play version leaves
that experimental integration out entirely (separate `play` / `full` source sets). This split
does not itself guarantee store approval; the final app still needs policy review and testing.

Release status, device test gates, and Play Console requirements are tracked in
[Release readiness](docs/RELEASE_READINESS.md).
The generation-by-generation support assessment and known protocol exceptions are in
[TV compatibility](docs/COMPATIBILITY.md).

## Install

Download the APK from [Releases](../../releases) and install it, or build it yourself:

```bash
cd android
./gradlew assembleFullRelease      # GitHub version: app/build/outputs/apk/full/release/
./gradlew bundlePlayRelease        # Play version (.aab); signing key from keystore.properties
```

## First-time setup

1. Phone and TV on the same Wi-Fi. Open the app; it finds the TV by itself.
2. The TV asks to **Allow** the new remote. Use an available local control to accept it. If your
   remote is missing, tap **No remote?** for TV-button/keyboard guidance and optional phone IR.
3. For YouTube search: tap **Search YouTube**, then on the TV open YouTube →
   **Settings → Link with TV code** and type the 12 numbers into the app. This is done once per
   phone (for example by whoever sets the phone up); the link is kept from then on.

The YouTube link and the Samsung remote pairing are separate: linking YouTube does not
authorize remote buttons. If remote pairing fails, follow the connection help shown above
the buttons. The app can recover a changed address after learning the TV's device ID or MAC.
Use **Find my TV** if automatic recovery cannot locate it. For a denied request,
allow this phone in the TV's device connection settings; menu names vary by model. Guest
Wi-Fi, router device isolation, and VPNs can prevent a connection even on the same Wi-Fi name.
Manual address entry is available under **Settings → Enter address manually (advanced)**.
See [network recovery](docs/NETWORK_RECOVERY.md) for matching, routing, and VPN limitations.

If YouTube isn't open on the TV when a video is picked, the app tries to start TizenBrew, enter
TizenTube from its module list (Down, then OK) and play the video. This is **not reliable yet**:
TizenBrew restarts itself while opening, and its autolaunch setting only sometimes takes
effect, so the key presses can miss. If YouTube was fully closed, open it once with the normal
remote; after that, picking videos on the phone works every time.

## How it works

Everything is local network traffic to the TV, except YouTube search and playback, which go
through YouTube's own servers exactly like the official YouTube phone app.

| Feature | Mechanism |
|---|---|
| Buttons | WebSocket `wss://TV:8002/api/v2/channels/samsung.remote.control` sending `ms.remote.control` key events. First connection shows an Allow prompt; the returned token is stored and reused. |
| TV certificate | Self-signed on port 8002; the app pins it on first use and rejects changes. |
| Open apps | `POST http://TV:8001/api/v2/applications/<appId>` (the WebSocket `ed.apps.launch` event is ignored by recent firmware). |
| Discovery | SSDP searches for DIAL, Samsung remote and generic services, confirmed through `http://TV:8001/api/v2/`. |
| Power on | Wake-on-LAN to the TV's Wi-Fi MAC (enable *Power On with Mobile* on the TV). |
| YouTube search | YouTube's InnerTube search API (what youtube.com uses), no API key. |
| Play on TV | YouTube "lounge" API - the *Link with TV code* channel - sending `setPlaylist`. Ported from [pyytlounge](https://github.com/FabioGNR/pyytlounge) (MIT). |

Why not type into the TV's search box? YouTube on the TV uses its own letter grid instead of the
TV keyboard, so text sent to the TV's keyboard never reaches it. Searching on the phone and
sending the chosen video avoids the grid entirely.

## Laptop remote (for testing)

`tvremote.py` is a small Python client for the same protocol (early prototype, lightly tested):

```bash
pip install websocket-client
TV_IP=192.168.1.50 python3 tvremote.py pair    # accept the prompt on the TV
TV_IP=192.168.1.50 python3 tvremote.py         # interactive: arrows, enter, backspace=back, h=home, +/-=volume
TV_IP=192.168.1.50 python3 tvremote.py key KEY_VOLUP
```

## Limitations

- **Same Wi-Fi.** The phone and the TV must be on the same home network for every button. Mobile
  data or another Wi-Fi won't reach the TV.
- **First connection needs TV approval.** The physical remote is one option; suitable TV buttons,
  a supported keyboard or the optional IR helper may work. Some TVs offer only limited local
  controls. Approval and IR compatibility are model-specific; see [remote-free setup](docs/REMOTELESS_SETUP.md).
- **YouTube search needs internet.** In the GitHub version it also needs the one-time *Link with
  TV code*, and results come from YouTube's unofficial web API, which YouTube can change; if search
  breaks, an app update is needed. The Play version hands searches to the YouTube app instead.
- **Target TVs:** compatible Samsung Tizen smart TVs, typically 2016 onwards. Support depends
  on firmware and an available local remote API; every model is not verified. Older models use
  a different protocol and are not supported.
- **Early Tizen exceptions:** some TVs need encrypted PIN pairing, including certain 2016
  models. That protocol is not implemented; Tizen alone does not guarantee compatibility.
- **Typing into other TV apps isn't supported.** Most TV apps (such as YouTube) use their own
  on-screen letter grids, which a remote's text input can't reach.
- **Turning the TV on** from the phone needs *Power On with Mobile* and may not work from deep
  standby over Wi-Fi. Turning it off always works.
- **Volume** follows the TV's speakers; it may not reflect a soundbar connected over HDMI ARC.
- **Notification bar buttons** can be swiped away on Android 14+; turn them back on in Settings.
- **Starting YouTube from a fully closed TizenBrew** is unreliable (see First-time setup).
- **Firmware updates** on the TV can change these interfaces. Tested on one TV and firmware only.
- **Disabling Samsung components** such as `com.samsung.tv.remoteapp*` or
  `com.samsung.tizen.smartthings-thing` (e.g. while debloating) breaks the remote API.

## Battery

Nothing runs in the background. The connection to the TV is open only while the app is on
screen; widget, notification and tile buttons connect for a moment, send one key and
disconnect. When the TV is off, reconnect attempts back off from 3 s to once a minute, and
"now playing" stops as soon as the app leaves the screen.

## Debloating tip

If you remove Samsung bloat from the TV, keep `com.samsung.tv.remoteapp*` and
`com.samsung.tizen.smartthings-thing` enabled - they provide the network remote API this app uses.

## License

MIT
