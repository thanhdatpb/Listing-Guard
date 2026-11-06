package com.thanhdatpb.listingguard.personalization;

import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset.Template;
import com.thanhdatpb.listingguard.personalization.config.Templates;
import com.thanhdatpb.listingguard.personalization.rules.RuleContext;
import com.thanhdatpb.listingguard.trademark.Evidence;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the trademark scanner (ADR-009) on the values of a screening and reads its findings back (ADR-016). Part of
 * {@link AlgorithmVersions#RULES}.
 *
 * <p>The scanner has a closed set of input fields, and the shipped trademark ruleset scans {@code tags} item by item, each
 * item as a field of its own (so words of different items are never combined). The values are therefore handed over as the
 * items of {@code tags}, and a finding on {@code tags[i]} is read back as a finding on {@code personalization[i]}. Nothing
 * of the scanner changes. The text of a value is passed as it is; the scanner strips it, so its offsets are shifted by the
 * leading whitespace it removed to refer to the text as sent.
 *
 * <p>The buyer's text is never copied into a hit: the scanner's snippet, token and message are dropped, and the message is
 * built from this pack's own templates with the field, the catalog mark and positions.
 */
final class TrademarkAdapter {

    private static final Pattern TAG_FIELD = Pattern.compile("tags\\[(\\d+)]");

    private TrademarkAdapter() {
    }

    static TrademarkPart scan(TrademarkScanner scanner, RuleContext context) {
        List<String> texts = context.values().stream().map(PersonalizationInput.Value::text).toList();
        TrademarkResult result = scanner.scan(new TrademarkCheckInput(null, texts, List.of(), null, null, null, null, null));
        List<TrademarkHit> hits = new ArrayList<>();
        for (TrademarkFinding finding : result.findings()) {
            hits.add(hit(context, finding));
        }
        TrademarkStatus status = result.completeness() == com.thanhdatpb.listingguard.trademark.Completeness.COMPLETE
                ? TrademarkStatus.COMPLETE : TrademarkStatus.INCOMPLETE;
        return new TrademarkPart(status, result.riskLevel(), result.catalogVersion(), result.rulesetVersion(),
                result.failedRuleIds(), hits);
    }

    private static TrademarkHit hit(RuleContext context, TrademarkFinding finding) {
        Evidence evidence = finding.evidence();
        Matcher matcher = TAG_FIELD.matcher(evidence.field());
        if (!matcher.matches()) {
            throw new IllegalStateException("The trademark scanner reported a finding on an unexpected field: "
                    + evidence.field());
        }
        int index = Integer.parseInt(matcher.group(1));
        String text = context.values().get(index).text();
        int shift = text.length() - text.stripLeading().length();
        int start = evidence.start() + shift;
        int end = evidence.end() + shift;
        Template template = context.ruleset().trademark().rules().get(finding.ruleId());
        if (template == null) {
            throw new IllegalStateException("The ruleset has no wording for the trademark rule " + finding.ruleId());
        }
        Map<String, String> values = new HashMap<>();
        values.put("subject", context.subject(index));
        values.put("from", String.valueOf(text.codePointCount(0, start) + 1));
        values.put("to", String.valueOf(text.codePointCount(0, end)));
        values.put("mark", evidence.matchedMark() == null ? "" : evidence.matchedMark());
        values.put("score", evidence.score() == null ? "" : String.valueOf(Math.round(evidence.score() * 100)));
        String message = Templates.fill(template.message(), values);
        if (evidence.insideColorPhrase()) {
            message = message + " " + context.ruleset().trademark().colorPhraseNote();
        }
        return new TrademarkHit(finding.ruleId(), finding.risk(), index, context.field(index), context.label(index), start,
                end, evidence.matchType(), evidence.matchedMark(), evidence.score(), evidence.insideColorPhrase(),
                evidence.otherRuleIds(), evidence.sourceIds(), message, Templates.fill(template.suggestion(), values));
    }
}
