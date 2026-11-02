# Samsung TV compatibility audit

Reviewed 2 November 2026. This is a source and protocol review, **not physical certification of
every TV**. Samsung's platform catalog is not a compatibility list for this Android app.

## Generation coverage

Samsung's published baseline OS mapping is below. Some 2023+ TVs can receive newer OS versions,
so record installed firmware as well as model year.
[Samsung general specifications](https://developer.samsung.com/smarttv/develop/specifications/general-specifications.html).

| Model year | Baseline Tizen | Assessment for this app's core buttons |
| --- | --- | --- |
| 2015 | 2.3 | Outside current support claim; encrypted PIN-pairing sets need another implementation |
| 2016 | 2.4 | Conditional: compatible WebSocket sets targeted; encrypted PIN-pairing exceptions excluded |
| 2017 | 3.0 | WebSocket candidate; verify both transport and authorization on physical hardware |
| 2018 | 4.0 | WebSocket candidate; verify firmware and token behavior |
| 2019 | 5.0 | WebSocket candidate; verify firmware and token behavior |
| 2020 | 5.5 | WebSocket candidate; verify firmware and token behavior |
| 2021 | 6.0 | WebSocket candidate; verify firmware and token behavior |
| 2022 | 6.5 | WebSocket candidate; README records prior testing on UA75BU8000 only |
| 2023 | 7.0 | WebSocket candidate; test both original and upgraded firmware |
| 2024 | 8.0 | WebSocket candidate; test original/upgraded firmware and model-specific functions |
| 2025 | 9.0 | WebSocket candidate; no physical verification recorded here |
| 2026 | 10.0 | WebSocket candidate; no physical verification recorded here |

The candidate assessment is an inference from the app's protocol implementation and maintained
third-party implementations, not a Samsung guarantee. Current Home Assistant documentation
targets Tizen 2016+ with REST/WebSocket support.
[Home Assistant supported devices](https://www.home-assistant.io/integrations/samsungtv/#supported-devices).

For complete product lineups, including regional model groups, use
[Samsung's model catalog](https://developer.samsung.com/smarttv/develop/specifications/tv-model-groups.html).
It includes ordinary UHD/Crystal models, QLED/Neo QLED, OLED, lifestyle TVs, monitors, projectors
and licensed Tizen groups. Catalog presence does not establish that the local remote endpoint
is available. Do not add these products to an app-supported list solely from their OS.

## Protocol boundary

| TV's available interface | Current app | Evidence/check |
| --- | --- | --- |
| Secure remote WebSocket on 8002 | Implemented | Authorization event, saved token, first-use certificate pin |
| Plain remote WebSocket on 8001 | Implemented | Fallback after secure connection refusal/timeout; no token in URL |
| Encrypted v1 / PIN pairing | **Not implemented** | Separate protocol, not solved by trying plain port 8001 |
| Legacy remote on 55000 | **Not implemented** | Separate pre-Tizen protocol |
| SmartThings cloud control | **Not implemented** | Different integration with account/cloud requirements |

The maintained `samsungtvws` implementation distinguishes secure/plain WebSockets from its
encrypted and legacy transports.
[Protocol implementation](https://github.com/xchwarze/samsung-tv-ws-api/blob/master/samsungtvws/connection.py),
[library support scope](https://github.com/xchwarze/samsung-tv-ws-api#supported-tvs).

Home Assistant's current bridge explicitly handles H/J encrypted pairing and identifies some
2016 K-series sets as possible encrypted-pairing exceptions. This is why a blanket
“all Samsung TVs from 2016” claim would be inaccurate. Do not infer an old protocol from a lone
H/J/K letter: model naming patterns vary, and newer generations reuse letters.
[Home Assistant protocol selection](https://github.com/home-assistant/core/blob/dev/homeassistant/components/samsungtv/bridge.py).

## Features must be assessed separately

| Feature | What the code supports | Remaining restriction |
| --- | --- | --- |
| Discovery | SSDP responders confirmed through HTTP 8001 device info reporting Tizen | Requires multicast and that HTTP endpoint; secure-only info discovery is not implemented |
| Basic keys | Samsung v2 remote channel, secure/plain | A successful socket upgrade alone is insufficient; authorization and accepted commands are needed |
| App shortcuts | Known app IDs, checked through HTTP 8001 | IDs and REST launch behavior vary; not an exhaustive installed-app enumeration |
| Volume display | UPnP RenderingControl on port 9197 | That service/path and TV/soundbar behavior need separate verification |
| Power off | Power key | On Frame TVs the power key can interact with Art Mode; behavior not certified |
| Power on | Wake-on-LAN using a saved MAC | Standby, Ethernet/Wi-Fi, settings and firmware affect results |
| Frame Art Mode management | **Not implemented** | Ordinary keys do not provide art upload or Art Mode management |
| YouTube in Play build | Official phone app/browser handoff | Casting is handled separately by YouTube |
| YouTube in full build | Unofficial search/link plus TV app launch | Separate authorization; not evidence that Samsung remote keys work |
| Hospitality, signage, licensed Tizen, monitors/projectors | No app-specific verification recorded | Check local API individually before claiming support |

Known app ID alternatives are documented by the maintained library; Prime Video and Disney+
alternatives were added during this audit. The app still probes availability before displaying
shortcuts. This does not prove every region's app ID or launch behavior.
[Application ID reference](https://github.com/xchwarze/samsung-tv-ws-api/blob/master/APPLICATIONS.md).

The audit also added handling for an explicit unsupported `ms.remote.control` error, so the app
does not remain marked connected or automatically retry that incompatibility forever.

## Device verification record

Only one historical device is recorded by this repository: **2022 Samsung UA75BU8000, Tizen
6.5, Galaxy S9+ / Android 10**. This comes from the README, not a new test performed during this
audit. Current changes need testing on that TV too. No physical TVs were connected to this
workspace during the audit.

Automated tests exercise simulated protocol behavior, not model firmware. They cover secure
token recovery, plain fallback, authorization/denial, stale callbacks, timeout, changed
certificates, unsupported commands and manual address validation.

For each physical report, collect this record without tokens or network identifiers:

```text
TV model / region:
Installed firmware / Tizen version:
Phone / Android version / app version:
TV Ethernet or Wi-Fi:
Discovery / Allow prompt / reconnect:
D-pad / Home / volume / mute / playback:
App shortcuts / power off / standby power on:
Frame-specific power behavior, if applicable:
Result: passed / partial / failed
Visible error and reproduction steps:
```

Prioritize an early plain-WebSocket TV, a known encrypted K-series exception, the existing
BU8000, a modern OLED/Neo QLED, and a Frame. Then expand reports across the remaining generation
rows and regions. A passing model is evidence for that model/firmware, not its whole year.
See [release gates](RELEASE_READINESS.md) for network and Android lifecycle scenarios.

## Store wording

“For compatible Samsung Tizen smart TVs, typically 2016 or later, that support Samsung's local
remote interface. Compatibility and available features vary by model and firmware. Older TVs
requiring encrypted PIN pairing are not currently supported.”

Do not advertise “all Tizen TVs” or include monitors, projectors, hospitality/signage, licensed
Tizen products, Frame Art Mode management or guaranteed standby wake without verification.
