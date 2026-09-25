<#-- Plan & Billing: choose plan + period, pay with PayPal (JS SDK buttons), payment history -->
<#macro money cur amt><#if cur == "USD">$<#elseif cur == "INR">&#8377;<#else>${cur} </#if>${amt?string(",##0.##")}</#macro>
<#assign status = currentTenant.statusId!>
<#assign curPlanId = currentTenant.planId!>
<div class="fc-bill">
  <div class="fc-bill-status">
    <div>
      <div class="fc-bill-label">Current plan</div>
      <div class="fc-bill-plan">${(currentPlan.planName)!"None"}
        <#switch status>
          <#case "WA_TNT_ACTIVE"><span class="ms-pill ms-pill-green">Active</span><#break>
          <#case "WA_TNT_TRIAL"><span class="ms-pill ms-pill-blue">Free trial</span><#break>
          <#default><span class="ms-pill ms-pill-red">${status?replace("WA_TNT_","")?capitalize}</span>
        </#switch>
      </div>
      <div class="fc-bill-sub">
        <#if currentTenant.subscriptionThruDate?has_content>
          <#if status == "WA_TNT_TRIAL">Trial ends<#else>Paid until</#if> <strong>${currentTenant.subscriptionThruDate?string("dd MMM yyyy")}</strong>
          <#if trialDaysLeft??> &middot; ${trialDaysLeft} day<#if trialDaysLeft != 1>s</#if> left</#if>
        </#if>
      </div>
    </div>
    <div class="fc-bill-secure"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg> Secure checkout by PayPal. Pay with your PayPal balance or any debit or credit card.</div>
  </div>

  <#if parameters.paid?has_content>
    <div class="fc-bill-ok">Payment received. Your ${(currentPlan.planName)!} plan is active until ${(currentTenant.subscriptionThruDate?string("dd MMM yyyy"))!}. Thank you!</div>
  </#if>

  <div class="fc-bill-head">
    <h2>Choose your plan</h2>
    <div class="fc-period" role="radiogroup" aria-label="Billing period">
      <button type="button" class="on" data-months="1">Monthly</button>
      <button type="button" data-months="12">Yearly <em>${12 - yearlyMonthsCharged} months free</em></button>
    </div>
  </div>

  <div class="fc-plans">
    <#list billingPlans as p>
      <label class="fc-plan<#if p.planId == curPlanId> current</#if>">
        <input type="radio" name="fcPlan" value="${p.planId}" <#if p.planId == curPlanId || (!curPlanId?has_content && p?index == 0)>checked</#if>/>
        <div class="fc-plan-top">
          <span class="fc-plan-name"><i class="fc-radio"></i>${p.planName}</span>
          <span><#if hiddenPlanIds?seq_contains(p.planId?lower_case)><span class="ms-pill ms-pill-amber">Test only</span></#if><#if p.planId == curPlanId> <span class="ms-pill">Current</span></#if></span>
        </div>
        <div class="fc-price" data-m="<@money p.currency p.monthly/>" data-y="<@money p.currency p.yearly/>">
          <span class="fc-amt"><@money p.currency p.monthly/></span><small class="fc-per">/month</small>
        </div>
        <ul>
          <li>${(p.maxChannels)?has_content?then(p.maxChannels + " WhatsApp number" + ((p.maxChannels!1) gt 1)?then("s",""), "Unlimited WhatsApp numbers")}</li>
          <li>${(p.maxFlows)?has_content?then(p.maxFlows + " bot flows", "Unlimited bot flows")}</li>
          <li>${(p.maxMessagesPerMonth)?has_content?then(p.maxMessagesPerMonth?string(",##0") + " messages / month", "Unlimited messages")}</li>
        </ul>
      </label>
    </#list>
  </div>

  <div class="fc-pay">
    <#if !isOwner>
      <p class="fc-note">Only the workspace owner can change the plan or pay. Ask your owner to open Plan &amp; Billing.</p>
    <#elseif !paypalConfigured>
      <p class="fc-note">Online payment is being set up. Please contact <a href="mailto:${supportEmail!"info@msoftdynamic.com"}">${supportEmail!"info@msoftdynamic.com"}</a> to upgrade.</p>
    <#else>
      <div class="fc-pay-summary">You pay <strong id="fcTotal"></strong> <span id="fcFor"></span></div>
      <div id="fcMsg" class="fc-msg" role="alert" hidden></div>
      <div id="paypal-buttons" class="fc-pp"></div>
    </#if>
  </div>

  <#if payments?has_content>
    <div class="fc-history">
      <h2>Payment history</h2>
      <table class="basic-table hover-bar">
        <thead><tr><th>Date</th><th>Plan</th><th>Period</th><th>Amount</th><th>Status</th><th>PayPal reference</th></tr></thead>
        <tbody>
        <#list payments as pay>
          <tr>
            <td>${pay.createdDate?string("dd MMM yyyy")}</td>
            <td>${planNames[pay.planId!]!pay.planId!}</td>
            <td><#if pay.periodFromDate?has_content>${pay.periodFromDate?string("dd MMM yyyy")} &ndash; ${pay.periodThruDate?string("dd MMM yyyy")}<#else>${pay.months!1} month<#if (pay.months!1) != 1>s</#if></#if></td>
            <td><@money pay.currencyUomId!"USD" pay.amount!0/></td>
            <td><#switch pay.statusId!><#case "COMPLETED"><span class="ms-pill ms-pill-green">Paid</span><#break><#case "PENDING"><span class="ms-pill ms-pill-amber">Pending</span><#break><#default><span class="ms-pill ms-pill-red">Failed</span></#switch></td>
            <td><code>${pay.providerCaptureId!pay.providerOrderId!}</code></td>
          </tr>
        </#list>
        </tbody>
      </table>
    </div>
  </#if>
