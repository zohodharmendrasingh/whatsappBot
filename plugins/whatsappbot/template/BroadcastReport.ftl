<#-- One broadcast: funnel (sent -> delivered -> read -> replied), actions and recipients -->
<link rel="stylesheet" href="/theme/crm.css?v=4"/>
<div class="cx" id="bcReport" data-live="<#if (campaignLive!false)>Y<#else>N</#if>">
<#if !campaign??>
  <div class="cx-card cx-empty"><p>Broadcast not found.</p><a class="cx-btn" href="<@ofbizUrl>Broadcast</@ofbizUrl>">← All broadcasts</a></div>
<#else>
  <#assign c = campaign s = campaignStats>
  <a class="cx-link" href="<@ofbizUrl>Broadcast</@ofbizUrl>">← All broadcasts</a>
  <#if parameters.created! == "scheduled"><div class="cx-flash">Scheduled. It will be sent on ${(c.scheduledDate?string("dd MMM yyyy 'at' HH:mm"))!}.</div>
  <#elseif parameters.created! == "sending"><div class="cx-flash">Sending started. This page updates by itself.</div></#if>
  <div class="cx-head">
    <div>
      <h2 class="cx-title">${c.campaignName!c.templateName!} <span class="cx-pill cx-st-${c.statusId}">${bcStatusLabel[c.statusId]!c.statusId}</span></h2>
      <p class="cx-sub">Template <b>${c.templateName!}</b> &middot; from ${(campaignChannel.displayPhoneNumber)!c.channelId} &middot; ${campaignAudience}
        <br/><#if c.statusId == "SCHEDULED">Scheduled for ${(c.scheduledDate?string("dd MMM yyyy, HH:mm"))!}<#else>Started ${(c.startedDate?string("dd MMM yyyy, HH:mm"))!"–"}<#if c.completedDate??> &middot; finished ${c.completedDate?string("HH:mm")}</#if></#if>
        &middot; by ${c.createdBy!}</p>
    </div>
    <div class="cx-actions">
      <#if c.statusId == "SCHEDULED">
        <form method="post" action="<@ofbizUrl>campaignAction</@ofbizUrl>"><input type="hidden" name="campaignId" value="${c.campaignId}"/><input type="hidden" name="do" value="sendnow"/><button class="cx-btn">Send now</button></form>
      </#if>
      <#if campaignCanResume>
        <form method="post" action="<@ofbizUrl>campaignAction</@ofbizUrl>"><input type="hidden" name="campaignId" value="${c.campaignId}"/><input type="hidden" name="do" value="resume"/><button class="cx-btn cx-btn-primary">Resume sending</button></form>
      </#if>
      <#if c.statusId == "SCHEDULED" || c.statusId == "SENDING" || c.statusId == "FAILED">
        <form method="post" action="<@ofbizUrl>campaignAction</@ofbizUrl>" onsubmit="return confirm('Cancel this broadcast? Messages already sent stay sent.')"><input type="hidden" name="campaignId" value="${c.campaignId}"/><input type="hidden" name="do" value="cancel"/><button class="cx-btn cx-btn-danger">Cancel</button></form>
      </#if>
      <#if (s.total > 0)><a class="cx-btn" href="<@ofbizUrl>campaignExport?campaignId=${c.campaignId}</@ofbizUrl>">⬇ Export CSV</a></#if>
    </div>
  </div>
  <#if c.errorText?has_content><div class="cx-note cx-note-warn">${c.errorText}</div></#if>

  <#assign base = [s.sent, 1]?max>
  <div class="bc-funnel">
    <div class="bc-f"><span>Recipients</span><b>${(c.totalCount!s.total)?string(",##0")}</b><small><#if (s.pending > 0)>${s.pending} still to send<#elseif (s.skipped > 0)>${s.skipped} skipped (opted out)<#else>&nbsp;</#if></small></div>
    <div class="bc-f"><span>Sent</span><b>${s.sent?string(",##0")}</b><small>accepted by WhatsApp</small></div>
    <div class="bc-f"><span>Delivered</span><b>${s.delivered?string(",##0")}</b><small>${(s.delivered * 100 / base)?round}% of sent</small><i style="width:${(s.delivered * 100 / base)?round}%"></i></div>
    <div class="bc-f"><span>Read</span><b>${s.read?string(",##0")}</b><small>${(s.read * 100 / base)?round}% of sent</small><i style="width:${(s.read * 100 / base)?round}%"></i></div>
    <div class="bc-f"><span>Replied</span><b>${s.replied?string(",##0")}</b><small>${(s.replied * 100 / base)?round}% of sent</small><i style="width:${(s.replied * 100 / base)?round}%"></i></div>
    <div class="bc-f<#if (s.failed > 0)> bad</#if>"><span>Failed</span><b>${s.failed?string(",##0")}</b><small><#if (s.failed > 0)>see errors below<#else>&nbsp;</#if></small></div>
  </div>
  <p class="cx-hint">Read counts can be lower than reality: customers who turned off read receipts are counted as read only when they reply.</p>

  <div class="cx-card">
    <div class="cx-card-head"><h3>Recipients</h3>
      <div class="cx-seg">
        <#list [["","All"],["DELIVERED","Delivered"],["READ","Read"],["REPLIED","Replied"],["FAILED","Failed"],["SKIPPED","Skipped"],["PENDING","Not sent"]] as f>
          <a class="<#if recipientFilter == f[0]>on</#if>" href="<@ofbizUrl>BroadcastReport?campaignId=${c.campaignId}&amp;status=${f[0]}</@ofbizUrl>">${f[1]}</a>
        </#list>
      </div>
    </div>
    <#if recipients?has_content>
    <table class="cx-table">
      <thead><tr><th>Customer</th><th>Status</th><th class="cx-hide-sm">Sent</th><th class="cx-hide-sm">Delivered</th><th class="cx-hide-sm">Read</th><th>Replied</th></tr></thead>
      <tbody>
      <#list recipients as r>
        <tr>
          <td><a href="<@ofbizUrl>Inbox?contactId=${r.contactId}</@ofbizUrl>"><b>${recipientNames[r.contactId]!("+" + r.waId)}</b></a><small class="cx-muted cx-block">+${r.waId}</small></td>
          <td><span class="cx-pill cx-rs-${r.statusId}">${r.statusId?lower_case?cap_first}</span><#if r.errorText?has_content><small class="cx-block cx-danger">${r.errorText}</small></#if></td>
          <td class="cx-hide-sm cx-muted">${(r.sentDate?string("dd MMM HH:mm"))!"–"}</td>
          <td class="cx-hide-sm cx-muted">${(r.deliveredDate?string("dd MMM HH:mm"))!"–"}</td>
          <td class="cx-hide-sm cx-muted">${(r.readDate?string("dd MMM HH:mm"))!"–"}</td>
          <td><#if r.repliedDate??><span class="cx-pill cx-pill-green">${r.repliedDate?string("dd MMM HH:mm")}</span><#else><span class="cx-muted">–</span></#if></td>
        </tr>
      </#list>
      </tbody>
    </table>
    <#if (recipients?size >= 200)><p class="cx-hint">Showing the first 200. Export CSV for everyone.</p></#if>
    <#else>
      <div class="cx-empty"><p><#if c.statusId == "SCHEDULED">The recipient list is made when the broadcast starts.<#else>Nobody here.</#if></p></div>
    </#if>
  </div>
</#if>
</div>
<script src="/js/crm.js?v=4"></script>
