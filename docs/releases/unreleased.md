# Unreleased changes

Changes on `main` since v0.8.0 (see [v0.8.0.md](v0.8.0.md)) that have not shipped yet: the console in English and Vietnamese
(ADR-028). It changes no API response, request, status, header or migration.

Every pull request that changes the API runs the compatibility check (`./mvnw -B -pl contract-tools verify`, ADR-022) and copies the
additive and non-breaking lines of `contract-tools/target/contract/compatibility-report.md` here, with a short section for the
change. When the next version is cut, move this file to `docs/releases/v<version>.md` and start a new one.

## What is new

### The console in English and Vietnamese (ADR-028)

Every page of the optional web console and its sign-in e-mail now exist in English and Vietnamese, for the Vietnamese sellers
who read the findings. **No API response, request, status, header or migration changes**; the OpenAPI document and the
compatibility report are unchanged by it, and a deployment that does nothing shows the same English pages as before.

- **Language.** `LISTING_GUARD_CONSOLE_DEFAULT_LOCALE` (`en` by default, `vi` for a Vietnamese deployment; any other value stops
  the start-up) is the language of a page whose reader has not chosen one. A reader switches with the links in the corner of every
  page; the choice is the cookie `lg_lang` (the code only, `HttpOnly`, `SameSite=Lax`). The browser's `Accept-Language` is not read,
  and the API's own answers do not change language.
- **Texts.** No sentence is written in a template or a script any more: they are keys of `messages.properties` and
  `messages_vi.properties`. A test fails on a key missing from one file, on a key nothing uses, and on a sentence written in a page.
  This includes the Cloudflare Access sign-in text of ADR-027.
- **Findings.** Rule ids, fields, evidence, versions and the message, suggestion and fix of each finding are shown exactly as the
  API returns them. Under each finding the page adds one sentence saying what its rule checks, from `rules.properties` and
  `rules_vi.properties` (an English sentence stands in for a missing Vietnamese one). A test fails on a rule of the registered
  rulesets with no sentence, so a new rule must add its two sentences.
- **Trademark disclaimer.** The ruleset's own wording (it is Vietnamese) is always printed unchanged. An English page puts an
  English statement above it and labels the ruleset's text as the original.
- **For the maintainer to review:** the Vietnamese sentences, the rule explanations and the English disclaimer statement were
  written for this change and have not been reviewed by a native reader or a lawyer (ADR-028, Open questions).
