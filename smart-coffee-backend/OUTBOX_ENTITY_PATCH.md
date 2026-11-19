# OutboxEvent required patch
Add a package-visible or public rehydration factory used by JDBC claiming:
```java
public static OutboxEvent rehydrate(UUID id, UUID eventId, String eventType, int eventVersion, String aggregateType, UUID aggregateId, String routingKey, String payload) {
    OutboxEvent e = new OutboxEvent();
    e.id = id; e.eventId = eventId; e.eventType = eventType; e.eventVersion = eventVersion;
    e.aggregateType = aggregateType; e.aggregateId = aggregateId; e.routingKey = routingKey; e.payload = payload;
    e.status = OutboxStatus.PROCESSING;
    return e;
}
```
