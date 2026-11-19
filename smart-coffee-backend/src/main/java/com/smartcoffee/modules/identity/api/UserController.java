package com.smartcoffee.modules.identity.api;
import com.smartcoffee.common.api.ApiResponse; 
import com.smartcoffee.common.context.CorrelationContext; 
import com.smartcoffee.common.security.CurrentActorProvider; 
import com.smartcoffee.modules.identity.application.UserQueryService; 
import com.smartcoffee.modules.identity.application.AccessContextQueryService; 
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@Tag(name = "User Profile", description = "Self-service profile and access context under /api/v1/users")
@RestController @RequestMapping("/api/v1/users") 
public class UserController { 
    private final UserQueryService users; 
    private final AccessContextQueryService accessContext;
    private final CurrentActorProvider actor; 
    public UserController(UserQueryService u, AccessContextQueryService ac, CurrentActorProvider a){
        users=u;accessContext=ac;actor=a;
    } 
    @Operation(summary = "Read own profile",
            description = "Authentication: bearer access token. Self-scoped: the subject of the token; no id "
                    + "parameter, no admin permission required. Response: ApiResponse<UserResponse> (profile fields only — no "
                    + "credentials or token data).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token")
    })
    @GetMapping("/me") ApiResponse<UserResponse> me(){
        return ApiResponse.ok(users.me(actor.require().userId()),
        CorrelationContext.requestId());
    } 
    @Operation(summary = "Update own profile",
            description = "Authentication: bearer access token. Self-scoped profile update "
                    + "(UpdateUserProfileRequest, validated). Status, roles, and "
                    + "security fields are NOT settable here — administration lives under "
                    + "/api/v1/management/staff (privilege-escalation guard).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — invalid field values"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token")
    })
    @PutMapping("/me") ApiResponse<UserResponse> updateMe(@Valid @RequestBody UpdateUserProfileRequest request){
        return ApiResponse.ok(users.update(actor.require().userId(), request),
        CorrelationContext.requestId());
    } 
    @Operation(summary = "Read own access context",
            description = "Everything an authenticated client needs to render its own authority: userId, fullName, "
                    + "email, preferredLanguage, status, effective roles and permission codes, globalAccess, and "
                    + "accessibleBranches (storeId/code/name/timezone/currency/language/active). Authentication: "
                    + "bearer access token; strictly self-scoped — no permission code is required and no other "
                    + "user's context can be requested (no IDOR surface). Derived live from active, non-expired "
                    + "assignments, so revoked authority disappears on the next call. "
                    + "Response: ApiResponse<AccessContextResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid or still-authorized access token (security_version bump also lands here)")
    })
    @GetMapping("/me/access-context") ApiResponse<AccessContextResponse> accessContext(){
        return ApiResponse.ok(accessContext.build(actor.require()),
        CorrelationContext.requestId());
    } 
}

