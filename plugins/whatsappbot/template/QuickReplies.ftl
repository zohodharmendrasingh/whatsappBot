<#-- Settings: saved replies used in the inbox (type / in the reply box) -->
<link rel="stylesheet" href="/theme/crm.css?v=3"/>
<div class="cx" id="cxQrSettings" data-save-url="<@ofbizUrl>quickReplySave</@ofbizUrl>" data-delete-url="<@ofbizUrl>quickReplyDelete</@ofbizUrl>">
  <a id="replies"></a>
  <p class="cx-muted">Answers your team sends often. In the inbox, type <b>/</b> and the name (e.g. <code>/price</code>) to insert one. <code>{{name}}</code> becomes the customer's first name.</p>
  <#if savedReplies?has_content>
  <ul class="cx-qr-list">
    <#list savedReplies as r>
      <li data-sc="${r.shortcut}"><b>/${r.shortcut}</b><span class="cx-qr-body">${r.body}</span>
        <span class="cx-qr-act"><button type="button" class="cx-link" data-qr-edit="${r.quickReplyId}">Edit</button><button type="button" class="cx-link cx-danger" data-qr-del="${r.quickReplyId}">Delete</button></span></li>
    </#list>
  </ul>
  </#if>
  <form id="cxQrForm" class="cx-qr-form" autocomplete="off">
    <input type="hidden" name="quickReplyId"/>
    <label>Name<span class="cx-prefix"><i>/</i><input type="text" name="shortcut" maxlength="30" placeholder="price" required/></span></label>
    <label class="cx-grow">Reply text<textarea name="body" rows="3" maxlength="4000" placeholder="Hi {{name}}, our gold rate today is ₹… per gram. Would you like to visit the store?" required></textarea></label>
    <div class="cx-qr-btns"><button type="submit" class="cx-btn cx-btn-primary" id="cxQrSave">Save reply</button><button type="button" class="cx-btn" id="cxQrCancel" hidden>Cancel</button></div>
  </form>
  <p class="cx-err" id="cxQrErr" hidden></p>
</div>
<script src="/js/crm.js?v=3"></script>
