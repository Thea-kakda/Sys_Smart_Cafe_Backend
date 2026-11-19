package com.smartcoffee.modules.file;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.StorageException;
import com.smartcoffee.modules.file.api.FileSignedUrlResponse;
import com.smartcoffee.modules.file.api.FileUploadResponse;
import com.smartcoffee.modules.file.application.FileStorageService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * File API behaviour against the real migrated database and the Testcontainers
 * MinIO instance. Exercises the {@link FileStorageService} contract end to end:
 * permission gating (V031 grant matrix), per-category prefix binding, server-side
 * key generation (idempotent / traversal-safe), the validation rejections
 * (empty / oversize / unsupported type) that flow up from {@code FileValidator}
 * and {@code StoragePathPolicy}, signed-URL issuance and the cross-category and
 * delete-authorization guards. Storage is NOT stubbed — objects are actually
 * written to the sandboxed private bucket, so a returned key provably round-trips.
 */
class FileStorageServiceIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000002";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    FileStorageService service;
    @Autowired
    ObjectStorageService storage;

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "File User", email, email, "en-US");
        return userId;
    }

    private UUID roleIdByCode(String code) {
        return UUID.fromString(jdbc.queryForObject("select role_id from identity.roles where role_code = ?",
                String.class, code));
    }

    private UUID seedStore(String name) {
        UUID storeId = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?::uuid, ?, ?, 'Addr', 'Asia/Phnom_Penh', 'USD', 'km-KH', true, now(), now(), 0)",
                storeId.toString(), "FILE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), name);
        return storeId;
    }

    private void assign(UUID userId, String roleCode, UUID storeId) {
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), userId, roleIdByCode(roleCode),
                storeId == null ? null : storeId.toString());
    }

    private CurrentActor authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    private CurrentActor admin() {
        UUID userId = seedUser("fileadmin-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, '" + SUPER_ADMIN_ROLE_ID + "', null, ?)", UUID.randomUUID(), userId,
                java.sql.Timestamp.from(Instant.now()));
        return authenticate(userId);
    }

    private static ByteArrayInputStream body() {
        return new ByteArrayInputStream("binary-ish payload".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void adminHoldsUploadAndDeleteViaV031() {
        CurrentActor admin = admin();
        assertTrue(admin.hasPermission(PermissionCodes.FILE_UPLOAD));
        assertTrue(admin.hasPermission(PermissionCodes.FILE_DELETE));
        SecurityContextHolder.clearContext();
    }

    @Test
    void productUploadStoresUnderOwnPrefixAndReturnsSignedUrl() {
        CurrentActor admin = admin();
        FileUploadResponse resp = service.upload(admin, "products", "iced latte.jpg", "image/jpeg", 17, body());

        assertTrue(resp.objectKey().startsWith("products/"), resp.objectKey());
        // filename is sanitized (space -> -) and combined with a server uuid; the
        // original name is echoed back untouched for the UI.
        assertTrue(resp.objectKey().endsWith("-iced-latte.jpg"), resp.objectKey());
        assertEquals("iced latte.jpg", resp.originalFilename());
        assertEquals("image/jpeg", resp.contentType());
        assertEquals(17L, resp.size());
        assertNotNull(resp.url());
        assertFalse(resp.url().isBlank());
        // the object really landed in the sandboxed bucket
        assertTrue(storage.exists(resp.objectKey()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void categoryAndDocumentUploadBindTheirOwnPrefixes() {
        CurrentActor admin = admin();
        FileUploadResponse cat = service.upload(admin, "categories", "coffee.png", "image/png", 17, body());
        assertTrue(cat.objectKey().startsWith("categories/"), cat.objectKey());

        FileUploadResponse doc = service.upload(admin, "documents", "invoice.pdf", "application/pdf", 17, body());
        assertTrue(doc.objectKey().startsWith("documents/"), doc.objectKey());
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadRequiresFileUploadPermission() {
        UUID store = seedStore("Auth Store");
        UUID cashier = seedUser("filecashier-" + UUID.randomUUID() + "@smart.coffee");
        assign(cashier, "CASHIER", store);
        CurrentActor actor = authenticate(cashier);
        assertFalse(actor.hasPermission(PermissionCodes.FILE_UPLOAD));

        assertThrows(ForbiddenException.class,
                () -> service.upload(actor, "products", "x.jpg", "image/jpeg", 17, body()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void unsupportedContentTypeIsRejected() {
        CurrentActor admin = admin();
        StorageException ex = assertThrows(StorageException.class, () -> service.upload(admin, "documents",
                "payload.exe", "application/x-msdownload", 17, body()));
        assertEquals("UNSUPPORTED_CONTENT_TYPE", ex.code());
        assertEquals(415, ex.status());
        SecurityContextHolder.clearContext();
    }

    @Test
    void oversizeFileIsRejected() {
        CurrentActor admin = admin();
        StorageException ex = assertThrows(StorageException.class, () -> service.upload(admin, "products",
                "big.jpg", "image/jpeg", 20_000_000L, body()));
        assertEquals("FILE_TOO_LARGE", ex.code());
        assertEquals(413, ex.status());
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyFileIsRejected() {
        CurrentActor admin = admin();
        StorageException ex = assertThrows(StorageException.class,
                () -> service.upload(admin, "products", "empty.jpg", "image/jpeg", 0L, body()));
        assertEquals("INVALID_FILE", ex.code());
        SecurityContextHolder.clearContext();
    }

    @Test
    void traversalAttemptIsSanitizedNotEscaped() {
        CurrentActor admin = admin();
        FileUploadResponse resp = service.upload(admin, "products", "../../etc/passwd.jpg", "image/jpeg", 17, body());
        assertTrue(resp.objectKey().startsWith("products/"), resp.objectKey());
        assertFalse(resp.objectKey().contains(".."), resp.objectKey());
        assertFalse(resp.objectKey().contains("etc"), resp.objectKey());
        SecurityContextHolder.clearContext();
    }

    @Test
    void unknownCategoryIsRejected() {
        CurrentActor admin = admin();
        assertThrows(com.smartcoffee.common.exception.ValidationException.class,
                () -> service.upload(admin, "users", "avatar.jpg", "image/jpeg", 17, body()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void signedUrlIssuedAndCrossCategoryKeyRejected() {
        CurrentActor admin = admin();
        FileUploadResponse upload = service.upload(admin, "products", "latte.jpg", "image/jpeg", 17, body());

        FileSignedUrlResponse signed = service.signedUrl(admin, "products", upload.objectKey());
        assertEquals(upload.objectKey(), signed.objectKey());
        assertNotNull(signed.url());
        assertEquals(900L, signed.expiresIn());

        // a products key can never be addressed through the categories endpoint
        StorageException ex = assertThrows(StorageException.class,
                () -> service.signedUrl(admin, "categories", upload.objectKey()));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteIsAdminOnlyAndCategoryScoped() {
        // STORE_MANAGER may upload but NOT delete (V031 grant matrix).
        UUID store = seedStore("Delete Store");
        UUID mgr = seedUser("filemgr-" + UUID.randomUUID() + "@smart.coffee");
        assign(mgr, "STORE_MANAGER", store);
        CurrentActor manager = authenticate(mgr);
        assertTrue(manager.hasPermission(PermissionCodes.FILE_UPLOAD));
        assertFalse(manager.hasPermission(PermissionCodes.FILE_DELETE));
        FileUploadResponse upload = service.upload(manager, "products", "keep.jpg", "image/jpeg", 17, body());

        assertThrows(ForbiddenException.class,
                () -> service.delete(manager, "products", upload.objectKey()));

        // an admin can delete, but only through the matching category prefix
        CurrentActor admin = admin();
        assertThrows(StorageException.class,
                () -> service.delete(admin, "categories", upload.objectKey()));
        assertDoesNotThrow(() -> service.delete(admin, "products", upload.objectKey()));
        assertFalse(storage.exists(upload.objectKey()));
        SecurityContextHolder.clearContext();
    }
}
