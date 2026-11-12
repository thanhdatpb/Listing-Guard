package com.thanhdatpb.listingguard.api.policymonitor;

import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPolicyMonitorSnapshotStore implements PolicyMonitorSnapshotStore {

    private final JdbcTemplate jdbc;

    public JdbcPolicyMonitorSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DocumentSnapshot> readAll() {
        Map<String, MutableDocument> docs = new LinkedHashMap<>();
        jdbc.query("SELECT document_url, file_name, marketplace, title, version, fetched_at, document_hash "
                        + "FROM policy_monitor_document ORDER BY document_url", rs -> {
                    docs.put(rs.getString("document_url"), new MutableDocument(rs.getString("document_url"),
                            rs.getString("file_name"), rs.getString("marketplace"), rs.getString("title"),
                            rs.getString("version"), rs.getDate("fetched_at").toLocalDate(), rs.getString("document_hash")));
                });
        jdbc.query("SELECT document_url, section_path, section_hash FROM policy_monitor_section "
                        + "ORDER BY document_url, section_path", rs -> {
                    MutableDocument doc = docs.get(rs.getString("document_url"));
                    if (doc != null) {
                        doc.sections.add(new SectionSnapshot(rs.getString("section_path"), rs.getString("section_hash")));
                    }
                });
        return docs.values().stream().map(MutableDocument::snapshot).toList();
    }

    @Override
    @Transactional
    public void replaceAll(List<DocumentSnapshot> documents) {
        jdbc.update("DELETE FROM policy_monitor_document");
        for (DocumentSnapshot document : documents) {
            jdbc.update("INSERT INTO policy_monitor_document(document_url, file_name, marketplace, title, version, fetched_at, document_hash) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", document.url(), document.file(), document.marketplace(),
                    document.title(), document.version(), Date.valueOf(document.fetchedAt()), document.documentHash());
            for (SectionSnapshot section : document.sections()) {
                jdbc.update("INSERT INTO policy_monitor_section(document_url, section_path, section_hash) VALUES (?, ?, ?)",
                        document.url(), section.path(), section.hash());
            }
        }
    }

    private static final class MutableDocument {
        private final String url;
        private final String file;
        private final String marketplace;
        private final String title;
        private final String version;
        private final java.time.LocalDate fetchedAt;
        private final String hash;
        private final List<SectionSnapshot> sections = new ArrayList<>();

        private MutableDocument(String url, String file, String marketplace, String title, String version,
                                java.time.LocalDate fetchedAt, String hash) {
            this.url = url;
            this.file = file;
            this.marketplace = marketplace;
            this.title = title;
            this.version = version;
            this.fetchedAt = fetchedAt;
            this.hash = hash;
        }

        private DocumentSnapshot snapshot() {
            return new DocumentSnapshot(url, file, marketplace, title, version, fetchedAt, hash, sections);
        }
    }
}
