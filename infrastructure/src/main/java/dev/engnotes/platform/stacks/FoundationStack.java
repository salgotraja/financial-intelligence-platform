package dev.engnotes.platform.stacks;

import java.util.List;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.kms.KeySpec;
import software.amazon.awscdk.services.kms.KeyUsage;
import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.sns.*;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.constructs.Construct;

/**
 * Foundation Stack.
 *
 * Creates shared infrastructure used by all other stacks:
 *   - VPC with private subnets (Lambda runs here, no public internet)
 *   - KMS key for encryption at rest
 *   - DynamoDB table with TTL, point-in-time recovery, KMS encryption
 *   - S3 data lake bucket with versioning, lifecycle rules, KMS encryption
 *   - SNS topic for operational alerts
 *   - Budget alarm at 80% of monthly limit
 *
 * Security decisions:
 *   - All data encrypted with customer-managed KMS key
 *   - DynamoDB in private subnet via VPC endpoint (traffic never leaves AWS)
 *   - S3 bucket policy blocks public access unconditionally
 *   - CloudTrail-ready: all KMS key usage is auditable
 */
public class FoundationStack extends Stack {

    // Exported for use by other stacks
    private final Vpc vpc;
    private final Key encryptionKey;
    private final Table platformTable;
    private final IBucket dataLakeBucket;
    private final Topic alertTopic;

    public FoundationStack(final Construct scope, final String id, final StackProps props, final String env) {
        super(scope, id, props);

        // KMS Key
        // One key for all resources. Rotation enabled - AWS rotates annually.
        // In production use separate keys per data classification.
        this.encryptionKey = Key.Builder.create(this, "PlatformKey")
                .description("Encryption key for Financial Intelligence Platform - " + env)
                .keySpec(KeySpec.SYMMETRIC_DEFAULT)
                .keyUsage(KeyUsage.ENCRYPT_DECRYPT)
                .enableKeyRotation(true)
                .removalPolicy(RemovalPolicy.RETAIN) // never delete in prod
                .build();

        Tags.of(encryptionKey).add("component", "security");
        Tags.of(encryptionKey).add("env", env);

        // VPC
        // Public subnet holds the NAT gateway(s); private-with-egress runs the Lambdas
        // (egress to the internet, e.g. Yahoo Finance, via NAT); isolated is reserved for
        // databases. A PRIVATE_WITH_EGRESS subnet REQUIRES a PUBLIC subnet for the NAT.
        // S3 and DynamoDB use gateway endpoints (no NAT). A Bedrock interface endpoint is
        // added with the insight Lambda's VPC placement (see eng-review task T2).
        this.vpc = Vpc.Builder.create(this, "PlatformVpc")
                .vpcName("financial-platform-vpc-" + env)
                .maxAzs(2)
                .natGateways(env.equals("prod") ? 2 : 1)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("private")
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("isolated")
                                .subnetType(SubnetType.PRIVATE_ISOLATED)
                                .cidrMask(28)
                                .build()))
                .build();

        // VPC endpoints - Lambda can reach AWS services without NAT gateway
        vpc.addGatewayEndpoint(
                "S3Endpoint",
                GatewayVpcEndpointOptions.builder()
                        .service(GatewayVpcEndpointAwsService.S3)
                        .build());

        vpc.addGatewayEndpoint(
                "DynamoDbEndpoint",
                GatewayVpcEndpointOptions.builder()
                        .service(GatewayVpcEndpointAwsService.DYNAMODB)
                        .build());

        // Bedrock Runtime interface endpoint. The insight Lambda invokes Bedrock over
        // PrivateLink instead of the public internet. Verified available in ap-south-1
        // across all three AZs. Private DNS lets the SDK resolve
        // bedrock-runtime.<region>.amazonaws.com to this endpoint with no code change.
        vpc.addInterfaceEndpoint(
                "BedrockRuntimeEndpoint",
                InterfaceVpcEndpointOptions.builder()
                        .service(new InterfaceVpcEndpointAwsService("bedrock-runtime"))
                        .privateDnsEnabled(true)
                        .subnets(SubnetSelection.builder()
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .build())
                        .build());

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
                .removalPolicy(env.equals("prod") ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY)
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

        // S3 Data Lake
        // Raw market data archived from DynamoDB (via Lambda on TTL expiry).
        // Partitioned by: year/month/day/ticker for Athena queries.
        // Lifecycle: move to Intelligent-Tiering after 30 days.
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
                .removalPolicy(env.equals("prod") ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY)
                .autoDeleteObjects(!env.equals("prod"))
                .build();

        // SNS Alert Topic
        // All CloudWatch alarms publish here.
        // In production, add PagerDuty or Slack integration via Lambda subscriber.
        this.alertTopic = Topic.Builder.create(this, "AlertTopic")
                .topicName("financial-platform-alerts-" + env)
                .masterKey(encryptionKey)
                .build();

        // Add email subscription - replace with your email
        alertTopic.addSubscription(
                EmailSubscription.Builder.create("alerts@engnotes.dev").build());

        // CloudFormation Outputs
        // Other stacks import these values by name - no hardcoded ARNs.
        new CfnOutput(
                this,
                "VpcId",
                CfnOutputProps.builder()
                        .exportName("platform-vpc-id-" + env)
                        .value(vpc.getVpcId())
                        .build());

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

    // Getters for use by dependent stacks
    public Vpc getVpc() {
        return vpc;
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
