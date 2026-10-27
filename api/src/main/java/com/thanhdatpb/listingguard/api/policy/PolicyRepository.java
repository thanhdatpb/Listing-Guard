package com.thanhdatpb.listingguard.api.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.rag.policy.PolicyManifest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes policy documents and chunks with plain SQL (the vector column is not a JPA type). */
@Repository
public class PolicyRepository {

    private static final int BATCH_SIZE = 100;

    /** What ingest needs to know to decide whether a stored document is still current. */
    public record DocumentState(long id, String title, String url, LocalDate fetchedAt, String version,
                                String sourceHash, String pipelineVersion) {
    }

    public record NewChunk(int ordinal, String sectionPath, String url, String content, String contentHash,
                           Map<String, Object> metadata, float[] embedding) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DocumentState> findDocument(String marketplace, String sourceFile) {
        return jdbc.query("""
                        SELECT id, title, url, fetched_at, version, source_hash, pipeline_version
                        FROM policy_document WHERE marketplace_code = ? AND source_file = ?
                        """,
                (rs, row) -> new DocumentState(rs.getLong("id"), rs.getString("title"), rs.getString("url"),
                        rs.getDate("fetched_at").toLocalDate(), rs.getString("version"), rs.getString("source_hash"),
                        rs.getString("pipeline_version")),
                marketplace, sourceFile).stream().findFirst();
    }

    public int countChunks(long documentId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM policy_chunk WHERE document_id = ?", Integer.class, documentId);
        return count == null ? 0 : count;
    }

    public void updateFetchedAt(long documentId, LocalDate fetchedAt) {
        jdbc.update("UPDATE policy_document SET fetched_at = ?, updated_at = now() WHERE id = ?", Date.valueOf(fetchedAt), documentId);
    }

    /** Inserts the document, or updates it in place so its id (and therefore its place in references) is kept. */
    public long upsertDocument(PolicyManifest.Entry entry, String sourceHash, String pipelineVersion) {
        Long id = jdbc.queryForObject("""
                        INSERT INTO policy_document
                            (marketplace_code, source_file, title, url, fetched_at, version, source_hash, pipeline_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (marketplace_code, source_file) DO UPDATE SET
                            title = EXCLUDED.title, url = EXCLUDED.url, fetched_at = EXCLUDED.fetched_at,
                            version = EXCLUDED.version, source_hash = EXCLUDED.source_hash,
                            pipeline_version = EXCLUDED.pipeline_version, updated_at = now()
                        RETURNING id
                        """,
                Long.class, entry.marketplace(), entry.file(), entry.title(), entry.url(), Date.valueOf(entry.fetchedAt()),
                entry.version(), sourceHash, pipelineVersion);
        return id;
    }

    /** Replaces all chunks of a document: the old ones go, the new ones come in. */
    public void replaceChunks(long documentId, String marketplace, List<NewChunk> chunks) {
        jdbc.update("DELETE FROM policy_chunk WHERE document_id = ?", documentId);
        jdbc.batchUpdate("""
                        INSERT INTO policy_chunk
                            (document_id, marketplace_code, ordinal, section_path, url, content, content_hash, metadata, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                        """,
                chunks, BATCH_SIZE, (ps, chunk) -> {
                    ps.setLong(1, documentId);
                    ps.setString(2, marketplace);
                    ps.setInt(3, chunk.ordinal());
                    ps.setString(4, chunk.sectionPath());
                    ps.setString(5, chunk.url());
                    ps.setString(6, chunk.content());
                    ps.setString(7, chunk.contentHash());
                    ps.setString(8, toJson(chunk.metadata()));
                    ps.setString(9, vectorLiteral(chunk.embedding()));
                });
    }

    public List<String> documentFiles(String marketplace) {
        return jdbc.queryForList("SELECT source_file FROM policy_document WHERE marketplace_code = ? ORDER BY source_file",
                String.class, marketplace);
    }

    public void deleteDocument(String marketplace, String sourceFile) {
        jdbc.update("DELETE FROM policy_document WHERE marketplace_code = ? AND source_file = ?", marketplace, sourceFile);
    }

    /** pgvector text format: {@code [0.1,0.2,...]}. */
    static String vectorLiteral(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return json.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize chunk metadata", e);
        }
    }
}
