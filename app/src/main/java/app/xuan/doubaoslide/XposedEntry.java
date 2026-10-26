package app.xuan.doubaoslide;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口（{@code assets/xposed_init} 指向本类）。
 *
 * <p>去重：LSPosed 在同一个进程里可能用不同的 ClassLoader 加载模块，那样类里的静态量
 * 会各有一份、钩子会被装两遍。这里用 {@code System.setProperty} 做进程级标志
 * （System 由 boot classloader 加载，跨 ClassLoader 共享）。
 */
public final class XposedEntry implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.bytedance.android.doubaoime";
    private static final String HOOK_FLAG = "doubao.slide.hooked";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (System.getProperty(HOOK_FLAG) != null) {
            if (Log.DEBUG) Log.i("本进程已注入，跳过重复注入：" + lpparam.processName);
            return;
        }

        // 先占位再装配：同一进程里可能有多份 ClassLoader，装两次就会有两套静态状态
        // （手势状态、连接列表各一份），所以标志必须在装配之前立起来。
        System.setProperty(HOOK_FLAG, "1");
        boolean board;
        boolean slide;
        try {
            Log.always("注入 " + lpparam.processName + " pid=" + Process.myPid());
            // 先把设置读进来，再记心跳：设置页靠心跳回答"模块到底有没有注入"。
            Log.refresh();
            Log.heartbeat(lpparam.processName);
            board = BoardGuard.install(lpparam.classLoader);
            slide = SlideHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            // 抛出说明装配还没走完（多半在找类那一步），撤掉标志允许后续重试。
            System.clearProperty(HOOK_FLAG);
            Log.e("模块初始化失败", t);
            return;
        }
        if (!board || !slide) {
            // 半装配状态。这里**不撤标志**：撤了下一份 ClassLoader 会再装一遍，
            // 钩子与静态量各有两个副本，比缺一半更难查。用一行显眼的警告记下缺哪一半，
            // 键盘本身仍然照常工作（降级方向是对的，只是要能看见）。
            Log.w("模块未完整装配：棋盘守卫=" + board + " 手势闸门=" + slide
                    + "，标志保留、不再重装");
        }
    }
}
