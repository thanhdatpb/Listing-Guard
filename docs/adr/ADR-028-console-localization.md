# ADR-028: Console localization

- Status: Proposed
- Date: 2026-11-13

## Context

The first user of the console (ADR-014, part B) is a Vietnamese seller. Everything the console prints around a finding is
English: the bar, the labels, the panels, the quota sentences, the sign-in e-mail. A seller reads the findings slowly and skips
them. The findings themselves are not the problem: the message and the suggestion of every rule, the fix description and the
trademark disclaimer already come from the rulesets in Vietnamese and are part of the API response (ADR-005, ADR-022), which this
change does not touch.

Two things in the brief did not match the repository, so they are recorded here.

- The trademark disclaimer is not English. `trademark-risk-2026-11b` (and `2026-11` before it) carries it in Vietnamese, and the
  console prints that text. What an English reader lacks is an English statement, not a Vietnamese one.
- There is no explanation of a rule id anywhere. A finding shows its rule id (`title_repeated_words`) and a Vietnamese sentence
  about this one listing, but nothing says what the rule checks in general.

## Decision

1. **Scope.** The console only: the Thymeleaf pages under `templates/console/`, the sentences the console's Java builds for a page
   (`EditorCheckService`, `EditorFixApplier`, `EditorListingMapper`, the login, API keys and report controllers), the strings of
   `static/console/*.js` and the sign-in e-mail. Not the API, the batch and health-report files, `report.html` of the job, the MCP
   server or the logs. No response of the API changes, no field is added to one, and no migration is needed.
2. **Mechanism.** Spring's `MessageSource` and `#{...}` in Thymeleaf. `messages.properties` is the English base and
   `messages_vi.properties` the Vietnamese; `rules.properties` and `rules_vi.properties` hold the rule explanations (below). Both
   basenames are configured in `application.yml` with `fallback-to-system-locale: false`, so a missing Vietnamese key falls to
   English and never to the language of the server's JVM. A template holds no text of its own; JavaScript gets its sentences from
   `data-i18n-*` attributes the template writes, and a message holds no markup (every value still goes through `th:text`). The
   English text equals what the console printed before, so a deployment on `en` shows the same pages.
3. **Choosing the language.**
   - The default is `listing-guard.console.default-locale` (`LISTING_GUARD_CONSOLE_DEFAULT_LOCALE`), `en` when unset, so a
     self-hosted deployment is unchanged; the Vietnamese deployment sets `vi` (`deploy/env.prod.example`). A value other than a
     supported language stops the start-up (`ConsoleStartupSafetyCheck`).
   - A reader changes it with the links in the corner of every page (`?lang=en`, `?lang=vi`, a `GET` that sets one cookie). The
     cookie `lg_lang` holds the code only, is `HttpOnly`, `SameSite=Lax`, `Secure` as `console.cookie-secure` says, and lasts a year.
     Anything other than a supported code is ignored. The browser's `Accept-Language` is not used: the operator decides the
     default.
   - **Not stored on the account.** The sign-in pages come before there is an account, so they need the cookie anyway; a column
     would add a migration and a second source for the same fact. If a seller later needs the choice to follow them to another
     browser, an additive column can be read before the cookie.
   - The resolver (`ConsoleLocaleResolver`) is the application's `localeResolver` but acts only on `/console/**` and `/login/**`;
     every other request gets the `AcceptHeaderLocaleResolver` it got before, so the problem details and validation messages of
     the API do not change language.
4. **What stays as the API gave it.** Rule ids, fields, evidence, versions, the codes of the enumerations (`ERROR`, `HIGH`,
   `INCOMPLETE`...), pack names, catalog marks, and the message, suggestion and fix text of a finding. They are identifiers or data,
   shown the same in both languages, and the API documents them.
5. **Rule explanations.** `rules.properties` (English) and `rules_vi.properties` hold one short sentence per rule id under the key
   `rule.<ruleId>`: what the rule checks, not a verdict on the listing and never a number of a marketplace (a number would drift from
   the ruleset; the finding carries the real one). The editor shows it under each finding and the report under each ranked rule.
   A rule id with no Vietnamese sentence shows the English one; one with neither shows nothing. A rule id is shared by the packs
   that use it (`required_fields`, `title_length`), so the sentence holds for each.
6. **The trademark disclaimer.** The text of the ruleset is always printed unchanged, because it is the legal wording. When the
   language of the page has a statement of its own in `messages*.properties` that differs from it, the page prints that statement
   first and labels the ruleset's text as the original. In English that is an English statement above the Vietnamese original; in
   Vietnamese the statement equals the ruleset's text, so only the original shows. A new ruleset version that changes the wording
   shows both until the statement is reviewed. The English statement is a plain translation made for this change and is for the
   maintainer to check.
7. **Tests.** A key of `messages.properties` is in `messages_vi.properties` and the other way round, and the same for `rules`;
   every key a template or a console class uses exists; a message with arguments is valid for `MessageFormat` (an apostrophe is
   doubled) and one without arguments has no stray `''`; every enabled rule id of the rulesets that `application.yml` registers
   has an explanation in both languages, with a short list of exceptions that must itself stay true; each page renders in `en` and
   `vi` with no key shown as `??key??`; the cookie, the `?lang` link and the default are tested; and a scan of the templates and the
   JavaScript fails on a sentence written in them.

## Consequences

- A new text in the console is a key in both files, never a literal. A new rule is an explanation in both rule files in the same
  change (CLAUDE.md says so).
- Switching language is a page load. The editor keeps no text between loads (the listing is never stored, ADR-014), so a seller who
  switches after typing starts the form again; the language is meant to be chosen once, by the default or the first click.
- The sign-in e-mail is sent in the language of the page on which the link was requested.
- A second language costs one `messages_<code>.properties`, one `rules_<code>.properties`, one entry of `ConsoleLocales.SUPPORTED`
  and its endonym; nothing else changes.

## Open questions

- Should the choice be stored on the account as well (see 3)?
- Should the Vietnamese deployment's default be set in `docker-compose.prod.yml` rather than in the operator's environment file?
- The English statement of the trademark disclaimer (6) and every Vietnamese explanation of a rule (5) are for the maintainer to
  review: the explanations describe what each rule checks from the ruleset's comments and rule classes, and say nothing about a
  marketplace's policy beyond them.
- Localizing the sentences of the API's problem details and of `report.html` is not planned.
