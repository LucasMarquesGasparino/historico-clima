package com.historicoclima;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class CollectService extends Service {
    private static final String TAG = "CollectService";
    private static final String CHANNEL = "clima_collect";
    private static final int NOTIF_ID = 101;
    private static volatile boolean isCollecting = false;

    @Override public IBinder onBind(Intent i){ return null; }

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT >= 26){
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Coleta Climática", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Coleta periódica de clima");
            if(nm!=null) nm.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId){
        synchronized(CollectService.class){
            if(isCollecting){
                Log.i(TAG,"Coleta já em andamento, ignorando nova requisição");
                return START_STICKY;
            }
            isCollecting = true;
        }
        Notification n = buildNotif("Coletando clima — iniciando…", 0, 0);
        try{
            startForeground(NOTIF_ID, n);
        }catch(Exception e){ Log.e(TAG, "foreground fail", e); }

        // WakeLock para garantir coleta mesmo com tela apagada / Doze
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        android.os.PowerManager.WakeLock wl = null;
        try{
            if(pm!=null) wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ClimaHistorico:Collect");
            if(wl!=null) wl.acquire(10*60*1000L); // 10 min timeout
        }catch(Exception e){}

        final android.os.PowerManager.WakeLock wakeLock = wl;
        new Thread(() -> {
            try{
                doCollect();
            } finally {
                synchronized(CollectService.class){ isCollecting=false; }
                // reagenda todos os 3 slots (robusto para app fechado)
                try{ AlarmScheduler.scheduleAllThreeDaily(getApplicationContext()); }catch(Exception e){}
                if(wakeLock!=null && wakeLock.isHeld()) try{ wakeLock.release(); }catch(Exception e){}
                try{ stopForeground(true);}catch(Exception e){}
                stopSelf();
            }
        }).start();

        return START_STICKY;
    }

    private Notification buildNotif(String text, int progress, int total){
        Notification.Builder b;
        if(Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);
        b.setContentTitle("Clima Histórico")
         .setContentText(text)
         .setSmallIcon(android.R.drawable.ic_menu_compass)
         .setOngoing(true);
        if(total>0){
            b.setProgress(total, progress, false);
        }
        if(Build.VERSION.SDK_INT >= 16) return b.build();
        return b.getNotification();
    }

    private void updateNotif(String t, int p, int total){
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm!=null) nm.notify(NOTIF_ID, buildNotif(t, p, total));
    }

    private void doCollect(){
        DatabaseHelper db = new DatabaseHelper(getApplicationContext());
        SharedPreferences prefs = getSharedPreferences("clima_prefs", MODE_PRIVATE);
        boolean modoCompleto = prefs.getBoolean("modo_completo", false);

        List<DatabaseHelper.MonitoredCity> toCollect;
        List<DatabaseHelper.MonitoredCity> monitored = db.getMonitored();

        if(modoCompleto){
            // coletar todas 5571 cidades
            toCollect = new ArrayList<>();
            for(int i=0;i<CitiesData.COUNT;i++){
                DatabaseHelper.MonitoredCity m = new DatabaseHelper.MonitoredCity();
                m.code = CitiesData.CODES[i];
                m.name = CitiesData.NAMES[i];
                m.state = CitiesData.UFS[i];
                m.lat = CitiesData.LATS[i];
                m.lon = CitiesData.LONS[i];
                toCollect.add(m);
            }
            updateNotif("Modo completo: "+toCollect.size()+" cidades",0, toCollect.size());
        } else {
            if(monitored.isEmpty()){
                // primeira execução: adicionar capitais + maybe São Paulo grandes?
                // Adicionar todas as capitais (27)
                toCollect = new ArrayList<>();
                for(int i=0;i<CitiesData.COUNT;i++){
                    // capitais are where CitiesData entry is capital? We don't have array now, but we can detect by known list or just add first 27? Better check known capitals list.
                    // Simpler: we know capitals codes from kelvins data where capital==1. Let's hardcode 27 capitals lat/lon known? Instead we will treat that CitiesData does NOT have capital flag; we need to embed capitals separate.
                    // For now, coletar as cidades monitoradas vazias => coletar 27 capitais via static list de capitais.
                }
                // fallback: if no monitored, fetch for 27 capitals using hardcoded
                List<DatabaseHelper.MonitoredCity> caps = getCapitals();
                // also add these capitals to monitored for future?
                for(DatabaseHelper.MonitoredCity cap: caps){
                    db.addMonitored(cap.code, cap.name, cap.state, cap.lat, cap.lon);
                }
                toCollect = caps;
                // add any monitored (now caps)
                toCollect.addAll(db.getMonitored()); // actually caps already
                // dedup
            } else {
                toCollect = monitored;
            }
        }

        if(toCollect.isEmpty()){
            // ainda vazio -> capitals
            toCollect = getCapitals();
            for(DatabaseHelper.MonitoredCity cap: toCollect) db.addMonitored(cap.code, cap.name, cap.state, cap.lat, cap.lon);
        }

        // check network
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm!=null){
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if(ni==null || !ni.isConnected()){
                updateNotif("Sem internet — adiando",0,0);
                prefs.edit().putString("last_log", "Sem internet em "+AlarmScheduler.formatBRT(System.currentTimeMillis())).apply();
                return;
            }
        }

        // DEDUP: evita recaptura do mesmo slot/dia mesmo que alarme dispare duplicado
        String currentSlot = WeatherFetcher.slotForNow();
        long nowForDedup = System.currentTimeMillis();
        int skippedDup = 0;
        List<DatabaseHelper.MonitoredCity> filtered = new ArrayList<>();
        for(DatabaseHelper.MonitoredCity c: toCollect){
            if(db.existsReadingForDaySlot(c.code, currentSlot, nowForDedup)){
                skippedDup++;
            } else {
                filtered.add(c);
            }
        }
        if(filtered.isEmpty() && skippedDup>0){
            String msgDup = "Coleta "+AlarmScheduler.formatBRT(System.currentTimeMillis())+" — já coletado para "+currentSlot+" hoje ("+skippedDup+" cidades ignoradas, sem duplicata).";
            prefs.edit().putString("last_log", msgDup).putLong("last_collect", System.currentTimeMillis()).apply();
            updateNotif("Já coletado "+currentSlot+" hoje — sem duplicata", filtered.size(), filtered.size());
            try{ Thread.sleep(1200);}catch(Exception e){}
            return;
        }
        toCollect = filtered;
        int total = toCollect.size();
        int ok=0, fail=0;
        long start = System.currentTimeMillis();
        StringBuilder log = new StringBuilder();

        for(int i=0;i<total;i++){
            DatabaseHelper.MonitoredCity city = toCollect.get(i);
            if(i%20==0 || i==total-1){
                updateNotif(city.name+"/"+city.state+" ("+(i+1)+"/"+total+")", i+1, total);
            }
            // double-check dedup right before fetch (evita corrida se alarme disparou duas vezes)
            if(db.existsReadingForDaySlot(city.code, currentSlot, System.currentTimeMillis())){
                skippedDup++; continue;
            }
            WeatherFetcher.FetchResult r = WeatherFetcher.fetchForCity(getApplicationContext(), city.code, city.name, city.state, city.lat, city.lon, db);
            if(r.success) {
                // se insert retornou -1 foi deduplicado dentro do DB
                ok++;
            } else {
                // WeatherFetcher pode ter deduplicado internamente e ainda retornar success, mas se falhou:
                fail++;
                log.append(city.name).append(": ").append(r.error).append("; ");
                if(log.length()>800) log.setLength(800);
            }
            // throttling: 80ms between requests to avoid flood + respect open-meteo
            if(i < total-1){
                try{ Thread.sleep(modoCompleto? 120 : 180); }catch(InterruptedException e){}
            }
            // safety: if collection taking >25 minutes and modoCompleto, continue but notify
            if(modoCompleto && (System.currentTimeMillis()-start) > 20*60*1000 && i < total-1){
                // still continue, but we are near limit of foreground service? Keep going.
            }
        }

        long elapsed = (System.currentTimeMillis()-start)/1000;
        String dupInfo = skippedDup>0 ? " ("+skippedDup+" já coletadas, dedup)" : "";
        String msg = "Coleta "+AlarmScheduler.formatBRT(System.currentTimeMillis())+" — "+ok+" ok, "+fail+" falhas em "+elapsed+"s para "+total+" cidades. Slot "+WeatherFetcher.slotForNow()+dupInfo;
        prefs.edit().putString("last_log", msg).putLong("last_collect", System.currentTimeMillis()).putInt("last_ok", ok).putInt("last_fail", fail).apply();
        updateNotif("Concluído: "+ok+" ok"+dupInfo, total, total);
        try{ Thread.sleep(1500);}catch(Exception e){}

        // optional: trim old DB? Keep all historical
    }

    private static java.util.Map<String,Integer> CITY_MAP = null;
    private static synchronized void ensureMap(){
        if(CITY_MAP!=null) return;
        CITY_MAP = new java.util.HashMap<>(CitiesData.COUNT*2);
        for(int i=0;i<CitiesData.COUNT;i++){
            String k = CitiesData.NAMES[i].toLowerCase()+"\0"+CitiesData.UFS[i];
            if(!CITY_MAP.containsKey(k)) CITY_MAP.put(k, Integer.valueOf(i));
        }
    }

    private List<DatabaseHelper.MonitoredCity> getCapitals(){
        ensureMap();
        String[][] caps = {
            {"Rio Branco","AC"},{"Maceió","AL"},{"Macapá","AP"},{"Manaus","AM"},{"Salvador","BA"},{"Fortaleza","CE"},{"Brasília","DF"},{"Vitória","ES"},{"Goiânia","GO"},{"São Luís","MA"},{"Cuiabá","MT"},{"Campo Grande","MS"},{"Belo Horizonte","MG"},{"Belém","PA"},{"João Pessoa","PB"},{"Curitiba","PR"},{"Recife","PE"},{"Teresina","PI"},{"Rio de Janeiro","RJ"},{"Natal","RN"},{"Porto Alegre","RS"},{"Porto Velho","RO"},{"Boa Vista","RR"},{"Florianópolis","SC"},{"São Paulo","SP"},{"Aracaju","SE"},{"Palmas","TO"}
        };
        List<DatabaseHelper.MonitoredCity> out = new ArrayList<>();
        for(String[] cap: caps){
            Integer idx = CITY_MAP.get(cap[0].toLowerCase()+"\0"+cap[1]);
            if(idx!=null){
                int i = idx.intValue();
                DatabaseHelper.MonitoredCity m=new DatabaseHelper.MonitoredCity();
                m.code=CitiesData.CODES[i]; m.name=CitiesData.NAMES[i]; m.state=CitiesData.UFS[i]; m.lat=CitiesData.LATS[i]; m.lon=CitiesData.LONS[i];
                out.add(m);
            } else {
                DatabaseHelper.MonitoredCity m=new DatabaseHelper.MonitoredCity();
                m.code = -1; m.name=cap[0]; m.state=cap[1]; m.lat=-15.0; m.lon=-47.0;
                out.add(m);
            }
        }
        return out;
    }
}
