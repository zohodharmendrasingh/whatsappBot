/* Plan & Billing page: plans, PayPal settings and this workspace's payment history */
import com.msoftdynamic.whatsapp.PayPalClient
import com.msoftdynamic.whatsapp.WaBillingEvents
import com.msoftdynamic.whatsapp.WaSignupEvents
import com.msoftdynamic.whatsapp.WaUtil
import org.apache.ofbiz.entity.util.EntityQuery

def tenantId = context.currentTenantId
def plans = WaSignupEvents.activePlans(delegator)
context.billingPlans = plans.collect { p ->
    [planId: p.planId, planName: p.planName, currency: p.currencyUomId ?: "USD",
     monthly: WaBillingEvents.priceFor(p, 1), yearly: WaBillingEvents.priceFor(p, 12),
     maxChannels: p.maxChannels, maxFlows: p.maxFlows, maxMessagesPerMonth: p.maxMessagesPerMonth]
}
context.yearlyMonthsCharged = WaUtil.propInt("paypal.yearly.months.charged", 10)
context.paypalConfigured = PayPalClient.isConfigured()
context.paypalClientId = PayPalClient.clientId()
context.hiddenPlanIds = WaUtil.propList("saas.hidden.plans")
context.paypalCurrency = plans ? (plans[0].currencyUomId ?: "USD") : "USD"
context.isOwner = tenantId && userLogin && WaUtil.getTenantRole(delegator, tenantId, userLogin.userLoginId) == "WA_OWNER"
context.payments = tenantId ? EntityQuery.use(delegator).from("WaPayment").where("tenantId", tenantId)
        .orderBy("-createdDate").maxRows(25).queryList().findAll { it.statusId != "CREATED" } : []
context.planNames = plans.collectEntries { [(it.planId): it.planName] }

// ---- add-ons: catalog, what this workspace uses, and what it bought
def addons = EntityQuery.use(delegator).from("WaAddon").where("isActive", "Y").orderBy("sequenceNum", "addonName").queryList()
context.addons = addons.collect { a ->
    [addonId: a.addonId, name: a.addonName, description: a.description, type: a.addonType, quantity: a.quantity,
     price: a.price ?: 0, currency: a.currencyUomId ?: "USD"]
}
context.addonNames = addons.collectEntries { [(it.addonId): it.addonName] } + EntityQuery.use(delegator).from("WaAddon").queryList().collectEntries { [(it.addonId): it.addonName] }
if (tenantId) {
    context.limitUsage = com.msoftdynamic.whatsapp.WaAddons.usage(delegator, tenantId)
    def now = org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp()
    context.activeAddons = EntityQuery.use(delegator).from("WaTenantAddon").where(
            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("tenantId", tenantId),
            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("thruDate", org.apache.ofbiz.entity.condition.EntityOperator.GREATER_THAN, now))
            .orderBy("thruDate").queryList()
}
context.addonFocus = parameters.addon ?: ""
