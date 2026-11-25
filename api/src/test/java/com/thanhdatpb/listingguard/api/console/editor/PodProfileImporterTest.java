package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.ApiKeyHasher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Supplier spec files become new POD profile versions (ADR-048). All data below is invented. */
class PodProfileImporterTest {

    private static final String HEADER = "product_type,variant,print_area_width_mm,print_area_height_mm,max_chars,"
            + "source_ref,verified_by,verified_at";

    @TempDir Path dir;

    private final PodProfileImporter importer = new PodProfileImporter(new PathMatchingResourcePatternResolver());

    private Path csv(String... lines) throws IOException {
        Path file = Files.createTempFile(dir, "spec", ".csv");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private Path out() {
        return dir.resolve("out");
    }

    private List<String> written() throws IOException {
        if (!Files.isDirectory(out())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(out())) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private PodProfiles load() throws IOException {
        return new PodProfiles(new ObjectMapper(), new PathMatchingResourcePatternResolver(), out().toUri() + "*.json");
    }

    @Test
    void aVerifiedRowNeedsASourceAndTwoPositiveDimensionsAndOtherRowsStayUnverified() throws Exception {
        Path file = csv(HEADER,
                "Sample Tee,S,280,350.5,,BLK-TS-01,Operator A,2026-11-20",
                "Sample Tee,M,300,380,,BLK-TS-02,Operator A,2026-11-20",
                "Sample Tee,L,320,,,BLK-TS-03,Operator A,2026-11-20",
                "Sample Tee,XL,340,400,,,Operator A,2026-11-20");

        var items = importer.importCsv(file, out(), new PodProfileImporter.Options(null, List.of("sku", "size", "color"),
                null, null, false));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.key()).isEqualTo("sample-tee-v1");
            assertThat(item.outcome()).isEqualTo(PodProfileImporter.Outcome.WRITTEN);
            assertThat(item.verified()).isEqualTo(2);
            assertThat(item.unverified()).isEqualTo(2);
        });
        PodProfiles.Release release = load().find("sample-tee-v1");
        assertThat(release.profile().areaFor("s").sourceStatus()).isEqualTo(PodProfiles.Verification.VERIFIED);
        assertThat(release.profile().areaFor(" S ").maxHeightMm()).isEqualByComparingTo("350.5");
        assertThat(release.profile().areaFor("L").sourceStatus()).isEqualTo(PodProfiles.Verification.UNVERIFIED);
        assertThat(release.profile().areaFor("XL").sourceStatus()).isEqualTo(PodProfiles.Verification.UNVERIFIED);
        assertThat(release.profile().areaFor("XXL")).isNull();
        assertThat(release.profile().verified()).isFalse();
        assertThat(release.profile().areaFor("M").verifiedBy()).isEqualTo("Operator A");
        assertThat(release.profile().areaFor("M").verifiedAt()).isEqualTo("2026-11-20");
    }

    @Test
    void theStoredSha256IsTheOneThePodProfilesLoaderComputesAndTheFileUsesLfEndings() throws Exception {
        var items = importer.importCsv(csv(HEADER, "Sample Tee,,280,350,12,BLK-TS-01,Operator A,2026-11-20"), out(),
                new PodProfileImporter.Options(null, null, List.of("text"), null, false));

        String content = Files.readString(out().resolve("sample-tee-v1.json"), StandardCharsets.UTF_8);
        assertThat(content).doesNotContain("\r").endsWith("}\n");
        assertThat(items.get(0).sha256()).isEqualTo(ApiKeyHasher.sha256Hex(content));
        assertThat(load().find("sample-tee-v1").sha256()).isEqualTo(items.get(0).sha256());
        var area = load().find("sample-tee-v1").profile().areaFor("anything");
        assertThat(area.maxChars()).isEqualTo(12);
        assertThat(area.variant()).isNull();
        assertThat(load().find("sample-tee-v1").profile().personalizationFields()).containsExactly("text");
    }

    @Test
    void aNewVersionIsAddedAndTheOldOneIsKeptByteForByte() throws Exception {
        PodProfileImporter.Options options = new PodProfileImporter.Options(null, null, null, null, false);
        importer.importCsv(csv(HEADER, "Sample Mug,,90,95,,MUG-01,Operator A,2026-11-20"), out(), options);
        byte[] first = Files.readAllBytes(out().resolve("sample-mug-v1.json"));

        var second = importer.importCsv(csv(HEADER, "Sample Mug,,92,95,,MUG-01,Operator B,2026-11-24"), out(), options);

        assertThat(second.get(0).key()).isEqualTo("sample-mug-v2");
        assertThat(written()).containsExactly("sample-mug-v1.json", "sample-mug-v2.json");
        assertThat(Files.readAllBytes(out().resolve("sample-mug-v1.json"))).isEqualTo(first);
        assertThat(load().groups()).singleElement().satisfies(group ->
                assertThat(group.versions()).extracting(PodProfiles.Profile::version).containsExactly(2, 1));
    }

    @Test
    void importingTheSameDataAgainWritesNothing() throws Exception {
        PodProfileImporter.Options options = new PodProfileImporter.Options(null, null, null, null, false);
        Path file = csv(HEADER, "Sample Mug,,90.0,95,,MUG-01,Operator A,2026-11-20");
        importer.importCsv(file, out(), options);

        var again = importer.importCsv(file, out(), options);

        assertThat(again).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(PodProfileImporter.Outcome.UNCHANGED);
            assertThat(item.key()).isEqualTo("sample-mug-v1");
        });
        assertThat(written()).containsExactly("sample-mug-v1.json");
    }

    @Test
    void aRepeatedVersionIsRefusedAndNothingIsOverwritten() throws Exception {
        importer.importCsv(csv(HEADER, "Sample Mug,,90,95,,MUG-01,Operator A,2026-11-20"), out(), PodProfileImporter.Options.NONE);
        byte[] first = Files.readAllBytes(out().resolve("sample-mug-v1.json"));
        Path changed = csv(HEADER, "Sample Mug,,99,95,,MUG-01,Operator A,2026-11-20");

        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(changed, out(),
                        new PodProfileImporter.Options(1, null, null, null, false)))
                .withMessageContaining("sample-mug-v1 already exists").withMessageContaining("immutable");
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(changed, out(),
                new PodProfileImporter.Options(0, null, null, null, false)));
        assertThat(Files.readAllBytes(out().resolve("sample-mug-v1.json"))).isEqualTo(first);
        assertThat(written()).containsExactly("sample-mug-v1.json");

        // A shipped version is as final as an imported one.
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(
                        csv(HEADER, "T-Shirt,,280,350,,BLK-TS-01,Operator A,2026-11-20"), out(),
                        new PodProfileImporter.Options(1, null, null, null, false)))
                .withMessageContaining("t-shirt-v1 already exists");
    }

    @Test
    void aProductTypeWithAShippedProfileGetsTheNextVersionAndKeepsItsFields() throws Exception {
        var items = importer.importCsv(csv(HEADER,
                "T-Shirt,S,280,350,,BLK-TS-01,Operator A,2026-11-20",
                "T-Shirt,M,300,380,,BLK-TS-02,Operator A,2026-11-20"), out(), PodProfileImporter.Options.NONE);

        assertThat(items.get(0).key()).isEqualTo("t-shirt-v2");
        var profile = load().find("t-shirt-v2").profile();
        assertThat(profile.variationRules()).containsExactly("sku", "size", "color");
        assertThat(profile.marketplaces()).containsExactly(EditorMarketplace.ETSY, EditorMarketplace.AMAZON);
        assertThat(profile.verified()).isTrue();
    }

    @Test
    void variantsNeedASizeRuleForAProductTypeThatHasNone() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(
                        csv(HEADER, "Sample Hat,S,100,100,,HAT-01,Operator A,2026-11-20"), out(), PodProfileImporter.Options.NONE))
                .withMessageContaining("--variation-rules");
        assertThat(written()).isEmpty();
    }

    @Test
    void aFileWithAMissingColumnIsRefusedWhole() throws Exception {
        Path file = csv("product_type,variant,print_area_width_mm,print_area_height_mm,verified_by",
                "Sample Mug,,90,95,Operator A");

        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(file, out(), PodProfileImporter.Options.NONE))
                .withMessage("Missing columns: source_ref, verified_at.");
        assertThat(written()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(
                        csv("product_type,print_area_width_mm,print_area_height_mm,source_ref,verified_by,verified_at"),
                        out(), PodProfileImporter.Options.NONE))
                .withMessageContaining("variant (or size)");
    }

    @Test
    void sizeIsAcceptedForVariantButNotBothAndAnUnknownColumnIsRefused() throws Exception {
        importer.importCsv(csv("product_type,size,print_area_width_mm,print_area_height_mm,source_ref,verified_by,verified_at",
                "Sample Mug,,90,95,MUG-01,Operator A,2026-11-20"), out(), PodProfileImporter.Options.NONE);
        assertThat(written()).containsExactly("sample-mug-v1.json");

        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(csv(HEADER + ",size",
                "Sample Cup,,90,95,,CUP-01,Operator A,2026-11-20,M"), out(), PodProfileImporter.Options.NONE))
                .withMessageContaining("not both");
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(csv(HEADER + ",max_char",
                "Sample Cup,,90,95,,CUP-01,Operator A,2026-11-20,9"), out(), PodProfileImporter.Options.NONE))
                .withMessageContaining("Unknown column 'max_char'");
    }

    @Test
    void aNegativeOrZeroOrMalformedDimensionRefusesTheFileAndNamesTheRow() throws Exception {
        for (String bad : new String[] {"-5", "0", "abc", "1,5", "12.3456"}) {
            Path file = csv(HEADER,
                    "Sample Mug,A,90,95,,MUG-01,Operator A,2026-11-20",
                    "Sample Mug,B," + (bad.contains(",") ? "\"" + bad + "\"" : bad) + ",95,,MUG-01,Operator A,2026-11-20");
            assertThatIllegalArgumentException().as(bad)
                    .isThrownBy(() -> importer.importCsv(file, out(), new PodProfileImporter.Options(null,
                            List.of("sku", "size"), null, null, false)))
                    .withMessageContaining("Row 3").withMessageContaining("print_area_width_mm");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(
                        csv(HEADER, "Sample Mug,,90,-1,,MUG-01,Operator A,2026-11-20"), out(), PodProfileImporter.Options.NONE))
                .withMessageContaining("cannot be negative");
        assertThat(written()).isEmpty();
    }

    @Test
    void aBadDateCharacterLimitOrRepeatedVariantRefusesTheFile() throws Exception {
        PodProfileImporter.Options sizes = new PodProfileImporter.Options(null, List.of("sku", "size"), null, null, false);
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(
                        csv(HEADER, "Sample Mug,,90,95,,MUG-01,Operator A,20/11/2026"), out(), sizes))
                .withMessageContaining("yyyy-MM-dd");
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(
                        csv(HEADER, "Sample Mug,,90,95,0,MUG-01,Operator A,2026-11-20"), out(), sizes))
                .withMessageContaining("max_chars");
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(csv(HEADER,
                        "Sample Mug,S,90,95,,MUG-01,Operator A,2026-11-20",
                        "Sample Mug, s ,91,95,,MUG-01,Operator A,2026-11-20"), out(), sizes))
                .withMessageContaining("Row 3 repeats the variant of row 2");
        assertThat(written()).isEmpty();
    }

    @Test
    void severalProductTypesBecomeSeveralProfilesAndOneBadRowWritesNone() throws Exception {
        PodProfileImporter.Options sizes = new PodProfileImporter.Options(null, List.of("sku", "size"), null, null, false);
        var items = importer.importCsv(csv(HEADER,
                "Sample Mug,,90,95,,MUG-01,Operator A,2026-11-20",
                "Sample Cap,M,60,50,,CAP-01,Operator A,2026-11-20"), out(), sizes);
        assertThat(items).extracting(PodProfileImporter.Item::key).containsExactly("sample-mug-v1", "sample-cap-v1");

        Path broken = csv(HEADER,
                "Sample Plate,,200,200,,PLT-01,Operator A,2026-11-20",
                "Sample Bowl,,-1,50,,BWL-01,Operator A,2026-11-20");
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(broken, out(), sizes));
        assertThat(written()).containsExactly("sample-cap-v1.json", "sample-mug-v1.json");
        assertThatIllegalArgumentException().isThrownBy(() -> importer.importCsv(
                        csv(HEADER, "Sample Mug,,1,1,,X,Y,2026-11-20", "Sample Cap,M,1,1,,X,Y,2026-11-20"), out(),
                        new PodProfileImporter.Options(5, List.of("sku", "size"), null, null, false)))
                .withMessageContaining("--version applies to one product type");
    }

    @Test
    void theSampleInTheDocsImportsAsTheDocumentationSays() throws Exception {
        var items = importer.importCsv(Path.of("..", "docs", "samples", "pod-profile-spec-sample.csv"), out(),
                PodProfileImporter.Options.NONE);

        assertThat(items).extracting(PodProfileImporter.Item::key).containsExactly("t-shirt-v2", "personalized-ornament-v2");
        assertThat(items).extracting(PodProfileImporter.Item::verified, PodProfileImporter.Item::unverified)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, 1), org.assertj.core.groups.Tuple.tuple(1, 0));
        assertThat(load().find("t-shirt-v2").profile().verified()).isFalse();
        var ornament = load().find("personalized-ornament-v2").profile();
        assertThat(ornament.verified()).isTrue();
        assertThat(ornament.areaFor("anything").maxChars()).isEqualTo(12);
        assertThat(ornament.personalizationFields()).containsExactly("text");
    }

    @Test
    void aDryRunReportsTheFilesAndWritesNone() throws Exception {
        var items = importer.importCsv(csv(HEADER, "Sample Mug,,90,95,,MUG-01,Operator A,2026-11-20"), out(),
                new PodProfileImporter.Options(null, null, null, null, true));

        assertThat(items.get(0).outcome()).isEqualTo(PodProfileImporter.Outcome.DRY_RUN);
        assertThat(items.get(0).sha256()).hasSize(64);
        assertThat(written()).isEmpty();
    }

    @Test
    void aByteOrderMarkBlankLinesAndQuotedValuesAreRead() throws Exception {
        Path file = dir.resolve("bom.csv");
        Files.writeString(file, "﻿" + HEADER + "\n\n\"Sample, Mug\",,90,95,,\"MUG, 01\",Operator A,2026-11-20\n,,,,,,,\n",
                StandardCharsets.UTF_8);

        importer.importCsv(file, out(), PodProfileImporter.Options.NONE);

        assertThat(written()).containsExactly("sample-mug-v1.json");
        assertThat(load().find("sample-mug-v1").profile().areas().get(0).sourceReference()).isEqualTo("MUG, 01");
    }
}
