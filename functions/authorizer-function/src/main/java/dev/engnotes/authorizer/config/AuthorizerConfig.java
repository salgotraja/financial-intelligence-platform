package dev.engnotes.authorizer.config;

import dev.engnotes.authorizer.jwt.JwksSigningKeyResolver;
import dev.engnotes.authorizer.jwt.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link JwtVerifier} from Lambda env: the Cognito region, user pool id, and app client
 * id. The issuer is derived as {@code https://cognito-idp.<region>.amazonaws.com/<poolId>}; the
 * JWKS resolver fetches and caches the pool keys. Defaults keep the Spring context loadable in tests.
 */
@Configuration
public class AuthorizerConfig {

    @Bean
    public JwtVerifier jwtVerifier(
            @Value("${COGNITO_REGION:ap-south-1}") String region,
            @Value("${COGNITO_USER_POOL_ID:pool-unset}") String userPoolId,
            @Value("${COGNITO_CLIENT_ID:client-unset}") String clientId)
            throws Exception {
        String issuer = "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
        return new JwtVerifier(new JwksSigningKeyResolver(issuer), issuer, clientId);
    }
}
