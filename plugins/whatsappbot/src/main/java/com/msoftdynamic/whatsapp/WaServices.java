package com.msoftdynamic.whatsapp;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.apache.ofbiz.security.Security;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/** Service implementations declared in servicedef/services.xml. */
public final class WaServices {

    private static final String MODULE = WaServices.class.getName();

    private WaServices() { }

    // =================================================================== security
    public static Map<String, Object> tenantPermissionCheck(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Security security = dctx.getSecurity();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String mainAction = (String) context.get("mainAction");
        Locale locale = (Locale) context.get("locale");
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("hasPermission", Boolean.FALSE);

        if (userLogin == null) {
            result.put("failMessage", "Login required");
            return result;
        }
        if (security.hasEntityPermission("WABOT", "_ADMIN", userLogin)) {
            result.put("hasPermission", Boolean.TRUE);
            return result;
        }
        String action = UtilValidate.isEmpty(mainAction) ? "VIEW" : mainAction;
        if ("ADMIN".equals(action) || !security.hasEntityPermission("WABOT", "_" + action, userLogin)) {
            result.put("failMessage", WaUtil.label("WaPermissionError", locale));
            return result;
        }
        Set<String> tenants = new LinkedHashSet<>();
        addIfNotNull(tenants, (String) context.get("tenantId"));
        addIfNotNull(tenants, WaUtil.tenantOf(delegator, "WaChannel", "channelId", (String) context.get("channelId")));
        addIfNotNull(tenants, WaUtil.tenantOf(delegator, "WaFlow", "flowId", (String) context.get("flowId")));
        addIfNotNull(tenants, WaUtil.tenantOf(delegator, "WaContact", "contactId", (String) context.get("contactId")));
        addIfNotNull(tenants, WaUtil.tenantOf(delegator, "WaTemplate", "templateId", (String) context.get("templateId")));
        addIfNotNull(tenants, WaUtil.tenantOf(delegator, "WaApiKey", "apiKeyId", (String) context.get("apiKeyId")));
        List<String> mine = WaUtil.getUserTenantIds(delegator, userLogin.getString("userLoginId"));
        if (tenants.isEmpty() || !mine.containsAll(tenants)) {
            result.put("failMessage", WaUtil.label("WaPermissionError", locale));
            return result;
        }
        // user management and API keys: tenant owners only (agents can chat and build flows)
        if ("OWNER".equals(context.get("resourceDescription"))) {
            for (String t : tenants) {
                if (!"WA_OWNER".equals(WaUtil.getTenantRole(delegator, t, userLogin.getString("userLoginId")))) {
                    result.put("failMessage", "Only the tenant owner can do this");
                    return result;
                }
            }
        }
        result.put("hasPermission", Boolean.TRUE);
        return result;
    }

    private static void addIfNotNull(Set<String> set, String v) {
        if (UtilValidate.isNotEmpty(v)) {
            set.add(v);
        }
    }

