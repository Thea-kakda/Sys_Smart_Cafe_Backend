package com.smartcoffee.modules.rbac.persistence;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import com.smartcoffee.modules.rbac.domain.UserRoleAssignment;

public interface PermissionQueryRepository extends Repository<UserRoleAssignment, UUID> {
 @Query(value = "select distinct p.permission_code from identity.user_role_assignments ura join identity.role_permissions rp on rp.role_id=ura.role_id join identity.permissions p on p.permission_id=rp.permission_id where ura.user_id=:userId and ura.revoked_at is null and (ura.expires_at is null or ura.expires_at>:now) and (ura.store_id is null or ura.store_id=:storeId)", nativeQuery = true)
 Set<String> permissions(@Param("userId") UUID userId, @Param("storeId") UUID storeId, @Param("now") Instant now);

 @Query(value = "select distinct ura.store_id from identity.user_role_assignments ura where ura.user_id=:userId and ura.store_id is not null and ura.revoked_at is null and (ura.expires_at is null or ura.expires_at>:now)", nativeQuery = true)
 Set<UUID> storeIds(@Param("userId") UUID userId, @Param("now") Instant now);

 @Query(value = "select exists(select 1 from identity.user_role_assignments ura join identity.roles r on r.role_id=ura.role_id where ura.user_id=:userId and ura.store_id is null and ura.revoked_at is null and (ura.expires_at is null or ura.expires_at>:now) and r.role_code in ('SUPER_ADMIN','ADMIN'))", nativeQuery = true)
 boolean global(@Param("userId") UUID userId, @Param("now") Instant now);

 @Query(value = "select distinct p.permission_code from identity.user_role_assignments ura join identity.role_permissions rp on rp.role_id=ura.role_id join identity.permissions p on p.permission_id=rp.permission_id where ura.user_id=:userId and ura.store_id is null and ura.revoked_at is null and (ura.expires_at is null or ura.expires_at>:now)", nativeQuery = true)
 Set<String> globalPermissions(@Param("userId") UUID userId, @Param("now") Instant now);

 @Query(value = "select distinct r.role_code from identity.user_role_assignments ura join identity.roles r on r.role_id=ura.role_id where ura.user_id=:userId and ura.revoked_at is null and (ura.expires_at is null or ura.expires_at>:now)", nativeQuery = true)
 Set<String> activeRoles(@Param("userId") UUID userId, @Param("now") Instant now);
}

