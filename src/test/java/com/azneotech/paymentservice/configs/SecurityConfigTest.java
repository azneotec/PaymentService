package com.azneotech.paymentservice.configs;

import com.azneotech.paymentservice.clients.IAuthClient;
import com.azneotech.paymentservice.controllers.PaymentController;
import com.azneotech.paymentservice.controllers.RazorpayWebhookController;
import com.azneotech.paymentservice.controllers.StripeWebhookController;
import com.azneotech.paymentservice.dtos.ReceiptDto;
import com.azneotech.paymentservice.dtos.ValidateTokenResponseDto;
import com.azneotech.paymentservice.exceptions.DownstreamUnavailableException;
import com.azneotech.paymentservice.exceptions.PaymentNotFoundException;
import com.azneotech.paymentservice.exceptions.ReceiptNotAvailableException;
import com.azneotech.paymentservice.services.PaymentLedger;
import com.azneotech.paymentservice.services.PaymentService;
import com.azneotech.paymentservice.services.ReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The security rules in front of the real controllers; user-auth-service and the services behind them are mocked. */
@WebMvcTest({PaymentController.class, RazorpayWebhookController.class, StripeWebhookController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"razorpay.webhook.secret=", "stripe.webhook.secret="})
class SecurityConfigTest {

    private static final String PAY_BODY = "{\"amount\":100,\"phoneNumber\":\"1\",\"name\":\"A\",\"email\":\"a@b.c\",\"orderId\":\"ORD-1\"}";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private IAuthClient authClient;
    @MockBean
    private PaymentService paymentService;
    @MockBean
    private ReceiptService receiptService;
    @MockBean
    private PaymentLedger paymentLedger;

    @BeforeEach
    void tokens() {
        when(authClient.validateToken("user-token")).thenReturn(new ValidateTokenResponseDto(true, 5L, "alice@example.com", List.of("ROLE_USER")));
        when(authClient.validateToken("admin-token")).thenReturn(new ValidateTokenResponseDto(true, 1L, "admin@example.com", List.of("ROLE_ADMIN")));
        when(authClient.validateToken("bad-token")).thenReturn(new ValidateTokenResponseDto(false, null, null, null));
    }

    private static ReceiptDto receipt() {
        return new ReceiptDto("RCT-ORD-1", "ORD-1", "STRIPE", null, "pi_1", "1.00", 100L, "INR", "Alice", "a****@example.com", Instant.parse("2026-10-24T10:00:00Z"));
    }

    @Test
    void aReceiptNeedsAToken() throws Exception {
        mockMvc.perform(get("/payment/ORD-1/receipt")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/payment/ORD-1/receipt").header("Authorization", "Bearer bad-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(receiptService);
    }

    @Test
    void aCustomerGetsTheirReceiptScopedToTheirEmail() throws Exception {
        when(receiptService.getReceipt("ORD-1", "alice@example.com", false)).thenReturn(receipt());

        mockMvc.perform(get("/payment/ORD-1/receipt").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value("RCT-ORD-1"));
    }

    @Test
    void anAdminGetsAnyReceipt() throws Exception {
        when(receiptService.getReceipt("ORD-1", "admin@example.com", true)).thenReturn(receipt());

        mockMvc.perform(get("/payment/ORD-1/receipt").header("Authorization", "Bearer admin-token")).andExpect(status().isOk());
    }

    @Test
    void receiptErrorsKeepTheirOwnStatusInsteadOfTurningInto401() throws Exception {
        when(receiptService.getReceipt(eq("NOPE"), any(), eq(false))).thenThrow(new PaymentNotFoundException("NOPE"));
        when(receiptService.getReceipt(eq("ORD-2"), any(), eq(false))).thenThrow(new ReceiptNotAvailableException("ORD-2"));

        mockMvc.perform(get("/payment/NOPE/receipt").header("Authorization", "Bearer user-token")).andExpect(status().isNotFound());
        mockMvc.perform(get("/payment/ORD-2/receipt").header("Authorization", "Bearer user-token")).andExpect(status().isConflict());
    }

    @Test
    void creatingALinkOverRestNeedsAnAdmin() throws Exception {
        mockMvc.perform(post("/payment").contentType(MediaType.APPLICATION_JSON).content(PAY_BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/payment").header("Authorization", "Bearer user-token").contentType(MediaType.APPLICATION_JSON).content(PAY_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(paymentService);

        when(paymentService.getPaymentLink(100L, "1", "A", "a@b.c", "ORD-1")).thenReturn("https://pay/x");
        mockMvc.perform(post("/payment").header("Authorization", "Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content(PAY_BODY))
                .andExpect(status().isOk());
        verify(paymentService).getPaymentLink(100L, "1", "A", "a@b.c", "ORD-1");
    }

    @Test
    void webhooksStayOpenAndRelyOnTheirOwnSignature() throws Exception {
        // No token, and the configured secret is blank, so the controllers themselves answer 503: they were reached.
        mockMvc.perform(post("/razorpayWebhook").content("{}")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/stripeWebhook").content("{}")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void userAuthBeingDownAnswers503() throws Exception {
        when(authClient.validateToken("any-token")).thenThrow(new DownstreamUnavailableException("user-auth-service"));

        mockMvc.perform(get("/payment/ORD-1/receipt").header("Authorization", "Bearer any-token")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void everythingElseNeedsAToken() throws Exception {
        mockMvc.perform(get("/somethingElse")).andExpect(status().isUnauthorized());
    }

}
