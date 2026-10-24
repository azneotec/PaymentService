package com.azneotech.paymentservice.repos;

import com.azneotech.paymentservice.models.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
}
