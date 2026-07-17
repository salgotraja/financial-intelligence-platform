package dev.engnotes.query.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.query.model.QueryRequest;
import dev.engnotes.query.model.StoryResponse;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@SpringBootTest
class StoriesIT extends AbstractLocalStackIT {

    @Autowired
    Function<QueryRequest, StoryResponse> serveStory;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void seedDay(String ticker, String day, String close, String previousClose, String volume) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK", AttributeValue.builder().s("DAY#" + day).build(),
                        "day", AttributeValue.builder().s(day).build(),
                        "open", AttributeValue.builder().n("100.00").build(),
                        "high", AttributeValue.builder().n("112.00").build(),
                        "low", AttributeValue.builder().n("98.00").build(),
                        "close", AttributeValue.builder().n(close).build(),
                        "previousClose",
                                AttributeValue.builder().n(previousClose).build(),
                        "volume", AttributeValue.builder().n(volume).build()))
                .build());
    }

    private void seedInsight(String ticker, String generatedAt) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK",
                                AttributeValue.builder()
                                        .s("INSIGHT#" + generatedAt)
                                        .build(),
                        "generatedAt", AttributeValue.builder().s(generatedAt).build(),
                        "signal", AttributeValue.builder().s("BULLISH").build(),
                        "confidence", AttributeValue.builder().n("0.4").build(),
                        "rationale",
                                AttributeValue.builder().s("sustained momentum").build(),
                        "source", AttributeValue.builder().s("RULE_BASED").build(),
                        "drivers",
                                AttributeValue.builder()
                                        .l(AttributeValue.builder()
                                                .s("volume 2.1x baseline")
                                                .build())
                                        .build()))
                .build());
    }

    private void seedLatestPoint(String ticker) {
        String timestamp = "2026-07-16T09:59:00Z";
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK", AttributeValue.builder().s("TS#" + timestamp).build(),
                        "timestamp", AttributeValue.builder().s(timestamp).build(),
                        "price", AttributeValue.builder().n("110.00").build()))
                .build());
    }

    @Test
    void richFixtureComposesFullStory() {
        seedDay("INFY.NS", "2026-07-14", "102.00", "100.00", "40000");
        seedDay("INFY.NS", "2026-07-15", "106.00", "102.00", "45000");
        seedDay("INFY.NS", "2026-07-16", "110.00", "106.00", "90000"); // volume well above prior average
        seedInsight("INFY.NS", "2026-07-16T05:00:00Z");
        seedLatestPoint("INFY.NS");

        StoryResponse response = serveStory.apply(new QueryRequest("INFY.NS", "corr-s-1"));

        assertThat(response.found()).isTrue();
        assertThat(response.source()).isEqualTo("RULE_BASED");
        assertThat(response.inputs().days()).isEqualTo(3);
        assertThat(response.inputs().insightCount()).isEqualTo(1);
        assertThat(response.story()).contains("INFY.NS");
        assertThat(response.story()).contains("up"); // trend sentence: closes rose over the window
        assertThat(response.story()).contains("BULLISH"); // insight sentence
        assertThat(response.story()).contains("52-week range");
        assertThat(response.story()).doesNotContain("Over the past quarter");
    }

    @Test
    void sparseFixtureFallsBackWithoutFailing() {
        StoryResponse response = serveStory.apply(new QueryRequest("EMPTY.NS", "corr-s-2"));

        assertThat(response.found()).isFalse();
        assertThat(response.story()).contains("Not enough history yet");
        assertThat(response.inputs().days()).isZero();
        assertThat(response.inputs().insightCount()).isZero();
    }
}
