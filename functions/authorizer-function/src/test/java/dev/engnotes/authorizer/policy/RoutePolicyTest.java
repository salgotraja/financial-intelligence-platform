package dev.engnotes.authorizer.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutePolicyTest {

    @Test
    void readersGetOnlyReadRules() {
        List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(Set.of("readers"));
        assertThat(rules)
                .extracting(RoutePolicy.Rule::resourcePattern)
                .containsExactlyInAnyOrder("insights/*", "market/*");
    }

    @Test
    void premiumGetsReadAndWriteRules() {
        List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(Set.of("premium"));
        assertThat(rules)
                .extracting(rule -> rule.httpMethod() + " " + rule.resourcePattern())
                .containsExactlyInAnyOrder(
                        "GET insights/*",
                        "GET market/*",
                        "GET watchlist",
                        "POST watchlist/*",
                        "DELETE watchlist/*",
                        "POST ingest/*");
    }

    @Test
    void adminsGetEverything() {
        List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(Set.of("admins"));
        assertThat(rules).hasSize(6);
    }

    @Test
    void unknownOrEmptyGroupsGetNothing() {
        assertThat(RoutePolicy.allowedRules(Set.of())).isEmpty();
        assertThat(RoutePolicy.allowedRules(Set.of("guests"))).isEmpty();
    }
}
