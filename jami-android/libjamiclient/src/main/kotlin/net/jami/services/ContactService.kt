/*
 *  Copyright (C) 2004-2022 Savoir-faire Linux Inc.
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

import ezvcard.VCard
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.core.Single
import net.jami.model.*
import net.jami.smartlist.ConversationItemViewModel
import net.jami.utils.Log
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * This service handles the contacts
 * - Load the contacts stored in the system
 * - Keep a local cache of the contacts
 * - Provide query tools to search contacts by id, number, ...
 */
abstract class ContactService(
    val mPreferencesService: PreferencesService,
    val mDeviceRuntimeService: DeviceRuntimeService,
    val mAccountService: AccountService
) {
    abstract fun loadContactsFromSystem(loadRingContacts: Boolean, loadSipContacts: Boolean): Map<Long, Contact>
    protected abstract fun findContactByIdFromSystem(contactId: Long, contactKey: String): Contact?
    protected abstract fun findContactBySipNumberFromSystem(number: String): Contact?
    protected abstract fun findContactByNumberFromSystem(number: String): Contact?
    abstract fun loadContactData(contact: Contact, accountId: String): Single<Profile>
    abstract fun saveVCardContactData(contact: Contact, accountId: String, vcard: VCard)
    abstract fun saveVCardContact(accountId: String, uri: String?, displayName: String?, pictureB64: String?): Single<VCard>

    /**
     * Load contacts from system and generate a local contact cache
     *
     * @param loadRingContacts if true, ring contacts will be taken care of
     * @param loadSipContacts  if true, sip contacts will be taken care of
     */
    fun loadContacts(loadRingContacts: Boolean, loadSipContacts: Boolean, account: Account?): Single<Map<Long, Contact>> {
        return Single.fromCallable {
            val settings = mPreferencesService.settings
            if (settings.useSystemContacts && mDeviceRuntimeService.hasContactPermission()) {
                return@fromCallable loadContactsFromSystem(loadRingContacts, loadSipContacts)
            }
            HashMap()
        }
    }

    fun observeContact(accountId: String, contactUri: Uri, withPresence: Boolean): Observable<ContactViewModel> {
        val account = mAccountService.getAccount(accountId) ?: return Observable.error(IllegalArgumentException())
        val contact = account.getContactFromCache(contactUri)
        return observeContact(accountId, contact, withPresence)
    }

    fun observeContact(accountId: String, contact: Contact, withPresence: Boolean): Observable<ContactViewModel> {
        // Log.w(TAG, "observeContact $accountId ${contact.uri} ${contact.isUser}")
        val uriString = contact.uri.rawRingId
        synchronized(contact) {
            val presenceUpdates = contact.presenceUpdates ?: run {
                Observable.create { emitter: ObservableEmitter<Boolean> ->
                    emitter.onNext(false)
                    contact.setPresenceEmitter(emitter)
                    mAccountService.subscribeBuddy(accountId, uriString, true)
                    emitter.setCancellable {
                        mAccountService.subscribeBuddy(accountId, uriString, false)
                        contact.setPresenceEmitter(null)
                        emitter.onNext(false)
                    }
                }
                    .replay(1)
                    .refCount(5, TimeUnit.SECONDS)
                    .apply { contact.presenceUpdates = this }
            }
            val username = contact.username ?: run {
                mAccountService.findRegistrationByAddress(accountId, "", uriString)
                    .map { registration ->
                        // Log.w(TAG, "username lookup response $registration")
                        if (registration.state > 2)
                            throw RuntimeException("lookup failed")
                        registration.name
                    }
                    .doOnError { contact.username = null }
                    .onErrorReturn { "" }
                    .cache()
                    .apply { contact.username = this }
            }

            return if (contact.isUser) {
                mAccountService.getObservableAccountProfile(accountId).map { profile ->
                    ContactViewModel(contact, profile.second, profile.first.registeredName.ifEmpty { null }, withPresence && profile.first.isRegistered) }
            } else {
                if (contact.loadedProfile == null) {
                    contact.loadedProfile = loadContactData(contact, accountId).cache()
                }

                if (withPresence)
                    Observable.combineLatest(contact.profile, username.toObservable(), presenceUpdates)
                    { profile, name, presence -> ContactViewModel(contact, profile, name.ifEmpty { null }, presence) }
                else
                    Observable.combineLatest(contact.profile, username.toObservable())
                    { profile, name -> ContactViewModel(contact, profile, name.ifEmpty { null }, false) }
            }
        }
    }

    fun observeContact(accountId: String, contacts: Collection<Contact>, withPresence: Boolean): Observable<List<ContactViewModel>> =
        if (contacts.isEmpty()) {
            Observable.just(emptyList())
        } else if (contacts.size == 1 && contacts.first().isUser) {
            observeContact(accountId, contacts.first(), withPresence).map(Collections::singletonList)
        }  else {
            val observables: MutableList<Observable<ContactViewModel>> = ArrayList(contacts.size)
            for (contact in contacts) {
                if (!contact.isUser) observables.add(observeContact(accountId, contact, withPresence))
            }
            if (observables.isEmpty()) Observable.just(emptyList()) else Observable.combineLatest(observables) { a: Array<Any> ->
                val obs: MutableList<ContactViewModel> = ArrayList(a.size)
                for (o in a) obs.add(o as ContactViewModel)
                obs
            }
        }

    fun getLoadedContact(accountId: String, contact: Contact, withPresence: Boolean = false): Single<ContactViewModel> =
        observeContact(accountId, contact, withPresence)
            .firstOrError()

    fun getLoadedContact(accountId: String, contacts: Collection<Contact>, withPresence: Boolean = false): Single<List<ContactViewModel>> =
        if (contacts.isEmpty()) Single.just(emptyList()) else Observable.fromIterable(contacts)
            .concatMapEager { contact: Contact -> getLoadedContact(accountId, contact, withPresence).toObservable() }
            .toList(contacts.size)

    fun getLoadedConversation(conversation: Conversation): Single<ConversationItemViewModel> =
        conversation.contactUpdates.firstOrError().flatMap { contacts -> Single.zip(getLoadedContact(
            conversation.accountId, contacts, false),
            conversation.profile.firstOrError()
        ){ c, p ->
            ConversationItemViewModel(conversation, p, c, false)
        } }

    fun observeLoadedContact(accountId: String, contacts: List<Contact>, withPresence: Boolean = false): List<Observable<ContactViewModel>> =
        contacts.map { contact -> observeContact(accountId, contact, withPresence) }

    /**
     * Searches a contact in the local cache and then in the system repository
     * In the last case, the contact is created and added to the local cache
     *
     * @return The found/created contact
     */
    fun findContactByNumber(account: Account, number: String): Contact? =
        if (number.isEmpty()) null else findContact(account, Uri.fromString(number))

    fun findContact(account: Account, uri: Uri): Contact {
        val contact = account.getContactFromCache(uri)
        // TODO load system contact info into SIP contact
        if (account.isSip) {
            loadContactData(contact, account.accountId).subscribe({})
            { e: Throwable -> Log.e(TAG, "Can't load contact data") }
        }
        return contact
    }

    companion object {
        private val TAG = ContactService::class.simpleName!!
    }
}