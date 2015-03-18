package com.android.settings.applications;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.format.Formatter;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.text.Collator;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Keeps track of information about all installed applications, lazy-loading
 * as needed.
 */
public class ApplicationsState {
    static final String TAG = "ApplicationsState";
    static final boolean DEBUG = false;
    static final boolean DEBUG_LOCKING = false;

    public static interface Callbacks {
        public void onRunningStateChanged(boolean running);
        public void onPackageListChanged();
        public void onRebuildComplete(ArrayList<AppEntry> apps);
        public void onPackageIconChanged();
        public void onPackageSizeChanged(String packageName);
        public void onAllSizesComputed();
        public void onLauncherInfoChanged();
    }

    public static interface AppFilter {
        public void init();
        public boolean filterApp(AppEntry info);
    }

    static final int SIZE_UNKNOWN = -1;
    static final int SIZE_INVALID = -2;

    static final Pattern REMOVE_DIACRITICALS_PATTERN
            = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    public static String normalize(String str) {
        String tmp = Normalizer.normalize(str, Form.NFD);
        return REMOVE_DIACRITICALS_PATTERN.matcher(tmp)
                .replaceAll("").toLowerCase();
    }

    public static class SizeInfo {
        long cacheSize;
        long codeSize;
        long dataSize;
        long externalCodeSize;
        long externalDataSize;

        // This is the part of externalDataSize that is in the cache
        // section of external storage.  Note that we don't just combine
        // this with cacheSize because currently the platform can't
        // automatically trim this data when needed, so it is something
        // the user may need to manage.  The externalDataSize also includes
        // this value, since what this is here is really the part of
        // externalDataSize that we can just consider to be "cache" files
        // for purposes of cleaning them up in the app details UI.
        long externalCacheSize;
    }

    public static class AppEntry extends SizeInfo {
        final File apkFile;
        final long id;
        String label;
        long size;
        long internalSize;
        long externalSize;

        boolean mounted;

        boolean hasLauncherEntry;

        String getNormalizedLabel() {
            if (normalizedLabel != null) {
                return normalizedLabel;
            }
            normalizedLabel = normalize(label);
            return normalizedLabel;
        }

        // Need to synchronize on 'this' for the following.
        ApplicationInfo info;
        Drawable icon;
        String sizeStr;
        String internalSizeStr;
        String externalSizeStr;
        boolean sizeStale;
        long sizeLoadStart;

        String normalizedLabel;

        AppEntry(Context context, ApplicationInfo info, long id) {
            apkFile = new File(info.sourceDir);
            this.id = id;
            this.info = info;
            this.size = SIZE_UNKNOWN;
            this.sizeStale = true;
            ensureLabel(context);
        }

        void ensureLabel(Context context) {
            if (this.label == null || !this.mounted) {
                if (!this.apkFile.exists()) {
                    this.mounted = false;
                    this.label = info.packageName;
                } else {
                    this.mounted = true;
                    CharSequence label = info.loadLabel(context.getPackageManager());
                    this.label = label != null ? label.toString() : info.packageName;
                }
            }
        }

        boolean ensureIconLocked(Context context, PackageManager pm) {
            if (this.icon == null) {
                if (this.apkFile.exists()) {
                    this.icon = getBadgedIcon(pm);
                    return true;
                } else {
                    this.mounted = false;
                    this.icon = context.getDrawable(
                            com.android.internal.R.drawable.sym_app_on_sd_unavailable_icon);
                }
            } else if (!this.mounted) {
                // If the app wasn't mounted but is now mounted, reload
                // its icon.
                if (this.apkFile.exists()) {
                    this.mounted = true;
                    this.icon = getBadgedIcon(pm);
                    return true;
                }
            }
            return false;
        }

        private Drawable getBadgedIcon(PackageManager pm) {
            // Do badging ourself so that it comes from the user of the app not the current user.
            return pm.getUserBadgedIcon(pm.loadUnbadgedItemIcon(info, info),
                    new UserHandle(UserHandle.getUserId(info.uid)));
        }
    }

    public static final Comparator<AppEntry> ALPHA_COMPARATOR = new Comparator<AppEntry>() {
        private final Collator sCollator = Collator.getInstance();
        @Override
        public int compare(AppEntry object1, AppEntry object2) {
            return sCollator.compare(object1.label, object2.label);
        }
    };

    public static final Comparator<AppEntry> SIZE_COMPARATOR
            = new Comparator<AppEntry>() {
        private final Collator sCollator = Collator.getInstance();
        @Override
        public int compare(AppEntry object1, AppEntry object2) {
            if (object1.size < object2.size) return 1;
            if (object1.size > object2.size) return -1;
            return sCollator.compare(object1.label, object2.label);
        }
    };

    public static final Comparator<AppEntry> INTERNAL_SIZE_COMPARATOR
            = new Comparator<AppEntry>() {
        private final Collator sCollator = Collator.getInstance();
        @Override
        public int compare(AppEntry object1, AppEntry object2) {
            if (object1.internalSize < object2.internalSize) return 1;
            if (object1.internalSize > object2.internalSize) return -1;
            return sCollator.compare(object1.label, object2.label);
        }
    };

