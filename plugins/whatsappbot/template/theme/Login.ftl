<#if requestAttributes.uiLabelMap??><#assign uiLabelMap = requestAttributes.uiLabelMap></#if>
<#assign username = requestParameters.USERNAME?default((sessionAttributes.autoUserLogin.userLoginId)?default(""))>
<div class="msoft-login">
  <div class="msoft-login-brand">
    <a href="/control/home"><img src="/theme/flochat-logo.svg" alt="FloChat" height="44"/></a>
    <h1>WhatsApp automation for growing businesses</h1>
    <p>Automate customer conversations on WhatsApp: chatbot flows, shared team inbox, broadcasts and APIs, all in one place.</p>
    <ul>
      <li>Official WhatsApp Cloud API</li>
      <li>No-code bot builder</li>
      <li>Team inbox with human hand-off</li>
      <li>REST API for Zoho, CRMs and ERPs</li>
    </ul>
  </div>
  <div class="msoft-login-card">
    <h2>Welcome back</h2>
    <p class="msoft-muted">Sign in to your FloChat workspace.</p>
    <form method="post" action="<@ofbizUrl>login</@ofbizUrl>" name="loginform">
      <label>Username<input type="text" name="USERNAME" value="${username}" autocomplete="username"/></label>
      <label>Password<input type="password" name="PASSWORD" autocomplete="current-password" value=""/></label>
      <input type="hidden" name="JavaScriptEnabled" value="N"/>
      <button type="submit">Sign in</button>
      <a class="msoft-forgot" href="<@ofbizUrl>forgotPassword</@ofbizUrl>">${uiLabelMap.CommonForgotYourPassword}</a>
      <p class="msoft-signup-link">New to FloChat? <a href="/control/signup">Start a free trial</a></p>
    </form>
  </div>
</div>
<script type="application/javascript">
  document.loginform.JavaScriptEnabled.value = "Y";
  <#if username != "">document.loginform.PASSWORD.focus();<#else>document.loginform.USERNAME.focus();</#if>
</script>
