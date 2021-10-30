/*
 *  Copyright (C) 2004-2021 Savoir-faire Linux Inc.
 *
 *  Author: Thibault Wittemberg <thibault.wittemberg@savoirfairelinux.com>
 *  Author: Adrien Béraud <adrien.beraud@savoirfairelinux.com>
 *  Author: Raphaël Brulé <raphael.brule@savoirfairelinux.com>
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

import com.google.gson.JsonParser
import ezvcard.Ezvcard
import ezvcard.VCard
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.SingleSubject
import io.reactivex.rxjava3.subjects.Subject
import net.jami.daemon.*
import net.jami.model.*
import net.jami.model.Interaction.InteractionStatus
import net.jami.smartlist.SmartListViewModel
import net.jami.utils.*
import java.io.File
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.SocketException
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.min

/**
 * This service handles the accounts
 * - Load and manage the accounts stored in the daemon
 * - Keep a local cache of the accounts
 * - handle the callbacks that are send by the daemon
 */
class AccountService(
    private val mExecutor: ScheduledExecutorService,
    private val mHistoryService: HistoryService,
    private val mDeviceRuntimeService: DeviceRuntimeService,
    private val mVCardService: VCardService
) {
    /**
     * @return the current Account from the local cache
     */
    var currentAccount: Account? = null
        set(account) {
            if (field == null) {
                field = account
                return
            }
            if (field === account) return
            field = account!!

            // the account order is changed
            // the current Account is now on the top of the list
            val accounts: List<Account> = mAccountList
            val orderedAccountIdList: MutableList<String> = ArrayList(accounts.size)
            val selectedID = account.accountId
            orderedAccountIdList.add(selectedID)
            for (a in accounts) {
                if (a.accountId == selectedID) {
                    continue
                }
                orderedAccountIdList.add(a.accountId)
            }
            setAccountOrder(orderedAccountIdList)
        }

    private var mAccountList: MutableList<Account> = ArrayList()
    private var mHasSipAccount = false
    private var mHasRingAccount = false
    private var mStartingTransfer: DataTransfer? = null
    private val accountsSubject = BehaviorSubject.create<List<Account>>()
    val observableAccounts: Subject<Account> = PublishSubject.create()
    val currentAccountSubject: Observable<Account> = accountsSubject
        .filter { l -> l.isNotEmpty() }
        .map { l -> l[0] }
        .distinctUntilChanged()

    class Message constructor (
        val accountId: String,
        val messageId: String?,
        val callId: String?,
        val author: String,
        val messages: Map<String, String>
    )

    class Location(
        val account: String,
        val callId: String?,
        val peer: Uri,
        var date: Long) {
        enum class Type {
            Position, Stop
        }

        lateinit var type: Type
        var latitude = 0.0
        var longitude = 0.0
    }

    private val incomingMessageSubject: Subject<Message> = PublishSubject.create()
    private val incomingSwarmMessageSubject: Subject<Interaction> = PublishSubject.create()
    val incomingMessages: Observable<TextMessage> = incomingMessageSubject
        .flatMapMaybe { msg: Message ->
            val message = msg.messages[CallService.MIME_TEXT_PLAIN]
            if (message != null) {
                return@flatMapMaybe mHistoryService
                    .incomingMessage(msg.accountId, msg.messageId, msg.author, message)
                    .toMaybe()
            }
            Maybe.empty()
        }
        .share()
    val locationUpdates: Observable<Location> = incomingMessageSubject
        .flatMapMaybe { msg: Message ->
            try {
                val loc = msg.messages[CallService.MIME_GEOLOCATION] ?: return@flatMapMaybe Maybe.empty<Location>()
                val obj = JsonParser.parseString(loc).asJsonObject
                if (obj.size() < 2) return@flatMapMaybe Maybe.empty<Location>()
                return@flatMapMaybe Maybe.just(Location(msg.accountId, msg.callId, Uri.fromId(msg.author), obj["time"].asLong).apply {
                    val t = obj["type"]
                    if (t == null || t.asString == Location.Type.Position.toString()) {
                        type = Location.Type.Position
                        latitude = obj["lat"].asDouble
                        longitude = obj["long"].asDouble
                    } else if (t.asString == Location.Type.Stop.toString()) {
                        type = Location.Type.Stop
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Failed to receive geolocation", e)
                return@flatMapMaybe Maybe.empty<Location>()
            }
        }
        .share()
    private val messageSubject: Subject<Interaction> = PublishSubject.create()
    val dataTransfers: Subject<DataTransfer> = PublishSubject.create()
    private val incomingRequestsSubject: Subject<TrustRequest> = PublishSubject.create()
    fun refreshAccounts() {
        accountsSubject.onNext(mAccountList)
    }

    class RegisteredName(
        val accountId: String,
        val name: String,
        val address: String? = null,
        val state: Int = 0
    )

    class UserSearchResult(val accountId: String, val query: String, var state: Int = 0) {
        var results: MutableList<Contact>? = null
        val resultsViewModels: List<Observable<SmartListViewModel>>
            get() {
                val vms: MutableList<Observable<SmartListViewModel>> = ArrayList(results!!.size)
                for (user in results!!) {
                    vms.add(Observable.just(SmartListViewModel(accountId, user, null)))
                }
                return vms
            }
    }

    private val registeredNameSubject: Subject<RegisteredName> = PublishSubject.create()
    private val searchResultSubject: Subject<UserSearchResult> = PublishSubject.create()

    private class ExportOnRingResult (
        var accountId: String,
        var code: Int,
        var pin: String?
    )

    private class DeviceRevocationResult (
        var accountId: String,
        var deviceId: String,
        var code: Int
    )

    private class MigrationResult (
        var accountId: String,
        var state: String
    )

    private val mExportSubject: Subject<ExportOnRingResult> = PublishSubject.create()
    private val mDeviceRevocationSubject: Subject<DeviceRevocationResult> = PublishSubject.create()
    private val mMigrationSubject: Subject<MigrationResult> = PublishSubject.create()
    val registeredNames: Observable<RegisteredName>
        get() = registeredNameSubject
    val searchResults: Observable<UserSearchResult>
        get() = searchResultSubject
    val incomingSwarmMessages: Observable<TextMessage>
        get() = incomingSwarmMessageSubject
            .filter { i: Interaction -> i is TextMessage }
            .map { i: Interaction -> i as TextMessage }
    val messageStateChanges: Observable<Interaction>
        get() = messageSubject
    val incomingRequests: Observable<TrustRequest>
        get() = incomingRequestsSubject

    /**
     * @return true if at least one of the loaded accounts is a SIP one
     */
    fun hasSipAccount(): Boolean {
        return mHasSipAccount
    }

    /**
     * @return true if at least one of the loaded accounts is a Ring one
     */
    fun hasRingAccount(): Boolean {
        return mHasRingAccount
    }

    /**
     * Loads the accounts from the daemon and then builds the local cache (also sends ACCOUNTS_CHANGED event)
     *
     * @param isConnected sets the initial connection state of the accounts
     */
    fun loadAccountsFromDaemon(isConnected: Boolean) {
        mExecutor.execute {
            refreshAccountsCacheFromDaemon()
            setAccountsActive(isConnected)
        }
    }

    private fun refreshAccountsCacheFromDaemon() {
        var hasSip = false
        var hasJami = false
        val curList: List<Account> = mAccountList
        val accountIds: List<String> = ArrayList(JamiService.getAccountList())
        val newAccounts: MutableList<Account> = ArrayList(accountIds.size)
        for (id in accountIds) {
            for (acc in curList) if (acc.accountId == id) {
                newAccounts.add(acc)
                break
            }
        }

        // Cleanup removed accounts
        for (acc in curList) if (!newAccounts.contains(acc)) acc.cleanup()
        for (accountId in accountIds) {
            var account = findAccount(newAccounts, accountId)
            val details: Map<String, String> = JamiService.getAccountDetails(accountId).toNative()
            val credentials: List<Map<String, String>> = JamiService.getCredentials(accountId).toNative()
            val volatileAccountDetails: Map<String, String> = JamiService.getVolatileAccountDetails(accountId).toNative()
            if (account == null) {
                account = Account(accountId, details, credentials, volatileAccountDetails)
                newAccounts.add(account)
            } else {
                account.setDetails(details)
                account.setCredentials(credentials)
                account.setVolatileDetails(volatileAccountDetails)
            }
        }
        mAccountList = newAccounts
        synchronized(newAccounts) {
            for (account in newAccounts) {
                val accountId = account.accountId
                if (account.isSip) {
                    hasSip = true
                } else if (account.isJami) {
                    hasJami = true
                    val enabled = account.isEnabled
                    account.devices = JamiService.getKnownRingDevices(accountId).toNative()
                    Log.w(TAG, "$accountId loading contacts")
                    account.setContacts(JamiService.getContacts(accountId).toNative())

                    val requests: List<Map<String, String>> = JamiService.getTrustRequests(accountId).toNative()
                    Log.w(TAG, "$accountId loading ${requests.size} trust requests")
                    for (requestInfo in requests) {
                        val request = TrustRequest(accountId, requestInfo)
                        request.vCard?.let { vcard ->
                            request.profile = mVCardService.loadVCardProfile(vcard)
                        }
                        account.addRequest(request)
                    }
                    val conversations: List<String> = JamiService.getConversations(account.accountId)
                    Log.w(TAG, "$accountId loading ${conversations.size} conversations: ")
                    for (conversationId in conversations) {
                        try {
                            val info: Map<String, String> = JamiService.conversationInfos(accountId, conversationId).toNative()
                            /*for (Map.Entry<String, String> i : info.entrySet()) {
                                Log.w(TAG, "conversation info: " + i.getKey() + " " + i.getValue());
                            }*/
                            val mode = if ("true" == info["syncing"]) Conversation.Mode.Syncing else Conversation.Mode.values()[info["mode"]!!.toInt()]
                            val conversation = account.newSwarm(conversationId, mode)
                            for (member in JamiService.getConversationMembers(accountId, conversationId)) {
                                /*for (Map.Entry<String, String> i : member.entrySet()) {
                                    Log.w(TAG, "conversation member: " + i.getKey() + " " + i.getValue());
                                }*/
                                val uri = Uri.fromId(member["uri"]!!)
                                //String role = member.get("role");
                                val lastDisplayed = member["lastDisplayed"]
                                var contact = conversation.findContact(uri)
                                if (contact == null) {
                                    contact = account.getContactFromCache(uri)
                                    conversation.addContact(contact)
                                }
                                if (!StringUtils.isEmpty(lastDisplayed) && contact.isUser) {
                                    conversation.setLastMessageRead(lastDisplayed)
                                }
                            }
                            conversation.lastElementLoaded = Completable.defer { loadMore(conversation, 2).ignoreElement() }.cache()
                            account.conversationStarted(conversation)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error loading conversation", e)
                        }
                    }
                    Log.w(TAG, "$accountId loading conversation requests")
                    for (requestData in JamiService.getConversationRequests(account.accountId).toNative()) {
                        /*for (Map.Entry<String, String> e : requestData.entrySet()) {
                            Log.e(TAG, "Request: " + e.getKey() + " " + e.getValue());
                        }*/
                        val from = Uri.fromString(requestData["from"]!!)
                        val conversationId = requestData["id"]
                        val conversationUri =
                            if (conversationId == null || conversationId.isEmpty()) null
                            else Uri(Uri.SWARM_SCHEME, conversationId)
                        val request = account.getRequest(conversationUri ?: from)
                        if (request == null || conversationUri != request.from) {
                            val received = requestData["received"]!!.toLong() * 1000L
                            account.addRequest(TrustRequest(account.accountId, from, received, null, conversationUri))
                        }
                    }
                    if (enabled) {
                        for (contact in account.contacts.values) {
                            if (!contact.isUsernameLoaded)
                                JamiService.lookupAddress(accountId, "", contact.uri.rawRingId)
                        }
                    }
                }
            }
            mHasSipAccount = hasSip
            mHasRingAccount = hasJami
            if (newAccounts.isNotEmpty()) {
                val newAccount = newAccounts[0]
                if (currentAccount !== newAccount) {
                    currentAccount = newAccount
                }
            }
        }
        accountsSubject.onNext(newAccounts)
    }

    private fun getAccountByName(name: String): Account? {
        synchronized(mAccountList) {
            for (acc in mAccountList) {
                if (acc.alias == name) return acc
            }
        }
        return null
    }

    fun getNewAccountName(prefix: String): String {
        var name = String.format(prefix, "").trim { it <= ' ' }
        if (getAccountByName(name) == null) {
            return name
        }
        var num = 1
        do {
            num++
            name = String.format(prefix, num).trim { it <= ' ' }
        } while (getAccountByName(name) != null)
        return name
    }

    /**
     * Adds a new Account in the Daemon (also sends an ACCOUNT_ADDED event)
     * Sets the new account as the current one
     *
     * @param map the account details
     * @return the created Account
     */
    fun addAccount(map: Map<String, String>): Observable<Account> {
        return Observable.fromCallable {
            val accountId = JamiService.addAccount(StringMap.toSwig(map))
            if (StringUtils.isEmpty(accountId)) {
                throw RuntimeException("Can't create account.")
            }
            var account = getAccount(accountId)
            if (account == null) {
                val accountDetails: Map<String, String> = JamiService.getAccountDetails(accountId).toNative()
                val accountCredentials: List<Map<String, String>> = JamiService.getCredentials(accountId).toNative()
                val accountVolatileDetails: Map<String, String> = JamiService.getVolatileAccountDetails(accountId).toNative()
                val accountDevices: Map<String, String> = JamiService.getKnownRingDevices(accountId).toNative()
                account = Account(accountId, accountDetails, accountCredentials, accountVolatileDetails)
                account.devices = accountDevices
                if (account.isSip) {
                    account.setRegistrationState(AccountConfig.STATE_READY, -1)
                }
                mAccountList.add(account)
                accountsSubject.onNext(mAccountList)
            }
            account
        }
            .flatMap { account: Account ->
                observableAccounts
                    .filter { acc: Account? -> acc!!.accountId == account.accountId }
                    .startWithItem(account)
            }
            .subscribeOn(Schedulers.from(mExecutor))
    }

    val currentAccountIndex: Int
        get() = mAccountList.indexOf(currentAccount)

    /**
     * @return the Account from the local cache that matches the accountId
     */
    fun getAccount(accountId: String?): Account? {
        if (!StringUtils.isEmpty(accountId)) {
            synchronized(mAccountList) {
                for (account in mAccountList)
                    if (accountId == account.accountId) return account
            }
        }
        return null
    }

    fun getAccountSingle(accountId: String): Single<Account> {
        return accountsSubject
            .firstOrError()
            .map { accounts: List<Account> ->
                for (account in accounts) {
                    if (account.accountId == accountId) {
                        return@map account
                    }
                }
                Log.d(TAG, "getAccountSingle() can't find account $accountId")
                throw IllegalArgumentException()
            }
    }

    val observableAccountList: Observable<List<Account>>
        get() = accountsSubject

    fun getObservableAccountUpdates(accountId: String): Observable<Account> {
        return observableAccounts.filter { acc -> acc.accountId == accountId }
    }

    fun getObservableAccountProfile(accountId: String): Observable<Pair<Account, Profile>> {
        return getObservableAccount(accountId).flatMap { a: Account ->
            mVCardService.loadProfile(a).map { profile -> Pair(a, profile) }
        }
    }

    fun getObservableAccount(accountId: String): Observable<Account> {
        return Observable.fromCallable<Account> { getAccount(accountId) }
            .concatWith(getObservableAccountUpdates(accountId))
    }

    fun getObservableAccount(account: Account): Observable<Account> {
        return Observable.just(account)
            .concatWith(observableAccounts.filter { acc -> acc === account })
    }

    val currentProfileAccountSubject: Observable<Pair<Account, Profile>>
        get() = currentAccountSubject.flatMap { a: Account ->
            mVCardService.loadProfile(a).map { profile -> Pair(a, profile) }
        }

    fun subscribeBuddy(accountID: String, uri: String, flag: Boolean) {
        mExecutor.execute { JamiService.subscribeBuddy(accountID, uri, flag) }
    }

    /**
     * Send profile through SIP
     */
    fun sendProfile(callId: String, accountId: String) {
        mVCardService.loadSmallVCard(accountId, VCardService.MAX_SIZE_SIP)
            .subscribeOn(Schedulers.computation())
            .observeOn(Schedulers.from(mExecutor))
            .subscribe({ vcard: VCard ->
                var stringVCard = VCardUtils.vcardToString(vcard)!!
                val nbTotal = stringVCard.length / VCARD_CHUNK_SIZE + if (stringVCard.length % VCARD_CHUNK_SIZE != 0) 1 else 0
                var i = 1
                val r = Random(System.currentTimeMillis())
                val key = Math.abs(r.nextInt())
                Log.d(TAG, "sendProfile, vcard $callId")
                while (i <= nbTotal) {
                    val chunk = HashMap<String, String>()
                    Log.d(TAG, "length vcard " + stringVCard.length + " id " + key + " part " + i + " nbTotal " + nbTotal)
                    val keyHashMap = VCardUtils.MIME_PROFILE_VCARD + "; id=" + key + ",part=" + i + ",of=" + nbTotal
                    val message = stringVCard.substring(0, min(VCARD_CHUNK_SIZE, stringVCard.length))
                    chunk[keyHashMap] = message
                    JamiService.sendTextMessage(callId, StringMap.toSwig(chunk), "Me", false)
                    if (stringVCard.length > VCARD_CHUNK_SIZE) {
                        stringVCard = stringVCard.substring(VCARD_CHUNK_SIZE)
                    }
                    i++
                }
            }) { e: Throwable -> Log.w(TAG, "Not sending empty profile", e) }
    }

    fun setMessageDisplayed(accountId: String?, conversationUri: Uri, messageId: String) {
        mExecutor.execute { JamiService.setMessageDisplayed(accountId, conversationUri.uri, messageId, 3) }
    }

    fun startConversation(accountId: String, initialMembers: Collection<String>): Single<Conversation> {
        return getAccountSingle(accountId).map { account ->
            Log.w(TAG, "startConversation")
            val id = JamiService.startConversation(accountId)
            val conversation = account.getSwarm(id)!!
            for (member in initialMembers) {
                Log.w(TAG, "addConversationMember $member")
                JamiService.addConversationMember(accountId, id, member)
                conversation.addContact(account.getContactFromCache(member))
            }
            account.conversationStarted(conversation)
            Log.w(TAG, "loadConversationMessages")
            conversation
        }.subscribeOn(Schedulers.from(mExecutor))
    }

    fun removeConversation(accountId: String, conversationUri: Uri): Completable {
        return Completable.fromAction { JamiService.removeConversation(accountId, conversationUri.rawRingId) }
            .subscribeOn(Schedulers.from(mExecutor))
    }

    fun loadConversationHistory(accountId: String, conversationUri: Uri, root: String, n: Long) {
        JamiService.loadConversationMessages(accountId, conversationUri.rawRingId, root, n)
    }

    fun loadMore(conversation: Conversation, n: Int = 16): Single<Conversation> {
        synchronized(conversation) {
            if (conversation.isLoaded()) {
                Log.w(TAG, "loadMore: conversation already fully loaded")
                return Single.just(conversation)
            }
            val mode = conversation.mode.blockingFirst()
            if (mode == Conversation.Mode.Syncing || mode == Conversation.Mode.Request) {
                Log.w(TAG, "loadMore: conversation is syncing")
                return Single.just(conversation)
            }
            conversation.loading?.let { return it }
            val ret = SingleSubject.create<Conversation>()
            val roots = conversation.swarmRoot
            Log.w(TAG, "loadMore " + conversation.uri + " " + roots)
            conversation.loading = ret
            if (roots.isEmpty())
                loadConversationHistory(conversation.accountId, conversation.uri, "", n.toLong()
            ) else {
                for (root in roots)
                    loadConversationHistory(conversation.accountId, conversation.uri, root, n.toLong())
            }
            return ret
        }
    }

    fun sendConversationMessage(accountId: String, conversationUri: Uri, txt: String) {
        mExecutor.execute {
            Log.w(TAG, "sendConversationMessages " + conversationUri.rawRingId + " : " + txt)
            JamiService.sendMessage(accountId, conversationUri.rawRingId, txt, "")
        }
    }
    /**
     * @return Account Ids list from Daemon
     */
    /*public Single<List<String>> getAccountList() {
        return Single.fromCallable(() -> (List<String>)new ArrayList<>(JamiService.getAccountList()))
                .subscribeOn(Schedulers.from(mExecutor));
    }*/
    /**
     * Sets the order of the accounts in the Daemon
     *
     * @param accountOrder The ordered list of account ids
     */
    private fun setAccountOrder(accountOrder: List<String>) {
        mExecutor.execute {
            val order = StringBuilder()
            for (accountId in accountOrder) {
                order.append(accountId)
                order.append(File.separator)
            }
            JamiService.setAccountsOrder(order.toString())
        }
    }

    /**
     * Sets the account details in the Daemon
     */
    fun setAccountDetails(accountId: String, map: Map<String, String>) {
        Log.i(TAG, "setAccountDetails() $accountId")
        mExecutor.execute { JamiService.setAccountDetails(accountId, StringMap.toSwig(map)) }
    }

    fun migrateAccount(accountId: String, password: String): Single<String> {
        return mMigrationSubject
            .filter { r: MigrationResult -> r.accountId == accountId }
            .map { r: MigrationResult -> r.state }
            .firstOrError()
            .doOnSubscribe {
                val details = getAccount(accountId)!!.details
                details[ConfigKey.ARCHIVE_PASSWORD.key()] = password
                mExecutor.execute { JamiService.setAccountDetails(accountId, StringMap.toSwig(details)) }
            }
            .subscribeOn(Schedulers.from(mExecutor))
    }

    fun setAccountEnabled(accountId: String, active: Boolean) {
        mExecutor.execute { JamiService.sendRegister(accountId, active) }
    }

    /**
     * Sets the activation state of the account in the Daemon
     */
    fun setAccountActive(accountId: String, active: Boolean) {
        mExecutor.execute { JamiService.setAccountActive(accountId, active) }
    }

    /**
     * Sets the activation state of all the accounts in the Daemon
     */
    fun setAccountsActive(active: Boolean) {
        mExecutor.execute {
            Log.i(TAG, "setAccountsActive() running... $active")
            synchronized(mAccountList) {
                for (a in mAccountList) {
                    // If the proxy is enabled we can considered the account
                    // as always active
                    if (a.isDhtProxyEnabled) {
                        JamiService.setAccountActive(a.accountId, true)
                    } else {
                        JamiService.setAccountActive(a.accountId, active)
                    }
                }
            }
        }
    }

    /**
     * Sets the video activation state of all the accounts in the local cache
     */
    fun setAccountsVideoEnabled(isEnabled: Boolean) {
        synchronized(mAccountList) {
            for (account in mAccountList) {
                account.setDetail(ConfigKey.VIDEO_ENABLED, isEnabled)
            }
        }
    }

    /**
     * @return the default template (account details) for a type of account
     */
    fun getAccountTemplate(accountType: String): Single<HashMap<String, String>> {
        Log.i(TAG, "getAccountTemplate() $accountType")
        return Single.fromCallable { JamiService.getAccountTemplate(accountType).toNative() }
            .subscribeOn(Schedulers.from(mExecutor))
    }

    /**
     * Removes the account in the Daemon as well as local history
     */
    fun removeAccount(accountId: String) {
        Log.i(TAG, "removeAccount() $accountId")
        mExecutor.execute { JamiService.removeAccount(accountId) }
        mHistoryService.clearHistory(accountId).subscribe()
    }

    /**
     * Exports the account on the DHT (used for multi-devices feature)
     */
    fun exportOnRing(accountId: String, password: String): Single<String> {
        return mExportSubject
            .filter { r: ExportOnRingResult -> r.accountId == accountId }
            .firstOrError()
            .map { result: ExportOnRingResult ->
                when (result.code) {
                    PIN_GENERATION_SUCCESS -> return@map result.pin!!
                    PIN_GENERATION_WRONG_PASSWORD -> throw IllegalArgumentException()
                    PIN_GENERATION_NETWORK_ERROR -> throw SocketException()
                    else -> throw UnsupportedOperationException()
                }
            }
            .doOnSubscribe {
                Log.i(TAG, "exportOnRing() $accountId")
                mExecutor.execute { JamiService.exportOnRing(accountId, password) }
            }
            .subscribeOn(Schedulers.io())
    }

    /**
     * @return the list of the account's devices from the Daemon
     */
    fun getKnownRingDevices(accountId: String): Map<String, String> {
        Log.i(TAG, "getKnownRingDevices() $accountId")
        return try {
             mExecutor.submit<HashMap<String, String>> {
                JamiService.getKnownRingDevices(accountId).toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getKnownRingDevices()", e)
            return HashMap()
        }
    }

    /**
     * @param accountId id of the account used with the device
     * @param deviceId  id of the device to revoke
     * @param password  password of the account
     */
    fun revokeDevice(accountId: String, password: String, deviceId: String): Single<Int> {
        return mDeviceRevocationSubject
            .filter { r: DeviceRevocationResult -> r.accountId == accountId && r.deviceId == deviceId }
            .firstOrError()
            .map { r: DeviceRevocationResult -> r.code }
            .doOnSubscribe { mExecutor.execute {
                JamiService.revokeDevice(accountId, password, deviceId)
            }}
            .subscribeOn(Schedulers.io())
    }

    /**
     * @param accountId id of the account used with the device
     * @param newName   new device name
     */
    fun renameDevice(accountId: String, newName: String) {
        val account = getAccount(accountId)
        mExecutor.execute {
            Log.i(TAG, "renameDevice() thread running... $newName")
            val details = JamiService.getAccountDetails(accountId)
            details[ConfigKey.ACCOUNT_DEVICE_NAME.key()] = newName
            JamiService.setAccountDetails(accountId, details)
            account!!.setDetail(ConfigKey.ACCOUNT_DEVICE_NAME, newName)
            account.devices = JamiService.getKnownRingDevices(accountId).toNative()
        }
    }

    fun exportToFile(accountId: String, absolutePath: String, password: String): Completable {
        return Completable.fromAction {
            require(JamiService.exportToFile(accountId, absolutePath, password)) { "Can't export archive" }
        }.subscribeOn(Schedulers.from(mExecutor))
    }

    /**
     * @param accountId   id of the account
     * @param oldPassword old account password
     */
    fun setAccountPassword(accountId: String, oldPassword: String, newPassword: String): Completable {
        return Completable.fromAction {
            require(JamiService.changeAccountPassword(accountId, oldPassword, newPassword)) { "Can't change password" }
        }.subscribeOn(Schedulers.from(mExecutor))
    }

    /**
     * Sets the active codecs list of the account in the Daemon
     */
    fun setActiveCodecList(accountId: String, codecs: List<Long>) {
        mExecutor.execute {
            val list = UintVect()
            list.reserve(codecs.size.toLong())
            list.addAll(codecs)
            JamiService.setActiveCodecList(accountId, list)
            observableAccounts.onNext(getAccount(accountId))
        }
    }

    /**
     * @return The account's codecs list from the Daemon
     */
    fun getCodecList(accountId: String): Single<List<Codec>> {
        return Single.fromCallable<List<Codec>> {
            val results: MutableList<Codec> = ArrayList()
            val payloads = JamiService.getCodecList()
            val activePayloads = JamiService.getActiveCodecList(accountId)
            for (i in payloads.indices) {
                val details = JamiService.getCodecDetails(accountId, payloads[i])
                if (details.size > 1) {
                    results.add(Codec(payloads[i], details.toNative(), activePayloads.contains(payloads[i])))
                } else {
                    Log.i(TAG, "Error loading codec $i")
                }
            }
            results
        }.subscribeOn(Schedulers.from(mExecutor))
    }

    fun validateCertificatePath(
        accountID: String,
        certificatePath: String,
        privateKeyPath: String,
        privateKeyPass: String
    ): Map<String, String>? {
        try {
            return mExecutor.submit<HashMap<String, String>> {
                Log.i(TAG, "validateCertificatePath() running...")
                JamiService.validateCertificatePath(accountID, certificatePath, privateKeyPath, privateKeyPass, "").toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running validateCertificatePath()", e)
        }
        return null
    }

    fun validateCertificate(accountId: String, certificate: String): Map<String, String>? {
        try {
            return mExecutor.submit<HashMap<String, String>> {
                Log.i(TAG, "validateCertificate() running...")
                JamiService.validateCertificate(accountId, certificate).toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running validateCertificate()", e)
        }
        return null
    }

    fun getCertificateDetailsPath(certificatePath: String): Map<String, String>? {
        try {
            return mExecutor.submit<HashMap<String, String>> {
                Log.i(TAG, "getCertificateDetailsPath() running...")
                JamiService.getCertificateDetails(certificatePath).toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getCertificateDetailsPath()", e)
        }
        return null
    }

    fun getCertificateDetails(certificateRaw: String): Map<String, String>? {
        try {
            return mExecutor.submit<HashMap<String, String>> {
                Log.i(TAG, "getCertificateDetails() running...")
                JamiService.getCertificateDetails(certificateRaw).toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getCertificateDetails()", e)
        }
        return null
    }

    /**
     * @return the supported TLS methods from the Daemon
     */
    val tlsSupportedMethods: List<String>
        get() {
            Log.i(TAG, "getTlsSupportedMethods()")
            return SwigNativeConverter.toJava(JamiService.getSupportedTlsMethod())
        }

    /**
     * @return the account's credentials from the Daemon
     */
    fun getCredentials(accountId: String): List<Map<String, String>>? {
        try {
            return mExecutor.submit<ArrayList<Map<String, String>>> {
                Log.i(TAG, "getCredentials() running...")
                JamiService.getCredentials(accountId).toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getCredentials()", e)
        }
        return null
    }

    /**
     * Sets the account's credentials in the Daemon
     */
    fun setCredentials(accountId: String, credentials: List<Map<String, String>>) {
        Log.i(TAG, "setCredentials() $accountId")
        mExecutor.execute { JamiService.setCredentials(accountId, SwigNativeConverter.toSwig(credentials)) }
    }

    /**
     * Sets the registration state to true for all the accounts in the Daemon
     */
    fun registerAllAccounts() {
        Log.i(TAG, "registerAllAccounts()")
        mExecutor.execute { registerAllAccounts() }
    }

    /**
     * Registers a new name on the blockchain for the account
     */
    fun registerName(account: Account, password: String?, name: String) {
        if (account.registeringUsername) {
            Log.w(TAG, "Already trying to register username")
            return
        }
        account.registeringUsername = true
        registerName(account.accountId, password ?: "", name)
    }

    /**
     * Register a new name on the blockchain for the account Id
     */
    fun registerName(account: String, password: String, name: String) {
        Log.i(TAG, "registerName()")
        mExecutor.execute { JamiService.registerName(account, password, name) }
    }
    /* contact requests */
    /**
     * @return all trust requests from the daemon for the account Id
     */
    fun getTrustRequests(accountId: String): List<Map<String, String>>? {
        try {
            return mExecutor.submit<ArrayList<Map<String, String>>> {
                JamiService.getTrustRequests(accountId).toNative()
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error running getTrustRequests()", e)
        }
        return null
    }

    /**
     * Accepts a pending trust request
     */
    fun acceptTrustRequest(accountId: String, from: Uri) {
        Log.i(TAG, "acceptRequest() $accountId $from")
        getAccount(accountId)?.let { account -> account.getRequest(from)?.vCard?.let{ vcard ->
            VCardUtils.savePeerProfileToDisk(vcard, accountId, from.rawRingId + ".vcf", mDeviceRuntimeService.provideFilesDir())
        }}
        mExecutor.execute {
            if (from.isSwarm)
                JamiService.acceptConversationRequest(accountId, from.rawRingId)
            else
                JamiService.acceptTrustRequest(accountId, from.rawRingId)
        }
    }

    /**
     * Handles adding contacts and is the initial point of conversation creation
     *
     * @param conversation    the user's account
     * @param contactUri the contacts raw string uri
     */
    private fun handleTrustRequest(conversation: Conversation, contactUri: Uri, request: TrustRequest?, type: ContactType) {
        val event = ContactEvent()
        when (type) {
            ContactType.ADDED -> {
            }
            ContactType.INVITATION_RECEIVED -> {
                event.status = InteractionStatus.UNKNOWN
                event.author = contactUri.rawRingId
                event.timestamp = request!!.timestamp
            }
            ContactType.INVITATION_ACCEPTED -> {
                event.status = InteractionStatus.SUCCESS
                event.author = contactUri.rawRingId
            }
            ContactType.INVITATION_DISCARDED -> {
                mHistoryService.clearHistory(contactUri.rawRingId, conversation.accountId, true)
                    .subscribe()
                return
            }
            else -> return
        }
        mHistoryService.insertInteraction(conversation.accountId, conversation, event).subscribe()
    }

    private enum class ContactType {
        ADDED, INVITATION_RECEIVED, INVITATION_ACCEPTED, INVITATION_DISCARDED
    }

    /**
     * Refuses and blocks a pending trust request
     */
    fun discardTrustRequest(accountId: String, contactUri: Uri): Boolean {
        return if (contactUri.isSwarm)  {
            JamiService.declineConversationRequest(accountId, contactUri.rawRingId)
            true
        } else {
            val account = getAccount(accountId)
            var removed = false
            if (account != null) {
                removed = account.removeRequest(contactUri) != null
                mHistoryService.clearHistory(contactUri.rawRingId, accountId, true).subscribe()
            }
            mExecutor.execute { JamiService.discardTrustRequest(accountId, contactUri.rawRingId) }
            removed
        }
    }

    /**
     * Sends a new trust request
     */
    fun sendTrustRequest(conversation: Conversation, to: Uri, message: Blob?) {
        Log.i(TAG, "sendTrustRequest() " + conversation.accountId + " " + to)
        handleTrustRequest(conversation, to, null, ContactType.ADDED)
        mExecutor.execute { JamiService.sendTrustRequest(conversation.accountId, to.rawRingId, message ?: Blob()) }
    }

    /**
     * Add a new contact for the account Id on the Daemon
     */
    fun addContact(accountId: String, uri: String) {
        Log.i(TAG, "addContact() $accountId $uri")
        //handleTrustRequest(accountId, Uri.fromString(uri), null, ContactType.ADDED);
        mExecutor.execute { JamiService.addContact(accountId, uri) }
    }

    /**
     * Remove an existing contact for the account Id on the Daemon
     */
    fun removeContact(accountId: String, uri: String, ban: Boolean) {
        Log.i(TAG, "removeContact() $accountId $uri ban:$ban")
        mExecutor.execute { JamiService.removeContact(accountId, uri, ban) }
    }

    /**
     * Looks up for the availability of the name on the blockchain
     */
    fun lookupName(account: String, nameserver: String, name: String) {
        Log.i(TAG, "lookupName() $account $nameserver $name")
        mExecutor.execute { JamiService.lookupName(account, nameserver, name) }
    }

    fun findRegistrationByName(account: String, nameserver: String, name: String): Single<RegisteredName> {
        return if (name.isEmpty()) {
            Single.just(RegisteredName(account, name))
        } else registeredNames
            .filter { r: RegisteredName -> account == r.accountId && name == r.name }
            .firstOrError()
            .doOnSubscribe {
                mExecutor.execute { JamiService.lookupName(account, nameserver, name) }
            }
            .subscribeOn(Schedulers.from(mExecutor))
    }

    fun searchUser(account: String, query: String): Single<UserSearchResult> {
        if (StringUtils.isEmpty(query)) {
            return Single.just(UserSearchResult(account, query))
        }
        val encodedUrl: String = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (e: UnsupportedEncodingException) {
            return Single.error(e)
        }
        return searchResults
            .filter { r: UserSearchResult -> account == r.accountId && encodedUrl == r.query }
            .firstOrError()
            .doOnSubscribe {
                mExecutor.execute { JamiService.searchUser(account, encodedUrl) }
            }
            .subscribeOn(Schedulers.from(mExecutor))
    }

    /**
     * Reverse looks up the address in the blockchain to find the name
     */
    fun lookupAddress(account: String?, nameserver: String?, address: String?) {
        mExecutor.execute { JamiService.lookupAddress(account, nameserver, address) }
    }

    fun pushNotificationReceived(from: String?, data: Map<String?, String?>?) {
        // Log.i(TAG, "pushNotificationReceived()");
        mExecutor.execute { JamiService.pushNotificationReceived(from, StringMap.toSwig(data)) }
    }

    fun setPushNotificationToken(pushNotificationToken: String?) {
        //Log.i(TAG, "setPushNotificationToken()");
        mExecutor.execute { JamiService.setPushNotificationToken(pushNotificationToken) }
    }

    fun volumeChanged(device: String, value: Int) {
        Log.w(TAG, "volumeChanged $device $value")
    }

    fun accountsChanged() {
        // Accounts have changed in Daemon, we have to update our local cache
        refreshAccountsCacheFromDaemon()
    }

    fun stunStatusFailure(accountId: String) {
        Log.d(TAG, "stun status failure: $accountId")
    }

    fun registrationStateChanged(accountId: String, newState: String, code: Int, detailString: String?) {
        //Log.d(TAG, "registrationStateChanged: " + accountId + ", " + newState + ", " + code + ", " + detailString);
        val account = getAccount(accountId) ?: return
        val oldState = account.registrationState
        if (oldState.contentEquals(AccountConfig.STATE_INITIALIZING) && !newState.contentEquals(AccountConfig.STATE_INITIALIZING)) {
            account.setDetails(JamiService.getAccountDetails(account.accountId).toNative())
            account.setCredentials(JamiService.getCredentials(account.accountId).toNative())
            account.devices = JamiService.getKnownRingDevices(account.accountId).toNative()
            account.setVolatileDetails(JamiService.getVolatileAccountDetails(account.accountId).toNative())
        } else {
            account.setRegistrationState(newState, code)
        }
        if (oldState != newState) {
            observableAccounts.onNext(account)
        }
    }

    fun accountDetailsChanged(accountId: String, details: Map<String, String>) {
        val account = getAccount(accountId) ?: return
        Log.d(TAG, "accountDetailsChanged: " + accountId + " " + details.size)
        account.setDetails(details)
        observableAccounts.onNext(account)
    }

    fun volatileAccountDetailsChanged(accountId: String, details: Map<String, String>) {
        val account = getAccount(accountId) ?: return
        //Log.d(TAG, "volatileAccountDetailsChanged: " + accountId + " " + details.size());
        account.setVolatileDetails(details)
        observableAccounts.onNext(account)
    }

    fun accountProfileReceived(accountId: String, name: String?, photo: String?) {
        val account = getAccount(accountId) ?: return
        mVCardService.saveVCardProfile(accountId, account.uri, name, photo)
            .subscribeOn(Schedulers.io())
            .subscribe({ vcard -> account.loadedProfile = mVCardService.loadVCardProfile(vcard).cache() })
                { e -> Log.e(TAG, "Error saving profile", e) }
    }

    fun profileReceived(accountId: String, peerId: String, vcardPath: String) {
        val account = getAccount(accountId) ?: return
        Log.w(TAG, "profileReceived: $accountId, $peerId, $vcardPath")
        val contact = account.getContactFromCache(peerId)
        if (contact.isUser) {
            mVCardService.accountProfileReceived(accountId, File(vcardPath))
                .subscribe({ profile: Profile ->
                    account.loadedProfile = Single.just(profile)
                }) { e -> Log.e(TAG, "Error saving contact profile", e) }
        } else {
            mVCardService.peerProfileReceived(accountId, peerId, File(vcardPath))
                .subscribe({ profile -> contact.setProfile(profile) })
                    { e -> Log.e(TAG, "Error saving contact profile", e) }
        }
    }

    fun incomingAccountMessage(accountId: String, messageId: String?, callId: String?, from: String, messages: Map<String, String>) {
        Log.d(TAG, "incomingAccountMessage: " + accountId + " " + messages.size)
        incomingMessageSubject.onNext(Message(accountId, messageId, callId, from, messages))
    }

    fun accountMessageStatusChanged(
        accountId: String,
        conversationId: String,
        messageId: String,
        peer: String,
        status: Int
    ) {
        val newStatus = InteractionStatus.fromIntTextMessage(status)
        Log.d(TAG, "accountMessageStatusChanged: $accountId, $conversationId, $messageId, $peer, $newStatus")
        if (StringUtils.isEmpty(conversationId)) {
            mHistoryService
                .accountMessageStatusChanged(accountId, messageId, peer, newStatus)
                .subscribe({ t: TextMessage -> messageSubject.onNext(t) }) { e: Throwable ->
                    Log.e(TAG, "Error updating message: " + e.localizedMessage) }
        } else {
            val msg = Interaction(accountId)
            msg.status = newStatus
            msg.setSwarmInfo(conversationId, messageId, null)
            messageSubject.onNext(msg)
        }
    }

    fun composingStatusChanged(accountId: String, conversationId: String, contactUri: String, status: Int) {
        Log.d(TAG, "composingStatusChanged: $accountId, $contactUri, $conversationId, $status")
        getAccountSingle(accountId)
            .subscribe { account: Account ->
                account.composingStatusChanged(
                    conversationId,
                    Uri.fromId(contactUri),
                    Account.ComposingStatus.fromInt(status)
                )
            }
    }

    fun errorAlert(alert: Int) {
        Log.d(TAG, "errorAlert : $alert")
    }

    fun knownDevicesChanged(accountId: String, devices: Map<String, String>) {
        getAccount(accountId)?.let { account ->
            account.devices = devices
            observableAccounts.onNext(account)
        }
    }

    fun exportOnRingEnded(accountId: String, code: Int, pin: String) {
        Log.d(TAG, "exportOnRingEnded: $accountId, $code, $pin")
        mExportSubject.onNext(ExportOnRingResult(accountId, code, pin))
    }

    fun nameRegistrationEnded(accountId: String, state: Int, name: String) {
        Log.d(TAG, "nameRegistrationEnded: $accountId, $state, $name")
        val acc = getAccount(accountId)
        if (acc == null) {
            Log.w(TAG, "Can't find account for name registration callback")
            return
        }
        acc.registeringUsername = false
        acc.setVolatileDetails(JamiService.getVolatileAccountDetails(acc.accountId).toNative())
        if (state == 0) {
            acc.setDetail(ConfigKey.ACCOUNT_REGISTERED_NAME, name)
        }
        observableAccounts.onNext(acc)
    }

    fun migrationEnded(accountId: String, state: String) {
        Log.d(TAG, "migrationEnded: $accountId, $state")
        mMigrationSubject.onNext(MigrationResult(accountId, state))
    }

    fun deviceRevocationEnded(accountId: String, device: String, state: Int) {
        Log.d(TAG, "deviceRevocationEnded: $accountId, $device, $state")
        if (state == 0) {
            getAccount(accountId)?.let { account ->
                val devices = HashMap(account.devices)
                devices.remove(device)
                account.devices = devices
                observableAccounts.onNext(account)
            }
        }
        mDeviceRevocationSubject.onNext(DeviceRevocationResult(accountId, device, state))
    }

    fun incomingTrustRequest(accountId: String, conversationId: String,from: String, message: String?, received: Long) {
        Log.d(TAG, "incomingTrustRequest: $accountId, $conversationId, $from, $received")
        val account = getAccount(accountId)
        if (account != null) {
            val fromUri = Uri.fromString(from)
            var request = account.getRequest(fromUri)
            if (request == null)
                request = TrustRequest(accountId, fromUri, received * 1000L, message, if (conversationId.isEmpty()) null else Uri(Uri.SWARM_SCHEME, conversationId))
            else request.vCard = Ezvcard.parse(message).first()
            request.vCard?.let { vcard ->
                val contact = account.getContactFromCache(fromUri)
                if (!contact.detailsLoaded) {
                    // VCardUtils.savePeerProfileToDisk(vcard, accountId, from + ".vcf", mDeviceRuntimeService.provideFilesDir());
                    mVCardService.loadVCardProfile(vcard)
                        .subscribeOn(Schedulers.computation())
                        .subscribe { profile -> contact.setProfile(profile) }
                }
            }
            account.addRequest(request)
            // handleTrustRequest(account, Uri.fromString(from), request, ContactType.INVITATION_RECEIVED);
            if (account.isEnabled) lookupAddress(accountId, "", from)
            incomingRequestsSubject.onNext(request)
        }
    }

    fun contactAdded(accountId: String, uri: String, confirmed: Boolean) {
        getAccount(accountId)?.let { account ->
            val details: Map<String, String> = JamiService.getContactDetails(accountId, uri)
            val contact = account.addContact(details)
            val conversationUri = contact.conversationUri.blockingFirst()
            if (conversationUri.isSwarm) {
                var conversation = account.getByUri(conversationUri)
                if (conversation == null) {
                    conversation = account.newSwarm(conversationUri.rawRingId, Conversation.Mode.Syncing)
                    conversation.addContact(contact)
                }
            }
            //account.addContact(uri, confirmed);
            if (account.isEnabled) lookupAddress(accountId, "", uri)
        }
    }

    fun contactRemoved(accountId: String, uri: String, banned: Boolean) {
        Log.d(TAG, "Contact removed: $uri User is banned: $banned")
        getAccount(accountId)?.let { account ->
            mHistoryService.clearHistory(uri, accountId, true).subscribe()
            account.removeContact(uri, banned)
        }
    }

    fun registeredNameFound(accountId: String, state: Int, address: String, name: String) {
        try {
            //Log.d(TAG, "registeredNameFound: " + accountId + ", " + state + ", " + name + ", " + address);
            if (address.isNotEmpty()) {
                getAccount(accountId)?.registeredNameFound(state, address, name)
            }
            registeredNameSubject.onNext(RegisteredName(accountId, name, address, state))
        } catch (e: Exception) {
            Log.w(TAG, "registeredNameFound exception", e)
        }
    }

    fun userSearchEnded(accountId: String, state: Int, query: String, results: ArrayList<Map<String, String>>) {
        val account = getAccount(accountId)!!
        val r = UserSearchResult(accountId, query, state)
        val contacts = ArrayList<Contact>(results.size)
        for (m in results) {
            val uri = m["id"]!!
            val username = m["username"]
            val firstName = m["firstName"]
            val lastName = m["lastName"]
            val picture_b64 = m["profilePicture"]
            val contact = account.getContactFromCache(uri)
            if (username != null)
                contact.setUsername(username)
            contact.setProfile("$firstName $lastName", mVCardService.base64ToBitmap(picture_b64))
            contacts.add(contact)
        }
        r.results = contacts
        searchResultSubject.onNext(r)
    }

    private fun addMessage(
        account: Account,
        conversation: Conversation,
        message: Map<String, String>
    ): Interaction {
        /*for ((key, value) in message) {
            Log.w(TAG, "$key -> $value")
        }*/
        val id = message["id"]!!
        val type = message["type"]!!
        val author = message["author"]!!
        val parent = message["linearizedParent"]
        val authorUri = Uri.fromId(author)
        val timestamp = message["timestamp"]!!.toLong() * 1000
        val contact = conversation.findContact(authorUri) ?: account.getContactFromCache(authorUri)
        val interaction: Interaction = when (type) {
            "initial" -> if (conversation.mode.blockingFirst() == Conversation.Mode.OneToOne) {
                    val invited = message["invited"]!!
                    var invitedContact = conversation.findContact(Uri.fromId(invited))
                    if (invitedContact == null) {
                        invitedContact = account.getContactFromCache(invited)
                    }
                    invitedContact.addedDate = Date(timestamp)
                    ContactEvent(invitedContact).setEvent(ContactEvent.Event.fromConversationAction("add"))
                } else {
                    Interaction(conversation, Interaction.InteractionType.INVALID)
                }
            "member" -> {
                val action = message["action"]!!
                val uri = message["uri"]!!
                val member = conversation.findContact(Uri.fromId(uri)) ?: account.getContactFromCache(uri)
                member.addedDate = Date(timestamp)
                ContactEvent(member).setEvent(ContactEvent.Event.fromConversationAction(action))
            }
            "text/plain" -> TextMessage(author, account.accountId, timestamp, conversation, message["body"]!!, !contact.isUser)
            "application/data-transfer+json" -> {
                try {
                    val fileName = message["displayName"]!!
                    val fileId = message["fileId"]
                    //interaction = account.getDataTransfer(fileId);
                    //if (interaction == null) {
                    val paths = arrayOfNulls<String>(1)
                    val progressA = LongArray(1)
                    val totalA = LongArray(1)
                    JamiService.fileTransferInfo(account.accountId, conversation.uri.rawRingId, fileId, paths, totalA, progressA)
                    if (totalA[0] == 0L) {
                        totalA[0] = message["totalSize"]!!.toLong()
                    }
                    val path = File(paths[0]!!)
                    val isComplete = path.exists() && progressA[0] == totalA[0]
                    Log.w(TAG, "add DataTransfer at " + paths[0] + " with progress " + progressA[0] + "/" + totalA[0])
                    DataTransfer(fileId, account.accountId, author, fileName, contact.isUser, timestamp, totalA[0], progressA[0]).apply {
                        daemonPath = path
                        status = if (isComplete) InteractionStatus.TRANSFER_FINISHED else InteractionStatus.FILE_AVAILABLE
                    }
                } catch (e: Exception) {
                    Interaction(conversation, Interaction.InteractionType.INVALID)
                }
            }
            "application/call-history+json" ->
                Call(null, account.accountId, authorUri.rawUriString, if (contact.isUser) Call.Direction.OUTGOING else Call.Direction.INCOMING,timestamp).apply {
                    duration = message["duration"]!!.toLong()
                }
            "merge" -> Interaction(conversation, Interaction.InteractionType.INVALID)
            else -> Interaction(conversation, Interaction.InteractionType.INVALID)
        }
        interaction.contact = contact
        interaction.setSwarmInfo(conversation.uri.rawRingId, id, if (StringUtils.isEmpty(parent)) null else parent)
        interaction.conversation = conversation
        if (conversation.addSwarmElement(interaction)) {
            /*if (conversation.isVisible)
                mHistoryService.setMessageRead(account.accountID, conversation.uri, interaction.messageId!!)*/
        }
        return interaction
    }

    fun conversationLoaded(accountId: String, conversationId: String, messages: List<Map<String, String>>) {
        try {
            // Log.w(TAG, "ConversationCallback: conversationLoaded " + accountId + "/" + conversationId + " " + messages.size());
            getAccount(accountId)?.let { account -> account.getSwarm(conversationId)?.let { conversation ->
                synchronized(conversation) {
                    for (message in messages) {
                        addMessage(account, conversation, message)
                    }
                    conversation.stopLoading()
                }
                account.conversationChanged()
            }}
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading message", e)
        }
    }

    private enum class ConversationMemberEvent {
        Add, Join, Remove, Ban
    }

    fun conversationMemberEvent(accountId: String, conversationId: String, peerUri: String, event: Int) {
        Log.w(TAG, "ConversationCallback: conversationMemberEvent $accountId/$conversationId")
        getAccount(accountId)?.let { account -> account.getSwarm(conversationId)?.let { conversation ->
            val uri = Uri.fromId(peerUri)
            when (ConversationMemberEvent.values()[event]) {
                ConversationMemberEvent.Add, ConversationMemberEvent.Join -> {
                    val contact = conversation.findContact(uri)
                    if (contact == null) {
                        conversation.addContact(account.getContactFromCache(uri))
                    }
                }
                ConversationMemberEvent.Remove, ConversationMemberEvent.Ban -> {
                    if (conversation.mode.blockingFirst() != Conversation.Mode.OneToOne) {
                        conversation.findContact(uri)?.let { contact -> conversation.removeContact(contact) }
                    }
                }
            }
        }}
    }

    fun conversationReady(accountId: String, conversationId: String) {
        Log.w(TAG, "ConversationCallback: conversationReady $accountId/$conversationId")
        val account = getAccount(accountId)
        if (account == null) {
            Log.w(TAG, "conversationReady: can't find account")
            return
        }
        val info = JamiService.conversationInfos(accountId, conversationId)
        /*for (Map.Entry<String, String> i : info.entrySet()) {
            Log.w(TAG, "conversation info: " + i.getKey() + " " + i.getValue());
        }*/
        val modeInt = info["mode"]!!.toInt()
        val mode = Conversation.Mode.values()[modeInt]
        val uri = Uri(Uri.SWARM_SCHEME, conversationId)
        var c = account.getByUri(uri)//getSwarm(conversationId) ?: account.getByUri(Uri(Uri.SWARM_SCHEME, conversationId))
        var setMode = false
        if (c == null) {
            c = account.newSwarm(conversationId, mode)
        } else {
            c.loaded = null
            c.clearHistory(true)
            setMode = mode != c.mode.blockingFirst()
        }
        val conversation = c
        synchronized(conversation) {
            // Making sure to add contacts before changing the mode
            for (member in JamiService.getConversationMembers(accountId, conversationId)) {
                val memberUri = Uri.fromId(member["uri"]!!)
                var contact = conversation.findContact(memberUri)
                if (contact == null) {
                    contact = account.getContactFromCache(memberUri)
                    conversation.addContact(contact)
                }
            }
            if (conversation.lastElementLoaded == null)
                conversation.lastElementLoaded = Completable.defer { loadMore(conversation, 2).ignoreElement() }
                    .cache()
            if (setMode)
                conversation.setMode(mode)
        }
        account.conversationStarted(conversation)
        loadMore(conversation, 2)
    }

    fun conversationRemoved(accountId: String, conversationId: String) {
        val account = getAccount(accountId)
        if (account == null) {
            Log.w(TAG, "conversationRemoved: can't find account")
            return
        }
        account.removeSwarm(conversationId)
    }

    fun conversationRequestDeclined(accountId: String, conversationId: String) {
        Log.d(TAG, "conversation request for $conversationId is declined")
        val account = getAccount(accountId)
        if (account == null) {
            Log.w(TAG, "conversationRequestDeclined: can't find account")
            return
        }
        account.removeRequest(Uri(Uri.SWARM_SCHEME, conversationId))
    }

    fun conversationRequestReceived(accountId: String, conversationId: String, metadata: Map<String, String>) {
        Log.w(TAG, "ConversationCallback: conversationRequestReceived " + accountId + "/" + conversationId + " " + metadata.size)
        val account = getAccount(accountId)
        if (account == null) {
            Log.w(TAG, "conversationRequestReceived: can't find account")
            return
        }
        val contactUri = Uri.fromId(metadata["from"]!!)
        val conversationUri = if (conversationId.isEmpty()) null else Uri(Uri.SWARM_SCHEME, conversationId)
        val request = account.getRequest(contactUri)
        if (request == null || conversationUri != request.conversationUri) {
            val received = metadata["received"]!!
            account.addRequest(TrustRequest(account.accountId, contactUri, received.toLong() * 1000L, null, conversationUri))
        }
    }

    fun messageReceived(accountId: String, conversationId: String, message: Map<String, String>) {
        Log.w(TAG, "ConversationCallback: messageReceived " + accountId + "/" + conversationId + " " + message.size)
        getAccount(accountId)?.let { account -> account.getSwarm(conversationId)?.let { conversation ->
            synchronized(conversation) {
                val interaction = addMessage(account, conversation, message)
                account.conversationUpdated(conversation)
                val isIncoming = !interaction.contact!!.isUser
                if (isIncoming)
                    incomingSwarmMessageSubject.onNext(interaction)
                if (interaction is DataTransfer)
                    dataTransfers.onNext(interaction)
            }
        }}
    }

    fun sendFile(file: File, dataTransfer: DataTransfer): Single<DataTransfer> {
        return Single.fromCallable {
            mStartingTransfer = dataTransfer
            val dataTransferInfo = DataTransferInfo()
            dataTransferInfo.accountId = dataTransfer.account
            val conversationId = dataTransfer.conversationId
            if (!StringUtils.isEmpty(conversationId))
                dataTransferInfo.conversationId = conversationId
            else
                dataTransferInfo.peer = dataTransfer.conversation?.participant
            dataTransferInfo.path = file.absolutePath
            dataTransferInfo.displayName = dataTransfer.displayName
            Log.i(TAG, "sendFile() id=" + dataTransfer.id + " accountId=" + dataTransferInfo.accountId + ", peer=" + dataTransferInfo.peer + ", filePath=" + dataTransferInfo.path)
            val id = LongArray(1)
            val err = getDataTransferError(JamiService.sendFileLegacy(dataTransferInfo, id))
            if (err != DataTransferError.SUCCESS) {
                throw IOException(err.name)
            } else {
                Log.e(TAG, "sendFile: got ID " + id[0])
                dataTransfer.daemonId = id[0]
            }
            dataTransfer
        }.subscribeOn(Schedulers.from(mExecutor))
    }

    fun sendFile(conversation: Conversation, file: File) {
        mExecutor.execute { JamiService.sendFile(conversation.accountId, conversation.uri.rawRingId,file.absolutePath, file.name, "") }
    }

    fun acceptFileTransfer(accountId: String, conversationUri: Uri, messageId: String?, fileId: String) {
        getAccount(accountId)?.let { account -> account.getByUri(conversationUri)?.let { conversation ->
            val transfer = if (conversation.isSwarm)
                conversation.getMessage(messageId!!) as DataTransfer?
            else
                account.getDataTransfer(fileId)
            acceptFileTransfer(conversation, fileId, transfer!!)
        }}
    }

    fun acceptFileTransfer(conversation: Conversation, fileId: String, transfer: DataTransfer) {
        if (conversation.isSwarm) {
            val conversationId = conversation.uri.rawRingId
            val newPath = mDeviceRuntimeService.getNewConversationPath(conversation.accountId, conversationId, transfer.displayName)
            Log.i(TAG, "downloadFile() id=" + conversation.accountId + ", path=" + conversationId + " " + fileId + " to -> " + newPath.absolutePath)
            JamiService.downloadFile(conversation.accountId, conversationId, transfer.messageId, fileId, newPath.absolutePath)
        } else {
            val path = mDeviceRuntimeService.getTemporaryPath(conversation.uri.rawRingId, transfer.storagePath)
            Log.i(TAG, "acceptFileTransfer() id=" + fileId + ", path=" + path.absolutePath)
            JamiService.acceptFileTransfer(conversation.accountId, fileId, path.absolutePath)
        }
    }

    fun cancelDataTransfer(accountId: String, conversationId: String, messageId: String?, fileId: String) {
        Log.i(TAG, "cancelDataTransfer() id=$fileId")
        mExecutor.execute { JamiService.cancelDataTransfer(accountId, conversationId, fileId) }
    }

    private inner class DataTransferRefreshTask constructor(
        private val mAccount: Account,
        private val mConversation: Conversation?,
        private val mToUpdate: DataTransfer
    ) : Runnable {
        var scheduledTask: ScheduledFuture<*>? = null
        override fun run() {
            synchronized(mToUpdate) {
                if (mToUpdate.status == InteractionStatus.TRANSFER_ONGOING) {
                    dataTransferEvent(mAccount, mConversation, mToUpdate.messageId, mToUpdate.fileId!!, 5)
                } else {
                    scheduledTask!!.cancel(false)
                    scheduledTask = null
                }
            }
        }
    }

    fun dataTransferEvent(accountId: String, conversationId: String, interactionId: String, fileId: String, eventCode: Int) {
        val account = getAccount(accountId)
        if (account != null) {
            val conversation = if (conversationId.isEmpty()) null else account.getSwarm(conversationId)
            dataTransferEvent(account, conversation, interactionId, fileId, eventCode)
        }
    }

    fun dataTransferEvent(account: Account, conversation: Conversation?, interactionId: String?, fileId: String, eventCode: Int) {
        var conversation = conversation
        val transferStatus = getDataTransferEventCode(eventCode)
        Log.d(TAG, "Data Transfer $interactionId $fileId $transferStatus")
        val from: String
        val total: Long
        val progress: Long
        val displayName: String
        var transfer = account.getDataTransfer(fileId)
        var outgoing = false
        if (conversation == null) {
            val info = DataTransferInfo()
            val err =
                getDataTransferError(JamiService.dataTransferInfo(account.accountId, fileId, info))
            if (err != DataTransferError.SUCCESS) {
                Log.d(TAG, "Data Transfer error getting details $err")
                return
            }
            from = info.peer
            total = info.totalSize
            progress = info.bytesProgress
            conversation = account.getByUri(from)
            outgoing = info.flags == 0L
            displayName = info.displayName
        } else {
            val paths = arrayOfNulls<String>(1)
            val progressA = LongArray(1)
            val totalA = LongArray(1)
            JamiService.fileTransferInfo(account.accountId, conversation.uri.rawRingId, fileId, paths, totalA, progressA)
            progress = progressA[0]
            total = totalA[0]
            if (transfer == null && interactionId != null && interactionId.isNotEmpty()) {
                transfer = conversation.getMessage(interactionId) as DataTransfer
            }
            if (transfer == null) return
            transfer.conversation = conversation
            transfer.daemonPath = File(paths[0]!!)
            from = transfer.author!!
            displayName = transfer.displayName
        }
        if (transfer == null) {
            val startingTransfer = mStartingTransfer
            if (outgoing && startingTransfer != null) {
                Log.d(TAG, "Data Transfer mStartingTransfer")
                transfer = startingTransfer
                mStartingTransfer = null
            } else {
                transfer = DataTransfer(conversation, from, account.accountId, displayName,
                    outgoing, total,
                    progress, fileId
                )
                if (conversation!!.isSwarm) {
                    transfer.setSwarmInfo(conversation.uri.rawRingId, interactionId!!, null)
                } else {
                    mHistoryService.insertInteraction(account.accountId, conversation, transfer)
                        .blockingAwait()
                }
            }
            account.putDataTransfer(fileId, transfer)
        } else synchronized(transfer) {
            val oldState = transfer.status
            if (oldState != transferStatus) {
                if (transferStatus == InteractionStatus.TRANSFER_ONGOING) {
                    val task = DataTransferRefreshTask(account, conversation, transfer)
                    task.scheduledTask = mExecutor.scheduleAtFixedRate(
                        task,
                        DATA_TRANSFER_REFRESH_PERIOD,
                        DATA_TRANSFER_REFRESH_PERIOD, TimeUnit.MILLISECONDS
                    )
                } else if (transferStatus.isError) {
                    if (!transfer.isOutgoing) {
                        val tmpPath = mDeviceRuntimeService.getTemporaryPath(
                            conversation!!.uri.rawRingId, transfer.storagePath
                        )
                        tmpPath.delete()
                    }
                } else if (transferStatus == InteractionStatus.TRANSFER_FINISHED) {
                    if (!conversation!!.isSwarm && !transfer.isOutgoing) {
                        val tmpPath = mDeviceRuntimeService.getTemporaryPath(
                            conversation.uri.rawRingId, transfer.storagePath
                        )
                        val path = mDeviceRuntimeService.getConversationPath(
                            conversation.uri.rawRingId, transfer.storagePath
                        )
                        FileUtils.moveFile(tmpPath, path)
                    }
                }
            }
            transfer.status = transferStatus
            transfer.bytesProgress = progress
            if (!conversation!!.isSwarm) {
                mHistoryService.updateInteraction(transfer, account.accountId).subscribe()
            }
        }
        Log.d(TAG, "Data Transfer dataTransferSubject.onNext")
        dataTransfers.onNext(transfer)
    }

    fun setProxyEnabled(enabled: Boolean) {
        mExecutor.execute {
            synchronized(mAccountList) {
                for (acc in mAccountList) {
                    if (acc.isJami && acc.isDhtProxyEnabled != enabled) {
                        Log.d(TAG, (if (enabled) "Enabling" else "Disabling") + " proxy for account " + acc.accountId)
                        acc.isDhtProxyEnabled = enabled
                        val details = JamiService.getAccountDetails(acc.accountId)
                        details[ConfigKey.PROXY_ENABLED.key()] = if (enabled) "true" else "false"
                        JamiService.setAccountDetails(acc.accountId, details)
                    }
                }
            }
        }
    }

    companion object {
        private val TAG = AccountService::class.java.simpleName
        private const val VCARD_CHUNK_SIZE = 1000
        private const val DATA_TRANSFER_REFRESH_PERIOD: Long = 500
        private const val PIN_GENERATION_SUCCESS = 0
        private const val PIN_GENERATION_WRONG_PASSWORD = 1
        private const val PIN_GENERATION_NETWORK_ERROR = 2
        private fun findAccount(accounts: List<Account?>, accountId: String): Account? {
            for (account in accounts) if (accountId == account!!.accountId) return account
            return null
        }

        private fun getDataTransferEventCode(eventCode: Int): InteractionStatus {
            var dataTransferEventCode = InteractionStatus.INVALID
            try {
                dataTransferEventCode = InteractionStatus.fromIntFile(eventCode)
            } catch (ignored: ArrayIndexOutOfBoundsException) {
                Log.e(TAG, "getEventCode: invalid data transfer status from daemon")
            }
            return dataTransferEventCode
        }

        private fun getDataTransferError(errorCode: Long): DataTransferError {
            try {
                return DataTransferError.values()[errorCode.toInt()]
            } catch (ignored: ArrayIndexOutOfBoundsException) {
                Log.e(TAG, "getDataTransferError: invalid data transfer error from daemon")
            }
            return DataTransferError.UNKNOWN
        }
    }
}