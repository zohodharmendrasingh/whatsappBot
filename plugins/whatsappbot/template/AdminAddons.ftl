<#-- Platform admin: add-ons customers can buy on Plan & Billing -->
<p class="msoft-muted">Customers buy these on <b>Plan &amp; Billing</b> when they need more. Messages last until the end of the calendar month; numbers and flows for 30 days.</p>
<table class="basic-table hover-bar">
  <tr class="header-row"><td>Id</td><td>Name</td><td>Description</td><td>Type</td><td>Adds</td><td>Price</td><td>Currency</td><td>Order</td><td>Active</td><td></td></tr>
  <#list allAddons as a>
  <tr><form method="post" action="<@ofbizUrl>addonSave</@ofbizUrl>">
    <td><input type="hidden" name="addonId" value="${a.addonId}"/>${a.addonId}</td>
    <td><input type="text" name="addonName" value="${a.addonName!}" size="22"/></td>
    <td><input type="text" name="description" value="${a.description!}" size="30"/></td>
    <td><select name="addonType"><#list ["MESSAGES","CHANNELS","FLOWS"] as t><option value="${t}" <#if a.addonType! == t>selected</#if>>${t?lower_case?cap_first}</option></#list></select></td>
    <td><input type="number" name="quantity" value="${(a.quantity!1)?c}" min="1" style="width:90px"/></td>
    <td><input type="text" name="price" value="${(a.price!0)?string("0.00")}" size="7"/></td>
    <td><input type="text" name="currencyUomId" value="${a.currencyUomId!}" size="4"/></td>
    <td><input type="number" name="sequenceNum" value="${(a.sequenceNum!10)?c}" style="width:60px"/></td>
    <td><select name="isActive"><option value="Y" <#if a.isActive! == "Y">selected</#if>>Yes</option><option value="N" <#if a.isActive! != "Y">selected</#if>>No</option></select></td>
    <td><input type="submit" class="smallSubmit" value="Save"/></td>
  </form></tr>
  </#list>
  <tr><form method="post" action="<@ofbizUrl>addonSave</@ofbizUrl>">
    <td><input type="text" name="addonId" placeholder="new id" size="10"/></td>
    <td><input type="text" name="addonName" placeholder="5,000 extra messages" size="22"/></td>
    <td><input type="text" name="description" placeholder="Extra messages for this month" size="30"/></td>
    <td><select name="addonType"><option value="MESSAGES">Messages</option><option value="CHANNELS">Channels</option><option value="FLOWS">Flows</option></select></td>
    <td><input type="number" name="quantity" value="1000" min="1" style="width:90px"/></td>
    <td><input type="text" name="price" value="5.00" size="7"/></td>
    <td><input type="text" name="currencyUomId" value="USD" size="4"/></td>
    <td><input type="number" name="sequenceNum" value="10" style="width:60px"/></td>
    <td><input type="hidden" name="isActive" value="Y"/>Yes</td>
    <td><input type="submit" class="smallSubmit" value="Add"/></td>
  </form></tr>
</table>
<#if !allAddons?has_content>
  <form method="post" action="<@ofbizUrl>addonSeed</@ofbizUrl>" style="margin-top:12px"><input type="submit" class="smallSubmit" value="Add suggested add-ons (2k / 10k messages, extra number, 10 flows)"/></form>
</#if>
