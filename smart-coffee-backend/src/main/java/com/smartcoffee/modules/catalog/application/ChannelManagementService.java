package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.catalog.api.ChannelConfigurationRequest;
import com.smartcoffee.modules.catalog.api.ChannelPolicyResponse;
import com.smartcoffee.modules.catalog.api.ManualSoldOutRequest;
import com.smartcoffee.modules.catalog.api.ProductDetailsResponse;
import com.smartcoffee.modules.catalog.api.ReplaceSchedulesRequest;
import com.smartcoffee.modules.catalog.domain.Channel;
import com.smartcoffee.modules.catalog.domain.ProductChannel;
import com.smartcoffee.modules.catalog.domain.ProductChannelSchedule;
import com.smartcoffee.modules.catalog.domain.StoreProductId;
import com.smartcoffee.modules.catalog.persistence.ProductChannelRepository;
import com.smartcoffee.modules.catalog.persistence.ProductChannelScheduleRepository;
import com.smartcoffee.modules.catalog.persistence.StoreProductRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Channel publication + schedule + manual sold-out management (Slice 3B, spec §18).
 * Every method enforces the {@code product-channel:read}/{@code :manage} permission
 * and the caller's store scope BEFORE mutation, uses optimistic locking on the
 * affected row version, and NEVER mutates inventory. Publication owns only the
 * per-channel policy; the manual sold-out control writes the existing
 * {@code store_products.available}/{@code sold_out_until} fields (spec §6) so the
 * Slice 3A availability engine keeps a single source of truth.
 */
@Service
public class ChannelManagementService {

    private final ProductChannelRepository channels;
    private final ProductChannelScheduleRepository schedules;
    private final StoreProductRepository storeProducts;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService storeAuth;
    private final ChannelCatalogService catalog;
    private final AuditWriter audit;

    public ChannelManagementService(ProductChannelRepository channels, ProductChannelScheduleRepository schedules,
            StoreProductRepository storeProducts, PermissionEvaluator permissions, StoreAuthorizationService storeAuth,
            ChannelCatalogService catalog, AuditWriter audit) {
        this.channels = channels;
        this.schedules = schedules;
        this.storeProducts = storeProducts;
        this.permissions = permissions;
        this.storeAuth = storeAuth;
        this.catalog = catalog;
        this.audit = audit;
    }

    // ------------------------------------------------------------- channel CRUD

    @Transactional(readOnly = true)
    public List<ChannelPolicyResponse> listChannels(CurrentActor actor, UUID storeId, UUID productId) {
        permissions.require(actor, PermissionCodes.PRODUCT_CHANNEL_READ);
        storeAuth.requireStore(actor, storeId);
        List<ChannelPolicyResponse> out = new ArrayList<>();
        for (Channel ch : Channel.values()) {
            var row = channels.findByStoreIdAndProductIdAndChannel(storeId, productId, ch);
            out.add(row.map(pc -> toResponse(pc, false)).orElseGet(() -> inheritedResponse(ch)));
        }
        return out;
    }

    @Transactional
    public ChannelPolicyResponse upsertChannel(CurrentActor actor, UUID storeId, UUID productId, Channel channel,
            ChannelConfigurationRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_CHANNEL_MANAGE);
        storeAuth.requireStore(actor, storeId);
        requireStoreProduct(storeId, productId);
        validateWindow(req.availableFrom(), req.availableUntil());
        int displayOrder = req.displayOrder() == null ? 0 : req.displayOrder();

