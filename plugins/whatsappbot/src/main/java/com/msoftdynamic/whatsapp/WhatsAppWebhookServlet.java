package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.webapp.WebAppUtil;

/**
 * Meta WhatsApp Cloud API webhook: https://flolink.ai/webhook
 * GET  = subscription verification (hub.challenge)
 * POST = events. Signature is verified on the raw body, payload is stored,
 *        200 is returned at once and processing happens asynchronously.
 */
public class WhatsAppWebhookServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String MODULE = WhatsAppWebhookServlet.class.getName();
    private static final int MAX_BODY = 2 * 1024 * 1024;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String mode = req.getParameter("hub.mode");
        String token = req.getParameter("hub.verify_token");
        String challenge = req.getParameter("hub.challenge");
        String expected = WaUtil.prop("webhook.verify.token", "");
        if ("subscribe".equals(mode) && UtilValidate.isNotEmpty(expected) && WaUtil.constantTimeEquals(expected, token)
                && challenge != null && challenge.matches("[0-9A-Za-z_-]{1,200}")) {
            resp.setContentType("text/plain");
            resp.getWriter().write(challenge);
            return;
        }
        resp.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        byte[] raw = (byte[]) req.getAttribute(WaRawBodyFilter.RAW_BODY_ATTR);
        if (raw == null) {
            try (InputStream in = req.getInputStream()) {
                raw = in.readNBytes(MAX_BODY + 1);
            }
        }
        if (raw.length > MAX_BODY) {
            resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        String secret = WaUtil.prop("meta.app.secret", "");
        boolean sigValid = false;
        String header = req.getHeader("X-Hub-Signature-256");
        if (UtilValidate.isNotEmpty(secret) && header != null && header.startsWith("sha256=")) {
            sigValid = WaUtil.constantTimeEquals(header.substring(7), WaUtil.hmacSha256Hex(secret, raw));
        }
        if (!sigValid && WaUtil.propTrue("webhook.require.signature")) {
            Debug.logWarning("Rejected webhook with invalid/missing signature from " + req.getRemoteAddr(), MODULE);
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String body = new String(raw, StandardCharsets.UTF_8);
        Delegator delegator = WebAppUtil.getDelegator(getServletContext());
        LocalDispatcher dispatcher = WebAppUtil.getDispatcher(getServletContext());
        try {
            JsonNode root = WaUtil.JSON.readTree(body);
            String pnid = root.path("entry").path(0).path("changes").path(0).path("value")
                    .path("metadata").path("phone_number_id").asText(null);
            String logId = delegator.getNextSeqId("WaWebhookLog");
            GenericValue log = delegator.makeValue("WaWebhookLog");
            log.set("webhookLogId", logId);
            log.set("phoneNumberId", pnid);
            log.set("payload", body);
            log.set("signatureValid", sigValid ? "Y" : "N");
            log.set("processedStatus", "NEW");
            log.set("receivedDate", UtilDateTime.nowTimestamp());
            log.create();
            dispatcher.runAsync("waProcessWebhook", Map.of("webhookLogId", logId), false);
        } catch (Exception e) {
            // Still answer 200 for unparsable bodies, otherwise Meta keeps retrying
            Debug.logError(e, "Webhook store failed", MODULE);
        }
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        resp.getWriter().write("EVENT_RECEIVED");
    }
}
