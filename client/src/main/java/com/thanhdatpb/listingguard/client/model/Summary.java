package com.thanhdatpb.listingguard.client.model;

/**
 * The number of findings per severity (schema {@code Summary}).
 *
 * @param errors   findings of severity {@code ERROR}
 * @param warnings findings of severity {@code WARNING}
 * @param infos    findings of severity {@code INFO}
 */
public record Summary(int errors, int warnings, int infos) {
}
