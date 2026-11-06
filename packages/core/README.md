# jomhor_core

Pure Dart (no Flutter) package shared by `apps/fan`, `apps/scanner` and tools:
the ticket token codec from `docs/04-api.md` §Token.

- `encodeStatic` (v1, signed with K_e) / `encodeRotating` (v2, signed with K_t)
- `deriveTicketKey` (K_t = HMAC-SHA256(K_e, ticket_id)), `slotAt` (floor(unix / 30))
- `decode` (structure only, throws `TokenFormatException` -> `BAD_FORMAT`)
- `verify` (steps 3-4 of the offline algorithm: `WRONG_EVENT`, `EXPIRED_SLOT`, `BAD_SIG`)

## Tests

```
dart pub get
dart analyze
dart test
```

`test/vectors_test.dart` holds interim known-answer vectors computed by the
independent Python script `tool/interim_vectors.py` (`python tool/interim_vectors.py`),
and a golden test against `docs/token-vectors.json` that is skipped until the
backend generates that file (Lot 1).
