package dev.engnotes.authorizer.policy;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Group-to-route authorization map (spec section 11). Each rule binds an HTTP method and an API
 * Gateway resource pattern to the user-pool groups allowed to call it. {@code readers} read only;
 * {@code premium} read plus watchlist CRUD and on-demand ingest; {@code admins} everything.
 *
 * <p>{@code resourcePattern} is the API Gateway resource path with {@code *} wildcards for path
 * parameters (e.g. {@code insights/*} matches {@code GET /insights/RELIANCE.NS}). The bare
 * {@code insights} resource is a separate rule: it matches only {@code GET /insights} (the
 * watchlist-scoped feed, no ticker), distinct from the {@code insights/*} per-ticker route. The
 * authorizer expands an allowed rule into a method-ARN resource so the returned policy is reusable
 * across the caller's routes (safe under API Gateway authorizer caching).
 */
public final class RoutePolicy {

    public record Rule(String httpMethod, String resourcePattern, Set<String> allowedGroups) {}

    private static final List<Rule> RULES = List.of(
            new Rule("GET", "insights", Set.of("readers", "premium", "admins")),
            new Rule("GET", "insights/*", Set.of("readers", "premium", "admins")),
            new Rule("GET", "market-data/*", Set.of("readers", "premium", "admins")),
            new Rule("GET", "stories/*", Set.of("readers", "premium", "admins")),
            new Rule("GET", "watchlist", Set.of("premium", "admins")),
            new Rule("POST", "watchlist/*", Set.of("premium", "admins")),
            new Rule("DELETE", "watchlist/*", Set.of("premium", "admins")),
            new Rule("POST", "ingest/*", Set.of("premium", "admins")),
            new Rule("GET", "user/consent", Set.of("readers", "premium", "admins")),
            new Rule("POST", "user/consent", Set.of("readers", "premium", "admins")),
            new Rule("DELETE", "user/consent", Set.of("readers", "premium", "admins")),
            new Rule("GET", "user/export", Set.of("readers", "premium", "admins")),
            new Rule("DELETE", "user/account", Set.of("readers", "premium", "admins")));

    private RoutePolicy() {}

    /** Returns the rules the given groups may call (a caller is allowed if in any rule's group set). */
    public static List<Rule> allowedRules(Collection<String> groups) {
        return RULES.stream()
                .filter(rule -> rule.allowedGroups().stream().anyMatch(groups::contains))
                .toList();
    }
}
