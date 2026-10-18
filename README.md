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
- **Finds the TV automatically** on Wi-Fi (no IP address needed), with manual entry as a fallback.
- **Power**: off over the network, on via Wake-on-LAN.
- **App shortcuts** for YouTube (TizenBrew) and Hotstar.
- Large labelled buttons, dark calm design, no vibration.

Tested on a 2022 Samsung UA75BU8000 (Tizen 6.5) with a Galaxy S9+ (Android 10).

## Install

Download the APK from [Releases](../../releases) and install it, or build it yourself:

```bash
cd android
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## First-time setup

1. Phone and TV on the same Wi-Fi. Open the app; it finds the TV by itself.
2. The TV asks to **Allow** the new remote - accept with the normal remote (one time).
3. For YouTube search: tap **Search YouTube**, then on the TV open YouTube →
   **Settings → Link with TV code** and type the 12 numbers into the app (one time).

## How it works

Everything is local network traffic to the TV, except YouTube search and playback, which go
through YouTube's own servers exactly like the official YouTube phone app.

| Feature | Mechanism |
|---|---|
| Buttons | WebSocket `wss://TV:8002/api/v2/channels/samsung.remote.control` sending `ms.remote.control` key events. First connection shows an Allow prompt; the returned token is stored and reused. |
| TV certificate | Self-signed on port 8002; the app pins it on first use and rejects changes. |
| Open apps | `POST http://TV:8001/api/v2/applications/<appId>` (the WebSocket `ed.apps.launch` event is ignored by recent firmware). |
| Discovery | SSDP search for the DIAL service, confirmed through `http://TV:8001/api/v2/`. |
| Power on | Wake-on-LAN to the TV's Wi-Fi MAC (enable *Power On with Mobile* on the TV). |
| YouTube search | YouTube's InnerTube search API (what youtube.com uses), no API key. |
| Play on TV | YouTube "lounge" API - the *Link with TV code* channel - sending `setPlaylist`. Ported from [pyytlounge](https://github.com/FabioGNR/pyytlounge) (MIT). |

Why not type into the TV's search box? YouTube on the TV uses its own letter grid instead of the
TV keyboard, so text sent to the TV's keyboard never reaches it. Searching on the phone and
sending the chosen video avoids the grid entirely.

## Laptop remote (for testing)

`tvremote.py` is a small Python client for the same protocol:

```bash
pip install websocket-client
TV_IP=192.168.1.50 python3 tvremote.py pair    # accept the prompt on the TV
TV_IP=192.168.1.50 python3 tvremote.py         # interactive: arrows, enter, backspace=back, h=home, +/-=volume
TV_IP=192.168.1.50 python3 tvremote.py key KEY_VOLUP
```

## Debloating tip

If you remove Samsung bloat from the TV, keep `com.samsung.tv.remoteapp*` and
`com.samsung.tizen.smartthings-thing` enabled - they provide the network remote API this app uses.

## License

MIT
