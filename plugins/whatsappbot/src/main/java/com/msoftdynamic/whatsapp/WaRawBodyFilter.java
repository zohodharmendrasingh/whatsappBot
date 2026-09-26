package com.msoftdynamic.whatsapp;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

/**
 * OFBiz's ContextFilter reads JSON request bodies into request attributes, which drains
 * the input stream. The webhook needs the exact raw bytes to verify Meta's
 * X-Hub-Signature-256, and the REST API needs the body too, so this filter (mapped
 * before ContextFilter on /webhook and /api/*) buffers the body and makes it re-readable.
 */
public class WaRawBodyFilter implements Filter {

    public static final String RAW_BODY_ATTR = "waRawBody";
    static final int MAX_BODY = 2 * 1024 * 1024;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String m = req.getMethod();
        if (!"POST".equalsIgnoreCase(m) && !"PATCH".equalsIgnoreCase(m) && !"PUT".equalsIgnoreCase(m)) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body;
        try (InputStream in = req.getInputStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        req.setAttribute(RAW_BODY_ATTR, body);
        chain.doFilter(new CachedBodyRequest(req, body), response);
    }

    /** Request whose body can be read any number of times. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }
                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }
                @Override
                public boolean isReady() {
                    return true;
                }
                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
