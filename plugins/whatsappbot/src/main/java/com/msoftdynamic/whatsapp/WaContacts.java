package com.msoftdynamic.whatsapp;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

/** Contact helpers: tags, custom fields, consent (opt-in / opt-out) and phone number clean-up. */
public final class WaContacts {

    public static final int MAX_TAG = 40;
    public static final int MAX_TAGS_PER_CONTACT = 30;
    public static final int MAX_FIELDS = 30;
    public static final int MAX_FIELD_VALUE = 500;
    private static final Pattern FIELD_KEY = Pattern.compile("[a-z][a-z0-9_]{0,29}");
    /** Keys already used by {{...}} placeholders; custom fields may not reuse them. */
    public static final Set<String> RESERVED_KEYS = Set.of("name", "phone", "whatsapp", "email", "lastchoice", "zohoerror");

    private WaContacts() { }

    // ------------------------------------------------------------------ tags
    /** "  VIP Customer " -> "vip customer"; null when empty. */
    public static String normTag(String t) {
        if (t == null) {
            return null;
        }
        String s = t.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        s = s.replaceAll("[,<>\"'`|]", "");
        if (s.length() > MAX_TAG) {
            s = s.substring(0, MAX_TAG).trim();
        }
        return s.isEmpty() ? null : s;
    }

    public static Set<String> parseTags(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) {
            return out;
        }
        for (String p : csv.split("[,;\\n]")) {
            String t = normTag(p);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    public static List<String> tagsOf(Delegator delegator, String contactId) throws GenericEntityException {
        List<String> out = new ArrayList<>();
        for (GenericValue t : EntityQuery.use(delegator).from("WaContactTag").where("contactId", contactId).orderBy("tag").queryList()) {
            out.add(t.getString("tag"));
        }
        return out;
    }

    /** All tags used in a workspace with their contact counts, most used first. */
    public static Map<String, Long> tenantTags(Delegator delegator, String tenantId) throws GenericEntityException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (GenericValue t : EntityQuery.use(delegator).select("tag").from("WaContactTag").where("tenantId", tenantId).queryList()) {
            counts.merge(t.getString("tag"), 1L, Long::sum);
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue().equals(a.getValue()) ? a.getKey().compareTo(b.getKey()) : Long.compare(b.getValue(), a.getValue()));
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : list) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public static int addTags(Delegator delegator, GenericValue contact, Collection<String> tags) throws GenericEntityException {
        int added = 0;
        Set<String> have = new TreeSet<>(tagsOf(delegator, contact.getString("contactId")));
        for (String raw : tags) {
            String t = normTag(raw);
            if (t == null || have.contains(t) || have.size() >= MAX_TAGS_PER_CONTACT) {
                continue;
            }
            delegator.create("WaContactTag", UtilMisc.toMap("contactId", contact.getString("contactId"), "tag", t,
                    "tenantId", contact.getString("tenantId"), "createdDate", UtilDateTime.nowTimestamp()));
            have.add(t);
            added++;
        }
        return added;
    }

    public static int removeTags(Delegator delegator, String contactId, Collection<String> tags) throws GenericEntityException {
        int n = 0;
        for (String raw : tags) {
            String t = normTag(raw);
            if (t != null) {
                n += delegator.removeByAnd("WaContactTag", UtilMisc.toMap("contactId", contactId, "tag", t));
            }
        }
        return n;
    }

    /** Replace a contact's tags with exactly these. */
    public static void setTags(Delegator delegator, GenericValue contact, Collection<String> tags) throws GenericEntityException {
        Set<String> want = new LinkedHashSet<>();
        for (String t : tags) {
            String n = normTag(t);
            if (n != null && want.size() < MAX_TAGS_PER_CONTACT) {
                want.add(n);
            }
        }
        List<String> have = tagsOf(delegator, contact.getString("contactId"));
        List<String> remove = new ArrayList<>(have);
        remove.removeAll(want);
        removeTags(delegator, contact.getString("contactId"), remove);
        addTags(delegator, contact, want);
    }

