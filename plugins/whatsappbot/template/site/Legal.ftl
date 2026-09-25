<#-- Public legal pages: privacy policy, terms of service, data deletion. legalDoc = privacy | terms | deletion -->
<#assign b = brandName!"FloChat">
<#assign co = legalName!"Msoft Dynamic Technologies (OPC) Private Limited">
<#assign mail = supportEmail!"info@msoftdynamic.com">
<#assign site = "https://" + (brandDomain!"flochat.flolink.ai")>
<#if legalDoc == "terms"><#assign pageTitle = "Terms of Service"><#elseif legalDoc == "deletion"><#assign pageTitle = "Data Deletion"><#else><#assign pageTitle = "Privacy Policy"></#if>
<#include "component://whatsappbot/template/site/SiteHead.ftl"/>
<section class="s-legal">
  <div class="s-wrap s-legal-in">
    <nav class="s-legal-nav">
      <a href="<@ofbizUrl>privacy</@ofbizUrl>"<#if legalDoc == "privacy"> class="on"</#if>>Privacy Policy</a>
      <a href="<@ofbizUrl>terms</@ofbizUrl>"<#if legalDoc == "terms"> class="on"</#if>>Terms of Service</a>
      <a href="<@ofbizUrl>data-deletion</@ofbizUrl>"<#if legalDoc == "deletion"> class="on"</#if>>Data Deletion</a>
    </nav>
    <article class="s-legal-body">
<#if legalDoc == "privacy">
      <h1>Privacy Policy</h1>
      <p class="s-legal-date">Effective ${legalEffectiveDate!"24 September 2026"}</p>
      <p>${b} (${site}) is a WhatsApp automation platform operated by ${co} ("we", "us"), ${legalAddress!"Gwalior, Madhya Pradesh, India"}. This policy explains what data we collect when businesses ("customers") use ${b}, and when their end customers message those businesses on WhatsApp.</p>

      <h2>1. Data we collect</h2>
      <ul>
        <li><strong>Account data:</strong> business name, your name, email address, phone number and password (stored hashed) when you sign up, and the details of team members you invite.</li>
        <li><strong>WhatsApp Business data:</strong> when you connect a number through Meta, we receive your WhatsApp Business Account ID, phone number ID, display number and an access token issued by Meta. Access tokens are stored encrypted.</li>
        <li><strong>Conversation data:</strong> messages exchanged between your business and your end customers on WhatsApp (text, media links, button and list replies), the sender's WhatsApp number and profile name, delivery and read statuses, and any answers your bot collects (for example a name or appointment date).</li>
        <li><strong>Billing data:</strong> the plan you buy, amounts, dates and PayPal transaction references. Card and PayPal account details are handled by PayPal and never reach our servers.</li>
        <li><strong>Technical data:</strong> IP address, browser type and log entries needed to secure and operate the service.</li>
      </ul>

      <h2>2. How we use data</h2>
      <ul>
        <li>To provide the service: run your chatbot flows, show conversations in your inbox, send the messages you or your bot choose to send, broadcasts and API calls.</li>
        <li>To manage your account, subscription and payments, and to contact you about the service.</li>
        <li>To keep the platform secure, prevent abuse and meet legal obligations.</li>
      </ul>
      <p>We do not sell personal data and we do not use your conversations for advertising.</p>

      <h2>3. Data from Meta</h2>
      <p>${b} uses the WhatsApp Business Platform (Cloud API) and Facebook Login for Business provided by Meta Platforms, Inc. Data received through these products is used only to provide the ${b} features you request, in line with Meta's Platform Terms and WhatsApp Business policies. Messages are transmitted through Meta's infrastructure; Meta's own processing is governed by Meta's privacy policy.</p>

      <h2>4. Roles</h2>
      <p>For conversation data, the business using ${b} decides what is collected from its end customers and is the data controller; we process it on the business's behalf. End customers who want to access or delete their messages should contact the business they messaged, or write to us and we will pass the request on.</p>

      <h2>5. Sharing</h2>
      <p>We share data only with service providers needed to run ${b}: Meta (WhatsApp messaging), PayPal (payments), and our hosting and email providers, each bound by confidentiality and security obligations. We may disclose data if required by law.</p>

      <h2>6. Retention</h2>
      <p>We keep account and conversation data while your account is active. Raw webhook logs are deleted automatically after a short period. When an account is closed we delete or anonymise its data within 30 days, except records we must keep for tax and accounting.</p>

      <h2>7. Security</h2>
      <p>Data is sent over HTTPS, access tokens are encrypted at rest, passwords and API keys are stored hashed, and each business can only access its own workspace.</p>

      <h2>8. Your rights</h2>
      <p>You can access, correct, export or delete your data, or withdraw consent, by writing to <a href="mailto:${mail}">${mail}</a>. See our <a href="<@ofbizUrl>data-deletion</@ofbizUrl>">data deletion instructions</a>. We reply within 30 days.</p>

      <h2>9. Changes and contact</h2>
      <p>We may update this policy and will post the new version here with a new effective date. Questions: <a href="mailto:${mail}">${mail}</a>, ${co}, ${legalAddress!"Gwalior, Madhya Pradesh, India"}.</p>
