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
context.companyName = WaUtil.prop("brand.company.name", "FloLink")
context.flowlinkerUrl = WaUtil.prop("brand.flowlinker.url", "https://app.flolink.ai/app/")
context.flowlinkerTagline = WaUtil.prop("brand.flowlinker.tagline", "No-code Zoho CRM and Zoho Books sync. Invoices, products and accounts flow across automatically. Free.")
context.billeaseUrl = WaUtil.prop("brand.billease.url", "https://app.msoftdynamic.com/app/")
context.billeaseTagline = WaUtil.prop("brand.billease.tagline", "Billing and POS for shops: fast checkout, tax invoices, stock alerts and sales reports.")
context.productsSiteUrl = WaUtil.prop("brand.products.url", "https://flolink.ai")
context.supportEmail = WaUtil.prop("brand.support.email", "info@msoftdynamic.com")
context.yearlyFree = 12 - WaUtil.propInt("paypal.yearly.months.charged", 10)
context.legalName = WaUtil.prop("brand.legal.name", "Msoft Dynamic Technologies (OPC) Private Limited")
context.legalAddress = WaUtil.prop("brand.legal.address", "Gwalior, Madhya Pradesh, India")
