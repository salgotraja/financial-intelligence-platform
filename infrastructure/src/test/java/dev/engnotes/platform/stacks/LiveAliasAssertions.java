package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awscdk.assertions.Template;

/**
 * Shared CDK-template assertions used by more than one stack test, so the check logic for two
 * platform-wide SnapStart invariants lives in exactly one place instead of being copy-pasted per
 * stack:
 *
 * <ol>
 *   <li>no Lambda function with SnapStart enabled has an alias carrying
 *       {@code ProvisionedConcurrencyConfig} (AWS rejects the combination at deploy time);
 *   <li>every {@code arn:...:lambda:path/2015-03-31/functions/{alias}/invocations} URI (API
 *       Gateway method integrations and TOKEN/REQUEST authorizers, REST and WebSocket) targets a
 *       published "live" alias, never {@code $LATEST}, so SnapStart actually engages.
 * </ol>
 */
final class LiveAliasAssertions {

    private LiveAliasAssertions() {}

    /** Item 7: no SnapStart function's alias also carries provisioned concurrency, in this template. */
    @SuppressWarnings("unchecked")
    static void assertNoSnapStartFunctionCombinesWithProvisionedConcurrency(String stackLabel, Template template) {
        var functions = template.findResources("AWS::Lambda::Function");
        var snapStartFunctionLogicalIds = functions.entrySet().stream()
                .filter(e -> e.getValue().get("Properties") != null
                        && ((Map<String, Object>) e.getValue().get("Properties")).get("SnapStart") != null)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());

        var aliases = template.findResources("AWS::Lambda::Alias");
        var provisionedAliases = aliases.entrySet().stream()
                .filter(e -> {
                    var props = (Map<String, Object>) e.getValue().get("Properties");
                    return props.get("ProvisionedConcurrencyConfig") != null;
                })
                .toList();

        for (var entry : provisionedAliases) {
            var props = (Map<String, Object>) entry.getValue().get("Properties");
            var functionRef = (Map<String, Object>) props.get("FunctionName");
            var functionLogicalId = (String) functionRef.get("Ref");
            assertFalse(
                    snapStartFunctionLogicalIds.contains(functionLogicalId),
                    "[" + stackLabel + "] alias " + entry.getKey()
                            + " combines provisioned concurrency with SnapStart function " + functionLogicalId);
        }
    }

    /**
     * Item 3 / item 5: asserts a {@code Fn::Join} Lambda-invocation URI (API GW integration Uri or
     * authorizer AuthorizerUri) ends by invoking a published "live" alias: the URI's last array
     * element must be the literal {@code /invocations} and the element before it a {@code Ref} to an
     * {@code AWS::Lambda::Alias} resource named {@code live}. Rejects a raw Function Ref (which
     * resolves to the unqualified, $LATEST-serving ARN) and any alias not named "live".
     */
    @SuppressWarnings("unchecked")
    static void assertUriTargetsLiveAlias(
            String label, Map<String, Object> uri, Map<String, Map<String, Object>> aliasResources) {
        assertNotNull(uri, "expected a URI on " + label);
        var joinArgs = (List<Object>) uri.get("Fn::Join");
        assertNotNull(joinArgs, "expected an Fn::Join URI on " + label + ": " + uri);
        var parts = (List<Object>) joinArgs.get(1);
        assertEquals(
                "/invocations",
                parts.get(parts.size() - 1),
                "expected " + label + " URI to end with /invocations: " + uri);

        var aliasRefObj = parts.get(parts.size() - 2);
        assertTrue(aliasRefObj instanceof Map, "expected " + label + " URI to target a Ref: " + uri);
        var aliasLogicalId = (String) ((Map<String, Object>) aliasRefObj).get("Ref");
        assertNotNull(aliasLogicalId, "expected " + label + " URI to Ref a Lambda alias: " + uri);

        var aliasResource = aliasResources.get(aliasLogicalId);
        assertNotNull(
                aliasResource,
                "[" + label + "] URI targets " + aliasLogicalId + ", which is not an AWS::Lambda::Alias resource: "
                        + uri);
        var aliasProps = (Map<String, Object>) aliasResource.get("Properties");
        assertEquals(
                "live",
                aliasProps.get("Name"),
                "[" + label + "] URI must target the live alias, not " + aliasLogicalId);
    }

    /**
     * True if the URI's {@code Fn::Join} literal segments include the Lambda-invoke path, as
     * opposed to, e.g., a Step Functions {@code StartExecution} service integration (which is also
     * {@code Type: AWS} but carries no alias/invocations suffix at all).
     */
    @SuppressWarnings("unchecked")
    static boolean isLambdaInvocationUri(Object uri) {
        if (!(uri instanceof Map)) {
            return false;
        }
        var joinArgs = (List<Object>) ((Map<String, Object>) uri).get("Fn::Join");
        if (joinArgs == null) {
            return false;
        }
        var parts = (List<Object>) joinArgs.get(1);
        return parts.stream().anyMatch(p -> p instanceof String s && s.contains("lambda:path/2015-03-31/functions/"));
    }
}
