package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The records of the workspace (ADR-059). */
public final class WorkspaceModels {

    private WorkspaceModels() {
    }

    /** A seller account on a marketplace of a client (an Amazon seller account, an Etsy shop). Not a console login. */
    public record SellerAccount(UUID id, UUID clientId, Marketplace marketplace, String name, String brand, ExportFormat exportFormat,
                                boolean enabled, Instant createdAt) {
    }

    /**
     * A column of a template: its position, the key its values are kept under in a listing's {@code data}, and the field-name cell as the file
     * wrote it. A header that repeats gets the key {@code header~2}, {@code header~3}; a column without a header gets {@code col_<letters>}.
     */
    public record TemplateColumn(int index, String key, String header) {
    }

    /**
     * What a person confirmed about the columns: {@code targets} (column key to standard field), {@code separators} (list target to separator
     * key) and, for an Etsy template whose columns are fixed, {@code source} (the header of a file's column to the Etsy field it feeds).
     */
    public record Mapping(Map<String, String> targets, Map<String, String> separators, Map<String, String> source) {

        public Mapping {
            targets = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(targets));
            separators = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(separators));
            source = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }

        public static Mapping empty() {
            return new Mapping(Map.of(), Map.of(), Map.of());
        }
    }

    /** One version of an account's template. The bytes of an Excel file are read separately ({@code WorkspaceTemplates.fileBytes}). */
    public record Template(UUID id, UUID clientId, UUID accountId, TemplateKind kind, int version, String sourceFormat,
                           String sourceFileName, String fileSha256, String sheetName, int fieldRow, int firstDataRow,
                           List<List<String>> headerRows, List<TemplateColumn> columns, Mapping mapping, String headerHash,
                           boolean unverified, Instant createdAt) {

        public Template {
            headerRows = List.copyOf(headerRows);
            columns = List.copyOf(columns);
        }

        public boolean hasFile() {
            return fileSha256 != null;
        }

        public Template withMapping(Mapping changed) {
            return new Template(id, clientId, accountId, kind, version, sourceFormat, sourceFileName, fileSha256, sheetName, fieldRow,
                    firstDataRow, headerRows, columns, changed, headerHash, unverified, createdAt);
        }
    }

    /** A listing of the workspace. {@code data} holds every column by key, in the template's order; {@code fields} the standard fields. */
    public record Listing(UUID id, UUID clientId, UUID accountId, UUID templateId, String sku, String parentSku, ListingRole role,
                          String title, Map<String, String> data, JsonNode fields, String contentHash, UUID ownerAccountId,
                          ListingStatus status, ListingSource source, UUID clonedFrom, long version, Instant createdAt,
                          Instant updatedAt, Instant deletedAt) {

        public Listing {
            data = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    /** What the list shows of a listing. */
    public record ListingSummary(UUID id, UUID accountId, String accountName, Marketplace marketplace, String sku, String parentSku,
                                 ListingRole role, String title, ListingStatus status, UUID ownerAccountId, Instant updatedAt) {
    }

    /** A page of the list. */
    public record Page<T>(List<T> items, long total, int page, int pageSize) {

        public int pages() {
            return (int) Math.max(1, (total + pageSize - 1) / pageSize);
        }
    }

    /** The filters of the list; every field is optional. {@code owner} is an account id, or {@code none} for no one. */
    public record ListingFilter(UUID accountId, Marketplace marketplace, ListingStatus status, UUID owner, boolean noOwner, String query) {

        public static ListingFilter none() {
            return new ListingFilter(null, null, null, null, false, "");
        }
    }

    /** A refusal of the workspace; the {@code code} is the tail of a {@code workspace.error.<code>} message of the console. */
    public static class WorkspaceException extends RuntimeException {

        private final String code;

        public WorkspaceException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
