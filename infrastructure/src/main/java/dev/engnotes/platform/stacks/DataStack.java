package dev.engnotes.platform.stacks;

import java.util.List;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.budgets.CfnBudget;
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
    private final Table auditTable;
    private final IBucket dataLakeBucket;
    private final Topic alertTopic;
    private final Topic criticalTopic;

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
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true)
                        .build()) // PITR for disaster recovery
                .timeToLiveAttribute("ttl") // auto-expire hot data
                // Realtime feed (spec 2026-07-12): notifier Lambda consumes INSERTed INSIGHT# items.
                .stream(StreamViewType.NEW_IMAGE)
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

        // Append-only consent audit table (spec sub-project B). Separate from the single table so the
        // app role can hold PutItem only (no Update/Delete) for tamper-evidence. RETAIN in every env
        // (audit records must survive stack deletion); PITR on; no TTL (events never expire). Reused
        // by sub-project C (right-to-access / erasure logging).
        this.auditTable = Table.Builder.create(this, "AuditTable")
                .tableName("financial-platform-audit-" + env)
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
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true)
                        .build())
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

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

        // SNS Critical/Page Topic - P1 symptom alarms (API availability, p99 latency) publish here,
        // kept separate from the warning topic so future routing (SMS/PagerDuty) is a subscription
        // change, not a redesign. Same KMS key and email as the warning topic: one inbox today, two
        // channels tomorrow.
        this.criticalTopic = Topic.Builder.create(this, "CriticalTopic")
                .topicName("financial-platform-alerts-critical-" + env)
                .masterKey(encryptionKey)
                .build();
        criticalTopic.addSubscription(
                EmailSubscription.Builder.create(alertEmail).build());

        // Monthly cost budget - codifies the lean learning-account limit. Notify-only (no enforcement).
        // Notifications use EMAIL subscribers (not SNS): the alert topics are KMS-encrypted, and routing
        // Budgets through an encrypted topic would need both an SNS topic policy and a KMS key-policy
        // grant for budgets.amazonaws.com - two silent-failure points for no benefit with one recipient.
        // A Budget is account-scoped; multi-env on one account would double-alert (latent: only dev today).
        // No explicit budgetName: NotificationsWithSubscribers changes force REPLACEMENT, and a fixed
        // name makes the create-before-delete collide with itself (Budgets 400 "different internalId").
        CfnBudget.Builder.create(this, "MonthlyCostBudget")
                .budget(CfnBudget.BudgetDataProperty.builder()
                        .budgetType("COST")
                        .timeUnit("MONTHLY")
                        .budgetLimit(CfnBudget.SpendProperty.builder()
                                .amount(5)
                                .unit("USD")
                                .build())
                        .build())
                .notificationsWithSubscribers(List.of(
                        budgetNotification("ACTUAL", 80, alertEmail),
                        budgetNotification("ACTUAL", 100, alertEmail),
                        budgetNotification("FORECASTED", 100, alertEmail)))
                .build();

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
                "AuditTableName",
                CfnOutputProps.builder()
                        .exportName("platform-audit-table-" + env)
                        .value(auditTable.getTableName())
                        .build());

        new CfnOutput(
                this,
                "DataLakeBucketName",
                CfnOutputProps.builder()
                        .exportName("platform-datalake-bucket-" + env)
                        .value(dataLakeBucket.getBucketName())
                        .build());
    }

    private static CfnBudget.NotificationWithSubscribersProperty budgetNotification(
            final String notificationType, final Number thresholdPct, final String email) {
        return CfnBudget.NotificationWithSubscribersProperty.builder()
                .notification(CfnBudget.NotificationProperty.builder()
                        .notificationType(notificationType)
                        .comparisonOperator("GREATER_THAN")
                        .threshold(thresholdPct)
                        .thresholdType("PERCENTAGE")
                        .build())
                .subscribers(List.of(CfnBudget.SubscriberProperty.builder()
                        .subscriptionType("EMAIL")
                        .address(email)
                        .build()))
                .build();
    }

    public Key getEncryptionKey() {
        return encryptionKey;
    }

    public Table getPlatformTable() {
        return platformTable;
    }

    public Table getAuditTable() {
        return auditTable;
    }

    public IBucket getDataLakeBucket() {
        return dataLakeBucket;
    }

    public Topic getAlertTopic() {
        return alertTopic;
    }

    public Topic getCriticalTopic() {
        return criticalTopic;
    }
}
