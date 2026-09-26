package com.msoftdynamic.whatsapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.LocalDispatcher;

/** AI Agent page: settings, knowledge sources (file, web page, note) and the test chat. */
public final class WaAgentEvents {
    private static final String MODULE = WaAgentEvents.class.getName();

    private WaAgentEvents() { }

    private static Delegator del(HttpServletRequest request) {
        return (Delegator) request.getAttribute("delegator");
    }

    private static String p(HttpServletRequest request, String name) {
        String v = request.getParameter(name);
        return v == null ? null : v.trim();
    }

    /** POST agentSave: enabled, instructions, handoffMessage, answerInMenus, maxPerDay (owner) */
    public static String agentSave(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            if (w == null || !w.owner) {
                return error(response, 403, "Only the workspace owner can change the AI agent.");
            }
            GenericValue a = WaAgentAi.settings(delegator, w.tenantId);
            if (a == null) {
                a = delegator.makeValue("WaAgent", UtilMisc.toMap("tenantId", w.tenantId, "answerInMenus", "Y"));
            }
            if (request.getParameter("enabled") != null) {
                boolean on = "Y".equals(p(request, "enabled"));
                WaFlowAi.Key k = WaFlowAi.key(delegator, w.tenantId);
                if (on && (k == null || !k.own)) {
                    return error(response, 400, "Add your Claude or OpenAI key in Settings > AI assistant first.");
                }
                a.set("enabled", on ? "Y" : "N");
            }
            if (request.getParameter("instructions") != null) {
                a.set("instructions", WaAgentAi.cut(p(request, "instructions"), 4000));
            }
            if (request.getParameter("handoffMessage") != null) {
                a.set("handoffMessage", WaAgentAi.cut(p(request, "handoffMessage"), 1000));
            }
            if (request.getParameter("answerInMenus") != null) {
                a.set("answerInMenus", "Y".equals(p(request, "answerInMenus")) ? "Y" : "N");
            }
            if (request.getParameter("maxPerDay") != null) {
                long max;
                try {
                    max = Long.parseLong(p(request, "maxPerDay"));
                } catch (NumberFormatException e) {
                    return error(response, 400, "Daily limit must be a number.");
                }
                if (max < 10 || max > 100000) {
                    return error(response, 400, "Daily limit must be between 10 and 100,000 answers.");
                }
                a.set("maxPerDay", max);
            }
            a.set("updatedBy", w.userId);
            a.set("updatedDate", UtilDateTime.nowTimestamp());
            delegator.createOrStore(a);
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true).put("enabled", "Y".equals(a.getString("enabled"))));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not save.");
        }
    }

    private static String limitCheck(Delegator delegator, String tenantId) throws Exception {
        if (EntityQuery.use(delegator).from("WaKbSource").where("tenantId", tenantId).queryCount() >= WaKnowledge.maxSources()) {
            return "You can keep up to " + WaKnowledge.maxSources() + " knowledge sources. Remove one first.";
        }
        return null;
    }

    private static GenericValue newSource(Delegator delegator, WaCrmEvents.Who w, String type, String title) {
        return delegator.makeValue("WaKbSource", UtilMisc.toMap("sourceId", delegator.getNextSeqId("WaKbSource"), "tenantId", w.tenantId,
                "sourceType", type, "title", title == null ? null : WaAgentAi.cut(title, 100), "statusId", "PROCESSING",
                "createdBy", w.userId, "createdDate", UtilDateTime.nowTimestamp()));
    }

    /** POST kbUpload?fileName=... with the raw file as the request body (PDF, DOCX, TXT, CSV, MD, HTML; max 10 MB). */
    public static String kbUpload(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            String lim = limitCheck(delegator, w.tenantId);
            if (lim != null) {
                return error(response, 400, lim);
            }
            String name = p(request, "fileName");
            if (UtilValidate.isEmpty(name)) {
                return error(response, 400, "File name missing.");
            }
            name = name.replaceAll("[\\\\/]", "_");
            if (request.getContentLengthLong() > WaKnowledge.MAX_FILE_BYTES) {
                return error(response, 400, "The file is too big. The limit is 10 MB.");
            }
            byte[] data;
            try (InputStream in = request.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > WaKnowledge.MAX_FILE_BYTES) {
                        return error(response, 400, "The file is too big. The limit is 10 MB.");
                    }
                }
                data = out.toByteArray();
            }
            String text;
            try {
                text = WaKnowledge.extractFile(name, data);
            } catch (WaKnowledge.KbException e) {
                return error(response, 400, e.getMessage());
            }
            GenericValue src = newSource(delegator, w, "FILE", name);
            src.create();
            try {
                WaKnowledge.store(delegator, src, name, text, 1);
            } catch (WaKnowledge.KbException e) {
                delegator.removeByAnd("WaKbSource", UtilMisc.toMap("sourceId", src.getString("sourceId")));
                return error(response, 400, e.getMessage());
            }
            return json(response, 200, sourceJson(src).put("ok", true));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not read the file.");
        }
    }

    /** POST kbAddUrl: url, pages (1-30). Read in the background; the page polls kbList. */
    public static String kbAddUrl(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            String lim = limitCheck(delegator, w.tenantId);
            if (lim != null) {
                return error(response, 400, lim);
            }
            java.net.URI u;
            try {
                u = WaKnowledge.checkUrl(p(request, "url"));
            } catch (WaKnowledge.KbException e) {
                return error(response, 400, e.getMessage());
            }
            long pages = 1;
            try {
                pages = Math.max(1, Math.min(Long.parseLong(String.valueOf(p(request, "pages"))), WaUtil.propInt("ai.kb.max.pages", 30)));
            } catch (NumberFormatException ignore) {
                // one page
            }
            GenericValue src = newSource(delegator, w, "URL", null);
            src.set("url", u.toString());
            src.set("crawlPages", pages);
            src.create();
            dispatcher.runAsync("waKbReadUrl", UtilMisc.toMap("sourceId", src.getString("sourceId")), false);
            return json(response, 200, sourceJson(src).put("ok", true));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not add the link.");
        }
    }

    /** POST kbAddText: title, text, sourceId (edit an existing note) */
    public static String kbAddText(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            String title = p(request, "title");
            String text = WaKnowledge.clean(request.getParameter("text"));
            if (UtilValidate.isEmpty(title)) {
                return error(response, 400, "Give the note a title, e.g. \"Store timings\" or \"FAQ\".");
            }
            if (text.length() < 10) {
                return error(response, 400, "Write a little more text for the AI to use.");
            }
            if (text.length() > 200_000) {
                return error(response, 400, "That is too long for one note (max 200,000 characters). Split it or upload a file.");
            }
            GenericValue src;
            String id = p(request, "sourceId");
            if (UtilValidate.isNotEmpty(id)) {
                src = EntityQuery.use(delegator).from("WaKbSource").where("sourceId", id).queryOne();
                if (src == null || !w.tenantId.equals(src.getString("tenantId")) || !"TEXT".equals(src.getString("sourceType"))) {
                    return error(response, 404, "Note not found.");
                }
                src.set("title", WaAgentAi.cut(title, 100));
            } else {
                String lim = limitCheck(delegator, w.tenantId);
                if (lim != null) {
                    return error(response, 400, lim);
                }
                src = newSource(delegator, w, "TEXT", title);
                src.create();
            }
            try {
                WaKnowledge.store(delegator, src, title, text, 1);
            } catch (WaKnowledge.KbException e) {
                if (UtilValidate.isEmpty(id)) {
                    delegator.removeByAnd("WaKbSource", UtilMisc.toMap("sourceId", src.getString("sourceId")));
                }
                return error(response, 400, e.getMessage());
            }
            return json(response, 200, sourceJson(src).put("ok", true));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not save the note.");
        }
    }

    private static GenericValue own(Delegator delegator, WaCrmEvents.Who w, String sourceId) throws Exception {
        if (w == null || UtilValidate.isEmpty(sourceId)) {
            return null;
        }
        GenericValue s = EntityQuery.use(delegator).from("WaKbSource").where("sourceId", sourceId).queryOne();
        return s != null && w.tenantId.equals(s.getString("tenantId")) ? s : null;
    }

    /** POST kbDelete sourceId / kbRefresh sourceId (web pages are read again) */
    public static String kbDelete(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            GenericValue s = own(delegator, w, p(request, "sourceId"));
            if (s == null) {
                return error(response, 404, "Source not found.");
            }
            delegator.removeByAnd("WaKbChunk", UtilMisc.toMap("sourceId", s.getString("sourceId")));
            s.remove();
            WaKnowledge.invalidate(w.tenantId);
            return json(response, 200, WaUtil.JSON.createObjectNode().put("ok", true));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not remove it.");
        }
    }

    public static String kbRefresh(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            GenericValue s = own(delegator, w, p(request, "sourceId"));
            if (s == null || !"URL".equals(s.getString("sourceType"))) {
                return error(response, 404, "Web source not found.");
            }
            s.set("statusId", "PROCESSING");
            s.set("errorText", null);
            s.store();
            dispatcher.runAsync("waKbReadUrl", UtilMisc.toMap("sourceId", s.getString("sourceId")), false);
            return json(response, 200, sourceJson(s).put("ok", true));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not refresh.");
        }
    }

    /** GET kbView?sourceId= : the stored text (for checking what the AI reads, and editing notes) */
    public static String kbView(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            GenericValue s = own(delegator, w, p(request, "sourceId"));
            if (s == null) {
                return error(response, 404, "Source not found.");
            }
            StringBuilder sb = new StringBuilder();
            boolean more = false;
            for (GenericValue c : EntityQuery.use(delegator).from("WaKbChunk").where("sourceId", s.getString("sourceId")).orderBy("seqNum").queryList()) {
                if (sb.length() > 150_000) {
                    more = true;
                    break;
                }
                sb.append(sb.length() == 0 ? "" : "\n\n").append(c.getString("content"));
            }
            return json(response, 200, sourceJson(s).put("ok", true).put("text", sb.toString()).put("truncated", more));
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not load it.");
        }
    }

    /** GET kbList: status of all sources (polled while web pages are being read) */
    public static String kbList(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            ObjectNode out = WaUtil.JSON.createObjectNode().put("ok", true);
            ArrayNode arr = out.putArray("sources");
            for (GenericValue s : EntityQuery.use(delegator).from("WaKbSource").where("tenantId", w.tenantId).orderBy("-createdDate").queryList()) {
                arr.add(sourceJson(s));
            }
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not load.");
        }
    }

    /** POST agentTest: message, history (JSON [[role, text], ...]) - answers like the bot would, sends nothing to WhatsApp */
    public static String agentTest(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = del(request);
        try {
            WaCrmEvents.Who w = WaCrmEvents.who(request);
            if (w == null) {
                return error(response, 403, "Please log in to a workspace.");
            }
            String msg = p(request, "message");
            if (UtilValidate.isEmpty(msg)) {
                return error(response, 400, "Type a question.");
            }
            List<String[]> hist = new ArrayList<>();
            try {
                JsonNode h = WaUtil.JSON.readTree(String.valueOf(request.getParameter("history")));
                for (JsonNode t : h) {
                    if (hist.size() < 12 && t.size() == 2) {
                        hist.add(new String[] {"user".equals(t.get(0).asText()) ? "user" : "assistant", WaAgentAi.cut(t.get(1).asText(""), 1200)});
                    }
                }
            } catch (Exception ignore) {
                // no history
            }
            GenericValue tenant = EntityQuery.use(delegator).from("WaTenant").where("tenantId", w.tenantId).queryOne();
            boolean hasMenu = EntityQuery.use(delegator).from("WaFlow").where("tenantId", w.tenantId, "isDefault", "Y", "isActive", "Y").queryCount() > 0;
            WaAgentAi.Answer a = WaAgentAi.answer(delegator, w.tenantId, tenant == null ? null : tenant.getString("tenantName"), null, hist, msg, null, hasMenu);
            ObjectNode out = WaUtil.JSON.createObjectNode().put("ok", a.error == null).put("action", a.action).put("reply", a.reply)
                    .put("reason", a.reason).put("noKnowledge", a.noKnowledge);
            if (a.error != null) {
                out.put("error", a.error);
            }
            GenericValue cfg = WaAgentAi.settings(delegator, w.tenantId);
            if ("handoff".equals(a.action) && UtilValidate.isEmpty(a.reply)) {
                String h = cfg == null || UtilValidate.isEmpty(cfg.getString("handoffMessage")) ? WaAgentAi.DEFAULT_HANDOFF : cfg.getString("handoffMessage");
                out.put("reply", WaUtil.render(h, delegator.makeValue("WaContact", UtilMisc.toMap("waId", "919800000000")), null));
            }
            ArrayNode src = out.putArray("sources");
            a.sources.forEach(src::add);
            return json(response, 200, out);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return error(response, 500, "Could not test the agent.");
        }
    }

    // ------------------------------------------------------------------ helpers
    static ObjectNode sourceJson(GenericValue s) {
        ObjectNode o = WaUtil.JSON.createObjectNode();
        o.put("sourceId", s.getString("sourceId")).put("type", s.getString("sourceType")).put("title", s.getString("title"))
                .put("url", s.getString("url")).put("status", s.getString("statusId")).put("error", s.getString("errorText"))
                .put("chars", s.get("charCount") == null ? 0 : s.getLong("charCount"))
                .put("pages", s.get("pageCount") == null ? 0 : s.getLong("pageCount"))
                .put("synced", WaCrmEvents.fmt(s.getTimestamp("syncedDate")));
        return o;
    }

    private static String json(HttpServletResponse response, int status, JsonNode body) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(WaUtil.JSON.writeValueAsString(body));
            response.getWriter().flush();
        } catch (IOException e) {
            Debug.logError(e, MODULE);
        }
        return "none";
    }

    private static String error(HttpServletResponse response, int status, String msg) {
        return json(response, status, WaUtil.JSON.createObjectNode().put("ok", false).put("error", msg));
    }
}
