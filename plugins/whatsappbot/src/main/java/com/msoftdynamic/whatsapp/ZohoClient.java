package com.msoftdynamic.whatsapp;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Zoho OAuth (multi data centre) and REST calls for one workspace.
 * One platform client (zoho.client.id / zoho.client.secret) serves every customer; a workspace may use its own
 * client instead (WaZohoConnection.clientId/clientSecret). Tokens are stored encrypted per workspace.
 * All API URLs are built from the api_domain / accounts-server Zoho returned for that customer, and both are
 * checked against Zoho's hosts so a forged callback cannot send our client secret elsewhere.
 */
public final class ZohoClient {
    private static final String MODULE = ZohoClient.class.getName();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Pattern ACCOUNTS_HOST = Pattern.compile(
            "^https://accounts\\.(zoho\\.(com|in|eu|com\\.au|jp|sa|uk|com\\.cn)|zohocloud\\.ca)$");
    private static final Pattern API_HOST = Pattern.compile(
            "^https://www\\.zohoapis\\.(com|in|eu|com\\.au|jp|sa|uk|com\\.cn|ca)$|^https://www\\.zohocloud\\.ca$|^https://people\\.(zoho\\.(com|in|eu|com\\.au|jp|sa|uk|com\\.cn)|zohocloud\\.ca)$");

    /** One lock per workspace for every read-modify-write of its connection row. */
    public static Object lock(String tenantId) {
        return LOCKS.computeIfAbsent(tenantId, k -> new Object());
    }

    public static final List<String> APPS = List.of("crm", "books", "inventory", "people");
    public static final Map<String, String> APP_NAMES = Map.of("crm", "Zoho CRM", "books", "Zoho Books",
            "inventory", "Zoho Inventory", "people", "Zoho People");
    private static final Map<String, String> DEFAULT_SCOPES = Map.of(
            "crm", "ZohoCRM.modules.ALL,ZohoCRM.coql.READ,ZohoSearch.securesearch.READ,ZohoCRM.settings.modules.READ,ZohoCRM.settings.fields.READ",
            "books", "ZohoBooks.contacts.READ,ZohoBooks.invoices.READ,ZohoBooks.settings.READ",
            "inventory", "ZohoInventory.items.READ,ZohoInventory.salesorders.READ,ZohoInventory.contacts.READ,ZohoInventory.settings.READ",
            "people", "ZOHOPEOPLE.forms.READ,ZOHOPEOPLE.leave.ALL");
    public static final Map<String, String> DC_NAMES = new LinkedHashMap<>();
    static {
        DC_NAMES.put("com", "United States");
        DC_NAMES.put("in", "India");
        DC_NAMES.put("eu", "Europe");
        DC_NAMES.put("com.au", "Australia");
        DC_NAMES.put("jp", "Japan");
        DC_NAMES.put("ca", "Canada");
        DC_NAMES.put("sa", "Saudi Arabia");
        DC_NAMES.put("uk", "United Kingdom");
        DC_NAMES.put("com.cn", "China");
    }

    private ZohoClient() { }

