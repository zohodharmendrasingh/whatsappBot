<#-- Shown on every workspace page when the monthly message limit is nearly or fully used -->
<#if currentTenant?has_content && (currentMsgLimit!0) gt 0 && (requestAttributes._CURRENT_VIEW_!"") != "Billing">
  <#assign used = (currentUsage.messagesOut)!0>
  <#assign pct = ((used * 100) / currentMsgLimit)?round>
  <#if (used >= currentMsgLimit)>
    <div class="ms-limit-banner">⛔ <span><b>Your monthly message limit is used up</b> (${used?string(",##0")} of ${currentMsgLimit?string(",##0")}). Your bot and team can't send messages until you add more.</span>
      <a class="ms-limit-cta" href="<@ofbizUrl>Billing</@ofbizUrl>#addons">Buy extra messages</a></div>
  <#elseif (pct >= 90)>
    <div class="ms-limit-banner warn">⚠️ <span>You have used <b>${pct}%</b> of this month's messages (${used?string(",##0")} of ${currentMsgLimit?string(",##0")}).</span>
      <a class="ms-limit-cta" href="<@ofbizUrl>Billing</@ofbizUrl>#addons">Buy extra messages</a></div>
  </#if>
</#if>
