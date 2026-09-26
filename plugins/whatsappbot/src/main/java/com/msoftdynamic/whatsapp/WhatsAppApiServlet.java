package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.webapp.WebAppUtil;

/**
 * Workspace REST API v1, authenticated with an API key (header X-Api-Key or Authorization: Bearer).
 * The endpoints live in {@link WaApi}; this servlet does auth, rate limits and request logs.
 * GET /api/v1/openapi.json is public (the API description for Postman / code generators).
 */
public class WhatsAppApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String MODULE = WhatsAppApiServlet.class.getName();

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Delegator delegator = WebAppUtil.getDelegator(getServletContext());
        LocalDispatcher dispatcher = WebAppUtil.getDispatcher(getServletContext());
        long t0 = System.currentTimeMillis();
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        String method = req.getMethod();
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Headers", "X-Api-Key, Authorization, Content-Type");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS");
        if ("OPTIONS".equals(method)) {
            resp.setStatus(204);
            return;
        }
        resp.setContentType("application/json;charset=UTF-8");
        if ("GET".equals(method) && "/v1/openapi.json".equals(path)) {
            String base = WaUtil.prop("brand.app.url", "");
            if (base.isEmpty()) {
                base = req.getScheme() + "://" + req.getServerName() + ((req.getServerPort() == 80 || req.getServerPort() == 443) ? "" : ":" + req.getServerPort());
            }
            resp.getWriter().write(WaApi.openApi(base.replaceAll("/+$", "") + "/api"));
            return;
        }
        String tenantId = null;
        String keyId = null;
        WaApi.Res res;
        try {
            String key = req.getHeader("X-Api-Key");
            String auth = req.getHeader("Authorization");
            if ((key == null || key.isEmpty()) && auth != null && auth.startsWith("Bearer ")) {
                key = auth.substring(7).trim();
            }
            String[] who = WaApi.authenticate(delegator, key);
            if (who == null) {
                write(resp, WaApi.err(401, "unauthorized", "Invalid or missing API key. Send it in the X-Api-Key header."));
                return;
            }
            tenantId = who[0];
            keyId = who[1];
            int wait = WaApi.rateLimited(keyId);
            if (wait > 0) {
                resp.setHeader("Retry-After", String.valueOf(wait));
                res = WaApi.err(429, "rate_limited", "Too many requests. Try again in " + wait + " seconds.");
            } else {
                byte[] body = null;
                if (!"GET".equals(method)) {
                    byte[] raw = (byte[]) req.getAttribute(WaRawBodyFilter.RAW_BODY_ATTR);
                    if (raw != null) {
                        body = raw;
                    } else {
                        try (InputStream in = req.getInputStream()) {
                            body = in.readNBytes(512 * 1024);
                        }
                    }
                }
                Map<String, String> q = new HashMap<>();
                req.getParameterMap().forEach((k, v) -> q.put(k, v.length == 0 ? null : v[0]));
                res = WaApi.handle(delegator, dispatcher, tenantId, "PUT".equals(method) ? "PATCH" : method, path, q, body);
            }
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            res = WaApi.err(500, "internal_error", "Internal error");
        }
        write(resp, res);
        String error = res.status >= 400 ? res.body.path("error").asText(null) : null;
        String ip = req.getHeader("X-Forwarded-For") != null ? req.getHeader("X-Forwarded-For").split(",")[0].trim() : req.getRemoteAddr();
        WaApi.log(delegator, tenantId, keyId, method, path + (req.getQueryString() == null ? "" : "?" + req.getQueryString()), res.status,
                System.currentTimeMillis() - t0, error, ip);
    }

    private static void write(HttpServletResponse resp, WaApi.Res res) throws IOException {
        resp.setStatus(res.status);
        JsonNode b = res.body;
        resp.getWriter().write(b.toString());
    }
}
