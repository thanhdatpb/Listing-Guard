package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConsoleModelAdviceTest {

    @Test
    void aVersionIsADemoOneOnlyWhenItStartsWithDemoAndAHyphen() {
        assertThat(ConsoleModelAdvice.isDemo("demo-fictional-2026-11-0123456789ab")).isTrue();
        assertThat(ConsoleModelAdvice.isDemo("demo-x")).isTrue();

        assertThat(ConsoleModelAdvice.isDemo("")).isFalse();
        assertThat(ConsoleModelAdvice.isDemo(null)).isFalse();
        assertThat(ConsoleModelAdvice.isDemo("demo")).isFalse();
        assertThat(ConsoleModelAdvice.isDemo("demonstration-2026-11")).isFalse();
        assertThat(ConsoleModelAdvice.isDemo("Demo-fictional")).isFalse();
        assertThat(ConsoleModelAdvice.isDemo("not-a-demo-catalog")).isFalse();
        assertThat(ConsoleModelAdvice.isDemo("registry-2026-11-0123456789ab")).isFalse();
    }
}
