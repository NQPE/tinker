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

import android.annotation.TargetApi;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.ShareDexDiffPatchInfo;
import com.tencent.tinker.loader.shareutil.ShareIntentUtil;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.ShareSecurityCheck;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import dalvik.system.PathClassLoader;

/**
 * Created by zhangshaowen on 16/3/8.
 * check the complete of the dex files
 * pre-load patch dex files
 */
public class TinkerDexLoader {

    private static final String TAG = "Tinker.TinkerDexLoader";

    private static final String                           DEX_MEAT_FILE     = ShareConstants.DEX_META_FILE;
    private static final String                           DEX_PATH          = ShareConstants.DEX_PATH;
    private static final String                           DEX_OPTIMIZE_PATH = ShareConstants.DEX_OPTIMIZE_PATH;
    private static final ArrayList<ShareDexDiffPatchInfo> dexList           = new ArrayList<>();

    private static boolean   parallelOTAResult;
    private static Throwable parallelOTAThrowable;

    private TinkerDexLoader() {
    }

    /**
     * Load tinker JARs and add them to
     * the Application ClassLoader.
     *
     * @param application The application.
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public static boolean loadTinkerJars(Application application, boolean tinkerLoadVerifyFlag, String directory, Intent intentResult, boolean isSystemOTA) {
        if (dexList.isEmpty()) {
            Log.w(TAG, "there is no dex to load");
            return true;
        }

        PathClassLoader classLoader = (PathClassLoader) TinkerDexLoader.class.getClassLoader();
        if (classLoader != null) {
            Log.i(TAG, "classloader: " + classLoader.toString());
        } else {
            Log.e(TAG, "classloader is null");
            ShareIntentUtil.setIntentReturnCode(intentResult, ShareConstants.ERROR_LOAD_PATCH_VERSION_DEX_CLASSLOADER_NULL);
            return false;
        }
        //tinker/patch-2c150d85/dex/
        String dexPath = directory + "/" + DEX_PATH + "/";
        File optimizeDir = new File(directory + "/" + DEX_OPTIMIZE_PATH);
//        Log.i(TAG, "loadTinkerJars: dex path: " + dexPath);
//        Log.i(TAG, "loadTinkerJars: opt path: " + optimizeDir.getAbsolutePath());

        ArrayList<File> legalFiles = new ArrayList<>();

        final boolean isArtPlatForm = ShareTinkerInternals.isVmArt();
        for (ShareDexDiffPatchInfo info : dexList) {
            //for dalvik, ignore art support dex
            if (isJustArtSupportDex(info)) {
                continue;
            }
            String path = dexPath + info.realName;
            File file = new File(path);

            if (tinkerLoadVerifyFlag) {//默认一般不验证MD5
                long start = System.currentTimeMillis();
                String checkMd5 = isArtPlatForm ? info.destMd5InArt : info.destMd5InDvm;
                if (!SharePatchFileUtil.verifyDexFileMd5(file, checkMd5)) {
                    //it is good to delete the mismatch file
                    ShareIntentUtil.setIntentReturnCode(intentResult, ShareConstants.ERROR_LOAD_PATCH_VERSION_DEX_MD5_MISMATCH);
                    intentResult.putExtra(ShareIntentUtil.INTENT_PATCH_MISMATCH_DEX_PATH,
                        file.getAbsolutePath());
                    return false;
                }
                Log.i(TAG, "verify dex file:" + file.getPath() + " md5, use time: " + (System.currentTimeMillis() - start));
            }
            legalFiles.add(file);
        }


        /**
         * 至于全量新Dex在系统OTA之后触发dex2oat
         * 可能导致App启动时ANR的问题，
         * Tinker是通过在进入ApplicationLike之前判断fingerprint是否变化来得知系统是否进行过OTA，
         * 然后根据判断结果手动触发多线程dex2oat加以缓解的。
         */
        //如果是厂商OTA 优化dex TODO
        if (isSystemOTA) {
            parallelOTAResult = true;
            parallelOTAThrowable = null;
            Log.w(TAG, "systemOTA, try parallel oat dexes!!!!!");

            TinkerParallelDexOptimizer.optimizeAll(
                legalFiles, optimizeDir,
                new TinkerParallelDexOptimizer.ResultCallback() {
                    long start;

                    @Override
                    public void onStart(File dexFile, File optimizedDir) {
                        start = System.currentTimeMillis();
                        Log.i(TAG, "start to optimize dex:" + dexFile.getPath());
                    }

                    @Override
                    public void onSuccess(File dexFile, File optimizedDir, File optimizedFile) {
                        // Do nothing.
                        Log.i(TAG, "success to optimize dex " + dexFile.getPath() + "use time " + (System.currentTimeMillis() - start));
                    }
                    @Override
                    public void onFailed(File dexFile, File optimizedDir, Throwable thr) {
                        parallelOTAResult = false;
                        parallelOTAThrowable = thr;
                        Log.i(TAG, "fail to optimize dex " + dexFile.getPath() + "use time " + (System.currentTimeMillis() - start));
                    }
                }
            );
            if (!parallelOTAResult) {
                Log.e(TAG, "parallel oat dexes failed");
                intentResult.putExtra(ShareIntentUtil.INTENT_PATCH_EXCEPTION, parallelOTAThrowable);
                ShareIntentUtil.setIntentReturnCode(intentResult, ShareConstants.ERROR_LOAD_PATCH_VERSION_PARALLEL_DEX_OPT_EXCEPTION);
                return false;
            }
        }
        try {
            SystemClassLoaderAdder.installDexes(application, classLoader, optimizeDir, legalFiles);
        } catch (Throwable e) {
            Log.e(TAG, "install dexes failed");
//            e.printStackTrace();
            intentResult.putExtra(ShareIntentUtil.INTENT_PATCH_EXCEPTION, e);
            ShareIntentUtil.setIntentReturnCode(intentResult, ShareConstants.ERROR_LOAD_PATCH_VERSION_DEX_LOAD_EXCEPTION);
            return false;
        }

