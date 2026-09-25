package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.location.FlexibleLocation;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.security.Security;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/** JSON endpoints of the visual flow builder, plus the "create a new bot" wizard. */
public final class WaFlowEvents {
    private static final String MODULE = WaFlowEvents.class.getName();
    private static volatile JsonNode templatesCache;
    private static volatile long templatesStamp;

    private WaFlowEvents() { }

    // ------------------------------------------------------------------ access
    private static final class Who {
        GenericValue userLogin;
        boolean admin;
        List<String> tenants;
        String currentTenant;
    }

    private static Who who(HttpServletRequest request) {
        GenericValue ul = (GenericValue) request.getSession().getAttribute("userLogin");
        if (ul == null) {
            return null;
        }
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        Security security = (Security) request.getAttribute("security");
        Who w = new Who();
        w.userLogin = ul;
        w.admin = security != null && security.hasEntityPermission("WABOT", "_ADMIN", ul);
        w.tenants = WaUtil.getUserTenantIds(delegator, ul.getString("userLoginId"));
        Object cur = request.getSession().getAttribute("waTenantId");
        String t = cur == null ? null : cur.toString();
        if (!w.admin && !w.tenants.contains(t)) {
            t = w.tenants.isEmpty() ? null : w.tenants.get(0);
        }
        w.currentTenant = t;
        if (!w.admin && (security == null || !security.hasEntityPermission("WABOT", "_UPDATE", ul))) {
            w.tenants = List.of();
        }
        return w;
    }

    private static boolean canEdit(Who w, String tenantId) {
        return w != null && tenantId != null && (w.admin || w.tenants.contains(tenantId));
    }

    private static GenericValue flowFor(HttpServletRequest request, Who w) throws Exception {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        String flowId = request.getParameter("flowId");
        GenericValue flow = UtilValidate.isEmpty(flowId) ? null
                : EntityQuery.use(delegator).from("WaFlow").where("flowId", flowId).queryOne();
        return flow != null && canEdit(w, flow.getString("tenantId")) ? flow : null;
    }

    // ------------------------------------------------------------------ endpoints
    /** GET flowBuilderData?flowId= : the flow as a graph */
    public static String graph(HttpServletRequest request, HttpServletResponse response) {
        try {
            Who w = who(request);
            GenericValue flow = flowFor(request, w);
            if (flow == null) {
                return error(response, 404, "Flow not found or no access.");
            }
            ObjectNode out = WaUtil.JSON.createObjectNode();
            out.set("graph", WaFlowBuilder.load((Delegator) request.getAttribute("delegator"), flow));
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not load the flow.");
        }
    }

    /** POST flowBuilderSave flowId, graph(JSON) */
    public static String save(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        boolean began = false;
        try {
            Who w = who(request);
            GenericValue flow = flowFor(request, w);
            if (flow == null) {
                return error(response, 404, "Flow not found or no access.");
            }
            JsonNode g = WaUtil.JSON.readTree(request.getParameter("graph"));
            WaFlowBuilder.Check c = WaFlowBuilder.validate(delegator, flow.getString("tenantId"), flow.getString("flowId"), g);
            ObjectNode out = WaUtil.JSON.createObjectNode();
            out.set("errors", WaUtil.JSON.valueToTree(c.errors));
            out.set("warnings", WaUtil.JSON.valueToTree(c.warnings));
            if (!c.ok()) {
                out.put("ok", false);
                return json(response, 200, out);
            }
            began = TransactionUtil.begin();
            WaFlowBuilder.save(delegator, flow, g);
            TransactionUtil.commit(began);
            began = false;
            out.put("ok", true);
            out.put("message", "Flow saved. Your bot is using it now.");
            return json(response, 200, out);
        } catch (Exception e) {
            try {
                TransactionUtil.rollback(began, "flow save failed", e);
            } catch (Exception ignore) {
                // nothing more to do
            }
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not save the flow: " + e.getMessage());
        }
    }

