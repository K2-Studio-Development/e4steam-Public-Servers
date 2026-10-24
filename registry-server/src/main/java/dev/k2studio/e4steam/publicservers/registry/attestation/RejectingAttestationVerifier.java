package dev.k2studio.e4steam.publicservers.registry.attestation;

import java.time.Instant;

/** Fail-closed verifier used when no explicit development or production verifier is configured. */
public final class RejectingAttestationVerifier implements PublisherAttestationVerifier {
    @Override
    public VerifiedPublisher verify(Challenge challenge, String assertion, Instant now)
            throws VerificationException {
        throw new VerificationException();
    }

    @Override
    public boolean available() {
        return false;
    }
}
