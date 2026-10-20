package com.darkcompany.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки и обновления приложения — снова слушаем сообщения. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { NotifyService.start(c); }
}
