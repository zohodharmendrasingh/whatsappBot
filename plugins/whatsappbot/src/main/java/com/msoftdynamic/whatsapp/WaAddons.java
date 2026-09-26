package com.msoftdynamic.whatsapp;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Add-ons on top of a plan: extra messages for the current month, extra WhatsApp numbers and extra bot flows
 * for 30 days. Bought with PayPal on Plan &amp; Billing; limits = plan + active add-ons.
 */
public final class WaAddons {
    private static final String MODULE = WaAddons.class.getName();
    public static final List<String> TYPES = List.of("MESSAGES", "CHANNELS", "FLOWS");
    public static final int MAX_PACKS = 20;

    private WaAddons() { }

    /** Plan field that an add-on type raises. */
    public static String planField(String type) {
        switch (type) {
        case "CHANNELS":
            return "maxChannels";
        case "FLOWS":
            return "maxFlows";
        default:
            return "maxMessagesPerMonth";
        }
    }

    public static String typeForPlanField(String field) {
        return "maxChannels".equals(field) ? "CHANNELS" : "maxFlows".equals(field) ? "FLOWS" : "MESSAGES";
    }

    /** Extra units of a type from add-ons active right now. */
    public static long extra(Delegator delegator, String tenantId, String type) {
        Timestamp now = UtilDateTime.nowTimestamp();
        long sum = 0;
        try {
            for (GenericValue a : EntityQuery.use(delegator).from("WaTenantAddon").where(
                    EntityCondition.makeCondition("tenantId", tenantId), EntityCondition.makeCondition("addonType", type),
                    EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, now),
                    EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, now)).queryList()) {
                sum += a.get("quantity") == null ? 0 : a.getLong("quantity");
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }
        return sum;
    }

    /** Effective limit (plan + add-ons), or null when the plan is unlimited for this type. */
    public static Long limit(Delegator delegator, GenericValue plan, String tenantId, String type) {
        Long base = plan == null ? null : plan.getLong(planField(type));
        if (base == null || base <= 0) {
            return null;
        }
        return base + extra(delegator, tenantId, type);
    }

    /** Validity of a new add-on: messages until the end of this month, numbers and flows for 30 days. */
    public static Timestamp[] period(String type) {
        Timestamp now = UtilDateTime.nowTimestamp();
        Timestamp thru;
        if ("MESSAGES".equals(type)) {
            Calendar c = Calendar.getInstance();
            c.setTime(now);
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            c.add(Calendar.MONTH, 1);
            thru = new Timestamp(c.getTimeInMillis());
        } else {
            thru = UtilDateTime.adjustTimestamp(now, Calendar.DAY_OF_MONTH, 30);
        }
        return new Timestamp[] {now, thru};
    }

    /** Price of n packs. */
    public static BigDecimal priceFor(GenericValue addon, int packs) {
        BigDecimal p = addon.getBigDecimal("price") == null ? BigDecimal.ZERO : addon.getBigDecimal("price");
        return p.multiply(BigDecimal.valueOf(packs)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Record a paid add-on (called once the PayPal capture is complete). */
    static GenericValue activate(Delegator delegator, GenericValue pay) throws GenericEntityException {
        GenericValue addon = EntityQuery.use(delegator).from("WaAddon").where("addonId", pay.getString("addonId")).queryOne();
        if (addon == null) {
            throw new GenericEntityException("Add-on " + pay.getString("addonId") + " not found");
        }
        long packs = pay.get("packs") == null ? 1 : pay.getLong("packs");
        long qty = (addon.get("quantity") == null ? 0 : addon.getLong("quantity")) * packs;
        Timestamp[] per = period(addon.getString("addonType"));
        GenericValue ta = delegator.makeValue("WaTenantAddon", UtilMisc.toMap("tenantAddonId", delegator.getNextSeqId("WaTenantAddon"),
                "tenantId", pay.getString("tenantId"), "addonId", addon.getString("addonId"), "addonType", addon.getString("addonType"),
                "quantity", qty, "fromDate", per[0], "thruDate", per[1], "paymentId", pay.getString("paymentId"),
                "createdBy", pay.getString("createdByUserLogin"), "createdDate", UtilDateTime.nowTimestamp()));
        ta.create();
        pay.set("periodFromDate", per[0]);
        pay.set("periodThruDate", per[1]);
        pay.store();
        return ta;
    }

    /** Usage vs limits for the billing page and prompts: {type: {used, limit, extra, pct}}. */
    public static Map<String, Map<String, Object>> usage(Delegator delegator, String tenantId) throws GenericEntityException {
        GenericValue tenant = EntityQuery.use(delegator).from("WaTenant").where("tenantId", tenantId).queryOne();
        GenericValue plan = tenant == null ? null : tenant.getRelatedOne("WaPlan", false);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        GenericValue u = EntityQuery.use(delegator).from("WaUsage").where("tenantId", tenantId, "periodId", WaUtil.currentPeriod()).queryOne();
        long used;
        for (String t : TYPES) {
            switch (t) {
            case "MESSAGES":
                used = u == null || u.get("messagesOut") == null ? 0 : u.getLong("messagesOut");
                break;
            case "CHANNELS":
                used = WaUtil.countWhere(delegator, "WaChannel", "tenantId", tenantId);
                break;
            default:
                used = WaUtil.countWhere(delegator, "WaFlow", "tenantId", tenantId);
            }
            Long lim = limit(delegator, plan, tenantId, t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("used", used);
            m.put("limit", lim);
            m.put("extra", extra(delegator, tenantId, t));
            m.put("pct", lim == null || lim == 0 ? 0 : Math.min(100, Math.round(100.0 * used / lim)));
            m.put("full", lim != null && used >= lim);
            out.put(t, m);
        }
        return out;
    }

    /** Suggested catalog for a new installation (admin "Add suggested add-ons"). */
    public static void seed(Delegator delegator, String currency) throws GenericEntityException {
        Object[][] rows = {
            {"ADD_MSG_2K", "2,000 extra messages", "Extra messages for this calendar month", "MESSAGES", 2000L, "5.00", 1L},
            {"ADD_MSG_10K", "10,000 extra messages", "Extra messages for this calendar month", "MESSAGES", 10000L, "19.00", 2L},
            {"ADD_NUMBER", "Extra WhatsApp number", "Connect one more WhatsApp number for 30 days", "CHANNELS", 1L, "9.00", 3L},
            {"ADD_FLOWS_10", "10 extra bot flows", "Build 10 more bot flows for 30 days", "FLOWS", 10L, "5.00", 4L},
        };
        for (Object[] r : rows) {
            if (EntityQuery.use(delegator).from("WaAddon").where("addonId", r[0]).queryOne() == null) {
                delegator.create("WaAddon", UtilMisc.toMap("addonId", r[0], "addonName", r[1], "description", r[2], "addonType", r[3],
                        "quantity", r[4], "price", new BigDecimal((String) r[5]), "currencyUomId", currency, "isActive", "Y", "sequenceNum", r[6]));
            }
        }
    }

    // ------------------------------------------------------------------ admin (Plans & Pricing page)
    private static boolean isAdmin(javax.servlet.http.HttpServletRequest request) {
        GenericValue ul = (GenericValue) request.getSession().getAttribute("userLogin");
        org.apache.ofbiz.security.Security sec = (org.apache.ofbiz.security.Security) request.getAttribute("security");
        return ul != null && sec != null && sec.hasEntityPermission("WABOT", "_ADMIN", ul);
    }

    /** POST addonSave: addonId (new if empty), addonName, description, addonType, quantity, price, currencyUomId, isActive, sequenceNum */
    public static String addonSave(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        if (!isAdmin(request)) {
            request.setAttribute("_ERROR_MESSAGE_", "Platform admins only.");
            return "error";
        }
        try {
            String id = request.getParameter("addonId");
            String type = request.getParameter("addonType");
            String name = request.getParameter("addonName");
            if (!TYPES.contains(type) || name == null || name.isBlank()) {
                request.setAttribute("_ERROR_MESSAGE_", "Give the add-on a name and a type.");
                return "error";
            }
            long qty;
            BigDecimal price;
            try {
                qty = Long.parseLong(request.getParameter("quantity").trim());
                price = new BigDecimal(request.getParameter("price").trim());
            } catch (Exception e) {
                request.setAttribute("_ERROR_MESSAGE_", "Quantity and price must be numbers.");
                return "error";
            }
            if (qty < 1 || price.signum() <= 0) {
                request.setAttribute("_ERROR_MESSAGE_", "Quantity and price must be more than zero.");
                return "error";
            }
            GenericValue a = id == null || id.isBlank() ? null : EntityQuery.use(delegator).from("WaAddon").where("addonId", id.trim()).queryOne();
            if (a == null) {
                a = delegator.makeValue("WaAddon");
                a.set("addonId", id == null || id.isBlank() ? delegator.getNextSeqId("WaAddon") : id.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_"));
            }
            a.set("addonName", name.trim());
            a.set("description", request.getParameter("description"));
            a.set("addonType", type);
            a.set("quantity", qty);
            a.set("price", price.setScale(2, java.math.RoundingMode.HALF_UP));
            String cur = request.getParameter("currencyUomId");
            a.set("currencyUomId", cur == null || cur.isBlank() ? WaUtil.prop("paypal.currency", "USD") : cur.trim().toUpperCase(java.util.Locale.ROOT));
            a.set("isActive", "Y".equals(request.getParameter("isActive")) ? "Y" : "N");
            try {
                a.set("sequenceNum", Long.parseLong(String.valueOf(request.getParameter("sequenceNum")).trim()));
            } catch (NumberFormatException e) {
                a.set("sequenceNum", 10L);
            }
            delegator.createOrStore(a);
            request.setAttribute("_EVENT_MESSAGE_", "Add-on saved.");
            return "success";
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }
    }

    public static String addonSeed(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response) {
        if (!isAdmin(request)) {
            request.setAttribute("_ERROR_MESSAGE_", "Platform admins only.");
            return "error";
        }
        try {
            seed((Delegator) request.getAttribute("delegator"), WaUtil.prop("paypal.currency", "USD"));
            request.setAttribute("_EVENT_MESSAGE_", "Suggested add-ons added. Adjust the prices as you like.");
            return "success";
        } catch (GenericEntityException e) {
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }
    }
}
