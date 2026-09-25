<#assign pageTitle = "Start your free trial">
<#include "component://whatsappbot/template/site/SiteHead.ftl"/>
<#assign errorMsg = (request.getAttribute("waSignupError"))!((request.getAttribute("_ERROR_MESSAGE_"))!"")>
<#if !errorMsg?has_content && (request.getAttribute("_ERROR_MESSAGE_LIST_"))?has_content><#assign errorMsg = request.getAttribute("_ERROR_MESSAGE_LIST_")?join(" ")></#if>
<section class="s-signup">
  <div class="s-wrap s-signup-in">
    <div class="s-signup-copy">
      <h1>Start your ${trialDays}-day free trial</h1>
      <p class="s-lead">Create your workspace in under a minute. No credit card needed.</p>
      <ul class="s-ticks">
        <li>No-code WhatsApp chatbot builder</li>
        <li>Shared team inbox with human hand-off</li>
        <li>Broadcasts with approved templates</li>
        <li>REST API for Zoho, CRMs and ERPs</li>
      </ul>
    </div>
    <div class="s-card">
      <#if !signupEnabled>
        <h2>Sign-up is closed</h2><p>Please write to <a href="mailto:${supportEmail!"info@msoftdynamic.com"}">${supportEmail!"info@msoftdynamic.com"}</a>.</p>
      <#else>
      <h2>Create your account</h2>
      <#if errorMsg?has_content><div class="s-error">${errorMsg}</div></#if>
      <form method="post" action="<@ofbizUrl>doSignup</@ofbizUrl>" class="s-form" autocomplete="on">
        <label>Business name<input type="text" name="businessName" value="${parameters.businessName!}" required maxlength="100" autocomplete="organization"/></label>
        <label>Your name<input type="text" name="fullName" value="${parameters.fullName!}" maxlength="100" autocomplete="name"/></label>
        <label>Work email<small>You'll use this to sign in</small><input type="email" name="email" value="${parameters.email!}" required maxlength="200" autocomplete="email"/></label>
        <label>Mobile number<input type="tel" name="phone" value="${parameters.phone!}" maxlength="20" placeholder="+91" autocomplete="tel"/></label>
        <div class="s-row">
          <label>Password<input type="password" name="password" required minlength="8" autocomplete="new-password"/></label>
          <label>Confirm password<input type="password" name="confirmPassword" required minlength="8" autocomplete="new-password"/></label>
        </div>
        <label>Plan
          <select name="planId">
            <#list plans as p><option value="${p.planId}" <#if selectedPlan == p.planId>selected</#if>>${p.planName} - <#if (p.currencyUomId!"USD") == "USD">$<#elseif (p.currencyUomId!"") == "INR">&#8377;<#else>${p.currencyUomId!} </#if>${(p.monthlyPrice!0)?string(",##0")}/month after trial</option></#list>
          </select>
        </label>
        <div class="s-hp" aria-hidden="true"><label>Website<input type="text" name="website" tabindex="-1" autocomplete="off"/></label></div>
        <label class="s-check"><input type="checkbox" name="acceptTerms" value="Y" required/> I agree to the <a href="<@ofbizUrl>terms</@ofbizUrl>" target="_blank">terms of service</a> and <a href="<@ofbizUrl>privacy</@ofbizUrl>" target="_blank">privacy policy</a></label>
        <button type="submit" class="s-btn s-btn-primary s-btn-block s-btn-lg">Create workspace</button>
        <p class="s-muted s-center">Already have an account? <a href="<@ofbizUrl>main</@ofbizUrl>">Sign in</a></p>
      </form>
      </#if>
    </div>
  </div>
</section>
<#include "component://whatsappbot/template/site/SiteFoot.ftl"/>
