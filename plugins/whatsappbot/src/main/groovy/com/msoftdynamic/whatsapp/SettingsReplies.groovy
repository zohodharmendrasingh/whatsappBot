/* Settings: the workspace's saved replies */
import org.apache.ofbiz.entity.util.EntityQuery
if (context.currentTenantId) {
    context.savedReplies = EntityQuery.use(delegator).from("WaQuickReply").where("tenantId", context.currentTenantId).orderBy("shortcut").queryList()
}
