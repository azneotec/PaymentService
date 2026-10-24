package com.azneotech.paymentservice.paymentgateways;

import com.razorpay.PaymentLink;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** The default gateway: creates a Razorpay payment link whose {@code reference_id} is the order number. */
@Component
@Primary
public class RazorpayPaymentGateway implements IPaymentGateway {

    private final RazorpayClient razorpayClient;
    private final Clock clock;
    private final String currency;
    private final Duration linkExpiry;
    private final String callbackUrl;

    public RazorpayPaymentGateway(
            RazorpayClient razorpayClient,
            Clock clock,
            @Value("${razorpay.currency:INR}") String currency,
            @Value("${razorpay.link.expiry:24h}") Duration linkExpiry,
            @Value("${razorpay.callback-url:}") String callbackUrl
    ) {
        this.razorpayClient = razorpayClient;
        this.clock = clock;
        this.currency = currency;
        this.linkExpiry = linkExpiry;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public String getPaymentLink(
            Long amount,
            String phoneNumber,
            String name,
            String email,
            String orderId
    ) {
        try {
            PaymentLink payment = razorpayClient.paymentLink.create(buildRequest(amount, phoneNumber, name, email, orderId));
            return payment.get("short_url");
        } catch (RazorpayException ex) {
            throw new RuntimeException(ex.getMessage());
        }
    }

    /** The payment link request; package-private so the fields can be checked without calling Razorpay. */
    JSONObject buildRequest(Long amount, String phoneNumber, String name, String email, String orderId) {
        JSONObject paymentLinkRequest = new JSONObject();
        paymentLinkRequest.put("amount", amount);
        paymentLinkRequest.put("currency", currency);
        // An order is paid in full or not at all; a partial payment would never fire payment_link.paid.
        paymentLinkRequest.put("accept_partial", false);
        // Razorpay requires the expiry to be at least 15 minutes ahead, as epoch seconds.
        paymentLinkRequest.put("expire_by", clock.instant().plus(linkExpiry).getEpochSecond());
        paymentLinkRequest.put("reference_id", orderId);
        paymentLinkRequest.put("description", "Payment for order " + orderId);

        JSONObject customer = new JSONObject();
        customer.put("name", name);
        customer.put("contact", phoneNumber);
        customer.put("email", email);
        paymentLinkRequest.put("customer", customer);

        JSONObject notify = new JSONObject();
        notify.put("sms", true);
        notify.put("email", true);
        paymentLinkRequest.put("notify", notify);
        paymentLinkRequest.put("reminder_enable", true);

        JSONObject notes = new JSONObject();
        notes.put("order_id", orderId);
        paymentLinkRequest.put("notes", notes);

        if (callbackUrl != null && !callbackUrl.isBlank()) {
            paymentLinkRequest.put("callback_url", callbackUrl);
            paymentLinkRequest.put("callback_method", "get");
        }
        return paymentLinkRequest;
    }
}
