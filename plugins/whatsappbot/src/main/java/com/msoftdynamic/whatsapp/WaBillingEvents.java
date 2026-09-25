package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Plan payments with PayPal Checkout (Orders v2).
 * Browser: PayPal JS SDK buttons call paypalCreateOrder, the buyer approves in PayPal, then paypalCaptureOrder.
 * The server always computes the price from WaPlan, captures the order itself and checks the captured amount
 * before it activates the workspace and extends subscriptionThruDate. Captures are idempotent per order.
 */
public final class WaBillingEvents {

    private static final String MODULE = WaBillingEvents.class.getName();
    private static final Object CAPTURE_LOCK = new Object();

    private WaBillingEvents() { }

    /** Price for a billing period: monthly price x months, yearly = monthly x paypal.yearly.months.charged (default 10). */
    public static BigDecimal priceFor(GenericValue plan, int months) {
        BigDecimal monthly = plan.getBigDecimal("monthlyPrice");
        if (monthly == null) {
            monthly = BigDecimal.ZERO;
        }
        int charged = months == 12 ? WaUtil.propInt("paypal.yearly.months.charged", 10) : months;
        return monthly.multiply(BigDecimal.valueOf(charged)).setScale(2, RoundingMode.HALF_UP);
    }

    public static String createOrder(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        ObjectNode out = WaUtil.JSON.createObjectNode();
        try {
            String tenantId = ownerTenant(request, delegator);
            if (tenantId == null) {
                return json(response, 403, out.put("error", "Only the workspace owner can pay for the plan."));
            }
            if (!PayPalClient.isConfigured()) {
                return json(response, 503, out.put("error", "Online payment is not configured yet. Please contact support."));
            }
            String planId = request.getParameter("planId");
            int months = "12".equals(request.getParameter("months")) ? 12 : 1;
            GenericValue plan = UtilValidate.isEmpty(planId) ? null
                    : EntityQuery.use(delegator).from("WaPlan").where("planId", planId).queryOne();
            if (plan == null || !"Y".equals(plan.getString("isActive"))) {
                return json(response, 400, out.put("error", "Please choose a plan."));
            }
            BigDecimal amount = priceFor(plan, months);
            if (amount.signum() <= 0) {
                return json(response, 400, out.put("error", "This plan has no price. Please contact support."));
            }
            String currency = UtilValidate.isNotEmpty(plan.getString("currencyUomId")) ? plan.getString("currencyUomId")
                    : WaUtil.prop("paypal.currency", "USD");
            GenericValue tenant = EntityQuery.use(delegator).from("WaTenant").where("tenantId", tenantId).queryOne();
            String brand = WaUtil.prop("brand.name", "FloChat");
            String paymentId = delegator.getNextSeqId("WaPayment");

            ObjectNode order = WaUtil.JSON.createObjectNode();
            order.put("intent", "CAPTURE");
            ObjectNode pu = order.putArray("purchase_units").addObject();
            pu.put("reference_id", paymentId);
            pu.put("custom_id", tenantId);
            pu.put("invoice_id", "FC-" + paymentId);
            pu.put("description", cut(brand + " " + plan.getString("planName") + " plan - "
                    + (months == 12 ? "12 months" : "1 month") + " - " + tenant.getString("tenantName"), 127));
            pu.putObject("amount").put("currency_code", currency).put("value", amount.toPlainString());
            ObjectNode ctx = order.putObject("payment_source").putObject("paypal").putObject("experience_context");
            ctx.put("brand_name", cut(brand, 127));
            ctx.put("shipping_preference", "NO_SHIPPING");
            ctx.put("user_action", "PAY_NOW");

            PayPalClient.Result r = PayPalClient.createOrder(order, "fc-order-" + paymentId);
            if (!r.isOk() || r.body == null || !r.body.hasNonNull("id")) {
                return json(response, 502, out.put("error", "PayPal: " + r.error()));
            }
            String orderId = r.body.get("id").asText();
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue pay = delegator.makeValue("WaPayment");
            pay.set("paymentId", paymentId);
            pay.set("tenantId", tenantId);
            pay.set("planId", planId);
            pay.set("months", (long) months);
            pay.set("amount", amount);
            pay.set("currencyUomId", currency);
            pay.set("provider", "PAYPAL");
            pay.set("providerOrderId", orderId);
            pay.set("statusId", "CREATED");
            pay.set("createdByUserLogin", userLoginId(request));
            pay.set("createdDate", now);
            pay.create();
            return json(response, 200, out.put("orderId", orderId));
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return json(response, 500, out.put("error", "Could not start the payment."));
        }
    }

