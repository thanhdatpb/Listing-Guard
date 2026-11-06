package com.thanhdatpb.listingguard.api.variation;

import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.amazonFamily;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.failingRulesets;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.variation.Completeness;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What {@link VariationCheckService} does below the HTTP layer (ADR-015): an INCOMPLETE result (a rule failed) is stored so
 * its id can be read and is never reused for another request, a complete one is cached normally next to it, and only the
 * unique violation of the cache index counts as a cache race.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class VariationCheckServiceIntegrationTest {

    @Autowired
    VariationCheckService service;

    @Autowired
    VariationProperties properties;

    @Autowired
    ResourceLoader resources;

    @Autowired
    RulesetVersionRepository versions;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    NormalizerRegistry marketplaces;

    @Autowired
    VariationNormalizer normalizer;

    @Autowired
    VariationCheckStore store;

    @Autowired
    VariationRulesets rulesets;

    @Autowired
    PlanService plans;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    private VariationCheckService incompleteService() {
        return new VariationCheckService(marketplaces, normalizer,
                failingRulesets(properties, resources, versions, transactions), store, transactions, plans);
    }

    private VariationCheckRequest family(String unique) throws Exception {
        return MAPPER.treeToValue(amazonFamily(rulesets.ruleset(), unique, null), VariationCheckRequest.class);
    }

    @Test
    void anIncompleteResultIsStoredReadableAndNeverReusedForAnotherRequest() throws Exception {
        TestClient client = newClient(provisioning, clients, PlanCode.FREE);
        VariationCheckRequest request = family(unique());

        VariationCheckResult first = incompleteService().check(client.id(), request);
        VariationCheckResult again = incompleteService().check(client.id(), request);

        assertThat(first.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(first.failedRuleIds()).containsExactly(VariationApiSupport.failingRule().id());
        assertThat(first.cached()).isFalse();
        assertThat(first.findings()).as("the failing rule is the only rule, so nothing was established").isEmpty();
        assertThat(service.get(client.id(), first.checkId()).completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(service.get(client.id(), first.checkId()).failedRuleIds()).isEqualTo(first.failedRuleIds());

        assertThat(again.cached()).as("an INCOMPLETE result answers no other request").isFalse();
        assertThat(again.checkId()).isNotEqualTo(first.checkId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM variation_check WHERE client_id = ? AND completeness = 'INCOMPLETE'",
                Integer.class, client.id())).isEqualTo(2);
    }

    @Test
    void aCompleteResultIsCachedNormallyNextToIncompleteOnes() throws Exception {
        TestClient client = newClient(provisioning, clients, PlanCode.FREE);
        VariationCheckRequest request = family(unique());
        incompleteService().check(client.id(), request);

        VariationCheckResult complete = service.check(client.id(), request);
        VariationCheckResult cached = service.check(client.id(), request);

        assertThat(complete.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(complete.cached()).isFalse();
        assertThat(cached.cached()).isTrue();
        assertThat(cached.checkId()).isEqualTo(complete.checkId());
    }

    @Test
    void aCheckThatDoesNotExistForTheClientIsNotFound() {
        TestClient client = newClient(provisioning, clients, PlanCode.FREE);

        assertThatThrownBy(() -> service.get(client.id(), UUID.randomUUID())).isInstanceOf(VariationCheckNotFoundException.class);
    }

    @Test
    void onlyTheUniqueViolationOfTheCacheIndexIsACacheRace() {
        String cache = "ERROR: duplicate key value violates unique constraint \"uq_variation_check_cache\"";
        String other = "ERROR: duplicate key value violates unique constraint \"variation_check_pkey\"";

        assertThat(VariationCheckStore.isCacheIdentityViolation(
                new DataIntegrityViolationException("x", new SQLException(cache, "23505")))).isTrue();
        assertThat(VariationCheckStore.isCacheIdentityViolation(
                new DataIntegrityViolationException("x", new SQLException(other, "23505")))).isFalse();
        assertThat(VariationCheckStore.isCacheIdentityViolation(
                new DataIntegrityViolationException("x", new SQLException(cache, "23503")))).isFalse();
        assertThat(VariationCheckStore.isCacheIdentityViolation(new IllegalStateException("x"))).isFalse();
    }

    @Test
    void theMarketplaceOfTheStoredRowIsTheCanonicalCode() throws Exception {
        TestClient client = newClient(provisioning, clients, PlanCode.FREE);
        VariationCheckRequest base = family(unique());
        VariationCheckRequest lower = new VariationCheckRequest("amazon", base.parent(), null, base.children());

        VariationCheckResult result = service.check(client.id(), lower);

        assertThat(result.marketplace()).isEqualTo("AMAZON");
        assertThat(jdbc.queryForObject("SELECT marketplace_code FROM variation_check WHERE id = ?", String.class,
                result.checkId())).isEqualTo("AMAZON");
    }
}
