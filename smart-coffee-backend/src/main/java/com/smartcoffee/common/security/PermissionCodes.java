package com.smartcoffee.common.security;

public final class PermissionCodes {
    private PermissionCodes() {
    }

    public static final String INVENTORY_READ = "inventory:read";
    public static final String AUDIT_READ = "audit:read";

    // Seeded and declared but NOT yet referenced by any PermissionEvaluator.require(...)
    // call. pos:access / order:read / inventory:adjust (V013) and notification:manage
    // (V010) are operational codes reserved for a future authorization pass. NOTE:
    // Orders Administration (Master Phase 4) intentionally uses the dedicated
    // order:admin-read (V025) below rather than reusing operational order:read, per
    // the spec's warning against conflating the two.
    public static final String POS_ACCESS = "pos:access";
    public static final String ORDER_READ = "order:read";
    public static final String INVENTORY_ADJUST = "inventory:adjust";

    // Production queue workflow (seeded V008). Previously hard-coded as string
    // literals in ProductionController; centralized here so every code is referenced
    // from a single source. Enforcement unchanged.
    public static final String PRODUCTION_QUEUE_READ = "production:queue-read";
    public static final String PRODUCTION_ITEM_START = "production:item-start";
    public static final String PRODUCTION_ITEM_READY = "production:item-ready";

    // Branch (technical term: Store) management capabilities introduced in
    // Checkpoint 2. branch:read guards the protected management read contract;
    // branch:manage guards create, update and status mutations.
    public static final String BRANCH_READ = "branch:read";
    public static final String BRANCH_MANAGE = "branch:manage";

    // Branch-product (catalog.store_products) management introduced in
    // Checkpoint 3. read guards the protected management listing/detail;
    // manage guards add-to-branch and configuration mutations. Seeded in V017.
    public static final String BRANCH_PRODUCT_READ = "branch-product:read";
    public static final String BRANCH_PRODUCT_MANAGE = "branch-product:manage";

    // Production station discovery (Checkpoint 4). read guards the protected
    // active-station listing; manage additionally reveals inactive stations
    // (administrative use case). Seeded in V018.
    public static final String PRODUCTION_STATION_READ = "production:station-read";
    public static final String PRODUCTION_STATION_MANAGE = "production:station-manage";

    // Currency conversion (Checkpoint 5). preview guards the read-only
    // conversion preview used by POS/dashboard before a cash tender; the
    // settlement itself is recomputed authoritatively at payment time (5C).
    // rate-read / rate-manage guard the operational rate table (Checkpoint 10).
    // Seeded in V020.
    public static final String CURRENCY_CONVERSION_PREVIEW = "currency-conversion:preview";
    public static final String CURRENCY_RATE_READ = "currency-rate:read";
    public static final String CURRENCY_RATE_MANAGE = "currency-rate:manage";

    // Staff administration (Master Prompt Phase 2). Guards the protected
    // /api/v1/management/staff endpoints. Seeded in V023 and granted only to the
    // global administrative roles (SUPER_ADMIN, ADMIN).
    public static final String STAFF_READ = "staff:read";
    public static final String STAFF_CREATE = "staff:create";
    public static final String STAFF_UPDATE = "staff:update";
    public static final String STAFF_STATUS_MANAGE = "staff:status-manage";
    public static final String STAFF_ROLE_ASSIGN = "staff:role-assign";

    // Roles & Permissions administration (Master Prompt Phase 3). Guards the
    // protected /api/v1/management/roles and /api/v1/management/permissions
    // endpoints. Seeded in V024 and granted only to the global administrative
    // roles (SUPER_ADMIN, ADMIN); SUPER_ADMIN's own baseline is immutable so
    // role:permission-manage can never be lost org-wide.
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_PERMISSION_MANAGE = "role:permission-manage";
    public static final String PERMISSION_READ = "permission:read";

