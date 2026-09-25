/* Visual flow builder: initial graph, templates and other flows (for "Go to flow"), all as base64 JSON */
import com.msoftdynamic.whatsapp.WaFlowAi
import com.msoftdynamic.whatsapp.WaFlowBuilder
import com.msoftdynamic.whatsapp.WaFlowEvents
import com.msoftdynamic.whatsapp.WaUtil
import org.apache.ofbiz.entity.util.EntityQuery
import java.nio.charset.StandardCharsets

def flow = context.flow
if (!flow) return
def b64 = { o -> Base64.urlEncoder.withoutPadding().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8)) }
def tenant = EntityQuery.use(delegator).from("WaTenant").where("tenantId", flow.tenantId).queryOne()
def business = tenant?.tenantName ?: ""
context.graphB64 = b64(WaFlowBuilder.load(delegator, flow))
def tpls = WaUtil.JSON.createArrayNode()
WaFlowEvents.templates().each { t ->
    def o = tpls.addObject()
    o.put("id", t.path("id").asText()); o.put("name", t.path("name").asText()); o.put("icon", t.path("icon").asText(""))
    o.put("description", t.path("description").asText(""))
    o.set("graph", WaFlowBuilder.sanitize(t, business))
}
context.templatesB64 = b64(tpls)
def others = WaUtil.JSON.createArrayNode()
EntityQuery.use(delegator).from("WaFlow").where("tenantId", flow.tenantId).orderBy("flowName").queryList().each { f ->
    if (f.flowId != flow.flowId) others.addObject().put("id", f.flowId).put("name", f.flowName ?: f.flowId)
}
context.otherFlowsB64 = b64(others)
context.aiConfigured = WaFlowAi.isConfigured(delegator, flow.tenantId)
context.businessName = business