    public static final Comparator<AppEntry> EXTERNAL_SIZE_COMPARATOR
            = new Comparator<AppEntry>() {
        private final Collator sCollator = Collator.getInstance();
        @Override
        public int compare(AppEntry object1, AppEntry object2) {
            if (object1.externalSize < object2.externalSize) return 1;
            if (object1.externalSize > object2.externalSize) return -1;
            return sCollator.compare(object1.label, object2.label);
        }
    };

    public static final AppFilter FILTER_PERSONAL = new AppFilter() {
        private int mCurrentUser;

        public void init() {
            mCurrentUser = ActivityManager.getCurrentUser();
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return UserHandle.getUserId(entry.info.uid) == mCurrentUser;
        }
    };

    public static final AppFilter FILTER_WORK = new AppFilter() {
        private int mCurrentUser;

        public void init() {
            mCurrentUser = ActivityManager.getCurrentUser();
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return UserHandle.getUserId(entry.info.uid) != mCurrentUser;
        }
    };

    public static final AppFilter FILTER_DOWNLOADED_AND_LAUNCHER = new AppFilter() {
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            if ((entry.info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return true;
            } else if ((entry.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return true;
            } else if (entry.hasLauncherEntry) {
                return true;
            }
            return false;
        }
    };

    public static final AppFilter FILTER_THIRD_PARTY = new AppFilter() {
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            if ((entry.info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return true;
            } else if ((entry.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return true;
            }
            return false;
        }
    };

    public static final AppFilter FILTER_ON_SD_CARD = new AppFilter() {
        final CanBeOnSdCardChecker mCanBeOnSdCardChecker
                = new CanBeOnSdCardChecker();

        public void init() {
            mCanBeOnSdCardChecker.init();
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return mCanBeOnSdCardChecker.check(entry.info);
        }
    };

    public static final AppFilter FILTER_DISABLED = new AppFilter() {
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return !entry.info.enabled;
        }
    };

    public static final AppFilter FILTER_ALL_ENABLED = new AppFilter() {
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return entry.info.enabled;
        }
    };

