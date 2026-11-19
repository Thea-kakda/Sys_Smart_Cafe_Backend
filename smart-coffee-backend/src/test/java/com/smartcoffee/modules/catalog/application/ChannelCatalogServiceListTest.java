package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartcoffee.modules.availability.AvailabilityService;
import com.smartcoffee.modules.availability.ManualAvailabilityState;
import com.smartcoffee.modules.availability.ProductAvailabilityResult;
import com.smartcoffee.modules.catalog.api.ProductSummaryResponse;
import com.smartcoffee.modules.catalog.domain.Channel;
import com.smartcoffee.modules.catalog.persistence.ChannelCatalogProjection;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import com.smartcoffee.modules.catalog.persistence.ProductChannelRepository;
import com.smartcoffee.modules.catalog.persistence.ProductChannelScheduleRepository;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.catalog.persistence.StoreProductRepository;
import com.smartcoffee.modules.organization.domain.Store;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for the read-model projector {@link ChannelCatalogService#list} (Slice
 * 3B, spec §4/§6/§13). They pin the three guarantees that make the catalog both correct
 * and performant without touching the database: (1) the Slice 3A batch availability is
 * invoked EXACTLY ONCE per page (no per-product loop), (2) the final verdict merges the
 * channel gate with the availability verdict and never re-derives stock here, and (3)
 * the legacy-visibility inheritance flag is passed to the query per channel so hidden
 * products are filtered before pagination. Availability is mocked, so no inventory
 * logic is duplicated or re-executed.
 */
class ChannelCatalogServiceListTest {

    private static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant NOW = Instant.parse("2026-01-05T07:00:00Z");

    private StoreProductRepository storeProducts;
    private ProductChannelRepository productChannels;
    private ProductChannelScheduleRepository schedules;
    private StoreRepository stores;
    private AvailabilityService availability;
    private CatalogImageUrlResolver images;
    private ChannelCatalogService service;

    @BeforeEach
    void setUp() {
        storeProducts = mock(StoreProductRepository.class);
        productChannels = mock(ProductChannelRepository.class);
        schedules = mock(ProductChannelScheduleRepository.class);
        var variants = mock(ProductVariantRepository.class);
        var products = mock(ProductRepository.class);
        var categories = mock(ProductCategoryRepository.class);
        stores = mock(StoreRepository.class);
        availability = mock(AvailabilityService.class);
        var customizations = mock(ProductCustomizationQueryService.class);
        images = mock(CatalogImageUrlResolver.class);
        service = new ChannelCatalogService(storeProducts, productChannels, schedules, variants, products, categories,
                stores, availability, customizations, images, Clock.fixed(NOW, ZoneOffset.UTC));

        Store store = mock(Store.class);
        when(store.timezone()).thenReturn("UTC");
        when(stores.findByIdAndActiveTrue(STORE)).thenReturn(java.util.Optional.of(store));
        when(images.resolve(any())).thenReturn("https://cdn/img");
    }

