package com.aliothmoon.maameow;

import android.content.Intent;
import com.aliothmoon.maameow.ITouchEventCallback;
import com.aliothmoon.maameow.remote.PermissionGrantRequest;
import com.aliothmoon.maameow.remote.PermissionStateInfo;

/**
 * 提权进程（Shizuku / Root）对外暴露的能力。
 *
 * 飞书打卡版只保留「定时唤醒 + 自动解锁 + 权限放行」三件事：
 * MaaCore、虚拟屏、帧缓冲、音频静音等游戏侧能力已全部移除。
 */
interface RemoteService {

    oneway void destroy() = 16777114; // Destroy method defined by Shizuku server

    void exit() = 1; // Exit method defined by user

    String version() = 2;

    void test(in Map<String,String> map) = 3;

    int setup(String userDir, boolean isDebug) = 10;

    PermissionStateInfo grantPermissions(in PermissionGrantRequest request) = 11;

    int pid() = 22;

    int isAppAlive(String packageName) = 23;

    oneway void heartbeat(int pid) = 24;

    boolean startActivity(in Intent intent) = 27;

    boolean isPackageInstalled(String packageName) = 28;

    int unlock(String credential) = 33;

    int lockAndSleep() = 34;

    int testUnlock(String credential) = 35;

    oneway void startGestureRecord(int timeoutMs) = 36;

    String pollGestureRecord() = 37;

    oneway void cancelGestureRecord() = 38;

    int unlockWithGesture(String gestureJson) = 39;

    int testUnlockGesture(String gestureJson) = 40;

    // 触控注入：解锁手势回放依赖它
    oneway void touchDown(int x, int y, int contact) = 17;

    oneway void touchMove(int x, int y, int contact) = 18;

    oneway void touchUp(int x, int y, int contact) = 19;

    oneway void touchCancel() = 42;

    oneway void setTouchCallback(ITouchEventCallback callback) = 26;

    // HyperOS 发岛时短断 com.xiaomi.xmsf 网络（实况通知能力探测需要）
    boolean setPackageNetworkingEnabled(String packageName, boolean enabled) = 41;
}
