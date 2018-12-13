package de.evil2000.blackpitter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Debug;

public class StarterReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        intent.setClass(context,SearchAndDownload.class);
        context.startService(intent);
    }
}
