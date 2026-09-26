/* AI Agent page: settings, knowledge sources, key status */
import com.msoftdynamic.whatsapp.WaAgentAi
import com.msoftdynamic.whatsapp.WaKnowledge
import com.msoftdynamic.whatsapp.WaUtil
import org.apache.ofbiz.entity.util.EntityQuery

def tenantId = context.currentTenantId
if (!tenantId) return
context.isOwner = context.isWaAdmin == "Y" || WaUtil.getTenantRole(delegator, tenantId, userLogin.userLoginId) == "WA_OWNER"
def cfg = WaAgentAi.settings(delegator, tenantId)
context.agent = cfg
context.agentOn = cfg?.enabled == "Y"
def keyRow = EntityQuery.use(delegator).from("WaTenantAi").where("tenantId", tenantId).queryOne()
context.hasOwnKey = keyRow?.apiKey ? true : false
context.keyProvider = keyRow?.provider == "openai" ? "OpenAI" : "Claude"
context.kbSources = EntityQuery.use(delegator).from("WaKbSource").where("tenantId", tenantId).orderBy("-createdDate").queryList()
context.kbSummary = WaKnowledge.summary(delegator, tenantId)
context.kbMaxSources = WaKnowledge.maxSources()
context.aiUsedToday = WaAgentAi.usedToday(tenantId)
context.aiMaxPerDay = cfg?.maxPerDay ?: WaUtil.propInt("ai.agent.max.per.day", 1000)
context.hasMainMenu = EntityQuery.use(delegator).from("WaFlow").where("tenantId", tenantId, "isDefault", "Y", "isActive", "Y").queryCount() > 0
context.defaultHandoff = WaAgentAi.DEFAULT_HANDOFF
