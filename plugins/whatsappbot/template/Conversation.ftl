<link rel="stylesheet" href="/theme/crm.css?v=5"/>
<#if contact??>
<#assign chatSt = contact.chatStatus!"OPEN">
<#macro keep><input type="hidden" name="contactId" value="${contact.contactId}"/><input type="hidden" name="tab" value="${inboxTab!"all"}"/><input type="hidden" name="st" value="${inboxSt!"active"}"/></#macro>
<div class="wa-chat-head">
  <span class="ms-av ms-av-lg">${(contact.profileName!contact.waId)?substring(0,1)?upper_case}</span>
  <span class="ms-chat-who"><strong>${contact.profileName!contact.waId}</strong><small class="wa-muted">+${contact.waId}
    <#list contactTags![] as t> <a class="cx-tag" href="<@ofbizUrl>Contacts?tag=${t?url('UTF-8')}</@ofbizUrl>">${t}</a></#list>
    <#if contact.optInStatus! == "N"> <span class="cx-pill cx-pill-red">Opted out</span></#if></small></span>
  <span class="ms-spacer"></span>
  <div class="cx-chat-tools">
    <form method="post" action="<@ofbizUrl>chatAssign</@ofbizUrl>" class="wa-inline cx-assign"><@keep/>
      <label title="Who handles this chat">👤<select name="assignTo" onchange="this.form.submit()" aria-label="Assign to">
        <option value="">Unassigned</option>
        <#list teamMembers![] as u><option value="${u}" <#if (contact.assignedTo!"") == u>selected</#if>>${u}<#if u == (inboxMe!"")> (me)</#if></option></#list>
        <#if contact.assignedTo?has_content && !(teamMembers![])?seq_contains(contact.assignedTo)><option value="${contact.assignedTo}" selected>${contact.assignedTo}</option></#if>
      </select></label>
    </form>
    <form method="post" action="<@ofbizUrl>chatStatus</@ofbizUrl>" class="wa-inline cx-status"><@keep/>
      <#list [["OPEN","Open"],["PENDING","Pending"],["SOLVED","Solved"]] as o>
        <button type="submit" name="status" value="${o[0]}" class="<#if chatSt == o[0]>on</#if> cx-st-${o[0]}" title="<#if o[0]=="PENDING">Waiting for the customer<#elseif o[0]=="SOLVED">Done. Reopens when the customer writes again<#else>Needs attention</#if>">${o[1]}</button>
      </#list>
    </form>
  </div>
  <#if contact.botPaused! == "Y">
    <span class="wa-badge wa-warn">Agent handling – bot paused</span>
    <form method="post" action="<@ofbizUrl>waResumeBot</@ofbizUrl>" class="wa-inline"><input type="hidden" name="contactId" value="${contact.contactId}"/><input type="submit" class="smallSubmit" value="Hand back to bot"/></form>
  <#else>
    <span class="wa-badge">Bot active</span>
    <form method="post" action="<@ofbizUrl>waPauseBot</@ofbizUrl>" class="wa-inline"><input type="hidden" name="contactId" value="${contact.contactId}"/><input type="submit" class="smallSubmit" value="Take over"/></form>
  </#if>
  <#assign lastIn = contact.lastInboundDate!>
  <#if lastIn?has_content && (nowTimestamp.getTime() - lastIn.getTime()) < 86400000>
    <span class="wa-badge wa-ok">24h window open</span>
  <#else>
    <span class="wa-badge wa-warn">24h window closed – templates only</span>
  </#if>
</div>
<div class="wa-chat" id="waChat">
  <#list timeline![] as it>
    <#if it.kind == "note">
      <#assign n = it.n>
      <#if it.sys>
        <div class="cx-event">${it.text} &middot; ${n.createdBy!} &middot; ${(n.createdDate?string("dd-MMM HH:mm"))!}</div>
      <#else>
        <div class="cx-note-msg"><div class="cx-note-head">📝 Internal note &middot; ${n.createdBy!}</div>
          <div class="wa-body">${it.text?replace("\n", "<br/>")}</div>
          <div class="wa-meta">${(n.createdDate?string("dd-MMM HH:mm"))!} &middot; only your team sees this</div></div>
      </#if>
    <#else>
      <#assign m = it.m>
      <div class="wa-msg <#if m.direction == 'IN'>wa-in<#else>wa-out</#if>">
        <div class="wa-body">${(m.body!"")?replace("\n", "<br/>")}</div>
        <div class="wa-meta">${(m.createdDate?string("dd-MMM HH:mm"))!}
          <#if m.direction == 'OUT'> · <#if (m.sentBy!"")?starts_with("BROADCAST")>broadcast<#elseif (m.sentBy!"") == "AI">✨ AI agent<#else>${m.sentBy!}</#if> · <span class="wa-st-${m.deliveryStatus!}">${m.deliveryStatus!}</span></#if>
        </div>
        <#if m.errorText?has_content><div class="wa-err">${m.errorText}</div></#if>
      </div>
    </#if>
  <#else>
    <p class="wa-muted">No messages.</p>
  </#list>
</div>
<details class="cx-note-form">
  <summary>📝 Add internal note <small>only your team sees it</small></summary>
  <form method="post" action="<@ofbizUrl>chatNote</@ofbizUrl>"><@keep/>
    <textarea name="noteText" rows="2" maxlength="4000" placeholder="e.g. Customer wants a callback after 6 pm" required></textarea>
    <button type="submit" class="smallSubmit">Save note</button>
  </form>
</details>
<ul id="cxQuickReplies" hidden data-first="${contactFirstName!""}" data-manage="<@ofbizUrl>Settings</@ofbizUrl>#replies"><#list quickReplies![] as r><li data-sc="${r.shortcut}">${r.body}</li></#list></ul>
<script>(function(){var c=document.getElementById('waChat'); if(c){c.scrollTop=c.scrollHeight;}})();</script>
<script src="/js/crm.js?v=5"></script>
</#if>
