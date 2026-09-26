package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.util.EntityListIterator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.security.Security;
import org.apache.ofbiz.service.LocalDispatcher;

/** Contacts (tags, custom fields, consent, CSV), team inbox tools (assign, status, notes, saved replies) and broadcast campaigns. */
public final class WaCrmEvents {
    private static final String MODULE = WaCrmEvents.class.getName();
    private static final int MAX_IMPORT_ROWS = 1000;
    public static final List<String> CHAT_STATUSES = List.of("OPEN", "PENDING", "SOLVED");

    private WaCrmEvents() { }

    // ------------------------------------------------------------------ access
    static final class Who {
        GenericValue userLogin;
        String userId;
        boolean admin;
        boolean owner;
        String tenantId;
    }

    /** The logged-in user working in their current workspace; null when not logged in or no workspace. */
    static Who who(HttpServletRequest request) {
        GenericValue ul = (GenericValue) request.getSession().getAttribute("userLogin");
        if (ul == null) {
            return null;
        }
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        Security security = (Security) request.getAttribute("security");
        Who w = new Who();
        w.userLogin = ul;
        w.userId = ul.getString("userLoginId");
        w.admin = security != null && security.hasEntityPermission("WABOT", "_ADMIN", ul);
        List<String> mine = WaUtil.getUserTenantIds(delegator, w.userId);
        Object cur = request.getSession().getAttribute("waTenantId");
        String t = cur == null ? null : cur.toString();
        if (!w.admin && !mine.contains(t)) {
            t = mine.isEmpty() ? null : mine.get(0);
        }
        if (t == null || (!w.admin && (security == null || !security.hasEntityPermission("WABOT", "_UPDATE", ul)))) {
            return null;
        }
        w.tenantId = t;
        w.owner = w.admin || "WA_OWNER".equals(WaUtil.getTenantRole(delegator, t, w.userId));
        return w;
    }

    private static GenericValue contactOf(Delegator delegator, Who w, String contactId) throws GenericEntityException {
        if (w == null || UtilValidate.isEmpty(contactId)) {
            return null;
        }
        GenericValue c = EntityQuery.use(delegator).from("WaContact").where("contactId", contactId).queryOne();
        return c != null && w.tenantId.equals(c.getString("tenantId")) ? c : null;
    }

    private static GenericValue channelOf(Delegator delegator, Who w, String channelId) throws GenericEntityException {
        if (w == null || UtilValidate.isEmpty(channelId)) {
            return null;
        }
        GenericValue ch = EntityQuery.use(delegator).from("WaChannel").where("channelId", channelId).queryOne();
        return ch != null && w.tenantId.equals(ch.getString("tenantId")) ? ch : null;
    }

    private static Delegator del(HttpServletRequest request) {
        return (Delegator) request.getAttribute("delegator");
    }

    private static String p(HttpServletRequest request, String name) {
        String v = request.getParameter(name);
        return v == null ? null : v.trim();
    }

