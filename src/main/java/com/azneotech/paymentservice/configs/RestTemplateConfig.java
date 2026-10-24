package com.azneotech.paymentservice.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    /** Resolves Eureka names such as {@code user-auth-service}; the timeouts keep a slow dependency from hanging a request. */
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate(RestTemplateBuilder builder,
                                     @Value("${payment.clients.connect-timeout}") Duration connectTimeout,
                                     @Value("${payment.clients.read-timeout}") Duration readTimeout) {
        return builder.setConnectTimeout(connectTimeout).setReadTimeout(readTimeout).build();
    }

}
