package com.msoftdynamic.whatsapp;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;

/**
 * Zoho steps a bot flow can run. Each action reads its settings (with {{variables}} filled in),
 * calls the workspace's own Zoho account and returns ok / not-ok plus variables for later messages.
 * On failure {{zohoError}} holds a short reason.
 */
public final class ZohoActions {
    private static final String MODULE = ZohoActions.class.getName();

    private ZohoActions() { }

    /** Catalogue shared with the builder: id, app, label, outputs, labels of the two exits. */
    public static final List<Map<String, Object>> CATALOG = new ArrayList<>();
    private static void def(String id, String app, String label, String ok, String fail, String... outputs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("app", app);
        m.put("label", label);
        m.put("ok", ok);
        m.put("fail", fail);
        m.put("outputs", List.of(outputs));
        CATALOG.add(m);
    }
    static {
        def("crm_find", "crm", "Find contact by WhatsApp number", "Found", "Not found", "crmId", "crmName", "crmEmail", "crmPhone", "crmOwner");
        def("crm_upsert", "crm", "Create or update a lead / contact", "Saved", "Failed", "crmId", "crmAction");
        def("books_invoice", "books", "Invoice status by invoice number", "Found", "Not found", "invoiceNumber", "invoiceStatus", "invoiceTotal", "invoiceBalance", "invoiceDueDate", "invoiceLink", "customerName");
        def("books_balance", "books", "Customer balance and unpaid invoices", "Found", "Not a customer", "customerName", "balanceDue", "unpaidCount", "unpaidInvoices");
        def("inv_stock", "inventory", "Check stock and price of an item", "Found", "Not found", "itemName", "itemSku", "itemRate", "itemStock", "itemList");
        def("inv_order", "inventory", "Sales order status by order number", "Found", "Not found", "orderNumber", "orderStatus", "orderShipped", "orderInvoiced", "orderTotal", "orderDate");
        def("people_balance", "people", "Employee leave balance", "Found", "Not an employee", "employeeName", "leaveBalances");
        def("people_apply", "people", "Apply for leave", "Applied", "Failed", "employeeName", "leaveRequestId");
    }

    public static Map<String, Object> action(String id) {
        for (Map<String, Object> a : CATALOG) {
            if (a.get("id").equals(id)) {
                return a;
            }
        }
        return null;
    }

    public static String appOf(String actionId) {
        Map<String, Object> a = action(actionId);
        return a == null ? null : (String) a.get("app");
    }

    /** Settings problems that block saving a flow (empty list = fine). */
    public static List<String> validate(JsonNode cfg) {
        List<String> errs = new ArrayList<>();
        String id = cfg.path("action").asText("");
        if (action(id) == null) {
            errs.add("choose what the Zoho step should do");
            return errs;
        }
        switch (id) {
        case "crm_find":
        case "crm_upsert":
            if (!cfg.path("module").asText("").matches("[A-Za-z][A-Za-z0-9_]{0,59}")) {
                errs.add("choose the CRM module");
            }
            if ("crm_find".equals(id) && "email".equals(cfg.path("matchBy").asText()) && cfg.path("value").asText("").isBlank()) {
                errs.add("choose the email to search for");
            }
            if ("crm_upsert".equals(id)) {
                boolean any = false;
                boolean lastName = false;
                for (JsonNode f : cfg.path("fields")) {
                    String fn = f.path("field").asText("");
                    if (fn.isEmpty()) {
                        continue;
                    }
                    if (!fn.matches("[A-Za-z][A-Za-z0-9_]{0,99}")) {
                        errs.add("field '" + fn + "' is not a valid CRM field name");
                    }
                    any = true;
                    lastName |= "Last_Name".equals(fn) && !f.path("value").asText("").isBlank();
                }
                if (!any) {
                    errs.add("map at least one CRM field");
                }
                String m = cfg.path("module").asText("");
                if (("Leads".equals(m) || "Contacts".equals(m)) && !lastName) {
                    errs.add("Last_Name is required for " + m + " (e.g. {{name}})");
                }
            }
            break;
        case "books_invoice":
        case "inv_order":
            if (cfg.path("number").asText("").isBlank()) {
                errs.add("choose where the number comes from (e.g. {{invoiceNo}})");
            }
            break;
        case "inv_stock":
            if (cfg.path("query").asText("").isBlank()) {
                errs.add("choose the item to search for (e.g. {{item}})");
            }
            break;
        case "people_apply":
            if (cfg.path("from").asText("").isBlank() || cfg.path("leaveType").asText("").isBlank()) {
                errs.add("set the leave type and the from date");
            }
            break;
        default:
            break;
        }
        return errs;
    }

