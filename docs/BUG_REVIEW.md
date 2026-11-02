# Bug review and fixes

This review uses source inspection, Robolectric, and local simulated TV/YouTube servers. No
physical TV or live YouTube service is needed for these regressions. The earlier compatibility
audit remains separate: passing simulated protocols does not certify TV firmware.

## Fixed issues

| Failure | Fix | Verification |
| --- | --- | --- |
| Discovery could finish after manual TV selection and replace it | Cancel and release discovery ownership when selecting a TV | Activity regression exercises selection with pending discovery |
| Previous TV's installed-app cache remained active after switching | Reload the selected TV's cache; cancel old detection and use a fixed host for each scan | Activity regression verifies cache and visible shortcuts |
| Old volume requests could update another TV or run after pause | Snapshot the selected host, cancel superseded work and pending refresh callbacks; clear disconnected volume | Code review and both builds; simulated HTTP failures are tested |
| Failed volume/mute HTTP responses could be displayed as legitimate data | Require successful responses, a 0–100 volume, and a valid mute value | Simulated UPnP failures |
| App launch failures disappeared silently | Use bounded REST calls that report HTTP/network errors; keep calls off the main thread | Simulated failed launch |
| Old searches/play operations could overwrite newer work | Cancel superseded jobs and preserve coroutine cancellation instead of converting it to a network error | Cancellation regression; activity paths compile in both variants |
| Playback startup could continue sending keys after leaving the app | Cancel on pause, replace blocking sleeps with cancellable delays, and require the foreground remote for startup keys | Code review and both builds |
| A YouTube link for one TV could remain active after choosing a different TV | Unlink YouTube when selecting another address | Code review; re-link is intentionally required for a different selected address |
| Late playback callbacks could restore cleared now-playing state | Version watcher callbacks and ignore callbacks after stop/unlink/pause | Watcher lifecycle regressions plus source guards |
| Stopped/replaced watchers could resume through a shared boolean | Give each watcher its own cancellation signal and owned HTTP call | Stalled-command cancellation and replacement-watcher regressions |
| Failed or immediately closed polls could retry without backoff | Back off on unsuccessful polls and EOF; stop interrupts the backoff | Simulated failed poll and prompt-stop regression |
| Line-based playback parsing could cross frame boundaries | Read exactly the declared UTF-16 length, bound frame sizes, and reject malformed/truncated payloads | Adjacent frames, Unicode, multiline payloads and malformed frame tests |
| Missing session fields could leave a partially initialized session reusable | Parse into locals; commit only a complete session | Incomplete-session recovery regression |
| Missing link/token fields could crash the watcher or leave a half-linked TV | Validate responses and report an IOException without committing partial pairing data | Malformed pairing and token-refresh regressions |
| An in-flight pairing response could restore a link after unlink | Guard link and session commits with a revision and a short state lock | Delayed pairing/unlink regression |
| Partial state updates reset duration/position; empty now-playing could revive the previous video | Preserve previous timing for the same video and clear the old playback identity when empty | Streamed playback-event regression |
| Non-finite playback positions could enter UI/seek commands | Reject invalid seeks; sanitize incoming timing values | Invalid-seek regression and parser guards |
| Cached plain transport could strand a TV now offering secure remote | Try each transport at most once for connection refusal/timeout; retain certificate-failure protection | Plain-to-secure and secure-to-plain regressions |
| Background button failures/interruption could skip disconnect or crash a receiver thread | Disconnect in finally and contain worker errors; do not initiate a new secure pairing without a token | Connection policy regression and source review |
| Connection state mutated from both worker and main threads | Dispatch connect/disconnect to the main thread; publish socket/state safely | Existing connection, cancellation and stale-callback regressions |
| Invalid saved addresses could throw during URL construction | Validate saved inputs, trim addresses, reject ambiguous leading-zero octets | Address regressions |
| Malformed MAC addresses produced invalid wake packets with apparent success | Validate six MAC bytes before sending; show a useful error for invalid saved data | Magic-packet wire-format regression |

## Checks

From `android/`:

```sh
./gradlew --offline --max-workers=2 \
  :app:testPlayDebugUnitTest :app:testFullDebugUnitTest \
  :app:assemblePlayDebug :app:assembleFullDebug \
  :app:lintPlayDebug :app:lintFullDebug
```

Shared regressions run in both flavors. Full-only tests cover the YouTube implementation.
The local TV simulators require loopback ports 8001, 8002 and 9197 to be free. Other HTTP
simulations use automatically assigned ports. No requests go to a physical TV or to YouTube.

## Remaining limits

- Automatic address recovery and local-network routing have since been implemented and tested
  in simulation; see [network recovery](NETWORK_RECOVERY.md). Recovery needs a saved device ID
  or valid MAC, an unambiguous discovery match, and a reachable local network. VPN lockdown can
  still block access. The independent YouTube link is retained for a recognized address change.
- Some router/device combinations block multicast. Discovery still requires an SSDP response
  and the HTTP device-info endpoint. It does not scan every address on a user's network.
- A command already sent over the network cannot be recalled by cancelling its coroutine.
  Cancellation prevents subsequent startup delays, retries, and UI writes; finite network
  timeouts bound any in-flight REST request.
- App probing can still take time on an unresponsive TV. Cancelling detection prevents further
  probes after the current bounded request finishes; it does not prove an app is uninstalled.
- Startup timing for TizenBrew, firmware-specific app launching, standby wake, and TV-specific
  authorization behavior remain unverified. Encrypted PIN pairing remains unimplemented.
- The final Play release bundle must be rebuilt from these changes and pass release checks
  before upload. This review produces debug builds and automated verification, not a publication.

See [compatibility](COMPATIBILITY.md) and [release readiness](RELEASE_READINESS.md).
