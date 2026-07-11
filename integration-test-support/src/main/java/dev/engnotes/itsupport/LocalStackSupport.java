package dev.engnotes.itsupport;

import java.net.URI;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * One LocalStack container per JVM fork, started on first access and reused across every IT in the
 * fork (Ryuk tears it down at JVM exit). Also publishes dummy credentials as system properties so
 * the production AWS client beans, which use the default credentials chain, resolve them.
 */
public final class LocalStackSupport {

    private static final LocalStackContainer CONTAINER = new LocalStackContainer(
                    DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(Service.DYNAMODB, Service.S3, Service.SECRETSMANAGER);

    static {
        CONTAINER.start();
        // The default credentials provider chain (used by every production client bean) reads these.
        System.setProperty("aws.accessKeyId", CONTAINER.getAccessKey());
        System.setProperty("aws.secretAccessKey", CONTAINER.getSecretKey());
        System.setProperty("aws.region", CONTAINER.getRegion());
    }

    private LocalStackSupport() {}

    public static String endpoint() {
        return CONTAINER.getEndpoint().toString();
    }

    public static String region() {
        return CONTAINER.getRegion();
    }

    public static DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.of(region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(CONTAINER.getAccessKey(), CONTAINER.getSecretKey())))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    public static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.of(region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(CONTAINER.getAccessKey(), CONTAINER.getSecretKey())))
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
