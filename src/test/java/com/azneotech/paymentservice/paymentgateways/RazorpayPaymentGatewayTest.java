package com.azneotech.paymentservice.paymentgateways;

import com.razorpay.RazorpayClient;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RazorpayPaymentGatewayTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private RazorpayPaymentGateway gateway(String callbackUrl) {
        return new RazorpayPaymentGateway(mock(RazorpayClient.class), clock, "INR", Duration.ofHours(24), callbackUrl);
    }

    @Test
    void sendsTheCustomerWithTheNameAndPhoneTheRightWayRound() throws Exception {
        JSONObject customer = gateway("").buildRequest(24220L, "+15551234567", "Alice", "alice@example.com", "ORD-1")
                .getJSONObject("customer");

        assertThat(customer.getString("name")).isEqualTo("Alice");
        assertThat(customer.getString("contact")).isEqualTo("+15551234567");
        assertThat(customer.getString("email")).isEqualTo("alice@example.com");
    }

    @Test
    void identifiesTheOrderAndAsksForTheFullAmountOnly() throws Exception {
        JSONObject request = gateway("").buildRequest(24220L, "1", "Alice", "a@b.c", "ORD-1");

        assertThat(request.getLong("amount")).isEqualTo(24220L);
        assertThat(request.getString("currency")).isEqualTo("INR");
        assertThat(request.getString("reference_id")).isEqualTo("ORD-1");
        assertThat(request.getString("description")).isEqualTo("Payment for order ORD-1");
        assertThat(request.getJSONObject("notes").getString("order_id")).isEqualTo("ORD-1");
        assertThat(request.getBoolean("accept_partial")).isFalse();
        assertThat(request.has("first_min_partial_amount")).isFalse();
    }

    @Test
    void theLinkExpiresARelativeTimeAfterNowNotAtAFixedDate() throws Exception {
        JSONObject request = gateway("").buildRequest(100L, "1", "Alice", "a@b.c", "ORD-1");

        assertThat(request.getLong("expire_by")).isEqualTo(NOW.plus(Duration.ofHours(24)).getEpochSecond());
    }

    @Test
    void theCallbackIsOnlySentWhenConfigured() throws Exception {
        assertThat(gateway("").buildRequest(100L, "1", "A", "a@b.c", "ORD-1").has("callback_url")).isFalse();

        JSONObject withCallback = gateway("https://shop.example/paid").buildRequest(100L, "1", "A", "a@b.c", "ORD-1");
        assertThat(withCallback.getString("callback_url")).isEqualTo("https://shop.example/paid");
        assertThat(withCallback.getString("callback_method")).isEqualTo("get");
    }

    @Test
    void carriesNoPlaceholderText() throws Exception {
        String request = gateway("").buildRequest(100L, "1", "A", "a@b.c", "ORD-1").toString();

        assertThat(request).doesNotContain("policy").doesNotContain("example-callback-url");
    }

    @Test
    void isNotTheDefaultGatewayStripeIs() throws Exception {
        assertThat(RazorpayPaymentGateway.class.isAnnotationPresent(Primary.class)).isFalse();
        assertThat(StripePaymentGateway.class.isAnnotationPresent(Primary.class)).isTrue();
    }

}
