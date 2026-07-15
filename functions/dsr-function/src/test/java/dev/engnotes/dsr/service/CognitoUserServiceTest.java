package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

@ExtendWith(MockitoExtension.class)
class CognitoUserServiceTest {

    private static final String POOL_ID = "ap-south-1_test";

    @Mock
    private CognitoIdentityProviderClient cognito;

    private CognitoUserService service;

    @BeforeEach
    void setUp() {
        service = new CognitoUserService(cognito, POOL_ID);
    }

    @Test
    void deleteBySubResolvesUsernameThenDeletes() {
        when(cognito.listUsers(any(ListUsersRequest.class)))
                .thenReturn(ListUsersResponse.builder()
                        .users(UserType.builder().username("jdoe").build())
                        .build());

        boolean deleted = service.deleteBySub("user-1");

        assertThat(deleted).isTrue();
        ArgumentCaptor<ListUsersRequest> listCaptor = ArgumentCaptor.forClass(ListUsersRequest.class);
        verify(cognito).listUsers(listCaptor.capture());
        assertThat(listCaptor.getValue().filter()).isEqualTo("sub = \"user-1\"");
        assertThat(listCaptor.getValue().userPoolId()).isEqualTo(POOL_ID);

        ArgumentCaptor<AdminDeleteUserRequest> delCaptor = ArgumentCaptor.forClass(AdminDeleteUserRequest.class);
        verify(cognito).adminDeleteUser(delCaptor.capture());
        assertThat(delCaptor.getValue().username()).isEqualTo("jdoe");
        assertThat(delCaptor.getValue().userPoolId()).isEqualTo(POOL_ID);
    }

    @Test
    void deleteBySubIsNoOpWhenUserAbsent() {
        when(cognito.listUsers(any(ListUsersRequest.class)))
                .thenReturn(ListUsersResponse.builder().users(List.of()).build());

        boolean deleted = service.deleteBySub("ghost");

        assertThat(deleted).isFalse();
        verify(cognito, never()).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    void findEmailBySubReturnsEmailAttribute() {
        when(cognito.listUsers(any(ListUsersRequest.class)))
                .thenReturn(ListUsersResponse.builder()
                        .users(UserType.builder()
                                .username("jdoe")
                                .attributes(
                                        AttributeType.builder()
                                                .name("sub")
                                                .value("user-1")
                                                .build(),
                                        AttributeType.builder()
                                                .name("email")
                                                .value("jdoe@example.com")
                                                .build())
                                .build())
                        .build());

        String email = service.findEmailBySub("user-1");

        assertThat(email).isEqualTo("jdoe@example.com");
    }

    @Test
    void findEmailBySubReturnsNullWhenUserAbsent() {
        when(cognito.listUsers(any(ListUsersRequest.class)))
                .thenReturn(ListUsersResponse.builder().users(List.of()).build());

        assertThat(service.findEmailBySub("ghost")).isNull();
    }

    @Test
    void findEmailBySubReturnsNullWhenEmailAttributeMissing() {
        when(cognito.listUsers(any(ListUsersRequest.class)))
                .thenReturn(ListUsersResponse.builder()
                        .users(UserType.builder().username("jdoe").build())
                        .build());

        assertThat(service.findEmailBySub("user-1")).isNull();
    }
}
