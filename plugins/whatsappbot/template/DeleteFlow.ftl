<#if flow??>
<form class="wa-danger" method="post" action="<@ofbizUrl>deleteWaFlow</@ofbizUrl>" onsubmit="return confirm('Delete this flow and all its steps?');">
  <input type="hidden" name="flowId" value="${flow.flowId}"/>
  <input type="submit" class="smallSubmit" value="Delete this flow"/>
</form>
</#if>
