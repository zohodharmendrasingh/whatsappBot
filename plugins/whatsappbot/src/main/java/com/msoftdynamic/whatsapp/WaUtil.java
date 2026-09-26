package com.msoftdynamic.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/** Shared helpers: config, tenants, quota, usage, hashing, JSON. */
public final class WaUtil {

    public static final String MODULE = WaUtil.class.getName();
    public static final String CONFIG = "whatsappbot";
    public static final String LABELS = "WhatsAppBotUiLabels";
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private WaUtil() { }

    // ------------------------------------------------------------------ config
    /**
     * Settings come from config/whatsappbot.properties (committed defaults, no secrets), overridden by the
     * server-only file /etc/flochat/flochat.properties (path can be changed with env FLOCHAT_CONFIG or
     * -Dflochat.config). Secrets such as meta.app.secret and paypal.client.secret belong in that file.
     * The override file is re-read automatically when it changes.
     */
    public static String prop(String name, String def) {
        String v = overrides().getProperty(name);
        if (v != null && !v.trim().isEmpty()) {
            return v.trim();
        }
        return UtilProperties.getPropertyValue(CONFIG, name, def);
    }

    private static volatile java.util.Properties overrideProps = new java.util.Properties();
    private static volatile long overrideStamp = -2L;
    private static volatile long overrideCheckedAt = 0L;