    private ChannelCatalogProjection proj(UUID channelId, boolean channelOrderable) {
        ChannelCatalogProjection p = mock(ChannelCatalogProjection.class);
        when(p.getProductId()).thenReturn(PRODUCT);
        when(p.getProductCode()).thenReturn("COFFEE-1");
        when(p.getCategoryId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000000c"));
        when(p.getCategoryName()).thenReturn("Coffee");
        when(p.getProductName()).thenReturn("Latte");
        when(p.getDescription()).thenReturn("desc");
        when(p.getImageObjectKey()).thenReturn("obj/key");
        when(p.getPrice()).thenReturn(new BigDecimal("39000"));
        when(p.getCurrencyCode()).thenReturn("VND");
        when(p.getEffectiveAvailable()).thenReturn(true);
        when(p.getPreparationMinutes()).thenReturn(3);
        when(p.getCustomizable()).thenReturn(true);
        when(p.getChannelId()).thenReturn(channelId);
        when(p.getChannelVisible()).thenReturn(true);
        when(p.getChannelOrderable()).thenReturn(channelOrderable);
        when(p.getChannelDisplayName()).thenReturn(null);
        when(p.getChannelDisplayDescription()).thenReturn(null);
        when(p.getChannelDisplayOrder()).thenReturn(0);
        return p;
    }

    private ProductAvailabilityResult avail(boolean orderable, boolean inStock, List<String> reasons) {
        return new ProductAvailabilityResult(PRODUCT, STORE, true, true, orderable, inStock, ManualAvailabilityState.AUTO,
                10, reasons, List.of(), List.of(), NOW, "fp");
    }

    private void stubQuery(ChannelCatalogProjection projection) {
        Page<ChannelCatalogProjection> page = new PageImpl<>(List.of(projection));
        when(storeProducts.channelCatalog(eq(STORE), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(),
                any(PageRequest.class))).thenReturn(page);
    }

    @Test
    void batchAvailabilityIsInvokedExactlyOnceForThePage() {
        stubQuery(proj(null, true));
        when(availability.availabilityForProducts(eq(STORE), eq(List.of(PRODUCT)), eq(1)))
                .thenReturn(List.of(avail(true, true, List.of())));

        Page<ProductSummaryResponse> result = service.list(STORE, Channel.CUSTOMER_WEB, null, null, false, 0, 20);

        verify(availability, times(1)).availabilityForProducts(eq(STORE), any(), eq(1));
        verify(availability, never()).availabilityForProduct(any(), any(), anyInt());
        assertEquals(1, result.getContent().size());
    }

    @Test
    void channelGateAndAvailabilityBothClearMeansOrderable() {
        stubQuery(proj(null, true));
        when(availability.availabilityForProducts(any(), any(), anyInt()))
                .thenReturn(List.of(avail(true, true, List.of())));

        ProductSummaryResponse s = service.list(STORE, Channel.CUSTOMER_WEB, null, null, false, 0, 20).getContent()
                .get(0);

        assertTrue(s.visible());
        assertTrue(s.orderable());
        assertTrue(s.inStock());
        assertEquals("AUTO", s.manualState());
    }

    @Test
    void channelNotOrderableBlocksVerdictAndAddsReason() {
        stubQuery(proj(null, false));
        when(availability.availabilityForProducts(any(), any(), anyInt()))
                .thenReturn(List.of(avail(true, true, List.of())));

        ProductSummaryResponse s = service.list(STORE, Channel.CUSTOMER_WEB, null, null, false, 0, 20).getContent()
                .get(0);

        assertTrue(s.visible(), "channel gate off does not hide the product");
        assertFalse(s.orderable());
        assertTrue(s.reasonCodes().contains(CatalogChannelReason.CHANNEL_NOT_ORDERABLE.code()));
    }

    @Test
    void availabilityOutOfStockBlocksOrderableButStaysVisible() {
        stubQuery(proj(null, true));
        when(availability.availabilityForProducts(any(), any(), anyInt()))
                .thenReturn(List.of(avail(false, false, List.of("INSUFFICIENT_INVENTORY"))));

        ProductSummaryResponse s = service.list(STORE, Channel.CUSTOMER_WEB, null, null, false, 0, 20).getContent()
                .get(0);

        assertTrue(s.visible());
        assertFalse(s.orderable());
        assertFalse(s.inStock());
        assertTrue(s.reasonCodes().contains("INSUFFICIENT_INVENTORY"), "3A reasons are merged through");
    }

    @Test
    void reasonCodesAreDeduplicatedDeterministically() {
        stubQuery(proj(null, false));
        when(availability.availabilityForProducts(any(), any(), anyInt()))
                .thenReturn(List.of(avail(false, false, List.of("CHANNEL_NOT_ORDERABLE", "DUP", "DUP"))));

        ProductSummaryResponse s = service.list(STORE, Channel.CUSTOMER_WEB, null, null, false, 0, 20).getContent()
                .get(0);

        long channelReasons = s.reasonCodes().stream().filter(CatalogChannelReason.CHANNEL_NOT_ORDERABLE.code()::equals)
                .count();
        assertEquals(1, channelReasons, "duplicate reason codes collapse to one");
        assertEquals(CatalogChannelReason.CHANNEL_NOT_ORDERABLE.code(), s.reasonCodes().get(0),
                "channel reason is emitted before availability reasons");
    }

    @Test
    void integratedChannelQueryPassesInheritTrue() {
        stubQuery(proj(null, true));
        when(availability.availabilityForProducts(any(), any(), anyInt()))
                .thenReturn(List.of(avail(true, true, List.of())));

        service.list(STORE, Channel.POS, "  latte  ", null, true, 0, 20);

        verify(storeProducts).channelCatalog(eq(STORE), eq("POS"), eq(true), eq(true), eq("latte"), eq(null), eq(true),
                any(PageRequest.class));
    }

    @Test
    void deferredChannelQueryPassesInheritFalse() {
        stubQuery(proj(null, true));
        when(availability.availabilityForProducts(any(), any(), anyInt()))
                .thenReturn(List.of(avail(true, true, List.of())));

        service.list(STORE, Channel.DELIVERY, null, null, false, 0, 20);

        verify(storeProducts).channelCatalog(eq(STORE), eq("DELIVERY"), eq(false), eq(false), eq(""), any(),
                anyBoolean(), any(PageRequest.class));
    }

    @Test
    void explicitChannelRowTriggersBoundedScheduleLoadNotPerProduct() {
        UUID channelId = UUID.fromString("00000000-0000-0000-0000-00000000000d");
        stubQuery(proj(channelId, true));
        when(availability.availabilityForProducts(any(), any(), anyInt()))
                .thenReturn(List.of(avail(true, true, List.of())));
        when(productChannels.findAllById(List.of(channelId))).thenReturn(List.of());
        when(schedules.findByProductChannelIdIn(List.of(channelId))).thenReturn(List.of());

        service.list(STORE, Channel.CUSTOMER_WEB, null, null, false, 0, 20);

        verify(schedules, times(1)).findByProductChannelIdIn(List.of(channelId));
        verify(schedules, never()).findByProductChannelIdOrderByDayOfWeekAscStartLocalTimeAsc(any());
    }
}
