"""Independent reference for the interim known-answer vectors in
test/vectors_test.dart (stdlib only: hmac, hashlib, base64, struct).
Byte layout: docs/04-api.md §Token. Run: python tool/interim_vectors.py
Delete once docs/token-vectors.json (backend, Lot 1) is the golden source."""
import base64, hashlib, hmac, struct, uuid


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def tag(key, body):
    return hmac.new(key, body, hashlib.sha256).digest()[:16]


def static(k_e, event_id, ticket_id, zone_id):
    body = struct.pack(">BI", 1, event_id) + uuid.UUID(ticket_id).bytes + struct.pack(">H", zone_id)
    return b64(body + tag(k_e, body))


def rotating(k_e, event_id, ticket_id, zone_id, slot):
    t = uuid.UUID(ticket_id).bytes
    k_t = hmac.new(k_e, t, hashlib.sha256).digest()
    body = struct.pack(">BI", 2, event_id) + t + struct.pack(">HI", zone_id, slot)
    return b64(body + tag(k_t, body))


KE_A = bytes(range(32))
KE_B = bytes([0xFF] * 32)
KE_C = hashlib.sha256(b"jomhor-interim").digest()

CASES = [
    (KE_A, 1, "00000000-0000-0000-0000-000000000000", 0, None),
    (KE_B, 0xFFFFFFFF, "ffffffff-ffff-ffff-ffff-ffffffffffff", 0xFFFF, None),
    (KE_C, 4242, "0192f0c1-7a3e-7c4d-9b1a-2f3e4d5c6b7a", 12, None),
    (KE_A, 1, "00000000-0000-0000-0000-000000000000", 0, 0),
    (KE_B, 0xFFFFFFFF, "ffffffff-ffff-ffff-ffff-ffffffffffff", 0xFFFF, 0xFFFFFFFF),
    (KE_C, 4242, "0192f0c1-7a3e-7c4d-9b1a-2f3e4d5c6b7a", 12, 59_000_000),
]

if __name__ == "__main__":
    print("k_t(KE_C, case 3) =", hmac.new(KE_C, uuid.UUID(CASES[2][2]).bytes, hashlib.sha256).hexdigest())
    for k_e, ev, tid, zone, slot in CASES:
        tok = static(k_e, ev, tid, zone) if slot is None else rotating(k_e, ev, tid, zone, slot)
        print(k_e.hex(), ev, tid, zone, slot, tok, len(tok))
