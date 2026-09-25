package com.msoftdynamic.whatsapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Generates or edits a bot flow from a plain-language description using an LLM.
 * Each workspace adds its own Claude API key in Settings (WaTenantAi, stored encrypted); the platform
 * key in the configuration is only an optional fallback.
 * Default provider is the Claude API (Anthropic); an OpenAI-compatible endpoint also works.
 *   ai.provider=anthropic | openai
 *   ai.api.key=...            (optional platform fallback key)
 *   ai.model=claude-sonnet-5  (optional)
 *   ai.base.url=...           (optional, for a proxy or testing)
 *   ai.daily.limit=30         (generations per workspace per day on the platform key)
 *   ai.daily.limit.own.key=200 (safety limit when the workspace uses its own key)
 */
public final class WaFlowAi {
    private static final String MODULE = WaFlowAi.class.getName();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final Map<String, Integer> USED_TODAY = new ConcurrentHashMap<>();

    private WaFlowAi() { }

    /** The key a workspace uses: its own key first, then the optional platform key. */
    static final class Key {
        final String apiKey;
        final String model;
        final boolean own;
        Key(String apiKey, String model, boolean own) {
            this.apiKey = apiKey;
            this.model = model;
            this.own = own;
        }
    }

    static Key key(Delegator delegator, String tenantId) {
        try {
            GenericValue t = tenantId == null ? null
                    : EntityQuery.use(delegator).from("WaTenantAi").where("tenantId", tenantId).queryOne();
            if (t != null && UtilValidate.isNotEmpty(t.getString("apiKey"))) {
                return new Key(t.getString("apiKey").trim(), t.getString("model"), true);
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, "Could not read workspace AI key", MODULE);
        }
        String platform = WaUtil.prop("ai.api.key", "").trim();
        return platform.isEmpty() ? null : new Key(platform, null, false);
    }

    public static boolean isConfigured(Delegator delegator, String tenantId) {
        return key(delegator, tenantId) != null;
    }

    /** Checks a key with a free call (list models). Returns null if the key works, else a message. */
    public static String verifyKey(String apiKey) {
        String provider = WaUtil.prop("ai.provider", "anthropic").trim().toLowerCase();
        HttpRequest.Builder rb;
        if ("openai".equals(provider)) {
            rb = HttpRequest.newBuilder(URI.create(WaUtil.prop("ai.base.url", "https://api.openai.com").replaceAll("/+$", "") + "/v1/models"))
                    .header("Authorization", "Bearer " + apiKey);
        } else {
            rb = HttpRequest.newBuilder(URI.create(WaUtil.prop("ai.base.url", "https://api.anthropic.com").replaceAll("/+$", "") + "/v1/models"))
                    .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
        }
        try {
            HttpResponse<String> res = HTTP.send(rb.timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                return "This API key was not accepted by Claude. Please copy it again from console.anthropic.com.";
            }
            if (res.statusCode() / 100 != 2 && res.statusCode() != 429) {
                Debug.logWarning("AI key check " + res.statusCode() + ": " + res.body(), MODULE);
                return "Could not check the key right now (error " + res.statusCode() + "). Please try again.";
            }
            return null;
        } catch (Exception e) {
            Debug.logWarning(e, "AI key check failed", MODULE);
            return "Could not reach Claude to check the key. Please try again in a minute.";
        }
    }

    /** Result: graph on success, otherwise error. */
    public static final class Result {
        public final ObjectNode graph;
        public final String error;
        Result(ObjectNode graph, String error) {
            this.graph = graph;
            this.error = error;
        }
    }

