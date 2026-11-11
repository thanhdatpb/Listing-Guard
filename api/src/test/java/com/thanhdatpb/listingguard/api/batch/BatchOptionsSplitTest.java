package com.thanhdatpb.listingguard.api.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.batch.SplitOutput.TrademarkHold;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** The command line of the review gate: off unless asked for, and the hold level only with it. */
class BatchOptionsSplitTest {

    private static final Path DEFAULT_OUT = Path.of("batch", "out");

    private static BatchOptions parse(String... extra) {
        List<String> args = new ArrayList<>(List.of("--input=in.csv", "--mapping=map.yaml", "--client=c1", "--marketplace=ETSY"));
        args.addAll(List.of(extra));
        return BatchOptions.parse(new DefaultApplicationArguments(args.toArray(new String[0])), DEFAULT_OUT);
    }

    @Test
    void withoutTheFlagThereIsNoReviewGateAndTheOutputIsTheOldOne() {
        BatchOptions options = parse();

        assertThat(options.splitOutput()).isFalse();
        assertThat(options.holdTrademarkRisk()).isEqualTo(TrademarkHold.HIGH);
    }

    @Test
    void theFlagTurnsTheGateOnAndTrademarkHighHoldsByDefault() {
        BatchOptions options = parse("--split-output");

        assertThat(options.splitOutput()).isTrue();
        assertThat(options.holdTrademarkRisk()).isEqualTo(TrademarkHold.HIGH);
    }

    @Test
    void theHoldLevelIsTheOperatorsChoice() {
        assertThat(parse("--split-output", "--hold-trademark-risk=medium").holdTrademarkRisk()).isEqualTo(TrademarkHold.MEDIUM);
        assertThat(parse("--split-output", "--hold-trademark-risk=NONE").holdTrademarkRisk()).isEqualTo(TrademarkHold.NONE);
    }

    @Test
    void aHoldLevelWithoutTheFlagIsRefusedInsteadOfIgnored() {
        assertThatThrownBy(() -> parse("--hold-trademark-risk=HIGH")).isInstanceOf(BatchConfigException.class)
                .hasMessageContaining("--hold-trademark-risk needs --split-output");
    }

    @Test
    void theFlagTakesNoValueAndTheLevelMustBeKnown() {
        assertThatThrownBy(() -> parse("--split-output=true")).isInstanceOf(BatchConfigException.class)
                .hasMessageContaining("--split-output takes no value");
        assertThatThrownBy(() -> parse("--split-output", "--hold-trademark-risk=low")).isInstanceOf(BatchConfigException.class)
                .hasMessageContaining("HIGH, MEDIUM or NONE");
        assertThatThrownBy(() -> parse("--split-output", "--hold-trademark-risk=HIGH", "--hold-trademark-risk=NONE"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("once");
    }

    @Test
    void theUsageTextNamesBothOptions() {
        assertThat(BatchOptions.USAGE).contains("--split-output").contains("--hold-trademark-risk=HIGH|MEDIUM|NONE");
    }
}
