<#if flow??>
<p><a class="buttontext" href="<@ofbizUrl>EditFlow?flowId=${flow.flowId}</@ofbizUrl>">← Back to flow ${flow.flowName!}</a>
<#if node??>
<form method="post" action="<@ofbizUrl>deleteWaFlowNode</@ofbizUrl>" class="wa-inline wa-danger" onsubmit="return confirm('Delete this step and its options?');">
  <input type="hidden" name="flowId" value="${node.flowId}"/><input type="hidden" name="nodeId" value="${node.nodeId}"/>
  <input type="submit" class="smallSubmit" value="Delete step"/>
</form>
</#if></p>
<p class="wa-muted">BUTTONS: up to 3 options (more are sent as a list). LIST: up to 10 options. Customers can also type the option number or its keywords.</p>
</#if>