    /** POST flowBuilderAi flowId, description, graph(optional current graph to edit) */
    public static String ai(HttpServletRequest request, HttpServletResponse response) {
        try {
            Who w = who(request);
            GenericValue flow = flowFor(request, w);
            if (flow == null) {
                return error(response, 404, "Flow not found or no access.");
            }
            JsonNode current = null;
            String cg = request.getParameter("graph");
            if (UtilValidate.isNotEmpty(cg) && "edit".equals(request.getParameter("mode"))) {
                current = WaUtil.JSON.readTree(cg);
            }
            WaFlowAi.Result r = WaFlowAi.generate((Delegator) request.getAttribute("delegator"), flow.getString("tenantId"), request.getParameter("description"),
                    tenantName((Delegator) request.getAttribute("delegator"), flow.getString("tenantId")), current);
            if (r.error != null) {
                return error(response, 200, r.error);
            }
            ObjectNode out = WaUtil.JSON.createObjectNode();
            out.set("graph", r.graph);
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "AI generation failed.");
        }
    }

    /**
     * POST flowWizard mode=ai|template|blank, flowName, description, templateId.
     * Creates the flow (plan limits apply), fills it and opens the builder.
     */
    public static String wizard(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        Who w = who(request);
        if (w == null || w.currentTenant == null) {
            return fail(request, "Select a workspace first.");
        }
        if (!canEdit(w, w.currentTenant)) {
            return fail(request, "You do not have permission to create flows.");
        }
        String tenantId = w.currentTenant;
        String mode = UtilValidate.isEmpty(request.getParameter("mode")) ? "blank" : request.getParameter("mode");
        String business = tenantName(delegator, tenantId);
        try {
            ObjectNode g;
            if ("ai".equals(mode)) {
                WaFlowAi.Result r = WaFlowAi.generate(delegator, tenantId, request.getParameter("description"), business, null);
                if (r.error != null) {
                    return fail(request, r.error);
                }
                g = r.graph;
            } else if ("template".equals(mode)) {
                JsonNode t = template(request.getParameter("templateId"));
                if (t == null) {
                    return fail(request, "Choose a template.");
                }
                g = WaFlowBuilder.sanitize(t, business);
                if (UtilValidate.isEmpty(g.path("flowName").asText(""))) {
                    g.put("flowName", t.path("name").asText("Main menu"));
                }
            } else {
                g = blank(business);
            }
            String name = request.getParameter("flowName");
            if (UtilValidate.isEmpty(name)) {
                name = g.path("flowName").asText("");
            }
            if (UtilValidate.isEmpty(name)) {
                name = "Main menu";
            }
            boolean hasDefault = EntityQuery.use(delegator).from("WaFlow")
                    .where("tenantId", tenantId, "isDefault", "Y", "isActive", "Y").queryCount() > 0;
            String keywords = g.path("triggerKeywords").asText("");
            Map<String, Object> r = dispatcher.runSync("waCreateFlow", UtilMisc.toMap("tenantId", tenantId,
                    "flowName", WaFlowBuilder.cut(name.trim(), 90), "triggerKeywords", UtilValidate.isEmpty(keywords) ? null : keywords,
                    "isDefault", hasDefault ? "N" : "Y", "isActive", "Y", "userLogin", w.userLogin,
                    "locale", request.getLocale()));
            if (ServiceUtil.isError(r)) {
                return fail(request, ServiceUtil.getErrorMessage(r));
            }
            String flowId = (String) r.get("flowId");
            GenericValue flow = EntityQuery.use(delegator).from("WaFlow").where("flowId", flowId).queryOne();
            g.remove("flowName");
            g.remove("isDefault");
            WaFlowBuilder.Check c = WaFlowBuilder.validate(delegator, tenantId, flowId, g);
            if (c.ok()) {
                boolean began = TransactionUtil.begin();
                try {
                    WaFlowBuilder.save(delegator, flow, g);
                    TransactionUtil.commit(began);
                } catch (Exception e) {
                    TransactionUtil.rollback(began, "wizard save", e);
                    throw e;
                }
            } else {
                Debug.logWarning("Wizard graph invalid, opening builder with it unsaved: " + c.errors, MODULE);
            }
            request.setAttribute("flowId", flowId);
            request.setAttribute("created", mode);
            return "success";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return fail(request, "Could not create the flow: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ workspace AI key (Settings)
    public static final Map<String, String> AI_MODELS = UtilMisc.toMap(
            "claude-sonnet-5", "Claude Sonnet 5 (recommended, best flows)",
            "claude-haiku-4-5-20251001", "Claude Haiku 4.5 (faster, cheaper)");

    /** POST saveAiKey apiKey, model : owner saves the workspace's own Claude API key after checking it. */
    public static String saveAiKey(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        Who w = who(request);
        String tenantId = w == null ? null : w.currentTenant;
        if (!isOwner(delegator, w, tenantId)) {
            return fail(request, "Only the workspace owner can change the AI key.");
        }
        String key = request.getParameter("apiKey") == null ? "" : request.getParameter("apiKey").trim();
        String model = request.getParameter("model");
        if (UtilValidate.isNotEmpty(model) && !AI_MODELS.containsKey(model)) {
            model = null;
        }
        try {
            GenericValue row = EntityQuery.use(delegator).from("WaTenantAi").where("tenantId", tenantId).queryOne();
            if (key.isEmpty()) {
                if (row == null) {
                    return fail(request, "Paste your Claude API key first.");
                }
                row.set("model", UtilValidate.isEmpty(model) ? null : model);
                row.set("updatedBy", w.userLogin.getString("userLoginId"));
                row.set("updatedDate", org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp());
                row.store();
                request.setAttribute("_EVENT_MESSAGE_", "AI settings saved.");
                return "success";
            }
            if (key.length() < 20 || key.length() > 300 || key.matches(".*\\s.*")) {
                return fail(request, "That does not look like a Claude API key. It starts with sk-ant- and is about 100 characters long.");
            }
            String err = WaFlowAi.verifyKey(key);
            if (err != null) {
                return fail(request, err);
            }
            if (row == null) {
                row = delegator.makeValue("WaTenantAi", UtilMisc.toMap("tenantId", tenantId));
            }
            row.set("apiKey", key);
            row.set("keyHint", "..." + key.substring(key.length() - 4));
            row.set("model", UtilValidate.isEmpty(model) ? null : model);
            row.set("updatedBy", w.userLogin.getString("userLoginId"));
            row.set("updatedDate", org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp());
            delegator.createOrStore(row);
            request.setAttribute("_EVENT_MESSAGE_", "Claude API key connected. You can now build bots with AI.");
            return "success";
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return fail(request, "Could not save the key: " + e.getMessage());
        }
    }

    /** POST removeAiKey */
    public static String removeAiKey(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        Who w = who(request);
        String tenantId = w == null ? null : w.currentTenant;
        if (!isOwner(delegator, w, tenantId)) {
            return fail(request, "Only the workspace owner can change the AI key.");
        }
        try {
            delegator.removeByAnd("WaTenantAi", UtilMisc.toMap("tenantId", tenantId));
            request.setAttribute("_EVENT_MESSAGE_", "Claude API key removed.");
            return "success";
        } catch (Exception e) {
            return fail(request, "Could not remove the key: " + e.getMessage());
        }
    }

    private static boolean isOwner(Delegator delegator, Who w, String tenantId) {
        return w != null && tenantId != null && (w.admin
                || "WA_OWNER".equals(WaUtil.getTenantRole(delegator, tenantId, w.userLogin.getString("userLoginId"))));
    }

    // ------------------------------------------------------------------ templates
    public static JsonNode templates() {
        try {
            URL url = FlexibleLocation.resolveLocation("component://whatsappbot/config/flow-templates.json");
            java.net.URLConnection con = url.openConnection();
            long stamp = con.getLastModified();
            if (templatesCache == null || stamp != templatesStamp) {
                try (InputStream in = con.getInputStream()) {
                    templatesCache = WaUtil.JSON.readTree(in);
                    templatesStamp = stamp;
                }
            }
            return templatesCache;
        } catch (Exception e) {
            Debug.logError(e, "Could not read flow templates", MODULE);
            return WaUtil.JSON.createArrayNode();
        }
    }

    public static JsonNode template(String id) {
        for (JsonNode t : templates()) {
            if (t.path("id").asText().equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** Template cards for the UI, without the step details. */
    public static List<Map<String, Object>> templateCards() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (JsonNode t : templates()) {
            out.add(UtilMisc.toMap("id", t.path("id").asText(), "name", t.path("name").asText(),
                    "icon", t.path("icon").asText(""), "description", t.path("description").asText(""),
                    "steps", t.path("nodes").size()));
        }
        return out;
    }

    static ObjectNode blank(String business) {
        ObjectNode g = WaUtil.JSON.createObjectNode();
        g.put("startNodeId", "WELCOME");
        g.put("triggerKeywords", "hi,hello,menu,start");
        ArrayNode nodes = g.putArray("nodes");
        ObjectNode n = nodes.addObject();
        n.put("id", "WELCOME").put("type", "buttons")
         .put("text", "Hi {{name}} 👋 Welcome to " + (UtilValidate.isEmpty(business) ? "our business" : business) + ". How can we help you today?");
        ArrayNode o = n.putArray("options");
        o.addObject().put("label", "Talk to us").put("target", "AGENT");
        nodes.addObject().put("id", "AGENT").put("type", "handoff")
            .put("text", "Thanks! A team member will reply here shortly.").putArray("options");
        return g;
    }

    private static String tenantName(Delegator delegator, String tenantId) {
        try {
            GenericValue t = EntityQuery.use(delegator).from("WaTenant").where("tenantId", tenantId).cache().queryOne();
            return t == null ? "" : t.getString("tenantName");
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ helpers
    private static String fail(HttpServletRequest request, String msg) {
        request.setAttribute("_ERROR_MESSAGE_", msg);
        return "error";
    }

    private static String error(HttpServletResponse response, int status, String msg) {
        ObjectNode o = WaUtil.JSON.createObjectNode();
        o.put("error", msg);
        return json(response, status, o);
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
