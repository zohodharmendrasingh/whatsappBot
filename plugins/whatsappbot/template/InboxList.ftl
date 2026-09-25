<div class="ms-inbox-list">
  <form method="get" action="<@ofbizUrl>Inbox</@ofbizUrl>" class="ms-inbox-search">
    <input type="hidden" name="tab" value="${inboxTab}"/>
    <input type="text" name="q" value="${inboxQ!}" placeholder="Search name or number"/>
  </form>
  <div class="ms-tabs">
    <#list [["all","All"],["unread","Unread"],["agent","Needs agent"]] as t>
      <a class="<#if inboxTab == t[0]>active</#if>" href="<@ofbizUrl>Inbox?tab=${t[0]}</@ofbizUrl>">${t[1]}</a>
    </#list>
  </div>
  <div class="ms-conv-list">
    <#list inboxContacts as c>
      <#assign pv = inboxPreviews[c.contactId]!{}>
      <a class="ms-conv<#if (parameters.contactId!"") == c.contactId> active</#if>" href="<@ofbizUrl>Inbox?contactId=${c.contactId}&amp;tab=${inboxTab}</@ofbizUrl>">
        <span class="ms-av">${(c.profileName!c.waId)?substring(0,1)?upper_case}</span>
        <span class="ms-conv-main">
          <span class="ms-conv-top"><strong>${c.profileName!("+" + c.waId)}</strong><small>${(c.lastMessageDate?string("dd MMM HH:mm"))!}</small></span>
          <span class="ms-conv-bottom">
            <span class="ms-conv-preview"><#if pv.out?? && pv.out>You: </#if>${pv.text!""}</span>
            <#if (c.unreadCount!0) gt 0><em class="ms-count">${c.unreadCount}</em></#if>
            <#if c.botPaused! == "Y"><span class="ms-pill ms-pill-amber">Agent</span></#if>
          </span>
        </span>
      </a>
    <#else>
      <div class="ms-empty"><p>No conversations<#if inboxQ?has_content> match "${inboxQ}"</#if>.</p></div>
    </#list>
  </div>
</div>
