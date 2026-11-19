# Required Order patches
Add `stationCode()` to OrderItem. Add to Order:
```java
public void productionPreparing(UUID actor){ if(status==OrderStatus.PREPARING)return; if(status!=OrderStatus.CONFIRMED)throw new ConflictException("ORDER_STATE_CONFLICT","Order must be confirmed"); var old=status;status=OrderStatus.PREPARING;updatedAt=Instant.now();addHistory(old,status,actor,"Production started");}
public void productionReady(UUID actor){if(status==OrderStatus.READY)return;if(status!=OrderStatus.PREPARING)throw new ConflictException("ORDER_STATE_CONFLICT","Order must be preparing");var old=status;status=OrderStatus.READY;updatedAt=Instant.now();addHistory(old,status,actor,"Production ready");}
```
