package com.msoftdynamic.whatsapp;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * The visual flow builder works on a flow "graph" (JSON). This class converts between the graph and the
 * WaFlowNode / WaFlowNodeOption rows the bot engine runs, validates graphs against WhatsApp limits and
 * cleans up graphs that come from AI or templates so they are always safe to save.
 *
 * Graph: {"startNodeId":"WELCOME","nodes":[{"id","type","text","header","footer","buttonLabel","mediaUrl",
 *          "saveAs","validation","next","targetFlowId","x","y","options":[{"label","description","keywords","target"}]}]}
 * type: text | image | buttons | list | ask | handoff | end | goto
 */
public final class WaFlowBuilder {

    public static final Map<String, String> TYPE_TO_ENUM = new LinkedHashMap<>();
    public static final Map<String, String> ENUM_TO_TYPE = new HashMap<>();
    static {
        TYPE_TO_ENUM.put("text", "WA_NODE_TEXT");
        TYPE_TO_ENUM.put("buttons", "WA_NODE_BUTTONS");
        TYPE_TO_ENUM.put("list", "WA_NODE_LIST");
        TYPE_TO_ENUM.put("ask", "WA_NODE_ASK");
        TYPE_TO_ENUM.put("image", "WA_NODE_IMAGE");
        TYPE_TO_ENUM.put("handoff", "WA_NODE_HANDOFF");
        TYPE_TO_ENUM.put("end", "WA_NODE_END");
        TYPE_TO_ENUM.put("goto", "WA_NODE_GOTO_FLOW");
        TYPE_TO_ENUM.forEach((k, v) -> ENUM_TO_TYPE.put(v, k));
    }

