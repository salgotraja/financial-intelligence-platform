package dev.engnotes.dsr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DSR Lambda - Spring Cloud Function entry point (DPDP, spec sub-project C). The {@code dsr} bean is
 * added in a later task; this class establishes the Spring Boot context and the AWS client beans.
 */
@SpringBootApplication
public class DsrHandler {

    public static void main(String[] args) {
        SpringApplication.run(DsrHandler.class, args);
    }
}