    /** Thrown for any Zoho failure; message is safe to show to the workspace owner. */
    public static final class ZohoException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;
        public ZohoException(String msg, int status) {
            super(msg);
            this.status = status;
        }
    }

    // ------------------------------------------------------------------ config
    public static boolean platformConfigured() {
        return UtilValidate.isNotEmpty(WaUtil.prop("zoho.client.id", "").trim())
                && UtilValidate.isNotEmpty(WaUtil.prop("zoho.client.secret", "").trim());
    }

    public static String scopesFor(Iterable<String> apps) {
        Set<String> out = new LinkedHashSet<>();
        for (String a : apps) {
            String s = WaUtil.prop("zoho.scopes." + a, DEFAULT_SCOPES.getOrDefault(a, ""));
            for (String x : s.split(",")) {
                if (!x.trim().isEmpty()) {
                    out.add(x.trim());
                }
            }
        }
        return String.join(",", out);
    }

    static String clientId(GenericValue conn) {
        return conn != null && UtilValidate.isNotEmpty(conn.getString("clientId")) ? conn.getString("clientId").trim()
                : WaUtil.prop("zoho.client.id", "").trim();
    }

    /** Multi-DC clients can have one secret per data centre: zoho.client.secret.in, ... (falls back to zoho.client.secret). */
    static String clientSecret(GenericValue conn, String location) {
        if (conn != null && UtilValidate.isNotEmpty(conn.getString("clientId"))) {
            return conn.getString("clientSecret") == null ? "" : conn.getString("clientSecret").trim();
        }
        String perDc = UtilValidate.isEmpty(location) ? "" : WaUtil.prop("zoho.client.secret." + location, "").trim();
        return perDc.isEmpty() ? WaUtil.prop("zoho.client.secret", "").trim() : perDc;
    }

    public static String authorizeUrl(String clientId, String scopes, String redirectUri, String state) {
        String base = WaUtil.prop("zoho.accounts.url", "https://accounts.zoho.com").replaceAll("/+$", "");
        return base + "/oauth/v2/auth?response_type=code&access_type=offline&prompt=consent"
                + "&client_id=" + enc(clientId) + "&scope=" + enc(scopes) + "&redirect_uri=" + enc(redirectUri) + "&state=" + enc(state);
    }

    static boolean trustedAccounts(String url) {
        return url != null && (ACCOUNTS_HOST.matcher(url.replaceAll("/+$", "")).matches() || testHost(url));
    }

    static boolean trustedApi(String url) {
        return url != null && (API_HOST.matcher(url.replaceAll("/+$", "")).matches() || testHost(url));
    }

    /** Sandbox only: zoho.test.hosts=http://127.0.0.1:18083 */
    private static boolean testHost(String url) {
        for (String h : WaUtil.prop("zoho.test.hosts", "").split(",")) {
            if (!h.trim().isEmpty() && url.replaceAll("/+$", "").equals(h.trim())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ OAuth
    /** Exchanges the authorization code at the customer's data centre and stores the tokens. */
    public static void completeAuthorization(Delegator delegator, GenericValue conn, String code, String location,
            String accountsServer, String redirectUri) throws ZohoException, GenericEntityException {
        String acc = UtilValidate.isEmpty(accountsServer) ? WaUtil.prop("zoho.accounts.url", "https://accounts.zoho.com") : accountsServer;
        acc = acc.replaceAll("/+$", "");
        if (!trustedAccounts(acc)) {
            throw new ZohoException("Unexpected Zoho accounts server: " + acc, 400);
        }
        String dc = dcFromAccounts(acc);  // never trust the separate 'location' parameter
        if (!DC_NAMES.containsKey(dc)) {
            throw new ZohoException("Unknown Zoho data centre: " + dc, 400);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", clientId(conn));
        form.put("client_secret", clientSecret(conn, dc));
        form.put("redirect_uri", redirectUri);
        form.put("code", code);
        JsonNode r = postForm(acc + "/oauth/v2/token", form);
        if (r.has("error") || !r.hasNonNull("access_token")) {
            throw new ZohoException("Zoho sign-in failed: " + r.path("error").asText("no token returned")
                    + ("invalid_client".equals(r.path("error").asText()) ? " (check the client ID/secret and that this data centre is enabled for the client)" : ""), 400);
        }
        if (!r.hasNonNull("refresh_token")) {
            throw new ZohoException("Zoho did not return a refresh token. Please try connecting again.", 400);
        }
        String apiDomain = r.path("api_domain").asText("https://www.zohoapis.com").replaceAll("/+$", "");
        if (!trustedApi(apiDomain)) {
            throw new ZohoException("Unexpected Zoho API domain: " + apiDomain, 400);
        }
        conn.set("location", dc);
        conn.set("accountsServer", acc);
        conn.set("apiDomain", apiDomain);
        conn.set("refreshToken", r.path("refresh_token").asText());
        conn.set("accessToken", r.path("access_token").asText());
        conn.set("accessExpiry", new Timestamp(System.currentTimeMillis() + r.path("expires_in").asLong(3600) * 1000L));
        conn.set("statusId", "CONNECTED");
        conn.set("lastError", null);
        conn.set("lastErrorDate", null);
        conn.set("connectedDate", UtilDateTime.nowTimestamp());
        delegator.createOrStore(conn);
    }

    static String dcFromAccounts(String acc) {
        if (acc.contains("zohocloud.ca")) {
            return "ca";
        }
        int i = acc.indexOf("accounts.zoho.");
        if (i < 0) {
            return testHost(acc) ? WaUtil.prop("zoho.test.location", "in") : "com";
        }
        return acc.substring(i + "accounts.zoho.".length()).replaceAll("/.*$", "");
    }

    public static void revoke(GenericValue conn) {
        if (conn != null) {
            revoke(conn.getString("accountsServer"), conn.getString("refreshToken"));
        }
    }

    public static void revoke(String accountsServer, String refreshToken) {
        try {
            if (UtilValidate.isNotEmpty(refreshToken) && trustedAccounts(accountsServer)) {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("token", refreshToken);
                // Zoho documents both paths across its docs; revoking twice is harmless (second call just says invalid token)
                postForm(accountsServer + "/oauth/v2/token/revoke", form);
                postForm(accountsServer + "/oauth/v2/revoke/token", form);
            }
        } catch (Exception e) {
            Debug.logWarning(e, "Zoho revoke failed (ignored)", MODULE);
        }
    }

    public static GenericValue connection(Delegator delegator, String tenantId) throws GenericEntityException {
        return tenantId == null ? null : EntityQuery.use(delegator).from("WaZohoConnection").where("tenantId", tenantId).queryOne();
    }

    public static boolean hasApp(GenericValue conn, String app) {
        return conn != null && "CONNECTED".equals(conn.getString("statusId")) && WaUtil.splitCsv(conn.getString("apps")).contains(app);
    }

    /**
     * Valid access token for the workspace. Refreshes when it is about to expire, or when Zoho rejected
     * {@code rejected} and nobody refreshed it in the meantime (avoids refresh storms / Zoho's refresh limits).
     */
    static String accessToken(Delegator delegator, String tenantId, String rejected) throws ZohoException, GenericEntityException {
        synchronized (lock(tenantId)) {
            GenericValue conn = connection(delegator, tenantId);
            if (conn == null || UtilValidate.isEmpty(conn.getString("refreshToken"))) {
                throw new ZohoException("Zoho is not connected for this workspace.", 401);
            }
            String current = conn.getString("accessToken");
            Timestamp exp = conn.getTimestamp("accessExpiry");
            boolean fresh = UtilValidate.isNotEmpty(current) && exp != null && exp.getTime() - 120_000L > System.currentTimeMillis();
            if (fresh && (rejected == null || !rejected.equals(current))) {
                return current;
            }
            String acc = conn.getString("accountsServer");
            if (!trustedAccounts(acc)) {
                throw new ZohoException("Untrusted Zoho accounts server " + acc, 400);
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("client_id", clientId(conn));
            form.put("client_secret", clientSecret(conn, conn.getString("location")));
            form.put("refresh_token", conn.getString("refreshToken"));
            JsonNode r = postForm(acc + "/oauth/v2/token", form);
            if (!r.hasNonNull("access_token")) {
                String err = r.path("error").asText("unknown error");
                boolean dead = err.contains("invalid_code") || err.contains("invalid_client") || err.contains("invalid_token");
                String msg = dead ? "Zoho access was revoked or expired. Please reconnect Zoho in Settings." : "Zoho token refresh failed: " + err;
                updateFields(delegator, tenantId, dead ? Map.of("statusId", "ERROR") : Map.of(), msg);
                throw new ZohoException(dead ? "Zoho needs to be reconnected in Settings." : "Zoho token refresh failed: " + err, 401);
            }
            conn.set("accessToken", r.path("access_token").asText());
            conn.set("accessExpiry", new Timestamp(System.currentTimeMillis() + r.path("expires_in").asLong(3600) * 1000L));
            conn.store();  // row was read under the workspace lock, which connect/disconnect also take
            return conn.getString("accessToken");
        }
    }

    /** Updates plain (non-token) fields plus the last error, without writing back a possibly stale row. */
    static void updateFields(Delegator delegator, String tenantId, Map<String, Object> fields, String error) {
        try {
            Map<String, Object> set = new LinkedHashMap<>(fields);
            if (error != null) {
                set.put("lastError", error.length() > 1000 ? error.substring(0, 1000) : error);
                set.put("lastErrorDate", UtilDateTime.nowTimestamp());
            }
            if (!set.isEmpty()) {
                delegator.storeByCondition("WaZohoConnection", set,
                        org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("tenantId", tenantId));
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }
    }

    // ------------------------------------------------------------------ REST
    /** Base URL for an app: CRM/Books/Inventory use api_domain; People uses people.zoho.{dc}. */
    static String base(GenericValue conn, String app) {
        if ("people".equals(app)) {
            String override = WaUtil.prop("zoho.people.base", "").trim();
            if (!override.isEmpty()) {
                return override.replaceAll("/+$", "");
            }
            String acc = conn.getString("accountsServer");
            return acc.replace("://accounts.", "://people.");
        }
        return conn.getString("apiDomain");
    }

    public static JsonNode get(Delegator delegator, String tenantId, String app, String path, Map<String, String> query)
            throws ZohoException, GenericEntityException {
        return call(delegator, tenantId, app, "GET", path, query, null);
    }

    public static JsonNode post(Delegator delegator, String tenantId, String app, String path, Map<String, String> query, JsonNode body)
            throws ZohoException, GenericEntityException {
        return call(delegator, tenantId, app, "POST", path, query, body);
    }

    private static JsonNode call(Delegator delegator, String tenantId, String app, String method, String path,
            Map<String, String> query, JsonNode body) throws ZohoException, GenericEntityException {
        GenericValue conn = connection(delegator, tenantId);
        if (!hasApp(conn, app)) {
            throw new ZohoException(APP_NAMES.getOrDefault(app, "Zoho") + " is not connected for this workspace.", 401);
        }
        String base = base(conn, app);
        if (!trustedApi(base)) {
            throw new ZohoException("Untrusted Zoho host " + base, 400);
        }
        StringJoiner qs = new StringJoiner("&");
        if (query != null) {
            query.forEach((k, v) -> {
                if (v != null) {
                    qs.add(enc(k) + "=" + enc(v));
                }
            });
        }
        String url = base + path + (qs.length() > 0 ? "?" + qs : "");
        String rejected = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken(delegator, tenantId, rejected);
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(WaUtil.propInt("zoho.timeout.seconds", 15)))
                    .header("Authorization", "Zoho-oauthtoken " + token).header("Accept", "application/json");
            if ("POST".equals(method) && body == null) {
                rb.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.noBody());
            } else if ("POST".equals(method)) {
                rb.header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            } else {
                rb.GET();
            }
            HttpResponse<String> res;
            try {
                res = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new ZohoException("Could not reach Zoho: " + e.getClass().getSimpleName(), 503);
            }
            String text = res.body() == null ? "" : res.body();
            // refresh only when the token itself was rejected (401 is also used for scope/permission errors)
            if (res.statusCode() == 401 && attempt == 0
                    && text.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(invalid_token|invalid oauth ?token|invalid_oauthtoken|token expired|expired token).*")) {
                rejected = token;
                continue;
            }
            if (res.statusCode() == 204 || text.isBlank()) {
                return WaUtil.JSON.createObjectNode();
            }
            JsonNode json;
            try {
                json = WaUtil.JSON.readTree(text);
            } catch (Exception e) {
                throw new ZohoException(APP_NAMES.getOrDefault(app, "Zoho") + " returned an unreadable answer (" + res.statusCode() + ")", res.statusCode());
            }
            if (res.statusCode() / 100 != 2) {
                JsonNode d0 = json.path("data").path(0);
                String msg = d0.path("message").asText("");
                if (!msg.isEmpty() && d0.path("details").hasNonNull("api_name")) {
                    msg += " (" + d0.path("details").path("api_name").asText() + ")";
                }
                if (msg.isEmpty()) {
                    msg = json.path("message").asText(json.path("error").asText(json.path("response").path("message").asText("")));
                }
                if (msg.isEmpty()) {
                    msg = "error " + res.statusCode();
                }
                String full = APP_NAMES.getOrDefault(app, "Zoho") + ": " + msg;
                if (res.statusCode() == 401 || res.statusCode() == 403 || res.statusCode() >= 500) {
                    updateFields(delegator, tenantId, Map.of(), full);  // setup problems show in Settings; "not found"s don't
                }
                throw new ZohoException(full, res.statusCode());
            }
            return json;
        }
        throw new ZohoException("Zoho rejected the access token.", 401);
    }

    private static JsonNode postForm(String url, Map<String, String> form) throws ZohoException {
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(enc(k) + "=" + enc(v == null ? "" : v)));
        try {
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            String b = res.body() == null || res.body().isBlank() ? "{}" : res.body();
            return WaUtil.JSON.readTree(b);
        } catch (Exception e) {
            throw new ZohoException("Could not reach Zoho accounts: " + e.getClass().getSimpleName(), 503);
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Digits only, without the international "00" prefix or a national trunk "0". */
    static String digits(String s) {
        if (s == null) {
            return "";
        }
        String d = s.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) {
            d = d.substring(2);
        }
        while (d.startsWith("0")) {
            d = d.substring(1);
        }
        return d;
    }

    /** Search key (last 10 digits) for Zoho queries; every hit is confirmed with samePhone. */
    public static String phoneKey(String s) {
        String d = digits(s);
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    /**
     * True when both are the same phone. Numbers that both carry a country code must match fully
     * (+1 917 555 1234 never equals +91 91755 51234). A number stored without country code matches when the
     * other ends with it and differs only by a 1-3 digit country code; zoho.default.country.code (e.g. 91)
     * restricts that to one country.
     */
    public static boolean samePhone(String a, String b) {
        return samePhone(a, b, WaUtil.prop("zoho.default.country.code", ""));
    }

    /** @param countryCode country of numbers stored without one (e.g. "91"); empty = any 1-3 digit code */
    public static boolean samePhone(String a, String b, String countryCode) {
        String x = digits(a);
        String y = digits(b);
        if (x.length() < 7 || y.length() < 7) {
            return false;
        }
        if (x.equals(y)) {
            return true;
        }
        String longer = x.length() >= y.length() ? x : y;
        String shorter = longer == x ? y : x;
        int cc = longer.length() - shorter.length();
        if (shorter.length() > 10 || shorter.length() < 8 || cc < 1 || cc > 3 || !longer.endsWith(shorter)) {
            return false;
        }
        String def = countryCode == null ? "" : countryCode.replaceAll("[^0-9]", "");
        return def.isEmpty() || longer.equals(def + shorter);
    }

    /**
     * Country code assumed for numbers saved without one: zoho.default.country.code, else taken from the
     * business's own WhatsApp number (e.g. +91 98765 43210 -> 91, since local numbers have the same length).
     */
    public static String homeCountryCode(String businessNumber, String nationalSample) {
        String cfg = WaUtil.prop("zoho.default.country.code", "").replaceAll("[^0-9]", "");
        if (!cfg.isEmpty()) {
            return cfg;
        }
        String biz = digits(businessNumber);
        String nat = digits(nationalSample);
        int cc = biz.length() - nat.length();
        return cc >= 1 && cc <= 3 && biz.length() >= 10 ? biz.substring(0, cc) : "";
    }
}
