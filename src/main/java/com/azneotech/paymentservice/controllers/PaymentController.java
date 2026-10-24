package com.azneotech.paymentservice.controllers;

import com.azneotech.paymentservice.dtos.InitiatePaymentRequestDto;
import com.azneotech.paymentservice.dtos.ReceiptDto;
import com.azneotech.paymentservice.security.AuthenticatedUser;
import com.azneotech.paymentservice.services.PaymentService;
import com.azneotech.paymentservice.services.ReceiptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReceiptService receiptService;

    /**
     * The receipt of a paid order: 404 when there is no such payment or it belongs to someone else, 409 while it is
     * unpaid. Admins may read any receipt.
     */
    @GetMapping("/{orderNumber}/receipt")
    public ReceiptDto getReceipt(@PathVariable String orderNumber,
                                 @AuthenticationPrincipal AuthenticatedUser caller,
                                 Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return receiptService.getReceipt(orderNumber, caller.email(), admin);
    }

    @PostMapping
    public String initiatePayment(
            @RequestBody InitiatePaymentRequestDto initiatePaymentRequestDto
    ) {
        // OID#0001 1rupee https://rzp.io/rzp/lWsFo4S
        return paymentService.getPaymentLink(
                initiatePaymentRequestDto.getAmount(),
                initiatePaymentRequestDto.getPhoneNumber(),
                initiatePaymentRequestDto.getName(),
                initiatePaymentRequestDto.getEmail(),
                initiatePaymentRequestDto.getOrderId()
        );
    }
}
