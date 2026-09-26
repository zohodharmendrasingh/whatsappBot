package com.msoftdynamic.whatsapp;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import org.apache.ofbiz.entity.model.DynamicViewEntity;
import org.apache.ofbiz.entity.util.EntityListIterator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Broadcast campaigns: audience by tags / all contacts / pasted numbers, send now or later,
 * and a per-recipient report (sent, delivered, read, replied, failed) fed by Meta's status webhooks.
 */
public final class WaCampaigns {
    private static final String MODULE = WaCampaigns.class.getName();
    /** A reply within this many hours of the broadcast counts as a reply to it. */
    private static final long REPLY_WINDOW_MS = 72L * 3600 * 1000;
    public static final int MAX_NUMBERS = 10000;
    private static final List<String> ORDER = List.of("PENDING", "SENT", "DELIVERED", "READ");

    private WaCampaigns() { }

    // ------------------------------------------------------------------ audience
    /** Numbers pasted by the user, cleaned; invalid ones are returned in {@code bad}. */
    public static Set<String> parseNumbers(String text, String defaultCc, List<String> bad) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("[,;\\r\\n\\t]+")) {
            if (raw.trim().isEmpty()) {
                continue;
            }
            String n = WaContacts.cleanNumber(raw, defaultCc);
            if (n == null) {
                if (bad != null && bad.size() < 20) {
                    bad.add(raw.trim());
                }
            } else if (out.size() < MAX_NUMBERS) {
                out.add(n);
            }
        }
        return out;
    }

    /** Contacts of the campaign's number matching the audience (existing contacts only). */
    public static List<GenericValue> audienceContacts(Delegator delegator, String channelId, String tenantId, String audienceType,
                                                     String tags, String excludeTags) throws GenericEntityException {
        List<EntityCondition> conds = new ArrayList<>();
        conds.add(EntityCondition.makeCondition("channelId", channelId));
        conds.add(EntityCondition.makeCondition("tenantId", tenantId));
        Set<String> include = null;
        if ("TAGS".equals(audienceType)) {
            include = WaContacts.contactsWithTags(delegator, tenantId, WaContacts.parseTags(tags));
            if (include.isEmpty()) {
                return new ArrayList<>();
            }
        }
        Set<String> exclude = WaContacts.contactsWithTags(delegator, tenantId, WaContacts.parseTags(excludeTags));
        List<GenericValue> out = new ArrayList<>();
        try (EntityListIterator it = EntityQuery.use(delegator).from("WaContact").where(conds).orderBy("contactId").queryIterator()) {
            GenericValue c;
            while ((c = it.next()) != null) {
                String id = c.getString("contactId");
                if ((include == null || include.contains(id)) && !exclude.contains(id)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** How many a campaign would reach: {total, optedOut, eligible, invalid}. */
    public static Map<String, Object> estimate(Delegator delegator, GenericValue channel, String audienceType, String tags,
                                               String excludeTags, String numbers) throws GenericEntityException {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        long optedOut = 0;
        List<String> bad = new ArrayList<>();
        if ("NUMBERS".equals(audienceType)) {
            Set<String> nums = parseNumbers(numbers, WaContacts.countryCodeOf(channel), bad);
            total = nums.size();
            Set<String> excluded = WaContacts.contactsWithTags(delegator, channel.getString("tenantId"), WaContacts.parseTags(excludeTags));
            for (String n : nums) {
                GenericValue c = EntityQuery.use(delegator).from("WaContact").where("channelId", channel.getString("channelId"), "waId", n).queryFirst();
                if (c != null && ("N".equals(c.getString("optInStatus")) || excluded.contains(c.getString("contactId")))) {
                    optedOut++;
                }
            }
        } else {
            for (GenericValue c : audienceContacts(delegator, channel.getString("channelId"), channel.getString("tenantId"),
                    audienceType, tags, excludeTags)) {
                total++;
                if ("N".equals(c.getString("optInStatus"))) {
                    optedOut++;
                }
            }
        }
        out.put("total", total);
        out.put("optedOut", optedOut);
        out.put("eligible", total - optedOut);
        out.put("invalid", bad);
        return out;
    }

    // ------------------------------------------------------------------ run (service waRunCampaign)
    public static Map<String, Object> runCampaign(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String campaignId = (String) context.get("campaignId");
        synchronized (lock(campaignId)) {
            try {
                return run(delegator, campaignId);
            } catch (Exception e) {
                Debug.logError(e, "Campaign " + campaignId + " failed", MODULE);
                try {
                    GenericValue cmp = EntityQuery.use(delegator).from("WaCampaign").where("campaignId", campaignId).queryOne();
                    if (cmp != null) {
                        cmp.set("statusId", "FAILED");
                        cmp.set("errorText", "Stopped by an error: " + e.getMessage());
                        cmp.store();
                    }
                } catch (GenericEntityException e2) {
                    Debug.logError(e2, MODULE);
                }
                return ServiceUtil.returnError(e.getMessage());
            }
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object lock(String id) {
        if (LOCKS.size() > 1000) {
            LOCKS.clear();
        }
        return LOCKS.computeIfAbsent(id, k -> new Object());
    }

    private static Map<String, Object> run(Delegator delegator, String campaignId) throws GenericEntityException {
        GenericValue cmp = EntityQuery.use(delegator).from("WaCampaign").where("campaignId", campaignId).queryOne();
        if (cmp == null) {
            return ServiceUtil.returnError("Campaign not found");
        }
        String st = cmp.getString("statusId");
        if (!"SCHEDULED".equals(st) && !"SENDING".equals(st) && !"FAILED".equals(st)) {
            return ServiceUtil.returnSuccess("Campaign is " + st + ", nothing to do");
        }
        Timestamp due = cmp.getTimestamp("scheduledDate");
        if ("SCHEDULED".equals(st) && due != null && due.getTime() > System.currentTimeMillis() + 60_000L) {
            return ServiceUtil.returnSuccess("Not due yet"); // rescheduled; a newer job will run it
        }
        GenericValue channel = EntityQuery.use(delegator).from("WaChannel").where("channelId", cmp.getString("channelId")).queryOne();
        if (channel == null || !cmp.getString("tenantId").equals(channel.getString("tenantId"))) {
            return fail(cmp, "The WhatsApp number of this broadcast no longer exists.");
        }
        GenericValue tpl = UtilValidate.isEmpty(cmp.getString("templateId")) ? null
                : EntityQuery.use(delegator).from("WaTemplate").where("templateId", cmp.getString("templateId")).queryOne();
        if (tpl == null || !channel.getString("channelId").equals(tpl.getString("channelId"))) {
            return fail(cmp, "The template of this broadcast was not found for this WhatsApp number. Sync templates and try again.");
        }
        cmp.set("statusId", "SENDING");
        cmp.set("errorText", null);
        if (cmp.get("startedDate") == null) {
            cmp.set("startedDate", UtilDateTime.nowTimestamp());
        }
        cmp.store();

        // 1. recipient list, built once (a resumed campaign keeps its list)
        if (EntityQuery.use(delegator).from("WaCampaignRecipient").where("campaignId", campaignId).queryCount() == 0) {
            List<GenericValue> contacts;
            if ("NUMBERS".equals(cmp.getString("audienceType"))) {
                contacts = new ArrayList<>();
                Set<String> excluded = WaContacts.contactsWithTags(delegator, cmp.getString("tenantId"), WaContacts.parseTags(cmp.getString("excludeTags")));
                for (String n : parseNumbers(cmp.getString("numbers"), WaContacts.countryCodeOf(channel), null)) {
                    GenericValue c = WaMessenger.findOrCreateContact(delegator, channel, n, null, "BROADCAST");
                    if (!excluded.contains(c.getString("contactId"))) {
                        contacts.add(c);
                    }
                }
            } else {
                contacts = audienceContacts(delegator, channel.getString("channelId"), cmp.getString("tenantId"),
                        cmp.getString("audienceType"), cmp.getString("audienceTags"), cmp.getString("excludeTags"));
            }
            for (GenericValue c : contacts) {
                delegator.create("WaCampaignRecipient", UtilMisc.toMap("campaignId", campaignId, "contactId", c.getString("contactId"),
                        "waId", c.getString("waId"), "statusId", "PENDING"));
            }
            cmp.set("totalCount", (long) contacts.size());
            cmp.store();
        }

        // 2. send to everyone still pending
        String templateName = tpl.getString("templateName");
        String lang = tpl.getString("languageCode");
        String bodyParams = cmp.getString("bodyParams");
        List<String> paramTpl = UtilValidate.isEmpty(bodyParams) ? null : WaUtil.splitParams(bodyParams);
        String sentBy = "BROADCAST:" + (cmp.getString("createdBy") == null ? "" : cmp.getString("createdBy"));
        Locale locale = Locale.getDefault();
        List<GenericValue> pending = EntityQuery.use(delegator).from("WaCampaignRecipient")
                .where("campaignId", campaignId, "statusId", "PENDING").orderBy("contactId").queryList();
        int i = 0;
        for (GenericValue r : pending) {
            if (i++ % 20 == 0) {
                GenericValue fresh = EntityQuery.use(delegator).from("WaCampaign").where("campaignId", campaignId).queryOne();
                if (fresh == null || "CANCELLED".equals(fresh.getString("statusId"))) {
                    return ServiceUtil.returnSuccess("Cancelled");
                }
            }
            GenericValue contact = EntityQuery.use(delegator).from("WaContact").where("contactId", r.getString("contactId")).queryOne();
            if (contact == null) {
                skip(r, "Contact was deleted");
                continue;
            }
            if ("N".equals(contact.getString("optInStatus"))) {
                skip(r, "Opted out");
                continue;
            }
            // stop the whole broadcast when the workspace can't send (quota used up, trial ended, number off)
            String cant = WaUtil.checkCanSend(delegator, cmp.getString("tenantId"), locale);
            if (cant == null && !"Y".equals(channel.getString("isActive"))) {
                cant = "The WhatsApp number is turned off.";
            }
            if (cant != null) {
                return fail(cmp, "Paused: " + cant + " Fix this and press Resume to send to the rest.");
            }
            List<String> params = null;
            if (paramTpl != null) {
                params = new ArrayList<>();
                for (String p : paramTpl) {
                    String v = WaUtil.render(p, contact, null);
                    params.add(UtilValidate.isEmpty(v) ? "-" : v);
                }
            }
            ObjectNode payload = WaMessenger.template(contact.getString("waId"), templateName, lang, params);
            String log = "[template " + templateName + "] " + (params == null ? "" : String.join(" | ", params));
            WaMessenger.SendResult res = WaMessenger.send(delegator, channel, contact, payload, log, sentBy, locale,
                    Map.of("campaignId", campaignId));
            // status webhooks may already have updated this row: only move it forward from PENDING
            EntityCondition pk = EntityCondition.makeCondition(UtilMisc.toMap("campaignId", campaignId, "contactId", r.getString("contactId")));
            delegator.storeByCondition("WaCampaignRecipient", UtilMisc.toMap("messageId", res.getMessageId(),
                    "sentDate", UtilDateTime.nowTimestamp()), pk);
            if (res.isOk()) {
                delegator.storeByCondition("WaCampaignRecipient", UtilMisc.toMap("statusId", "SENT"), EntityCondition.makeCondition(
                        pk, EntityCondition.makeCondition("statusId", "PENDING")));
            } else {
                delegator.storeByCondition("WaCampaignRecipient", UtilMisc.toMap("statusId", "FAILED", "errorText", res.getError()), pk);
            }
        }
        cmp.refresh();
        if (!"CANCELLED".equals(cmp.getString("statusId"))) {
            cmp.set("statusId", "DONE");
            cmp.set("completedDate", UtilDateTime.nowTimestamp());
            cmp.store();
        }
        return ServiceUtil.returnSuccess();
    }

    private static void skip(GenericValue r, String why) throws GenericEntityException {
        r.set("statusId", "SKIPPED");
        r.set("errorText", why);
        r.store();
    }

    private static Map<String, Object> fail(GenericValue cmp, String why) throws GenericEntityException {
        cmp.set("statusId", "FAILED");
        cmp.set("errorText", why);
        cmp.store();
        return ServiceUtil.returnSuccess(why);
    }

    // ------------------------------------------------------------------ webhook feedback
    /** A delivery status of a broadcast message arrived from Meta. */
    static void onStatus(Delegator delegator, GenericValue msg, String status, String error) {
        try {
            GenericValue r = EntityQuery.use(delegator).from("WaCampaignRecipient")
                    .where("campaignId", msg.getString("campaignId"), "contactId", msg.getString("contactId")).queryOne();
            if (r == null) {
                return;
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            String cur = r.getString("statusId");
            switch (status) {
            case "failed":
                r.set("statusId", "FAILED");
                r.set("errorText", error);
                break;
            case "delivered":
                if (r.get("deliveredDate") == null) {
                    r.set("deliveredDate", now);
                }
                if (ORDER.indexOf(cur) >= 0 && ORDER.indexOf(cur) < ORDER.indexOf("DELIVERED")) {
                    r.set("statusId", "DELIVERED");
                }
                break;
            case "read":
                if (r.get("deliveredDate") == null) {
                    r.set("deliveredDate", now);
                }
                if (r.get("readDate") == null) {
                    r.set("readDate", now);
                }
                if (!"FAILED".equals(cur)) {
                    r.set("statusId", "READ");
                }
                break;
            default:
                return;
            }
            r.store();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }
    }

    /** The customer wrote to us: mark recent broadcasts to them as replied (a reply also means it was read). */
    static void markReplied(Delegator delegator, String contactId, Timestamp when) {
        try {
            Timestamp since = new Timestamp(when.getTime() - REPLY_WINDOW_MS);
            List<GenericValue> recent = EntityQuery.use(delegator).from("WaCampaignRecipient").where(
                    EntityCondition.makeCondition("contactId", contactId),
                    EntityCondition.makeCondition("sentDate", EntityOperator.GREATER_THAN_EQUAL_TO, since),
                    EntityCondition.makeCondition("repliedDate", EntityOperator.EQUALS, null)).queryList();
            for (GenericValue r : recent) {
                if ("FAILED".equals(r.getString("statusId")) || "SKIPPED".equals(r.getString("statusId"))) {
                    continue;
                }
                r.set("repliedDate", when);
                if (r.get("deliveredDate") == null) {
                    r.set("deliveredDate", when);
                }
                if (r.get("readDate") == null) {
                    r.set("readDate", when);
                }
                r.set("statusId", "READ");
                r.store();
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }
    }

    // ------------------------------------------------------------------ report
    /** Counts for one campaign: total, pending, skipped, failed, sent, delivered, read, replied (sent counts everything that left). */
    public static Map<String, Long> stats(Delegator delegator, String campaignId) throws GenericEntityException {
        DynamicViewEntity dve = new DynamicViewEntity();
        dve.addMemberEntity("R", "WaCampaignRecipient");
        dve.addAlias("R", "campaignId", null, null, null, Boolean.TRUE, null);
        dve.addAlias("R", "statusId", null, null, null, Boolean.TRUE, null);
        dve.addAlias("R", "cnt", "contactId", null, null, null, "count");
        Map<String, Long> by = new HashMap<>();
        for (GenericValue g : EntityQuery.use(delegator).select("statusId", "cnt").from(dve).where("campaignId", campaignId).queryList()) {
            by.put(g.getString("statusId"), g.getLong("cnt"));
        }
        long pending = by.getOrDefault("PENDING", 0L);
        long skipped = by.getOrDefault("SKIPPED", 0L);
        long failed = by.getOrDefault("FAILED", 0L);
        long read = by.getOrDefault("READ", 0L);
        long delivered = by.getOrDefault("DELIVERED", 0L) + read;
        long sent = by.getOrDefault("SENT", 0L) + delivered;
        long replied = EntityQuery.use(delegator).from("WaCampaignRecipient").where(
                EntityCondition.makeCondition("campaignId", campaignId),
                EntityCondition.makeCondition("repliedDate", EntityOperator.NOT_EQUAL, null)).queryCount();
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("total", pending + skipped + failed + sent);
        out.put("pending", pending);
        out.put("sent", sent);
        out.put("delivered", delivered);
        out.put("read", read);
        out.put("replied", replied);
        out.put("failed", failed);
        out.put("skipped", skipped);
        return out;
    }
}
