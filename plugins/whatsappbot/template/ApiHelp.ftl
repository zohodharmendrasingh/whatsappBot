<div class="screenlet"><div class="screenlet-title-bar"><ul><li class="h3">REST API</li></ul></div><div class="screenlet-body wa-help">
<pre>
# send a text (within 24h of customer's last message)
curl -X POST ${apiBaseUrl}/messages \
  -H "X-Api-Key: wab_xxx" -H "Content-Type: application/json" \
  -d '{"to":"919876543210","text":"Your order #123 is shipped"}'

# send an approved template (any time)
curl -X POST ${apiBaseUrl}/messages -H "X-Api-Key: wab_xxx" -H "Content-Type: application/json" \
  -d '{"to":"919876543210","template":{"name":"order_update","language":"en","params":["#123","Shipped"]}}'

# list contacts / messages
curl -H "X-Api-Key: wab_xxx" "${apiBaseUrl}/contacts?limit=50"
curl -H "X-Api-Key: wab_xxx" "${apiBaseUrl}/messages?contactId=10000"
</pre>
Works from Zoho Deluge with <code>invokeurl</code> (header X-Api-Key), Zoho Flow webhooks, Postman, etc.
</div></div>
