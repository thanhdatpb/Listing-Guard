package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Mapping;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetFormat;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the templates of seller accounts (ADR-059). Only an ADMIN does it. An Excel file is kept whole, byte for byte, with its SHA-256;
 * for every format the rows above the first data row are kept verbatim and the columns keep their order. A file whose field-name row is the
 * current version's creates nothing; a different row creates the next version and the older one stays for the listings that use it.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    /** The original file of a template, when it is an Excel file. */
    public record SourceFile(SheetFormat format, String fileName, byte[] bytes) {
    }

    public record Created(Template template, boolean created) {
    }

    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final EtsyFields etsy;
    private final StandardTargets targets;
    private final WorkspaceProperties properties;

    public TemplateService(WorkspaceAccounts accounts, WorkspaceTemplates templates, WorkspaceListings listings, EtsyFields etsy,
                           StandardTargets targets, WorkspaceProperties properties) {
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.etsy = etsy;
        this.targets = targets;
        this.properties = properties;
    }

    /** The targets a column of a sheet of this kind may be given, in the order the page offers them. */
    public List<String> allowedTargets(TemplateKind kind) {
        return switch (kind) {
            case AMAZON_BULK -> targets.of(Marketplace.AMAZON).stream().map(StandardTargets.Target::key).toList();
            case AMAZON_CUSTOM -> List.of("sku");
            case ETSY_FIELDS -> {
                List<String> keys = new ArrayList<>(etsy.fields());
                keys.add("owner");
                yield keys;
            }
        };
    }

    /** The Etsy template of an account: the fixed fields, made once. */
    @Transactional
    public Template ensureEtsy(UUID clientId, UUID accountId) {
        return templates.current(clientId, accountId, TemplateKind.ETSY_FIELDS).orElseGet(() -> {
            listings.lockAccount(accountId);
            return templates.current(clientId, accountId, TemplateKind.ETSY_FIELDS).orElseGet(() -> {
                List<TemplateColumn> columns = new ArrayList<>();
                Map<String, String> identity = new LinkedHashMap<>();
                for (int i = 0; i < etsy.fields().size(); i++) {
                    String field = etsy.fields().get(i);
                    columns.add(new TemplateColumn(i, field, field));
                    identity.put(field, field);
                }
                Template template = new Template(null, clientId, accountId, TemplateKind.ETSY_FIELDS, 1, "BUILTIN", null, null, null, 1, 2,
                        List.of(etsy.fields()), columns, new Mapping(identity, Map.of(), Map.of()), WorkspaceTemplates.headerHash(columns),
                        true, null);
                return templates.insert(template, null);
            });
        });
    }

    /**
     * Makes the template of an Amazon sheet from a file the ADMIN confirmed, or reports that the current version already has this header.
     * {@code targets} gives each column (zero-based) its standard field; the mapping is kept with the version.
     */
    @Transactional
    public Created createFromFile(Actor actor, UUID accountId, TemplateKind kind, SheetGrid grid, int fieldRow, int firstDataRow,
                                  SourceFile file, Map<Integer, String> targets, Map<String, String> separators) {
        if (!actor.admin()) {
            throw new WorkspaceException("forbidden");
        }
        if (kind == TemplateKind.ETSY_FIELDS) {
            return new Created(ensureEtsy(actor.clientId(), accountId), false);
        }
        SellerAccount account = accounts.find(actor.clientId(), accountId).orElseThrow(() -> new WorkspaceException("account.unknown"));
        if (account.marketplace() != kind.marketplace()) {
            throw new WorkspaceException("kind.marketplace");
        }
        listings.lockAccount(accountId);
        List<String> fieldCells = grid.row(fieldRow);
        int width = trimmedWidth(fieldCells);
        if (width == 0) {
            throw new WorkspaceException("header.empty");
        }
        if (firstDataRow <= fieldRow) {
            throw new WorkspaceException("rows.order");
        }
        List<TemplateColumn> columns = WorkspaceTemplates.columnsOf(fieldCells, width);
        Mapping mapping = mapping(kind, columns, targets, separators);
        String hash = WorkspaceTemplates.headerHash(columns);
        Optional<Template> current = templates.current(actor.clientId(), accountId, kind);
        if (current.isPresent() && current.get().headerHash().equals(hash)) {
            templates.updateMapping(actor.clientId(), current.get().id(), mapping);
            return new Created(current.get().withMapping(mapping), false);
        }
        return new Created(insertVersion(actor.clientId(), accountId, kind, grid, columns, hash, fieldRow, firstDataRow, file, mapping), true);
    }

    /** Inserts the next version from an already resolved header. The caller holds the account lock. */
    Template insertVersion(UUID clientId, UUID accountId, TemplateKind kind, SheetGrid grid, List<TemplateColumn> columns, String hash,
                           int fieldRow, int firstDataRow, SourceFile file, Mapping mapping) {
        byte[] stored = null;
        String sheetName = null;
        if (file.format().isExcel()) {
            if (file.bytes().length > properties.maxTemplateBytes().toBytes()) {
                throw new WorkspaceException("template.too_large");
            }
            stored = file.bytes();
            sheetName = grid.sheetName();
        }
        List<List<String>> headerRows = new ArrayList<>();
        for (int r = 1; r < firstDataRow; r++) {
            headerRows.add(grid.row(r));
        }
        String name = file.fileName() == null ? null : file.fileName().length() > 255 ? file.fileName().substring(0, 255) : file.fileName();
        Template template = new Template(null, clientId, accountId, kind, 1, file.format().name(), name,
                stored == null ? null : WorkspaceJson.sha256(stored), sheetName, fieldRow, firstDataRow, headerRows, columns, mapping, hash,
                true, null);
        Template saved = templates.insert(template, stored);
        log.info("Workspace template version {} of kind {} created for account {} of client {}", saved.version(), kind, accountId, clientId);
        return saved;
    }

    /** The mapping of a column list: every non-empty target must be allowed and a target that takes one column may be given once. */
    Mapping mapping(TemplateKind kind, List<TemplateColumn> columns, Map<Integer, String> chosen, Map<String, String> separators) {
        List<String> allowed = allowedTargets(kind);
        Map<String, String> byKey = new LinkedHashMap<>();
        Map<String, Integer> uses = new LinkedHashMap<>();
        for (TemplateColumn column : columns) {
            String target = chosen.get(column.index());
            if (target == null || target.isBlank()) {
                continue;
            }
            if (!allowed.contains(target)) {
                throw new WorkspaceException("mapping.target.unknown");
            }
            uses.merge(target, 1, Integer::sum);
            byKey.put(column.key(), target);
        }
        for (Map.Entry<String, Integer> use : uses.entrySet()) {
            Marketplace marketplace = kind.marketplace();
            boolean multiple = targets.find(marketplace, use.getKey()).map(StandardTargets.Target::multiple).orElse(false);
            if (use.getValue() > 1 && !multiple) {
                throw new WorkspaceException("mapping.target.duplicate");
            }
        }
        if (!uses.containsKey("sku")) {
            throw new WorkspaceException("mapping.sku.required");
        }
        Map<String, String> kept = new LinkedHashMap<>();
        separators.forEach((target, separator) -> {
            if (targets.find(kind.marketplace(), target).filter(t -> t.kind() == Kind.LIST).isPresent()
                    && targets.separator(separator).isPresent()) {
                kept.put(target, separator);
            }
        });
        return new Mapping(byKey, kept, Map.of());
    }

    static int trimmedWidth(List<String> cells) {
        int width = cells.size();
        while (width > 0 && (cells.get(width - 1) == null || cells.get(width - 1).isBlank())) {
            width--;
        }
        return width;
    }
}
