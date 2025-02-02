/*
 * MIT License
 *
 * Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.iqiyi.android.qigsaw.core.splitload;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.ArraySet;

import com.iqiyi.android.qigsaw.core.common.SplitConstants;
import com.iqiyi.android.qigsaw.core.common.SplitLog;
import com.iqiyi.android.qigsaw.core.splitload.listener.OnSplitLoadListener;
import com.iqiyi.android.qigsaw.core.splitreport.SplitLoadError;
import com.iqiyi.android.qigsaw.core.splitreport.SplitLoadReporter;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfo;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfoManager;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfoManagerService;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitPathManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

abstract class SplitLoadTask implements Runnable {

    private static final String TAG = "SplitLoadTask";

    final Context appContext;

    private final Handler mainHandler;

    private final List<String> moduleNames;

    private final SplitActivator activator;

    private final SplitLoadManager loadManager;

    private final SplitInfoManager infoManager;

    private final List<Intent> splitFileIntents;

    private final OnSplitLoadListener loadListener;

    private final Object mLock = new Object();

    SplitLoadTask(@NonNull SplitLoadManager loadManager,
                  @NonNull List<Intent> splitFileIntents,
                  @Nullable OnSplitLoadListener loadListener) {
        this.loadManager = loadManager;
        this.splitFileIntents = splitFileIntents;
        this.loadListener = loadListener;
        this.appContext = loadManager.getContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.infoManager = SplitInfoManagerService.getInstance();
        this.activator = new SplitActivator(loadManager.getContext());
        this.moduleNames = extractModuleNames();
    }

    abstract SplitLoader createSplitLoader();

    abstract ClassLoader loadCode(SplitLoader loader,
                                  String splitName,
                                  List<String> addedDexPaths,
                                  File optimizedDirectory,
                                  File librarySearchPath) throws SplitLoadException;

    abstract void onSplitActivateFailed(ClassLoader classLoader);

    @Override
    public final void run() {
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            loadSplitOnUIThread();
        } else {
            synchronized (mLock) {
                mainHandler.post(new Runnable() {

                    @Override
                    public void run() {
                        synchronized (mLock) {
                            loadSplitOnUIThread();
                            mLock.notifyAll();
                        }
                    }
                });
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    List<SplitLoadError> errors = Collections.singletonList(new SplitLoadError(moduleNames.get(0), SplitLoadError.INTERRUPTED_ERROR, e));
                    reportLoadResult(errors, 0);
                }
            }
        }
    }

    private void loadSplitOnUIThread() {
        long time = System.currentTimeMillis();
        List<SplitLoadError> errors = loadSplitInternal();
        long cost = System.currentTimeMillis() - time;
        reportLoadResult(errors, cost);
    }

    private List<SplitLoadError> loadSplitInternal() {
        SplitLoader loader = createSplitLoader();
        Set<Split> splits = new ArraySet<>();
        List<SplitLoadError> loadErrors = new ArrayList<>(0);
        for (Intent splitFileIntent : splitFileIntents) {
            String splitName = splitFileIntent.getStringExtra(SplitConstants.KET_NAME);
            //if if split has been loaded, just skip.
            if (checkSplitLoaded(splitName)) {
                SplitLog.i(TAG, "Split %s has been loaded!", splitName);
                continue;
            }
            String splitApkPath = splitFileIntent.getStringExtra(SplitConstants.KEY_APK);
            try {
                //load split's resources.
                loader.loadResources(splitApkPath);
            } catch (SplitLoadException e) {
                SplitLog.printErrStackTrace(TAG, e, "Failed to load split %s resources!", splitName);
                loadErrors.add(new SplitLoadError(splitName, e.getErrorCode(), e.getCause()));
                continue;
            }
            List<String> addedDexPaths = splitFileIntent.getStringArrayListExtra(SplitConstants.KEY_ADDED_DEX);
            SplitInfo info = infoManager.getSplitInfo(appContext, splitName);
            File optimizedDirectory = SplitPathManager.require().getSplitOptDir(info);
            File librarySearchPath = null;
            if (info.hasLibs()) {
                librarySearchPath = SplitPathManager.require().getSplitLibDir(info);
            }
            File splitDir = SplitPathManager.require().getSplitDir(info);
            ClassLoader classLoader;
            try {
                classLoader = loadCode(loader, splitName, addedDexPaths, optimizedDirectory, librarySearchPath);
            } catch (SplitLoadException e) {
                SplitLog.printErrStackTrace(TAG, e, "Failed to load split %s code!", splitName);
                loadErrors.add(new SplitLoadError(splitName, e.getErrorCode(), e.getCause()));
                continue;
            }
            //activate split, include application and provider.
            try {
                activator.activate(classLoader, splitName);
            } catch (SplitLoadException e) {
                SplitLog.printErrStackTrace(TAG, e, "Failed to activate " + splitName);
                loadErrors.add(new SplitLoadError(splitName, e.getErrorCode(), e.getCause()));
                onSplitActivateFailed(classLoader);
                continue;
            }
            splits.add(new Split(splitName, splitApkPath));
            if (!splitDir.setLastModified(System.currentTimeMillis())) {
                SplitLog.w(TAG, "Failed to set last modified time for " + splitName);
            }
        }
        loadManager.putSplits(splits);
        return loadErrors;
    }

    private void reportLoadResult(List<SplitLoadError> errors, long cost) {
        SplitLoadReporter loadReporter = SplitLoadReporterManager.getLoadReporter();
        if (!errors.isEmpty()) {
            if (loadListener != null) {
                int lastErrorCode = errors.get(errors.size() - 1).getErrorCode();
                loadListener.onFailed(lastErrorCode);
            }
            if (loadReporter != null) {
                loadReporter.onLoadFailed(moduleNames, loadManager.currentProcessName, errors, cost);
            }
        } else {
            if (loadListener != null) {
                loadListener.onCompleted();
            }
            if (loadReporter != null) {
                loadReporter.onLoadOK(moduleNames, loadManager.currentProcessName, cost);
            }
        }
    }

    private List<String> extractModuleNames() {
        List<String> requestModuleNames = new ArrayList<>(splitFileIntents.size());
        for (Intent intent : splitFileIntents) {
            requestModuleNames.add(intent.getStringExtra(SplitConstants.KET_NAME));
        }
        return requestModuleNames;
    }

    private boolean checkSplitLoaded(String splitName) {
        for (Split split : loadManager.getLoadedSplits()) {
            if (split.splitName.equals(splitName)) {
                return true;
            }
        }
        return false;
    }


}
