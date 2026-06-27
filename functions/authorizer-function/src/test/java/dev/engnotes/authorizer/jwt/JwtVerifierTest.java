package dev.engnotes.authorizer.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtVerifierTest {

    private static final String ISSUER = "https://cognito-idp.ap-south-1.amazonaws.com/pool-1";
    private static final String CLIENT_ID = "client-123";
    private static final String KID = "test-kid";

    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        SigningKeyResolver resolver = keyId -> publicKey;
        verifier = new JwtVerifier(resolver, ISSUER, CLIENT_ID);
    }

    private String token(String issuer, String clientId, String tokenUse, Instant expiry, List<String> groups) {
        return JWT.create()
                .withKeyId(KID)
                .withIssuer(issuer)
                .withSubject("user-abc")
                .withClaim("client_id", clientId)
                .withClaim("token_use", tokenUse)
                .withClaim("cognito:groups", groups)
                .withExpiresAt(expiry)
                .sign(Algorithm.RSA256(publicKey, privateKey));
    }

    @Test
    void verifiesValidAccessTokenAndExtractsSubAndGroups() {
        String jwt = token(ISSUER, CLIENT_ID, "access", Instant.now().plusSeconds(300), List.of("premium"));
        Principal principal = verifier.verify(jwt);
        assertThat(principal.sub()).isEqualTo("user-abc");
        assertThat(principal.groups()).containsExactly("premium");
    }

    @Test
    void emptyGroupsWhenClaimAbsent() {
        String jwt = token(ISSUER, CLIENT_ID, "access", Instant.now().plusSeconds(300), null);
        assertThat(verifier.verify(jwt).groups()).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        String jwt = token(ISSUER, CLIENT_ID, "access", Instant.now().minusSeconds(60), List.of("premium"));
        assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsWrongIssuer() {
        String jwt = token(
                "https://evil.example.com", CLIENT_ID, "access", Instant.now().plusSeconds(300), List.of("premium"));
        assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsWrongClientId() {
        String jwt = token(ISSUER, "other-client", "access", Instant.now().plusSeconds(300), List.of("premium"));
        assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsIdTokenUse() {
        String jwt = token(ISSUER, CLIENT_ID, "id", Instant.now().plusSeconds(300), List.of("premium"));
        assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(RuntimeException.class);
    }
}
