#!/usr/bin/env python3
"""Laptop remote for the Samsung UA75BU8000 (Tizen) over its local network API.

Protocol (Samsung "multiscreen" remote channel):
  keys/text/mouse : wss://TV:8002/api/v2/channels/samsung.remote.control?name=<b64>&token=<t>
  apps            : http://TV:8001/api/v2/applications/<appId>   (GET status / POST launch / DELETE close)
  power on        : Wake-on-LAN magic packet to the TV's MAC

Set the TV's address with TV_IP (and TV_MAC for wake), e.g. TV_IP=192.168.1.50 tvremote.py pair

Usage:
  tvremote.py pair               # first run - press Allow on the TV, saves token
  tvremote.py key KEY_VOLUP [..] # send one or more keys
  tvremote.py text "hello"       # type into a focused on-screen text field
  tvremote.py app <appId>        # launch an app (e.g. 111299001912 = YouTube)
  tvremote.py wake               # Wake-on-LAN
  tvremote.py info               # device info
  tvremote.py                    # interactive keyboard mode
"""
import base64
import json
import os
import socket
import ssl
import sys
import termios
import tty
import urllib.request
from pathlib import Path

import websocket

TV_IP = os.environ.get("TV_IP", "")
TV_MAC = os.environ.get("TV_MAC", "")
CLIENT_NAME = "LaptopRemote"  # must stay constant, the token is bound to it
TOKEN_FILE = Path(__file__).with_name("token.txt")


def _ws_url():
    name = base64.b64encode(CLIENT_NAME.encode()).decode()
    url = f"wss://{TV_IP}:8002/api/v2/channels/samsung.remote.control?name={name}"
    if TOKEN_FILE.exists():
        url += f"&token={TOKEN_FILE.read_text().strip()}"
    return url


def connect(timeout=30):
    # The TV uses a self-signed cert on 8002, so verification must be off.
    ws = websocket.create_connection(_ws_url(), timeout=timeout,
                                     sslopt={"cert_reqs": ssl.CERT_NONE, "check_hostname": False})
    msg = json.loads(ws.recv())
    if msg.get("event") != "ms.channel.connect":
        ws.close()
        raise RuntimeError(f"TV refused connection: {msg}")
    token = msg.get("data", {}).get("token")
    if token:
        TOKEN_FILE.write_text(token)
    return ws


def _send(ws, params):
    ws.send(json.dumps({"method": "ms.remote.control", "params": params}))


def send_key(ws, key, cmd="Click"):
    _send(ws, {"Cmd": cmd, "DataOfCmd": key, "Option": "false", "TypeOfRemote": "SendRemoteKey"})


def send_text(ws, text):
    _send(ws, {"Cmd": base64.b64encode(text.encode()).decode(), "DataOfCmd": "base64",
               "TypeOfRemote": "SendInputString"})


def launch_app(app_id):
    req = urllib.request.Request(f"http://{TV_IP}:8001/api/v2/applications/{app_id}", method="POST")
    return urllib.request.urlopen(req, timeout=5).read().decode()


def wake():
    if not TV_MAC:
        sys.exit("Set TV_MAC (shown as wifiMac by: tvremote.py info)")
    mac = bytes.fromhex(TV_MAC.replace(":", ""))
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.sendto(b"\xff" * 6 + mac * 16, ("255.255.255.255", 9))


INTERACTIVE_KEYS = {
    "\x1b[A": "KEY_UP", "\x1b[B": "KEY_DOWN", "\x1b[C": "KEY_RIGHT", "\x1b[D": "KEY_LEFT",
    "\r": "KEY_ENTER", "\x7f": "KEY_RETURN", "h": "KEY_HOME", "+": "KEY_VOLUP", "=": "KEY_VOLUP",
    "-": "KEY_VOLDOWN", "m": "KEY_MUTE", "p": "KEY_POWER", "s": "KEY_SOURCE", "u": "KEY_MENU",
    "]": "KEY_CHUP", "[": "KEY_CHDOWN", " ": "KEY_PLAY", "x": "KEY_EXIT", "i": "KEY_INFO",
}


def interactive():
    print("arrows=move enter=OK backspace=back h=home +/-=vol m=mute ]/[=ch "
          "space=play s=source u=menu x=exit p=power  q=quit")
    ws = connect()
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        while True:
            ch = sys.stdin.read(1)
            if ch == "\x1b":
                ch += sys.stdin.read(2)
            if ch in ("q", "\x03"):
                break
            key = INTERACTIVE_KEYS.get(ch)
            if key:
                send_key(ws, key)
                sys.stdout.write(f"{key}\r\n")
                sys.stdout.flush()
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)
        ws.close()


def main(argv):
    if not TV_IP:
        sys.exit("Set TV_IP to your TV's address, e.g. TV_IP=192.168.1.50")
    if not argv:
        return interactive()
    cmd, args = argv[0], argv[1:]
    if cmd == "info":
        print(urllib.request.urlopen(f"http://{TV_IP}:8001/api/v2/", timeout=5).read().decode())
    elif cmd == "pair":
        print("Press ALLOW on the TV (30s)...")
        connect(timeout=30).close()
        print(f"Paired, token saved to {TOKEN_FILE}" if TOKEN_FILE.exists() else "Connected (no token issued)")
    elif cmd == "key":
        ws = connect()
        for k in args:
            send_key(ws, k)
        ws.close()
    elif cmd == "text":
        ws = connect()
        send_text(ws, " ".join(args))
        ws.close()
    elif cmd == "app":
        print(launch_app(args[0]))
    elif cmd == "wake":
        wake()
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
