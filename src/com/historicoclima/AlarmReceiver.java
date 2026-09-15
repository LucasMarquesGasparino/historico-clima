package com.historicoclima;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent){
        final PendingResult result = goAsync();
        new Thread(() -> {
            try{
                String action = intent != null ? intent.getAction() : null;
                if("com.historicoclima.WATCHDOG".equals(action)){
                    // Watchdog: apenas garante que os 3 alarmes exatos continuam agendados (resiliência para app fechado / Doze)
                    Log.i("AlarmReceiver","Watchdog disparado em "+AlarmScheduler.formatBRT(System.currentTimeMillis())+" — reagendando 3 slots");
                    try{
                        AlarmScheduler.scheduleAllThreeDaily(ctx);
                    }catch(Exception e){ Log.e("AlarmReceiver","watchdog reschedule fail",e); }
                    // Opcional: se última coleta está muito atrasada (>12h), dispara coleta de recuperação
                    long last = ctx.getSharedPreferences("clima_prefs", Context.MODE_PRIVATE).getLong("last_collect", 0);
                    long now = System.currentTimeMillis();
                    if(last!=0 && (now - last) > 12*60*60*1000L){
                        Log.i("AlarmReceiver","Watchdog: coleta atrasada >12h, disparando recuperação");
                        Intent svc = new Intent(ctx, CollectService.class);
                        svc.putExtra("trigger_slot", "watchdog-recovery");
                        if(Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                        else ctx.startService(svc);
                    }
                    return;
                }
                String slot = intent != null && intent.hasExtra("slot_hour") ? String.valueOf(intent.getIntExtra("slot_hour", -1)) : "next";
                Log.i("AlarmReceiver","Alarme disparado slot="+slot+" em "+AlarmScheduler.formatBRT(System.currentTimeMillis())+" action="+action);
                Intent svc = new Intent(ctx, CollectService.class);
                svc.putExtra("trigger_slot", slot);
                if(Build.VERSION.SDK_INT >= 26){
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
            }catch(Exception e){
                Log.e("AlarmReceiver","start service failed", e);
                try{ AlarmScheduler.scheduleAllThreeDaily(ctx); }catch(Exception ex){}
            } finally {
                try{ result.finish(); }catch(Exception e){}
            }
        }).start();
    }
}