<#elseif legalDoc == "terms">
      <h1>Terms of Service</h1>
      <p class="s-legal-date">Effective ${legalEffectiveDate!"24 September 2026"}</p>
      <p>These terms govern your use of ${b} (${site}), provided by ${co} ("we", "us"). By creating an account you agree to them on behalf of your business.</p>

      <h2>1. The service</h2>
      <p>${b} lets businesses build WhatsApp chatbots, manage conversations in a shared inbox, send template broadcasts and connect other systems through an API. ${b} works on top of the WhatsApp Business Platform provided by Meta.</p>

      <h2>2. Your account</h2>
      <ul>
        <li>You must provide accurate information and keep your login details safe. You are responsible for activity under your account and your team members' accounts.</li>
        <li>You must be authorised to act for the business you register and for any WhatsApp number you connect.</li>
      </ul>

      <h2>3. WhatsApp and Meta rules</h2>
      <ul>
        <li>You must follow the WhatsApp Business Terms of Service, WhatsApp Business Messaging Policy and WhatsApp Commerce Policy.</li>
        <li>You must have your end customers' opt-in before messaging them and honour opt-out requests (for example STOP).</li>
        <li>Meta charges for conversations directly to your WhatsApp Business Account; these charges are not included in ${b} plans.</li>
        <li>Meta may restrict or ban numbers that break its policies. We are not responsible for Meta's decisions.</li>
      </ul>

      <h2>4. Acceptable use</h2>
      <p>You may not use ${b} to send spam, unlawful, misleading or harmful content, to collect sensitive data without a lawful basis, or to attempt to access other customers' data or disrupt the service.</p>

      <h2>5. Plans, trial and payment</h2>
      <ul>
        <li>New workspaces get a free trial. After the trial a paid plan is needed to keep sending messages.</li>
        <li>Plans are priced in USD and paid in advance through PayPal for one month or one year. Plans do not renew automatically; you renew from Plan &amp; Billing.</li>
        <li>Payments are non-refundable except where required by law or at our discretion for billing errors.</li>
        <li>Plan limits (numbers, flows, messages) apply as shown on the pricing page. We may change prices with notice; changes apply from your next payment.</li>
      </ul>

      <h2>6. Your data</h2>
      <p>You own your content and conversation data. You give us permission to process it only to provide the service, as described in our <a href="<@ofbizUrl>privacy</@ofbizUrl>">Privacy Policy</a>. You are responsible for having the right to collect and process your end customers' data.</p>

      <h2>7. Availability and changes</h2>
      <p>We work to keep ${b} available but do not guarantee uninterrupted service. Features that depend on Meta or PayPal may change when those providers change their products. We may improve or change features over time.</p>

      <h2>8. Suspension and termination</h2>
      <p>You can stop using ${b} at any time. We may suspend or close accounts that break these terms or Meta's policies, or that are unpaid. After closure, data is deleted as described in the Privacy Policy.</p>

      <h2>9. Liability</h2>
      <p>The service is provided "as is". To the extent permitted by law, we are not liable for indirect or consequential losses, and our total liability is limited to the amount you paid us in the 12 months before the claim.</p>

      <h2>10. Law and contact</h2>
      <p>These terms are governed by the laws of India, with courts at Gwalior, Madhya Pradesh having jurisdiction. Contact: <a href="mailto:${mail}">${mail}</a>.</p>
<#else>
      <h1>Data Deletion</h1>
      <p class="s-legal-date">How to delete your data from ${b}</p>

      <h2>Businesses using ${b}</h2>
      <ol>
        <li>Email <a href="mailto:${mail}?subject=Data%20deletion%20request">${mail}</a> from the email address of your ${b} account with the subject "Data deletion request".</li>
        <li>Include your workspace (business) name. If you connected WhatsApp through Facebook, you can also remove ${b} under Facebook Settings &rarr; Business Integrations.</li>
        <li>We confirm the request and delete your workspace, users, WhatsApp connection details, bot flows, contacts and messages within 30 days, and send you a confirmation. Payment records we must keep for tax law are retained for the legally required period.</li>
      </ol>

      <h2>People who messaged a business on WhatsApp</h2>
      <p>Your messages are stored on behalf of the business you messaged. Ask that business to delete them, or email <a href="mailto:${mail}?subject=Data%20deletion%20request">${mail}</a> with your WhatsApp number and the business name, and we will delete them or pass the request to the business within 30 days.</p>

      <h2>Removing the Facebook connection</h2>
      <p>Go to Facebook &rarr; Settings &amp; privacy &rarr; Settings &rarr; Business Integrations, select ${b} and choose Remove. This revokes ${b}'s access to your WhatsApp Business Account. To also delete data already stored in ${b}, send the email above.</p>
</#if>
    </article>
  </div>
</section>
<#include "component://whatsappbot/template/site/SiteFoot.ftl"/>