    public static final AppFilter FILTER_EVERYTHING = new AppFilter() {
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return true;
        }
    };

    final Context mContext;
    final PackageManager mPm;
    final IPackageManager mIpm;
    final UserManager mUm;
    final int mRetrieveFlags;
    PackageIntentReceiver mPackageIntentReceiver;

    boolean mResumed;
    boolean mHaveDisabledApps;

    // Information about all applications.  Synchronize on mEntriesMap
    // to protect access to these.
    final ArrayList<Session> mSessions = new ArrayList<Session>();
    final ArrayList<Session> mRebuildingSessions = new ArrayList<Session>();
    final InterestingConfigChanges mInterestingConfigChanges = new InterestingConfigChanges();
    // Map: userid => (Map: package name => AppEntry)
    final SparseArray<HashMap<String, AppEntry>> mEntriesMap =
            new SparseArray<HashMap<String, AppEntry>>();
    final ArrayList<AppEntry> mAppEntries = new ArrayList<AppEntry>();
    List<ApplicationInfo> mApplications = new ArrayList<ApplicationInfo>();
    long mCurId = 1;
    String mCurComputingSizePkg;
    int mCurComputingSizeUserId;
    boolean mSessionsChanged;

    // Temporary for dispatching session callbacks.  Only touched by main thread.
    final ArrayList<Session> mActiveSessions = new ArrayList<Session>();

    /**
     * Receives notifications when applications are added/removed.
     */
    private class PackageIntentReceiver extends BroadcastReceiver {
         void registerReceiver() {
             IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
             filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
             filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
             filter.addDataScheme("package");
             mContext.registerReceiver(this, filter);
             // Register for events related to sdcard installation.
             IntentFilter sdFilter = new IntentFilter();
             sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
             sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
             mContext.registerReceiver(this, sdFilter);
             // Register for events related to user creation/deletion.
             IntentFilter userFilter = new IntentFilter();
             userFilter.addAction(Intent.ACTION_USER_ADDED);
             userFilter.addAction(Intent.ACTION_USER_REMOVED);
             mContext.registerReceiver(this, userFilter);
         }
         void unregisterReceiver() {
             mContext.unregisterReceiver(this);
         }
         @Override
         public void onReceive(Context context, Intent intent) {
             String actionStr = intent.getAction();
             if (Intent.ACTION_PACKAGE_ADDED.equals(actionStr)) {
                 Uri data = intent.getData();
                 String pkgName = data.getEncodedSchemeSpecificPart();
                 for (int i = 0; i < mEntriesMap.size(); i++) {
                     addPackage(pkgName, mEntriesMap.keyAt(i));
                 }
             } else if (Intent.ACTION_PACKAGE_REMOVED.equals(actionStr)) {
                 Uri data = intent.getData();
                 String pkgName = data.getEncodedSchemeSpecificPart();
                 for (int i = 0; i < mEntriesMap.size(); i++) {
                     removePackage(pkgName, mEntriesMap.keyAt(i));
                 }
             } else if (Intent.ACTION_PACKAGE_CHANGED.equals(actionStr)) {
                 Uri data = intent.getData();
                 String pkgName = data.getEncodedSchemeSpecificPart();
                 for (int i = 0; i < mEntriesMap.size(); i++) {
                     invalidatePackage(pkgName, mEntriesMap.keyAt(i));
                 }
             } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(actionStr) ||
                     Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(actionStr)) {
                 // When applications become available or unavailable (perhaps because
                 // the SD card was inserted or ejected) we need to refresh the
                 // AppInfo with new label, icon and size information as appropriate
                 // given the newfound (un)availability of the application.
                 // A simple way to do that is to treat the refresh as a package
                 // removal followed by a package addition.
                 String pkgList[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                 if (pkgList == null || pkgList.length == 0) {
                     // Ignore
                     return;
                 }
                 boolean avail = Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(actionStr);
                 if (avail) {
                     for (String pkgName : pkgList) {
                         for (int i = 0; i < mEntriesMap.size(); i++) {
                             invalidatePackage(pkgName, mEntriesMap.keyAt(i));
                         }
                     }
                 }
             } else if (Intent.ACTION_USER_ADDED.equals(actionStr)) {
                 addUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL));
             } else if (Intent.ACTION_USER_REMOVED.equals(actionStr)) {
                 removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL));
             }
         }
    }

    void rebuildActiveSessions() {
        synchronized (mEntriesMap) {
            if (!mSessionsChanged) {
                return;
            }
            mActiveSessions.clear();
            for (int i=0; i<mSessions.size(); i++) {
                Session s = mSessions.get(i);
                if (s.mResumed) {
                    mActiveSessions.add(s);
                }
            }
        }
    }

    class MainHandler extends Handler {
        static final int MSG_REBUILD_COMPLETE = 1;
        static final int MSG_PACKAGE_LIST_CHANGED = 2;
        static final int MSG_PACKAGE_ICON_CHANGED = 3;
        static final int MSG_PACKAGE_SIZE_CHANGED = 4;
        static final int MSG_ALL_SIZES_COMPUTED = 5;
        static final int MSG_RUNNING_STATE_CHANGED = 6;
        static final int MSG_LAUNCHER_INFO_CHANGED = 7;

        @Override
        public void handleMessage(Message msg) {
            rebuildActiveSessions();
            switch (msg.what) {
                case MSG_REBUILD_COMPLETE: {
                    Session s = (Session)msg.obj;
                    if (mActiveSessions.contains(s)) {
                        s.mCallbacks.onRebuildComplete(s.mLastAppList);
                    }
                } break;
                case MSG_PACKAGE_LIST_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onPackageListChanged();
                    }
                } break;
                case MSG_PACKAGE_ICON_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onPackageIconChanged();
                    }
                } break;
                case MSG_PACKAGE_SIZE_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onPackageSizeChanged(
                                (String)msg.obj);
                    }
                } break;
                case MSG_ALL_SIZES_COMPUTED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onAllSizesComputed();
                    }
                } break;
                case MSG_RUNNING_STATE_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onRunningStateChanged(
                                msg.arg1 != 0);
                    }
                } break;
                case MSG_LAUNCHER_INFO_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onLauncherInfoChanged();
                    }
                } break;
            }
        }
    }

    final MainHandler mMainHandler = new MainHandler();

    // --------------------------------------------------------------

    static final Object sLock = new Object();
    static ApplicationsState sInstance;

    static ApplicationsState getInstance(Application app) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new ApplicationsState(app);
            }
            return sInstance;
        }
    }

    private ApplicationsState(Application app) {
        mContext = app;
        mPm = mContext.getPackageManager();
        mIpm = AppGlobals.getPackageManager();
        mUm = (UserManager) app.getSystemService(Context.USER_SERVICE);
        for (UserHandle user : mUm.getUserProfiles()) {
            mEntriesMap.put(user.getIdentifier(), new HashMap<String, AppEntry>());
        }
        mThread = new HandlerThread("ApplicationsState.Loader",
                Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        mBackgroundHandler = new BackgroundHandler(mThread.getLooper());

        // Only the owner can see all apps.
        if (UserHandle.myUserId() == 0) {
            mRetrieveFlags = PackageManager.GET_UNINSTALLED_PACKAGES |
                    PackageManager.GET_DISABLED_COMPONENTS |
                    PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS;
        } else {
            mRetrieveFlags = PackageManager.GET_DISABLED_COMPONENTS |
                    PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS;
        }

        /**
         * This is a trick to prevent the foreground thread from being delayed.
         * The problem is that Dalvik monitors are initially spin locks, to keep
         * them lightweight.  This leads to unfair contention -- Even though the
         * background thread only holds the lock for a short amount of time, if
         * it keeps running and locking again it can prevent the main thread from
         * acquiring its lock for a long time...  sometimes even > 5 seconds
         * (leading to an ANR).
         *
         * Dalvik will promote a monitor to a "real" lock if it detects enough
         * contention on it.  It doesn't figure this out fast enough for us
         * here, though, so this little trick will force it to turn into a real
         * lock immediately.
         */
        synchronized (mEntriesMap) {
            try {
                mEntriesMap.wait(1);
            } catch (InterruptedException e) {
            }
        }
    }

    public class Session {
        final Callbacks mCallbacks;
        boolean mResumed;

        // Rebuilding of app list.  Synchronized on mRebuildSync.
        final Object mRebuildSync = new Object();
        boolean mRebuildRequested;
        boolean mRebuildAsync;
        AppFilter mRebuildFilter;
        Comparator<AppEntry> mRebuildComparator;
        ArrayList<AppEntry> mRebuildResult;
        ArrayList<AppEntry> mLastAppList;

        Session(Callbacks callbacks) {
            mCallbacks = callbacks;
        }

        public void resume() {
            if (DEBUG_LOCKING) Log.v(TAG, "resume about to acquire lock...");
            synchronized (mEntriesMap) {
                if (!mResumed) {
                    mResumed = true;
                    mSessionsChanged = true;
                    doResumeIfNeededLocked();
                }
            }
            if (DEBUG_LOCKING) Log.v(TAG, "...resume releasing lock");
        }

        public void pause() {
            if (DEBUG_LOCKING) Log.v(TAG, "pause about to acquire lock...");
            synchronized (mEntriesMap) {
                if (mResumed) {
                    mResumed = false;
                    mSessionsChanged = true;
                    mBackgroundHandler.removeMessages(BackgroundHandler.MSG_REBUILD_LIST, this);
                    doPauseIfNeededLocked();
                }
                if (DEBUG_LOCKING) Log.v(TAG, "...pause releasing lock");
            }
        }

        ArrayList<AppEntry> getAllApps() {
            synchronized (mEntriesMap) {
                return new ArrayList<>(mAppEntries);
            }
        }

        // Creates a new list of app entries with the given filter and comparator.
        ArrayList<AppEntry> rebuild(AppFilter filter, Comparator<AppEntry> comparator) {
            synchronized (mRebuildSync) {
                synchronized (mEntriesMap) {
                    mRebuildingSessions.add(this);
                    mRebuildRequested = true;
                    mRebuildAsync = false;
                    mRebuildFilter = filter;
                    mRebuildComparator = comparator;
                    mRebuildResult = null;
                    if (!mBackgroundHandler.hasMessages(BackgroundHandler.MSG_REBUILD_LIST)) {
                        Message msg = mBackgroundHandler.obtainMessage(
                                BackgroundHandler.MSG_REBUILD_LIST);
                        mBackgroundHandler.sendMessage(msg);
                    }
                }

                // We will wait for .25s for the list to be built.
                long waitend = SystemClock.uptimeMillis()+250;

                while (mRebuildResult == null) {
                    long now = SystemClock.uptimeMillis();
                    if (now >= waitend) {
                        break;
                    }
                    try {
                        mRebuildSync.wait(waitend - now);
                    } catch (InterruptedException e) {
                    }
                }

                mRebuildAsync = true;

                return mRebuildResult;
            }
        }

        void handleRebuildList() {
            AppFilter filter;
            Comparator<AppEntry> comparator;
            synchronized (mRebuildSync) {
                if (!mRebuildRequested) {
                    return;
                }

                filter = mRebuildFilter;
                comparator = mRebuildComparator;
                mRebuildRequested = false;
                mRebuildFilter = null;
                mRebuildComparator = null;
            }

            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

            if (filter != null) {
                filter.init();
            }

            List<AppEntry> apps;
            synchronized (mEntriesMap) {
                apps = new ArrayList<>(mAppEntries);
            }

            ArrayList<AppEntry> filteredApps = new ArrayList<AppEntry>();
            if (DEBUG) Log.i(TAG, "Rebuilding...");
            for (int i=0; i<apps.size(); i++) {
                AppEntry entry = apps.get(i);
                if (filter == null || filter.filterApp(entry)) {
                    synchronized (mEntriesMap) {
                        if (DEBUG_LOCKING) Log.v(TAG, "rebuild acquired lock");
                        entry.ensureLabel(mContext);
                        if (DEBUG) Log.i(TAG, "Using " + entry.info.packageName + ": " + entry);
                        filteredApps.add(entry);
                        if (DEBUG_LOCKING) Log.v(TAG, "rebuild releasing lock");
                    }
                }
            }

            Collections.sort(filteredApps, comparator);

            synchronized (mRebuildSync) {
                if (!mRebuildRequested) {
                    mLastAppList = filteredApps;
                    if (!mRebuildAsync) {
                        mRebuildResult = filteredApps;
                        mRebuildSync.notifyAll();
                    } else {
                        if (!mMainHandler.hasMessages(MainHandler.MSG_REBUILD_COMPLETE, this)) {
                            Message msg = mMainHandler.obtainMessage(
                                    MainHandler.MSG_REBUILD_COMPLETE, this);
                            mMainHandler.sendMessage(msg);
                        }
                    }
                }
            }

            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }

        public void release() {
            pause();
            synchronized (mEntriesMap) {
                mSessions.remove(this);
            }
        }
    }

    public Session newSession(Callbacks callbacks) {
        Session s = new Session(callbacks);
        synchronized (mEntriesMap) {
            mSessions.add(s);
        }
        return s;
    }

    void doResumeIfNeededLocked() {
        if (mResumed) {
            return;
        }
        mResumed = true;
        if (mPackageIntentReceiver == null) {
            mPackageIntentReceiver = new PackageIntentReceiver();
            mPackageIntentReceiver.registerReceiver();
        }
        mApplications = new ArrayList<ApplicationInfo>();
        for (UserHandle user : mUm.getUserProfiles()) {
            try {
                ParceledListSlice<ApplicationInfo> list =
                        mIpm.getInstalledApplications(mRetrieveFlags, user.getIdentifier());
                mApplications.addAll(list.getList());
            } catch (RemoteException e) {
            }
        }

        if (mInterestingConfigChanges.applyNewConfig(mContext.getResources())) {
            // If an interesting part of the configuration has changed, we
            // should completely reload the app entries.
            for (int i = 0; i < mEntriesMap.size(); i++) {
                mEntriesMap.valueAt(i).clear();
            }
            mAppEntries.clear();
        } else {
            for (int i=0; i<mAppEntries.size(); i++) {
                mAppEntries.get(i).sizeStale = true;
            }
        }

        mHaveDisabledApps = false;
        for (int i=0; i<mApplications.size(); i++) {
            final ApplicationInfo info = mApplications.get(i);
            // Need to trim out any applications that are disabled by
            // something different than the user.
            if (!info.enabled) {
                if (info.enabledSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    mApplications.remove(i);
                    i--;
                    continue;
                }
                mHaveDisabledApps = true;
            }
            int userId = UserHandle.getUserId(info.uid);
            final AppEntry entry = mEntriesMap.get(userId).get(info.packageName);
            if (entry != null) {
                entry.info = info;
            }
        }
        mCurComputingSizePkg = null;
        if (!mBackgroundHandler.hasMessages(BackgroundHandler.MSG_LOAD_ENTRIES)) {
            mBackgroundHandler.sendEmptyMessage(BackgroundHandler.MSG_LOAD_ENTRIES);
        }
    }

    public boolean haveDisabledApps() {
        return mHaveDisabledApps;
    }

    void doPauseIfNeededLocked() {
        if (!mResumed) {
            return;
        }
        for (int i=0; i<mSessions.size(); i++) {
            if (mSessions.get(i).mResumed) {
                return;
            }
        }
        doPauseLocked();
    }

    void doPauseLocked() {
        mResumed = false;
        if (mPackageIntentReceiver != null) {
            mPackageIntentReceiver.unregisterReceiver();
            mPackageIntentReceiver = null;
        }
    }

    AppEntry getEntry(String packageName, int userId) {
        if (DEBUG_LOCKING) Log.v(TAG, "getEntry about to acquire lock...");
        synchronized (mEntriesMap) {
            AppEntry entry = mEntriesMap.get(userId).get(packageName);
            if (entry == null) {
                for (int i=0; i<mApplications.size(); i++) {
                    ApplicationInfo info = mApplications.get(i);
                    if (packageName.equals(info.packageName)) {
                        entry = getEntryLocked(info);
                        break;
                    }
                }
            }
            if (DEBUG_LOCKING) Log.v(TAG, "...getEntry releasing lock");
            return entry;
        }
    }

    void ensureIcon(AppEntry entry) {
        if (entry.icon != null) {
            return;
        }
        synchronized (entry) {
            entry.ensureIconLocked(mContext, mPm);
        }
    }

    void requestSize(String packageName, int userId) {
        if (DEBUG_LOCKING) Log.v(TAG, "requestSize about to acquire lock...");
        synchronized (mEntriesMap) {
            AppEntry entry = mEntriesMap.get(userId).get(packageName);
            if (entry != null) {
                mPm.getPackageSizeInfo(packageName, userId, mBackgroundHandler.mStatsObserver);
            }
            if (DEBUG_LOCKING) Log.v(TAG, "...requestSize releasing lock");
        }
    }

    long sumCacheSizes() {
        long sum = 0;
        if (DEBUG_LOCKING) Log.v(TAG, "sumCacheSizes about to acquire lock...");
        synchronized (mEntriesMap) {
            if (DEBUG_LOCKING) Log.v(TAG, "-> sumCacheSizes now has lock");
            for (int i=mAppEntries.size()-1; i>=0; i--) {
                sum += mAppEntries.get(i).cacheSize;
            }
            if (DEBUG_LOCKING) Log.v(TAG, "...sumCacheSizes releasing lock");
        }
        return sum;
    }

    int indexOfApplicationInfoLocked(String pkgName, int userId) {
        for (int i=mApplications.size()-1; i>=0; i--) {
            ApplicationInfo appInfo = mApplications.get(i);
            if (appInfo.packageName.equals(pkgName)
                    && UserHandle.getUserId(appInfo.uid) == userId) {
                return i;
            }
        }
        return -1;
    }

    void addPackage(String pkgName, int userId) {
        try {
            synchronized (mEntriesMap) {
                if (DEBUG_LOCKING) Log.v(TAG, "addPackage acquired lock");
                if (DEBUG) Log.i(TAG, "Adding package " + pkgName);
                if (!mResumed) {
                    // If we are not resumed, we will do a full query the
                    // next time we resume, so there is no reason to do work
                    // here.
                    if (DEBUG_LOCKING) Log.v(TAG, "addPackage release lock: not resumed");
                    return;
                }
                if (indexOfApplicationInfoLocked(pkgName, userId) >= 0) {
                    if (DEBUG) Log.i(TAG, "Package already exists!");
                    if (DEBUG_LOCKING) Log.v(TAG, "addPackage release lock: already exists");
                    return;
                }
                ApplicationInfo info = mIpm.getApplicationInfo(pkgName, mRetrieveFlags, userId);
                if (info == null) {
                    return;
                }
                if (!info.enabled) {
                    if (info.enabledSetting
                            != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                        return;
                    }
                    mHaveDisabledApps = true;
                }
                mApplications.add(info);
                if (!mBackgroundHandler.hasMessages(BackgroundHandler.MSG_LOAD_ENTRIES)) {
                    mBackgroundHandler.sendEmptyMessage(BackgroundHandler.MSG_LOAD_ENTRIES);
                }
                if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_LIST_CHANGED)) {
                    mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_LIST_CHANGED);
                }
                if (DEBUG_LOCKING) Log.v(TAG, "addPackage releasing lock");
            }
        } catch (RemoteException e) {
        }
    }

    void removePackage(String pkgName, int userId) {
        synchronized (mEntriesMap) {
            if (DEBUG_LOCKING) Log.v(TAG, "removePackage acquired lock");
            int idx = indexOfApplicationInfoLocked(pkgName, userId);
            if (DEBUG) Log.i(TAG, "removePackage: " + pkgName + " @ " + idx);
            if (idx >= 0) {
                AppEntry entry = mEntriesMap.get(userId).get(pkgName);
                if (DEBUG) Log.i(TAG, "removePackage: " + entry);
                if (entry != null) {
                    mEntriesMap.get(userId).remove(pkgName);
                    mAppEntries.remove(entry);
                }
                ApplicationInfo info = mApplications.get(idx);
                mApplications.remove(idx);
                if (!info.enabled) {
                    mHaveDisabledApps = false;
                    for (int i=0; i<mApplications.size(); i++) {
                        if (!mApplications.get(i).enabled) {
                            mHaveDisabledApps = true;
                            break;
                        }
                    }
                }
                if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_LIST_CHANGED)) {
                    mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_LIST_CHANGED);
                }
            }
            if (DEBUG_LOCKING) Log.v(TAG, "removePackage releasing lock");
        }
    }

    void invalidatePackage(String pkgName, int userId) {
        removePackage(pkgName, userId);
        addPackage(pkgName, userId);
    }

    void addUser(int userId) {
        if (mUm.getUserProfiles().contains(new UserHandle(userId))) {
            synchronized (mEntriesMap) {
                mEntriesMap.put(userId, new HashMap<String, AppEntry>());
                if (mResumed) {
                    // If resumed, Manually pause, then cause a resume to repopulate the app list.
                    // This is the simplest way to reload the packages so that the new user
                    // is included.  Otherwise the list will be repopulated on next resume.
                    doPauseLocked();
                    doResumeIfNeededLocked();
                }
                if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_LIST_CHANGED)) {
                    mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_LIST_CHANGED);
                }
            }
        }
    }

    void removeUser(int userId) {
        synchronized (mEntriesMap) {
            HashMap<String, AppEntry> userMap = mEntriesMap.get(userId);
            if (userMap != null) {
                for (AppEntry appEntry : userMap.values()) {
                    mAppEntries.remove(appEntry);
                    mApplications.remove(appEntry.info);
                }
                mEntriesMap.remove(userId);
                if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_LIST_CHANGED)) {
                    mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_LIST_CHANGED);
                }
            }
        }
    }

    AppEntry getEntryLocked(ApplicationInfo info) {
        int userId = UserHandle.getUserId(info.uid);
        AppEntry entry = mEntriesMap.get(userId).get(info.packageName);
        if (DEBUG) Log.i(TAG, "Looking up entry of pkg " + info.packageName + ": " + entry);
        if (entry == null) {
            if (DEBUG) Log.i(TAG, "Creating AppEntry for " + info.packageName);
            entry = new AppEntry(mContext, info, mCurId++);
            mEntriesMap.get(userId).put(info.packageName, entry);
            mAppEntries.add(entry);
        } else if (entry.info != info) {
            entry.info = info;
        }
        return entry;
    }

    // --------------------------------------------------------------

    private long getTotalInternalSize(PackageStats ps) {
        if (ps != null) {
            return ps.codeSize + ps.dataSize;
        }
        return SIZE_INVALID;
    }

    private long getTotalExternalSize(PackageStats ps) {
        if (ps != null) {
            // We also include the cache size here because for non-emulated
            // we don't automtically clean cache files.
            return ps.externalCodeSize + ps.externalDataSize
                    + ps.externalCacheSize
                    + ps.externalMediaSize + ps.externalObbSize;
        }
        return SIZE_INVALID;
    }

    private String getSizeStr(long size) {
        if (size >= 0) {
            return Formatter.formatFileSize(mContext, size);
        }
        return null;
    }

    final HandlerThread mThread;
    final BackgroundHandler mBackgroundHandler;
    class BackgroundHandler extends Handler {
        static final int MSG_REBUILD_LIST = 1;
        static final int MSG_LOAD_ENTRIES = 2;
        static final int MSG_LOAD_ICONS = 3;
        static final int MSG_LOAD_SIZES = 4;
        static final int MSG_LOAD_LAUNCHER = 5;

        boolean mRunning;

        final IPackageStatsObserver.Stub mStatsObserver = new IPackageStatsObserver.Stub() {
            public void onGetStatsCompleted(PackageStats stats, boolean succeeded) {
                boolean sizeChanged = false;
                synchronized (mEntriesMap) {
                    if (DEBUG_LOCKING) Log.v(TAG, "onGetStatsCompleted acquired lock");
                    HashMap<String, AppEntry> userMap = mEntriesMap.get(stats.userHandle);
                    if (userMap == null) {
                        // The user must have been removed.
                        return;
                    }
                    AppEntry entry = userMap.get(stats.packageName);
                    if (entry != null) {
                        synchronized (entry) {
                            entry.sizeStale = false;
                            entry.sizeLoadStart = 0;
                            long externalCodeSize = stats.externalCodeSize
                                    + stats.externalObbSize;
                            long externalDataSize = stats.externalDataSize
                                    + stats.externalMediaSize;
                            long newSize = externalCodeSize + externalDataSize
                                    + getTotalInternalSize(stats);
                            if (entry.size != newSize ||
                                    entry.cacheSize != stats.cacheSize ||
                                    entry.codeSize != stats.codeSize ||
                                    entry.dataSize != stats.dataSize ||
                                    entry.externalCodeSize != externalCodeSize ||
                                    entry.externalDataSize != externalDataSize ||
                                    entry.externalCacheSize != stats.externalCacheSize) {
                                entry.size = newSize;
                                entry.cacheSize = stats.cacheSize;
                                entry.codeSize = stats.codeSize;
                                entry.dataSize = stats.dataSize;
                                entry.externalCodeSize = externalCodeSize;
                                entry.externalDataSize = externalDataSize;
                                entry.externalCacheSize = stats.externalCacheSize;
                                entry.sizeStr = getSizeStr(entry.size);
                                entry.internalSize = getTotalInternalSize(stats);
                                entry.internalSizeStr = getSizeStr(entry.internalSize);
                                entry.externalSize = getTotalExternalSize(stats);
                                entry.externalSizeStr = getSizeStr(entry.externalSize);
                                if (DEBUG) Log.i(TAG, "Set size of " + entry.label + " " + entry
                                        + ": " + entry.sizeStr);
                                sizeChanged = true;
                            }
                        }
                        if (sizeChanged) {
                            Message msg = mMainHandler.obtainMessage(
                                    MainHandler.MSG_PACKAGE_SIZE_CHANGED, stats.packageName);
                            mMainHandler.sendMessage(msg);
                        }
                    }
                    if (mCurComputingSizePkg == null
                            || (mCurComputingSizePkg.equals(stats.packageName)
                                    && mCurComputingSizeUserId == stats.userHandle)) {
                        mCurComputingSizePkg = null;
                        sendEmptyMessage(MSG_LOAD_SIZES);
                    }
                    if (DEBUG_LOCKING) Log.v(TAG, "onGetStatsCompleted releasing lock");
                }
            }
        };

        BackgroundHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            // Always try rebuilding list first thing, if needed.
            ArrayList<Session> rebuildingSessions = null;
            synchronized (mEntriesMap) {
                if (mRebuildingSessions.size() > 0) {
                    rebuildingSessions = new ArrayList<Session>(mRebuildingSessions);
                    mRebuildingSessions.clear();
                }
            }
            if (rebuildingSessions != null) {
                for (int i=0; i<rebuildingSessions.size(); i++) {
                    rebuildingSessions.get(i).handleRebuildList();
                }
            }

            switch (msg.what) {
                case MSG_REBUILD_LIST: {
                } break;
                case MSG_LOAD_ENTRIES: {
                    int numDone = 0;
                    synchronized (mEntriesMap) {
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_ENTRIES acquired lock");
                        for (int i=0; i<mApplications.size() && numDone<6; i++) {
                            if (!mRunning) {
                                mRunning = true;
                                Message m = mMainHandler.obtainMessage(
                                        MainHandler.MSG_RUNNING_STATE_CHANGED, 1);
                                mMainHandler.sendMessage(m);
                            }
                            ApplicationInfo info = mApplications.get(i);
                            int userId = UserHandle.getUserId(info.uid);
                            if (mEntriesMap.get(userId).get(info.packageName) == null) {
                                numDone++;
                                getEntryLocked(info);
                            }
                        }
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_ENTRIES releasing lock");
                    }

                    if (numDone >= 6) {
                        sendEmptyMessage(MSG_LOAD_ENTRIES);
                    } else {
                        sendEmptyMessage(MSG_LOAD_LAUNCHER);
                    }
                } break;
                case MSG_LOAD_LAUNCHER: {
                    Intent launchIntent = new Intent(Intent.ACTION_MAIN, null)
                            .addCategory(Intent.CATEGORY_LAUNCHER);

                    for (int i = 0; i < mEntriesMap.size(); i++) {
                        int userId = mEntriesMap.keyAt(i);
                        List<ResolveInfo> intents = mPm.queryIntentActivitiesAsUser(launchIntent,
                                PackageManager.GET_DISABLED_COMPONENTS, userId);
                        synchronized (mEntriesMap) {
                            if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_LAUNCHER acquired lock");
                            HashMap<String, AppEntry> userEntries = mEntriesMap.valueAt(i);
                            final int N = intents.size();
                            for (int j = 0; j < N; j++) {
                                String packageName = intents.get(j).activityInfo.packageName;
                                AppEntry entry = userEntries.get(packageName);
                                if (entry != null) {
                                    entry.hasLauncherEntry = true;
                                } else {
                                    Log.w(TAG, "Cannot find pkg: " + packageName
                                            + " on user " + userId);
                                }
                            }
                            if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_LAUNCHER releasing lock");
                        }
                    }

                    if (!mMainHandler.hasMessages(MainHandler.MSG_LAUNCHER_INFO_CHANGED)) {
                        mMainHandler.sendEmptyMessage(MainHandler.MSG_LAUNCHER_INFO_CHANGED);
                    }
                    sendEmptyMessage(MSG_LOAD_ICONS);
                } break;
                case MSG_LOAD_ICONS: {
                    int numDone = 0;
                    synchronized (mEntriesMap) {
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_ICONS acquired lock");
                        for (int i=0; i<mAppEntries.size() && numDone<2; i++) {
                            AppEntry entry = mAppEntries.get(i);
                            if (entry.icon == null || !entry.mounted) {
                                synchronized (entry) {
                                    if (entry.ensureIconLocked(mContext, mPm)) {
                                        if (!mRunning) {
                                            mRunning = true;
                                            Message m = mMainHandler.obtainMessage(
                                                    MainHandler.MSG_RUNNING_STATE_CHANGED, 1);
                                            mMainHandler.sendMessage(m);
                                        }
                                        numDone++;
                                    }
                                }
                            }
                        }
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_ICONS releasing lock");
                    }
                    if (numDone > 0) {
                        if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_ICON_CHANGED)) {
                            mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_ICON_CHANGED);
                        }
                    }
                    if (numDone >= 2) {
                        sendEmptyMessage(MSG_LOAD_ICONS);
                    } else {
                        sendEmptyMessage(MSG_LOAD_SIZES);
                    }
                } break;
                case MSG_LOAD_SIZES: {
                    synchronized (mEntriesMap) {
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_SIZES acquired lock");
                        if (mCurComputingSizePkg != null) {
                            if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_SIZES releasing: currently computing");
                            return;
                        }

                        long now = SystemClock.uptimeMillis();
                        for (int i=0; i<mAppEntries.size(); i++) {
                            AppEntry entry = mAppEntries.get(i);
                            if (entry.size == SIZE_UNKNOWN || entry.sizeStale) {
                                if (entry.sizeLoadStart == 0 ||
                                        (entry.sizeLoadStart < (now-20*1000))) {
                                    if (!mRunning) {
                                        mRunning = true;
                                        Message m = mMainHandler.obtainMessage(
                                                MainHandler.MSG_RUNNING_STATE_CHANGED, 1);
                                        mMainHandler.sendMessage(m);
                                    }
                                    entry.sizeLoadStart = now;
                                    mCurComputingSizePkg = entry.info.packageName;
                                    mCurComputingSizeUserId = UserHandle.getUserId(entry.info.uid);
                                    mPm.getPackageSizeInfo(mCurComputingSizePkg,
                                            mCurComputingSizeUserId, mStatsObserver);
                                }
                                if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_SIZES releasing: now computing");
                                return;
                            }
                        }
                        if (!mMainHandler.hasMessages(MainHandler.MSG_ALL_SIZES_COMPUTED)) {
                            mMainHandler.sendEmptyMessage(MainHandler.MSG_ALL_SIZES_COMPUTED);
                            mRunning = false;
                            Message m = mMainHandler.obtainMessage(
                                    MainHandler.MSG_RUNNING_STATE_CHANGED, 0);
                            mMainHandler.sendMessage(m);
                        }
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_SIZES releasing lock");
                    }
                } break;
            }
        }

    }
}
