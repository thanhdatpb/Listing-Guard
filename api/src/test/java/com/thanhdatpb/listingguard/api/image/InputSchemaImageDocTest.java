package com.thanhdatpb.listingguard.api.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.image.ImageRole;
import com.thanhdatpb.listingguard.image.ImageSource;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * docs/input-schema.md, "Image checks", is a public contract. This test reads its example and its field tables from the file
 * and holds them to the real reader, so the page cannot drift from the code unnoticed.
 */
class InputSchemaImageDocTest {

    private static final Path DOC = Path.of("..", "docs", "input-schema.md");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String section() throws IOException {
        assertThat(DOC).as("docs/input-schema.md is missing").exists();
        String text = Files.readString(DOC);
        int start = text.indexOf("## Image checks");
        assertThat(start).as("the 'Image checks' section").isNotNegative();
        int end = text.indexOf("\n## ", start + 1);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private static List<String> documentedFields() throws IOException {
        List<String> fields = new ArrayList<>();
        Matcher row = Pattern.compile("^\\| `([a-z_]+)` \\|", Pattern.MULTILINE).matcher(section());
        while (row.find()) {
            fields.add(row.group(1));
        }
        return fields.stream().sorted().toList();
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void theExampleIsReadByTheStrictReader() throws IOException {
        Matcher block = Pattern.compile("```json image-metadata\\R(.*?)\\R```", Pattern.DOTALL).matcher(section());
        assertThat(block.find()).as("example 'image-metadata' in docs/input-schema.md").isTrue();

        ImageCheckMetadata metadata = ImageCheckMetadata.parse(block.group(1), MAPPER);

        assertThat(metadata.marketplace()).isEqualTo("AMAZON");
        assertThat(metadata.profile()).isEqualTo("default");
        assertThat(metadata.parsedImages()).extracting(ImageCheckMetadata.Parsed::role)
                .containsExactly(ImageRole.MAIN, ImageRole.ADDITIONAL);
        assertThat(metadata.parsedImages()).extracting(ImageCheckMetadata.Parsed::source)
                .as("an omitted source is UNKNOWN").containsExactly(ImageSource.SELLER, ImageSource.UNKNOWN);
    }

    @Test
    void everyDocumentedFieldIsAMemberOfTheReaderAndEveryMemberIsDocumented() throws IOException {
        List<String> expected = new ArrayList<>(List.of("metadata", "images"));
        expected.addAll(components(ImageCheckMetadata.class));
        expected.addAll(components(ImageCheckMetadata.ImageMeta.class));

        assertThat(documentedFields()).isEqualTo(expected.stream().sorted().toList());
    }

    @Test
    void theDocumentedNamesAreTheEnumsOfTheChecker() throws IOException {
        String section = section();
        for (ImageRole role : ImageRole.values()) {
            assertThat(section).contains("`" + role.name() + "`");
        }
        for (ImageSource source : ImageSource.values()) {
            assertThat(section).contains("`" + source.name() + "`");
        }
    }
}
