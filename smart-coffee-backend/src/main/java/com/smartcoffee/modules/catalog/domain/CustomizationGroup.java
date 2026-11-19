package com.smartcoffee.modules.catalog.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(schema="catalog", name="customization_groups")
public class CustomizationGroup {
    @Id @Column(name="group_id") private UUID id;
    @Column(name="group_code", nullable=false, unique=true, length=50) private String code;
    @Column(name="group_name", nullable=false, length=100) private String name;
    @Enumerated(EnumType.STRING) @Column(name="selection_type", nullable=false, length=20) private SelectionType selectionType;
    @Column(name="minimum_selections", nullable=false) private int minimumSelections;
    @Column(name="maximum_selections") private Integer maximumSelections;
    @Column(name="display_order", nullable=false) private int displayOrder;
    @Column(nullable=false) private boolean active;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @Version @Column(nullable=false) private long version;
    protected CustomizationGroup() {}
    public UUID id(){return id;} public String code(){return code;} public String name(){return name;} public SelectionType selectionType(){return selectionType;}
    public int minimumSelections(){return minimumSelections;} public Integer maximumSelections(){return maximumSelections;} public int displayOrder(){return displayOrder;}
    public long version(){return version;}
    /**
     * Master-group active flag. The customer/POS catalog paths filter on it in
     * SQL (CustomizationGroupRepository.findForProduct) and never expose it, so
     * only the management read surface needs the accessor: an editor must be
     * able to see an attached-but-inactive group and detach it deliberately.
     */
    public boolean isActive(){return active;}

    /**
     * Round 2 (§7) master management: a group is created active with its own
     * identity. The selection type is fixed at creation — flipping SINGLE to
     * MULTIPLE (or back) would silently invalidate per-product attachments and
     * existing cart snapshots that were validated under the old type, so the
     * update path never touches it. Limits follow the same shape rules the DB
     * CHECKs use: non-negative, max &gt;= min, and a SINGLE group caps at 1.
     */
    public static CustomizationGroup create(String code, String name, SelectionType selectionType,
            int minimumSelections, Integer maximumSelections, int displayOrder) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Group code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
        if (selectionType == null) {
            throw new IllegalArgumentException("Selection type is required");
        }
        validateLimits(selectionType, minimumSelections, maximumSelections, displayOrder);
        CustomizationGroup g = new CustomizationGroup();
        g.id = UUID.randomUUID();
        g.code = code.trim();
        g.name = name.trim();
        g.selectionType = selectionType;
        g.minimumSelections = minimumSelections;
        g.maximumSelections = maximumSelections;
        g.displayOrder = displayOrder;
        g.active = true;
        Instant now = Instant.now();
        g.createdAt = now;
        g.updatedAt = now;
        return g;
    }

    /** Name and limits stay editable; the selection type never changes (see create). */
    public void updateMetadata(String name, int minimumSelections, Integer maximumSelections, int displayOrder) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
        validateLimits(selectionType, minimumSelections, maximumSelections, displayOrder);
        this.name = name.trim();
        this.minimumSelections = minimumSelections;
        this.maximumSelections = maximumSelections;
        this.displayOrder = displayOrder;
        this.updatedAt = Instant.now();
    }

    public void markActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    private static void validateLimits(SelectionType type, int min, Integer max, int displayOrder) {
        if (min < 0) {
            throw new IllegalArgumentException("Minimum selections must be non-negative");
        }
        if (max != null && max < 0) {
            throw new IllegalArgumentException("Maximum selections must be non-negative");
        }
        if (max != null && max < min) {
            throw new IllegalArgumentException("Maximum selections must be >= minimum selections");
        }
        if (type == SelectionType.SINGLE && (max == null || max != 1)) {
            throw new IllegalArgumentException("A SINGLE group must cap maximumSelections at 1");
        }
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Display order must be non-negative");
        }
    }
}

