package com.thanhdatpb.listingguard.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies larger than the limit with 413. A body whose Content-Length is already too big is refused
 * without reading it; a body with no declared length (chunked) is cut off as soon as it grows past the limit.
 */
public final class BodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper mapper;
    private final Map<String, Long> uploadLimits;

    public BodyLimitFilter(long maxBytes, ObjectMapper mapper) {
        this(maxBytes, mapper, Map.of());
    }

    /**
     * @param uploadPath     the one path whose {@code POST} may carry a {@code multipart/form-data} body up to
     *                       {@code uploadMaxBytes} (the image checks, ADR-008); null for none. Every other request keeps
     *                       {@code maxBytes}
     * @param uploadMaxBytes the limit of that path
     */
    public BodyLimitFilter(long maxBytes, ObjectMapper mapper, String uploadPath, long uploadMaxBytes) {
        this(maxBytes, mapper, uploadPath == null ? Map.of() : Map.of(uploadPath, uploadMaxBytes));
    }

    /**
     * @param uploadLimits the paths whose {@code POST} may carry a {@code multipart/form-data} body up to the limit given for the
     *                     path: the image checks (ADR-008) and the two pages of the console's bulk check (ADR-029). A path is the
     *                     whole request path, not a prefix. Every other request keeps {@code maxBytes}
     */
    public BodyLimitFilter(long maxBytes, ObjectMapper mapper, Map<String, Long> uploadLimits) {
        this.maxBytes = maxBytes;
        this.mapper = mapper;
        this.uploadLimits = Map.copyOf(uploadLimits);
    }

    private long limitFor(HttpServletRequest request) {
        String type = request.getContentType();
        if ("POST".equals(request.getMethod()) && type != null
                && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) {
            Long upload = uploadLimits.get(request.getRequestURI());
            if (upload != null) {
                return upload;
            }
        }
        return maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        if (request.getContentLengthLong() > limit) {
            ProblemResponses.write(request, response, mapper, HttpStatus.CONTENT_TOO_LARGE, "payload-too-large",
                    "The request body is larger than the limit of " + limit + " bytes.");
            return;
        }
        chain.doFilter(new LimitedRequest(request, limit), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /** Counts the bytes read and fails once more than the limit have been read. */
    static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
