package com.smartcoffee.modules.cart.persistence;

import com.smartcoffee.modules.cart.domain.CartItem;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {
}

