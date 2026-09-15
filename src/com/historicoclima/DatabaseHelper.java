package com.historicoclima;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "historico_clima.db";
    private static final int DB_VERSION = 5;
    private static final java.util.TimeZone TZ_BRA = java.util.TimeZone.getTimeZone("America/Sao_Paulo");

    public DatabaseHelper(Context c){ super(c, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS readings ("+
                "id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "city_code INTEGER,"+
                "city_name TEXT,"+
                "state TEXT,"+
                "captured_at INTEGER,"+
                "slot TEXT,"+
                "temp REAL,"+
                "humidity REAL,"+
                "precip REAL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_readings_city_time ON readings(city_code, captured_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_readings_slot ON readings(slot)");

        db.execSQL("CREATE TABLE IF NOT EXISTS forecasts ("+
                "id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "city_code INTEGER,"+
                "city_name TEXT,"+
                "state TEXT,"+
                "issued_at INTEGER,"+
                "target_time INTEGER,"+
                "slot TEXT,"+
                "forecast_temp REAL,"+
                "forecast_humidity REAL,"+
                "forecast_precip REAL,"+
                "verified INTEGER DEFAULT 0,"+
                "actual_temp REAL,"+
                "actual_humidity REAL,"+
                "actual_precip REAL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_forecasts_city_target ON forecasts(city_code, target_time)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_forecasts_verified ON forecasts(verified)");

        db.execSQL("CREATE TABLE IF NOT EXISTS monitored ("+
                "city_code INTEGER PRIMARY KEY,"+
                "city_name TEXT,"+
                "state TEXT,"+
                "lat REAL,"+
                "lon REAL,"+
                "added_at INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV){
        if(oldV < 4){
            try{ db.execSQL("DROP TABLE IF EXISTS readings"); }catch(Exception e){}
            try{ db.execSQL("DROP TABLE IF EXISTS forecasts"); }catch(Exception e){}
            try{ db.execSQL("DROP TABLE IF EXISTS monitored"); }catch(Exception e){}
            onCreate(db);
        }
        if(oldV < 5){
            // v5: dedup por código em aplicação (1 captura por slot/dia). Nenhuma alteração de schema necessária,
            // apenas bump de versão para limpar caches se necessário. Mantemos dados existentes.
        }
    }

    // Verifica se já existe leitura para mesma cidade/slot/dia BRT
    public boolean existsReadingForDaySlot(int code, String slot, long capturedAt){
        String dayKey = dayKeyBRT(capturedAt);
        // Busca por leituras do mesmo dia BRT e mesmo slot
        // Calcula janela do dia BRT 00:00-23:59
        long[] bounds = dayBoundsBRT(capturedAt);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try{
            c = db.rawQuery("SELECT id FROM readings WHERE city_code=? AND slot=? AND captured_at BETWEEN ? AND ? LIMIT 1",
                    new String[]{String.valueOf(code), slot, String.valueOf(bounds[0]), String.valueOf(bounds[1])});
            boolean exists = c.moveToFirst();
            // Fallback extra: se slot já existe mas com dayKey diferente por causa de DST, verifica dayKey via query manual
            // Já coberto pela janela 24h, mas se leitura foi às 01:05 e outra às 01:55 mesmo dia/slot, deve bloquear duplicata
            return exists;
        }catch(Exception e){ return false; }
        finally{ if(c!=null) c.close(); }
    }

    public long insertReading(int code, String name, String uf, long capturedAt, String slot, double temp, double hum, double precip){
        // DEDUP: 1 captura por hora/dia por cidade (slot = 01h/09h/15h)
        if(existsReadingForDaySlot(code, slot, capturedAt)){
            // Já existe — não duplica, retorna -1
            return -1;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("city_code", code);
        cv.put("city_name", name);
        cv.put("state", uf);
        cv.put("captured_at", capturedAt);
        cv.put("slot", slot);
        cv.put("temp", temp);
        cv.put("humidity", hum);
        cv.put("precip", precip);
        long id = db.insert("readings", null, cv);
        // tentativa de verificação de forecasts pendentes
        verifyForecasts(code, capturedAt, temp, hum, precip);
        return id;
    }

    private void verifyForecasts(int cityCode, long capturedAt, double actualTemp, double actualHum, double actualPrec){
        SQLiteDatabase db = getWritableDatabase();
        // tolerance 3h = 10800000 ms (to cover 6h vs 8h mismatch). Also use 90 min for exact 8h but widen to 3h per spec 01/09/15 irregular
        long tol = 10800000L;
        long low = capturedAt - tol;
        long high = capturedAt + tol;
        Cursor c = null;
        try{
            c = db.rawQuery("SELECT id, target_time FROM forecasts WHERE city_code=? AND verified=0 AND target_time BETWEEN ? AND ? ORDER BY ABS(target_time - ?) ASC LIMIT 5", new String[]{String.valueOf(cityCode), String.valueOf(low), String.valueOf(high), String.valueOf(capturedAt)});
            List<Long> ids = new ArrayList<>();
            long bestId = -1;
            long bestDiff = Long.MAX_VALUE;
            while(c.moveToNext()){
                long id = c.getLong(0);
                long target = c.getLong(1);
                long diff = Math.abs(target - capturedAt);
                if(diff < bestDiff){ bestDiff = diff; bestId = id; }
            }
            if(bestId != -1){
                ContentValues cv = new ContentValues();
                cv.put("verified", 1);
                cv.put("actual_temp", actualTemp);
                cv.put("actual_humidity", actualHum);
                cv.put("actual_precip", actualPrec);
                db.update("forecasts", cv, "id=?", new String[]{String.valueOf(bestId)});
            }
        }catch(Exception e){}
        finally{ if(c!=null) c.close(); }
    }

    public boolean existsForecastForDaySlot(int code, String slot, long issuedAt){
        long[] bounds = dayBoundsBRT(issuedAt);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try{
            c = db.rawQuery("SELECT id FROM forecasts WHERE city_code=? AND slot=? AND issued_at BETWEEN ? AND ? LIMIT 1",
                    new String[]{String.valueOf(code), slot, String.valueOf(bounds[0]), String.valueOf(bounds[1])});
            return c.moveToFirst();
        }catch(Exception e){ return false; }
        finally{ if(c!=null) c.close(); }
    }

    public long insertForecast(int code, String name, String uf, long issuedAt, long targetTime, String slot, double fTemp, double fHum, double fPrec){
        // DEDUP: 1 previsão por slot/dia
        if(existsForecastForDaySlot(code, slot, issuedAt)){
            return -1;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("city_code", code);
        cv.put("city_name", name);
        cv.put("state", uf);
        cv.put("issued_at", issuedAt);
        cv.put("target_time", targetTime);
        cv.put("slot", slot);
        cv.put("forecast_temp", fTemp);
        cv.put("forecast_humidity", fHum);
        cv.put("forecast_precip", fPrec);
        cv.put("verified", 0);
        return db.insert("forecasts", null, cv);
    }

    // Helpers BRT
    private String dayKeyBRT(long millis){
        java.util.Calendar c = java.util.Calendar.getInstance(TZ_BRA);
        c.setTimeInMillis(millis);
        return String.format("%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH)+1, c.get(java.util.Calendar.DAY_OF_MONTH));
    }
    private long[] dayBoundsBRT(long millis){
        java.util.Calendar c = java.util.Calendar.getInstance(TZ_BRA);
        c.setTimeInMillis(millis);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long start = c.getTimeInMillis();
        c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        long end = c.getTimeInMillis() - 1;
        return new long[]{start, end};
    }

    public static class Reading {
        public long id; public int cityCode; public String cityName; public String state; public long capturedAt; public String slot; public double temp; public double humidity; public double precip;
    }

    public static class Forecast {
        public long id; public int cityCode; public String cityName; public String state; public long issuedAt; public long targetTime; public String slot; public double fTemp; public double fHum; public double fPrec; public int verified; public double aTemp; public double aHum; public double aPrec;
        public double tempError(){ return Math.abs(fTemp - aTemp); }
        public double humError(){ return Math.abs(fHum - aHum); }
        public double precipError(){ return Math.abs(fPrec - aPrec); }
    }

    public List<Reading> getReadingsForCity(int cityCode, int limit){
        List<Reading> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try{
            c = db.rawQuery("SELECT id, city_code, city_name, state, captured_at, slot, temp, humidity, precip FROM readings WHERE city_code=? ORDER BY captured_at DESC LIMIT ?", new String[]{String.valueOf(cityCode), String.valueOf(limit)});
            while(c.moveToNext()){
                Reading r = new Reading();
                r.id=c.getLong(0); r.cityCode=c.getInt(1); r.cityName=c.getString(2); r.state=c.getString(3); r.capturedAt=c.getLong(4); r.slot=c.getString(5); r.temp=c.getDouble(6); r.humidity=c.getDouble(7); r.precip=c.getDouble(8);
                out.add(r);
            }
        }finally{ if(c!=null) c.close(); }
        return out;
    }

    public List<Reading> getReadingsForCityAsc(int cityCode, long sinceMillis){
        // Limite 200 + filtro 90 dias por padrão p/ fluidez (antes: full-scan sem LIMIT)
        long cutoff = sinceMillis;
        if(cutoff<=0) cutoff = System.currentTimeMillis() - 90L*24*3600*1000;
        List<Reading> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try{
            c = db.rawQuery("SELECT id, city_code, city_name, state, captured_at, slot, temp, humidity, precip FROM readings WHERE city_code=? AND captured_at>=? ORDER BY captured_at ASC LIMIT 200", new String[]{String.valueOf(cityCode), String.valueOf(cutoff)});
            while(c.moveToNext()){
                Reading r = new Reading();
                r.id=c.getLong(0); r.cityCode=c.getInt(1); r.cityName=c.getString(2); r.state=c.getString(3); r.capturedAt=c.getLong(4); r.slot=c.getString(5); r.temp=c.getDouble(6); r.humidity=c.getDouble(7); r.precip=c.getDouble(8);
                out.add(r);
            }
        }finally{ if(c!=null) c.close(); }
        return out;
    }

    public List<Forecast> getForecastsForCity(int cityCode, int limit){
        List<Forecast> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c=null;
        try{
            c=db.rawQuery("SELECT id, city_code, city_name, state, issued_at, target_time, slot, forecast_temp, forecast_humidity, forecast_precip, verified, actual_temp, actual_humidity, actual_precip FROM forecasts WHERE city_code=? ORDER BY issued_at DESC LIMIT ?", new String[]{String.valueOf(cityCode), String.valueOf(limit)});
            while(c.moveToNext()){
                Forecast f=new Forecast();
                f.id=c.getLong(0); f.cityCode=c.getInt(1); f.cityName=c.getString(2); f.state=c.getString(3); f.issuedAt=c.getLong(4); f.targetTime=c.getLong(5); f.slot=c.getString(6); f.fTemp=c.getDouble(7); f.fHum=c.getDouble(8); f.fPrec=c.getDouble(9); f.verified=c.getInt(10);
                f.aTemp=c.isNull(11)? Double.NaN : c.getDouble(11);
                f.aHum=c.isNull(12)? Double.NaN : c.getDouble(12);
                f.aPrec=c.isNull(13)? Double.NaN : c.getDouble(13);
                out.add(f);
            }
        }finally{ if(c!=null) c.close(); }
        return out;
    }

    public List<Forecast> getVerifiedForecasts(int cityCode){
        List<Forecast> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c=null;
        try{
            c=db.rawQuery("SELECT id, city_code, city_name, state, issued_at, target_time, slot, forecast_temp, forecast_humidity, forecast_precip, verified, actual_temp, actual_humidity, actual_precip FROM forecasts WHERE city_code=? AND verified=1 ORDER BY issued_at DESC LIMIT 100", new String[]{String.valueOf(cityCode)});
            while(c.moveToNext()){
                Forecast f=new Forecast();
                f.id=c.getLong(0); f.cityCode=c.getInt(1); f.cityName=c.getString(2); f.state=c.getString(3); f.issuedAt=c.getLong(4); f.targetTime=c.getLong(5); f.slot=c.getString(6); f.fTemp=c.getDouble(7); f.fHum=c.getDouble(8); f.fPrec=c.getDouble(9); f.verified=c.getInt(10);
                f.aTemp=c.getDouble(11); f.aHum=c.getDouble(12); f.aPrec=c.getDouble(13);
                out.add(f);
            }
        }finally{ if(c!=null) c.close(); }
        return out;
    }

    public int countReadings(){ SQLiteDatabase db=getReadableDatabase(); Cursor c=db.rawQuery("SELECT COUNT(*) FROM readings", null); try{ c.moveToFirst(); return c.getInt(0);}finally{ c.close(); } }
    public int countForecasts(){ SQLiteDatabase db=getReadableDatabase(); Cursor c=db.rawQuery("SELECT COUNT(*) FROM forecasts", null); try{ c.moveToFirst(); return c.getInt(0);}finally{ c.close(); } }
    public int countForecastsVerified(int cityCode){ SQLiteDatabase db=getReadableDatabase(); Cursor c=db.rawQuery("SELECT COUNT(*) FROM forecasts WHERE city_code=? AND verified=1", new String[]{String.valueOf(cityCode)}); try{ c.moveToFirst(); return c.getInt(0);}finally{ c.close(); } }

    // monitored
    public boolean isMonitored(int code){
        SQLiteDatabase db=getReadableDatabase();
        Cursor c=db.rawQuery("SELECT 1 FROM monitored WHERE city_code=?", new String[]{String.valueOf(code)});
        try{ return c.moveToFirst(); } finally{ c.close(); }
    }
    public void addMonitored(int code, String name, String uf, double lat, double lon){
        SQLiteDatabase db=getWritableDatabase();
        ContentValues cv=new ContentValues();
        cv.put("city_code", code); cv.put("city_name", name); cv.put("state", uf); cv.put("lat", lat); cv.put("lon", lon); cv.put("added_at", System.currentTimeMillis());
        db.insertWithOnConflict("monitored", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void removeMonitored(int code){ getWritableDatabase().delete("monitored","city_code=?", new String[]{String.valueOf(code)}); }
    public List<MonitoredCity> getMonitored(){
        List<MonitoredCity> out=new ArrayList<>();
        SQLiteDatabase db=getReadableDatabase();
        Cursor c=db.rawQuery("SELECT city_code, city_name, state, lat, lon FROM monitored ORDER BY state, city_name", null);
        try{
            while(c.moveToNext()){
                MonitoredCity m=new MonitoredCity();
                m.code=c.getInt(0); m.name=c.getString(1); m.state=c.getString(2); m.lat=c.getDouble(3); m.lon=c.getDouble(4);
                out.add(m);
            }
        }finally{ c.close(); }
        return out;
    }
    public int countMonitored(){ SQLiteDatabase db=getReadableDatabase(); Cursor c=db.rawQuery("SELECT COUNT(*) FROM monitored", null); try{ c.moveToFirst(); return c.getInt(0);}finally{ c.close(); }}

    public static class MonitoredCity{ public int code; public String name; public String state; public double lat; public double lon; }

    public void clearAll(){
        getWritableDatabase().delete("readings", null, null);
        getWritableDatabase().delete("forecasts", null, null);
    }
}
