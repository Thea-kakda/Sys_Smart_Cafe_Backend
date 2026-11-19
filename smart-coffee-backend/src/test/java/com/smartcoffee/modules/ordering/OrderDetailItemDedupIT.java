package com.smartcoffee.modules.ordering;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.ordering.api.ManagementOrderDetail;
import com.smartcoffee.modules.ordering.api.OrderItemResponse;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderAdminQueryService;
import com.smartcoffee.modules.ordering.application.OrderQueryService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import com.smartcoffee.testsupport.SqlCounter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression for the Phase B3.1 order-detail defect: an Order with a single
 * Item that carries several Customizations was returned with the Item repeated
 * once per Customization because the read graph join-fetched two collections.
 * The invariants here are identity-based: one Item, one instance, three
 * Customizations, deterministic order.
 */
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.smartcoffee.testsupport.SqlCounter" })
class OrderDetailItemDedupIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000002";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderQueryService queries;
    @Autowired
    OrderAdminQueryService adminQueries;
    @Autowired
    CurrentActorProvider actors;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Dedup User", email, email, "en-US");
        return userId;
    }

    private UUID seedGlobalAdmin() {
        UUID userId = seedUser("dedup-admin-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, null, now())", UUID.randomUUID(), userId, SUPER_ADMIN_ROLE_ID);
        return userId;
    }

    private UUID seedStore() {
        UUID storeId = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?::uuid, ?, 'Dedup Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                storeId.toString(), "DDP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return storeId;
    }

    private UUID seedCustomer(UUID userId) {
        UUID customerId = UUID.randomUUID();
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, created_at, updated_at, version) "
                + "values (?::uuid, ?::uuid, now(), now(), 0)", customerId.toString(), userId.toString());
        return customerId;
    }

    private UUID insertOrder(String number, UUID storeId, UUID customerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                + "fulfillment_type, status, payment_status, currency_code, subtotal, total_amount, total_quantity, "
                + "ordered_at, updated_at) values (?::uuid, ?, ?::uuid, ?::uuid, 'CUSTOMER_WEB', 'PICKUP', "
                + "'CONFIRMED', 'PAID', 'USD', 7.00, 7.00, 1, now(), now())",
                id.toString(), number, storeId.toString(), customerId.toString());
        return id;
    }

    private UUID insertPromotedOrder(String number, UUID storeId, UUID customerId, UUID promotionId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                + "fulfillment_type, status, payment_status, currency_code, subtotal, discount_amount, total_amount, "
                + "total_quantity, ordered_at, updated_at, promotion_id, promotion_code_snapshot, "
                + "promotion_name_snapshot, promotion_type_snapshot, promotion_value_snapshot, "
                + "promotion_eligible_subtotal, promotion_discount_amount, promotion_version) "
                + "values (?::uuid, ?, ?::uuid, ?::uuid, 'CUSTOMER_WEB', 'PICKUP', 'CONFIRMED', 'PAID', 'USD', "
                + "7.00, 1.00, 6.00, 1, now(), now(), ?::uuid, 'SAVE10', 'Welcome', 'PERCENTAGE', 10.00, 7.00, "
                + "1.00, 3)",
                id.toString(), number, storeId.toString(), customerId.toString(), promotionId.toString());
        return id;
    }

    private UUID seedPromotion() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into promotion.promotions (promotion_id, promotion_code, promotion_name, discount_type, "
                + "discount_value, minimum_order_amount, maximum_discount_amount, currency_code, starts_at, ends_at, "
                + "status, usage_limit, per_customer_limit, usage_count, stackable, automatic, priority, version) "
                + "values (?,?, 'Welcome', 'PERCENTAGE', 10.00, 0, null, 'USD', now() - interval '1 hour', "
                + "now() + interval '1 day', 'ACTIVE', 100, 5, 0, false, false, 0, 0)",
                id, "SAVE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        return id;
    }

    private UUID insertItem(UUID orderId, int index) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.order_items (order_item_id, order_id, product_code_snapshot, "
                + "product_name_snapshot, quantity, unit_base_price, unit_customization_price, line_discount_amount, "
                + "line_tax_amount, line_total, created_at) values (?::uuid, ?::uuid, ?, ?, 1, 3.50, 0.00, "
                + "0.00, 0.00, 3.50, ?)",
                id.toString(), orderId.toString(),
                "P-" + index, "Latte " + index,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(index)));
        return id;
    }

    private UUID insertVariantedItem(UUID orderId, int index) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.order_items (order_item_id, order_id, variant_code_snapshot, "
                + "variant_name_snapshot, variant_price_snapshot, product_code_snapshot, product_name_snapshot, "
                + "quantity, unit_base_price, unit_customization_price, line_discount_amount, line_tax_amount, "
                + "line_total, created_at) values (?::uuid, ?::uuid, 'LARGE', 'Large', 4.50, ?, ?, 1, "
                + "4.50, 0.00, 0.00, 0.00, 4.50, ?)",
                id.toString(), orderId.toString(),
                "P-" + index, "Latte " + index,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(index)));
        return id;
    }

    private UUID insertCustomization(UUID itemId, int index, String group, String option) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.order_item_customizations (order_item_customization_id, order_item_id, "
                + "group_name_snapshot, option_name_snapshot, additional_price, quantity, created_at) "
                + "values (?::uuid, ?::uuid, ?, ?, 0.00, 1, ?)",
                id.toString(), itemId.toString(), group, option,
                java.sql.Timestamp.from(java.time.Instant.now().plusMillis(index)));
        return id;
    }

    private record Fixture(UUID user, UUID customer, UUID store, UUID order) {
    }

    private Fixture newFixture() {
        UUID user = seedUser("dedup-" + UUID.randomUUID() + "@smart.coffee");
        UUID customer = seedCustomer(user);
        UUID store = seedStore();
        UUID order = insertOrder("D-" + UUID.randomUUID().toString().substring(0, 8), store, customer);
        authenticate(user);
        return new Fixture(user, customer, store, order);
    }

    // ------------------------------------------------------- repository reads

    @Test
    void singleItemWithThreeCustomizationsAppearsExactlyOnce() {
        UUID user = seedUser("dedup-" + UUID.randomUUID() + "@smart.coffee");
        UUID customer = seedCustomer(user);
        UUID store = seedStore();
        UUID orderId = insertOrder("D-1", store, customer);
        UUID itemId = insertItem(orderId, 1);
        UUID c1 = insertCustomization(itemId, 1, "Milk", "Fresh");
        UUID c2 = insertCustomization(itemId, 2, "Sugar", "50%");
        UUID c3 = insertCustomization(itemId, 3, "Extras", "Shot");

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from ordering.order_items where order_id = ?::uuid", Integer.class,
                orderId.toString()));
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from ordering.order_item_customizations where order_item_id = ?::uuid",
                Integer.class, itemId.toString()));

        authenticate(user);
        OrderResponse order = queries.get(customer, orderId);

        List<OrderItemResponse> items = order.items();
        assertEquals(1, items.size(), "business line must appear exactly once");
        assertEquals(itemId, items.get(0).orderItemId());
        assertEquals(3, items.get(0).customizations().size(), "every customization exactly once");
        assertEquals(List.of("Milk: Fresh", "Sugar: 50%", "Extras: Shot"), items.get(0).customizations());
        assertNotEquals(c1, c2);
        assertNotEquals(c2, c3);
        assertEquals(0, new BigDecimal("7.00").compareTo(order.totalAmount()));
    }

    @Test
    void itemWithoutCustomizationsAppearsOnceWithEmptyList() {
        Fixture f = newFixture();
        UUID itemId = insertItem(f.order(), 1);

        OrderResponse order = queries.get(f.customer(), f.order());

        assertEquals(1, order.items().size());
        assertEquals(itemId, order.items().get(0).orderItemId());
        assertTrue(order.items().get(0).customizations().isEmpty());
    }

    @Test
    void singleCustomizationAppearsExactlyOnce() {
        Fixture f = newFixture();
        UUID itemId = insertItem(f.order(), 1);
        insertCustomization(itemId, 1, "Milk", "Oat");

        OrderResponse order = queries.get(f.customer(), f.order());

        assertEquals(1, order.items().size());
        assertEquals(List.of("Milk: Oat"), order.items().get(0).customizations());
    }

    @Test
    void severalItemsKeepTheirOwnCustomizations() {
        Fixture f = newFixture();
        UUID plain = insertItem(f.order(), 1);
        UUID one = insertItem(f.order(), 2);
        UUID two = insertItem(f.order(), 3);
        insertCustomization(one, 2, "Milk", "Fresh");
        insertCustomization(two, 3, "Sugar", "100%");
        insertCustomization(two, 4, "Extras", "Shot");

        OrderResponse order = queries.get(f.customer(), f.order());

        assertEquals(3, order.items().size(), "every business line exactly once");
        assertEquals(List.of(plain, one, two), order.items().stream().map(OrderItemResponse::orderItemId).toList(),
                "lines stay in deterministic creation order");
        assertTrue(order.items().get(0).customizations().isEmpty());
        assertEquals(List.of("Milk: Fresh"), order.items().get(1).customizations());
        assertEquals(List.of("Sugar: 100%", "Extras: Shot"), order.items().get(2).customizations(),
                "no cross-line leakage");
    }

    @Test
    void identicalSnapshotNamesStayDistinctByIdentity() {
        UUID user = seedUser("dedup-" + UUID.randomUUID() + "@smart.coffee");
        UUID customer = seedCustomer(user);
        UUID store = seedStore();
        UUID orderId = insertOrder("D-DUP", store, customer);
        UUID first = insertItem(orderId, 1);
        UUID second = insertItem(orderId, 1); // same product code/name snapshot
        UUID c1 = insertCustomization(first, 1, "Milk", "Fresh");
        UUID c2 = insertCustomization(second, 2, "Milk", "Fresh");

        authenticate(user);
        OrderResponse order = queries.get(customer, orderId);

        assertEquals(2, order.items().size(), "value-equal lines must not be collapsed");
        assertEquals(List.of(first, second), order.items().stream().map(OrderItemResponse::orderItemId).toList());
        assertEquals(List.of("Milk: Fresh"), order.items().get(0).customizations());
        assertEquals(List.of("Milk: Fresh"), order.items().get(1).customizations());
        assertNotEquals(c1, c2);
    }

    @Test
    void historyPageReturnsSameShapeAsDetail() {
        Fixture f = newFixture();
        UUID itemId = insertItem(f.order(), 1);
        insertCustomization(itemId, 1, "Milk", "Fresh");
        insertCustomization(itemId, 2, "Sugar", "50%");
        insertCustomization(itemId, 3, "Extras", "Shot");

        var page = queries.list(f.customer(), 0, 20);

        var historyOrder = page.getContent().stream()
                .filter(o -> o.orderId().equals(f.order()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, historyOrder.items().size(), "history must not duplicate the line");
        assertEquals(itemId, historyOrder.items().get(0).orderItemId());
        assertEquals(3, historyOrder.items().get(0).customizations().size());
    }

    // ------------------------------------------------------- management read

    @Test
    void managementDetailMapsEachLineOnce() {
        UUID adminUser = seedGlobalAdmin();
        UUID store = seedStore();
        UUID customer = seedCustomer(seedUser("dedup-" + UUID.randomUUID() + "@smart.coffee"));
        UUID orderId = insertOrder("D-MGT", store, customer);
        UUID itemId = insertItem(orderId, 1);
        insertCustomization(itemId, 1, "Milk", "Fresh");
        insertCustomization(itemId, 2, "Sugar", "50%");
        insertCustomization(itemId, 3, "Extras", "Shot");

        authenticate(adminUser);
        CurrentActor admin = actors.require();
        ManagementOrderDetail detail = adminQueries.detail(admin, orderId);

        assertEquals(1, detail.items().size());
        assertEquals(itemId, detail.items().get(0).orderItemId());
        assertEquals(List.of("Milk: Fresh", "Sugar: 50%", "Extras: Shot"),
                detail.items().get(0).customizations());
    }

    // ------------------------------------------------------- query behavior

    @Test
    void promotionAndVariantSnapshotsSurviveTheRead() {
        UUID user = seedUser("dedup-" + UUID.randomUUID() + "@smart.coffee");
        UUID customer = seedCustomer(user);
        UUID store = seedStore();
        UUID promotionId = seedPromotion();
        UUID orderId = insertPromotedOrder("D-PRM", store, customer, promotionId);
        UUID itemId = insertVariantedItem(orderId, 1);
        insertCustomization(itemId, 1, "Milk", "Fresh");

        authenticate(user);
        OrderResponse order = queries.get(customer, orderId);

        assertEquals(promotionId, order.promotionId());
        assertEquals("SAVE10", order.promotionCode());
        assertEquals(0, new BigDecimal("1.00").compareTo(order.promotionDiscountAmount()));
        assertEquals(1, order.items().size());
        // The read path must not mutate the variant identity snapshot.
        assertEquals("LARGE", jdbc.queryForObject(
                "select variant_code_snapshot from ordering.order_items where order_item_id = ?::uuid",
                String.class, itemId.toString()));
        assertEquals(0, new BigDecimal("4.50").compareTo(jdbc.queryForObject(
                "select variant_price_snapshot from ordering.order_items where order_item_id = ?::uuid",
                BigDecimal.class, itemId.toString())));
    }

    @Test
    void detailLoadsOrderWithItemsAndCustomizationsInTwoQueries() {
        Fixture f = newFixture();
        UUID itemId = insertItem(f.order(), 1);
        insertCustomization(itemId, 1, "Milk", "Fresh");
        insertCustomization(itemId, 2, "Sugar", "50%");
        insertCustomization(itemId, 3, "Extras", "Shot");

        SqlCounter.reset();
        queries.get(f.customer(), f.order());

        assertEquals(2, SqlCounter.count(),
                "one header+items query and one batch customization query; no per-item N+1");
    }
}
