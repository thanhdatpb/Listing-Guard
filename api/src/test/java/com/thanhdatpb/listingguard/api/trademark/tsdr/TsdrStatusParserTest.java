package com.thanhdatpb.listingguard.api.trademark.tsdr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The status reader (ADR-009, "TSDR status check"): XML and JSON, only plain values, nothing guessed, nothing resolved from the
 * outside. Every document here is synthetic and shaped like the default field names, which are themselves unverified.
 */
class TsdrStatusParserTest {

    private final TsdrStatusParser parser = new TsdrStatusParser(new TsdrProperties.Fields(
            List.of("MarkCurrentStatusCode", "status"),
            List.of("MarkCurrentStatusExternalDescriptionText", "extStatusDesc"),
            List.of("MarkCurrentStatusDate", "statusDate")));

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsAnXmlDocumentWhereverTheNamesAre() throws Exception {
        var parsed = parser.parse(bytes(FakeTsdrServer.xml("700", "REGISTERED", "2020-01-02-04:00")));
        assertThat(parsed.code()).isEqualTo("700");
        assertThat(parsed.description()).isEqualTo("REGISTERED");
        assertThat(parsed.date()).isEqualTo(LocalDate.of(2020, 1, 2));
    }

    @Test
    void readsAJsonDocumentAndIgnoresCase() throws Exception {
        var parsed = parser.parse(bytes("{\"trademarks\":[{\"STATUS\":{\"Status\":603,\"extStatusDesc\":\"ABANDONED\","
                + "\"statusDate\":\"20190820\"}}]}"));
        assertThat(parsed.code()).isEqualTo("603");
        assertThat(parsed.description()).isEqualTo("ABANDONED");
        assertThat(parsed.date()).isEqualTo(LocalDate.of(2019, 8, 20));
    }

    @Test
    void aMissingDescriptionOrDateIsNullButAMissingCodeIsNoAnswer() throws Exception {
        var parsed = parser.parse(bytes(FakeTsdrServer.xml("700", null, null)));
        assertThat(parsed.description()).isNull();
        assertThat(parsed.date()).isNull();

        assertThatThrownBy(() -> parser.parse(bytes("<a><b>x</b></a>"))).isInstanceOf(TsdrStatusParser.Unparseable.class)
                .hasMessageContaining("no status code");
        assertThatThrownBy(() -> parser.parse(bytes("{\"other\":1}"))).isInstanceOf(TsdrStatusParser.Unparseable.class);
    }

    @Test
    void anElementThatHoldsOtherElementsIsNotAValue() throws Exception {
        // the first name is an object here, so the reader must go on to a plain one and never join the children's text
        var parsed = parser.parse(bytes("<r><MarkCurrentStatusCode><x>1</x></MarkCurrentStatusCode><status>700</status></r>"));
        assertThat(parsed.code()).isEqualTo("700");
    }

    @Test
    void thingsThatAreNotDocumentsAreRefusedWithoutQuotingThem() {
        for (String text : new String[] {"", "   ", "plain text with SECRET-123", "<unclosed", "{broken"}) {
            assertThatThrownBy(() -> parser.parse(bytes(text))).isInstanceOf(TsdrStatusParser.Unparseable.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET-123"));
        }
        assertThatThrownBy(() -> parser.parse(bytes("<r><status>not a code!</status></r>")))
                .isInstanceOf(TsdrStatusParser.Unparseable.class);
    }

    @Test
    void anExternalEntityIsNeverResolved() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r><status>&x;</status></r>";
        // either refused or read without the entity's content: never a status code made of a local file
        try {
            var parsed = parser.parse(bytes(xxe));
            assertThat(parsed.code()).doesNotContain("root");
        } catch (TsdrStatusParser.Unparseable expected) {
            assertThat(expected.getMessage()).doesNotContain("passwd");
        }
    }

    @Test
    void datesInTheFormsTheDocumentsUse() {
        assertThat(TsdrStatusParser.date("2019-08-20")).isEqualTo(LocalDate.of(2019, 8, 20));
        assertThat(TsdrStatusParser.date("2019-08-20T10:00:00Z")).isEqualTo(LocalDate.of(2019, 8, 20));
        assertThat(TsdrStatusParser.date("20190820")).isEqualTo(LocalDate.of(2019, 8, 20));
        assertThat(TsdrStatusParser.date("2019-13-45")).isNull();
        assertThat(TsdrStatusParser.date("yesterday")).isNull();
        assertThat(TsdrStatusParser.date(null)).isNull();
    }
}
