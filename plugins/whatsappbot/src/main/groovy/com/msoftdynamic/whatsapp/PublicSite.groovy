/* Data for the public landing, pricing and sign-up pages */
import com.msoftdynamic.whatsapp.WaSignupEvents
import com.msoftdynamic.whatsapp.WaUtil

context.plans = WaSignupEvents.publicPlans(delegator)
context.trialDays = WaUtil.propInt("saas.trial.days", 14)
context.signupEnabled = "true".equalsIgnoreCase(WaUtil.prop("saas.signup.enabled", "true"))
context.selectedPlan = parameters.planId ?: parameters.plan ?: WaUtil.prop("saas.signup.default.plan", "WA_STARTER")
context.isLoggedIn = userLogin != null
context.brandName = WaUtil.prop("brand.name", "FloChat")
context.brandDomain = WaUtil.prop("brand.domain", "flochat.flolink.ai")
context.supportEmail = WaUtil.prop("brand.support.email", "info@msoftdynamic.com")
context.yearlyFree = 12 - WaUtil.propInt("paypal.yearly.months.charged", 10)
context.legalName = WaUtil.prop("brand.legal.name", "Msoft Dynamic Technologies (OPC) Private Limited")
context.legalAddress = WaUtil.prop("brand.legal.address", "Gwalior, Madhya Pradesh, India")
