package com.example.pocketrunner;

interface IPrivileged {
    // Kode transaksi wajib untuk Shizuku UserService
    void destroy() = 16777114;

    // AIDL mewajibkan semua method punya id jika salah satunya punya
    String startOnDisplay(int displayId, String component) = 1;
    void forceStop(String pkg) = 2;
    void restoreSettings() = 3;

    oneway void injectTouch(int displayId, int action, float x, float y, long downTime, long eventTime) = 4;
    oneway void injectKey(int displayId, int keyCode) = 5;
}
