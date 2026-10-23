"""Resolves the current Supabase user from a request's access token, if any. No password ever
reaches this service; only short-lived access tokens issued by Supabase Auth."""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass

from fastapi import Header, HTTPException, Request

_TIMEOUT_SECONDS = 5
# A Supabase user record is a few KB; cap the read so a misbehaving upstream cannot stream without bound.
_MAX_RESPONSE_BYTES = 256 * 1024


@dataclass(frozen=True)
class CurrentUser:
    id: str
    email: str


class _UpstreamError(Exception):
    """Supabase answered, but not with a user record this service can trust."""


def _lookup_user(supabase_url: str, service_role_key: str, access_token: str) -> CurrentUser | None:
    """None for an invalid/expired token; raises for a genuine network/service failure."""
    req = urllib.request.Request(
        f"{supabase_url}/auth/v1/user",
        headers={"apikey": service_role_key, "Authorization": f"Bearer {access_token}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=_TIMEOUT_SECONDS) as r:  # noqa: S310 (fixed https host from settings)
            raw = r.read(_MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as exc:
        try:
            if exc.code in (401, 403):
                return None
        finally:
            exc.close()
        raise
    if len(raw) > _MAX_RESPONSE_BYTES:
        raise _UpstreamError("response body exceeded the size limit")
    try:
        body = json.loads(raw)
    except ValueError as exc:
        raise _UpstreamError("response body was not JSON") from exc
    if not isinstance(body, dict):
        raise _UpstreamError("response body was not a JSON object")
    user_id = body.get("id")
    if not isinstance(user_id, str) or not user_id.strip():
        raise _UpstreamError("response body did not contain a user id")
    email = body.get("email")
    return CurrentUser(id=user_id.strip(), email=email.strip() if isinstance(email, str) else "")


def get_current_user(request: Request, authorization: str | None = Header(default=None)) -> CurrentUser | None:
    """None for an anonymous request (no/non-bearer header). An invalid/expired token raises 401
    rather than silently degrading to anonymous, so a broken session is visible in the UI
    instead of silently losing history."""
    if not authorization:
        return None
    scheme, _, token = authorization.partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token:
        return None
    settings = request.app.state.settings
    if not settings.supabase_url or not settings.supabase_service_role_key:
        raise HTTPException(503, "Accounts are not configured on this server")
    if any(ch.isspace() for ch in token):
        # A bearer token never contains whitespace; reject it here instead of forwarding junk upstream.
        raise HTTPException(401, "Your session has expired; please log in again")
    try:
        user = _lookup_user(settings.supabase_url, settings.supabase_service_role_key, token)
    except Exception:
        raise HTTPException(503, "Could not verify your session; please try again shortly")
    if user is None:
        raise HTTPException(401, "Your session has expired; please log in again")
    return user