    // ================================================================== contacts
    /** POST contactSave: contactId (edit) or channelId + phone (new); profileName, email, tags, f_[key], optInStatus */
    public static String contactSave(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            GenericValue c;
            boolean isNew = UtilValidate.isEmpty(p(request, "contactId"));
            if (isNew) {
                GenericValue ch = channelOf(delegator, w, p(request, "channelId"));
                if (ch == null) {
                    return error(response, 400, "Choose the WhatsApp number this contact belongs to.");
                }
                String num = WaContacts.cleanNumber(p(request, "phone"), WaContacts.countryCodeOf(ch));
                if (num == null) {
                    return error(response, 400, "Enter a valid WhatsApp number with country code, e.g. +91 98123 45678.");
                }
                if (EntityQuery.use(delegator).from("WaContact").where("channelId", ch.getString("channelId"), "waId", num).queryCount() > 0) {
                    return error(response, 400, "+" + num + " is already in your contacts.");
                }
                c = WaMessenger.findOrCreateContact(delegator, ch, num, null, "MANUAL");
            } else {
                c = contactOf(delegator, w, p(request, "contactId"));
                if (c == null) {
                    return error(response, 404, "Contact not found.");
                }
            }
            String name = p(request, "profileName");
            if (name != null) {
                c.set("profileName", name.isEmpty() ? null : cut(name, 100));
            }
            String email = p(request, "email");
            if (email != null) {
                if (!email.isEmpty() && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    return error(response, 400, "That email address doesn't look right.");
                }
                c.set("email", email.isEmpty() ? null : cut(email, 250));
            }
            Map<String, String> vals = new LinkedHashMap<>();
            for (GenericValue f : WaContacts.fieldDefs(delegator, w.tenantId)) {
                String v = request.getParameter("f_" + f.getString("fieldKey"));
                if (v != null) {
                    vals.put(f.getString("fieldKey"), v);
                }
            }
            WaContacts.putFields(c, vals);
            String opt = p(request, "optInStatus");
            if (("Y".equals(opt) || "N".equals(opt)) && !opt.equals(c.getString("optInStatus"))) {
                WaContacts.setConsent(c, "Y".equals(opt), "MANUAL");
            }
            c.store();
            if (request.getParameter("tags") != null) {
                WaContacts.setTags(delegator, c, WaContacts.parseTags(request.getParameter("tags")));
            }
            ObjectNode out = WaUtil.JSON.createObjectNode().put("ok", true).put("contactId", c.getString("contactId"));
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not save the contact.");
        }
    }

    /** GET contactData?contactId= : one contact for the edit panel */
    public static String contactData(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            GenericValue c = contactOf(delegator, w, p(request, "contactId"));
            if (c == null) {
                return error(response, 404, "Contact not found.");
            }
            ObjectNode o = WaUtil.JSON.createObjectNode();
            o.put("contactId", c.getString("contactId")).put("waId", c.getString("waId"))
                    .put("profileName", c.getString("profileName")).put("email", c.getString("email"))
                    .put("optInStatus", c.getString("optInStatus")).put("optSource", c.getString("optSource"))
                    .put("channelId", c.getString("channelId"));
            o.put("optInDate", fmt(c.getTimestamp("optInDate"))).put("optOutDate", fmt(c.getTimestamp("optOutDate")));
            ArrayNode tags = o.putArray("tags");
            WaContacts.tagsOf(delegator, c.getString("contactId")).forEach(tags::add);
            ObjectNode f = o.putObject("fields");
            WaContacts.fields(c).forEach(f::put);
            return json(response, 200, o);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not load the contact.");
        }
    }

    /** POST contactsBulk: contactIds (comma list), action = tag | untag | optin | optout | delete, tag */
    public static String contactsBulk(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            String action = String.valueOf(p(request, "action"));
            if ("delete".equals(action) && !w.owner) {
                return error(response, 403, "Only the workspace owner can delete contacts.");
            }
            Set<String> tags = WaContacts.parseTags(p(request, "tag"));
            if (("tag".equals(action) || "untag".equals(action)) && tags.isEmpty()) {
                return error(response, 400, "Type a tag.");
            }
            int n = 0;
            for (String id : WaUtil.splitCsv(p(request, "contactIds"))) {
                GenericValue c = contactOf(delegator, w, id);
                if (c == null) {
                    continue;
                }
                switch (action) {
                case "tag":
                    WaContacts.addTags(delegator, c, tags);
                    break;
                case "untag":
                    WaContacts.removeTags(delegator, c.getString("contactId"), tags);
                    break;
                case "optin":
                case "optout":
                    boolean in = "optin".equals(action);
                    if (!(in ? "Y" : "N").equals(c.getString("optInStatus"))) {
                        WaContacts.setConsent(c, in, "MANUAL");
                        c.store();
                    }
                    break;
                case "delete":
                    WaContacts.delete(delegator, c.getString("contactId"));
                    break;
                default:
                    return error(response, 400, "Unknown action.");
                }
                n++;
            }
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true).put("count", n));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not update the contacts.");
        }
    }

    /**
     * POST contactsImport: channelId, consent=Y, rows = JSON [{phone, name, email, tags, fields:{key:value}}],
     * tags (added to every row), update=Y (update existing contacts), countryCode (for numbers without one)
     */
    public static String contactsImport(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            GenericValue ch = channelOf(delegator, w, p(request, "channelId"));
            if (ch == null) {
                return error(response, 400, "Choose the WhatsApp number to import into.");
            }
            if (!"Y".equals(p(request, "consent"))) {
                return error(response, 400, "Please confirm these people agreed to get WhatsApp messages from you.");
            }
            JsonNode rows = WaUtil.JSON.readTree(String.valueOf(request.getParameter("rows")));
            if (!rows.isArray() || rows.size() == 0) {
                return error(response, 400, "No rows to import.");
            }
            if (rows.size() > MAX_IMPORT_ROWS) {
                return error(response, 400, "Send at most " + MAX_IMPORT_ROWS + " rows at a time.");
            }
            String cc = p(request, "countryCode");
            if (UtilValidate.isEmpty(cc)) {
                cc = WaContacts.countryCodeOf(ch);
            }
            boolean update = "Y".equals(p(request, "update"));
            Set<String> allTags = WaContacts.parseTags(p(request, "tags"));
            Set<String> keys = new LinkedHashSet<>();
            for (GenericValue f : WaContacts.fieldDefs(delegator, w.tenantId)) {
                keys.add(f.getString("fieldKey"));
            }
            int created = 0;
            int updated = 0;
            int skipped = 0;
            ArrayNode invalid = WaUtil.JSON.createArrayNode();
            int line = parseInt(p(request, "firstLine"), 2);
            for (JsonNode row : rows) {
                int thisLine = line++;
                String num = WaContacts.cleanNumber(row.path("phone").asText(""), cc);
                if (num == null) {
                    if (invalid.size() < 50) {
                        invalid.addObject().put("line", thisLine).put("phone", cut(row.path("phone").asText(""), 40));
                    }
                    continue;
                }
                GenericValue c = EntityQuery.use(delegator).from("WaContact").where("channelId", ch.getString("channelId"), "waId", num).queryFirst();
                boolean isNew = c == null;
                if (!isNew && !update) {
                    skipped++;
                    continue;
                }
                if (isNew) {
                    c = WaMessenger.findOrCreateContact(delegator, ch, num, null, "IMPORT");
                }
                String name = row.path("name").asText("").trim();
                if (!name.isEmpty()) {
                    c.set("profileName", cut(name, 100));
                }
                String email = row.path("email").asText("").trim();
                if (!email.isEmpty() && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    c.set("email", cut(email, 250));
                }
                Map<String, String> vals = new LinkedHashMap<>();
                row.path("fields").properties().forEach(e -> {
                    if (keys.contains(e.getKey()) && !e.getValue().asText("").trim().isEmpty()) {
                        vals.put(e.getKey(), e.getValue().asText());
                    }
                });
                WaContacts.putFields(c, vals);
                c.store();
                Set<String> tags = new LinkedHashSet<>(allTags);
                tags.addAll(WaContacts.parseTags(row.path("tags").asText("")));
                WaContacts.addTags(delegator, c, tags);
                if (isNew) {
                    created++;
                } else {
                    updated++;
                }
            }
            ObjectNode out = WaUtil.JSON.createObjectNode().put("ok", true).put("created", created).put("updated", updated)
                    .put("skipped", skipped);
            out.set("invalid", invalid);
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not import the contacts: " + e.getMessage());
        }
    }

    /** GET contactsExport?tag=&q=&opt=&channelId= : CSV download (workspace owner) */
    public static String contactsExport(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            if (w == null || !w.owner) {
                return error(response, 403, "Only the workspace owner can export contacts.");
            }
            List<GenericValue> defs = WaContacts.fieldDefs(delegator, w.tenantId);
            Map<String, String> chNames = new LinkedHashMap<>();
            for (GenericValue ch : EntityQuery.use(delegator).from("WaChannel").where("tenantId", w.tenantId).queryList()) {
                chNames.put(ch.getString("channelId"), ch.getString("displayPhoneNumber"));
            }
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"contacts-" + new SimpleDateFormat("yyyyMMdd").format(new java.util.Date()) + ".csv\"");
            response.setHeader("Cache-Control", "no-store");
            PrintWriter out = response.getWriter();
            out.write('﻿'); // Excel: UTF-8
            List<String> head = new ArrayList<>(List.of("phone", "name", "email", "tags", "opted_in", "consent_source", "opted_in_date",
                    "opted_out_date", "whatsapp_number", "created", "last_message"));
            for (GenericValue f : defs) {
                head.add(f.getString("label"));
            }
            out.write(csvLine(head));
            try (EntityListIterator it = EntityQuery.use(delegator).from("WaContact").where(ContactFilter.from(request, delegator, w.tenantId).conditions())
                    .orderBy("-lastMessageDate").queryIterator()) {
                ContactFilter f = ContactFilter.from(request, delegator, w.tenantId);
                GenericValue c;
                while ((c = it.next()) != null) {
                    if (!f.matches(c)) {
                        continue;
                    }
                    Map<String, String> vals = WaContacts.fields(c);
                    List<String> row = new ArrayList<>(Arrays.asList("+" + c.getString("waId"), c.getString("profileName"), c.getString("email"),
                            String.join(", ", WaContacts.tagsOf(delegator, c.getString("contactId"))),
                            "N".equals(c.getString("optInStatus")) ? "no" : "yes", c.getString("optSource"),
                            fmt(c.getTimestamp("optInDate")), fmt(c.getTimestamp("optOutDate")), chNames.get(c.getString("channelId")),
                            fmt(c.getTimestamp("createdDate")), fmt(c.getTimestamp("lastMessageDate"))));
                    for (GenericValue d : defs) {
                        row.add(vals.get(d.getString("fieldKey")));
                    }
                    out.write(csvLine(row));
                }
            }
            out.flush();
            return "none";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not export the contacts.");
        }
    }

    /** POST contactFieldAdd label / contactFieldRemove fieldKey */
    public static String contactFieldAdd(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            String err = WaContacts.addField(delegator, w.tenantId, p(request, "label"));
            if (err != null) {
                return error(response, 400, err);
            }
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true).put("key", WaContacts.fieldKeyFor(p(request, "label"))));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not add the field.");
        }
    }

    public static String contactFieldRemove(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            if (w == null || !w.owner) {
                return error(response, 403, "Only the workspace owner can remove fields.");
            }
            delegator.removeByAnd("WaContactField", UtilMisc.toMap("tenantId", w.tenantId, "fieldKey", p(request, "fieldKey")));
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not remove the field.");
        }
    }

    /** Filters shared by the Contacts page and the CSV export: q (name/number/email), tag, opt (Y/N), channelId. */
    public static final class ContactFilter {
        String tenantId;
        String q;
        String digits;
        String opt;
        String channelId;
        Set<String> tagged;

        public static ContactFilter from(HttpServletRequest request, Delegator delegator, String tenantId) throws GenericEntityException {
            return of(delegator, tenantId, request.getParameter("q"), request.getParameter("tag"), request.getParameter("opt"),
                    request.getParameter("channelId"));
        }

        public static ContactFilter of(Delegator delegator, String tenantId, String q, String tag, String opt, String channelId)
                throws GenericEntityException {
            ContactFilter f = new ContactFilter();
            f.tenantId = tenantId;
            f.q = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            f.digits = f.q.replaceAll("[^0-9]", "");
            f.opt = "Y".equals(opt) || "N".equals(opt) ? opt : null;
            f.channelId = UtilValidate.isEmpty(channelId) ? null : channelId;
            String t = WaContacts.normTag(tag);
            f.tagged = t == null ? null : WaContacts.contactsWithTags(delegator, tenantId, List.of(t));
            return f;
        }

        public List<EntityCondition> conditions() {
            List<EntityCondition> c = new ArrayList<>();
            c.add(EntityCondition.makeCondition("tenantId", tenantId));
            if (channelId != null) {
                c.add(EntityCondition.makeCondition("channelId", channelId));
            }
            if ("N".equals(opt)) {
                c.add(EntityCondition.makeCondition("optInStatus", "N"));
            }
            return c;
        }

        public boolean matches(GenericValue c) {
            if (tagged != null && !tagged.contains(c.getString("contactId"))) {
                return false;
            }
            if ("Y".equals(opt) && "N".equals(c.getString("optInStatus"))) {
                return false;
            }
            if (!q.isEmpty()) {
                boolean hit = String.valueOf(c.getString("profileName")).toLowerCase(Locale.ROOT).contains(q)
                        || String.valueOf(c.getString("email")).toLowerCase(Locale.ROOT).contains(q)
                        || (digits.length() >= 3 && String.valueOf(c.getString("waId")).contains(digits))
                        || String.valueOf(c.getString("customFields")).toLowerCase(Locale.ROOT).contains(q);
                if (!hit) {
                    return false;
                }
            }
            return true;
        }
    }

    // ================================================================== team inbox
    /** Assign an unassigned chat to the agent who replies or takes it over. */
    public static void assignIfUnassigned(Delegator delegator, String contactId, String userLoginId) {
        try {
            GenericValue c = EntityQuery.use(delegator).from("WaContact").where("contactId", contactId).queryOne();
            if (c != null && UtilValidate.isEmpty(c.getString("assignedTo"))
                    && WaUtil.getUserTenantIds(delegator, userLoginId).contains(c.getString("tenantId"))) {
                delegator.storeByCondition("WaContact", UtilMisc.toMap("assignedTo", userLoginId),
                        EntityCondition.makeCondition(UtilMisc.toMap("contactId", contactId)));
            }
        } catch (GenericEntityException e) {
            Debug.logWarning(e, MODULE);
        }
    }

    /** POST chatAssign contactId, assignTo ("" = unassign) -> back to the chat */
    public static String chatAssign(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            GenericValue c = contactOf(delegator, w, p(request, "contactId"));
            if (c == null) {
                return flash(request, "Chat not found.");
            }
            String to = p(request, "assignTo");
            if (UtilValidate.isNotEmpty(to) && EntityQuery.use(delegator).from("WaTenantUser")
                    .where("tenantId", w.tenantId, "userLoginId", to).queryOne() == null) {
                return flash(request, "That person is not in your team.");
            }
            delegator.storeByCondition("WaContact", UtilMisc.toMap("assignedTo", UtilValidate.isEmpty(to) ? null : to),
                    EntityCondition.makeCondition(UtilMisc.toMap("contactId", c.getString("contactId"))));
            systemNote(delegator, c, w.userId, UtilValidate.isEmpty(to) ? "Unassigned" : "Assigned to " + to);
            return "success";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return flash(request, "Could not assign the chat.");
        }
    }

    /** POST chatStatus contactId, status OPEN | PENDING | SOLVED */
    public static String chatStatus(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            GenericValue c = contactOf(delegator, w, p(request, "contactId"));
            String st = p(request, "status");
            if (c == null || !CHAT_STATUSES.contains(st)) {
                return flash(request, "Chat not found.");
            }
            Map<String, Object> fields = UtilMisc.toMap("chatStatus", st);
            if ("SOLVED".equals(st)) {
                fields.put("unreadCount", 0L);
            }
            delegator.storeByCondition("WaContact", fields, EntityCondition.makeCondition(UtilMisc.toMap("contactId", c.getString("contactId"))));
            if (!st.equals(c.getString("chatStatus") == null ? "OPEN" : c.getString("chatStatus"))) {
                systemNote(delegator, c, w.userId, "Marked " + st.toLowerCase(Locale.ROOT));
            }
            return "success";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return flash(request, "Could not change the status.");
        }
    }

    /** POST chatNote contactId, noteText */
    public static String chatNote(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            GenericValue c = contactOf(delegator, w, p(request, "contactId"));
            String text = p(request, "noteText");
            if (c == null) {
                return flash(request, "Chat not found.");
            }
            if (UtilValidate.isEmpty(text)) {
                return flash(request, "Write a note first.");
            }
            delegator.create("WaNote", UtilMisc.toMap("noteId", delegator.getNextSeqId("WaNote"), "tenantId", w.tenantId,
                    "contactId", c.getString("contactId"), "noteText", cut(text, 4000), "createdBy", w.userId,
                    "createdDate", UtilDateTime.nowTimestamp()));
            touch(delegator, c.getString("contactId"));
            return "success";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return flash(request, "Could not save the note.");
        }
    }

    private static void systemNote(Delegator delegator, GenericValue c, String by, String text) throws GenericEntityException {
        delegator.create("WaNote", UtilMisc.toMap("noteId", delegator.getNextSeqId("WaNote"), "tenantId", c.getString("tenantId"),
                "contactId", c.getString("contactId"), "noteText", "• " + text, "createdBy", by, "createdDate", UtilDateTime.nowTimestamp()));
        touch(delegator, c.getString("contactId"));
    }

    /** A one-line event in the chat timeline (only the team sees it). */
    public static void addSystemNote(Delegator delegator, String tenantId, String contactId, String by, String text) {
        try {
            delegator.create("WaNote", UtilMisc.toMap("noteId", delegator.getNextSeqId("WaNote"), "tenantId", tenantId,
                    "contactId", contactId, "noteText", "\u2022 " + cut(text, 1000), "createdBy", by, "createdDate", UtilDateTime.nowTimestamp()));
        } catch (GenericEntityException e) {
            Debug.logWarning(e, MODULE);
        }
    }

    /** Bump the contact's stamp so live inbox views refresh. */
    private static void touch(Delegator delegator, String contactId) throws GenericEntityException {
        delegator.storeByCondition("WaContact", UtilMisc.toMap("lastUpdatedStamp", UtilDateTime.nowTimestamp()),
                EntityCondition.makeCondition(UtilMisc.toMap("contactId", contactId)));
    }

    /** POST quickReplySave quickReplyId (edit), shortcut, body -> JSON */
    public static String quickReplySave(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            String sc = String.valueOf(p(request, "shortcut")).toLowerCase(Locale.ROOT).replaceAll("^/+", "").replaceAll("[^a-z0-9_-]", "");
            String body = p(request, "body");
            if (sc.isEmpty() || sc.length() > 30) {
                return error(response, 400, "Give the reply a short name (letters, numbers, - or _), e.g. price.");
            }
            if (UtilValidate.isEmpty(body)) {
                return error(response, 400, "Write the reply text.");
            }
            String id = p(request, "quickReplyId");
            GenericValue qr = UtilValidate.isEmpty(id) ? null : EntityQuery.use(delegator).from("WaQuickReply").where("quickReplyId", id).queryOne();
            if (qr != null && !w.tenantId.equals(qr.getString("tenantId"))) {
                return error(response, 404, "Saved reply not found.");
            }
            GenericValue same = EntityQuery.use(delegator).from("WaQuickReply").where("tenantId", w.tenantId, "shortcut", sc).queryFirst();
            if (same != null && (qr == null || !same.getString("quickReplyId").equals(qr.getString("quickReplyId")))) {
                return error(response, 400, "You already have a saved reply called /" + sc + ".");
            }
            if (qr == null) {
                if (EntityQuery.use(delegator).from("WaQuickReply").where("tenantId", w.tenantId).queryCount() >= 200) {
                    return error(response, 400, "You can keep up to 200 saved replies.");
                }
                qr = delegator.makeValue("WaQuickReply", UtilMisc.toMap("quickReplyId", delegator.getNextSeqId("WaQuickReply"),
                        "tenantId", w.tenantId, "createdBy", w.userId, "createdDate", UtilDateTime.nowTimestamp()));
            }
            qr.set("shortcut", sc);
            qr.set("body", cut(body, 4000));
            delegator.createOrStore(qr);
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true).put("quickReplyId", qr.getString("quickReplyId")));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not save the reply.");
        }
    }

    public static String quickReplyDelete(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            delegator.removeByAnd("WaQuickReply", UtilMisc.toMap("tenantId", w.tenantId, "quickReplyId", p(request, "quickReplyId")));
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not delete the reply.");
        }
    }

    // ================================================================== broadcasts
    /** GET campaignEstimate: channelId, audienceType, tags, excludeTags, numbers -> {total, optedOut, eligible, invalid} */
    public static String campaignEstimate(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            GenericValue ch = channelOf(delegator, w, p(request, "channelId"));
            if (ch == null) {
                return error(response, 400, "Choose a WhatsApp number.");
            }
            Map<String, Object> est = WaCampaigns.estimate(delegator, ch, p(request, "audienceType"), p(request, "tags"),
                    p(request, "excludeTags"), request.getParameter("numbers"));
            return json(response, 200, WaUtil.JSON.valueToTree(est));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not count the audience.");
        }
    }

    /**
     * POST campaignCreate: campaignName, channelId, templateId, bodyParams, audienceType (TAGS | ALL | NUMBERS), tags,
     * excludeTags, numbers, sendAt (epoch millis, empty = now)
     */
    public static String campaignCreate(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        try {
            Who w = who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            GenericValue ch = channelOf(delegator, w, p(request, "channelId"));
            if (ch == null) {
                return error(response, 400, "Choose the WhatsApp number to send from.");
            }
            GenericValue tpl = UtilValidate.isEmpty(p(request, "templateId")) ? null
                    : EntityQuery.use(delegator).from("WaTemplate").where("templateId", p(request, "templateId")).queryOne();
            if (tpl == null || !ch.getString("channelId").equals(tpl.getString("channelId"))) {
                return error(response, 400, "Choose an approved template of this WhatsApp number.");
            }
            if (!"APPROVED".equals(tpl.getString("metaStatus"))) {
                return error(response, 400, "This template is not approved by Meta yet.");
            }
            String type = p(request, "audienceType");
            if (!List.of("TAGS", "ALL", "NUMBERS").contains(type)) {
                return error(response, 400, "Choose who should get this broadcast.");
            }
            if ("TAGS".equals(type) && WaContacts.parseTags(p(request, "tags")).isEmpty()) {
                return error(response, 400, "Pick at least one tag.");
            }
            int needed = placeholders(tpl.getString("bodyText"));
            String bodyParams = request.getParameter("bodyParams");
            List<String> givenList = UtilValidate.isEmpty(bodyParams) ? List.of() : WaUtil.splitParams(bodyParams);
            int given = givenList.size();
            if (needed == given && givenList.stream().anyMatch(v -> v.trim().isEmpty())) {
                return error(response, 400, "Fill in every template value.");
            }
            if (needed != given) {
                return error(response, 400, "This template needs " + needed + " value" + (needed == 1 ? "" : "s") + " ({{1}}"
                        + (needed > 1 ? "…{{" + needed + "}}" : "") + "). Fill in every value.");
            }
            Map<String, Object> est = WaCampaigns.estimate(delegator, ch, type, p(request, "tags"), p(request, "excludeTags"), request.getParameter("numbers"));
            if (((Long) est.get("eligible")) <= 0) {
                return error(response, 400, "Nobody to send to: no opted-in contacts match this audience.");
            }
            long now = System.currentTimeMillis();
            long sendAt = parseLong(p(request, "sendAt"), 0L);
            boolean later = sendAt > now + 60_000L;
            if (later && sendAt > now + 60L * 24 * 3600 * 1000) {
                return error(response, 400, "You can schedule up to 60 days ahead.");
            }
            String name = p(request, "campaignName");
            String id = delegator.getNextSeqId("WaCampaign");
            GenericValue cmp = delegator.makeValue("WaCampaign", UtilMisc.toMap("campaignId", id, "tenantId", w.tenantId,
                    "channelId", ch.getString("channelId"),
                    "campaignName", UtilValidate.isEmpty(name) ? tpl.getString("templateName") + " " + new SimpleDateFormat("d MMM").format(new java.util.Date()) : cut(name, 100),
                    "templateId", tpl.getString("templateId"), "templateName", tpl.getString("templateName"),
                    "languageCode", tpl.getString("languageCode"), "bodyParams", UtilValidate.isEmpty(bodyParams) ? null : bodyParams,
                    "audienceType", type, "audienceTags", String.join(",", WaContacts.parseTags(p(request, "tags"))),
                    "excludeTags", String.join(",", WaContacts.parseTags(p(request, "excludeTags"))),
                    "numbers", "NUMBERS".equals(type) ? request.getParameter("numbers") : null,
                    "statusId", "SCHEDULED", "scheduledDate", new Timestamp(later ? sendAt : now),
                    "createdBy", w.userId, "createdDate", UtilDateTime.nowTimestamp()));
            cmp.create();
            start(dispatcher, id, later ? sendAt : 0L);
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true).put("campaignId", id).put("scheduled", later));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not create the broadcast.");
        }
    }

    private static void start(LocalDispatcher dispatcher, String campaignId, long at) throws Exception {
        Map<String, Object> ctx = UtilMisc.toMap("campaignId", campaignId);
        if (at > 0) {
            dispatcher.schedule("waRunCampaign", ctx, at); // stored job: survives restarts
        } else {
            dispatcher.runAsync("waRunCampaign", ctx, false);
        }
    }

    /** POST campaignCancel / campaignResume / campaignSendNow campaignId */
    public static String campaignAction(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        try {
            Who w = who(request);
            GenericValue cmp = w == null ? null : EntityQuery.use(delegator).from("WaCampaign").where("campaignId", p(request, "campaignId")).queryOne();
            if (cmp == null || !w.tenantId.equals(cmp.getString("tenantId"))) {
                return flash(request, "Broadcast not found.");
            }
            String st = cmp.getString("statusId");
            switch (String.valueOf(p(request, "do"))) {
            case "cancel":
                if (!"SCHEDULED".equals(st) && !"SENDING".equals(st) && !"FAILED".equals(st)) {
                    return flash(request, "This broadcast has already finished.");
                }
                cmp.set("statusId", "CANCELLED");
                cmp.set("completedDate", UtilDateTime.nowTimestamp());
                cmp.store();
                break;
            case "sendnow":
                if (!"SCHEDULED".equals(st)) {
                    return flash(request, "Only a scheduled broadcast can be sent now.");
                }
                cmp.set("scheduledDate", UtilDateTime.nowTimestamp());
                cmp.store();
                start(dispatcher, cmp.getString("campaignId"), 0L);
                break;
            case "resume":
                boolean stale = "SENDING".equals(st) && cmp.getTimestamp("startedDate") != null
                        && System.currentTimeMillis() - cmp.getTimestamp("startedDate").getTime() > 10 * 60_000L;
                if (!"FAILED".equals(st) && !stale) {
                    return flash(request, "This broadcast is not paused.");
                }
                start(dispatcher, cmp.getString("campaignId"), 0L);
                break;
            default:
                return flash(request, "Unknown action.");
            }
            return "success";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return flash(request, "Could not update the broadcast.");
        }
    }

    /** GET campaignExport?campaignId= : recipients and their status as CSV */
    public static String campaignExport(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            Who w = who(request);
            GenericValue cmp = w == null ? null : EntityQuery.use(delegator).from("WaCampaign").where("campaignId", p(request, "campaignId")).queryOne();
            if (cmp == null || !w.tenantId.equals(cmp.getString("tenantId"))) {
                return error(response, 404, "Broadcast not found.");
            }
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"broadcast-" + cmp.getString("campaignId") + ".csv\"");
            PrintWriter out = response.getWriter();
            out.write('﻿');
            out.write(csvLine(List.of("phone", "name", "status", "sent", "delivered", "read", "replied", "error")));
            try (EntityListIterator it = EntityQuery.use(delegator).from("WaCampaignRecipient").where("campaignId", cmp.getString("campaignId"))
                    .orderBy("contactId").queryIterator()) {
                GenericValue r;
                while ((r = it.next()) != null) {
                    GenericValue c = EntityQuery.use(delegator).from("WaContact").where("contactId", r.getString("contactId")).queryOne();
                    out.write(csvLine(Arrays.asList("+" + r.getString("waId"), c == null ? "" : c.getString("profileName"),
                            r.getString("statusId").toLowerCase(Locale.ROOT), fmt(r.getTimestamp("sentDate")), fmt(r.getTimestamp("deliveredDate")),
                            fmt(r.getTimestamp("readDate")), fmt(r.getTimestamp("repliedDate")), r.getString("errorText"))));
                }
            }
            out.flush();
            return "none";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not export.");
        }
    }

    /** Number of {{n}} placeholders in a template body (highest index). */
    public static int placeholders(String body) {
        int max = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{\\s*(\\d+)\\s*}}").matcher(body == null ? "" : body);
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }

    // ------------------------------------------------------------------ helpers
    private static final ThreadLocal<SimpleDateFormat> DT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm"));

    static String fmt(Timestamp t) {
        return t == null ? "" : DT.get().format(t);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    /** One CSV line; cells that start like a formula are prefixed with ' so spreadsheets don't run them. */
    static String csvLine(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            String v = cells.get(i) == null ? "" : cells.get(i);
            if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !(v.startsWith("+") && v.substring(1).matches("[0-9]+"))) {
                v = "'" + v;
            }
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                v = "\"" + v.replace("\"", "\"\"") + "\"";
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.append("\r\n").toString();
    }

    private static String flash(HttpServletRequest request, String msg) {
        request.setAttribute("_ERROR_MESSAGE_", msg);
        return "error";
    }

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
