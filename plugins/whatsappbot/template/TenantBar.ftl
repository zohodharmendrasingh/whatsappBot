<div class="wa-tenantbar">
  <#if isWaAdmin == "Y">
    <span class="wa-badge wa-admin">Platform admin</span>
    <form method="get" action="<@ofbizUrl>main</@ofbizUrl>" class="wa-inline">
      <label>Working on tenant:</label>
      <select name="switchTenantId" onchange="this.form.submit()">
        <option value="">— All tenants —</option>
        <#list delegator.findAll("WaTenant", false) as t>
          <option value="${t.tenantId}" <#if currentTenantId?has_content && currentTenantId == t.tenantId>selected</#if>>${t.tenantName!t.tenantId}</option>
        </#list>
      </select>
    </form>
  <#elseif (myTenantIds?size > 1)>
    <form method="get" action="<@ofbizUrl>main</@ofbizUrl>" class="wa-inline">
      <select name="switchTenantId" onchange="this.form.submit()">
        <#list myTenantIds as tid><option value="${tid}" <#if currentTenantId == tid>selected</#if>>${tid}</option></#list>
      </select>
    </form>
  </#if>
  <#if currentTenant?has_content>
    <strong>${currentTenant.tenantName!}</strong>
    <span class="wa-badge">${(currentPlan.planName)!"No plan"}</span>
    <span class="wa-muted">This month: ${(currentUsage.messagesOut)!0} sent<#if (currentPlan.maxMessagesPerMonth)?has_content> / ${currentPlan.maxMessagesPerMonth}</#if>, ${(currentUsage.messagesIn)!0} received</span>
    <#if currentTenant.statusId != "WA_TNT_ACTIVE" && currentTenant.statusId != "WA_TNT_TRIAL"><span class="wa-badge wa-warn">Subscription ${currentTenant.statusId}</span></#if>
  </#if>
</div>