    /** Contact ids of a workspace having any of the tags. */
    public static Set<String> contactsWithTags(Delegator delegator, String tenantId, Collection<String> tags) throws GenericEntityException {
        Set<String> out = new LinkedHashSet<>();
        List<String> norm = new ArrayList<>();
        for (String t : tags) {
            String n = normTag(t);
            if (n != null) {
                norm.add(n);
            }
        }
        if (norm.isEmpty()) {
            return out;
        }
        for (GenericValue t : EntityQuery.use(delegator).select("contactId").from("WaContactTag")
                .where(EntityCondition.makeCondition("tenantId", tenantId), EntityCondition.makeCondition("tag", EntityOperator.IN, norm))
                .queryList()) {
            out.add(t.getString("contactId"));
        }
        return out;
    }

    // ------------------------------------------------------------------ custom fields
    public static List<GenericValue> fieldDefs(Delegator delegator, String tenantId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("WaContactField").where("tenantId", tenantId).orderBy("sequenceNum", "label").queryList();
    }

    /** "Date of birth" -> "date_of_birth"; null if it can't be made valid. */
    public static String fieldKeyFor(String label) {
        if (label == null) {
            return null;
        }
        String k = label.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (!k.isEmpty() && Character.isDigit(k.charAt(0))) {
            k = "f_" + k;
        }
        if (k.length() > 30) {
            k = k.substring(0, 30).replaceAll("_+$", "");
        }
        return FIELD_KEY.matcher(k).matches() && !RESERVED_KEYS.contains(k) ? k : null;
    }

    /** Add a custom field; returns an error text or null. */
    public static String addField(Delegator delegator, String tenantId, String label) throws GenericEntityException {
        String clean = label == null ? "" : label.trim();
        if (clean.isEmpty() || clean.length() > 60) {
            return "Give the field a name (up to 60 characters).";
        }
        String key = fieldKeyFor(clean);
        if (key == null) {
            return "\"" + clean + "\" can't be used as a field name. Use letters and numbers (name, phone and email already exist).";
        }
        List<GenericValue> defs = fieldDefs(delegator, tenantId);
        if (defs.size() >= MAX_FIELDS) {
            return "You can have up to " + MAX_FIELDS + " custom fields.";
        }
        for (GenericValue d : defs) {
            if (key.equals(d.getString("fieldKey"))) {
                return "A field called \"" + d.getString("label") + "\" already exists.";
            }
        }
        delegator.create("WaContactField", UtilMisc.toMap("tenantId", tenantId, "fieldKey", key, "label", clean,
                "sequenceNum", (long) defs.size() + 1));
        return null;
    }

