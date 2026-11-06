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
dart analyze --fatal-infos
dart test
```

`test/vectors_test.dart` is the golden test against `docs/token-vectors.json`
(10 static + 10 rotating vectors, written by the backend's `TokenVectorsTest`,
see `docs/04-api.md` §Test vectors). Each vector must encode to its
`expected_token`, decode back to its inputs and verify. A missing file fails
the test: run the backend `TokenVectorsTest` and commit the file.
