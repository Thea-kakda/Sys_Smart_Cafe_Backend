package com.smartcoffee.modules.production;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.production.api.ProductionStationResponse;
import com.smartcoffee.modules.production.application.ProductionStationQueryService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Checkpoint 4 — production station discovery. Runs against the real migrated
 * database with strict ddl-auto=validate. Seeded (V008) at store BKK1:
 * BARISTA (display 10), KITCHEN (20), BAKERY (30), all active.
 */
@SuppressWarnings("null")
class ProductionStationDiscoveryIT extends IntegrationTestBase {

    private static final UUID BKK1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String STORE_MANAGER = "00000000-0000-0000-0000-000000000004";
    private static final String BARISTA_ROLE = "00000000-0000-0000-0000-000000000006";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired ProductionStationQueryService service;

    private CurrentActor adminActor;

    @BeforeEach
    void seedAdmin() {
        adminActor = asUserWithRole(SUPER_ADMIN, null);
    }

    private CurrentActor asUserWithRole(String roleId, UUID storeId) {
        UUID userId = UUID.randomUUID();
        String email = "u-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "IT User", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,?,?)", UUID.randomUUID(), userId, UUID.fromString(roleId), storeId,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    private UUID createTestStore(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'IT Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id, code);
        return id;
    }

    private void seedStation(UUID storeId, String code, String type, int order, boolean active) {
        jdbc.update("insert into production.production_stations (station_id, store_id, station_code, station_name, "
                + "station_type, display_order, active, version) values (?,?,?,?,?,?,?,0)",
                UUID.randomUUID(), storeId, code, code, type, order, active);
    }

    @Test
    void discoversActiveStationsInDisplayOrder() {
        List<ProductionStationResponse> stations = service.discover(adminActor, BKK1, false);
        assertEquals(List.of("BARISTA", "KITCHEN", "BAKERY"),
                stations.stream().map(ProductionStationResponse::stationCode).toList());
        // deterministic ordering by display order
        assertEquals(10, stations.get(0).displayOrder());
        assertEquals(20, stations.get(1).displayOrder());
        assertEquals(30, stations.get(2).displayOrder());
        assertTrue(stations.stream().allMatch(ProductionStationResponse::active));
        // authoritative fields present
        assertEquals(BKK1, stations.get(0).storeId());
        assertEquals("BARISTA", stations.get(0).stationType());
        assertNotNull(stations.get(0).stationId());
    }

    @Test
    void activeOnlyByDefault() {
        seedStation(BKK1, "PICKUP", "PICKUP", 40, false);
        try {
            List<ProductionStationResponse> active = service.discover(adminActor, BKK1, false);
            assertTrue(active.stream().noneMatch(s -> s.stationCode().equals("PICKUP")),
                    "inactive station must not appear by default");
        } finally {
            jdbc.update("delete from production.production_stations where store_id=? and station_code='PICKUP'", BKK1);
        }
    }

    @Test
    void includeInactiveRevealsDisabledStationsForAdministrator() {
        seedStation(BKK1, "OLD_BAR", "BARISTA", 5, false);
        try {
            assertTrue(adminActor.hasPermission("production:station-manage"),
                    "SUPER_ADMIN must hold the administrative station grant");
            List<ProductionStationResponse> all = service.discover(adminActor, BKK1, true);
            assertTrue(all.stream().anyMatch(s -> s.stationCode().equals("OLD_BAR")),
                    "administrative view reveals inactive stations");
            assertTrue(all.stream().anyMatch(s -> !s.active()));
        } finally {
            jdbc.update("delete from production.production_stations where store_id=? and station_code='OLD_BAR'", BKK1);
        }
    }

    @Test
    void includeInactiveRejectedWithoutManagePermission() {
        // A store-scoped barista has station-read but not station-manage.
        CurrentActor barista = asUserWithRole(BARISTA_ROLE, BKK1);
        assertTrue(barista.hasPermission("production:station-read"));
        assertFalse(barista.hasPermission("production:station-manage"));

        var ex = assertThrows(ForbiddenException.class, () -> service.discover(barista, BKK1, true));
        assertEquals("AUTH_FORBIDDEN", ex.code());

        // Active discovery is still allowed.
        assertFalse(service.discover(barista, BKK1, false).isEmpty());
    }

    @Test
    void forbiddenForUnassignedBranch() {
        UUID otherStore = createTestStore("PB" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        CurrentActor manager = asUserWithRole(STORE_MANAGER, otherStore);

        var ex = assertThrows(ForbiddenException.class, () -> service.discover(manager, BKK1, false));
        assertEquals("STORE_ACCESS_DENIED", ex.code());
    }

    @Test
    void unknownBranchNotFound() {
        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.discover(adminActor, UUID.randomUUID(), false));
        assertEquals("STORE_NOT_FOUND", ex.code());
    }

    @Test
    void stationBelongsToItsAssignedStoreOnly() {
        UUID myStore = createTestStore("MY" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        CurrentActor manager = asUserWithRole(STORE_MANAGER, myStore);
        seedStation(myStore, "MY_BAR", "BARISTA", 10, true);

        List<ProductionStationResponse> mine = service.discover(manager, myStore, false);
        assertEquals(1, mine.size());
        assertEquals(myStore, mine.get(0).storeId());
        assertEquals("MY_BAR", mine.get(0).stationCode());
    }
}
