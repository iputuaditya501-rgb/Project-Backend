package com.example.pocketrunner;

interface IPrivileged {
    // Kode transaksi wajib untuk Shizuku UserService
    void destroy() = 16777114;

    String startOnDisplay(int displayId, String component);
    void forceStop(String pkg);
    void restoreSettings();

    oneway void injectTouch(int displayId, int action, float x, float y, long downTime, long eventTime);
    oneway void injectKey(int displayId, int keyCode);
}
