package com.smartcoffee.modules.cart.api;
import java.math.BigDecimal; import java.time.Instant; import java.util.*;
public record CartResponse(UUID cartId,UUID storeId,String status,String fulfillmentType,String currencyCode,List<CartItemResponse> items,BigDecimal subtotal,BigDecimal discountAmount,BigDecimal taxAmount,BigDecimal totalAmount,Instant expiresAt,long version){}

