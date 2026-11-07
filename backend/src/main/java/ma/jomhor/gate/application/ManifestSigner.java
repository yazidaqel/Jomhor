package ma.jomhor.gate.application;

/**
 * The server's Ed25519 manifest key (docs/04-api.md §Gate, §Manifest payload). Scanners pin {@link #publicKey()} at
 * enrolment and verify every manifest payload with it. The private key never leaves the adapter.
 */
public interface ManifestSigner {

    /** Raw 32-byte Ed25519 public key. */
    byte[] publicKey();

    /** Ed25519 signature (64 bytes) over {@code payload}. */
    byte[] sign(byte[] payload);
}
