/*
 * Copyright (C) 2017 Beijing Didi Infinity Technology and Development Co.,Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.didi.virtualapk.internal;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.ContextThemeWrapper;

import com.didi.virtualapk.PluginManager;
import com.didi.virtualapk.utils.PluginUtil;
import com.didi.virtualapk.utils.ReflectUtil;


/**
 * 技术： 反射了两处
 *     ReflectUtil.setInstrumentation(activityThread, instrumentation);
 *     ReflectUtil.setHandlerCallback(this.mContext, instrumentation);
 *     其中handler callback：
 *      if (msg.callback != null) {
            handleCallback(msg);
        } else {
            if (mCallback != null) { // set this field: mCallback
                if (mCallback.handleMessage(msg)) { // VAInstrumentation返回false了，所以逻辑正常走
                    return;
                }
            }
            handleMessage(msg);
        }
 * Created by renyugang on 16/8/10.
 */
public class VAInstrumentation extends Instrumentation implements Handler.Callback {
    public static final String TAG = "VAInstrumentation";
    public static final int LAUNCH_ACTIVITY         = 100;

    private Instrumentation mBase;

    PluginManager mPluginManager;

    public VAInstrumentation(PluginManager pluginManager, Instrumentation base) {
        this.mPluginManager = pluginManager;
        this.mBase = base;
    }

    /**
     * 入口：启动Activity, 设置各种代理参数等等
     */
    public ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        mPluginManager.getComponentsHandler().transformIntentToExplicitAsNeeded(intent);
        // null component is an implicitly intent
        if (intent.getComponent() != null) {
            Log.i(TAG, String.format("execStartActivity[%s : %s]", intent.getComponent().getPackageName(),
                    intent.getComponent().getClassName()));
            // resolve intent with Stub Activity if needed
            this.mPluginManager.getComponentsHandler().markIntentIfNeeded(intent);
        }

        ActivityResult result = realExecStartActivity(who, contextThread, token, target,
                    intent, requestCode, options);

        return result;

    }

    private ActivityResult realExecStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        ActivityResult result = null;
        try {
            Class[] parameterTypes = {Context.class, IBinder.class, IBinder.class, Activity.class, Intent.class,
            int.class, Bundle.class};
            result = (ActivityResult)ReflectUtil.invoke(Instrumentation.class, mBase,
                    "execStartActivity", parameterTypes,
                    who, contextThread, token, target, intent, requestCode, options);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    /**
     * 执行顺序
     * 0 handleMessage--LAUNCH_ACTIVITY
     * 1 newActivity
     * 2 callActivityOnCreate
     */
    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            // 首先是cl.loadClass(className)，注意，我使用的demo,
            // 这里的className为com.didi.virtualapk.core.A$1，还有可能是其他的，
            // 但都会是在清单文件声明的stubActivity的名字
            // 调用cl.loadClass(className)去加载这些类，肯定是会爆出ClassNotFoundException异常的，
            // 因为这些类并不存在，他们只是在清单文件中起到占坑的作用，用来欺骗系统的，这里的设计确实非常巧妙，
            // 接下来自然走到catch里，catch里自然是去构建真正需要加载的TargetActivity
            cl.loadClass(className);
        } catch (ClassNotFoundException e) {
            LoadedPlugin plugin = this.mPluginManager.getLoadedPlugin(intent);
            // 拿到插件的Activity名称
            String targetClassName = PluginUtil.getTargetActivity(intent);

            Log.i(TAG, String.format("newActivity[%s : %s]", className, targetClassName));

            if (targetClassName != null) {
                // set classLoader.传入newActivity方法的是LoadedPlugin中的ClassLoader，
                // 这个ClassLoader已经是修改过的，可以加载插件和宿主里的类，关羽ClassLoader不懂的
                Activity activity = mBase.newActivity(plugin.getClassLoader(), targetClassName, intent);
                activity.setIntent(intent); // callActivityOnCreate use

                try {
                    // for 4.1+
                    ReflectUtil.setField(ContextThemeWrapper.class, activity, "mResources", plugin.getResources());
                } catch (Exception ignored) {
                    // ignored.
                }

                return activity;
            }
        }

        return mBase.newActivity(cl, className, intent);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        final Intent intent = activity.getIntent(); // above
        if (PluginUtil.isIntentFromPlugin(intent)) {
            Context base = activity.getBaseContext();
            try {
                LoadedPlugin plugin = this.mPluginManager.getLoadedPlugin(intent);
                ReflectUtil.setField(base.getClass(), base, "mResources", plugin.getResources());
                ReflectUtil.setField(ContextWrapper.class, activity, "mBase", plugin.getPluginContext());
                ReflectUtil.setField(Activity.class, activity, "mApplication", plugin.getApplication());
                ReflectUtil.setFieldNoException(ContextThemeWrapper.class, activity, "mBase", plugin.getPluginContext());

                // set screenOrientation
                ActivityInfo activityInfo = plugin.getActivityInfo(PluginUtil.getComponent(intent));
                if (activityInfo.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    activity.setRequestedOrientation(activityInfo.screenOrientation);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        mBase.callActivityOnCreate(activity, icicle);
    }

    @Override
    public boolean handleMessage(Message msg) {
        if (msg.what == LAUNCH_ACTIVITY) {
            // ActivityClientRecord r
            // final ActivityClientRecord r = (ActivityClientRecord) msg.obj;
            Object r = msg.obj;
            try {
                Intent intent = (Intent) ReflectUtil.getField(r.getClass(), r, "intent");
                // Changes the ClassLoader this Bundle uses when instantiating objects.
                //  @param loader An explicit ClassLoader to use when instantiating objects
                //  inside of the Bundle.
                // 并给intent设置了ClassLoader,这里为什么要设置一个ClassLoader？
                // 我想是因为在ActivityThread的performLaunchActivity方法会将其取出，
                // 然后设置进mInstrumentation.newActivity方法中
                intent.setExtrasClassLoader(VAInstrumentation.class.getClassLoader());
                ActivityInfo activityInfo = (ActivityInfo) ReflectUtil.getField(r.getClass(), r, "activityInfo");

                // ComponentsHandler设置了代理的各种标志参数
                if (PluginUtil.isIntentFromPlugin(intent)) {
                    int theme = PluginUtil.getTheme(mPluginManager.getHostContext(), intent);
                    if (theme != 0) {
                        Log.i(TAG, "resolve theme, current theme:" + activityInfo.theme + "  after :0x" + Integer.toHexString(theme));
                        activityInfo.theme = theme;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return false;
    }

    @Override
    public Context getContext() {
        return mBase.getContext();
    }

    @Override
    public Context getTargetContext() {
        return mBase.getTargetContext();
    }

    @Override
    public ComponentName getComponentName() {
        return mBase.getComponentName();
    }

}
