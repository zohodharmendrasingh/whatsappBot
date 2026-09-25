/* Tenant dashboard data: KPIs, 14-day message series, delivery rate, onboarding checklist */
import com.msoftdynamic.whatsapp.WaUtil
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.condition.EntityCondition as EC
import org.apache.ofbiz.entity.condition.EntityOperator as EO
import org.apache.ofbiz.entity.util.EntityQuery

def scope = context.scopeTenantId
if (!scope && context.isWaAdmin != "Y") {
    scope = "_NO_TENANT_" // never show unscoped data to non-admins
}
def base = { Map extra = [:] ->
    def list = []
    if (scope) list << EC.makeCondition("tenantId", scope)
    extra.each { k, v -> list << EC.makeCondition(k, v) }
    return list
}
def count = { String entity, List conds -> EntityQuery.use(delegator).from(entity).where(EC.makeCondition(conds)).queryCount() }
def between = { from, to -> [EC.makeCondition("createdDate", EO.GREATER_THAN_EQUAL_TO, from), EC.makeCondition("createdDate", EO.LESS_THAN, to)] }

def now = UtilDateTime.nowTimestamp()
def today = UtilDateTime.getDayStart(now)
def tomorrow = UtilDateTime.getDayStart(now, 1)

// 14-day series
def series = []
int maxVal = 1
(13..0).each { back ->
    def d0 = UtilDateTime.getDayStart(now, -back)
    def d1 = UtilDateTime.getDayStart(now, -back + 1)
    long inC = count("WaMessage", base([direction: "IN"]) + between(d0, d1))
    long outC = count("WaMessage", base([direction: "OUT"]) + between(d0, d1))
    maxVal = Math.max(maxVal, (int) Math.max(inC, outC))
    series << [label: new java.text.SimpleDateFormat("dd MMM").format(d0), inC: inC, outC: outC]
}
context.series = series
context.seriesMax = maxVal

// delivery rate over the last 7 days (outbound)
def week = between(UtilDateTime.getDayStart(now, -6), tomorrow)
long sent7 = count("WaMessage", base([direction: "OUT"]) + week)
long ok7 = EntityQuery.use(delegator).from("WaMessage").where(EC.makeCondition(base([direction: "OUT"]) + week +
        [EC.makeCondition("deliveryStatus", EO.IN, ["delivered", "read"])])).queryCount()
long read7 = count("WaMessage", base([direction: "OUT", deliveryStatus: "read"]) + week)
long failed7 = count("WaMessage", base([direction: "OUT", deliveryStatus: "failed"]) + week)

context.stats = [
    contacts     : count("WaContact", base()),
    waitingAgent : count("WaContact", base([botPaused: "Y"])),
    inToday      : count("WaMessage", base([direction: "IN"]) + between(today, tomorrow)),
    outToday     : count("WaMessage", base([direction: "OUT"]) + between(today, tomorrow)),
    channels     : count("WaChannel", base()),
    flows        : count("WaFlow", base()),
    templates    : count("WaTemplate", base([metaStatus: "APPROVED"])),
    sent7        : sent7,
    deliveryRate : sent7 ? Math.round(ok7 * 100d / sent7) : null,
    readRate     : sent7 ? Math.round(read7 * 100d / sent7) : null,
    failed7      : failed7
]
context.checklist = [
    [done: context.stats.channels > 0, label: "Connect your WhatsApp number", target: "Channels"],
    [done: context.stats.flows > 0, label: "Build your first bot flow", target: "FindFlow"],
    [done: count("WaMessage", base([direction: "IN"])) > 0, label: "Receive your first customer message", target: "Inbox"],
    [done: context.stats.templates > 0, label: "Sync an approved template for broadcasts", target: "Templates"]
]
context.recentContacts = EntityQuery.use(delegator).from("WaContact").where(EC.makeCondition(base()))
        .orderBy("-lastMessageDate").maxRows(6).queryList()
context.periodId = WaUtil.currentPeriod()
