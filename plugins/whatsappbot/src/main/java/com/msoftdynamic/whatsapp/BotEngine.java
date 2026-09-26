package com.msoftdynamic.whatsapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Flow runner. A contact is either idle (no current node) or waiting on a
 * BUTTONS / LIST / ASK node. Each inbound message either answers the waiting
 * node or starts a flow chosen by keyword (or the default flow).
 */
public final class BotEngine {

    private static final String MODULE = BotEngine.class.getName();
    private static final String OPT_PREFIX = "o|";

    private final Delegator delegator;
    private final GenericValue channel;
    private final GenericValue contact;
    private final Locale locale;
    private final Map<String, Object> vars;

    public BotEngine(Delegator delegator, GenericValue channel, GenericValue contact, Locale locale) {
        this.delegator = delegator;
        this.channel = channel;
        this.contact = contact;
        this.locale = locale == null ? Locale.getDefault() : locale;
        this.vars = WaUtil.readVars(contact);
    }

    /**
     * @param text   what the user typed (or the title of the tapped button)
     * @param replyId id of the tapped button / list row, may be null
     */
    public void handle(String text, String replyId) throws GenericEntityException {
        String input = text == null ? "" : text.trim();
        String lower = input.toLowerCase(Locale.ROOT);

        // --- opt-out / opt-in (compliance) ---
        if (WaUtil.propList("bot.optout.keywords").contains(lower)) {
            WaContacts.setConsent(contact, false, "KEYWORD");
            clearState();
            save();
            send(WaMessenger.text(waId(), "You have been unsubscribed. Reply START to subscribe again."), "[opt-out confirmation]");
            return;
        }
        if ("N".equals(contact.getString("optInStatus"))) {
            if (!WaUtil.propList("bot.optin.keywords").contains(lower)) {
                return;
            }
            WaContacts.setConsent(contact, true, "KEYWORD");
        }

        // --- human agent has the chat ---
        if ("Y".equals(contact.getString("botPaused"))) {
            save();
            return;
        }

        boolean restart = WaUtil.propList("bot.restart.keywords").contains(lower);
        if (restart) {
            clearState();
        }

        // --- answer to a waiting node ---
        if (!restart && UtilValidate.isNotEmpty(contact.getString("currentNodeId"))) {
            GenericValue node = node(contact.getString("currentFlowId"), contact.getString("currentNodeId"));
            if (node != null && handleAnswer(node, input, replyId)) {
                save();
                return;
            }
            if (node == null) {
                clearState();
            }
        }

        // --- AI agent answers anything that is not a flow keyword (greetings still open the main menu) ---
        if (findFlowByKeyword(lower) == null && WaAgentAi.active(delegator, channel.getString("tenantId"))) {
            GenericValue def = defaultFlow();
            if (def == null || !WaAgentAi.isGreeting(lower)) {
                agentTurn(input, null, def);
                save();
                return;
            }
        }

        // --- start a flow by keyword or default ---
        GenericValue flow = findFlow(lower);
        if (flow == null) {
            save();
            return;
        }
        startRun(flow.getString("flowId"));
        run(flow.getString("flowId"), flow.getString("startNodeId"));
        save();
    }

