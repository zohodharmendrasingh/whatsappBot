<#if contact??>
<div class="wa-chat-head">
  <span class="ms-av ms-av-lg">${(contact.profileName!contact.waId)?substring(0,1)?upper_case}</span>
  <span class="ms-chat-who"><strong>${contact.profileName!contact.waId}</strong><small class="wa-muted">+${contact.waId}</small></span>
  <span class="ms-spacer"></span>
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
  <#list messages as m>
    <div class="wa-msg <#if m.direction == 'IN'>wa-in<#else>wa-out</#if>">
      <div class="wa-body">${(m.body!"")?replace("\n", "<br/>")}</div>
      <div class="wa-meta">${(m.createdDate?string("dd-MMM HH:mm"))!}
        <#if m.direction == 'OUT'> · ${m.sentBy!} · <span class="wa-st-${m.deliveryStatus!}">${m.deliveryStatus!}</span></#if>
      </div>
      <#if m.errorText?has_content><div class="wa-err">${m.errorText}</div></#if>
    </div>
  <#else>
    <p class="wa-muted">No messages.</p>
  </#list>
</div>
<script>(function(){var c=document.getElementById('waChat'); if(c){c.scrollTop=c.scrollHeight;}})();</script>
</#if>
