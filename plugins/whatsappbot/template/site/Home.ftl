<#assign pageTitle = "WhatsApp automation for growing businesses">
<#include "component://whatsappbot/template/site/SiteHead.ftl"/>
<#macro ico name><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><#switch name>
<#case "bot"><rect x="4" y="8" width="16" height="12" rx="3"/><path d="M12 4v4M9 13h.01M15 13h.01M9 17h6"/><#break>
<#case "inbox"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><path d="M8 9h8M8 13h5"/><#break>
<#case "broadcast"><path d="M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1z"/><path d="M16 8.5a5 5 0 0 1 0 7M19 5.5a9 9 0 0 1 0 13"/><#break>
<#case "api"><path d="M8 9l-4 3 4 3M16 9l4 3-4 3M13.5 6l-3 12"/><#break>
<#case "chart"><path d="M3 3v18h18"/><path d="M7 15l4-4 3 3 5-6"/><#break>
<#case "shield"><path d="M12 3l8 3v6c0 4.5-3.4 8.4-8 9-4.6-.6-8-4.5-8-9V6z"/><path d="M9 12l2 2 4-4"/><#break>
<#case "team"><circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 7M18 14.5a6.5 6.5 0 0 1 3.5 5.5"/><#break>
<#case "clock"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/><#break>
<#case "check"><path d="M5 12.5l4.5 4.5L19 7.5"/><#break>
</#switch></svg></#macro>

<section class="h-hero">
  <div class="s-wrap h-hero-in">
    <span class="h-chip">Built on the official WhatsApp Business Platform</span>
    <h1>WhatsApp automation for<br/><span>growing businesses</span></h1>
    <p class="h-lead">Answer customers instantly with no-code chatbots, work every conversation from one shared team inbox, and send updates your customers actually read.</p>
    <div class="h-cta">
      <a class="s-btn s-btn-primary s-btn-lg" href="<@ofbizUrl>signup</@ofbizUrl>">Start ${trialDays}-day free trial</a>
      <a class="s-btn h-btn-outline s-btn-lg" href="#product">See the product</a>
    </div>
    <ul class="h-points"><li><@ico "check"/>No credit card required</li><li><@ico "check"/>Live in under 30 minutes</li><li><@ico "check"/>Cancel anytime</li></ul>
  </div>
  <div class="s-wrap h-shot-wrap">
    <div class="h-browser">
      <div class="h-browser-bar"><i></i><i></i><i></i><span>${brandDomain}</span></div>
      <img src="/theme/img/app-dashboard.jpg" alt="Dashboard showing conversations, message volume and delivery rate" width="1600" height="956"/>
    </div>
  </div>
</section>

<section class="h-strip">
  <div class="s-wrap h-strip-in">
    <div><strong>Official Cloud API</strong><span>Meta's WhatsApp Business Platform</span></div>
    <div><strong>No-code</strong><span>Menus, buttons, lists and questions</span></div>
    <div><strong>Human hand-off</strong><span>Your team takes over in one click</span></div>
    <div><strong>Open API</strong><span>Zoho, CRMs, ERPs and websites</span></div>
  </div>
</section>

<section class="s-section" id="product">
  <div class="s-wrap">
    <div class="h-row">
      <div class="h-row-copy">
        <span class="h-kicker">Team inbox</span>
        <h2>Every customer chat in one place</h2>
        <p>Your bot handles common questions around the clock. When a customer needs a person, the chat moves to your team's shared inbox with the full history and everything the bot already collected.</p>
        <ul class="h-list"><li><@ico "check"/>Unread and "needs agent" views so nothing is missed</li><li><@ico "check"/>Take over from the bot and hand back in one click</li><li><@ico "check"/>Sent, delivered and read ticks on every message</li></ul>
      </div>
      <div class="h-row-media"><img src="/theme/img/app-inbox.jpg" alt="Shared team inbox with conversation list and chat" loading="lazy" width="1600" height="956"/></div>
    </div>

    <div class="h-row h-row-rev">
      <div class="h-row-copy">
        <span class="h-kicker">Bot builder</span>
        <h2>Build chatbots without writing code</h2>
        <p>Create menus with reply buttons and lists, ask questions and save the answers, and route customers to the right step. Start from a ready-made template and change the words to match your business.</p>
        <ul class="h-list"><li><@ico "check"/>Keyword triggers like "hi", "price" or "track"</li><li><@ico "check"/>Personalise replies with the customer's name and answers</li><li><@ico "check"/>Validate answers such as dates, pincodes or order numbers</li></ul>
      </div>
      <div class="h-row-media"><img src="/theme/img/app-flow.jpg" alt="Bot flow editor showing steps and options" loading="lazy" width="1600" height="956"/></div>
    </div>
  </div>
