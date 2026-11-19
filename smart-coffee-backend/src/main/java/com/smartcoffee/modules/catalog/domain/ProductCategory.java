package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "catalog", name = "product_categories")
public class ProductCategory {
    @Id @Column(name = "category_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "parent_category_id") private UUID parentCategoryId;
    @Column(name = "category_code", nullable = false, unique = true, length = 50) private String code;
    @Column(name = "category_name", nullable = false, length = 100) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "image_object_key") private String imageObjectKey;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected ProductCategory() {}

    /**
     * Creates a new category (master data). Parent is optional (null = top level).
     * The id is application-generated. Self-parenting is rejected here; existence
     * of the parent id is validated in the application service.
     */
    public static ProductCategory create(UUID parentCategoryId, String code, String name, String description,
            int displayOrder, boolean active, String imageObjectKey) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Category code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Category name is required");
        }
        var category = new ProductCategory();
        category.id = UUID.randomUUID();
        category.parentCategoryId = parentCategoryId;
        category.code = code.trim();
        category.name = name.trim();
        category.description = description;
        category.displayOrder = displayOrder;
        category.active = active;
        category.imageObjectKey = null;
        if (imageObjectKey != null && !imageObjectKey.isBlank()) {
            category.changeImage(imageObjectKey);
        }
        var now = Instant.now();
        category.createdAt = now;
        category.updatedAt = now;
        return category;
    }

    /** Full-replacement of the mutable descriptive fields. Image handled separately. */
    public void updateDetails(UUID parentCategoryId, String code, String name, String description,
            int displayOrder, boolean active) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Category code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Category name is required");
        }
        if (parentCategoryId != null && parentCategoryId.equals(this.id)) {
            throw new IllegalArgumentException("A category cannot be its own parent");
        }
        this.parentCategoryId = parentCategoryId;
        this.code = code.trim();
        this.name = name.trim();
        this.description = description;
        this.displayOrder = displayOrder;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    /**
     * Sets the private-bucket object key for the category image. Blank clears it.
     * Prefix ownership ({@code categories/}) is enforced in the application layer.
     */
    public void changeImage(String imageObjectKey) {
        this.imageObjectKey = (imageObjectKey == null || imageObjectKey.isBlank()) ? null : imageObjectKey.trim();
        this.updatedAt = Instant.now();
    }

    public UUID id() {return id;}
    public UUID parentCategoryId() {return parentCategoryId;}
    public String code() {return code;}
    public String name() {return name;}
    public String description() {return description;}
    public String imageObjectKey() {return imageObjectKey;}
    public int displayOrder() {return displayOrder;}
    public boolean active() {return active;}
    public Instant createdAt() {return createdAt;}
    public Instant updatedAt() {return updatedAt;}
    public long version() {return version;}
}
