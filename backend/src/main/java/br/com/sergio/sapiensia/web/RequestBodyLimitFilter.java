package br.com.sergio.sapiensia.web;

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
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a body above 64 KiB on {@code /api/**} with 413 (SEC-015). It counts the bytes as the controller reads them,
 * so a chunked body without Content-Length is refused too and nothing is ever buffered; a declared Content-Length
 * above the limit is refused before the first byte. It runs ahead of Spring Security (only after the correlation ID)
 * so an oversized body is dropped before any work is done for it; it never reads the body itself. Multipart uploads
 * (their own 50 MB limit) and the Asaas webhook (its own limit, {@code app.asaas.webhook.max-body-bytes}) are skipped.
 *
 * <p>Runs inside the Spring Security chain, right after the {@code HeaderWriterFilter} (see SecurityConfig): the 413
 * leaves with the security headers, yet still before the CSRF check, the session check's database query and any
 * controller. It is public only so SecurityConfig can register it.
 */
@Component
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 64 * 1024;
    private static final String EXCEEDED_ATTRIBUTE = RequestBodyLimitFilter.class.getName() + ".EXCEEDED";
    private static final String WEBHOOK_PATH = "/api/webhooks/asaas";
    private static final Pattern UPLOAD_PATH = Pattern.compile("/api/admin/products/[^/]+/(file|cover)");
    private static final String PROBLEM =
            "{\"title\":\"Payload too large\",\"status\":413,\"code\":\"PAYLOAD_TOO_LARGE\"}";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.equals(WEBHOOK_PATH)) {
            return true;
        }
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase().startsWith("multipart/")
                && isUploadRoute(request.getMethod(), path);
    }

    /** The admin upload endpoints (product file and cover) have their own multipart limits. */
    private static boolean isUploadRoute(String method, String path) {
        return "POST".equals(method) && UPLOAD_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            writeTooLarge(response);
            return;
        }
        chain.doFilter(new CountingRequest(request), response);
    }

    /** True when the body of the current request went over the limit while it was being read. */
    static boolean exceeded() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null
                && attributes.getAttribute(EXCEEDED_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) != null;
    }

    static void writeTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        // the body was not consumed: the connection must not be reused
        response.setHeader("Connection", "close");
        response.getOutputStream().write(PROBLEM.getBytes(StandardCharsets.UTF_8));
    }

    /** Thrown to whoever reads the body past the limit; the answer is built from {@link #exceeded()}. */
    static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException() {
            super("Request body too large");
        }
    }

    private static final class CountingRequest extends HttpServletRequestWrapper {

        private ServletInputStream stream;

        CountingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), (HttpServletRequest) getRequest());
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CountingStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final HttpServletRequest request;
        private long total;

        CountingStream(ServletInputStream delegate, HttpServletRequest request) {
            this.delegate = delegate;
            this.request = request;
        }

        private void count(long read) throws IOException {
            if (read > 0) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    request.setAttribute(EXCEEDED_ATTRIBUTE, Boolean.TRUE);
                    throw new PayloadTooLargeException();
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            count(n);
            return n;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
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
