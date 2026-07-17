package dev.engnotes.notifier.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.notifier.service.ConnectionRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

class ConnectionRegistryIT extends AbstractLocalStackIT {

    private static final String CONNECTIONS_TABLE = "financial-connections-test";

    private ConnectionRegistry registry;

    @BeforeEach
    void createConnectionsTableAndRegistry() {
        // Not part of PlatformSchemaProvisioner (it mirrors DataStack; connections live in
        // RealtimeStack), so this IT provisions its own copy of the table.
        try {
            ddb().deleteTable(b -> b.tableName(CONNECTIONS_TABLE));
            ddb().waiter().waitUntilTableNotExists(b -> b.tableName(CONNECTIONS_TABLE));
        } catch (ResourceNotFoundException ignored) {
            // first run: nothing to drop
        }
        ddb().createTable(CreateTableRequest.builder()
                .tableName(CONNECTIONS_TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName("ticker")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("connectionId")
                                .attributeType(ScalarAttributeType.S)
                                .build())
                .keySchema(
                        KeySchemaElement.builder()
                                .attributeName("ticker")
                                .keyType(KeyType.HASH)
                                .build(),
                        KeySchemaElement.builder()
                                .attributeName("connectionId")
                                .keyType(KeyType.RANGE)
                                .build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("by-connection")
                        .keySchema(KeySchemaElement.builder()
                                .attributeName("connectionId")
                                .keyType(KeyType.HASH)
                                .build())
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.ALL)
                                .build())
                        .build())
                .build());
        ddb().waiter().waitUntilTableExists(b -> b.tableName(CONNECTIONS_TABLE));
        registry = new ConnectionRegistry(ddb(), CONNECTIONS_TABLE, PlatformSchema.PLATFORM_TABLE);
    }

    private List<Map<String, AttributeValue>> allRows() {
        return ddb().scan(ScanRequest.builder().tableName(CONNECTIONS_TABLE).build())
                .items();
    }

    @Test
    void subscribeWritesOneRowPerTickerWithTtl() {
        long before = Instant.now().getEpochSecond();

        int written = registry.subscribe("conn-1", List.of("INFY.NS", "TCS.NS"));

        assertThat(written).isEqualTo(2);
        var rows = allRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> r.get("ticker").s())).containsExactlyInAnyOrder("INFY.NS", "TCS.NS");
        for (var row : rows) {
            long ttl = Long.parseLong(row.get(PlatformSchema.TTL_ATTRIBUTE).n());
            assertThat(ttl).isBetween(before + 2 * 60 * 60 - 60, before + 2 * 60 * 60 + 60);
        }
    }

    @Test
    void disconnectRemovesOnlyThatConnectionsRowsViaGsi() {
        registry.subscribe("conn-1", List.of("INFY.NS", "TCS.NS"));
        registry.subscribe("conn-2", List.of("INFY.NS"));

        int deleted = registry.disconnect("conn-1");

        assertThat(deleted).isEqualTo(2);
        var remaining = allRows();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().get("connectionId").s()).isEqualTo("conn-2");
    }

    @Test
    void isDeletionPendingReadsTheProfileFlag() {
        ddb().putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#sub-erasing").build(),
                        "SK", AttributeValue.builder().s("PROFILE").build(),
                        "deletionPending", AttributeValue.builder().bool(true).build()))
                .build());

        assertThat(registry.isDeletionPending("sub-erasing")).isTrue();
        assertThat(registry.isDeletionPending("sub-absent")).isFalse();
    }
}
