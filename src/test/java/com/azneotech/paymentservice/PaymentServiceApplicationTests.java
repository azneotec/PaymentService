package com.azneotech.paymentservice;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full-context wiring test. Requires a live MySQL at the configured datasource (Flyway migrations run and
 * Hibernate validates the schema), so it is excluded from the default {@code ./mvnw test}. It also needs
 * RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET (environment or .env).
 * Run it with: {@code ./mvnw test -DexcludedGroups=none -Dgroups=integration}
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/payment_service_test?createDatabaseIfNotExist=true",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false"})
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
