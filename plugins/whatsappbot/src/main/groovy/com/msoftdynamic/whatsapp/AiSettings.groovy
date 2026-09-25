/* Settings > AI assistant: which AI (Claude or OpenAI) the workspace uses; never exposes the key itself */
import com.msoftdynamic.whatsapp.WaFlowEvents
import com.msoftdynamic.whatsapp.WaUtil
import org.apache.ofbiz.entity.util.EntityQuery

def t = context.currentTenantId
if (!t) return
def row = EntityQuery.use(delegator).from("WaTenantAi").where("tenantId", t).queryOne()
context.aiKeySet = row?.apiKey ? true : false
context.aiProvider = row?.provider == "openai" ? "openai" : "anthropic"
context.aiProviderName = context.aiProvider == "openai" ? "OpenAI" : "Claude"
context.aiKeyHint = row?.keyHint ?: ""
context.aiModel = row?.model ?: ""
context.aiUpdated = row?.updatedDate
context.aiModels = WaFlowEvents.AI_MODELS
context.aiOpenAiModels = WaFlowEvents.OPENAI_MODELS
context.aiIsOwner = context.isWaAdmin == "Y" || WaUtil.getTenantRole(delegator, t, userLogin.userLoginId) == "WA_OWNER"
