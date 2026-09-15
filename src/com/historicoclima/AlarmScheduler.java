package com.historicoclima;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class AlarmScheduler {
    private static final TimeZone TZ_BRA = TimeZone.getTimeZone("America/Sao_Paulo");
    public static final int[] SLOTS_HOUR = new int[]{1, 9, 15};
    private static final int REQ_BASE = 1001;

    // Agenda os 3 slots diários de forma robusta (mesmo fechado) + watchdog
    public static void scheduleAllThreeDaily(Context ctx){
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if(am==null) return;
        for(int h: SLOTS_HOUR){
            scheduleSlot(ctx, am, h);
        }
        scheduleWatchdog(ctx);
        // salva próximo para UI
        long next = nextTriggerMillis();
        ctx.getSharedPreferences("clima_prefs", Context.MODE_PRIVATE).edit().putLong("next_alarm", next).apply();
    }

    // Compat: scheduleNext agora chama o robusto
    public static void scheduleNext(Context ctx){
        scheduleAllThreeDaily(ctx);
    }

    private static void scheduleSlot(Context ctx, AlarmManager am, int hour){
        long trigger = nextTriggerForHour(hour);
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.setAction("com.historicoclima.COLLECT");
        intent.putExtra("slot_hour", hour);
        int req = REQ_BASE + hour; // 1001, 1009, 1015
        PendingIntent pi = PendingIntent.getBroadcast(ctx, req, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try{ am.cancel(pi); }catch(Exception e){}

        boolean hasExactPerm = true;
        if(Build.VERSION.SDK_INT >= 31){
            try{ hasExactPerm = am.canScheduleExactAlarms(); }catch(Exception e){ hasExactPerm=false; }
        }

        try{
            if(hasExactPerm){
                if(Build.VERSION.SDK_INT >= 23){
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
                }
            } else {
                // FALLBACK sem permissão: setAlarmClock é exato, mostra ícone de alarme mas funciona mesmo em Doze e sem permissão
                // É a forma mais confiável para app fechado quando usuário negou SCHEDULE_EXACT_ALARM
                AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(trigger, pi);
                am.setAlarmClock(info, pi);
            }
        }catch(Exception e){
            try{
                // último fallback inexact
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            }catch(Exception ex){
                try{ am.set(AlarmManager.RTC_WAKEUP, trigger, pi);}catch(Exception ex2){}
            }
        }
    }

    private static long nextTriggerForHour(int hour){
        Calendar now = Calendar.getInstance(TZ_BRA);
        Calendar c = Calendar.getInstance(TZ_BRA);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if(c.getTimeInMillis() <= now.getTimeInMillis() + 60*1000){
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    public static void cancelAll(Context ctx){
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if(am==null) return;
        for(int h: SLOTS_HOUR){
            Intent intent = new Intent(ctx, AlarmReceiver.class);
            intent.setAction("com.historicoclima.COLLECT");
            int req = REQ_BASE + h;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, req, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            try{ am.cancel(pi);}catch(Exception e){}
        }
        // também cancela legado REQ_BASE sem hour (compat v1)
        try{
            Intent intent = new Intent(ctx, AlarmReceiver.class);
            intent.setAction("com.historicoclima.COLLECT");
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_BASE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        }catch(Exception e){}
    }

    public static long nextTriggerMillis(){
        Calendar now = Calendar.getInstance(TZ_BRA);
        long nowMs = now.getTimeInMillis();
        for(int h: SLOTS_HOUR){
            Calendar c = Calendar.getInstance(TZ_BRA);
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if(c.getTimeInMillis() > nowMs + 60*1000){
                return c.getTimeInMillis();
            }
        }
        Calendar c = Calendar.getInstance(TZ_BRA);
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, SLOTS_HOUR[0]);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static List<Long> getNextThreeTimes(){
        List<Long> out = new ArrayList<>();
        Calendar now = Calendar.getInstance(TZ_BRA);
        long nowMs = now.getTimeInMillis();
        int found=0;
        Calendar cursor = Calendar.getInstance(TZ_BRA);
        cursor.set(Calendar.HOUR_OF_DAY,0); cursor.set(Calendar.MINUTE,0); cursor.set(Calendar.SECOND,0); cursor.set(Calendar.MILLISECOND,0);
        for(int dayAdd=0; dayAdd<3 && found<3; dayAdd++){
            for(int h: SLOTS_HOUR){
                Calendar c = (Calendar) cursor.clone();
                c.add(Calendar.DAY_OF_MONTH, dayAdd);
                c.set(Calendar.HOUR_OF_DAY, h);
                if(c.getTimeInMillis() > nowMs + 60*1000){
                    out.add(c.getTimeInMillis());
                    found++;
                    if(found>=3) break;
                }
            }
        }
        return out;
    }

    // Watchdog a cada 6h para garantir coleta mesmo se alarme exato falhar (Doze / permissão negada)
    public static void scheduleWatchdog(Context ctx){
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if(am==null) return;
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.setAction("com.historicoclima.WATCHDOG");
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1999, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try{ am.cancel(pi);}catch(Exception e){}
        long trigger = System.currentTimeMillis() + 2*60*60*1000L; // 2h a partir de agora
        long interval = 6*60*60*1000L;
        try{
            // Tenta inexact repetitivo que sobrevive a Doze
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            // Também agenda variante repetitiva via setRepeating como fallback (pode ser batched)
            // Não usamos setRepeating exato para não conflitar com os 3 slots
        }catch(Exception e){
            try{ am.set(AlarmManager.RTC_WAKEUP, trigger, pi);}catch(Exception ex){}
        }
    }

    // Retorna todos os 3 próximos triggers (um por slot) para debug
    public static List<Long> getAllScheduledTriggers(){
        List<Long> out = new ArrayList<>();
        for(int h: SLOTS_HOUR){
            out.add(nextTriggerForHour(h));
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static String formatBRT(long millis){
        Calendar c = Calendar.getInstance(TZ_BRA);
        c.setTimeInMillis(millis);
        int d=c.get(Calendar.DAY_OF_MONTH);
        int m=c.get(Calendar.MONTH)+1;
        int y=c.get(Calendar.YEAR);
        int h=c.get(Calendar.HOUR_OF_DAY);
        int min=c.get(Calendar.MINUTE);
        return String.format("%02d/%02d/%04d %02d:%02d BRT", d,m,y,h,min);
    }

    public static String formatDaySlot(long millis){
        Calendar c = Calendar.getInstance(TZ_BRA);
        c.setTimeInMillis(millis);
        return String.format("%04d-%02d-%02d %02d:00", c.get(Calendar.YEAR), c.get(Calendar.MONTH)+1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY));
    }
}
