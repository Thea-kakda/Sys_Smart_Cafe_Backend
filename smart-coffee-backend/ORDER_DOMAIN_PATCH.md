# Required Step 7 Order patch
Add methods to `Order.java`:
```java
public void paymentCompleted(UUID actor) {
    if (status != OrderStatus.AWAITING_PAYMENT || paymentStatus == PaymentStatus.PAID)
        throw new ConflictException("ORDER_ALREADY_PAID", "Order is not eligible for payment");
    OrderStatus old = status;
    paymentStatus = PaymentStatus.PAID;
    status = OrderStatus.CONFIRMED;
    updatedAt = Instant.now();
    addHistory(old, status, actor, "Payment completed");
}

public void paymentExpired(UUID actor) {
    if (status != OrderStatus.AWAITING_PAYMENT || paymentStatus == PaymentStatus.PAID) return;
    OrderStatus old = status;
    paymentStatus = PaymentStatus.FAILED;
    status = OrderStatus.CANCELLED;
    updatedAt = Instant.now();
    addHistory(old, status, actor, "Payment window expired");
}
```
