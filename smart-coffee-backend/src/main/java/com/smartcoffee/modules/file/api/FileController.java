package com.smartcoffee.modules.file.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.infrastructure.storage.StorageException;
import com.smartcoffee.modules.file.application.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Object-storage file API layered on the existing {@link FileStorageService}
 * (which in turn reuses {@code ObjectStorageService}/{@code SignedUrlService}).
 * It gives the frontend a controlled way to upload catalog media and documents
 * and to obtain short-lived presigned URLs WITHOUT ever seeing a bucket name or
 * a MinIO credential.
 *
 * <p>Design rules enforced here and downstream in the service:
 * <ul>
 *   <li>the client sends ONLY a {@code file} part — the bucket, the object-key
 *       prefix and the full key are chosen server-side;</li>
 *   <li>only the categories that actually make sense as uploads are exposed
 *       (products, categories, documents). Avatars (users/) have no profile
 *       write to attach to, and receipts/invoices are produced by their own
 *       domain services, so neither is offered here;</li>
 *   <li>authorization is service-layer ({@code file:upload} / {@code file:delete}),
 *       matching this project's convention; every route is also behind
 *       authentication at the security filter.</li>
 * </ul>
 * Validation (empty / oversize / content-type / traversal) is NOT duplicated in
 * the controller — it lives in {@code FileValidator} + {@code StoragePathPolicy}.
 */
@Tag(name = "Files", description = "Object-storage upload / signed-url / delete under /api/v1/files")
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileStorageService service;
    private final CurrentActorProvider actors;

    public FileController(FileStorageService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @Operation(summary = "Upload a product image",
            description = "Stores a product image under the fixed {@code products/} prefix. The server "
                    + "generates the object key ({@code products/{uuid}-{safe-filename}}); the client never "
                    + "supplies a bucket, prefix or key. Authentication: bearer access token. Permission: "
                    + "file:upload. Response: ApiResponse<FileUploadResponse> including a short-lived "
                    + "presigned URL and the objectKey to persist as the product's image_object_key.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Uploaded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_FILE / INVALID_OBJECT_KEY — empty file or unsafe name"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks file:upload"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE — exceeds the configured size limit"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "UNSUPPORTED_CONTENT_TYPE — type not in the allowlist")
    })
    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> uploadProduct(
            @RequestPart("file") MultipartFile file) {
        return upload("products", file);
    }

    @Operation(summary = "Upload a category image",
            description = "Stores a category image under the fixed {@code categories/} prefix with a "
                    + "server-generated key ({@code categories/{uuid}-{safe-filename}}). Authentication: "
                    + "bearer access token. Permission: file:upload. Response: "
                    + "ApiResponse<FileUploadResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Uploaded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_FILE / INVALID_OBJECT_KEY — empty file or unsafe name"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks file:upload"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE — exceeds the configured size limit"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "UNSUPPORTED_CONTENT_TYPE — type not in the allowlist")
    })
    @PostMapping(value = "/categories", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> uploadCategory(
            @RequestPart("file") MultipartFile file) {
        return upload("categories", file);
    }

    @Operation(summary = "Upload a business document",
            description = "Stores a document (PDF / plain text) under the fixed {@code documents/} prefix "
                    + "with a server-generated key. Authentication: bearer access token. Permission: "
                    + "file:upload. Response: ApiResponse<FileUploadResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Uploaded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_FILE / INVALID_OBJECT_KEY — empty file or unsafe name"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks file:upload"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE — exceeds the configured size limit"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "UNSUPPORTED_CONTENT_TYPE — type not in the allowlist")
    })
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> uploadDocument(
            @RequestPart("file") MultipartFile file) {
        return upload("documents", file);
    }

    @Operation(summary = "Issue a short-lived signed URL for an object",
            description = "Returns a fresh presigned GET URL for an object the caller may already address "
                    + "through this category. The {@code objectKey} MUST belong to the given category's "
                    + "prefix (e.g. a {@code products/} key only via {@code /products}); cross-category "
                    + "access is rejected. Authentication: bearer access token. Permission: file:upload. "
                    + "Response: ApiResponse<FileSignedUrlResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Signed URL issued"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_OBJECT_KEY — malformed key or key outside this category prefix"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks file:upload"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "OBJECT_NOT_FOUND — unknown category")
    })
    @GetMapping(value = "/{category}/signed-url")
    public ApiResponse<FileSignedUrlResponse> signedUrl(
            @PathVariable String category,
            @Parameter(in = ParameterIn.QUERY, required = true,
                    schema = @Schema(type = "string"),
                    description = "The server-assigned object key, e.g. products/{uuid}-latte.jpg")
            @RequestParam String objectKey) {
        var actor = actors.require();
        FileSignedUrlResponse body = service.signedUrl(actor, category, objectKey);
        return ApiResponse.ok(body, CorrelationContext.requestId());
    }

    @Operation(summary = "Delete a stored object",
            description = "Removes an object from the sandboxed bucket. The {@code objectKey} MUST belong to "
                    + "the given category's prefix; arbitrary keys and buckets are unreachable. Destructive — "
                    + "guarded by the stricter file:delete permission (global admins only). Authentication: "
                    + "bearer access token. Response: ApiResponse<Void>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_OBJECT_KEY — malformed key or key outside this category prefix"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks file:delete"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "DELETE_FAILED — storage backend rejected the delete")
    })
    @DeleteMapping(value = "/{category}")
    public ApiResponse<Void> delete(
            @PathVariable String category,
            @Parameter(in = ParameterIn.QUERY, required = true,
                    schema = @Schema(type = "string"),
                    description = "The server-assigned object key to delete")
            @RequestParam String objectKey) {
        var actor = actors.require();
        service.delete(actor, category, objectKey);
        return ApiResponse.ok(null, CorrelationContext.requestId());
    }

    /**
     * Shared upload path: reads the multipart stream and hands it to the service.
     * Content-type / size / empty-file / traversal validation and the
     * permission check all live in the service and storage layers.
     */
    private ApiResponse<FileUploadResponse> upload(String category, MultipartFile file) {
        var actor = actors.require();
        if (file == null || file.isEmpty()) {
            throw StorageException.invalidFile("File is empty");
        }
        try (InputStream in = file.getInputStream()) {
            FileUploadResponse body = service.upload(actor, category, file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), in);
            return ApiResponse.ok(body, CorrelationContext.requestId());
        } catch (IOException ex) {
            throw StorageException.uploadFailed(category, ex);
        }
    }
}
