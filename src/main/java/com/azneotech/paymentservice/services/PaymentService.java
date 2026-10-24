package com.azneotech.paymentservice.services;

import com.azneotech.paymentservice.paymentgateways.IPaymentGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    @Autowired
    private IPaymentGateway paymentGateway;

    @Autowired
    private PaymentLedger paymentLedger;

    /**
     * Creates the payment link and logs it. An order that is already paid is refused before the gateway is
     * called, with {@code PaymentAlreadyPaidException}.
     */
    public String getPaymentLink(
            Long amount,
            String phoneNumber,
            String name,
            String email,
            String orderId
    ) {
        paymentLedger.assertNotPaid(orderId);
        String link = paymentGateway.getPaymentLink(amount, phoneNumber, name, email, orderId);
        paymentLedger.recordLinkCreated(orderId, amount, paymentGateway.currency(), paymentGateway.name(),
                name, email, phoneNumber, link);
        return link;
    }

}
