package com.msoftdynamic.whatsapp;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/** Builds WhatsApp message payloads, sends them and records them in WaMessage. */
public final class WaMessenger {

    private static final String MODULE = WaMessenger.class.getName();
    public static final long WINDOW_MILLIS = 24L * 60 * 60 * 1000;

    private WaMessenger() { }

    /** Outcome of a send. */
    public static final class SendResult {
        private final String messageId;
        private final String wamid;
        private final String error;

        SendResult(String messageId, String wamid, String error) {
            this.messageId = messageId;
            this.wamid = wamid;
            this.error = error;
        }
        public String getMessageId() {
            return messageId;
        }
        public String getWamid() {
            return wamid;
        }
        public String getError() {
            return error;
        }
        public boolean isOk() {
            return error == null;
        }
    }

    // ------------------------------------------------------------------ payload builders
    public static ObjectNode text(String to, String body) {
        ObjectNode m = base(to, "text");
        m.putObject("text").put("preview_url", body != null && body.contains("http")).put("body", cut(body, 4096));
        return m;
    }

    public static ObjectNode image(String to, String url, String caption) {
        ObjectNode m = base(to, "image");
        ObjectNode img = m.putObject("image").put("link", url);
        if (UtilValidate.isNotEmpty(caption)) {
            img.put("caption", cut(caption, 1024));
        }
        return m;
    }

    /** Reply buttons: options as [id, title] pairs, max 3. */
    public static ObjectNode buttons(String to, String header, String body, String footer, List<String[]> options) {
        ObjectNode m = base(to, "interactive");
        ObjectNode i = m.putObject("interactive").put("type", "button");
        if (UtilValidate.isNotEmpty(header)) {
            i.putObject("header").put("type", "text").put("text", cut(header, 60));
        }
        i.putObject("body").put("text", cut(body, 1024));
        if (UtilValidate.isNotEmpty(footer)) {
            i.putObject("footer").put("text", cut(footer, 60));
        }
        ArrayNode arr = i.putObject("action").putArray("buttons");
        for (String[] o : options.subList(0, Math.min(3, options.size()))) {
            arr.addObject().put("type", "reply").putObject("reply").put("id", o[0]).put("title", cut(o[1], 20));
        }
        return m;
    }

    /** List menu: options as [id, title, description], max 10 rows. */
    public static ObjectNode list(String to, String header, String body, String footer, String buttonLabel, List<String[]> options) {
        ObjectNode m = base(to, "interactive");
        ObjectNode i = m.putObject("interactive").put("type", "list");
        if (UtilValidate.isNotEmpty(header)) {
            i.putObject("header").put("type", "text").put("text", cut(header, 60));
        }
        i.putObject("body").put("text", cut(body, 4096));
        if (UtilValidate.isNotEmpty(footer)) {
            i.putObject("footer").put("text", cut(footer, 60));
        }
        ObjectNode action = i.putObject("action").put("button", cut(UtilValidate.isEmpty(buttonLabel) ? "Choose" : buttonLabel, 20));
        ArrayNode rows = action.putArray("sections").addObject().put("title", cut(UtilValidate.isEmpty(header) ? "Options" : header, 24))
                .putArray("rows");
        for (String[] o : options.subList(0, Math.min(10, options.size()))) {
            ObjectNode r = rows.addObject().put("id", o[0]).put("title", cut(o[1], 24));
            if (o.length > 2 && UtilValidate.isNotEmpty(o[2])) {
                r.put("description", cut(o[2], 72));
            }
        }
        return m;
    }

    public static ObjectNode template(String to, String name, String lang, List<String> bodyParams) {
        ObjectNode m = base(to, "template");
        ObjectNode t = m.putObject("template").put("name", name);
        t.putObject("language").put("code", UtilValidate.isEmpty(lang) ? "en" : lang);
        if (bodyParams != null && !bodyParams.isEmpty()) {
            ArrayNode params = t.putArray("components").addObject().put("type", "body").putArray("parameters");
            for (String p : bodyParams) {
                params.addObject().put("type", "text").put("text", p == null ? "" : p);
            }
        }
        return m;
    }

