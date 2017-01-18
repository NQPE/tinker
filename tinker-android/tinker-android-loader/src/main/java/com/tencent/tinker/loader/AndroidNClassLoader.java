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
import android.content.Context;
import android.os.Build;

import com.tencent.tinker.loader.shareutil.ShareReflectUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;

/**
 * Created by zhangshaowen on 16/7/24.
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
class AndroidNClassLoader extends PathClassLoader {
    /**
     * 我们知道，在Dalvik虚拟机中，总是在运行时通过JIT（Just-In—Time）把字节码文件编译成机器码文件再执行，
     * 这样跑起来程序就很慢，所在ART上，改为AOT（Ahead-Of—Time）提前编译，
     * 即在安装应用或OTA系统升级时提前把字节码编译成机器码，这样就可以直接执行了，提高了运行效率。
     * 但是AOT有个缺点就是每次执行的时间都太长了，并且占用的ROM空间又很大，
     * 所以在Android N上Google做了混合编译同时支持JIT和AOT。混合编译的作用简单来说，在应用运行时分析运行过的代码以及“热代码”，
     * 并将配置存储下来。在设备空闲与充电时，ART仅仅编译这份配置中的“热代码”。
     *简单来说，就是在应用安装和首次运行不做AOT编译，先让用户愉快的玩耍起来，
     * 然后把在运行中JIT解释执行的那部分代码收集起来，在手机空闲的时候通过dex2aot编译生成一份名为app image的base.art文件，
     * 然后在下次启动的时候一次性把app image加载进来到缓存，预先加载代替用时查找以提升应用的性能。
     *这种方式对热补丁的影响就是，app image中已经存在的类会被插入到ClassLoader的ClassTable，再次加载类时，
     * 直接从ClassTable中取而不会走DefineClass。假设base.art文件在补丁前已经存在，这里存在三种情况：
     *1.补丁修改的类都不appimage中；这种情况是最理想的，此时补丁机制依然有效；
     *2.补丁修改的类部分在appimage中；这种情况我们只能更新一部分的类，此时是最危险的。一部分类是新的，一部分类是旧的，app可能会出现地址错乱而出现crash。
     *3.补丁修改的类全部在appimage中；这种情况只是造成补丁不生效，app并不会因此造成crash。
     *Tinker的解决方案是，完全废弃掉PathClassloader，而采用一个新建Classloader来加载后续的所有类，即可达到将cache无用化的效果
     */
    static ArrayList<DexFile> oldDexFiles = new ArrayList<>();
    PathClassLoader originClassLoader;

    private AndroidNClassLoader(String dexPath, PathClassLoader parent) {
        super(dexPath, parent.getParent());
        originClassLoader = parent;
    }

    /**
     *新建一个AndroidNClassLoader 它的parent是originPathClassLoader。
     *注意，PathClassLoader的optimizedDirectory只能是null，这个后面还有用。
     *找到originPathClassLoader中的pathList 和 pathList中的类型为ClassLoader的definingContext。
     *替换definingContext为AndroidNClassLoader
     *将AndroidNClassLoader中的pathList替换为originPathClassLoader的pathList。
     * @param original
     * @return
     * @throws Exception
     */
    private static AndroidNClassLoader createAndroidNClassLoader(PathClassLoader original) throws Exception {
        //let all element ""
        AndroidNClassLoader androidNClassLoader = new AndroidNClassLoader("",  original);
        Field originPathList = ShareReflectUtil.findField(original, "pathList");
        Object originPathListObject = originPathList.get(original);
        //should reflect definingContext also
        Field originClassloader = ShareReflectUtil.findField(originPathListObject, "definingContext");
        originClassloader.set(originPathListObject, androidNClassLoader);
        //copy pathList
        Field pathListField = ShareReflectUtil.findField(androidNClassLoader, "pathList");
        //just use PathClassloader's pathList
        pathListField.set(androidNClassLoader, originPathListObject);

        //we must recreate dexFile due to dexCache
        //可能androidN上会存在dex缓存 所以需要把dexElements重新makePathElements一次新的来消除缓存
        List<File> additionalClassPathEntries = new ArrayList<>();
        Field dexElement = ShareReflectUtil.findField(originPathListObject, "dexElements");
        Object[] originDexElements = (Object[]) dexElement.get(originPathListObject);
        for (Object element : originDexElements) {
            DexFile dexFile = (DexFile) ShareReflectUtil.findField(element, "dexFile").get(element);
            additionalClassPathEntries.add(new File(dexFile.getName()));
            //protect for java.lang.AssertionError: Failed to close dex file in finalizer.
            oldDexFiles.add(dexFile);
        }
        Method makePathElements = ShareReflectUtil.findMethod(originPathListObject, "makePathElements", List.class, File.class,
            List.class);
        ArrayList<IOException> suppressedExceptions = new ArrayList<>();
        Object[] newDexElements = (Object[]) makePathElements.invoke(originPathListObject, additionalClassPathEntries, null, suppressedExceptions);
        dexElement.set(originPathListObject, newDexElements);
        return androidNClassLoader;
    }

    /**
     * 将application以及mPackageInfo中的ClassLoader都替换为AndroidNClassLoader
     * 作用是替换掉了mPackageInfo中的ClassLoader，mPackageInfo是LoadedApk的对象，代表了APK文件在内存中的表示，
     * 诸如Apk文件的代码和资源，甚至代码里面的Activity，Service等组件的信息我们都可以通过此对象获取。
     * @param application
     * @param reflectClassLoader
     * @throws Exception
     */
    private static void reflectPackageInfoClassloader(Application application, ClassLoader reflectClassLoader) throws Exception {
        String defBase = "mBase";
        String defPackageInfo = "mPackageInfo";
        String defClassLoader = "mClassLoader";

        Context baseContext = (Context) ShareReflectUtil.findField(application, defBase).get(application);
        Object basePackageInfo = ShareReflectUtil.findField(baseContext, defPackageInfo).get(baseContext);
        Field classLoaderField = ShareReflectUtil.findField(basePackageInfo, defClassLoader);
        Thread.currentThread().setContextClassLoader(reflectClassLoader);
        classLoaderField.set(basePackageInfo, reflectClassLoader);
    }

    public static AndroidNClassLoader inject(PathClassLoader originClassLoader, Application application) throws Exception {
        AndroidNClassLoader classLoader = createAndroidNClassLoader(originClassLoader);
        reflectPackageInfoClassloader(application, classLoader);
        return classLoader;
    }

//    public static String getLdLibraryPath(ClassLoader loader) throws Exception {
//        String nativeLibraryPath;
//
//        nativeLibraryPath = (String) loader.getClass()
//            .getMethod("getLdLibraryPath", new Class[0])
//            .invoke(loader, new Object[0]);
//
//        return nativeLibraryPath;
//    }

    public Class<?> findClass(String name) throws ClassNotFoundException {
        // loader class use default pathClassloader to load
        if (name != null && name.startsWith("com.tencent.tinker.loader.") && !name.equals("com.tencent.tinker.loader.TinkerTestDexLoad")) {
            return originClassLoader.loadClass(name);
        }
        return super.findClass(name);
    }

    @Override
    public String findLibrary(String name) {
        return super.findLibrary(name);
    }
}