</section>

<section class="s-section" id="products">
  <div class="s-wrap">
    <div class="h-center"><span class="h-kicker">${companyName} products</span><h2 class="s-h2">Software for businesses that run on WhatsApp and Zoho</h2><p class="h-sub"><a href="${productsSiteUrl}">See all products on ${productsSiteUrl?replace("https://","")} &rarr;</a></p></div>
    <div class="h-products">
      <div class="h-product h-product-main">
        <img src="/theme/flochat-logo-dark.svg" alt="FloChat" height="34"/>
        <p><b>WhatsApp automation.</b> AI agent and no-code chatbots, a shared team inbox, broadcasts with read and reply reports, and an open API.</p>
        <a class="s-btn s-btn-primary" href="<@ofbizUrl>signup</@ofbizUrl>">Start ${trialDays}-day free trial</a>
      </div>
      <div class="h-product">
        <div class="h-product-name">Flow<span>Linker</span></div>
        <p>${flowlinkerTagline}</p>
        <a class="s-btn s-btn-ghost" href="${flowlinkerUrl}">Open FlowLinker &rarr;</a>
      </div>
      <div class="h-product">
        <div class="h-product-name">Bill<span>Ease</span></div>
        <p>${billeaseTagline}</p>
        <a class="s-btn s-btn-ghost" href="${billeaseUrl}">Open BillEase &rarr;</a>
      </div>
    </div>
  </div>
</section>

<section class="s-section s-alt" id="features">
  <div class="s-wrap">
    <div class="h-center"><span class="h-kicker">Everything included</span><h2 class="s-h2">One platform for sales and support on WhatsApp</h2></div>
    <div class="h-grid">
      <div class="h-card"><div class="h-ico"><@ico "bot"/></div><h3>AI agent</h3><p>Answers from your price list, FAQ and website, and hands over to your team when unsure.</p></div>
      <div class="h-card"><div class="h-ico"><@ico "bot"/></div><h3>Chatbot flows</h3><p>Automate FAQs, catalogues, bookings and lead capture. Or let AI build the bot for you.</p></div>
      <div class="h-card"><div class="h-ico"><@ico "inbox"/></div><h3>Shared inbox</h3><p>Your whole team answers from one screen, with clear ownership.</p></div>
      <div class="h-card"><div class="h-ico"><@ico "broadcast"/></div><h3>Broadcasts</h3><p>Send offers to tagged customers now or later, and see who read and replied.</p></div>
      <div class="h-card"><div class="h-ico"><@ico "api"/></div><h3>Developer API</h3><p>REST API, signed webhooks and a developer portal with ready-made code for Zoho, Node, PHP and Python.</p></div>
      <div class="h-card"><div class="h-ico"><@ico "chart"/></div><h3>Analytics</h3><p>Track volume, delivery and read rates, and chats waiting for a reply.</p></div>
      <div class="h-card"><div class="h-ico"><@ico "team"/></div><h3>Team roles</h3><p>Owners manage settings and billing. Agents focus on customers.</p></div>
      <div class="h-card"><div class="h-ico"><@ico "shield"/></div><h3>Secure</h3><p>Signed webhooks, encrypted tokens and fully separate workspaces.</p></div>
    </div>
  </div>
</section>

<section class="s-section" id="api">
  <div class="s-wrap"><div class="h-row">
    <div class="h-row-copy">
      <span class="h-kicker">Integrations</span>
      <h2>Connect WhatsApp to the tools you already use</h2>
      <p>Send order confirmations from Zoho CRM, payment reminders from your ERP, or OTPs from your website with a single API call. Read conversations and captured customer details back into your systems.</p>
      <ul class="h-list"><li><@ico "check"/>Simple API keys per workspace</li><li><@ico "check"/>Works with Zoho Deluge, Zoho Flow, Postman or any language</li></ul>
    </div>
    <div class="h-row-media">
<pre class="h-code"><span class="c">// Send an order update from Zoho Deluge</span>
resp = <span class="k">invokeurl</span>
[
  url: <span class="s">"https://${brandDomain}/api/v1/messages"</span>
  type: POST
  headers: {<span class="s">"X-Api-Key"</span>: <span class="s">"wab_..."</span>}
  parameters: {<span class="s">"to"</span>: <span class="s">"919826012345"</span>,
    <span class="s">"template"</span>: {<span class="s">"name"</span>: <span class="s">"order_update"</span>,
                 <span class="s">"params"</span>: [<span class="s">"#A1042"</span>, <span class="s">"Shipped"</span>]}}
      .toString()
];</pre>
    </div>
  </div></div>
</section>