    // WhatsApp Cloud API limits
    public static final int MAX_BUTTONS = 3;
    public static final int MAX_ROWS = 10;
    public static final int MAX_BUTTON_LABEL = 20;
    public static final int MAX_ROW_LABEL = 24;
    public static final int MAX_ROW_DESC = 72;
    public static final int MAX_HEADER = 60;
    public static final int MAX_FOOTER = 60;
    public static final int MAX_LIST_BUTTON = 20;
    public static final int MAX_INTERACTIVE_BODY = 1024;
    public static final int MAX_TEXT = 4096;
    public static final int MAX_NODES = 60;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_]{1,20}");

    private WaFlowBuilder() { }

    /** Validation outcome: errors block saving, warnings are shown only. */
    public static final class Check {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    // ------------------------------------------------------------------ DB -> graph
    public static ObjectNode load(Delegator delegator, GenericValue flow) throws GenericEntityException {
        ObjectNode g = WaUtil.JSON.createObjectNode();
        String flowId = flow.getString("flowId");
        g.put("flowId", flowId);
        g.put("flowName", s(flow.getString("flowName")));
        g.put("triggerKeywords", s(flow.getString("triggerKeywords")));
        g.put("isDefault", "Y".equals(flow.getString("isDefault")));
        g.put("isActive", !"N".equals(flow.getString("isActive")));
        g.put("startNodeId", s(flow.getString("startNodeId")));
        ArrayNode arr = g.putArray("nodes");
        List<GenericValue> nodes = EntityQuery.use(delegator).from("WaFlowNode").where("flowId", flowId)
                .orderBy("sequenceNum", "nodeId").queryList();
        List<GenericValue> opts = EntityQuery.use(delegator).from("WaFlowNodeOption").where("flowId", flowId)
                .orderBy("nodeId", "optionSeqId").queryList();
        for (GenericValue n : nodes) {
            ObjectNode o = arr.addObject();
            o.put("id", n.getString("nodeId"));
            o.put("type", ENUM_TO_TYPE.getOrDefault(n.getString("nodeTypeId"), "text"));
            o.put("text", s(n.getString("messageText")));
            o.put("header", s(n.getString("headerText")));
            o.put("footer", s(n.getString("footerText")));
            o.put("buttonLabel", s(n.getString("buttonLabel")));
            o.put("mediaUrl", s(n.getString("mediaUrl")));
            o.put("saveAs", s(n.getString("saveAsVariable")));
            o.put("validation", s(n.getString("validationRegex")));
            o.put("next", s(n.getString("nextNodeId")));
            o.put("targetFlowId", s(n.getString("targetFlowId")));
            if (n.get("posX") != null) {
                o.put("x", n.getLong("posX"));
                o.put("y", n.getLong("posY") == null ? 0L : n.getLong("posY"));
            }
            ArrayNode oa = o.putArray("options");
            for (GenericValue op : opts) {
                if (op.getString("nodeId").equals(n.getString("nodeId"))) {
                    ObjectNode x = oa.addObject();
                    x.put("label", s(op.getString("optionLabel")));
                    x.put("description", s(op.getString("optionDescription")));
                    x.put("keywords", s(op.getString("matchKeywords")));
                    x.put("target", s(op.getString("targetNodeId")));
                }
            }
        }
        return g;
    }

    // ------------------------------------------------------------------ validation
    public static Check validate(Delegator delegator, String tenantId, String flowId, JsonNode g) throws GenericEntityException {
        Check c = new Check();
        JsonNode nodes = g.path("nodes");
        if (!nodes.isArray() || nodes.size() == 0) {
            c.errors.add("Add at least one step.");
            return c;
        }
        if (nodes.size() > MAX_NODES) {
            c.errors.add("A flow can have at most " + MAX_NODES + " steps. Split it into several flows and link them with a 'Go to flow' step.");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode n : nodes) {
            String id = n.path("id").asText("");
            if (!ID.matcher(id).matches()) {
                c.errors.add("Step id '" + id + "' must be 1-20 letters, digits or _.");
            } else if (!ids.add(id)) {
                c.errors.add("Two steps use the same id '" + id + "'.");
            }
        }
        String start = g.path("startNodeId").asText("");
        if (!ids.contains(start)) {
            c.errors.add("Choose which step the bot starts with (Set as start).");
        }
        for (JsonNode n : nodes) {
            String id = n.path("id").asText("");
            String type = n.path("type").asText("");
            String name = "Step " + id;
            String text = n.path("text").asText("").trim();
            if (!TYPE_TO_ENUM.containsKey(type)) {
                c.errors.add(name + ": unknown step type '" + type + "'.");
                continue;
            }
            boolean choice = "buttons".equals(type) || "list".equals(type);
            if ((choice || "text".equals(type) || "ask".equals(type)) && text.isEmpty()) {
                c.errors.add(name + ": the message is empty.");
            }
            int maxText = choice ? MAX_INTERACTIVE_BODY : MAX_TEXT;
            if (text.length() > maxText) {
                c.errors.add(name + ": message is " + text.length() + " characters, WhatsApp allows " + maxText + ".");
            }
            if ("image".equals(type)) {
                String url = n.path("mediaUrl").asText("").trim();
                if (!url.startsWith("https://")) {
                    c.errors.add(name + ": add an image link starting with https://");
                }
                if (text.length() > MAX_INTERACTIVE_BODY) {
                    c.errors.add(name + ": image caption is too long (max " + MAX_INTERACTIVE_BODY + ").");
                }
            }
            if ("ask".equals(type)) {
                String re = n.path("validation").asText("");
                if (!re.isEmpty()) {
                    try {
                        Pattern.compile(re);
                    } catch (PatternSyntaxException e) {
                        c.errors.add(name + ": the answer check (pattern) is not valid.");
                    }
                }
                String var = n.path("saveAs").asText("");
                if (!var.isEmpty() && !var.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) {
                    c.errors.add(name + ": 'save answer as' must be a simple name like customerName.");
                }
            }
            if ("goto".equals(type)) {
                String tf = n.path("targetFlowId").asText("");
                GenericValue f = tf.isEmpty() ? null : EntityQuery.use(delegator).from("WaFlow").where("flowId", tf).queryOne();
                if (f == null || (tenantId != null && !tenantId.equals(f.getString("tenantId")))) {
                    c.errors.add(name + ": choose the flow to go to.");
                } else if (tf.equals(flowId)) {
                    c.errors.add(name + ": a flow cannot jump to itself; connect to a step instead.");
                }
            }
            if (lenOver(n, "header", MAX_HEADER)) {
                c.errors.add(name + ": header is longer than " + MAX_HEADER + " characters.");
            }
            if (lenOver(n, "footer", MAX_FOOTER)) {
                c.errors.add(name + ": footer is longer than " + MAX_FOOTER + " characters.");
            }
            if (lenOver(n, "buttonLabel", MAX_LIST_BUTTON)) {
                c.errors.add(name + ": menu button text is longer than " + MAX_LIST_BUTTON + " characters.");
            }
            String next = n.path("next").asText("");
            if (!next.isEmpty() && !ids.contains(next)) {
                c.errors.add(name + ": its arrow points to a step that no longer exists.");
            }
            if (choice) {
                JsonNode opts = n.path("options");
                int count = opts.isArray() ? opts.size() : 0;
                if (count == 0) {
                    c.errors.add(name + ": add at least one option.");
                }
                if (count > MAX_ROWS) {
                    c.errors.add(name + ": WhatsApp allows at most " + MAX_ROWS + " options.");
                }
                boolean asButtons = "buttons".equals(type) && count <= MAX_BUTTONS;
                if ("buttons".equals(type) && count > MAX_BUTTONS) {
                    c.warnings.add(name + ": more than 3 buttons, so WhatsApp will show them as a list menu.");
                }
                Set<String> labels = new HashSet<>();
                int i = 0;
                for (JsonNode o : opts) {
                    i++;
                    String label = o.path("label").asText("").trim();
                    int max = asButtons ? MAX_BUTTON_LABEL : MAX_ROW_LABEL;
                    if (label.isEmpty()) {
                        c.errors.add(name + ": option " + i + " has no text.");
                    } else if (label.length() > max) {
                        c.errors.add(name + ": option '" + label + "' is longer than " + max + " characters.");
                    } else if (!labels.add(label.toLowerCase(Locale.ROOT))) {
                        c.errors.add(name + ": two options are both called '" + label + "'.");
                    }
                    if (o.path("description").asText("").length() > MAX_ROW_DESC) {
                        c.errors.add(name + ": description of '" + label + "' is longer than " + MAX_ROW_DESC + " characters.");
                    }
                    String t = o.path("target").asText("");
                    if (!t.isEmpty() && !ids.contains(t)) {
                        c.errors.add(name + ": option '" + label + "' points to a step that no longer exists.");
                    }
                }
            }
        }
        if (c.ok()) {
            Set<String> reach = reachable(g);
            for (String id : ids) {
                if (!reach.contains(id)) {
                    c.warnings.add("Step " + id + " is not connected, customers will never reach it.");
                }
            }
        }
        return c;
    }

    private static boolean lenOver(JsonNode n, String field, int max) {
        return n.path(field).asText("").length() > max;
    }

    public static Set<String> reachable(JsonNode g) {
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode n : g.path("nodes")) {
            byId.put(n.path("id").asText(""), n);
        }
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(g.path("startNodeId").asText(""));
        while (!q.isEmpty()) {
            String id = q.poll();
            JsonNode n = byId.get(id);
            if (n == null || !seen.add(id)) {
                continue;
            }
            String next = n.path("next").asText("");
            if (!next.isEmpty()) {
                q.add(next);
            }
            for (JsonNode o : n.path("options")) {
                String t = o.path("target").asText("");
                if (!t.isEmpty()) {
                    q.add(t);
                }
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ graph -> DB
    /** Replaces all steps of the flow with the graph. Caller validates first and runs inside a transaction. */
    public static void save(Delegator delegator, GenericValue flow, JsonNode g) throws GenericEntityException {
        String flowId = flow.getString("flowId");
        Set<String> keep = new HashSet<>();
        for (JsonNode n : g.path("nodes")) {
            keep.add(n.path("id").asText());
        }
        delegator.removeByAnd("WaFlowNodeOption", UtilMisc.toMap("flowId", flowId));
        delegator.removeByAnd("WaFlowNode", UtilMisc.toMap("flowId", flowId));
        long seq = 10;
        for (JsonNode n : g.path("nodes")) {
            String type = n.path("type").asText();
            GenericValue v = delegator.makeValue("WaFlowNode");
            v.set("flowId", flowId);
            v.set("nodeId", n.path("id").asText());
            v.set("nodeTypeId", TYPE_TO_ENUM.get(type));
            v.set("messageText", blankToNull(n.path("text").asText("").trim()));
            v.set("headerText", blankToNull(n.path("header").asText("").trim()));
            v.set("footerText", blankToNull(n.path("footer").asText("").trim()));
            v.set("buttonLabel", blankToNull(n.path("buttonLabel").asText("").trim()));
            v.set("mediaUrl", "image".equals(type) ? blankToNull(n.path("mediaUrl").asText("").trim()) : null);
            v.set("saveAsVariable", "ask".equals(type) ? blankToNull(n.path("saveAs").asText("").trim()) : null);
            v.set("validationRegex", "ask".equals(type) ? blankToNull(n.path("validation").asText("")) : null);
            boolean hasNext = "text".equals(type) || "image".equals(type) || "ask".equals(type);
            v.set("nextNodeId", hasNext ? blankToNull(n.path("next").asText("")) : null);
            v.set("targetFlowId", "goto".equals(type) ? blankToNull(n.path("targetFlowId").asText("")) : null);
            v.set("sequenceNum", seq);
            seq += 10;
            if (n.has("x") && n.path("x").isNumber()) {
                v.set("posX", Math.round(n.path("x").asDouble()));
                v.set("posY", Math.round(n.path("y").asDouble()));
            }
            v.create();
            if ("buttons".equals(type) || "list".equals(type)) {
                int i = 1;
                for (JsonNode o : n.path("options")) {
                    GenericValue ov = delegator.makeValue("WaFlowNodeOption");
                    ov.set("flowId", flowId);
                    ov.set("nodeId", v.getString("nodeId"));
                    ov.set("optionSeqId", String.format("%02d", i++));
                    ov.set("optionLabel", o.path("label").asText("").trim());
                    ov.set("optionDescription", blankToNull(o.path("description").asText("").trim()));
                    ov.set("matchKeywords", blankToNull(o.path("keywords").asText("").trim().toLowerCase(Locale.ROOT)));
                    ov.set("targetNodeId", blankToNull(o.path("target").asText("")));
                    ov.create();
                }
            }
        }
        flow.set("startNodeId", g.path("startNodeId").asText());
        if (g.has("flowName") && !g.path("flowName").asText("").trim().isEmpty()) {
            flow.set("flowName", g.path("flowName").asText().trim());
        }
        if (g.has("triggerKeywords")) {
            flow.set("triggerKeywords", blankToNull(normaliseKeywords(g.path("triggerKeywords").asText(""))));
        }
        if (g.has("isDefault")) {
            flow.set("isDefault", g.path("isDefault").asBoolean() ? "Y" : "N");
        }
        if (g.has("isActive")) {
            flow.set("isActive", g.path("isActive").asBoolean() ? "Y" : "N");
        }
        flow.store();
        // customers waiting on a step that was removed start fresh next time
        List<GenericValue> waiting = EntityQuery.use(delegator).from("WaContact").where(EntityCondition.makeCondition(
                EntityCondition.makeCondition("currentFlowId", flowId),
                EntityCondition.makeCondition("currentNodeId", EntityOperator.NOT_IN, new ArrayList<>(keep)))).queryList();
        for (GenericValue c : waiting) {
            c.set("currentFlowId", null);
            c.set("currentNodeId", null);
            c.store();
        }
    }

    public static String normaliseKeywords(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : s.split(",")) {
            String t = k.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return String.join(",", out);
    }

    // ------------------------------------------------------------------ cleanup of AI / template graphs
    /**
     * Turns a loosely shaped graph (AI output, template) into one that passes validation:
     * fixes ids, types, lengths and dangling links. Never throws on odd input.
     */
    public static ObjectNode sanitize(JsonNode raw, String businessName) {
        ObjectNode g = WaUtil.JSON.createObjectNode();
        JsonNode in = raw.path("nodes");
        Map<String, String> idMap = new LinkedHashMap<>();   // original id -> clean id of its first step (for links)
        List<String> assigned = new ArrayList<>();           // clean id per step, in order
        Set<String> used = new HashSet<>();
        int k = 0;
        for (JsonNode n : in) {
            if (k++ >= MAX_NODES) {
                break;
            }
            String id = cleanId(n.path("id").asText(""), used, k);
            assigned.add(id);
            idMap.putIfAbsent(n.path("id").asText(""), id);
        }
        ArrayNode out = g.putArray("nodes");
        k = 0;
        for (JsonNode n : in) {
            if (k >= assigned.size()) {
                break;
            }
            String id = assigned.get(k++);
            String type = n.path("type").asText("text").toLowerCase(Locale.ROOT).trim();
            if (ENUM_TO_TYPE.containsKey(n.path("type").asText(""))) {
                type = ENUM_TO_TYPE.get(n.path("type").asText(""));
            }
            if ("message".equals(type)) {
                type = "text";
            } else if ("question".equals(type) || "input".equals(type)) {
                type = "ask";
            } else if ("agent".equals(type) || "human".equals(type)) {
                type = "handoff";
            } else if ("menu".equals(type)) {
                type = "list";
            }
            if (!TYPE_TO_ENUM.containsKey(type) || "goto".equals(type)) {
                type = "goto".equals(type) ? "end" : "text";
            }
            ObjectNode o = out.addObject();
            o.put("id", id);
            String text = fill(n.path("text").asText(""), businessName);
            JsonNode opts = n.path("options");
            if (("buttons".equals(type) || "list".equals(type)) && (!opts.isArray() || opts.size() == 0)) {
                type = "text";
            }
            if ("image".equals(type) && !n.path("mediaUrl").asText("").startsWith("https://")) {
                type = "text";
            }
            if ("buttons".equals(type) && opts.size() > MAX_BUTTONS) {
                type = "list";
            }
            boolean choice = "buttons".equals(type) || "list".equals(type);
            if (text.trim().isEmpty() && (choice || "text".equals(type) || "ask".equals(type))) {
                text = choice ? "Please choose an option:" : ("ask".equals(type) ? "Please type your answer:" : "Thank you!");
            }
            o.put("type", type);
            o.put("text", cut(text, choice ? MAX_INTERACTIVE_BODY : MAX_TEXT));
            o.put("header", cut(fill(n.path("header").asText(""), businessName), MAX_HEADER));
            o.put("footer", cut(fill(n.path("footer").asText(""), businessName), MAX_FOOTER));
            o.put("buttonLabel", cut(n.path("buttonLabel").asText(""), MAX_LIST_BUTTON));
            o.put("mediaUrl", "image".equals(type) ? n.path("mediaUrl").asText("") : "");
            String var = n.path("saveAs").asText("").replaceAll("[^A-Za-z0-9_]", "");
            if (!var.isEmpty() && !Character.isLetter(var.charAt(0))) {
                var = "v" + var;
            }
            o.put("saveAs", "ask".equals(type) ? cut(var, 40) : "");
            String re = "ask".equals(type) ? n.path("validation").asText("") : "";
            try {
                Pattern.compile(re);
            } catch (PatternSyntaxException e) {
                re = "";
            }
            o.put("validation", re);
            boolean hasNext = "text".equals(type) || "image".equals(type) || "ask".equals(type);
            String next = idMap.get(n.path("next").asText(""));
            o.put("next", hasNext && next != null && !next.equals(id) ? next : "");
            o.put("targetFlowId", "");
            if (n.path("x").isNumber()) {
                o.put("x", n.path("x").asDouble());
                o.put("y", n.path("y").asDouble());
            }
            ArrayNode oa = o.putArray("options");
            if (choice) {
                Set<String> labels = new HashSet<>();
                int max = "buttons".equals(type) ? MAX_BUTTON_LABEL : MAX_ROW_LABEL;
                for (JsonNode op : opts) {
                    if (oa.size() >= MAX_ROWS) {
                        break;
                    }
                    String label = cut(op.path("label").asText("").trim(), max);
                    if (label.isEmpty() || !labels.add(label.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    ObjectNode x = oa.addObject();
                    x.put("label", label);
                    x.put("description", "list".equals(type) ? cut(op.path("description").asText(""), MAX_ROW_DESC) : "");
                    x.put("keywords", normaliseKeywords(op.path("keywords").asText("")));
                    String t = idMap.get(op.path("target").asText(""));
                    x.put("target", t == null ? "" : t);
                }
            }
        }
        String start = idMap.get(raw.path("startNodeId").asText(""));
        if (start == null && out.size() > 0) {
            start = out.get(0).path("id").asText();
        }
        g.put("startNodeId", start == null ? "" : start);
        if (raw.has("flowName")) {
            g.put("flowName", cut(raw.path("flowName").asText(""), 90));
        }
        if (raw.has("triggerKeywords")) {
            g.put("triggerKeywords", normaliseKeywords(raw.path("triggerKeywords").asText("")));
        }
        return g;
    }

    private static String cleanId(String raw, Set<String> used, int n) {
        String id = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (id.isEmpty()) {
            id = "STEP_" + n;
        }
        if (id.length() > 20) {
            id = id.substring(0, 20);
        }
        String base = id.length() > 16 ? id.substring(0, 16) : id;
        int i = 2;
        while (used.contains(id)) {
            id = base + "_" + i++;
        }
        used.add(id);
        return id;
    }

    private static String fill(String s, String business) {
        return s == null ? "" : s.replace("{{business}}", UtilValidate.isEmpty(business) ? "us" : business);
    }

    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }

    private static String blankToNull(String v) {
        return UtilValidate.isEmpty(v) ? null : v;
    }
}
