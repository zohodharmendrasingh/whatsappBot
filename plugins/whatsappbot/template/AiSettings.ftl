<#-- Settings > AI assistant: each workspace connects its own Claude or OpenAI key -->
<div class="fc-ai" id="ai">
  <div class="fc-ai-status">
    <#if aiKeySet>
      <span class="ms-pill ms-pill-green">✓ Connected</span> ${aiProviderName} API key <code>${aiKeyHint}</code>
      <#if aiUpdated??><small> &middot; added ${aiUpdated?string("dd MMM yyyy")}</small></#if>
    <#else>
      <span class="ms-pill ms-pill-amber">Not connected</span> Add a Claude or OpenAI key to build and edit bots with AI.
    </#if>
  </div>
  <#if aiIsOwner>
    <form method="post" action="<@ofbizUrl>saveAiKey</@ofbizUrl>" class="fc-ai-form fc-ai-form2" autocomplete="off" id="fcAiForm">
      <div class="fc-ai-prov" role="radiogroup" aria-label="AI provider">
        <label class="fc-ai-card"><input type="radio" name="provider" value="anthropic"<#if aiProvider == "anthropic"> checked</#if>/>
          <span><b>Claude</b><small>by Anthropic &middot; recommended</small></span></label>
        <label class="fc-ai-card"><input type="radio" name="provider" value="openai"<#if aiProvider == "openai"> checked</#if>/>
          <span><b>OpenAI</b><small>GPT models</small></span></label>
      </div>
      <div class="fc-ai-fields">
        <label><span data-prov="anthropic">Claude API key</span><span data-prov="openai">OpenAI API key</span>
          <input type="password" name="apiKey" autocomplete="new-password" data-hint="${aiKeySet?then(aiKeyHint,'')}" data-saved-prov="${aiProvider}"/>
        </label>
        <label data-prov="anthropic">Model
          <select name="model.anthropic"><#list aiModels?keys as m><option value="${m}"<#if aiProvider == "anthropic" && (aiModel == m || (!aiModel?has_content && m?index == 0))> selected</#if>>${aiModels[m]}</option></#list></select>
        </label>
        <label data-prov="openai">Model
          <select name="model.openai"><#list aiOpenAiModels?keys as m><option value="${m}"<#if aiProvider == "openai" && (aiModel == m || (!aiModel?has_content && m?index == 0))> selected</#if>>${aiOpenAiModels[m]}</option></#list></select>
        </label>
        <div class="fc-ai-actions"><button type="submit" class="smallSubmit">${aiKeySet?then("Save","Connect key")}</button></div>
      </div>
    </form>
    <#if aiKeySet>
      <form method="post" action="<@ofbizUrl>removeAiKey</@ofbizUrl>" onsubmit="return confirm('Remove the AI key? AI building will stop until you add a key again.');" class="fc-ai-remove">
        <button type="submit" class="buttontext">Remove key</button>
      </form>
    </#if>
  <#else>
    <p class="fc-ai-note">Only the workspace owner can add or change the AI key.</p>
  </#if>
  <details class="fc-ai-help" data-prov="anthropic">
    <summary>How do I get a Claude API key?</summary>
    <ol>
      <li>Open <a href="https://console.anthropic.com/" target="_blank" rel="noopener">console.anthropic.com</a> and sign in or create an account.</li>
      <li>Go to <b>Billing</b> and add a small amount of credit (building a bot usually costs a few cents).</li>
      <li>Go to <b>API keys</b>, click <b>Create key</b>, copy it and paste it above.</li>
    </ol>
  </details>
  <details class="fc-ai-help" data-prov="openai">
    <summary>How do I get an OpenAI API key?</summary>
    <ol>
      <li>Open <a href="https://platform.openai.com/api-keys" target="_blank" rel="noopener">platform.openai.com</a> and sign in or create an account.</li>
      <li>Go to <b>Billing</b> and add a small amount of credit (a ChatGPT Plus subscription does not include API usage).</li>
      <li>Go to <b>API keys</b>, click <b>Create new secret key</b>, copy it and paste it above.</li>
    </ol>
  </details>
  <p class="fc-ai-note">Your key is stored encrypted and is only used when you press ✨ AI in the bot builder. Usage is billed by the AI company to your own account.</p>
</div>
<script>
(function () {
  var f = document.getElementById('fcAiForm');
  if (!f) { return; }
  var key = f.querySelector('input[name=apiKey]');
  function upd() {
    var p = (f.querySelector('input[name=provider]:checked') || {}).value || 'anthropic';
    document.querySelectorAll('#ai [data-prov]').forEach(function (e) { e.style.display = e.getAttribute('data-prov') === p ? '' : 'none'; });
    var same = key.dataset.hint && key.dataset.savedProv === p;
    key.placeholder = same ? 'Paste a new key to replace ' + key.dataset.hint : (p === 'openai' ? 'sk-proj-...' : 'sk-ant-...');
    f.querySelectorAll('.fc-ai-card').forEach(function (c) { c.classList.toggle('on', c.querySelector('input').checked); });
  }
  f.querySelectorAll('input[name=provider]').forEach(function (r) { r.addEventListener('change', upd); });
  upd();
})();
</script>
