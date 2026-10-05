package com.azneotech.paymentservice.paymentgateways;

public interface IPaymentGateway {
    String getPaymentLink(
            Long amount,
            String phoneNumber,
            String name,
            String email,
            String orderId
    );
}
