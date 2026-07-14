package dev.engnotes.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.notifier.service.ConnectionRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class NotifierHandlerTest {

    private static final String SUB = "user-1";

    @Mock
    private ConnectionRegistry registry;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void allowByDefault() {
        lenient().when(registry.isDeletionPending(any())).thenReturn(false);
    }

    private Map<String, Object> event(String routeKey, String connectionId, String body) {
        return event(routeKey, connectionId, body, SUB);
    }

    private Map<String, Object> event(String routeKey, String connectionId, String body, String sub) {
        Map<String, Object> requestContext = new HashMap<>();
        requestContext.put("routeKey", routeKey);
        requestContext.put("connectionId", connectionId);
        if (sub != null) {
            requestContext.put("authorizer", Map.of("sub", sub));
        }
        Map<String, Object> event = new HashMap<>();
        event.put("requestContext", requestContext);
        if (body != null) {
            event.put("body", body);
        }
        return event;
    }

    @Test
    void connectAllowsAndChecksDeletionPending() {
        var response =
                new NotifierHandler().manageConnection(registry, mapper).apply(event("$connect", "conn-1", null));

        assertThat(response).containsEntry("statusCode", 200);
        verify(registry).isDeletionPending(SUB);
        verify(registry, never()).subscribe(any(), any());
        verify(registry, never()).disconnect(any());
    }

    @Test
    void connectRefusesWhenDeletionPending() {
        when(registry.isDeletionPending(SUB)).thenReturn(true);

        var response =
                new NotifierHandler().manageConnection(registry, mapper).apply(event("$connect", "conn-1", null));

        assertThat(response).containsEntry("statusCode", 400);
    }

    @Test
    void subscribeParsesBodyAndRegistersTickers() {
        var response = new NotifierHandler()
                .manageConnection(registry, mapper)
                .apply(event(
                        "subscribe", "conn-1", "{\"action\":\"subscribe\",\"tickers\":[\"RELIANCE.NS\",\"TCS.NS\"]}"));

        assertThat(response).containsEntry("statusCode", 200);
        verify(registry).subscribe("conn-1", List.of("RELIANCE.NS", "TCS.NS"));
    }

    @Test
    void disconnectDeregistersConnection() {
        var response =
                new NotifierHandler().manageConnection(registry, mapper).apply(event("$disconnect", "conn-1", null));

        assertThat(response).containsEntry("statusCode", 200);
        verify(registry).disconnect("conn-1");
    }

    @Test
    void invalidSubscribeBodyReturns400WithoutRegistering() {
        var response = new NotifierHandler()
                .manageConnection(registry, mapper)
                .apply(event("subscribe", "conn-1", "not json"));

        assertThat(response).containsEntry("statusCode", 400);
        verify(registry, never()).subscribe(any(), any());
    }

    @Test
    void subscribeRefusesWhenDeletionPending() {
        when(registry.isDeletionPending(SUB)).thenReturn(true);

        var response = new NotifierHandler()
                .manageConnection(registry, mapper)
                .apply(event("subscribe", "conn-1", "{\"action\":\"subscribe\",\"tickers\":[\"RELIANCE.NS\"]}"));

        assertThat(response).containsEntry("statusCode", 400);
        verify(registry, never()).subscribe(any(), any());
    }

    @Test
    void malformedRequestContextReturns400() {
        var response =
                new NotifierHandler().manageConnection(registry, mapper).apply(Map.of("requestContext", "not-a-map"));

        assertThat(response).containsEntry("statusCode", 400);
        verifyNoInteractions(registry);
    }
}
