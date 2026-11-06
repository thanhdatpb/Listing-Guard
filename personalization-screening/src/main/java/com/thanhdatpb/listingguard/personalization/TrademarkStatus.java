package com.thanhdatpb.listingguard.personalization;

/**
 * What became of the trademark part of a screening (ADR-016). {@code NOT_CONFIGURED}: no catalog is served
 * ({@code DISABLED} or {@code REFUSED}), so the scanner did not run and the risk level is {@code UNKNOWN}.
 * {@code INCOMPLETE}: the scanner ran and a trademark rule failed; its findings are kept. Keep in sync with
 * {@code ck_personalization_screening_trademark_status}.
 */
public enum TrademarkStatus {
    COMPLETE,
    INCOMPLETE,
    NOT_CONFIGURED
}
