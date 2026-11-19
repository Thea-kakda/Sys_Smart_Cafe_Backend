# Authentication API

<cite>
**Referenced Files in This Document**
- [AuthController.java](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java)
- [LoginRequest.java](file://src/main/java/com/smartcoffee/modules/auth/api/LoginRequest.java)
- [RegisterRequest.java](file://src/main/java/com/smartcoffee/modules/auth/api/RegisterRequest.java)
- [AuthResponse.java](file://src/main/java/com/smartcoffee/modules/auth/api/AuthResponse.java)
- [AuthenticationService.java](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java)
- [JwtTokenService.java](file://src/main/java/com/smartcoffee/modules/auth/security/JwtTokenService.java)
- [RefreshTokenService.java](file://src/main/java/com/smartcoffee/modules/auth/security/RefreshTokenService.java)
- [JwtAuthenticationFilter.java](file://src/main/java/com/smartcoffee/modules/auth/security/JwtAuthenticationFilter.java)
- [SecurityConfig.java](file://src/main/java/com/smartcoffee/config/SecurityConfig.java)
- [application.yml](file://src/main/resources/application.yml)
- [ApiResponse.java](file://src/main/java/com/smartcoffee/common/api/ApiResponse.java)
- [ApiErrorResponse.java](file://src/main/java/com/smartcoffee/common/api/ApiErrorResponse.java)
- [UnauthorizedException.java](file://src/main/java/com/smartcoffee/common/exception/UnauthorizedException.java)
- [ConflictException.java](file://src/main/java/com/smartcoffee/common/exception/ConflictException.java)
- [AuthSession.java](file://src/main/java/com/smartcoffee/modules/auth/domain/AuthSession.java)
- [AuthSessionRepository.java](file://src/main/java/com/smartcoffee/modules/auth/persistence/AuthSessionRepository.java)
</cite>

## Table of Contents
1. Introduction
2. Project Structure
3. Core Components
4. Architecture Overview
5. Detailed Component Analysis
6. Dependency Analysis
7. Performance Considerations
8. Troubleshooting Guide
9. Conclusion

## Introduction
This document provides detailed API documentation for the Authentication endpoints, including user registration, login, logout, and token refresh operations. It explains the request/response schemas (LoginRequest, RegisterRequest, AuthResponse), the JWT token flow, refresh token mechanism, session management, example authentication headers, error responses for invalid credentials, security considerations, rate limiting policies, and account lockout mechanisms.

## Project Structure
The authentication feature is implemented as a modular component with clear separation between API layer, application service, domain entities, persistence, and security utilities:
- API layer exposes REST endpoints under /api/v1/auth
- Application service orchestrates business logic
- Domain and persistence manage sessions and credentials
- Security utilities handle JWT encoding/decoding and filter-based authorization

```mermaid
graph TB
Client["Client"] --> AC["AuthController<br/>REST Endpoints"]
AC --> AS["AuthenticationService<br/>Business Logic"]
AS --> JTS["JwtTokenService<br/>JWT Encoding"]
AS --> RTS["RefreshTokenService<br/>Issue & Hash Refresh Tokens"]
AS --> ARepo["AuthSessionRepository<br/>JPA Repository"]
ARepo --> AEnt["AuthSession<br/>Domain Entity"]
AC --> SecCfg["SecurityConfig<br/>Spring Security"]
SecCfg --> JAF["JwtAuthenticationFilter<br/>Bearer Token Extraction"]
```

**Diagram sources**
- [AuthController.java:13-70](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L13-L70)
- [AuthenticationService.java:16-100](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L16-L100)
- [JwtTokenService.java:23-85](file://src/main/java/com/smartcoffee/modules/auth/security/JwtTokenService.java#L23-L85)
- [RefreshTokenService.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/security/RefreshTokenService.java#L1-L5)
- [AuthSessionRepository.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/persistence/AuthSessionRepository.java#L1-L5)
- [AuthSession.java:7-53](file://src/main/java/com/smartcoffee/modules/auth/domain/AuthSession.java#L7-L53)
- [SecurityConfig.java:46-79](file://src/main/java/com/smartcoffee/config/SecurityConfig.java#L46-L79)
- [JwtAuthenticationFilter.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/security/JwtAuthenticationFilter.java#L1-L5)

**Section sources**
- [AuthController.java:13-70](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L13-L70)
- [SecurityConfig.java:46-79](file://src/main/java/com/smartcoffee/config/SecurityConfig.java#L46-L79)

## Core Components
- AuthController: Exposes REST endpoints for register, login, refresh, logout, and logout-all. Sets or clears the refresh_token cookie and returns ApiResponse-wrapped payloads.
- AuthenticationService: Implements business logic for registration, login, refresh, logout, and logout-all; manages sessions and issues tokens.
- JwtTokenService: Encodes access tokens using HS256 with configurable TTLs.
- RefreshTokenService: Generates cryptographically secure refresh tokens and hashes them before storage.
- JwtAuthenticationFilter: Extracts Bearer tokens from Authorization header and populates Spring Security context.
- SecurityConfig: Configures stateless sessions, permits public auth endpoints, and wires the JWT decoder and filter.

Key data models:
- LoginRequest: email (string, required, valid email), password (string, required)
- RegisterRequest: fullName (string, required, max 150), email (string, required, valid email, max 320), password (string, required, min 10, max 128), preferredLanguage (string, required)
- AuthResponse: userId (UUID), fullName (string), email (string), preferredLanguage (string), accessToken (string)

**Section sources**
- [AuthController.java:13-70](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L13-L70)
- [AuthenticationService.java:16-100](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L16-L100)
- [JwtTokenService.java:23-85](file://src/main/java/com/smartcoffee/modules/auth/security/JwtTokenService.java#L23-L85)
- [RefreshTokenService.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/security/RefreshTokenService.java#L1-L5)
- [JwtAuthenticationFilter.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/security/JwtAuthenticationFilter.java#L1-L5)
- [SecurityConfig.java:46-79](file://src/main/java/com/smartcoffee/config/SecurityConfig.java#L46-L79)
- [LoginRequest.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/LoginRequest.java#L1-L4)
- [RegisterRequest.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/RegisterRequest.java#L1-L4)
- [AuthResponse.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/AuthResponse.java#L1-L4)

## Architecture Overview
The authentication flow uses short-lived access tokens (JWT) and long-lived refresh tokens stored as hashed values in the database. The client receives an access token in the response body and a refresh token via an HTTP-only cookie. Subsequent requests include the access token in the Authorization header. When the access token expires, the client calls the refresh endpoint to obtain a new access token without re-authenticating.

```mermaid
sequenceDiagram
participant C as "Client"
participant AC as "AuthController"
participant AS as "AuthenticationService"
participant JTS as "JwtTokenService"
participant RTS as "RefreshTokenService"
participant DB as "AuthSessionRepository"
C->>AC : POST /api/v1/auth/login {email,password}
AC->>AS : login(LoginRequest)
AS->>RTS : issue()
RTS-->>AS : refreshToken
AS->>DB : save(AuthSession{userId,familyId,refreshTokenHash,expiresAt})
AS->>JTS : access(userId,securityVersion,scopes)
JTS-->>AS : accessToken
AS-->>AC : Result{AuthResponse,refreshToken,expiresAt}
AC-->>C : 200 OK + Set-Cookie(refresh_token) + ApiResponse{data : AuthResponse}
C->>AC : POST /api/v1/auth/refresh (cookie : refresh_token)
AC->>AS : refresh(raw)
AS->>DB : findByRefreshTokenHash(hash)
DB-->>AS : AuthSession
AS->>DB : revoke(old session)
AS->>JTS : access(userId,securityVersion,scopes)
JTS-->>AS : newAccessToken
AS-->>AC : Result{new AuthResponse,new refreshToken,new expiresAt}
AC-->>C : 200 OK + Set-Cookie(refresh_token) + ApiResponse{data : AuthResponse}
```

**Diagram sources**
- [AuthController.java:33-45](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L33-L45)
- [AuthenticationService.java:48-99](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L48-L99)
- [JwtTokenService.java:66-85](file://src/main/java/com/smartcoffee/modules/auth/security/JwtTokenService.java#L66-L85)
- [RefreshTokenService.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/security/RefreshTokenService.java#L1-L5)
- [AuthSessionRepository.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/persistence/AuthSessionRepository.java#L1-L5)

## Detailed Component Analysis

### Authentication Endpoints

#### Register
- Method: POST
- Path: /api/v1/auth/register
- Request Body: RegisterRequest
- Response: ApiResponse<AuthResponse>
- Cookie: Sets refresh_token on success
- Status Codes:
  - 201 Created on success
  - 409 Conflict if email already registered
  - 400 Bad Request for validation errors

Example Request Body:
{
  "fullName": "Jane Doe",
  "email": "jane@example.com",
  "password": "securePassword123",
  "preferredLanguage": "en"
}

Example Success Response:
{
  "success": true,
  "data": {
    "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "fullName": "Jane Doe",
    "email": "jane@example.com",
    "preferredLanguage": "en",
    "accessToken": "eyJhbGciOiJIUzI1NiIs..."
  },
  "requestId": "req-uuid",
  "timestamp": "2025-01-01T12:00:00Z"
}

Error Response Example (Duplicate Email):
{
  "success": false,
  "error": {
    "code": "CONFLICT",
    "message": "Email already registered",
    "details": []
  },
  "requestId": "req-uuid",
  "timestamp": "2025-01-01T12:00:00Z"
}

**Section sources**
- [AuthController.java:25-31](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L25-L31)
- [AuthenticationService.java:38-46](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L38-L46)
- [RegisterRequest.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/RegisterRequest.java#L1-L4)
- [AuthResponse.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/AuthResponse.java#L1-L4)
- [ApiResponse.java:1-11](file://src/main/java/com/smartcoffee/common/api/ApiResponse.java#L1-L11)
- [ApiErrorResponse.java:1-15](file://src/main/java/com/smartcoffee/common/api/ApiErrorResponse.java#L1-L15)
- [ConflictException.java:1-13](file://src/main/java/com/smartcoffee/common/exception/ConflictException.java#L1-L13)

#### Login
- Method: POST
- Path: /api/v1/auth/login
- Request Body: LoginRequest
- Response: ApiResponse<AuthResponse>
- Cookie: Sets refresh_token on success
- Status Codes:
  - 200 OK on success
  - 401 Unauthorized for invalid credentials or unavailable account
  - 400 Bad Request for validation errors

Example Request Body:
{
  "email": "jane@example.com",
  "password": "securePassword123"
}

Example Success Response:
{
  "success": true,
  "data": {
    "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "fullName": "Jane Doe",
    "email": "jane@example.com",
    "preferredLanguage": "en",
    "accessToken": "eyJhbGciOiJIUzI1NiIs..."
  },
  "requestId": "req-uuid",
  "timestamp": "2025-01-01T12:00:00Z"
}

Error Response Example (Invalid Credentials):
{
  "success": false,
  "error": {
    "code": "AUTH_UNAUTHORIZED",
    "message": "Invalid credentials",
    "details": []
  },
  "requestId": "req-uuid",
  "timestamp": "2025-01-01T12:00:00Z"
}

**Section sources**
- [AuthController.java:33-38](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L33-L38)
- [AuthenticationService.java:48-62](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L48-L62)
- [LoginRequest.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/LoginRequest.java#L1-L4)
- [AuthResponse.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/AuthResponse.java#L1-L4)
- [UnauthorizedException.java:1-13](file://src/main/java/com/smartcoffee/common/exception/UnauthorizedException.java#L1-L13)

#### Refresh
- Method: POST
- Path: /api/v1/auth/refresh
- Headers: None required
- Cookies: refresh_token (required)
- Response: ApiResponse<AuthResponse>
- Cookie: Sets refresh_token on success
- Status Codes:
  - 200 OK on success
  - 401 Unauthorized for invalid or expired refresh session

Example Success Response:
{
  "success": true,
  "data": {
    "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "fullName": "Jane Doe",
    "email": "jane@example.com",
    "preferredLanguage": "en",
    "accessToken": "eyJhbGciOiJIUzI1NiIs..."
  },
  "requestId": "req-uuid",
  "timestamp": "2025-01-01T12:00:00Z"
}

Error Response Example (Invalid Refresh Session):
{
  "success": false,
  "error": {
    "code": "AUTH_UNAUTHORIZED",
    "message": "Invalid refresh session",
    "details": []
  },
  "requestId": "req-uuid",
  "timestamp": "2025-01-01T12:00:00Z"
}

**Section sources**
- [AuthController.java:40-45](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L40-L45)
- [AuthenticationService.java:64-74](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L64-L74)

#### Logout
- Method: POST
- Path: /api/v1/auth/logout
- Cookies: refresh_token (optional)
- Response: 204 No Content
- Behavior: Revokes the specific refresh session if provided

**Section sources**
- [AuthController.java:47-52](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L47-L52)
- [AuthenticationService.java:76-82](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L76-L82)

#### Logout All
- Method: POST
- Path: /api/v1/auth/logout-all
- Headers: Authorization: Bearer <access_token>
- Response: 204 No Content
- Behavior: Revokes all active refresh sessions for the authenticated user

**Section sources**
- [AuthController.java:54-59](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L54-L59)
- [AuthenticationService.java:84-87](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L84-L87)

### Request and Response Schemas

- LoginRequest
  - Fields:
    - email: string, required, valid email format
    - password: string, required
- RegisterRequest
  - Fields:
    - fullName: string, required, max length 150
    - email: string, required, valid email format, max length 320
    - password: string, required, min length 10, max length 128
    - preferredLanguage: string, required
- AuthResponse
  - Fields:
    - userId: UUID
    - fullName: string
    - email: string
    - preferredLanguage: string
    - accessToken: string

**Section sources**
- [LoginRequest.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/LoginRequest.java#L1-L4)
- [RegisterRequest.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/RegisterRequest.java#L1-L4)
- [AuthResponse.java:1-4](file://src/main/java/com/smartcoffee/modules/auth/api/AuthResponse.java#L1-L4)

### JWT Token Flow and Security

- Access Token
  - Algorithm: HS256
  - Issuer: smart-coffee-api
  - Claims: subject (user ID), sec_ver (security version), scopes (set), issuedAt, expiresAt
  - Lifetime: Configurable via app.security.jwt.access-token-minutes (default 15 minutes)
- Refresh Token
  - Generated securely and hashed before storage
  - Stored as hashed value in auth_sessions table
  - Lifetime: Configurable via app.security.jwt.refresh-token-days (default 30 days)
- Cookie Handling
  - refresh_token cookie is set with httpOnly=true, path=/api/v1/auth, sameSite=Lax
  - On logout or logout-all, the cookie is cleared

Authorization Header Example:
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...

**Section sources**
- [JwtTokenService.java:23-85](file://src/main/java/com/smartcoffee/modules/auth/security/JwtTokenService.java#L23-L85)
- [application.yml:49-53](file://src/main/resources/application.yml#L49-L53)
- [AuthController.java:61-70](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L61-L70)
- [JwtAuthenticationFilter.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/security/JwtAuthenticationFilter.java#L1-L5)

### Session Management

- AuthSession Entity
  - Tracks user_id, token_family_id, refresh_token_hash, expires_at, revoked_at, created_at
  - active() checks expiry and revocation status
- Repository Operations
  - findByRefreshTokenHash(hash) retrieves session by hash
  - revokeAll(userId) revokes all active sessions for a user
- Rotation Policy
  - On refresh, the old session is revoked and a new session is created with a new refresh token

```mermaid
flowchart TD
Start(["Refresh Endpoint"]) --> Validate["Validate refresh_token cookie"]
Validate --> Hash["Hash token"]
Hash --> Lookup["Lookup session by hash"]
Lookup --> Found{"Session found?"}
Found --> |No| Error401["Return 401 Invalid refresh session"]
Found --> |Yes| CheckActive{"Session active?"}
CheckActive --> |No| Error401
CheckActive --> |Yes| RevokeOld["Revoke old session"]
RevokeOld --> IssueNew["Issue new refresh token and access token"]
IssueNew --> SaveNew["Save new session"]
SaveNew --> Return["Return new tokens and set cookie"]
```

**Diagram sources**
- [AuthenticationService.java:64-99](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L64-L99)
- [AuthSession.java:29-53](file://src/main/java/com/smartcoffee/modules/auth/domain/AuthSession.java#L29-L53)
- [AuthSessionRepository.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/persistence/AuthSessionRepository.java#L1-L5)

**Section sources**
- [AuthSession.java:7-53](file://src/main/java/com/smartcoffee/modules/auth/domain/AuthSession.java#L7-L53)
- [AuthSessionRepository.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/persistence/AuthSessionRepository.java#L1-L5)
- [AuthenticationService.java:64-99](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L64-L99)

### Account Lockout Mechanisms

- Login attempts track failures and successes on UserCredential
- If the account is locked or inactive, login fails with an appropriate unauthorized message
- Successful login marks the credential as successful; failed login increments failure count

Note: The exact threshold and lockout duration are not defined in the referenced files; implementation details may be present in other modules.

**Section sources**
- [AuthenticationService.java:48-62](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L48-L62)

### Rate Limiting Policies

- No explicit rate limiting is configured in the referenced authentication components
- Public endpoints (/api/v1/auth/**) are permitted without authentication
- For production, consider adding rate limiting at the gateway or application level

**Section sources**
- [SecurityConfig.java:58-73](file://src/main/java/com/smartcoffee/config/SecurityConfig.java#L58-L73)

## Dependency Analysis

```mermaid
classDiagram
class AuthController {
+register(req,res)
+login(req,res)
+refresh(token,res)
+logout(token,res)
+logoutAll(res)
}
class AuthenticationService {
+register(q)
+login(q)
+refresh(raw)
+logout(raw)
+logoutAll(userId)
}
class JwtTokenService {
+access(userId,securityVersion,scopes)
+refreshTtl()
}
class RefreshTokenService {
+issue()
+hash(token)
}
class AuthSessionRepository {
+findByRefreshTokenHash(hash)
+revokeAll(userId)
}
class AuthSession {
+userId()
+familyId()
+active()
+revoke()
}
AuthController --> AuthenticationService : "uses"
AuthenticationService --> JwtTokenService : "uses"
AuthenticationService --> RefreshTokenService : "uses"
AuthenticationService --> AuthSessionRepository : "uses"
AuthSessionRepository --> AuthSession : "manages"
```

**Diagram sources**
- [AuthController.java:13-70](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L13-L70)
- [AuthenticationService.java:16-100](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L16-L100)
- [JwtTokenService.java:23-85](file://src/main/java/com/smartcoffee/modules/auth/security/JwtTokenService.java#L23-L85)
- [RefreshTokenService.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/security/RefreshTokenService.java#L1-L5)
- [AuthSessionRepository.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/persistence/AuthSessionRepository.java#L1-L5)
- [AuthSession.java:7-53](file://src/main/java/com/smartcoffee/modules/auth/domain/AuthSession.java#L7-L53)

**Section sources**
- [AuthController.java:13-70](file://src/main/java/com/smartcoffee/modules/auth/api/AuthController.java#L13-L70)
- [AuthenticationService.java:16-100](file://src/main/java/com/smartcoffee/modules/auth/application/AuthenticationService.java#L16-L100)
- [JwtTokenService.java:23-85](file://src/main/java/com/smartcoffee/modules/auth/security/JwtTokenService.java#L23-L85)
- [RefreshTokenService.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/security/RefreshTokenService.java#L1-L5)
- [AuthSessionRepository.java:1-5](file://src/main/java/com/smartcoffee/modules/auth/persistence/AuthSessionRepository.java#L1-L5)
- [AuthSession.java:7-53](file://src/main/java/com/smartcoffee/modules/auth/domain/AuthSession.java#L7-L53)

## Performance Considerations
- Use short-lived access tokens to minimize exposure window
- Rotate refresh tokens on each refresh to limit reuse risk
- Avoid storing sensitive data in JWT claims beyond necessary identifiers and versions
- Ensure database queries for session lookup and revocation are indexed appropriately
- Consider caching frequently accessed user metadata if needed, while maintaining consistency with security_version changes

[No sources needed since this section provides general guidance]

## Troubleshooting Guide
Common errors and their meanings:
- AUTH_UNAUTHORIZED: Invalid credentials or unavailable account during login; invalid or expired refresh session during refresh
- CONFLICT: Duplicate email during registration
- Validation errors: Missing or invalid fields in request bodies

Debugging tips:
- Verify Authorization header format: Authorization: Bearer <token>
- Confirm refresh_token cookie presence and correctness for refresh endpoint
- Check server logs for exception stack traces and correlation IDs included in responses

**Section sources**
- [UnauthorizedException.java:1-13](file://src/main/java/com/smartcoffee/common/exception/UnauthorizedException.java#L1-L13)
- [ConflictException.java:1-13](file://src/main/java/com/smartcoffee/common/exception/ConflictException.java#L1-L13)
- [ApiErrorResponse.java:1-15](file://src/main/java/com/smartcoffee/common/api/ApiErrorResponse.java#L1-L15)

## Conclusion
The Authentication API provides secure, stateless access control using JWT access tokens and hashed refresh tokens stored in sessions. Clients authenticate via email/password, receive an access token and a refresh token cookie, and can rotate refresh tokens to maintain continuous access. Proper security practices include using HTTPS, protecting the HMAC secret, and implementing rate limiting and account lockout policies at the infrastructure or application layers.

[No sources needed since this section summarizes without analyzing specific files]