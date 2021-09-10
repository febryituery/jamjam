/*
 *  Copyright (C) 2004-2021 Savoir-faire Linux Inc.
 *
 *  Authors: Adrien Béraud <adrien.beraud@savoirfairelinux.com>
 *           Romain Bertozzi <romain.bertozzi@savoirfairelinux.com>
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
 *   Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package cx.ring.fragments

import android.app.Activity
import android.app.SearchManager
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.RelativeLayout
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import cx.ring.R
import cx.ring.adapters.SmartListAdapter
import cx.ring.client.CallActivity
import cx.ring.client.HomeActivity
import cx.ring.databinding.FragSmartlistBinding
import cx.ring.mvp.BaseSupportFragment
import cx.ring.utils.ActionHelper
import cx.ring.utils.ClipboardHelper
import cx.ring.utils.ConversationPath
import cx.ring.utils.DeviceUtils
import cx.ring.viewholders.SmartListViewHolder.SmartListListeners
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.Conversation.ConversationActionCallback
import net.jami.model.Uri
import net.jami.smartlist.SmartListPresenter
import net.jami.smartlist.SmartListView
import net.jami.smartlist.SmartListViewModel

@AndroidEntryPoint
class SmartListFragment : BaseSupportFragment<SmartListPresenter, SmartListView>(),
    SearchView.OnQueryTextListener, SmartListListeners, ConversationActionCallback, SmartListView {
    private var mSmartListAdapter: SmartListAdapter? = null
    private var mSearchView: SearchView? = null
    private var mSearchMenuItem: MenuItem? = null
    private var mDialpadMenuItem: MenuItem? = null
    private var binding: FragSmartlistBinding? = null

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.clear()
        inflater.inflate(R.menu.smartlist_menu, menu)
        val searchMenuItem = menu.findItem(R.id.menu_contact_search)
        val dialpadMenuItem = menu.findItem(R.id.menu_contact_dial)
        searchMenuItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                dialpadMenuItem.isVisible = false
                binding!!.newconvFab.show()
                setOverflowMenuVisible(menu, true)
                changeSeparatorHeight(false)
                binding!!.qrCode.visibility = View.GONE
                //binding.newGroup.setVisibility(View.GONE);
                setTabletQRLayout(false)
                return true
            }

            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                dialpadMenuItem.isVisible = true
                binding!!.newconvFab.hide()
                setOverflowMenuVisible(menu, false)
                changeSeparatorHeight(true)
                binding!!.qrCode.visibility = View.VISIBLE
                //binding.newGroup.setVisibility(View.VISIBLE);
                setTabletQRLayout(true)
                return true
            }
        })
        val searchView = searchMenuItem.actionView as SearchView
        searchView.setOnQueryTextListener(this)
        searchView.queryHint = getString(R.string.searchbar_hint)
        searchView.layoutParams = Toolbar.LayoutParams(
            Toolbar.LayoutParams.WRAP_CONTENT,
            Toolbar.LayoutParams.MATCH_PARENT
        )
        searchView.imeOptions = EditorInfo.IME_ACTION_GO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val editText = searchView.findViewById<EditText>(R.id.search_src_text)
            editText?.setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        }
        mSearchMenuItem = searchMenuItem
        mDialpadMenuItem = dialpadMenuItem
        mSearchView = searchView
    }

    override fun onStart() {
        super.onStart()
        activity?.intent?.let { handleIntent(it) }
    }

    fun handleIntent(intent: Intent) {
        if (mSearchView != null && intent.action != null) {
            when (intent.action) {
                Intent.ACTION_VIEW, Intent.ACTION_CALL -> mSearchView!!.setQuery(intent.dataString, true)
                Intent.ACTION_DIAL -> {
                    mSearchMenuItem?.expandActionView()
                    mSearchView?.setQuery(intent.dataString, false)
                }
                Intent.ACTION_SEARCH -> {
                    mSearchMenuItem?.expandActionView()
                    mSearchView?.setQuery(intent.getStringExtra(SearchManager.QUERY), true)
                }
                else -> {}
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_contact_search -> {
                mSearchView!!.inputType = (EditorInfo.TYPE_CLASS_TEXT
                        or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        )
                return false
            }
            R.id.menu_contact_dial -> {
                if (mSearchView!!.inputType == EditorInfo.TYPE_CLASS_PHONE) {
                    mSearchView!!.inputType = EditorInfo.TYPE_CLASS_TEXT
                    mDialpadMenuItem!!.setIcon(R.drawable.baseline_dialpad_24)
                } else {
                    mSearchView!!.inputType = EditorInfo.TYPE_CLASS_PHONE
                    mDialpadMenuItem!!.setIcon(R.drawable.baseline_keyboard_24)
                }
                return true
            }
            R.id.menu_settings -> {
                (requireActivity() as HomeActivity).goToSettings()
                return true
            }
            R.id.menu_about -> {
                (requireActivity() as HomeActivity).goToAbout()
                return true
            }
            else -> return false
        }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        // presenter.newContactClicked();
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding?.apply { outState.putBoolean(STATE_LOADING, loadingIndicator.isShown) }
        super.onSaveInstanceState(outState)
    }

    override fun onQueryTextChange(query: String): Boolean {
        presenter.queryTextChanged(query)
        return true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setHasOptionsMenu(true)
        return FragSmartlistBinding.inflate(inflater, container, false).apply {
            qrCode.setOnClickListener { presenter.clickQRSearch() }
            newconvFab.setOnClickListener { presenter.fabButtonClicked() }
            confsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val canScrollUp = recyclerView.canScrollVertically(SCROLL_DIRECTION_UP)
                    val isExtended = newconvFab.isExtended
                    if (dy > 0 && isExtended) {
                        newconvFab.shrink()
                    } else if ((dy < 0 || !canScrollUp) && !isExtended) {
                        newconvFab.extend()
                    }
                    (activity as HomeActivity?)?.setToolbarElevation(canScrollUp)
                }
            })
            (confsList.itemAnimator as DefaultItemAnimator?)?.supportsChangeAnimations = false
            binding = this
        }.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun startNewGroup() {
        val fragment = ContactPickerFragment.newInstance()
        fragment.show(parentFragmentManager, ContactPickerFragment.TAG)
        binding!!.qrCode.visibility = View.GONE
        //binding.newGroup.setVisibility(View.GONE);
        setTabletQRLayout(false)
    }

    override fun setLoading(loading: Boolean) {
        binding!!.loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /**
     * Handles the visibility of some menus to hide / show the overflow menu
     *
     * @param menu    the menu containing the menuitems we need to access
     * @param visible true to display the overflow menu, false otherwise
     */
    private fun setOverflowMenuVisible(menu: Menu?, visible: Boolean) {
        menu?.findItem(R.id.menu_overflow)?.isVisible = visible
    }

    override fun removeConversation(conversationUri: Uri) {
        presenter.removeConversation(conversationUri)
    }

    override fun clearConversation(conversationUri: Uri) {
        presenter.clearConversation(conversationUri)
    }

    override fun copyContactNumberToClipboard(contactNumber: String) {
        ClipboardHelper.copyToClipboard(requireContext(), contactNumber)
        val snackbarText = getString(
            R.string.conversation_action_copied_peer_number_clipboard,
            ActionHelper.getShortenedNumber(contactNumber)
        )
        Snackbar.make(binding!!.listCoordinator, snackbarText, Snackbar.LENGTH_LONG).show()
    }

    override fun displayChooseNumberDialog(numbers: Array<CharSequence>) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.choose_number)
            .setItems(numbers) { _: DialogInterface?, which: Int ->
                val selected = numbers[which]
                val intent = Intent(CallActivity.ACTION_CALL)
                    .setClass(context, CallActivity::class.java)
                    .setData(android.net.Uri.parse(selected.toString()))
                startActivityForResult(intent, HomeActivity.REQUEST_CODE_CALL)
            }
            .show()
    }

    override fun displayNoConversationMessage() {
        binding!!.placeholder.visibility = View.VISIBLE
    }

    override fun hideNoConversationMessage() {
        binding!!.placeholder.visibility = View.GONE
    }

    override fun displayConversationDialog(smartListViewModel: SmartListViewModel) {
        if (smartListViewModel.isSwarm) {
            MaterialAlertDialogBuilder(requireContext())
                .setItems(R.array.swarm_actions) { dialog, which ->
                    when (which) {
                        0 -> presenter.copyNumber(smartListViewModel)
                        1 -> presenter.removeConversation(smartListViewModel)
                        2 -> presenter.banContact(smartListViewModel)
                    }
                }
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setItems(R.array.conversation_actions) { dialog, which ->
                    when (which) {
                        ActionHelper.ACTION_COPY -> presenter.copyNumber(smartListViewModel)
                        ActionHelper.ACTION_CLEAR -> presenter.clearConversation(smartListViewModel)
                        ActionHelper.ACTION_DELETE -> presenter.removeConversation(smartListViewModel)
                        ActionHelper.ACTION_BLOCK -> presenter.banContact(smartListViewModel)
                    }
                }
                .show()
        }
    }

    override fun displayClearDialog(conversationUri: Uri) {
        ActionHelper.launchClearAction(activity, conversationUri, this@SmartListFragment)
    }

    override fun displayDeleteDialog(conversationUri: Uri) {
        ActionHelper.launchDeleteAction(activity, conversationUri, this@SmartListFragment)
    }

    override fun copyNumber(uri: Uri) {
        ActionHelper.launchCopyNumberToClipboardFromContact(activity, uri, this)
    }

    override fun displayMenuItem() {
        mSearchMenuItem?.expandActionView()
    }

    override fun hideList() {
        binding!!.confsList.visibility = View.GONE
        mSmartListAdapter?.update(null)
    }

    override fun updateList(smartListViewModels: MutableList<SmartListViewModel>?, parentDisposable: CompositeDisposable) {
        binding?.apply {
            if (confsList.adapter == null) {
                confsList.adapter = SmartListAdapter(smartListViewModels, this@SmartListFragment, parentDisposable).apply {
                    mSmartListAdapter = this
                }
                confsList.setHasFixedSize(true)
                confsList.layoutManager = LinearLayoutManager(requireContext()).apply {
                    orientation = RecyclerView.VERTICAL
                }
            } else {
                mSmartListAdapter?.update(smartListViewModels)
            }
            confsList.visibility = View.VISIBLE
        }
    }

    override fun update(position: Int) {
        Log.w(TAG, "update $position $mSmartListAdapter")
        mSmartListAdapter?.notifyItemChanged(position)
    }

    override fun update(model: SmartListViewModel) {
        mSmartListAdapter?.update(model)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HomeActivity.REQUEST_CODE_QR_CONVERSATION && data != null && resultCode == Activity.RESULT_OK) {
            val contactId = data.getStringExtra(ConversationPath.KEY_CONVERSATION_URI)
            if (contactId != null) {
                presenter.startConversation(Uri.fromString(contactId))
            }
        }
    }

    override fun goToConversation(accountId: String, conversationUri: Uri) {
        Log.w(TAG, "goToConversation $accountId $conversationUri")
        mSearchMenuItem?.collapseActionView()
        (requireActivity() as HomeActivity).startConversation(accountId, conversationUri)
    }

    override fun goToCallActivity(accountId: String, conversationUri: Uri, contactId: String) {
        val intent = Intent(CallActivity.ACTION_CALL)
            .setClass(requireContext(), CallActivity::class.java)
            .putExtras(ConversationPath.toBundle(accountId, conversationUri))
            .putExtra(CallFragment.KEY_AUDIO_ONLY, false)
            .putExtra(Intent.EXTRA_PHONE_NUMBER, contactId)
        startActivityForResult(intent, HomeActivity.REQUEST_CODE_CALL)
    }

    override fun goToQRFragment() {
        val qrCodeFragment = QRCodeFragment.newInstance(QRCodeFragment.INDEX_SCAN)
        qrCodeFragment.show(parentFragmentManager, QRCodeFragment.TAG)
        binding!!.qrCode.visibility = View.GONE
        //binding.newGroup.setVisibility(View.GONE);
        setTabletQRLayout(false)
    }

    override fun scrollToTop() {
        binding?.apply { confsList.scrollToPosition(0) }
    }

    override fun onItemClick(item: SmartListViewModel) {
        presenter.conversationClicked(item)
    }

    override fun onItemLongClick(item: SmartListViewModel) {
        presenter.conversationLongClicked(item)
    }

    private fun changeSeparatorHeight(open: Boolean) {
        binding?.let { binding -> binding.separator?.let { separator ->
            if (DeviceUtils.isTablet(binding.root.context)) {
                val params = separator.layoutParams as RelativeLayout.LayoutParams
                params.topMargin = if (open) activity?.findViewById<Toolbar>(R.id.main_toolbar)?.height ?: 0 else 0
                separator.layoutParams = params
            }
        }}
    }

    private fun setTabletQRLayout(show: Boolean) {
        val context = requireContext()
        if (!DeviceUtils.isTablet(context)) return
        val params = binding!!.listCoordinator.layoutParams as RelativeLayout.LayoutParams
        if (show) {
            params.addRule(RelativeLayout.BELOW, R.id.qr_code)
            params.topMargin = 0
        } else {
            params.removeRule(RelativeLayout.BELOW)
            val value = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.actionBarSize, value, true)) {
                params.topMargin = TypedValue.complexToDimensionPixelSize(value.data, context.resources.displayMetrics)
            }
        }
        binding!!.listCoordinator.layoutParams = params
    }

    companion object {
        private val TAG = SmartListFragment::class.simpleName!!
        private val STATE_LOADING = "$TAG.STATE_LOADING"
        private const val SCROLL_DIRECTION_UP = -1
    }
}