package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.security.Security;

/** Settings > Zoho: connect (OAuth), callback, disconnect, organizations, optional own client; builder metadata. */
public final class WaZohoEvents {
    private static final String MODULE = WaZohoEvents.class.getName();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Object[]> META_CACHE = new ConcurrentHashMap<>();
    /** OAuth attempts keyed by the one-time state (kept server-side: the browser returns from Zoho without our
     *  SameSite=strict session cookie). Value: tenantId, userLoginId, apps, started-at. */
    private static final Map<String, Map<String, Object>> PENDING = new ConcurrentHashMap<>();
    /** Result message per workspace, shown once in Settings after returning from Zoho. */
    private static final Map<String, Map<String, Object>> FLASH = new ConcurrentHashMap<>();

    private WaZohoEvents() { }

    // ------------------------------------------------------------------ access helpers
    private static GenericValue userLogin(HttpServletRequest request) {
        return (GenericValue) request.getSession().getAttribute("userLogin");
    }

    private static boolean isAdmin(HttpServletRequest request) {
        Security security = (Security) request.getAttribute("security");
        GenericValue ul = userLogin(request);
        return ul != null && security != null && security.hasEntityPermission("WABOT", "_ADMIN", ul);
    }

    /** Current workspace of the user (same rule as the rest of the app). */
    static String currentTenant(HttpServletRequest request) {
        GenericValue ul = userLogin(request);
        if (ul == null) {
            return null;
        }
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        List<String> mine = WaUtil.getUserTenantIds(delegator, ul.getString("userLoginId"));
        Object cur = request.getSession().getAttribute("waTenantId");
        String t = cur == null ? null : cur.toString();
        if (isAdmin(request)) {
            return t;
        }
        return mine.contains(t) ? t : (mine.isEmpty() ? null : mine.get(0));
    }

    private static String ownerTenant(HttpServletRequest request) {
        String t = currentTenant(request);
        if (t == null) {
            return null;
        }
        if (isAdmin(request)) {
            return t;
        }
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        return "WA_OWNER".equals(WaUtil.getTenantRole(delegator, t, userLogin(request).getString("userLoginId"))) ? t : null;
    }

    static String redirectUri(HttpServletRequest request) {
        String cfg = WaUtil.prop("zoho.redirect.uri", "").trim();
        if (!cfg.isEmpty()) {
            return cfg;
        }
        String base = WaUtil.prop("brand.app.url", "").trim();
        if (base.isEmpty()) {
            int port = request.getServerPort();
            base = request.getScheme() + "://" + request.getServerName() + (port == 80 || port == 443 ? "" : ":" + port);
        }
        return base.replaceAll("/+$", "") + "/control/zohoCallback";
    }

    private static void flash(HttpServletRequest request, boolean ok, String msg) {
        request.getSession().setAttribute("zohoFlash", UtilMisc.toMap("ok", ok, "msg", msg));
    }

    private static void flashTenant(String tenantId, boolean ok, String msg) {
        if (tenantId != null) {
            FLASH.put(tenantId, UtilMisc.toMap("ok", ok, "msg", msg, "at", System.currentTimeMillis()));
        }
    }

    /** Settings: message waiting for this workspace (from the Zoho callback), or null. */
    public static Map<String, Object> takeFlash(String tenantId) {
        Map<String, Object> f = tenantId == null ? null : FLASH.remove(tenantId);
        return f != null && System.currentTimeMillis() - (Long) f.get("at") < 10 * 60_000L ? f : null;
    }

