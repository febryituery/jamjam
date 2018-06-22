/*
 *  Copyright (C) 2004-2018 Savoir-faire Linux Inc.
 *
 *  Authors:    Adrien Béraud <adrien.beraud@savoirfairelinux.com>
 *              Romain Bertozzi <romain.bertozzi@savoirfairelinux.com>
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
package cx.ring.client;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.KeyEvent;

import butterknife.BindView;
import butterknife.ButterKnife;
import cx.ring.R;
import cx.ring.application.RingApplication;
import cx.ring.fragments.ConversationFragment;
import cx.ring.utils.MediaButtonsHelper;

public class ConversationActivity extends AppCompatActivity {

    @BindView(R.id.main_toolbar)
    Toolbar mToolbar;

    private ConversationFragment mConversationFragment;
    private String contactUri = null;
    private String accountId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RingApplication.getInstance().startDaemon();

        setContentView(R.layout.activity_conversation);

        ButterKnife.bind(this);
        setSupportActionBar(mToolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        if (getIntent() != null || getIntent().getExtras() != null) {
            contactUri = getIntent().getStringExtra(ConversationFragment.KEY_CONTACT_RING_ID);
            accountId = getIntent().getStringExtra(ConversationFragment.KEY_ACCOUNT_ID);
        } else if (savedInstanceState != null) {
            contactUri = savedInstanceState.getString(ConversationFragment.KEY_CONTACT_RING_ID);
            accountId = savedInstanceState.getString(ConversationFragment.KEY_ACCOUNT_ID);
        }
        if (mConversationFragment == null) {
            Bundle bundle = new Bundle();
            bundle.putString(ConversationFragment.KEY_CONTACT_RING_ID, contactUri);
            bundle.putString(ConversationFragment.KEY_ACCOUNT_ID, accountId);

            mConversationFragment = new ConversationFragment();
            mConversationFragment.setArguments(bundle);
            getFragmentManager().beginTransaction()
                    .replace(R.id.main_frame, mConversationFragment, null)
                    .commit();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(ConversationFragment.KEY_CONTACT_RING_ID, contactUri);
        outState.putString(ConversationFragment.KEY_ACCOUNT_ID, accountId);
        super.onSaveInstanceState(outState);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return MediaButtonsHelper.handleMediaKeyCode(keyCode, mConversationFragment)
                || super.onKeyDown(keyCode, event);
    }
}
