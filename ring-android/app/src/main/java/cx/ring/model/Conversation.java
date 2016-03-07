/*
 *  Copyright (C) 2015-2016 Savoir-faire Linux Inc.
 *
 *  Author: Adrien Béraud <adrien.beraud@savoirfairelinux.com>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package cx.ring.model;

import android.content.res.Resources;
import android.database.ContentObservable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import cx.ring.R;
import cx.ring.history.HistoryCall;
import cx.ring.history.HistoryEntry;

public class Conversation extends ContentObservable
{
    static final String TAG = Conversation.class.getSimpleName();
    private final static Random rand = new Random();

    public CallContact contact;
    /** accountId -> histroy entries */
    private final Map<String, HistoryEntry> history = new HashMap<>();
    public final ArrayList<Conference> current_calls;
    private final ArrayList<ConversationElement> agregate_history = new ArrayList<>(32);

    // runtime flag set to true if the user
    public boolean mVisible = false;
    public int notificationId;
    public NotificationCompat.Builder notificationBuilder = null;

    public String getLastNumberUsed(String accountID) {
        HistoryEntry he = history.get(accountID);
        if (he == null)
            return null;
        return he.getLastNumberUsed();
    }

    public Conference getConference(String id) {
        for (Conference c : current_calls)
            if (c.getId().contentEquals(id) || c.getCallById(id) != null)
                return c;
        return null;
    }

    public void addConference(Conference c) {
        current_calls.add(c);
    }

    public void removeConference(Conference c) {
        current_calls.remove(c);
    }

    public Pair<HistoryEntry, HistoryCall> findHistoryByCallId(String id) {
        for (HistoryEntry e : history.values()) {
            for (HistoryCall c : e.getCalls().values()) {
                if (c.getCallId().equals(id))
                    return new Pair<>(e, c);
            }
        }
        return null;
    }

    public class ConversationElement {
        public HistoryCall call = null;
        public TextMessage text = null;
        public ConversationElement(HistoryCall c) {
            call = c;
        }
        public ConversationElement(TextMessage t) {
            text = t;
        }
        public long getDate() {
            if (text != null)
                return text.getTimestamp();
            else if (call != null)
                return call.call_start;
            return 0;
        }
    }

    public Conversation(CallContact c) {
        contact = c;
        current_calls = new ArrayList<>();
        notificationId = rand.nextInt();
    }

    public CallContact getContact() {
        return contact;
    }

    public Date getLastInteraction() {
        if (!current_calls.isEmpty()) {
            return new Date();
        }
        Date d = new Date(0);

        //for (Map.Entry<String, HistoryEntry> e : history.entrySet()) {
        for (HistoryEntry e : history.values()) {
            Date nd = e.getLastInteraction();
            if (d.compareTo(nd) < 0)
                d = nd;
        }
        return d;
    }

    public String getLastInteractionSumary(Resources resources) {
        if (!current_calls.isEmpty()) {
            return resources.getString(R.string.ongoing_call);
        }
        Pair<Date, String> d = new Pair<>(new Date(0), null);

        for (HistoryEntry e : history.values()) {
            Pair<Date, String> nd = e.getLastInteractionSumary(resources);
            if (nd == null)
                continue;
            if (d.first.compareTo(nd.first) < 0)
                d = nd;
        }
        return d.second;
    }

    public void addHistoryCall(HistoryCall c) {
        String accountId = c.getAccountID();
        if (history.containsKey(accountId))
            history.get(accountId).addHistoryCall(c, contact);
        else {
            HistoryEntry e = new HistoryEntry(accountId, contact);
            e.addHistoryCall(c, contact);
            history.put(accountId, e);
        }
        agregate_history.add(new ConversationElement(c));
    }
    public void addTextMessage(TextMessage txt) {
        if (txt.getCallId() != null && !txt.getCallId().isEmpty()) {
            Conference conf = getConference(txt.getCallId());
            if (conf == null)
                return;
            conf.addSipMessage(txt);
        }
        if (txt.getContact() == null)
            txt.setContact(contact);
        String accountId = txt.getAccount();
        if (history.containsKey(accountId))
            history.get(accountId).addTextMessage(txt);
        else {
            HistoryEntry e = new HistoryEntry(accountId, contact);
            e.addTextMessage(txt);
            history.put(accountId, e);
        }
        agregate_history.add(new ConversationElement(txt));
    }

    public ArrayList<ConversationElement> getHistory() {
        Collections.sort(agregate_history, new Comparator<ConversationElement>() {
            @Override
            public int compare(ConversationElement lhs, ConversationElement rhs) {
                return (int)((lhs.getDate() - rhs.getDate())/1000L);
            }
        });
        return agregate_history;
    }

    public Set<String> getAccountsUsed() {
        return history.keySet();
    }

    public String getLastAccountUsed() {
        String last = null;
        Date d = new Date(0);
        for (Map.Entry<String, HistoryEntry> e : history.entrySet()) {
            Date nd = e.getValue().getLastInteraction();
            if (d.compareTo(nd) < 0) {
                d = nd;
                last = e.getKey();
            }
        }
        Log.i(TAG, "getLastAccountUsed " + last);
        return last;
    }

    public Conference getCurrentCall() {
        if (current_calls.isEmpty())
            return null;
        return current_calls.get(0);
    }

    public ArrayList<Conference> getCurrentCalls() {
        return current_calls;
    }

    public Collection<TextMessage> getTextMessages() {
        return getTextMessages(null);
    }

    public Collection<TextMessage> getTextMessages(Date since) {
        TreeMap<Long, TextMessage> texts = new TreeMap<>();
        for (HistoryEntry h : history.values()) {
            texts.putAll(since == null ? h.getTextMessages() : h.getTextMessages(since.getTime()));
        }
        return texts.values();
    }

    public TreeMap<Long, TextMessage> getUnreadTextMessages() {
        TreeMap<Long, TextMessage> texts = new TreeMap<>();
        for (HistoryEntry h : history.values()) {
            for (Map.Entry<Long, TextMessage> entry : h.getTextMessages().descendingMap().entrySet())
                if (entry.getValue().isRead())
                    break;
            else
                texts.put(entry.getKey(), entry.getValue());
        }
        return texts;
    }
    public boolean hasUnreadTextMessages() {
        for (HistoryEntry h : history.values()) {
            Map.Entry<Long, TextMessage> m = h.getTextMessages().lastEntry();
            if (m != null && !m.getValue().isRead())
                return true;
        }
        return false;
    }

    public Map<String, HistoryEntry> getRawHistory() {
        return history;
    }

}
