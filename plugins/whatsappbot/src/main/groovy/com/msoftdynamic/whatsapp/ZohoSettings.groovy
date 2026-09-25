/* Settings > Zoho: connection status for the current workspace (never exposes tokens) */
import com.msoftdynamic.whatsapp.WaUtil
import com.msoftdynamic.whatsapp.WaZohoEvents
import com.msoftdynamic.whatsapp.ZohoClient

def t = context.currentTenantId
if (!t) return
def z = WaZohoEvents.summary(delegator, t)
context.zoho = z
context.zohoApps = ZohoClient.APPS
context.zohoAppNames = ZohoClient.APP_NAMES
context.zohoIsOwner = context.isWaAdmin == "Y" || WaUtil.getTenantRole(delegator, t, userLogin.userLoginId) == "WA_OWNER"
context.zohoRedirectUri = WaZohoEvents.redirectUri(request)
def flash = session.getAttribute("zohoFlash")
if (flash) { session.removeAttribute("zohoFlash"); context.zohoFlash = flash }
def tf = WaZohoEvents.takeFlash(t)
if (tf) context.zohoFlash = tf
if (z.connected) {
    if ("books" in z.apps) context.zohoBooksOrgs = WaZohoEvents.orgs(delegator, t, "books")
    if ("inventory" in z.apps) context.zohoInventoryOrgs = WaZohoEvents.orgs(delegator, t, "inventory")
}
