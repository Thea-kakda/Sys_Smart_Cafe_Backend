package com.smartcoffee.modules.identity.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.identity.application.UserDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected User Directory API (Full-stack wiring §1.1): the global-administrative
 * view over ALL identity users — staff and customers — backing the "Users" screen
 * of the management dashboard. Distinct from {@code /api/v1/management/staff}
 * (branch-scoped staff roster) and guarded by its own permission,
 * user:admin-read (V028, global admins only). Read-only by design: mutations stay
 * on the staff-management and profile contracts that own their state machines.
 */
@Tag(name = "User Directory", description = "Global user listing under /api/v1/management/users")
@RestController
@RequestMapping("/api/v1/management/users")
public class UserDirectoryController {

    private final UserDirectoryService service;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public UserDirectoryController(UserDirectoryService service, CurrentActorProvider actors,
            PermissionEvaluator permissions) {
        this.service = service;
        this.actors = actors;
        this.permissions = permissions;
    }

    @Operation(summary = "Search all users (administration)",
            description = "Read-only paginated directory over every identity user (staff AND customers). "
                    + "Authentication: bearer access token. Permission: user:admin-read (global admins only). "
                    + "Filters: search (name/email fragment), status (ACTIVE|DISABLED|LOCKED), roleCode, "
                    + "storeId (active assignment at that store), accountType ALL|STAFF|CUSTOMER. Newest "
                    + "first; page size bounded at 100. Each row carries the active role codes and the "
                    + "customer-profile flag; no credential material is ever included. Response: "
                    + "ApiResponse<PaginatedResponse<ManagementUserSummary>>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — unknown accountType or malformed status/roleCode value"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks user:admin-read")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<ManagementUserSummary>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "ALL") String accountType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.USER_ADMIN_READ);
        Page<ManagementUserSummary> result = service.list(actor, search, status, roleCode, storeId, accountType,
                page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Read one user with assignment history (administration)",
            description = "Directory detail: user header, FULL role-assignment history (active, expired and "
                    + "revoked rows) and, when present, the customer-profile slice (loyalty number, preferred "
                    + "store, birth date, marketing opt-in). Authentication: bearer access token. Permission: "
                    + "user:admin-read. Response: ApiResponse<ManagementUserDetail>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks user:admin-read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND — unknown user id")
    })
    @GetMapping("/{userId}")
    public ApiResponse<ManagementUserDetail> detail(@PathVariable UUID userId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.USER_ADMIN_READ);
        return ApiResponse.ok(service.detail(actor, userId), CorrelationContext.requestId());
    }
}