    // SUPER_ADMIN RBAC hardening (V052). The five codes below are granted to
    // SUPER_ADMIN ONLY — the standing V013..V048 "SUPER_ADMIN AND ADMIN"
    // cross-join was deliberately narrowed for them (see the V052 header).
    // role:privileged-assign gates every GLOBAL-scope authority decision:
    // assigning/revoking GLOBAL roles, editing GLOBAL roles' permission sets,
    // and granting IAM capabilities (staff:/role:/permission:/user:) to any
    // role. With ADMIN ≡ SUPER_ADMIN in the seed data (63 = 63), this is the
    // single lever that re-establishes privilege separation (audit finding F2).
    public static final String ROLE_CREATE = "role:create";
    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_DELETE = "role:delete";
    public static final String ROLE_ASSIGNMENT_READ = "role:assignment-read";
    public static final String ROLE_PRIVILEGED_ASSIGN = "role:privileged-assign";

    // Orders Administration read (Master Prompt Phase 4). A dedicated management
    // scope, kept separate from operational order:read (which is branch-operational
    // and held by cashiers), per the spec's "do not reuse order:read" guidance.
    // Seeded in V025; granted to global admins and store managers only.
    public static final String ORDER_ADMIN_READ = "order:admin-read";

    // Promotions administration (Master Prompt Phase 5). Separate from the
    // customer-facing validation endpoint, which stays unauthenticated-per-code
    // as today. Seeded in V026; manage is admin-only, read also granted to
    // store managers.
    public static final String PROMOTION_READ = "promotion:read";
    public static final String PROMOTION_MANAGE = "promotion:manage";

    // BI report reads (Master Prompt §10.3). Seeded in V027; sales-read is also
    // granted to store managers (server-side store scoping still applies),
    // inventory-cogs-read stays admin-only.
    public static final String REPORT_SALES_READ = "report:sales-read";
    public static final String REPORT_INVENTORY_COGS_READ = "report:inventory-cogs-read";

    // User Directory read (Full-stack wiring, "see users in the UI"). Seeded in
    // V028; SUPER_ADMIN/ADMIN only — deliberately distinct from staff:read
    // (branch-scoped staff roster) because the directory spans ALL users
    // including customers and carries their personal data.
    public static final String USER_ADMIN_READ = "user:admin-read";

    // Orders Administration commands (cancel/complete). Seeded in V029; granted
    // to global admins and store managers — the service still re-checks the
    // order's store against the caller's scope before any mutation.
    public static final String ORDER_ADMIN_WRITE = "order:admin-write";

    // Object-storage file API (/api/v1/files/**). Seeded in V031. upload guards
    // multipart writes and signed-URL reads (catalog images / documents); delete
    // is the more sensitive mutation and stays with the global administrative
    // roles. The backend fixes the bucket and the object-key prefix per category,
    // so holding file:upload grants access only to the sandboxed prefixes, never
    // to arbitrary buckets or keys.
    public static final String FILE_UPLOAD = "file:upload";
    public static final String FILE_DELETE = "file:delete";

    // Catalog master-data management (product/category create + update, image
    // assignment). Distinct from file:upload (a storage capability) and from
    // branch-product:manage (per-store pricing/availability): these gate the
    // write path on the PRODUCT / CATEGORY entities themselves. Seeded in V032;
    // granted to SUPER_ADMIN, ADMIN and STORE_MANAGER.
    public static final String PRODUCT_MANAGE = "product:manage";
    public static final String CATEGORY_MANAGE = "category:manage";

    // Inventory purchasing (M1). Supplier master CRUD, purchase-order lifecycle
    // (create/submit/cancel + reads) and the receiving stock-in. Seeded in V034;
    // granted to SUPER_ADMIN, ADMIN and STORE_MANAGER. receive is separated from
    // purchase:manage so a store can gate the physically-stocking act on its own.
    public static final String SUPPLIER_MANAGE = "supplier:manage";
    public static final String PURCHASE_MANAGE = "purchase:manage";
    public static final String INVENTORY_RECEIVE = "inventory:receive";

    // Inventory management (M2). Inter-branch transfers, converting low-stock
    // suggestions into a draft purchase order, and the ingredient / unit-of-
    // measure master data editor (closing the Dashboard's documented API gap).
    // Seeded in V037; granted to SUPER_ADMIN, ADMIN and STORE_MANAGER.
    public static final String INVENTORY_TRANSFER = "inventory:transfer";
    public static final String INVENTORY_REORDER = "inventory:reorder";
    public static final String INGREDIENT_MANAGE = "ingredient:manage";
    public static final String UOM_MANAGE = "uom:manage";