    /** @return true if the input was consumed by the waiting node */
    private boolean handleAnswer(GenericValue node, String input, String replyId) throws GenericEntityException {
        String type = node.getString("nodeTypeId");
        String flowId = node.getString("flowId");
        if ("WA_NODE_BUTTONS".equals(type) || "WA_NODE_LIST".equals(type)) {
            GenericValue opt = matchOption(node, input, replyId);
            if (opt != null) {
                vars.put("lastChoice", opt.getString("optionLabel"));
                String target = opt.getString("targetNodeId");
                if (UtilValidate.isEmpty(target)) {
                    endRun(flowId, "COMPLETED");
                    clearState();
                } else {
                    run(flowId, target);
                }
                return true;
            }
            // Typed a flow keyword (e.g. "hi")? let the caller start that flow instead.
            if (findFlowByKeyword(input.toLowerCase(Locale.ROOT)) != null) {
                clearState();
                return false;
            }
            // a question typed while the menu waits: the AI agent answers it and the menu stays open
            GenericValue agentCfg = WaAgentAi.settings(delegator, channel.getString("tenantId"));
            if (agentCfg != null && !"N".equals(agentCfg.getString("answerInMenus")) && input.length() > 2
                    && WaAgentAi.active(delegator, channel.getString("tenantId"))) {
                agentTurn(input, node, defaultFlow());
                return true;
            }
            send(WaMessenger.text(waId(), WaUtil.prop("bot.invalid.choice.text", "Please choose one of the options.")), "[invalid choice]");
            run(flowId, node.getString("nodeId"));
            return true;
        }
        if ("WA_NODE_ASK".equals(type)) {
            String regex = node.getString("validationRegex");
            if (UtilValidate.isNotEmpty(regex) && !safeMatches(regex, input)) {
                send(WaMessenger.text(waId(), "That doesn't look right. " + render(node.getString("messageText"))), "[validation failed]");
                return true;
            }
            String var = node.getString("saveAsVariable");
            vars.put(UtilValidate.isEmpty(var) ? node.getString("nodeId") : var, input);
            String next = node.getString("nextNodeId");
            if (UtilValidate.isEmpty(next)) {
                endRun(flowId, "COMPLETED");
                clearState();
            } else {
                run(flowId, next);
            }
            return true;
        }
        clearState();
        return false;
    }

    private GenericValue matchOption(GenericValue node, String input, String replyId) throws GenericEntityException {
        List<GenericValue> options = options(node);
        if (UtilValidate.isNotEmpty(replyId) && replyId.startsWith(OPT_PREFIX)) {
            String[] parts = replyId.split("\\|");
            if (parts.length == 3 && parts[1].equals(node.getString("nodeId"))) {
                for (GenericValue o : options) {
                    if (parts[2].equals(o.getString("optionSeqId"))) {
                        return o;
                    }
                }
            }
        }
        String lower = input.toLowerCase(Locale.ROOT);
        for (int i = 0; i < options.size(); i++) {
            GenericValue o = options.get(i);
            if (lower.equals(String.valueOf(i + 1))
                    || lower.equalsIgnoreCase(o.getString("optionLabel"))
                    || WaUtil.splitCsv(o.getString("matchKeywords")).contains(lower)) {
                return o;
            }
        }
        return null;
    }

    /** Execute nodes starting at nodeId until one needs user input. */
    private void run(String flowId, String nodeId) throws GenericEntityException {
        int maxSteps = WaUtil.propInt("bot.max.auto.steps", 10);
        String curFlow = flowId;
        String cur = nodeId;
        for (int step = 0; step < maxSteps && UtilValidate.isNotEmpty(cur); step++) {
            sendingFlow = curFlow;
            GenericValue node = node(curFlow, cur);
            if (node == null) {
                Debug.logWarning("Flow " + curFlow + " has no node " + cur, MODULE);
                clearState();
                return;
            }
            String type = node.getString("nodeTypeId");
            String text = render(node.getString("messageText"));
            switch (type == null ? "" : type) {
            case "WA_NODE_TEXT":
                send(WaMessenger.text(waId(), text), text);
                cur = node.getString("nextNodeId");
                break;
            case "WA_NODE_IMAGE":
                send(WaMessenger.image(waId(), node.getString("mediaUrl"), text), "[image] " + (text == null ? "" : text));
                cur = node.getString("nextNodeId");
                break;
            case "WA_NODE_BUTTONS":
            case "WA_NODE_LIST":
                sendChoice(node, text);
                wait(curFlow, cur);
                return;
            case "WA_NODE_ASK":
                send(WaMessenger.text(waId(), text), text);
                wait(curFlow, cur);
                return;
            case "WA_NODE_HANDOFF":
                if (UtilValidate.isNotEmpty(text)) {
                    send(WaMessenger.text(waId(), text), text);
                }
                contact.set("botPaused", "Y");
                contact.set("chatStatus", "OPEN");
                endRun(curFlow, "HANDOFF");
                clearState();
                return;
            case "WA_NODE_ZOHO":
                // call the workspace's Zoho account; continue on the "found/done" or the "not found/failed" arrow
                ZohoActions.Result zr = ZohoActions.run(delegator, channel.getString("tenantId"), node, vars, contact);
                vars.remove("zohoError");
                vars.putAll(zr.vars);
                cur = zr.ok ? node.getString("nextNodeId") : node.getString("failNodeId");
                if (UtilValidate.isEmpty(cur)) {
                    endRun(curFlow, "COMPLETED");
                    clearState();
                    return;
                }
                break;
            case "WA_NODE_GOTO_FLOW":
                GenericValue target = EntityQuery.use(delegator).from("WaFlow")
                        .where("flowId", node.getString("targetFlowId")).queryOne();
                if (target == null || !channel.getString("tenantId").equals(target.getString("tenantId"))) {
                    clearState();
                    return;
                }
                endRun(curFlow, "COMPLETED");
                curFlow = target.getString("flowId");
                cur = target.getString("startNodeId");
                startRun(curFlow);
                break;
            case "WA_NODE_END":
            default:
                if (UtilValidate.isNotEmpty(text)) {
                    send(WaMessenger.text(waId(), text), text);
                }
                endRun(curFlow, "COMPLETED");
                clearState();
                return;
            }
        }
        if (UtilValidate.isEmpty(cur)) {
            endRun(curFlow, "COMPLETED");
        }
        clearState();
    }

