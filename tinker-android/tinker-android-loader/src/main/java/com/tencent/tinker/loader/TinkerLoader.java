/*
 * Tencent is pleased to support the open source community by making Tinker available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.tinker.loader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.tencent.tinker.loader.app.TinkerApplication;
import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.ShareIntentUtil;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.SharePatchInfo;
import com.tencent.tinker.loader.shareutil.ShareSecurityCheck;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

import java.io.File;

/**
 * Created by zhangshaowen on 16/3/10.
 * Warning, it is special for loader classes, they can't change through tinker patch.
 * thus, it's reference class must put in the tinkerPatch.dex.loader{} and the android main dex pattern through gradle
 */
public class TinkerLoader extends AbstractTinkerLoader {
    private static final String TAG = "Tinker.TinkerLoader";

    /**
     * the patch info file
     */
    private SharePatchInfo patchInfo;

    /**
     * only main process can handle patch version change or incomplete
     */
    @Override
    public Intent tryLoad(TinkerApplication app, int tinkerFlag, boolean tinkerLoadVerifyFlag) {
        Intent resultIntent = new Intent();

        long begin = SystemClock.elapsedRealtime();
        tryLoadPatchFilesInternal(app, tinkerFlag, tinkerLoadVerifyFlag, resultIntent);
        long cost = SystemClock.elapsedRealtime() - begin;
        ShareIntentUtil.setIntentPatchCostTime(resultIntent, cost);
        return resultIntent;
    }

