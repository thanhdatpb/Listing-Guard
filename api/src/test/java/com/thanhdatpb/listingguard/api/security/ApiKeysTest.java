package com.thanhdatpb.listingguard.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiKeysTest {

    private static final String KEY_A = "a".repeat(32);
    private static final String KEY_B = "b".repeat(40) + "-._~+/=";

    @Test
    void severalKeysAreValidAtTheSameTimeForRotation() {
        ApiKeys keys = ApiKeys.parse(List.of("preview-old:" + KEY_A, "preview-new:" + KEY_B));

        assertThat(keys.authenticate(KEY_A)).contains("preview-old");
        assertThat(keys.authenticate(KEY_B)).contains("preview-new");
        assertThat(keys.names()).containsExactly("preview-old", "preview-new");
    }

    @Test
    void aWrongOrMissingKeyDoesNotAuthenticate() {
        ApiKeys keys = ApiKeys.parse(List.of("preview:" + KEY_A));

        assertThat(keys.authenticate("x".repeat(32))).isEmpty();
        assertThat(keys.authenticate(KEY_A + "x")).isEmpty();
        assertThat(keys.authenticate(KEY_A.substring(1))).isEmpty();
        assertThat(keys.authenticate("")).isEmpty();
        assertThat(keys.authenticate(null)).isEmpty();
    }

    @Test
    void blankEntriesAreIgnored() {
        assertThat(ApiKeys.parse(List.of("", "  ", "preview:" + KEY_A)).names()).containsExactly("preview");
    }

    @Test
    void noKeyAtAllIsAnError() {
        assertThatThrownBy(() -> ApiKeys.parse(List.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No API keys configured").hasMessageContaining("LISTING_GUARD_API_KEYS");
        assertThatThrownBy(() -> ApiKeys.parse(List.of(" "))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyShorterThan32CharactersIsRejectedWithoutRepeatingIt() {
        String shortKey = "tooShortSecret12345678901234567";
        assertThat(shortKey).hasSize(31);

        assertThatThrownBy(() -> ApiKeys.parse(List.of("preview:" + shortKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry #1")
                .hasMessageContaining("'preview'")
                .hasMessageContaining("shorter than 32")
                .hasMessageNotContaining(shortKey);
    }

    @Test
    void malformedEntriesAreRejectedWithoutRepeatingTheKey() {
        String secret = "s3cret-" + "x".repeat(40);

        assertThatThrownBy(() -> ApiKeys.parse(List.of(secret)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no ':'").hasMessageNotContaining(secret);
        assertThatThrownBy(() -> ApiKeys.parse(List.of("Bad Name:" + secret)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("name is not valid").hasMessageNotContaining(secret);
        assertThatThrownBy(() -> ApiKeys.parse(List.of("preview:" + secret + " with space")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed").hasMessageNotContaining(secret);
        assertThatThrownBy(() -> ApiKeys.parse(List.of("preview:" + KEY_A, "second-entry-has-no-colon")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("entry #2");
    }

    @Test
    void duplicateNamesAndDuplicateKeysAreRejected() {
        assertThatThrownBy(() -> ApiKeys.parse(List.of("preview:" + KEY_A, "preview:" + KEY_B)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("configured twice");
        assertThatThrownBy(() -> ApiKeys.parse(List.of("one:" + KEY_A, "two:" + KEY_A)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("same as another");
    }
}
