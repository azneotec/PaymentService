package com.azneotech.paymentservice.paymentgateways;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StripePaymentGatewayTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private StripePaymentGateway gateway(String redirectUrl) {
        return new StripePaymentGateway("sk_test_x", "inr", redirectUrl);
    }

    @Test
    void priceIsAOneOffInTheConfiguredCurrencyNamedAfterTheOrder() {
        Map<String, Object> price = gateway("").buildPriceParams(24220L, "ORD-1").toMap();

        assertThat(price).containsEntry("currency", "inr").containsEntry("unit_amount", 24220L);
        assertThat(price).doesNotContainKey("recurring");
        assertThat(asMap(price.get("product_data"))).containsEntry("name", "Order ORD-1");
    }

    @Test
    void linkCarriesTheOrderNumberInItsMetadata() {
        Map<String, Object> link = gateway("").buildLinkParams("price_1", "ORD-1").toMap();

        assertThat(asMap(link.get("metadata"))).containsEntry(StripePaymentGateway.ORDER_ID_METADATA, "ORD-1");
        List<?> lines = (List<?>) link.get("line_items");
        assertThat(asMap(lines.get(0))).containsEntry("price", "price_1").containsEntry("quantity", 1L);
    }

    @Test
    void redirectIsOnlySetWhenConfigured() {
        assertThat(gateway("").buildLinkParams("price_1", "ORD-1").toMap()).doesNotContainKey("after_completion");

        Map<String, Object> link = gateway("https://shop.example/thanks").buildLinkParams("price_1", "ORD-1").toMap();
        assertThat(link.get("after_completion").toString()).contains("https://shop.example/thanks");
    }

}