    private static final String SYSTEM = String.join("\n",
        "You design WhatsApp chatbot flows for small businesses. Reply with ONE JSON object only, no markdown, no comments.",
        "",
        "JSON shape:",
        "{\"flowName\": string (max 40), \"triggerKeywords\": \"hi,hello,menu\" (comma separated, lowercase),",
        " \"startNodeId\": id of first step,",
        " \"nodes\": [ {\"id\": \"UPPER_SNAKE\" (max 20 chars, unique), \"type\": one of text|buttons|list|ask|handoff|end,",
        "   \"text\": message shown to the customer,",
        "   \"header\": optional short title for buttons/list (max 60), \"footer\": optional (max 60),",
        "   \"buttonLabel\": for list only, text of the menu button (max 20), e.g. \"View options\",",
        "   \"saveAs\": for ask only, variable name in camelCase e.g. customerName,",
        "   \"validation\": for ask only, optional Java regex the answer must match (e.g. phone ^\\\\+?[0-9 ]{8,15}$), else \"\",",
        "   \"next\": for text and ask, id of the step that follows (\"\" to finish),",
        "   \"options\": for buttons/list: [{\"label\": string, \"description\": list only (max 72), \"keywords\": \"extra words, comma separated\", \"target\": id of the step it leads to}] } ] }",
        "",
        "WhatsApp rules you must follow:",
        "- buttons: 1 to 3 options, each label max 20 characters.",
        "- list: 2 to 10 options, each label max 24 characters, description max 72.",
        "- Message text max 1024 characters for buttons/list; keep all messages short and friendly, 1-3 short lines, emojis sparingly.",
        "- text steps send a message and continue immediately to next; ask waits for a typed answer; buttons/list wait for a choice.",
        "- handoff passes the chat to a human agent (use it for 'Talk to us' / complaints / custom requests). end sends a final message.",
        "- Placeholders: {{name}} is the customer's WhatsApp name; {{someVar}} shows an earlier ask answer saved as someVar.",
        "",
        "Good flow design:",
        "- Start with a warm welcome (buttons or list) that greets {{name}} and offers the main choices.",
        "- Every branch must end in end, handoff, or lead back to the main menu (target the welcome step). No dead ends, no unused steps.",
        "- Collect details with ask steps one question at a time, then confirm with a summary using the placeholders.",
        "- Always include a way to reach a human.",
        "- Use realistic content for the business described. Do not invent prices, addresses or phone numbers unless given; say a team member will share details instead.",
        "- Usually 6 to 20 steps.",
        "- Write in the same language as the business description unless told otherwise.");

    public static Result generate(Delegator delegator, String tenantKey, String description, String businessName, JsonNode currentGraph) {
        return generate(delegator, tenantKey, description, businessName, currentGraph, java.util.Collections.emptySet());
    }

    private static final Map<String, String> ZOHO_DOCS = Map.of(
        "crm", "- crm_find: find the customer in Zoho CRM by their WhatsApp number. config {\"action\":\"crm_find\",\"module\":\"Contacts\"|\"Leads\",\"matchBy\":\"whatsapp\"}. Sets {{crmName}}, {{crmEmail}}, {{crmOwner}}.\n"
             + "- crm_upsert: create or update a CRM record. config {\"action\":\"crm_upsert\",\"module\":\"Leads\",\"dupField\":\"Mobile\",\"fields\":[{\"field\":\"Last_Name\",\"value\":\"{{fullName}}\"},{\"field\":\"Mobile\",\"value\":\"{{whatsapp}}\"},{\"field\":\"Email\",\"value\":\"{{email}}\"},{\"field\":\"Lead_Source\",\"value\":\"WhatsApp\"},{\"field\":\"Description\",\"value\":\"...\"}]}. Use real CRM API field names; Last_Name is required.",
        "books", "- books_invoice: invoice status by number. config {\"action\":\"books_invoice\",\"number\":\"{{invoiceNo}}\",\"verify\":true}. Sets {{invoiceStatus}}, {{invoiceTotal}}, {{invoiceBalance}}, {{invoiceDueDate}}, {{invoiceLink}}.\n"
             + "- books_balance: the customer's balance by WhatsApp number. config {\"action\":\"books_balance\"}. Sets {{customerName}}, {{balanceDue}}, {{unpaidCount}}, {{unpaidInvoices}} (multi-line list).",
        "inventory", "- inv_stock: stock and price of an item. config {\"action\":\"inv_stock\",\"query\":\"{{item}}\"}. Sets {{itemName}}, {{itemRate}}, {{itemStock}}, {{itemList}}.\n"
             + "- inv_order: sales order status by number. config {\"action\":\"inv_order\",\"number\":\"{{orderNo}}\",\"verify\":true}. Sets {{orderStatus}}, {{orderShipped}}, {{orderTotal}}, {{orderDate}}.",
        "people", "- people_balance: employee's leave balance (employee found by WhatsApp number). config {\"action\":\"people_balance\"}. Sets {{employeeName}}, {{leaveBalances}}.\n"
             + "- people_apply: apply for leave. config {\"action\":\"people_apply\",\"leaveType\":\"{{leaveType}}\",\"from\":\"{{fromDate}}\",\"to\":\"{{toDate}}\",\"reason\":\"{{reason}}\"}. Sets {{leaveRequestId}}.");