    // =================================================================== tenants
    public static Map<String, Object> createTenantUser(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        String tenantId = (String) context.get("tenantId");
        String userLoginId = (String) context.get("userLoginId");
        String password = (String) context.get("password");
        try {
            GenericValue existing = EntityQuery.use(delegator).from("UserLogin").where("userLoginId", userLoginId).queryOne();
            List<String> userTenants = WaUtil.getUserTenantIds(delegator, userLoginId);
            boolean isAdmin = dctx.getSecurity().hasEntityPermission("WABOT", "_ADMIN", (GenericValue) context.get("userLogin"));
            if (existing != null && userTenants.contains(tenantId)) {
                return ServiceUtil.returnSuccess("User " + userLoginId + " already belongs to tenant " + tenantId);
            } else if (existing != null && !userTenants.isEmpty()) {
                return ServiceUtil.returnError("Login " + userLoginId + " already belongs to another tenant");
            } else if (existing != null && !isAdmin) {
                return ServiceUtil.returnError("Login " + userLoginId + " already exists, choose another");
            } else if (existing == null) {
                Map<String, Object> r = dispatcher.runSync("createUserLogin", UtilMisc.toMap(
                        "userLoginId", userLoginId, "currentPassword", password, "currentPasswordVerify", password,
                        "requirePasswordChange", "N", "userLogin", context.get("userLogin"), "locale", context.get("locale")));
                if (ServiceUtil.isError(r)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(r));
                }
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            if (EntityQuery.use(delegator).from("UserLoginSecurityGroup")
                    .where("userLoginId", userLoginId, "groupId", "WABOT_TENANT").filterByDate().queryFirst() == null) {
                delegator.create("UserLoginSecurityGroup", "userLoginId", userLoginId, "groupId", "WABOT_TENANT", "fromDate", now);
            }
            delegator.createOrStore(delegator.makeValue("WaTenantUser", UtilMisc.toMap("tenantId", tenantId,
                    "userLoginId", userLoginId, "roleTypeId", context.get("roleTypeId"), "fromDate", now)));
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess("User " + userLoginId + " added to tenant " + tenantId);
    }

    // =================================================================== channels
    public static Map<String, Object> createChannel(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String tenantId = (String) context.get("tenantId");
        try {
            String limitErr = checkPlanLimit(delegator, tenantId, "maxChannels", "WaChannel");
            if (limitErr != null) {
                return ServiceUtil.returnError(limitErr);
            }
            String err = checkFlowOwner(delegator, tenantId, (String) context.get("defaultFlowId"));
            if (err == null) {
                err = verifyNumberOwnership((String) context.get("phoneNumberId"), (String) context.get("wabaId"),
                        ((String) context.get("newAccessToken")).trim(), (String) context.get("graphApiVersion"));
            }
            if (err != null) {
                return ServiceUtil.returnError(err);
            }
            GenericValue ch = delegator.makeValue("WaChannel");
            ch.setNonPKFields(context);
            String channelId = delegator.getNextSeqId("WaChannel");
            ch.set("channelId", channelId);
            ch.set("accessToken", ((String) context.get("newAccessToken")).trim());
            if (UtilValidate.isEmpty(ch.getString("isActive"))) {
                ch.set("isActive", "Y");
            }
            ch.set("createdDate", UtilDateTime.nowTimestamp());
            ch.create();
            String subErr = subscribeWaba(ch);
            Map<String, Object> result = subErr == null
                    ? ServiceUtil.returnSuccess("Number saved and subscribed to incoming WhatsApp messages")
                    : ServiceUtil.returnSuccess("Number saved, but " + subErr);
            result.put("channelId", channelId);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> updateChannel(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            GenericValue ch = EntityQuery.use(delegator).from("WaChannel").where("channelId", context.get("channelId")).queryOne();
            if (ch == null) {
                return ServiceUtil.returnError("Channel not found");
            }
            String tenantId = ch.getString("tenantId");
            String token = ch.getString("accessToken");
            String oldPnid = ch.getString("phoneNumberId");
            String oldWaba = ch.getString("wabaId");
            ch.setNonPKFields(context);
            ch.set("tenantId", tenantId);
            String newToken = (String) context.get("newAccessToken");
            ch.set("accessToken", UtilValidate.isNotEmpty(newToken) ? newToken.trim() : token);
            String err = checkFlowOwner(delegator, tenantId, ch.getString("defaultFlowId"));
            boolean idsChanged = !java.util.Objects.equals(oldPnid, ch.getString("phoneNumberId"))
                    || !java.util.Objects.equals(oldWaba, ch.getString("wabaId")) || UtilValidate.isNotEmpty(newToken);
            if (err == null && idsChanged) {
                err = verifyNumberOwnership(ch.getString("phoneNumberId"), ch.getString("wabaId"),
                        ch.getString("accessToken"), ch.getString("graphApiVersion"));
            }
            if (err != null) {
                return ServiceUtil.returnError(err);
            }
            ch.store();
            if (idsChanged) {
                String subErr = subscribeWaba(ch);
                return subErr == null
                        ? ServiceUtil.returnSuccess("Number saved and subscribed to incoming WhatsApp messages")
                        : ServiceUtil.returnSuccess("Number saved, but " + subErr);
            }
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    /**
     * Subscribe FloChat's Meta app to the number's WhatsApp Business Account, so Meta forwards incoming
     * messages and delivery statuses to our webhook. Embedded Signup does this too; manual numbers need it here.
     * Returns null on success, otherwise a short reason.
     */
    static String subscribeWaba(GenericValue ch) {
        if (UtilValidate.isEmpty(ch.getString("wabaId")) || UtilValidate.isEmpty(ch.getString("accessToken"))) {
            return "no WhatsApp Business Account id/token to subscribe incoming messages";
        }
        GraphApiClient.Result sub = GraphApiClient.post(GraphApiClient.baseUrl(ch) + "/" + ch.getString("wabaId")
                + "/subscribed_apps", ch.getString("accessToken"), null);
        return sub.isOk() ? null : "incoming messages could not be subscribed: " + sub.errorMessage();
    }

    /** A channel's default flow must belong to the same tenant. */
    private static String checkFlowOwner(Delegator delegator, String tenantId, String flowId) throws GenericEntityException {
        if (UtilValidate.isEmpty(flowId)) {
            return null;
        }
        GenericValue f = EntityQuery.use(delegator).from("WaFlow").where("flowId", flowId).queryOne();
        return f != null && tenantId.equals(f.getString("tenantId")) ? null : "Default flow " + flowId + " not found";
    }

    /**
     * Prove the tenant controls the number: the token must be able to read the phone number
     * and the number must belong to the given WhatsApp Business Account.
     */
    static String verifyNumberOwnership(String phoneNumberId, String wabaId, String token, String version) {
        if (UtilValidate.isEmpty(wabaId)) {
            return "WhatsApp Business Account id is required";
        }
        String base = GraphApiClient.baseUrl(version);
        GraphApiClient.Result r = GraphApiClient.get(base + "/" + wabaId + "/phone_numbers", token, Map.of("fields", "id", "limit", "200"));
        if (!r.isOk()) {
            return "Could not verify the number with Meta using this token: " + r.errorMessage();
        }
        for (JsonNode n : r.getBody().path("data")) {
            if (phoneNumberId.equals(n.path("id").asText())) {
                return null;
            }
        }
        return "Phone number id " + phoneNumberId + " does not belong to WABA " + wabaId;
    }

    private static String checkPlanLimit(Delegator delegator, String tenantId, String planField, String entity)
            throws GenericEntityException {
        GenericValue tenant = EntityQuery.use(delegator).from("WaTenant").where("tenantId", tenantId).queryOne();
        if (tenant == null) {
            return "Unknown tenant " + tenantId;
        }
        GenericValue plan = tenant.getRelatedOne("WaPlan", false);
        Long max = plan == null ? null : plan.getLong(planField);
        if (max != null && max > 0 && WaUtil.countWhere(delegator, entity, "tenantId", tenantId) >= max) {
            return "Plan " + plan.getString("planName") + " allows only " + max + " (" + planField + "). Please upgrade.";
        }
        return null;
    }

    public static Map<String, Object> completeEmbeddedSignup(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String tenantId = (String) context.get("tenantId");
        String wabaId = (String) context.get("wabaId");
        String phoneNumberId = (String) context.get("phoneNumberId");
        String appId = WaUtil.prop("meta.app.id", "");
        String appSecret = WaUtil.prop("meta.app.secret", "");
        if (appId.isEmpty() || appSecret.isEmpty()) {
            return ServiceUtil.returnError("Set meta.app.id and meta.app.secret in whatsappbot.properties");
        }
        String base = GraphApiClient.baseUrl((String) null);

        // 1. exchange the code for a business integration system user token
        GraphApiClient.Result tok = GraphApiClient.get(base + "/oauth/access_token", null,
                Map.of("client_id", appId, "client_secret", appSecret, "code", (String) context.get("code")));
        if (!tok.isOk() || !tok.getBody().has("access_token")) {
            return ServiceUtil.returnError("Token exchange failed: " + tok.errorMessage());
        }
        String token = tok.getBody().get("access_token").asText();

        try {
            GenericValue existing = EntityQuery.use(delegator).from("WaChannel").where("phoneNumberId", phoneNumberId).queryFirst();
            if (existing != null && !tenantId.equals(existing.getString("tenantId"))) {
                return ServiceUtil.returnError("This number is already connected to another tenant");
            }
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        String ownErr = verifyNumberOwnership(phoneNumberId, wabaId, token, null);
        if (ownErr != null) {
            return ServiceUtil.returnError(ownErr);
        }
        // 2. subscribe our app to the customer's WABA webhooks
        GraphApiClient.Result sub = GraphApiClient.post(base + "/" + wabaId + "/subscribed_apps", token, null);
        if (!sub.isOk()) {
            return ServiceUtil.returnError("Webhook subscription failed: " + sub.errorMessage());
        }
        // 3. register the number for Cloud API (needs the 2-step PIN)
        String pin = (String) context.get("pin");
        if (UtilValidate.isNotEmpty(pin)) {
            ObjectNode reg = WaUtil.JSON.createObjectNode().put("messaging_product", "whatsapp").put("pin", pin);
            GraphApiClient.Result r = GraphApiClient.post(base + "/" + phoneNumberId + "/register", token, reg);
            if (!r.isOk()) {
                Debug.logWarning("Number registration: " + r.errorMessage(), MODULE);
            }
        }
        // 4. read display number / verified name
        GraphApiClient.Result info = GraphApiClient.get(base + "/" + phoneNumberId, token,
                Map.of("fields", "display_phone_number,verified_name"));
        String display = info.isOk() ? info.getBody().path("display_phone_number").asText(null) : null;
        String verifiedName = info.isOk() ? info.getBody().path("verified_name").asText(null) : null;

        try {
            GenericValue ch = EntityQuery.use(delegator).from("WaChannel").where("phoneNumberId", phoneNumberId).queryFirst();
            if (ch != null && !tenantId.equals(ch.getString("tenantId"))) {
                return ServiceUtil.returnError("This number is already connected to another tenant");
            }
            if (ch == null) {
                String limitErr = checkPlanLimit(delegator, tenantId, "maxChannels", "WaChannel");
                if (limitErr != null) {
                    return ServiceUtil.returnError(limitErr);
                }
                ch = delegator.makeValue("WaChannel");
                ch.set("channelId", delegator.getNextSeqId("WaChannel"));
                ch.set("tenantId", tenantId);
                ch.set("phoneNumberId", phoneNumberId);
                ch.set("createdDate", UtilDateTime.nowTimestamp());
            }
            ch.set("wabaId", wabaId);
            ch.set("accessToken", token);
            ch.set("displayPhoneNumber", display);
            ch.set("channelName", verifiedName);
            ch.set("isActive", "Y");
            delegator.createOrStore(ch);
            Map<String, Object> result = ServiceUtil.returnSuccess("WhatsApp number " + (display == null ? phoneNumberId : display) + " connected");
            result.put("channelId", ch.getString("channelId"));
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =================================================================== flows
    public static Map<String, Object> createFlow(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String tenantId = (String) context.get("tenantId");
        try {
            String limitErr = checkPlanLimit(delegator, tenantId, "maxFlows", "WaFlow");
            if (limitErr != null) {
                return ServiceUtil.returnError(limitErr);
            }
            String flowId = (String) context.get("flowId");
            if (UtilValidate.isEmpty(flowId)) {
                flowId = delegator.getNextSeqId("WaFlow");
            } else if (EntityQuery.use(delegator).from("WaFlow").where("flowId", flowId).queryOne() != null) {
                return ServiceUtil.returnError("Flow id " + flowId + " already exists");
            }
            GenericValue flow = delegator.makeValue("WaFlow");
            flow.setNonPKFields(context);
            flow.set("flowId", flowId);
            if (UtilValidate.isEmpty(flow.getString("isActive"))) {
                flow.set("isActive", "Y");
            }
            if (UtilValidate.isEmpty(flow.getString("startNodeId"))) {
                flow.set("startNodeId", "START");
            }
            flow.create();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("flowId", flowId);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> deleteFlow(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String flowId = (String) context.get("flowId");
        try {
            for (GenericValue c : EntityQuery.use(delegator).from("WaContact").where("currentFlowId", flowId).queryList()) {
                c.set("currentFlowId", null);
                c.set("currentNodeId", null);
                c.store();
            }
            for (GenericValue ch : EntityQuery.use(delegator).from("WaChannel").where("defaultFlowId", flowId).queryList()) {
                ch.set("defaultFlowId", null);
                ch.store();
            }
            delegator.removeByAnd("WaFlowNodeOption", UtilMisc.toMap("flowId", flowId));
            delegator.removeByAnd("WaFlowNode", UtilMisc.toMap("flowId", flowId));
            delegator.removeByAnd("WaFlow", UtilMisc.toMap("flowId", flowId));
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> deleteFlowNode(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> pk = UtilMisc.toMap("flowId", context.get("flowId"), "nodeId", context.get("nodeId"));
        try {
            delegator.removeByAnd("WaFlowNodeOption", pk);
            delegator.removeByAnd("WaFlowNode", pk);
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    // =================================================================== messaging
    public static Map<String, Object> sendMessage(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String sentBy = (String) context.get("sentBy");
        if (UtilValidate.isEmpty(sentBy)) {
            sentBy = userLogin == null ? "SYSTEM" : userLogin.getString("userLoginId");
        }
        try {
            GenericValue contact;
            GenericValue channel;
            String contactId = (String) context.get("contactId");
            if (UtilValidate.isNotEmpty(contactId)) {
                contact = EntityQuery.use(delegator).from("WaContact").where("contactId", contactId).queryOne();
                if (contact == null) {
                    return ServiceUtil.returnError("Contact not found: " + contactId);
                }
                channel = contact.getRelatedOne("WaChannel", false);
            } else {
                channel = EntityQuery.use(delegator).from("WaChannel").where("channelId", context.get("channelId")).queryOne();
                String to = WaUtil.normalizeNumber((String) context.get("toNumber"));
                if (channel == null || UtilValidate.isEmpty(to)) {
                    return ServiceUtil.returnError("Give contactId, or channelId + toNumber");
                }
                contact = WaMessenger.findOrCreateContact(delegator, channel, to, null);
            }
            String tenantId = (String) context.get("tenantId");
            if (UtilValidate.isNotEmpty(tenantId) && !tenantId.equals(channel.getString("tenantId"))) {
                return ServiceUtil.returnError(WaUtil.label("WaPermissionError", locale));
            }
            Map<String, Object> result = doSend(delegator, channel, contact, context, sentBy, locale);
            if (ServiceUtil.isError(result)) {
                return result;
            }
            if ("Y".equals(context.get("pauseBot"))) {
                setPaused(delegator, contact.getString("contactId"), "Y");
            }
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Shared by the service and the REST API servlet. */
    @SuppressWarnings("unchecked")
    /**
     * Language to send a template in: the requested one if the synced template exists in that language,
     * otherwise the language the template was synced with (e.g. hello_world is en_US, not en).
     */
    static String templateLanguage(Delegator delegator, GenericValue channel, String templateName, String requested) {
        try {
            List<GenericValue> tpls = EntityQuery.use(delegator).from("WaTemplate")
                    .where("channelId", channel.getString("channelId"), "templateName", templateName).queryList();
            if (tpls.isEmpty()) {
                return requested;
            }
            for (GenericValue t : tpls) {
                if (t.getString("languageCode") != null && t.getString("languageCode").equalsIgnoreCase(requested)) {
                    return t.getString("languageCode");
                }
            }
            return tpls.get(0).getString("languageCode");
        } catch (GenericEntityException e) {
            Debug.logWarning(e, "Template language lookup failed", MODULE);
            return requested;
        }
    }

    public static Map<String, Object> doSend(Delegator delegator, GenericValue channel, GenericValue contact,
                                             Map<String, ? extends Object> in, String sentBy, Locale locale) {
        String text = (String) in.get("text");
        String mediaUrl = (String) in.get("mediaUrl");
        String templateName = (String) in.get("templateName");
        String templateLang = (String) in.get("languageCode");
        String templateId = (String) in.get("templateId");
        if (UtilValidate.isNotEmpty(templateId)) {
            try {
                GenericValue tpl = EntityQuery.use(delegator).from("WaTemplate").where("templateId", templateId).queryOne();
                if (tpl == null || !channel.getString("channelId").equals(tpl.getString("channelId"))) {
                    return ServiceUtil.returnError("Template not found for this WhatsApp number");
                }
                templateName = tpl.getString("templateName");
                templateLang = tpl.getString("languageCode");
            } catch (GenericEntityException e) {
                return ServiceUtil.returnError(e.getMessage());
            }
        }
        String to = contact.getString("waId");
        ObjectNode payload;
        String log;
        if (UtilValidate.isNotEmpty(templateName)) {
            List<String> params = (List<String>) in.get("templateParams");
            String paramsText = (String) in.get("templateParamsText");
            if (params == null && UtilValidate.isNotEmpty(paramsText)) {
                params = Arrays.asList(paramsText.split("\\|", -1));
            }
            payload = WaMessenger.template(to, templateName, templateLanguage(delegator, channel, templateName, templateLang), params);
            log = "[template " + templateName + "] " + (params == null ? "" : String.join(" | ", params));
        } else {
            if (!WaMessenger.windowOpen(contact)) {
                return ServiceUtil.returnError(WaUtil.label("WaWindowClosed", locale));
            }
            if (UtilValidate.isNotEmpty(mediaUrl)) {
                payload = WaMessenger.image(to, mediaUrl, text);
                log = "[image] " + (text == null ? "" : text);
            } else if (UtilValidate.isNotEmpty(text)) {
                payload = WaMessenger.text(to, text);
                log = text;
            } else {
                return ServiceUtil.returnError("Nothing to send: give text, mediaUrl or templateName");
            }
        }
        if ("N".equals(contact.getString("optInStatus"))) {
            return ServiceUtil.returnError("Contact has opted out (STOP)");
        }
        WaMessenger.SendResult r = WaMessenger.send(delegator, channel, contact, payload, log, sentBy, locale);
        if (!r.isOk()) {
            return ServiceUtil.returnError(r.getError());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("messageId", r.getMessageId());
        result.put("wamid", r.getWamid());
        result.put("contactId", contact.getString("contactId"));
        return result;
    }

    public static Map<String, Object> resumeBot(DispatchContext dctx, Map<String, ? extends Object> context) {
        return setPaused(dctx.getDelegator(), (String) context.get("contactId"), "N");
    }

    public static Map<String, Object> pauseBot(DispatchContext dctx, Map<String, ? extends Object> context) {
        return setPaused(dctx.getDelegator(), (String) context.get("contactId"), "Y");
    }

    private static Map<String, Object> setPaused(Delegator delegator, String contactId, String flag) {
        synchronized (contactLock(contactId)) {
            return setPausedLocked(delegator, contactId, flag);
        }
    }

    private static Map<String, Object> setPausedLocked(Delegator delegator, String contactId, String flag) {
        try {
            GenericValue c = EntityQuery.use(delegator).from("WaContact").where("contactId", contactId).queryOne();
            if (c == null) {
                return ServiceUtil.returnError("Contact not found");
            }
            c.set("botPaused", flag);
            c.set("currentFlowId", null);
            c.set("currentNodeId", null);
            c.set("unreadCount", 0L);
            c.store();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> sendBroadcast(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        int sent = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        try {
            GenericValue channel = EntityQuery.use(delegator).from("WaChannel").where("channelId", context.get("channelId")).queryOne();
            if (channel == null) {
                return ServiceUtil.returnError("Unknown channel");
            }
            if (UtilValidate.isEmpty(context.get("templateId")) && UtilValidate.isEmpty(context.get("templateName"))) {
                return ServiceUtil.returnError("Choose a template");
            }
            String bodyParams = (String) context.get("bodyParams");
            List<String> params = UtilValidate.isEmpty(bodyParams) ? null : Arrays.asList(bodyParams.split("\\|", -1));
            Set<String> numbers = new LinkedHashSet<>();
            for (String n : ((String) context.get("numbers")).split("[,;\\r\\n]+")) {
                String norm = WaUtil.normalizeNumber(n);
                if (norm != null && norm.length() >= 8) {
                    numbers.add(norm);
                }
            }
            for (String n : numbers) {
                GenericValue contact = WaMessenger.findOrCreateContact(delegator, channel, n, null);
                Map<String, Object> in = new HashMap<>();
                in.put("templateId", context.get("templateId"));
                in.put("templateName", context.get("templateName"));
                in.put("languageCode", context.get("languageCode"));
                in.put("templateParams", params);
                Map<String, Object> r = doSend(delegator, channel, contact, in,
                        "BROADCAST:" + (userLogin == null ? "" : userLogin.getString("userLoginId")), locale);
                if (ServiceUtil.isError(r)) {
                    failed++;
                    if (errors.size() < 5) {
                        errors.add(n + ": " + ServiceUtil.getErrorMessage(r));
                    }
                } else {
                    sent++;
                }
            }
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        String summary = "Broadcast: " + sent + " sent, " + failed + " failed"
                + (errors.isEmpty() ? "" : ". " + String.join("; ", errors));
        if (sent == 0 && failed > 0) {
            return ServiceUtil.returnError(summary);
        }
        Map<String, Object> result = ServiceUtil.returnSuccess(summary);
        result.put("sentCount", sent);
        result.put("failedCount", failed);
        return result;
    }

    // =================================================================== templates
    public static Map<String, Object> syncTemplates(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        int count = 0;
        try {
            GenericValue ch = EntityQuery.use(delegator).from("WaChannel").where("channelId", context.get("channelId")).queryOne();
            if (ch == null || UtilValidate.isEmpty(ch.getString("wabaId"))) {
                return ServiceUtil.returnError("Channel not found or WABA id missing");
            }
            String url = GraphApiClient.baseUrl(ch) + "/" + ch.getString("wabaId") + "/message_templates";
            Map<String, String> q = Map.of("fields", "name,language,status,category,components", "limit", "100");
            int pages = 0;
            while (url != null && pages++ < 20) {
                GraphApiClient.Result r = GraphApiClient.get(url, ch.getString("accessToken"), q);
                if (!r.isOk()) {
                    return ServiceUtil.returnError(r.errorMessage());
                }
                for (JsonNode t : r.getBody().path("data")) {
                    String name = t.path("name").asText();
                    String lang = t.path("language").asText();
                    String body = null;
                    for (JsonNode comp : t.path("components")) {
                        if ("BODY".equals(comp.path("type").asText())) {
                            body = comp.path("text").asText();
                        }
                    }
                    GenericValue tpl = EntityQuery.use(delegator).from("WaTemplate")
                            .where("channelId", ch.getString("channelId"), "templateName", name, "languageCode", lang).queryFirst();
                    if (tpl == null) {
                        tpl = delegator.makeValue("WaTemplate");
                        tpl.set("templateId", delegator.getNextSeqId("WaTemplate"));
                        tpl.set("tenantId", ch.getString("tenantId"));
                        tpl.set("channelId", ch.getString("channelId"));
                        tpl.set("templateName", name);
                        tpl.set("languageCode", lang);
                    }
                    tpl.set("category", t.path("category").asText(null));
                    tpl.set("metaStatus", t.path("status").asText(null));
                    tpl.set("bodyText", body);
                    tpl.set("lastSyncDate", UtilDateTime.nowTimestamp());
                    delegator.createOrStore(tpl);
                    count++;
                }
                String next = r.getBody().path("paging").path("next").asText(null);
                url = next;
                q = null; // "next" already carries the query string
            }
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess(count + " templates synced");
        result.put("syncedCount", count);
        return result;
    }

    // =================================================================== API keys
    public static Map<String, Object> createApiKey(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String key = WaUtil.newApiKey();
        String id = delegator.getNextSeqId("WaApiKey");
        try {
            delegator.create("WaApiKey", UtilMisc.toMap("apiKeyId", id, "tenantId", context.get("tenantId"),
                    "keyHash", WaUtil.sha256Hex(key), "keyPrefix", key.substring(0, 12) + "…",
                    "description", context.get("description"), "isActive", "Y", "createdDate", UtilDateTime.nowTimestamp()));
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess(
                WaUtil.label("WaApiKeyCreated", (Locale) context.get("locale")) + " " + key);
        result.put("apiKey", key);
        result.put("apiKeyId", id);
        return result;
    }

    // =================================================================== webhook
    public static Map<String, Object> processWebhook(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = Locale.getDefault();
        GenericValue log;
        try {
            log = EntityQuery.use(delegator).from("WaWebhookLog").where("webhookLogId", context.get("webhookLogId")).queryOne();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        if (log == null) {
            return ServiceUtil.returnError("Webhook log not found");
        }
        List<String> errors = new ArrayList<>();
        boolean handledSomething = false;
        try {
            JsonNode root = WaUtil.JSON.readTree(log.getString("payload"));
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    if (!"messages".equals(change.path("field").asText())) {
                        continue;
                    }
                    JsonNode value = change.path("value");
                    String pnid = value.path("metadata").path("phone_number_id").asText();
                    GenericValue channel = EntityQuery.use(delegator).from("WaChannel").where("phoneNumberId", pnid).queryFirst();
                    if (channel == null) {
                        errors.add("No channel for phone_number_id " + pnid);
                        continue;
                    }
                    Map<String, String> names = new HashMap<>();
                    for (JsonNode c : value.path("contacts")) {
                        names.put(c.path("wa_id").asText(), c.path("profile").path("name").asText(null));
                    }
                    for (JsonNode m : value.path("messages")) {
                        try {
                            handleInbound(delegator, channel, m, names.get(m.path("from").asText()), locale);
                            handledSomething = true;
                        } catch (Exception e) {
                            Debug.logError(e, "Inbound message failed", MODULE);
                            errors.add(e.getMessage());
                        }
                    }
                    for (JsonNode s : value.path("statuses")) {
                        handleStatus(delegator, s);
                        handledSomething = true;
                    }
                }
            }
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            errors.add(String.valueOf(e.getMessage()));
        }
        try {
            log.set("processedStatus", errors.isEmpty() ? (handledSomething ? "DONE" : "SKIP") : "ERROR");
            log.set("errorText", errors.isEmpty() ? null : String.join("\n", errors));
            log.store();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }
        return ServiceUtil.returnSuccess();
    }

    private static void handleInbound(Delegator delegator, GenericValue channel, JsonNode m, String profileName, Locale locale)
            throws GenericEntityException {
        String wamid = m.path("id").asText();
        if (EntityQuery.use(delegator).from("WaMessage").where("wamid", wamid, "direction", "IN").queryCount() > 0) {
            return; // Meta retries deliveries: ignore duplicates
        }
        String type = m.path("type").asText("text");
        String text;
        String replyId = null;
        switch (type) {
        case "text":
            text = m.path("text").path("body").asText("");
            break;
        case "interactive":
            JsonNode i = m.path("interactive");
            JsonNode reply = i.has("button_reply") ? i.get("button_reply") : i.path("list_reply");
            replyId = reply.path("id").asText(null);
            text = reply.path("title").asText("");
            break;
        case "button": // quick-reply button of a template
            replyId = m.path("button").path("payload").asText(null);
            text = m.path("button").path("text").asText("");
            break;
        case "image":
        case "video":
        case "document":
        case "audio":
        case "sticker":
            text = "[" + type + "] " + m.path(type).path("caption").asText("");
            break;
        case "location":
            JsonNode loc = m.path("location");
            text = "[location] " + loc.path("latitude").asText() + "," + loc.path("longitude").asText()
                    + " " + loc.path("name").asText("");
            break;
        default:
            text = "[" + type + "]";
        }

        String from = m.path("from").asText();
        GenericValue contact = WaMessenger.findOrCreateContact(delegator, channel, from, profileName);
        Timestamp now = UtilDateTime.nowTimestamp();

        GenericValue msg = delegator.makeValue("WaMessage");
        msg.set("messageId", delegator.getNextSeqId("WaMessage"));
        msg.set("tenantId", channel.getString("tenantId"));
        msg.set("channelId", channel.getString("channelId"));
        msg.set("contactId", contact.getString("contactId"));
        msg.set("direction", "IN");
        msg.set("wamid", wamid);
        msg.set("messageType", type);
        msg.set("body", text);
        msg.set("payload", m.toString());
        msg.set("deliveryStatus", "received");
        msg.set("createdDate", now);
        msg.create();
        WaUtil.incrementUsage(delegator, channel.getString("tenantId"), false);

        WaMessenger.markRead(channel, wamid);
        // one message per customer at a time, so parallel webhook jobs can't interleave flow state
        synchronized (contactLock(contact.getString("contactId"))) {
            GenericValue fresh = EntityQuery.use(delegator).from("WaContact").where("contactId", contact.getString("contactId")).queryOne();
            fresh.set("lastInboundDate", now);
            fresh.set("lastMessageDate", now);
            Long unread = fresh.getLong("unreadCount");
            fresh.set("unreadCount", (unread == null ? 0L : unread) + 1L);
            fresh.store();
            new BotEngine(delegator, channel, fresh, locale).handle(text, replyId);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> CONTACT_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object contactLock(String contactId) {
        if (CONTACT_LOCKS.size() > 10000) {
            CONTACT_LOCKS.clear();
        }
        return CONTACT_LOCKS.computeIfAbsent(contactId, k -> new Object());
    }

    private static final List<String> STATUS_ORDER = List.of("failed", "accepted", "sent", "delivered", "read");

    private static void handleStatus(Delegator delegator, JsonNode s) {
        try {
            GenericValue msg = EntityQuery.use(delegator).from("WaMessage")
                    .where("wamid", s.path("id").asText(), "direction", "OUT").queryFirst();
            if (msg == null) {
                return;
            }
            String status = s.path("status").asText();
            String old = msg.getString("deliveryStatus");
            // webhooks can arrive out of order: never go backwards (except to failed)
            if ("failed".equals(status) || STATUS_ORDER.indexOf(status) > STATUS_ORDER.indexOf(old)) {
                msg.set("deliveryStatus", status);
                if (s.has("errors")) {
                    JsonNode e = s.path("errors").path(0);
                    msg.set("errorText", e.path("code").asText() + " " + e.path("title").asText() + " "
                            + e.path("error_data").path("details").asText(""));
                }
                msg.store();
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }
    }

    public static Map<String, Object> purgeWebhookLogs(DispatchContext dctx, Map<String, ? extends Object> context) {
        Integer days = (Integer) context.get("daysToKeep");
        Timestamp cutoff = UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), -(days == null ? 15 : days));
        try {
            int n = dctx.getDelegator().removeByCondition("WaWebhookLog",
                    EntityCondition.makeCondition("receivedDate", EntityOperator.LESS_THAN, cutoff));
            return ServiceUtil.returnSuccess("Removed " + n + " webhook logs");
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
