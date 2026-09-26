package com.msoftdynamic.whatsapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Outgoing webhooks (developer portal): FloChat POSTs events to the workspace's own URL, signed with
 * HMAC-SHA256, and retries failed deliveries 3 times (after 30 s, 2 min and 10 min).
 */
public final class WaWebhooks {
    private static final String MODULE = WaWebhooks.class.getName();
    public static final List<String> EVENTS = List.of("message.received", "message.status", "contact.created",
            "conversation.handoff", "broadcast.completed");
    private static final long[] RETRY_SECONDS = {30, 120, 600};
    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "flochat-webhooks");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Map<String, Object[]> ACTIVE = new ConcurrentHashMap<>(); // tenantId -> [time, List<GenericValue>]

    private WaWebhooks() { }

    static void invalidate(String tenantId) {
        ACTIVE.remove(tenantId);
    }

    @SuppressWarnings("unchecked")
    private static List<GenericValue> hooks(Delegator delegator, String tenantId) {
        Object[] c = ACTIVE.get(tenantId);
        if (c != null && System.currentTimeMillis() - (Long) c[0] < 30_000L) {
            return (List<GenericValue>) c[1];
        }
        List<GenericValue> list;
        try {
            list = EntityQuery.use(delegator).from("WaWebhook").where("tenantId", tenantId, "isActive", "Y").queryList();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            list = List.of();
        }
        ACTIVE.put(tenantId, new Object[] {System.currentTimeMillis(), list});
        return list;
    }

    static boolean subscribed(GenericValue hook, String event) {
        String ev = hook.getString("events");
        return UtilValidate.isEmpty(ev) || "*".equals(ev.trim()) || WaUtil.splitCsv(ev).contains(event);
    }

    /** Send an event to every active webhook of the workspace that wants it. Never throws, never blocks. */
    public static void emit(Delegator delegator, String tenantId, String event, ObjectNode data) {
        if (tenantId == null) {
            return;
        }
        try {
            for (GenericValue h : hooks(delegator, tenantId)) {
                if (subscribed(h, event)) {
                    ObjectNode body = envelope(event, tenantId, data);
                    String webhookId = h.getString("webhookId");
                    POOL.execute(() -> deliver(delegator, webhookId, event, body.toString(), null, 1));
                }
            }
        } catch (Exception e) {
            Debug.logWarning(e, "Webhook emit failed", MODULE);
        }
    }

    static ObjectNode envelope(String event, String tenantId, ObjectNode data) {
        ObjectNode o = WaUtil.JSON.createObjectNode();
        o.put("id", "evt_" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1_000_000), 36));
        o.put("event", event);
        o.put("createdAt", Instant.now().toString());
        o.put("workspaceId", tenantId);
        o.set("data", data == null ? WaUtil.JSON.createObjectNode() : data);
        return o;
    }

    /** Signature header value: t=unix,v1=hex(HMAC_SHA256(secret, t + "." + body)). */
    public static String signature(String secret, long ts, String body) {
        return "t=" + ts + ",v1=" + WaUtil.hmacSha256Hex(secret, (ts + "." + body).getBytes(StandardCharsets.UTF_8));
    }

    /** One attempt. deliveryId null = first attempt (the row is created here, outside any caller transaction). */
    static int[] deliver(Delegator delegator, String webhookId, String event, String body, String deliveryId, int attempt) {
        int code = 0;
        String text;
        long t0 = System.currentTimeMillis();
        GenericValue hook;
        try {
            hook = EntityQuery.use(delegator).from("WaWebhook").where("webhookId", webhookId).queryOne();
        } catch (GenericEntityException e) {
            return new int[] {0, 0};
        }
        if (hook == null || !"Y".equals(hook.getString("isActive")) && !"ping".equals(event)) {
            return new int[] {0, 0};
        }
        try {
            URI u = WaKnowledge.checkUrl(hook.getString("url"));
            long ts = Instant.now().getEpochSecond();
            HttpRequest req = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "FloChat-Webhooks/1.0")
                    .header("X-FloChat-Event", event)
                    .header("X-FloChat-Delivery", deliveryId == null ? "new" : deliveryId)
                    .header("X-FloChat-Signature", signature(hook.getString("secret"), ts, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            code = res.statusCode();
            text = res.body() == null ? "" : res.body();
        } catch (WaKnowledge.KbException e) {
            text = "Address not allowed: " + e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            text = "Timed out after 15 seconds";
        } catch (Exception e) {
            text = "Could not connect: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
        long ms = System.currentTimeMillis() - t0;
        boolean ok = code >= 200 && code < 300;
        boolean retry = !ok && attempt <= RETRY_SECONDS.length && !"ping".equals(event);
        String id = deliveryId;
        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue d = id == null ? null : EntityQuery.use(delegator).from("WaWebhookDelivery").where("deliveryId", id).queryOne();
            if (d == null) {
                id = delegator.getNextSeqId("WaWebhookDelivery");
                d = delegator.makeValue("WaWebhookDelivery", UtilMisc.toMap("deliveryId", id, "webhookId", webhookId,
                        "tenantId", hook.getString("tenantId"), "eventType", event, "payload", body, "createdDate", now));
            }
            d.set("statusId", ok ? "OK" : retry ? "RETRYING" : "FAILED");
            d.set("statusCode", (long) code);
            d.set("responseText", text.length() > 500 ? text.substring(0, 500) : text);
            d.set("attempts", (long) attempt);
            d.set("durationMs", ms);
            d.set("lastAttemptDate", now);
            delegator.createOrStore(d);
            hook.set("lastStatus", ok ? String.valueOf(code) : code == 0 ? "ERROR" : String.valueOf(code));
            hook.set("lastDeliveryDate", now);
            hook.set("failCount", ok ? 0L : (hook.get("failCount") == null ? 0L : hook.getLong("failCount")) + 1);
            hook.store();
        } catch (GenericEntityException e) {
            Debug.logWarning(e, "Could not log webhook delivery", MODULE);
        }
        if (retry) {
            String did = id;
            POOL.schedule(() -> deliver(delegator, webhookId, event, body, did, attempt + 1), RETRY_SECONDS[attempt - 1], TimeUnit.SECONDS);
        }
        return new int[] {code, (int) ms};
    }

    /** Send a ping now (developer portal "Send test"): returns [status code, ms]. */
    public static int[] ping(Delegator delegator, GenericValue hook) {
        ObjectNode data = WaUtil.JSON.createObjectNode().put("message", "Webhook test from FloChat. If you can read this, it works.");
        return deliver(delegator, hook.getString("webhookId"), "ping", envelope("ping", hook.getString("tenantId"), data).toString(), null, 1);
    }

    // ------------------------------------------------------------------ event payloads
    static ObjectNode contactJson(GenericValue c) {
        ObjectNode o = WaUtil.JSON.createObjectNode();
        if (c == null) {
            return o;
        }
        o.put("contactId", c.getString("contactId")).put("phone", "+" + c.getString("waId")).put("name", c.getString("profileName"))
                .put("channelId", c.getString("channelId"));
        return o;
    }

    public static void messageReceived(Delegator delegator, GenericValue msg, GenericValue contact, String replyId) {
        ObjectNode d = WaUtil.JSON.createObjectNode();
        d.put("messageId", msg.getString("messageId")).put("wamid", msg.getString("wamid")).put("type", msg.getString("messageType"))
                .put("text", msg.getString("body")).put("buttonId", replyId);
        d.set("contact", contactJson(contact));
        emit(delegator, msg.getString("tenantId"), "message.received", d);
    }

    public static void messageStatus(Delegator delegator, GenericValue msg) {
        ObjectNode d = WaUtil.JSON.createObjectNode();
        d.put("messageId", msg.getString("messageId")).put("wamid", msg.getString("wamid")).put("contactId", msg.getString("contactId"))
                .put("status", msg.getString("deliveryStatus")).put("error", msg.getString("errorText"))
                .put("broadcastId", msg.getString("campaignId"));
        emit(delegator, msg.getString("tenantId"), "message.status", d);
    }

    public static void contactCreated(Delegator delegator, GenericValue c) {
        ObjectNode d = contactJson(c);
        d.put("source", c.getString("optSource"));
        emit(delegator, c.getString("tenantId"), "contact.created", d);
    }

    public static void handoff(Delegator delegator, GenericValue c, String reason) {
        ObjectNode d = WaUtil.JSON.createObjectNode();
        d.set("contact", contactJson(c));
        d.put("reason", reason);
        emit(delegator, c.getString("tenantId"), "conversation.handoff", d);
    }

    public static void broadcastCompleted(Delegator delegator, GenericValue cmp) {
        try {
            ObjectNode d = WaUtil.JSON.createObjectNode();
            d.put("broadcastId", cmp.getString("campaignId")).put("name", cmp.getString("campaignName")).put("status", cmp.getString("statusId"));
            d.set("stats", WaUtil.JSON.valueToTree(WaCampaigns.stats(delegator, cmp.getString("campaignId"))));
            emit(delegator, cmp.getString("tenantId"), "broadcast.completed", d);
        } catch (GenericEntityException e) {
            Debug.logWarning(e, MODULE);
        }
    }

    static List<String> cleanEvents(String[] values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                if (EVENTS.contains(v) && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }
}
