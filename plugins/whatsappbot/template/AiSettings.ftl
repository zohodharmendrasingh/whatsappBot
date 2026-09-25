<#-- Settings > AI assistant: each workspace connects its own Claude API key -->
<div class="fc-ai" id="ai">
  <div class="fc-ai-status">
    <#if aiKeySet>
      <span class="ms-pill ms-pill-green">✓ Connected</span> Claude API key <code>${aiKeyHint}</code>
      <#if aiUpdated??><small> &middot; added ${aiUpdated?string("dd MMM yyyy")}</small></#if>
    <#else>
      <span class="ms-pill ms-pill-amber">Not connected</span> Add your Claude API key to build and edit bots with AI.
    </#if>
  </div>
  <#if aiIsOwner>
    <form method="post" action="<@ofbizUrl>saveAiKey</@ofbizUrl>" class="fc-ai-form" autocomplete="off">
      <label>Claude API key
        <input type="password" name="apiKey" autocomplete="new-password" placeholder="<#if aiKeySet>Paste a new key to replace ${aiKeyHint}<#else>sk-ant-...</#if>"/>
      </label>
      <label>Model
        <select name="model">
          <#list aiModels?keys as m><option value="${m}"<#if aiModel == m || (!aiModel?has_content && m?index == 0)> selected</#if>>${aiModels[m]}</option></#list>
        </select>
      </label>
      <div class="fc-ai-actions">
        <button type="submit" class="smallSubmit">${aiKeySet?then("Save","Connect key")}</button>
      </div>
    </form>
    <#if aiKeySet>
      <form method="post" action="<@ofbizUrl>removeAiKey</@ofbizUrl>" onsubmit="return confirm('Remove the Claude API key? AI building will stop until you add a key again.');" class="fc-ai-remove">
        <button type="submit" class="buttontext">Remove key</button>
      </form>
    </#if>
  <#else>
    <p class="fc-ai-note">Only the workspace owner can add or change the AI key.</p>
  </#if>
  <details class="fc-ai-help">
    <summary>How do I get a Claude API key?</summary>
    <ol>
      <li>Open <a href="https://console.anthropic.com/" target="_blank" rel="noopener">console.anthropic.com</a> and sign in or create an account.</li>
      <li>Go to <b>Billing</b> and add a small amount of credit (building a bot usually costs a few cents).</li>
      <li>Go to <b>API keys</b>, click <b>Create key</b>, copy it and paste it above.</li>
    </ol>
    <p>Your key is stored encrypted and is only used when you press ✨ AI in the bot builder. Usage is billed by Anthropic to your own account.</p>
  </details>
</div>