    // Variant + option-inventory-effect management (Enterprise upgrade Slice 1).
    // variant:manage         guards POST/PATCH/DELETE on
    //                        /api/v1/management/catalog/products/{id}/variants
    //                        and reordering / set-default actions.
    // option-effect:manage   guards the inventory-effect upsert/clear on
    //                        /api/v1/management/catalog/customization-options/{id}/inventory-effect.
    // Seeded in V040; granted to SUPER_ADMIN, ADMIN and STORE_MANAGER to mirror
    // the existing product:manage / category:manage / ingredient:manage audience.
    public static final String VARIANT_MANAGE = "variant:manage";
    public static final String OPTION_EFFECT_MANAGE = "option-effect:manage";

    // Recipe master + lifecycle management (Enterprise upgrade Slice 2A).
    // recipe:view     guards the read/preview/resolved-recipe surface.
    // recipe:manage   guards draft create/update and line replacement.
    // recipe:activate guards DRAFT -> ACTIVE activation (and supersede/archive).
    // recipe:assign   guards assigning / clearing a variant recipe-version override.
    // Seeded in V043; granted to SUPER_ADMIN, ADMIN and STORE_MANAGER to mirror
    // the existing product:manage / variant:manage audience.
    public static final String RECIPE_VIEW = "recipe:view";
    public static final String RECIPE_MANAGE = "recipe:manage";
    public static final String RECIPE_ACTIVATE = "recipe:activate";
    public static final String RECIPE_ASSIGN = "recipe:assign";

    // Reservation recovery & reconciliation (Enterprise upgrade Slice 2E).
    // reservation:release     guards POST /api/v1/management/inventory/reservations/{id}/release
    //                         — an administrative give-back of one ACTIVE hold.
    // reconciliation:view     guards GET reads of reconciliation reports/findings.
    // reconciliation:run      guards POST .../reconciliation/run (dry-run included).
    // reconciliation:repair   guards POST .../reconciliation/{runId}/repair (stock-moving).
    // Seeded in V047; SUPER_ADMIN/ADMIN hold all four, STORE_MANAGER holds them for
    // its own store (service-layer store scoping still applies).
    public static final String RESERVATION_RELEASE = "reservation:release";
    public static final String RECONCILIATION_VIEW = "reconciliation:view";
    public static final String RECONCILIATION_RUN = "reconciliation:run";
    public static final String RECONCILIATION_REPAIR = "reconciliation:repair";

    // Channel publication + scheduling (Enterprise upgrade Slice 3B).
    // product-channel:read   guards GET reads of per-store/per-product channel
    //                        publication policy, schedules and the channel catalog
    //                        preview.
    // product-channel:manage guards channel upsert/delete, schedule replacement and
    //                        the manual sold-out control. Seeded in V048; granted to
    //                        SUPER_ADMIN/ADMIN (global) and STORE_MANAGER (store
    //                        scoped). Distinct from branch-product:manage (store
    //                        product configuration) and product:manage (master data).
    public static final String PRODUCT_CHANNEL_READ = "product-channel:read";
    public static final String PRODUCT_CHANNEL_MANAGE = "product-channel:manage";

    // Production Orders core backend (Enterprise upgrade Slice 5A). Seven codes
    // gating the planned-manufacturing aggregate that lives alongside the
    // existing production:queue-* sale-ticket workflow. Seeded in V051;
    // granted to SUPER_ADMIN/ADMIN globally and STORE_MANAGER for authorized
    // stores. A dedicated PRODUCTION_OPERATOR role is deliberately deferred
    // per Decision 3. Enforcement still layers Store scope on top of the
    // permission check via StoreAuthorizationService.requireStore.
    public static final String PRODUCTION_ORDER_VIEW = "production-order:view";
    public static final String PRODUCTION_ORDER_CREATE = "production-order:create";
    public static final String PRODUCTION_ORDER_PLAN = "production-order:plan";
    public static final String PRODUCTION_ORDER_START = "production-order:start";
    public static final String PRODUCTION_ORDER_COMPLETE = "production-order:complete";
    public static final String PRODUCTION_ORDER_CANCEL = "production-order:cancel";
    public static final String PRODUCTION_ORDER_CLOSE = "production-order:close";
}

