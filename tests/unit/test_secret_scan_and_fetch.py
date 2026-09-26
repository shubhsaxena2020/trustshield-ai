import hashlib
import json
from pathlib import Path

import pytest

from scripts import fetch_model_artifact as fetch
from scripts import scan_secrets


def _write(tmp_path, name, text):
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


def test_scanner_flags_real_looking_secrets_without_printing_values(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(scan_secrets, "ROOT", tmp_path)
    fake_key = "ghp_" + "a1B2c3D4e5F6" * 4
    files = [
        _write(tmp_path, "a.py", f'TOKEN = "{fake_key}"\n'),
        _write(tmp_path, "b.txt", "-----BEGIN RSA PRIVATE KEY-----\nabc\n"),
        _write(tmp_path, "c.env", "API_KEY=Zk39dLq81xVb27Ns0Pw4Ty6uRe5Ha1Cm\n"),
    ]
    assert scan_secrets.main([str(p) for p in files]) == 1
    out = capsys.readouterr().out
    assert "github token" in out and "private key block" in out and "generic secret assignment" in out
    assert fake_key not in out and "Zk39dLq81xVb27Ns0Pw4Ty6uRe5Ha1Cm" not in out


def test_scanner_ignores_placeholders_and_plain_text(tmp_path, monkeypatch):
    monkeypatch.setattr(scan_secrets, "ROOT", tmp_path)
    ok = _write(tmp_path, ".env.example", "THREAT_PROVIDER_API_KEY=\nCORS_ALLOWED_ORIGINS=https://example.app\n")
    prose = _write(tmp_path, "notes.md", "The password field is never stored. Set your token in the dashboard.\n")
    assert scan_secrets.main([str(ok), str(prose)]) == 0


def test_repository_is_currently_clean():
    assert scan_secrets.main([]) == 0


# ---- model artifact installation --------------------------------------------------------------------------------------

def _manifest(tmp_path, payload: bytes, model="trustshield_text_v3"):
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps({"models": {model: {"artifact_sha256": hashlib.sha256(payload).hexdigest()}}}), encoding="utf-8")
    return path


def test_matching_artifact_is_installed_atomically(tmp_path):
    payload = b"model-bytes"
    source = tmp_path / "src"
    source.mkdir()
    (source / "trustshield_text_v3.joblib").write_bytes(payload)
    dest = tmp_path / "out"
    code = fetch.main(["--from-dir", str(source), "--manifest", str(_manifest(tmp_path, payload)), "--dest", str(dest)])
    assert code == 0 and (dest / "trustshield_text_v3.joblib").read_bytes() == payload
    assert not list(dest.glob("*.part"))


def test_hash_mismatch_installs_nothing_and_fails(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    (source / "trustshield_text_v3.joblib").write_bytes(b"tampered")
    dest = tmp_path / "out"
    with pytest.raises(SystemExit, match="SHA-256 mismatch"):
        fetch.main(["--from-dir", str(source), "--manifest", str(_manifest(tmp_path, b"expected")), "--dest", str(dest)])
    assert not (dest / "trustshield_text_v3.joblib").exists()


def test_no_source_and_non_https_urls_are_refused(tmp_path):
    assert fetch.main(["--from-dir", str(tmp_path), "--manifest", str(_manifest(tmp_path, b"x")), "--dest", str(tmp_path / "o")]) == 2
    with pytest.raises(SystemExit, match="non-HTTPS"):
        fetch.main(["--url", "http://example.com/m.joblib", "--manifest", str(_manifest(tmp_path, b"x")), "--dest", str(tmp_path / "o")])


def test_real_artifact_matches_the_release_manifest():
    artifact = Path(__file__).resolve().parents[2] / "models" / "trained" / "trustshield_text_v3.joblib"
    if not artifact.exists():
        pytest.skip("artifact not present")
    manifest = json.loads((artifact.parents[2] / "models" / "release" / "v3_release_manifest.json").read_text())
    assert fetch.sha256(artifact) == manifest["models"]["trustshield_text_v3"]["artifact_sha256"]