    // ------------------------------------------------------------------ AI agent
    private GenericValue defaultFlow() throws GenericEntityException {
        String def = channel.getString("defaultFlowId");
        if (UtilValidate.isNotEmpty(def)) {
            GenericValue f = EntityQuery.use(delegator).from("WaFlow")
                    .where("flowId", def, "tenantId", channel.getString("tenantId"), "isActive", "Y").queryFirst();
            if (f != null) {
                return f;
            }
        }
        return EntityQuery.use(delegator).from("WaFlow")
                .where("tenantId", channel.getString("tenantId"), "isDefault", "Y", "isActive", "Y").orderBy("flowId").queryFirst();
    }

    /**
     * Let the AI agent answer. menuNode = the button/list step that is waiting (or null); def = main menu flow (or null).
     * If the AI can't be used, the main menu runs, otherwise the chat goes to the team.
     */
    private void agentTurn(String input, GenericValue menuNode, GenericValue def) throws GenericEntityException {
        String tenantId = channel.getString("tenantId");
        String menuText = null;
        if (menuNode != null) {
            StringBuilder mt = new StringBuilder(String.valueOf(render(menuNode.getString("messageText"))));
            for (GenericValue o : options(menuNode)) {
                mt.append("\n- ").append(o.getString("optionLabel"));
            }
            menuText = mt.toString();
        }
        GenericValue tenant = EntityQuery.use(delegator).from("WaTenant").where("tenantId", tenantId).cache().queryOne();
        String business = tenant == null ? null : tenant.getString("tenantName");
        WaAgentAi.Answer a = WaAgentAi.answer(delegator, tenantId, business, contact,
                WaAgentAi.history(delegator, contact, null), input, menuText, def != null);
        GenericValue cfg = WaAgentAi.settings(delegator, tenantId);
        String handoffText = cfg == null || UtilValidate.isEmpty(cfg.getString("handoffMessage")) ? WaAgentAi.DEFAULT_HANDOFF
                : render(cfg.getString("handoffMessage"));
        if (a.error != null) {
            Debug.logWarning("AI agent could not answer for " + tenantId + ": " + a.error, MODULE);
            if (menuNode != null) {
                run(menuNode.getString("flowId"), menuNode.getString("nodeId"));
            } else if (def != null) {
                startRun(def.getString("flowId"));
                run(def.getString("flowId"), def.getString("startNodeId"));
            } else {
                handoff(handoffText, "AI agent could not answer: " + a.error);
            }
            return;
        }
        switch (a.action) {
        case "menu":
            if (UtilValidate.isNotEmpty(a.reply)) {
                sendAs(WaMessenger.text(waId(), a.reply), a.reply, "AI");
            }
            if (menuNode != null) {
                run(menuNode.getString("flowId"), menuNode.getString("nodeId"));
            } else if (def != null) {
                clearState();
                startRun(def.getString("flowId"));
                run(def.getString("flowId"), def.getString("startNodeId"));
            }
            break;
        case "handoff":
            handoff(UtilValidate.isNotEmpty(a.reply) ? a.reply : handoffText,
                    "AI agent handed over" + (UtilValidate.isNotEmpty(a.reason) ? ": " + a.reason : ""));
            break;
        default:
            sendAs(WaMessenger.text(waId(), a.reply), a.reply, "AI");
        }
    }

