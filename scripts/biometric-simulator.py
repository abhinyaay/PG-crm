#!/usr/bin/env python3
"""Pretend to be a ZKTeco / eSSL fingerprint terminal speaking the ADMS ("Cloud Server") push protocol.

Lets you try the biometric entry/exit module without buying hardware. It does exactly what a real device does:

  1. handshake   GET  /iclock/cdata?SN=<serial>&options=all      -> server answers "GET OPTION FROM: <serial> ..."
  2. heartbeat   GET  /iclock/getrequest?SN=<serial>             -> "OK"
  3. punches     POST /iclock/cdata?SN=<serial>&table=ATTLOG      -> "OK: <n>"
     body: one line per punch, tab separated: PIN, local time (YYYY-MM-DD HH:MM:SS), status (0 in / 1 out), verify (1 finger)
  4. optional re-send of the same punches, to show that duplicates are ignored (devices re-send after reconnects)

Before running, register the serial number in the app (Settings -> Biometric devices -> Add device) and give each
resident a "Fingerprint ID" (the PIN). Uses only the Python 3 standard library.

Examples:
  # resident with Fingerprint ID 1 enters, resident 2 enters then leaves, everything sent twice
  python3 scripts/biometric-simulator.py --url http://localhost --serial CQZ7232460123 \\
      --punch 1:in --punch 2:in --punch 2:out --resend

  # published site over HTTPS, device clock in IST, three entries 10 minutes apart for PIN 7 (to hit a daily limit)
  python3 scripts/biometric-simulator.py --url https://pg.example.com --serial CQZ7232460123 \\
      --punch 7:in --punch 7:in --punch 7:in --spacing 600

  # keep heart-beating like an idle device (Ctrl+C to stop)
  python3 scripts/biometric-simulator.py --url http://192.168.1.20 --serial CQZ7232460123 --heartbeats 0
"""

import argparse
import datetime as dt
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

IST = dt.timezone(dt.timedelta(hours=5, minutes=30), "IST")
DIRECTIONS = {"in": 0, "entry": 0, "out": 1, "exit": 1}
VERIFY_MODES = {"finger": 1, "fingerprint": 1, "card": 4, "face": 15}


def parse_punch(text):
    """PIN:in|out[:finger|card|face] -> (pin, status, verify)."""
    parts = text.split(":")
    if len(parts) < 2 or parts[1].lower() not in DIRECTIONS:
        raise argparse.ArgumentTypeError("use PIN:in or PIN:out (optionally :finger/:card/:face), e.g. 12:in")
    pin = parts[0].strip()
    if not pin.isalnum() or len(pin) > 24:
        raise argparse.ArgumentTypeError("PIN must be 1-24 letters or digits")
    verify = VERIFY_MODES.get(parts[2].lower(), 1) if len(parts) > 2 else 1
    return pin, DIRECTIONS[parts[1].lower()], verify


class Device:
    def __init__(self, base_url, serial, timeout, insecure, user_agent):
        self.base = base_url.rstrip("/")
        self.serial = serial
        self.timeout = timeout
        self.user_agent = user_agent
        self.context = ssl._create_unverified_context() if insecure else None  # noqa: S323 (opt-in for self-signed certs)

    def _url(self, path, **params):
        query = urllib.parse.urlencode({"SN": self.serial, **params})
        return f"{self.base}{path}?{query}"

    def _call(self, method, url, body=None):
        data = body.encode("utf-8") if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("User-Agent", self.user_agent)
        if data is not None:
            request.add_header("Content-Type", "text/plain")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout, context=self.context) as response:
                return response.status, response.read().decode("utf-8", "replace"), response.headers.get("Date")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace"), e.headers.get("Date")

    def handshake(self):
        return self._call("GET", self._url("/iclock/cdata", options="all", pushver="2.4.1", language="69"))

    def heartbeat(self):
        return self._call("GET", self._url("/iclock/getrequest", INFO="Ver 6.60,1,0,0,192.168.1.201,10,7,12,1,111"))

    def push_attlog(self, lines, stamp):
        return self._call("POST", self._url("/iclock/cdata", table="ATTLOG", Stamp=str(stamp)), "\n".join(lines) + "\n")


