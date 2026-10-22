package com.example.pocketrunner;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * Context palsu atas nama "com.android.shell" supaya DisplayManager (dibuat di proses Shizuku,
 * uid 2000) lolos pengecekan paket saat membuat layar virtual trusted.
 * getOpPackageName() adalah hidden API, jadi sengaja tanpa @Override.
 */
public class ShellContext extends ContextWrapper {
    public ShellContext() { super(null); }

    @Override public String getPackageName() { return "com.android.shell"; }

    public String getOpPackageName() { return "com.android.shell"; }

    @Override public String getAttributionTag() { return null; }

    @Override public Context getApplicationContext() { return this; }
}
