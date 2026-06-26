package dev.engnotes.query;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the full Spring context. Guards against context-wiring failures the unit tests miss
 * (they call beans directly): for example the {@code queryHandler} class-vs-@Bean name collision
 * that only surfaces at context refresh, i.e. at real Lambda init under SnapStart.
 */
@SpringBootTest
class QueryFunctionApplicationTests {

    @Test
    void contextLoads() {}
}
