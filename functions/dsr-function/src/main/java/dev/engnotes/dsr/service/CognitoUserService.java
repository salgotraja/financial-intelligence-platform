package dev.engnotes.dsr.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/**
 * Deletes the Cognito identity for a subject as the final, irreversible erasure step. The immutable
 * {@code sub} is mapped to the pool {@code Username} via a {@code ListUsers} filter, then
 * {@code adminDeleteUser} removes it. Absent user is a logged no-op so erasure stays idempotent.
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
        UserType user = cognito
                .listUsers(ListUsersRequest.builder()
                        .userPoolId(userPoolId)
                        .filter("sub = \"" + subjectSub + "\"")
                        .limit(1)
                        .build())
                .users()
                .stream()
                .findFirst()
                .orElse(null);
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
}
