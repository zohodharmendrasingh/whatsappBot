package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.webapp.WebAppUtil;

/**
 * Tenant REST API, authenticated with an API key (header X-Api-Key or Authorization: Bearer).
 *
 * POST /api/v1/messages   {"to":"9198..","text":"Hi"} or
 *                                      {"to":"..","template":{"name":"order_update","language":"en","params":["A1"]}}
 * GET  /api/v1/contacts?limit=50
 * GET  /api/v1/messages?contactId=10000&limit=50
 */
public class WhatsAppApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String MODULE = WhatsAppApiServlet.class.getName();

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Delegator delegator = WebAppUtil.getDelegator(getServletContext());
        resp.setContentType("application/json;charset=UTF-8");
        try {
            String tenantId = authenticate(delegator, req);
            if (tenantId == null) {
                write(resp, 401, error("Invalid or missing API key"));
                return;
            }
            String path = req.getPathInfo() == null ? "" : req.getPathInfo();
            String method = req.getMethod();
            if ("POST".equals(method) && "/v1/messages".equals(path)) {
                sendMessage(delegator, tenantId, req, resp);
            } else if ("GET".equals(method) && "/v1/contacts".equals(path)) {
                listContacts(delegator, tenantId, req, resp);
            } else if ("GET".equals(method) && "/v1/messages".equals(path)) {
                listMessages(delegator, tenantId, req, resp);
            } else {
                write(resp, 404, error("Unknown endpoint " + method + " " + path));
            }
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            write(resp, 500, error("Internal error"));
        }
    }

    private String authenticate(Delegator delegator, HttpServletRequest req) throws GenericEntityException {
        String key = req.getHeader("X-Api-Key");
        String auth = req.getHeader("Authorization");
        if (UtilValidate.isEmpty(key) && auth != null && auth.startsWith("Bearer ")) {
            key = auth.substring(7).trim();
        }
        if (UtilValidate.isEmpty(key)) {
            return null;
        }
        GenericValue k = EntityQuery.use(delegator).from("WaApiKey").where("keyHash", WaUtil.sha256Hex(key), "isActive", "Y").queryFirst();
        if (k == null) {
            return null;
        }
        k.set("lastUsedDate", UtilDateTime.nowTimestamp());
        k.store();
        return k.getString("tenantId");
    }

    private void sendMessage(Delegator delegator, String tenantId, HttpServletRequest req, HttpServletResponse resp)
            throws IOException, GenericEntityException {
        JsonNode body;
        try (InputStream in = req.getInputStream()) {
            byte[] raw = (byte[]) req.getAttribute(WaRawBodyFilter.RAW_BODY_ATTR);
            body = WaUtil.JSON.readTree(raw != null ? raw : in.readNBytes(256 * 1024));
        } catch (Exception e) {
            write(resp, 400, error("Body must be JSON"));
            return;
        }
        String channelId = body.path("channelId").asText(null);
        GenericValue channel = UtilValidate.isNotEmpty(channelId)
                ? EntityQuery.use(delegator).from("WaChannel").where("channelId", channelId, "tenantId", tenantId).queryOne()
                : EntityQuery.use(delegator).from("WaChannel").where("tenantId", tenantId, "isActive", "Y").orderBy("channelId").queryFirst();
        if (channel == null) {
            write(resp, 400, error("No active WhatsApp number for this tenant"));
            return;
        }
        String to = WaUtil.normalizeNumber(body.path("to").asText(""));
        if (to == null || to.length() < 8) {
            write(resp, 400, error("'to' must be a full international number, e.g. 919876543210"));
            return;
        }
        GenericValue contact = WaMessenger.findOrCreateContact(delegator, channel, to, null);
        Map<String, Object> in = new HashMap<>();
        in.put("text", body.path("text").asText(null));
        in.put("mediaUrl", body.path("mediaUrl").asText(null));
        JsonNode tpl = body.path("template");
        if (tpl.isObject()) {
            in.put("templateName", tpl.path("name").asText(null));
            in.put("languageCode", tpl.path("language").asText("en"));
            List<String> params = new ArrayList<>();
            tpl.path("params").forEach(p -> params.add(p.asText()));
            in.put("templateParams", params);
        }
        Map<String, Object> r = WaServices.doSend(delegator, channel, contact, in, "API", Locale.getDefault());
        if (ServiceUtil.isError(r)) {
            write(resp, 422, error(ServiceUtil.getErrorMessage(r)));
            return;
        }
        ObjectNode ok = WaUtil.JSON.createObjectNode();
        ok.put("success", true).put("messageId", (String) r.get("messageId")).put("wamid", (String) r.get("wamid"))
                .put("contactId", (String) r.get("contactId"));
        write(resp, 200, ok);
    }

    private void listContacts(Delegator delegator, String tenantId, HttpServletRequest req, HttpServletResponse resp)
            throws IOException, GenericEntityException {
        ArrayNode arr = WaUtil.JSON.createArrayNode();
        for (GenericValue c : EntityQuery.use(delegator).from("WaContact").where("tenantId", tenantId)
                .orderBy("-lastMessageDate").maxRows(limit(req)).queryList()) {
            arr.addObject().put("contactId", c.getString("contactId")).put("waId", c.getString("waId"))
                    .put("name", c.getString("profileName")).put("optIn", c.getString("optInStatus"))
                    .put("botPaused", c.getString("botPaused"))
                    .put("lastMessageDate", String.valueOf(c.getTimestamp("lastMessageDate")))
                    .putPOJO("variables", WaUtil.readVars(c));
        }
        ObjectNode ok = WaUtil.JSON.createObjectNode();
        ok.set("contacts", arr);
        write(resp, 200, ok);
    }

    private void listMessages(Delegator delegator, String tenantId, HttpServletRequest req, HttpServletResponse resp)
            throws IOException, GenericEntityException {
        String contactId = req.getParameter("contactId");
        EntityQuery q = EntityQuery.use(delegator).from("WaMessage");
        q = UtilValidate.isEmpty(contactId) ? q.where("tenantId", tenantId) : q.where("tenantId", tenantId, "contactId", contactId);
        ArrayNode arr = WaUtil.JSON.createArrayNode();
        for (GenericValue m : q.orderBy("-createdDate").maxRows(limit(req)).queryList()) {
            arr.addObject().put("messageId", m.getString("messageId")).put("contactId", m.getString("contactId"))
                    .put("direction", m.getString("direction")).put("type", m.getString("messageType"))
                    .put("body", m.getString("body")).put("status", m.getString("deliveryStatus"))
                    .put("wamid", m.getString("wamid")).put("createdDate", String.valueOf(m.getTimestamp("createdDate")));
        }
        ObjectNode ok = WaUtil.JSON.createObjectNode();
        ok.set("messages", arr);
        write(resp, 200, ok);
    }

    private static int limit(HttpServletRequest req) {
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(req.getParameter("limit"))));
        } catch (Exception e) {
            return 50;
        }
    }

    private static ObjectNode error(String msg) {
        return WaUtil.JSON.createObjectNode().put("success", false).put("error", msg);
    }

    private static void write(HttpServletResponse resp, int status, JsonNode json) throws IOException {
        resp.setStatus(status);
        resp.getWriter().write(json.toString());
    }
}
