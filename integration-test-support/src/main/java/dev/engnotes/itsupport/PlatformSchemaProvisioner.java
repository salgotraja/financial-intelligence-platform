package dev.engnotes.itsupport;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Creates and resets the integration-test data plane (mirrors DataStack). Idempotent: reset()
 * drops and recreates both tables and empties the bucket so each test starts clean.
 */
public final class PlatformSchemaProvisioner {

    private PlatformSchemaProvisioner() {}

    public static void reset(DynamoDbClient ddb, S3Client s3) {
        recreatePlatformTable(ddb);
        recreateAuditTable(ddb);
        recreateBucket(s3);
    }

    private static void recreatePlatformTable(DynamoDbClient ddb) {
        dropIfExists(ddb, PlatformSchema.PLATFORM_TABLE);
        ddb.createTable(CreateTableRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        attr(PlatformSchema.PK),
                        attr(PlatformSchema.SK),
                        attr(PlatformSchema.GSI1_PK),
                        attr(PlatformSchema.GSI1_SK))
                .keySchema(key(PlatformSchema.PK, KeyType.HASH), key(PlatformSchema.SK, KeyType.RANGE))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(PlatformSchema.GSI1_NAME)
                        .keySchema(
                                key(PlatformSchema.GSI1_PK, KeyType.HASH), key(PlatformSchema.GSI1_SK, KeyType.RANGE))
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.ALL)
                                .build())
                        .build())
                .build());
        ddb.waiter().waitUntilTableExists(b -> b.tableName(PlatformSchema.PLATFORM_TABLE));
    }

    private static void recreateAuditTable(DynamoDbClient ddb) {
        dropIfExists(ddb, PlatformSchema.AUDIT_TABLE);
        ddb.createTable(CreateTableRequest.builder()
                .tableName(PlatformSchema.AUDIT_TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attr(PlatformSchema.PK), attr(PlatformSchema.SK))
                .keySchema(key(PlatformSchema.PK, KeyType.HASH), key(PlatformSchema.SK, KeyType.RANGE))
                .build());
        ddb.waiter().waitUntilTableExists(b -> b.tableName(PlatformSchema.AUDIT_TABLE));
    }

    private static void dropIfExists(DynamoDbClient ddb, String table) {
        try {
            ddb.deleteTable(b -> b.tableName(table));
            ddb.waiter().waitUntilTableNotExists(b -> b.tableName(table));
        } catch (ResourceNotFoundException ignored) {
            // first run: nothing to drop
        }
    }

    private static void recreateBucket(S3Client s3) {
        try {
            var objects = s3.listObjectsV2(b -> b.bucket(PlatformSchema.DATA_LAKE_BUCKET))
                    .contents();
            if (!objects.isEmpty()) {
                s3.deleteObjects(b -> b.bucket(PlatformSchema.DATA_LAKE_BUCKET)
                        .delete(Delete.builder()
                                .objects(objects.stream()
                                        .map(o -> ObjectIdentifier.builder()
                                                .key(o.key())
                                                .build())
                                        .toList())
                                .build()));
            }
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                s3.createBucket(b -> b.bucket(PlatformSchema.DATA_LAKE_BUCKET));
            } else {
                throw e;
            }
        }
    }

    private static AttributeDefinition attr(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