<section class="s-section s-alt" id="how">
  <div class="s-wrap">
    <div class="h-center"><span class="h-kicker">Get started</span><h2 class="s-h2">Live in three steps</h2></div>
    <div class="s-steps">
      <div class="s-step"><span>1</span><h3>Create your workspace</h3><p>Sign up with your business email and start a ${trialDays}-day free trial.</p></div>
      <div class="s-step"><span>2</span><h3>Connect WhatsApp</h3><p>Link your WhatsApp Business number through Facebook in a guided flow.</p></div>
      <div class="s-step"><span>3</span><h3>Go live</h3><p>Start from the ready-made welcome bot, adjust the replies and share your number.</p></div>
    </div>
  </div>
</section>

<section class="s-section" id="pricing">
  <div class="s-wrap">
    <div class="h-center"><span class="h-kicker">Pricing</span><h2 class="s-h2">Simple plans that grow with you</h2>
    <p class="s-sub">Every plan starts with a ${trialDays}-day free trial. Pay monthly, or yearly and get ${yearlyFree} months free. Prices in USD, paid securely with PayPal or card. WhatsApp fees are billed separately by Meta.</p></div>
    <div class="s-plans">
      <#list plans as p>
        <div class="s-plan<#if p?index == 1> s-plan-featured</#if>">
          <#if p?index == 1><div class="s-badge">Most popular</div></#if>
          <h3>${p.planName!}</h3>
          <div class="s-price"><#if (p.currencyUomId!"USD") == "USD">$<#elseif (p.currencyUomId!"") == "INR">&#8377;<#else>${p.currencyUomId!} </#if>${(p.monthlyPrice!0)?string(",##0")}<small>/month</small></div>
          <ul>
            <li>${(p.maxChannels)?has_content?then(p.maxChannels + " WhatsApp number" + ((p.maxChannels!1) gt 1)?then("s",""), "Unlimited WhatsApp numbers")}</li>
            <li>${(p.maxFlows)?has_content?then(p.maxFlows + " bot flows", "Unlimited bot flows")}</li>
            <li>${(p.maxMessagesPerMonth)?has_content?then(p.maxMessagesPerMonth?string(",##0") + " messages / month", "Unlimited messages")}</li>
            <li>Shared team inbox &amp; broadcasts</li>
            <li>REST API access</li>
          </ul>
          <a class="s-btn <#if p?index == 1>s-btn-primary<#else>s-btn-ghost</#if> s-btn-block" href="<@ofbizUrl>signup?planId=${p.planId}</@ofbizUrl>">Start free trial</a>
        </div>
      </#list>
    </div>
  </div>
</section>

<section class="s-section s-alt" id="faq">
  <div class="s-wrap s-faq">
    <div class="h-center"><h2 class="s-h2">Frequently asked questions</h2></div>
    <details><summary>What do I need to get started?</summary><p>A phone number for WhatsApp Business and a Facebook Business account. We walk you through connecting both inside the app.</p></details>
    <details><summary>Can I keep using WhatsApp on my phone?</summary><p>A number connected to the WhatsApp Business Platform is managed from this app instead of the phone app. Many businesses use a dedicated number for this.</p></details>
    <details><summary>Are WhatsApp message charges included?</summary><p>No. Meta bills WhatsApp fees directly to your own WhatsApp Business Account. Bot replies within 24 hours of a customer's message are free; Meta charges only for template messages you start, such as marketing broadcasts, reminders and OTPs, at rates that depend on the customer's country (<a href="https://developers.facebook.com/docs/whatsapp/pricing" target="_blank" rel="noopener">see Meta's pricing</a>). Our plans cover the ${brandName!"FloChat"} platform.</p></details>
    <details><summary>How do I pay?</summary><p>After your trial, open Plan &amp; Billing in the app and pay with PayPal or any debit or credit card through PayPal. Choose monthly or yearly billing. Prices are in USD.</p></details>
    <details><summary>Can my team reply to customers?</summary><p>Yes. Invite team members as agents. The bot answers common questions and hands the chat to your team when needed.</p></details>
    <details><summary>Can I connect Zoho or my own software?</summary><p>Yes. Use the REST API with an API key from Zoho CRM, Zoho Flow, your website or any other system.</p></details>
  </div>
</section>

<section class="s-band">
  <div class="s-wrap s-band-in">
    <div><h2>Ready to automate WhatsApp?</h2><p>Start your free trial today. Setup takes minutes.</p></div>
    <a class="s-btn s-btn-white s-btn-lg" href="<@ofbizUrl>signup</@ofbizUrl>">Start ${trialDays}-day free trial</a>
  </div>
</section>

<#include "component://whatsappbot/template/site/SiteFoot.ftl"/>
