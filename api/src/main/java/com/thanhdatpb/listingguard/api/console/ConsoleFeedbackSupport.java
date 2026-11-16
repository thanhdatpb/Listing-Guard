package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.feedback.FindingFeedbackService;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * What a console page needs to show the feedback widget next to its findings (ADR-034): the signed-in account and its own
 * verdicts on the findings of the page, read in one query. A page that shows stored findings calls {@link #populate} and includes
 * {@code console/feedback :: widget(${target})} next to each one.
 *
 * <p>The reviewer is the account. A signed-in user with no account row (only possible in tests) gets no widget.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleFeedbackSupport {

    /** The model attribute that holds the reviewer's own verdicts, by {@link FeedbackTarget#key()}. */
    public static final String VERDICTS = "feedbackVerdicts";

    /** The model attribute that says whether the widget is shown at all. */
    public static final String ENABLED = "feedbackEnabled";

    private final ConsoleAccountRepository accounts;
    private final FindingFeedbackService feedback;

    public ConsoleFeedbackSupport(ConsoleAccountRepository accounts, FindingFeedbackService feedback) {
        this.accounts = accounts;
        this.feedback = feedback;
    }

    /** The account of the signed-in user, which is the reviewer of the console. */
    public Optional<UUID> accountId(ConsoleUser user) {
        return accounts.findByEmail(user.email()).filter(account -> account.getClientId().equals(user.clientId()))
                .map(ConsoleAccount::getId);
    }

    public void populate(Model model, ConsoleUser user, Collection<FeedbackTarget> targets) {
        Optional<UUID> account = accountId(user);
        model.addAttribute(ENABLED, account.isPresent());
        model.addAttribute("feedbackNoteMax", FindingFeedbackService.MAX_NOTE_CHARS);
        Map<String, FindingFeedbackService.Mine> verdicts = account
                .map(id -> feedback.mine(user.clientId(), id, targets))
                .orElse(Map.of());
        model.addAttribute(VERDICTS, verdicts);
    }
}
