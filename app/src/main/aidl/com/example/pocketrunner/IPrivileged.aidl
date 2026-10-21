package com.example.pocketrunner;

interface IPrivileged {
    // Kode transaksi wajib untuk Shizuku UserService
    void destroy() = 16777114;

    // AIDL mewajibkan semua method punya id jika salah satunya punya
    // freshStart=true -> app dimatikan dulu (am start -S) supaya tidak "ditarik" dari layar utama
    String startOnDisplay(int displayId, String component, boolean freshStart) = 1;
    void forceStop(String pkg) = 2;
    void restoreSettings() = 3;

    oneway void injectTouch(int displayId, int action, float x, float y, long downTime, long eventTime) = 4;
    oneway void injectKey(int displayId, int keyCode) = 5;

    String status() = 6;
    void setMuted(String pkg, boolean muted) = 7;

    // true kalau app terlanjur muncul di layar utama (bukan di layar virtual)
    boolean isOnDefaultDisplay(String pkg, int ourDisplayId) = 8;
}
