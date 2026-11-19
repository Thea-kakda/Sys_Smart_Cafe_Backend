package com.smartcoffee.modules.payment.persistence;

import com.smartcoffee.modules.payment.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {
    Optional<PaymentMethod> findByCodeAndActiveTrue(String code);

    List<PaymentMethod> findByActiveTrueOrderByDisplayOrderAsc();
}

