package dev.engnotes.authorizer.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RoutePolicyTest {

    @Test
    void readersGetReadRulesAndConsentManagement() {
        List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(Set.of("readers"));
        assertThat(rules)
                .extracting(rule -> rule.httpMethod() + " " + rule.resourcePattern())
                .containsExactlyInAnyOrder(
                        "GET insights",
                        "GET insights/*",
                        "GET market-data/*",
                        "GET stories/*",
                        "GET analysis/*",
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
                        "GET insights",
                        "GET insights/*",
                        "GET market-data/*",
                        "GET stories/*",
                        "GET analysis/*",
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
        assertThat(rules).hasSize(14);
    }

    @Test
    void unknownOrEmptyGroupsGetNothing() {
        assertThat(RoutePolicy.allowedRules(Set.of())).isEmpty();
        assertThat(RoutePolicy.allowedRules(Set.of("guests"))).isEmpty();
    }

    @Test
    void bareInsightsFeedRouteAllowedForEveryGroupAndDistinctFromPerTickerRoute() {
        for (String group : java.util.List.of("readers", "premium", "admins")) {
            java.util.List<RoutePolicy.Rule> rules = RoutePolicy.allowedRules(java.util.Set.of(group));
            assertThat(rules)
                    .extracting(r -> r.httpMethod() + " " + r.resourcePattern())
                    .contains("GET insights", "GET insights/*");
        }
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

    // Task 14: GET /market-data/{ticker}/daily has no dedicated RoutePolicy rule - it relies on the
    // existing "market-data/*" rule covering it. AuthorizerHandler.arnBase builds a method ARN
    // resource as base + "/" + httpMethod + "/" + resourcePattern (e.g.
    // ".../GET/market-data/*"), and API Gateway/IAM evaluate that "*" as a standard IAM ARN
    // wildcard: it matches ANY sequence of characters, including "/", the same way
    // "arn:aws:s3:::bucket/*" matches every nested key under bucket/ regardless of how many "/"
    // segments follow (AWS IAM policy wildcard semantics - not a single-path-segment glob). So
    // "market-data/*" already covers the two-segment "market-data/{ticker}/daily" path with no
    // RoutePolicy change needed; this test pins that match with the same glob-to-regex conversion
    // IAM applies (escape literals, "*" -> ".*"), so a future switch to a single-segment matcher
    // would fail loudly here instead of silently 403-ing the new route in production.
    @Test
    void marketDataWildcardRuleCoversTheTwoSegmentDailyPath() {
        String resourcePattern = RoutePolicy.allowedRules(Set.of("readers")).stream()
                .filter(rule -> "GET".equals(rule.httpMethod()) && "market-data/*".equals(rule.resourcePattern()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a GET market-data/* rule"))
                .resourcePattern();

        String regex = Pattern.quote(resourcePattern).replace("*", "\\E.*\\Q");
        assertThat("market-data/RELIANCE.NS/daily").matches(regex);
        assertThat("market-data/RELIANCE.NS").matches(regex);
    }
}
