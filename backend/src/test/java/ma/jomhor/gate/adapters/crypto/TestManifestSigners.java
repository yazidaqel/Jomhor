package ma.jomhor.gate.adapters.crypto;

import ma.jomhor.gate.application.ManifestSigner;

/** Test access to the package-private Ed25519 signer, for a fixed key (GateVectorsTest). */
public final class TestManifestSigners {

    private TestManifestSigners() {}

    public static ManifestSigner ed25519(String pkcs8Base64) {
        return new Ed25519ManifestSigner(pkcs8Base64);
    }
}
