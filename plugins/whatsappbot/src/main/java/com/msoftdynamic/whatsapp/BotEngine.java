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
            contact.set("optInStatus", "N");
            clearState();
            save();
            send(WaMessenger.text(waId(), "You have been unsubscribed. Reply START to subscribe again."), "[opt-out confirmation]");
            return;
        }
        if ("N".equals(contact.getString("optInStatus"))) {
            if (!WaUtil.propList("bot.optin.keywords").contains(lower)) {
                return;
            }
            contact.set("optInStatus", "Y");
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

        // --- start a flow by keyword or default ---
        GenericValue flow = findFlow(lower);
        if (flow == null) {
            save();
            return;
        }
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
                clearState();
                return;
            case "WA_NODE_GOTO_FLOW":
                GenericValue target = EntityQuery.use(delegator).from("WaFlow")
                        .where("flowId", node.getString("targetFlowId")).queryOne();
                if (target == null || !channel.getString("tenantId").equals(target.getString("tenantId"))) {
                    clearState();
                    return;
                }
                curFlow = target.getString("flowId");
                cur = target.getString("startNodeId");
                break;
            case "WA_NODE_END":
            default:
                if (UtilValidate.isNotEmpty(text)) {
                    send(WaMessenger.text(waId(), text), text);
                }
                clearState();
                return;
            }
        }
        clearState();
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

    private static final String[] BOT_FIELDS = {"currentFlowId", "currentNodeId", "sessionData", "optInStatus", "botPaused"};

    private String render(String s) {
        return WaUtil.render(s, contact, vars);
    }

    private String waId() {
        return contact.getString("waId");
    }

    private void send(ObjectNode payload, String logText) {
        WaMessenger.SendResult r = WaMessenger.send(delegator, channel, contact, payload, logText, "BOT", locale);
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
