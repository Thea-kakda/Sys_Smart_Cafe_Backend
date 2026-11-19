package com.smartcoffee.modules.ordering.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.ordering.api.ManagementOrderDetail;
import com.smartcoffee.modules.ordering.api.ManagementOrderSummary;
import com.smartcoffee.modules.ordering.api.ManagementPromotionSnapshot;
import com.smartcoffee.modules.ordering.api.OrderItemResponse;
import com.smartcoffee.modules.ordering.domain.Order;
import com.smartcoffee.modules.ordering.domain.OrderItem;
import com.smartcoffee.modules.ordering.domain.OrderItemCustomization;
import com.smartcoffee.modules.ordering.persistence.OrderAdminQueryRepository;
import com.smartcoffee.modules.ordering.persistence.OrderItemCustomizationRepository;
import com.smartcoffee.modules.ordering.persistence.OrderAdminQueryRepository.OrderSummaryProjection;
import com.smartcoffee.modules.ordering.persistence.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders Administration read service (Master Prompt §8). Enforces store scoping on
 * every query — store-scoped users can only search their assigned stores, global
 * administrators may scope to one store or all — and always paginates with a bounded
 * page size and a whitelisted sort, so no unbounded scan is possible. The summary
 * projection excludes customer personal data; the detail additionally checks the
 * caller may access the specific order's store before returning line items.
 */
@Service
public class OrderAdminQueryService {

    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final Instant EPOCH = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");
    private static final Set<String> SORTABLE = Set.of("orderedAt", "totalAmount", "status", "orderNumber");

    private final OrderAdminQueryRepository search;
    private final OrderRepository orders;
    private final OrderItemCustomizationRepository customizations;
    private final StoreAuthorizationService storeAuth;

    public OrderAdminQueryService(OrderAdminQueryRepository search, OrderRepository orders,
            OrderItemCustomizationRepository customizations, StoreAuthorizationService storeAuth) {
        this.search = search;
        this.orders = orders;
        this.customizations = customizations;
        this.storeAuth = storeAuth;
    }

    @Transactional(readOnly = true)
    public Page<ManagementOrderSummary> search(CurrentActor actor, UUID storeId, String orderNumber, UUID customerId,
            String status, String paymentStatus, String source, String fulfillment, Instant orderedFrom,
            Instant orderedTo, String sort, int page, int size) {

        // --- store scoping: every caller is constrained to a concrete scope ---
        boolean scopeAll;
        List<UUID> scopeStores;
        if (storeId != null) {
            storeAuth.requireStore(actor, storeId); // explicit store must be authorized (else 403)
            scopeAll = false;
            scopeStores = List.of(storeId);
        } else if (actor.globalAccess()) {
            scopeAll = true;
            scopeStores = List.of(NIL_UUID); // never empty; short-circuited by storeScopeAll
        } else {
            Set<UUID> mine = actor.storeIds();
            if (mine.isEmpty()) {
                return Page.empty(); // store user with no assignment sees nothing
            }
            scopeAll = false;
            scopeStores = List.copyOf(mine);
        }

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String number = (orderNumber == null) ? "" : orderNumber.trim();
        boolean numberFilter = !number.isEmpty();
        boolean customerFilter = customerId != null;
        String customerIdText = (customerId != null) ? customerId.toString() : NIL_UUID.toString();
        boolean statusFilter = notBlank(status);
        boolean paymentFilter = notBlank(paymentStatus);
        boolean sourceFilter = notBlank(source);
        boolean fulfillmentFilter = notBlank(fulfillment);
        Instant from = orderedFrom != null ? orderedFrom : EPOCH;
        Instant to = orderedTo != null ? orderedTo : FAR_FUTURE;

        return search.search(scopeAll, scopeStores, numberFilter, number,
                customerFilter, customerIdText,
                statusFilter, statusFilter ? status.trim() : "",
                paymentFilter, paymentFilter ? paymentStatus.trim() : "",
                sourceFilter, sourceFilter ? source.trim() : "",
                fulfillmentFilter, fulfillmentFilter ? fulfillment.trim() : "",
                from, to, PageRequest.of(safePage, safeSize, buildSort(sort)))
                .map(OrderAdminQueryService::toSummary);
    }

    @Transactional(readOnly = true)
    public ManagementOrderDetail detail(CurrentActor actor, UUID orderId) {
        Order o = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
        storeAuth.requireStore(actor, o.storeId()); // management authorization is store-based
        List<UUID> itemIds = o.items().stream().map(OrderItem::id).toList();
        var byItem = itemIds.isEmpty() ? java.util.Map.<UUID, List<OrderItemCustomization>>of()
                : customizations.findAllForItems(itemIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(OrderItemCustomization::orderItemId,
                                java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        return new ManagementOrderDetail(toSummary(o), items(o, byItem), promotion(o));
    }

    // ---------------------------------------------------------------- helpers

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private Sort buildSort(String sort) {
        String field = (sort == null || !SORTABLE.contains(sort)) ? "orderedAt" : sort;
        // Stable pagination: always break ties on the immutable order id.
        return Sort.by(Sort.Order.desc(field), Sort.Order.desc("orderId"));
    }

    private static ManagementOrderSummary toSummary(OrderSummaryProjection p) {
        return new ManagementOrderSummary(p.getOrderId(), p.getOrderNumber(), p.getStoreId(), p.getCustomerId(),
                p.getSource(), p.getFulfillmentType(), p.getStatus(), p.getPaymentStatus(), p.getCurrencyCode(),
                p.getSubtotal(), p.getDiscountAmount(), p.getTaxAmount(), p.getDeliveryFee(), p.getRoundingAmount(),
                p.getTotalAmount(), p.getTotalQuantity(), p.getOrderedAt(), p.getUpdatedAt(), p.getVersion());
    }

    private static ManagementOrderSummary toSummary(Order o) {
        return new ManagementOrderSummary(o.id(), o.number(), o.storeId(), o.customerId(), o.source().name(),
                o.fulfillmentType().name(), o.status().name(), o.paymentStatus().name(), o.currency(), o.subtotal(),
                o.discount(), o.tax(), o.deliveryFee(), o.rounding(), o.total(), o.totalQuantity(), o.orderedAt(),
                o.updatedAt(), o.version());
    }

    private static List<OrderItemResponse> items(Order o,
            java.util.Map<UUID, List<OrderItemCustomization>> byItem) {
        return o.items().stream()
                .map(i -> new OrderItemResponse(i.id(), i.productId(), i.productName(), i.quantity(), i.lineSubtotal(),
                        i.lineDiscount(), i.lineTaxableAmount(), i.lineTax(), i.lineTotal(),
                        byItem.getOrDefault(i.id(), List.of()).stream()
                                .map(x -> x.groupName() + ": " + x.optionName()).toList()))
                .toList();
    }

    private static ManagementPromotionSnapshot promotion(Order o) {
        var p = o.promotion();
        return p == null ? null
                : new ManagementPromotionSnapshot(p.promotionId(), p.code(), p.name(), p.type(), p.value(),
                        p.eligibleSubtotal(), p.discountAmount(), p.version());
    }
}
