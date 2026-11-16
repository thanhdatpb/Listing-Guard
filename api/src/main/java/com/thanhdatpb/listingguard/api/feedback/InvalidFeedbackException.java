package com.thanhdatpb.listingguard.api.feedback;

/**
 * A verdict that cannot be stored as sent (ADR-034): an unknown pack or verdict, or a note over the limit. Nothing is stored. The
 * message names what is wrong and never repeats the note.
 */
public class InvalidFeedbackException extends RuntimeException {

    /** The stable problem code of the answer (HTTP 400). */
    public static final String CODE = "invalid-feedback";

    /** What is wrong, so that the console can say it in the language of its page (ADR-028). */
    public enum Reason {
        PACK, VERDICT, NOTE_TOO_LONG
    }

    private final Reason reason;
    private final int noteLength;

    public InvalidFeedbackException(Reason reason, String message) {
        this(reason, message, 0);
    }

    public InvalidFeedbackException(Reason reason, String message, int noteLength) {
        super(message);
        this.reason = reason;
        this.noteLength = noteLength;
    }

    public Reason reason() {
        return reason;
    }

    /** The length of the refused note, in characters; 0 for another reason. */
    public int noteLength() {
        return noteLength;
    }
}
