<div class="ms-kpis">
  <div class="ms-kpi"><div class="ms-kpi-label">Customers</div><div class="ms-kpi-value">${admin.total}</div><div class="ms-kpi-sub">${admin.active} paying &middot; ${admin.trial} on trial</div></div>
  <div class="ms-kpi"><div class="ms-kpi-label">Monthly recurring revenue</div><div class="ms-kpi-value">$${admin.mrr?string(",##0")}</div><div class="ms-kpi-sub">$${(admin.revenueMonth!0)?string(",##0")} collected this month</div></div>
  <div class="ms-kpi"><div class="ms-kpi-label">New sign-ups (30d)</div><div class="ms-kpi-value">${admin.signups30}</div><div class="ms-kpi-sub">self-service trials</div></div>
  <div class="ms-kpi"><div class="ms-kpi-label">Messages this month</div><div class="ms-kpi-value">${admin.msgsOut + admin.msgsIn}</div><div class="ms-kpi-sub">${admin.msgsOut} sent &middot; ${admin.msgsIn} received</div></div>
  <a class="ms-kpi<#if (admin.webhookErr > 0)> ms-kpi-alert</#if>" href="<@ofbizUrl>WebhookLogs?processedStatus=ERROR</@ofbizUrl>"><div class="ms-kpi-label">Webhook errors</div><div class="ms-kpi-value">${admin.webhookErr}</div><div class="ms-kpi-sub">${admin.channels} numbers connected &rarr;</div></a>
</div>

<div class="ms-grid-2">
  <div class="screenlet ms-card">
    <div class="screenlet-title-bar"><ul><li class="h3">Recent sign-ups</li><li class="ms-title-link"><a href="<@ofbizUrl>Tenants</@ofbizUrl>">All customers &rarr;</a></li></ul></div>
    <div class="screenlet-body">
      <#if recentSignups?has_content>
      <table class="basic-table hover-bar">
        <tr class="header-row"><td>Business</td><td>Plan</td><td>Status</td><td>Joined</td><td></td></tr>
        <#list recentSignups as t>
          <tr><td><a href="<@ofbizUrl>EditTenant?tenantId=${t.tenantId}</@ofbizUrl>"><strong>${t.tenantName!t.tenantId}</strong></a><br/><small class="msoft-muted">${t.contactEmail!}</small></td>
              <td>${(plansById[t.planId].planName)!t.planId!}</td>
              <td><#switch t.statusId!><#case "WA_TNT_ACTIVE"><span class="ms-pill ms-pill-green">Active</span><#break><#case "WA_TNT_TRIAL"><span class="ms-pill ms-pill-blue">Trial</span><#break><#default><span class="ms-pill ms-pill-red">${(t.statusId!"")?replace("WA_TNT_","")?capitalize}</span></#switch></td>
              <td>${(t.createdDate?string("dd MMM yyyy"))!}</td>
              <td><a class="ms-btn-ghost" href="<@ofbizUrl>main?switchTenantId=${t.tenantId}</@ofbizUrl>">Open workspace</a></td></tr>
        </#list>
      </table>
      <#else><div class="ms-empty"><p>No customers yet.</p></div></#if>
    </div>
  </div>
  <div>
    <div class="screenlet ms-card">
      <div class="screenlet-title-bar"><ul><li class="h3">Trials ending within 7 days</li></ul></div>
      <div class="screenlet-body">
        <#if expiringTrials?has_content>
          <ul class="ms-list"><#list expiringTrials as t><li><a href="<@ofbizUrl>EditTenant?tenantId=${t.tenantId}</@ofbizUrl>">${t.tenantName!}</a><span class="msoft-muted">${t.subscriptionThruDate?string("dd MMM")}</span></li></#list></ul>
        <#else><p class="msoft-muted">Nothing expiring this week.</p></#if>
      </div>
    </div>
    <div class="screenlet ms-card">
      <div class="screenlet-title-bar"><ul><li class="h3">Most active this month</li></ul></div>
      <div class="screenlet-body">
        <#if topUsage?has_content>
          <ul class="ms-list"><#list topUsage as u><li><a href="<@ofbizUrl>EditTenant?tenantId=${u.tenantId}</@ofbizUrl>">${u.name!u.tenantId}</a><span class="msoft-muted">${u.out} sent &middot; ${u.inn} received</span></li></#list></ul>
        <#else><p class="msoft-muted">No traffic yet this month.</p></#if>
      </div>
    </div>
  </div>
</div>

<div class="screenlet ms-card">
  <div class="screenlet-title-bar"><ul><li class="h3">Recent payments (PayPal)</li></ul></div>
  <div class="screenlet-body">
    <#if recentPayments?has_content>
    <table class="basic-table hover-bar">
      <tr class="header-row"><td>Date</td><td>Customer</td><td>Plan</td><td>Amount</td><td>Payer</td><td>PayPal capture id</td></tr>
      <#list recentPayments as p>
        <tr><td>${(p.date?string("dd MMM yyyy"))!}</td>
            <td><a href="<@ofbizUrl>EditTenant?tenantId=${p.tenantId}</@ofbizUrl>">${p.name!}</a></td>
            <td>${p.plan!} &middot; ${p.months} mo</td>
            <td>$${p.amount?string(",##0.00")}</td>
            <td>${p.payer!}</td>
            <td><code>${p.ref!}</code></td></tr>
      </#list>
    </table>
    <#else><p class="msoft-muted">No payments yet.</p></#if>
  </div>
</div>
