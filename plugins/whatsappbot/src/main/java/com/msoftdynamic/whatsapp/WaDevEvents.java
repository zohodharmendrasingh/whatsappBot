package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
import org.apache.ofbiz.service.LocalDispatcher;

/** Developer portal: API keys, outgoing webhooks, deliveries and the "Try it" API console. */
public final class WaDevEvents {
    private static final String MODULE = WaDevEvents.class.getName();
    private static final int MAX_WEBHOOKS = 5;
    private static final int MAX_KEYS = 20;

    private WaDevEvents() { }

    private static Delegator del(HttpServletRequest r) {
        return (Delegator) r.getAttribute("delegator");
    }

    private static String p(HttpServletRequest r, String n) {
        String v = r.getParameter(n);
        return v == null ? null : v.trim();
    }

    private static WaCrmEvents.Who owner(HttpServletRequest request) {
        WaCrmEvents.Who w = WaCrmEvents.who(request);
        return w != null && w.owner ? w : null;
    }

    // ------------------------------------------------------------------ API keys
    public static String devKeyCreate(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = owner(request);
            if (w == null) {
                return error(response, 403, "Only the workspace owner can create API keys.");
            }
            if (EntityQuery.use(delegator).from("WaApiKey").where("tenantId", w.tenantId, "isActive", "Y").queryCount() >= MAX_KEYS) {
                return error(response, 400, "You have " + MAX_KEYS + " active keys. Revoke one you no longer use.");
            }
            String desc = p(request, "description");
            String key = WaUtil.newApiKey();
            String id = delegator.getNextSeqId("WaApiKey");
            delegator.create("WaApiKey", UtilMisc.toMap("apiKeyId", id, "tenantId", w.tenantId, "keyHash", WaUtil.sha256Hex(key),
                    "keyPrefix", key.substring(0, 12) + "…", "description", UtilValidate.isEmpty(desc) ? "API key" : WaAgentAi.cut(desc, 100),
                    "isActive", "Y", "createdDate", UtilDateTime.nowTimestamp()));
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true).put("apiKeyId", id).put("key", key));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not create the key.");
        }
    }

    public static String devKeyRevoke(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = owner(request);
            if (w == null) {
                return error(response, 403, "Only the workspace owner can revoke API keys.");
            }
            GenericValue k = EntityQuery.use(delegator).from("WaApiKey").where("apiKeyId", p(request, "apiKeyId")).queryOne();
            if (k == null || !w.tenantId.equals(k.getString("tenantId"))) {
                return error(response, 404, "Key not found.");
            }
            k.set("isActive", "N");
            k.store();
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not revoke the key.");
        }
    }

    // ------------------------------------------------------------------ webhooks
    private static GenericValue hook(Delegator delegator, WaCrmEvents.Who w, String id) throws Exception {
        if (w == null || UtilValidate.isEmpty(id)) {
            return null;
        }
        GenericValue h = EntityQuery.use(delegator).from("WaWebhook").where("webhookId", id).queryOne();
        return h != null && w.tenantId.equals(h.getString("tenantId")) ? h : null;
    }

    private static String newSecret() {
        return "whsec_" + WaUtil.newApiKey().replaceFirst("^wab_", "");
    }

    /** POST webhookSave: webhookId (edit), url, events (repeatable), description, isActive */
    public static String webhookSave(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = owner(request);
            if (w == null) {
                return error(response, 403, "Only the workspace owner can manage webhooks.");
            }
            String url = p(request, "url");
            if (UtilValidate.isEmpty(url) || (!url.toLowerCase().startsWith("https://") && !WaUtil.propTrue("ai.kb.allow.private.hosts"))) {
                return error(response, 400, "Use an https:// address for your webhook.");
            }
            try {
                WaKnowledge.checkUrl(url);
            } catch (WaKnowledge.KbException e) {
                return error(response, 400, e.getMessage());
            }
            List<String> events = WaWebhooks.cleanEvents(request.getParameterValues("events"));
            if (events.isEmpty()) {
                return error(response, 400, "Choose at least one event.");
            }
            GenericValue h = hook(delegator, w, p(request, "webhookId"));
            String secret = null;
            if (h == null) {
                if (EntityQuery.use(delegator).from("WaWebhook").where("tenantId", w.tenantId).queryCount() >= MAX_WEBHOOKS) {
                    return error(response, 400, "You can have up to " + MAX_WEBHOOKS + " webhooks.");
                }
                secret = newSecret();
                h = delegator.makeValue("WaWebhook", UtilMisc.toMap("webhookId", delegator.getNextSeqId("WaWebhook"), "tenantId", w.tenantId,
                        "secret", secret, "failCount", 0L, "createdBy", w.userId, "createdDate", UtilDateTime.nowTimestamp()));
            }
            h.set("url", url);
            h.set("events", String.join(",", events));
            h.set("description", WaAgentAi.cut(p(request, "description"), 200));
            h.set("isActive", "N".equals(p(request, "isActive")) ? "N" : "Y");
            delegator.createOrStore(h);
            WaWebhooks.invalidate(w.tenantId);
            ObjectNode out = WaUtil.JSON.createObjectNode().put("ok", true).put("webhookId", h.getString("webhookId"));
            if (secret != null) {
                out.put("secret", secret);
            }
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not save the webhook.");
        }
    }

    /** POST webhookAction: webhookId, do = delete | rotate | test | enable | disable */
    public static String webhookAction(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = owner(request);
            GenericValue h = hook(delegator, w, p(request, "webhookId"));
            if (h == null) {
                return error(response, 404, w == null ? "Only the workspace owner can manage webhooks." : "Webhook not found.");
            }
            ObjectNode out = WaUtil.JSON.createObjectNode().put("ok", true);
            switch (String.valueOf(p(request, "do"))) {
            case "delete":
                delegator.removeByAnd("WaWebhookDelivery", UtilMisc.toMap("webhookId", h.getString("webhookId")));
                h.remove();
                break;
            case "rotate":
                String s = newSecret();
                h.set("secret", s);
                h.store();
                out.put("secret", s);
                break;
            case "enable":
            case "disable":
                h.set("isActive", "enable".equals(p(request, "do")) ? "Y" : "N");
                h.set("failCount", 0L);
                h.store();
                break;
            case "test":
                int[] r = WaWebhooks.ping(delegator, h);
                GenericValue last = EntityQuery.use(delegator).from("WaWebhookDelivery").where("webhookId", h.getString("webhookId"))
                        .orderBy("-createdDate").queryFirst();
                out.put("status", r[0]).put("ms", r[1]).put("response", last == null ? "" : last.getString("responseText"))
                        .put("ok", r[0] >= 200 && r[0] < 300);
                break;
            default:
                return error(response, 400, "Unknown action.");
            }
            WaWebhooks.invalidate(w.tenantId);
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not update the webhook.");
        }
    }

    /** GET webhookDeliveries?webhookId= : last 50 deliveries with payloads */
    public static String webhookDeliveries(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = owner(request);
            GenericValue h = hook(delegator, w, p(request, "webhookId"));
            if (h == null) {
                return error(response, 404, "Webhook not found.");
            }
            ArrayNode arr = WaUtil.JSON.createArrayNode();
            for (GenericValue d : EntityQuery.use(delegator).from("WaWebhookDelivery").where("webhookId", h.getString("webhookId"))
                    .orderBy("-createdDate").maxRows(50).queryList()) {
                arr.addObject().put("deliveryId", d.getString("deliveryId")).put("event", d.getString("eventType"))
                        .put("status", d.getString("statusId")).put("code", d.get("statusCode") == null ? 0 : d.getLong("statusCode"))
                        .put("attempts", d.get("attempts") == null ? 0 : d.getLong("attempts")).put("ms", d.get("durationMs") == null ? 0 : d.getLong("durationMs"))
                        .put("at", WaCrmEvents.fmt(d.getTimestamp("createdDate"))).put("payload", d.getString("payload"))
                        .put("response", d.getString("responseText"));
            }
            ObjectNode out = WaUtil.JSON.createObjectNode().put("ok", true);
            out.set("deliveries", arr);
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not load deliveries.");
        }
    }

    /** POST webhookRedeliver: deliveryId - send the same payload again */
    public static String webhookRedeliver(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = owner(request);
            GenericValue d = EntityQuery.use(delegator).from("WaWebhookDelivery").where("deliveryId", p(request, "deliveryId")).queryOne();
            if (w == null || d == null || !w.tenantId.equals(d.getString("tenantId"))) {
                return error(response, 404, "Delivery not found.");
            }
            int[] r = WaWebhooks.deliver(delegator, d.getString("webhookId"), d.getString("eventType"), d.getString("payload"), null, 99);
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", r[0] >= 200 && r[0] < 300).put("status", r[0]).put("ms", r[1]));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not redeliver.");
        }
    }

    // ------------------------------------------------------------------ API console
    /** POST devTry: method, path (/v1/...), body - runs the API for the logged-in workspace (really sends). */
    public static String devTry(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            String method = String.valueOf(p(request, "method")).toUpperCase();
            if (!List.of("GET", "POST", "PATCH").contains(method)) {
                return error(response, 400, "Method must be GET, POST or PATCH.");
            }
            String full = String.valueOf(p(request, "path"));
            if (!full.startsWith("/v1/")) {
                return error(response, 400, "Path must start with /v1/");
            }
            String path = full;
            Map<String, String> q = new HashMap<>();
            int qi = full.indexOf('?');
            if (qi >= 0) {
                path = full.substring(0, qi);
                for (String kv : full.substring(qi + 1).split("&")) {
                    String[] p2 = kv.split("=", 2);
                    if (!p2[0].isEmpty()) {
                        q.put(java.net.URLDecoder.decode(p2[0], StandardCharsets.UTF_8), p2.length > 1 ? java.net.URLDecoder.decode(p2[1], StandardCharsets.UTF_8) : "");
                    }
                }
            }
            String body = request.getParameter("body");
            if (WaApi.rateLimited("CONSOLE-" + w.tenantId) > 0) {
                return error(response, 429, "Too many requests. Wait a minute.");
            }
            long t0 = System.currentTimeMillis();
            WaApi.Res res = WaApi.handle(delegator, dispatcher, w.tenantId, method, path, q, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
            long ms = System.currentTimeMillis() - t0;
            WaApi.log(delegator, w.tenantId, "CONSOLE", method, full, res.status, ms,
                    res.status >= 400 ? res.body.path("error").asText(null) : null, "console:" + w.userId);
            ObjectNode out = WaUtil.JSON.createObjectNode().put("ok", true).put("status", res.status).put("ms", ms);
            out.set("body", res.body);
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not run the request.");
        }
    }

    // ------------------------------------------------------------------ helpers
    private static String json(HttpServletResponse response, int status, JsonNode body) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(WaUtil.JSON.writeValueAsString(body));
            response.getWriter().flush();
        } catch (IOException e) {
            Debug.logError(e, MODULE);
        }
        return "none";
    }

    private static String error(HttpServletResponse response, int status, String msg) {
        return json(response, status, WaUtil.JSON.createObjectNode().put("ok", false).put("error", msg));
    }
}
