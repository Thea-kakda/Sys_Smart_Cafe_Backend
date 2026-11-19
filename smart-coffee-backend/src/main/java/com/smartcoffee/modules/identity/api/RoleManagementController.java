package com.smartcoffee.modules.identity.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.identity.application.RoleAdministrationService;
import com.smartcoffee.modules.identity.application.RolePermissionManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Protected Roles &amp; Permissions Administration API (Master Prompt §7, extended
 * by V052). Serves {@code /api/v1/management/roles} and
 * {@code /api/v1/management/permissions} under the authenticated security filter
 * chain, enforcing an exact permission per operation server-side. Reads need
 * role:read / permission:read; the grant replacement keeps role:permission-manage
 * plus the V052 privilege ladder; role lifecycle needs the SUPER_ADMIN-only
 * role:create / role:update / role:delete / role:assignment-read codes.
 */
@Tag(name = "Roles & Permissions", description = "Protected role/permission administration under /api/v1/management")
@RestController
@RequestMapping("/api/v1/management")
public class RoleManagementController {

    private final RolePermissionManagementService service;
    private final RoleAdministrationService admin;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public RoleManagementController(RolePermissionManagementService service, RoleAdministrationService admin,
            CurrentActorProvider actors, PermissionEvaluator permissions) {
        this.service = service;
        this.admin = admin;
        this.actors = actors;
        this.permissions = permissions;
    }

    @Operation(summary = "List all roles with granted permissions",
            description = "Every role with its full permission-code set plus the V052 lifecycle metadata "
                    + "(isSystem, roleScope, description). Authentication: bearer access token. "
                    + "Permission: role:read. No store scope (roles are global metadata). "
                    + "Response: ApiResponse<List<RoleResponse>>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks role:read")
    })
    @GetMapping("/roles")
    public ApiResponse<List<RoleResponse>> listRoles() {
        permissions.require(actors.require(), PermissionCodes.ROLE_READ);
        return ApiResponse.ok(service.listRoles(), CorrelationContext.requestId());
    }

    @Operation(summary = "Read one role with its granted permissions",
            description = "Authentication: bearer access token. Permission: role:read. "
                    + "Response: ApiResponse<RoleResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks role:read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROLE_NOT_FOUND — unknown role id")
    })
    @GetMapping("/roles/{roleId}")
    public ApiResponse<RoleResponse> roleDetail(@PathVariable UUID roleId) {
        permissions.require(actors.require(), PermissionCodes.ROLE_READ);
        return ApiResponse.ok(service.roleDetail(roleId), CorrelationContext.requestId());
    }

    @Operation(summary = "List the permission catalog",
            description = "All persisted permissions with a display category derived from the code prefix. "
                    + "Authentication: bearer access token. Permission: permission:read. "
                    + "Response: ApiResponse<List<PermissionResponse>>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks permission:read")
    })
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionResponse>> listPermissions() {
        permissions.require(actors.require(), PermissionCodes.PERMISSION_READ);
        return ApiResponse.ok(service.listPermissions(), CorrelationContext.requestId());
    }

    @Operation(summary = "Replace a role's permission set",
            description = "Full-replacement PUT of one role's grants (UpdateRolePermissionsRequest: "
                    + "{permissionCodes: [...]}). Authentication: bearer access token. Permission: "
                    + "role:permission-manage. Unknown codes are rejected (no permission is ever minted here); "
                    + "SUPER_ADMIN is immutable so administrators cannot lock themselves out. Every member of the "
                    + "role has security_version bumped and the old/new sets are audited. "
                    + "Response: ApiResponse<RoleResponse> with the persisted set.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — PERMISSION_UNKNOWN for a code outside the catalog"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks role:permission-manage, or PROTECTED_ROLE (SUPER_ADMIN)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROLE_NOT_FOUND — unknown role id")
    })
    @PutMapping("/roles/{roleId}/permissions")
    public ApiResponse<RoleResponse> updateRolePermissions(@PathVariable UUID roleId,
            @Valid @RequestBody UpdateRolePermissionsRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.ROLE_PERMISSION_MANAGE);
        return ApiResponse.ok(service.updateRolePermissions(actor, roleId, request.permissionCodes()),
                CorrelationContext.requestId());
    }

    // ------------------------------------------------------- V052 role lifecycle

    @Operation(summary = "Create a custom role",
            description = "Mints an EMPTY custom role (is_system=false) with the given code/name/description "
                    + "and scope. Permissions are attached afterwards via PUT /roles/{roleId}/permissions and "
                    + "can never be pre-assigned in this call. Authentication: bearer access token. "
                    + "Permission: role:create (SUPER_ADMIN only after V052). "
                    + "Response: ApiResponse<RoleResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — bad roleCode pattern / roleScope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks role:create"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROLE_CODE_EXISTS — code already taken")
    })
    @PostMapping("/roles")
    public ApiResponse<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.ROLE_CREATE);
        return ApiResponse.ok(
                admin.create(actor, request.roleCode(), request.roleName(), request.description(),
                        request.roleScope()),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Rename a custom role / edit its description",
            description = "PATCH of roleName and/or description. role_code, role_scope and the system flag "
                    + "are immutable; system roles are refused with 403 PROTECTED_ROLE. Authentication: bearer "
                    + "access token. Permission: role:update (SUPER_ADMIN only after V052). "
                    + "Response: ApiResponse<RoleResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — ROLE_UPDATE_EMPTY when both fields are absent"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PROTECTED_ROLE — system roles are immutable"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROLE_NOT_FOUND")
    })
    @PatchMapping("/roles/{roleId}")
    public ApiResponse<RoleResponse> updateRole(@PathVariable UUID roleId,
            @Valid @RequestBody UpdateRoleRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.ROLE_UPDATE);
        return ApiResponse.ok(admin.update(actor, roleId, request.roleName(), request.description()),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Delete an unreferenced custom role",
            description = "Deletes the role's grant rows and the role in one transaction. System roles are "
            + "refused (403); any assignment — active or historical — blocks with 409 ROLE_IN_USE. "
            + "Authentication: bearer access token. Permission: role:delete (SUPER_ADMIN only after V052). "
            + "Response: 204 via ApiResponse<Void>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PROTECTED_ROLE — system roles are immutable"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROLE_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROLE_IN_USE — assignments exist")
    })
    @DeleteMapping("/roles/{roleId}")
    public ApiResponse<Void> deleteRole(@PathVariable UUID roleId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.ROLE_DELETE);
        admin.delete(actor, roleId);
        return ApiResponse.ok(null, CorrelationContext.requestId());
    }

    @Operation(summary = "List a role's assignments",
            description = "Paginated assignment history (active AND revoked) with holder ids, account status "
                    + "and store scope only — no emails, no phones. Authentication: bearer access token. "
                    + "Permission: role:assignment-read (SUPER_ADMIN only after V052). "
                    + "Response: ApiResponse<Page<RoleAssignmentSummary>>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROLE_NOT_FOUND")
    })
    @GetMapping("/roles/{roleId}/assignments")
    public ApiResponse<Page<RoleAssignmentSummary>> roleAssignments(@PathVariable UUID roleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        permissions.require(actors.require(), PermissionCodes.ROLE_ASSIGNMENT_READ);
        return ApiResponse.ok(admin.assignments(roleId, page, size), CorrelationContext.requestId());
    }
}
