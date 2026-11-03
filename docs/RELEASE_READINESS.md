# Release readiness

## Product and support boundary

Keep the MIT license and both build flavors public. Google Play distribution does not require
closing the source. The Play flavor hands YouTube search to the official phone app; the full
flavor includes experimental integrations with unofficial YouTube services. Neither flavor
needs TizenBrew for ordinary remote buttons.

Target compatible Samsung **Tizen TVs exposing the local remote API**, typically 2016 onward.
Do not promise every Samsung TV. Pre-Tizen televisions, hospitality/commercial restrictions,
firmware differences, and disabled Samsung services require separate investigation. The README
records the tested TV (UA75BU8000, Tizen 6.5). This revision was tested on it on 3 November 2026
with a Galaxy S21 (Android 15); the parents' A56 (Android 16) still needs a quick check.
See [the compatibility audit](COMPATIBILITY.md) for all 2015–2026 Tizen generations, protocol
exceptions, and feature-specific limits. Some early Tizen TVs require encrypted PIN pairing,
which this app does not implement.

## First use

1. Turn on the TV and join the same home network on the phone.
2. Open the app. It searches automatically; choose a TV if several respond.
3. Choose **Allow** on the TV using its physical remote.

No Samsung login, developer mode, TizenBrew installation, or IP entry should be required for
the core remote. Manual addresses remain an advanced fallback. Notification controls are opt-in.
YouTube TV-code linking in the full build is independent of Samsung remote authorization.

## Changes made after the connection review

- Search DIAL, Samsung remote, and generic SSDP targets; hold a multicast lock while searching.
- Check responders concurrently with bounded HTTP timeouts, rather than serially waiting for
  every device on a busy network. This still depends on routers allowing device discovery.
- Ignore callbacks from cancelled sockets; deliver socket events on the main thread; discard
  obsolete state notifications so switching TV or resuming cannot receive an old disconnect.
- Encode query parameters; retry an invalid secure pairing token once without the token.
- Bound pairing waits, stop automatic retries after denial/timeouts/certificate changes, and
  show actionable errors. Do not silently erase certificate pins or bypass certificate changes.
- Bind certificate verification to the connection's TV address, avoiding a mutable-host race.
- Reject malformed manual addresses and prevent keys from being queued before authorization.
- Bound REST calls independently from the long-lived remote WebSocket.
- Prevent stale device/app responses from updating another selected TV.
- Disable Android backup for stored pairing data. Play releases no longer fall back to a debug
  signing key when the upload key is absent (they remain unsigned).

These address code defects and plausible causes. They do not establish the cause of a particular
physical TV failure without reproducing it and observing its authorization response.

## Verification commands

From `android/`:

```sh
./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug :app:assembleFullDebug \
  :app:lintPlayDebug :app:lintFullDebug :app:bundlePlayRelease
```

Regression tests use local simulated TV WebSockets and cover authorization, token delivery,
denial, stale socket callbacks, timeout, expired secure-token recovery, and invalid manual IPs.
They do not replace router/TV tests. Ports 8001 and 8002 must be free on the test machine.

Build outputs are under `android/app/build/outputs/`. Upload only a release bundle signed with
your upload key, keep that key private, and enroll in Play App Signing. Never commit
`keystore.properties` or a keystore. Increase `versionCode` before uploading a subsequent release.

## Physical test gates before publishing

Record TV model, firmware, phone model, Android version, network conditions, result, and error
message for each run. Do not include tokens, MAC addresses, or home IPs in public bug reports.

Emulator results (3 November 2026) used a local simulated TV on Android 16 and Android 8;
they check app behavior, not TV firmware.

| Scenario | Required outcome | Status for this revision |
| --- | --- | --- |
| Fresh install, one modern TV | Discover → Allow → volume/Home works without typing | Real TV + S21 passed for both builds: found by discovery, Allow, keys, app launch |
| Older Tizen TV with port 8001 only | Secure connection fails → plain remote succeeds | Needs device test |
| Deny, then allow through TV device settings | No repeated prompts; explicit retry works | Emulator + simulated TV: one attempt, guidance shown, retry works; real TV pending |
| Stored token invalidated by TV | One new pairing attempt, then usable controls | Needs device test |
| TV off / guest Wi-Fi / VPN / isolation | Useful guidance; no indefinite pairing wait | Emulator: TV off, silent TV (45 s timeout) and auto-reconnect passed; guest Wi-Fi/VPN pending |
| Several TVs | Choose intended TV; no state from previous TV | Needs device test |
| Router changes the TV's address | Saved identity is rediscovered and pairing retained | Simulated; the real TV moved from .176 to .175 and discovery found it |
| Reopen app, rotate, switch apps rapidly | No old socket changing new connection state | Emulator: rotate and reopen passed; rapid switching pending |
| Wi-Fi standby / deep standby | Document whether Wake-on-LAN works for that model | Needs device test |
| Widget, notification, tile | Test with app closed and Android permission denied | Emulator passed, including a killed process and denied/blocked permission; S21 + real TV passed with the app closed, widget also with power saving on |
| Small screen, landscape, large font, TalkBack | Setup steps and all controls usable | Emulator (Android 8 small, Android 16): passed by scrolling; TalkBack pending |
| Play YouTube search | Official app/browser opens; no promise of automatic casting | Emulator: YouTube app opens on the results; S21 without the app: browser opens and the sheet says so |
| Full build YouTube link, search, play | Code links once; video plays and pauses on the TV | Real TV + S21 passed |

Automatic address recovery using a saved identity and binding TV traffic to a local network are
implemented; see [network recovery](NETWORK_RECOVERY.md). Physical router/VPN checks remain.
Discovery on networks blocking multicast remains follow-up work. Pairing credentials are never
migrated based only on a matching friendly name.

## Play Console gates

- The app targets API 36, meeting the currently published new-app target requirement as of
  2 November 2026. [Google's target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878).
- Complete your developer account setup, store listing, App access instructions, content rating,
  target audience, ads declaration, and Data safety from the actual release build. The listing's
  Data safety notes are a draft, not a completed declaration. Review speech recognition and
  external YouTube handoff carefully; do not mark all traffic encrypted (some TV traffic is HTTP).
- Publish an accessible privacy policy and link it both in the store and inside the app. Verify
  the contact address and repo URL. Explain optional voice and notification behavior accurately.
- Use screenshots of the Play build, without full-build YouTube features. State independence
  from Samsung and the limited compatibility scope. Do not claim universal support or guaranteed
  wake from standby.
- Run an internal test and Play's pre-launch report; investigate crashes, accessibility issues,
  and native-library/page-size compatibility reported for the final bundle.
- New personal accounts subject to Google's testing rule need at least 12 testers opted in
  continuously for 14 days before applying for production access.
  [Google's testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).
- Before raising targetSdk to 37, implement local-network permission handling throughout setup,
  discovery, reconnect, widgets, and notifications. API 36 does not currently require that new
  permission in normal operation. [Android local-network protections](https://developer.android.com/privacy-and-security/local-network-permission).

The Play/full split is a conservative product decision, not a guarantee of policy approval.
Review YouTube terms and Play policies before including unofficial APIs in any store release.
