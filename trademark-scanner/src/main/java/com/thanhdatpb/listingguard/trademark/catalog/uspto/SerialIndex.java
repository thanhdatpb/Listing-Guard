package com.thanhdatpb.listingguard.trademark.catalog.uspto;

/**
 * For each serial number (eight digits, so an {@code int}) the largest order key seen: an open-addressing hash table of
 * primitives, about 12 bytes per slot and at most 70 percent full, so about 17 to 34 bytes per serial number (ADR-009,
 * PR A2c). A key is always positive; zero marks an empty slot.
 */
final class SerialIndex {

    private static final double MAX_LOAD = 0.7;

    private final int limit;
    private int[] serials = new int[1 << 10];
    private long[] keys = new long[1 << 10];
    private int size;

    /** @param limit the most distinct serial numbers it may hold */
    SerialIndex(int limit) {
        this.limit = limit;
    }

    int size() {
        return size;
    }

    /** Remembers {@code key} for {@code serial} when it is larger than the one held, or when there is none. */
    void offer(int serial, long key) {
        if (key <= 0) {
            throw new IllegalArgumentException("An order key is positive.");
        }
        int slot = slotOf(serials, keys, serial);
        if (keys[slot] == 0) {
            if (size >= limit) {
                throw new UsptoConversionException("The files hold more than " + limit + " distinct serial numbers, the "
                        + "limit of listing-guard.trademark.uspto.max-distinct-serials (about 17 to 34 bytes of heap each). "
                        + "Raise it and the heap, or convert fewer files in one run.");
            }
            serials[slot] = serial;
            keys[slot] = key;
            size++;
            if (size > MAX_LOAD * keys.length) {
                grow();
            }
        } else if (key > keys[slot]) {
            keys[slot] = key;
        }
    }

    /** The largest key offered for {@code serial}, or 0 when it was never offered. */
    long get(int serial) {
        return keys[slotOf(serials, keys, serial)];
    }

    private static int slotOf(int[] serials, long[] keys, int serial) {
        int mask = keys.length - 1;
        int slot = mix(serial) & mask;
        while (keys[slot] != 0 && serials[slot] != serial) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void grow() {
        int[] oldSerials = serials;
        long[] oldKeys = keys;
        serials = new int[oldKeys.length * 2];
        keys = new long[oldKeys.length * 2];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != 0) {
                int slot = slotOf(serials, keys, oldSerials[i]);
                serials[slot] = oldSerials[i];
                keys[slot] = oldKeys[i];
            }
        }
    }

    /** Serial numbers run in sequence; a multiplicative hash spreads them. */
    private static int mix(int serial) {
        int h = serial * 0x9E3779B9;
        return h ^ (h >>> 16);
    }
}
