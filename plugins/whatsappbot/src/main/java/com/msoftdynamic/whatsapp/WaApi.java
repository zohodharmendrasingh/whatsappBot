package com.msoftdynamic.whatsapp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * REST API v1 (used by the servlet with an API key, and by the developer portal's "Try it" console with the login session).
 * Every call is scoped to one workspace.
 */
public final class WaApi {
    private static final String MODULE = WaApi.class.getName();

    private WaApi() { }

    /** Result of a call. */
    public static final class Res {
        public final int status;
        public final JsonNode body;
        Res(int status, JsonNode body) {
            this.status = status;
            this.body = body;
        }
    }

    static Res ok(JsonNode b) {
        return new Res(200, b);
    }

    static Res err(int status, String code, String msg) {
        ObjectNode o = WaUtil.JSON.createObjectNode().put("success", false).put("error", msg).put("code", code);
        return new Res(status, o);
    }

    private static final Pattern CONTACT_ID = Pattern.compile("^/v1/contacts/([A-Za-z0-9_-]{1,40})$");
    private static final Pattern BROADCAST_ID = Pattern.compile("^/v1/broadcasts/([A-Za-z0-9_-]{1,40})$");

    public static Res handle(Delegator delegator, LocalDispatcher dispatcher, String tenantId, String method, String path,
                             Map<String, String> query, byte[] rawBody) {
        try {
            JsonNode body = null;
            if (("POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method))) {
                try {
                    body = rawBody == null || rawBody.length == 0 ? WaUtil.JSON.createObjectNode()
                            : WaUtil.JSON.readTree(new String(rawBody, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    return err(400, "invalid_json", "The request body must be JSON.");
                }
                if (!body.isObject()) {
                    return err(400, "invalid_json", "The request body must be a JSON object.");
                }
            }
            Matcher m;
            if ("GET".equals(method) && "/v1/account".equals(path)) {
                return account(delegator, tenantId);
            }
            if ("/v1/messages".equals(path)) {
                return "POST".equals(method) ? sendMessage(delegator, tenantId, body)
                        : "GET".equals(method) ? listMessages(delegator, tenantId, query) : notAllowed();
            }
            if ("/v1/contacts".equals(path)) {
                return "POST".equals(method) ? upsertContact(delegator, tenantId, null, body)
                        : "GET".equals(method) ? listContacts(delegator, tenantId, query) : notAllowed();
            }
            if ((m = CONTACT_ID.matcher(path)).matches()) {
                GenericValue c = EntityQuery.use(delegator).from("WaContact").where("contactId", m.group(1)).queryOne();
                if (c == null || !tenantId.equals(c.getString("tenantId"))) {
                    return err(404, "not_found", "Contact not found.");
                }
                return "GET".equals(method) ? ok(contactJson(delegator, c))
                        : ("PATCH".equals(method) || "POST".equals(method) || "PUT".equals(method)) ? upsertContact(delegator, tenantId, c, body) : notAllowed();
            }
            if ("GET".equals(method) && "/v1/templates".equals(path)) {
                return templates(delegator, tenantId);
            }
            if ("POST".equals(method) && "/v1/broadcasts".equals(path)) {
                return createBroadcast(delegator, dispatcher, tenantId, body);
            }
            if ("GET".equals(method) && "/v1/broadcasts".equals(path)) {
                return listBroadcasts(delegator, tenantId);
            }
            if ("GET".equals(method) && (m = BROADCAST_ID.matcher(path)).matches()) {
                GenericValue cmp = EntityQuery.use(delegator).from("WaCampaign").where("campaignId", m.group(1)).queryOne();
                if (cmp == null || !tenantId.equals(cmp.getString("tenantId"))) {
                    return err(404, "not_found", "Broadcast not found.");
                }
                return ok(broadcastJson(delegator, cmp));
            }
            return err(404, "not_found", "Unknown endpoint " + method + " " + path + ". See the API reference in the Developers page.");
        } catch (Exception e) {
            Debug.logError(e, "API call failed", MODULE);
            return err(500, "internal_error", "Something went wrong on our side. Please try again.");
        }
    }

    private static Res notAllowed() {
        return err(405, "method_not_allowed", "This method is not supported for this endpoint.");
    }

    // ------------------------------------------------------------------ account
    private static Res account(Delegator delegator, String tenantId) throws GenericEntityException {
        GenericValue t = EntityQuery.use(delegator).from("WaTenant").where("tenantId", tenantId).queryOne();
        GenericValue plan = t.getRelatedOne("WaPlan", false);
        ObjectNode o = WaUtil.JSON.createObjectNode().put("success", true);
        o.put("workspaceId", tenantId).put("name", t.getString("tenantName"))
                .put("plan", plan == null ? null : plan.getString("planName"))
                .put("status", String.valueOf(t.getString("statusId")).replace("WA_TNT_", "").toLowerCase(Locale.ROOT))
                .put("paidUntil", iso(t.getTimestamp("subscriptionThruDate")));
        ObjectNode usage = o.putObject("usage");
        for (Map.Entry<String, Map<String, Object>> e : WaAddons.usage(delegator, tenantId).entrySet()) {
            ObjectNode u = usage.putObject(e.getKey().toLowerCase(Locale.ROOT));
            u.put("used", (Long) e.getValue().get("used"));
            if (e.getValue().get("limit") == null) {
                u.putNull("limit");
            } else {
                u.put("limit", (Long) e.getValue().get("limit"));
            }
        }
        ArrayNode nums = o.putArray("numbers");
        for (GenericValue ch : EntityQuery.use(delegator).from("WaChannel").where("tenantId", tenantId).orderBy("channelId").queryList()) {
            nums.addObject().put("channelId", ch.getString("channelId")).put("phone", ch.getString("displayPhoneNumber"))
                    .put("name", ch.getString("channelName")).put("active", "Y".equals(ch.getString("isActive")));
        }
        return ok(o);
    }

    // ------------------------------------------------------------------ messages
    private static GenericValue channel(Delegator delegator, String tenantId, JsonNode body) throws GenericEntityException {
        String channelId = body.path("channelId").asText(null);
        return UtilValidate.isNotEmpty(channelId)
                ? EntityQuery.use(delegator).from("WaChannel").where("channelId", channelId, "tenantId", tenantId).queryOne()
                : EntityQuery.use(delegator).from("WaChannel").where("tenantId", tenantId, "isActive", "Y").orderBy("channelId").queryFirst();
    }

    private static Res sendMessage(Delegator delegator, String tenantId, JsonNode body) throws GenericEntityException {
        GenericValue channel = channel(delegator, tenantId, body);
        if (channel == null) {
            return err(400, "no_number", "No active WhatsApp number (check channelId).");
        }
        String to = WaContacts.cleanNumber(body.path("to").asText(""), null);
        if (to == null) {
            return err(400, "invalid_number", "'to' must be a full international number, e.g. 919876543210.");
        }
        GenericValue contact = WaMessenger.findOrCreateContact(delegator, channel, to, null, "API");
        Map<String, Object> in = new HashMap<>();
        in.put("text", body.path("text").asText(null));
        JsonNode img = body.path("image");
        in.put("mediaUrl", img.isObject() ? img.path("url").asText(null) : body.path("mediaUrl").asText(null));
        if (img.isObject() && img.hasNonNull("caption")) {
            in.put("text", img.path("caption").asText());
        }
        JsonNode tpl = body.path("template");
        if (tpl.isObject()) {
            in.put("templateName", tpl.path("name").asText(null));
            in.put("languageCode", tpl.path("language").asText("en"));
            List<String> params = new ArrayList<>();
            tpl.path("params").forEach(p -> params.add(p.asText()));
            in.put("templateParams", params);
        }
        if (in.get("text") == null && in.get("mediaUrl") == null && in.get("templateName") == null) {
            return err(400, "empty_message", "Give text, image or template.");
        }
        Map<String, Object> r = WaServices.doSend(delegator, channel, contact, in, "API", Locale.getDefault());
        if (ServiceUtil.isError(r)) {
            String msg = ServiceUtil.getErrorMessage(r);
            String code = msg.contains("24") ? "window_closed" : msg.contains("opted out") ? "opted_out"
                    : msg.toLowerCase(Locale.ROOT).contains("limit") ? "limit_reached" : "send_failed";
            return err(422, code, msg);
        }
        ObjectNode ok = WaUtil.JSON.createObjectNode();
        ok.put("success", true).put("messageId", (String) r.get("messageId")).put("wamid", (String) r.get("wamid"))
                .put("contactId", (String) r.get("contactId"));
        return ok(ok);
    }

    private static Res listMessages(Delegator delegator, String tenantId, Map<String, String> q) throws GenericEntityException {
        List<EntityCondition> conds = new ArrayList<>();
        conds.add(EntityCondition.makeCondition("tenantId", tenantId));
        if (UtilValidate.isNotEmpty(q.get("contactId"))) {
            conds.add(EntityCondition.makeCondition("contactId", q.get("contactId")));
        }
        Timestamp before = parseTime(q.get("before"));
        if (before != null) {
            conds.add(EntityCondition.makeCondition("createdDate", EntityOperator.LESS_THAN, before));
        }
        ArrayNode arr = WaUtil.JSON.createArrayNode();
        for (GenericValue m : EntityQuery.use(delegator).from("WaMessage").where(conds).orderBy("-createdDate").maxRows(limit(q)).queryList()) {
            arr.addObject().put("messageId", m.getString("messageId")).put("contactId", m.getString("contactId"))
                    .put("direction", m.getString("direction")).put("type", m.getString("messageType"))
                    .put("text", m.getString("body")).put("status", m.getString("deliveryStatus"))
                    .put("sentBy", m.getString("sentBy")).put("wamid", m.getString("wamid"))
                    .put("createdAt", iso(m.getTimestamp("createdDate")));
        }
        ObjectNode ok = WaUtil.JSON.createObjectNode().put("success", true);
        ok.set("messages", arr);
        return ok(ok);
    }

    // ------------------------------------------------------------------ contacts
    static ObjectNode contactJson(Delegator delegator, GenericValue c) throws GenericEntityException {
        ObjectNode o = WaUtil.JSON.createObjectNode();
        o.put("contactId", c.getString("contactId")).put("phone", "+" + c.getString("waId")).put("waId", c.getString("waId"))
                .put("name", c.getString("profileName")).put("email", c.getString("email")).put("optIn", !"N".equals(c.getString("optInStatus")))
                .put("channelId", c.getString("channelId")).put("chatStatus", c.getString("chatStatus") == null ? "OPEN" : c.getString("chatStatus"))
                .put("assignedTo", c.getString("assignedTo")).put("botPaused", "Y".equals(c.getString("botPaused")))
                .put("lastMessageAt", iso(c.getTimestamp("lastMessageDate"))).put("createdAt", iso(c.getTimestamp("createdDate")));
        ArrayNode tags = o.putArray("tags");
        WaContacts.tagsOf(delegator, c.getString("contactId")).forEach(tags::add);
        ObjectNode f = o.putObject("fields");
        WaContacts.fields(c).forEach(f::put);
        o.putPOJO("variables", WaUtil.readVars(c));
        return o;
    }

    private static Res listContacts(Delegator delegator, String tenantId, Map<String, String> q) throws GenericEntityException {
        String opt = "true".equals(q.get("optIn")) ? "Y" : "false".equals(q.get("optIn")) ? "N" : null;
        WaCrmEvents.ContactFilter f = WaCrmEvents.ContactFilter.of(delegator, tenantId, q.get("q"), q.get("tag"), opt, q.get("channelId"));
        int lim = limit(q);
        int page = 1;
        try {
            page = Math.max(1, Integer.parseInt(String.valueOf(q.get("page"))));
        } catch (NumberFormatException ignore) {
            // page 1
        }
        ArrayNode arr = WaUtil.JSON.createArrayNode();
        int matched = 0;
        boolean more = false;
        try (org.apache.ofbiz.entity.util.EntityListIterator it = EntityQuery.use(delegator).from("WaContact").where(f.conditions())
                .orderBy("-lastMessageDate", "-createdDate").queryIterator()) {
            GenericValue c;
            while ((c = it.next()) != null) {
                if (!f.matches(c)) {
                    continue;
                }
                matched++;
                if (matched <= (page - 1) * lim) {
                    continue;
                }
                if (arr.size() >= lim) {
                    more = true;
                    break;
                }
                arr.add(contactJson(delegator, c));
            }
        }
        ObjectNode ok = WaUtil.JSON.createObjectNode().put("success", true).put("page", page).put("hasMore", more);
        ok.set("contacts", arr);
        return ok(ok);
    }

    /** Create (by phone) or update a contact: name, email, tags (replace), addTags, removeTags, fields, optIn. */
    private static Res upsertContact(Delegator delegator, String tenantId, GenericValue existing, JsonNode body) throws GenericEntityException {
        GenericValue c = existing;
        boolean created = false;
        if (c == null) {
            GenericValue channel = channel(delegator, tenantId, body);
            if (channel == null) {
                return err(400, "no_number", "No WhatsApp number to add the contact to (check channelId).");
            }
            String num = WaContacts.cleanNumber(body.path("phone").asText(""), WaContacts.countryCodeOf(channel));
            if (num == null) {
                return err(400, "invalid_number", "'phone' must be a valid WhatsApp number, e.g. +919876543210.");
            }
            c = EntityQuery.use(delegator).from("WaContact").where("channelId", channel.getString("channelId"), "waId", num).queryFirst();
            if (c == null) {
                c = WaMessenger.findOrCreateContact(delegator, channel, num, null, "API");
                created = true;
            }
        }
        if (body.hasNonNull("name")) {
            String n = body.get("name").asText("").trim();
            c.set("profileName", n.isEmpty() ? null : WaAgentAi.cut(n, 100));
        }
        if (body.hasNonNull("email")) {
            String e = body.get("email").asText("").trim();
            if (!e.isEmpty() && !e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                return err(400, "invalid_email", "'email' is not a valid email address.");
            }
            c.set("email", e.isEmpty() ? null : WaAgentAi.cut(e, 250));
        }
        if (body.path("fields").isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            for (GenericValue d : WaContacts.fieldDefs(delegator, tenantId)) {
                keys.add(d.getString("fieldKey"));
            }
            Map<String, String> vals = new LinkedHashMap<>();
            List<String> unknown = new ArrayList<>();
            body.get("fields").properties().forEach(e -> {
                if (keys.contains(e.getKey())) {
                    vals.put(e.getKey(), e.getValue().isNull() ? "" : e.getValue().asText());
                } else {
                    unknown.add(e.getKey());
                }
            });
            if (!unknown.isEmpty()) {
                return err(400, "unknown_field", "Unknown custom field(s): " + String.join(", ", unknown)
                        + ". Create them on the Contacts page first (Custom fields).");
            }
            WaContacts.putFields(c, vals);
        }
        if (body.has("optIn") && body.get("optIn").isBoolean()) {
            boolean in = body.get("optIn").asBoolean();
            if ((in ? "Y" : "N").equals(c.getString("optInStatus")) == false) {
                WaContacts.setConsent(c, in, "API");
            }
        }
        c.store();
        if (body.path("tags").isArray()) {
            List<String> t = new ArrayList<>();
            body.get("tags").forEach(x -> t.add(x.asText()));
            WaContacts.setTags(delegator, c, t);
        }
        if (body.path("addTags").isArray()) {
            List<String> t = new ArrayList<>();
            body.get("addTags").forEach(x -> t.add(x.asText()));
            WaContacts.addTags(delegator, c, t);
        }
        if (body.path("removeTags").isArray()) {
            List<String> t = new ArrayList<>();
            body.get("removeTags").forEach(x -> t.add(x.asText()));
            WaContacts.removeTags(delegator, c.getString("contactId"), t);
        }
        ObjectNode o = WaUtil.JSON.createObjectNode().put("success", true).put("created", created);
        o.set("contact", contactJson(delegator, c));
        return new Res(created ? 201 : 200, o);
    }

    // ------------------------------------------------------------------ templates, broadcasts
    private static Res templates(Delegator delegator, String tenantId) throws GenericEntityException {
        ArrayNode arr = WaUtil.JSON.createArrayNode();
        for (GenericValue t : EntityQuery.use(delegator).from("WaTemplate").where("tenantId", tenantId).orderBy("templateName").queryList()) {
            arr.addObject().put("templateId", t.getString("templateId")).put("channelId", t.getString("channelId"))
                    .put("name", t.getString("templateName")).put("language", t.getString("languageCode"))
                    .put("category", t.getString("category")).put("status", t.getString("metaStatus"))
                    .put("body", t.getString("bodyText")).put("params", WaCrmEvents.placeholders(t.getString("bodyText")));
        }
        ObjectNode ok = WaUtil.JSON.createObjectNode().put("success", true);
        ok.set("templates", arr);
        return ok(ok);
    }

    private static Res createBroadcast(Delegator delegator, LocalDispatcher dispatcher, String tenantId, JsonNode b) throws Exception {
        GenericValue channel = channel(delegator, tenantId, b);
        if (channel == null) {
            return err(400, "no_number", "No active WhatsApp number (check channelId).");
        }
        JsonNode t = b.path("template");
        GenericValue tpl = null;
        if (t.hasNonNull("templateId")) {
            tpl = EntityQuery.use(delegator).from("WaTemplate").where("templateId", t.get("templateId").asText()).queryOne();
        } else if (t.hasNonNull("name")) {
            List<GenericValue> ts = EntityQuery.use(delegator).from("WaTemplate")
                    .where("channelId", channel.getString("channelId"), "templateName", t.get("name").asText()).queryList();
            String lang = t.path("language").asText("");
            for (GenericValue x : ts) {
                if (tpl == null || lang.equalsIgnoreCase(x.getString("languageCode"))) {
                    tpl = x;
                }
            }
        }
        if (tpl == null) {
            return err(400, "template_not_found", "Template not found for this number. Use GET /v1/templates.");
        }
        List<String> params = new ArrayList<>();
        t.path("params").forEach(p -> params.add(p.asText()));
        if (params.stream().anyMatch(p -> p.replaceAll("\\{\\{[^{}]*}}", "").contains("|"))) {
            return err(400, "invalid_params", "Template values cannot contain the | character.");
        }
        JsonNode a = b.path("audience");
        Map<String, String> f = new HashMap<>();
        f.put("channelId", channel.getString("channelId"));
        f.put("templateId", tpl.getString("templateId"));
        f.put("bodyParams", params.isEmpty() ? null : String.join("|", params));
        f.put("campaignName", b.path("name").asText(null));
        if (a.path("numbers").isArray() && a.get("numbers").size() > 0) {
            f.put("audienceType", "NUMBERS");
            List<String> n = new ArrayList<>();
            a.get("numbers").forEach(x -> n.add(x.asText()));
            f.put("numbers", String.join("\n", n));
        } else if (a.path("tags").isArray() && a.get("tags").size() > 0) {
            f.put("audienceType", "TAGS");
            List<String> n = new ArrayList<>();
            a.get("tags").forEach(x -> n.add(x.asText()));
            f.put("tags", String.join(",", n));
        } else if (a.path("all").asBoolean(false)) {
            f.put("audienceType", "ALL");
        } else {
            return err(400, "invalid_audience", "Set audience.tags, audience.numbers or audience.all = true.");
        }
        if (a.path("excludeTags").isArray()) {
            List<String> n = new ArrayList<>();
            a.get("excludeTags").forEach(x -> n.add(x.asText()));
            f.put("excludeTags", String.join(",", n));
        }
        if (b.hasNonNull("sendAt")) {
            Timestamp at = parseTime(b.get("sendAt").asText());
            if (at == null) {
                return err(400, "invalid_time", "'sendAt' must be an ISO time like 2026-10-01T10:00:00+05:30.");
            }
            f.put("sendAt", String.valueOf(at.getTime()));
        }
        String[] r = WaCrmEvents.createCampaign(delegator, dispatcher, tenantId, "API", f);
        if (r[1] != null) {
            return err(422, "invalid_broadcast", r[1]);
        }
        GenericValue cmp = EntityQuery.use(delegator).from("WaCampaign").where("campaignId", r[0]).queryOne();
        ObjectNode o = broadcastJson(delegator, cmp);
        return new Res(201, o);
    }

    private static Res listBroadcasts(Delegator delegator, String tenantId) throws GenericEntityException {
        ArrayNode arr = WaUtil.JSON.createArrayNode();
        for (GenericValue c : EntityQuery.use(delegator).from("WaCampaign").where("tenantId", tenantId).orderBy("-createdDate").maxRows(50).queryList()) {
            arr.add(broadcastJson(delegator, c).without("success"));
        }
        ObjectNode ok = WaUtil.JSON.createObjectNode().put("success", true);
        ok.set("broadcasts", arr);
        return ok(ok);
    }

    static ObjectNode broadcastJson(Delegator delegator, GenericValue c) throws GenericEntityException {
        ObjectNode o = WaUtil.JSON.createObjectNode().put("success", true);
        o.put("broadcastId", c.getString("campaignId")).put("name", c.getString("campaignName")).put("template", c.getString("templateName"))
                .put("status", c.getString("statusId").toLowerCase(Locale.ROOT)).put("scheduledAt", iso(c.getTimestamp("scheduledDate")))
                .put("startedAt", iso(c.getTimestamp("startedDate"))).put("completedAt", iso(c.getTimestamp("completedDate")))
                .put("error", c.getString("errorText"));
        o.set("stats", WaUtil.JSON.valueToTree(WaCampaigns.stats(delegator, c.getString("campaignId"))));
        return o;
    }

    // ------------------------------------------------------------------ helpers
    static String iso(Timestamp t) {
        return t == null ? null : Instant.ofEpochMilli(t.getTime()).toString();
    }

    static Timestamp parseTime(String s) {
        if (UtilValidate.isEmpty(s)) {
            return null;
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(s).toInstant());
        } catch (Exception e) {
            try {
                return Timestamp.from(Instant.parse(s));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static int limit(Map<String, String> q) {
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(q.get("limit"))));
        } catch (Exception e) {
            return 50;
        }
    }

    // ------------------------------------------------------------------ keys, rate limit, logs
    /** tenantId + apiKeyId for a key, or null. */
    public static String[] authenticate(Delegator delegator, String key) throws GenericEntityException {
        if (UtilValidate.isEmpty(key)) {
            return null;
        }
        GenericValue k = EntityQuery.use(delegator).from("WaApiKey").where("keyHash", WaUtil.sha256Hex(key.trim()), "isActive", "Y").queryFirst();
        if (k == null) {
            return null;
        }
        Timestamp last = k.getTimestamp("lastUsedDate");
        if (last == null || System.currentTimeMillis() - last.getTime() > 60_000L) {
            delegator.storeByCondition("WaApiKey", UtilMisc.toMap("lastUsedDate", UtilDateTime.nowTimestamp()),
                    EntityCondition.makeCondition("apiKeyId", k.getString("apiKeyId")));
        }
        return new String[] {k.getString("tenantId"), k.getString("apiKeyId")};
    }

    private static final Map<String, long[]> WINDOWS = new ConcurrentHashMap<>();

    /** Seconds to wait, or 0 when the call is allowed (per key, per minute). */
    public static int rateLimited(String keyId) {
        int max = WaUtil.propInt("api.rate.per.minute", 120);
        long minute = System.currentTimeMillis() / 60_000L;
        long[] w = WINDOWS.computeIfAbsent(keyId, k -> new long[] {minute, 0});
        synchronized (w) {
            if (w[0] != minute) {
                w[0] = minute;
                w[1] = 0;
            }
            if (++w[1] > max) {
                return (int) (60 - (System.currentTimeMillis() / 1000L) % 60);
            }
        }
        if (WINDOWS.size() > 20000) {
            WINDOWS.clear();
        }
        return 0;
    }

    public static void log(Delegator delegator, String tenantId, String keyId, String method, String path, int status, long ms,
                           String error, String ip) {
        try {
            delegator.create("WaApiLog", UtilMisc.toMap("logId", delegator.getNextSeqId("WaApiLog"), "tenantId", tenantId,
                    "apiKeyId", keyId, "method", method, "path", WaAgentAi.cut(path, 250), "statusCode", (long) status,
                    "durationMs", ms, "errorText", WaAgentAi.cut(error, 250), "clientIp", WaAgentAi.cut(ip, 60),
                    "createdDate", UtilDateTime.nowTimestamp()));
            if (Math.random() < 0.01) {
                delegator.removeByCondition("WaApiLog", EntityCondition.makeCondition("createdDate", EntityOperator.LESS_THAN,
                        UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), -30)));
            }
        } catch (GenericEntityException e) {
            Debug.logWarning(e, "API log failed", MODULE);
        }
    }

    /** The OpenAPI description (config/openapi.json) with this server's URL. */
    public static String openApi(String baseUrl) {
        try (InputStream in = WaApi.class.getClassLoader().getResourceAsStream("openapi.json")) {
            String s = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (s == null) {
                java.io.File f = new java.io.File(System.getProperty("ofbiz.home", "."), "plugins/whatsappbot/config/openapi.json");
                s = f.isFile() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "{}";
            }
            return s.replace("{{BASE_URL}}", baseUrl);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return "{}";
        }
    }
}