    static String zohoPrompt(java.util.Set<String> apps) {
        if (apps == null || apps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nThis business has connected Zoho. You may add steps of type \"zoho\" that read or write their Zoho data:\n")
            .append("{\"id\":..., \"type\":\"zoho\", \"config\":{...}, \"next\": step when it worked/was found, \"failNext\": step when not found or failed}. ")
            .append("Collect the needed inputs first with ask steps (saveAs), then use them as {{variables}} in config. Show results in the next message with the variables. ")
            .append("Always connect failNext to a helpful message or a handoff. {{whatsapp}} is the customer's number with +. Available actions:\n");
        for (String a : List.of("crm", "books", "inventory", "people")) {
            if (apps.contains(a)) {
                sb.append(ZOHO_DOCS.get(a)).append("\n");
            }
        }
        return sb.toString();
    }

    public static Result generate(Delegator delegator, String tenantKey, String description, String businessName, JsonNode currentGraph,
            java.util.Set<String> zohoApps) {
        Key k = key(delegator, tenantKey);
        if (k == null) {
            return new Result(null, "Add your Claude API key in Settings > AI assistant to use AI.");
        }
        if (UtilValidate.isEmpty(description) || description.trim().length() < 5) {
            return new Result(null, "Describe your business or the change you want in a few words.");
        }
        if (description.length() > 4000) {
            description = description.substring(0, 4000);
        }
        int limit = k.own ? WaUtil.propInt("ai.daily.limit.own.key", 200) : WaUtil.propInt("ai.daily.limit", 30);
        String key = LocalDate.now() + "|" + tenantKey;
        int used = USED_TODAY.getOrDefault(key, 0);
        if (limit > 0 && used >= limit) {
            return new Result(null, "Daily AI limit reached (" + limit + " per day). Please try again tomorrow or edit the flow by hand.");
        }
        USED_TODAY.keySet().removeIf(e -> !e.startsWith(LocalDate.now().toString()));
        USED_TODAY.merge(key, 1, Integer::sum);

        StringBuilder user = new StringBuilder();
        if (UtilValidate.isNotEmpty(businessName)) {
            user.append("Business name: ").append(businessName).append("\n");
        }
        if (currentGraph != null && currentGraph.path("nodes").size() > 0) {
            ObjectNode slim = currentGraph.deepCopy();
            for (JsonNode n : slim.path("nodes")) {
                ((ObjectNode) n).remove("x");
                ((ObjectNode) n).remove("y");
            }
            slim.remove("flowId");
            user.append("Here is the current flow:\n").append(slim.toString()).append("\n\n")
                .append("Change it as follows and return the COMPLETE updated flow (keep existing step ids where the step stays):\n")
                .append(description.trim());
        } else {
            user.append("Create a WhatsApp chatbot flow for this business:\n").append(description.trim());
        }
        try {
            String text = call(user.toString(), k, zohoPrompt(zohoApps));
            JsonNode raw = WaUtil.JSON.readTree(extractJson(text));
            if (!raw.path("nodes").isArray() || raw.path("nodes").size() == 0) {
                return new Result(null, "The AI did not return a usable flow. Please try again with a little more detail.");
            }
            ObjectNode g = WaFlowBuilder.sanitize(raw, businessName, zohoApps == null ? java.util.Collections.emptySet() : zohoApps);
            // keep canvas positions of steps that still exist
            if (currentGraph != null) {
                Map<String, JsonNode> old = new java.util.HashMap<>();
                for (JsonNode n : currentGraph.path("nodes")) {
                    old.put(n.path("id").asText(), n);
                }
                for (JsonNode n : g.path("nodes")) {
                    JsonNode o = old.get(n.path("id").asText());
                    if (o != null && o.path("x").isNumber()) {
                        ((ObjectNode) n).put("x", o.path("x").asDouble()).put("y", o.path("y").asDouble());
                    }
                }
            }
            return new Result(g, null);
        } catch (AiException e) {
            USED_TODAY.merge(key, -1, Integer::sum);
            return new Result(null, e.getMessage());
        } catch (Exception e) {
            Debug.logWarning(e, "AI flow parse failed", MODULE);
            return new Result(null, "The AI answer could not be read. Please try again.");
        }
    }

