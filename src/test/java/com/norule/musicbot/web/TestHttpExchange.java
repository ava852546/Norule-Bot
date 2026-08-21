package com.norule.musicbot.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class TestHttpExchange extends HttpExchange {
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final Map<String, Object> attributes = new HashMap<>();
    private final String requestMethod;
    private final URI requestUri;
    private final InetSocketAddress remoteAddress;
    private InputStream requestBody;
    private OutputStream responseBody = new ByteArrayOutputStream();
    private int responseCode = -1;
    private boolean requestBodyOpened;

    public TestHttpExchange(String requestMethod, String requestUri, byte[] requestBody) {
        this(requestMethod, requestUri, requestBody,
                new InetSocketAddress("203.0.113.10", 54321));
    }

    public TestHttpExchange(String requestMethod,
                            String requestUri,
                            byte[] requestBody,
                            InetSocketAddress remoteAddress) {
        this.requestMethod = requestMethod;
        this.requestUri = URI.create(requestUri);
        this.requestBody = new ByteArrayInputStream(requestBody == null ? new byte[0] : requestBody);
        this.remoteAddress = remoteAddress;
    }

    public TestHttpExchange header(String name, String value) {
        requestHeaders.set(name, value);
        return this;
    }

    public int responseCode() {
        return responseCode;
    }

    public String responseBodyUtf8() {
        return responseBody instanceof ByteArrayOutputStream output
                ? output.toString(StandardCharsets.UTF_8)
                : "";
    }

    public boolean requestBodyOpened() {
        return requestBodyOpened;
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return requestUri;
    }

    @Override
    public String getRequestMethod() {
        return requestMethod;
    }

    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public void close() {
        // No resources are owned by the test exchange.
    }

    @Override
    public InputStream getRequestBody() {
        requestBodyOpened = true;
        return requestBody;
    }

    @Override
    public OutputStream getResponseBody() {
        return responseBody;
    }

    @Override
    public void sendResponseHeaders(int responseCode, long responseLength) {
        this.responseCode = responseCode;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 60000);
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    @Override
    public void setStreams(InputStream input, OutputStream output) {
        if (input != null) {
            requestBody = input;
        }
        if (output != null) {
            responseBody = output;
        }
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }
}
