#!/usr/bin/env python3
"""Fetch raw public inputs, then run the deterministic curation pipeline."""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

import requests

try:
    from huggingface_hub import hf_hub_download
except ImportError:  # pragma: no cover
    hf_hub_download = None


ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "datasets" / "raw"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    if target.exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    response = requests.get(url, timeout=60)
    response.raise_for_status()
    target.write_bytes(response.content)


def fetch_uci() -> None:
    directory = RAW / "ucI_sms_spam"
    archive = directory / "smsspamcollection.zip"
    download("https://archive.ics.uci.edu/ml/machine-learning-databases/00228/smsspamcollection.zip", archive)
    extracted = directory / "SMSSpamCollection"
    if not extracted.exists():
        with zipfile.ZipFile(archive) as source:
            source.extractall(directory)
    print(f"uci_sms_spam: {extracted} sha256={sha256(extracted)}")


def fetch_india() -> None:
    target = RAW / "india_spam_sms" / "spam_ham_india.csv"
    download("https://raw.githubusercontent.com/junioralive/india-spam-sms-classification/main/dataset/spam_ham_india.csv", target)
    print(f"india_spam_sms: {target} sha256={sha256(target)}")


def fetch_hf_texts() -> None:
    if hf_hub_download is None:
        raise RuntimeError("huggingface_hub is required to fetch ealvaradob/phishing-dataset")
    directory = RAW / "ealvaradob_phishing-dataset"
    directory.mkdir(parents=True, exist_ok=True)
    for filename in ("README.md", "phishing-dataset.py", "texts.json"):
        path = hf_hub_download(
            "ealvaradob/phishing-dataset",
            filename,
            repo_type="dataset",
            local_dir=str(directory),
            local_dir_use_symlinks=False,
        )
        print(f"ealvaradob/phishing-dataset:{filename}: {path} sha256={sha256(Path(path))}")


def record_mendeley_status() -> None:
    directory = RAW / "mendeley_sms_phishing"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "SOURCE.md").write_text(
        """# Source: mendeley_sms_phishing

- **URL**: https://data.mendeley.com/datasets/f45bkkt8pr/1
- **Download URL attempted**: https://data.mendeley.com/public-api/zip/f45bkkt8pr/download/1
- **Licence**: not used; the public API returned HTTP 403 on 2026-09-24.
- **Decision**: excluded because the raw archive could not be obtained and the terms could not be independently checked.
- **Reproduction**: `fetch_public_datasets.py` records the failed source and does not create a raw payload.
""",
        encoding="utf-8",
    )


def main() -> int:
    for name, function in (("UCI SMS Spam Collection", fetch_uci), ("India SMS repository", fetch_india), ("Hugging Face texts subset", fetch_hf_texts)):
        print(f"=== {name} ===")
        try:
            function()
        except Exception as exc:
            print(f"FAILED: {exc}")
            return 1
    record_mendeley_status()
    print("=== Curation ===")
    from curate_public_datasets import main as curate_main

    return curate_main()


if __name__ == "__main__":
    sys.exit(main())
