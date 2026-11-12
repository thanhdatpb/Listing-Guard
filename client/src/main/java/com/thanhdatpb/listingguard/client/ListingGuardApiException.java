package com.thanhdatpb.listingguard.client;

import java.util.Map;
import java.util.Objects;

/**
 * The API answered with an error status ({@code 4xx} or {@code 5xx}). The subclasses name the answers a caller usually treats
 * differently; everything else is this class. Branch on {@link #code()}, never on {@link #detail()}.
 *
 * <p>When {@link #isProblem()} is false the answer was not a problem of the Listing Guard API: it came from something in
 * front of it (a proxy, a gateway, Cloudflare Access refusing a service token) and has no code.
 */
public class ListingGuardApiException extends ListingGuardException {

    private static final long serialVersionUID = 1L;
    private static final int MAX_MESSAGE_DETAIL = 500;

    private final transient ApiProblem problem;

    public ListingGuardApiException(ApiProblem problem) {
        super(message(Objects.requireNonNull(problem, "problem")), null);
        this.problem = problem;
    }

    /** The whole problem as read. */
    public ApiProblem problem() {
        return problem;
    }

    /** The HTTP status. */
    public int status() {
        return problem.status();
    }

    /** The {@code type} URI, or {@code null}. */
    public String type() {
        return problem.type();
    }

    /** The stable code ({@code invalid-request}, {@code not-found}, ...), or {@code null} when {@link #isProblem()} is false. */
    public String code() {
        return problem.code();
    }

    /** The {@code title}, or {@code null}. */
    public String title() {
        return problem.title();
    }

    /** The {@code detail} (text for people), or {@code null}. */
    public String detail() {
        return problem.detail();
    }

    /** The {@code instance}, or {@code null}. */
    public String instance() {
        return problem.instance();
    }

    /** The request id to quote when reporting the problem, or {@code null}. */
    public String requestId() {
        return problem.requestId();
    }

    /** The other members of the problem. */
    public Map<String, Object> properties() {
        return problem.properties();
    }

    /** True when the answer is a problem of the Listing Guard API. */
    public boolean isProblem() {
        return problem.isListingGuardProblem();
    }

    private static String message(ApiProblem p) {
        StringBuilder out = new StringBuilder();
        if (p.isListingGuardProblem()) {
            out.append("Listing Guard API error ").append(p.code()).append(" (HTTP ").append(p.status()).append(')');
            String text = p.detail() != null && !p.detail().isBlank() ? p.detail() : p.title();
            if (text != null && !text.isBlank()) {
                out.append(": ").append(cut(text));
            }
        } else {
            out.append("HTTP ").append(p.status())
                    .append(" without a Listing Guard problem body: something in front of the API answered "
                            + "(a proxy, a gateway, Cloudflare Access)");
        }
        if (p.requestId() != null) {
            out.append(" [requestId=").append(p.requestId()).append(']');
        }
        return out.toString();
    }

    private static String cut(String text) {
        String clean = text.replaceAll("\\p{Cntrl}", " ");
        return clean.length() <= MAX_MESSAGE_DETAIL ? clean : clean.substring(0, MAX_MESSAGE_DETAIL) + "...";
    }
}
