package com.thanhdatpb.listingguard.api.workspace.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleLocales;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleProperties;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.AlertRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tells people about the new risks an audit found, by e-mail (ADR-061). After a run it takes the new risks nobody was told about, in the
 * client of that run and no other, and sends each person at most one summary: the person responsible for a listing gets their listings,
 * every ADMIN of the client gets all of them with how many each seller account has. A person who turned the e-mail off gets none, and a
 * person who already got {@code daily-cap} e-mails today (UTC) gets none until tomorrow; what they were not told stays untold, so the next
 * summary includes it. A listing is marked as told only after an e-mail about it was sent, so a failed or crashed send is tried again, and
 * a crash between the send and the mark can send one e-mail twice.
 */
@Service
@AlertsEnabled
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AuditStore store;
    private final ConsoleAccountRepository accounts;
    private final AlertMailer mailer;
    private final AlertProperties properties;
    private final ConsoleProperties console;
    private final ConsoleMessages messages;
    private final ConsoleLocales locales;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AlertService(AuditStore store, ConsoleAccountRepository accounts, AlertMailer mailer, AlertProperties properties,
                        ConsoleProperties console, ConsoleMessages messages, ConsoleLocales locales, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.mailer = mailer;
        this.properties = properties;
        this.console = console;
        this.messages = messages;
        this.locales = locales;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * @param runId the run that found them, kept with the e-mail's log row; null when a save found them
     * @return how many e-mails were sent
     */
    public int send(UUID clientId, UUID runId) {
        List<AlertRow> rows = store.unalerted(clientId);
        if (rows.isEmpty()) {
            return 0;
        }
        List<ConsoleAccount> people = accounts.findByClientIdOrderByEmail(clientId);
        Set<UUID> off = store.emailDisabled(clientId);
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Locale locale = locales.defaultLocale();
        Map<UUID, String> fields = store.fieldsOf(clientId, rows.stream().map(AlertRow::listingId).toList());
        Map<UUID, Map<String, String>> texts = new LinkedHashMap<>();
        fields.forEach((id, json) -> {
            try {
                texts.put(id, FieldTexts.of(mapper.readTree(json)));
            } catch (java.io.IOException e) {
                texts.put(id, Map.of());
            }
        });
        String base = console.baseUrl().replaceAll("/+$", "");
        Set<UUID> told = new LinkedHashSet<>();
        int sent = 0;
        for (ConsoleAccount person : people) {
            boolean admin = person.getRole() == ConsoleRole.ADMIN;
            List<AlertRow> mine = rows.stream().filter(r -> admin || person.getId().equals(r.ownerId())).toList();
            if (mine.isEmpty() || off.contains(person.getId())
                    || store.sentOn(clientId, person.getId(), today) >= properties.dailyCap()) {
                continue;
            }
            List<AlertDigest.Item> items = new ArrayList<>();
            for (AlertRow row : mine) {
                String text = texts.getOrDefault(row.listingId(), Map.of()).get(row.field());
                items.add(new AlertDigest.Item(row.listingId(), row.sku(), row.accountName(), row.field(), row.mark(), row.level(),
                        AlertExcerpt.of(text, row.spanStart(), row.spanEnd(), properties.excerptChars())));
            }
            AlertDigest.Digest digest = AlertDigest.compose(messages, locale, admin, items, properties.maxRows(),
                    base.isEmpty() ? "" : base + "/console/workspace?check=NEW_RISK", base.isEmpty() ? "" : base + "/console/workspace/audit");
            try {
                mailer.send(person.getEmail(), digest.subject(), digest.body());
            } catch (RuntimeException e) {
                // only the kind of failure is logged: a mail server's message can carry the address or the text
                log.warn("The alert e-mail to account {} was not sent ({})", person.getId(), e.getClass().getSimpleName());
                continue;
            }
            store.logSent(clientId, person.getId(), runId, today, digest.listings());
            mine.forEach(r -> told.add(r.listingId()));
            sent++;
        }
        store.markAlerted(clientId, told, clock.instant());
        log.info("Alert e-mails of client {}: {} sent for {} listings", clientId, sent, told.size());
        return sent;
    }
}
