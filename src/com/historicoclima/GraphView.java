package com.historicoclima;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GraphView extends View {
    private String title="Gráfico";
    private String unit="";
    private List<String> xLabels = new ArrayList<>();
    private Map<String, float[]> series = new LinkedHashMap<>();
    private Map<String, Integer> colors = new HashMap<>();
    private float minY=0, maxY=100;
    private boolean showLegend=true;
    private boolean loading=false;

    private Paint paintGrid, paintAxis, paintText, paintLine, paintCircle, paintTitle;
    private Paint paintBg, paintEmpty, paintLegBox, paintLegText;
    private Path pathGrid, pathSeries;
    private String[] yLabelsCache = new String[5];
    private float density = 1f, scaledDensity = 1f;
    private static final Locale LOCALE_BR = new Locale("pt", "BR");
    private static final int FALLBACK_BLUE = Color.parseColor("#1E88E5");

    public GraphView(Context c){ super(c); init(); }
    public GraphView(Context c, AttributeSet a){ super(c,a); init(); }

    private void init(){
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        paintGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintGrid.setColor(Color.parseColor("#E0E7EF"));
        paintGrid.setStyle(Paint.Style.STROKE);
        paintGrid.setStrokeWidth(1f);
        paintGrid.setPathEffect(new DashPathEffect(new float[]{6,6},0));

        paintAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintAxis.setColor(Color.parseColor("#8AA0B8"));
        paintAxis.setStrokeWidth(2f);

        paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintText.setColor(Color.parseColor("#334455"));
        paintText.setTextSize(10*scaledDensity);
        paintText.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));

        paintTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintTitle.setColor(Color.parseColor("#0D2A4A"));
        paintTitle.setTextSize(13*scaledDensity);
        paintTitle.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));

        paintLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintLine.setStyle(Paint.Style.STROKE);
        paintLine.setStrokeWidth(2.2f*density);
        paintLine.setStrokeCap(Paint.Cap.ROUND);
        paintLine.setStrokeJoin(Paint.Join.ROUND);

        paintCircle = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintCircle.setStyle(Paint.Style.FILL);

        paintBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintBg.setColor(Color.WHITE);
        paintBg.setStyle(Paint.Style.FILL);

        paintEmpty = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintEmpty.setColor(Color.parseColor("#6B7C93"));
        paintEmpty.setTextSize(12*scaledDensity);

        paintLegBox = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintLegText = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintLegText.setTextSize(10*scaledDensity);
        paintLegText.setTextAlign(Paint.Align.LEFT);
        paintLegText.setColor(Color.parseColor("#334455"));

        pathGrid = new Path();
        pathSeries = new Path();
    }

    public void setData(String title, String unit, List<String> xLabels, Map<String,float[]> series, Map<String,Integer> colors, float minY, float maxY){
        this.loading = false;
        this.title=title; this.unit=unit;
        this.xLabels = xLabels!=null? xLabels: new ArrayList<String>();
        this.series = series!=null? series: new LinkedHashMap<String,float[]>();
        this.colors = colors!=null? colors: new HashMap<String,Integer>();
        this.minY=minY; this.maxY=maxY;
        if(maxY<=minY) this.maxY=minY+1;
        // Pré-formata labels Y (evita String.format no onDraw)
        for(int i=0;i<=4;i++){
            float v = this.minY + (this.maxY-this.minY)*i/4;
            yLabelsCache[i] = formatVal(v, this.unit);
        }
        invalidate();
    }

    public void setLoading(){
        this.loading = true;
        this.xLabels = new ArrayList<String>();
        this.series = new LinkedHashMap<String,float[]>();
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec){
        int w = MeasureSpec.getSize(wSpec);
        int modeH = MeasureSpec.getMode(hSpec);
        int h = (int)(220*density + .5f);
        if(modeH==MeasureSpec.EXACTLY) h = MeasureSpec.getSize(hSpec);
        else if(modeH==MeasureSpec.AT_MOST) h = Math.min(h, MeasureSpec.getSize(hSpec));
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        int W = getWidth();
        int H = getHeight();
        int padL = (int)(52*density+.5f);
        int padR = (int)(14*density+.5f);
        int padT = (int)(30*density+.5f);
        int padB = (int)(52*density+.5f);
        int chartW = W - padL - padR;
        int chartH = H - padT - padB;
        if(chartW<=10 || chartH<=10) return;

        float r14 = 14*density;
        canvas.drawRoundRect(0,0,W,H, r14, r14, paintBg);

        // title
        canvas.drawText(title + (unit.isEmpty()? "":" ("+unit+")"), padL, padT - 8*density, paintTitle);

        if(loading){
            canvas.drawText("Carregando…", padL, padT + chartH/2, paintEmpty);
            return;
        }

        // if no data
        if(xLabels.isEmpty() || series.isEmpty()){
            canvas.drawText("Sem dados ainda — aguarde as coletas 01h/09h/15h", padL, padT + chartH/2, paintEmpty);
            return;
        }

        // draw grid horizontal 4 lines + labels Y (cache)
        int gridLines=4;
        for(int i=0;i<=gridLines;i++){
            float y = padT + chartH * (1 - (float)i/gridLines);
            pathGrid.rewind();
            pathGrid.moveTo(padL, y);
            pathGrid.lineTo(padL+chartW, y);
            canvas.drawPath(pathGrid, paintGrid);
            paintText.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(yLabelsCache[i], padL - 6*density, y+ 4*density, paintText);
        }

        // X labels
        int n = xLabels.size();
        paintText.setTextAlign(Paint.Align.CENTER);
        int step = 1;
        if(n>12) step=2;
        if(n>24) step=3;
        if(n>40) step=5;
        for(int i=0;i<n;i++){
            if(i%step!=0 && i!=n-1) continue;
            float x = padL + chartW * (n==1? 0.5f : (float)i/(n-1));
            canvas.drawText(xLabels.get(i), x, padT+chartH+ 18*density, paintText);
            canvas.drawLine(x, padT+chartH, x, padT+chartH+ 4*density, paintAxis);
        }

        // axes
        canvas.drawLine(padL, padT, padL, padT+chartH, paintAxis);
        canvas.drawLine(padL, padT+chartH, padL+chartW, padT+chartH, paintAxis);

        float range = maxY - minY;
        if(range<=0) range = 1;
        float dotR = 3.2f*density;
        // draw series (1 círculo por ponto, sem Paint novo)
        for(Map.Entry<String,float[]> e: series.entrySet()){
            String slot = e.getKey();
            float[] vals = e.getValue();
            if(vals==null) continue;
            Integer c = colors.get(slot);
            int col = c!=null? c.intValue() : FALLBACK_BLUE;
            paintLine.setColor(col);
            paintCircle.setColor(col);
            pathSeries.rewind();
            boolean started=false;
            for(int i=0;i<n;i++){
                if(i>=vals.length) break;
                float v = vals[i];
                if(Float.isNaN(v)) { started=false; continue; }
                float x = padL + chartW * (n==1? 0.5f : (float)i/(n-1));
                float y = padT + chartH * (1 - (v - minY)/range);
                if(y < padT) y=padT;
                if(y > padT+chartH) y=padT+chartH;
                if(!started){
                    pathSeries.moveTo(x,y);
                    started=true;
                } else {
                    pathSeries.lineTo(x,y);
                }
            }
            canvas.drawPath(pathSeries, paintLine);
            for(int i=0;i<n;i++){
                if(i>=vals.length) break;
                float v=vals[i];
                if(Float.isNaN(v)) continue;
                float x = padL + chartW * (n==1? 0.5f : (float)i/(n-1));
                float y = padT + chartH * (1 - (v - minY)/range);
                if(y < padT || y > padT+chartH) continue;
                canvas.drawCircle(x,y, dotR, paintCircle);
            }
        }

        // marcadores máx/mín p/ temperatura (dataviz: insight sem tooltip)
        try{
            if(title.contains("Temperatura") && n>0){
                float gMax=-1e30f, gMin=1e30f;
                int iMax=-1, iMin=-1;
                for(Map.Entry<String,float[]> e: series.entrySet()){
                    float[] vals=e.getValue();
                    if(vals==null) continue;
                    for(int i=0;i<n && i<vals.length;i++){
                        float v=vals[i];
                        if(Float.isNaN(v)) continue;
                        if(v>gMax){ gMax=v; iMax=i; }
                        if(v<gMin){ gMin=v; iMin=i; }
                    }
                }
                if(iMax>=0){
                    float x = padL + chartW * (n==1?0.5f:(float)iMax/(n-1));
                    float y = padT + chartH * (1-(gMax-minY)/range);
                    canvas.drawCircle(x, y, dotR*1.7f, paintCircle);
                    paintLegText.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(formatVal(gMax, unit)+" máx", x, y-8*density, paintLegText);
                    paintLegText.setTextAlign(Paint.Align.LEFT);
                }
                if(iMin>=0 && iMin!=iMax){
                    float x = padL + chartW * (n==1?0.5f:(float)iMin/(n-1));
                    float y = padT + chartH * (1-(gMin-minY)/range);
                    canvas.drawCircle(x, y, dotR*1.4f, paintCircle);
                }
            }
        }catch(Exception ignored){}

        // legend abaixo do eixo (não cobre dados)
        if(showLegend && series.size()>1){
            float lx = padL;
            float ly = padT+chartH+ 34*density;
            for(Map.Entry<String,float[]> e: series.entrySet()){
                String slot=e.getKey();
                Integer c = colors.get(slot);
                int col = c!=null? c.intValue() : FALLBACK_BLUE;
                paintLegBox.setColor(col);
                canvas.drawRoundRect(lx, ly- 7*density, lx+ 10*density, ly+ 3*density, 2*density, 2*density, paintLegBox);
                canvas.drawText(slot, lx+ 14*density, ly+ 2*density, paintLegText);
                lx += paintLegText.measureText(slot) + 28*density;
                if(lx > W - padR - 40*density){
                    lx = padL;
                    ly += 14*density;
                }
            }
        }
    }

    private String formatVal(float v, String unit){
        if(unit.equals("°C")) return String.format(LOCALE_BR,"%.1f°C",v);
        if(unit.equals("%")) return String.format(LOCALE_BR,"%.0f%%",v);
        if(unit.equals("mm")) return String.format(LOCALE_BR,"%.1f mm",v);
        return String.format(LOCALE_BR,"%.1f",v);
    }

    private int dp(float v){ return (int)(v*density + .5f); }
    private float sp(float v){ return v*scaledDensity; }
}
