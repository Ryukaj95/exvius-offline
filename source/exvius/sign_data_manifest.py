"""Sign the exact game data manifest bytes for the Android updater."""

import argparse
import base64
import json
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("private_key", type=Path, help="PEM RSA release key stored outside Git")
    parser.add_argument("--manifest", type=Path, default=root / "data/latest.json")
    parser.add_argument("--output", type=Path, default=root / "data/latest.signed.json")
    args = parser.parse_args()

    payload = args.manifest.read_bytes()
    if len(payload) > 524288:
        parser.error("manifest exceeds the Android updater's size limit")
    json.loads(payload)
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(args.private_key)],
        input=payload, stdout=subprocess.PIPE, check=True,
    ).stdout
    envelope = {
        "manifest": base64.b64encode(payload).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    args.output.write_text(json.dumps(envelope) + "\n", encoding="ascii")


if __name__ == "__main__":
    main()
