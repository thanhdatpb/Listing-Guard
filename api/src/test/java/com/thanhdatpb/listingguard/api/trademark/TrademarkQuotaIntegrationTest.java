package com.thanhdatpb.listingguard.api.trademark;

import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.setUsed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the quota does to the trademark check itself (ADR-013), below the HTTP layer: a complete result is counted once
 * and a cached one not at all, an INCOMPLETE result is stored but not counted, and the plan's pack and the month's quota
 * still come before an analysis whose outcome is not known yet.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class TrademarkQuotaIntegrationTest {

    @Autowired
    TrademarkCheckService service;

    @Autowired
    TrademarkCheckStore store;

    @Autowired
    NormalizerRegistry normalizers;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    TrademarkProperties properties;

    @Autowired
    PlanService plans;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(PlanTestSupport.NOVEMBER_15);
    }

    private TestClient seller() {
        return newClient(provisioning, clients, PlanCode.SELLER);
    }

    private int used(TestClient client) {
        return PlanTestSupport.used(jdbc, client.id(), NOVEMBER);
    }

    private CheckRequest request(String title) {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title);
        return new CheckRequest("ETSY", listing);
    }

    /** A catalog that fails every lookup, so every rule that reads it fails and the result is INCOMPLETE. */
    private record FailingCatalog(String version) implements TrademarkCatalog {
        @Override
        public Map<String, CatalogMark> findExact(Collection<String> normalizedForms) {
            throw new IllegalStateException("catalog unavailable");
        }

        @Override
        public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> normalizedForms, double candidateThreshold) {
            throw new IllegalStateException("catalog unavailable");
        }
    }

    private TrademarkCheckService failingService() {
        TrademarkCatalogRuntime custom = new TrademarkCatalogRuntime(properties, jdbc, transactions, rulesets) {
            @Override
            public Serving serving(String marketplace) {
                Serving real = super.serving(marketplace);
                Function<Serving, TrademarkCatalog> failing = serving -> new FailingCatalog(serving.catalogVersion());
                return new Serving(new TrademarkScanner(rulesets.ruleset(), failing.apply(real)), real.fingerprint(),
                        real.catalogVersionId(), real.catalogVersion());
            }
        };
        assertThat(custom.evaluate().state()).isEqualTo(TrademarkCatalogRuntime.State.READY);
        return new TrademarkCheckService(normalizers, custom, rulesets, store, transactions, plans);
    }

    @Test
    void aCompleteResultIsCountedOnceAndACachedOneNotAtAll() {
        TestClient seller = seller();
        CheckRequest first = request("Plain ceramic mug " + PlanTestSupport.unique());

        TrademarkCheckResult stored = service.check(seller.id(), first);
        assertThat(stored.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(stored.cached()).isFalse();
        assertThat(used(seller)).isEqualTo(1);

        assertThat(service.check(seller.id(), first).cached()).isTrue();
        assertThat(used(seller)).isEqualTo(1);

        service.check(seller.id(), request("Plain ceramic mug " + PlanTestSupport.unique()));
        assertThat(used(seller)).isEqualTo(2);
    }

    @Test
    void anIncompleteResultIsStoredButNotCounted() {
        TestClient seller = seller();

        TrademarkCheckResult result = failingService().check(seller.id(), request("Northvale Baseball Jersey " + PlanTestSupport.unique()));

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(countOf(jdbc, "trademark_check", seller.id())).as("it is stored, so GET can read it").isEqualTo(1);
        assertThat(service.get(seller.id(), result.checkId()).completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(used(seller)).as("it is never reused, so it is not charged").isZero();

        service.check(seller.id(), request("Plain ceramic mug " + PlanTestSupport.unique()));
        assertThat(used(seller)).as("a complete result is").isEqualTo(1);
    }

    @Test
    void theQuotaIsCheckedBeforeAnAnalysisWhoseOutcomeIsNotKnownYet() {
        TestClient seller = seller();
        setUsed(jdbc, seller.id(), NOVEMBER, quota(jdbc, PlanCode.SELLER));

        assertThatThrownBy(() -> failingService().check(seller.id(), request("Northvale Baseball Jersey " + PlanTestSupport.unique())))
                .isInstanceOf(QuotaExceededException.class);

        assertThat(countOf(jdbc, "trademark_check", seller.id())).isZero();
    }

    @Test
    void thePackIsCheckedBeforeTheCatalogIsAsked() {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        assertThatThrownBy(() -> service.check(free.id(), request("Plain ceramic mug " + PlanTestSupport.unique())))
                .isInstanceOfSatisfying(PlanNotAllowedException.class, e -> {
                    assertThat(e.plan()).isEqualTo("FREE");
                    assertThat(e.pack().id()).isEqualTo("trademark-risk");
                });
        assertThat(countOf(jdbc, "trademark_check", free.id())).isZero();
    }
}
