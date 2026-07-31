#!/usr/bin/env python3
"""
ONE-TIME migration: turn stored QRIS *images* into QRIS *codes*.

QRIS used to be stored as a base64 image in core.business_qris.qris_image and
core.user_qris.qris_image. Those columns now hold the EMV payload string (the "QRIS code")
instead, which the app renders locally. Rows written before that switch still contain base64
and have to be decoded once — the server has no QR decoder, so this cannot be done in SQL.

Run once, then delete this file. It is deliberately not wired into the app or Flyway.

    # 1. See what would change (default — writes nothing)
    python3 scripts/one-time-convert-qris-images.py --db "$DB_URL"

    # 2. Apply, inside a single transaction
    python3 scripts/one-time-convert-qris-images.py --db "$DB_URL" --apply

DB_URL is a libpq URL, e.g. postgresql://user:pass@host:5432/bejo

Requires: python3, opencv (`pip install opencv-python-headless`), numpy, and `psql` on PATH.
It shells out to psql rather than importing a driver, so there is no extra dependency to
install against production.

Safe to re-run: rows that already hold a code are skipped, so a second pass converts nothing.
Rows whose QR cannot be read are reported and left untouched for manual re-upload.
"""

import argparse
import base64
import binascii
import subprocess
import sys

try:
    import cv2
    import numpy as np
except ImportError:
    sys.exit("Missing dependency. Install with: pip install opencv-python-headless numpy")

TABLES = ("core.business_qris", "core.user_qris")

# A decoded EMV payload is a few hundred characters; the app rejects anything longer than this
# (QrisService.MAX_QRIS_CODE_LENGTH), so it doubles as "is this row still an image?".
MAX_QRIS_CODE_LENGTH = 1024

# Every EMV QR starts with the payload-format-indicator tag 00, length 02, value 01.
EMV_PREFIX = "000201"


def psql(db_url, sql, capture=True):
    """Run one statement through psql. Returns stdout for queries."""
    cmd = ["psql", db_url, "-v", "ON_ERROR_STOP=1", "-tAq", "-c", sql]
    result = subprocess.run(cmd, capture_output=capture, text=True)
    if result.returncode != 0:
        sys.exit(f"psql failed:\n{result.stderr}")
    return result.stdout if capture else ""


def fetch_pending(db_url, table):
    """(id, stored) for rows that still hold an image rather than a code."""
    rows = psql(db_url, (
        f"SELECT id, qris_image FROM {table} "
        f"WHERE qris_image IS NOT NULL AND length(qris_image) > {MAX_QRIS_CODE_LENGTH} "
        f"ORDER BY id"
    ))
    out = []
    for line in rows.splitlines():
        if not line.strip():
            continue
        row_id, _, stored = line.partition("|")
        out.append((int(row_id), stored))
    return out


def decode_qris(b64_value):
    """The EMV payload inside a base64 QR image, or None with a reason."""
    try:
        raw = base64.b64decode("".join(b64_value.split()), validate=False)
    except (binascii.Error, ValueError) as exc:
        return None, f"not valid base64 ({exc})"
    buf = np.frombuffer(raw, dtype=np.uint8)
    image = cv2.imdecode(buf, cv2.IMREAD_GRAYSCALE)
    if image is None:
        return None, "not a decodable image"
    payload, _, _ = cv2.QRCodeDetector().detectAndDecode(image)
    if not payload:
        return None, "no QR code found in the image"
    if len(payload) > MAX_QRIS_CODE_LENGTH:
        return None, f"payload is {len(payload)} chars, over the {MAX_QRIS_CODE_LENGTH} limit"
    if any(ord(c) < 0x20 or ord(c) > 0x7E for c in payload):
        return None, "payload contains non-printable characters"
    if not payload.startswith(EMV_PREFIX):
        # Decoded fine but is not an EMV QR — surface it rather than writing it silently.
        return None, f"does not look like an EMV QRIS payload (starts with {payload[:8]!r})"
    return payload, None


def main():
    parser = argparse.ArgumentParser(description="Convert stored QRIS images to QRIS codes (one-time).")
    parser.add_argument("--db", required=True, help="libpq URL, e.g. postgresql://user:pass@host:5432/bejo")
    parser.add_argument("--apply", action="store_true", help="write the changes (default: dry run)")
    args = parser.parse_args()

    updates, failures, total = [], [], 0
    for table in TABLES:
        pending = fetch_pending(args.db, table)
        total += len(pending)
        print(f"\n{table}: {len(pending)} row(s) still holding an image")
        for row_id, stored in pending:
            payload, reason = decode_qris(stored)
            if payload is None:
                failures.append((table, row_id, reason))
                print(f"  id={row_id:<6} FAILED — {reason}")
            else:
                updates.append((table, row_id, payload))
                print(f"  id={row_id:<6} OK     {len(stored):>7} chars -> {len(payload)} chars  {payload[:48]}...")

    print(f"\nSummary: {total} candidate(s), {len(updates)} decodable, {len(failures)} not")
    if failures:
        print("Rows that could not be decoded are left untouched — the merchant must re-supply "
              "their QRIS from the app:")
        for table, row_id, reason in failures:
            print(f"  {table} id={row_id}: {reason}")

    if not updates:
        print("\nNothing to write.")
        return

    if not args.apply:
        print("\nDry run — nothing written. Re-run with --apply to commit these changes.")
        return

    # One transaction: either every decodable row is converted or none is.
    statements = ["BEGIN;"]
    for table, row_id, payload in updates:
        escaped = payload.replace("'", "''")
        statements.append(f"UPDATE {table} SET qris_image = '{escaped}' WHERE id = {row_id};")
    statements.append("COMMIT;")
    psql(args.db, "\n".join(statements), capture=True)
    print(f"\nApplied {len(updates)} update(s).")

    remaining = sum(len(fetch_pending(args.db, t)) for t in TABLES)
    print(f"Verification: {remaining} row(s) still holding an image "
          f"({'only the undecodable ones' if remaining == len(failures) else 'UNEXPECTED — investigate'}).")


if __name__ == "__main__":
    main()
