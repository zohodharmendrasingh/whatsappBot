package com.msoftdynamic.whatsapp;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityListIterator;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Workspace analytics for a date range: messages per day, first reply times (bot and team),
 * who answers (bot / team / broadcasts / API), flow usage and broadcast results.
 */
public final class WaAnalytics {
    private static final int MAX_MESSAGES = 300_000;

    private WaAnalytics() { }

    /** Who sent an outbound message: BOT, AGENT, BROADCAST, API or SYSTEM. */
    public static String senderType(String sentBy) {
        if (sentBy == null || sentBy.isEmpty() || "SYSTEM".equals(sentBy)) {
            return "SYSTEM";
        }
        if ("BOT".equals(sentBy)) {
            return "BOT";
        }
        if (sentBy.startsWith("BROADCAST")) {
            return "BROADCAST";
        }
        if (sentBy.startsWith("API")) {
            return "API";
        }
        return "AGENT";
    }

    public static Map<String, Object> compute(Delegator delegator, String tenantId, int days, TimeZone tz) throws GenericEntityException {
        ZoneId zone = (tz == null ? TimeZone.getDefault() : tz).toZoneId();
        LocalDate today = LocalDate.now(zone);
        LocalDate firstDay = today.minusDays(Math.max(1, days) - 1L);
        Timestamp from = Timestamp.from(firstDay.atStartOfDay(zone).toInstant());

        // per-day buckets
        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        for (LocalDate d = firstDay; !d.isAfter(today); d = d.plusDays(1)) {
            perDay.put(d, new long[3]); // in, out (replies), broadcasts
        }
        long[] hours = new long[24];
        Map<String, Long> outBy = new LinkedHashMap<>();
        for (String k : List.of("BOT", "AGENT", "BROADCAST", "API")) {
            outBy.put(k, 0L);
        }
        long in = 0;
        long out = 0;
        long failed = 0;
        Set<String> chatting = new HashSet<>();
        Set<String> agentTouched = new HashSet<>();
        List<Long> firstAll = new ArrayList<>();
        List<Long> firstAgent = new ArrayList<>();
        long waiting = 0;
        Map<String, long[]> agents = new LinkedHashMap<>(); // messages, chats(set size later), replyCount, replySum
        Map<String, Set<String>> agentChats = new HashMap<>();
        Map<String, List<Long>> agentReplies = new HashMap<>();
        boolean truncated = false;

        String curContact = null;
        long waitingSince = -1;
        int n = 0;
        try (EntityListIterator it = EntityQuery.use(delegator)
                .select("contactId", "direction", "sentBy", "createdDate", "deliveryStatus")
                .from("WaMessage")
                .where(EntityCondition.makeCondition("tenantId", tenantId),
                        EntityCondition.makeCondition("createdDate", EntityOperator.GREATER_THAN_EQUAL_TO, from))
                .orderBy("contactId", "createdDate").queryIterator()) {
            GenericValue m;
            while ((m = it.next()) != null) {
                if (++n > MAX_MESSAGES) {
                    truncated = true;
                    break;
                }
                String cid = m.getString("contactId");
                if (!cid.equals(curContact)) {
                    if (waitingSince > 0) {
                        waiting++;
                    }
                    curContact = cid;
                    waitingSince = -1;
                }
                Timestamp at = m.getTimestamp("createdDate");
                ZonedDateTime z = Instant.ofEpochMilli(at.getTime()).atZone(zone);
                long[] day = perDay.get(z.toLocalDate());
                if ("IN".equals(m.getString("direction"))) {
                    in++;
                    chatting.add(cid);
                    hours[z.getHour()]++;
                    if (day != null) {
                        day[0]++;
                    }
                    if (waitingSince < 0) {
                        waitingSince = at.getTime();
                    }
                    continue;
                }
                String type = senderType(m.getString("sentBy"));
                if ("failed".equals(m.getString("deliveryStatus"))) {
                    failed++;
                    continue; // never reached the customer
                }
                if ("SYSTEM".equals(type)) {
                    continue;
                }
                out++;
                outBy.merge(type, 1L, Long::sum);
                if (day != null) {
                    day["BROADCAST".equals(type) ? 2 : 1]++;
                }
                if ("AGENT".equals(type)) {
                    String who = m.getString("sentBy");
                    agentTouched.add(cid);
                    agents.computeIfAbsent(who, k -> new long[1])[0]++;
                    agentChats.computeIfAbsent(who, k -> new HashSet<>()).add(cid);
                }
                if (waitingSince > 0 && !"BROADCAST".equals(type)) {
                    long secs = Math.max(0, (at.getTime() - waitingSince) / 1000);
                    firstAll.add(secs);
                    if ("AGENT".equals(type)) {
                        firstAgent.add(secs);
                        agentReplies.computeIfAbsent(m.getString("sentBy"), k -> new ArrayList<>()).add(secs);
                    }
                    waitingSince = -1;
                }
            }
        }
        if (waitingSince > 0) {
            waiting++;
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("days", days);
        r.put("fromDate", from);
        r.put("truncated", truncated);
        r.put("messagesIn", in);
        r.put("messagesOut", out);
        r.put("messagesFailed", failed);
        r.put("conversations", (long) chatting.size());
        r.put("outBy", outBy);
        long botOnly = chatting.stream().filter(c -> !agentTouched.contains(c)).count();
        r.put("botOnlyChats", botOnly);
        r.put("botOnlyPct", chatting.isEmpty() ? 0 : Math.round(100.0 * botOnly / chatting.size()));
        r.put("waitingChats", waiting);
        r.put("firstReplyMedian", median(firstAll));
        r.put("firstReplyAgentMedian", median(firstAgent));
        r.put("firstReplyAgentAvg", avg(firstAgent));
        r.put("agentReplyCount", (long) firstAgent.size());
        r.put("firstReplyTxt", duration(median(firstAll)));
        r.put("firstReplyAgentTxt", duration(median(firstAgent)));
        r.put("firstReplyAgentAvgTxt", duration(avg(firstAgent)));
        r.put("newContacts", EntityQuery.use(delegator).from("WaContact").where(EntityCondition.makeCondition("tenantId", tenantId),
                EntityCondition.makeCondition("createdDate", EntityOperator.GREATER_THAN_EQUAL_TO, from)).queryCount());

        DateTimeFormatter lbl = DateTimeFormatter.ofPattern(days > 31 ? "d MMM" : "d MMM", Locale.ENGLISH);
        List<Map<String, Object>> series = new ArrayList<>();
        long max = 1;
        for (Map.Entry<LocalDate, long[]> e : perDay.entrySet()) {
            long[] v = e.getValue();
            max = Math.max(max, Math.max(v[0], v[1] + v[2]));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("label", e.getKey().format(lbl));
            d.put("weekday", e.getKey().getDayOfWeek().getValue());
            d.put("rcv", v[0]);
            d.put("rep", v[1]);
            d.put("bc", v[2]);
            series.add(d);
        }
        r.put("series", series);
        r.put("seriesMax", max);
        List<Long> hourList = new ArrayList<>();
        long hourMax = 1;
        for (long h : hours) {
            hourList.add(h);
            hourMax = Math.max(hourMax, h);
        }
        r.put("hours", hourList);
        r.put("hoursMax", hourMax);

        List<Map<String, Object>> agentRows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agents.entrySet()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("user", e.getKey());
            a.put("messages", e.getValue()[0]);
            a.put("chats", (long) agentChats.getOrDefault(e.getKey(), Set.of()).size());
            List<Long> rep = agentReplies.getOrDefault(e.getKey(), List.of());
            a.put("replies", (long) rep.size());
            a.put("firstReplyTxt", duration(median(rep)));
            agentRows.add(a);
        }
        agentRows.sort((a, b) -> Long.compare((Long) b.get("messages"), (Long) a.get("messages")));
        r.put("agents", agentRows);

        r.put("flows", flows(delegator, tenantId, from));
        r.put("broadcasts", broadcasts(delegator, tenantId, from));
        return r;
    }

