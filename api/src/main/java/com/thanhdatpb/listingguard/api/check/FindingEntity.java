package com.thanhdatpb.listingguard.api.check;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finding")
public class FindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_id", nullable = false)
    private UUID checkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FindingSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    private String field;

    /** The exact value that caused the finding, or null when no single value applies. */
    @Column(name = "value")
    private String value;

    @Column(nullable = false)
    private String message;

    private String suggestion;

    private String citation;

    @Column(name = "ruleset_version_id")
    private Long rulesetVersionId;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected FindingEntity() {
    }

    public static FindingEntity fromRule(UUID checkId, Finding finding, long rulesetVersionId) {
        FindingEntity entity = new FindingEntity();
        entity.checkId = checkId;
        entity.source = FindingSource.RULE;
        entity.severity = finding.severity();
        entity.field = finding.field();
        entity.value = finding.value();
        entity.message = finding.message();
        entity.suggestion = finding.suggestion();
        entity.rulesetVersionId = rulesetVersionId;
        entity.ruleId = finding.ruleId();
        return entity;
    }

    public Long getId() {
        return id;
    }

    public UUID getCheckId() {
        return checkId;
    }

    public FindingSource getSource() {
        return source;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getField() {
        return field;
    }

    public String getValue() {
        return value;
    }

    public String getMessage() {
        return message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public Long getRulesetVersionId() {
        return rulesetVersionId;
    }

    public String getRuleId() {
        return ruleId;
    }
}
