<div id="footer-offset"></div>
<div id="footer" class="msoft-footer">
    <span>&copy; ${nowTimestamp?string("yyyy")} ${brandName!"FloChat"} &middot; ${brandDomain!"flochat.flolink.ai"}</span>
    <span>Need help? <a href="mailto:${supportEmail!"info@msoftdynamic.com"}">${supportEmail!"info@msoftdynamic.com"}</a></span>
</div>
</div>
<#if layoutSettings.VT_FTR_JAVASCRIPT?has_content>
  <#list layoutSettings.VT_FTR_JAVASCRIPT as javaScript>
    <script type="application/javascript" src="<@ofbizContentUrl>${StringUtil.wrapString(javaScript)}</@ofbizContentUrl>"></script>
  </#list>
</#if>
<@scriptTagsFooter/>
<script type="application/javascript">
/* keep browsers from auto-filling the FloChat login into Meta/API credential fields */
document.querySelectorAll('input[name=newAccessToken], input[type=password][name*=Token], input[type=password][name*=token]').forEach(function (e) { e.setAttribute('autocomplete', 'new-password'); });
/* success notices fade out after 8 seconds; errors stay until clicked */
setTimeout(function () { var m = document.querySelector('#content-messages.eventMessage'); if (m) { m.style.transition = 'opacity .4s'; m.style.opacity = '0'; setTimeout(function () { m.remove(); }, 450); } }, 8000);
document.querySelectorAll('input[name=wabaId], input[name=phoneNumberId], input[name=displayPhoneNumber]').forEach(function (e) { e.setAttribute('autocomplete', 'off'); });
</script>
</body>
</html>
