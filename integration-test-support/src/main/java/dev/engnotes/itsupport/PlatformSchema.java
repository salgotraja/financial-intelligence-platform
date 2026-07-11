package dev.engnotes.itsupport;

/**
 * Single source of truth for the integration-test data plane. The names here MUST match the
 * DynamoDB schema defined in infrastructure DataStack; the drift guard (PlatformSchemaDriftTest)
 * fails the build if they diverge.
 */
public final class PlatformSchema {

    private PlatformSchema() {}

    public static final String PLATFORM_TABLE = "financial-platform-test";
    public static final String AUDIT_TABLE = "financial-platform-audit-test";
    public static final String DATA_LAKE_BUCKET = "financial-platform-datalake-test";

    public static final String PK = "PK";
    public static final String SK = "SK";
    public static final String GSI1_NAME = "GSI1";
    public static final String GSI1_PK = "GSI1PK";
    public static final String GSI1_SK = "GSI1SK";
    public static final String TTL_ATTRIBUTE = "ttl";
}
