<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>${pageTitle!"WhatsApp automation platform"} | FloChat</title>
<meta name="description" content="Automate customer conversations on WhatsApp with no-code chatbots, a shared team inbox, broadcasts and APIs. FloChat."/>
<link rel="icon" href="/theme/favicon.svg" type="image/svg+xml"/>
<link rel="icon" href="/theme/favicon.png" type="image/png"/>
<link rel="apple-touch-icon" href="/theme/apple-touch-icon.png"/>
<link rel="preconnect" href="https://fonts.googleapis.com"/>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&amp;display=swap"/>
<link rel="stylesheet" href="/theme/site.css"/>
</head>
<body>
<header class="s-nav">
  <div class="s-wrap s-nav-in">
    <a href="<@ofbizUrl>home</@ofbizUrl>" class="s-logo"><img src="/theme/flochat-logo-dark.svg" alt="FloChat" height="36"/></a>
    <nav class="s-links">
      <a href="<@ofbizUrl>home</@ofbizUrl>#features">Features</a>
      <a href="<@ofbizUrl>home</@ofbizUrl>#how">How it works</a>
      <a href="<@ofbizUrl>home</@ofbizUrl>#pricing">Pricing</a>
      <a href="<@ofbizUrl>home</@ofbizUrl>#faq">FAQ</a>
    </nav>
    <div class="s-cta">
      <#if isLoggedIn!false>
        <a class="s-btn s-btn-primary" href="<@ofbizUrl>main</@ofbizUrl>">Open dashboard</a>
      <#else>
        <a class="s-link" href="<@ofbizUrl>main</@ofbizUrl>">Sign in</a>
        <#if signupEnabled!true><a class="s-btn s-btn-primary" href="<@ofbizUrl>signup</@ofbizUrl>">Start free trial</a></#if>
      </#if>
    </div>
  </div>
</header>
