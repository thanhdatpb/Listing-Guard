package com.thanhdatpb.listingguard.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PolicyManifestLoaderTest {

    private static final String VALID = """
            documents:
              - file: seller-policy.html
                marketplace: etsy
                title: Seller Policy
                url: https://www.etsy.com/legal/sellers/
                fetched_at: 2026-10-27
                version: 2026-10-05
            """;

    @Test
    void readsAnEntry() {
        PolicyManifest manifest = PolicyManifestLoader.parse(VALID);

        assertThat(manifest.documents()).hasSize(1);
        PolicyManifest.Entry entry = manifest.documents().get(0);
        assertThat(entry.file()).isEqualTo("seller-policy.html");
        assertThat(entry.marketplace()).isEqualTo("ETSY");
        assertThat(entry.title()).isEqualTo("Seller Policy");
        assertThat(entry.url()).isEqualTo("https://www.etsy.com/legal/sellers/");
        assertThat(entry.fetchedAt()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(entry.version()).isEqualTo("2026-10-05");
    }

    @Test
    void rejectsAMissingKey() {
        String[][] fields = {{"file", "a.html"}, {"marketplace", "etsy"}, {"title", "T"}, {"url", "https://x/"},
                {"fetched_at", "2026-10-27"}, {"version", "1"}};
        for (int skip = 0; skip < fields.length; skip++) {
            StringBuilder yaml = new StringBuilder("documents:\n");
            boolean first = true;
            for (int i = 0; i < fields.length; i++) {
                if (i != skip) {
                    yaml.append(first ? "  - " : "    ").append(fields[i][0]).append(": ").append(fields[i][1]).append("\n");
                    first = false;
                }
            }
            assertThatThrownBy(() -> PolicyManifestLoader.parse(yaml.toString()))
                    .isInstanceOf(PolicyParseException.class)
                    .hasMessageContaining("'" + fields[skip][0] + "'");
        }
    }

    @Test
    void rejectsAnUnknownKey() {
        assertThatThrownBy(() -> PolicyManifestLoader.parse(VALID + "    extra: 1\n"))
                .isInstanceOf(PolicyParseException.class).hasMessageContaining("extra");
    }

    @Test
    void rejectsABadDateAndABadUrl() {
        assertThatThrownBy(() -> PolicyManifestLoader.parse(VALID.replace("2026-10-27", "27/10/2026")))
                .isInstanceOf(PolicyParseException.class).hasMessageContaining("fetched_at");
        assertThatThrownBy(() -> PolicyManifestLoader.parse(VALID.replace("https://www.etsy.com/legal/sellers/", "etsy.com/legal")))
                .isInstanceOf(PolicyParseException.class).hasMessageContaining("url");
    }

    @Test
    void rejectsABlankVersion() {
        assertThatThrownBy(() -> PolicyManifestLoader.parse(VALID.replace("version: 2026-10-05", "version: ''")))
                .isInstanceOf(PolicyParseException.class).hasMessageContaining("version");
    }

    @Test
    void rejectsTheSameFileTwiceForOneMarketplace() {
        String twice = VALID + VALID.substring(VALID.indexOf("  - file"));

        assertThatThrownBy(() -> PolicyManifestLoader.parse(twice))
                .isInstanceOf(PolicyParseException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void sameFileNameInDifferentMarketplacesIsFine() {
        String other = VALID.substring(VALID.indexOf("  - file")).replace("marketplace: etsy", "marketplace: amazon");

        assertThat(PolicyManifestLoader.parse(VALID + other).documents()).hasSize(2);
    }

    @Test
    void rejectsAMissingDocumentsList() {
        assertThatThrownBy(() -> PolicyManifestLoader.parse("foo: bar\n")).isInstanceOf(PolicyParseException.class);
        assertThatThrownBy(() -> PolicyManifestLoader.parse("")).isInstanceOf(PolicyParseException.class);
    }

    @Test
    void rejectsDuplicateYamlKeys() {
        assertThatThrownBy(() -> PolicyManifestLoader.parse(VALID + "    title: Again\n")).isInstanceOf(PolicyParseException.class);
    }
}
