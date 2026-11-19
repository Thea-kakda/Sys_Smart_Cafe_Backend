package com.smartcoffee.modules.identity.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.identity.application.StaffManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Protected Staff Administration API (Master Prompt §6). Lives under
 * {@code /api/v1/management/staff} so it never collides with the public
 * registration path, inherits authentication from the security filter chain, and
 * enforces an exact permission per operation server-side. Every mutation is
 * audited in the service; the browser is never authoritative.
 */
@Tag(name = "Staff Management", description = "Protected staff administration under /api/v1/management/staff")
@RestController
@RequestMapping("/api/v1/management/staff")
public class StaffManagementController {

    private final StaffManagementService service;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public StaffManagementController(StaffManagementService service, CurrentActorProvider actors,
            PermissionEvaluator permissions) {
        this.service = service;
        this.actors = actors;
        this.permissions = permissions;
    }

    @Operation(summary = "Search staff members",
            description = "Paginated staff directory for administration. Authentication: bearer access token. "
                    + "Permission: staff:read. Store scope: all staff are visible to any holder of staff:read; "
                    + "storeId narrows to members with an active assignment on that branch. "
                    + "Filters: search (case-insensitive name/email fragment), status (ACTIVE|DISABLED), roleCode. "
                    + "Sort whitelist: fullName, email, status, createdAt, updatedAt (default createdAt, DESC; "
                    + "unsupported values silently fall back). Pagination: zero-based page, bounded size. "
                    + "Response: ApiResponse<PaginatedResponse<StaffResponse>>; StaffResponse contains no "
                    + "credentials, hashes, or token fields.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — malformed parameter type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks staff:read")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<StaffResponse>> list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        permissions.require(actors.require(), PermissionCodes.STAFF_READ);
        Page<StaffResponse> result = service.list(search, status, roleCode, storeId, sort, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Read one staff member",
            description = "Staff detail including role assignments. Authentication: bearer access token. "
                    + "Permission: staff:read. Response: ApiResponse<StaffResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks staff:read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND — unknown staff id")
    })
    @GetMapping("/{userId}")
    public ApiResponse<StaffResponse> detail(@PathVariable UUID userId) {
        permissions.require(actors.require(), PermissionCodes.STAFF_READ);
        return ApiResponse.ok(service.detail(userId), CorrelationContext.requestId());
    }

    @Operation(summary = "Create a staff account",
            description = "Enrolls a new user with ACTIVE status. Authentication: bearer access token. "
                    + "Permission: staff:create. Request: CreateStaffRequest (fullName, email, password — "
                    + "enrollment-only, min 10 chars, stored as a hash, never echoed back — phone, preferredLanguage, plus roleCode/storeId/expiresAt driving the single initial assignment). Validation failures return 400 VALIDATION_ERROR with field details. "
                    + "Every creation is audited server-side. Response: ApiResponse<StaffResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — invalid or missing fields"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks staff:create"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED — normalized email is taken")
    })
    @PostMapping
    public ApiResponse<StaffResponse> create(@Valid @RequestBody CreateStaffRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.STAFF_CREATE);
        return ApiResponse.ok(service.create(actor, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Update staff profile",
            description = "Edits profile fields only (fullName, phone, preferredLanguage). Authentication: bearer "
                    + "access token. "
                    + "Email and password are NOT "
                    + "changeable here (authentication-ownership rule). Request: UpdateStaffRequest must echo the "
                    + "current version for optimistic locking. Permission: staff:update. Audited. "
                    + "Response: ApiResponse<StaffResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — invalid field values"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks staff:update"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND — unknown staff id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale version echo")
    })
    @PutMapping("/{userId}")
    public ApiResponse<StaffResponse> update(@PathVariable UUID userId, @Valid @RequestBody UpdateStaffRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.STAFF_UPDATE);
        return ApiResponse.ok(service.update(actor, userId, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Enable or disable a staff account",
            description = "Authentication: bearer access token. "
                    + "Status transition ACTIVE|DISABLED with a mandatory reason and version echo "
                    + "(StaffStatusRequest). Disabling bumps security_version and revokes refresh sessions "
                    + "server-side. Permission: staff:status-manage. Audited. Response: ApiResponse<StaffResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — missing reason or invalid status"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks staff:status-manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND — unknown staff id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale version echo")
    })
    @PostMapping("/{userId}/status")
    public ApiResponse<StaffResponse> changeStatus(@PathVariable UUID userId,
            @Valid @RequestBody StaffStatusRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.STAFF_STATUS_MANAGE);
        return ApiResponse.ok(service.changeStatus(actor, userId, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Assign a role to a staff member",
            description = "Authentication: bearer access token. Grants a role, globally (storeId null — only allowed for global administrator roles) or "
                    + "scoped to one branch (storeId required for store-level roles). Optional expiresAt must be in "
                    + "the future (RoleAssignmentRequest). Permission: staff:role-assign. Audited. "
                    + "Response: ApiResponse<StaffResponse> with the updated assignment list.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — GLOBAL_ROLE_STORE_CONFLICT, STORE_REQUIRED, or EXPIRY_IN_PAST"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks staff:role-assign"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND, ROLE_NOT_FOUND, or STORE_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ASSIGNMENT_EXISTS — same role/scope already granted")
    })
    @PostMapping("/{userId}/role-assignments")
    public ApiResponse<StaffResponse> addRoleAssignment(@PathVariable UUID userId,
            @Valid @RequestBody RoleAssignmentRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.STAFF_ROLE_ASSIGN);
        return ApiResponse.ok(service.addRoleAssignment(actor, userId, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Revoke one role assignment",
            description = "Authentication: bearer access token. Revokes a single assignment by id with a mandatory reason (AssignmentRevokeRequest); "
                    + "bumps the member's security_version server-side. "
                    + "Permission: staff:role-assign. Audited. The assignment must belong to the path user — a "
                    + "mismatch is 404, never a cross-user mutation (IDOR guard). "
                    + "Response: ApiResponse<StaffResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — missing reason"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks staff:role-assign"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND or ASSIGNMENT_NOT_FOUND (also used for assignment/user mismatch)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ASSIGNMENT_REVOKED — already revoked")
    })
    @PostMapping("/{userId}/role-assignments/{assignmentId}/revoke")
    public ApiResponse<StaffResponse> revokeRoleAssignment(@PathVariable UUID userId,
            @PathVariable UUID assignmentId, @Valid @RequestBody AssignmentRevokeRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.STAFF_ROLE_ASSIGN);
        return ApiResponse.ok(service.revokeRoleAssignment(actor, userId, assignmentId, request.reason()),
                CorrelationContext.requestId());
    }
}
