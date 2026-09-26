<#-- Contacts: search / filter, tags, custom fields, consent, CSV import & export, bulk actions -->
<link rel="stylesheet" href="/theme/crm.css?v=5"/>
<#macro qs page=contactsPage tag=filterTag>?q=${filterQ?url('UTF-8')}&amp;tag=${tag?url('UTF-8')}&amp;opt=${filterOpt}&amp;channelId=${filterChannel?url('UTF-8')}&amp;page=${page}</#macro>
<div class="cx" id="cxContacts" data-owner="<#if isOwner>Y<#else>N</#if>"
     data-save-url="<@ofbizUrl>contactSave</@ofbizUrl>" data-data-url="<@ofbizUrl>contactData</@ofbizUrl>"
     data-bulk-url="<@ofbizUrl>contactsBulk</@ofbizUrl>" data-import-url="<@ofbizUrl>contactsImport</@ofbizUrl>"
     data-field-add-url="<@ofbizUrl>contactFieldAdd</@ofbizUrl>" data-field-remove-url="<@ofbizUrl>contactFieldRemove</@ofbizUrl>">
<#if !channels?has_content>
  <div class="cx-card cx-empty-big">
    <div class="cx-empty-ico">👥</div>
    <h2>Connect a WhatsApp number first</h2>
    <p>Contacts belong to one of your WhatsApp numbers. Connect a number, then add or import your customers here.</p>
    <a class="cx-btn cx-btn-primary" href="<@ofbizUrl>Channels</@ofbizUrl>">Connect WhatsApp number</a>
  </div>
