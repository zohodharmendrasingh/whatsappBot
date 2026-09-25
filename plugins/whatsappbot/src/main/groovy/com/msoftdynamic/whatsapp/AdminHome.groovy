/* Platform owner overview: customers, MRR, signups, trials expiring, top usage */
import com.msoftdynamic.whatsapp.WaUtil
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.condition.EntityCondition as EC
import org.apache.ofbiz.entity.condition.EntityOperator as EO
import org.apache.ofbiz.entity.util.EntityQuery

def now = UtilDateTime.nowTimestamp()
def tenants = EntityQuery.use(delegator).from("WaTenant").queryList()
def plans = EntityQuery.use(delegator).from("WaPlan").queryList().collectEntries { [(it.planId): it] }
def byStatus = tenants.groupBy { it.statusId }
BigDecimal mrr = 0
tenants.findAll { it.statusId == "WA_TNT_ACTIVE" }.each { t -> mrr += (plans[t.planId]?.monthlyPrice ?: 0) }
def period = WaUtil.currentPeriod()
def usage = EntityQuery.use(delegator).from("WaUsage").where("periodId", period).queryList()
def since30 = UtilDateTime.getDayStart(now, -30)
def in7 = UtilDateTime.getDayStart(now, 8)

context.admin = [
    total      : tenants.size(),
    active     : (byStatus["WA_TNT_ACTIVE"] ?: []).size(),
    trial      : (byStatus["WA_TNT_TRIAL"] ?: []).size(),
    inactive   : (byStatus["WA_TNT_SUSPENDED"] ?: []).size() + (byStatus["WA_TNT_CANCELLED"] ?: []).size(),
    mrr        : mrr,
    signups30  : tenants.count { it.createdDate && it.createdDate.after(since30) },
    msgsOut    : usage.sum(0L) { it.messagesOut ?: 0L },
    msgsIn     : usage.sum(0L) { it.messagesIn ?: 0L },
    channels   : EntityQuery.use(delegator).from("WaChannel").where("isActive", "Y").queryCount(),
    webhookErr : EntityQuery.use(delegator).from("WaWebhookLog").where("processedStatus", "ERROR").queryCount()
]
context.plansById = plans
context.recentSignups = tenants.findAll { it.createdDate }.sort { -it.createdDate.time }.take(8)
context.expiringTrials = tenants.findAll { it.statusId == "WA_TNT_TRIAL" && it.subscriptionThruDate && it.subscriptionThruDate.before(in7) }
        .sort { it.subscriptionThruDate.time }
def names = tenants.collectEntries { [(it.tenantId): it.tenantName] }
context.topUsage = usage.sort { -(it.messagesOut ?: 0L) }.take(5).collect { [tenantId: it.tenantId, name: names[it.tenantId], out: it.messagesOut ?: 0L, inn: it.messagesIn ?: 0L] }
// PayPal revenue
def monthStart = UtilDateTime.getMonthStart(now)
def paid = EntityQuery.use(delegator).from("WaPayment").where("statusId", "COMPLETED").orderBy("-completedDate").queryList()
context.admin.revenueMonth = paid.findAll { it.completedDate && !it.completedDate.before(monthStart) }.sum(0.0) { it.amount ?: 0 }
context.recentPayments = paid.take(8).collect { [tenantId: it.tenantId, name: names[it.tenantId] ?: it.tenantId, plan: plans[it.planId]?.planName ?: it.planId,
        months: it.months ?: 1, amount: it.amount ?: 0, date: it.completedDate, ref: it.providerCaptureId, payer: it.payerEmail] }
