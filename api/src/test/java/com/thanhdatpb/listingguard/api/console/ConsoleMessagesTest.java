package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/** How the console looks a message up (ADR-028): the language, the fallback, the arguments and the two special lookups. */
class ConsoleMessagesTest {

    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");

    private final ConsoleMessages messages = ConsoleMessagesTestSupport.messages();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static ConsoleMessages fallbackCheck() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("fallbackcheck");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new ConsoleMessages(source);
    }

    @Test
    void aMessageIsInTheLanguageAskedFor() {
        assertThat(messages.get(Locale.ENGLISH, "nav.signout")).isEqualTo("Sign out");
        assertThat(messages.get(VIETNAMESE, "nav.signout")).isEqualTo("Đăng xuất");
    }

    @Test
    void theCurrentRequestsLanguageIsTheOneUsedWhenNoneIsGiven() {
        LocaleContextHolder.setLocale(VIETNAMESE);
        assertThat(messages.get("nav.signout")).isEqualTo("Đăng xuất");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(messages.get("nav.signout")).isEqualTo("Sign out");
    }

    @Test
    void aNumberIsNeverGroupedByTheLanguageOfThePage() {
        assertThat(messages.get(Locale.ENGLISH, "keys.count", 1000, 5000)).isEqualTo("(1000 of 5000)");
        assertThat(messages.get(VIETNAMESE, "keys.count", 1000, 5000)).isEqualTo("(1000 trên 5000)");
    }

    @Test
    void anArgumentIsPrintedAsItIsEvenWhenItLooksLikeAPlaceholderOrAQuote() {
        assertThat(messages.get(Locale.ENGLISH, "editor.apply.tag.removed", "x{1}y'z")).isEqualTo("Removed the tag \"x{1}y'z\".");
    }

    @Test
    void aMessageThatDoesNotExistIsAnErrorAndNotTextOnAPage() {
        assertThatThrownBy(() -> messages.get(Locale.ENGLISH, "no.such.key")).isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void theLanguageOfTheServerDoesNotDecideWhichFileAnEnglishPageReads() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(VIETNAMESE);
            assertThat(messages.get(Locale.ENGLISH, "nav.signout")).isEqualTo("Sign out");
            assertThat(messages.get(Locale.ENGLISH, "keys.new.keyid")).isEqualTo("Key id:");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void aRuleSentenceMissingInVietnameseShowsTheEnglishOneAndOneMissingEverywhereShowsNothing() {
        ConsoleMessages check = fallbackCheck();

        assertThat(check.ruleExplanation(VIETNAMESE, "in_both")).isEqualTo("Tiếng Việt trong cả hai");
        assertThat(check.ruleExplanation(VIETNAMESE, "english_only")).isEqualTo("English only");
        assertThat(check.ruleExplanation(Locale.ENGLISH, "in_both")).isEqualTo("English in both");
        assertThat(check.ruleExplanation(VIETNAMESE, "no_such_rule")).isNull();
        assertThat(check.ruleExplanation(VIETNAMESE, null)).isNull();
        assertThat(check.ruleExplanation(VIETNAMESE, " ")).isNull();
    }

    @Test
    void theShippedRuleSentencesAreInBothLanguagesAndDifferent() {
        assertThat(messages.ruleExplanation(Locale.ENGLISH, "title_length")).startsWith("Checks that the title");
        assertThat(messages.ruleExplanation(VIETNAMESE, "title_length")).startsWith("Kiểm tra tiêu đề");
    }

    @Test
    void theRuleIdOfAFindingIsOnlyEverLookedUpUnderTheRulePrefix() {
        assertThat(messages.ruleExplanation(Locale.ENGLISH, "nav.signout")).isNull();
        assertThat(messages.ruleExplanation(Locale.ENGLISH, "../messages")).isNull();
    }

    @Test
    void theStatementAboutTheDisclaimerGoesAboveItUnlessItIsTheSameText() {
        String vietnamese = messages.get(VIETNAMESE, "trademark.disclaimer.note");
        String english = messages.get(Locale.ENGLISH, "trademark.disclaimer.note");

        LocaleContextHolder.setLocale(VIETNAMESE);
        assertThat(messages.disclaimerNote(vietnamese)).isNull();
        assertThat(messages.disclaimerNote("  " + vietnamese + " ")).isNull();
        assertThat(messages.disclaimerNote("Một disclaimer khác")).isEqualTo(vietnamese);
        assertThat(messages.disclaimerNote(null)).isEqualTo(vietnamese);

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(messages.disclaimerNote(vietnamese)).isEqualTo(english);
        assertThat(messages.disclaimerNote(english)).isNull();
    }
}
