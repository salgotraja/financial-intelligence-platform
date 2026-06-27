package dev.engnotes.authorizer.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Verifies a Cognito access token: RSA signature against the pool JWKS, issuer, {@code client_id}
 * (the app client), {@code token_use=access}, and expiry (spec section 11). Returns the caller
 * {@link Principal}; throws on any failure so the authorizer can fail closed.
 */
public class JwtVerifier {

    private final SigningKeyResolver keyResolver;
    private final String issuer;
    private final String clientId;

    public JwtVerifier(SigningKeyResolver keyResolver, String issuer, String clientId) {
        this.keyResolver = keyResolver;
        this.issuer = issuer;
        this.clientId = clientId;
    }

    public Principal verify(String token) {
        try {
            String keyId = JWT.decode(token).getKeyId();
            RSAPublicKey publicKey = keyResolver.resolve(keyId);
            Algorithm algorithm = Algorithm.RSA256(publicKey, null);
            DecodedJWT jwt = JWT.require(algorithm)
                    .withIssuer(issuer)
                    .withClaim("client_id", clientId)
                    .withClaim("token_use", "access")
                    .build()
                    .verify(token);
            List<String> groups = jwt.getClaim("cognito:groups").asList(String.class);
            return new Principal(jwt.getSubject(), groups == null ? List.of() : groups);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("JWT verification failed", e);
        }
    }
}
