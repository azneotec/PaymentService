package com.azneotech.paymentservice.services;

import com.azneotech.paymentservice.dtos.ReceiptDto;
import com.azneotech.paymentservice.exceptions.PaymentNotFoundException;
import com.azneotech.paymentservice.exceptions.ReceiptNotAvailableException;
import com.azneotech.paymentservice.models.Payment;
import com.azneotech.paymentservice.models.PaymentStatus;
import com.azneotech.paymentservice.repos.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Builds the receipt of a paid order from the transaction log. A customer only gets receipts of payments made with
 * their own email (the order's customer email is the email of the user who placed it); an admin gets any.
 */
@Service
public class ReceiptService {

    /** Receipt numbers follow the order number, so they are unique because order numbers are. */
    public static final String RECEIPT_PREFIX = "RCT-";

    private final PaymentRepository paymentRepository;

    public ReceiptService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * @param requesterEmail the caller's email, as user-auth-service reports it
     * @param admin          admins may read every receipt
     * @throws PaymentNotFoundException for an unknown order and also for someone else's, so the answer does not
     *                                  reveal which order numbers exist
     */
    @Transactional(readOnly = true)
    public ReceiptDto getReceipt(String orderNumber, String requesterEmail, boolean admin) {
        Payment payment = paymentRepository.findByOrderNumber(orderNumber)
                .filter(p -> admin || ownedBy(p, requesterEmail))
                .orElseThrow(() -> new PaymentNotFoundException(orderNumber));
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new ReceiptNotAvailableException(orderNumber);
        }
        return new ReceiptDto(payment.getReceiptNumber(), payment.getOrderNumber(), payment.getGateway(),
                payment.getPaymentMethod(), payment.getGatewayPaymentId(),
                formatAmount(payment.getAmountMinor(), payment.getCurrency()), payment.getAmountMinor(),
                payment.getCurrency(), payment.getCustomerName(), maskEmail(payment.getCustomerEmail()), payment.getPaidAt());
    }

    private static boolean ownedBy(Payment payment, String email) {
        return email != null && email.equalsIgnoreCase(payment.getCustomerEmail());
    }

    /** 24220 INR is "242.20"; a currency without minor units (JPY) keeps the number as it is. */
    public static String formatAmount(long amountMinor, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return BigDecimal.valueOf(amountMinor, Math.max(digits, 0)).toPlainString();
    }

    /** "alice@example.com" becomes "a****@example.com". */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        return email.charAt(0) + "****" + email.substring(at);
    }

}