<#else>
  <div class="cx-head">
    <div>
      <h2 class="cx-title">Contacts</h2>
      <p class="cx-sub">${contactsTotal?string(",##0")} contact<#if contactsTotal != 1>s</#if><#if (contactsOptedOut > 0)> &middot; ${contactsOptedOut?string(",##0")} opted out</#if></p>
    </div>
    <div class="cx-actions">
      <button type="button" class="cx-btn" data-open="cxFieldsModal">⚙ Custom fields</button>
      <#if isOwner><a class="cx-btn" id="cxExport" href="<@ofbizUrl>contactsExport</@ofbizUrl><@qs page=1/>">⬇ Export CSV</a></#if>
      <button type="button" class="cx-btn" data-open="cxImportModal">⬆ Import CSV</button>
      <button type="button" class="cx-btn cx-btn-primary" id="cxAdd">+ Add contact</button>
    </div>
  </div>

  <form class="cx-filters" method="get" action="<@ofbizUrl>Contacts</@ofbizUrl>">
    <input type="search" name="q" value="${filterQ}" placeholder="Search name, number, email or field" class="cx-search"/>
    <select name="tag" onchange="this.form.submit()"><option value="">All tags</option>
      <#list tagCounts?keys as t><option value="${t}" <#if t == filterTag>selected</#if>>${t} (${tagCounts[t]})</option></#list></select>
    <select name="opt" onchange="this.form.submit()">
      <option value="">Opted in or out</option><option value="Y" <#if filterOpt == "Y">selected</#if>>Opted in</option><option value="N" <#if filterOpt == "N">selected</#if>>Opted out</option></select>
    <#if multiChannel><select name="channelId" onchange="this.form.submit()"><option value="">All numbers</option>
      <#list channels as ch><option value="${ch.channelId}" <#if ch.channelId == filterChannel>selected</#if>>${ch.displayPhoneNumber!ch.channelName!ch.channelId}</option></#list></select></#if>
    <button type="submit" class="cx-btn">Search</button>
    <#if filterQ?has_content || filterTag?has_content || filterOpt?has_content || filterChannel?has_content><a class="cx-link" href="<@ofbizUrl>Contacts</@ofbizUrl>">Clear</a></#if>
  </form>

  <#if tagCounts?has_content>
  <div class="cx-tagbar">
    <a class="cx-chip<#if !filterTag?has_content> on</#if>" href="<@ofbizUrl>Contacts</@ofbizUrl>?q=${filterQ?url('UTF-8')}&amp;opt=${filterOpt}&amp;channelId=${filterChannel?url('UTF-8')}">All</a>
    <#list tagCounts?keys as t><#if t?index lt 14><a class="cx-chip<#if t == filterTag> on</#if>" href="<@ofbizUrl>Contacts</@ofbizUrl><@qs page=1 tag=t/>">${t} <em>${tagCounts[t]}</em></a></#if></#list>
  </div>
  </#if>

  <div class="cx-bulk" id="cxBulk" hidden>
    <b><span id="cxSelCount">0</span> selected</b>
    <input type="text" id="cxBulkTag" list="cxTagList" placeholder="Tag name" maxlength="40"/>
    <button type="button" class="cx-btn" data-bulk="tag">+ Add tag</button>
    <button type="button" class="cx-btn" data-bulk="untag">− Remove tag</button>
    <span class="cx-sep"></span>
    <button type="button" class="cx-btn" data-bulk="optin">Opt in</button>
    <button type="button" class="cx-btn" data-bulk="optout">Opt out</button>
    <#if isOwner><button type="button" class="cx-btn cx-btn-danger" data-bulk="delete">Delete</button></#if>
  </div>

  <div class="cx-card cx-table-wrap">
  <#if contactRows?has_content>
    <table class="cx-table">
      <thead><tr>
        <th class="cx-cb"><input type="checkbox" id="cxAll" aria-label="Select all"/></th>
        <th>Contact</th><th>Tags</th><th class="cx-hide-sm">Email</th>
        <#list fieldDefs as d><#if d?index lt 2><th class="cx-hide-sm">${d.label}</th></#if></#list>
        <th>Broadcasts</th><th class="cx-hide-sm">Last message</th><th></th>
      </tr></thead>
      <tbody>
      <#list contactRows as c>
        <tr data-id="${c.contactId}">
          <td class="cx-cb"><input type="checkbox" class="cx-row-cb" value="${c.contactId}" aria-label="Select"/></td>
          <td><div class="cx-person"><span class="ms-av">${(c.name!c.waId)?substring(0,1)?upper_case}</span>
              <span><b>${c.name!("+" + c.waId)}</b><small><#if c.name?has_content>+${c.waId}<#else>No name yet</#if><#if multiChannel> &middot; via ${c.channel!}</#if></small></span></div></td>
          <td><div class="cx-tags"><#list c.tags as t><a class="cx-tag" href="<@ofbizUrl>Contacts</@ofbizUrl>?tag=${t?url('UTF-8')}">${t}</a></#list></div></td>
          <td class="cx-hide-sm">${c.email!""}</td>
          <#list fieldDefs as d><#if d?index lt 2><td class="cx-hide-sm">${c.fields[d.fieldKey]!""}</td></#if></#list>
          <td><#if c.optIn><span class="cx-pill cx-pill-green" title="Consent: ${(c.optSource!"")?lower_case}">Opted in</span><#else><span class="cx-pill cx-pill-red">Opted out</span></#if></td>
          <td class="cx-hide-sm cx-muted">${(c.lastMessageDate?string("dd MMM yyyy"))!"–"}</td>
          <td class="cx-row-actions"><a class="cx-icon-btn" title="Open chat" href="<@ofbizUrl>Inbox?contactId=${c.contactId}</@ofbizUrl>">💬</a>
              <button type="button" class="cx-icon-btn" title="Edit" data-edit="${c.contactId}">✏️</button></td>
        </tr>
      </#list>
      </tbody>
    </table>
    <#if (contactsPages > 1)>
    <div class="cx-pager">
      <span>${contactsMatched?string(",##0")} matching &middot; page ${contactsPage} of ${contactsPages}</span>
      <#if (contactsPage > 1)><a class="cx-btn" href="<@ofbizUrl>Contacts</@ofbizUrl><@qs page=contactsPage-1/>">← Previous</a></#if>
      <#if (contactsPage < contactsPages)><a class="cx-btn" href="<@ofbizUrl>Contacts</@ofbizUrl><@qs page=contactsPage+1/>">Next →</a></#if>
    </div>
    </#if>
  <#else>
    <div class="cx-empty">
      <#if filterQ?has_content || filterTag?has_content || filterOpt?has_content || filterChannel?has_content>
        <p>No contacts match these filters.</p>
      <#else>
        <div class="cx-empty-ico">👥</div>
        <p><b>No contacts yet.</b> Everyone who messages your WhatsApp number shows up here automatically.</p>
        <p class="cx-muted">You can also add contacts one by one or import a CSV from Excel, Google Sheets or your CRM.</p>
        <button type="button" class="cx-btn cx-btn-primary" data-open="cxImportModal">⬆ Import CSV</button>
      </#if>
    </div>
  </#if>
  </div>
