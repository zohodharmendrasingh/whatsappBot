<#-- Visual flow builder shell. Logic: /js/flowbuilder.js, styles: /theme/flowbuilder.css -->
<link rel="stylesheet" href="/theme/flowbuilder.css?v=4"/>
<div class="fb" id="fb" data-flow-id="${flow.flowId}" data-ai="<#if aiConfigured>Y<#else>N</#if>"
     data-save-url="<@ofbizUrl>flowBuilderSave</@ofbizUrl>" data-meta-url="<@ofbizUrl>zohoMeta</@ofbizUrl>" data-settings-url="<@ofbizUrl>Settings</@ofbizUrl>#zoho" data-ai-url="<@ofbizUrl>flowBuilderAi</@ofbizUrl>">
  <div class="fb-top">
    <a class="fb-back" id="fbBack" href="<@ofbizUrl>FindFlow</@ofbizUrl>" title="All bots">←</a>
    <input id="fbName" class="fb-name" type="text" maxlength="90" placeholder="Bot name" aria-label="Bot name"/>
    <label class="fb-kw" title="The bot starts this flow when a customer types one of these words">
      <span>Starts on</span><input id="fbKeywords" type="text" placeholder="hi, hello, menu"/>
    </label>
    <label class="fb-switch" title="Turn this flow on or off"><input type="checkbox" id="fbLive"/><i></i><span>Live</span></label>
    <label class="fb-switch" title="Also run this flow when a message matches no keyword"><input type="checkbox" id="fbDefault"/><i></i><span>Any message</span></label>
    <span class="fb-spacer"></span>
    <button type="button" class="fb-btn fb-ic" id="fbUndo" title="Undo (Ctrl+Z)">↶</button>
    <button type="button" class="fb-btn fb-ic" id="fbRedo" title="Redo (Ctrl+Shift+Z)">↷</button>
    <button type="button" class="fb-btn" id="fbTplBtn">📋 Templates</button>
    <button type="button" class="fb-btn fb-btn-ai" id="fbAiBtn">✨ AI</button>
    <button type="button" class="fb-btn" id="fbTestBtn">▶ Test</button>
    <span class="fb-badge" id="fbCheckBadge"></span>
    <button type="button" class="fb-btn fb-btn-primary" id="fbSave">Saved</button>
  </div>

  <div class="fb-main">
    <aside class="fb-pal">
      <div class="fb-pal-t">Steps</div>
      <#assign pal = [["text","💬","Message","Send a message"],["buttons","🔘","Buttons","Up to 3 quick replies"],["list","📋","List menu","Up to 10 choices"],["ask","❓","Question","Ask &amp; save answer"],["image","🖼️","Image","Picture + caption"],["handoff","🙋","Talk to agent","Hand over to team"],["end","🏁","End","Close the chat"],["goto","↪️","Go to flow","Jump to another bot"]]>
      <#list pal as p>
        <div class="fb-pal-item" data-type="${p[0]}" title="Drag onto the canvas or click to add">
          <span class="fb-pal-ico">${p[1]}</span><span><b>${p[2]}</b><small>${p[3]}</small></span>
        </div>
      </#list>
      <div class="fb-pal-t fb-pal-t2">Zoho</div>
      <#list [["crm","Zoho CRM","Leads &amp; contacts"],["books","Zoho Books","Invoices &amp; balance"],["inventory","Zoho Inventory","Stock &amp; orders"],["people","Zoho People","Leave &amp; HR"]] as z>
        <div class="fb-pal-item fb-pal-zoho" data-type="zoho" data-app="${z[0]}" title="Drag onto the canvas or click to add">
          <span class="fb-pal-ico fb-zlogo fb-z-${z[0]}">Z</span><span><b>${z[1]}</b><small>${z[2]}</small></span>
        </div>
      </#list>
      <div class="fb-pal-help">Drag a step onto the canvas. Drag from a <i class="fb-dot"></i> to connect.</div>
    </aside>

    <div class="fb-canvas" id="fbCanvas">
      <div class="fb-world" id="fbWorld"><svg class="fb-edges" id="fbEdges" width="1" height="1"></svg></div>
      <div class="fb-empty" id="fbEmpty" hidden>
        <div class="fb-empty-box">
          <b>Start building your bot</b>
          <p>Drag a step from the left, pick a ready-made template, or let AI build it for you.</p>
          <div><button type="button" class="fb-btn fb-btn-ai" onclick="document.getElementById('fbAiBtn').click()">✨ Build with AI</button>
               <button type="button" class="fb-btn" onclick="document.getElementById('fbTplBtn').click()">📋 Templates</button></div>
        </div>
      </div>
      <div class="fb-zoom">
        <button type="button" id="fbZoomOut" title="Zoom out">−</button><span id="fbZoomPct">100%</span><button type="button" id="fbZoomIn" title="Zoom in">+</button>
        <button type="button" id="fbFit" title="Fit to screen">⤢</button><button type="button" id="fbArrange" title="Tidy up the layout">▦ Tidy</button>
      </div>
    </div>

    <aside class="fb-panel" id="fbPanel"></aside>

    <section class="fb-chat" id="fbChat" hidden>
      <div class="fb-chat-top"><span class="fb-chat-av">${(businessName!"B")?substring(0,1)?upper_case}</span>
        <div><b>${businessName!"Your business"}</b><small>Test chat</small></div>
        <button type="button" id="fbChatRestart" title="Restart">↺</button><button type="button" id="fbChatClose" title="Close">✕</button></div>
      <div class="fb-chat-log" id="fbChatLog"></div>
      <form class="fb-chat-in" id="fbChatForm" autocomplete="off"><input id="fbChatIn" type="text" placeholder="Type a message"/><button type="submit">➤</button></form>
    </section>
  </div>

  <div class="fb-modal" id="fbAiModal" hidden>
    <div class="fb-modal-box">
      <h3>✨ Build with AI</h3>
      <div class="fb-ai-mode" id="fbAiModeRow">
        <label><input type="radio" name="fbAiMode" value="edit" checked/> Change my current flow</label>
        <label><input type="radio" name="fbAiMode" value="new"/> Create a new flow (replaces the steps)</label>
      </div>
      <textarea id="fbAiText" rows="5" maxlength="4000"></textarea>
      <div class="fb-ideas"><span>Ideas:</span>
        <button type="button" data-ai-idea="Add a step that asks for the customer's phone number before handing over to staff.">+ ask phone number</button>
        <button type="button" data-ai-idea="Translate all messages to Hindi, keep the same steps.">Translate to Hindi</button>
        <button type="button" data-ai-idea="Make the messages shorter and friendlier, with a few emojis.">Friendlier texts</button>
      </div>
      <p class="fb-muted">AI makes a draft on the canvas. Nothing goes live until you press Save. You can undo.</p>
      <div class="fb-modal-actions"><button type="button" class="fb-btn" data-close>Cancel</button><button type="button" class="fb-btn fb-btn-ai" id="fbAiGo">✨ Generate</button></div>
    </div>
  </div>

  <div class="fb-modal" id="fbAiKeyModal" hidden>
    <div class="fb-modal-box">
      <h3>✨ Connect your AI key</h3>
      <p>To build and edit bots with AI, add your own Claude or OpenAI API key once in Settings. It takes about 2 minutes and building a bot usually costs a few cents on your own account.</p>
      <div class="fb-modal-actions"><button type="button" class="fb-btn" data-close>Not now</button>
        <a class="fb-btn fb-btn-ai" id="fbAiKeyGo" href="<@ofbizUrl>Settings</@ofbizUrl>#ai">Add AI key</a></div>
    </div>
  </div>

  <div class="fb-modal" id="fbTplModal" hidden>
    <div class="fb-modal-box wide">
      <h3>📋 Start from a template</h3>
      <p class="fb-muted">Your business name is filled in automatically. You can edit every step afterwards.</p>
      <div class="fb-tpls" id="fbTplList"></div>
      <div class="fb-modal-actions"><button type="button" class="fb-btn" data-close>Close</button></div>
    </div>
  </div>

  <div class="fb-busy" id="fbBusy" hidden><div class="fb-busy-box"><div class="fh-spin"></div><p id="fbBusyText">Working…</p></div></div>
  <div class="fb-toast" id="fbToast"></div>
</div>
<script type="text/plain" id="fbGraph">${graphB64}</script>
<script type="text/plain" id="fbTemplates">${templatesB64}</script>
<script type="text/plain" id="fbFlows">${otherFlowsB64}</script>
<script type="text/plain" id="fbZoho">${zohoB64}</script>
<script src="/js/flowbuilder.js?v=4"></script>
