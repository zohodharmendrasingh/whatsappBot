<#assign appId = metaAppId!"">
<#assign configId = metaSignupConfigId!"">
<#if !appId?has_content || !configId?has_content>
  <p class="wa-muted">Embedded Signup is not configured. Set <code>meta.app.id</code>, <code>meta.app.secret</code> and
  <code>meta.embedded.signup.config.id</code> in <code>plugins/whatsappbot/config/whatsappbot.properties</code>, or add the number manually below.</p>
<#else>
<p>Log in with Facebook, pick or create the business and WhatsApp number. We'll connect it automatically.</p>
<p><label>2-step verification PIN for the number (6 digits, you choose it): <input type="text" id="waPin" maxlength="6" size="8" pattern="[0-9]{6}"/></label></p>
<button type="button" class="buttontext wa-fb" onclick="waLaunchSignup()">Connect WhatsApp with Facebook</button>
<span id="waSignupStatus" class="wa-muted"></span>

<form id="waSignupForm" method="post" action="<@ofbizUrl>waCompleteEmbeddedSignup</@ofbizUrl>" style="display:none">
  <input type="hidden" name="tenantId" value="${currentTenantId!}"/>
  <input type="hidden" name="code"/><input type="hidden" name="wabaId"/><input type="hidden" name="phoneNumberId"/><input type="hidden" name="pin"/>
</form>
<script>
  var waSession = {};
  window.fbAsyncInit = function () { FB.init({appId: '${appId}', autoLogAppEvents: true, xfbml: true, version: '${graphVersion}'}); };
  window.addEventListener('message', function (event) {
    if (!/facebook\.com$/.test(new URL(event.origin).hostname)) return;
    try {
      var data = JSON.parse(event.data);
      if (data.type === 'WA_EMBEDDED_SIGNUP') {
        if (data.event === 'FINISH' || data.event === 'FINISH_ONLY_WABA') { waSession = data.data; waMaybeSubmit(); }
        else if (data.event === 'CANCEL') { document.getElementById('waSignupStatus').innerText = 'Signup cancelled at: ' + (data.data.current_step || ''); }
      }
    } catch (e) { /* not ours */ }
  });
  var waCode = null;
  function waMaybeSubmit() {
    if (!waCode || !waSession.waba_id) return;
    var f = document.getElementById('waSignupForm');
    f.code.value = waCode; f.wabaId.value = waSession.waba_id; f.phoneNumberId.value = waSession.phone_number_id || '';
    f.pin.value = document.getElementById('waPin').value;
    document.getElementById('waSignupStatus').innerText = 'Connecting…';
    f.submit();
  }
  function waLaunchSignup() {
    FB.login(function (response) {
      if (response.authResponse && response.authResponse.code) { waCode = response.authResponse.code; waMaybeSubmit(); }
      else { document.getElementById('waSignupStatus').innerText = 'Facebook login was not completed.'; }
    }, {config_id: '${configId}', response_type: 'code', override_default_response_type: true,
        extras: {setup: {}, featureType: '', sessionInfoVersion: '3'}});
  }
</script>
<script async defer crossorigin="anonymous" src="https://connect.facebook.net/en_US/sdk.js"></script>
</#if>
