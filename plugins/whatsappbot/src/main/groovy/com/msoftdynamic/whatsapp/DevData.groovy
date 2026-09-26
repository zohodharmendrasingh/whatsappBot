/* Developer portal: keys, webhooks, recent API calls */
import com.msoftdynamic.whatsapp.WaUtil
import com.msoftdynamic.whatsapp.WaWebhooks
import org.apache.ofbiz.entity.condition.EntityCondition as EC
import org.apache.ofbiz.entity.condition.EntityOperator as EO
import org.apache.ofbiz.entity.util.EntityQuery

def tenantId = context.currentTenantId
if (!tenantId) return
context.isOwner = context.isWaAdmin == "Y" || WaUtil.getTenantRole(delegator, tenantId, userLogin.userLoginId) == "WA_OWNER"
context.devKeys = EntityQuery.use(delegator).from("WaApiKey").where("tenantId", tenantId).orderBy("-createdDate").queryList()
context.devHooks = EntityQuery.use(delegator).from("WaWebhook").where("tenantId", tenantId).orderBy("createdDate").queryList()
context.hookEvents = WaWebhooks.EVENTS
context.devLogs = EntityQuery.use(delegator).from("WaApiLog").where("tenantId", tenantId).orderBy("-createdDate").maxRows(100).queryList()
def dayAgo = new java.sql.Timestamp(System.currentTimeMillis() - 86400000L)
def cond = EC.makeCondition([EC.makeCondition("tenantId", tenantId), EC.makeCondition("createdDate", EO.GREATER_THAN_EQUAL_TO, dayAgo)])
context.calls24 = EntityQuery.use(delegator).from("WaApiLog").where(cond).queryCount()
context.errors24 = EntityQuery.use(delegator).from("WaApiLog").where(EC.makeCondition([cond, EC.makeCondition("statusCode", EO.GREATER_THAN_EQUAL_TO, 400L)])).queryCount()
context.keyNames = context.devKeys.collectEntries { [(it.apiKeyId): it.description] }
context.devTab = parameters.tab ?: "overview"
context.rateLimit = WaUtil.propInt("api.rate.per.minute", 120)
