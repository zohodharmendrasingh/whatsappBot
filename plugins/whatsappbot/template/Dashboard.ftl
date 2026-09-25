<#-- Tenant dashboard -->
<div class="ms-kpis">
  <div class="ms-kpi"><div class="ms-kpi-label">Conversations</div><div class="ms-kpi-value">${stats.contacts}</div><div class="ms-kpi-sub">customers who messaged you</div></div>
  <div class="ms-kpi"><div class="ms-kpi-label">Received today</div><div class="ms-kpi-value">${stats.inToday}</div><div class="ms-kpi-sub">inbound messages</div></div>
  <div class="ms-kpi"><div class="ms-kpi-label">Sent today</div><div class="ms-kpi-value">${stats.outToday}</div><div class="ms-kpi-sub">bot, agents and API</div></div>
  <a class="ms-kpi<#if (stats.waitingAgent > 0)> ms-kpi-alert</#if>" href="<@ofbizUrl>Inbox?botPaused=Y</@ofbizUrl>"><div class="ms-kpi-label">Waiting for agent</div><div class="ms-kpi-value">${stats.waitingAgent}</div><div class="ms-kpi-sub">open hand-offs &rarr;</div></a>
  <div class="ms-kpi"><div class="ms-kpi-label">Delivery rate (7d)</div><div class="ms-kpi-value"><#if stats.deliveryRate??>${stats.deliveryRate}%<#else>&ndash;</#if></div><div class="ms-kpi-sub"><#if stats.readRate??>${stats.readRate}% read &middot; </#if>${stats.failed7} failed</div></div>
</div>

<div class="ms-grid-2">
  <div class="screenlet ms-card">
    <div class="screenlet-title-bar"><ul><li class="h3">Messages, last 14 days</li></ul></div>
    <div class="screenlet-body">
      <#assign w = 640 h = 190 pad = 26 n = series?size bw = ((w - pad) / n)>
      <svg class="ms-chart" viewBox="0 0 ${w} ${h + 26}" role="img" aria-label="Messages per day">
        <#list 0..4 as g><#assign gy = pad/2 + (h - pad/2) * g / 4><line x1="${pad}" x2="${w}" y1="${gy}" y2="${gy}" class="ms-grid"/></#list>
        <#list series as d>
          <#assign x = pad + d?index * bw>
          <#assign hi = ((d.inC * (h - 20)) / seriesMax)?round>
          <#assign ho = ((d.outC * (h - 20)) / seriesMax)?round>
          <rect x="${x + bw*0.14}" y="${h - hi}" width="${bw*0.34}" height="${hi}" rx="2" class="ms-bar-in"><title>${d.label}: ${d.inC} received</title></rect>
          <rect x="${x + bw*0.52}" y="${h - ho}" width="${bw*0.34}" height="${ho}" rx="2" class="ms-bar-out"><title>${d.label}: ${d.outC} sent</title></rect>
          <#if d?index % 2 == 0><text x="${x + bw/2}" y="${h + 18}" text-anchor="middle" class="ms-axis">${d.label}</text></#if>
        </#list>
        <text x="0" y="${pad/2 + 4}" class="ms-axis">${seriesMax}</text><text x="0" y="${h}" class="ms-axis">0</text>
      </svg>
      <div class="ms-legend"><span><i class="ms-dot-in"></i>Received</span><span><i class="ms-dot-out"></i>Sent</span></div>
    </div>
  </div>

  <div class="screenlet ms-card">
    <div class="screenlet-title-bar"><ul><li class="h3">Getting started</li></ul></div>
    <div class="screenlet-body">
      <#assign doneCount = checklist?filter(c -> c.done)?size>
      <div class="ms-progress-label">${doneCount} of ${checklist?size} done</div>
      <div class="ms-meter ms-meter-lg"><span style="width:${(doneCount * 100 / checklist?size)?round}%"></span></div>
      <ul class="ms-checklist">
        <#list checklist as c>
          <li class="<#if c.done>done</#if>"><span class="ms-check"></span><a href="<@ofbizUrl>${c.target}</@ofbizUrl>">${c.label}</a></li>
        </#list>
      </ul>
    </div>
  </div>
</div>

<div class="screenlet ms-card">
  <div class="screenlet-title-bar"><ul><li class="h3">Recent conversations</li><li class="ms-title-link"><a href="<@ofbizUrl>Inbox</@ofbizUrl>">Open inbox &rarr;</a></li></ul></div>
  <div class="screenlet-body">
    <#if recentContacts?has_content>
    <table class="basic-table hover-bar">
      <tr class="header-row"><td>Customer</td><td>WhatsApp</td><td>Unread</td><td>Last activity</td><td>Handled by</td></tr>
      <#list recentContacts as c>
        <tr><td><a class="ms-person" href="<@ofbizUrl>Inbox?contactId=${c.contactId}</@ofbizUrl>"><span class="ms-av">${(c.profileName!c.waId)?substring(0,1)?upper_case}</span>${c.profileName!c.waId}</a></td>
            <td>+${c.waId}</td>
            <td><#if (c.unreadCount!0) gt 0><span class="ms-pill ms-pill-green">${c.unreadCount}</span><#else>&ndash;</#if></td>
            <td>${(c.lastMessageDate?string("dd MMM, HH:mm"))!}</td>
            <td><#if c.botPaused! == "Y"><span class="ms-pill ms-pill-amber">Agent</span><#else><span class="ms-pill">Bot</span></#if></td></tr>
      </#list>
    </table>
    <#else>
      <div class="ms-empty"><p>No conversations yet.</p><p class="msoft-muted">Connect a WhatsApp number and send "hi" to it to see your bot in action.</p></div>
    </#if>
  </div>
</div>
