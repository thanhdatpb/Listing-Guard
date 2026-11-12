package com.thanhdatpb.listingguard.contract;

/**
 * Which way the data of a schema flows. A request is written by the client, so the server may only accept more; a response is
 * read by the client, so the server may only send less variety. A schema used on both sides is compared on both.
 */
enum Direction {
    REQUEST,
    RESPONSE
}
