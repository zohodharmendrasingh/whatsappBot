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
</body>
</html>
