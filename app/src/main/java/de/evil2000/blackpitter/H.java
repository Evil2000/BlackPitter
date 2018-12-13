package de.evil2000.blackpitter;

import android.util.Log;

public class H {

    /**
     * Get the method name for a depth in call stack.
     *
     * @param c The class which includes the currently executed function.
     * @return method name
     */
    private static String getFunc(Class<?> c) {
        final StackTraceElement[] ste = Thread.currentThread().getStackTrace();
        for (int i = 1; i < ste.length; i++) {
            StackTraceElement e = ste[i];
            //Log.d("__FUNC__()", e.getClassName() + " " + e.getMethodName() + " (" + e.getFileName() + ":" + e.getLineNumber() + ")");
            if (c.getName().equals(e.getClassName()) && !c.getName().equals(ste[i + 1].getClassName())) {
                //return e.getMethodName();
                //return e.getClassName() + "." + e.getMethodName() + "(" + e.getFileName() + ":" + e.getLineNumber() + ")";
                return ste[i + 1].getMethodName() + "(" + ste[i + 1].getFileName() + ":" + ste[i + 1].getLineNumber() + ")";
            }

        }
        return "";
    }

    static void logW(String msg) {
        Log.w(getFunc(H.class), msg);
    }

    static void logD(String msg) {
        Log.d(getFunc(H.class), msg);
    }

    static void logE(String msg) {
        Log.e(getFunc(H.class), msg);
    }

    static void logI(String msg) {
        Log.i(getFunc(H.class), msg);
    }

    static void logV(String msg) {
        Log.v(getFunc(H.class), msg);
    }

    static void logWtf(String msg) {
        Log.wtf(getFunc(H.class), msg);
    }
}
