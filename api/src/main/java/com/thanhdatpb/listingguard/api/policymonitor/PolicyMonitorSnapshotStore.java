package com.thanhdatpb.listingguard.api.policymonitor;

import java.time.LocalDate;
import java.util.List;

/** Stores hashes and section paths only; policy text is never part of a snapshot. */
public interface PolicyMonitorSnapshotStore {

    record DocumentSnapshot(String url, String file, String marketplace, String title, String version,
                            LocalDate fetchedAt, String documentHash, List<SectionSnapshot> sections) {
        public DocumentSnapshot {
            sections = List.copyOf(sections);
        }
    }

    record SectionSnapshot(String path, String hash) {
    }

    List<DocumentSnapshot> readAll();

    void replaceAll(List<DocumentSnapshot> documents);
}