    private static ObjectNode base(String to, String type) {
        ObjectNode m = WaUtil.JSON.createObjectNode();
        m.put("recipient_type", "individual").put("to", to).put("type", type);
        return m;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------ sending
    /**
     * Send a payload to a contact, enforcing tenant status and quota, and store it.
     * @param logText human readable text stored in WaMessage.body
     */
    public static SendResult send(Delegator delegator, GenericValue channel, GenericValue contact, ObjectNode payload,
                                  String logText, String sentBy, Locale locale) {
        String tenantId = channel.getString("tenantId");
        String messageId = delegator.getNextSeqId("WaMessage");
        String type = payload.path("type").asText("text");
        String wamid = null;
        String error;
        try {
            error = WaUtil.checkCanSend(delegator, tenantId, locale);
        } catch (GenericEntityException e) {
            error = e.getMessage();
        }
        if (!"Y".equals(channel.getString("isActive"))) {
            error = "WhatsApp number " + channel.getString("channelId") + " is not active";
        }
        if (error == null) {
            GraphApiClient.Result r = GraphApiClient.sendMessage(channel, payload);
            if (r.isOk()) {
                wamid = r.getBody().path("messages").path(0).path("id").asText(null);
                WaUtil.incrementUsage(delegator, tenantId, true);
            } else {
                error = r.errorMessage();
            }
        }
        Timestamp now = UtilDateTime.nowTimestamp();
        try {
            GenericValue msg = delegator.makeValue("WaMessage");
            msg.set("messageId", messageId);
            msg.set("tenantId", tenantId);
            msg.set("channelId", channel.getString("channelId"));
            msg.set("contactId", contact.getString("contactId"));
            msg.set("direction", "OUT");
            msg.set("wamid", wamid);
            msg.set("messageType", type);
            msg.set("body", logText);
            msg.set("payload", payload.toString());
            msg.set("sentBy", sentBy);
            msg.set("deliveryStatus", error == null ? "accepted" : "failed");
            msg.set("errorText", error);
            msg.set("createdDate", now);
            msg.create();
            contact.set("lastMessageDate", now); // keep caller's in-memory copy consistent
            GenericValue fresh = EntityQuery.use(delegator).from("WaContact").where("contactId", contact.getString("contactId")).queryOne();
            if (fresh != null) {
                fresh.set("lastMessageDate", now);
                fresh.store();
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, "Could not store outbound message", MODULE);
        }
        return new SendResult(messageId, wamid, error);
    }

    public static boolean windowOpen(GenericValue contact) {
        Timestamp last = contact.getTimestamp("lastInboundDate");
        return last != null && UtilDateTime.nowTimestamp().getTime() - last.getTime() < WINDOW_MILLIS;
    }

    /** Blue ticks + typing indicator for an inbound message. Fire and forget. */
    public static void markRead(GenericValue channel, String wamid) {
        if (UtilValidate.isEmpty(wamid) || !WaUtil.propTrue("bot.mark.inbound.read")) {
            return;
        }
        ObjectNode m = WaUtil.JSON.createObjectNode();
        m.put("status", "read").put("message_id", wamid);
        GraphApiClient.sendMessage(channel, m);
    }

    // ------------------------------------------------------------------ contacts
    public static GenericValue findOrCreateContact(Delegator delegator, GenericValue channel, String waId, String profileName)
            throws GenericEntityException {
        GenericValue c = EntityQuery.use(delegator).from("WaContact")
                .where("channelId", channel.getString("channelId"), "waId", waId).queryFirst();
        if (c == null) {
            c = delegator.makeValue("WaContact");
            c.set("contactId", delegator.getNextSeqId("WaContact"));
            c.set("tenantId", channel.getString("tenantId"));
            c.set("channelId", channel.getString("channelId"));
            c.set("waId", waId);
            c.set("profileName", profileName);
            c.set("optInStatus", "Y");
            c.set("botPaused", "N");
            c.set("unreadCount", 0L);
            c.set("createdDate", UtilDateTime.nowTimestamp());
            c.create();
        } else if (UtilValidate.isNotEmpty(profileName) && !profileName.equals(c.getString("profileName"))) {
            c.set("profileName", profileName);
            c.store();
        }
        return c;
    }
}
