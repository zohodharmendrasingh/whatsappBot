package com.msoftdynamic.whatsapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;

/**
 * Minimal PayPal REST client (Orders v2): OAuth client-credentials token, create order, capture order, get order.
 * Configured in whatsappbot.properties: paypal.mode (sandbox|live), paypal.client.id, paypal.client.secret,
 * optional paypal.api.base to override the endpoint.
 */
public final class PayPalClient {

    private static final String MODULE = PayPalClient.class.getName();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static String cachedToken;
    private static long tokenExpiresAt;

    private PayPalClient() { }

    /** HTTP status + parsed JSON body. */
    public static final class Result {
        public final int status;
        public final JsonNode body;
        Result(int status, JsonNode body) {
            this.status = status;
            this.body = body;
        }
        public boolean isOk() {
            return status >= 200 && status < 300;
        }
        public String error() {
            if (body == null) {
                return "HTTP " + status;
            }
            String issue = body.path("details").path(0).path("issue").asText("");
            String msg = body.path("message").asText(body.path("error_description").asText(body.toString()));
            return (issue.isEmpty() ? "" : issue + ": ") + msg;
        }
        public String issue() {
            return body == null ? "" : body.path("details").path(0).path("issue").asText("");
        }
    }

    public static boolean isConfigured() {
        return UtilValidate.isNotEmpty(clientId()) && UtilValidate.isNotEmpty(WaUtil.prop("paypal.client.secret", ""));
    }

    public static String clientId() {
        return WaUtil.prop("paypal.client.id", "");
    }

    public static boolean isLive() {
        return "live".equalsIgnoreCase(WaUtil.prop("paypal.mode", "sandbox"));
    }

    public static String base() {
        String b = WaUtil.prop("paypal.api.base", "");
        if (UtilValidate.isEmpty(b)) {
            b = isLive() ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
        }
        return b.replaceAll("/+$", "");
    }

    private static synchronized String token() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpiresAt) {
            return cachedToken;
        }
        String basic = Base64.getEncoder().encodeToString(
                (clientId() + ":" + WaUtil.prop("paypal.client.secret", "")).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/v1/oauth2/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
        Result r = send(req);
        if (!r.isOk() || r.body == null || !r.body.hasNonNull("access_token")) {
            Debug.logError("PayPal token request failed: " + r.error(), MODULE);
            return null;
        }
        cachedToken = r.body.get("access_token").asText();
        tokenExpiresAt = now + Math.max(60, r.body.path("expires_in").asLong(3000) - 120) * 1000L;
        return cachedToken;
    }

    private static Result call(String method, String path, JsonNode payload, String requestId) {
        String t = token();
        if (t == null) {
            ObjectNode err = WaUtil.JSON.createObjectNode().put("message", "Could not authenticate with PayPal. Check paypal.client.id / paypal.client.secret / paypal.mode.");
            return new Result(401, err);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + t)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation");
        if (requestId != null) {
            b.header("PayPal-Request-Id", requestId);
        }
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.POST(HttpRequest.BodyPublishers.ofString(payload == null ? "{}" : payload.toString()));
        }
        return send(b.build());
    }

    public static Result createOrder(JsonNode order, String requestId) {
        return call("POST", "/v2/checkout/orders", order, requestId);
    }

    public static Result captureOrder(String orderId, String requestId) {
        return call("POST", "/v2/checkout/orders/" + orderId + "/capture", null, requestId);
    }

    public static Result getOrder(String orderId) {
        return call("GET", "/v2/checkout/orders/" + orderId, null, null);
    }

    private static Result send(HttpRequest req) {
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = null;
            if (UtilValidate.isNotEmpty(resp.body())) {
                try {
                    body = WaUtil.JSON.readTree(resp.body());
                } catch (Exception e) {
                    body = WaUtil.JSON.createObjectNode().put("message", resp.body());
                }
            }
            if (resp.statusCode() >= 300) {
                Debug.logWarning("PayPal " + req.method() + " " + req.uri().getPath() + " -> " + resp.statusCode() + " " + resp.body(), MODULE);
            }
            return new Result(resp.statusCode(), body);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Result(599, WaUtil.JSON.createObjectNode().put("message", "interrupted"));
        } catch (Exception e) {
            Debug.logError(e, "PayPal call failed: " + req.uri().getPath(), MODULE);
            return new Result(599, WaUtil.JSON.createObjectNode().put("message", "Could not reach PayPal: " + e.getMessage()));
        }
    }
}