    /** Small page that forwards to Settings. A navigation started by our own page is same-site, so the
     *  (SameSite=strict) session cookie is sent again. */
    private static String bridge(HttpServletRequest request, HttpServletResponse response, boolean ok, String msg) {
        try {
            String target = request.getContextPath() + "/control/Settings#zoho";
            response.setContentType("text/html;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            String safe = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
            response.getWriter().write("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>FloChat</title>"
                    + "<meta http-equiv=\"refresh\" content=\"1;url=" + target + "\"></head>"
                    + "<body style=\"font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;height:90vh;color:#1d2939\">"
                    + "<div style=\"text-align:center;max-width:460px\"><div style=\"font-size:40px\">" + (ok ? "&#9989;" : "&#9888;&#65039;") + "</div>"
                    + "<p>" + safe + "</p><p><a href=\"" + target + "\">Continue to Settings</a></p></div>"
                    + "<script>setTimeout(function(){location.replace('" + target + "');},300);</script></body></html>");
            response.getWriter().flush();
        } catch (IOException e) {
            Debug.logError(e, MODULE);
        }
        return "none";
    }

    private static String toSettings(HttpServletRequest request, HttpServletResponse response) {
        try {
            response.sendRedirect(request.getContextPath() + "/control/Settings#zoho");
        } catch (IOException e) {
            Debug.logError(e, MODULE);
        }
        return "none";
    }

    // ------------------------------------------------------------------ connect
    /** POST zohoConnect apps=crm&apps=books... : redirects the owner to Zoho to approve access. */
    public static String connect(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        String tenantId = ownerTenant(request);
        if (tenantId == null) {
            flash(request, false, "Only the workspace owner can connect Zoho.");
            return toSettings(request, response);
        }
        List<String> apps = new ArrayList<>();
        String[] vals = request.getParameterValues("apps");
        if (vals != null) {
            for (String a : vals) {
                if (ZohoClient.APPS.contains(a) && !apps.contains(a)) {
                    apps.add(a);
                }
            }
        }
        if (apps.isEmpty()) {
            flash(request, false, "Tick at least one Zoho app to connect.");
            return toSettings(request, response);
        }
        try {
            GenericValue conn = ZohoClient.connection(delegator, tenantId);
            String clientId = ZohoClient.clientId(conn);
            if (UtilValidate.isEmpty(clientId) || (conn == null || UtilValidate.isEmpty(conn.getString("clientId"))) && !ZohoClient.platformConfigured()) {
                flash(request, false, "Zoho connection is not set up yet. Add your own Zoho API client under 'Advanced' or ask the FloChat administrator.");
                return toSettings(request, response);
            }
            byte[] b = new byte[24];
            RANDOM.nextBytes(b);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            Map<String, Object> pending = new HashMap<>();
            pending.put("tenantId", tenantId);
            pending.put("userLoginId", userLogin(request).getString("userLoginId"));
            pending.put("apps", String.join(",", apps));
            pending.put("at", System.currentTimeMillis());
            PENDING.values().removeIf(p -> System.currentTimeMillis() - (Long) p.get("at") > 15 * 60_000L);
            PENDING.put(state, pending);
            response.sendRedirect(ZohoClient.authorizeUrl(clientId, ZohoClient.scopesFor(apps), redirectUri(request), state));
            return "none";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            flash(request, false, "Could not start the Zoho connection: " + e.getMessage());
            return toSettings(request, response);
        }
    }

    /**
     * GET zohoCallback?code&accounts-server&state (Zoho redirects here). Works without our session cookie:
     * the one-time, unguessable state identifies the workspace and the owner who started the connection.
     */
    public static String callback(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        String state = request.getParameter("state");
        Map<String, Object> pending = state == null ? null : PENDING.remove(state);
        if (pending == null || System.currentTimeMillis() - (Long) pending.get("at") > 15 * 60_000L) {
            return bridge(request, response, false, "This Zoho connection link expired or was already used. Please click Connect Zoho again.");
        }
        String tenantId = (String) pending.get("tenantId");
        if (UtilValidate.isNotEmpty(request.getParameter("error"))) {
            flashTenant(tenantId, false, "Zoho access was not granted (" + request.getParameter("error") + ").");
            return bridge(request, response, false, "Zoho access was not granted.");
        }
        String code = request.getParameter("code");
        if (UtilValidate.isEmpty(code)) {
            flashTenant(tenantId, false, "Zoho did not send an authorization code. Please try again.");
            return bridge(request, response, false, "Zoho did not send an authorization code.");
        }
        String note = "";
        try {
            synchronized (ZohoClient.lock(tenantId)) {
                GenericValue conn = ZohoClient.connection(delegator, tenantId);
                String oldServer = conn == null ? null : conn.getString("accountsServer");
                String oldRefresh = conn == null ? null : conn.getString("refreshToken");
                if (conn == null) {
                    conn = delegator.makeValue("WaZohoConnection", UtilMisc.toMap("tenantId", tenantId));
                }
                String apps = (String) pending.get("apps");
                conn.set("apps", apps);
                conn.set("scopes", ZohoClient.scopesFor(WaUtil.splitCsv(apps)));
                conn.set("connectedBy", pending.get("userLoginId"));
                if (!WaUtil.splitCsv(apps).contains("books")) {
                    conn.set("booksOrgId", null);
                    conn.set("booksOrgName", null);
                }
                if (!WaUtil.splitCsv(apps).contains("inventory")) {
                    conn.set("inventoryOrgId", null);
                    conn.set("inventoryOrgName", null);
                }
                ZohoClient.completeAuthorization(delegator, conn, code, null, request.getParameter("accounts-server"), redirectUri(request));
                // the new grant works: now revoke the one it replaces
                if (UtilValidate.isNotEmpty(oldRefresh) && !oldRefresh.equals(conn.getString("refreshToken"))) {
                    ZohoClient.revoke(oldServer, oldRefresh);
                }
            }
            META_CACHE.keySet().removeIf(k -> k.startsWith(tenantId + "|"));
            // pick the default Books / Inventory organization automatically
            StringBuilder sb = new StringBuilder();
            for (String app : new String[] {"books", "inventory"}) {
                GenericValue c = ZohoClient.connection(delegator, tenantId);
                if (!WaUtil.splitCsv(c.getString("apps")).contains(app)) {
                    continue;
                }
                List<Map<String, String>> orgs = orgs(delegator, tenantId, app);
                if (orgs.isEmpty()) {
                    sb.append(" No ").append(ZohoClient.APP_NAMES.get(app)).append(" organization was found for this Zoho account.");
                    continue;
                }
                Map<String, String> pick = orgs.get(0);
                String current = c.getString(app + "OrgId");
                for (Map<String, String> o : orgs) {
                    if (o.get("id").equals(current) || current == null && "Y".equals(o.get("default"))) {
                        pick = o;
                    }
                }
                ZohoClient.updateFields(delegator, tenantId, UtilMisc.toMap(app + "OrgId", pick.get("id"), app + "OrgName", pick.get("name")), null);
            }
            note = sb.toString();
            GenericValue fresh = ZohoClient.connection(delegator, tenantId);
            String msg = "Zoho connected (" + ZohoClient.DC_NAMES.getOrDefault(fresh.getString("location"), fresh.getString("location"))
                    + " data centre). You can now add Zoho steps to your bots." + note;
            flashTenant(tenantId, true, msg);
            return bridge(request, response, true, "Zoho connected. Taking you back to FloChat...");
        } catch (ZohoClient.ZohoException e) {
            flashTenant(tenantId, false, e.getMessage());
            return bridge(request, response, false, e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            flashTenant(tenantId, false, "Could not finish the Zoho connection: " + e.getMessage());
            return bridge(request, response, false, "Could not finish the Zoho connection.");
        }
    }

    static List<Map<String, String>> orgs(Delegator delegator, String tenantId, String app) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            String path = "books".equals(app) ? "/books/v3/organizations" : "/inventory/v1/organizations";
            for (JsonNode o : ZohoClient.get(delegator, tenantId, app, path, null).path("organizations")) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", o.path("organization_id").asText());
                m.put("name", o.path("name").asText());
                m.put("default", o.path("is_default_org").asBoolean() ? "Y" : "N");
                out.add(m);
            }
        } catch (Exception e) {
            Debug.logWarning("Could not list " + app + " organizations: " + e.getMessage(), MODULE);
        }
        return out;
    }

    /** POST zohoDisconnect */
    public static String disconnect(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        String tenantId = ownerTenant(request);
        if (tenantId == null) {
            flash(request, false, "Only the workspace owner can disconnect Zoho.");
            return toSettings(request, response);
        }
        try {
            synchronized (ZohoClient.lock(tenantId)) {
            GenericValue conn = ZohoClient.connection(delegator, tenantId);
            if (conn != null) {
                ZohoClient.revoke(conn);
                for (String f : new String[] {"statusId", "location", "accountsServer", "apiDomain", "refreshToken", "accessToken", "accessExpiry",
                        "apps", "scopes", "booksOrgId", "booksOrgName", "inventoryOrgId", "inventoryOrgName", "lastError", "lastErrorDate", "connectedDate"}) {
                    conn.set(f, null);
                }
                conn.store();
            }
            }
            META_CACHE.keySet().removeIf(k -> k.startsWith(tenantId + "|"));
            flash(request, true, "Zoho disconnected. Zoho steps in your bots will take their 'not found / failed' path.");
        } catch (Exception e) {
            flash(request, false, "Could not disconnect: " + e.getMessage());
        }
        return toSettings(request, response);
    }

    /** POST zohoSaveOrgs booksOrgId, inventoryOrgId */
    public static String saveOrgs(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        String tenantId = ownerTenant(request);
        if (tenantId == null) {
            flash(request, false, "Only the workspace owner can change this.");
            return toSettings(request, response);
        }
        try {
            GenericValue conn = ZohoClient.connection(delegator, tenantId);
            for (String app : new String[] {"books", "inventory"}) {
                String want = request.getParameter(app + "OrgId");
                if (conn == null || UtilValidate.isEmpty(want) || !ZohoClient.hasApp(conn, app)) {
                    continue;
                }
                for (Map<String, String> o : orgs(delegator, tenantId, app)) {
                    if (o.get("id").equals(want)) {
                        ZohoClient.updateFields(delegator, tenantId, UtilMisc.toMap(app + "OrgId", o.get("id"), app + "OrgName", o.get("name")), null);
                    }
                }
            }
            flash(request, true, "Zoho organization saved.");
        } catch (Exception e) {
            flash(request, false, "Could not save: " + e.getMessage());
        }
        return toSettings(request, response);
    }

    /** POST zohoSaveClient clientId, clientSecret (empty clientId = use the FloChat client) */
    public static String saveClient(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        String tenantId = ownerTenant(request);
        if (tenantId == null) {
            flash(request, false, "Only the workspace owner can change this.");
            return toSettings(request, response);
        }
        String id = request.getParameter("clientId") == null ? "" : request.getParameter("clientId").trim();
        String secret = request.getParameter("clientSecret") == null ? "" : request.getParameter("clientSecret").trim();
        try {
            synchronized (ZohoClient.lock(tenantId)) {
            GenericValue conn = ZohoClient.connection(delegator, tenantId);
            if (conn == null) {
                conn = delegator.makeValue("WaZohoConnection", UtilMisc.toMap("tenantId", tenantId));
            }
            String before = conn.getString("clientId") == null ? "" : conn.getString("clientId");
            if (!before.equals(id) && UtilValidate.isNotEmpty(conn.getString("refreshToken"))) {
                // tokens belong to the old client: revoke and ask the owner to connect again
                ZohoClient.revoke(conn);
                for (String f : new String[] {"statusId", "refreshToken", "accessToken", "accessExpiry", "apps", "scopes"}) {
                    conn.set(f, null);
                }
            }
            if (id.isEmpty()) {
                conn.set("clientId", null);
                conn.set("clientSecret", null);
                delegator.createOrStore(conn);
                flash(request, true, "Using the FloChat Zoho client. Connect again if you changed clients.");
            } else {
                if (!id.matches("1000\\.[A-Z0-9]{10,60}")) {
                    flash(request, false, "That does not look like a Zoho client ID (it starts with 1000.).");
                    return toSettings(request, response);
                }
                if (secret.isEmpty() && UtilValidate.isEmpty(conn.getString("clientSecret"))) {
                    flash(request, false, "Also paste the client secret.");
                    return toSettings(request, response);
                }
                conn.set("clientId", id);
                if (!secret.isEmpty()) {
                    conn.set("clientSecret", secret);
                }
                delegator.createOrStore(conn);
                flash(request, true, "Your Zoho API client is saved. Now click Connect Zoho.");
            }
            }
        } catch (Exception e) {
            flash(request, false, "Could not save: " + e.getMessage());
        }
        return toSettings(request, response);
    }

    // ------------------------------------------------------------------ builder metadata (CRM modules / fields)
    /** GET zohoMeta?what=modules | what=fields&module=Leads (JSON) */
    public static String meta(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        ObjectNode out = WaUtil.JSON.createObjectNode();
        String tenantId = currentTenant(request);
        String flowId = request.getParameter("flowId");
        try {
            if (UtilValidate.isNotEmpty(flowId)) {
                GenericValue flow = EntityQuery.use(delegator).from("WaFlow").where("flowId", flowId).queryOne();
                GenericValue ul = userLogin(request);
                if (flow == null || ul == null || !(isAdmin(request)
                        || WaUtil.getUserTenantIds(delegator, ul.getString("userLoginId")).contains(flow.getString("tenantId")))) {
                    return json(response, 403, out.put("error", "no access"));
                }
                tenantId = flow.getString("tenantId");
            }
            if (tenantId == null) {
                return json(response, 403, out.put("error", "no workspace"));
            }
            String what = request.getParameter("what");
            String module = request.getParameter("module");
            if ("fields".equals(what) && (module == null || !module.matches("[A-Za-z][A-Za-z0-9_]{0,59}"))) {
                return json(response, 400, out.put("error", "bad module"));
            }
            String key = tenantId + "|" + what + "|" + (module == null ? "" : module);
            Object[] hit = META_CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 10 * 60_000L) {
                out.set("items", (JsonNode) hit[1]);
                return json(response, 200, out);
            }
            ArrayNode items = WaUtil.JSON.createArrayNode();
            if ("modules".equals(what)) {
                for (JsonNode m : ZohoClient.get(delegator, tenantId, "crm", "/crm/v8/settings/modules", null).path("modules")) {
                    if (m.path("api_supported").asBoolean(true) && m.path("creatable").asBoolean(true)
                            && !m.path("generated_type").asText("default").equals("linking")) {
                        items.addObject().put("value", m.path("api_name").asText()).put("label", m.path("plural_label").asText(m.path("api_name").asText()));
                    }
                }
            } else if ("fields".equals(what)) {
                for (JsonNode f : ZohoClient.get(delegator, tenantId, "crm", "/crm/v8/settings/fields", Map.of("module", module)).path("fields")) {
                    String type = f.path("data_type").asText("");
                    if (f.path("read_only").asBoolean(false) || f.path("field_read_only").asBoolean(false)
                            || List.of("ownerlookup", "lookup", "multiselectlookup", "fileupload", "imageupload", "subform", "formula", "autonumber", "profileimage").contains(type)) {
                        continue;
                    }
                    items.addObject().put("value", f.path("api_name").asText()).put("label", f.path("field_label").asText(f.path("api_name").asText()))
                        .put("type", type).put("required", f.path("system_mandatory").asBoolean(false));
                }
            } else {
                return json(response, 400, out.put("error", "unknown"));
            }
            META_CACHE.put(key, new Object[] {System.currentTimeMillis(), items});
            out.set("items", items);
            return json(response, 200, out);
        } catch (ZohoClient.ZohoException e) {
            return json(response, 200, out.put("error", e.getMessage()));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return json(response, 500, out.put("error", "failed"));
        }
    }

    /** Connection summary for pages (never includes tokens). */
    public static Map<String, Object> summary(Delegator delegator, String tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platformConfigured", ZohoClient.platformConfigured());
        m.put("connected", false);
        m.put("apps", List.of());
        try {
            GenericValue c = ZohoClient.connection(delegator, tenantId);
            if (c != null) {
                boolean connected = UtilValidate.isNotEmpty(c.getString("refreshToken"));
                m.put("connected", connected);
                m.put("status", c.getString("statusId"));
                m.put("apps", connected ? WaUtil.splitCsv(c.getString("apps")) : List.of());
                m.put("location", c.getString("location"));
                m.put("dcName", ZohoClient.DC_NAMES.getOrDefault(c.getString("location"), c.getString("location")));
                m.put("booksOrgId", c.getString("booksOrgId"));
                m.put("booksOrgName", c.getString("booksOrgName"));
                m.put("inventoryOrgId", c.getString("inventoryOrgId"));
                m.put("inventoryOrgName", c.getString("inventoryOrgName"));
                m.put("connectedDate", c.getTimestamp("connectedDate"));
                m.put("lastError", c.getString("lastError"));
                m.put("lastErrorDate", c.getTimestamp("lastErrorDate"));
                m.put("ownClientId", c.getString("clientId"));
            }
        } catch (Exception e) {
            Debug.logWarning(e, MODULE);
        }
        return m;
    }

    private static String json(HttpServletResponse response, int status, ObjectNode body) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(body.toString());
            response.getWriter().flush();
        } catch (IOException e) {
            Debug.logError(e, MODULE);
        }
        return "none";
    }

    static long now() {
        return UtilDateTime.nowTimestamp().getTime();
    }
}
