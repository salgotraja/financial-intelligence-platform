package dev.engnotes.authorizer.jwt;

import com.auth0.jwk.GuavaCachedJwkProvider;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;

/**
 * Resolves signing keys from the Cognito pool JWKS endpoint
 * ({@code <issuer>/.well-known/jwks.json}), cached in memory so repeated authorizations do not refetch.
 */
public class JwksSigningKeyResolver implements SigningKeyResolver {

    private final JwkProvider provider;

    public JwksSigningKeyResolver(String issuer) throws Exception {
        this.provider = new GuavaCachedJwkProvider(
                new UrlJwkProvider(URI.create(issuer + "/.well-known/jwks.json").toURL()));
    }

    @Override
    public RSAPublicKey resolve(String keyId) throws Exception {
        return (RSAPublicKey) provider.get(keyId).getPublicKey();
    }
}
