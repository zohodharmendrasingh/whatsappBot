<#-- Analytics dashboard -->
<link rel="stylesheet" href="/theme/crm.css?v=3"/>
<#if !analyticsTzKnown>
<script>(function(){try{var z=Intl.DateTimeFormat().resolvedOptions().timeZone;if(z){var u=new URL(location.href);u.searchParams.set('tz',z);location.replace(u.toString());}}catch(e){}})();</script>
</#if>
<#function pct x y><#return (y > 0)?then((x * 100 / y)?round, 0)></#function>
<div class="cx an">
  <div class="cx-head">
    <div><h2 class="cx-title">Analytics</h2><p class="cx-sub">Last ${a.days} days &middot; times in ${analyticsTz}<#if a.truncated> &middot; showing the most recent 300,000 messages</#if></p></div>
    <div class="cx-seg"><#list [7, 30, 90] as n><a class="<#if a.days == n>on</#if>" href="<@ofbizUrl>Analytics?days=${n}</@ofbizUrl>">${n} days</a></#list></div>
  </div>

  <div class="an-kpis">
    <div class="an-kpi"><span>Conversations</span><b>${a.conversations?string(",##0")}</b><small>${a.newContacts?string(",##0")} new contacts</small></div>
    <div class="an-kpi"><span>Messages received</span><b>${a.messagesIn?string(",##0")}</b><small>${a.messagesOut?string(",##0")} sent<#if (a.messagesFailed > 0)> &middot; ${a.messagesFailed} failed</#if></small></div>
    <div class="an-kpi"><span>First reply (all)</span><b>${a.firstReplyTxt}</b><small>median time to first answer</small></div>
    <div class="an-kpi"><span>First reply by team</span><b>${a.firstReplyAgentTxt}</b><small><#if a.agentReplyCount gt 0>median of ${a.agentReplyCount} &middot; avg ${a.firstReplyAgentAvgTxt}<#else>no team replies yet</#if></small></div>
    <div class="an-kpi"><span>Handled by bot only</span><b>${a.botOnlyPct}%</b><small>${a.botOnlyChats?string(",##0")} of ${a.conversations?string(",##0")} chats, no agent needed</small></div>
    <a class="an-kpi<#if (a.waitingChats > 0)> warn</#if>" href="<@ofbizUrl>Inbox?tab=all&amp;st=active</@ofbizUrl>"><span>Waiting for a reply</span><b>${a.waitingChats}</b><small>customer wrote last &rarr;</small></a>
  </div>

  <div class="an-grid">
    <div class="cx-card an-wide">
      <div class="cx-card-head"><h3>Messages per day</h3>
        <div class="an-legend"><span><i class="an-c-in"></i>Received</span><span><i class="an-c-out"></i>Replies (bot + team)</span><span><i class="an-c-bc"></i>Broadcasts</span></div></div>
      <#assign n = a.series?size w = 760 h = 200 left = 34 bw = (w - left) / n>
      <svg class="an-chart" viewBox="0 0 ${(w)?c} ${(h + 24)?c}" role="img" aria-label="Messages per day">
        <#list 0..4 as g><#assign gy = 8 + (h - 8) * g / 4><line x1="${(left)?c}" x2="${(w)?c}" y1="${(gy)?c}" y2="${(gy)?c}" class="an-grid-line"/>
          <text x="${(left - 6)?c}" y="${(gy + 4)?c}" text-anchor="end" class="an-axis">${((a.seriesMax * (4 - g)) / 4)?round}</text></#list>
        <#list a.series as p>
          <#assign x = left + p?index * bw gap = [bw * 0.12, 1]?max bar = (bw - gap * 3) / 2>
          <#assign hi = (p.rcv * (h - 8) / a.seriesMax) ho = (p.rep * (h - 8) / a.seriesMax) hb = (p.bc * (h - 8) / a.seriesMax)>
          <rect x="${(x + gap)?c}" y="${(h - hi)?c}" width="${(bar)?c}" height="${(hi)?c}" rx="2" class="an-in"><title>${p.label}: ${p.rcv} received</title></rect>
          <rect x="${(x + gap * 2 + bar)?c}" y="${(h - ho)?c}" width="${(bar)?c}" height="${(ho)?c}" rx="2" class="an-out"><title>${p.label}: ${p.rep} replies</title></rect>
          <rect x="${(x + gap * 2 + bar)?c}" y="${(h - ho - hb)?c}" width="${(bar)?c}" height="${(hb)?c}" rx="2" class="an-bc"><title>${p.label}: ${p.bc} broadcast messages</title></rect>
          <#if n <= 10 || p?index % ((n / 8)?ceiling) == 0><text x="${(x + bw / 2)?c}" y="${(h + 18)?c}" text-anchor="middle" class="an-axis">${p.label}</text></#if>
        </#list>
      </svg>
    </div>

    <div class="cx-card">
      <div class="cx-card-head"><h3>Who sends the replies</h3></div>
      <#assign ob = a.outBy tot = [a.messagesOut, 1]?max>
      <div class="an-stack">
        <#list [["BOT","Bot","an-s-bot"],["AGENT","Team","an-s-agent"],["BROADCAST","Broadcasts","an-s-bc"],["API","API","an-s-api"]] as k>
          <#if (ob[k[0]] > 0)><span class="${k[2]}" style="width:${pct(ob[k[0]], tot)}%" title="${k[1]}: ${ob[k[0]]}"></span></#if>
        </#list>
      </div>
      <ul class="an-list">
        <#list [["BOT","Bot","an-s-bot"],["AGENT","Team","an-s-agent"],["BROADCAST","Broadcasts","an-s-bc"],["API","API / integrations","an-s-api"]] as k>
          <li><i class="${k[2]}"></i>${k[1]}<b>${ob[k[0]]?string(",##0")}</b><small>${pct(ob[k[0]], tot)}%</small></li>
        </#list>
      </ul>
    </div>

    <div class="cx-card">
      <div class="cx-card-head"><h3>When customers write</h3><small class="cx-muted">messages by hour</small></div>
      <div class="an-hours">
        <#list a.hours as v><span style="height:${[(v * 100 / a.hoursMax)?round, 2]?max}%" title="${v?index}:00 – ${v?index}:59 · ${v} messages"></span></#list>
      </div>
      <div class="an-hours-axis"><span>0</span><span>6</span><span>12</span><span>18</span><span>23</span></div>
    </div>

    <div class="cx-card an-wide">
      <div class="cx-card-head"><h3>Bot flows</h3><a class="cx-link" href="<@ofbizUrl>FindFlow</@ofbizUrl>">Open flows &rarr;</a></div>
      <#if a.flows?has_content>
      <table class="cx-table">
        <thead><tr><th>Flow</th><th class="num">Started</th><th class="num">People</th><th>Finished / handed to team / left</th></tr></thead>
        <tbody>
        <#list a.flows as f>
          <tr><td><#if f.flowId??><a href="<@ofbizUrl>FlowBuilder?flowId=${f.flowId}</@ofbizUrl>"><b>${f.name}</b></a><#else><span class="cx-muted">${f.name}</span></#if></td>
            <td class="num">${f.runs?string(",##0")}</td><td class="num">${f.people?string(",##0")}</td>
            <td><div class="an-bar3" title="${f.completed} finished · ${f.handoff} handed to team · ${f.dropped} left without finishing">
              <span class="an-s-bot" style="width:${pct(f.completed, f.runs)}%"></span><span class="an-s-agent" style="width:${pct(f.handoff, f.runs)}%"></span><span class="an-s-drop" style="width:${pct(f.dropped, f.runs)}%"></span></div>
              <small class="cx-muted">${f.completedPct}% finished &middot; ${f.handoffPct}% to team<#if (f.dropped > 0)> &middot; ${pct(f.dropped, f.runs)}% left</#if></small></td></tr>
        </#list>
        </tbody>
      </table>
      <#else><div class="cx-empty"><p>No flows ran in this period.</p></div></#if>
    </div>

    <div class="cx-card">
      <div class="cx-card-head"><h3>Team</h3></div>
      <#if a.agents?has_content>
      <table class="cx-table">
        <thead><tr><th>Member</th><th class="num">Chats</th><th class="num">Messages</th><th class="num">First reply</th></tr></thead>
        <tbody><#list a.agents as g><tr><td>${g.user}</td><td class="num">${g.chats}</td><td class="num">${g.messages}</td><td class="num">${g.firstReplyTxt}</td></tr></#list></tbody>
      </table>
      <#else><div class="cx-empty"><p>No team replies in this period. The bot handled everything.</p></div></#if>
    </div>

    <div class="cx-card">
      <div class="cx-card-head"><h3>Broadcasts</h3><a class="cx-link" href="<@ofbizUrl>Broadcast</@ofbizUrl>">All broadcasts &rarr;</a></div>
      <#assign b = a.broadcasts>
      <#if (b.campaigns > 0)>
      <ul class="an-list">
        <li>Broadcasts<b>${b.campaigns}</b></li>
        <li>Messages sent<b>${(b.sent!0)?string(",##0")}</b></li>
        <li>Delivered<b>${(b.delivered!0)?string(",##0")}</b><small>${pct(b.delivered!0, b.sent!0)}%</small></li>
        <li>Read<b>${(b.read!0)?string(",##0")}</b><small>${b.readPct}%</small></li>
        <li>Replied<b>${(b.replied!0)?string(",##0")}</b><small>${b.repliedPct}%</small></li>
        <#if ((b.failed!0) > 0)><li class="cx-danger">Failed<b>${b.failed}</b></li></#if>
      </ul>
      <#else><div class="cx-empty"><p>No broadcasts in this period.</p></div></#if>
    </div>
  </div>
</div>
