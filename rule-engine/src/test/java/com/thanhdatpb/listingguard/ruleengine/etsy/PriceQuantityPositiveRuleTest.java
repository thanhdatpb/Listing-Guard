package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.empty;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.valid;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceQuantityPositiveRuleTest {

    private final PriceQuantityPositiveRule rule = new PriceQuantityPositiveRule();

    private static Listing with(String price, Integer quantity) {
        Listing base = valid();
        return new Listing(base.title(), base.description(), base.tags(),
                price == null ? null : new Money(new BigDecimal(price), "USD"), base.category(), base.materials(),
                base.images(), quantity, base.whoMade(), base.whenMade());
    }

    private List<Finding> run(String price, Integer quantity) {
        return rule.evaluate(with(price, quantity), CONFIG);
    }

    @Test
    void positiveValuesAreValid() {
        assertThat(run("19.99", 5)).isEmpty();
    }

    @Test
    void smallestPositiveValuesAreValid() {
        assertThat(run("0.01", 1)).isEmpty();
    }

    @Test
    void zeroPriceIsAnError() {
        List<Finding> findings = run("0", 5);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("price_quantity_positive");
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.field()).isEqualTo("price");
        assertThat(finding.message()).isEqualTo("Giá là 0, phải lớn hơn 0.");
    }

    @Test
    void negativePriceIsAnError() {
        assertThat(run("-3.50", 5)).extracting(Finding::field).containsExactly("price");
        assertThat(run("-3.50", 5).get(0).message()).contains("-3.50");
    }

    @Test
    void zeroQuantityIsAnError() {
        List<Finding> findings = run("19.99", 0);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).field()).isEqualTo("quantity");
        assertThat(findings.get(0).message()).isEqualTo("Số lượng là 0, phải lớn hơn 0.");
    }

    @Test
    void negativeQuantityIsAnError() {
        assertThat(run("19.99", -2)).extracting(Finding::field).containsExactly("quantity");
    }

    @Test
    void reportsPriceAndQuantityTogether() {
        assertThat(run("0", 0)).extracting(Finding::field).containsExactly("price", "quantity");
    }

    @Test
    void missingValuesAreLeftToRequiredFields() {
        assertThat(rule.evaluate(empty(), CONFIG)).isEmpty();
        assertThat(run(null, null)).isEmpty();
    }
}
