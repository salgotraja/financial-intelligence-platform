package dev.engnotes.dsr.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/**
 * Cognito identity lookups for the erasure workflow (spec s11, Task 11). The immutable {@code sub} is
 * mapped to the pool user via a {@code ListUsers} filter, shared by both operations: {@link #deleteBySub}
 * removes the identity as the final, irreversible erasure step (absent user is a logged no-op, so
 * erasure stays idempotent); {@link #findEmailBySub} reads the {@code email} attribute for
 * {@code MarkDeletionPending} to capture, since the address is gone once {@link #deleteBySub} runs.
 */
@Service
public class CognitoUserService {

    private static final Logger log = LoggerFactory.getLogger(CognitoUserService.class);

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    public CognitoUserService(
            CognitoIdentityProviderClient cognito, @Value("${USER_POOL_ID:unset}") String userPoolId) {
        this.cognito = cognito;
        this.userPoolId = userPoolId;
    }

    public boolean deleteBySub(String subjectSub) {
        UserType user = findBySub(subjectSub);
        if (user == null) {
            log.info("Cognito delete no-op (no user for sub). subjectSub={}", subjectSub);
            return false;
        }
        cognito.adminDeleteUser(AdminDeleteUserRequest.builder()
                .userPoolId(userPoolId)
                .username(user.username())
                .build());
        log.info("Deleted Cognito user. subjectSub={} username={}", subjectSub, user.username());
        return true;
    }

    /** Returns the subject's email, or {@code null} if no matching user exists. */
    public String findEmailBySub(String subjectSub) {
        UserType user = findBySub(subjectSub);
        if (user == null) {
            log.info("Cognito email lookup no-op (no user for sub). subjectSub={}", subjectSub);
            return null;
        }
        return user.attributes().stream()
                .filter(a -> "email".equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }

    private UserType findBySub(String subjectSub) {
        return cognito
                .listUsers(ListUsersRequest.builder()
                        .userPoolId(userPoolId)
                        .filter("sub = \"" + subjectSub + "\"")
                        .limit(1)
                        .build())
                .users()
                .stream()
                .findFirst()
                .orElse(null);
    }
}