</#if>

  <datalist id="cxTagList"><#list tagCounts?keys as t><option value="${t}"></option></#list></datalist>

  <#-- ===== add / edit contact ===== -->
  <div class="cx-modal" id="cxEditModal" hidden>
    <form class="cx-modal-box" id="cxEditForm" autocomplete="off">
      <h3 id="cxEditTitle">Add contact</h3>
      <input type="hidden" name="contactId"/>
      <div class="cx-new-only">
        <#if multiChannel><label>WhatsApp number it belongs to<select name="channelId"><#list channels as ch><option value="${ch.channelId}">${ch.displayPhoneNumber!ch.channelName!ch.channelId}</option></#list></select></label>
        <#elseif channels?has_content><input type="hidden" name="channelId" value="${channels[0].channelId}"/></#if>
        <label>WhatsApp number<input type="tel" name="phone" placeholder="+91 98123 45678" maxlength="30"/></label>
      </div>
      <div class="cx-edit-only cx-phone-ro"></div>
      <div class="cx-grid2">
        <label>Name<input type="text" name="profileName" maxlength="100"/></label>
        <label>Email<input type="email" name="email" maxlength="250"/></label>
      </div>
      <label>Tags <small>comma separated</small><input type="text" name="tags" list="cxTagList" placeholder="vip, gold, gwalior" maxlength="600"/></label>
      <#if fieldDefs?has_content>
      <div class="cx-grid2">
        <#list fieldDefs as d><label>${d.label}<input type="text" name="f_${d.fieldKey}" maxlength="500"/></label></#list>
      </div>
      </#if>
      <label class="cx-toggle"><input type="checkbox" name="optIn" value="Y" checked/><i></i>
        <span><b>Opted in to broadcasts</b><small id="cxConsentInfo">Only message people who agreed to hear from you. Customers can reply STOP at any time.</small></span></label>
      <p class="cx-err" id="cxEditErr" hidden></p>
      <div class="cx-modal-actions"><button type="button" class="cx-btn" data-close>Cancel</button><button type="submit" class="cx-btn cx-btn-primary">Save</button></div>
    </form>
  </div>

  <#-- ===== custom fields ===== -->
  <div class="cx-modal" id="cxFieldsModal" hidden>
    <div class="cx-modal-box">
      <h3>Custom fields</h3>
      <p class="cx-muted">Extra details you keep about customers, like city, birthday or customer ID. Use them in bot messages and broadcasts as <code>{{city}}</code>.</p>
      <ul class="cx-fields">
        <#list fieldDefs as d><li><span><b>${d.label}</b><code>{{${d.fieldKey}}}</code></span><#if isOwner><button type="button" class="cx-link cx-danger" data-remove-field="${d.fieldKey}">Remove</button></#if></li>
        <#else><li class="cx-muted">No custom fields yet.</li></#list>
      </ul>
      <form class="cx-row" id="cxFieldForm"><input type="text" name="label" placeholder="New field, e.g. City" maxlength="60" required/><button type="submit" class="cx-btn cx-btn-primary">Add field</button></form>
      <p class="cx-err" id="cxFieldErr" hidden></p>
      <div class="cx-modal-actions"><button type="button" class="cx-btn" data-close>Done</button></div>
    </div>
  </div>

  <#-- ===== CSV import ===== -->
  <div class="cx-modal" id="cxImportModal" hidden>
    <div class="cx-modal-box wide">
      <h3>Import contacts from CSV</h3>
      <div id="cxImp1">
        <p class="cx-muted">Export a sheet from Excel, Google Sheets or your CRM as <b>CSV</b>. It needs a column with WhatsApp numbers; name, email, tags and any other columns are optional.</p>
        <label class="cx-drop" id="cxDrop"><input type="file" id="cxFile" accept=".csv,text/csv,.txt"/><span>📄 <b>Choose a CSV file</b> or drop it here</span></label>
        <a class="cx-link" href="data:text/csv;charset=utf-8,phone%2Cname%2Cemail%2Ctags%2Ccity%0A%2B919812345678%2CRavi%20Kumar%2Cravi%40example.com%2Cvip%3Bgold%2CGwalior%0A9876543210%2CPriya%20Sharma%2C%2Cnew%2CIndore%0A" download="contacts-sample.csv">Download a sample file</a>
      </div>
      <div id="cxImp2" hidden>
        <p class="cx-muted"><b id="cxImpFile"></b> &middot; <span id="cxImpRows"></span> rows. Tell us what each column is:</p>
        <div class="cx-map-wrap"><table class="cx-table cx-map" id="cxMap"></table></div>
        <div class="cx-grid2">
          <#if multiChannel><label>Import into WhatsApp number<select id="cxImpChannel"><#list channels as ch><option value="${ch.channelId}">${ch.displayPhoneNumber!ch.channelName!ch.channelId}</option></#list></select></label>
          <#elseif channels?has_content><input type="hidden" id="cxImpChannel" value="${channels[0].channelId}"/></#if>
          <label>Country code for numbers without one<input type="text" id="cxImpCc" value="${defaultCountryCode}" maxlength="4" placeholder="91"/></label>
          <label>Add these tags to everyone <small>optional</small><input type="text" id="cxImpTags" list="cxTagList" placeholder="imported, diwali-list" maxlength="300"/></label>
        </div>
        <label class="cx-check"><input type="checkbox" id="cxImpUpdate" checked/> Update contacts that already exist (name, email, fields, tags)</label>
        <label class="cx-check cx-consent"><input type="checkbox" id="cxImpConsent"/> These people agreed to receive WhatsApp messages from my business (required by WhatsApp)</label>
        <p class="cx-err" id="cxImpErr" hidden></p>
        <div class="cx-modal-actions"><button type="button" class="cx-btn" id="cxImpBack">Back</button><button type="button" class="cx-btn cx-btn-primary" id="cxImpGo">Import</button></div>
      </div>
      <div id="cxImp3" hidden>
        <div class="cx-progress"><span id="cxImpBar"></span></div>
        <p id="cxImpStatus" class="cx-muted">Importing…</p>
        <div id="cxImpResult"></div>
        <div class="cx-modal-actions"><button type="button" class="cx-btn cx-btn-primary" id="cxImpDone" hidden>Done</button></div>
      </div>
      <div class="cx-modal-actions cx-imp-close"><button type="button" class="cx-btn" data-close>Close</button></div>
    </div>
  </div>
  <template id="cxMapOptions">
    <option value="">— Skip —</option><option value="phone">WhatsApp number</option><option value="name">Name</option><option value="email">Email</option><option value="tags">Tags</option>
    <#list fieldDefs as d><option value="f:${d.fieldKey}">${d.label}</option></#list>
  </template>
  <div class="cx-toast" id="cxToast"></div>
</div>
<script src="/js/crm.js?v=5"></script>
