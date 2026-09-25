<#-- FloChat SaaS shell: fixed left sidebar + top bar. The OFBiz application switcher is intentionally not rendered. -->
<#assign page = (requestAttributes._CURRENT_VIEW_)!(request.getRequestURI()?keep_after_last("/"))>
<#if page == "main" && (isWaAdmin!"N") == "Y" && !currentTenantId?has_content><#assign page = "AdminHome"></#if>
<#macro icon name><svg class="ms-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><#switch name>
<#case "home"><path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/><#break>
<#case "inbox"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><#break>
<#case "flow"><rect x="3" y="3" width="6" height="6" rx="1.5"/><rect x="15" y="15" width="6" height="6" rx="1.5"/><path d="M6 9v4a2 2 0 0 0 2 2h7"/><#break>
<#case "template"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h5"/><#break>
<#case "broadcast"><path d="M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1z"/><path d="M16 8.5a5 5 0 0 1 0 7M19 5.5a9 9 0 0 1 0 13"/><#break>
<#case "phone"><rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18h2"/><#break>
<#case "settings"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/><#break>
<#case "key"><circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 9.8-9.8M17 6l3 3M14.5 8.5l2 2"/><#break>
<#case "chart"><path d="M3 3v18h18"/><path d="M7 15l4-4 3 3 5-6"/><#break>
<#case "building"><rect x="4" y="3" width="16" height="18" rx="1.5"/><path d="M9 7h1M14 7h1M9 11h1M14 11h1M9 15h1M14 15h1M10 21v-3h4v3"/><#break>
<#case "tag"><path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><circle cx="7.5" cy="7.5" r="1.5"/><#break>
<#case "card"><rect x="2.5" y="5" width="19" height="14" rx="2"/><path d="M2.5 10h19M6.5 15h4"/><#break>
<#case "log"><path d="M4 6h16M4 12h16M4 18h10"/><#break>
<#case "menu"><path d="M4 6h16M4 12h16M4 18h16"/><#break>
<#case "logout"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/><#break>
</#switch></svg></#macro>
<#macro nav target label ico pages badge="">
  <#assign active = pages?split(",")?seq_contains(page)>
  <a class="ms-nav-item<#if active> active</#if>" href="<@ofbizUrl>${target}</@ofbizUrl>"><@icon ico/><span>${label}</span><#if badge?has_content && badge != "0"><em class="ms-count">${badge}</em></#if></a>
