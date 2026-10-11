"""Resolves the current Supabase user from a request's access token, if any. No password ever
reaches this service; only short-lived access tokens issued by Supabase Auth."""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass

from fastapi import Header, HTTPException, Request

_TIMEOUT_SECONDS = 5


@dataclass(frozen=True)
class CurrentUser:
    id: str
    email: str


def _lookup_user(supabase_url: str, service_role_key: str, access_token: str) -> CurrentUser | None:
    """None for an invalid/expired token; raises for a genuine network/service failure."""
    req = urllib.request.Request(
        f"{supabase_url}/auth/v1/user",
        headers={"apikey": service_role_key, "Authorization": f"Bearer {access_token}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=_TIMEOUT_SECONDS) as r:  # noqa: S310 (fixed https host from settings)
            body = json.loads(r.read())
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 403):
            return None
        raise
    user_id = body.get("id") if isinstance(body, dict) else None
    if not user_id:
        return None
    return CurrentUser(id=str(user_id), email=str(body.get("email", "")))


def get_current_user(request: Request, authorization: str | None = Header(default=None)) -> CurrentUser | None:
    """None for an anonymous request (no/non-bearer header). An invalid/expired token raises 401
    rather than silently degrading to anonymous, so a broken session is visible in the UI
    instead of silently losing history."""
    if not authorization:
        return None
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    settings = request.app.state.settings
    if not settings.supabase_url or not settings.supabase_service_role_key:
        raise HTTPException(503, "Accounts are not configured on this server")
    try:
        user = _lookup_user(settings.supabase_url, settings.supabase_service_role_key, token.strip())
    except Exception:
        raise HTTPException(503, "Could not verify your session; please try again shortly")
    if user is None:
        raise HTTPException(401, "Your session has expired; please log in again")
    return user
