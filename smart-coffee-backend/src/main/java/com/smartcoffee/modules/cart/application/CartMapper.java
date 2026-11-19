package com.smartcoffee.modules.cart.application;
import com.smartcoffee.common.money.MoneyCalculator;
import com.smartcoffee.modules.cart.api.*; import com.smartcoffee.modules.cart.domain.*;
import java.math.BigDecimal; import org.springframework.stereotype.Component;
@Component public class CartMapper { private final MoneyCalculator money; public CartMapper(MoneyCalculator money){this.money=money;} public CartResponse response(Cart c){var items=c.items().stream().map(i->new CartItemResponse(i.id(),i.productId(),i.productCode(),i.productName(),i.quantity(),i.unitBasePrice(),i.unitCustomizationPrice(),i.lineTotal(),i.note(),i.customizations().stream().map(x->new CartCustomizationResponse(x.groupId(),x.optionId(),x.groupName(),x.optionName(),x.quantity(),x.additionalPrice())).toList(),i.variantId(),i.variantCode(),i.variantName(),i.variantPrice())).toList();BigDecimal subtotal=money.round(c.subtotal(),c.currencyCode());return new CartResponse(c.id(),c.storeId(),c.status().name(),c.fulfillmentType()==null?null:c.fulfillmentType().name(),c.currencyCode(),items,subtotal,BigDecimal.ZERO,BigDecimal.ZERO,subtotal,c.expiresAt(),c.version());} }

