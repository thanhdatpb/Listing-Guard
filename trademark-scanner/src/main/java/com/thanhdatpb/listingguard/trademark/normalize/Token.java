package com.thanhdatpb.listingguard.trademark.normalize;

/**
 * One normalized token of a field.
 *
 * @param index position in the field's token list
 * @param text  the normalized form
 * @param start offset in the canonical field text (UTF-16 code units), inclusive
 * @param end   offset in the canonical field text, exclusive
 */
public record Token(int index, String text, int start, int end) {
}
