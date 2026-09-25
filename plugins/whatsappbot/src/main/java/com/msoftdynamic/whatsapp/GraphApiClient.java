package com.msoftdynamic.whatsapp;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.GenericValue;

/** Thin client for the Meta WhatsApp Cloud API (Graph API). */
public final class GraphApiClient {

    private static final String MODULE = GraphApiClient.class.getName();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private GraphApiClient() { }

    /** Result of a Graph call. */
    public static final class Result {
        private final int status;
        private final JsonNode body;

        Result(int status, JsonNode body) {
            this.status = status;
            this.body = body;
        }
        public int getStatus() {
            return status;
        }
        public JsonNode getBody() {
            return body;
        }
        public boolean isOk() {
            return status >= 200 && status < 300 && (body == null || !body.has("error"));
        }
        public String errorMessage() {
            if (body != null && body.has("error")) {
                JsonNode e = body.get("error");
                String details = e.path("error_data").path("details").asText("");
                return "Meta error " + e.path("code").asText() + ": " + e.path("message").asText()
                        + (details.isEmpty() ? "" : " (" + details + ")");
            }
            return "HTTP " + status + (body == null ? "" : ": " + body);
        }
    }

    public static String baseUrl(String version) {
        String v = UtilValidate.isNotEmpty(version) ? version : WaUtil.prop("graph.api.version", "v23.0");
        return WaUtil.prop("graph.api.base", "https://graph.facebook.com") + "/" + v;
    }

    public static String baseUrl(GenericValue channel) {
        return baseUrl(channel == null ? null : channel.getString("graphApiVersion"));
    }

    public static Result post(String url, String token, JsonNode payload) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(WaUtil.propInt("http.timeout.seconds", 20)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "{}" : payload.toString()));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return execute(b.build());
    }

    public static Result get(String url, String token, Map<String, String> query) {
        String full = url;
        if (query != null && !query.isEmpty()) {
            StringJoiner j = new StringJoiner("&");
            query.forEach((k, v) -> j.add(enc(k) + "=" + enc(v)));
            full = url + (url.contains("?") ? "&" : "?") + j;
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(full))
                .timeout(Duration.ofSeconds(WaUtil.propInt("http.timeout.seconds", 20))).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return execute(b.build());
    }

    /** POST /{phone-number-id}/messages using the channel's token. */
    public static Result sendMessage(GenericValue channel, ObjectNode message) {
        message.put("messaging_product", "whatsapp");
        String url = baseUrl(channel) + "/" + channel.getString("phoneNumberId") + "/messages";
        return post(url, channel.getString("accessToken"), message);
    }

    private static Result execute(HttpRequest req) {
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = null;
            String s = resp.body();
            if (UtilValidate.isNotEmpty(s)) {
                try {
                    body = WaUtil.JSON.readTree(s);
                } catch (Exception parse) {
                    body = WaUtil.JSON.createObjectNode().put("raw", s);
                }
            }
            if (resp.statusCode() >= 300) {
                Debug.logWarning("Graph API " + req.method() + " " + req.uri().getPath() + " -> " + resp.statusCode() + " " + s, MODULE);
            }
            return new Result(resp.statusCode(), body);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Result(599, WaUtil.JSON.createObjectNode().put("raw", "interrupted"));
        } catch (Exception e) {
            Debug.logError(e, "Graph API call failed: " + req.uri().getPath(), MODULE);
            ObjectNode err = WaUtil.JSON.createObjectNode();
            err.putObject("error").put("code", "NETWORK").put("message", e.getMessage());
            return new Result(599, err);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
