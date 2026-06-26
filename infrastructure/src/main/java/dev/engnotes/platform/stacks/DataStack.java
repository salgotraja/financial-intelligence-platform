package dev.engnotes.platform.stacks;

import java.util.List;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.kms.KeySpec;
import software.amazon.awscdk.services.kms.KeyUsage;
import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.sns.*;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.constructs.Construct;

/**
 * Data Stack - the stateful, persistent half of the platform.
 *
 * <p>Holds everything that must survive across deploys: the KMS key, the single DynamoDB table, the
 * S3 data lake, and the SNS alert topic (so the email subscription is confirmed once, not on every
 * redeploy). It carries no NAT, endpoints, or compute, so it costs almost nothing at rest and is
 * meant to stay deployed while {@link NetworkStack}, {@link IngestionStack}, and {@link QueryStack}
 * are torn down between work sessions to avoid idle NAT/endpoint/provisioned-concurrency cost.
 *
 * <p>The KMS key encrypts the table, the bucket, and the topic, so all four live together here. In
 * dev the table/bucket/key are {@code DESTROY} (throwaway data, clean teardown); in prod they are
 * {@code RETAIN}.
 */
public class DataStack extends Stack {

    private final Key encryptionKey;
    private final Table platformTable;
    private final IBucket dataLakeBucket;
    private final Topic alertTopic;

    public DataStack(final Construct scope, final String id, final StackProps props, final String env) {
        super(scope, id, props);

        boolean prod = env.equals("prod");
        RemovalPolicy statefulRemoval = prod ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY;

        // KMS Key - one customer-managed key for all data at rest. Rotation enabled (annual).
        this.encryptionKey = Key.Builder.create(this, "PlatformKey")
                .description("Encryption key for Financial Intelligence Platform - " + env)
                .keySpec(KeySpec.SYMMETRIC_DEFAULT)
                .keyUsage(KeyUsage.ENCRYPT_DECRYPT)
                .enableKeyRotation(true)
                // dev DESTROY avoids orphaned keys piling up across teardown cycles.
                .removalPolicy(statefulRemoval)
                .build();

        Tags.of(encryptionKey).add("component", "security");
        Tags.of(encryptionKey).add("env", env);

        // DynamoDB: single-table design (spec section 4)
        // One table overloads every operational entity onto generic PK/SK:
        //   market data point  PK=TICKER#{ticker}  SK=TS#{iso8601}      (ttl ~24h)
        //   anomaly baseline    PK=TICKER#{ticker}  SK=BASELINE          (no ttl)
        //   insight (latest)    PK=TICKER#{ticker}  SK=INSIGHT#{iso8601} (ttl 7d)
        //   bedrock cost        PK=COST#{yyyy-MM-dd} SK=INVOKE#... | TOTAL (ttl 7d)
        // On-demand billing: pay per request, no capacity planning needed.
        this.platformTable = Table.Builder.create(this, "PlatformTable")
                .tableName("financial-platform-" + env)
                .partitionKey(Attribute.builder()
                        .name("PK")
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name("SK")
                        .type(AttributeType.STRING)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .encryption(TableEncryption.CUSTOMER_MANAGED)
                .encryptionKey(encryptionKey)
                .pointInTimeRecovery(true) // PITR for disaster recovery
                .timeToLiveAttribute("ttl") // auto-expire hot data
                .removalPolicy(statefulRemoval)
                .build();

        // GSI1 (insight-by-ticker): reserved for the by-ticker insight feed once correlation
        // grouping moves insights under GROUP# keys. Empty until items carry GSI1PK/GSI1SK.
        platformTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("GSI1")
                .partitionKey(Attribute.builder()
                        .name("GSI1PK")
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name("GSI1SK")
                        .type(AttributeType.STRING)
                        .build())
                .projectionType(ProjectionType.ALL)
                .build());

        // S3 Data Lake - raw market data archived from DynamoDB, partitioned for Athena.
        this.dataLakeBucket = Bucket.Builder.create(this, "DataLakeBucket")
                .bucketName("financial-platform-datalake-" + env + "-" + this.getAccount())
                .versioned(true)
                .encryption(BucketEncryption.KMS)
                .encryptionKey(encryptionKey)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL) // non-negotiable
                .enforceSsl(true)
                .lifecycleRules(List.of(LifecycleRule.builder()
                        .id("move-to-intelligent-tiering")
                        .transitions(List.of(Transition.builder()
                                .storageClass(StorageClass.INTELLIGENT_TIERING)
                                .transitionAfter(Duration.days(30))
                                .build()))
                        .build()))
                .removalPolicy(statefulRemoval)
                .autoDeleteObjects(!prod)
                .build();

        // SNS Alert Topic - all CloudWatch alarms publish here. Lives in the persistent stack so the
        // email subscription is confirmed once and survives compute/network teardowns.
        this.alertTopic = Topic.Builder.create(this, "AlertTopic")
                .topicName("financial-platform-alerts-" + env)
                .masterKey(encryptionKey)
                .build();

        // SNS emails the endpoint a confirmation link that the recipient must click; CDK cannot
        // auto-confirm email subscriptions. Point it at an inbox you control with
        // `--context alertEmail=you@example.com`.
        Object alertEmailContext = this.getNode().tryGetContext("alertEmail");
        String alertEmail = alertEmailContext != null ? alertEmailContext.toString() : "alerts@engnotes.dev";
        alertTopic.addSubscription(EmailSubscription.Builder.create(alertEmail).build());

        // Outputs - dependent stacks import these by name.
        new CfnOutput(
                this,
                "EncryptionKeyArn",
                CfnOutputProps.builder()
                        .exportName("platform-kms-key-arn-" + env)
                        .value(encryptionKey.getKeyArn())
                        .build());

        new CfnOutput(
                this,
                "PlatformTableName",
                CfnOutputProps.builder()
                        .exportName("platform-table-" + env)
                        .value(platformTable.getTableName())
                        .build());

        new CfnOutput(
                this,
                "DataLakeBucketName",
                CfnOutputProps.builder()
                        .exportName("platform-datalake-bucket-" + env)
                        .value(dataLakeBucket.getBucketName())
                        .build());
    }

    public Key getEncryptionKey() {
        return encryptionKey;
    }

    public Table getPlatformTable() {
        return platformTable;
    }

    public IBucket getDataLakeBucket() {
        return dataLakeBucket;
    }

    public Topic getAlertTopic() {
        return alertTopic;
    }
}
