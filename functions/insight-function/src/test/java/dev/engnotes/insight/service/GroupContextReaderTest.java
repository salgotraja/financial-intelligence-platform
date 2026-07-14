package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.CorrelationGroup;
import dev.engnotes.insight.model.GroupInsightContext;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.MemberSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

@ExtendWith(MockitoExtension.class)
class GroupContextReaderTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private GroupContextReader reader;

    @BeforeEach
    void setUp() {
        reader = new GroupContextReader(dynamoDb, TABLE);
    }

    @Test
    void buildsSnapshotsFromLatestTsPointAndBaselineForEachMember() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(latestPoint("RELIANCE.NS", "2900", "3.2", 1200L))
                .thenReturn(latestPoint("TCS.NS", "3900", "2.8", 900L));
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(baseline(800.0, 4.0, 50.0))
                .thenReturn(baseline(700.0, 6.0, 45.0));

        CorrelationGroup group = new CorrelationGroup(
                "g1",
                List.of("RELIANCE.NS", "TCS.NS"),
                List.of(new CorrelationEdge("RELIANCE.NS", "TCS.NS", 0.82)),
                "30-point window",
                "2026-07-14T10:15:00Z");
        InsightRequest trigger = new InsightRequest();
        trigger.setTicker("RELIANCE.NS");
        trigger.setAnomalyReason("return z=5.20");

        GroupInsightContext context = reader.buildContext(group, trigger);

        assertThat(context.groupId()).isEqualTo("g1");
        assertThat(context.tickers()).containsExactly("RELIANCE.NS", "TCS.NS");
        assertThat(context.triggeringTicker()).isEqualTo("RELIANCE.NS");
        assertThat(context.anomalyReason()).isEqualTo("return z=5.20");
        assertThat(context.pairwiseRhos()).hasSize(1);

        MemberSnapshot reliance = context.members().get(0);
        assertThat(reliance.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(reliance.price().toPlainString()).isEqualTo("2900");
        assertThat(reliance.changePercent().toPlainString()).isEqualTo("3.2");
        assertThat(reliance.volume()).isEqualTo(1200L);
        assertThat(reliance.baselineVolumeMean()).isEqualTo(800.0);
        assertThat(reliance.baselineVolumeStdDev()).isGreaterThan(0.0);
    }

    @Test
    void missingDataYieldsNullSnapshotFieldsInsteadOfThrowing() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        CorrelationGroup group = new CorrelationGroup("g1", List.of("WIPRO.NS"), List.of(), "window", "computedAt");
        InsightRequest trigger = new InsightRequest();
        trigger.setTicker("WIPRO.NS");

        GroupInsightContext context = reader.buildContext(group, trigger);

        MemberSnapshot snapshot = context.members().getFirst();
        assertThat(snapshot.ticker()).isEqualTo("WIPRO.NS");
        assertThat(snapshot.price()).isNull();
        assertThat(snapshot.changePercent()).isNull();
        assertThat(snapshot.volume()).isNull();
        assertThat(snapshot.baselineVolumeMean()).isNull();
        assertThat(snapshot.baselineVolumeStdDev()).isNull();
    }

    @Test
    void readFailureForOneMemberYieldsNullSnapshotAndDoesNotThrow() {
        when(dynamoDb.query(any(QueryRequest.class))).thenThrow(new RuntimeException("dynamo down"));

        CorrelationGroup group = new CorrelationGroup("g1", List.of("WIPRO.NS"), List.of(), "window", "computedAt");
        InsightRequest trigger = new InsightRequest();
        trigger.setTicker("WIPRO.NS");

        GroupInsightContext context = reader.buildContext(group, trigger);

        assertThat(context.members().getFirst().ticker()).isEqualTo("WIPRO.NS");
        assertThat(context.members().getFirst().price()).isNull();
    }

    private static QueryResponse latestPoint(String ticker, String price, String changePercent, long volume) {
        return QueryResponse.builder()
                .items(List.of(Map.of(
                        "ticker", s(ticker),
                        "price", n(price),
                        "changePercent", n(changePercent),
                        "volume", n(String.valueOf(volume)))))
                .build();
    }

    private static GetItemResponse baseline(double volumeMean, double volumeCount, double volumeM2) {
        return GetItemResponse.builder()
                .item(Map.of(
                        "volumeMean", n(String.valueOf(volumeMean)),
                        "volumeCount", n(String.valueOf(volumeCount)),
                        "volumeM2", n(String.valueOf(volumeM2))))
                .build();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
