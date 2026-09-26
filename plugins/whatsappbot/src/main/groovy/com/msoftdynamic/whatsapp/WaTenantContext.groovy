/*
 * Resolves which tenant the logged-in user is working on.
 *  - platform admins (WABOT_ADMIN) may pick any tenant or none (= see all)
 *  - tenant users are always pinned to one of their own tenants
 * Sets: isWaAdmin (Y/N), currentTenantId (for creates), scopeTenantId (for list filters), currentTenant, myTenantIds
 */
import com.msoftdynamic.whatsapp.WaUtil
import org.apache.ofbiz.entity.util.EntityQuery

def isAdmin = userLogin != null && security.hasEntityPermission("WABOT", "_ADMIN", userLogin)
def myTenants = WaUtil.getUserTenantIds(delegator, userLogin?.userLoginId)

def requested = parameters.switchTenantId
if (requested != null && session != null) {
    if (requested == "") {
        session.removeAttribute("waTenantId")
    } else if (isAdmin || myTenants.contains(requested)) {
        session.setAttribute("waTenantId", requested)
    }
}
def cur = session?.getAttribute("waTenantId")
if (!isAdmin && !myTenants.contains(cur)) {
    cur = myTenants ? myTenants[0] : null
}

globalContext.isWaAdmin = isAdmin ? "Y" : "N"
globalContext.myTenantIds = myTenants
globalContext.currentTenantId = cur
// a tenant user without tenant must never see unfiltered lists
globalContext.scopeTenantId = cur ?: (isAdmin ? null : "_NO_TENANT_")
globalContext.currentTenant = cur ? EntityQuery.use(delegator).from("WaTenant").where("tenantId", cur).queryOne() : null
if (cur) {
    globalContext.currentUsage = EntityQuery.use(delegator).from("WaUsage")
            .where("tenantId", cur, "periodId", WaUtil.currentPeriod()).queryOne()
    globalContext.currentPlan = globalContext.currentTenant?.getRelatedOne("WaPlan", true)
    globalContext.currentMsgLimit = com.msoftdynamic.whatsapp.WaAddons.limit(delegator, globalContext.currentPlan, cur, "MESSAGES")
}
def base = WaUtil.prop("brand.app.url", "")
if (!base) base = request ? (request.scheme + "://" + request.serverName + ((request.serverPort in [80, 443]) ? "" : ":" + request.serverPort)) : ""
base = base.replaceAll('/+$', '')
globalContext.appUrl = base
globalContext.webhookUrl = base + "/webhook"
globalContext.apiBaseUrl = base + "/api/v1"
globalContext.brandName = WaUtil.prop("brand.name", "FloChat")
globalContext.brandDomain = WaUtil.prop("brand.domain", "flochat.flolink.ai")
globalContext.supportEmail = WaUtil.prop("brand.support.email", "info@msoftdynamic.com")
globalContext.metaAppId = WaUtil.prop("meta.app.id", "")
globalContext.metaSignupConfigId = WaUtil.prop("meta.embedded.signup.config.id", "")
globalContext.graphVersion = WaUtil.prop("graph.api.version", "v23.0")

// ---- SaaS shell data (sidebar badge, trial countdown, admin workspace switcher)
if (cur) {
    globalContext.unreadConversations = EntityQuery.use(delegator).from("WaContact")
            .where(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition([
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("tenantId", cur),
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("unreadCount",
                        org.apache.ofbiz.entity.condition.EntityOperator.GREATER_THAN, 0L)])).queryCount()
    def thru = globalContext.currentTenant?.subscriptionThruDate
    if (globalContext.currentTenant?.statusId == "WA_TNT_TRIAL" && thru) {
        long ms = thru.getTime() - System.currentTimeMillis()
        globalContext.trialDaysLeft = ms <= 0 ? 0 : (int) Math.ceil(ms / 86400000d)
    }
}
if (isAdmin) {
    globalContext.allTenantsForSwitch = EntityQuery.use(delegator).from("WaTenant").orderBy("tenantName").cache(false).queryList()
}
