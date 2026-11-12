package com.thanhdatpb.listingguard.input;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GenericJsonAdapterTest {
    @Test
    void streamsOnlyStandardFieldsAndEnforcesRecordLimit() throws Exception {
        var adapter = new GenericJsonAdapter("ETSY", Set.of("title", "tags"));
        byte[] json = "[{\"title\":\"Mug\",\"tags\":[\"ceramic\"],\"vendor_secret\":\"ignored\"}]"
                .getBytes(StandardCharsets.UTF_8);
        try (var reader = adapter.open(new ByteArrayInputStream(json), new ListingInputAdapter.Limits(1024, 1))) {
            var record = reader.next();
            assertThat(record.listing()).containsKeys("title", "tags").doesNotContainKey("vendor_secret");
            assertThat(reader.next()).isNull();
        }
    }
}
