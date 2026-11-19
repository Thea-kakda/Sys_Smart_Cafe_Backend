package com.smartcoffee.modules.cart.domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(schema="cart",name="cart_item_customizations")
public class CartItemCustomization {
 @Id @Column(name="cart_item_customization_id") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="cart_item_id",nullable=false) private CartItem cartItem;
 @Column(name="group_id",nullable=false) private UUID groupId;
 @Column(name="option_id",nullable=false) private UUID optionId;
 @Column(name="group_name_snapshot",nullable=false,length=100) private String groupName;
 @Column(name="option_name_snapshot",nullable=false,length=100) private String optionName;
 @Column(name="additional_price_snapshot",nullable=false,precision=14,scale=4) private BigDecimal additionalPrice;
 @Column(nullable=false) private int quantity;
 @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
 protected CartItemCustomization(){}
 public CartItemCustomization(UUID groupId,UUID optionId,String groupName,String optionName,BigDecimal price,int quantity){this.id=UUID.randomUUID();this.groupId=groupId;this.optionId=optionId;this.groupName=groupName;this.optionName=optionName;this.additionalPrice=price;this.quantity=quantity;this.createdAt=Instant.now();}
 void attach(CartItem item){cartItem=item;} public UUID groupId(){return groupId;} public UUID optionId(){return optionId;} public String groupName(){return groupName;} public String optionName(){return optionName;} public BigDecimal additionalPrice(){return additionalPrice.multiply(BigDecimal.valueOf(quantity));} public int quantity(){return quantity;}
}

