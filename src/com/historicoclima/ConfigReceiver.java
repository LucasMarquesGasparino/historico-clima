package com.historicoclima;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

public class ConfigReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent){
        if(intent==null) return;
        String a = intent.getAction();
        if(a==null) return;
        SharedPreferences prefs = ctx.getSharedPreferences("clima_prefs", Context.MODE_PRIVATE);
        if(a.equals("com.historicoclima.ENABLE_FULL")){
            boolean en = intent.getBooleanExtra("enable", true);
            prefs.edit().putBoolean("modo_completo", en).apply();
            String msg = en? "Modo completo ATIVADO (5571 cidades)":"Modo completo DESATIVADO (econômico)";
            Log.i("ConfigReceiver", msg);
            try{ Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); }catch(Exception e){}
            // reagendar se ativou
            if(en){
                // opcional: adicionar todas as cidades como monitoradas é feito pelo serviço, não precisa aqui
            }
            try{ AlarmScheduler.scheduleNext(ctx); }catch(Exception e){}
        } else if(a.equals("com.historicoclima.TRIGGER_COLLECT")){
            try{
                Intent svc = new Intent(ctx, CollectService.class);
                if(android.os.Build.VERSION.SDK_INT>=26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
            }catch(Exception e){ Log.e("ConfigReceiver","trigger fail",e); }
        } else if(a.equals("com.historicoclima.ADD_CITY")){
            int code = intent.getIntExtra("code", -1);
            if(code!=-1){
                int idx = CitiesData.indexOf(code);
                if(idx!=-1){
                    DatabaseHelper db = new DatabaseHelper(ctx);
                    db.addMonitored(code, CitiesData.NAMES[idx], CitiesData.UFS[idx], CitiesData.LATS[idx], CitiesData.LONS[idx]);
                    Log.i("ConfigReceiver","Cidade "+code+" adicionada");
                }
            }
        }
    }
}
