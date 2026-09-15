package com.historicoclima;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent){
        String a = intent.getAction();
        if(a==null) return;
        if(a.equals(Intent.ACTION_BOOT_COMPLETED) ||
           a.equals(Intent.ACTION_LOCKED_BOOT_COMPLETED) ||
           a.equals("android.intent.action.MY_PACKAGE_REPLACED") ||
           a.equals(Intent.ACTION_MY_PACKAGE_REPLACED) ||
           a.equals(Intent.ACTION_PACKAGE_REPLACED)){
            try{
                Log.i("BootReceiver","Boot recebido, reagendando 3 alarmes");
                AlarmScheduler.scheduleAllThreeDaily(ctx);
            }catch(Exception e){ Log.e("BootReceiver","schedule fail",e); }
        }
    }
}
