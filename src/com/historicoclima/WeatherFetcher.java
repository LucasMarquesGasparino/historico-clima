package com.historicoclima;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.TimeZone;

public class WeatherFetcher {
    private static final String TAG = "WeatherFetcher";
    public static final TimeZone TZ_BRA = TimeZone.getTimeZone("America/Sao_Paulo");

    public static class FetchResult {
        public boolean success;
        public String error;
        public double temp;
        public double humidity;
        public double precip;
        public double fTemp;
        public double fHum;
        public double fPrec;
        public long targetTime;
    }

    public static FetchResult fetchForCity(Context ctx, int code, String name, String uf, double lat, double lon, DatabaseHelper db){
        FetchResult res = new FetchResult();
        HttpURLConnection conn=null;
        try{
            String urlStr = "https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,relative_humidity_2m,precipitation&hourly=temperature_2m,relative_humidity_2m,precipitation&forecast_hours=9&timezone=America%2FSao_Paulo";
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "ClimaHistorico/1.0");
            conn.setRequestMethod("GET");
            int codeHttp = conn.getResponseCode();
            if(codeHttp != 200){
                res.success=false; res.error="HTTP "+codeHttp;
                return res;
            }
            InputStream is = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = br.readLine()) != null) sb.append(line);
            br.close();
            String json = sb.toString();
            JSONObject root = new JSONObject(json);
            JSONObject current = root.getJSONObject("current");
            double temp = current.getDouble("temperature_2m");
            double hum = current.getDouble("relative_humidity_2m");
            double prec = current.getDouble("precipitation");

            JSONObject hourly = root.getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray hums = hourly.getJSONArray("relative_humidity_2m");
            JSONArray precs = hourly.getJSONArray("precipitation");

            // forecast for +8h : index 8 (0 is current hour). If array smaller use last.
            int idx = 8;
            if(idx >= temps.length()) idx = temps.length()-1;
            double fTemp = temps.getDouble(idx);
            double fHum = hums.getDouble(idx);
            double fPrec = precs.getDouble(idx);
            String targetTimeStr = times.getString(idx); // e.g. "2026-08-31T22:00"
            long targetMillis = parseSaoPauloTime(targetTimeStr);
            // fallback to issued +8h if parse fails
            if(targetMillis==0) targetMillis = System.currentTimeMillis() + 8L*3600*1000;

            res.temp = temp; res.humidity = hum; res.precip = prec;
            res.fTemp = fTemp; res.fHum = fHum; res.fPrec = fPrec;
            res.targetTime = targetMillis;

            // slot determination
            String slot = slotForNow();

            long now = System.currentTimeMillis();
            // inserts
            if(db != null){
                db.insertReading(code, name, uf, now, slot, temp, hum, prec);
                db.insertForecast(code, name, uf, now, targetMillis, slot, fTemp, fHum, fPrec);
            }

            res.success = true;
            return res;
        }catch(Exception e){
            Log.e(TAG, "fetch error", e);
            res.success=false; res.error=e.getMessage();
            return res;
        }finally{
            if(conn!=null) conn.disconnect();
        }
    }

    private static long parseSaoPauloTime(String iso){
        try{
            // iso is "2026-08-31T22:00" no seconds, timezone is Sao Paulo.
            // Parse manually
            int year = Integer.parseInt(iso.substring(0,4));
            int month = Integer.parseInt(iso.substring(5,7));
            int day = Integer.parseInt(iso.substring(8,10));
            int hour = Integer.parseInt(iso.substring(11,13));
            int minute = iso.length()>=16? Integer.parseInt(iso.substring(14,16)):0;
            Calendar cal = Calendar.getInstance(TZ_BRA);
            cal.set(year, month-1, day, hour, minute, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }catch(Exception e){ return 0; }
    }

    public static String slotForNow(){
        Calendar cal = Calendar.getInstance(TZ_BRA);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        // nearest of 1,9,15
        int d1 = Math.abs(h-1); if(d1>12) d1=24-d1;
        int d9 = Math.abs(h-9);
        int d15 = Math.abs(h-15);
        // handle wrap: 1 is early morning, distance to 1 from 23h should be 2, etc.
        // simpler: bucket by range
        if(h>=0 && h<5) return "01h";
        if(h>=5 && h<12) return "09h";
        if(h>=12 && h<20) return "15h";
        return "01h";
    }

    public static String slotForMillis(long millis){
        Calendar cal = Calendar.getInstance(TZ_BRA);
        cal.setTimeInMillis(millis);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        if(h>=0 && h<5) return "01h";
        if(h>=5 && h<12) return "09h";
        if(h>=12 && h<20) return "15h";
        return "01h";
    }
}