    public static String captureOrder(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        ObjectNode out = WaUtil.JSON.createObjectNode();
        String orderId = request.getParameter("orderId");
        try {
            String tenantId = ownerTenant(request, delegator);
            if (tenantId == null) {
                return json(response, 403, out.put("error", "Only the workspace owner can pay for the plan."));
            }
            if (UtilValidate.isEmpty(orderId) || !orderId.matches("[A-Za-z0-9-]{5,64}")) {
                return json(response, 400, out.put("error", "Missing order."));
            }
            synchronized (CAPTURE_LOCK) {
                GenericValue pay = EntityQuery.use(delegator).from("WaPayment")
                        .where("providerOrderId", orderId, "provider", "PAYPAL").queryFirst();
                if (pay == null || !tenantId.equals(pay.getString("tenantId"))) {
                    return json(response, 404, out.put("error", "Unknown payment."));
                }
                if ("COMPLETED".equals(pay.getString("statusId"))) {
                    return json(response, 200, done(out, delegator, tenantId));
                }
                PayPalClient.Result r = PayPalClient.captureOrder(orderId, "fc-capture-" + pay.getString("paymentId"));
                JsonNode order = r.body;
                if (!r.isOk() && "ORDER_ALREADY_CAPTURED".equals(r.issue())) {
                    PayPalClient.Result g = PayPalClient.getOrder(orderId);
                    order = g.isOk() ? g.body : null;
                } else if (!r.isOk()) {
                    if ("INSTRUMENT_DECLINED".equals(r.issue())) {
                        // buyer can pick another funding source in the PayPal window
                        return json(response, 402, out.put("error", "Your payment method was declined. Please try another one.")
                                .put("restart", true));
                    }
                    pay.set("statusId", "FAILED");
                    pay.set("failureReason", cut(r.error(), 250));
                    pay.store();
                    return json(response, 502, out.put("error", "PayPal: " + r.error()));
                }
                JsonNode cap = order == null ? null : order.path("purchase_units").path(0).path("payments").path("captures").path(0);
                String capStatus = cap == null ? "" : cap.path("status").asText("");
                String capCurrency = cap == null ? "" : cap.path("amount").path("currency_code").asText("");
                BigDecimal capValue;
                try {
                    capValue = new BigDecimal(cap == null ? "0" : cap.path("amount").path("value").asText("0"));
                } catch (NumberFormatException nfe) {
                    capValue = BigDecimal.ZERO;
                }
                if (order == null || !"COMPLETED".equals(order.path("status").asText())) {
                    return json(response, 502, out.put("error", "PayPal did not complete the payment."));
                }
                if (!pay.getString("currencyUomId").equals(capCurrency)
                        || capValue.compareTo(pay.getBigDecimal("amount")) != 0) {
                    pay.set("statusId", "FAILED");
                    pay.set("failureReason", "Amount mismatch: " + capValue + " " + capCurrency);
                    pay.set("providerCaptureId", cap.path("id").asText(null));
                    pay.store();
                    Debug.logError("PayPal amount mismatch for order " + orderId + ": " + capValue + " " + capCurrency, MODULE);
                    return json(response, 400, out.put("error", "Payment amount did not match. Please contact support."));
                }
                pay.set("providerCaptureId", cap.path("id").asText(null));
                pay.set("captureStatus", capStatus);
                pay.set("payerEmail", cut(order.path("payer").path("email_address").asText(null), 250));
                if (!"COMPLETED".equals(capStatus)) {
                    // e.g. PENDING (eCheck / review): record it but do not extend yet
                    pay.set("statusId", "PENDING");
                    pay.store();
                    return json(response, 202, out.put("pending", true)
                            .put("message", "PayPal is still processing this payment. Your plan will be updated once it clears."));
                }
                Timestamp now = UtilDateTime.nowTimestamp();
                pay.set("statusId", "COMPLETED");
                pay.set("completedDate", now);
                pay.store();
                applyPayment(delegator, pay, now);
                return json(response, 200, done(out, delegator, tenantId));
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return json(response, 500, out.put("error", "Payment captured but the plan could not be updated. Please contact support with order " + orderId + "."));
        }
    }

    /** Switch the tenant to the paid plan, activate it and push the paid-until date by the purchased months. */
    static void applyPayment(Delegator delegator, GenericValue pay, Timestamp now) throws GenericEntityException {
        GenericValue tenant = EntityQuery.use(delegator).from("WaTenant").where("tenantId", pay.getString("tenantId")).queryOne();
        Timestamp from = tenant.getTimestamp("subscriptionThruDate");
        if (from == null || from.before(now)) {
            from = now;
        }
        int months = pay.getLong("months") == null ? 1 : pay.getLong("months").intValue();
        Timestamp thru = UtilDateTime.adjustTimestamp(from, java.util.Calendar.MONTH, months);
        tenant.set("planId", pay.getString("planId"));
        tenant.set("statusId", "WA_TNT_ACTIVE");
        tenant.set("subscriptionThruDate", thru);
        tenant.store();
        pay.set("periodFromDate", from);
        pay.set("periodThruDate", thru);
        pay.store();
    }

    private static ObjectNode done(ObjectNode out, Delegator delegator, String tenantId) throws GenericEntityException {
        GenericValue t = EntityQuery.use(delegator).from("WaTenant").where("tenantId", tenantId).queryOne();
        out.put("ok", true);
        if (t != null && t.getTimestamp("subscriptionThruDate") != null) {
            out.put("paidUntil", new java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).format(t.getTimestamp("subscriptionThruDate")));
        }
        return out;
    }

    /** The tenant the logged-in user is working on, only if the user is its owner. */
    private static String ownerTenant(HttpServletRequest request, Delegator delegator) {
        String login = userLoginId(request);
        if (login == null) {
            return null;
        }
        List<String> mine = WaUtil.getUserTenantIds(delegator, login);
        Object cur = request.getSession().getAttribute("waTenantId");
        String tenantId = cur != null && mine.contains(cur.toString()) ? cur.toString() : (mine.isEmpty() ? null : mine.get(0));
        if (tenantId == null || !"WA_OWNER".equals(WaUtil.getTenantRole(delegator, tenantId, login))) {
            return null;
        }
        return tenantId;
    }

    private static String userLoginId(HttpServletRequest request) {
        GenericValue ul = (GenericValue) request.getSession().getAttribute("userLogin");
        return ul == null ? null : ul.getString("userLoginId");
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String json(HttpServletResponse response, int status, ObjectNode body) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(body.toString());
            response.getWriter().flush();
        } catch (IOException e) {
            Debug.logError(e, MODULE);
        }
        return "none";
    }
}