    private void tryLoadPatchFilesInternal(TinkerApplication app, int tinkerFlag, boolean tinkerLoadVerifyFlag, Intent resultIntent) {
        if (!ShareTinkerInternals.isTinkerEnabled(tinkerFlag)) {
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_DISABLE);
            return;
        }
        //tinker
        File patchDirectoryFile = SharePatchFileUtil.getPatchDirectory(app);
        if (patchDirectoryFile == null) {
            Log.w(TAG, "tryLoadPatchFiles:getPatchDirectory == null");
            //treat as not exist
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_DIRECTORY_NOT_EXIST);
            return;
        }
        //patchDirectoryPath==== /data/data/com.tinker.tinker_souce_android/tinker
        String patchDirectoryPath = patchDirectoryFile.getAbsolutePath();

        //check patch directory whether exist
        if (!patchDirectoryFile.exists()) {
            Log.w(TAG, "tryLoadPatchFiles:patch dir not exist:" + patchDirectoryPath);
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_DIRECTORY_NOT_EXIST);
            return;
        }

        //tinker/patch.info
        File patchInfoFile = SharePatchFileUtil.getPatchInfoFile(patchDirectoryPath);

        //check patch info file whether exist
        if (!patchInfoFile.exists()) {
            Log.w(TAG, "tryLoadPatchFiles:patch info not exist:" + patchInfoFile.getAbsolutePath());
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_INFO_NOT_EXIST);
            return;
        }
        //tinker/info.lock
        //old = 641e634c5b8f1649c75caf73794acbdf
        //new = 2c150d8560334966952678930ba67fa8
        File patchInfoLockFile = SharePatchFileUtil.getPatchInfoLockFile(patchDirectoryPath);

        patchInfo = SharePatchInfo.readAndCheckPropertyWithLock(patchInfoFile, patchInfoLockFile);
        if (patchInfo == null) {
            Log.w(TAG, "tryLoadPatchFiles:onPatchInfoCorrupted : patchInfo == null");
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_INFO_CORRUPTED);
            return;
        }

        String oldVersion = patchInfo.oldVersion;
        String newVersion = patchInfo.newVersion;

        if (oldVersion == null || newVersion == null) {
            //it is nice to clean patch
            Log.w(TAG, "tryLoadPatchFiles:onPatchInfoCorrupted");
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_INFO_CORRUPTED);
            return;
        }

        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_OLD_VERSION, oldVersion);
        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_NEW_VERSION, newVersion);

        boolean mainProcess = ShareTinkerInternals.isInMainProcess(app);
        boolean versionChanged = !(oldVersion.equals(newVersion));

        String version = oldVersion;
        if (versionChanged && mainProcess) {
            version = newVersion;
        }
        if (ShareTinkerInternals.isNullOrNil(version)) {
            Log.w(TAG, "tryLoadPatchFiles:version is blank, wait main process to restart");
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_INFO_BLANK);
            return;
        }

        //patch-2c150d85
        String patchName = SharePatchFileUtil.getPatchVersionDirectory(version);
        if (patchName == null) {
            Log.w(TAG, "tryLoadPatchFiles:patchName is null");
            //we may delete patch info file
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_VERSION_DIRECTORY_NOT_EXIST);
            return;
        }
        //tinker/patch-2c150d85
        String patchVersionDirectory = patchDirectoryPath + "/" + patchName;
        File patchVersionDirectoryFile = new File(patchVersionDirectory);

        if (!patchVersionDirectoryFile.exists()) {
            Log.w(TAG, "tryLoadPatchFiles:onPatchVersionDirectoryNotFound");
            //we may delete patch info file
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_VERSION_DIRECTORY_NOT_EXIST);
            return;
        }

        //tinker/patch-2c150d85/patch-2c150d85.apk
        File patchVersionFile = new File(patchVersionDirectoryFile.getAbsolutePath(), SharePatchFileUtil.getPatchVersionFile(version));

        if (!SharePatchFileUtil.isLegalFile(patchVersionFile)) {
            Log.w(TAG, "tryLoadPatchFiles:onPatchVersionFileNotFound");
            //we may delete patch info file
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_VERSION_FILE_NOT_EXIST);
            return;
        }

        ShareSecurityCheck securityCheck = new ShareSecurityCheck(app);
        //主要是检查base和patch里面的tinkerID是否相同
        int returnCode = ShareTinkerInternals.checkTinkerPackage(app, tinkerFlag, patchVersionFile, securityCheck);
        if (returnCode != ShareConstants.ERROR_PACKAGE_CHECK_OK) {
            Log.w(TAG, "tryLoadPatchFiles:checkTinkerPackage");
            resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_PACKAGE_PATCH_CHECK, returnCode);
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_PACKAGE_CHECK_FAIL);
            return;
        }

        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_PACKAGE_CONFIG, securityCheck.getPackagePropertiesIfPresent());

        final boolean isEnabledForDex = ShareTinkerInternals.isTinkerEnabledForDex(tinkerFlag);
        //是否支持补丁修改dex类型
        if (isEnabledForDex) {
            //tinker/patch-2c150d85/dex
            //2017/1/17 18:33 分析断点
            //主要是验证meta信息里面dex是否与补丁匹配
            //并且将meta信息里面的 K:info.realName V:info.destMd5InDvm 打包为map传入intent里面
            boolean dexCheck = TinkerDexLoader.checkComplete(patchVersionDirectory, securityCheck, resultIntent);
            if (!dexCheck) {
                //file not found, do not load patch
                Log.w(TAG, "tryLoadPatchFiles:dex check fail");
                return;
            }
        }

        //检查so补丁 TODO
        final boolean isEnabledForNativeLib = ShareTinkerInternals.isTinkerEnabledForNativeLib(tinkerFlag);

        if (isEnabledForNativeLib) {
            //tinker/patch-641e634c/lib
            boolean libCheck = TinkerSoLoader.checkComplete(patchVersionDirectory, securityCheck, resultIntent);
            if (!libCheck) {
                //file not found, do not load patch
                Log.w(TAG, "tryLoadPatchFiles:native lib check fail");
                return;
            }
        }

        //check resource 检查res资源补丁
        final boolean isEnabledForResource = ShareTinkerInternals.isTinkerEnabledForResource(tinkerFlag);
        Log.w(TAG, "tryLoadPatchFiles:isEnabledForResource:" + isEnabledForResource);
        if (isEnabledForResource) {
            //根据res_meta.xml文件中记载的信息检查文件(res/resources.apk)是否存在
            boolean resourceCheck = TinkerResourceLoader.checkComplete(app, patchVersionDirectory, securityCheck, resultIntent);
            if (!resourceCheck) {
                //file not found, do not load patch
                Log.w(TAG, "tryLoadPatchFiles:resource check fail");
                return;
            }
        }
        //only work for art platform oat //ota应该是ROM厂商的系统升级
        boolean isSystemOTA = ShareTinkerInternals.isVmArt() && ShareTinkerInternals.isSystemOTA(patchInfo.fingerPrint);
        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_SYSTEM_OTA, isSystemOTA);

        //we should first try rewrite patch info file, if there is a error, we can't load jar
        if (isSystemOTA
            || (mainProcess && versionChanged)) {
            patchInfo.oldVersion = version;
            //update old version to new 替换oldversion为newversion
            if (!SharePatchInfo.rewritePatchInfoFileWithLock(patchInfoFile, patchInfo, patchInfoLockFile)) {
                ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_REWRITE_PATCH_INFO_FAIL);
                Log.w(TAG, "tryLoadPatchFiles:onReWritePatchInfoCorrupted");
                return;
            }
        }
        if (!checkSafeModeCount(app)) {
            resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_EXCEPTION, new TinkerRuntimeException("checkSafeModeCount fail"));
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_UNCAUGHT_EXCEPTION);
            Log.w(TAG, "tryLoadPatchFiles:checkSafeModeCount fail");
            return;
        }
        //前面的一系列都是检查补丁的信息情况 通过检查后 下面就进入真正的加载补丁功能
        //now we can load patch jar
        if (isEnabledForDex) {
            //真正的开始加载补丁dex
            boolean loadTinkerJars = TinkerDexLoader.loadTinkerJars(app, tinkerLoadVerifyFlag, patchVersionDirectory, resultIntent, isSystemOTA);
            if (!loadTinkerJars) {
                Log.w(TAG, "tryLoadPatchFiles:onPatchLoadDexesFail");
                return;
            }
        }
        //真正加载res资源
        //now we can load patch resource
        if (isEnabledForResource) {
            //真正加载res资源
            boolean loadTinkerResources = TinkerResourceLoader.loadTinkerResources(app, tinkerLoadVerifyFlag, patchVersionDirectory, resultIntent);
            if (!loadTinkerResources) {
                Log.w(TAG, "tryLoadPatchFiles:onPatchLoadResourcesFail");
                return;
            }
        }
        //all is ok!
        ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_OK);
        Log.i(TAG, "tryLoadPatchFiles: load end, ok!");
        return;
    }

    /**
     * 检查加载补丁安全次数是否超标
     * @param application
     * @return
     */
    private boolean checkSafeModeCount(TinkerApplication application) {
        String processName = ShareTinkerInternals.getProcessName(application);
        String preferName = ShareConstants.TINKER_OWN_PREFERENCE_CONFIG + processName;
        //each process have its own SharedPreferences file
        SharedPreferences sp = application.getSharedPreferences(preferName, Context.MODE_PRIVATE);
        int count = sp.getInt(ShareConstants.TINKER_SAFE_MODE_COUNT, 0) + 1;
        Log.w(TAG, "tinker safe mode preferName:" + preferName + " count:" + count);
        if (count >= ShareConstants.TINKER_SAFE_MODE_MAX_COUNT) {
            sp.edit().putInt(ShareConstants.TINKER_SAFE_MODE_COUNT, 0).commit();
            return false;
        }
        application.setUseSafeMode(true);
        sp.edit().putInt(ShareConstants.TINKER_SAFE_MODE_COUNT, count).commit();
        return true;
    }


}