</div>

<script type="application/javascript">
(function () {
  var months = 1;
  function plan() { var r = document.querySelector('input[name=fcPlan]:checked'); return r ? r.value : ''; }
  function refresh() {
    document.querySelectorAll('.fc-price').forEach(function (el) {
      el.querySelector('.fc-amt').textContent = months === 12 ? el.dataset.y : el.dataset.m;
      el.querySelector('.fc-per').textContent = months === 12 ? '/year' : '/month';
    });
    document.querySelectorAll('.fc-plan').forEach(function (l) { l.classList.toggle('sel', l.querySelector('input').checked); });
    var sel = document.querySelector('input[name=fcPlan]:checked');
    var total = document.getElementById('fcTotal');
    if (sel && total) {
      var pr = sel.closest('.fc-plan').querySelector('.fc-price');
      total.textContent = months === 12 ? pr.dataset.y : pr.dataset.m;
      document.getElementById('fcFor').textContent = 'for ' + sel.closest('.fc-plan').querySelector('.fc-plan-name').textContent + ', ' + (months === 12 ? '12 months' : '1 month');
    }
  }
  document.querySelectorAll('.fc-period button').forEach(function (b) {
    b.addEventListener('click', function () {
      months = parseInt(b.dataset.months, 10);
      document.querySelectorAll('.fc-period button').forEach(function (x) { x.classList.toggle('on', x === b); });
      refresh(); rerender();
    });
  });
  document.querySelectorAll('input[name=fcPlan]').forEach(function (r) { r.addEventListener('change', function () { refresh(); rerender(); }); });
  refresh();
  var rerender = function () {};

  var box = document.getElementById('paypal-buttons');
  if (!box) { return; }
  var msg = document.getElementById('fcMsg');
  function show(text, ok) { msg.hidden = false; msg.className = 'fc-msg' + (ok ? ' ok' : ''); msg.textContent = text; }
  function post(url, data) {
    return fetch(url, { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(data).toString() }).then(function (r) { return r.json(); });
  }
  var s = document.createElement('script');
  s.src = 'https://www.paypal.com/sdk/js?client-id=${paypalClientId?url("UTF-8")}&currency=${paypalCurrency}&intent=capture&components=buttons';
  s.onerror = function () { show('Could not load PayPal. Please check your connection or disable ad blockers and reload.'); };
  var buttons = null, gen = 0;
  // A PayPal order is fixed to the plan/period chosen when the buyer clicks. When the choice changes,
  // close any open PayPal/card form and render fresh buttons so the next order uses the new amount.
  function renderButtons() {
    var my = ++gen;
    var old = buttons; buttons = null;
    var done = old && old.close ? old.close().catch(function () {}) : Promise.resolve();
    done.then(function () {
      if (my !== gen) { return; }
      box.innerHTML = '';
      buttons = makeButtons();
      buttons.render('#paypal-buttons');
    });
  }
  function makeButtons() {
    return paypal.Buttons({
      style: { layout: 'vertical', shape: 'rect', label: 'pay', height: 45 },
      createOrder: function () {
        msg.hidden = true;
        return post('<@ofbizUrl>paypalCreateOrder</@ofbizUrl>', { planId: plan(), months: months }).then(function (d) {
          if (!d.orderId) { throw new Error(d.error || 'Could not start the payment.'); }
          return d.orderId;
        });
      },
      onApprove: function (data, actions) {
        show('Confirming your payment...', true);
        return post('<@ofbizUrl>paypalCaptureOrder</@ofbizUrl>', { orderId: data.orderID }).then(function (d) {
          if (d.restart) { show(d.error); return actions.restart(); }
          if (d.ok) { window.location.href = '<@ofbizUrl>Billing?paid=Y</@ofbizUrl>'; return; }
          if (d.pending) { show(d.message, true); return; }
          show(d.error || 'The payment could not be completed.');
        });
      },
      onCancel: function () { show('Payment cancelled. You have not been charged.'); },
      onError: function (err) { show((err && err.message) ? err.message : 'The payment could not be completed.'); }
    });
  }
  s.onload = function () {
    rerender = function () { msg.hidden = true; renderButtons(); };
    renderButtons();
  };
  document.head.appendChild(s);
})();
</script>
