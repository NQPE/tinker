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

package com.tencent.tinker.lib.util;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.util.Log;

import com.tencent.tinker.lib.service.TinkerPatchService;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

import java.util.List;

/**
 * Created by zhangshaowen on 16/3/10.
 */
public class TinkerServiceInternals extends ShareTinkerInternals {
    private static final String TAG = "Tinker.ServiceInternals";

    /**
     * or you may just hardcode them in your app
     */
    private static String patchServiceProcessName = null;

    public static void killTinkerPatchServiceProcess(Context context) {
        String serverProcessName = getTinkerPatchServiceName(context);
        if (serverProcessName == null) {
            return;
        }

        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        // ActivityManager getRunningAppProcesses()
        List<ActivityManager.RunningAppProcessInfo> appProcessList = am
            .getRunningAppProcesses();
        if (appProcessList == null) {
            return;
        }
        for (ActivityManager.RunningAppProcessInfo appProcess : appProcessList) {
            String processName = appProcess.processName;
            if (processName.equals(serverProcessName)) {
                android.os.Process.killProcess(appProcess.pid);
            }
        }

    }

    public static boolean isTinkerPatchServiceRunning(Context context) {
        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        String serverName = getTinkerPatchServiceName(context);
        if (serverName == null) {
            return false;
        }
        try {
            // ActivityManager getRunningAppProcesses()
            List<ActivityManager.RunningAppProcessInfo> appProcessList = am
                .getRunningAppProcesses();
            if (appProcessList == null) {
                return false;
            }
            for (ActivityManager.RunningAppProcessInfo appProcess : appProcessList) {
                String processName = appProcess.processName;
                if (processName.equals(serverName)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "isTinkerPatchServiceRunning Exception: " + e.toString());
            return false;
        } catch (Error e) {
            Log.e(TAG, "isTinkerPatchServiceRunning Error: " + e.toString());
            return false;
        }

        return false;
    }


    public static String getTinkerPatchServiceName(final Context context) {
        if (patchServiceProcessName != null) {
            return patchServiceProcessName;
        }
        //may be null, and you may like to hardcode instead
        String serviceName = TinkerServiceInternals.getServiceProcessName(context, TinkerPatchService.class);
        if (serviceName == null) {
            return null;
        }
        patchServiceProcessName = serviceName;
        return patchServiceProcessName;
    }

    /**
     * add service cache
     *
     * @param context
     * @return boolean
     */
    public static boolean isInTinkerPatchServiceProcess(Context context) {
        String process = getProcessName(context);

        String service = TinkerServiceInternals.getTinkerPatchServiceName(context);
        if (service == null || service.length() == 0) {
            return false;
        }
        return process.equals(service);
    }

    /**
     * PackageItemInfo类
     说明： AndroidManifest.xml文件中所有节点的基类，提供了这些节点的基本信息：a label、icon、 meta-data。它并不
     直接使用，而是由子类继承然后调用相应方法。
     常用字段：
     public int icon           获得该资源图片在R文件中的值 (对应于android:icon属性)
     public int labelRes     获得该label在R文件中的值(对应于android:label属性)
     public String name   获得该节点的name值 (对应于android:name属性)
     public String packagename   获得该应用程序的包名 (对应于android：packagename属性)
     常用方法：
     Drawable  loadIcon(PackageManager pm)               获得当前应用程序的图像
     CharSequence  loadLabel(PackageManager pm)     获得当前应用程序的label

     ActivityInfo类  继承自 PackageItemInfo
     说明： 获得应用程序中<activity/>或者 <receiver />节点的信息 。我们可以通过它来获取我们设置的任何属性，包括
     theme 、launchMode、launchmode等
     常用方法继承至PackageItemInfo类中的loadIcon()和loadLabel()

     ServiceInfo 类
     说明： 同ActivityInfo类似 ，同样继承自 PackageItemInfo，只不过它表示的是<service>节点信息。
     * @param context
     * @param serviceClass
     * @return
     */
    private static String getServiceProcessName(Context context, Class<? extends Service> serviceClass) {
        PackageManager packageManager = context.getPackageManager();

        //ComponentName（组件名称）是用来打开其他应用程序中的Activity或服务的
        ComponentName component = new ComponentName(context, serviceClass);
        ServiceInfo serviceInfo;
        try {
            serviceInfo = packageManager.getServiceInfo(component, 0);
        } catch (Throwable ignored) {
            // Service is disabled.
            return null;
        }

        return serviceInfo.processName;
    }

}