</#macro>
<body<#if userLogin?has_content> class="ms-shell"</#if>>
<#include "component://common-theme/template/ImpersonateBanner.ftl"/>
<div id="wait-spinner" class="hidden"><div id="wait-spinner-image"></div></div>
<div class="page-container">
<#if userLogin?has_content>
<aside class="ms-sidebar" id="msSidebar">
  <a class="ms-sidebar-brand" href="<@ofbizUrl>main</@ofbizUrl>"><img src="/theme/flochat-logo.svg" alt="FloChat" height="36"/></a>
  <nav>
    <#if (isWaAdmin!"N") != "Y" || currentTenantId?has_content>
      <div class="ms-nav-section">Workspace</div>
      <@nav "main" "Dashboard" "home" "main"/>
      <@nav "Inbox" "Inbox" "inbox" "Inbox,Conversation" "${unreadConversations!0}"/>
      <@nav "FindFlow" "Bot Flows" "flow" "FindFlow,FlowBuilder,EditFlow,EditFlowNode"/>
      <@nav "Templates" "Templates" "template" "Templates"/>
      <@nav "Broadcast" "Broadcasts" "broadcast" "Broadcast"/>
      <@nav "Channels" "WhatsApp Numbers" "phone" "Channels,EditChannel"/>
      <div class="ms-nav-section">Settings</div>
      <@nav "Settings" "Team &amp; Profile" "settings" "Settings"/>
      <@nav "Billing" "Plan &amp; Billing" "card" "Billing"/>
      <@nav "ApiKeys" "API &amp; Integrations" "key" "ApiKeys"/>
    </#if>
    <#if (isWaAdmin!"N") == "Y">
      <div class="ms-nav-section ms-admin-section">Platform admin</div>
      <@nav "AdminHome" "Overview" "chart" "AdminHome"/>
      <@nav "Tenants" "Customers" "building" "Tenants,EditTenant"/>
      <@nav "Plans" "Plans &amp; Pricing" "tag" "Plans"/>
      <@nav "WebhookLogs" "Webhook Log" "log" "WebhookLogs,ViewWebhookLog"/>
    </#if>
  </nav>
  <#if currentTenant?has_content && currentPlan?has_content>
    <#assign used = (currentUsage.messagesOut)!0>
    <#assign quota = (currentPlan.maxMessagesPerMonth)!0>
    <div class="ms-plan-card">
      <div class="ms-plan-name">${currentPlan.planName!} plan<#if currentTenant.statusId! == "WA_TNT_TRIAL"> &middot; Trial</#if></div>
      <#if (quota > 0)>
        <#assign pct = ((used * 100) / quota)?round>
        <div class="ms-meter"><span style="width:${[pct,100]?min}%"></span></div>
        <div class="ms-plan-sub">${used} / ${quota} messages this month</div>
      <#else>
        <div class="ms-plan-sub">${used} messages this month &middot; unlimited</div>
      </#if>
      <#if trialDaysLeft??><div class="ms-plan-sub<#if (trialDaysLeft < 4)> ms-warn-text</#if>">${trialDaysLeft} day<#if trialDaysLeft != 1>s</#if> left in trial</div></#if>
      <#if currentTenant.statusId! != "WA_TNT_ACTIVE"><a class="ms-plan-cta" href="<@ofbizUrl>Billing</@ofbizUrl>">Upgrade plan</a></#if>
    </div>
  </#if>
</aside>
<header class="ms-topbar">
  <button type="button" class="ms-burger" onclick="document.body.classList.toggle('ms-nav-open')" aria-label="Menu"><@icon "menu"/></button>
  <div class="ms-page-title"><#if (titleProperty)?has_content>${uiLabelMap[titleProperty]}</#if></div>
  <div class="ms-topbar-right">
    <#if (isWaAdmin!"N") == "Y">
      <form method="get" action="<@ofbizUrl>main</@ofbizUrl>" class="ms-switcher">
        <select name="switchTenantId" onchange="this.form.submit()" title="Open a customer workspace">
          <option value="">Platform admin (no workspace)</option>
          <#list allTenantsForSwitch![] as t>
            <option value="${t.tenantId}" <#if currentTenantId?has_content && currentTenantId == t.tenantId>selected</#if>>${t.tenantName!t.tenantId}</option>
          </#list>
        </select>
      </form>
    <#elseif currentTenant?has_content>
      <div class="ms-workspace"><span class="ms-ws-dot"></span>${currentTenant.tenantName!}</div>
    </#if>
    <div id="user-avatar" class="ms-user" onclick="showHideUserPref()">
      <span class="msoft-avatar">${(userLogin.userLoginId!"?")?substring(0,1)?upper_case}</span>
      <span class="msoft-username">${userLogin.userLoginId}</span>
      <div id="user-details" style="display:none;">
        <p id="user-name"><strong>${userLogin.userLoginId}</strong><#if (isWaAdmin!"N") == "Y"><br/><small>Platform admin</small></#if></p>
        <#if currentTenantId?has_content><a class="user-pref-btn" href="<@ofbizUrl>Settings</@ofbizUrl>">Team &amp; Profile</a></#if>
        <a class="user-pref-btn" href="<@ofbizUrl>passwordChange</@ofbizUrl>">Change password</a>
        <a id="logout" class="user-pref-btn" href="<@ofbizUrl>logout</@ofbizUrl>"><@icon "logout"/> Sign out</a>
      </div>
    </div>
  </div>
</header>
</#if>
