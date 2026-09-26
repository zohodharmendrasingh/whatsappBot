<link rel="stylesheet" href="/theme/crm.css?v=4"/>
<div class="ms-inbox-list">
  <form method="get" action="<@ofbizUrl>Inbox</@ofbizUrl>" class="ms-inbox-search cx-inbox-search">
    <input type="hidden" name="tab" value="${inboxTab}"/>
    <input type="text" name="q" value="${inboxQ!}" placeholder="Search name or number"/>
    <select name="st" onchange="this.form.submit()" title="Chat status" aria-label="Chat status">
      <#list [["active","Open & pending"],["OPEN","Open"],["PENDING","Pending"],["SOLVED","Solved"],["any","All statuses"]] as o>
        <option value="${o[0]}" <#if inboxSt == o[0]>selected</#if>>${o[1]}</option>
      </#list>
    </select>
  </form>
  <div class="ms-tabs">
    <#list [["all","All"],["mine","Mine"],["unread","Unread"],["agent","Needs agent"]] as t>
      <a class="<#if inboxTab == t[0]>active</#if>" href="<@ofbizUrl>Inbox?tab=${t[0]}&amp;st=${inboxSt}</@ofbizUrl>">${t[1]}</a>
    </#list>
  </div>
  <div class="ms-conv-list">
    <#list inboxContacts as c>
      <#assign pv = inboxPreviews[c.contactId]!{}>
      <a class="ms-conv<#if (parameters.contactId!"") == c.contactId> active</#if>" href="<@ofbizUrl>Inbox?contactId=${c.contactId}&amp;tab=${inboxTab}&amp;st=${inboxSt}</@ofbizUrl>">
        <span class="ms-av">${(c.profileName!c.waId)?substring(0,1)?upper_case}</span>
        <span class="ms-conv-main">
          <span class="ms-conv-top"><strong>${c.profileName!("+" + c.waId)}</strong><small>${(c.lastMessageDate?string("dd MMM HH:mm"))!}</small></span>
          <span class="ms-conv-bottom">
            <span class="ms-conv-preview"><#if pv.out?? && pv.out>You: </#if>${pv.text!""}</span>
            <#if (c.unreadCount!0) gt 0><em class="ms-count">${c.unreadCount}</em></#if>
            <#if c.botPaused! == "Y"><span class="ms-pill ms-pill-amber">Agent</span></#if>
            <#if c.chatStatus! == "PENDING"><span class="ms-pill">Pending</span><#elseif c.chatStatus! == "SOLVED"><span class="ms-pill ms-pill-green">Solved</span></#if>
            <#if c.assignedTo?has_content><span class="cx-assignee" title="Assigned to ${c.assignedTo}">${(c.assignedTo == inboxMe)?then("Me", c.assignedTo?substring(0, [2, c.assignedTo?length]?min)?upper_case)}</span></#if>
          </span>
        </span>
      </a>
    <#else>
      <div class="ms-empty"><p>No conversations<#if inboxQ?has_content> match "${inboxQ}"</#if><#if inboxSt == "active"> are open</#if>.</p>
        <#if inboxSt != "any"><p><a href="<@ofbizUrl>Inbox?tab=${inboxTab}&amp;st=any</@ofbizUrl>">Show all statuses</a></p></#if></div>
    </#list>
  </div>
</div>
<script type="application/javascript">
/* Live inbox: poll a small change signature; when it changes, re-fetch this page and swap the list and chat in place
   (the reply box is left untouched so a half-typed reply is never lost). */
(function () {
  var params = new URLSearchParams(window.location.search);
  var pollUrl = '<@ofbizUrl>inboxPoll</@ofbizUrl>' + (params.get('contactId') ? '?contactId=' + encodeURIComponent(params.get('contactId')) : '');
  var sig = null, busy = false;
  function swap(doc, sel) {
    var a = document.querySelector(sel), b = doc.querySelector(sel);
    if (a && b && a.innerHTML !== b.innerHTML) { a.innerHTML = b.innerHTML; return true; }
    return false;
  }
  function refresh() {
    busy = true;
    return fetch(window.location.href, { credentials: 'same-origin', cache: 'no-store' }).then(function (r) { return r.text(); }).then(function (html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var chat = document.getElementById('waChat');
      var atBottom = chat && (chat.scrollHeight - chat.scrollTop - chat.clientHeight < 60);
      swap(doc, '.ms-conv-list');
      swap(doc, '.wa-chat-head');
      if (swap(doc, '#waChat') && chat && atBottom) { chat.scrollTop = chat.scrollHeight; }
      var nav = document.querySelector('.ms-nav-item.active'), nav2 = doc.querySelector('.ms-nav-item.active');
      if (nav && nav2) { nav.innerHTML = nav2.innerHTML; }
    }).catch(function () {}).then(function () { busy = false; });
  }
  function tick() {
    if (busy || document.hidden) { return; }
    fetch(pollUrl, { credentials: 'same-origin', cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; }).then(function (d) {
      if (!d || !d.sig) { return; }
      if (sig !== null && d.sig !== sig) { refresh(); }
      sig = d.sig;
    }).catch(function () {});
  }
  tick();
  setInterval(tick, 4000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) { tick(); } });
})();
</script>
