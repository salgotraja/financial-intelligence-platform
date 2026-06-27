package dev.engnotes.authorizer.jwt;

import java.security.interfaces.RSAPublicKey;

/**
 * Resolves the RSA public key for a JWT {@code kid}. Production resolves against the Cognito pool
 * JWKS; tests inject a local key so {@link JwtVerifier} is unit-testable without network access.
 */
public interface SigningKeyResolver {
    RSAPublicKey resolve(String keyId) throws Exception;
}
