/**
 * Request and response types of the Listing Guard SDK, following {@code docs/api/openapi.json} (ADR-023). Responses are read
 * tolerantly: an unknown member is ignored, an unknown enum value is {@code UNRECOGNIZED} (never one of the known values) and a
 * missing list is empty. Request types never show a listing or a buyer's text in {@code toString()}.
 */
package com.thanhdatpb.listingguard.client.model;
