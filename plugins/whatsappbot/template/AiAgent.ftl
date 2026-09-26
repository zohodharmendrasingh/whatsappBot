<#-- AI Agent: answers customers from the business's own knowledge, hands over when unsure -->
<link rel="stylesheet" href="/theme/crm.css?v=4"/>
<#macro srcIcon t><#if t == "URL">🌐<#elseif t == "TEXT">📝<#else>📄</#if></#macro>
<div class="cx ag" id="agPage" data-owner="<#if isOwner>Y<#else>N</#if>"
     data-save-url="<@ofbizUrl>agentSave</@ofbizUrl>" data-upload-url="<@ofbizUrl>kbUpload</@ofbizUrl>" data-url-url="<@ofbizUrl>kbAddUrl</@ofbizUrl>"
     data-text-url="<@ofbizUrl>kbAddText</@ofbizUrl>" data-delete-url="<@ofbizUrl>kbDelete</@ofbizUrl>" data-refresh-url="<@ofbizUrl>kbRefresh</@ofbizUrl>"
     data-view-url="<@ofbizUrl>kbView</@ofbizUrl>" data-list-url="<@ofbizUrl>kbList</@ofbizUrl>" data-test-url="<@ofbizUrl>agentTest</@ofbizUrl>">

  <div class="ag-hero">
    <div class="ag-hero-ico">✨</div>
    <div class="ag-hero-txt">
      <h2 class="cx-title">AI Agent <span class="cx-pill <#if agentOn && hasOwnKey>cx-pill-green<#else>cx-st-CANCELLED</#if>" id="agState"><#if agentOn && hasOwnKey>On<#else>Off</#if></span></h2>
      <p class="cx-sub">Answers your customers 24×7 from your own information: price lists, FAQs, policies, your website. When it isn't sure, it hands the chat to your team.</p>
    </div>
    <#if isOwner>
    <label class="cx-toggle ag-switch<#if !hasOwnKey> disabled</#if>" title="<#if !hasOwnKey>Add your AI key first</#if>">
      <input type="checkbox" id="agEnabled" <#if agentOn && hasOwnKey>checked</#if> <#if !hasOwnKey>disabled</#if>/><i></i>
      <span><b>Answer customers with AI</b><small>Uses your own ${keyProvider} key</small></span>
    </label>
    </#if>
  </div>

  <#if !hasOwnKey>
    <div class="cx-note cx-note-warn ag-key">🔑 <b>Connect your Claude or OpenAI key first.</b> The agent runs on your own AI account, so you control the cost (answering a customer usually costs well under ₹0.50).
      <a class="cx-btn cx-btn-primary" href="<@ofbizUrl>Settings</@ofbizUrl>#ai">Add AI key</a></div>
  <#elseif agent?? && agent.lastError?has_content>
    <div class="cx-note cx-note-warn">⚠️ Last problem (${(agent.lastErrorDate?string("dd MMM HH:mm"))!}): ${agent.lastError}. Until it's fixed, customers get your main menu or are handed to your team.</div>
  </#if>

  <div class="ag-cols">
    <div class="ag-main">
      <div class="cx-card">
        <div class="cx-card-head"><h3>1. What the agent knows</h3>
          <span class="cx-muted ag-usage">${kbSources?size} of ${kbMaxSources} sources &middot; ${(kbSummary.chars / 1000)?round}k of ${(kbSummary.maxChars / 1000)?round}k characters
            <span class="ag-bar"><i style="width:${kbSummary.pct}%"></i></span></span></div>
        <div class="ag-add">
          <div class="cx-seg ag-tabs" role="tablist">
            <a class="on" href="#" data-tab="file">📄 Upload file</a><a href="#" data-tab="url">🌐 Website</a><a href="#" data-tab="text">✏️ Write a note / FAQ</a>
          </div>
          <div class="ag-pane" data-pane="file">
            <label class="cx-drop ag-drop" id="agDrop"><input type="file" id="agFile" multiple accept=".pdf,.docx,.txt,.csv,.md,.htm,.html"/>
              <span>📄 <b>Choose files</b> or drop them here<br/><small class="cx-muted">PDF, Word (.docx), TXT or CSV &middot; up to 10 MB each. Price lists, menus, brochures, policies, FAQs.</small></span></label>
            <ul class="ag-uploads" id="agUploads"></ul>
          </div>
          <div class="ag-pane" data-pane="url" hidden>
            <form class="ag-url" id="agUrlForm" autocomplete="off">
              <input type="url" name="url" placeholder="https://www.yourshop.com/faq" required/>
              <select name="pages"><option value="1">Only this page</option><option value="10" selected>This page + up to 10 linked pages</option><option value="30">Up to 30 pages of the site</option></select>
              <button type="submit" class="cx-btn cx-btn-primary">Add website</button>
            </form>
            <p class="cx-hint">Only pages on the same website are read. Pages behind a login can't be read. Use Refresh after you update your site.</p>
          </div>
          <div class="ag-pane" data-pane="text" hidden>
            <form id="agTextForm" autocomplete="off">
              <input type="hidden" name="sourceId"/>
              <label>Title<input type="text" name="title" maxlength="100" placeholder="e.g. Store timings & delivery, Frequently asked questions"/></label>
              <label>Text<textarea name="text" rows="7" placeholder="Q: What are your timings?&#10;A: 10:30 am to 8:30 pm, Monday to Saturday. Closed on Sunday.&#10;&#10;Q: Do you deliver?&#10;A: Yes, free delivery in Gwalior on orders above ₹2,000."></textarea></label>
              <div class="cx-modal-actions"><button type="button" class="cx-btn" id="agTextCancel" hidden>Cancel edit</button><button type="submit" class="cx-btn cx-btn-primary" id="agTextSave">Save note</button></div>
            </form>
          </div>
          <p class="cx-err" id="agAddErr" hidden></p>
        </div>

        <ul class="ag-sources" id="agSources">
          <#list kbSources as s>
            <li data-id="${s.sourceId}" data-type="${s.sourceType}" data-status="${s.statusId!}">
              <span class="ag-src-ico"><@srcIcon s.sourceType/></span>
              <span class="ag-src-main"><b>${s.title!s.url!"Untitled"}</b>
                <small class="cx-muted"><#if s.sourceType == "URL">${s.url!}<#if (s.pageCount!0) gt 1> &middot; ${s.pageCount} pages</#if> &middot; </#if>
                  <#if s.statusId! == "READY">${((s.charCount!0) / 1000)?string("0.#")}k characters &middot; updated ${(s.syncedDate?string("dd MMM"))!}
                  <#elseif s.statusId! == "PROCESSING"><span class="ag-spin"></span> Reading…<#else><span class="cx-danger">${s.errorText!"Could not read"}</span></#if></small></span>
              <span class="ag-src-act">
                <#if s.statusId! == "READY"><button type="button" class="cx-link" data-view="${s.sourceId}"><#if s.sourceType == "TEXT">Edit<#else>View</#if></button></#if>
                <#if s.sourceType == "URL" && s.statusId! != "PROCESSING"><button type="button" class="cx-link" data-refresh="${s.sourceId}">Refresh</button></#if>
                <button type="button" class="cx-link cx-danger" data-del="${s.sourceId}">Remove</button></span>
            </li>
          <#else>
            <li class="ag-empty">No knowledge yet. Add your price list, FAQ or website so the agent can answer from it. Without knowledge it hands every specific question to your team.</li>
          </#list>
        </ul>
      </div>

      <div class="cx-card">
        <div class="cx-card-head"><h3>2. How it answers</h3></div>
        <form class="ag-settings" id="agSettings" autocomplete="off">
          <label>About your business and how to talk <small>optional, the agent follows these notes</small>
            <textarea name="instructions" rows="5" maxlength="4000" <#if !isOwner>disabled</#if> placeholder="We are Shree Jewellers, a family jewellery shop in Gwalior since 1985. Be warm and polite, address customers as ji. Never share gold rates, say today's rate is shared by our team. For custom designs, hand over to the team.">${(agent.instructions)!""}</textarea></label>
          <label>Message when it hands over to your team
            <input type="text" name="handoffMessage" maxlength="500" <#if !isOwner>disabled</#if> placeholder="${defaultHandoff}" value="${(agent.handoffMessage)!""}"/></label>
          <div class="cx-grid2">
            <label class="cx-check"><input type="checkbox" name="answerInMenus" <#if !isOwner>disabled</#if> <#if !(agent??) || (agent.answerInMenus!"Y") != "N">checked</#if>/> Also answer questions typed while a menu (buttons or list) is shown</label>
            <label>Max AI answers per day <small>used today: ${aiUsedToday}</small><input type="number" name="maxPerDay" min="10" max="100000" <#if !isOwner>disabled</#if> value="${aiMaxPerDay?c}"/></label>
          </div>
          <#if isOwner><div class="cx-modal-actions"><span class="cx-muted" id="agSaved" hidden>Saved ✓</span><button type="submit" class="cx-btn cx-btn-primary">Save</button></div>
          <#else><p class="cx-hint">Only the workspace owner can change these settings.</p></#if>
        </form>
        <div class="ag-how">
          <b>When a customer writes on WhatsApp</b>
          <ol>
            <li>Words that start one of your <a href="<@ofbizUrl>FindFlow</@ofbizUrl>">bot flows</a> (like <i>hi</i> or <i>menu</i>) still start that flow.</li>
            <li>Any other question is answered by the AI from your knowledge, in the customer's language.</li>
            <li>If the answer isn't in your knowledge, or the customer wants a person, the chat moves to your <a href="<@ofbizUrl>Inbox?tab=agent</@ofbizUrl>">Inbox</a> for your team, with a note saying why.</li>
          </ol>
          <#if !hasMainMenu><p class="cx-hint">Tip: set one flow as the main menu ("Any message" in the builder) so greetings open your menu.</p></#if>
        </div>
      </div>
    </div>

    <aside class="ag-side">
      <div class="cx-card ag-test">
        <div class="cx-card-head"><h3>3. Try it</h3><button type="button" class="cx-link" id="agTestClear">Clear</button></div>
        <div class="ag-chat" id="agChat">
          <div class="ag-hint">Ask what your customers ask, e.g. <i>"What are your timings?"</i> or <i>"Do you deliver to Indore?"</i>. Nothing is sent on WhatsApp.</div>
        </div>
        <form class="ag-test-in" id="agTestForm" autocomplete="off"><input type="text" name="message" maxlength="1000" placeholder="Type a customer question" <#if !hasOwnKey>disabled</#if>/><button type="submit" class="cx-btn cx-btn-primary" <#if !hasOwnKey>disabled</#if>>Send</button></form>
      </div>
    </aside>
  </div>

  <div class="cx-modal" id="agViewModal" hidden>
    <div class="cx-modal-box wide">
      <h3 id="agViewTitle"></h3>
      <p class="cx-muted" id="agViewMeta"></p>
      <pre class="ag-view" id="agViewText"></pre>
      <div class="cx-modal-actions"><button type="button" class="cx-btn" data-close>Close</button></div>
    </div>
  </div>
  <div class="cx-toast" id="cxToast"></div>
</div>
<script src="/js/crm.js?v=4"></script>
