package dev.engnotes.platform.stacks;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class QueryStackTest {

    static Template synth() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();
        var data = new DataStack(app, "Data", props, "dev");
        var security = new SecurityStack(app, "Security", props, "dev", data);
        var network = new NetworkStack(app, "Network", props, "dev");
        var ingestion = new IngestionStack(app, "Ingestion", props, "dev", network, data);
        var query = new QueryStack(app, "Query", props, "dev", network, data, ingestion, security);
        return Template.fromStack(query);
    }

    @Test
    void has5xxRatePageAlarm() {
        synth().hasResourceProperties(
                        "AWS::CloudWatch::Alarm", Match.objectLike(Map.of("AlarmName", "financial-api-5xx-rate-dev")));
    }

    @Test
    void hasPlatformDashboard() {
        synth().resourceCountIs("AWS::CloudWatch::Dashboard", 1);
    }
}
