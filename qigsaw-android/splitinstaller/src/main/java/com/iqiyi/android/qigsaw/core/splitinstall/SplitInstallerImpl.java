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

package com.iqiyi.android.qigsaw.core.splitinstall;

import android.content.Context;

import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.SplitBaseInfoProvider;
import com.iqiyi.android.qigsaw.core.common.SplitConstants;
import com.iqiyi.android.qigsaw.core.common.SplitLog;
import com.iqiyi.android.qigsaw.core.splitload.SplitApplicationLoaders;
import com.iqiyi.android.qigsaw.core.splitload.SplitDexClassLoader;
import com.iqiyi.android.qigsaw.core.splitload.SplitLoad;
import com.iqiyi.android.qigsaw.core.splitload.SplitLoadManagerService;
import com.iqiyi.android.qigsaw.core.splitreport.SplitInstallError;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfo;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfoManager;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfoManagerService;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitPathManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SplitInstallerImpl extends SplitInstaller {

    private static final boolean IS_VM_MULTIDEX_CAPABLE = isVMMultiDexCapable(System.getProperty("java.vm.version"));

    private static final String TAG = "Split:SplitInstallerImpl";

    private final Context appContext;

    SplitInstallerImpl(Context context) {
        this.appContext = context;
    }

    @Override
    public InstallResult install(SplitInfo info) throws InstallException {
        File splitDir = SplitPathManager.require().getSplitDir(info);
        File sourceApk = new File(splitDir, info.getSplitName() + SplitConstants.DOT_APK);
        validateSignature(sourceApk);
        File splitLibDir = null;
        if (isLibExtractNeeded(info)) {
            extractLib(info, sourceApk);
            splitLibDir = SplitPathManager.require().getSplitLibDir(info);
        }
        List<String> addedDexPaths = null;
        if (info.hasDex()) {
            addedDexPaths = new ArrayList<>();
            addedDexPaths.add(sourceApk.getAbsolutePath());
            if (!isVMMultiDexCapable()) {
                if (isMultiDexExtractNeeded(info)) {
                    addedDexPaths.addAll(extractMultiDex(info, sourceApk));
                }
            }
        }
        SplitDexClassLoader dexClassLoader = SplitDexClassLoader.create(
                appContext, info.getSplitName(),
                addedDexPaths,
                SplitPathManager.require().getSplitOptDir(info),
                splitLibDir
        );
        if (SplitLoadManagerService.getInstance().splitLoadMode() == SplitLoad.MULTIPLE_CLASSLOADER) {
            SplitApplicationLoaders.getInstance().addClassLoader(dexClassLoader);
        }
        createInstalledMark(info);
        return new InstallResult(info.getSplitName(), sourceApk, addedDexPaths, checkDependenciesInstalledStatus(info));
    }

    private boolean checkDependenciesInstalledStatus(SplitInfo info) {
        SplitInfoManager manager = SplitInfoManagerService.getInstance();
        if (manager == null) {
            return false;
        }
        List<String> dependencies = info.getDependencies();
        if (dependencies != null) {
            for (String dependency : dependencies) {
                SplitInfo dependencySplitInfo = manager.getSplitInfo(appContext, dependency);
                File dependencySplitDir = SplitPathManager.require().getSplitDir(dependencySplitInfo);
                File dependencyMarkFile = new File(dependencySplitDir, dependencySplitInfo.getMd5());
                if (!dependencyMarkFile.exists()) {
                    SplitLog.i(TAG, "Dependency %s mark file is not existed!", dependency);
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected void validateSignature(File splitApk) throws InstallException {
        if (!FileUtil.isLegalFile(splitApk)) {
            throw new InstallException(
                    SplitInstallError.APK_FILE_ILLEGAL,
                    new FileNotFoundException("Split apk " + splitApk.getAbsolutePath() + " is illegal!")
            );
        }
        if (!SignatureValidator.validateSplit(appContext, splitApk)) {
            deleteCorruptedFiles(Collections.singletonList(splitApk));
            throw new InstallException(
                    SplitInstallError.SIGNATURE_MISMATCH,
                    new SignatureException("Failed to check split apk " + splitApk.getAbsolutePath() + " signature!")
            );
        }
    }

    @Override
    protected List<String> extractMultiDex(SplitInfo info, File splitApk) throws InstallException {
        SplitLog.w(TAG,
                "VM do not support multi-dex, but split %s has multi dex files, so we need creteSplitInstallService other dex files manually",
                splitApk.getName());
        File codeCacheDir = SplitPathManager.require().getSplitCodeCacheDir(info);
        String prefsKeyPrefix = info.getSplitName() + "@" + SplitBaseInfoProvider.getVersionName() + "@" + info.getSplitVersion();
        try {
            SplitMultiDexExtractor extractor = new SplitMultiDexExtractor(splitApk, codeCacheDir);
            try {
                List<? extends File> dexFiles = extractor.load(appContext, prefsKeyPrefix, false);
                List<String> dexPaths = new ArrayList<>(dexFiles.size());
                for (File dexFile : dexFiles) {
                    dexPaths.add(dexFile.getAbsolutePath());
                }
                SplitLog.w(TAG, "Succeed to load or extract dex files", dexFiles.toString());
                return dexPaths;
            } catch (IOException e) {
                SplitLog.w(TAG, "Failed to load or extract dex files", e);
                throw new InstallException(SplitInstallError.DEX_EXTRACT_FAILED, e);
            } finally {
                FileUtil.closeQuietly(extractor);
            }
        } catch (IOException ioError) {
            throw new InstallException(SplitInstallError.DEX_EXTRACT_FAILED, ioError);
        }
    }

    @Override
    protected void extractLib(SplitInfo info, File sourceApk) throws InstallException {
        try {
            File splitLibDir = SplitPathManager.require().getSplitLibDir(info);
            SplitLibExtractor extractor = new SplitLibExtractor(sourceApk, splitLibDir);
            try {
                List<File> libFiles = extractor.load(info, false);
                SplitLog.i(TAG, "Succeed to extract libs:  %s", libFiles.toString());
            } catch (IOException e) {
                SplitLog.w(TAG, "Failed to load or extract lib files", e);
                throw new InstallException(SplitInstallError.LIB_EXTRACT_FAILED, e);
            } finally {
                FileUtil.closeQuietly(extractor);
            }
        } catch (IOException ioError) {
            throw new InstallException(SplitInstallError.LIB_EXTRACT_FAILED, ioError);
        }
    }

    @Override
    protected void createInstalledMark(SplitInfo info) throws InstallException {
        File splitDir = SplitPathManager.require().getSplitDir(info);
        File markFile = new File(splitDir, info.getMd5());
        if (!markFile.exists()) {
            boolean isCreationSuccessful = false;
            int numAttempts = 0;
            Exception cause = null;
            while (numAttempts < SplitConstants.MAX_RETRY_ATTEMPTS && !isCreationSuccessful) {
                numAttempts++;
                try {
                    if (!markFile.createNewFile()) {
                        SplitLog.w(TAG, "Split %s mark file %s already exists", info.getSplitName(), markFile.getAbsolutePath());
                    }
                    isCreationSuccessful = true;
                } catch (Exception e) {
                    isCreationSuccessful = false;
                    cause = e;
                }
            }
            if (!isCreationSuccessful) {
                throw new InstallException(SplitInstallError.MARK_CREATE_FAILED, cause);
            }

        }
    }

    /**
     * Estimate whether current platform supports multi dex.
     *
     * @return {@code true} if supports multi dex, otherwise {@code false}
     */
    private boolean isVMMultiDexCapable() {
        return IS_VM_MULTIDEX_CAPABLE;
    }

    /**
     * check whether split apk has multi dexes.
     *
     * @param info {@link SplitInfo}
     */
    private boolean isMultiDexExtractNeeded(SplitInfo info) {
        return info.isMultiDex();
    }

    /**
     * check whether split apk has native libraries.
     *
     * @param info {@link SplitInfo}
     */
    private boolean isLibExtractNeeded(SplitInfo info) {
        return info.hasLibs();
    }

    /**
     * Delete corrupted files if split apk installing failed.
     *
     * @param files list of corrupted files
     */
    private void deleteCorruptedFiles(List<File> files) {
        for (File file : files) {
            FileUtil.safeDeleteFile(file);
        }
    }

    private static boolean isVMMultiDexCapable(String versionString) {
        boolean isMultiDexCapable = false;
        if (versionString != null) {
            Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)(\\.\\d+)?").matcher(versionString);
            if (matcher.matches()) {
                try {
                    int major = Integer.parseInt(matcher.group(1));
                    int minor = Integer.parseInt(matcher.group(2));
                    isMultiDexCapable = major > 2 || major == 2 && minor >= 1;
                } catch (NumberFormatException var5) {
                    //ignored
                }
            }
        }
        SplitLog.i("Split:MultiDex", "VM with version " + versionString + (isMultiDexCapable ? " has multidex support" : " does not have multidex support"));
        return isMultiDexCapable;
    }
}