    private void handoff(String text, String note) {
        if (UtilValidate.isNotEmpty(text)) {
            sendAs(WaMessenger.text(waId(), text), text, "AI");
        }
        contact.set("botPaused", "Y");
        contact.set("chatStatus", "OPEN");
        clearState();
        WaCrmEvents.addSystemNote(delegator, channel.getString("tenantId"), contact.getString("contactId"), "AI", note);
    }

    // ------------------------------------------------------------------ flow runs (analytics)
    private String sendingFlow;

    private void startRun(String flowId) {
        sendingFlow = flowId;
        try {
            delegator.create("WaFlowRun", org.apache.ofbiz.base.util.UtilMisc.toMap("runId", delegator.getNextSeqId("WaFlowRun"),
                    "tenantId", channel.getString("tenantId"), "flowId", flowId, "contactId", contact.getString("contactId"),
                    "outcome", "STARTED", "startedDate", org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp()));
        } catch (GenericEntityException e) {
            Debug.logWarning(e, "Could not record flow run", MODULE);
        }
    }

    private void endRun(String flowId, String outcome) {
        try {
            GenericValue run = EntityQuery.use(delegator).from("WaFlowRun")
                    .where("contactId", contact.getString("contactId"), "flowId", flowId, "outcome", "STARTED")
                    .orderBy("-startedDate").queryFirst();
            if (run != null) {
                run.set("outcome", outcome);
                run.set("endedDate", org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp());
                run.store();
            }
        } catch (GenericEntityException e) {
            Debug.logWarning(e, "Could not record flow run end", MODULE);
        }
    }

    private void sendChoice(GenericValue node, String text) throws GenericEntityException {
        List<String[]> opts = new ArrayList<>();
        for (GenericValue o : options(node)) {
            opts.add(new String[] {OPT_PREFIX + node.getString("nodeId") + "|" + o.getString("optionSeqId"),
                    o.getString("optionLabel"), o.getString("optionDescription")});
        }
        String header = render(node.getString("headerText"));
        String footer = render(node.getString("footerText"));
        ObjectNode payload;
        // WhatsApp allows max 3 reply buttons; fall back to a list menu automatically
        if ("WA_NODE_BUTTONS".equals(node.getString("nodeTypeId")) && opts.size() <= 3 && !opts.isEmpty()) {
            payload = WaMessenger.buttons(waId(), header, text, footer, opts);
        } else if (!opts.isEmpty()) {
            payload = WaMessenger.list(waId(), header, text, footer, node.getString("buttonLabel"), opts);
        } else {
            payload = WaMessenger.text(waId(), text);
        }
        StringBuilder log = new StringBuilder(text == null ? "" : text);
        for (String[] o : opts) {
            log.append("\n [").append(o[1]).append(']');
        }
        send(payload, log.toString());
    }

    // ------------------------------------------------------------------ flow lookup
    private GenericValue findFlow(String lowerInput) throws GenericEntityException {
        GenericValue byKeyword = findFlowByKeyword(lowerInput);
        if (byKeyword != null) {
            return byKeyword;
        }
        String def = channel.getString("defaultFlowId");
        if (UtilValidate.isNotEmpty(def)) {
            GenericValue f = EntityQuery.use(delegator).from("WaFlow")
                    .where("flowId", def, "tenantId", channel.getString("tenantId"), "isActive", "Y").queryFirst();
            if (f != null) {
                return f;
            }
        }
        return EntityQuery.use(delegator).from("WaFlow")
                .where("tenantId", channel.getString("tenantId"), "isDefault", "Y", "isActive", "Y").orderBy("flowId").queryFirst();
    }

