package com.thanhdatpb.listingguard.api.llm;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Free-tier LLM providers and the trademark context judge (ADR-036). Everything is off by default.
 *
 * @param enabled   the shared client; false means no key is read and no request is ever made
 * @param providers tried in this order; an entry without a key is skipped
 */
@ConfigurationProperties(prefix = "listing-guard.llm")
public record LlmProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue List<Provider> providers,
        @DefaultValue Cache cache,
        @DefaultValue Judge judge) {

    /**
     * @param id                  short stable name, {@code [a-z0-9_-]{1,32}}; it names the daily counter, so renaming resets it
     * @param model               a specific model version, never an alias (ADR-011 item 4)
     * @param key                 the secret, from an environment variable; never printed
     * @param listingTextAllowed  may receive a client's listing text; false until the maintainer has read the provider's terms
     * @param personalDataAllowed may receive personalization text; false for every provider unless the terms say so
     * @param jsonMode            send {@code response_format: json_object}; turn off for a vendor that rejects it
     */
    public record Provider(
            String id,
            String baseUrl,
            String model,
            @DefaultValue("") String key,
            @DefaultValue("10") int requestsPerMinute,
            @DefaultValue("100") int requestsPerDay,
            @DefaultValue("false") boolean listingTextAllowed,
            @DefaultValue("false") boolean personalDataAllowed,
            @DefaultValue("true") boolean jsonMode) {

        public boolean usable() {
            return key != null && !key.isBlank();
        }

        @Override
        public String toString() {
            return "Provider[id=" + id + ", baseUrl=" + baseUrl + ", model=" + model + ", key=" + (usable() ? "<set>" : "<empty>")
                    + ", requestsPerMinute=" + requestsPerMinute + ", requestsPerDay=" + requestsPerDay + ", listingTextAllowed="
                    + listingTextAllowed + ", personalDataAllowed=" + personalDataAllowed + ", jsonMode=" + jsonMode + "]";
        }
    }

    public record Cache(@DefaultValue("2000") int maxEntries, @DefaultValue("24h") Duration ttl) {
    }

    /**
     * @param enabled         the trademark context judge; needs {@code listing-guard.llm.enabled}
     * @param maxPerCheck     findings sent per check; the others keep the rules' level
     * @param snippetChars    characters of the field text kept on each side of the match
     * @param maxOutputTokens tokens the answer may use (a reasoning model spends part of them thinking)
     * @param timeout         per provider
     * @param promptVersion   which prompt file the judge uses: {@code trademark-judge-2026-11} (the default, the prompt of ADR-036) or
     *                        {@code trademark-judge-2026-12} (the same question with an evidence pack, ADR-051). An unknown value
     *                        stops the start; setting the default again is the rollback
     */
    public record Judge(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("8") int maxPerCheck,
            @DefaultValue("160") int snippetChars,
            @DefaultValue("600") int maxOutputTokens,
            @DefaultValue("20s") Duration timeout,
            @DefaultValue("trademark-judge-2026-11") String promptVersion) {
    }
}