    static final class AiException extends Exception {
        private static final long serialVersionUID = 1L;
        AiException(String msg) {
            super(msg);
        }
    }

    private static String call(String userText, Key k, String systemExtra) throws AiException {
        String system = SYSTEM + systemExtra;
        String provider = WaUtil.prop("ai.provider", "anthropic").trim().toLowerCase();
        String apiKey = k.apiKey;
        String model = UtilValidate.isNotEmpty(k.model) ? k.model : null;
        int timeout = WaUtil.propInt("ai.timeout.seconds", 120);
        ObjectNode body = WaUtil.JSON.createObjectNode();
        HttpRequest.Builder rb;
        if ("openai".equals(provider)) {
            String base = WaUtil.prop("ai.base.url", "https://api.openai.com").replaceAll("/+$", "");
            body.put("model", model != null ? model : WaUtil.prop("ai.model", "gpt-4.1"));
            body.putObject("response_format").put("type", "json_object");
            ArrayNode msgs = body.putArray("messages");
            msgs.addObject().put("role", "system").put("content", system);
            msgs.addObject().put("role", "user").put("content", userText);
            rb = HttpRequest.newBuilder(URI.create(base + "/v1/chat/completions")).header("Authorization", "Bearer " + apiKey);
        } else {
            String base = WaUtil.prop("ai.base.url", "https://api.anthropic.com").replaceAll("/+$", "");
            body.put("model", model != null ? model : WaUtil.prop("ai.model", "claude-sonnet-5"));
            body.put("max_tokens", WaUtil.propInt("ai.max.tokens", 8000));
            body.put("system", system);
            body.putArray("messages").addObject().put("role", "user").put("content", userText);
            rb = HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                    .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
        }
        HttpRequest req = rb.timeout(Duration.ofSeconds(timeout)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> res;
        try {
            res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiException("The AI took too long to answer. Please try again.");
        } catch (Exception e) {
            Debug.logWarning(e, "AI call failed", MODULE);
            throw new AiException("Could not reach the AI service. Please try again in a minute.");
        }
        JsonNode json;
        try {
            json = WaUtil.JSON.readTree(res.body());
        } catch (Exception e) {
            json = WaUtil.JSON.createObjectNode();
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            Debug.logWarning("AI auth failed: " + res.body(), MODULE);
            throw new AiException(k.own ? "Your Claude API key was not accepted. Please update it in Settings > AI assistant."
                    : "The AI key is not valid. Please check ai.api.key in the configuration.");
        }
        if (res.statusCode() == 400 && json.path("error").path("message").asText("").toLowerCase().contains("credit")) {
            throw new AiException("Your Claude account has no credit left. Add credit at console.anthropic.com (Billing), then try again.");
        }
        if (res.statusCode() == 429 || res.statusCode() == 529) {
            throw new AiException("The AI service is busy right now. Please try again in a minute.");
        }
        if (res.statusCode() / 100 != 2) {
            Debug.logWarning("AI error " + res.statusCode() + ": " + res.body(), MODULE);
            throw new AiException("The AI service returned an error (" + res.statusCode() + "). Please try again.");
        }
        String text = "openai".equals(provider)
                ? json.path("choices").path(0).path("message").path("content").asText("")
                : collectText(json.path("content"));
        if (text.isEmpty()) {
            throw new AiException("The AI returned an empty answer. Please try again.");
        }
        return text;
    }

    private static String collectText(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            if ("text".equals(c.path("type").asText())) {
                sb.append(c.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    static String extractJson(String text) {
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        return a >= 0 && b > a ? text.substring(a, b + 1) : text;
    }
}
