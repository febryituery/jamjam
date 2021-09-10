/*
 *  Copyright (C) 2004-2018 Savoir-faire Linux Inc.
 *
 *  Author: Hadrien De Sousa <hadrien.desousa@savoirfairelinux.com>
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
package cx.ring.tv.contact

import cx.ring.utils.ConversationPath
import ezvcard.VCard
import io.reactivex.rxjava3.core.Scheduler
import net.jami.daemon.Blob
import net.jami.services.ConversationFacade
import net.jami.model.Account
import net.jami.model.Call
import net.jami.model.Uri
import net.jami.mvp.RootPresenter
import net.jami.services.AccountService
import net.jami.services.VCardService
import net.jami.smartlist.SmartListViewModel
import net.jami.utils.VCardUtils.vcardToString
import javax.inject.Inject
import javax.inject.Named

class TVContactPresenter @Inject constructor(
    private val mAccountService: AccountService,
    private val mConversationService: ConversationFacade,
    @param:Named("UiScheduler") private val mUiScheduler: Scheduler,
    private val mVCardService: VCardService
) : RootPresenter<TVContactView>() {
    private var mAccountId: String? = null
    private var mUri: Uri? = null

    fun setContact(path: ConversationPath) {
        mAccountId = path.accountId
        mUri = path.conversationUri
        mCompositeDisposable.clear()
        mCompositeDisposable.add(mConversationService
            .getAccountSubject(path.accountId)
            .map { a: Account -> SmartListViewModel(a.getByUri(mUri)!!, true) }
            .observeOn(mUiScheduler)
            .subscribe { c: SmartListViewModel -> view?.showContact(c) })
    }

    fun contactClicked() {
        val account = mAccountService.getAccount(mAccountId!!)
        if (account != null) {
            val conversation = account.getByUri(mUri)
            val conf = conversation!!.currentCall
            if (conf != null && conf.participants.isNotEmpty()
                && conf.participants[0].callStatus !== Call.CallStatus.INACTIVE && conf.participants[0].callStatus !== Call.CallStatus.FAILURE
            ) {
                view?.goToCallActivity(conf.id)
            } else {
                if (conversation.isSwarm) {
                    view?.callContact(mAccountId, mUri, conversation.contact!!.uri)
                } else {
                    view?.callContact(mAccountId, mUri, mUri)
                }
            }
        }
    }

    fun onAddContact() {
        mAccountId?.let { accountId -> mUri?.let { uri ->
            sendTrustRequest(accountId, uri)
        } }
        view?.switchToConversationView()
    }

    private fun sendTrustRequest(accountId: String, conversationUri: Uri) {
        val conversation = mAccountService.getAccount(accountId)!!.getByUri(conversationUri)
        mVCardService.loadSmallVCardWithDefault(accountId, VCardService.MAX_SIZE_REQUEST)
            .subscribe({ vCard: VCard ->
                mAccountService.sendTrustRequest(conversation!!, conversationUri, Blob.fromString(vcardToString(vCard))) })
            { mAccountService.sendTrustRequest(conversation!!, conversationUri, null) }
    }

    fun acceptTrustRequest() {
        mConversationService.acceptRequest(mAccountId!!, mUri!!)
        view?.switchToConversationView()
    }

    fun refuseTrustRequest() {
        mConversationService.discardRequest(mAccountId!!, mUri!!)
        view?.finishView()
    }

    fun blockTrustRequest() {
        mConversationService.discardRequest(mAccountId!!, mUri!!)
        mAccountService.removeContact(mAccountId!!, mUri!!.rawRingId, true)
        view?.finishView()
    }
}