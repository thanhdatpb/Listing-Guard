package com.thanhdatpb.listingguard.api.evaluation.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.batch.BatchConfigException;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** The label columns and the command line of the backtest (ADR-032). */
class BacktestLabelsTest {

    private static final List<String> HEADER = List.of("sku", "claimed", "issue", "mark");
    private final BacktestLabels labels = BacktestLabels.bind(HEADER, "claimed", "issue", "mark");

    private BacktestLabels.Labels read(String claimed, String issue, String mark) {
        return labels.read(new String[] {"S-1", claimed, issue, mark});
    }

    @Test
    void readsYesNoInSeveralSpellingsAndBlankAsUnlabeled() {
        assertThat(read("Yes", "", "").claimed()).isTrue();
        assertThat(read(" TRUE ", "", "").claimed()).isTrue();
        assertThat(read("1", "", "").claimed()).isTrue();
        assertThat(read("no", "", "").claimed()).isFalse();
        assertThat(read("0", "", "").claimed()).isFalse();
        assertThat(read("  ", "trademark", "Zibblefrax").claimed()).isNull();
    }

    @Test
    void readsTheIssueOfAClaimedListingOnly() {
        assertThat(read("yes", "Listing_Policy", "").issue()).isEqualTo(IssueType.LISTING_POLICY);
        assertThat(read("yes", "listing policy", "").issue()).isEqualTo(IssueType.LISTING_POLICY);
        assertThat(read("yes", "", "").issue()).isEqualTo(IssueType.UNSPECIFIED);
        assertThat(read("yes", "trademark", " Zibblefrax ").mark()).isEqualTo("Zibblefrax");
        // the issue of a listing that was not claimed is not read, so a stale cell cannot refuse the row
        assertThat(read("no", "whatever", "").issue()).isNull();
    }

    @Test
    void refusesAStrangeLabelWithItsColumnAndValue() {
        assertThatThrownBy(() -> read("maybe", "", "")).isInstanceOf(BacktestLabels.LabelException.class)
                .hasMessageContaining("'claimed'").hasMessageContaining("maybe");
        assertThatThrownBy(() -> read("yes", "trade mark?", "")).isInstanceOf(BacktestLabels.LabelException.class)
                .hasMessageContaining("'issue'").hasMessageContaining("trade mark?").hasMessageContaining("copyright");
        assertThatThrownBy(() -> read("yes", "trademark", "x".repeat(201)))
                .isInstanceOf(BacktestLabels.LabelException.class).hasMessageContaining("200");
    }

    @Test
    void aMissingOrRepeatedLabelColumnIsRefusedAtStartUp() {
        assertThatThrownBy(() -> BacktestLabels.bind(HEADER, "taken_down", null, null))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("no label column 'taken_down'");
        assertThatThrownBy(() -> BacktestLabels.bind(List.of("claimed", "claimed"), "claimed", null, null))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("more than once");
        assertThat(BacktestLabels.bind(HEADER, "claimed", null, null).read(new String[] {"S", "yes", "x", "y"}).issue())
                .isEqualTo(IssueType.UNSPECIFIED);
    }

    @Test
    void parsesTheCommandLine() {
        BacktestOptions options = BacktestOptions.parse(new DefaultApplicationArguments("--input=a.csv", "--mapping=m.yaml",
                "--marketplace=ETSY", "--claimed-column=claimed", "--catch-severity=error", "--catch-trademark-risk=high",
                "--variant-threshold=0.7", "--spring.profiles.active=backtest"), Path.of("eval", "backtest", "out", "x"));

        assertThat(options.catchSeverity()).isEqualTo(Severity.ERROR);
        assertThat(options.catchTrademarkRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(options.variantThreshold()).isEqualTo(0.7);
        assertThat(options.issueColumn()).isNull();
        assertThat(options.out()).isEqualTo(Path.of("eval", "backtest", "out", "x"));

        assertThatThrownBy(() -> BacktestOptions.parse(new DefaultApplicationArguments("--input=a.csv"), Path.of("o")))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("Missing --mapping");
        assertThatThrownBy(() -> BacktestOptions.parse(new DefaultApplicationArguments("--input=a.csv", "--mapping=m",
                "--marketplace=ETSY", "--claimed-column=c", "--client=x"), Path.of("o")))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("Unknown option --client");
        assertThatThrownBy(() -> BacktestOptions.parse(new DefaultApplicationArguments("--input=a.csv", "--mapping=m",
                "--marketplace=ETSY", "--claimed-column=c", "--catch-trademark-risk=LOW"), Path.of("o")))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("MEDIUM or HIGH");
        assertThatThrownBy(() -> BacktestOptions.parse(new DefaultApplicationArguments("--input=a.csv", "--mapping=m",
                "--marketplace=ETSY", "--claimed-column=c", "--variant-threshold=1"), Path.of("o")))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("between 0 and 1");
    }
}
