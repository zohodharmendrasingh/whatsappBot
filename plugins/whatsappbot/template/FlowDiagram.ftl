<#-- Compact text map of the flow so owners can see how steps connect -->
<#if flow??>
<#assign nodes = delegator.findByAnd("WaFlowNode", {"flowId": flow.flowId}, ["sequenceNum", "nodeId"], false)>
<div class="screenlet"><div class="screenlet-title-bar"><ul><li class="h3">How "${flow.flowName!}" runs</li></ul></div><div class="screenlet-body">
  <p class="wa-muted">Triggered by: <b>${flow.triggerKeywords!"(none)"}</b><#if flow.isDefault! == "Y"> · also the default reply</#if> · starts at <b>${flow.startNodeId!}</b></p>
  <#if !nodes?has_content><p>No steps yet. Add a step with id <b>${flow.startNodeId!"START"}</b> below.</p></#if>
  <ul class="wa-flow">
  <#list nodes as n>
    <li <#if n.nodeId == flow.startNodeId!>class="wa-start"</#if>>
      <a href="<@ofbizUrl>EditFlowNode?flowId=${n.flowId}&amp;nodeId=${n.nodeId}</@ofbizUrl>"><b>${n.nodeId}</b></a>
      <span class="wa-badge">${(n.nodeTypeId!"")?replace("WA_NODE_","")}</span>
      <span class="wa-muted">${((n.messageText!"")?length > 70)?then((n.messageText!"")?substring(0,70) + "…", n.messageText!"")}</span>
      <#assign opts = delegator.findByAnd("WaFlowNodeOption", {"flowId": n.flowId, "nodeId": n.nodeId}, ["optionSeqId"], false)>
      <#if opts?has_content>
        <ul><#list opts as o><li>[${o.optionLabel!}] → ${o.targetNodeId!"end"}</li></#list></ul>
      <#elseif n.nextNodeId?has_content>→ ${n.nextNodeId}
      <#elseif n.nodeTypeId! == "WA_NODE_GOTO_FLOW">→ flow ${n.targetFlowId!}
      </#if>
    </li>
  </#list>
  </ul>
</div></div>
</#if>
