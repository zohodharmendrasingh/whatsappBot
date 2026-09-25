<#-- Settings > Zoho: each workspace connects its own Zoho account (CRM, Books, Inventory, People) -->
<div class="fc-zoho" id="zoho">
  <#if zohoFlash??>
    <div class="fc-zoho-flash ${zohoFlash.ok?then('ok','err')}">${zohoFlash.msg}</div>
  </#if>
  <#if zoho.connected>
    <div class="fc-zoho-status">
      <#if zoho.status! == "ERROR"><span class="ms-pill ms-pill-red">Needs reconnect</span><#else><span class="ms-pill ms-pill-green">✓ Connected</span></#if>
      Zoho ${zoho.dcName!} data centre
      <#if zoho.connectedDate??><small> &middot; since ${zoho.connectedDate?string("dd MMM yyyy")}</small></#if>
    </div>
    <div class="fc-zoho-apps">
      <#list zohoApps as a>
        <span class="fc-zoho-app<#if zoho.apps?seq_contains(a)> on</#if>">${zoho.apps?seq_contains(a)?then("✓ ","")}${zohoAppNames[a]}</span>
      </#list>
    </div>
    <#if zoho.lastError?has_content>
      <div class="fc-zoho-err">Last problem<#if zoho.lastErrorDate??> (${zoho.lastErrorDate?string("dd MMM HH:mm")})</#if>: ${zoho.lastError}</div>
    </#if>
    <#if zohoIsOwner && (zohoBooksOrgs?? || zohoInventoryOrgs??)>
      <form method="post" action="<@ofbizUrl>zohoSaveOrgs</@ofbizUrl>" class="fc-zoho-orgs">
        <#if zohoBooksOrgs??>
          <label>Zoho Books organization
            <select name="booksOrgId"><#list zohoBooksOrgs as o><option value="${o.id}"<#if o.id == (zoho.booksOrgId!"")> selected</#if>>${o.name}</option></#list><#if !zohoBooksOrgs?has_content><option value="">No organization found</option></#if></select>
          </label>
        </#if>
        <#if zohoInventoryOrgs??>
          <label>Zoho Inventory organization
            <select name="inventoryOrgId"><#list zohoInventoryOrgs as o><option value="${o.id}"<#if o.id == (zoho.inventoryOrgId!"")> selected</#if>>${o.name}</option></#list><#if !zohoInventoryOrgs?has_content><option value="">No organization found</option></#if></select>
          </label>
        </#if>
        <button type="submit" class="smallSubmit">Save organization</button>
      </form>
    </#if>
  <#else>
    <div class="fc-zoho-status"><span class="ms-pill ms-pill-amber">Not connected</span> Connect Zoho so your bots can create CRM leads, look up invoices, check stock and more.</div>
  </#if>

  <#if zohoIsOwner>
    <#if zoho.platformConfigured || zoho.ownClientId?has_content>
      <form method="post" action="<@ofbizUrl>zohoConnect</@ofbizUrl>" class="fc-zoho-connect">
        <div class="fc-zoho-pick">
          <#list zohoApps as a>
            <label><input type="checkbox" name="apps" value="${a}"<#if !zoho.connected || zoho.apps?seq_contains(a)> checked</#if>/> ${zohoAppNames[a]}</label>
          </#list>
        </div>
        <button type="submit" class="smallSubmit">${zoho.connected?then("Update apps / reconnect","Connect Zoho")}</button>
        <small>You will sign in to Zoho and approve access. Your data stays in your Zoho account.</small>
      </form>
    <#else>
      <p class="fc-ai-note">Zoho connection is not switched on for FloChat yet. You can use your own Zoho API client under Advanced below.</p>
    </#if>
    <#if zoho.connected>
      <form method="post" action="<@ofbizUrl>zohoDisconnect</@ofbizUrl>" onsubmit="return confirm('Disconnect Zoho? Zoho steps in your bots will stop working until you connect again.');" class="fc-ai-remove">
        <button type="submit" class="buttontext">Disconnect Zoho</button>
      </form>
    </#if>
    <details class="fc-ai-help"<#if zoho.ownClientId?has_content> open</#if>>
      <summary>Advanced: use your own Zoho API client</summary>
      <p>Only needed if your company requires its own client. In <a href="https://api-console.zoho.com/" target="_blank" rel="noopener">api-console.zoho.com</a> create a <b>Server-based Application</b> with this redirect URL:</p>
      <p><code>${zohoRedirectUri}</code></p>
      <form method="post" action="<@ofbizUrl>zohoSaveClient</@ofbizUrl>" class="fc-ai-form" autocomplete="off">
        <label>Client ID <input type="text" name="clientId" value="${zoho.ownClientId!}" placeholder="1000.XXXXXXXX"/></label>
        <label>Client secret <input type="password" name="clientSecret" autocomplete="new-password" placeholder="<#if zoho.ownClientId?has_content>saved (paste to replace)</#if>"/></label>
        <div class="fc-ai-actions"><button type="submit" class="smallSubmit">Save client</button></div>
      </form>
      <#if zoho.ownClientId?has_content><p><small>To go back to the FloChat client, clear the Client ID and save.</small></p></#if>
    </details>
  <#else>
    <p class="fc-ai-note">Only the workspace owner can connect or change Zoho.</p>
  </#if>
</div>
