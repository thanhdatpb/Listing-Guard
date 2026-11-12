package com.thanhdatpb.listingguard.contract;

import java.util.List;

/**
 * The result of a compatibility check as Markdown, for a person and for the release notes (ADR-022, section 4): breaking
 * changes with whether each is declared, then the additive changes, then the other non-breaking ones.
 */
public final class CompatibilityReport {

    private CompatibilityReport() {
    }

    /**
     * @param baseline         the release the current contract was compared with
     * @param openApiChanges   the changes of {@code docs/api/openapi.json}
     * @param inputChanges     the changes of {@code docs/api/input-schema.json}, or null when the baseline has no input schema
     * @param exceptions       the declared breaking changes
     */
    public static String markdown(ContractBaseline baseline, List<Change> openApiChanges, List<Change> inputChanges,
                                  BreakingChangeExceptions exceptions) {
        StringBuilder out = new StringBuilder();
        out.append("# API compatibility report\n\n");
        out.append("Current contract compared with the baseline of release `").append(baseline.version()).append("` (ADR-022).\n\n");
        section(out, "`docs/api/openapi.json`", openApiChanges, exceptions);
        if (inputChanges == null) {
            out.append("## `docs/api/input-schema.json`\n\nNot compared: release `").append(baseline.version())
                    .append("` has no input schema baseline.\n");
        } else {
            section(out, "`docs/api/input-schema.json`", inputChanges, exceptions);
        }
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<Change> changes, BreakingChangeExceptions exceptions) {
        out.append("## ").append(title).append("\n\n");
        if (changes.isEmpty()) {
            out.append("No change.\n\n");
            return;
        }
        for (ChangeClass changeClass : ChangeClass.values()) {
            List<Change> ofClass = changes.stream().filter(change -> change.changeClass() == changeClass).toList();
            out.append("### ").append(heading(changeClass)).append(" (").append(ofClass.size()).append(")\n\n");
            if (ofClass.isEmpty()) {
                out.append("None.\n\n");
                continue;
            }
            for (Change change : ofClass) {
                out.append("- `").append(change.rule().id()).append("` at `").append(change.location()).append('`');
                if (!change.detail().isEmpty()) {
                    out.append(": ").append(change.detail());
                }
                if (changeClass == ChangeClass.BREAKING) {
                    out.append(exceptions.declaring(change)
                            .map(entry -> " (declared in `docs/api/breaking-changes.yaml`, " + entry.adr() + ")")
                            .orElse(" (**not declared**)"));
                }
                out.append('\n');
            }
            out.append('\n');
        }
    }

    private static String heading(ChangeClass changeClass) {
        return switch (changeClass) {
            case BREAKING -> "Breaking";
            case ADDITIVE -> "Additive";
            case NON_BREAKING -> "Other non-breaking";
        };
    }
}
