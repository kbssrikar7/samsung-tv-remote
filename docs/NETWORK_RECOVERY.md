# Automatic TV recovery and local-network routing

## User-visible behavior

After the app learns a TV's device ID or valid MAC, it can find that TV again when the router
changes its IP. A successful recovery keeps the pairing token, original certificate pin, cached
apps, wake address and independent YouTube link. Users do not need to type the new address.

The foreground app verifies device information before reusing saved credentials at an address.
If another TV occupies that address, it searches for the saved TV instead of pairing with the
replacement. If it cannot identify the saved TV, it keeps the selection and shows connection
help. Another discovered TV can still be selected explicitly.

TV REST, remote WebSocket, discovery and wake packets use the selected Wi-Fi network. Ethernet
on the phone is also accepted as a local-network route. Cellular alone is not selected for TV
controls. Wi-Fi does not need validated internet access for local controls to work.

The app listens for local-network changes only while resumed. A new Android Wi-Fi Network
causes the old socket to be closed and the selected TV to be checked again. Network observation
and foreground recovery are stopped when the activity pauses.

## Matching and pairing rules

- A Samsung device ID takes precedence when both the saved record and discovery provide one.
  Conflicting IDs cannot be matched just because their MACs or names agree.
- A valid, normalized MAC can match older saved records or device-info replies without IDs.
  Empty, unknown and placeholder identifiers do not establish a match.
- Multiple matching addresses are ambiguous: recovery does not pick the first one.
- A unique match moves the original TV's pairing data to its new address and removes the old
  address's credential entries. The original certificate pin remains the expected certificate.
  A changed certificate is still rejected; relocation does not reset trust.
- If REST information is missing, a known TV can be tried through its previously pinned secure
  socket. It cannot silently fall back to an unidentified plain remote socket.
- Plain WebSocket models have no certificate authentication. Their local protocol and device
  information remain dependent on a trusted home network.
- Deliberately selecting another TV clears the separate YouTube link. Recognizing the same TV
  at a new address keeps it. Forget pairing explicitly clears identity, token, pin and transport
  hints so replacement/reset TVs can be paired again.

## Routing design

Only local clients/sockets are bound; the process's default network is not changed. YouTube
search, images and playback services retain normal internet routing and certificate checks.
Local HTTP clients have a pool per selected network, use its DNS/socket factory, and avoid
system HTTP proxies. SSDP and Wake-on-LAN datagram sockets are bound to that same route.

Android documents that a Network socket factory binds its sockets to that network and that a
disconnected network's sockets stop working. That is why network changes create a new local
client/pool and reconnect the remote.
[Android Network API](https://developer.android.com/reference/android/net/Network#getSocketFactory()).

Automatic scans are limited to once per 30 seconds while normal reconnect attempts back off.
Manual Find my TV is still available. No subnet-wide IP scan is performed.

## Automated verification

Local simulations cover:

- moving a saved TV from one loopback address to another while preserving token and pin;
- replacing the Android Wi-Fi network and reconnecting the remote;
- refusing to pair with a different TV at the saved address;
- preserving YouTube pairing data during credential relocation;
- MAC normalization, conflicting identifiers, ambiguous matches and stale recovery results;
- selecting Wi-Fi when cellular or VPN is the default, accepting Ethernet, and rejecting mobile
  or VPN alone as the local route;
- separate connection pools, network socket factories, bound datagrams and observer cleanup;
- refusing an unidentified plain fallback.

Run both suites and build the debug apps with:

```sh
cd android
./gradlew --offline --max-workers=2 :app:testPlayDebugUnitTest :app:testFullDebugUnitTest \
  :app:assemblePlayDebug :app:assembleFullDebug :app:lintPlayDebug :app:lintFullDebug
```

## Limits

Recovery requires an identifier learned from a reachable TV. It cannot recognize an anonymous
device by name alone. It still needs SSDP to get through the router and the HTTP device-info
endpoint to confirm discoveries. Isolation, guest networks, multicast blocking and missing TV
services can prevent recovery.

VPNs can coexist with an accessible underlying local network, but VPN lockdown or policies
blocking local access cannot be bypassed by the app. Real phone/router/VPN combinations remain
untested; the automated results establish software behavior in simulation.

Background widget/notification buttons use the last recovered address and perform a bounded
identity check. They do not run a full discovery scan inside Android's short broadcast lifetime.
If the address changed while the app was closed, open the app to recover it automatically.

See [compatibility](COMPATIBILITY.md) and [release readiness](RELEASE_READINESS.md).
