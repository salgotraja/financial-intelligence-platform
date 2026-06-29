package dev.engnotes.platform.stacks;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class IngestionStackTest {

    private static Template synth() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();
        var data = new DataStack(app, "Data", props, "dev");
        var network = new NetworkStack(app, "Network", props, "dev");
        var ingestion = new IngestionStack(app, "Ingestion", props, "dev", network, data);
        return Template.fromStack(ingestion);
    }

    @Test
    void hasPipelineFailedAlarm() {
        synth().hasResourceProperties(
                        "AWS::CloudWatch::Alarm",
                        Match.objectLike(Map.of("AlarmName", "financial-ingestion-pipeline-failed-dev")));
    }

    @Test
    void hasDlqDepthAlarm() {
        synth().hasResourceProperties(
                        "AWS::CloudWatch::Alarm",
                        Match.objectLike(Map.of("AlarmName", "financial-ingestion-dlq-depth-dev")));
    }
}