def build_lines(punches, start, spacing):
    lines = []
    for i, (pin, status, verify) in enumerate(punches):
        local = start + dt.timedelta(seconds=i * spacing)
        lines.append(f"{pin}\t{local:%Y-%m-%d %H:%M:%S}\t{status}\t{verify}\t0\t0\t0")
    return lines


def show(step, status, body):
    ok = 200 <= status < 300
    print(f"[{'OK ' if ok else 'ERR'}] {step}: HTTP {status}")
    for line in body.strip().splitlines():
        print(f"        {line}")
    if status == 401:
        print("        -> The server does not know this serial number (or the device is disabled).")
        print("           Register it in the app: Settings -> Biometric devices -> Add device.")
    return ok


def main():
    parser = argparse.ArgumentParser(
        description="Emulate a ZKTeco/eSSL ADMS push device against a running PG platform.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Examples:", 1)[1] if "Examples:" in __doc__ else None)
    parser.add_argument("--url", required=True, help="site address, e.g. http://localhost, http://192.168.1.20 or "
                                                     "https://pg.example.com (no path)")
    parser.add_argument("--serial", required=True, help="device serial number exactly as registered in the app")
    parser.add_argument("--punch", action="append", type=parse_punch, default=[], metavar="PIN:in|out[:finger|card|face]",
                        help="a punch to upload; repeat for several (in = status 0, out = status 1)")
    parser.add_argument("--start", help="local device time of the first punch, 'YYYY-MM-DD HH:MM:SS' "
                                        "(default: now in India time)")
    parser.add_argument("--spacing", type=int, default=5, help="seconds between consecutive punches (default 5)")
    parser.add_argument("--resend", action="store_true", help="upload the same punches a second time to show "
                                                             "that duplicates are ignored")
    parser.add_argument("--heartbeats", type=int, default=1, help="heartbeats to send after the upload "
                                                                  "(0 = keep going until Ctrl+C; default 1)")
    parser.add_argument("--interval", type=float, default=10, help="seconds between heartbeats (default 10)")
    parser.add_argument("--stamp", type=int, default=int(time.time()), help="ATTLOG stamp reported to the server")
    parser.add_argument("--timeout", type=float, default=10, help="HTTP timeout in seconds (default 10)")
    parser.add_argument("--insecure", action="store_true", help="accept a self-signed HTTPS certificate (testing only)")
    parser.add_argument("--user-agent", default="iClock Proxy/1.09", help=argparse.SUPPRESS)
    args = parser.parse_args()

    if args.start:
        try:
            start = dt.datetime.strptime(args.start, "%Y-%m-%d %H:%M:%S")
        except ValueError:
            parser.error("--start must look like 2026-10-13 21:30:00")
    else:
        start = dt.datetime.now(IST).replace(tzinfo=None, microsecond=0)

    device = Device(args.url, args.serial, args.timeout, args.insecure, args.user_agent)
    print(f"Simulating device {args.serial} -> {device.base}")
    try:
        status, body, server_date = device.handshake()
        if not show("handshake (GET /iclock/cdata?options=all)", status, body):
            return 1
        if server_date:
            print(f"        server clock (HTTP Date header, real devices sync to it): {server_date}")

        if args.punch:
            lines = build_lines(args.punch, start, args.spacing)
            print("Uploading punches (PIN, local time, status 0=in/1=out, verify 1=finger/4=card/15=face):")
            for line in lines:
                print("        " + line.replace("\t", "  "))
            status, body, _ = device.push_attlog(lines, args.stamp)
            if not show(f"upload {len(lines)} punch(es) (POST /iclock/cdata?table=ATTLOG)", status, body):
                return 1
            if args.resend:
                status, body, _ = device.push_attlog(lines, args.stamp)
                show("re-send the same punches (stored once only; see the gate log)", status, body)

        count = 0
        while args.heartbeats == 0 or count < args.heartbeats:
            if count:
                time.sleep(args.interval)
            status, body, _ = device.heartbeat()
            show("heartbeat (GET /iclock/getrequest)", status, body)
            count += 1
    except KeyboardInterrupt:
        print("\nStopped.")
    except urllib.error.URLError as e:
        print(f"[ERR] cannot reach {device.base}: {e.reason}")
        print("      Is the site running and is the address/port right? Try opening it in a browser.")
        return 2
    print("Done. Check the app: Access control -> Gate log / Who's inside.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
