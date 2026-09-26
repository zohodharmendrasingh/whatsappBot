/* Broadcasts: new-campaign form data, campaign list with results, and one campaign's report */
import com.msoftdynamic.whatsapp.WaCampaigns
import com.msoftdynamic.whatsapp.WaContacts
import com.msoftdynamic.whatsapp.WaCrmEvents
import org.apache.ofbiz.entity.util.EntityQuery

def tenantId = context.currentTenantId
if (!tenantId) return

context.bcChannels = EntityQuery.use(delegator).from("WaChannel").where("tenantId", tenantId, "isActive", "Y").orderBy("channelId").queryList()
context.bcTemplates = EntityQuery.use(delegator).from("WaTemplate").where("tenantId", tenantId, "metaStatus", "APPROVED")
        .orderBy("templateName").queryList().collect { t ->
            [templateId: t.templateId, channelId: t.channelId, label: t.templateName + " (" + (t.languageCode ?: "") + ")",
             body: t.bodyText ?: "", n: WaCrmEvents.placeholders(t.bodyText), category: t.category ?: ""]
        }
context.bcTags = WaContacts.tenantTags(delegator, tenantId)
context.bcFields = WaContacts.fieldDefs(delegator, tenantId)

def statusLabel = [SCHEDULED: "Scheduled", SENDING: "Sending", DONE: "Sent", CANCELLED: "Cancelled", FAILED: "Paused"]
context.bcStatusLabel = statusLabel

def cid = parameters.campaignId
if (cid) {
    def cmp = EntityQuery.use(delegator).from("WaCampaign").where("campaignId", cid).queryOne()
    if (cmp && cmp.tenantId == tenantId) {
        context.campaign = cmp
        context.campaignStats = WaCampaigns.stats(delegator, cid)
        context.campaignChannel = EntityQuery.use(delegator).from("WaChannel").where("channelId", cmp.channelId).queryOne()
        def tpl = cmp.templateId ? EntityQuery.use(delegator).from("WaTemplate").where("templateId", cmp.templateId).queryOne() : null
        context.campaignBody = tpl?.bodyText ?: ""
        def filter = parameters.status in ["PENDING", "SKIPPED", "SENT", "DELIVERED", "READ", "FAILED", "REPLIED"] ? parameters.status : ""
        context.recipientFilter = filter
        def conds = [org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("campaignId", cid)]
        if (filter == "REPLIED") {
            conds << org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("repliedDate",
                    org.apache.ofbiz.entity.condition.EntityOperator.NOT_EQUAL, null)
        } else if (filter) {
            conds << org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("statusId", filter)
        }
        def recips = EntityQuery.use(delegator).from("WaCampaignRecipient").where(conds).orderBy("-sentDate", "contactId").maxRows(200).queryList()
        def names = [:]
        recips.each { r ->
            def c = EntityQuery.use(delegator).select("profileName").from("WaContact").where("contactId", r.contactId).queryOne()
            names[r.contactId] = c?.profileName
        }
        context.recipients = recips
        context.recipientNames = names
        context.campaignLive = cmp.statusId == "SENDING" || (cmp.statusId == "SCHEDULED" && cmp.scheduledDate && cmp.scheduledDate.time < System.currentTimeMillis() + 90000)
        context.campaignCanResume = cmp.statusId == "FAILED" || (cmp.statusId == "SENDING" && cmp.startedDate && System.currentTimeMillis() - cmp.startedDate.time > 600000)
        def aud = cmp.audienceType == "ALL" ? "All contacts of this number" :
                  cmp.audienceType == "NUMBERS" ? "Pasted numbers" : "Tagged " + (cmp.audienceTags ?: "").split(",").join(", ")
        if (cmp.excludeTags) aud += " · except " + cmp.excludeTags.split(",").join(", ")
        context.campaignAudience = aud
    }
}

// campaign list (latest 30) with results
def list = EntityQuery.use(delegator).from("WaCampaign").where("tenantId", tenantId).orderBy("-createdDate").maxRows(30).queryList()
context.campaigns = list.collect { c -> [c: c, s: WaCampaigns.stats(delegator, c.campaignId)] }
