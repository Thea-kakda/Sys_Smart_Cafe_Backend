# Required Cart patch
Add to `Cart.java`:
```java
public void checkout() { requireActive(); if (items.isEmpty()) throw new ConflictException("EMPTY_CART", "Cart is empty"); status = CartStatus.CHECKED_OUT; updatedAt = Instant.now(); }
```
