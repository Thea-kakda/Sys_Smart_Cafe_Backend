# Required patch to Step 2 AuthenticationService

Inject `CustomerRegistrationService customerRegistrationService` into `AuthenticationService`.
Immediately after saving the new `UserCredential` inside `register(...)`, call:

```java
customerRegistrationService.initialize(u.id());
```

This call participates in the same transaction, so User, credentials, CustomerProfile, role assignment, and AuthSession commit together.
