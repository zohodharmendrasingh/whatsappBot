/* Split inbox: filtered conversation list + selected conversation (tenant-checked) with notes, team and saved replies */
import org.apache.ofbiz.entity.condition.EntityCondition as EC
import org.apache.ofbiz.entity.condition.EntityOperator as EO
import org.apache.ofbiz.entity.util.EntityQuery

def scope = context.scopeTenantId
if (!scope && context.isWaAdmin != "Y") scope = "_NO_TENANT_"
def me = userLogin?.userLoginId
def tab = parameters.tab ?: (parameters.botPaused == "Y" ? "agent" : "all")
if (!(tab in ["all", "mine", "unread", "agent"])) tab = "all"
def st = parameters.st ?: "active"
if (!(st in ["active", "OPEN", "PENDING", "SOLVED", "any"])) st = "active"
def q = (parameters.q ?: "").trim()

def conds = []
if (scope) conds << EC.makeCondition("tenantId", scope)
if (tab == "unread") conds << EC.makeCondition("unreadCount", EO.GREATER_THAN, 0L)
if (tab == "agent") conds << EC.makeCondition("botPaused", "Y")
if (tab == "mine") conds << EC.makeCondition("assignedTo", me)
if (st == "active") {
    conds << EC.makeCondition([EC.makeCondition("chatStatus", EO.EQUALS, null),
                               EC.makeCondition("chatStatus", EO.IN, ["OPEN", "PENDING"])], EO.OR)
} else if (st == "OPEN") {
    conds << EC.makeCondition([EC.makeCondition("chatStatus", EO.EQUALS, null), EC.makeCondition("chatStatus", "OPEN")], EO.OR)
} else if (st != "any") {
    conds << EC.makeCondition("chatStatus", st)
}
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
context.inboxSt = st
context.inboxQ = q
context.inboxContacts = contacts
context.inboxPreviews = previews
context.inboxMe = me

// team members of the workspace (for assignment)
def team = []
if (context.currentTenantId) {
    team = EntityQuery.use(delegator).from("WaTenantUser").where("tenantId", context.currentTenantId).orderBy("userLoginId").queryList()*.userLoginId
}
context.teamMembers = team

// selected conversation
def cid = parameters.contactId
if (cid) {
    def c = EntityQuery.use(delegator).from("WaContact").where("contactId", cid).queryOne()
    if (c && (context.isWaAdmin == "Y" || c.tenantId == context.currentTenantId)) {
        if ((c.unreadCount ?: 0L) > 0L) {
            delegator.storeByCondition("WaContact", [unreadCount: 0L], EC.makeCondition("contactId", cid))
            c.unreadCount = 0L
        }
        context.contact = c
        def msgs = EntityQuery.use(delegator).from("WaMessage").where("contactId", cid).orderBy("createdDate").queryList()
        def notes = EntityQuery.use(delegator).from("WaNote").where("contactId", cid).orderBy("createdDate").queryList()
        context.messages = msgs
        def timeline = []
        msgs.each { timeline << [kind: "msg", at: it.createdDate, m: it] }
        notes.each { def t = it.noteText ?: ""; def sys = t.startsWith("\u2022 "); timeline << [kind: "note", at: it.createdDate, n: it, sys: sys, text: sys ? t.substring(2) : t] }
        timeline.sort { a, b -> a.at <=> b.at }
        context.timeline = timeline
        context.contactVars = com.msoftdynamic.whatsapp.WaUtil.readVars(c)
        context.contactTags = com.msoftdynamic.whatsapp.WaContacts.tagsOf(delegator, cid)
        context.quickReplies = EntityQuery.use(delegator).from("WaQuickReply").where("tenantId", c.tenantId).orderBy("shortcut").queryList()
        def first = (c.profileName ?: "").trim().split(/\s+/)[0]
        context.contactFirstName = first ?: ""
        if (c.tenantId != context.currentTenantId) {
            context.teamMembers = EntityQuery.use(delegator).from("WaTenantUser").where("tenantId", c.tenantId).orderBy("userLoginId").queryList()*.userLoginId
        }
    }
}
