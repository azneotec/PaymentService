package com.azneotech.paymentservice.configs;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class RazorpayConfig {

    @Value("${razorpay.key.id:}")
    private String razorpayKeyId;

    @Value("${razorpay.key.secret:}")
    private String razorpayKeySecret;

    /** Built even without keys (Razorpay is not the default gateway); creating a link then fails with Razorpay's own auth error. */
    @Bean
    public RazorpayClient getRazorpayClient() throws RazorpayException {
        return new RazorpayClient(razorpayKeyId, razorpayKeySecret);
    }

    /** The time source for link expiry; a bean so tests can fix it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

}
