/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.applications;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.usb.IUsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.PreferenceFragment;
import android.util.Log;

import com.android.settings.SettingsActivity;
import com.android.settings.applications.ApplicationsState.AppEntry;

import java.util.ArrayList;

public abstract class AppInfoBase extends PreferenceFragment
        implements ApplicationsState.Callbacks {

    public static final String ARG_PACKAGE_NAME = "package";

    protected static final String TAG = AppInfoBase.class.getSimpleName();
    protected static final boolean localLOGV = false;

    protected boolean mAppControlRestricted = false;

    protected ApplicationsState mState;
    private ApplicationsState.Session mSession;
    protected ApplicationsState.AppEntry mAppEntry;
    protected PackageInfo mPackageInfo;
    protected int mUserId;
    protected String mPackageName;

    protected IUsbManager mUsbManager;
    protected DevicePolicyManager mDpm;
    protected UserManager mUserManager;
    protected PackageManager mPm;

    // Dialog identifiers used in showDialog
    protected static final int DLG_BASE = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mState = ApplicationsState.getInstance(getActivity().getApplication());
        mSession = mState.newSession(this);
        Context context = getActivity();
        mDpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mPm = context.getPackageManager();
        IBinder b = ServiceManager.getService(Context.USB_SERVICE);
        mUsbManager = IUsbManager.Stub.asInterface(b);

        // Need to make sure we have loaded applications at this point.
        mSession.resume();

        retrieveAppEntry();
    }

    @Override
    public void onResume() {
        super.onResume();
        mAppControlRestricted = mUserManager.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL);
        mSession.resume();

        if (!refreshUi()) {
            setIntentAndFinish(true, true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mSession.pause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSession.release();
    }

    protected String retrieveAppEntry() {
        final Bundle args = getArguments();
        mPackageName = (args != null) ? args.getString(ARG_PACKAGE_NAME) : null;
        if (mPackageName == null) {
            Intent intent = (args == null) ?
                    getActivity().getIntent() : (Intent) args.getParcelable("intent");
            if (intent != null) {
                mPackageName = intent.getData().getSchemeSpecificPart();
            }
        }
        mUserId = UserHandle.myUserId();
        mAppEntry = mState.getEntry(mPackageName, mUserId);
        if (mAppEntry != null) {
            // Get application info again to refresh changed properties of application
            try {
                mPackageInfo = mPm.getPackageInfo(mAppEntry.info.packageName,
                        PackageManager.GET_DISABLED_COMPONENTS |
                        PackageManager.GET_UNINSTALLED_PACKAGES |
                        PackageManager.GET_SIGNATURES);
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Exception when retrieving package:" + mAppEntry.info.packageName, e);
            }
        } else {
            Log.w(TAG, "Missing AppEntry; maybe reinstalling?");
            mPackageInfo = null;
        }

        return mPackageName;
    }

    protected void setIntentAndFinish(boolean finish, boolean appChanged) {
        if (localLOGV) Log.i(TAG, "appChanged="+appChanged);
        Intent intent = new Intent();
        intent.putExtra(ManageApplications.APP_CHG, appChanged);
        SettingsActivity sa = (SettingsActivity)getActivity();
        sa.finishPreferencePanel(this, Activity.RESULT_OK, intent);
    }

    protected void showDialogInner(int id, int moveErrorCode) {
        DialogFragment newFragment = new MyAlertDialogFragment(id, moveErrorCode);
        newFragment.setTargetFragment(this, 0);
        newFragment.show(getFragmentManager(), "dialog " + id);
    }

    protected abstract boolean refreshUi();
    protected abstract AlertDialog createDialog(int id, int errorCode);

    @Override
    public void onRunningStateChanged(boolean running) {
        // No op.
    }

    @Override
    public void onRebuildComplete(ArrayList<AppEntry> apps) {
        // No op.
    }

    @Override
    public void onPackageIconChanged() {
        // No op.
    }

    @Override
    public void onPackageSizeChanged(String packageName) {
        // No op.
    }

    @Override
    public void onAllSizesComputed() {
        // No op.
    }

    @Override
    public void onLauncherInfoChanged() {
        // No op.
    }

    @Override
    public void onPackageListChanged() {
        refreshUi();
    }

    public class MyAlertDialogFragment extends DialogFragment {
        public MyAlertDialogFragment(int id, int errorCode) {
            Bundle args = new Bundle();
            args.putInt("id", id);
            args.putInt("moveError", errorCode);
            setArguments(args);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            int id = getArguments().getInt("id");
            int errorCode = getArguments().getInt("moveError");
            Dialog dialog = createDialog(id, errorCode);
            if (dialog == null) {
                throw new IllegalArgumentException("unknown id " + id);
            }
            return dialog;
        }
    }
}
