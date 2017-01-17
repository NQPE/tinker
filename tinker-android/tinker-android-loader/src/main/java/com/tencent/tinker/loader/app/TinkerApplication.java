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

package com.tencent.tinker.loader.app;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.SystemClock;

import com.tencent.tinker.loader.TinkerLoader;
import com.tencent.tinker.loader.TinkerRuntimeException;
import com.tencent.tinker.loader.TinkerUncaughtHandler;
import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.ShareIntentUtil;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Created by zhangshaowen on 16/3/8.
 * <p/>
 * A base class for implementing an Application that delegates to an {@link ApplicationLifeCycle}
 * instance. This is used in conjunction with secondary dex files so that the logic that would
 * normally live in the Application class is loaded after the secondary dexes are loaded.
 */
public abstract class TinkerApplication extends Application {

    //oh, we can use ShareConstants, because they are loader class and static final!
    private static final int    TINKER_DISABLE         = ShareConstants.TINKER_DISABLE;
    private static final String INTENT_PATCH_EXCEPTION = ShareIntentUtil.INTENT_PATCH_EXCEPTION;
    private static final String TINKER_LOADER_METHOD   = "tryLoad";
    /**
     * tinkerFlags, which types is supported
     * dex only, library only, all support
     * default: TINKER_ENABLE_ALL
     */
    private final int     tinkerFlags;
    /**
     * whether verify md5 when we load dex or lib
     * they store at data/data/package, and we had verity them at the :patch process.
     * so we don't have to verity them every time for quicker!
     * default:false
     */
    private final boolean tinkerLoadVerifyFlag;
    private final String  delegateClassName;
    private final String  loaderClassName;
    /**
     * if we have load patch, we should use safe mode
     */
    private       boolean useSafeMode;
    private       Intent  tinkerResultIntent;

    private ApplicationLike applicationLike = null;

    //从开机到现在的毫秒数（手机睡眠(sleep)的时间也包括在内）
    private long applicationStartElapsedTime;
    private long applicationStartMillisTime;

    /**
     * current build.
     */
    protected TinkerApplication(int tinkerFlags) {
        this(tinkerFlags, "com.tencent.tinker.loader.app.DefaultApplicationLike", TinkerLoader.class.getName(), false);
    }

    /**
     * @param delegateClassName The fully-qualified name of the {@link ApplicationLifeCycle} class
     *                          that will act as the delegate for application lifecycle callbacks.
     */
    protected TinkerApplication(int tinkerFlags, String delegateClassName,
                                String loaderClassName, boolean tinkerLoadVerifyFlag) {
        this.tinkerFlags = tinkerFlags;
        this.delegateClassName = delegateClassName;
        this.loaderClassName = loaderClassName;
        this.tinkerLoadVerifyFlag = tinkerLoadVerifyFlag;

    }

    protected TinkerApplication(int tinkerFlags, String delegateClassName) {
        this(tinkerFlags, delegateClassName, TinkerLoader.class.getName(), false);
    }

    /**
     * 通过反射构造ApplicationLike
     *
     * @return
     */
    private ApplicationLike createDelegate() {
        try {
            // Use reflection to create the delegate so it doesn't need to go into the primary dex.
            // And we can also patch it
            Class<?> delegateClass = Class.forName(delegateClassName, false, getClassLoader());
            Constructor<?> constructor = delegateClass.getConstructor(Application.class, int.class, boolean.class,
                long.class, long.class, Intent.class);
            return (ApplicationLike) constructor.newInstance(this, tinkerFlags, tinkerLoadVerifyFlag,
                applicationStartElapsedTime, applicationStartMillisTime, tinkerResultIntent);
        } catch (Throwable e) {
            throw new TinkerRuntimeException("createDelegate failed", e);
        }
    }

    private synchronized void ensureDelegate() {
        if (applicationLike == null) {
            applicationLike = createDelegate();
        }
    }

