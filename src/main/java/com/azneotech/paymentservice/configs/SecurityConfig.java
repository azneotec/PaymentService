package com.azneotech.paymentservice.configs;

import com.azneotech.paymentservice.clients.IAuthClient;
import com.azneotech.paymentservice.security.JwtAccessDeniedHandler;
import com.azneotech.paymentservice.security.JwtAuthenticationEntryPoint;
import com.azneotech.paymentservice.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /**
     * Stateless bearer-token security, validated against user-auth-service. Customers (ROLE_USER) and admins
     * (ROLE_ADMIN) can fetch a receipt (a customer only their own, see ReceiptService); creating a payment link
     * over REST needs ROLE_ADMIN, since the customer flow goes through Kafka. A missing or invalid token gets 401, a
     * valid one without the role 403.
     * <p>
     * Open on purpose: the health probe, the gateway webhooks (they carry the gateway's signature instead of a
     * token) and {@code /error}, without which Spring Security would turn every error status (a 404 or 409 raised by
     * a controller) into a 401.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity,
                                                   IAuthClient authClient,
                                                   ObjectMapper objectMapper) throws Exception {
        httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers("/razorpayWebhook", "/stripeWebhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/payment").hasAuthority("ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/payment/*/receipt").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(authClient, objectMapper), UsernamePasswordAuthenticationFilter.class);
        return httpSecurity.build();
    }

}
