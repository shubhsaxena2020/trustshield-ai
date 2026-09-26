"""Authenticated private-release retrieval: token goes only to the API host, never to the redirect target, never into output."""
import hashlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from scripts import fetch_model_artifact as fetch

TOKEN = "github_pat_TESTTOKEN_do_not_leak_0123456789"
PAYLOAD = b"exact-promoted-model-bytes"


class _Servers:
    def __init__(self):
        self.api_seen, self.store_seen = [], []
        outer = self

        class Storage(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                outer.store_seen.append(dict(self.headers))
                self.send_response(200)
                self.send_header("Content-Length", str(len(PAYLOAD)))
                self.end_headers()
                self.wfile.write(PAYLOAD)

        self.storage = ThreadingHTTPServer(("127.0.0.1", 0), Storage)
        storage_url = f"http://127.0.0.1:{self.storage.server_address[1]}/signed-download"

        class Api(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                outer.api_seen.append((self.path, dict(self.headers)))
                if self.headers.get("Authorization") != f"Bearer {TOKEN}":
                    self.send_response(401); self.end_headers(); return
                if self.path == "/repos/o/r/releases/tags/model-v3":
                    body = json.dumps({"assets": [{"name": "trustshield_text_v3.joblib", "id": 42}]}).encode()
                    self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
                elif self.path == "/repos/o/r/releases/assets/42":
                    self.send_response(302); self.send_header("Location", storage_url); self.end_headers()
                else:
                    self.send_response(404); self.end_headers()

        self.api = ThreadingHTTPServer(("127.0.0.1", 0), Api)
        self.api_url = f"http://127.0.0.1:{self.api.server_address[1]}"
        for server in (self.api, self.storage):
            threading.Thread(target=server.serve_forever, daemon=True).start()

    def close(self):
        for server in (self.api, self.storage):
            server.shutdown(); server.server_close()


@pytest.fixture()
def servers():
    s = _Servers()
    yield s
    s.close()


def test_token_reaches_only_the_api_and_the_artifact_downloads(servers, tmp_path):
    target = tmp_path / "m.joblib"
    fetch.github_release_download("o/r@model-v3", "trustshield_text_v3.joblib", TOKEN, target, api=servers.api_url)
    assert target.read_bytes() == PAYLOAD
    assert all(headers.get("Authorization") == f"Bearer {TOKEN}" for _, headers in servers.api_seen)
    assert servers.store_seen and all("Authorization" not in h and "authorization" not in {k.lower() for k in h} for h in servers.store_seen)


def test_wrong_token_and_missing_asset_fail_without_echoing_the_token(servers, tmp_path, capsys):
    with pytest.raises(SystemExit) as exc:
        fetch.github_release_download("o/r@model-v3", "trustshield_text_v3.joblib", "wrong-" + TOKEN, tmp_path / "a", api=servers.api_url)
    assert "401" in str(exc.value) and TOKEN not in str(exc.value)
    with pytest.raises(SystemExit) as exc2:
        fetch.github_release_download("o/r@model-v3", "no_such_asset.joblib", TOKEN, tmp_path / "b", api=servers.api_url)
    assert "no asset named" in str(exc2.value) and TOKEN not in str(exc2.value)
    captured = capsys.readouterr()
    assert TOKEN not in captured.out and TOKEN not in captured.err


def test_token_sources_and_missing_token(tmp_path, monkeypatch):
    for name in ("MODEL_FETCH_TOKEN", "GITHUB_TOKEN"):
        monkeypatch.delenv(name, raising=False)
    assert fetch.read_token(None) == ""
    monkeypatch.setenv("GITHUB_TOKEN", "from-github-token")
    assert fetch.read_token(None) == "from-github-token"
    monkeypatch.setenv("MODEL_FETCH_TOKEN", "from-model-fetch")
    assert fetch.read_token(None) == "from-model-fetch"
    token_file = tmp_path / "secret"
    token_file.write_text("from-file\n", encoding="utf-8")
    assert fetch.read_token(token_file) == "from-file"
    with pytest.raises(SystemExit, match="needs OWNER/REPO@TAG and a token"):
        fetch.github_release_download("o/r@t", "x", "", tmp_path / "c")


def test_cli_installs_only_a_hash_verified_artifact_from_the_release(servers, tmp_path, monkeypatch):
    real = fetch.github_release_download
    monkeypatch.setattr(fetch, "github_release_download",
                        lambda spec, name, token, dest, api="unused": real(spec, name, token, dest, api=servers.api_url))
    token_file = tmp_path / "tok"
    token_file.write_text(TOKEN, encoding="utf-8")
    good = tmp_path / "manifest.json"
    good.write_text(json.dumps({"models": {"trustshield_text_v3": {"artifact_sha256": hashlib.sha256(PAYLOAD).hexdigest()}}}), encoding="utf-8")
    dest = tmp_path / "out"
    code = fetch.main(["--github-release", "o/r@model-v3", "--token-file", str(token_file), "--manifest", str(good), "--dest", str(dest)])
    assert code == 0 and (dest / "trustshield_text_v3.joblib").read_bytes() == PAYLOAD
    bad = tmp_path / "bad.json"
    bad.write_text(json.dumps({"models": {"trustshield_text_v3": {"artifact_sha256": "0" * 64}}}), encoding="utf-8")
    dest2 = tmp_path / "out2"
    with pytest.raises(SystemExit, match="SHA-256 mismatch"):
        fetch.main(["--github-release", "o/r@model-v3", "--token-file", str(token_file), "--manifest", str(bad), "--dest", str(dest2)])
    assert not (dest2 / "trustshield_text_v3.joblib").exists()
