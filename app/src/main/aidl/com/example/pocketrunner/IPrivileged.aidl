package com.example.pocketrunner;

import android.view.Surface;

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

    // Pindahkan app dari layar utama ke layar virtual TANPA restart. true kalau berhasil.
    boolean pullToDisplay(String pkg, String component, int displayId) = 9;

    // Bawa activity kita sendiri ke depan di layar utama
    void bringToFront(String component) = 10;

    // Layar virtual TRUSTED dibuat di proses shell: activity yang dibuka app itu sendiri
    // (mis. setelah loading Roblox) tidak lagi dilempar ke layar utama. Return displayId, atau -1.
    int createDisplay(int width, int height, int dpi, in Surface surface) = 11;
    void setDisplaySurface(in Surface surface) = 12;
    void resizeDisplay(int width, int height, int dpi) = 13;
    void releaseDisplay() = 14;
    String displayError() = 15;
}
