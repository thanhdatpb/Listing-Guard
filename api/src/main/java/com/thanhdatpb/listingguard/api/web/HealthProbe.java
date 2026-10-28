package com.thanhdatpb.listingguard.api.web;

/** Answers whether what the service depends on is reachable. */
public interface HealthProbe {

    /** @return true only when the service can do its work (today: it can query PostgreSQL) */
    boolean isUp();
}
