package com.msoftdynamic.whatsapp;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.security.Security;

/**
 * Live inbox: the Inbox page polls inboxPoll every few seconds. It returns a short change signature
 * of the workspace's conversation list (and of the open conversation, incl. delivery statuses).
 * When the signature changes the page re-fetches itself and swaps the list and chat in place.
 */
public final class WaInboxEvents {
    private static final String MODULE = WaInboxEvents.class.getName();

    private WaInboxEvents() { }

    public static String poll(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        GenericValue ul = (GenericValue) request.getSession().getAttribute("userLogin");
        StringBuilder sig = new StringBuilder();
        try {
            if (ul == null) {
                return write(response, 401, "{\"error\":\"login\"}");
            }
            Security security = (Security) request.getAttribute("security");
            boolean admin = security != null && security.hasEntityPermission("WABOT", "_ADMIN", ul);
            List<String> mine = WaUtil.getUserTenantIds(delegator, ul.getString("userLoginId"));
            Object cur = request.getSession().getAttribute("waTenantId");
            String tenantId = cur == null ? null : cur.toString();
            if (!admin && !mine.contains(tenantId)) {
                tenantId = mine.isEmpty() ? "_NO_TENANT_" : mine.get(0);
            }
            List<EntityCondition> conds = new ArrayList<>();
            if (tenantId != null) {
                conds.add(EntityCondition.makeCondition("tenantId", tenantId));
            }
            GenericValue last = EntityQuery.use(delegator).from("WaContact").where(conds)
                    .orderBy("-lastUpdatedStamp").queryFirst();
            long count = EntityQuery.use(delegator).from("WaContact").where(conds).queryCount();
            sig.append(count).append('|').append(stamp(last));

            String contactId = request.getParameter("contactId");
            if (contactId != null && !contactId.isEmpty()) {
                GenericValue c = EntityQuery.use(delegator).from("WaContact").where("contactId", contactId).queryOne();
                if (c != null && (admin || mine.contains(c.getString("tenantId")))) {
                    GenericValue m = EntityQuery.use(delegator).from("WaMessage").where("contactId", contactId)
                            .orderBy("-lastUpdatedStamp").queryFirst();
                    long n = EntityQuery.use(delegator).from("WaMessage").where("contactId", contactId).queryCount();
                    sig.append('|').append(n).append('|').append(stamp(m));
                }
            }
            return write(response, 200, "{\"sig\":\"" + sig + "\"}");
        } catch (Exception e) {
            Debug.logError(e, "inboxPoll failed", MODULE);
            return write(response, 500, "{\"error\":\"failed\"}");
        }
    }

    private static String stamp(GenericValue v) {
        if (v == null) {
            return "0";
        }
        Timestamp t = v.getTimestamp("lastUpdatedStamp");
        return (t == null ? "0" : String.valueOf(t.getTime())) + ":" + v.getPkShortValueString();
    }

    private static String write(HttpServletResponse response, int status, String body) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(body);
            response.getWriter().flush();
        } catch (IOException e) {
            Debug.logError(e, MODULE);
        }
        return "none";
    }
}