    public static Map<String, String> fields(GenericValue contact) {
        String json = contact.getString("customFields");
        if (UtilValidate.isEmpty(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return WaUtil.JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Merge values into the contact's custom fields (blank value removes the field). Does not store. */
    public static void putFields(GenericValue contact, Map<String, String> values) {
        Map<String, String> cur = fields(contact);
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.isEmpty()) {
                cur.remove(e.getKey());
            } else {
                cur.put(e.getKey(), v.length() > MAX_FIELD_VALUE ? v.substring(0, MAX_FIELD_VALUE) : v);
            }
        }
        try {
            contact.set("customFields", cur.isEmpty() ? null : WaUtil.JSON.writeValueAsString(cur));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Values usable as {{key}} placeholders: custom fields plus email. */
    public static Map<String, Object> placeholderValues(GenericValue contact) {
        Map<String, Object> out = new LinkedHashMap<>(fields(contact));
        if (UtilValidate.isNotEmpty(contact.getString("email"))) {
            out.put("email", contact.getString("email"));
        }
        return out;
    }

    // ------------------------------------------------------------------ consent
    /** Set opt-in (Y) or opt-out (N) with date and source. Does not store. */
    public static void setConsent(GenericValue contact, boolean optIn, String source) {
        Timestamp now = UtilDateTime.nowTimestamp();
        contact.set("optInStatus", optIn ? "Y" : "N");
        contact.set(optIn ? "optInDate" : "optOutDate", now);
        contact.set("optSource", source);
    }

    // ------------------------------------------------------------------ numbers
    /**
     * Clean a typed or imported number to WhatsApp's digits-only international form.
     * "+91 98123 45678" -> "919812345678"; "098123 45678" with default code 91 -> "919812345678".
     * @return null when it can't be a valid number
     */
    public static String cleanNumber(String raw, String defaultCountryCode) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        boolean plus = s.startsWith("+");
        String d = s.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) {
            d = d.substring(2);
            plus = true;
        }
        String cc = defaultCountryCode == null ? "" : defaultCountryCode.replaceAll("[^0-9]", "");
        if (!plus && !cc.isEmpty()) {
            String national = d.replaceFirst("^0+", "");
            // a national number (no country code): add the default one
            if (national.length() <= 10 && !(d.startsWith(cc) && d.length() > 10)) {
                d = cc + national;
            }
        }
        return d.length() >= 8 && d.length() <= 15 && !d.startsWith("0") ? d : null;
    }

    /** E.164 country calling codes (prefix-free, so the first match of a number is its country). */
    private static final Set<String> COUNTRY_CODES = Set.of(("1 7 20 27 30 31 32 33 34 36 39 40 41 43 44 45 46 47 48 49 51 52 53 54 55 56 57 58 "
            + "60 61 62 63 64 65 66 81 82 84 86 90 91 92 93 94 95 98 211 212 213 216 218 220 221 222 223 224 225 226 227 228 229 "
            + "230 231 232 233 234 235 236 237 238 239 240 241 242 243 244 245 246 248 249 250 251 252 253 254 255 256 257 258 "
            + "260 261 262 263 264 265 266 267 268 269 290 291 297 298 299 350 351 352 353 354 355 356 357 358 359 370 371 372 "
            + "373 374 375 376 377 378 380 381 382 383 385 386 387 389 420 421 423 500 501 502 503 504 505 506 507 508 509 590 "
            + "591 592 593 594 595 596 597 598 599 670 672 673 674 675 676 677 678 679 680 681 682 683 685 686 687 688 689 690 "
            + "691 692 850 852 853 855 856 880 886 960 961 962 963 964 965 966 967 968 970 971 972 973 974 975 976 977 992 993 "
            + "994 995 996 998").split(" "));

    /** Country calling code of an international number, e.g. "+91 98123 45678" -> "91"; "" if unknown. */
    public static String countryCodeOfNumber(String number) {
        String d = number == null ? "" : number.replaceAll("[^0-9]", "");
        for (int len = 1; len <= 3 && len < d.length(); len++) {
            if (COUNTRY_CODES.contains(d.substring(0, len))) {
                return d.substring(0, len);
            }
        }
        return "";
    }

    /** Default country code for numbers typed without one: the business's own WhatsApp number's country. */
    public static String countryCodeOf(GenericValue channel) {
        String cc = channel == null ? "" : countryCodeOfNumber(channel.getString("displayPhoneNumber"));
        return cc.isEmpty() ? WaUtil.prop("contacts.default.country.code", "").replaceAll("[^0-9]", "") : cc;
    }

    // ------------------------------------------------------------------ delete
    /** Remove a contact and everything stored about it. */
    public static void delete(Delegator delegator, String contactId) throws GenericEntityException {
        Map<String, Object> k = UtilMisc.toMap("contactId", contactId);
        delegator.removeByAnd("WaContactTag", k);
        delegator.removeByAnd("WaNote", k);
        delegator.removeByAnd("WaCampaignRecipient", k);
        delegator.removeByAnd("WaFlowRun", k);
        delegator.removeByAnd("WaMessage", k);
        delegator.removeByAnd("WaContact", k);
    }
}
