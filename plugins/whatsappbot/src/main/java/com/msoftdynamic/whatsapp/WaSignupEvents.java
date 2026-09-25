package com.msoftdynamic.whatsapp;

import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.webapp.control.LoginWorker;

/**
 * Public self sign-up: creates a trial workspace (WaTenant), the owner's login and links them,
 * then signs the new owner in. Spam guards: hidden honeypot field + per-IP rate limit.
 */
public final class WaSignupEvents {

    private static final String MODULE = WaSignupEvents.class.getName();
    private static final Map<String, Deque<Long>> ATTEMPTS = new ConcurrentHashMap<>();
    private static final long HOUR = 3_600_000L;

    private WaSignupEvents() { }

    public static String signup(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");

        if (!"true".equalsIgnoreCase(WaUtil.prop("saas.signup.enabled", "true"))) {
            return error(request, "Sign-up is currently closed. Please contact us.");
        }
        if (UtilValidate.isNotEmpty(request.getParameter("website"))) {  // honeypot: bots fill every field
            Debug.logWarning("Signup honeypot triggered from " + request.getRemoteAddr(), MODULE);
            return error(request, "Could not create the account.");
        }
        if (!allow(request.getRemoteAddr())) {
            return error(request, "Too many sign-ups from your network. Please try again in an hour.");
        }

        String business = trim(request.getParameter("businessName"));
        String name = trim(request.getParameter("fullName"));
        String email = trim(request.getParameter("email")).toLowerCase(Locale.ROOT);
        String phone = trim(request.getParameter("phone"));
        String password = request.getParameter("password");
        String confirm = request.getParameter("confirmPassword");
        String planId = trim(request.getParameter("planId"));

        if (business.length() < 2 || business.length() > 100) {
            return error(request, "Please enter your business name.");
        }
        if (!UtilValidate.isEmail(email) || email.length() > 200) {
            return error(request, "Please enter a valid work email.");
        }
        if (password == null || password.length() < 8) {
            return error(request, "Password must be at least 8 characters.");
        }
        if (!password.equals(confirm)) {
            return error(request, "Passwords do not match.");
        }
        if (!"on".equals(request.getParameter("acceptTerms")) && !"Y".equals(request.getParameter("acceptTerms"))) {
            return error(request, "Please accept the terms of service.");
        }

        boolean began = false;
        try {
            if (EntityQuery.use(delegator).from("UserLogin").where("userLoginId", email).queryOne() != null) {
                return error(request, "An account with this email already exists. Please sign in.");
            }
            GenericValue plan = UtilValidate.isEmpty(planId) || isHiddenPlan(planId) ? null
                    : EntityQuery.use(delegator).from("WaPlan").where("planId", planId, "isActive", "Y").queryOne();
            if (plan == null) {
                plan = EntityQuery.use(delegator).from("WaPlan")
                        .where("planId", WaUtil.prop("saas.signup.default.plan", "WA_STARTER")).queryOne();
            }
            if (plan == null) {
                return error(request, "No plan is available for sign-up. Please contact us.");
            }
            GenericValue system = EntityQuery.use(delegator).from("UserLogin").where("userLoginId", "system").cache().queryOne();
            int trialDays = WaUtil.propInt("saas.trial.days", 14);
            Timestamp now = UtilDateTime.nowTimestamp();

            began = TransactionUtil.begin();
            String tenantId = delegator.getNextSeqId("WaTenant");
            GenericValue tenant = delegator.makeValue("WaTenant");
            tenant.set("tenantId", tenantId);
            tenant.set("tenantName", business);
            tenant.set("planId", plan.getString("planId"));
            tenant.set("statusId", "WA_TNT_TRIAL");
            tenant.set("contactEmail", email);
            tenant.set("contactPhone", phone);
            tenant.set("subscriptionThruDate", UtilDateTime.addDaysToTimestamp(now, trialDays));
            tenant.set("createdDate", now);
            tenant.create();

            Map<String, Object> r = dispatcher.runSync("createUserLogin", UtilMisc.toMap(
                    "userLoginId", email, "currentPassword", password, "currentPasswordVerify", password,
                    "requirePasswordChange", "N", "userLogin", system, "locale", request.getLocale()));
            if (ServiceUtil.isError(r)) {
                TransactionUtil.rollback(began, "signup failed", null);
                began = false;
                return error(request, ServiceUtil.getErrorMessage(r));
            }
            delegator.create("UserLoginSecurityGroup", "userLoginId", email, "groupId", "WABOT_TENANT", "fromDate", now);
            delegator.create("WaTenantUser", "tenantId", tenantId, "userLoginId", email, "roleTypeId", "WA_OWNER", "fromDate", now);
            createStarterFlow(delegator, tenantId, business);
            TransactionUtil.commit(began);
            began = false;
            Debug.logInfo("New self-service tenant " + tenantId + " (" + business + ") owner " + email
                    + (UtilValidate.isNotEmpty(name) ? " / " + name : ""), MODULE);

            // sign the new owner in straight away
            request.setAttribute("USERNAME", email);
            request.setAttribute("PASSWORD", password);
            String login = LoginWorker.login(request, response);
            if (!"success".equals(login)) {
                request.setAttribute("_EVENT_MESSAGE_", "Your workspace is ready. Please sign in.");
                return "login";
            }
            request.getSession().setAttribute("waTenantId", tenantId);
            return "success";
        } catch (Exception e) {
            Debug.logError(e, "Signup failed", MODULE);
            try {
                if (began) {
                    TransactionUtil.rollback(began, "signup failed", e);
                }
            } catch (Exception ignore) {
                Debug.logError(ignore, MODULE);
            }
            return error(request, "Something went wrong while creating your account. Please try again.");
        }
    }