        return true;
    }

    /**
     * 主要验证assets/dex_meta.txt里面的dex信息是否与补丁的真实dex匹配存在
     *
     * 补丁包assets/dex_meta.txt 里面的信息
     * 以','分隔 分别对应：name, path, destMd5InDvm, destMd5InArt, dexDiffMd5, oldDexCrc, dexMode
     * classes.dex,,3fa38034d90cf6fbd4207a4c0789dfb2,3fa38034d90cf6fbd4207a4c0789dfb2,8e244ce569c4b6c9c786d3b51d29ed32,2699196016,jar
     * test.dex,,56900442eb5b7e1de45449d0685e6e00,56900442eb5b7e1de45449d0685e6e00,0,0,jar
     * 主要是将meta信息里面的 K:info.realName V:info.destMd5InDvm 打包为map传入intent里面
     * all the dex files in meta file exist?
     * fast check, only check whether exist
     *
     * @param directory
     * @return boolean
     */
    public static boolean checkComplete(String directory, ShareSecurityCheck securityCheck, Intent intentResult) {
        String meta = securityCheck.getMetaContentMap().get(DEX_MEAT_FILE);
        //not found dex
        if (meta == null) {
            return true;
        }
        dexList.clear();
        //meta里面的信息封装为ShareDexDiffPatchInfo类 并add进dexList里
        ShareDexDiffPatchInfo.parseDexDiffPatchInfo(meta, dexList);

        if (dexList.isEmpty()) {
            return true;
        }

        HashMap<String, String> dexes = new HashMap<>();

        for (ShareDexDiffPatchInfo info : dexList) {
            //默认一般都是dvm art双支持
            //for dalvik, ignore art support dex
            if (isJustArtSupportDex(info)) {
                continue;
            }
            if (!ShareDexDiffPatchInfo.checkDexDiffPatchInfo(info)) {
                intentResult.putExtra(ShareIntentUtil.INTENT_PATCH_PACKAGE_PATCH_CHECK, ShareConstants.ERROR_PACKAGE_CHECK_DEX_META_CORRUPTED);
                ShareIntentUtil.setIntentReturnCode(intentResult, ShareConstants.ERROR_LOAD_PATCH_PACKAGE_CHECK_FAIL);
                return false;
            }
            dexes.put(info.realName, info.destMd5InDvm);
        }
        //tinker/patch-2c150d85/dex/
        String dexDirectory = directory + "/" + DEX_PATH + "/";

        File dexDir = new File(dexDirectory);

        if (!dexDir.exists() || !dexDir.isDirectory()) {
            ShareIntentUtil.setIntentReturnCode(intentResult, ShareConstants.ERROR_LOAD_PATCH_VERSION_DEX_DIRECTORY_NOT_EXIST);
            return false;
        }
        //tinker/patch-2c150d85/odex/ 这个应该是dex优化目录
        String optimizeDexDirectory = directory + "/" + DEX_OPTIMIZE_PATH + "/";
        File optimizeDexDirectoryFile = new File(optimizeDexDirectory);

        //fast check whether there is any dex files missing
        for (String name : dexes.keySet()) {
            File dexFile = new File(dexDirectory + name);
            if (!SharePatchFileUtil.isLegalFile(dexFile)) {
                intentResult.putExtra(ShareIntentUtil.INTENT_PATCH_MISSING_DEX_PATH, dexFile.getAbsolutePath());
                ShareIntentUtil.setIntentReturnCode(intentResult, ShareConstants.ERROR_LOAD_PATCH_VERSION_DEX_FILE_NOT_EXIST);
                return false;
            }
            //check dex opt whether complete also
            //查看优化文件是否存在
            File dexOptFile = new File(SharePatchFileUtil.optimizedPathFor(dexFile, optimizeDexDirectoryFile));
            if (!SharePatchFileUtil.isLegalFile(dexOptFile)) {
                intentResult.putExtra(ShareIntentUtil.INTENT_PATCH_MISSING_DEX_PATH, dexOptFile.getAbsolutePath());
                ShareIntentUtil.setIntentReturnCode(intentResult, ShareConstants.ERROR_LOAD_PATCH_VERSION_DEX_OPT_FILE_NOT_EXIST);
                return false;
            }
        }

        //if is ok, add to result intent
        intentResult.putExtra(ShareIntentUtil.INTENT_PATCH_DEXES_PATH, dexes);
        return true;
    }

    private static boolean isJustArtSupportDex(ShareDexDiffPatchInfo dexDiffPatchInfo) {
        if (ShareTinkerInternals.isVmArt()) {
            return false;
        }

        String destMd5InDvm = dexDiffPatchInfo.destMd5InDvm;

        if (destMd5InDvm.equals("0")) {
            return true;
        }

        return false;
    }
}
