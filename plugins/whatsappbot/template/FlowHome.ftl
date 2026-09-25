<#-- Bot Flows home: create a bot (AI / template / blank) + list of flows -->
<link rel="stylesheet" href="/theme/flowbuilder.css?v=3"/>
<div class="fh">
<#if canCreateFlow>
  <section class="fh-create">
    <div class="fh-create-head">
      <h2>Create a new bot</h2>
      <p>Pick how you want to start. You can change everything afterwards in the visual builder.</p>
    </div>
    <div class="fh-tabs" role="tablist">
      <button type="button" class="on" data-tab="ai" role="tab">✨ Build with AI</button>
      <button type="button" data-tab="tpl" role="tab">📋 Use a template</button>
      <button type="button" data-tab="blank" role="tab">✏️ Start blank</button>
    </div>

    <div class="fh-pane on" data-pane="ai">
      <#if aiConfigured>
        <form method="post" action="<@ofbizUrl>flowWizard</@ofbizUrl>" class="fh-ai" data-busy="Designing your bot... this usually takes 20-40 seconds.">
          <input type="hidden" name="mode" value="ai"/>
          <label for="fhDesc">Describe your business and what the bot should do</label>
          <textarea id="fhDesc" name="description" rows="4" required minlength="5" placeholder="Example: We are a jewellery shop in Gwalior. Show gold, diamond and silver collections, let customers book a store visit, ask for price of an item, and talk to our staff."></textarea>
          <div class="fh-ideas">
            <span>Try:</span>
            <button type="button" data-idea="Restaurant: show menu, book a table (people, date, time), take delivery orders with address, opening hours, talk to staff.">🍽️ Restaurant</button>
            <button type="button" data-idea="Dental clinic: book an appointment (service, date, morning/afternoon/evening, patient name), clinic timings, emergency: talk to doctor.">🦷 Clinic</button>
            <button type="button" data-idea="Real estate agency: ask buy or rent, property type, budget range, preferred area, then collect name and hand over to an agent.">🏠 Real estate</button>
            <button type="button" data-idea="Online clothing store: order status by order number, returns and exchanges, size guide, payment options, talk to support.">👗 Online store</button>
          </div>
          <div class="fh-row">
            <input type="text" name="flowName" placeholder="Bot name (optional)" maxlength="60"/>
            <button type="submit" class="fh-btn fh-btn-primary">✨ Build my bot</button>
          </div>
        </form>
      <#else>
        <div class="fh-connect">
          <div class="fh-connect-ico">✨</div>
          <div>
            <b>Connect your Claude API key to build bots with AI</b>
            <p>Describe your business in one sentence and AI designs the whole bot. You use your own Claude key, so you control the cost (usually a few cents per bot).</p>
            <a class="fh-btn fh-btn-primary" href="<@ofbizUrl>Settings</@ofbizUrl>#ai">Add Claude API key</a>
            <span class="fh-or">or start from a template (no key needed)</span>
          </div>
        </div>
      </#if>
    </div>

    <div class="fh-pane" data-pane="tpl">
      <div class="fh-tpls">
        <#list flowTemplates as t>
          <form method="post" action="<@ofbizUrl>flowWizard</@ofbizUrl>" class="fh-tpl">
            <input type="hidden" name="mode" value="template"/>
            <input type="hidden" name="templateId" value="${t.id}"/>
            <div class="fh-tpl-ico">${t.icon}</div>
            <strong>${t.name}</strong>
            <p>${t.description}</p>
            <div class="fh-tpl-foot"><small>${t.steps} steps</small><button type="submit" class="fh-btn">Use this</button></div>
          </form>
        </#list>
      </div>
    </div>

    <div class="fh-pane" data-pane="blank">
      <form method="post" action="<@ofbizUrl>flowWizard</@ofbizUrl>" class="fh-row">
        <input type="hidden" name="mode" value="blank"/>
        <input type="text" name="flowName" placeholder="Bot name, e.g. Main menu" maxlength="60" required/>
        <button type="submit" class="fh-btn fh-btn-primary">Create and open builder</button>
      </form>
    </div>
  </section>
<#else>
  <div class="fh-note">Select a workspace in the top bar to create bots.</div>
</#if>

  <section class="fh-list">
    <h2>Your bots <small>${flowList?size}</small></h2>
    <#if !flowList?has_content>
      <div class="fh-empty">No bots yet. Create your first one above; it takes less than a minute.</div>
    </#if>
    <div class="fh-cards">
    <#list flowList as f>
      <div class="fh-card">
        <div class="fh-card-top">
          <a class="fh-card-name" href="<@ofbizUrl>FlowBuilder?flowId=${f.flowId}</@ofbizUrl>">${f.flowName!f.flowId}</a>
          <#if f.isActive! == "N"><span class="ms-pill ms-pill-red">Off</span><#else><span class="ms-pill ms-pill-green">Live</span></#if>
        </div>
        <div class="fh-card-meta">
          ${flowSteps[f.flowId]!0} steps
          <#if f.isDefault! == "Y"> &middot; <span title="Runs when a message matches no keyword">answers any message</span></#if>
          <#list flowChannels[f.flowId]![] as cn> &middot; default for ${cn}</#list>
          <#if (isWaAdmin!"N") == "Y" && !currentTenantId?has_content> &middot; ${f.tenantId}</#if>
        </div>
        <div class="fh-kw">
          <#if f.triggerKeywords?has_content>
            <#list f.triggerKeywords?split(",") as k><#if k?trim?has_content><span>${k?trim}</span></#if></#list>
          <#else><em>No keywords</em></#if>
        </div>
        <div class="fh-card-actions">
          <a class="fh-btn fh-btn-primary" href="<@ofbizUrl>FlowBuilder?flowId=${f.flowId}</@ofbizUrl>">Open builder</a>
          <form method="post" action="<@ofbizUrl>deleteWaFlow</@ofbizUrl>" onsubmit="return confirm('Delete this bot and all its steps?');">
            <input type="hidden" name="flowId" value="${f.flowId}"/>
            <button type="submit" class="fh-btn fh-btn-ghost">Delete</button>
          </form>
        </div>
      </div>
    </#list>
    </div>
  </section>
</div>
<div class="fh-busy" id="fhBusy" hidden><div class="fh-busy-box"><div class="fh-spin"></div><p id="fhBusyText">Working...</p></div></div>
<script type="application/javascript">
(function () {
  document.querySelectorAll('.fh-tabs button').forEach(function (b) {
    b.addEventListener('click', function () {
      document.querySelectorAll('.fh-tabs button').forEach(function (x) { x.classList.toggle('on', x === b); });
      document.querySelectorAll('.fh-pane').forEach(function (p) { p.classList.toggle('on', p.dataset.pane === b.dataset.tab); });
    });
  });
  document.querySelectorAll('[data-idea]').forEach(function (b) {
    b.addEventListener('click', function () { var t = document.getElementById('fhDesc'); t.value = b.dataset.idea; t.focus(); });
  });
  document.querySelectorAll('.fh form[action*="flowWizard"]').forEach(function (f) {
    f.addEventListener('submit', function () {
      document.getElementById('fhBusyText').textContent = f.dataset.busy || 'Creating your bot...';
      document.getElementById('fhBusy').hidden = false;
    });
  });
})();
</script>
