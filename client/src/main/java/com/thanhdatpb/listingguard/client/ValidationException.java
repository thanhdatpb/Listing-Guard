package com.thanhdatpb.listingguard.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code invalid-request} or {@code unprocessable-request}: the request is not valid. {@link #errors()} lists what is wrong
 * when the API said so. Fix the request; do not retry it unchanged.
 */
public class ValidationException extends ListingGuardApiException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    public ValidationException(ApiProblem problem) {
        super(problem);
        this.errors = read(problem);
    }

    /** The field errors of the problem's {@code errors} member, in the API's order; empty when it sent none. */
    public List<FieldError> errors() {
        return errors;
    }

    private static List<FieldError> read(ApiProblem problem) {
        if (!(problem.properties().get("errors") instanceof List<?> list)) {
            return List.of();
        }
        List<FieldError> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object field = map.get("field");
                Object message = map.get("message");
                out.add(new FieldError(field instanceof String f ? f : null, message instanceof String m ? m : null));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
