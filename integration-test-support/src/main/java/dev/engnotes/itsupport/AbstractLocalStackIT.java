package dev.engnotes.itsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Base class for LocalStack integration tests. Subclasses get a clean data plane before each test
 * and (for @SpringBootTest subclasses) the production client beans repointed at the container via
 * the optional aws.endpoint-url hook. Service-layer ITs can use ddb()/s3() directly.
 */
public abstract class AbstractLocalStackIT {

    private static final DynamoDbClient DDB = LocalStackSupport.dynamoDbClient();
    private static final S3Client S3 = LocalStackSupport.s3Client();

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint-url", LocalStackSupport::endpoint);
        registry.add("aws.region", LocalStackSupport::region);
        registry.add("PLATFORM_TABLE", () -> PlatformSchema.PLATFORM_TABLE);
        registry.add("AUDIT_TABLE", () -> PlatformSchema.AUDIT_TABLE);
        registry.add("DATA_LAKE_BUCKET", () -> PlatformSchema.DATA_LAKE_BUCKET);
    }

    @BeforeEach
    void resetDataPlane() {
        PlatformSchemaProvisioner.reset(DDB, S3);
    }

    protected DynamoDbClient ddb() {
        return DDB;
    }

    protected S3Client s3() {
        return S3;
    }
}
