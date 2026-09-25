/* Split inbox: filtered conversation list + selected conversation (tenant-checked) */
import org.apache.ofbiz.entity.condition.EntityCondition as EC
import org.apache.ofbiz.entity.condition.EntityOperator as EO
import org.apache.ofbiz.entity.util.EntityQuery

def scope = context.scopeTenantId
if (!scope && context.isWaAdmin != "Y") scope = "_NO_TENANT_"
def tab = parameters.tab ?: (parameters.botPaused == "Y" ? "agent" : "all")
def q = (parameters.q ?: "").trim()

def conds = []
if (scope) conds << EC.makeCondition("tenantId", scope)
if (tab == "unread") conds << EC.makeCondition("unreadCount", EO.GREATER_THAN, 0L)
if (tab == "agent") conds << EC.makeCondition("botPaused", "Y")
// search by name or number: fetch a capped list, then filter in memory (portable across databases)
def contacts = EntityQuery.use(delegator).from("WaContact").where(conds)
        .orderBy("-lastMessageDate").maxRows(q ? 500 : 80).queryList()
if (q) {
    def ql = q.toLowerCase()
    def digits = q.replaceAll("[^0-9]", "")
    contacts = contacts.findAll {
        (it.profileName ?: "").toLowerCase().contains(ql) || (digits && (it.waId ?: "").contains(digits))
    }.take(80)
}
def previews = [:]
contacts.each { c ->
    def m = EntityQuery.use(delegator).from("WaMessage").where("contactId", c.contactId).orderBy("-createdDate").queryFirst()
    if (m) previews[c.contactId] = [text: (m.body ?: "").replaceAll("\\s+", " ").take(70), out: m.direction == "OUT", status: m.deliveryStatus]
}
context.inboxTab = tab
context.inboxQ = q
context.inboxContacts = contacts
context.inboxPreviews = previews

// selected conversation
def cid = parameters.contactId
if (cid) {
    def c = EntityQuery.use(delegator).from("WaContact").where("contactId", cid).queryOne()
    if (c && (context.isWaAdmin == "Y" || c.tenantId == context.currentTenantId)) {
        if ((c.unreadCount ?: 0L) > 0L) { c.unreadCount = 0L; c.store() }
        context.contact = c
        context.messages = EntityQuery.use(delegator).from("WaMessage").where("contactId", cid).orderBy("createdDate").queryList()
        context.contactVars = com.msoftdynamic.whatsapp.WaUtil.readVars(c)
    }
}
