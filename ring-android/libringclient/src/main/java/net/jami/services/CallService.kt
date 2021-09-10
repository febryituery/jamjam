/*
 *  Copyright (C) 2004-2021 Savoir-faire Linux Inc.
 *
 *  Author: Thibault Wittemberg <thibault.wittemberg@savoirfairelinux.com>
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
package net.jami.services

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import net.jami.daemon.Blob
import net.jami.daemon.JamiService
import net.jami.daemon.StringMap
import net.jami.model.Call
import net.jami.model.Call.CallStatus
import net.jami.model.Conference
import net.jami.model.Conference.ParticipantInfo
import net.jami.model.Uri
import net.jami.utils.Log
import net.jami.utils.StringUtils.isEmpty
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledExecutorService

class CallService(
    private val mExecutor: ScheduledExecutorService,
    private val mContactService: ContactService,
    private val mAccountService: AccountService
) {
    private val currentCalls: MutableMap<String, Call> = HashMap()
    private val currentConferences: MutableMap<String, Conference> = HashMap()
    private val callSubject = PublishSubject.create<Call>()
    private val conferenceSubject = PublishSubject.create<Conference>()

    // private final Set<String> currentConnections = new HashSet<>();
    // private final BehaviorSubject<Integer> connectionSubject = BehaviorSubject.createDefault(0);
    val confsUpdates: Observable<Conference>
        get() = conferenceSubject

    private fun getConfCallUpdates(conf: Conference): Observable<Conference> {
        Log.w(TAG, "getConfCallUpdates " + conf.id)
        return conferenceSubject
            .filter { c -> c == conf }
            .startWithItem(conf)
            .map(Conference::participants)
            .switchMap { list: List<Call> -> Observable.fromIterable(list)
                    .flatMap { call: Call -> callSubject.filter { c -> c == call } } }
            .map { conf }
            .startWithItem(conf)
    }

    fun getConfUpdates(confId: String): Observable<Conference> {
        return getCurrentCallForId(confId)?.let { getConfUpdates(it) }
            ?: Observable.error(IllegalArgumentException())
        /*Conference call = currentConferences.get(confId);
        return call == null ? Observable.error(new IllegalArgumentException()) : conferenceSubject
                .filter(c -> c.getId().equals(confId));//getConfUpdates(call);*/
    }

    /*public Observable<Boolean> getConnectionUpdates() {
        return connectionSubject
                .map(i -> i > 0)
                .distinctUntilChanged();
    }*/
    private fun updateConnectionCount() {
        //connectionSubject.onNext(currentConnections.size() - 2*currentCalls.size());
    }

    fun setIsComposing(accountId: String?, uri: String?, isComposing: Boolean) {
        mExecutor.execute { JamiService.setIsComposing(accountId, uri, isComposing) }
    }

    fun onConferenceInfoUpdated(confId: String, info: List<Map<String, String>>) {
        Log.w(TAG, "onConferenceInfoUpdated $confId $info")
        val conference = getConference(confId)
        var isModerator = false
        if (conference != null) {
            val newInfo: MutableList<ParticipantInfo> = ArrayList(info.size)
            if (conference.isConference) {
                for (i in info) {
                    val call = conference.findCallByContact(Uri.fromString(i["uri"]!!))
                    if (call != null) {
                        val confInfo = ParticipantInfo(call, call.contact!!, i)
                        if (confInfo.isEmpty) {
                            Log.w(TAG, "onConferenceInfoUpdated: ignoring empty entry $i")
                            continue
                        }
                        if (confInfo.contact.isUser && confInfo.isModerator) {
                            isModerator = true
                        }
                        newInfo.add(confInfo)
                    } else {
                        Log.w(TAG, "onConferenceInfoUpdated $confId can't find call for $i")
                        // TODO
                    }
                }
            } else {
                val account = mAccountService.getAccount(conference.call!!.account!!)!!
                for (i in info) {
                    val confInfo = ParticipantInfo(null, account.getContactFromCache(Uri.fromString(i["uri"]!!)), i)
                    if (confInfo.isEmpty) {
                        Log.w(TAG, "onConferenceInfoUpdated: ignoring empty entry $i")
                        continue
                    }
                    if (confInfo.contact.isUser && confInfo.isModerator) {
                        isModerator = true
                    }
                    newInfo.add(confInfo)
                }
            }
            conference.isModerator = isModerator
            conference.setInfo(newInfo)
        } else {
            Log.w(TAG, "onConferenceInfoUpdated can't find conference$confId")
        }
    }

    fun setConfMaximizedParticipant(confId: String, uri: Uri) {
        mExecutor.execute {
            JamiService.setActiveParticipant(confId, uri.rawRingId ?: "")
            JamiService.setConferenceLayout(confId, 1)
        }
    }

    fun setConfGridLayout(confId: String?) {
        mExecutor.execute { JamiService.setConferenceLayout(confId, 0) }
    }

    fun remoteRecordingChanged(callId: String, peerNumber: Uri, state: Boolean) {
        Log.w(TAG, "remoteRecordingChanged $callId $peerNumber $state")
        var conference = getConference(callId)
        val call: Call?
        if (conference == null) {
            call = getCurrentCallForId(callId)
            if (call != null) {
                conference = getConference(call)
            }
        } else {
            call = conference.firstCall
        }
        val account = if (call == null) null else mAccountService.getAccount(call.account!!)
        val contact = account?.getContactFromCache(peerNumber)
        if (conference != null && contact != null) {
            conference.setParticipantRecording(contact, state)
        }
    }

    private class ConferenceEntity internal constructor(var conference: Conference)

    fun getConfUpdates(call: Call): Observable<Conference> {
        return getConfUpdates(getConference(call))
    }

    private fun getConfUpdates(conference: Conference): Observable<Conference> {
        Log.w(TAG, "getConfUpdates " + conference.id)
        val conferenceEntity = ConferenceEntity(conference)
        return conferenceSubject
            .startWithItem(conference)
            .filter { conf: Conference ->
                Log.w(TAG, "getConfUpdates filter " + conf.id + " " + conf.participants.size + " (tracked " + conferenceEntity.conference.id + " " + conferenceEntity.conference.participants.size + ")")
                if (conf == conferenceEntity.conference) {
                    return@filter true
                }
                if (conf.contains(conferenceEntity.conference.id)) {
                    Log.w(TAG, "Switching tracked conference (up) to " + conf.id)
                    conferenceEntity.conference = conf
                    return@filter true
                }
                if (conferenceEntity.conference.participants.size == 1 && conf.participants.size == 1 && conferenceEntity.conference.call == conf.call && conf.call!!.daemonIdString == conf.id) {
                    Log.w(TAG, "Switching tracked conference (down) to " + conf.id)
                    conferenceEntity.conference = conf
                    return@filter true
                }
                false
            }
            .switchMap { conf: Conference -> getConfCallUpdates(conf) }
    }

    val callsUpdates: Observable<Call>
        get() = callSubject

    private fun getCallUpdates(call: Call): Observable<Call> {
        return callSubject.filter { c: Call -> c == call }
            .startWithItem(call)
            .takeWhile { c: Call -> c.callStatus !== CallStatus.OVER }
    }

    /*public Observable<SipCall> getCallUpdates(final String callId) {
        SipCall call = getCurrentCallForId(callId);
        return call == null ? Observable.error(new IllegalArgumentException()) : getCallUpdates(call);
    }*/
    fun placeCallObservable(accountId: String, conversationUri: Uri?, number: Uri, audioOnly: Boolean): Observable<Call> {
        return placeCall(accountId, conversationUri, number, audioOnly)
            .flatMapObservable { call: Call -> getCallUpdates(call) }
    }

    fun placeCall(account: String, conversationUri: Uri?, number: Uri, audioOnly: Boolean): Single<Call> {
        return Single.fromCallable<Call> {
            Log.i(TAG, "placeCall() thread running... $number audioOnly: $audioOnly")
            val volatileDetails = HashMap<String, String>()
            volatileDetails[Call.KEY_AUDIO_ONLY] = audioOnly.toString()
            val callId = JamiService.placeCall(account, number.uri, StringMap.toSwig(volatileDetails))
            if (callId == null || callId.isEmpty()) return@fromCallable null
            if (audioOnly) {
                JamiService.muteLocalMedia(callId, "MEDIA_TYPE_VIDEO", true)
            }
            val call = addCall(account, callId, number, Call.Direction.OUTGOING)
            if (conversationUri != null && conversationUri.isSwarm) call.setSwarmInfo(conversationUri.rawRingId)
            call.muteVideo(audioOnly)
            updateConnectionCount()
            call
        }.subscribeOn(Schedulers.from(mExecutor))
    }

    fun refuse(callId: String) {
        mExecutor.execute {
            Log.i(TAG, "refuse() running... $callId")
            JamiService.refuse(callId)
            JamiService.hangUp(callId)
        }
    }

    fun accept(callId: String) {
        mExecutor.execute {
            Log.i(TAG, "accept() running... $callId")
            JamiService.muteCapture(false)
            JamiService.accept(callId)
        }
    }

    fun hangUp(callId: String) {
        Log.i(TAG, "hangUp() called... $callId", Exception())
        mExecutor.execute {
            Log.i(TAG, "hangUp() running... $callId")
            JamiService.hangUp(callId)
        }
    }

    fun muteParticipant(confId: String, peerId: String, mute: Boolean) {
        mExecutor.execute {
            Log.i(TAG, "mute participant... $peerId")
            JamiService.muteParticipant(confId, peerId, mute)
        }
    }

    fun hangupParticipant(confId: String?, peerId: String) {
        mExecutor.execute {
            Log.i(TAG, "hangup participant... $peerId")
            JamiService.hangupParticipant(confId, peerId)
        }
    }

    fun hold(callId: String) {
        mExecutor.execute {
            Log.i(TAG, "hold() running... $callId")
            JamiService.hold(callId)
        }
    }

    fun unhold(callId: String) {
        mExecutor.execute {
            Log.i(TAG, "unhold() running... $callId")
            JamiService.unhold(callId)
        }
    }

    fun getCallDetails(callId: String): Map<String, String>? {
        try {
            return mExecutor.submit<HashMap<String, String>> {
                Log.i(TAG, "getCallDetails() running... $callId")
                JamiService.getCallDetails(callId).toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getCallDetails()", e)
        }
        return null
    }

    fun muteRingTone(mute: Boolean) {
        Log.d(TAG, (if (mute) "Muting." else "Unmuting.") + " ringtone.")
        JamiService.muteRingtone(mute)
    }

    fun restartAudioLayer() {
        mExecutor.execute {
            Log.i(TAG, "restartAudioLayer() running...")
            JamiService.setAudioPlugin(JamiService.getCurrentAudioOutputPlugin())
        }
    }

    fun setAudioPlugin(audioPlugin: String) {
        mExecutor.execute {
            Log.i(TAG, "setAudioPlugin() running...")
            JamiService.setAudioPlugin(audioPlugin)
        }
    }

    val currentAudioOutputPlugin: String?
        get() {
            try {
                return mExecutor.submit<String> {
                    Log.i(TAG, "getCurrentAudioOutputPlugin() running...")
                    JamiService.getCurrentAudioOutputPlugin()
                }.get()
            } catch (e: Exception) {
                Log.e(TAG, "Error running getCallDetails()", e)
            }
            return null
        }

    fun playDtmf(key: String) {
        mExecutor.execute {
            Log.i(TAG, "playDTMF() running...")
            JamiService.playDTMF(key)
        }
    }

    fun setMuted(mute: Boolean) {
        mExecutor.execute {
            Log.i(TAG, "muteCapture() running...")
            JamiService.muteCapture(mute)
        }
    }

    fun setLocalMediaMuted(callId: String, mediaType: String, mute: Boolean) {
        mExecutor.execute {
            Log.i(TAG, "muteCapture() running...")
            JamiService.muteLocalMedia(callId, mediaType, mute)
        }
    }

    val isCaptureMuted: Boolean
        get() = JamiService.isCaptureMuted()

    fun transfer(callId: String, to: String) {
        mExecutor.execute {
            Log.i(TAG, "transfer() thread running...")
            if (JamiService.transfer(callId, to)) {
                Log.i(TAG, "OK")
            } else {
                Log.i(TAG, "NOT OK")
            }
        }
    }

    fun attendedTransfer(transferId: String, targetID: String) {
        mExecutor.execute {
            Log.i(TAG, "attendedTransfer() thread running...")
            if (JamiService.attendedTransfer(transferId, targetID)) {
                Log.i(TAG, "OK")
            } else {
                Log.i(TAG, "NOT OK")
            }
        }
    }

    var recordPath: String?
        get() {
            try {
                return mExecutor.submit<String> { JamiService.getRecordPath() }.get()
            } catch (e: Exception) {
                Log.e(TAG, "Error running isCaptureMuted()", e)
            }
            return null
        }
        set(path) {
            mExecutor.execute { JamiService.setRecordPath(path) }
        }

    fun toggleRecordingCall(id: String): Boolean {
        mExecutor.execute { JamiService.toggleRecording(id) }
        return false
    }

    fun startRecordedFilePlayback(filepath: String): Boolean {
        mExecutor.execute { JamiService.startRecordedFilePlayback(filepath) }
        return false
    }

    fun stopRecordedFilePlayback() {
        mExecutor.execute { JamiService.stopRecordedFilePlayback() }
    }

    fun sendTextMessage(callId: String, msg: String) {
        mExecutor.execute {
            Log.i(TAG, "sendTextMessage() thread running...")
            val messages = StringMap()
            messages.setRaw("text/plain", Blob.fromString(msg))
            JamiService.sendTextMessage(callId, messages, "", false)
        }
    }

    fun sendAccountTextMessage(accountId: String, to: String, msg: String): Single<Long> {
        return Single.fromCallable {
            Log.i(TAG, "sendAccountTextMessage() running... $accountId $to $msg")
            val msgs = StringMap()
            msgs.setRaw("text/plain", Blob.fromString(msg))
            JamiService.sendAccountTextMessage(accountId, to, msgs)
        }.subscribeOn(Schedulers.from(mExecutor))
    }

    fun cancelMessage(accountId: String, messageID: Long): Completable {
        return Completable.fromAction {
            Log.i(TAG, "CancelMessage() running...   Account ID:  $accountId Message ID  $messageID")
            JamiService.cancelMessage(accountId, messageID)
        }.subscribeOn(Schedulers.from(mExecutor))
    }

    private fun getCurrentCallForId(callId: String): Call? {
        return currentCalls[callId]
    }

    /*public Call getCurrentCallForContactId(String contactId) {
        for (Call call : currentCalls.values()) {
            if (contactId.contains(call.getContact().getPrimaryNumber())) {
                return call;
            }
        }
        return null;
    }*/
    fun removeCallForId(callId: String) {
        synchronized(currentCalls) {
            currentCalls.remove(callId)
            currentConferences.remove(callId)
        }
    }

    private fun addCall(accountId: String, callId: String, from: Uri, direction: Call.Direction): Call {
        synchronized(currentCalls) {
            var call = currentCalls[callId]
            if (call == null) {
                val account = mAccountService.getAccount(accountId)!!
                val contact = mContactService.findContact(account, from)
                val conversationUri = contact.conversationUri.blockingFirst()
                val conversation =
                    if (conversationUri.equals(from)) account.getByUri(from) else account.getSwarm(conversationUri.rawRingId)
                call = Call(callId, from.uri, accountId, conversation, contact, direction)
                currentCalls[callId] = call
            } else {
                Log.w(TAG, "Call already existed ! $callId $from")
            }
            return call
        }
    }

    private fun addConference(call: Call): Conference {
        val confId = call.confId ?: call.daemonIdString!!
        var conference = currentConferences[confId]
        if (conference == null) {
            conference = Conference(call)
            currentConferences[confId] = conference
            conferenceSubject.onNext(conference)
        }
        return conference
    }

    private fun parseCallState(callId: String, newState: String): Call? {
        val callState = CallStatus.fromString(newState)
        var call = currentCalls[callId]
        if (call != null) {
            call.setCallState(callState)
            call.setDetails(JamiService.getCallDetails(callId).toNative())
        } else if (callState !== CallStatus.OVER && callState !== CallStatus.FAILURE) {
            val callDetails: Map<String?, String> = JamiService.getCallDetails(callId)
            call = Call(callId, callDetails)
            if (isEmpty(call.contactNumber)) {
                Log.w(TAG, "No number")
                return null
            }
            call.setCallState(callState)
            val account = mAccountService.getAccount(call.account!!)!!
            val contact = mContactService.findContact(account, Uri.fromString(call.contactNumber!!))
            val registeredName = callDetails[Call.KEY_REGISTERED_NAME]
            if (registeredName != null && registeredName.isNotEmpty()) {
                contact.setUsername(registeredName)
            }
            val conversation = account.getByUri(contact.conversationUri.blockingFirst())
            call.contact = contact
            call.conversation = conversation
            Log.w(TAG, "parseCallState " + contact + " " + contact.conversationUri.blockingFirst() + " " + conversation + " " + conversation!!.participant)
            currentCalls[callId] = call
            updateConnectionCount()
        }
        return call
    }

    fun connectionUpdate(id: String?, state: Int) {
        // Log.d(TAG, "connectionUpdate: " + id + " " + state);
        /*switch(state) {
            case 0:
                currentConnections.add(id);
                break;
            case 1:
            case 2:
                currentConnections.remove(id);
                break;
        }
        updateConnectionCount();*/
    }

    fun callStateChanged(callId: String, newState: String, detailCode: Int) {
        Log.d(TAG, "call state changed: $callId, $newState, $detailCode")
        try {
            synchronized(currentCalls) {
                parseCallState(callId, newState)?.let { call ->
                    callSubject.onNext(call)
                    if (call.callStatus === CallStatus.OVER) {
                        currentCalls.remove(call.daemonIdString)
                        currentConferences.remove(call.daemonIdString)
                        updateConnectionCount()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during state change: ", e)
        }
    }

    fun incomingCall(accountId: String, callId: String, from: String) {
        Log.d(TAG, "incoming call: $accountId, $callId, $from")
        val call = addCall(accountId, callId, Uri.fromStringWithName(from).first, Call.Direction.INCOMING)
        callSubject.onNext(call)
        updateConnectionCount()
    }

    fun incomingMessage(callId: String, from: String, messages: Map<String, String>) {
        val call = currentCalls[callId]
        if (call == null) {
            Log.w(TAG, "incomingMessage: unknown call or no message: $callId $from")
            return
        }
        call.appendToVCard(messages)?.let { vcard ->
            mContactService.saveVCardContactData(call.contact!!, call.account!!, vcard)
        }
        if (messages.containsKey(MIME_TEXT_PLAIN)) {
            mAccountService.incomingAccountMessage(call.account!!, null, callId, from, messages)
        }
    }

    fun recordPlaybackFilepath(id: String, filename: String) {
        Log.d(TAG, "record playback filepath: $id, $filename")
        // todo needs more explanations on that
    }

    fun onRtcpReportReceived(callId: String) {
        Log.i(TAG, "on RTCP report received: $callId")
    }

    fun removeConference(confId: String) {
        mExecutor.execute { JamiService.removeConference(confId) }
    }

    fun joinParticipant(selCallId: String, dragCallId: String): Single<Boolean> {
        return Single.fromCallable { JamiService.joinParticipant(selCallId, dragCallId) }
            .subscribeOn(Schedulers.from(mExecutor))
    }

    fun addParticipant(callId: String, confId: String) {
        mExecutor.execute { JamiService.addParticipant(callId, confId) }
    }

    fun addMainParticipant(confId: String) {
        mExecutor.execute { JamiService.addMainParticipant(confId) }
    }

    fun detachParticipant(callId: String) {
        mExecutor.execute { JamiService.detachParticipant(callId) }
    }

    fun joinConference(selConfId: String, dragConfId: String) {
        mExecutor.execute { JamiService.joinConference(selConfId, dragConfId) }
    }

    fun hangUpConference(confId: String) {
        mExecutor.execute { JamiService.hangUpConference(confId) }
    }

    fun holdConference(confId: String) {
        mExecutor.execute { JamiService.holdConference(confId) }
    }

    fun unholdConference(confId: String) {
        mExecutor.execute { JamiService.unholdConference(confId) }
    }

    fun isConferenceParticipant(callId: String): Boolean {
        try {
            return mExecutor.submit<Boolean> {
                Log.i(TAG, "isConferenceParticipant() running...")
                JamiService.isConferenceParticipant(callId)
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running isConferenceParticipant()", e)
        }
        return false
    }

    //todo remove condition when callDetails does not contains sips ids anymore
    val conferenceList: Map<String, ArrayList<String>>?
        get() {
            try {
                return mExecutor.submit(Callable {
                    Log.i(TAG, "getConferenceList() running...")
                    val callIds = JamiService.getCallList()
                    val confs = HashMap<String, ArrayList<String>>(callIds.size)
                    for (i in callIds.indices) {
                        val callId = callIds[i]
                        var confId = JamiService.getConferenceId(callId)
                        val callDetails: Map<String, String> = JamiService.getCallDetails(callId).toNative()

                        //todo remove condition when callDetails does not contains sips ids anymore
                        if (!callDetails["PEER_NUMBER"]!!.contains("sips")) {
                            if (confId == null || confId.isEmpty()) {
                                confId = callId
                            }
                            var calls = confs[confId]
                            if (calls == null) {
                                calls = ArrayList()
                                confs[confId] = calls
                            }
                            calls.add(callId)
                        }
                    }
                    confs
                }).get()
            } catch (e: Exception) {
                Log.e(TAG, "Error running isConferenceParticipant()", e)
            }
            return null
        }

    fun getParticipantList(confId: String?): List<String>? {
        try {
            return mExecutor.submit<ArrayList<String>> {
                Log.i(TAG, "getParticipantList() running...")
                ArrayList(JamiService.getParticipantList(confId))
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getParticipantList()", e)
        }
        return null
    }

    fun getConference(call: Call): Conference {
        return addConference(call)
    }

    fun getConferenceId(callId: String): String {
        return JamiService.getConferenceId(callId)
    }

    fun getConferenceState(callId: String): String? {
        try {
            return mExecutor.submit<String> {
                Log.i(TAG, "getConferenceDetails() thread running...")
                JamiService.getConferenceDetails(callId)["CONF_STATE"]
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getParticipantList()", e)
        }
        return null
    }

    fun getConference(id: String): Conference? {
        return currentConferences[id]
    }

    fun getConferenceDetails(id: String): Map<String, String>? {
        try {
            return mExecutor.submit<HashMap<String, String>> {
                Log.i(TAG, "getCredentials() thread running...")
                JamiService.getConferenceDetails(id).toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getParticipantList()", e)
        }
        return null
    }

    fun conferenceCreated(confId: String) {
        Log.d(TAG, "conference created: $confId")
        var conf = currentConferences[confId]
        if (conf == null) {
            conf = Conference(confId)
            currentConferences[confId] = conf
        }
        val participants = JamiService.getParticipantList(confId)
        val map = JamiService.getConferenceDetails(confId)
        conf.setState(map["STATE"])
        for (callId in participants) {
            val call = getCurrentCallForId(callId)
            if (call != null) {
                Log.d(TAG, "conference created: adding participant " + callId + " " + call.contact!!.displayName)
                call.confId = confId
                conf.addParticipant(call)
            }
            val rconf = currentConferences.remove(callId)
            Log.d(TAG, "conference created: removing conference " + callId + " " + rconf + " now " + currentConferences.size)
        }
        conferenceSubject.onNext(conf)
    }

    fun conferenceRemoved(confId: String) {
        Log.d(TAG, "conference removed: $confId")
        currentConferences.remove(confId)?.let { conf ->
            for (call in conf.participants) {
                call.confId = null
            }
            conf.removeParticipants()
            conferenceSubject.onNext(conf)
        }
    }

    fun conferenceChanged(confId: String, state: String) {
        Log.d(TAG, "conference changed: $confId, $state")
        try {
            var conf = currentConferences[confId]
            if (conf == null) {
                conf = Conference(confId)
                currentConferences[confId] = conf
            }
            conf.setState(state)
            val participants: Set<String> = JamiService.getParticipantList(confId).toHashSet()
            // Add new participants
            for (callId in participants) {
                if (!conf.contains(callId)) {
                    val call = getCurrentCallForId(callId)
                    if (call != null) {
                        Log.d(TAG, "conference changed: adding participant " + callId + " " + call.contact!!.displayName)
                        call.confId = confId
                        conf.addParticipant(call)
                    }
                    currentConferences.remove(callId)
                }
            }

            // Remove participants
            val calls = conf.participants
            var removed = false
            val i = calls.iterator()
            while (i.hasNext()) {
                val call = i.next()
                if (!participants.contains(call.daemonIdString)) {
                    Log.d(TAG, "conference changed: removing participant " + call.daemonIdString + " " + call.contact!!.displayName)
                    call.confId = null
                    i.remove()
                    removed = true
                }
            }
            conferenceSubject.onNext(conf)
            if (removed && conf.participants.size == 1) {
                val call = conf.participants[0]
                call.confId = null
                addConference(call)
            }
        } catch (e: Exception) {
            Log.w(TAG, "exception in conferenceChanged", e)
        }
    }

    companion object {
        private val TAG = CallService::class.simpleName!!
        const val MIME_TEXT_PLAIN = "text/plain"
        const val MIME_GEOLOCATION = "application/geo"
        const val MEDIA_TYPE_AUDIO = "MEDIA_TYPE_AUDIO"
        const val MEDIA_TYPE_VIDEO = "MEDIA_TYPE_VIDEO"
    }
}