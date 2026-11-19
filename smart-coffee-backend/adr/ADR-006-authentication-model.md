# ADR-006: Authentication, Sessions, and Store-Scoped Authorization

- **Status:** Proposed
- **Decision date:** 2026-08-18
- **Owners:** Architecture and Security Teams
- **Scope:** User authentication, JWT access tokens, refresh sessions, RBAC, and multi-store access

## Context

The platform serves customers, administrators, managers, cashiers, kitchen staff, and future service integrations. It requires short-lived API authorization, long-lived signed-in sessions, device revocation, permission-based access, and prevention of cross-store data access.

The original schema stores hashed refresh tokens but does not fully model rotation families, token reuse detection, or a security version.

## Decision

Use OAuth 2.1/OpenID Connect concepts with:

- Short-lived signed JWT access tokens
- Opaque random refresh tokens
- Hashed refresh tokens stored in PostgreSQL
- Refresh-token rotation on every successful refresh
- Token-family reuse detection
- Session and all-device revocation
- Argon2id password hashing
- Role and permission-based authorization
- Store-scoped resource authorization

For an educational or first controlled deployment, authentication may be implemented in the modular backend using Spring Security and a carefully bounded Auth module. For production with limited identity-security expertise, a managed identity platform or dedicated authorization server should be evaluated before launch.

## Token Model

### Access token

Recommended lifetime: 5 to 15 minutes.

Claims may include:

```text
sub
session_id
security_version
roles
permissions
store_scopes
issued_at
expires_at
issuer
audience
```

Do not include secrets, password data, or unnecessary personal data.

### Refresh token

Recommended lifetime: 7 to 30 days according to client and risk policy.

- Generate using a cryptographically secure random source.
- Return only to the authenticated client.
- Store only a strong hash.
- Rotate on every use.
- Detect reuse of an older token in the same family.
- Revoke the family after suspected reuse.

## Session Data

`auth_sessions` records:

```text
session_id
user_id
token_family_id
refresh_token_hash
rotation_counter
device_id
device_name
ip_address
user_agent
expires_at
last_used_at
revoked_at
revocation_reason
created_at
```

## Password Security

- Use Argon2id.
- Store algorithm/version metadata for future rehashing.
- Enforce rate limits on login and refresh.
- Use generic authentication errors to reduce account enumeration.
- Rehash on successful login when policy parameters change.
- Never log passwords, raw tokens, or authorization headers.

## Store-Scoped Authorization

Every store-sensitive use case verifies:

```text
authenticated user
requested store
required permission
active role assignment
assignment expiry or revocation
resource ownership
```

Gateway checks do not replace authorization inside the application service.

A request header or path `storeId` is untrusted until checked against granted store scopes. Store A staff cannot access Store B unless explicitly assigned.

## Endpoint Rules

Public endpoints are explicitly allow-listed in Spring Security configuration. A custom `@Public` annotation alone must not control exposure.

Refresh tokens are accepted only at:

```text
POST /api/v1/auth/refresh
```

A global refresh-token servlet filter is not used.

Logout behavior:

- `logout` revokes the current session.
- `logout-all` revokes all active user sessions.
- Password reset and severe role/security changes may revoke all sessions or increment `security_version`.

## Service-to-Service Security

After service extraction, use client credentials, workload identity, or mutually authenticated internal transport. Do not forward an administrator token as a general service credential.

## Alternatives Considered

### Stateful server session only

Not selected as the primary API model because mobile, web, and POS clients benefit from token-based authorization. PostgreSQL session state is still retained for refresh and revocation.

### Long-lived JWT without refresh sessions

Rejected because revocation, device management, and theft recovery are weak.

### Raw refresh tokens in the database

Rejected because database exposure would provide directly usable credentials.

### Authorization only at API Gateway

Rejected because direct/internal access and object-level authorization require enforcement by the resource-owning application.

## Consequences

### Positive

- Short-lived access limits exposure.
- Device sessions can be revoked independently.
- Refresh reuse detection improves theft response.
- Store isolation is explicit.
- Future identity-service extraction is supported.

### Negative

- Rotation and concurrency require careful transactional logic.
- JWT claims may become stale until access token expiry.
- Key rotation and token-verification operations are required.
- Custom identity implementation increases security responsibility.

## Additional Controls

- MFA is required for administrators, finance, refund, and high-risk inventory roles before production.
- Signing keys and provider credentials are held in secret management.
- CORS uses explicit trusted origins.
- CSRF policy depends on token storage and browser authentication design.
- Authentication, logout, role assignment, and session revocation produce audit records.
- WebSocket connections and subscriptions are authenticated and authorized.

## Revisit Conditions

Revisit internal authentication before public production, when federation/social login is needed, when MFA requirements expand, or when compliance requires a dedicated identity platform. Refresh hashing, rotation, store authorization, and least privilege remain mandatory.
