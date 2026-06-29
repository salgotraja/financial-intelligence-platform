package dev.engnotes.platform.stacks;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;

class DataStackTest {

    private static Template synth() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();
        return Template.fromStack(new DataStack(app, "Data", props, "dev"));
    }

    @Test
    void hasWarningAndCriticalTopics() {
        synth().resourceCountIs("AWS::SNS::Topic", 2);
    }
}
