package com.smartcoffee.modules.file.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.infrastructure.storage.ObjectMetadata;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.StorageException;
import com.smartcoffee.infrastructure.storage.StoragePathPolicy;
import com.smartcoffee.infrastructure.storage.StorageProperties;
import com.smartcoffee.modules.file.api.FileSignedUrlResponse;
import com.smartcoffee.modules.file.api.FileUploadResponse;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Focused unit coverage of the {@link FileStorageService} contract that does not
 * need Docker: authorization, server-side key binding, cross-category isolation
 * and response mapping. Real {@link PermissionEvaluator} and
 * {@link StoragePathPolicy} are used so the checks exercised here are the exact
 * ones shipped; only the MinIO-backed {@link ObjectStorageService} is stubbed.
 */
class FileStorageServiceTest {

    private static final List<String> PREFIXES = List.of(
            "products/", "categories/", "users/", "invoices/", "receipts/", "documents/");

    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final StorageProperties properties = new StorageProperties(
            PREFIXES, List.of("image/jpeg"), 10_485_760L, 900L);
    private final StoragePathPolicy pathPolicy = new StoragePathPolicy(properties);
    private final FileStorageService service =
            new FileStorageService(storage, pathPolicy, new PermissionEvaluator(), properties);

    private final CurrentActor uploader = actor(Set.of(PermissionCodes.FILE_UPLOAD));
    private final CurrentActor admin = actor(Set.of(PermissionCodes.FILE_UPLOAD, PermissionCodes.FILE_DELETE));

    private static CurrentActor actor(Set<String> perms) {
        return new CurrentActor(UUID.randomUUID(), "USER", perms, Set.of(), true);
    }

    private static ByteArrayInputStream body() {
        return new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8));
    }

    private void stubStore(String contentType, long size) {
        // the service uses only contentType/size from the returned metadata; the
        // object key it reports is the one it generated itself.
        when(storage.upload(any(), eq(contentType), eq(size), any()))
                .thenReturn(new ObjectMetadata("bucket", "ignored", contentType, size, null, Instant.now()));
        when(storage.signedGetUrl(any(), any())).thenReturn(URI.create("https://minio/signed?X-Amz-Signature=abc"));
    }

    @Test
    void uploadGeneratesKeyUnderCategoryPrefixAndReturnsSignedUrl() {
        stubStore("image/jpeg", 1L);
        FileUploadResponse resp = service.upload(uploader, "products", "iced latte.jpg", "image/jpeg", 1L, body());

        assertTrue(resp.objectKey().startsWith("products/"), resp.objectKey());
        assertTrue(resp.objectKey().endsWith("-iced-latte.jpg"), resp.objectKey());
        assertEquals("iced latte.jpg", resp.originalFilename());
        assertEquals("https://minio/signed?X-Amz-Signature=abc", resp.url());
        verify(storage).upload(eq(resp.objectKey()), eq("image/jpeg"), eq(1L), any());
    }

    @Test
    void uploadRejectsCallerWithoutFileUploadPermission() {
        CurrentActor nobody = actor(Set.of("order:read"));
        assertThrows(ForbiddenException.class,
                () -> service.upload(nobody, "products", "a.jpg", "image/jpeg", 1L, body()));
        verify(storage, never()).upload(any(), any(), anyLong(), any());
    }

    @Test
    void unknownCategoryIsRejectedBeforeAnyStorageCall() {
        assertThrows(ValidationException.class,
                () -> service.upload(uploader, "secrets", "a.jpg", "image/jpeg", 1L, body()));
        verify(storage, never()).upload(any(), any(), anyLong(), any());
    }

    @Test
    void signedUrlRejectsKeyFromAnotherCategory() {
        StorageException ex = assertThrows(StorageException.class,
                () -> service.signedUrl(uploader, "categories", "products/uuid-latte.jpg"));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
        verify(storage, never()).signedGetUrl(any(), any());
    }

    @Test
    void signedUrlIssuedForOwnCategoryKey() {
        when(storage.signedGetUrl(any(), any())).thenReturn(URI.create("https://minio/signed"));
        FileSignedUrlResponse resp = service.signedUrl(uploader, "products", "products/uuid-latte.jpg");
        assertEquals("products/uuid-latte.jpg", resp.objectKey());
        assertEquals(900L, resp.expiresIn());
    }

    @Test
    void deleteRequiresFileDeletePermission() {
        assertThrows(ForbiddenException.class,
                () -> service.delete(uploader, "products", "products/uuid-latte.jpg"));
        verify(storage, never()).delete(any());
    }

    @Test
    void deleteIsScopedToCategoryPrefix() {
        assertThrows(StorageException.class,
                () -> service.delete(admin, "documents", "products/uuid-latte.jpg"));
        verify(storage, never()).delete(any());
        // happy path: matching prefix deletes
        service.delete(admin, "products", "products/uuid-latte.jpg");
        verify(storage).delete("products/uuid-latte.jpg");
    }
}
