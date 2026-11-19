# Business audit integration
Inject `AuditWriter` into Inventory adjustment, Payment settlement, Order cancellation, Promotion administration, Loyalty manual adjustment, and Staff administration services. Call `auditWriter.success(...)` inside the same transaction after the authoritative mutation and before commit. Never serialize entities directly. Pass explicit safe before/after DTOs.
