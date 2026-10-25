package com.nishgamerrz.relaydash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who has opened the dashboard, keyed by remote address.
 *
 * Kept in memory only: this is "who is connected right now", not an audit log,
 * and nothing leaves the device.
 */
public final class DeviceLog {

    public static final class Entry {
        public final String ip;
        public String agent;        // browser / device string, trimmed
        public String path;         // last page or API call
        public long firstSeen;
        public long lastSeen;
        public int hits;

        Entry(String ip, String agent, String path, long now) {
            this.ip = ip;
            this.agent = agent;
            this.path = path;
            this.firstSeen = now;
            this.lastSeen = now;
            this.hits = 1;
        }
    }

    /** Newest activity first, capped so a long run cannot grow forever. */
    private static final int MAX_ENTRIES = 40;

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    public synchronized void touch(String ip, String agent, String path) {
        if (ip == null || ip.length() == 0) return;
        long now = System.currentTimeMillis();
        Entry e = entries.get(ip);
        if (e == null) {
            if (entries.size() >= MAX_ENTRIES) {
                String oldest = null;
                long oldestSeen = Long.MAX_VALUE;
                for (Map.Entry<String, Entry> cand : entries.entrySet()) {
                    if (cand.getValue().lastSeen < oldestSeen) {
                        oldestSeen = cand.getValue().lastSeen;
                        oldest = cand.getKey();
                    }
                }
                if (oldest != null) entries.remove(oldest);
            }
            e = new Entry(ip, agent, path, now);
            entries.put(ip, e);
        } else {
            e.lastSeen = now;
            e.hits++;
            e.path = path;
            if (agent != null && agent.length() > 0) e.agent = agent;
        }
    }

    public synchronized List<Entry> snapshot() {
        List<Entry> list = new ArrayList<Entry>(entries.values());
        // newest first
        for (int i = 1; i < list.size(); i++) {
            Entry key = list.get(i);
            int j = i - 1;
            while (j >= 0 && list.get(j).lastSeen < key.lastSeen) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, key);
        }
        return list;
    }

    public synchronized int onlineCount(long withinMs) {
        long cutoff = System.currentTimeMillis() - withinMs;
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.lastSeen >= cutoff) n++;
        }
        return n;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