        var existing = channels.findByStoreIdAndProductIdAndChannel(storeId, productId, channel);
        if (existing.isEmpty()) {
            if (req.expectedVersion() != null && req.expectedVersion() != 0L) {
                throw new ConflictException("PRODUCT_CHANNEL_VERSION_CONFLICT",
                        "No publication exists; expectedVersion must be 0 or absent");
            }
            var created = ProductChannel.create(storeId, productId, channel, req.visible(), req.orderable(),
                    req.displayName(), req.displayDescription(), displayOrder, req.availableFrom(),
                    req.availableUntil(), req.timezone(), actor.userId());
            ChannelPolicyResponse saved = toResponse(channels.save(created), false);
            audit.success(actor.userId(), storeId, "CATALOG_CHANNEL_CREATE", "CATALOG", "PRODUCT_CHANNEL",
                    saved.channelId(), null, saved, "Published " + channel.name());
            return saved;
        }
        var pc = existing.get();
        if (req.expectedVersion() == null || req.expectedVersion() != pc.version()) {
            throw new ConflictException("PRODUCT_CHANNEL_VERSION_CONFLICT",
                    "Stale channel version: expected " + pc.version());
        }
        ChannelPolicyResponse before = toResponse(pc, false);
        pc.reconfigure(req.visible(), req.orderable(), req.displayName(), req.displayDescription(), displayOrder,
                req.availableFrom(), req.availableUntil(), req.timezone(), actor.userId());
        // flush so the @Version increment is reflected in the response/audit before commit
        ChannelPolicyResponse saved = toResponse(channels.saveAndFlush(pc), false);
        audit.success(actor.userId(), storeId, "CATALOG_CHANNEL_UPDATE", "CATALOG", "PRODUCT_CHANNEL", saved.channelId(),
                before, saved, "Reconfigured " + channel.name());
        return saved;
    }

    @Transactional
    public void deleteChannel(CurrentActor actor, UUID storeId, UUID productId, Channel channel) {
        permissions.require(actor, PermissionCodes.PRODUCT_CHANNEL_MANAGE);
        storeAuth.requireStore(actor, storeId);
        var pc = channels.findByStoreIdAndProductIdAndChannel(storeId, productId, channel)
                .orElseThrow(() -> new ResourceNotFoundException("CHANNEL_CONFIGURATION_NOT_FOUND",
                        "No publication exists for this channel"));
        ChannelPolicyResponse before = toResponse(pc, false);
        List<ChannelScheduleView> beforeSchedules = schedules
                .findByProductChannelIdOrderByDayOfWeekAscStartLocalTimeAsc(pc.id()).stream()
                .map(s -> new ChannelScheduleView(s.id(), s.dayOfWeek(), s.startLocalTime(), s.endLocalTime(),
                        s.active(), s.version()))
                .toList();
        schedules.deleteByProductChannelId(pc.id());
        channels.delete(pc); // reverts the channel to its inherited default
        audit.success(actor.userId(), storeId, "CATALOG_CHANNEL_DELETE", "CATALOG", "PRODUCT_CHANNEL", pc.id(),
                java.util.Map.of("policy", before, "schedules", beforeSchedules), null,
                "Removed " + channel.name() + " publication (reverted to inherited default)");
    }

    // -------------------------------------------------------------- schedules

    @Transactional(readOnly = true)
    public List<ChannelScheduleView> listSchedules(CurrentActor actor, UUID storeId, UUID productId, Channel channel) {
        permissions.require(actor, PermissionCodes.PRODUCT_CHANNEL_READ);
        storeAuth.requireStore(actor, storeId);
        var pc = requireChannel(storeId, productId, channel);
        return schedules.findByProductChannelIdOrderByDayOfWeekAscStartLocalTimeAsc(pc.id()).stream()
                .map(s -> new ChannelScheduleView(s.id(), s.dayOfWeek(), s.startLocalTime(), s.endLocalTime(),
                        s.active(), s.version())).toList();
    }

    @Transactional
    public List<ChannelScheduleView> replaceSchedules(CurrentActor actor, UUID storeId, UUID productId, Channel channel,
            ReplaceSchedulesRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_CHANNEL_MANAGE);
        storeAuth.requireStore(actor, storeId);
        var pc = requireChannel(storeId, productId, channel);
        if (req.expectedVersion() != null && req.expectedVersion() != pc.version()) {
            throw new ConflictException("PRODUCT_CHANNEL_VERSION_CONFLICT",
                    "Stale channel version: expected " + pc.version());
        }
        List<ReplaceSchedulesRequest.Window> windows = req.schedules() == null ? List.of() : req.schedules();
        validateScheduleShape(windows);
        detectOverlap(windows);
        // The schedule list belongs to the channel aggregate, so replacing it must advance the
        // aggregate @Version that expectedVersion just guarded (Slice 3B.2 §9). Flush before the
        // child rows are rewritten so the increment is visible to the response and the audit entry.
        pc.scheduleReplaced(actor.userId());
        channels.saveAndFlush(pc);

        List<ChannelScheduleView> before = schedules
                .findByProductChannelIdOrderByDayOfWeekAscStartLocalTimeAsc(pc.id()).stream()
                .map(s -> new ChannelScheduleView(s.id(), s.dayOfWeek(), s.startLocalTime(), s.endLocalTime(),
                        s.active(), s.version()))
                .toList();
        schedules.deleteByProductChannelId(pc.id());
        List<ProductChannelSchedule> created = new ArrayList<>();
        for (var w : windows) {
            created.add(schedules.save(ProductChannelSchedule.create(pc.id(), w.dayOfWeek(), w.startTime(),
                    w.endTime(), w.active())));
        }
        List<ChannelScheduleView> after = created.stream()
                .map(s -> new ChannelScheduleView(s.id(), s.dayOfWeek(), s.startLocalTime(), s.endLocalTime(),
                        s.active(), s.version()))
                .toList();
        audit.success(actor.userId(), storeId, "CATALOG_CHANNEL_SCHEDULE_REPLACE", "CATALOG", "PRODUCT_CHANNEL",
                pc.id(), before, after, "Replaced " + channel.name() + " weekly schedule (" + after.size() + " windows)");
        return after;
    }

    // ----------------------------------------------------------- manual sold-out

    @Transactional
    public void setManualSoldOut(CurrentActor actor, UUID storeId, UUID productId, ManualSoldOutRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_CHANNEL_MANAGE);
        storeAuth.requireStore(actor, storeId);
        var sp = storeProducts.findById(new StoreProductId(storeId, productId))
                .orElseThrow(() -> new ResourceNotFoundException("STORE_PRODUCT_NOT_FOUND",
                        "Store does not offer this product"));
        if (req.expectedVersion() != sp.version()) {
            throw new ConflictException("STORE_PRODUCT_VERSION_CONFLICT",
                    "Stale store-product version: expected " + sp.version());
        }
        java.util.Map<String, Object> before = java.util.Map.of("available", sp.available(), "soldOutUntil",
                String.valueOf(sp.soldOutUntil()));
        sp.applyManualSoldOut(req.available(), req.soldOutUntil());
        storeProducts.save(sp);
        java.util.Map<String, Object> after = java.util.Map.of("available", sp.available(), "soldOutUntil",
                String.valueOf(sp.soldOutUntil()));
        audit.success(actor.userId(), storeId, "STORE_PRODUCT_MANUAL_SOLD_OUT", "CATALOG", "STORE_PRODUCT", productId,
                before, after, "Manual sold-out control set for store product");
    }

    // ----------------------------------------------------------- channel preview

    @Transactional(readOnly = true)
    public ProductDetailsResponse previewChannelCatalog(CurrentActor actor, UUID storeId, UUID productId,
            Channel channel) {
        permissions.require(actor, PermissionCodes.PRODUCT_CHANNEL_READ);
        permissions.require(actor, PermissionCodes.BRANCH_PRODUCT_READ);
        storeAuth.requireStore(actor, storeId);
        return catalog.detail(storeId, channel, productId, false); // tolerant of hidden products (§4B)
    }

    // ------------------------------------------------------------------ helpers

    private void requireStoreProduct(UUID storeId, UUID productId) {
        if (storeProducts.findById(new StoreProductId(storeId, productId)).isEmpty()) {
            throw new ResourceNotFoundException("STORE_PRODUCT_NOT_FOUND", "Store does not offer this product");
        }
    }

    private ProductChannel requireChannel(UUID storeId, UUID productId, Channel channel) {
        return channels.findByStoreIdAndProductIdAndChannel(storeId, productId, channel)
                .orElseThrow(() -> new ResourceNotFoundException("CHANNEL_CONFIGURATION_NOT_FOUND",
                        "No publication exists for this channel"));
    }

    private void validateWindow(Instant from, Instant until) {
        if (from != null && until != null && !from.isBefore(until)) {
            throw new ValidationException("INVALID_SCHEDULE", "availableFrom must precede availableUntil");
        }
    }

    private void validateScheduleShape(List<ReplaceSchedulesRequest.Window> windows) {
        for (var w : windows) {
            if (w.dayOfWeek() < 1 || w.dayOfWeek() > 7) {
                throw new ValidationException("INVALID_SCHEDULE", "dayOfWeek must be ISO 1..7");
            }
            if (w.startTime() == null || w.endTime() == null || w.startTime().equals(w.endTime())) {
                throw new ValidationException("INVALID_SCHEDULE", "start and end must be present and differ");
            }
        }
    }

    /**
     * Rejects overlapping ACTIVE windows on the weekly timeline. Each window is
     * projected to absolute week minutes (with overnight windows rolled into the
     * NEXT day, i.e. +1440, and duplicated a week earlier so the Sunday -> Monday
     * wrap is caught), then any interval intersection is a conflict.
     *
     * <p>Slice 3B.2 §9/§10 correction: an overnight window used to be extended by a whole
     * week (10080 minutes) instead of a single day, so e.g. Monday 22:00 &rarr; 02:00 covered
     * Monday 22:00 through the following Tuesday 02:00 and was rejected as an overlap against
     * every other window in the week (Wed 09:00 &rarr; 17:00 included). The wrap-around copy is
     * what makes a Sunday-evening tail collide with an early-Monday window, so the extension
     * itself must stay inside the next calendar day.
     */
    private void detectOverlap(List<ReplaceSchedulesRequest.Window> windows) {
        List<long[]> ranges = new ArrayList<>();
        for (var w : windows) {
            if (!w.active()) {
                continue; // disabled windows are ignored, so they cannot conflict
            }
            long start = (long) (w.dayOfWeek() - 1) * 1440 + toMinutes(w.startTime());
            long end = (long) (w.dayOfWeek() - 1) * 1440 + toMinutes(w.endTime());
            if (end <= start) {
                end += 1440; // overnight: the window finishes on the next calendar day
            }
            ranges.add(new long[] {start, end});
            ranges.add(new long[] {start - 10080, end - 10080}); // week-wrap copy
        }
        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                long a0 = ranges.get(i)[0], a1 = ranges.get(i)[1];
                long b0 = ranges.get(j)[0], b1 = ranges.get(j)[1];
                boolean sameWindowCopy = (a0 == b0 + 10080) || (b0 == a0 + 10080);
                if (sameWindowCopy) {
                    continue;
                }
                if (a0 < b1 && b0 < a1) {
                    throw new ConflictException("SCHEDULE_OVERLAP", "Overlapping schedule windows on the same day");
                }
            }
        }
    }

    private static int toMinutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private ChannelPolicyResponse toResponse(ProductChannel pc, boolean inherited) {
        return new ChannelPolicyResponse(pc.id(), pc.channel().name(), inherited, pc.visible(), pc.orderable(),
                pc.displayName(), pc.displayDescription(), pc.displayOrder(), pc.availableFrom(), pc.availableUntil(),
                pc.timezone(), pc.version());
    }

    private ChannelPolicyResponse inheritedResponse(Channel ch) {
        boolean def = ch.inheritsLegacyVisibility();
        return new ChannelPolicyResponse(null, ch.name(), true, def, def, null, null, 0, null, null, null, 0);
    }

    /** Schedule read projection (management only; exposes no inventory). */
    public record ChannelScheduleView(UUID scheduleId, int dayOfWeek, LocalTime startTime, LocalTime endTime,
            boolean active, long version) {
    }
}