    private static List<Map<String, Object>> flows(Delegator delegator, String tenantId, Timestamp from) throws GenericEntityException {
        Map<String, long[]> by = new LinkedHashMap<>(); // runs, completed, handoff, dropped
        Map<String, Set<String>> people = new HashMap<>();
        long stale = System.currentTimeMillis() - 24L * 3600 * 1000;
        try (EntityListIterator it = EntityQuery.use(delegator).select("flowId", "contactId", "outcome", "startedDate").from("WaFlowRun")
                .where(EntityCondition.makeCondition("tenantId", tenantId),
                        EntityCondition.makeCondition("startedDate", EntityOperator.GREATER_THAN_EQUAL_TO, from)).queryIterator()) {
            GenericValue run;
            while ((run = it.next()) != null) {
                long[] v = by.computeIfAbsent(run.getString("flowId"), k -> new long[4]);
                v[0]++;
                String o = run.getString("outcome");
                if ("COMPLETED".equals(o)) {
                    v[1]++;
                } else if ("HANDOFF".equals(o)) {
                    v[2]++;
                } else if (run.getTimestamp("startedDate").getTime() < stale) {
                    v[3]++;
                }
                people.computeIfAbsent(run.getString("flowId"), k -> new HashSet<>()).add(run.getString("contactId"));
            }
        }
        Map<String, String> names = new HashMap<>();
        for (GenericValue f : EntityQuery.use(delegator).select("flowId", "flowName").from("WaFlow").where("tenantId", tenantId).queryList()) {
            names.put(f.getString("flowId"), f.getString("flowName"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : by.entrySet()) {
            long[] v = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("flowId", e.getKey());
            row.put("name", names.getOrDefault(e.getKey(), "Deleted flow"));
            row.put("runs", v[0]);
            row.put("people", (long) people.get(e.getKey()).size());
            row.put("completed", v[1]);
            row.put("handoff", v[2]);
            row.put("dropped", v[3]);
            row.put("completedPct", v[0] == 0 ? 0 : Math.round(100.0 * v[1] / v[0]));
            row.put("handoffPct", v[0] == 0 ? 0 : Math.round(100.0 * v[2] / v[0]));
            rows.add(row);
        }
        // flows that were deleted since: one combined row
        Map<String, Object> gone = null;
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (names.containsKey(row.get("flowId"))) {
                kept.add(row);
                continue;
            }
            if (gone == null) {
                gone = new LinkedHashMap<>(row);
                gone.put("name", "Deleted flows");
                gone.put("flowId", null);
            } else {
                for (String k : List.of("runs", "people", "completed", "handoff", "dropped")) {
                    gone.put(k, (Long) gone.get(k) + (Long) row.get(k));
                }
            }
        }
        if (gone != null) {
            long runs = (Long) gone.get("runs");
            gone.put("completedPct", Math.round(100.0 * (Long) gone.get("completed") / runs));
            gone.put("handoffPct", Math.round(100.0 * (Long) gone.get("handoff") / runs));
            kept.add(gone);
        }
        kept.sort((a, b) -> a.get("flowId") == null ? 1 : b.get("flowId") == null ? -1 : Long.compare((Long) b.get("runs"), (Long) a.get("runs")));
        return kept;
    }

    private static Map<String, Object> broadcasts(Delegator delegator, String tenantId, Timestamp from) throws GenericEntityException {
        long campaigns = 0;
        Map<String, Long> sum = new LinkedHashMap<>();
        for (GenericValue c : EntityQuery.use(delegator).select("campaignId").from("WaCampaign").where(
                EntityCondition.makeCondition("tenantId", tenantId),
                EntityCondition.makeCondition("createdDate", EntityOperator.GREATER_THAN_EQUAL_TO, from)).queryList()) {
            campaigns++;
            WaCampaigns.stats(delegator, c.getString("campaignId")).forEach((k, v) -> sum.merge(k, v, Long::sum));
        }
        Map<String, Object> r = new LinkedHashMap<>(sum);
        r.put("campaigns", campaigns);
        long sent = sum.getOrDefault("sent", 0L);
        r.put("readPct", sent == 0 ? 0 : Math.round(100.0 * sum.getOrDefault("read", 0L) / sent));
        r.put("repliedPct", sent == 0 ? 0 : Math.round(100.0 * sum.getOrDefault("replied", 0L) / sent));
        return r;
    }

    private static Long median(List<Long> v) {
        if (v.isEmpty()) {
            return null;
        }
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        int m = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2;
    }

    private static Long avg(List<Long> v) {
        if (v.isEmpty()) {
            return null;
        }
        long t = 0;
        for (long x : v) {
            t += x;
        }
        return t / v.size();
    }

    /** 75 -> "1 min 15 s", 3700 -> "1 h 2 min". */
    public static String duration(Long secs) {
        if (secs == null) {
            return "–";
        }
        if (secs < 60) {
            return secs + " s";
        }
        if (secs < 3600) {
            long m = secs / 60;
            long s = secs % 60;
            return m + " min" + (s > 0 && m < 10 ? " " + s + " s" : "");
        }
        if (secs < 86400) {
            long h = secs / 3600;
            long m = (secs % 3600) / 60;
            return h + " h" + (m > 0 ? " " + m + " min" : "");
        }
        return (secs / 86400) + " d " + ((secs % 86400) / 3600) + " h";
    }
}
