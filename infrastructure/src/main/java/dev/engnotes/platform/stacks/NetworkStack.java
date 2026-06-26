package dev.engnotes.platform.stacks;

import java.util.List;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.constructs.Construct;

/**
 * Network Stack - the ephemeral, costly half of the platform.
 *
 * <p>Holds the VPC, the NAT gateway(s), the S3/DynamoDB gateway endpoints, and the Bedrock interface
 * endpoint. The NAT and interface endpoint bill 24/7, so this stack is designed to be torn down
 * between work sessions (along with {@link IngestionStack} and {@link QueryStack}) while
 * {@link DataStack} stays up with the data. Recreating it is a single {@code cdk deploy}.
 *
 * <p>Topology: a public subnet for the NAT, PRIVATE_WITH_EGRESS for the Lambdas (internet via NAT,
 * e.g. the Yahoo Finance fetch), and PRIVATE_ISOLATED in reserve. DynamoDB and S3 use free gateway
 * endpoints; Bedrock uses a PrivateLink interface endpoint so the insight Lambda never traverses the
 * public internet to reach the model.
 */
public class NetworkStack extends Stack {

    private final Vpc vpc;

    public NetworkStack(final Construct scope, final String id, final StackProps props, final String env) {
        super(scope, id, props);

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

        // Gateway endpoints (free) keep DynamoDB and S3 traffic off the NAT.
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

        // Bedrock Runtime interface endpoint (PrivateLink). Private DNS lets the SDK resolve
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

        new CfnOutput(
                this,
                "VpcId",
                CfnOutputProps.builder()
                        .exportName("platform-vpc-id-" + env)
                        .value(vpc.getVpcId())
                        .build());
    }

    public Vpc getVpc() {
        return vpc;
    }
}
