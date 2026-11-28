package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.ListingFilter;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.ListingSummary;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Page;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ChangeKind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The listings of the workspace, their history and the Amazon Custom rows (ADR-059, V57). The one place that stores listing text. Every
 * statement starts from {@code client_id = ?}; a listing of another client is simply not found. A change is guarded by the listing's
 * {@code version}: an update that finds another version changes nothing and reports it, so two people never overwrite each other.
 */
@Repository
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class WorkspaceListings {

    public static final int PAGE_SIZE = 50;

    private static final String COLUMNS = "id, client_id, account_id, template_id, sku, parent_sku, role, title, data::text AS data, "
            + "fields::text AS fields, content_hash, owner_account_id, status, source, cloned_from, version, created_at, updated_at, deleted_at";

    /** What an import needs to know of a listing that exists. */
    public record Existing(UUID id, UUID templateId, String contentHash, UUID ownerAccountId, long version, String role) {
    }

    /** One row to write: the new state of a listing, with its history entry's author. */
    public record Write(UUID id, Listing state, ChangeKind kind, UUID changedBy, long expectedVersion) {
    }

    private final JdbcTemplate jdbc;
    private final WorkspaceJson json;
    private final Clock clock;
    private final WorkspaceProperties properties;
    private final RowMapper<Listing> mapper;

    public WorkspaceListings(JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper objectMapper, Clock clock,
                             WorkspaceProperties properties) {
        this.jdbc = jdbc;
        this.json = new WorkspaceJson(objectMapper);
        this.clock = clock;
        this.properties = properties;
        this.mapper = (rs, n) -> new Listing(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getObject("template_id", UUID.class), rs.getString("sku"),
                rs.getString("parent_sku"), ListingRole.valueOf(rs.getString("role")), rs.getString("title"),
                json.stringMap(rs.getString("data")), json.tree(rs.getString("fields")), rs.getString("content_hash"),
                rs.getObject("owner_account_id", UUID.class), ListingStatus.valueOf(rs.getString("status")),
                ListingSource.valueOf(rs.getString("source")), rs.getObject("cloned_from", UUID.class), rs.getLong("version"),
                instant(rs.getObject("created_at", OffsetDateTime.class)), instant(rs.getObject("updated_at", OffsetDateTime.class)),
                instant(rs.getObject("deleted_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    public Optional<Listing> find(UUID clientId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workspace_listing WHERE id = ? AND client_id = ? AND deleted_at IS NULL", mapper, id,
                clientId).stream().findFirst();
    }

    /** The listings of an account that have these SKUs and are not deleted, by SKU. */
    public Map<String, Existing> existing(UUID clientId, UUID accountId, Collection<String> skus) {
        Map<String, Existing> found = new HashMap<>();
        List<String> all = new ArrayList<>(skus);
        for (int from = 0; from < all.size(); from += 1000) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + 1000));
            jdbc.query("SELECT id, sku, template_id, content_hash, owner_account_id, version, role FROM workspace_listing "
                    + "WHERE client_id = ? AND account_id = ? AND deleted_at IS NULL AND sku = ANY (?)", rs -> {
                found.put(rs.getString("sku"), new Existing(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                        rs.getString("content_hash"), rs.getObject("owner_account_id", UUID.class), rs.getLong("version"),
                        rs.getString("role")));
            }, clientId, accountId, chunk.toArray(String[]::new));
        }
        return found;
    }

    /** Whether any listing of the account (not deleted) has this SKU. */
    public boolean exists(UUID clientId, UUID accountId, String sku) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM workspace_listing WHERE client_id = ? AND account_id = ? "
                + "AND sku = ? AND deleted_at IS NULL)", Boolean.class, clientId, accountId, sku));
    }

    /** The SKUs among {@code skus} that a listing of the account has, so an import can tell a parent that is not in its file. */
    public java.util.Set<String> existingSkus(UUID clientId, UUID accountId, Collection<String> skus) {
        return new java.util.HashSet<>(existing(clientId, accountId, skus).keySet());
    }

    /** The SKUs of the account's listings that name one of these SKUs as their parent, so a parent row knows it has children. */
    public java.util.Set<String> parentsReferenced(UUID clientId, UUID accountId, Collection<String> skus) {
        java.util.Set<String> parents = new java.util.HashSet<>();
        List<String> all = new ArrayList<>(skus);
        for (int from = 0; from < all.size(); from += 1000) {
            jdbc.query("SELECT DISTINCT parent_sku FROM workspace_listing WHERE client_id = ? AND account_id = ? AND deleted_at IS NULL "
                    + "AND parent_sku = ANY (?)", rs -> {
                parents.add(rs.getString(1));
            }, clientId, accountId, all.subList(from, Math.min(all.size(), from + 1000)).toArray(String[]::new));
        }
        return parents;
    }

    /**
     * Inserts new listings and writes their first history entry, in one batch each. The caller holds the account's lock and a transaction.
     */
    public void insertAll(UUID clientId, List<Write> writes) {
        if (writes.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.batchUpdate("INSERT INTO workspace_listing (id, client_id, account_id, template_id, sku, parent_sku, role, title, data, fields, "
                + "content_hash, owner_account_id, status, source, cloned_from, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, 0, ?, ?)", writes, 500, (ps, w) -> {
            Listing l = w.state();
            int i = 1;
            ps.setObject(i++, w.id());
            ps.setObject(i++, clientId);
            ps.setObject(i++, l.accountId());
            ps.setObject(i++, l.templateId());
            ps.setString(i++, l.sku());
            ps.setString(i++, l.parentSku());
            ps.setString(i++, l.role().name());
            ps.setString(i++, l.title());
            ps.setString(i++, json.write(l.data()));
            ps.setString(i++, json.write(l.fields()));
            ps.setString(i++, l.contentHash());
            ps.setObject(i++, l.ownerAccountId());
            ps.setString(i++, l.status().name());
            ps.setString(i++, l.source().name());
            ps.setObject(i++, l.clonedFrom());
            ps.setTimestamp(i++, now);
            ps.setTimestamp(i, now);
        });
        writeHistory(clientId, writes, w -> 0L);
    }

    /**
     * Updates listings whose version is the one the caller read. Returns the writes that changed nothing because someone else changed the
     * listing first; the caller reports them and does not retry.
     */
    public List<Write> updateAll(UUID clientId, List<Write> writes) {
        if (writes.isEmpty()) {
            return List.of();
        }
        Timestamp now = Timestamp.from(clock.instant());
        int[][] counts = jdbc.batchUpdate("UPDATE workspace_listing SET template_id = ?, parent_sku = ?, role = ?, title = ?, data = ?::jsonb, "
                + "fields = ?::jsonb, content_hash = ?, owner_account_id = ?, version = version + 1, updated_at = ? "
                + "WHERE id = ? AND client_id = ? AND version = ? AND deleted_at IS NULL", writes, 500, (ps, w) -> {
            Listing l = w.state();
            int i = 1;
            ps.setObject(i++, l.templateId());
            ps.setString(i++, l.parentSku());
            ps.setString(i++, l.role().name());
            ps.setString(i++, l.title());
            ps.setString(i++, json.write(l.data()));
            ps.setString(i++, json.write(l.fields()));
            ps.setString(i++, l.contentHash());
            ps.setObject(i++, l.ownerAccountId());
            ps.setTimestamp(i++, now);
            ps.setObject(i++, w.id());
            ps.setObject(i++, clientId);
            ps.setLong(i, w.expectedVersion());
        });
        List<Write> conflicts = new ArrayList<>();
        List<Write> done = new ArrayList<>();
        int index = 0;
        for (int[] chunk : counts) {
            for (int count : chunk) {
                (count == 1 ? done : conflicts).add(writes.get(index++));
            }
        }
        // the history entry carries the version the row now has
        writeHistory(clientId, done, w -> w.expectedVersion() + 1);
        trimHistory(clientId, done.stream().map(Write::id).toList());
        return conflicts;
    }

    private void writeHistory(UUID clientId, List<Write> writes, java.util.function.ToLongFunction<Write> version) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.batchUpdate("INSERT INTO workspace_listing_history (client_id, listing_id, version, change_kind, data, fields, status, "
                + "owner_account_id, changed_by, changed_at) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)", writes, 500, (ps, w) -> {
            Listing l = w.state();
            ps.setObject(1, clientId);
            ps.setObject(2, w.id());
            ps.setLong(3, version.applyAsLong(w));
            ps.setString(4, w.kind().name());
            ps.setString(5, json.write(l.data()));
            ps.setString(6, json.write(l.fields()));
            ps.setString(7, l.status().name());
            ps.setObject(8, l.ownerAccountId());
            ps.setObject(9, w.changedBy());
            ps.setTimestamp(10, now);
        });
    }

    /** Keeps the newest {@code history-keep} entries of each listing. */
    void trimHistory(UUID clientId, Collection<UUID> listingIds) {
        if (listingIds.isEmpty()) {
            return;
        }
        List<UUID> all = new ArrayList<>(listingIds);
        for (int from = 0; from < all.size(); from += 1000) {
            jdbc.update("DELETE FROM workspace_listing_history WHERE id IN (SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY listing_id "
                    + "ORDER BY id DESC) AS rn FROM workspace_listing_history WHERE client_id = ? AND listing_id = ANY (?)) ranked WHERE rn > ?)",
                    clientId, all.subList(from, Math.min(all.size(), from + 1000)).toArray(UUID[]::new), properties.historyKeep());
        }
    }

    /** The kept versions of a listing, newest first. */
    public List<HistoryEntry> history(UUID clientId, UUID listingId) {
        return jdbc.query("SELECT version, change_kind, status, owner_account_id, changed_by, changed_at FROM workspace_listing_history "
                + "WHERE client_id = ? AND listing_id = ? ORDER BY id DESC", (rs, n) -> new HistoryEntry(rs.getLong("version"),
                ChangeKind.valueOf(rs.getString("change_kind")), ListingStatus.valueOf(rs.getString("status")),
                rs.getObject("owner_account_id", UUID.class), rs.getObject("changed_by", UUID.class),
                rs.getObject("changed_at", OffsetDateTime.class).toInstant()), clientId, listingId);
    }

    public record HistoryEntry(long version, ChangeKind kind, ListingStatus status, UUID ownerAccountId, UUID changedBy, Instant changedAt) {
    }

    /** Changes the responsible person, guarded by the version. Returns false when the listing is not found or changed meanwhile. */
    public boolean changeOwner(UUID clientId, UUID listingId, UUID ownerAccountId, long expectedVersion, UUID changedBy) {
        int changed = jdbc.update("UPDATE workspace_listing SET owner_account_id = ?, version = version + 1, updated_at = ? WHERE id = ? "
                + "AND client_id = ? AND version = ? AND deleted_at IS NULL", ownerAccountId, Timestamp.from(clock.instant()), listingId,
                clientId, expectedVersion);
        if (changed == 1) {
            snapshot(clientId, listingId, ChangeKind.OWNER, changedBy);
        }
        return changed == 1;
    }

    /** Soft delete. The row stays, so its history can be exported; only {@code workspace purge} removes it. */
    public boolean softDelete(UUID clientId, UUID listingId, long expectedVersion, UUID changedBy) {
        int changed = jdbc.update("UPDATE workspace_listing SET deleted_at = ?, version = version + 1, updated_at = ? WHERE id = ? "
                + "AND client_id = ? AND version = ? AND deleted_at IS NULL", Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()), listingId, clientId, expectedVersion);
        if (changed == 1) {
            jdbc.update("INSERT INTO workspace_listing_history (client_id, listing_id, version, change_kind, data, fields, status, "
                    + "owner_account_id, changed_by, changed_at) SELECT client_id, id, version, 'DELETE', data, fields, status, owner_account_id, ?, ? "
                    + "FROM workspace_listing WHERE id = ? AND client_id = ?", changedBy, Timestamp.from(clock.instant()), listingId, clientId);
            trimHistory(clientId, List.of(listingId));
        }
        return changed == 1;
    }

    void snapshot(UUID clientId, UUID listingId, ChangeKind kind, UUID changedBy) {
        jdbc.update("INSERT INTO workspace_listing_history (client_id, listing_id, version, change_kind, data, fields, status, owner_account_id, "
                + "changed_by, changed_at) SELECT client_id, id, version, ?, data, fields, status, owner_account_id, ?, ? FROM workspace_listing "
                + "WHERE id = ? AND client_id = ?", kind.name(), changedBy, Timestamp.from(clock.instant()), listingId, clientId);
        trimHistory(clientId, List.of(listingId));
    }

    /** The FROM and WHERE of a list and its arguments: the one place the filters of the list are turned into SQL. */
    private record Filtered(String from, List<Object> args) {
    }

    private Filtered filtered(UUID clientId, ListingFilter filter, String check) {
        StringBuilder where = new StringBuilder("l.client_id = ? AND l.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(clientId);
        if (filter.accountId() != null) {
            where.append(" AND l.account_id = ?");
            args.add(filter.accountId());
        }
        if (filter.marketplace() != null) {
            where.append(" AND a.marketplace = ?");
            args.add(filter.marketplace().name());
        }
        if (filter.status() != null) {
            where.append(" AND l.status = ?");
            args.add(filter.status().name());
        }
        if (filter.noOwner()) {
            where.append(" AND l.owner_account_id IS NULL");
        } else if (filter.owner() != null) {
            where.append(" AND l.owner_account_id = ?");
            args.add(filter.owner());
        }
        String query = filter.query() == null ? "" : filter.query().strip();
        if (!query.isEmpty()) {
            where.append(" AND (l.sku ILIKE ? ESCAPE '\\' OR l.title ILIKE ? ESCAPE '\\')");
            String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        String join = "";
        if (check != null) {
            join = " LEFT JOIN workspace_listing_audit au ON au.listing_id = l.id AND au.client_id = l.client_id";
            if ("NONE".equals(check)) {
                where.append(" AND au.listing_id IS NULL");
            } else {
                where.append(" AND au.state = ?");
                args.add(check);
            }
        }
        String from = " FROM workspace_listing l JOIN workspace_account a ON a.id = l.account_id AND a.client_id = l.client_id" + join
                + " WHERE " + where;
        return new Filtered(from, args);
    }

    /**
     * One page of the list. Children follow their parent: the order is the family (the parent SKU, else the SKU), the parent row first,
     * then the SKU. {@code query} matches a part of the SKU or the title, ignoring case.
     */
    public Page<ListingSummary> list(UUID clientId, ListingFilter filter, int page) {
        return list(clientId, filter, page, null);
    }

    /**
     * The same, filtered by the state of the latest background audit (ADR-061): {@code CLEAN}, {@code WARNING}, {@code ERROR}, {@code NEW_RISK},
     * {@code INCOMPLETE} or {@code NONE} (never audited); null for no filter, which is the statement of before the audit existed.
     */
    public Page<ListingSummary> list(UUID clientId, ListingFilter filter, int page, String check) {
        Filtered filtered = filtered(clientId, filter, check);
        String from = filtered.from();
        List<Object> args = filtered.args();
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        int safePage = Math.max(0, page);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add((long) safePage * PAGE_SIZE);
        List<ListingSummary> items = jdbc.query("SELECT l.id, l.account_id, a.name AS account_name, a.marketplace, l.sku, l.parent_sku, l.role, "
                + "l.title, l.status, l.owner_account_id, l.updated_at" + from
                + " ORDER BY a.marketplace, lower(a.name), a.id, COALESCE(l.parent_sku, l.sku), (l.parent_sku IS NOT NULL), l.sku LIMIT ? OFFSET ?",
                (rs, n) -> new ListingSummary(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"), Marketplace.valueOf(rs.getString("marketplace")), rs.getString("sku"),
                        rs.getString("parent_sku"), ListingRole.valueOf(rs.getString("role")), rs.getString("title"),
                        ListingStatus.valueOf(rs.getString("status")), rs.getObject("owner_account_id", UUID.class),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()), pageArgs.toArray());
        return new Page<>(items, total, safePage, PAGE_SIZE);
    }

    /** The children of a parent SKU in an account, by SKU. */
    public List<ListingSummary> children(UUID clientId, UUID accountId, String parentSku) {
        return jdbc.query("SELECT l.id, l.account_id, a.name AS account_name, a.marketplace, l.sku, l.parent_sku, l.role, l.title, l.status, "
                + "l.owner_account_id, l.updated_at FROM workspace_listing l JOIN workspace_account a ON a.id = l.account_id AND a.client_id = l.client_id "
                + "WHERE l.client_id = ? AND l.account_id = ? AND l.parent_sku = ? AND l.deleted_at IS NULL ORDER BY l.sku",
                (rs, n) -> new ListingSummary(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"), Marketplace.valueOf(rs.getString("marketplace")), rs.getString("sku"),
                        rs.getString("parent_sku"), ListingRole.valueOf(rs.getString("role")), rs.getString("title"),
                        ListingStatus.valueOf(rs.getString("status")), rs.getObject("owner_account_id", UUID.class),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()), clientId, accountId, parentSku);
    }

    public long count(UUID clientId, UUID accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing WHERE client_id = ? AND account_id = ? AND deleted_at IS NULL",
                Long.class, clientId, accountId);
    }


    // ---------------------------------------------------------------- the editor, the restore and the status (ADR-062)

    /**
     * Writes the new content of one listing (an edit or a restore), guarded by the version the caller read, with its status (a content that
     * changed is no longer "ready"), and records the history entry. Returns false, and writes nothing, when the listing was changed or
     * deleted meanwhile. The caller holds the account's lock and a transaction. The SKU, the account, the template, the owner and the source
     * of a listing never change here.
     */
    public boolean updateContent(UUID clientId, Write write) {
        Listing l = write.state();
        int changed = jdbc.update("UPDATE workspace_listing SET parent_sku = ?, role = ?, title = ?, data = ?::jsonb, fields = ?::jsonb, "
                + "content_hash = ?, status = ?, version = version + 1, updated_at = ? WHERE id = ? AND client_id = ? AND version = ? "
                + "AND deleted_at IS NULL", l.parentSku(), l.role().name(), l.title(), json.write(l.data()), json.write(l.fields()),
                l.contentHash(), l.status().name(), Timestamp.from(clock.instant()), write.id(), clientId, write.expectedVersion());
        if (changed == 1) {
            snapshot(clientId, write.id(), write.kind(), write.changedBy());
        }
        return changed == 1;
    }

    /** Changes the status of a listing, guarded by the version, and records a STATUS entry. False when the listing changed or is gone. */
    public boolean changeStatus(UUID clientId, UUID listingId, ListingStatus status, long expectedVersion, UUID changedBy) {
        int changed = jdbc.update("UPDATE workspace_listing SET status = ?, version = version + 1, updated_at = ? WHERE id = ? "
                + "AND client_id = ? AND version = ? AND deleted_at IS NULL", status.name(), Timestamp.from(clock.instant()), listingId, clientId,
                expectedVersion);
        if (changed == 1) {
            snapshot(clientId, listingId, ChangeKind.STATUS, changedBy);
        }
        return changed == 1;
    }

    /**
     * The ids of the listings a filter matches, in the order of the list, at most {@code max}; one more than {@code max} is never read, so a
     * caller can tell that the filter holds more (it asks for {@code max + 1} and compares).
     */
    public List<UUID> ids(UUID clientId, ListingFilter filter, int max) {
        Filtered filtered = filtered(clientId, filter, null);
        List<Object> args = new ArrayList<>(filtered.args());
        args.add(max);
        return jdbc.query("SELECT l.id" + filtered.from() + " ORDER BY a.marketplace, lower(a.name), a.id, COALESCE(l.parent_sku, l.sku), "
                + "(l.parent_sku IS NOT NULL), l.sku LIMIT ?", (rs, n) -> rs.getObject("id", UUID.class), args.toArray());
    }

    /**
     * Moves a listing that is ready to export out of the workspace: to EXPORTED (a file or a text was made of it) or to LIVE (an Etsy listing a
     * person posted by hand), stamps {@code exported_at}, bumps the version and records an EXPORT entry in its history (ADR-063 §7). Guarded
     * by the version and by the listing being ready; false when it is neither, or was changed meanwhile. The caller holds a transaction.
     */
    public boolean release(UUID clientId, UUID listingId, ListingStatus to, long expectedVersion, UUID changedBy) {
        Timestamp now = Timestamp.from(clock.instant());
        int changed = jdbc.update("UPDATE workspace_listing SET status = ?, exported_at = ?, version = version + 1, updated_at = ? WHERE id = ? "
                + "AND client_id = ? AND version = ? AND status = 'READY_TO_EXPORT' AND deleted_at IS NULL", to.name(), now, now, listingId,
                clientId, expectedVersion);
        if (changed == 1) {
            snapshot(clientId, listingId, ChangeKind.EXPORT, changedBy);
        }
        return changed == 1;
    }

    /** When each of these listings was last exported or posted; a listing that never was is absent. */
    public Map<UUID, Instant> exportedAt(UUID clientId, Collection<UUID> ids) {
        Map<UUID, Instant> found = new HashMap<>();
        List<UUID> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += 1000) {
            jdbc.query("SELECT id, exported_at FROM workspace_listing WHERE client_id = ? AND id = ANY (?) AND exported_at IS NOT NULL",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> found.put(rs.getObject("id", UUID.class),
                            rs.getObject("exported_at", OffsetDateTime.class).toInstant()), clientId,
                    all.subList(from, Math.min(all.size(), from + 1000)).toArray(UUID[]::new));
        }
        return found;
    }

    /**
     * Moves a listing that was exported to LIVE (a person says it is on sale), guarded by the version and by the status. Records a STATUS
     * entry. False when the listing is not exported or changed meanwhile.
     */
    public boolean markLive(UUID clientId, UUID listingId, long expectedVersion, UUID changedBy) {
        int changed = jdbc.update("UPDATE workspace_listing SET status = 'LIVE', version = version + 1, updated_at = ? WHERE id = ? "
                + "AND client_id = ? AND version = ? AND status = 'EXPORTED' AND deleted_at IS NULL", Timestamp.from(clock.instant()),
                listingId, clientId, expectedVersion);
        if (changed == 1) {
            snapshot(clientId, listingId, ChangeKind.STATUS, changedBy);
        }
        return changed == 1;
    }

    /** The Amazon Custom rows of an account for these SKUs, by SKU. A SKU without a row is absent. */
    public Map<String, CustomizationRow> customizationRows(UUID clientId, UUID accountId, Collection<String> skus) {
        Map<String, CustomizationRow> found = new HashMap<>();
        List<String> all = new ArrayList<>(skus);
        for (int from = 0; from < all.size(); from += 1000) {
            jdbc.query("SELECT sku, template_id, data::text AS data FROM workspace_customization WHERE client_id = ? AND account_id = ? "
                    + "AND sku = ANY (?)", (org.springframework.jdbc.core.RowCallbackHandler) rs -> found.put(rs.getString("sku"),
                    new CustomizationRow(rs.getString("sku"), rs.getObject("template_id", UUID.class), json.stringMap(rs.getString("data")))),
                    clientId, accountId, all.subList(from, Math.min(all.size(), from + 1000)).toArray(String[]::new));
        }
        return found;
    }

    /** One Amazon Custom row: its SKU, the template version it was read with and its cells by column key. */
    public record CustomizationRow(String sku, UUID templateId, Map<String, String> data) {
    }

    /** The live listings of the client with these ids, by id. A listing of another client is simply absent. */
    public Map<UUID, Listing> findMany(UUID clientId, Collection<UUID> ids) {
        Map<UUID, Listing> found = new HashMap<>();
        List<UUID> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += 1000) {
            UUID[] chunk = all.subList(from, Math.min(all.size(), from + 1000)).toArray(UUID[]::new);
            jdbc.query("SELECT " + COLUMNS + " FROM workspace_listing WHERE client_id = ? AND id = ANY (?) AND deleted_at IS NULL",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        Listing listing = mapper.mapRow(rs, 0);
                        found.put(listing.id(), listing);
                    }, clientId, chunk);
        }
        return found;
    }

    /** The live children of a parent SKU of an account, whole, by SKU. */
    public List<Listing> childrenWhole(UUID clientId, UUID accountId, String parentSku) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workspace_listing WHERE client_id = ? AND account_id = ? AND parent_sku = ? "
                + "AND deleted_at IS NULL ORDER BY sku", mapper, clientId, accountId, parentSku);
    }

    /** One kept version of a listing, whole. */
    public record HistorySnapshot(long version, ChangeKind kind, Map<String, String> data, com.fasterxml.jackson.databind.JsonNode fields,
                                  ListingStatus status, UUID ownerAccountId, UUID changedBy, Instant changedAt) {
    }

    public Optional<HistorySnapshot> historyEntry(UUID clientId, UUID listingId, long version) {
        return jdbc.query("SELECT version, change_kind, data::text AS data, fields::text AS fields, status, owner_account_id, changed_by, "
                + "changed_at FROM workspace_listing_history WHERE client_id = ? AND listing_id = ? AND version = ? ORDER BY id DESC LIMIT 1",
                (rs, n) -> new HistorySnapshot(rs.getLong("version"), ChangeKind.valueOf(rs.getString("change_kind")),
                        json.stringMap(rs.getString("data")), json.tree(rs.getString("fields")), ListingStatus.valueOf(rs.getString("status")),
                        rs.getObject("owner_account_id", UUID.class), rs.getObject("changed_by", UUID.class),
                        rs.getObject("changed_at", OffsetDateTime.class).toInstant()), clientId, listingId, version).stream().findFirst();
    }

    /** The kinds of change that put content into a listing from inside the workspace rather than from a sheet. */
    static final List<String> WORKSPACE_KINDS = List.of("EDIT", "NEW", "CLONE", "RESTORE");

    /** What the last change made inside the workspace to a listing was, and when. */
    public record Edited(ChangeKind kind, Instant at) {
    }

    /**
     * Of these listings, those that were edited, created, cloned or restored in the workspace after their latest import (ADR-062 §8): their
     * latest history entry of such a kind is newer than their latest IMPORT entry, or they have no IMPORT entry at all. A listing whose only
     * later changes are of the responsible person or the status is not in the answer.
     */
    public Map<UUID, Edited> editedSinceImport(UUID clientId, Collection<UUID> listingIds) {
        Map<UUID, Edited> found = new HashMap<>();
        List<UUID> all = new ArrayList<>(listingIds);
        for (int from = 0; from < all.size(); from += 1000) {
            UUID[] chunk = all.subList(from, Math.min(all.size(), from + 1000)).toArray(UUID[]::new);
            jdbc.query("SELECT DISTINCT ON (h.listing_id) h.listing_id, h.change_kind, h.changed_at FROM workspace_listing_history h "
                    + "WHERE h.client_id = ? AND h.listing_id = ANY (?) AND h.change_kind = ANY (?) AND h.id > COALESCE((SELECT MAX(i.id) "
                    + "FROM workspace_listing_history i WHERE i.listing_id = h.listing_id AND i.client_id = h.client_id AND i.change_kind = 'IMPORT'), 0) "
                    + "ORDER BY h.listing_id, h.id DESC", (org.springframework.jdbc.core.RowCallbackHandler) rs -> found.put(
                    rs.getObject("listing_id", UUID.class), new Edited(ChangeKind.valueOf(rs.getString("change_kind")),
                            rs.getObject("changed_at", OffsetDateTime.class).toInstant())), clientId, chunk, WORKSPACE_KINDS.toArray(String[]::new));
        }
        return found;
    }

    /** A listing of the account whose title is this one, apart from one listing (the one being looked at). */
    public record TitleMatch(UUID id, String sku) {
    }

    /** The other live listings of the account whose title equals {@code title} ignoring case and the blanks at both ends; at most 5. */
    public List<TitleMatch> sameTitle(UUID clientId, UUID accountId, UUID except, String title) {
        String clean = title == null ? "" : title.strip();
        if (clean.isEmpty()) {
            return List.of();
        }
        String like = clean.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.query("SELECT id, sku FROM workspace_listing WHERE client_id = ? AND account_id = ? AND deleted_at IS NULL AND id <> ? "
                + "AND btrim(title) ILIKE ? ESCAPE '\\' ORDER BY sku LIMIT 5", (rs, n) -> new TitleMatch(rs.getObject("id", UUID.class),
                rs.getString("sku")), clientId, accountId, except, like);
    }

    /** How many live listings the client has with these ids, so a selection can say how many it lost. */
    public int countLive(UUID clientId, Collection<UUID> ids) {
        return findMany(clientId, ids).size();
    }

    // ---------------------------------------------------------------- Amazon Custom rows

    public enum Outcome {
        NEW, UPDATED, UNCHANGED
    }

    /** What an Amazon Custom row of the account with this SKU holds now. */
    public record CustomizationState(UUID id, String contentHash, long version) {
    }

    public Map<String, CustomizationState> customizations(UUID clientId, UUID accountId, Collection<String> skus) {
        Map<String, CustomizationState> found = new HashMap<>();
        List<String> all = new ArrayList<>(skus);
        for (int from = 0; from < all.size(); from += 1000) {
            jdbc.query("SELECT id, sku, content_hash, version FROM workspace_customization WHERE client_id = ? AND account_id = ? "
                    + "AND sku = ANY (?)", rs -> {
                found.put(rs.getString("sku"), new CustomizationState(rs.getObject("id", UUID.class), rs.getString("content_hash"),
                        rs.getLong("version")));
            }, clientId, accountId, all.subList(from, Math.min(all.size(), from + 1000)).toArray(String[]::new));
        }
        return found;
    }

    /** A row of an Amazon Custom sheet to write. */
    public record CustomizationWrite(String sku, UUID templateId, Map<String, String> data, String contentHash, CustomizationState current) {
    }

    public void writeCustomizations(UUID clientId, UUID accountId, List<CustomizationWrite> writes) {
        Timestamp now = Timestamp.from(clock.instant());
        List<CustomizationWrite> inserts = writes.stream().filter(w -> w.current() == null).toList();
        List<CustomizationWrite> updates = writes.stream().filter(w -> w.current() != null).toList();
        jdbc.batchUpdate("INSERT INTO workspace_customization (id, client_id, account_id, template_id, sku, data, content_hash, version, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 0, ?, ?)", inserts, 500, (ps, w) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, clientId);
            ps.setObject(3, accountId);
            ps.setObject(4, w.templateId());
            ps.setString(5, w.sku());
            ps.setString(6, json.write(w.data()));
            ps.setString(7, w.contentHash());
            ps.setTimestamp(8, now);
            ps.setTimestamp(9, now);
        });
        jdbc.batchUpdate("UPDATE workspace_customization SET template_id = ?, data = ?::jsonb, content_hash = ?, version = version + 1, "
                + "updated_at = ? WHERE id = ? AND client_id = ?", updates, 500, (ps, w) -> {
            ps.setObject(1, w.templateId());
            ps.setString(2, json.write(w.data()));
            ps.setString(3, w.contentHash());
            ps.setTimestamp(4, now);
            ps.setObject(5, w.current().id());
            ps.setObject(6, clientId);
        });
    }

    /** One Amazon Custom row joined to its listing, when the account has a listing with that SKU. */
    public record CustomizationView(String sku, UUID templateId, Map<String, String> data, UUID listingId) {
    }

    public Optional<CustomizationView> customization(UUID clientId, UUID accountId, String sku) {
        return jdbc.query("SELECT c.sku, c.template_id, c.data::text AS data, l.id AS listing_id FROM workspace_customization c "
                + "LEFT JOIN workspace_listing l ON l.account_id = c.account_id AND l.client_id = c.client_id AND l.sku = c.sku AND l.deleted_at IS NULL "
                + "WHERE c.client_id = ? AND c.account_id = ? AND c.sku = ?", (rs, n) -> new CustomizationView(rs.getString("sku"),
                rs.getObject("template_id", UUID.class), json.stringMap(rs.getString("data")), rs.getObject("listing_id", UUID.class)),
                clientId, accountId, sku).stream().findFirst();
    }

    /** The Amazon Custom rows of the account that no live listing has the SKU of. */
    public long unlinkedCustomizations(UUID clientId, UUID accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workspace_customization c WHERE c.client_id = ? AND c.account_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM workspace_listing l WHERE l.account_id = c.account_id AND l.client_id = c.client_id AND l.sku = c.sku "
                + "AND l.deleted_at IS NULL)", Long.class, clientId, accountId);
    }

    public long countCustomizations(UUID clientId, UUID accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workspace_customization WHERE client_id = ? AND account_id = ?", Long.class, clientId,
                accountId);
    }

    /** Takes the lock that serializes the writes of one seller account until the transaction ends (ADR-059). */
    public void lockAccount(UUID accountId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "workspace:" + accountId);
    }
}
