"""Observe the real reverse-proxy chain in front of a deployed API instead of guessing TRUSTSHIELD_TRUSTED_PROXY_HOPS.

    python scripts/observe_proxy_chain.py --base https://<api>.onrender.com [--my-ip 203.0.113.9]

Requires the API to run briefly with TRUSTSHIELD_DEBUG_PROXY=1 (exposes GET /debug/proxy: peer address and forwarding headers only).
The script sends a spoofed X-Forwarded-For, reads what the API received, and reports how many entries the platform appended. The
client is the Nth entry from the right, so hops = number of appended entries; if --my-ip (or a public-IP lookup) does not equal the
first appended entry the observation is flagged as inconclusive. Remove the debug flag afterwards.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request

SPOOF = ["203.0.113.7", "198.51.100.9"]


def analyse(received: str, my_ip: str | None) -> dict:
    seen = [item.strip() for item in received.split(",") if item.strip()]
    if seen[: len(SPOOF)] != SPOOF:
        return {"conclusive": False, "reason": "the platform did not preserve the spoofed entries in order", "seen": seen}
    appended = seen[len(SPOOF):]
    if not appended:
        return {"conclusive": False, "reason": "the platform appended no entry; the socket peer is the client", "seen": seen, "hops": 0}
    result = {"conclusive": True, "seen": seen, "appended": appended, "hops": len(appended), "client_entry": appended[0]}
    if my_ip is not None and appended[0] != my_ip:
        result.update(conclusive=False, reason=f"first appended entry {appended[0]} is not this machine's public IP {my_ip}")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--my-ip")
    args = parser.parse_args(argv)
    my_ip = args.my_ip
    if my_ip is None:
        try:
            my_ip = urllib.request.urlopen("https://api.ipify.org", timeout=10).read().decode().strip()
        except Exception:
            my_ip = None
    request = urllib.request.Request(args.base.rstrip("/") + "/debug/proxy", headers={"X-Forwarded-For": ", ".join(SPOOF)})
    with urllib.request.urlopen(request, timeout=30) as response:
        observed = json.loads(response.read())
    outcome = analyse(observed.get("x_forwarded_for", ""), my_ip)
    print(json.dumps({"peer": observed.get("peer"), "x_forwarded_for": observed.get("x_forwarded_for"), "x_forwarded_proto": observed.get("x_forwarded_proto"),
                      "my_ip": my_ip, **outcome}, indent=2))
    if outcome.get("conclusive"):
        print(f"\nSet TRUSTSHIELD_TRUSTED_PROXY_HOPS={outcome['hops']}")
        return 0
    print("\nInconclusive: do not set the hop count until this is resolved.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
