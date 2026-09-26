<#-- Developer portal: quick start, API keys, webhooks, logs, API reference with Try it -->
<link rel="stylesheet" href="/theme/crm.css?v=5"/>
<div class="cx dv" id="dvPage" data-owner="<#if isOwner>Y<#else>N</#if>" data-api="${apiBaseUrl}"
     data-key-create="<@ofbizUrl>devKeyCreate</@ofbizUrl>" data-key-revoke="<@ofbizUrl>devKeyRevoke</@ofbizUrl>"
     data-hook-save="<@ofbizUrl>webhookSave</@ofbizUrl>" data-hook-action="<@ofbizUrl>webhookAction</@ofbizUrl>"
     data-hook-deliveries="<@ofbizUrl>webhookDeliveries</@ofbizUrl>" data-hook-redeliver="<@ofbizUrl>webhookRedeliver</@ofbizUrl>"
     data-try="<@ofbizUrl>devTry</@ofbizUrl>" data-spec="/api/v1/openapi.json" data-tab="${devTab}">
  <div class="cx-head">
    <div><h2 class="cx-title">Developers</h2>
      <p class="cx-sub">Connect ${brandName} to your website, app, CRM or store: send WhatsApp messages, sync contacts, run broadcasts and get events in real time.</p></div>
    <div class="cx-actions"><a class="cx-btn" href="/api/v1/openapi.json" download="flochat-openapi.json">⬇ OpenAPI (Postman)</a></div>
  </div>
  <div class="cx-seg dv-tabs" role="tablist">
    <#list [["overview","Overview"],["keys","API keys"],["webhooks","Webhooks"],["logs","Logs"],["reference","API reference"]] as t>
      <a href="#${t[0]}" data-tab="${t[0]}" class="<#if devTab == t[0]>on</#if>">${t[1]}</a>
    </#list>
  </div>

  <section class="dv-pane" data-pane="overview">
    <div class="dv-cards">
      <div class="cx-card dv-stat"><span>API calls (24 h)</span><b>${calls24?string(",##0")}</b><small>${errors24} with errors</small></div>
      <div class="cx-card dv-stat"><span>Active API keys</span><b>${devKeys?filter(k -> k.isActive! == "Y")?size}</b><small>max ${rateLimit} calls / minute per key</small></div>
      <div class="cx-card dv-stat"><span>Webhooks</span><b>${devHooks?filter(h -> h.isActive! == "Y")?size}</b><small>real-time events to your URL</small></div>
    </div>
    <div class="cx-card dv-quick">
      <div class="cx-card-head"><h3>Quick start</h3></div>
      <ol class="dv-steps">
        <li><b>Create an API key</b> on the <a href="#keys" data-go="keys">API keys</a> tab. Keep it secret, like a password.</li>
        <li><b>Check it works:</b><pre class="dv-code">curl ${apiBaseUrl}/account \
  -H "X-Api-Key: YOUR_KEY"</pre></li>
        <li><b>Send your first template message:</b><pre class="dv-code">curl -X POST ${apiBaseUrl}/messages \
  -H "X-Api-Key: YOUR_KEY" -H "Content-Type: application/json" \
  -d '{"to":"919812345678","template":{"name":"order_update","language":"en","params":["Ravi","#A-1024"]}}'</pre></li>
        <li><b>Get replies and delivery updates</b> in real time with a <a href="#webhooks" data-go="webhooks">webhook</a>.</li>
      </ol>
      <div class="dv-facts">
        <div><b>Base URL</b><code>${apiBaseUrl}</code></div>
        <div><b>Auth</b><code>X-Api-Key: &lt;key&gt;</code> or <code>Authorization: Bearer &lt;key&gt;</code></div>
        <div><b>Format</b>JSON in, JSON out. Times in ISO 8601 (UTC).</div>
        <div><b>Errors</b><code>{"success":false,"error":"…","code":"…"}</code> with HTTP 4xx/5xx</div>
        <div><b>24-hour rule</b>Free text only within 24 h of the customer's last message; templates any time.</div>
        <div><b>Zoho</b>Use <code>invokeurl</code> in Deluge. Every endpoint in the reference has a Deluge example.</div>
      </div>
    </div>
  </section>

  <section class="dv-pane" data-pane="keys" hidden>
    <div class="cx-card">
      <div class="cx-card-head"><h3>API keys</h3>
        <#if isOwner><form class="dv-inline" id="dvKeyForm"><input type="text" name="description" maxlength="100" placeholder="What is it for? e.g. Zoho CRM" required/><button class="cx-btn cx-btn-primary">+ Create key</button></form></#if></div>
      <div class="dv-newkey" id="dvNewKey" hidden>
        <b>Your new API key</b> <span class="cx-muted">Copy it now: for your security it is shown only once.</span>
        <div class="dv-copy"><code id="dvNewKeyVal"></code><button type="button" class="cx-btn cx-btn-sm" data-copy="#dvNewKeyVal">Copy</button></div>
      </div>
      <#if devKeys?has_content>
      <table class="cx-table">
        <thead><tr><th>Name</th><th>Key</th><th>Created</th><th>Last used</th><th>Status</th><th></th></tr></thead>
        <tbody><#list devKeys as k>
          <tr><td><b>${k.description!"API key"}</b></td><td><code>${k.keyPrefix!}</code></td><td class="cx-muted">${(k.createdDate?string("dd MMM yyyy"))!}</td>
            <td class="cx-muted">${(k.lastUsedDate?string("dd MMM yyyy HH:mm"))!"never"}</td>
            <td><#if k.isActive! == "Y"><span class="cx-pill cx-pill-green">Active</span><#else><span class="cx-pill cx-st-CANCELLED">Revoked</span></#if></td>
            <td class="cx-row-actions"><#if isOwner && k.isActive! == "Y"><button type="button" class="cx-link cx-danger" data-revoke="${k.apiKeyId}">Revoke</button></#if></td></tr>
        </#list></tbody>
      </table>
      <#else><div class="cx-empty"><p>No API keys yet.<#if !isOwner> Ask the workspace owner to create one.</#if></p></div></#if>
    </div>
  </section>

  <section class="dv-pane" data-pane="webhooks" hidden>
    <div class="cx-card">
      <div class="cx-card-head"><h3>Webhooks</h3><#if isOwner><button type="button" class="cx-btn cx-btn-primary" id="dvHookNew">+ Add webhook</button></#if></div>
      <p class="cx-hint dv-pad">We POST a JSON event to your URL when something happens. Each request is signed: check <code>X-FloChat-Signature</code> (see <a href="#reference" data-go="reference">Verifying webhooks</a>). Answer with any 2xx within 15 seconds; failures are retried after 30 s, 2 min and 10 min.</p>
      <div class="dv-newkey" id="dvNewSecret" hidden>
        <b>Signing secret</b> <span class="cx-muted">Copy it now: it is shown only once. Use it to verify our signature.</span>
        <div class="dv-copy"><code id="dvNewSecretVal"></code><button type="button" class="cx-btn cx-btn-sm" data-copy="#dvNewSecretVal">Copy</button></div>
      </div>
      <#if devHooks?has_content>
      <ul class="dv-hooks">
        <#list devHooks as h>
        <li data-id="${h.webhookId}" data-url="${h.url!}" data-events="${h.events!}" data-desc="${h.description!}" data-active="${h.isActive!}">
          <div class="dv-hook-main">
            <b>${h.url!}</b>
            <small class="cx-muted">${h.description!""}<#if h.description?has_content> &middot; </#if>${(h.events!"")?replace(",", ", ")}</small>
            <small class="cx-muted">Last delivery: <#if h.lastDeliveryDate??>${h.lastDeliveryDate?string("dd MMM HH:mm")} &middot; <span class="<#if (h.lastStatus!"")?starts_with("2")>dv-ok<#else>cx-danger</#if>">${h.lastStatus!}</span><#else>none yet</#if><#if (h.failCount!0) gt 0> &middot; <span class="cx-danger">${h.failCount} failing</span></#if></small>
          </div>
          <div class="dv-hook-act">
            <#if h.isActive! == "Y"><span class="cx-pill cx-pill-green">On</span><#else><span class="cx-pill cx-st-CANCELLED">Off</span></#if>
            <button type="button" class="cx-link" data-hook-do="test">Send test</button>
            <button type="button" class="cx-link" data-hook-log>Deliveries</button>
            <#if isOwner><button type="button" class="cx-link" data-hook-edit>Edit</button>
            <button type="button" class="cx-link" data-hook-do="${(h.isActive! == "Y")?then("disable", "enable")}">${(h.isActive! == "Y")?then("Turn off", "Turn on")}</button>
            <button type="button" class="cx-link" data-hook-do="rotate">New secret</button>
            <button type="button" class="cx-link cx-danger" data-hook-do="delete">Delete</button></#if>
          </div>
          <div class="dv-test" hidden></div>
        </li>
        </#list>
      </ul>
      <#else><div class="cx-empty"><p>No webhooks yet. Add one to get customer messages, delivery updates and hand-overs in your own system.</p></div></#if>
    </div>
  </section>

  <section class="dv-pane" data-pane="logs" hidden>
    <div class="cx-card">
      <div class="cx-card-head"><h3>Recent API calls</h3><span class="cx-muted">last 100 &middot; kept 30 days</span></div>
      <#if devLogs?has_content>
      <table class="cx-table dv-logs">
        <thead><tr><th>Time</th><th>Request</th><th>Status</th><th class="num">Time</th><th class="cx-hide-sm">Key</th><th class="cx-hide-sm">Error</th></tr></thead>
        <tbody><#list devLogs as l>
          <tr><td class="cx-muted">${(l.createdDate?string("dd MMM HH:mm:ss"))!}</td><td><code><b>${l.method!}</b> ${l.path!}</code></td>
            <td><span class="cx-pill <#if (l.statusCode!0) lt 300>cx-pill-green<#elseif (l.statusCode!0) lt 500>cx-st-SENDING<#else>cx-pill-red</#if>">${l.statusCode!}</span></td>
            <td class="num cx-muted">${l.durationMs!0} ms</td><td class="cx-hide-sm cx-muted"><#if (l.apiKeyId!"") == "CONSOLE">Try it console<#else>${keyNames[l.apiKeyId!]!(l.apiKeyId!"–")}</#if></td>
            <td class="cx-hide-sm cx-danger">${l.errorText!""}</td></tr>
        </#list></tbody>
      </table>
      <#else><div class="cx-empty"><p>No API calls yet.</p></div></#if>
    </div>
  </section>

  <section class="dv-pane" data-pane="reference" hidden>
    <div class="dv-ref">
      <nav class="dv-nav" id="dvNav"><span class="cx-muted">Loading…</span></nav>
      <div class="dv-docs" id="dvDocs"></div>
    </div>
  </section>

  <div class="cx-modal" id="dvHookModal" hidden>
    <form class="cx-modal-box" id="dvHookForm" autocomplete="off">
      <h3 id="dvHookTitle">Add webhook</h3>
      <input type="hidden" name="webhookId"/>
      <label>Your URL <small>https:// address that accepts POST</small><input type="url" name="url" required placeholder="https://yourapp.com/flochat/webhook"/></label>
      <label>Description <small>optional</small><input type="text" name="description" maxlength="200" placeholder="Zoho CRM sync"/></label>
      <div class="dv-evs"><b>Events</b>
        <#list hookEvents as e><label class="cx-check"><input type="checkbox" name="events" value="${e}" checked/> <code>${e}</code>
          <small class="cx-muted"><#switch e><#case "message.received">a customer sent a message<#break><#case "message.status">sent, delivered, read or failed<#break><#case "contact.created">a new contact was added<#break><#case "conversation.handoff">a chat was handed to your team<#break><#default>a broadcast finished</#switch></small></label></#list>
      </div>
      <p class="cx-err" id="dvHookErr" hidden></p>
      <div class="cx-modal-actions"><button type="button" class="cx-btn" data-close>Cancel</button><button type="submit" class="cx-btn cx-btn-primary">Save</button></div>
    </form>
  </div>
  <div class="cx-modal" id="dvDelModal" hidden>
    <div class="cx-modal-box wide"><h3>Deliveries</h3><div id="dvDelList"></div>
      <div class="cx-modal-actions"><button type="button" class="cx-btn" data-close>Close</button></div></div>
  </div>
  <div class="cx-toast" id="cxToast"></div>
</div>
<script src="/js/crm.js?v=5"></script>
