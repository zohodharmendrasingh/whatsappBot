<#if currentTenant?has_content>
<div class="screenlet ms-card"><div class="screenlet-body ms-settings-plan">
  <div>
    <h3>${(currentPlan.planName)!"No"} plan
      <#switch currentTenant.statusId!>
        <#case "WA_TNT_ACTIVE"><span class="ms-pill ms-pill-green">Active</span><#break>
        <#case "WA_TNT_TRIAL"><span class="ms-pill ms-pill-blue">Free trial</span><#break>
        <#default><span class="ms-pill ms-pill-red">${(currentTenant.statusId!"")?replace("WA_TNT_","")?capitalize}</span>
      </#switch>
    </h3>
    <p class="msoft-muted">
      <#if currentTenant.subscriptionThruDate?has_content><#if currentTenant.statusId! == "WA_TNT_TRIAL">Trial ends<#else>Paid until</#if> ${currentTenant.subscriptionThruDate?string("dd MMM yyyy")}<#if trialDaysLeft??> (${trialDaysLeft} days left)</#if> &middot; </#if>
      <#if currentPlan?has_content><#if (currentPlan.currencyUomId!"USD") == "USD">$<#elseif (currentPlan.currencyUomId!"") == "INR">&#8377;<#else>${currentPlan.currencyUomId!} </#if>${(currentPlan.monthlyPrice!0)?string(",##0")}/month</#if>
    </p>
  </div>
  <#assign used = (currentUsage.messagesOut)!0><#assign quota = (currentPlan.maxMessagesPerMonth)!0>
  <div>
    <div class="msoft-muted">Messages sent this month: <strong>${used}</strong><#if (quota > 0)> of ${quota}</#if></div>
    <div class="msoft-muted" style="font-size:1.2rem;margin-top:4px">WhatsApp fees are billed by Meta to your WhatsApp Business Account. <a href="https://developers.facebook.com/docs/whatsapp/pricing" target="_blank" rel="noopener">Meta pricing</a></div>
    <#if (quota > 0)><div class="ms-meter"><span style="width:${[((used * 100) / quota)?round,100]?min}%"></span></div></#if>
  </div>
  <a class="s-btn-upgrade smallSubmit" href="<@ofbizUrl>Billing</@ofbizUrl>"><#if currentTenant.statusId! == "WA_TNT_ACTIVE">Change plan or renew<#else>Upgrade now</#if></a>
</div></div>
</#if>
