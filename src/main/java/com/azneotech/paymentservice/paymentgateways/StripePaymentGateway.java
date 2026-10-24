package com.azneotech.paymentservice.paymentgateways;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentLink;
import com.stripe.model.Price;
import com.stripe.param.PaymentLinkCreateParams;
import com.stripe.param.PriceCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Creates a one-time Stripe payment link for an order. The order number is stored in the link's metadata, which
 * Stripe copies onto the checkout session, so {@code StripeWebhookController} can tell which order was paid.
 * Not the default gateway (see {@link RazorpayPaymentGateway}); inject it by type to use it.
 */
@Component
public class StripePaymentGateway implements IPaymentGateway {

    /** Metadata key holding the order number on the payment link and its checkout sessions. */
    public static final String ORDER_ID_METADATA = "orderId";

    private final String stripeApiKey;
    private final String currency;
    private final String redirectUrl;

    public StripePaymentGateway(
            @Value("${stripe.apiKey:}") String stripeApiKey,
            @Value("${stripe.currency:inr}") String currency,
            @Value("${stripe.redirect-url:}") String redirectUrl
    ) {
        this.stripeApiKey = stripeApiKey;
        this.currency = currency;
        this.redirectUrl = redirectUrl;
    }

    @Override
    public String getPaymentLink(Long amount, String phoneNumber, String name, String email, String orderId) {
        try {
            Stripe.apiKey = this.stripeApiKey;

            Price price = Price.create(buildPriceParams(amount, orderId));
            PaymentLink paymentLink = PaymentLink.create(buildLinkParams(price.getId(), orderId));
            return paymentLink.getUrl();
        } catch (StripeException ex) {
            throw new RuntimeException(ex.getMessage());
        }
    }

    /** A one-off price for this order; amounts are in the currency's smallest unit, as everywhere else. */
    PriceCreateParams buildPriceParams(Long amount, String orderId) {
        return PriceCreateParams.builder()
                .setCurrency(currency)
                .setUnitAmount(amount)
                .setProductData(PriceCreateParams.ProductData.builder().setName("Order " + orderId).build())
                .build();
    }

    PaymentLinkCreateParams buildLinkParams(String priceId, String orderId) {
        PaymentLinkCreateParams.Builder params = PaymentLinkCreateParams.builder()
                .addLineItem(PaymentLinkCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
                .putMetadata(ORDER_ID_METADATA, orderId);
        if (redirectUrl != null && !redirectUrl.isBlank()) {
            params.setAfterCompletion(PaymentLinkCreateParams.AfterCompletion.builder()
                    .setType(PaymentLinkCreateParams.AfterCompletion.Type.REDIRECT)
                    .setRedirect(PaymentLinkCreateParams.AfterCompletion.Redirect.builder().setUrl(redirectUrl).build())
                    .build());
        }
        return params.build();
    }

}
