package dev.engnotes.authorizer.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutePolicyTest {

    @Test
    void readersGetReadRulesAndConsentManagement() {
        List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(Set.of("readers"));
        assertThat(rules)
                .extracting(rule -> rule.httpMethod() + " " + rule.resourcePattern())
                .containsExactlyInAnyOrder(
                        "GET insights/*",
                        "GET market-data/*",
                        "GET user/consent",
                        "POST user/consent",
                        "DELETE user/consent",
                        "GET user/export",
                        "DELETE user/account");
    }

    @Test
    void premiumGetsReadWriteAndConsentRules() {
        List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(Set.of("premium"));
        assertThat(rules)
                .extracting(rule -> rule.httpMethod() + " " + rule.resourcePattern())
                .containsExactlyInAnyOrder(
                        "GET insights/*",
                        "GET market-data/*",
                        "GET watchlist",
                        "POST watchlist/*",
                        "DELETE watchlist/*",
                        "POST ingest/*",
                        "GET user/consent",
                        "POST user/consent",
                        "DELETE user/consent",
                        "GET user/export",
                        "DELETE user/account");
    }

    @Test
    void adminsGetEverything() {
        List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(Set.of("admins"));
        assertThat(rules).hasSize(11);
    }

    @Test
    void unknownOrEmptyGroupsGetNothing() {
        assertThat(RoutePolicy.allowedRules(Set.of())).isEmpty();
        assertThat(RoutePolicy.allowedRules(Set.of("guests"))).isEmpty();
    }

    @Test
    void dsrRoutesAllowedForEveryGroup() {
        for (String group : java.util.List.of("readers", "premium", "admins")) {
            java.util.List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(java.util.Set.of(group));
            assertThat(rules)
                    .extracting(r -> r.httpMethod() + " " + r.resourcePattern())
                    .contains("GET user/export", "DELETE user/account");
        }
    }
}
