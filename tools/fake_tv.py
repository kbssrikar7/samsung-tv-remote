"""Fake Samsung Tizen TV for testing the app on an emulator without a real TV.

Setup (needs aiohttp; ports 8001, 8002 and 9197 must be free, so stop it before unit tests):
    cd tools
    openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 30 -subj /CN=fake-tv
    MODE=allow ALLOW_DELAY=2 python3 fake_tv.py
In the emulator app: Settings -> Enter address manually -> 10.0.2.2 (the host machine).

8001: REST device info / app status / app launch, plus the plain remote WebSocket.
8002: TLS remote WebSocket with token pairing (self-signed cert, like a real TV).
9197: UPnP RenderingControl GetVolume/GetMute.

MODE=allow (default) issues a token after ALLOW_DELAY seconds; MODE=deny sends unauthorized;
MODE=ignore never answers (pairing timeout). Every event is printed as one line.
"""
import asyncio, json, os, ssl, sys
from aiohttp import web, WSMsgType

MODE = os.environ.get("MODE", "allow")
DELAY = float(os.environ.get("ALLOW_DELAY", "2"))
TOKEN = "12345678"
state = {"volume": 17, "muted": False}
HERE = os.path.dirname(os.path.abspath(__file__))


def log(*a):
    print(*a, flush=True)


async def info(_):
    log("INFO")
    return web.json_response({
        "id": "uuid:fake-tv-0001", "name": "[TV] Fake Samsung",
        "device": {"OS": "Tizen", "name": "[TV] Fake Samsung", "modelName": "UA75BU8000",
                   "id": "uuid:fake-tv-0001", "wifiMac": "aa:bb:cc:dd:ee:01",
                   "TokenAuthSupport": "true", "PowerState": "on"},
        "isSupport": "{\"remote_available\":\"true\"}", "type": "Samsung SmartTV",
    })


async def app_status(req):
    app = req.match_info["app"]
    installed = app in ("111299001912", "3201907018807", "3201910019365", "3201711015117", "3201606009684")
    log("APP_STATUS", app, installed)
    if not installed:
        return web.Response(status=404)
    names = {"111299001912": "YouTube", "3201907018807": "Netflix", "3201910019365": "Prime Video", "3201711015117": "JioHotstar", "3201606009684": "Spotify"}
    return web.json_response({"id": app, "name": names[app], "running": False, "visible": False})


async def app_launch(req):
    log("APP_LAUNCH", req.match_info["app"])
    return web.json_response({"ok": True})


async def remote(req, secure):
    ws = web.WebSocketResponse()
    await ws.prepare(req)
    token = req.query.get("token")
    log("WS_OPEN", "secure" if secure else "plain", "token=" + ("yes" if token else "no"))
    if secure and token == TOKEN:
        await ws.send_json({"event": "ms.channel.connect", "data": {"clients": []}})
    elif MODE == "deny":
        await asyncio.sleep(DELAY)
        await ws.send_json({"event": "ms.channel.unauthorized"})
        log("DENIED")
    elif MODE == "ignore":
        pass
    else:
        await asyncio.sleep(DELAY)
        data = {"clients": []}
        if secure:
            data["token"] = TOKEN
        await ws.send_json({"event": "ms.channel.connect", "data": data})
        log("ALLOWED")
    async for msg in ws:
        if msg.type == WSMsgType.TEXT:
            m = json.loads(msg.data)
            p = m.get("params", {})
            key = p.get("DataOfCmd")
            log("KEY", key, p.get("Cmd"))
            if key == "KEY_VOLUP":
                state["volume"] = min(100, state["volume"] + 1)
            elif key == "KEY_VOLDOWN":
                state["volume"] = max(0, state["volume"] - 1)
            elif key == "KEY_MUTE":
                state["muted"] = not state["muted"]
    log("WS_CLOSED")
    return ws


async def upnp(req):
    body = await req.text()
    if "GetVolume" in body:
        inner = f"<CurrentVolume>{state['volume']}</CurrentVolume>"
    elif "GetMute" in body:
        inner = f"<CurrentMute>{1 if state['muted'] else 0}</CurrentMute>"
    else:
        return web.Response(status=500)
    return web.Response(text=f'<?xml version="1.0"?><s:Envelope><s:Body>{inner}</s:Body></s:Envelope>',
                        content_type="text/xml")


def app(secure):
    a = web.Application()
    a.router.add_get("/api/v2/", info)
    a.router.add_get("/api/v2/applications/{app}", app_status)
    a.router.add_post("/api/v2/applications/{app}", app_launch)
    a.router.add_get("/api/v2/channels/samsung.remote.control", lambda r: remote(r, secure))
    return a


async def main():
    ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    ctx.load_cert_chain(os.path.join(HERE, "cert.pem"), os.path.join(HERE, "key.pem"))
    runners = []
    for a, port, sslctx in ((app(False), 8001, None), (app(True), 8002, ctx)):
        r = web.AppRunner(a); await r.setup(); runners.append(r)
        await web.TCPSite(r, "0.0.0.0", port, ssl_context=sslctx).start()
    u = web.Application(); u.router.add_post("/upnp/control/RenderingControl1", upnp)
    r = web.AppRunner(u); await r.setup(); await web.TCPSite(r, "0.0.0.0", 9197).start()
    log("READY mode=" + MODE)
    await asyncio.Event().wait()

asyncio.run(main())
