package com.azneotech.paymentservice.security;

/** Principal placed in the {@code SecurityContext} by {@link JwtAuthenticationFilter}. */
public record AuthenticatedUser(Long userId, String email) {
}