    private GenericValue findFlowByKeyword(String lowerInput) throws GenericEntityException {
        if (UtilValidate.isEmpty(lowerInput)) {
            return null;
        }
        List<GenericValue> flows = EntityQuery.use(delegator).from("WaFlow")
                .where("tenantId", channel.getString("tenantId"), "isActive", "Y").orderBy("flowId").cache().queryList();
        for (GenericValue f : flows) {
            if (WaUtil.splitCsv(f.getString("triggerKeywords")).contains(lowerInput)) {
                return f;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers
    private GenericValue node(String flowId, String nodeId) throws GenericEntityException {
        if (UtilValidate.isEmpty(flowId) || UtilValidate.isEmpty(nodeId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("WaFlowNode").where("flowId", flowId, "nodeId", nodeId).queryOne();
    }

    private List<GenericValue> options(GenericValue node) throws GenericEntityException {
        return EntityQuery.use(delegator).from("WaFlowNodeOption")
                .where("flowId", node.getString("flowId"), "nodeId", node.getString("nodeId"))
                .orderBy("optionSeqId").queryList();
    }

    private void wait(String flowId, String nodeId) {
        contact.set("currentFlowId", flowId);
        contact.set("currentNodeId", nodeId);
    }

    private void clearState() {
        contact.set("currentFlowId", null);
        contact.set("currentNodeId", null);
    }

    /** Persist only the fields the bot owns, on a fresh copy, so agent/other updates are never overwritten. */
    private void save() throws GenericEntityException {
        WaUtil.writeVars(contact, vars);
        GenericValue fresh = EntityQuery.use(delegator).from("WaContact").where("contactId", contact.getString("contactId")).queryOne();
        if (fresh == null) {
            return;
        }
        for (String f : BOT_FIELDS) {
            fresh.set(f, contact.get(f));
        }
        fresh.store();
    }

    private static final String[] BOT_FIELDS = {"currentFlowId", "currentNodeId", "sessionData", "optInStatus", "botPaused",
        "optInDate", "optOutDate", "optSource", "chatStatus"};

    private String render(String s) {
        return WaUtil.render(s, contact, vars);
    }

    private String waId() {
        return contact.getString("waId");
    }

    private void sendAs(ObjectNode payload, String logText, String sentBy) {
        WaMessenger.SendResult r = WaMessenger.send(delegator, channel, contact, payload, logText, sentBy, locale);
        if (!r.isOk()) {
            Debug.logWarning("AI reply to " + waId() + " failed: " + r.getError(), MODULE);
        }
    }

    private void send(ObjectNode payload, String logText) {
        String flowId = sendingFlow != null ? sendingFlow : contact.getString("currentFlowId");
        WaMessenger.SendResult r = WaMessenger.send(delegator, channel, contact, payload, logText, "BOT", locale,
                flowId == null ? null : java.util.Map.of("flowId", flowId));
        if (!r.isOk()) {
            Debug.logWarning("Bot reply to " + waId() + " failed: " + r.getError(), MODULE);
        }
    }

    /** Tenant-written regex on customer input: cap sizes and abort after 100 ms (ReDoS guard). */
    private static boolean safeMatches(String regex, String input) {
        if (regex.length() > 300 || input.length() > 1000) {
            return false;
        }
        try {
            long deadline = System.nanoTime() + 100_000_000L;
            return Pattern.compile(regex).matcher(new DeadlineCharSequence(input, deadline)).matches();
        } catch (PatternSyntaxException e) {
            return true;
        } catch (IllegalStateException timeout) {
            Debug.logWarning("Validation regex timed out: " + regex, MODULE);
            return false;
        }
    }

    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadline;

        DeadlineCharSequence(CharSequence inner, long deadline) {
            this.inner = inner;
            this.deadline = deadline;
        }
        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("regex timeout");
            }
            return inner.charAt(index);
        }
        @Override
        public int length() {
            return inner.length();
        }
        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadline);
        }
        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
