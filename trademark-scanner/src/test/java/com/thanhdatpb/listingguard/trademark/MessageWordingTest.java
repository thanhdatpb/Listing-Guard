package com.thanhdatpb.listingguard.trademark;

import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.color;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.place;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.sport;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ADR-009: no message, suggestion or note may say or imply that a text is safe, allowed, permitted, compliant or
 * otherwise legally valid, in English or Vietnamese. The Vietnamese phrases are compared without diacritics.
 */
class MessageWordingTest {

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\b(safe|safely|allowed|permitted|permissible|compliant|compliance|legal|legally|valid"
                    + "|approved|authorized|authorised|cleared|ok|okay)\\b"),
            Pattern.compile("\\b(an toan|duoc phep|cho phep|hop le|hop phap|tuan thu|dung quy dinh|khong vi pham"
                    + "|duoc chap nhan|chap thuan|yen tam|khong sao|khong van de|dat chuan|hop quy|duoc duyet)\\b"));

    private static String fold(String text) {
        String decomposed = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> forbiddenIn(String text) {
        String folded = fold(text);
        List<String> hits = new ArrayList<>();
        for (Pattern pattern : FORBIDDEN) {
            var matcher = pattern.matcher(folded);
            while (matcher.find()) {
                hits.add(matcher.group());
            }
        }
        return hits;
    }

    private static List<String> allTexts(TrademarkRuleset ruleset) {
        return List.of(ruleset.disclaimer(), ruleset.catalogExact().message(), ruleset.catalogExact().suggestion(),
                ruleset.catalogPhrase().message(), ruleset.catalogPhrase().suggestion(),
                ruleset.catalogFuzzy().message(), ruleset.catalogFuzzy().suggestion(), ruleset.geoSport().message(),
                ruleset.geoSport().suggestion(), ruleset.colorContainsMark().note(), ruleset.pureColor().message(),
                ruleset.pureColor().suggestion());
    }

    @Test
    void theCheckItselfRecognizesForbiddenWording() {
        assertThat(forbiddenIn("This title is safe to publish")).isNotEmpty();
        assertThat(forbiddenIn("Compliant with the policy")).isNotEmpty();
        assertThat(forbiddenIn("Nội dung này an toàn")).isNotEmpty();
        assertThat(forbiddenIn("Được phép sử dụng")).isNotEmpty();
        assertThat(forbiddenIn("Hợp lệ")).isNotEmpty();
        assertThat(forbiddenIn("Không phát hiện tham chiếu nhãn hiệu nào")).isEmpty();
    }

    @Test
    void everyConfiguredTextOfBothRulesetsIsFreeOfForbiddenWording() {
        for (TrademarkRuleset ruleset : List.of(TestRulesets.test(), TestRulesets.shipped())) {
            for (String text : allTexts(ruleset)) {
                assertThat(forbiddenIn(text)).as(ruleset.version() + ": " + text).isEmpty();
            }
        }
    }

    @Test
    void everyTextOfAGeneratedFindingIsFreeOfForbiddenWording() {
        TrademarkRuleset ruleset = TestRulesets.test();
        TrademarkScanner scanner = ScannerFixtures.scanner(ruleset, "Zorvex", "Blue Harbor Tools");
        List<String> titles = List.of("Zorvex", "Zorvx", "blue harbor tools", "Zorvex " + color(ruleset, 0),
                place(ruleset, 0) + " " + sport(ruleset, 0), color(ruleset, 0) + " " + color(ruleset, 1));

        for (String title : titles) {
            TrademarkResult result = ScannerFixtures.scanTitle(scanner, title);
            assertThat(result.findings()).as(title).isNotEmpty();
            for (TrademarkFinding finding : result.findings()) {
                assertThat(forbiddenIn(finding.message())).as(finding.message()).isEmpty();
                assertThat(forbiddenIn(finding.suggestion())).as(finding.suggestion()).isEmpty();
            }
            assertThat(forbiddenIn(result.disclaimer())).isEmpty();
        }
    }

    @Test
    void theColorFindingSaysNoReferenceWasDetectedInThatPhraseOnly() {
        TrademarkRuleset ruleset = TestRulesets.test();
        TrademarkResult result = ScannerFixtures.scanTitle(ScannerFixtures.scanner(ruleset),
                color(ruleset, 0) + " " + color(ruleset, 1));

        TrademarkFinding finding = result.findings().get(0);
        assertThat(finding.message()).contains("Không phát hiện");
        assertThat(finding.suggestion()).contains("không phải kết luận pháp lý");
    }
}