    private static java.util.Properties overrides() {
        long now = System.currentTimeMillis();
        if (now - overrideCheckedAt < 5000L) {
            return overrideProps;
        }
        overrideCheckedAt = now;
        String path = System.getProperty("flochat.config");
        if (path == null || path.isEmpty()) {
            path = System.getenv("FLOCHAT_CONFIG");
        }
        if (path == null || path.isEmpty()) {
            path = "/etc/flochat/flochat.properties";
        }
        java.io.File file = new java.io.File(path);
        long stamp = file.isFile() ? file.lastModified() : -1L;
        if (stamp != overrideStamp) {
            java.util.Properties p = new java.util.Properties();
            if (stamp > 0) {
                try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                    p.load(r);
                    Debug.logInfo("Loaded " + p.size() + " FloChat settings from " + path, MODULE);
                } catch (java.io.IOException e) {
                    Debug.logError(e, "Cannot read " + path, MODULE);
                }
            }
            overrideProps = p;
            overrideStamp = stamp;
        }
        return overrideProps;
    }

    public static boolean propTrue(String name) {
        return "true".equalsIgnoreCase(prop(name, "false"));
    }

    public static int propInt(String name, int def) {
        try {
            return Integer.parseInt(prop(name, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static List<String> propList(String name) {
        return splitCsv(prop(name, ""));
    }

    public static String label(String key, Locale locale) {
        return UtilProperties.getMessage(LABELS, key, locale == null ? Locale.getDefault() : locale);
    }

    public static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        if (UtilValidate.isEmpty(s)) {
            return out;
        }
        for (String part : s.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tenants
    public static List<String> getUserTenantIds(Delegator delegator, String userLoginId) {
        List<String> ids = new ArrayList<>();
        if (UtilValidate.isEmpty(userLoginId)) {
            return ids;
        }
        try {
            for (GenericValue tu : EntityQuery.use(delegator).from("WaTenantUser")
                    .where("userLoginId", userLoginId).orderBy("tenantId").cache().queryList()) {
                ids.add(tu.getString("tenantId"));
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, "Cannot read tenants of " + userLoginId, MODULE);
        }
        return ids;
    }

    public static String getTenantRole(Delegator delegator, String tenantId, String userLoginId) {
        try {
            GenericValue tu = EntityQuery.use(delegator).from("WaTenantUser")
                    .where("tenantId", tenantId, "userLoginId", userLoginId).cache().queryOne();
            return tu == null ? null : tu.getString("roleTypeId");
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return null;
        }
    }

    /** Resolve the tenant owning a record, or null. */
    public static String tenantOf(Delegator delegator, String entityName, String pkField, String pkValue) {
        if (UtilValidate.isEmpty(pkValue)) {
            return null;
        }
        try {
            GenericValue gv = EntityQuery.use(delegator).from(entityName).where(pkField, pkValue).queryOne();
            return gv == null ? null : gv.getString("tenantId");
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return null;
        }
    }

    /** Returns null if the tenant may send messages, otherwise the reason. */
    public static String checkCanSend(Delegator delegator, String tenantId, Locale locale) throws GenericEntityException {
        GenericValue tenant = EntityQuery.use(delegator).from("WaTenant").where("tenantId", tenantId).cache().queryOne();
        if (tenant == null) {
            return "Unknown tenant " + tenantId;
        }
        String status = tenant.getString("statusId");
        Timestamp thru = tenant.getTimestamp("subscriptionThruDate");
        boolean statusOk = "WA_TNT_ACTIVE".equals(status) || "WA_TNT_TRIAL".equals(status);
        if (!statusOk || (thru != null && thru.before(UtilDateTime.nowTimestamp()))) {
            return label("WaTenantInactive", locale);
        }
        GenericValue plan = tenant.getRelatedOne("WaPlan", true);
        Long max = plan == null ? null : plan.getLong("maxMessagesPerMonth");
        if (max != null && max > 0) {
            GenericValue usage = EntityQuery.use(delegator).from("WaUsage")
                    .where("tenantId", tenantId, "periodId", currentPeriod()).queryOne();
            long used = usage == null || usage.getLong("messagesOut") == null ? 0 : usage.getLong("messagesOut");
            if (used >= max) {
                return label("WaQuotaExceeded", locale);
            }
        }
        return null;
    }

    public static String currentPeriod() {
        return new SimpleDateFormat("yyyyMM").format(UtilDateTime.nowTimestamp());
    }

    public static synchronized void incrementUsage(Delegator delegator, String tenantId, boolean outbound) {
        try {
            String period = currentPeriod();
            GenericValue usage = EntityQuery.use(delegator).from("WaUsage")
                    .where("tenantId", tenantId, "periodId", period).queryOne();
            String field = outbound ? "messagesOut" : "messagesIn";
            if (usage == null) {
                usage = delegator.makeValue("WaUsage", Map.of("tenantId", tenantId, "periodId", period,
                        "messagesIn", 0L, "messagesOut", 0L));
                usage.set(field, 1L);
                usage.create();
            } else {
                Long v = usage.getLong(field);
                usage.set(field, (v == null ? 0L : v) + 1L);
                usage.store();
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, "Usage update failed for " + tenantId, MODULE);
        }
    }

    public static long countWhere(Delegator delegator, String entity, String field, String value) throws GenericEntityException {
        return EntityQuery.use(delegator).from(entity).where(field, value).queryCount();
    }

    // ------------------------------------------------------------------ JSON session vars
    public static Map<String, Object> readVars(GenericValue contact) {
        String json = contact.getString("sessionData");
        if (UtilValidate.isEmpty(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public static void writeVars(GenericValue contact, Map<String, Object> vars) {
        try {
            contact.set("sessionData", vars == null || vars.isEmpty() ? null : JSON.writeValueAsString(vars));
        } catch (Exception e) {
            Debug.logWarning(e, MODULE);
        }
    }

    /** Replace {{name}}, {{phone}} and {{anyVariable}} placeholders. */
    /** Split "a|{{name|friend}}|c" on the | separators that are not inside {{ }}. */
    public static List<String> splitParams(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' && i + 1 < s.length() && s.charAt(i + 1) == '{') {
                depth++;
                cur.append("{{");
                i++;
            } else if (c == '}' && i + 1 < s.length() && s.charAt(i + 1) == '}' && depth > 0) {
                depth--;
                cur.append("}}");
                i++;
            } else if (c == '|' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*(?:\\|([^{}]*))?}}");

    /**
     * Fill {{key}} placeholders from flow variables, the contact (name, phone, whatsapp, email) and its custom fields.
     * {{key|fallback}} uses the fallback when the value is missing. Single pass, so values are never re-expanded.
     */
    public static String render(String text, GenericValue contact, Map<String, Object> vars) {
        if (text == null || !text.contains("{{")) {
            return text;
        }
        Map<String, Object> all = new LinkedHashMap<>(vars == null ? Collections.emptyMap() : vars);
        String name = contact.getString("profileName");
        if (UtilValidate.isNotEmpty(name)) {
            all.putIfAbsent("name", name);
        }
        all.putIfAbsent("phone", contact.getString("waId"));
        all.putIfAbsent("whatsapp", "+" + contact.getString("waId"));
        for (Map.Entry<String, Object> e : WaContacts.placeholderValues(contact).entrySet()) {
            all.putIfAbsent(e.getKey(), e.getValue());
        }
        java.util.regex.Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = all.get(m.group(1));
            if (v == null) {
                v = all.get(m.group(1).toLowerCase(Locale.ROOT));
            }
            String val = v == null ? "" : String.valueOf(v).trim();
            if (val.isEmpty()) {
                val = m.group(2) != null ? m.group(2).trim() : ("name".equalsIgnoreCase(m.group(1)) ? "there" : "");
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------ crypto
    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static String newApiKey() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return "wab_" + HexFormat.of().formatHex(b);
    }

    /** Keep digits only: "+91 98765-43210" becomes "919876543210". */
    public static String normalizeNumber(String n) {
        return n == null ? null : n.replaceAll("[^0-9]", "");
    }
}
