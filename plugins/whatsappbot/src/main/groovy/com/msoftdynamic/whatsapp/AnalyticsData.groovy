/* Analytics: messages per day, first reply times, bot vs team, flows and broadcasts for 7 / 30 / 90 days */
import com.msoftdynamic.whatsapp.WaAnalytics

def tenantId = context.currentTenantId
if (!tenantId) return
def daysStr = (parameters.days ?: "30").toString()
int days = daysStr in ["7", "30", "90"] ? (daysStr as int) : 30
// the browser tells us its time zone once; days are counted in that zone
def tzId = parameters.tz ?: session?.getAttribute("waTz")
def tz = null
if (tzId && tzId ==~ /[A-Za-z_\/+\-0-9]{1,64}/ && TimeZone.getAvailableIDs().contains(tzId)) {
    tz = TimeZone.getTimeZone(tzId)
    session?.setAttribute("waTz", tzId)
}
context.analyticsTzKnown = tz != null
if (tz == null) tz = context.timeZone ?: TimeZone.getDefault()
context.analyticsTz = tz.getID()
context.a = WaAnalytics.compute(delegator, tenantId, days, tz)