    // ------------------------------------------------------------------ run
    public static final class Result {
        public final boolean ok;
        public final Map<String, Object> vars = new LinkedHashMap<>();
        Result(boolean ok) {
            this.ok = ok;
        }
    }

    public static Result run(Delegator delegator, String tenantId, GenericValue node, Map<String, Object> vars, GenericValue contact) {
        JsonNode cfg;
        try {
            cfg = WaUtil.JSON.readTree(UtilValidate.isEmpty(node.getString("actionConfig")) ? "{}" : node.getString("actionConfig"));
        } catch (Exception e) {
            cfg = WaUtil.JSON.createObjectNode();
        }
        String id = cfg.path("action").asText("");
        Ctx c = new Ctx(delegator, tenantId, cfg, vars, contact);
        try {
            switch (id) {
            case "crm_find": return crmFind(c);
            case "crm_upsert": return crmUpsert(c);
            case "books_invoice": return booksInvoice(c);
            case "books_balance": return booksBalance(c);
            case "inv_stock": return invStock(c);
            case "inv_order": return invOrder(c);
            case "people_balance": return peopleBalance(c);
            case "people_apply": return peopleApply(c);
            default: return fail("Unknown Zoho action " + id);
            }
        } catch (ZohoClient.ZohoException e) {
            Debug.logWarning("Zoho step " + id + " for tenant " + tenantId + ": " + e.getMessage(), MODULE);
            return fail(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Zoho step " + id + " failed", MODULE);
            return fail("Zoho step failed");
        }
    }

    private static final class Ctx {
        final Delegator delegator;
        final String tenantId;
        final JsonNode cfg;
        final Map<String, Object> vars;
        final GenericValue contact;
        Ctx(Delegator delegator, String tenantId, JsonNode cfg, Map<String, Object> vars, GenericValue contact) {
            this.delegator = delegator;
            this.tenantId = tenantId;
            this.cfg = cfg;
            this.vars = vars;
            this.contact = contact;
        }
        String val(String key) {
            String raw = cfg.path(key).asText("");
            String r = WaUtil.render(raw, contact, vars);
            return r == null ? "" : r.trim();
        }
        String wa() {
            return contact.getString("waId");
        }
        private String bizNumber;
        /** Is {@code stored} (a number from Zoho) this WhatsApp user's number? */
        boolean isMe(String stored) {
            if (bizNumber == null) {
                bizNumber = "";
                try {
                    GenericValue ch = contact.getRelatedOne("WaChannel", true);
                    if (ch != null && ch.getString("displayPhoneNumber") != null) {
                        bizNumber = ch.getString("displayPhoneNumber");
                    }
                } catch (GenericEntityException e) {
                    Debug.logWarning(e, MODULE);
                }
            }
            return ZohoClient.samePhone(stored, wa(), ZohoClient.homeCountryCode(bizNumber, stored));
        }
        GenericValue conn() throws GenericEntityException {
            return ZohoClient.connection(delegator, tenantId);
        }
        JsonNode get(String app, String path, Map<String, String> q) throws ZohoClient.ZohoException, GenericEntityException {
            return ZohoClient.get(delegator, tenantId, app, path, q);
        }
        JsonNode post(String app, String path, Map<String, String> q, JsonNode body) throws ZohoClient.ZohoException, GenericEntityException {
            return ZohoClient.post(delegator, tenantId, app, path, q, body);
        }
    }

    private static Result fail(String why) {
        Result r = new Result(false);
        r.vars.put("zohoError", why == null ? "" : why);
        return r;
    }

    private static Result notFound(String why) {
        return fail(why);
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ CRM
    private static final java.util.regex.Pattern MODULE_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[^@\\s'\\\\]{1,64}@[^@\\s'\\\\]{1,190}\\.[A-Za-z]{2,24}");

    private static Result crmFind(Ctx c) throws Exception {
        String module = c.cfg.path("module").asText("Contacts");
        if (!MODULE_NAME.matcher(module).matches()) {
            return fail("invalid CRM module");
        }
        boolean byEmail = "email".equals(c.cfg.path("matchBy").asText());
        String key = byEmail ? c.val("value").toLowerCase(Locale.ROOT) : ZohoClient.phoneKey(c.wa());
        if (byEmail && !EMAIL.matcher(key).matches()) {
            return notFound("that does not look like an email address");
        }
        if (key.isEmpty()) {
            return notFound("nothing to search for");
        }
        JsonNode rec = null;
        // COQL first (no search-index delay for new records); the Search API normalises phone formats, so it is
        // the fallback when COQL finds nothing or the module lacks these fields.
        String where = byEmail ? "Email = '" + coql(key) + "'" : "(Mobile like '%" + coql(key) + "' or Phone like '%" + coql(key) + "')";
        try {
            ObjectNode body = WaUtil.JSON.createObjectNode();
            body.put("select_query", "select id, Full_Name, Last_Name, Email, Phone, Mobile, Owner from " + module + " where " + where + " limit 5");
            rec = pickCrm(c, c.post("crm", "/crm/v8/coql", null, body).path("data"), byEmail, key);
        } catch (ZohoClient.ZohoException e) {
            if (e.status == 401 || e.status == 403 || e.status == 503) {
                throw e;
            }
        }
        if (rec == null) {
            JsonNode r = c.get("crm", "/crm/v8/" + module + "/search", byEmail ? q("email", key) : q("phone", key));
            rec = pickCrm(c, r.path("data"), byEmail, key);
        }
        if (rec == null) {
            return notFound("no " + module + " record for this " + (byEmail ? "email" : "WhatsApp number"));
        }
        Result res = new Result(true);
        res.vars.put("crmId", rec.path("id").asText());
        JsonNode owner = rec.path("Owner");
        res.vars.put("crmOwner", owner.isObject() ? owner.path("name").asText(owner.path("full_name").asText("")) : owner.asText(""));
        if (byEmail) {
            // someone typed an email: confirm the record exists, but don't reveal its personal details
            res.vars.put("crmName", "");
            res.vars.put("crmEmail", "");
            res.vars.put("crmPhone", "");
            return res;
        }
        String name = rec.path("Full_Name").asText("");
        if (name.isEmpty()) {
            name = (rec.path("First_Name").asText("") + " " + rec.path("Last_Name").asText(rec.path("Name").asText(""))).trim();
        }
        res.vars.put("crmName", name);
        res.vars.put("crmEmail", rec.path("Email").asText(""));
        res.vars.put("crmPhone", rec.path("Mobile").asText(rec.path("Phone").asText("")));
        return res;
    }

    private static JsonNode pickCrm(Ctx c, JsonNode data, boolean byEmail, String key) {
        for (JsonNode d : data) {
            if (byEmail ? key.equalsIgnoreCase(d.path("Email").asText(""))
                    : c.isMe(d.path("Mobile").asText("")) || c.isMe(d.path("Phone").asText(""))) {
                return d;
            }
        }
        return null;
    }

    private static String coql(String s) {
        return s.replace("\\", "").replace("'", "\\'");
    }

    private static Result crmUpsert(Ctx c) throws Exception {
        String module = c.cfg.path("module").asText("Leads");
        if (!MODULE_NAME.matcher(module).matches()) {
            return fail("invalid CRM module");
        }
        ObjectNode rec = WaUtil.JSON.createObjectNode();
        for (JsonNode f : c.cfg.path("fields")) {
            String field = f.path("field").asText("").trim();
            String v = WaUtil.render(f.path("value").asText(""), c.contact, c.vars);
            if (field.matches("[A-Za-z][A-Za-z0-9_]{0,99}") && v != null && !v.trim().isEmpty()) {
                rec.put(field, v.trim());
            }
        }
        if (("Leads".equals(module) || "Contacts".equals(module)) && !rec.hasNonNull("Last_Name")) {
            String n = c.contact.getString("profileName");
            rec.put("Last_Name", UtilValidate.isEmpty(n) ? "+" + c.wa() : n);
        }
        ObjectNode body = WaUtil.JSON.createObjectNode();
        body.putArray("data").add(rec);
        String dup = c.cfg.path("dupField").asText("");
        if (!dup.isEmpty() && rec.hasNonNull(dup)) {
            body.putArray("duplicate_check_fields").add(dup);
        }
        body.putArray("trigger").add("workflow");
        JsonNode r = c.post("crm", "/crm/v8/" + module + "/upsert", null, body);
        JsonNode d = r.path("data").path(0);
        if (!"SUCCESS".equalsIgnoreCase(d.path("code").asText())) {
            String msg = d.path("message").asText("not saved");
            String fld = d.path("details").path("api_name").asText("");
            return fail("Zoho CRM: " + msg + (fld.isEmpty() ? "" : " (" + fld + ")"));
        }
        Result res = new Result(true);
        res.vars.put("crmId", d.path("details").path("id").asText());
        res.vars.put("crmAction", "update".equals(d.path("action").asText()) ? "updated" : "created");
        return res;
    }

    // ------------------------------------------------------------------ Books
    private static String org(Ctx c, String app) throws Exception {
        GenericValue conn = c.conn();
        if (!ZohoClient.hasApp(conn, app)) {
            throw new ZohoClient.ZohoException(ZohoClient.APP_NAMES.get(app) + " is not connected for this workspace.", 401);
        }
        String id = conn.getString("books".equals(app) ? "booksOrgId" : "inventoryOrgId");
        if (UtilValidate.isEmpty(id)) {
            throw new ZohoClient.ZohoException("Choose the " + ZohoClient.APP_NAMES.get(app) + " organization in Settings.", 400);
        }
        return id;
    }

    private static boolean contactMatches(Ctx c, String app, String customerId, String orgId) throws Exception {
        if (UtilValidate.isEmpty(customerId)) {
            return false;
        }
        String base = "books".equals(app) ? "/books/v3" : "/inventory/v1";
        JsonNode ct = c.get(app, base + "/contacts/" + customerId, q("organization_id", orgId)).path("contact");
        if (c.isMe(ct.path("phone").asText("")) || c.isMe(ct.path("mobile").asText(""))) {
            return true;
        }
        for (JsonNode p : ct.path("contact_persons")) {
            if (c.isMe(p.path("phone").asText("")) || c.isMe(p.path("mobile").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private static Result booksInvoice(Ctx c) throws Exception {
        String orgId = org(c, "books");
        String number = c.val("number");
        if (number.isEmpty()) {
            return notFound("no invoice number given");
        }
        JsonNode list = c.get("books", "/books/v3/invoices", q("organization_id", orgId, "invoice_number", number)).path("invoices");
        JsonNode inv = null;
        for (JsonNode i : list) {
            String st = i.path("status").asText("");
            if (number.equalsIgnoreCase(i.path("invoice_number").asText()) && !"draft".equals(st) && !"void".equals(st)) {
                inv = i;
                break;
            }
        }
        // same answer whether it doesn't exist or isn't theirs, so numbers can't be probed
        String nf = "invoice " + number + " was not found for this WhatsApp number";
        if (inv == null) {
            return notFound(nf);
        }
        if (!c.cfg.path("verify").isBoolean() || c.cfg.path("verify").asBoolean()) {
            if (!contactMatches(c, "books", inv.path("customer_id").asText(), orgId)) {
                return notFound(nf);
            }
        }
        String link = inv.path("invoice_url").asText("");
        if (link.isEmpty()) {
            JsonNode full = c.get("books", "/books/v3/invoices/" + inv.path("invoice_id").asText(), q("organization_id", orgId)).path("invoice");
            link = full.path("invoice_url").asText("");
        }
        String cur = currency(inv);
        Result res = new Result(true);
        res.vars.put("invoiceNumber", inv.path("invoice_number").asText());
        res.vars.put("invoiceStatus", status(inv.path("status").asText("")));
        res.vars.put("invoiceTotal", money(cur, inv.path("total").asDouble()));
        res.vars.put("invoiceBalance", money(cur, inv.path("balance").asDouble()));
        res.vars.put("invoiceDueDate", date(inv.path("due_date").asText("")));
        res.vars.put("invoiceLink", link);
        res.vars.put("customerName", inv.path("customer_name").asText(""));
        return res;
    }

    private static JsonNode findByPhone(Ctx c, String app, String orgId) throws Exception {
        String base = "books".equals(app) ? "/books/v3" : "/inventory/v1";
        String key = ZohoClient.phoneKey(c.wa());
        String tail = key.length() > 5 ? key.substring(key.length() - 5) : key;
        // stored numbers may contain spaces or dashes, so also try the last digits and confirm every hit
        for (Map<String, String> query : List.of(q("organization_id", orgId, "phone_contains", key),
                q("organization_id", orgId, "search_text", key), q("organization_id", orgId, "phone_contains", tail),
                q("organization_id", orgId, "mobile_contains", tail))) {
            JsonNode list;
            try {
                list = c.get(app, base + "/contacts", query).path("contacts");
            } catch (ZohoClient.ZohoException e) {
                if (e.status == 400) {
                    continue;  // filter not supported by this app/edition
                }
                throw e;
            }
            for (JsonNode ct : list) {
                if (c.isMe(ct.path("phone").asText("")) || c.isMe(ct.path("mobile").asText(""))) {
                    return ct;
                }
            }
        }
        return null;
    }

    private static Result booksBalance(Ctx c) throws Exception {
        String orgId = org(c, "books");
        JsonNode ct = findByPhone(c, "books", orgId);
        if (ct == null) {
            return notFound("no customer with this WhatsApp number in Zoho Books");
        }
        String cur = currency(ct);
        JsonNode invs = c.get("books", "/books/v3/invoices", q("organization_id", orgId, "customer_id", ct.path("contact_id").asText(),
                "sort_column", "due_date", "sort_order", "A")).path("invoices");
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (JsonNode i : invs) {
            String st = i.path("status").asText("");
            if (i.path("balance").asDouble() > 0 && !"void".equals(st) && !"draft".equals(st)) {
                count++;
                if (lines.size() < 5) {
                    lines.add("• " + i.path("invoice_number").asText() + " — " + money(currency(i).isEmpty() ? cur : currency(i), i.path("balance").asDouble())
                            + (i.path("due_date").asText("").isEmpty() ? "" : ", due " + date(i.path("due_date").asText())));
                }
            }
        }
        Result res = new Result(true);
        res.vars.put("customerName", ct.path("contact_name").asText(""));
        res.vars.put("balanceDue", money(cur, ct.path("outstanding_receivable_amount").asDouble()));
        res.vars.put("unpaidCount", String.valueOf(count));
        res.vars.put("unpaidInvoices", lines.isEmpty() ? "No unpaid invoices 🎉" : String.join("\n", lines));
        return res;
    }

    // ------------------------------------------------------------------ Inventory
    private static Result invStock(Ctx c) throws Exception {
        String orgId = org(c, "inventory");
        String query = c.val("query");
        if (query.isEmpty()) {
            return notFound("no item given");
        }
        JsonNode items = c.get("inventory", "/inventory/v1/items", q("organization_id", orgId, "search_text", query, "per_page", "10")).path("items");
        List<JsonNode> active = new ArrayList<>();
        for (JsonNode i : items) {
            if (!"inactive".equalsIgnoreCase(i.path("status").asText(""))) {
                active.add(i);
            }
        }
        if (active.isEmpty()) {
            return notFound("no item matching " + query);
        }
        JsonNode best = active.get(0);
        for (JsonNode i : active) {
            if (query.equalsIgnoreCase(i.path("sku").asText("")) || query.equalsIgnoreCase(i.path("name").asText(""))) {
                best = i;
                break;
            }
        }
        String cur = currency(best);
        List<String> lines = new ArrayList<>();
        for (JsonNode i : active.subList(0, Math.min(3, active.size()))) {
            lines.add("• " + i.path("name").asText() + (i.path("sku").asText("").isEmpty() ? "" : " (" + i.path("sku").asText() + ")")
                    + " — " + money(currency(i).isEmpty() ? cur : currency(i), i.path("rate").asDouble()) + ", " + stockText(stock(i)));
        }
        Result res = new Result(true);
        res.vars.put("itemName", best.path("name").asText());
        res.vars.put("itemSku", best.path("sku").asText(""));
        res.vars.put("itemRate", money(cur, best.path("rate").asDouble()));
        res.vars.put("itemStock", stockText(stock(best)));
        res.vars.put("itemList", String.join("\n", lines));
        return res;
    }

    private static double stock(JsonNode i) {
        for (String f : new String[] {"available_stock", "actual_available_stock", "stock_on_hand"}) {
            if (i.path(f).isNumber()) {
                return i.path(f).asDouble();
            }
        }
        double sum = 0;
        for (JsonNode l : i.path("locations")) {
            sum += l.path("location_available_stock").asDouble(l.path("location_stock_on_hand").asDouble());
        }
        return sum;
    }

    private static String stockText(double qty) {
        if (qty <= 0) {
            return "out of stock";
        }
        return (qty == Math.rint(qty) ? String.valueOf((long) qty) : String.valueOf(qty)) + " in stock";
    }

    private static Result invOrder(Ctx c) throws Exception {
        String orgId = org(c, "inventory");
        String number = c.val("number");
        if (number.isEmpty()) {
            return notFound("no order number given");
        }
        JsonNode so = null;
        for (JsonNode s : c.get("inventory", "/inventory/v1/salesorders", q("organization_id", orgId, "salesorder_number", number)).path("salesorders")) {
            if (number.equalsIgnoreCase(s.path("salesorder_number").asText())) {
                so = s;
                break;
            }
        }
        String nf = "order " + number + " was not found for this WhatsApp number";
        if (so == null || "void".equals(so.path("status").asText("")) || "draft".equals(so.path("status").asText(""))) {
            return notFound(nf);
        }
        if (!c.cfg.path("verify").isBoolean() || c.cfg.path("verify").asBoolean()) {
            if (!contactMatches(c, "inventory", so.path("customer_id").asText(), orgId)) {
                return notFound(nf);
            }
        }
        Result res = new Result(true);
        res.vars.put("orderNumber", so.path("salesorder_number").asText());
        res.vars.put("orderStatus", status(so.path("order_status").asText(so.path("status").asText(""))));
        res.vars.put("orderShipped", status(so.path("shipped_status").asText("")));
        res.vars.put("orderInvoiced", status(so.path("invoiced_status").asText("")));
        res.vars.put("orderTotal", money(currency(so), so.path("total").asDouble()));
        res.vars.put("orderDate", date(so.path("date").asText("")));
        return res;
    }

    // ------------------------------------------------------------------ People
    private static final class Employee {
        String recordId;
        String name;
        String email;
    }

    private static Employee employee(Ctx c) throws Exception {
        String field = WaUtil.prop("zoho.people.mobile.field", "Mobile");
        if (!field.matches("[A-Za-z][A-Za-z0-9_]{0,59}")) {
            field = "Mobile";
        }
        String key = ZohoClient.phoneKey(c.wa());
        String tail = key.length() > 5 ? key.substring(key.length() - 5) : key;
        for (String text : new String[] {key, tail}) {
            String params = "{searchField:'" + field + "',searchOperator:'Contains',searchText:'" + text.replaceAll("[^0-9]", "") + "'}";
            JsonNode resp = c.get("people", "/people/api/forms/employee/getRecords", q("searchParams", params, "limit", "200")).path("response");
            if (resp.path("status").asInt(0) != 0) {
                String msg = resp.path("errors").path("message").asText(resp.path("message").asText("error"));
                if (!msg.toLowerCase(Locale.ROOT).contains("no record")) {
                    throw new ZohoClient.ZohoException("Zoho People: " + msg, 400);
                }
                continue;
            }
            for (JsonNode rec : resp.path("result")) {
                var it = rec.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    JsonNode f = e.getValue().isArray() ? e.getValue().path(0) : e.getValue();
                    if (c.isMe(f.path(field).asText(""))) {
                        Employee emp = new Employee();
                        emp.recordId = e.getKey();
                        emp.name = (f.path("FirstName").asText("") + " " + f.path("LastName").asText("")).trim();
                        emp.email = f.path("EmailID").asText("");
                        return emp;
                    }
                }
            }
        }
        return null;
    }

    private static JsonNode leaveTypes(Ctx c, Employee emp) throws Exception {
        String user = UtilValidate.isNotEmpty(emp.email) ? emp.email : emp.recordId;
        JsonNode resp = c.get("people", "/people/api/leave/getLeaveTypeDetails", q("userId", user)).path("response");
        if (resp.path("status").asInt(0) != 0) {
            throw new ZohoClient.ZohoException("Zoho People: " + resp.path("errors").path("message").asText(resp.path("message").asText("error")), 400);
        }
        return resp.path("result");
    }

    private static Result peopleBalance(Ctx c) throws Exception {
        Employee emp = employee(c);
        if (emp == null) {
            return notFound("no employee with this WhatsApp number in Zoho People");
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode t : leaveTypes(c, emp)) {
            double bal = t.path("BalanceCount").asDouble();
            String unit = t.path("Unit").asText("Days").toLowerCase(Locale.ROOT);
            lines.add("• " + t.path("Name").asText() + ": " + (bal == Math.rint(bal) ? String.valueOf((long) bal) : String.valueOf(bal)) + " " + unit);
        }
        Result res = new Result(true);
        res.vars.put("employeeName", emp.name);
        res.vars.put("leaveBalances", lines.isEmpty() ? "No leave types found." : String.join("\n", lines));
        return res;
    }

    private static Result peopleApply(Ctx c) throws Exception {
        Employee emp = employee(c);
        if (emp == null) {
            return notFound("no employee with this WhatsApp number in Zoho People");
        }
        String want = c.val("leaveType").toLowerCase(Locale.ROOT);
        String typeId = null;
        String typeName = null;
        for (JsonNode t : leaveTypes(c, emp)) {
            String n = t.path("Name").asText("");
            if (n.equalsIgnoreCase(want) || (typeId == null && !want.isEmpty() && n.toLowerCase(Locale.ROOT).contains(want))) {
                typeId = t.path("Id").asText();
                typeName = n;
                if (n.equalsIgnoreCase(want)) {
                    break;
                }
            }
        }
        if (typeId == null) {
            return fail("unknown leave type '" + c.val("leaveType") + "'");
        }
        LocalDate from = parseDate(c.val("from"));
        LocalDate to = c.val("to").isEmpty() ? from : parseDate(c.val("to"));
        if (from == null || to == null) {
            return fail("could not read the date; please use a format like 14-10-2026");
        }
        if (to.isBefore(from)) {
            return fail("the end date is before the start date");
        }
        DateTimeFormatter out = DateTimeFormatter.ofPattern(WaUtil.prop("zoho.people.date.format", "dd-MMM-yyyy"), Locale.ENGLISH);
        ObjectNode in = WaUtil.JSON.createObjectNode();
        in.put("Employee_ID", emp.recordId);
        in.put("Leavetype", typeId);
        in.put("From", from.format(out));
        in.put("To", to.format(out));
        String reason = c.val("reason");
        if (!reason.isEmpty()) {
            in.put("Reasonforleave", reason);
        }
        JsonNode r = c.post("people", "/people/api/forms/json/leave/insertRecord", q("inputData", in.toString()), null);
        JsonNode resp = r.path("response");
        if (resp.path("status").asInt(1) != 0) {
            String msg = resp.path("errors").path("message").asText(resp.path("message").asText("not applied"));
            return fail("Zoho People: " + msg);
        }
        Result res = new Result(true);
        res.vars.put("employeeName", emp.name);
        res.vars.put("leaveRequestId", resp.path("result").path("pkId").asText(""));
        res.vars.put("leaveType", typeName);
        return res;
    }

    private static final DateTimeFormatter[] DATE_IN = {
        DateTimeFormatter.ISO_LOCAL_DATE,
        fmt("d/M/uuuu"), fmt("d-M-uuuu"), fmt("d.M.uuuu"), fmt("d-MMM-uuuu"), fmt("d MMM uuuu"), fmt("d MMMM uuuu"),
        fmt("MMM d uuuu"), fmt("MMMM d uuuu"), fmt("d/M/uu"), fmt("d-M-uu")
    };

    private static DateTimeFormatter fmt(String p) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH);
    }

    static LocalDate parseDate(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replace(",", "").replaceAll("(?i)(\\d)(st|nd|rd|th)", "$1").replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        if ("today".equalsIgnoreCase(t)) {
            return today;
        }
        if ("tomorrow".equalsIgnoreCase(t)) {
            return today.plusDays(1);
        }
        for (DateTimeFormatter f : DATE_IN) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        // no year: "14 Oct" or "14/10" -> this year (next year if already past)
        for (String p : new String[] {"d MMM", "d MMMM", "d/M", "d-M", "MMM d"}) {
            try {
                var f = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                        .parseDefaulting(ChronoField.YEAR, today.getYear()).toFormatter(Locale.ENGLISH);
                LocalDate d = LocalDate.parse(t, f);
                return d.isBefore(today.minusDays(7)) ? d.plusYears(1) : d;
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ formatting
    private static String currency(JsonNode n) {
        String s = n.path("currency_symbol").asText("");
        return s.isEmpty() ? n.path("currency_code").asText("") : s;
    }

    static String money(String cur, double amt) {
        String num = String.format(Locale.ENGLISH, "%,.2f", amt);
        if (cur == null || cur.isEmpty()) {
            return num;
        }
        return cur.length() == 1 || !cur.matches("[A-Z]{3}") ? cur + num : cur + " " + num;
    }

    private static String status(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String date(String iso) {
        try {
            return new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(new SimpleDateFormat("yyyy-MM-dd").parse(iso));
        } catch (Exception e) {
            return iso == null ? "" : iso;
        }
    }

    /** Catalogue as JSON for the builder. */
    public static ArrayNode catalogJson() {
        return WaUtil.JSON.valueToTree(CATALOG);
    }
}
