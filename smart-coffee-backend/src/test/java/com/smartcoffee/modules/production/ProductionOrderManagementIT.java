package com.smartcoffee.modules.production;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.modules.auth.security.JwtTokenService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Slice 5A Phase 5A.1 vertical HTTP integration test (spec §26). Runs the
 * real Spring Security filter chain, the DB-backed permission grants from
 * V051, the Flyway-migrated schema, the JPA aggregate + repositories and the
 * controller wiring. No bean is stubbed.
 *
 * <p>Coverage maps one-to-one to the Phase 5A.1 acceptance list:
 * <ol>
 *   <li>{@code POST} createDraft produces a {@code PO-yyyyMMdd-<seq>} number
 *       and a {@code DRAFT} row.</li>
 *   <li>{@code POST} plan freezes the RecipeVersion + material snapshot +
 *       output row and performs no inventory movement (spec §7 completion-
 *       time posting policy — Phase 5A.2 wires the ledger).</li>
 *   <li>{@code POST} start transitions PLANNED → IN_PROGRESS exactly once,
 *       records actor/time and writes no {@code stock_transactions} row
 *       (spec: "Start moves no stock").</li>
 *   <li>Recipe line mutation after plan does NOT alter the snapshot
 *       (spec §5 immutability: "Recipe line changes do not affect the
 *       snapshot").</li>
 *   <li>Database idempotency replays a same-key/same-body request verbatim
 *       and rejects a same-key/different-body attempt with
 *       {@code IDEMPOTENCY_KEY_REUSED}.</li>
 *   <li>Optimistic version guard rejects a stale {@code expectedVersion} with
 *       {@code PRODUCTION_ORDER_VERSION_CONFLICT}.</li>
 *   <li>Store scoping: a STORE_MANAGER for Store B cannot create or read a
 *       Store A production order — {@code STORE_ACCESS_DENIED} and no side
 *       effects on the aggregate.</li>
 *   <li>Permission seed: SUPER_ADMIN (via V051 role grants) holds every
 *       {@code production-order:*} code; BARISTA holds none, so their create
 *       attempt is rejected as {@code AUTH_FORBIDDEN}.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductionOrderManagementIT extends IntegrationTestBase {

    // Role IDs come from the V002 seed and are stable across every existing
    // management IT (see ProductCustomizationGroupHttpIT and siblings).
    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String STORE_MANAGER = "00000000-0000-0000-0000-000000000004";
    private static final String BARISTA = "00000000-0000-0000-0000-000000000006";
    private static final String BASE = "/api/v1/management/production-orders";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private UUID storeA;
    private UUID storeB;
    private UUID product;
    private UUID beans;
    private UUID finishedGood;
    private UUID recipeVersionId;
    private String adminToken;

    @BeforeEach
    void seedWorld() {
        storeA = newStore("POA");
        storeB = newStore("POB");
        UUID gramUnit = ensureUnit("POG", "MASS");
        UUID eachUnit = ensureUnit("POEA", "COUNT");
        beans = newIngredient("POBEANS", gramUnit);
        finishedGood = newIngredient("POFGOOD", eachUnit);
        UUID category = newCategory();
        product = newProduct(category, finishedGood);
        recipeVersionId = newActiveRecipe(product, beans, /* perUnit */ "0.018");
        assignProductRecipe(product, recipeVersionId);

        adminToken = staffToken(UUID.fromString(SUPER_ADMIN), null);
    }

    // -------------------------------------------------------------- create
    @Test
    void createProducesDraftOrderWithServerGeneratedNumber() {
        UUID key = UUID.randomUUID();
        Resp created = post(BASE, createBody(storeA, product, new BigDecimal("10")), adminToken, key);
        assertEquals(200, created.status(), created.body());
        JsonNode data = created.data();
        assertEquals("DRAFT", data.path("status").asText());
        assertTrue(data.path("productionOrderNumber").asText()
                        .startsWith("PO-" + LocalDate.now(java.time.ZoneOffset.UTC)
                                .format(DateTimeFormatter.BASIC_ISO_DATE) + "-"),
                "PO number follows PO-yyyyMMdd-<seq> contract (spec §13)");
        // The database is the source of truth for the frozen DRAFT row.
        assertEquals("DRAFT", jdbc.queryForObject(
                "select status from production.production_orders where production_order_id = ?",
                String.class, UUID.fromString(data.path("productionOrderId").asText())));
        // No inventory movement in DRAFT.
        assertEquals(0L, stockTxCount(data.path("productionOrderId").asText()));
    }

    // --------------------------------------------------------- plan + start
    @Test
    void planFreezesSnapshotAndStartMovesNoStock() {
        Created c = createDraft(new BigDecimal("10"));
        long versionAfterCreate = orderVersion(c.id());

        // Plan: freeze recipe + material + output rows. No stock movement.
        Resp planned = post(BASE + "/" + c.id + "/plan",
                "{\"expectedVersion\":" + versionAfterCreate + "}", adminToken, UUID.randomUUID());
        assertEquals(200, planned.status(), planned.body());
        assertEquals("PLANNED", planned.data().path("status").asText());
        assertEquals(recipeVersionId.toString(), planned.data().path("recipeVersionId").asText());
        assertEquals(1, planned.data().path("materials").size());
        assertEquals(0, new BigDecimal("0.1800").compareTo(new BigDecimal(
                planned.data().path("materials").get(0).path("quantityPlannedBase").asText())));
        assertEquals(1, planned.data().path("outputs").size());
        assertEquals(finishedGood.toString(),
                planned.data().path("outputs").get(0).path("outputInventoryItemId").asText());

        long versionAfterPlan = orderVersion(c.id());
        assertTrue(versionAfterPlan > versionAfterCreate, "each transition must bump @Version");

        // Start: lifecycle only, no stock movement (Phase 5A.1 policy).
        Resp started = post(BASE + "/" + c.id + "/start",
                "{\"expectedVersion\":" + versionAfterPlan + "}", adminToken, UUID.randomUUID());
        assertEquals(200, started.status(), started.body());
        assertEquals("IN_PROGRESS", started.data().path("status").asText());
        assertNotNull(started.data().path("startedAt").asText());

        // Phase 5A.1 acceptance: start performed NO inventory mutation whatsoever.
        assertEquals(0L, stockTxCount(c.id.toString()),
                "spec §7 completion-time posting policy: PLANNED + IN_PROGRESS must not touch stock_transactions");
        BigDecimal beansOnHand = jdbc.queryForObject(
                "select coalesce(sum(current_stock),0) from inventory.inventory_balances where ingredient_id = ?",
                BigDecimal.class, beans);
        assertEquals(0, BigDecimal.ZERO.compareTo(beansOnHand),
                "no inventory_balances row may exist or move for the raw ingredient at Phase 5A.1");
    }

    @Test
    void startRejectsSecondAttemptAndOnlyAcceptsPlanned() {
        Created c = createDraft(new BigDecimal("5"));
        long v0 = orderVersion(c.id());
        // Cannot start a DRAFT directly.
        Resp bad = post(BASE + "/" + c.id + "/start", "{\"expectedVersion\":" + v0 + "}", adminToken,
                UUID.randomUUID());
        assertEquals(409, bad.status(), bad.body());
        assertEquals("PRODUCTION_ORDER_STATE_CONFLICT", bad.errorCode());

        // Plan → OK.
        post(BASE + "/" + c.id + "/plan", "{\"expectedVersion\":" + v0 + "}", adminToken, UUID.randomUUID());
        long v1 = orderVersion(c.id());
        assertEquals(200, post(BASE + "/" + c.id + "/start", "{\"expectedVersion\":" + v1 + "}", adminToken,
                UUID.randomUUID()).status());

        // Second start with the fresh version is a stable PRODUCTION_ALREADY_STARTED conflict.
        long v2 = orderVersion(c.id());
        Resp twice = post(BASE + "/" + c.id + "/start", "{\"expectedVersion\":" + v2 + "}", adminToken,
                UUID.randomUUID());
        assertEquals(409, twice.status(), twice.body());
        assertEquals("PRODUCTION_ALREADY_STARTED", twice.errorCode());
    }

    // ---------------------------------------------- recipe snapshot invariant
    @Test
    void recipeLineChangeAfterPlanDoesNotAffectTheFrozenSnapshot() {
        Created c = createDraft(new BigDecimal("10"));
        long v0 = orderVersion(c.id());
        post(BASE + "/" + c.id + "/plan", "{\"expectedVersion\":" + v0 + "}", adminToken, UUID.randomUUID());

        BigDecimal plannedBefore = materialQuantity(c.id, beans);
        assertEquals(0, new BigDecimal("0.1800").compareTo(plannedBefore));

        // Mutate the recipe line under our feet — the snapshot MUST resist.
        jdbc.update("update recipe.recipe_version_lines set quantity_required_base = 9.9999 "
                + "where recipe_version_id = ?", recipeVersionId);

        assertEquals(0, plannedBefore.compareTo(materialQuantity(c.id, beans)),
                "spec §5: recipe line changes must never rewrite the material snapshot");
    }

    // ---------------------------------------------------- idempotency replay
    @Test
    void idempotencyReplaysSameKeyAndRejectsKeyReuseWithDifferentBody() {
        UUID key = UUID.randomUUID();
        Resp first = post(BASE, createBody(storeA, product, new BigDecimal("10")), adminToken, key);
        assertEquals(200, first.status());
        Resp replay = post(BASE, createBody(storeA, product, new BigDecimal("10")), adminToken, key);
        assertEquals(200, replay.status());
        assertEquals(first.data().path("productionOrderId").asText(),
                replay.data().path("productionOrderId").asText(),
                "same key + same body replays the cached response (spec §14)");

        // Same key, different body → conflict.
        Resp clash = post(BASE, createBody(storeA, product, new BigDecimal("11")), adminToken, key);
        assertEquals(409, clash.status(), clash.body());
        assertEquals("IDEMPOTENCY_KEY_REUSED", clash.errorCode());
    }

    // ------------------------------------------------- optimistic versioning
    @Test
    void planWithStaleExpectedVersionIsRejected() {
        Created c = createDraft(new BigDecimal("4"));
        Resp stale = post(BASE + "/" + c.id + "/plan", "{\"expectedVersion\":999}", adminToken,
                UUID.randomUUID());
        assertEquals(409, stale.status(), stale.body());
        assertEquals("PRODUCTION_ORDER_VERSION_CONFLICT", stale.errorCode());
        // Order remains DRAFT and untouched.
        assertEquals("DRAFT", jdbc.queryForObject(
                "select status from production.production_orders where production_order_id = ?",
                String.class, c.id));
    }

    // ------------------------------------------------- store-scope enforcement
    @Test
    void storeManagerCannotCreateOrderForForeignStore() {
        String mgrBToken = staffToken(UUID.fromString(STORE_MANAGER), storeB);
        Resp denied = post(BASE, createBody(storeA, product, new BigDecimal("1")), mgrBToken,
                UUID.randomUUID());
        assertEquals(403, denied.status(), denied.body());
        assertEquals("STORE_ACCESS_DENIED", denied.errorCode());
        // No order leaked into the DB.
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from production.production_orders where store_id = ?", Long.class, storeA));
    }

    @Test
    void storeManagerCannotReadForeignOrderByGuessingId() {
        Created c = createDraft(new BigDecimal("1"));
        String mgrBToken = staffToken(UUID.fromString(STORE_MANAGER), storeB);
        Resp denied = get(BASE + "/" + c.id, mgrBToken);
        assertEquals(403, denied.status(), denied.body());
        assertEquals("STORE_ACCESS_DENIED", denied.errorCode());
        // The order itself remains intact.
        assertEquals("DRAFT", get(BASE + "/" + c.id, adminToken).data().path("status").asText());
    }

    // ---------------------------------------------------- permission seed
    @Test
    void baristaWithoutProductionOrderPermissionIsRejected() {
        String baristaToken = staffToken(UUID.fromString(BARISTA), storeA);
        Resp denied = post(BASE, createBody(storeA, product, new BigDecimal("1")), baristaToken,
                UUID.randomUUID());
        assertEquals(403, denied.status(), denied.body());
        assertEquals("AUTH_FORBIDDEN", denied.errorCode(),
                "V051 does not grant BARISTA any production-order:* permission (Decision 3)");
    }

    // ------------------------------------------------------ read + list
    @Test
    void listAndMaterialReadReflectStateTransitions() {
        Created c = createDraft(new BigDecimal("2"));
        long v0 = orderVersion(c.id);
        post(BASE + "/" + c.id + "/plan", "{\"expectedVersion\":" + v0 + "}", adminToken, UUID.randomUUID());

        Resp list = get(BASE + "?storeId=" + storeA + "&status=PLANNED", adminToken);
        assertEquals(200, list.status());
        JsonNode rows = list.data().path("content");
        assertEquals(1, rows.size(), "the just-planned order is the only PLANNED row in the fresh store");
        assertEquals(c.id.toString(), rows.get(0).path("productionOrderId").asText());

        Resp mats = get(BASE + "/" + c.id + "/materials", adminToken);
        assertEquals(200, mats.status());
        assertEquals(1, mats.data().size());
        assertEquals(beans.toString(), mats.data().get(0).path("inventoryItemId").asText());
    }

    // ============================================================ Phase 5A.2
    // Scenario A — completion with sufficient stock posts both ledgers, moves
    // material down / output up, leaves reservedStock untouched (spec §14 A).
    @Test
    void scenarioA_completeWithSufficientStockPostsBothLedgers() {
        seedBalance(beans, "100");
        Created c = advanceToInProgress(product, new BigDecimal("10"));
        Resp r = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "10", null, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(200, r.status(), r.body());
        assertEquals("COMPLETED", r.data().path("status").asText());
        // gross=10 → consumed 0.018*10 = 0.18; 100 - 0.18 = 99.82
        assertEquals(0, new BigDecimal("99.82").compareTo(balance(beans)), "material consumed");
        assertEquals(0, new BigDecimal("10").compareTo(balance(finishedGood)), "output posted");
        assertEquals(0, BigDecimal.ZERO.compareTo(reserved(beans)), "reservedStock untouched (spec §2)");
        assertEquals(1, ledgerCount(c.id, "PRODUCTION_CONSUMPTION"));
        assertEquals(1, ledgerCount(c.id, "PRODUCTION_OUTPUT"));
        assertNotEquals("", r.data().path("completedAt").asText());
    }

    // Scenario B — insufficient material rolls back clean, then succeeds after
    // restock (spec §14 B).
    @Test
    void scenarioB_insufficientMaterialRollsBackThenSucceedsAfterRestock() {
        seedBalance(beans, "0.01");
        Created c = advanceToInProgress(product, new BigDecimal("10"));
        Resp r = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "10", null, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(409, r.status(), r.body());
        assertEquals("PRODUCTION_MATERIAL_INSUFFICIENT", r.errorCode());
        // No partial consumption, no output row, no ledger, order still IN_PROGRESS.
        assertEquals(0, new BigDecimal("0.01").compareTo(balance(beans)));
        assertEquals(0L, stockTxCount(c.id.toString()));
        assertEquals(0, outputBalanceRows(c.id, finishedGood));
        assertEquals("IN_PROGRESS", jdbc.queryForObject(
                "select status from production.production_orders where production_order_id = ?",
                String.class, c.id));
        // Retry after restock succeeds.
        jdbc.update("update inventory.inventory_balances set current_stock = 100 where store_id = ? "
                + "and ingredient_id = ?", storeA, beans);
        Resp retry = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "10", null, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(200, retry.status(), retry.body());
        assertEquals("COMPLETED", retry.data().path("status").asText());
    }

    // Scenario C — batch-tracked output creates exactly one batch; idempotent
    // replay creates no second batch (spec §14 C).
    @Test
    void scenarioC_trackedOutputCreatesSingleBatchAndReplayNoSecond() {
        UUID trackedGood = newTrackedIngredient("POBATCH");
        UUID trackedProduct = newProductWithOutput(trackedGood);
        seedBalance(beans, "100");
        Created c = advanceToInProgress(trackedProduct, new BigDecimal("10"));
        UUID key = UUID.randomUUID();
        String body = completeBody(orderVersion(c.id), "10", null, "LOT-C1", "2026-11-06", "2026-12-06");
        Resp r = post(BASE + "/" + c.id + "/complete", body, adminToken, key);
        assertEquals(200, r.status(), r.body());
        assertEquals(1, batchCount(trackedGood, "LOT-C1"));
        assertNotEquals("", r.data().path("outputs").get(0).path("batchId").asText());
        // Replay same key + same body → no second batch, still one output ledger.
        Resp replay = post(BASE + "/" + c.id + "/complete", body, adminToken, key);
        assertEquals(200, replay.status(), replay.body());
        assertEquals(1, batchCount(trackedGood, "LOT-C1"), "replay must not create a second batch");
        assertEquals(1, ledgerCount(c.id, "PRODUCTION_OUTPUT"));
    }

    // Scenario D — finished-output waste uses the gross basis, output only
    // grows by the good quantity, no raw-material double decrement (spec §14 D).
    @Test
    void scenarioD_finishedOutputWasteUsesGrossBasis() {
        seedBalance(beans, "100");
        Created c = advanceToInProgress(product, new BigDecimal("10"));
        String waste = "[{\"inventoryItemId\":\"" + finishedGood + "\",\"quantity\":2,"
                + "\"unitCode\":\"POEA\",\"reasonCode\":\"BURNT\",\"notes\":\"burnt tray\"}]";
        Resp r = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "8", waste, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(200, r.status(), r.body());
        // gross = 8 + 2 = 10 → consumed 0.18 (same as full batch); output only 8.
        assertEquals(0, new BigDecimal("99.82").compareTo(balance(beans)));
        assertEquals(0, new BigDecimal("8").compareTo(balance(finishedGood)));
        assertEquals(1, r.data().path("waste").size());
    }

    // Scenario E — cancel DRAFT / PLANNED / IN_PROGRESS (before any movement)
    // writes no stock transaction (spec §14 E).
    @Test
    void scenarioE_cancelBeforeMovementMovesNoStock() {
        Created d = createDraft(new BigDecimal("3"));
        Resp cd = post(BASE + "/" + d.id + "/cancel",
                "{\"expectedVersion\":" + orderVersion(d.id) + ",\"reason\":\"mistake\"}", adminToken,
                UUID.randomUUID());
        assertEquals(200, cd.status(), cd.body());
        assertEquals("CANCELLED", cd.data().path("status").asText());
        assertEquals(0L, stockTxCount(d.id.toString()));

        Created p = createDraft(new BigDecimal("3"));
        post(BASE + "/" + p.id + "/plan", "{\"expectedVersion\":" + orderVersion(p.id) + "}", adminToken,
                UUID.randomUUID());
        Resp cp = post(BASE + "/" + p.id + "/cancel",
                "{\"expectedVersion\":" + orderVersion(p.id) + ",\"reason\":\"changed mind\"}", adminToken,
                UUID.randomUUID());
        assertEquals(200, cp.status(), cp.body());
        assertEquals(0L, stockTxCount(p.id.toString()));

        Created ip = advanceToInProgress(product, new BigDecimal("3"));
        Resp ci = post(BASE + "/" + ip.id + "/cancel",
                "{\"expectedVersion\":" + orderVersion(ip.id) + ",\"reason\":\"equipment failure\"}", adminToken,
                UUID.randomUUID());
        assertEquals(200, ci.status(), ci.body());
        assertEquals("CANCELLED", ci.data().path("status").asText());
        assertEquals(0L, stockTxCount(ip.id.toString()));
    }

    // Scenario F — close COMPLETED performs no inventory movement (spec §14 F).
    @Test
    void scenarioF_closeCompletedMovesNoStock() {
        seedBalance(beans, "100");
        Created c = advanceToInProgress(product, new BigDecimal("10"));
        post(BASE + "/" + c.id + "/complete", completeBody(orderVersion(c.id), "10", null, null, null, null),
                adminToken, UUID.randomUUID());
        BigDecimal beansBefore = balance(beans);
        BigDecimal outBefore = balance(finishedGood);
        Resp cl = post(BASE + "/" + c.id + "/close",
                "{\"expectedVersion\":" + orderVersion(c.id) + ",\"note\":\"reconciled\"}", adminToken,
                UUID.randomUUID());
        assertEquals(200, cl.status(), cl.body());
        assertEquals("CLOSED", cl.data().path("status").asText());
        assertEquals(0, beansBefore.compareTo(balance(beans)), "close moves no material");
        assertEquals(0, outBefore.compareTo(balance(finishedGood)), "close moves no output");
        assertEquals(1, ledgerCount(c.id, "PRODUCTION_CONSUMPTION"));
        assertEquals(1, ledgerCount(c.id, "PRODUCTION_OUTPUT"));
    }

    // Scenario G — idempotent completion replay yields one ledger set (spec §14 G).
    @Test
    void scenarioG_completionReplayProducesOneLedgerSet() {
        seedBalance(beans, "100");
        Created c = advanceToInProgress(product, new BigDecimal("10"));
        UUID key = UUID.randomUUID();
        String body = completeBody(orderVersion(c.id), "10", null, null, null, null);
        assertEquals(200, post(BASE + "/" + c.id + "/complete", body, adminToken, key).status());
        Resp replay = post(BASE + "/" + c.id + "/complete", body, adminToken, key);
        assertEquals(200, replay.status(), replay.body());
        assertEquals(1, ledgerCount(c.id, "PRODUCTION_CONSUMPTION"));
        assertEquals(1, ledgerCount(c.id, "PRODUCTION_OUTPUT"));
        assertEquals(0, new BigDecimal("99.82").compareTo(balance(beans)), "replay consumes once");
    }

    // Scenario H — same key with a different completion body is rejected and
    // causes no additional movement (spec §14 H).
    @Test
    void scenarioH_sameKeyDifferentBodyRejected() {
        seedBalance(beans, "100");
        Created c = advanceToInProgress(product, new BigDecimal("10"));
        UUID key = UUID.randomUUID();
        assertEquals(200, post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "10", null, null, null, null), adminToken, key).status());
        Resp clash = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "9", null, null, null, null), adminToken, key);
        assertEquals(409, clash.status(), clash.body());
        assertEquals("IDEMPOTENCY_KEY_REUSED", clash.errorCode());
        assertEquals(1, ledgerCount(c.id, "PRODUCTION_OUTPUT"));
        assertEquals(0, new BigDecimal("10").compareTo(balance(finishedGood)), "no extra output");
    }

    // HTTP error matrix — completion wrong state, stale version, invalid
    // output/waste, batch validation (spec §15).
    @Test
    void completeRejectsWrongStateStaleVersionAndInvalidInput() {
        // Wrong state: PLANNED (never started).
        Created p = createDraft(new BigDecimal("10"));
        post(BASE + "/" + p.id + "/plan", "{\"expectedVersion\":" + orderVersion(p.id) + "}", adminToken,
                UUID.randomUUID());
        Resp ws = post(BASE + "/" + p.id + "/complete",
                completeBody(orderVersion(p.id), "10", null, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(409, ws.status(), ws.body());
        assertEquals("PRODUCTION_ORDER_STATE_CONFLICT", ws.errorCode());

        seedBalance(beans, "100");
        Created c = advanceToInProgress(product, new BigDecimal("10"));

        // Stale version.
        Resp sv = post(BASE + "/" + c.id + "/complete",
                completeBody(999, "10", null, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(409, sv.status(), sv.body());
        assertEquals("PRODUCTION_ORDER_VERSION_CONFLICT", sv.errorCode());

        // Negative output quantity.
        Resp io = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "-1", null, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(400, io.status(), io.body());
        assertEquals("PRODUCTION_OUTPUT_QUANTITY_INVALID", io.errorCode());

        // Waste referencing an unrelated item.
        String bad = "[{\"inventoryItemId\":\"" + UUID.randomUUID() + "\",\"quantity\":1,"
                + "\"unitCode\":\"POEA\",\"reasonCode\":\"SPOILED\",\"notes\":\"x\"}]";
        Resp iw = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "8", bad, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(400, iw.status(), iw.body());
        assertEquals("PRODUCTION_WASTE_ITEM_INVALID", iw.errorCode());

        // OTHER reason without notes.
        String other = "[{\"inventoryItemId\":\"" + finishedGood + "\",\"quantity\":1,"
                + "\"unitCode\":\"POEA\",\"reasonCode\":\"OTHER\",\"notes\":null}]";
        Resp ow = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "8", other, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(400, ow.status(), ow.body());
        assertEquals("PRODUCTION_WASTE_ITEM_INVALID", ow.errorCode());
    }

    // Batch validation: blank lot for tracked output → PRODUCTION_BATCH_REQUIRED;
    // a repeated lot across two orders → PRODUCTION_BATCH_DUPLICATE (spec §15).
    @Test
    void batchValidationBlankLotAndDuplicateLot() {
        UUID trackedGood = newTrackedIngredient("POBATCH2");
        UUID trackedProduct = newProductWithOutput(trackedGood);
        seedBalance(beans, "100");

        Created c1 = advanceToInProgress(trackedProduct, new BigDecimal("10"));
        Resp blank = post(BASE + "/" + c1.id + "/complete",
                completeBody(orderVersion(c1.id), "10", null, "", null, null), adminToken, UUID.randomUUID());
        assertEquals(400, blank.status(), blank.body());
        assertEquals("PRODUCTION_BATCH_REQUIRED", blank.errorCode());

        Resp ok = post(BASE + "/" + c1.id + "/complete",
                completeBody(orderVersion(c1.id), "10", null, "LOT-DUP", null, null), adminToken,
                UUID.randomUUID());
        assertEquals(200, ok.status(), ok.body());

        // A second order reusing the same lot conflicts.
        Created c2 = advanceToInProgress(trackedProduct, new BigDecimal("10"));
        Resp dup = post(BASE + "/" + c2.id + "/complete",
                completeBody(orderVersion(c2.id), "10", null, "LOT-DUP", null, null), adminToken,
                UUID.randomUUID());
        assertEquals(409, dup.status(), dup.body());
        assertEquals("PRODUCTION_BATCH_DUPLICATE", dup.errorCode());
    }

    // Cancel/close terminal guards (spec §15).
    @Test
    void cancelCompletedRejectedAndCloseNonCompletedRejected() {
        seedBalance(beans, "100");
        Created c = advanceToInProgress(product, new BigDecimal("10"));
        post(BASE + "/" + c.id + "/complete", completeBody(orderVersion(c.id), "10", null, null, null, null),
                adminToken, UUID.randomUUID());
        // Cancel a COMPLETED order → stable 409, not a 500.
        Resp cc = post(BASE + "/" + c.id + "/cancel",
                "{\"expectedVersion\":" + orderVersion(c.id) + ",\"reason\":\"too late\"}", adminToken,
                UUID.randomUUID());
        assertEquals(409, cc.status(), cc.body());
        assertEquals("PRODUCTION_ORDER_STATE_CONFLICT", cc.errorCode());

        // Close a non-COMPLETED (IN_PROGRESS) order → stable 409.
        Created ip = advanceToInProgress(product, new BigDecimal("5"));
        Resp cn = post(BASE + "/" + ip.id + "/close",
                "{\"expectedVersion\":" + orderVersion(ip.id) + ",\"note\":\"no\"}", adminToken, UUID.randomUUID());
        assertEquals(409, cn.status(), cn.body());
        assertEquals("PRODUCTION_ORDER_STATE_CONFLICT", cn.errorCode());

        // Cancel with blank reason → 400.
        Resp br = post(BASE + "/" + ip.id + "/cancel",
                "{\"expectedVersion\":" + orderVersion(ip.id) + ",\"reason\":\"\"}", adminToken, UUID.randomUUID());
        assertEquals(400, br.status(), br.body());
    }

    // Authorization + not-found on the new endpoints (spec §15).
    @Test
    void completeCancelCloseEnforceAuthAndMissingOrder() {
        String baristaToken = staffToken(UUID.fromString(BARISTA), storeA);
        Created c = advanceToInProgress(product, new BigDecimal("1"));
        Resp denied = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "1", null, null, null, null), baristaToken, UUID.randomUUID());
        assertEquals(403, denied.status(), denied.body());
        assertEquals("AUTH_FORBIDDEN", denied.errorCode());

        // Missing order → 404.
        Resp missing = post(BASE + "/" + UUID.randomUUID() + "/cancel",
                "{\"expectedVersion\":0,\"reason\":\"ghost\"}", adminToken, UUID.randomUUID());
        assertEquals(404, missing.status(), missing.body());
        assertEquals("PRODUCTION_ORDER_NOT_FOUND", missing.errorCode());

        // Cross-store cancel → 403.
        String mgrBToken = staffToken(UUID.fromString(STORE_MANAGER), storeB);
        Created a = createDraft(new BigDecimal("1"));
        Resp x = post(BASE + "/" + a.id + "/cancel",
                "{\"expectedVersion\":" + orderVersion(a.id) + ",\"reason\":\"nope\"}", mgrBToken, UUID.randomUUID());
        assertEquals(403, x.status(), x.body());
        assertEquals("STORE_ACCESS_DENIED", x.errorCode());
    }

    // Error envelope must never leak SQL/stack detail (spec §15).
    @Test
    void insufficientStockErrorEnvelopeCarriesNoInternalDetail() {
        seedBalance(beans, "0.0001");
        Created c = advanceToInProgress(product, new BigDecimal("10"));
        Resp r = post(BASE + "/" + c.id + "/complete",
                completeBody(orderVersion(c.id), "10", null, null, null, null), adminToken, UUID.randomUUID());
        assertEquals(409, r.status());
        assertFalse(r.body().toLowerCase().contains("sql"), "no SQL leak: " + r.body());
        assertFalse(r.body().contains("inventory_balances"), "no table name leak: " + r.body());
        assertFalse(r.body().contains("Exception"), "no stack/exception leak: " + r.body());
    }

    // ---------------------------------------------------------- fixtures
    private record Created(UUID id, long version) {}

    // ------------------------------------------------- Phase 5A.2 fixtures
    private Created advanceToInProgress(UUID prod, BigDecimal plannedQty) {
        Resp r = post(BASE, createBody(storeA, prod, plannedQty), adminToken, UUID.randomUUID());
        assertEquals(200, r.status(), r.body());
        UUID id = UUID.fromString(r.data().path("productionOrderId").asText());
        post(BASE + "/" + id + "/plan", "{\"expectedVersion\":" + orderVersion(id) + "}", adminToken,
                UUID.randomUUID());
        Resp s = post(BASE + "/" + id + "/start", "{\"expectedVersion\":" + orderVersion(id) + "}", adminToken,
                UUID.randomUUID());
        assertEquals(200, s.status(), s.body());
        return new Created(id, orderVersion(id));
    }

    private BigDecimal balance(UUID ing) {
        return jdbc.queryForObject("select coalesce(sum(current_stock),0) from inventory.inventory_balances "
                + "where store_id = ? and ingredient_id = ?", BigDecimal.class, storeA, ing);
    }

    private BigDecimal reserved(UUID ing) {
        return jdbc.queryForObject("select coalesce(sum(reserved_stock),0) from inventory.inventory_balances "
                + "where store_id = ? and ingredient_id = ?", BigDecimal.class, storeA, ing);
    }

    private int outputBalanceRows(UUID orderId, UUID ing) {
        return jdbc.queryForObject("select count(*) from inventory.inventory_balances where store_id = ? "
                + "and ingredient_id = ?", Integer.class, storeA, ing);
    }

    private long ledgerCount(UUID orderId, String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions where "
                + "reference_type = 'PRODUCTION_ORDER' and reference_id = ? and transaction_type = ?",
                Long.class, orderId, type);
    }

    private long batchCount(UUID ing, String lot) {
        return jdbc.queryForObject("select count(*) from inventory.inventory_batches where store_id = ? "
                + "and ingredient_id = ? and lot_number = ?", Long.class, storeA, ing, lot);
    }

    private void seedBalance(UUID ing, String current) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                + "reserved_stock, minimum_stock, average_unit_cost) values (?,?,?,0,0,0.025)", storeA, ing,
                new BigDecimal(current));
    }

    private UUID newTrackedIngredient(String codePrefix) {
        UUID eachUnit = ensureUnit("POEA", "COUNT");
        UUID id = UUID.randomUUID();
        String code = codePrefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, "
                + "base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, "
                + "version) values (?,?,?, ?,0,true,true,true,now(),now(),0)", id, code, code, eachUnit);
        return id;
    }

    /** Builds a fresh product (new category + ACTIVE recipe) whose output maps
     *  to the supplied tracked ingredient, wired the same way as Step 2. */
    private UUID newProductWithOutput(UUID outputIng) {
        UUID category = newCategory();
        UUID prod = newProduct(category, outputIng);
        UUID rv = newActiveRecipe(prod, beans, "0.018");
        assignProductRecipe(prod, rv);
        return prod;
    }

    private String completeBody(long v, String good, String waste, String lot, String mfg, String exp) {
        StringBuilder sb = new StringBuilder("{\"expectedVersion\":" + v
                + ",\"actualGoodOutputQuantity\":" + good + ",\"outputUnit\":\"POEA\"");
        if (waste != null) {
            sb.append(",\"wasteEntries\":" + waste);
        }
        if (lot != null) {
            sb.append(",\"lotNumber\":\"" + lot + "\"");
        }
        if (mfg != null) {
            sb.append(",\"manufacturedDate\":\"" + mfg + "\"");
        }
        if (exp != null) {
            sb.append(",\"expiryDate\":\"" + exp + "\"");
        }
        return sb.append("}").toString();
    }


    private Created createDraft(BigDecimal qty) {
        Resp r = post(BASE, createBody(storeA, product, qty), adminToken, UUID.randomUUID());
        assertEquals(200, r.status(), r.body());
        return new Created(UUID.fromString(r.data().path("productionOrderId").asText()),
                r.data().path("version").asLong());
    }

    private long orderVersion(UUID id) {
        return jdbc.queryForObject(
                "select version from production.production_orders where production_order_id = ?",
                Long.class, id);
    }

    private BigDecimal materialQuantity(UUID orderId, UUID itemId) {
        return jdbc.queryForObject(
                "select quantity_planned_base from production.production_order_materials "
                        + "where production_order_id = ? and inventory_item_id = ?",
                BigDecimal.class, orderId, itemId);
    }

    private long stockTxCount(String orderId) {
        return jdbc.queryForObject(
                "select count(*) from inventory.stock_transactions where reference_type = 'PRODUCTION_ORDER' "
                        + "and reference_id = ?::uuid", Long.class, orderId);
    }

    private String createBody(UUID store, UUID product, BigDecimal qty) {
        return "{\"storeId\":\"" + store + "\",\"productId\":\"" + product
                + "\",\"plannedOutputQuantity\":" + qty + ",\"notes\":\"IT draft\"}";
    }

    // ---------------------------------------------------------- HTTP client
    private Resp post(String path, String body, String bearer, UUID idemKey) {
        return send("POST", path, body, bearer, idemKey == null ? null : idemKey.toString());
    }

    private Resp get(String path, String bearer) {
        return send("GET", path, null, bearer, null);
    }

    private Resp send(String method, String path, String body, String bearer, String idemKey) {
        HttpRequest.Builder b = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: " + path, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("I/O: " + path, e);
        }
    }

    private record Resp(int status, String body) {
        JsonNode data() {
            return tree().path("data");
        }

        String errorCode() {
            return tree().path("error").path("code").asText();
        }

        JsonNode tree() {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Unparseable: " + body, e);
            }
        }
    }

    // ---------------------------------------------------------- persistence helpers
    private UUID newStore(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,?,'Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id,
                prefix + "-" + UUID.randomUUID().toString().substring(0, 6), "PO IT " + prefix);
        return id;
    }

    private UUID ensureUnit(String code, String type) {
        List<UUID> existing = jdbc.queryForList(
                "select unit_id from inventory.units_of_measure where unit_code = ?", UUID.class, code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.units_of_measure (unit_id, unit_code, unit_name, unit_type, "
                + "decimal_scale, conversion_factor_to_base, base_unit, active, created_at, updated_at, version) "
                + "values (?,?,?, ?,3,1,true,true,now(),now(),0)", id, code, code, type);
        return id;
    }

    private UUID newIngredient(String codePrefix, UUID baseUnit) {
        UUID id = UUID.randomUUID();
        String code = codePrefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, "
                + "base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, "
                + "version) values (?,?,?, ?,0,true,false,true,now(),now(),0)", id, code, code, baseUnit);
        return id;
    }

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        String code = "POC-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, "
                + "display_order, active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)",
                id, code, code);
        return id;
    }

    private UUID newProduct(UUID categoryId, UUID outputIngredient) {
        UUID id = UUID.randomUUID();
        String code = "POP-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, "
                + "base_price, currency_code, preparation_minutes, tax_rate, customizable, active, "
                + "output_ingredient_id, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.10,false,true,?,now(),now(),0)", id, code, categoryId,
                "PO IT Latte", new BigDecimal("5.00"), outputIngredient);
        return id;
    }

    /**
     * Inserts an ACTIVE recipe_version + one line pointing at the raw
     * ingredient. Yield is 1 unit so {@code perUnit} demand equals the line's
     * {@code quantity_required_base} (the exact behaviour Slice 5A planner
     * expects from {@link com.smartcoffee.modules.recipe.application.RecipeLineNormalizer}).
     */
    private UUID newActiveRecipe(UUID productId, UUID ingredient, String perUnit) {
        UUID rv = UUID.randomUUID();
        jdbc.update("insert into recipe.recipe_versions (recipe_version_id, product_id, version_number, "
                + "status, yield_quantity, effective_from, created_at, updated_at, version) "
                + "values (?,?,1,'ACTIVE',1,now(),now(),now(),0)", rv, productId);
        jdbc.update("insert into recipe.recipe_version_lines (recipe_version_line_id, recipe_version_id, "
                + "ingredient_id, quantity_required_base, wastage_percent, scaling_rule, display_order, "
                + "optional) values (?,?,?,?,0,'PROPORTIONAL_TO_YIELD',0,false)",
                UUID.randomUUID(), rv, ingredient, new BigDecimal(perUnit));
        return rv;
    }

    private void assignProductRecipe(UUID productId, UUID recipeVersionId) {
        jdbc.update("update catalog.products set active_recipe_version_id = ? where product_id = ?",
                recipeVersionId, productId);
    }

    private String staffToken(UUID roleId, UUID storeId) {
        UUID userId = UUID.randomUUID();
        String email = "po-http-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, "
                + "preferred_language, status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", userId, "PO HTTP Actor", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), userId, roleId, storeId,
                java.sql.Timestamp.from(Instant.now()));
        return tokens.access(userId, 0L, Set.of());
    }
}
