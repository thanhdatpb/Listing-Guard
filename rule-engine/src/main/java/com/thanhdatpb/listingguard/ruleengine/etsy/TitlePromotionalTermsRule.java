package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Flags the configured terms about price, shipping or sales in the title ("free shipping", "sale", "% off"). A term matches
 * ignoring case, as a whole word or phrase: a term that starts or ends with a letter or digit has a word boundary on that
 * side ("sale" is not found in "wholesale"), one that starts or ends with a symbol has none ("% off" is found in "20% off").
 * One finding per term found. There is no fix: dropping a phrase or replacing it is the seller's choice.
 */
public final class TitlePromotionalTermsRule implements Rule {

    public static final String ID = "title_promotional_terms";

    private static final String NOT_WORD_BEFORE = "(?<![\\p{L}\\p{Nd}])";
    private static final String NOT_WORD_AFTER = "(?![\\p{L}\\p{Nd}])";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        List<String> terms = config.rule(ID).stringListParam("terms");
        if (terms.isEmpty() || terms.stream().anyMatch(String::isBlank)) {
            throw new RulesetException("Rule '" + ID + "': key 'terms' must list at least one non-blank term");
        }
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        if (listing.title() == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (String term : config.rule(ID).stringListParam("terms")) {
            Matcher matcher = pattern(term).matcher(listing.title());
            if (matcher.find()) {
                String found = matcher.group();
                findings.add(finding(config, "title", found,
                        "Tiêu đề có cụm từ về giá, vận chuyển hoặc khuyến mãi: " + Texts.quote(found) + ".",
                        "Cân nhắc bỏ cụm từ này khỏi tiêu đề: theo hướng dẫn của Etsy, Etsy tự gắn nhãn về giá, vận chuyển và "
                                + "khuyến mãi cho sản phẩm trong kết quả tìm kiếm."));
            }
        }
        return findings;
    }

    private static Pattern pattern(String term) {
        String trimmed = term.trim();
        String body = Arrays.stream(trimmed.split("\\s+")).map(Pattern::quote).collect(Collectors.joining("\\s+"));
        String before = Character.isLetterOrDigit(trimmed.codePointAt(0)) ? NOT_WORD_BEFORE : "";
        String after = Character.isLetterOrDigit(trimmed.codePointBefore(trimmed.length())) ? NOT_WORD_AFTER : "";
        return Pattern.compile(before + body + after, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