    /**
     * Hook for sub-classes to run logic after the {@link Application#attachBaseContext} has been
     * called but before the delegate is created. Implementors should be very careful what they do
     * here since {@link android.app.Application#onCreate} will not have yet been called.
     */
    private void onBaseContextAttached(Context base) {
        //从开机到现在的毫秒数（手机睡眠(sleep)的时间也包括在内）
        applicationStartElapsedTime = SystemClock.elapsedRealtime();
        applicationStartMillisTime = System.currentTimeMillis();
        //加载tinker 这里时机是在重写{@link Application#attachBaseContext}中
        loadTinker();
        //反射创建代理ApplicationLike
        ensureDelegate();
        //在代理中执行重写Application#attachBaseContext
        applicationLike.onBaseContextAttached(base);
        //reset save mode
        if (useSafeMode) {
            //将启动次数写入SP中 这个是第一次 为 0
            String processName = ShareTinkerInternals.getProcessName(this);
            String preferName = ShareConstants.TINKER_OWN_PREFERENCE_CONFIG + processName;
            SharedPreferences sp = getSharedPreferences(preferName, Context.MODE_PRIVATE);
            sp.edit().putInt(ShareConstants.TINKER_SAFE_MODE_COUNT, 0).commit();
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        //方法设置处理程序时调用线程突然终止默认由于未捕获到异常，并没有其他的处理程序被定义为该线程
        Thread.setDefaultUncaughtExceptionHandler(new TinkerUncaughtHandler(this));
        //主要是加载补丁和代理Like调用
        onBaseContextAttached(base);
    }

    private void loadTinker() {
        //disable tinker, not need to install
        if (tinkerFlags == TINKER_DISABLE) {
            return;
        }
        tinkerResultIntent = new Intent();
        try {
            //reflect tinker loader, because loaderClass may be define by user!
            Class<?> tinkerLoadClass = Class.forName(loaderClassName, false, getClassLoader());

            Method loadMethod = tinkerLoadClass.getMethod(TINKER_LOADER_METHOD, TinkerApplication.class, int.class, boolean.class);
            Constructor<?> constructor = tinkerLoadClass.getConstructor();
            //反射执行TinkerLoader的tryLoad(TinkerApplication app, int tinkerFlag, boolean tinkerLoadVerifyFlag)方法
            tinkerResultIntent = (Intent) loadMethod.invoke(constructor.newInstance(), this, tinkerFlags, tinkerLoadVerifyFlag);
        } catch (Throwable e) {
            //has exception, put exception error code
            ShareIntentUtil.setIntentReturnCode(tinkerResultIntent, ShareConstants.ERROR_LOAD_PATCH_UNKNOWN_EXCEPTION);
            tinkerResultIntent.putExtra(INTENT_PATCH_EXCEPTION, e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureDelegate();
        applicationLike.onCreate();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        //当终止应用程序对象时调用，不保证一定被调用，当程序是被内核终止以便为其他应用程序释放资源，那
        //么将不会提醒，并且不调用应用程序的对象的onTerminate方法而直接终止进程
        if (applicationLike != null) {
            applicationLike.onTerminate();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        //当后台程序已经终止资源还匮乏时会调用这个方法。好的应用程序一般会在这个方法里面释放一些不必
        //要的资源来应付当后台程序已经终止，前台应用程序内存还不够时的情况。
        if (applicationLike != null) {
            applicationLike.onLowMemory();
        }
    }

    @TargetApi(14)
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (applicationLike != null) {
            applicationLike.onTrimMemory(level);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        //配置改变时触发这个方法
        if (applicationLike != null) {
            applicationLike.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public Resources getResources() {
        Resources resources = super.getResources();
        if (applicationLike != null) {
            return applicationLike.getResources(resources);
        }
        return resources;
    }

    @Override
    public ClassLoader getClassLoader() {
        ClassLoader classLoader = super.getClassLoader();
        if (applicationLike != null) {
            return applicationLike.getClassLoader(classLoader);
        }
        return classLoader;
    }

    @Override
    public AssetManager getAssets() {
        AssetManager assetManager = super.getAssets();
        if (applicationLike != null) {
            return applicationLike.getAssets(assetManager);
        }
        return assetManager;
    }

    @Override
    public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        if (applicationLike != null) {
            return applicationLike.getSystemService(name, service);
        }
        return service;
    }

    public void setUseSafeMode(boolean useSafeMode) {
        this.useSafeMode = useSafeMode;
    }
}
