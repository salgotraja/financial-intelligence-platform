package dev.engnotes.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.engnotes.notifier.service.ConnectionRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class NotifierHandlerTest {

    @Mock
    private ConnectionRegistry registry;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private Map<String, Object> event(String routeKey, String connectionId, String body) {
        return body == null
                ? Map.of("requestContext", Map.of("routeKey", routeKey, "connectionId", connectionId))
                : Map.of("requestContext", Map.of("routeKey", routeKey, "connectionId", connectionId), "body", body);
    }

    @Test
    void connectIsANoOp200() {
        var response =
                new NotifierHandler().manageConnection(registry, mapper).apply(event("$connect", "conn-1", null));

        assertThat(response).containsEntry("statusCode", 200);
        verifyNoInteractions(registry);
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
        verifyNoInteractions(registry);
    }

    @Test
    void malformedRequestContextReturns400() {
        var response =
                new NotifierHandler().manageConnection(registry, mapper).apply(Map.of("requestContext", "not-a-map"));

        assertThat(response).containsEntry("statusCode", 400);
        verifyNoInteractions(registry);
    }
}
