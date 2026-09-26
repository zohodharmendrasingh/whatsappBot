/* Contacts page: filtered, paged list + tags, custom fields and numbers of the workspace */
import com.msoftdynamic.whatsapp.WaContacts
import com.msoftdynamic.whatsapp.WaCrmEvents
import org.apache.ofbiz.entity.condition.EntityCondition as EC
import org.apache.ofbiz.entity.util.EntityQuery

def tenantId = context.currentTenantId
if (!tenantId) return
def isOwner = context.isWaAdmin == "Y" || com.msoftdynamic.whatsapp.WaUtil.getTenantRole(delegator, tenantId, userLogin.userLoginId) == "WA_OWNER"
context.isOwner = isOwner

def f = WaCrmEvents.ContactFilter.of(delegator, tenantId, parameters.q, parameters.tag, parameters.opt, parameters.channelId)
int pageSize = 50
def pageStr = (parameters.page ?: "1").toString()
int page = pageStr.isInteger() ? Math.max(1, pageStr as int) : 1
def rows = []
long matched = 0
def it = EntityQuery.use(delegator).from("WaContact").where(f.conditions()).orderBy("-lastMessageDate", "-createdDate").queryIterator()
try {
    def c
    while ((c = it.next()) != null) {
        if (!f.matches(c)) continue
        matched++
        if (matched > (page - 1) * pageSize && rows.size() < pageSize) rows << c
    }
} finally { it.close() }

def tagsBy = [:]
if (rows) {
    EntityQuery.use(delegator).from("WaContactTag").where(EC.makeCondition("contactId", org.apache.ofbiz.entity.condition.EntityOperator.IN, rows*.contactId))
            .orderBy("tag").queryList().each { t -> (tagsBy[t.contactId] = (tagsBy[t.contactId] ?: [])) << t.tag }
}
def defs = WaContacts.fieldDefs(delegator, tenantId)
def channels = EntityQuery.use(delegator).from("WaChannel").where("tenantId", tenantId).orderBy("channelId").queryList()
def chLabel = [:]
channels.each { chLabel[it.channelId] = (it.displayPhoneNumber ?: it.channelName ?: it.channelId) }

context.contactRows = rows.collect { c ->
    [contactId: c.contactId, name: c.profileName, waId: c.waId, email: c.email, optIn: c.optInStatus != "N",
     optSource: c.optSource, tags: tagsBy[c.contactId] ?: [], fields: WaContacts.fields(c),
     lastMessageDate: c.lastMessageDate, createdDate: c.createdDate, channel: chLabel[c.channelId]]
}
context.contactsMatched = matched
context.contactsPage = page
context.contactsPages = Math.max(1, (int) Math.ceil(matched / (double) pageSize))
context.contactsTotal = EntityQuery.use(delegator).from("WaContact").where("tenantId", tenantId).queryCount()
context.contactsOptedOut = EntityQuery.use(delegator).from("WaContact").where("tenantId", tenantId, "optInStatus", "N").queryCount()
context.tagCounts = WaContacts.tenantTags(delegator, tenantId)
context.fieldDefs = defs
context.channels = channels
context.multiChannel = channels.size() > 1
context.filterQ = parameters.q ?: ""
context.filterTag = WaContacts.normTag(parameters.tag) ?: ""
context.filterOpt = parameters.opt in ["Y", "N"] ? parameters.opt : ""
context.filterChannel = parameters.channelId ?: ""
context.defaultCountryCode = channels ? WaContacts.countryCodeOf(channels[0]) : ""
