package com.smartcoffee.modules.cart.application;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.customer.persistence.CustomerProfileRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component public class CurrentCustomerResolver { private final CustomerProfileRepository profiles; public CurrentCustomerResolver(CustomerProfileRepository p){profiles=p;} public UUID customerId(UUID userId){return profiles.findByUserId(userId).orElseThrow(()->new ResourceNotFoundException("Customer profile not found")).id();} }

