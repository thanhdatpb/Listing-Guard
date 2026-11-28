package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who the listing is for, by axes: on the {@code parent} axis a listing is for {@code mom} or for {@code dad}; on {@code gender} for
 * {@code him} or {@code her}. The axes, their values and every spelling of a value are the ruleset's ({@code axes}: axis, then value, then
 * words). The family of a mention is its axis, so two values of one axis conflict and values of different axes never do. Only whole words and
 * phrases are read; a pronoun alone ({@code her}) is not a spelling, because it is mostly not a recipient.
 */
public final class RecipientFacts implements FactExtractor {

    private record Form(String axis, String value, Pattern pattern) {
    }

    private final List<Form> forms;
    private final Ambiguity ambiguity;

    private RecipientFacts(List<Form> forms, Ambiguity ambiguity) {
        this.forms = forms;
        this.ambiguity = ambiguity;
    }

    /** Reads {@code axes} and the ambiguity keys of {@code settings}. */
    public static RecipientFacts from(RuleSettings settings) {
        Object raw = settings.params().get("axes");
        if (!(raw instanceof Map<?, ?> axes) || axes.isEmpty()) {
            throw new RulesetException("Rule '" + settings.ruleId() + "' is missing required key 'axes'");
        }
        List<Form> forms = new ArrayList<>();
        for (Map.Entry<?, ?> axis : axes.entrySet()) {
            if (!(axis.getKey() instanceof String axisName) || !(axis.getValue() instanceof Map<?, ?> values) || values.size() < 2) {
                throw new RulesetException("Rule '" + settings.ruleId() + "': an axis needs a name and at least two values");
            }
            for (Map.Entry<?, ?> value : values.entrySet()) {
                if (!(value.getKey() instanceof String valueName) || !(value.getValue() instanceof List<?> words) || words.isEmpty()
                        || !words.stream().allMatch(word -> word instanceof String text && !text.isBlank())) {
                    throw new RulesetException("Rule '" + settings.ruleId() + "': axis '" + axisName + "' value must be a list of words");
                }
                String alternation = words.stream().map(w -> Regexes.phrase((String) w)).collect(java.util.stream.Collectors.joining("|"));
                forms.add(new Form(axisName, valueName, Regexes.compile(settings.ruleId(), alternation, true)));
            }
        }
        return new RecipientFacts(List.copyOf(forms), Ambiguity.from(settings));
    }

    @Override
    public List<Mention> extract(String text) {
        // a typographic apostrophe is the same letter to the seller; folding keeps every offset
        String folded = text.replace('’', '\'');
        List<Mention> mentions = new ArrayList<>();
        for (Form form : forms) {
            Matcher matcher = form.pattern().matcher(folded);
            while (matcher.find()) {
                if (!ambiguity.around(folded, matcher.start(), matcher.end())) {
                    mentions.add(new Mention(form.axis(), form.value(), text.substring(matcher.start(), matcher.end()), matcher.start(),
                            matcher.end()));
                }
            }
        }
        mentions.sort(Comparator.comparingInt(Mention::start));
        return mentions;
    }
}
