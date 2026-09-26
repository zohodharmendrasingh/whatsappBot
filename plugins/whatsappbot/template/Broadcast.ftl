<#-- Broadcasts: create a campaign (audience by tags, send now or later) + list with results -->
<link rel="stylesheet" href="/theme/crm.css?v=3"/>
<div class="cx">
<#if !bcChannels?has_content>
  <div class="cx-card cx-empty-big"><div class="cx-empty-ico">📣</div><h2>Connect a WhatsApp number first</h2>
    <p>Broadcasts are sent from one of your WhatsApp numbers with a template approved by Meta.</p>
    <a class="cx-btn cx-btn-primary" href="<@ofbizUrl>Channels</@ofbizUrl>">Connect WhatsApp number</a></div>
<#else>
  <details class="cx-card cx-new-bc" <#if !campaigns?has_content || parameters.new??>open</#if>>
    <summary><span class="cx-title">📣 New broadcast</span><span class="cx-muted">Send an approved template to many customers at once</span></summary>
    <form id="bcForm" class="bc-form" autocomplete="off"
          data-estimate-url="<@ofbizUrl>campaignEstimate</@ofbizUrl>" data-create-url="<@ofbizUrl>campaignCreate</@ofbizUrl>" data-report-url="<@ofbizUrl>BroadcastReport</@ofbizUrl>">
      <div class="bc-cols">
        <div class="bc-main">
          <div class="bc-step"><span>1</span><b>Message</b></div>
          <div class="cx-grid2">
            <label>Broadcast name <small>for your reports</small><input type="text" name="campaignName" maxlength="100" placeholder="Diwali offer 2026"/></label>
            <label>Send from<select name="channelId"><#list bcChannels as ch><option value="${ch.channelId}">${ch.channelName!""} ${ch.displayPhoneNumber!ch.channelId}</option></#list></select></label>
          </div>
          <label>Template<select name="templateId"></select></label>
          <p class="cx-note" id="bcNoTpl" hidden>No approved templates for this number yet. Create one in Meta WhatsApp Manager, then <a href="<@ofbizUrl>Templates</@ofbizUrl>">sync templates</a>.</p>
          <select id="bcTemplates" hidden><#list bcTemplates as t><option value="${t.templateId}" data-ch="${t.channelId}" data-body="${t.body}" data-n="${t.n}" data-cat="${t.category}">${t.label}</option></#list></select>
          <div id="bcParams" class="cx-grid2"></div>
          <p class="cx-hint" id="bcParamHelp" hidden>Personalise with <code>{{name}}</code><#list bcFields as f>, <code>{{${f.fieldKey}}}</code></#list>. Add a fallback for missing values: <code>{{name|friend}}</code>.</p>

          <div class="bc-step"><span>2</span><b>Who gets it</b></div>
          <div class="bc-auds">
            <label class="bc-aud"><input type="radio" name="audienceType" value="TAGS" <#if bcTags?has_content>checked</#if>/><b>Contacts with tags</b><small>e.g. everyone tagged vip</small></label>
            <label class="bc-aud"><input type="radio" name="audienceType" value="ALL"/><b>All contacts</b><small>of this WhatsApp number</small></label>
            <label class="bc-aud"><input type="radio" name="audienceType" value="NUMBERS" <#if !bcTags?has_content>checked</#if>/><b>Paste numbers</b><small>one per line</small></label>
          </div>
          <div id="bcTagsBox">
            <#if bcTags?has_content>
              <div class="cx-pick" id="bcTagPick"><#list bcTags?keys as t><label><input type="checkbox" value="${t}"/><span>${t} <em>${bcTags[t]}</em></span></label></#list></div>
            <#else>
              <p class="cx-note">No tags yet. Tag your customers on the <a href="<@ofbizUrl>Contacts</@ofbizUrl>">Contacts</a> page to send to groups.</p>
            </#if>
          </div>
          <div id="bcNumbersBox" hidden>
            <textarea name="numbers" rows="5" placeholder="+91 98123 45678&#10;+91 98765 43210"></textarea>
            <small class="cx-muted">With country code. New numbers are added to your contacts.</small>
          </div>
          <#if bcTags?has_content>
          <details class="bc-exclude"><summary>Leave out contacts with these tags <small>(optional)</small></summary>
            <div class="cx-pick" id="bcExcludePick"><#list bcTags?keys as t><label><input type="checkbox" value="${t}"/><span>${t}</span></label></#list></div>
          </details>
          </#if>
          <div class="bc-estimate" id="bcEstimate"></div>

          <div class="bc-step"><span>3</span><b>When</b></div>
          <div class="bc-when">
            <label class="cx-check"><input type="radio" name="when" value="now" checked/> Send now</label>
            <label class="cx-check"><input type="radio" name="when" value="later"/> Schedule for later</label>
            <span id="bcLaterBox" hidden><input type="datetime-local" name="sendAtLocal"/> <small class="cx-muted" id="bcTz"></small></span>
          </div>
          <p class="cx-hint">Opted-out contacts are always skipped. Meta charges your WhatsApp Business Account for each template message delivered.</p>
          <p class="cx-err" id="bcErr" hidden></p>
          <div class="cx-modal-actions"><button type="submit" class="cx-btn cx-btn-primary" id="bcSend">Send broadcast</button></div>
        </div>
        <div class="bc-side">
          <div class="bc-phone" id="bcPreview" hidden>
            <div class="bc-phone-top">Preview <small id="bcCat"></small></div>
            <div class="bc-phone-chat"><div class="bc-bubble" id="bcBubble"></div></div>
            <small class="cx-muted">Shown for a customer named Ravi.</small>
          </div>
        </div>
      </div>
    </form>
  </details>

  <div class="cx-card">
    <div class="cx-card-head"><h3>Your broadcasts</h3></div>
    <#if campaigns?has_content>
    <table class="cx-table bc-list">
      <thead><tr><th>Broadcast</th><th>Status</th><th class="num">Recipients</th><th class="num">Delivered</th><th class="num">Read</th><th class="num">Replied</th><th class="num">Failed</th></tr></thead>
      <tbody>
      <#list campaigns as row>
        <#assign c = row.c s = row.s>
        <tr class="cx-click" onclick="location.href='<@ofbizUrl>BroadcastReport?campaignId=${c.campaignId}</@ofbizUrl>'">
          <td><a href="<@ofbizUrl>BroadcastReport?campaignId=${c.campaignId}</@ofbizUrl>"><b>${c.campaignName!c.templateName!}</b></a>
              <small class="cx-muted cx-block">${c.templateName!} &middot; <#if c.statusId == "SCHEDULED">${(c.scheduledDate?string("dd MMM, HH:mm"))!}<#else>${(c.createdDate?string("dd MMM, HH:mm"))!}</#if></small></td>
          <td><span class="cx-pill cx-st-${c.statusId}">${bcStatusLabel[c.statusId]!c.statusId}</span></td>
          <td class="num">${(c.totalCount!s.total)?string(",##0")}</td>
          <td class="num">${s.delivered?string(",##0")}<#if (s.sent > 0)><small class="cx-muted"> ${(s.delivered * 100 / s.sent)?round}%</small></#if></td>
          <td class="num">${s.read?string(",##0")}<#if (s.sent > 0)><small class="cx-muted"> ${(s.read * 100 / s.sent)?round}%</small></#if></td>
          <td class="num">${s.replied?string(",##0")}<#if (s.sent > 0)><small class="cx-muted"> ${(s.replied * 100 / s.sent)?round}%</small></#if></td>
          <td class="num<#if (s.failed > 0)> cx-danger</#if>">${s.failed?string(",##0")}</td>
        </tr>
      </#list>
      </tbody>
    </table>
    <#else>
      <div class="cx-empty"><p>No broadcasts yet. Your first one will show up here with delivered, read and reply counts.</p></div>
    </#if>
  </div>
</#if>
</div>
<script src="/js/crm.js?v=3"></script>
