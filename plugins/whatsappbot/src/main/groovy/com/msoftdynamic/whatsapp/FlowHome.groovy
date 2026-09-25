/* Bot Flows home: flow cards, templates and the create-a-bot wizard */
import com.msoftdynamic.whatsapp.WaFlowAi
import com.msoftdynamic.whatsapp.WaFlowEvents
import org.apache.ofbiz.entity.util.EntityQuery

def scope = context.scopeTenantId
def q = EntityQuery.use(delegator).from("WaFlow").orderBy("flowName")
if (scope) q = q.where("tenantId", scope)
def flows = q.queryList()
def steps = [:], defaults = [:]
flows.each { f ->
    steps[f.flowId] = EntityQuery.use(delegator).from("WaFlowNode").where("flowId", f.flowId).queryCount()
    defaults[f.flowId] = EntityQuery.use(delegator).from("WaChannel").where("defaultFlowId", f.flowId).queryList()*.channelName
}
context.flowList = flows
context.flowSteps = steps
context.flowChannels = defaults
context.flowTemplates = WaFlowEvents.templateCards()
context.aiConfigured = WaFlowAi.isConfigured(delegator, context.currentTenantId)
context.canCreateFlow = context.currentTenantId as boolean
