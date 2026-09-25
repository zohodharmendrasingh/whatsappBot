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