    /** Every new workspace starts with an editable welcome-menu bot. */
    static void createStarterFlow(Delegator delegator, String tenantId, String business) throws Exception {
        String flowId = delegator.getNextSeqId("WaFlow");
        delegator.create("WaFlow", "flowId", flowId, "tenantId", tenantId, "flowName", "Welcome menu",
                "triggerKeywords", "hi,hello,menu,start", "startNodeId", "WELCOME", "isDefault", "Y", "isActive", "Y");
        delegator.create("WaFlowNode", "flowId", flowId, "nodeId", "WELCOME", "nodeTypeId", "WA_NODE_BUTTONS", "sequenceNum", 1L,
                "headerText", business.length() > 60 ? business.substring(0, 60) : business, "footerText", "Reply MENU anytime",
                "messageText", "Hi {{name}}! Welcome to " + business + ". How can we help you today?");
        delegator.create("WaFlowNodeOption", "flowId", flowId, "nodeId", "WELCOME", "optionSeqId", "01",
                "optionLabel", "Products & prices", "matchKeywords", "1,products,price", "targetNodeId", "PRODUCTS");
        delegator.create("WaFlowNodeOption", "flowId", flowId, "nodeId", "WELCOME", "optionSeqId", "02",
                "optionLabel", "Business hours", "matchKeywords", "2,hours,timing,address", "targetNodeId", "HOURS");
        delegator.create("WaFlowNodeOption", "flowId", flowId, "nodeId", "WELCOME", "optionSeqId", "03",
                "optionLabel", "Talk to us", "matchKeywords", "3,agent,human,help", "targetNodeId", "HANDOFF");
        delegator.create("WaFlowNode", "flowId", flowId, "nodeId", "PRODUCTS", "nodeTypeId", "WA_NODE_END", "sequenceNum", 2L,
                "messageText", "Here is our catalogue: (add your link here). Reply MENU to go back.");
        delegator.create("WaFlowNode", "flowId", flowId, "nodeId", "HOURS", "nodeTypeId", "WA_NODE_END", "sequenceNum", 3L,
                "messageText", "We are open Monday to Saturday, 10 AM to 7 PM. Reply MENU to go back.");
        delegator.create("WaFlowNode", "flowId", flowId, "nodeId", "HANDOFF", "nodeTypeId", "WA_NODE_HANDOFF", "sequenceNum", 4L,
                "messageText", "Thanks! A member of our team will reply here shortly.");
    }

    private static synchronized boolean allow(String ip) {
        int max = WaUtil.propInt("saas.signup.max.per.hour", 5);
        long now = System.currentTimeMillis();
        Deque<Long> q = ATTEMPTS.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > HOUR) {
            q.pollFirst();
        }
        if (q.size() >= max) {
            return false;
        }
        q.addLast(now);
        if (ATTEMPTS.size() > 5000) {
            ATTEMPTS.clear();
        }
        return true;
    }

    private static String error(HttpServletRequest request, String msg) {
        request.setAttribute("_ERROR_MESSAGE_", msg);
        request.setAttribute("waSignupError", msg); // read by the standalone sign-up page
        return "error";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** Plans ids hidden from the public site and sign-up (e.g. the $1 test plan); still payable from Plan &amp; Billing. */
    public static boolean isHiddenPlan(String planId) {
        return planId != null && WaUtil.propList("saas.hidden.plans").contains(planId.toLowerCase(java.util.Locale.ROOT));
    }

    /** Active plans including hidden ones (Plan &amp; Billing page). */
    public static List<GenericValue> activePlans(Delegator delegator) {
        try {
            return EntityQuery.use(delegator).from("WaPlan").where("isActive", "Y").orderBy("monthlyPrice").cache().queryList();
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return List.of();
        }
    }

    /** Plans shown on the public pricing page and sign-up form. */
    public static List<GenericValue> publicPlans(Delegator delegator) {
        try {
            return activePlans(delegator).stream().filter(p -> !isHiddenPlan(p.getString("planId"))).collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return List.of();
        }
    }
}
