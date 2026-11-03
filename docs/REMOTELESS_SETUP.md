# Setup when the physical remote is missing

Investigated 3 November 2026. The goal is to turn on the TV and approve a new phone without
requiring the missing Samsung remote.

## Findings and evidence boundary

| Question | Finding | Evidence/status |
| --- | --- | --- |
| Can the existing Wi-Fi remote approve itself? | The client waits for Samsung authorization before sending remote keys. No alternative authorization interface was established here. | Source review of `TvClient`; not a TV-firmware finding |
| Can a new install always wake an off TV? | Network wake needs a learned MAC and a TV/network that supports standby wake. An undiscovered, sleeping TV cannot supply its details to this app. | Source review of discovery, info lookup and wake code |
| Can the TV's own button help? | It can turn on supported models and may offer navigation; some controllers have limited functions. | [Samsung controller guidance](https://www.samsung.com/au/support/tv-audio-video/control-tv-without-remote/) |
| Can a keyboard approve the prompt? | A wired USB keyboard is worth trying on supported models, but acceptance by the TV's system pairing prompt was not verified. | [Samsung keyboard/IME documentation](https://developer.samsung.com/smarttv/develop/guides/user-interaction/keyboardime.html); model-specific limit |
| Does SmartThings remove all initial interaction? | No universal conclusion. Some models offer a displayed-PIN setup flow, with other TV setup steps still shown. An already connected remote may help. | [Samsung SmartThings setup](https://www.samsung.com/ca/support/tv-audio-video/tv-set-up-with-smartthings/) |
| Can phone IR send physical-remote presses? | Android exposes consumer IR on equipped phones. This provides an independent control path that does not use the Wi-Fi pairing token. TV receipt/acceptance still needs hardware verification. | [Android ConsumerIrManager](https://developer.android.com/reference/android/hardware/ConsumerIrManager) |

REA was checked: there was no open target or analysis-provider binding. This workspace supplies
the Android app's source and builds, but no selected Samsung TV firmware image, pairing capture,
or live TV target. Accordingly, **no TV firmware was decompiled and no authorization bypass was
proven**. Inspecting this app's own APK would not establish what the TV permits. The investigation
continues through published primary documentation and source-level implementation evidence.

## Implemented

- A **No remote?** entry in connection/setup help, including while waiting for Allow.
- A scrollable guide covering the TV controller, qualified USB keyboard support, existing
  authorized controllers and replacement-remotes as a fallback.
- Optional infrared setup buttons: Power, Up, Down, Left, Right and OK. They appear only when
  a supported built-in emitter is detected. Normal remote buttons continue to use Wi-Fi.
- When no wake MAC has been learned, the main Power button can use the detected IR emitter.
  Phones without supported IR get a useful TV-button/setup message instead.
- The IR transmission runs off the UI thread, sends one short frame per tap, and does not queue
  overlapping transmissions. It does not change Wi-Fi authorization or claim the TV responded.
- The IR feature is explicitly optional in the Android manifest, keeping non-IR phones eligible
  to install the app. Its transmit permission is declared; no microphone/Bluetooth permission
  or Samsung account is added for this feature.

Infrared needs line of sight and actual emitter hardware. It cannot be added through software.
The initial profile is experimental Samsung32, not a claim that every Tizen model or accessory
understands these signals. Power is a toggle, not a verified power-state command.

## Signal references and implementation

The implementation is new Kotlin code driven by protocol definitions, not a bundled Arduino or
LIRC library. It emits a 38 kHz Samsung32 frame with a 560 microsecond unit, an 8-unit header,
32 on-air bits and a trailing mark. The mapping uses the captured Samsung BN59-00865A profile.
The capture's **ENTER-OK** is used for selection; its differently labelled RETURN code is not.

- [Samsung32 timing and encoding reference](https://github.com/Arduino-IRremote/Arduino-IRremote/blob/master/src/ir_Samsung.hpp)
- [Original remote capture and command mapping](https://github.com/probonopd/lirc-remotes/blob/master/remotes/samsung/BN59-00865A.lircd.conf)

No service-menu codes, factory-reset sequences, token guessing, or fake network authorization
are implemented. Approving the TV remains an explicit user action through an available control.

## Verification

Tests exercise no emitter, unsupported carriers, unknown commands, emitter failures, transmission
length, the 38 kHz carrier, and independent decoding of all six waveforms against the captured
command values. Both app variants include the common helper.

These tests prove software behavior and waveform construction, not transmission from a physical
phone or receipt by a TV. First-time Wi-Fi approval through IR, USB keyboard, TV controller and
SmartThings remains model-specific and unverified on hardware in this investigation.

For phones without IR and TVs whose own button/keyboard cannot select Allow, this app has no
established software-only first-approval solution. Model-specific TV-side investigation would
require an explicitly selected firmware artifact or recorded pairing exchange.
