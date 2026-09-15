package com.historicoclima;

import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final int C_BLUE = Color.parseColor("#0D47A1");
    private static final int C_BLUE_LIGHT = Color.parseColor("#1E88E5");
    private static final int C_BLUE_BG = Color.parseColor("#E3F2FD");
    private static final int C_BG = Color.parseColor("#F5F7FA");
    private static final int C_CARD = Color.WHITE;
    private static final int C_TEXT = Color.parseColor("#0D2A4A");
    private static final int C_MUTED = Color.parseColor("#6B7C93");
    private static final int C_BORDER = Color.parseColor("#E0E7EF");
    private static final int C_GREEN = Color.parseColor("#2E7D32");
    private static final int C_ORANGE = Color.parseColor("#EF6C00");
    private static final int C_RED = Color.parseColor("#C62828");

    private static final TimeZone TZ_BRA = TimeZone.getTimeZone("America/Sao_Paulo");

    private DatabaseHelper db;
    private SharedPreferences prefs;
    private LinearLayout contentArea;
    private LinearLayout tabBar;
    private int currentTab = 0;
    private ScrollView mainScroll;

    // Alto risco: reuso de abas sem rebuild (preserva busca/scroll, evita re-query)
    private final TabController tabCtl = new TabController(4);
    private final java.util.ArrayList<View>[] tabCache = new java.util.ArrayList[4];
    private final int[] tabScroll = new int[4];
    { for (int i = 0; i < 4; i++) tabCache[i] = new java.util.ArrayList<View>(); }

    // common selected city for historico and precisao (separate)
    private int selectedHistoricoCode = -1;
    private String selectedHistoricoName = null;
    private String selectedHistoricoUF = null;
    private double selectedHistoricoLat = 0, selectedHistoricoLon = 0;

    private int selectedPrecisaoCode = -1;
    private String selectedPrecisaoName = null;
    private String selectedPrecisaoUF = null;

    // UI refs for dynamic updates
    private LinearLayout historicoDetailContainer;
    private GraphView graphTemp, graphHum, graphPrec;
    private LinearLayout listContainerHistorico;
    private LinearLayout listContainerPrecisao;
    private TextView historicoStatus;
    private TextView precisaoStatus;
    private TextView coletaLog;
    private TextView coletaNext;

    // Favoritos
    private LinearLayout favoritosListContainer;
    private LinearLayout favoritosDetailContainer;
    private TextView favoritosStatus;
    private GraphView favGraphTemp, favGraphHum, favGraphPrec;
    private int selectedFavoritoCode = -1;
    private String selectedFavoritoName = null;
    private String selectedFavoritoUF = null;
    private double selectedFavoritoLat = 0, selectedFavoritoLon = 0;

    // Fluidez v1.1: executor único, debounce busca, seq p/ cancelar stale, cache normalize
    private final ExecutorService DB_EXEC = Executors.newFixedThreadPool(2);
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final AtomicInteger searchSeq = new AtomicInteger(0);
    private final AtomicInteger detailSeq = new AtomicInteger(0);
    private static String[] NORM_NAMES = null;
    private static final Locale LOCALE_BR = new Locale("pt", "BR");
    // Alto risco: adapters unificados (elimina divergência Historico/Precisao)
    private final CityRowAdapter histAdapter = new CityRowAdapter();
    private final CityRowAdapter precAdapter = new CityRowAdapter();

    /** Render centralizado da lista de busca (15 + ver mais até 60). */
    private void renderCityMatches(LinearLayout listWrap, CityRowAdapter adapter, boolean isHistorico, String emptyMsg) {
        listWrap.removeAllViews();
        if (adapter.total() == 0) {
            listWrap.addView(mutedText(emptyMsg, 11), wrap());
            return;
        }
        TextView cnt = mutedText(adapter.total() + " resultado(s) — mostrando " + adapter.visible().size(), 10);
        cnt.setTextColor(C_BLUE_LIGHT);
        listWrap.addView(cnt, wrap());
        for (int idx : adapter.visible()) {
            listWrap.addView(createCityRow(idx, isHistorico));
        }
        if (adapter.remaining() > 0) {
            Button more = smallButton("Ver +" + adapter.remaining() + " resultados", C_BG, C_BLUE);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (adapter.showMore()) renderCityMatches(listWrap, adapter, isHistorico, emptyMsg);
                }
            });
            listWrap.addView(more, wrapPad(0, 8, 0, 0));
        }
    }

    @Override protected void onCreate(Bundle s){
        super.onCreate(s);
        db = new DatabaseHelper(this);
        prefs = getSharedPreferences("clima_prefs", MODE_PRIVATE);

        // garantir coleta inicial: se nunca marcou modo, default false (apenas capitais)
        // schedule alarms robustos (3 slots + watchdog) mesmo fechado
        try{ AlarmScheduler.scheduleAllThreeDaily(this); }catch(Exception e){}

        buildUI();
        switchTab(0);
        checkPermissionsBanner();
    }

    private void buildUI(){
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(18), dp(18), dp(12));
        header.setBackgroundColor(C_BLUE);
        TextView title = new TextView(this);
        title.setText("Clima Histórico • Brasil");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, wrap());

        TextView sub = new TextView(this);
        sub.setText("5.571 municípios • coleta 01h 09h 15h BRT • histórico + previsão 8h • Open-Meteo (grátis)");
        sub.setTextSize(11);
        sub.setTextColor(Color.parseColor("#BBDEFB"));
        sub.setPadding(0, dp(4),0,0);
        header.addView(sub, wrap());

        // stats bar (counts em bg p/ first frame rápido)
        LinearLayout statsBar = new LinearLayout(this);
        statsBar.setOrientation(LinearLayout.HORIZONTAL);
        statsBar.setPadding(0, dp(10),0,0);
        final TextView statRead = createHeaderStatValue("…");
        final TextView statFore = createHeaderStatValue("…");
        final TextView statMon = createHeaderStatValue("…");
        statsBar.addView(createHeaderStatCol("leituras", statRead), weight(1));
        statsBar.addView(createHeaderStatCol("previsões", statFore), weight(1));
        statsBar.addView(createHeaderStatCol("cidades*", statMon), weight(1));
        header.addView(statsBar, new LinearLayout.LayoutParams(-1, -2));
        DB_EXEC.execute(() -> {
            final int cR, cF, cM;
            try{ cR = db.countReadings(); }catch(Exception e){ return; }
            try{ cF = db.countForecasts(); }catch(Exception e){ return; }
            try{ cM = db.countMonitored(); }catch(Exception e){ return; }
            runOnUiThread(() -> {
                statRead.setText(String.valueOf(cR));
                statFore.setText(String.valueOf(cF));
                statMon.setText(String.valueOf(cM));
            });
        });

        TextView hint = new TextView(this);
        hint.setText("* monitoradas • busca abrange todas 5.571");
        hint.setTextSize(9);
        hint.setTextColor(Color.parseColor("#90CAF9"));
        hint.setPadding(0, dp(4),0,0);
        header.addView(hint, wrap());

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // tab bar
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(Color.WHITE);
        tabBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        // add elevation-like border bottom
        View border = new View(this); border.setBackgroundColor(C_BORDER);
        root.addView(tabBar, new LinearLayout.LayoutParams(-1, -2));
        root.addView(border, new LinearLayout.LayoutParams(-1, dp(1)));

        String[] tabNames = new String[]{"Histórico", "Previsão", "Favoritos", "Coletas"};
        for(int i=0;i<tabNames.length;i++){
            final int idx=i;
            Button b = new Button(this);
            b.setText(tabNames[i]);
            b.setAllCaps(false);
            b.setTextSize(11);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setPadding(dp(6), dp(6), dp(6), dp(6));
            b.setOnClickListener(v -> switchTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(2),0,dp(2),0);
            tabBar.addView(b, lp);
        }

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        mainScroll = sv;
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setPadding(dp(14), dp(14), dp(14), dp(24));
        sv.addView(contentArea, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        updateTabStyles();
    }

    private View createHeaderStat(String label, String value){
        TextView v = createHeaderStatValue(value);
        return createHeaderStatCol(label, v);
    }

    private TextView createHeaderStatValue(String value){
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(16);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private View createHeaderStatCol(String label, TextView v){
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(10);
        l.setTextColor(Color.parseColor("#90CAF9"));
        l.setGravity(Gravity.CENTER);
        col.addView(v, wrap());
        col.addView(l, wrap());
        return col;
    }

    private void switchTab(int idx){
        if (idx < 0 || idx > 3) return;
        if (idx == currentTab && tabCtl.isBuilt(idx) && contentArea.getChildCount() > 0) return;
        // salva scroll + destaca views atuais no cache (preserva EditText/busca)
        try {
            if (mainScroll != null) tabScroll[currentTab] = mainScroll.getScrollY();
            tabCache[currentTab].clear();
            for (int i = 0; i < contentArea.getChildCount(); i++) {
                tabCache[currentTab].add(contentArea.getChildAt(i));
            }
            contentArea.removeAllViews();
        } catch (Exception ignored) {}
        currentTab = idx;
        updateTabStyles();
        if (tabCtl.isBuilt(idx) && !tabCache[idx].isEmpty()) {
            // reuso: reanexa sem re-query/rebuild
            for (View v : tabCache[idx]) {
                try {
                    if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
                } catch (Exception ignored) {}
                contentArea.addView(v);
            }
            tabCache[idx].clear();
            tabCtl.switchTo(idx);
            refreshTab(idx);
            final int sy = tabScroll[idx];
            if (mainScroll != null) mainScroll.post(() -> mainScroll.scrollTo(0, sy));
            return;
        }
        if(idx==0) buildHistoricoTab();
        else if(idx==1) buildPrecisaoTab();
        else if(idx==2) buildFavoritosTab();
        else buildColetasTab();
        tabCtl.switchTo(idx);
        if (mainScroll != null) mainScroll.post(() -> mainScroll.scrollTo(0, 0));
    }

    /** Refresh leve pós-reuso (sem rebuild): só o dinâmico. */
    private void refreshTab(int idx){
        try {
            if(idx==3) refreshColetas();
        } catch (Exception ignored) {}
    }

    /** Invalida cache e reconstrói a aba (usado após favoritar/desfavoritar/modo). */
    private void rebuildTab(int idx){
        try { tabCache[idx].clear(); } catch (Exception ignored) {}
        if (idx == currentTab) {
            // rebuild imediato; mantém built=true (já estava construída)
            contentArea.removeAllViews();
            if(idx==0) buildHistoricoTab();
            else if(idx==1) buildPrecisaoTab();
            else if(idx==2) buildFavoritosTab();
            else buildColetasTab();
        } else {
            try { tabCtl.invalidate(idx); } catch (Exception ignored) {}
        }
        // se não é a aba atual, será rebuildada ao abrir (cache vazio + built=false)
    }

    private void updateTabStyles(){
        for(int i=0;i<tabBar.getChildCount();i++){
            View v = tabBar.getChildAt(i);
            if(!(v instanceof Button)) continue;
            Button b = (Button) v;
            if(i==currentTab){
                b.setBackground(roundDrawable(C_BLUE, 10));
                b.setTextColor(Color.WHITE);
            } else {
                b.setBackground(roundDrawable(C_BG, 10));
                b.setTextColor(C_BLUE);
            }
        }
    }

    // ==================== HISTORICO TAB ====================
    private void buildHistoricoTab(){
        // search card
        LinearLayout card = cardContainer();
        card.addView(sectionTitle("1) Buscar região (todas 5.571 cidades)", C_BLUE), wrap());
        TextView desc = mutedText("Digite parte do nome ou UF (ex: São Paulo, Curitiba, BA). A busca filtra todas as cidades. Toque em 'Ver' para carregar histórico com 3 linhas (01h / 09h / 15h).", 11);
        card.addView(desc, wrapPad(0,4,0,10));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText et = new EditText(this);
        et.setHint("Buscar cidade... ex: Campinas, SP");
        et.setHintTextColor(Color.parseColor("#90A4B8"));
        et.setTextColor(C_TEXT);
        et.setTextSize(13);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(roundDrawable(Color.WHITE, 8, C_BORDER, 1));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchRow.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));

        Button btnClear = smallButton("Limpar", C_BG, C_MUTED);
        btnClear.setOnClickListener(v-> et.setText(""));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), dp(38));
        lp.setMargins(dp(8),0,0,0);
        searchRow.addView(btnClear, lp);

        card.addView(searchRow, wrap());

        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setPadding(0, dp(10),0,0);
        card.addView(listWrap, wrap());

        // info selected
        LinearLayout infoSelected = new LinearLayout(this);
        infoSelected.setOrientation(LinearLayout.VERTICAL);
        infoSelected.setPadding(0, dp(10),0,0);
        card.addView(infoSelected, wrap());

        historicoStatus = mutedText("Nenhuma cidade selecionada. Busque acima.", 11);
        infoSelected.addView(historicoStatus, wrap());

        contentArea.addView(card, wrapMargined());

        // results container for search
        listContainerHistorico = listWrap;

        // detail container (graphs)
        historicoDetailContainer = new LinearLayout(this);
        historicoDetailContainer.setOrientation(LinearLayout.VERTICAL);
        historicoDetailContainer.setPadding(0, dp(6),0,0);
        contentArea.addView(historicoDetailContainer, wrap());

        // detail graphs placeholder
        graphTemp = new GraphView(this);
        graphHum = new GraphView(this);
        graphPrec = new GraphView(this);

        // wire search (debounce 300ms + background + adapter unificado)
        histAdapter.setNormNames(NORM_NAMES);
        et.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){}
            public void afterTextChanged(Editable e){
                final String q = e.toString().trim();
                final int my = searchSeq.incrementAndGet();
                searchHandler.removeCallbacksAndMessages(null);
                searchHandler.postDelayed(new Runnable(){
                    @Override public void run(){
                        if(my!=searchSeq.get()) return;
                        if(q.length()<2){
                            listWrap.removeAllViews();
                            TextView t = mutedText("Digite ao menos 2 letras.", 11);
                            listWrap.addView(t, wrap());
                            historicoStatus = t;
                            return;
                        }
                        listWrap.removeAllViews();
                        listWrap.addView(mutedText("Buscando…", 11), wrap());
                        DB_EXEC.execute(new Runnable(){
                            @Override public void run(){
                                ensureNormCache();
                                histAdapter.setNormNames(NORM_NAMES);
                                histAdapter.query(q);
                                runOnUiThread(new Runnable(){
                                    @Override public void run(){
                                        if(my!=searchSeq.get()) return;
                                        renderCityMatches(listWrap, histAdapter, true, "Nenhuma cidade encontrada para '"+q+"'. Tente sem acento.");
                                    }
                                });
                            }
                        });
                    }
                }, 300);
            }
        });
        // initial hint
        listWrap.addView(mutedText("Digite para buscar...",11), wrap());

        // if previously selected, reload
        if(selectedHistoricoCode!=-1){
            loadHistoricoDetail(selectedHistoricoCode, selectedHistoricoName, selectedHistoricoUF, selectedHistoricoLat, selectedHistoricoLon);
        }
    }

    private View createCityRow(int cityIdx, boolean isHistorico){
        int code = CitiesData.CODES[cityIdx];
        String name = CitiesData.NAMES[cityIdx];
        String uf = CitiesData.UFS[cityIdx];
        double lat = CitiesData.LATS[cityIdx];
        double lon = CitiesData.LONS[cityIdx];
        boolean monitored = db.isMonitored(code);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(roundDrawable(C_CARD, 10, C_BORDER, 1));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(6),0,0);
        row.setLayoutParams(rowLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvName = new TextView(this);
        tvName.setText(name+" — "+uf);
        tvName.setTextSize(13);
        tvName.setTextColor(C_TEXT);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        TextView tvMeta = new TextView(this);
        tvMeta.setText("IBGE "+code+" • "+String.format("%.2f, %.2f", lat, lon) + (monitored? " • ● monitorada":""));
        tvMeta.setTextSize(10);
        tvMeta.setTextColor(monitored? C_GREEN : C_MUTED);
        textCol.addView(tvName, wrap());
        textCol.addView(tvMeta, wrap());
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        Button btnView = smallButton("Ver", C_BLUE, Color.WHITE);
        btnView.setOnClickListener(v->{
            if(isHistorico){
                selectedHistoricoCode=code; selectedHistoricoName=name; selectedHistoricoUF=uf; selectedHistoricoLat=lat; selectedHistoricoLon=lon;
                loadHistoricoDetail(code, name, uf, lat, lon);
                Toast.makeText(this, "Carregando "+name+"/"+uf, Toast.LENGTH_SHORT).show();
            } else {
                selectedPrecisaoCode=code; selectedPrecisaoName=name; selectedPrecisaoUF=uf;
                loadPrecisaoDetail(code, name, uf);
            }
        });
        row.addView(btnView, new LinearLayout.LayoutParams(dp(62), dp(34)));

        Button btnStar = smallButton(monitored? "✓":"+", monitored? C_GREEN : C_BG, monitored? Color.WHITE: C_TEXT);
        btnStar.setOnClickListener(v->{
            if(db.isMonitored(code)){
                db.removeMonitored(code);
                Toast.makeText(this, name+" removida do monitoramento", Toast.LENGTH_SHORT).show();
            } else {
                db.addMonitored(code, name, uf, lat, lon);
                Toast.makeText(this, name+" adicionada — será coletada às 01h/09h/15h", Toast.LENGTH_SHORT).show();
            }
            // refresh row? simple rebuild search if text present
            btnStar.setText(db.isMonitored(code)? "✓":"+" );
            btnStar.setTextColor(db.isMonitored(code)? Color.WHITE: C_TEXT);
            btnStar.setBackground(roundDrawable(db.isMonitored(code)? C_GREEN: C_BG, 8));
            tvMeta.setText("IBGE "+code+" • "+String.format("%.2f, %.2f", lat, lon) + (db.isMonitored(code)? " • ● monitorada":""));
            tvMeta.setTextColor(db.isMonitored(code)? C_GREEN : C_MUTED);
        });
        LinearLayout.LayoutParams starLp = new LinearLayout.LayoutParams(dp(36), dp(34));
        starLp.setMargins(dp(6),0,0,0);
        row.addView(btnStar, starLp);

        return row;
    }

    private static String normStr(String s){
        if(s==null) return "";
        // lower + remove acentos sem alocar 11 Strings por chamada
        String t = s.toLowerCase();
        int n = t.length();
        StringBuilder sb = new StringBuilder(n);
        for(int i=0;i<n;i++){
            char ch = t.charAt(i);
            if(ch=='á'||ch=='ã'||ch=='â'||ch=='à') sb.append('a');
            else if(ch=='é'||ch=='ê') sb.append('e');
            else if(ch=='í') sb.append('i');
            else if(ch=='ó'||ch=='ô'||ch=='õ') sb.append('o');
            else if(ch=='ú'||ch=='ü') sb.append('u');
            else if(ch=='ç') sb.append('c');
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static synchronized void ensureNormCache(){
        if(NORM_NAMES!=null) return;
        NORM_NAMES = new String[CitiesData.COUNT];
        for(int i=0;i<CitiesData.COUNT;i++){
            NORM_NAMES[i] = normStr(CitiesData.NAMES[i]) + " " + CitiesData.UFS[i].toLowerCase();
        }
    }

    private List<Integer> filterCities(String q){
        ensureNormCache();
        String qq = normStr(q.trim());
        List<Integer> out = new ArrayList<>();
        List<Integer> second = new ArrayList<>();
        // 1 pass só, sem out.contains(O(n²)), limite 60 p/ fluidez
        for(int i=0;i<CitiesData.COUNT;i++){
            String norm = NORM_NAMES[i];
            if(norm.startsWith(qq) || (qq.contains(" ") && norm.contains(qq))){
                out.add(i);
                if(out.size()>=60) break;
            } else if(norm.contains(qq)){
                if(second.size()<60) second.add(i);
            }
            if(out.size()>=60) break;
        }
        if(out.size()<60){
            int need = 60 - out.size();
            for(int i=0;i<second.size() && i<need;i++) out.add(second.get(i));
        }
        return out;
    }

    private void loadHistoricoDetail(int code, String name, String uf, double lat, double lon){
        historicoDetailContainer.removeAllViews();

        LinearLayout card = cardContainer();
        TextView title = new TextView(this);
        title.setText(name+" / "+uf+" — IBGE "+code);
        title.setTextSize(16);
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, wrap());

        TextView coords = mutedText(String.format("%.4f, %.4f • %s • %s", lat, lon, WeatherFetcher.slotForNow(), db.isMonitored(code)? "● monitorada (coleta 01h/09h/15h)":"○ não monitorada — toque em + para coletar"), 10);
        card.addView(coords, wrapPad(0,2,0,8));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnRefresh = smallButton("Atualizar gráficos", C_BLUE, Color.WHITE);
        btnRefresh.setOnClickListener(v-> loadHistoricoDetail(code, name, uf, lat, lon));
        btnRow.addView(btnRefresh, new LinearLayout.LayoutParams(0, dp(36), 1f));
        Button btnNow = smallButton("Coletar agora", C_GREEN, Color.WHITE);
        btnNow.setOnClickListener(v-> triggerManualCollectForCity(code, name, uf, lat, lon));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp2.setMargins(dp(8),0,0,0);
        btnRow.addView(btnNow, lp2);
        card.addView(btnRow, wrapPad(0,6,0,10));

        // placeholder for graphs (resumo sticky + colapsável p/ fluidez)
        final TextView resumoHist = mutedText("Carregando resumo…", 12);
        resumoHist.setTypeface(Typeface.DEFAULT_BOLD);
        resumoHist.setTextColor(C_TEXT);
        card.addView(resumoHist, wrapPad(0,4,0,8));
        LinearLayout graphsWrap = new LinearLayout(this);
        graphsWrap.setOrientation(LinearLayout.VERTICAL);
        // remove de pai anterior se reusado
        try{ if(graphTemp.getParent()!=null) ((ViewGroup)graphTemp.getParent()).removeView(graphTemp); }catch(Exception ignored){}
        try{ if(graphHum.getParent()!=null) ((ViewGroup)graphHum.getParent()).removeView(graphHum); }catch(Exception ignored){}
        try{ if(graphPrec.getParent()!=null) ((ViewGroup)graphPrec.getParent()).removeView(graphPrec); }catch(Exception ignored){}
        graphsWrap.addView(graphTemp, wrap());
        final LinearLayout extraGraphs = new LinearLayout(this);
        extraGraphs.setOrientation(LinearLayout.VERTICAL);
        extraGraphs.addView(graphHum, wrapPad(0,12,0,0));
        extraGraphs.addView(graphPrec, wrapPad(0,12,0,0));
        extraGraphs.setVisibility(View.GONE);
        graphsWrap.addView(extraGraphs, wrap());
        final Button toggleExtra = smallButton("Ver umidade + chuva ▼", C_BG, C_BLUE);
        toggleExtra.setOnClickListener(new View.OnClickListener(){
            @Override public void onClick(View v){
                boolean show = extraGraphs.getVisibility()!=View.VISIBLE;
                extraGraphs.setVisibility(show?View.VISIBLE:View.GONE);
                toggleExtra.setText(show?"Ocultar umidade + chuva ▲":"Ver umidade + chuva ▼");
            }
        });
        graphsWrap.addView(toggleExtra, wrapPad(0,8,0,0));
        card.addView(graphsWrap, wrap());

        // info table
        LinearLayout tableWrap = new LinearLayout(this);
        tableWrap.setOrientation(LinearLayout.VERTICAL);
        tableWrap.setPadding(0, dp(14),0,0);
        card.addView(tableWrap, wrap());

        historicoDetailContainer.addView(card, wrapMargined());

        // loading distinto de vazio (dataviz) + cancela stale
        graphTemp.setLoading(); graphHum.setLoading(); graphPrec.setLoading();
        final int myDetail = detailSeq.incrementAndGet();
        // async load data (executor único, sem thread explosion)
        DB_EXEC.execute(new Runnable(){
            @Override public void run(){
                final List<DatabaseHelper.Reading> readings = db.getReadingsForCityAsc(code, 0);
                final List<DatabaseHelper.Reading> recent = db.getReadingsForCity(code, 8);
                // build graphs on UI
                runOnUiThread(new Runnable(){
                    @Override public void run(){
                        if(myDetail!=detailSeq.get()) return;
                        if(readings.isEmpty()){
                            resumoHist.setText("Sem coletas ainda");
                            graphTemp.setData("Temperatura — sem dados", "°C", new ArrayList<String>(), new LinkedHashMap<String,float[]>(), new HashMap<String,Integer>(), 0, 35);
                            graphHum.setData("Umidade — sem dados", "%", new ArrayList<String>(), new LinkedHashMap<String,float[]>(), new HashMap<String,Integer>(), 0, 100);
                            graphPrec.setData("Precipitação — sem dados", "mm", new ArrayList<String>(), new LinkedHashMap<String,float[]>(), new HashMap<String,Integer>(), 0, 10);
                            tableWrap.addView(emptyBox("Ainda sem coletas para "+name+". A coleta automática roda às 01h, 09h e 15h BRT. Para testar, toque em 'Coletar agora' ou aguarde o próximo agendamento. Se esta cidade não está monitorada, adicione (+) para entrar na rotina."), wrap());
                            // also show instructions for history creation
                        } else {
                            try{
                                float tMax=-100, tMin=100, lastT=0;
                                int nD=0;
                                java.util.HashSet<String> dd=new java.util.HashSet<>();
                                for(DatabaseHelper.Reading r: readings){
                                    if(r.temp>tMax) tMax=(float)r.temp;
                                    if(r.temp<tMin) tMin=(float)r.temp;
                                    lastT=(float)r.temp;
                                    dd.add(dateKey(r.capturedAt));
                                }
                                nD=dd.size();
                                resumoHist.setText(String.format(LOCALE_BR, "Última: %.1f°C • %d dias • máx %.1f°C / mín %.1f°C", lastT, nD, tMax, tMin));
                            }catch(Exception ignored){ resumoHist.setText(readings.size()+" leituras"); }
                            buildGraphs(readings, tableWrap, recent);
                        }
                    }
                });
            }
        });
    }

    private void buildGraphs(List<DatabaseHelper.Reading> readings, LinearLayout tableWrap, List<DatabaseHelper.Reading> recentPassed){
        // group by date string
        // create sorted unique dates
        // Use TZ_BRA to format
        Map<String, Map<String, Double[]>> tempMap = new LinkedHashMap<>(); // date -> slot-> temp
        // but simpler: collect dates list in order asc
        List<String> dateKeys = new ArrayList<>();
        Map<String, Integer> dateIndex = new HashMap<>();
        // first pass: collect unique dates (dd/MM)
        for(DatabaseHelper.Reading r: readings){
            String dk = dateKey(r.capturedAt);
            if(!dateIndex.containsKey(dk)){
                dateIndex.put(dk, dateKeys.size());
                dateKeys.add(dk);
            }
        }
        // limit to last 30 dates if more
        int maxDates = 30;
        if(dateKeys.size() > maxDates){
            // keep last maxDates
            int remove = dateKeys.size() - maxDates;
            List<String> newKeys = dateKeys.subList(remove, dateKeys.size());
            List<String> oldKeys = new ArrayList<>(newKeys);
            // need to rebuild index and filter readings?
            dateKeys = oldKeys;
            dateIndex.clear();
            for(int i=0;i<dateKeys.size();i++) dateIndex.put(dateKeys.get(i), i);
            // filter readings to only those whose date is in set (last 30 days)
            List<DatabaseHelper.Reading> filtered = new ArrayList<>();
            for(DatabaseHelper.Reading r: readings){
                if(dateIndex.containsKey(dateKey(r.capturedAt))) filtered.add(r);
            }
            readings = filtered;
        }

        int n = dateKeys.size();
        // prepare series for 3 slots
        String[] slots = new String[]{"01h","09h","15h"};
        // colors
        Map<String,Integer> colors = new HashMap<>();
        colors.put("01h", Color.parseColor("#1E88E5")); // blue
        colors.put("09h", Color.parseColor("#FB8C00")); // orange
        colors.put("15h", Color.parseColor("#E53935")); // red

        // temp series
        Map<String,float[]> tempSeries = new LinkedHashMap<>();
        Map<String,float[]> humSeries = new LinkedHashMap<>();
        Map<String,float[]> precSeries = new LinkedHashMap<>();
        for(String s: slots){
            tempSeries.put(s, createNaNArray(n));
            humSeries.put(s, createNaNArray(n));
            precSeries.put(s, createNaNArray(n));
        }
        // for each reading, place value
        Map<String, Integer> counts = new HashMap<>();
        for(DatabaseHelper.Reading r: readings){
            String dk = dateKey(r.capturedAt);
            Integer idx = dateIndex.get(dk);
            if(idx==null) continue;
            String slot = r.slot;
            if(!tempSeries.containsKey(slot)){
                // unknown slot -> create
                tempSeries.put(slot, createNaNArray(n));
                humSeries.put(slot, createNaNArray(n));
                precSeries.put(slot, createNaNArray(n));
                colors.put(slot, Color.GRAY);
            }
            tempSeries.get(slot)[idx] = (float) r.temp;
            humSeries.get(slot)[idx] = (float) r.humidity;
            precSeries.get(slot)[idx] = (float) r.precip;
        }

        // compute min/max for temp: auto from data
        float tMin= 100, tMax=-100;
        for(float[] arr: tempSeries.values()){
            for(float v: arr) if(!Float.isNaN(v)){ if(v<tMin) tMin=v; if(v>tMax) tMax=v; }
        }
        if(tMin==100){ tMin=0; tMax=35; } else { tMin = (float)Math.floor(tMin-1); tMax = (float)Math.ceil(tMax+1); if(tMax-tMin<5) tMax = tMin+5; }

        float hMin=0, hMax=100; // fixed

        float pMax=5;
        for(float[] arr: precSeries.values()){
            for(float v: arr) if(!Float.isNaN(v) && v>pMax) pMax=v;
        }
        pMax = (float)Math.ceil(pMax+1);
        if(pMax<5) pMax=5;
        if(pMax>50) pMax=50; // cap

        // set to graphs
        graphTemp.setData("Temperatura real histórica", "°C", dateKeys, tempSeries, colors, tMin, tMax);
        graphHum.setData("Umidade relativa", "%", dateKeys, humSeries, colors, hMin, hMax);
        graphPrec.setData("Precipitação", "mm", dateKeys, precSeries, colors, 0, pMax);

        // build table of recent readings (last 8, sem query na UI)
        tableWrap.removeAllViews();
        TextView th = new TextView(this);
        th.setText("Últimas leituras (mais recentes primeiro) — "+readings.size()+" total");
        th.setTextSize(12);
        th.setTextColor(C_TEXT);
        th.setTypeface(Typeface.DEFAULT_BOLD);
        th.setPadding(0, dp(8),0, dp(8));
        tableWrap.addView(th, wrap());

        // header row
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(6), dp(6), dp(6), dp(6));
        header.setBackground(roundDrawable(C_BLUE_BG, 6));
        header.addView(tableCell("Data/Hora", true, 1.4f), weight(1.4f));
        header.addView(tableCell("Slot", true, 0.6f), weight(0.6f));
        header.addView(tableCell("Temp", true, 0.7f), weight(0.7f));
        header.addView(tableCell("Umi", true, 0.7f), weight(0.7f));
        header.addView(tableCell("Chuva", true, 0.7f), weight(0.7f));
        tableWrap.addView(header, wrap());

        // usa lista já buscada em background (8 itens)
        List<DatabaseHelper.Reading> recent = recentPassed!=null? recentPassed : new ArrayList<DatabaseHelper.Reading>();
        int showN = Math.min(8, recent.size());
        for(int ri=0;ri<showN;ri++){
            DatabaseHelper.Reading r = recent.get(ri);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(6), dp(7), dp(6), dp(7));
            row.setBackground(roundDrawable(Color.WHITE, 6, C_BORDER, 1));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, dp(4),0,0);
            row.setLayoutParams(rlp);
            String dt = formatDateTime(r.capturedAt);
            row.addView(tableCell(dt, false, 1.4f), weight(1.4f));
            TextView slotTv = tableCell(r.slot, false, 0.6f);
            if("01h".equals(r.slot)) slotTv.setTextColor(Color.parseColor("#1E88E5"));
            else if("09h".equals(r.slot)) slotTv.setTextColor(Color.parseColor("#FB8C00"));
            else slotTv.setTextColor(Color.parseColor("#E53935"));
            slotTv.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(slotTv, weight(0.6f));
            row.addView(tableCell(String.format(LOCALE_BR,"%.1f°C", r.temp), false, 0.7f), weight(0.7f));
            row.addView(tableCell(String.format(LOCALE_BR,"%.0f%%", r.humidity), false, 0.7f), weight(0.7f));
            row.addView(tableCell(String.format(LOCALE_BR,"%.1f mm", r.precip), false, 0.7f), weight(0.7f));
            tableWrap.addView(row, wrap());
        }
        if(recent.size()>showN){
            final List<DatabaseHelper.Reading> fullRecent = recent;
            Button more = smallButton("Ver +"+(recent.size()-showN)+" leituras", C_BG, C_BLUE);
            more.setOnClickListener(new View.OnClickListener(){
                @Override public void onClick(View v){
                    tableWrap.removeView(v);
                    for(int ri=showN;ri<fullRecent.size();ri++){
                        DatabaseHelper.Reading r = fullRecent.get(ri);
                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(dp(6), dp(7), dp(6), dp(7));
                        row.setBackground(roundDrawable(Color.WHITE, 6, C_BORDER, 1));
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                        rlp.setMargins(0, dp(4),0,0);
                        row.setLayoutParams(rlp);
                        row.addView(tableCell(formatDateTime(r.capturedAt), false, 1.4f), weight(1.4f));
                        row.addView(tableCell(r.slot, false, 0.6f), weight(0.6f));
                        row.addView(tableCell(String.format(LOCALE_BR,"%.1f°C", r.temp), false, 0.7f), weight(0.7f));
                        row.addView(tableCell(String.format(LOCALE_BR,"%.0f%%", r.humidity), false, 0.7f), weight(0.7f));
                        row.addView(tableCell(String.format(LOCALE_BR,"%.1f mm", r.precip), false, 0.7f), weight(0.7f));
                        tableWrap.addView(row, wrap());
                    }
                }
            });
            tableWrap.addView(more, wrapPad(0,8,0,0));
        }

        // cobertura por dia (dataviz barato: verde=3 coletas, laranja=parcial, vermelho=falha)
        try{
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            Map<String,Integer> cov = new HashMap<>();
            for(DatabaseHelper.Reading r: readings){
                String dk = dateKey(r.capturedAt);
                Integer c = cov.get(dk);
                cov.put(dk, (c==null?1:c+1));
            }
            for(String dk: dateKeys){
                View dot = new View(this);
                int cnt = cov.containsKey(dk)? cov.get(dk) : 0;
                int col = cnt>=3? C_GREEN : (cnt==0? C_RED : C_ORANGE);
                dot.setBackground(roundDrawable(col, 4));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(10), 1f);
                dlp.setMargins(dp(1),0,dp(1),0);
                strip.addView(dot, dlp);
            }
            tableWrap.addView(mutedText("Cobertura por dia (verde=3 coletas, laranja=parcial, vermelho=falha)",9), wrapPad(0,10,0,0));
            tableWrap.addView(strip, wrapPad(0,4,0,0));
        }catch(Exception ignored){}

        // export hint
        TextView exp = mutedText("Dica: o banco cresce a cada 8h. Após dias, os 3 traços (azul 01h, laranja 09h, vermelho 15h) revelarão padrões por horário. Toque em 'Coletar agora' para testar sem esperar.", 10);
        exp.setPadding(0, dp(10),0,0);
        tableWrap.addView(exp, wrap());
    }

    private TextView tableCell(String txt, boolean bold, float w){
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(10);
        tv.setTextColor(bold? C_BLUE: C_TEXT);
        if(bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private float[] createNaNArray(int n){
        float[] a = new float[n];
        for(int i=0;i<n;i++) a[i]=Float.NaN;
        return a;
    }

    private final ThreadLocal<Calendar> CAL_BRA = new ThreadLocal<Calendar>(){
        @Override protected Calendar initialValue(){ return Calendar.getInstance(TZ_BRA); }
    };

    private String dateKey(long millis){
        Calendar c = CAL_BRA.get();
        c.setTimeInMillis(millis);
        int d = c.get(Calendar.DAY_OF_MONTH), m = c.get(Calendar.MONTH)+1;
        StringBuilder sb = new StringBuilder(5);
        if(d<10) sb.append('0');
        sb.append(d).append('/');
        if(m<10) sb.append('0');
        sb.append(m);
        return sb.toString();
    }

    private String formatDateTime(long millis){
        Calendar c = CAL_BRA.get();
        c.setTimeInMillis(millis);
        int d = c.get(Calendar.DAY_OF_MONTH), m = c.get(Calendar.MONTH)+1;
        int h = c.get(Calendar.HOUR_OF_DAY), mi = c.get(Calendar.MINUTE);
        StringBuilder sb = new StringBuilder(11);
        if(d<10) sb.append('0');
        sb.append(d).append('/');
        if(m<10) sb.append('0');
        sb.append(m).append(' ');
        if(h<10) sb.append('0');
        sb.append(h).append(':');
        if(mi<10) sb.append('0');
        sb.append(mi);
        return sb.toString();
    }

    private void triggerManualCollectForCity(int code, String name, String uf, double lat, double lon){
        Toast.makeText(this, "Coletando "+name+" agora...", Toast.LENGTH_SHORT).show();
        DB_EXEC.execute(new Runnable(){
            @Override public void run(){
                DatabaseHelper db2 = new DatabaseHelper(getApplicationContext());
                final WeatherFetcher.FetchResult r = WeatherFetcher.fetchForCity(getApplicationContext(), code, name, uf, lat, lon, db2);
                try{ db2.close(); }catch(Exception ignored){}
                runOnUiThread(new Runnable(){
                    @Override public void run(){
                        if(r.success){
                            Toast.makeText(MainActivity.this, "OK: "+String.format(LOCALE_BR,"%.1f°C, %.0f%%, %.1f mm → 8h: %.1f°C", r.temp, r.humidity, r.precip, r.fTemp), Toast.LENGTH_LONG).show();
                            detailSeq.incrementAndGet();
                            loadHistoricoDetail(code, name, uf, lat, lon);
                        } else {
                            Toast.makeText(MainActivity.this, "Falha: "+r.error, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    // ==================== PRECISAO TAB ====================
    private void buildPrecisaoTab(){
        LinearLayout card = cardContainer();
        card.addView(sectionTitle("2) Previsão 8h × Realidade", C_GREEN), wrap());
        card.addView(mutedText("A cada coleta salvamos a previsão para +8h. Quando o horário alvo chega, comparamos com a medição real. Veja abaixo se a previsão acertou (tolerância 2°C / 10% / chuva).",11), wrapPad(0,4,0,10));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText et = new EditText(this);
        et.setHint("Buscar cidade para ver acurácia...");
        et.setHintTextColor(Color.parseColor("#90A4B8"));
        et.setTextColor(C_TEXT);
        et.setTextSize(13);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(roundDrawable(Color.WHITE, 8, C_BORDER, 1));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchRow.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
        Button btnClear = smallButton("Limpar", C_BG, C_MUTED);
        btnClear.setOnClickListener(v-> et.setText(""));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), dp(38));
        lp.setMargins(dp(8),0,0,0);
        searchRow.addView(btnClear, lp);
        card.addView(searchRow, wrap());

        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setPadding(0, dp(10),0,0);
        card.addView(listWrap, wrap());

        precisaoStatus = mutedText("Nenhuma cidade selecionada.",11);
        card.addView(precisaoStatus, wrapPad(0,8,0,0));

        contentArea.addView(card, wrapMargined());

        LinearLayout detailWrap = new LinearLayout(this);
        detailWrap.setOrientation(LinearLayout.VERTICAL);
        contentArea.addView(detailWrap, wrap());

        listContainerPrecisao = listWrap;

        // if previously selected, show
        if(selectedPrecisaoCode!=-1){
            loadPrecisaoDetail(selectedPrecisaoCode, selectedPrecisaoName, selectedPrecisaoUF);
            // also show its detail in new container? We'll set detailWrap as target
        }

        // hook search precisao (adapter unificado, sem duplicação)
        precAdapter.setNormNames(NORM_NAMES);
        et.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){}
            public void afterTextChanged(Editable e){
                final String q=e.toString().trim();
                final int my = searchSeq.incrementAndGet();
                searchHandler.removeCallbacksAndMessages(null);
                searchHandler.postDelayed(new Runnable(){
                    @Override public void run(){
                        if(my!=searchSeq.get()) return;
                        if(q.length()<2){
                            listWrap.removeAllViews();
                            listWrap.addView(mutedText("Digite ao menos 2 letras.",11), wrap());
                            return;
                        }
                        listWrap.removeAllViews();
                        listWrap.addView(mutedText("Buscando…",11), wrap());
                        DB_EXEC.execute(new Runnable(){
                            @Override public void run(){
                                ensureNormCache();
                                precAdapter.setNormNames(NORM_NAMES);
                                precAdapter.query(q);
                                runOnUiThread(new Runnable(){
                                    @Override public void run(){
                                        if(my!=searchSeq.get()) return;
                                        renderCityMatches(listWrap, precAdapter, false, "Nenhuma cidade encontrada.");
                                    }
                                });
                            }
                        });
                    }
                }, 300);
            }
        });
        listWrap.addView(mutedText("Digite para buscar...",11), wrap());

        // detail container reference
        // store for reload
        detailWrap.setTag("precisao_detail");
        // we will use a field to hold it
        precisaoDetailContainer = detailWrap;
    }

    private LinearLayout precisaoDetailContainer;

    private void loadPrecisaoDetail(int code, String name, String uf){
        if(precisaoDetailContainer==null) return;
        precisaoDetailContainer.removeAllViews();

        LinearLayout card = cardContainer();
        TextView title = new TextView(this);
        title.setText(name+" / "+uf+" — precisão 8h");
        title.setTextSize(15);
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, wrap());

        precisaoDetailContainer.addView(card, wrapMargined());

        // async query (executor único)
        final int myP = detailSeq.incrementAndGet();
        DB_EXEC.execute(() -> {
                final List<DatabaseHelper.Forecast> forecasts = db.getForecastsForCity(code, 50);
                final List<DatabaseHelper.Forecast> verified = db.getVerifiedForecasts(code);
            int total = forecasts.size();
            int verifiedCount = verified.size();
            int pending = total - verifiedCount;

            // compute MAE
            double maeTemp=0, maeHum=0, maePrec=0;
            int hitsTemp=0, hitsHum=0, hitsPrec=0;
            for(DatabaseHelper.Forecast f: verified){
                maeTemp += Math.abs(f.fTemp - f.aTemp);
                maeHum += Math.abs(f.fHum - f.aHum);
                maePrec += Math.abs(f.fPrec - f.aPrec);
                if(Math.abs(f.fTemp - f.aTemp) <= 2.0) hitsTemp++;
                if(Math.abs(f.fHum - f.aHum) <= 10.0) hitsHum++;
                // precip: categorical + tolerance 0.5mm or both zero
                double errPrec = Math.abs(f.fPrec - f.aPrec);
                if(errPrec <= 0.5) hitsPrec++;
                else if(f.fPrec <0.1 && f.aPrec <0.1) hitsPrec++;
            }
            if(verifiedCount>0){
                maeTemp/=verifiedCount; maeHum/=verifiedCount; maePrec/=verifiedCount;
            }
            double accTemp = verifiedCount>0? (hitsTemp*100.0/verifiedCount):0;
            double accHum = verifiedCount>0? (hitsHum*100.0/verifiedCount):0;
            double accPrec = verifiedCount>0? (hitsPrec*100.0/verifiedCount):0;
            final int fTotal = total; final int fVerified = verifiedCount; final int fPending = pending;
            final double fMaeTemp = maeTemp; final double fMaeHum = maeHum; final double fMaePrec = maePrec;
            final double fAccTemp = accTemp; final double fAccHum = accHum; final double fAccPrec = accPrec;

            runOnUiThread(() -> {
                if(myP!=detailSeq.get()) return;
                // metrics row
                LinearLayout metrics = new LinearLayout(this);
                metrics.setOrientation(LinearLayout.HORIZONTAL);
                metrics.setPadding(0, dp(10),0, dp(10));
                metrics.addView(metricBox(String.valueOf(fTotal), "previsões", C_BLUE), weight(1));
                metrics.addView(metricBox(String.valueOf(fVerified), "verificadas", C_GREEN), weight(1));
                metrics.addView(metricBox(String.valueOf(fPending), "pendentes", C_ORANGE), weight(1));
                card.addView(metrics, wrap());

                if(fVerified==0){
                    card.addView(emptyBox("Ainda sem previsões verificadas para "+name+". Após a primeira coleta, aguarde ~8h para a próxima medição. O sistema compara automaticamente (tolerância 3h devido aos slots 01h/09h/15h). Toque 'Coletar agora' em Histórico e aguarde."), wrapPad(0,8,0,0));
                } else {
                    // accuracy grid
                    LinearLayout accGrid = new LinearLayout(this);
                    accGrid.setOrientation(LinearLayout.HORIZONTAL);
                    accGrid.addView(metricBox(String.format("%.1f°C", fMaeTemp), String.format("erro médio temp\n%.0f%% acerto (≤2°C)", fAccTemp), fMaeTemp<=2? C_GREEN: fMaeTemp<=4? C_ORANGE: C_RED), weight(1));
                    accGrid.addView(metricBox(String.format("%.0f%%", fMaeHum), String.format("erro médio umi\n%.0f%% acerto (≤10%%)", fAccHum), fMaeHum<=10? C_GREEN: fMaeHum<=20? C_ORANGE: C_RED), weight(1));
                    accGrid.addView(metricBox(String.format("%.1fmm", fMaePrec), String.format("erro chuva\n%.0f%% acerto (≤0.5mm)", fAccPrec), fMaePrec<=0.5? C_GREEN: fMaePrec<=2? C_ORANGE: C_RED), weight(1));
                    // set margins
                    for(int i=0;i<accGrid.getChildCount();i++){
                        View v=accGrid.getChildAt(i);
                        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
                        if(i>0) p.setMargins(dp(6),0,0,0);
                    }
                    card.addView(accGrid, wrap());

                    // legend for chuva
                    TextView leg = mutedText("Critério chuva: acerto se erro ≤0.5mm. Valores baixos (<0.1mm) considerados 'sem chuva'.",9);
                    leg.setPadding(0, dp(6),0, dp(10));
                    card.addView(leg, wrap());
                }

                // table header
                TextView th = new TextView(this);
                th.setText("Histórico de previsões (mais recentes primeiro)");
                th.setTextSize(12);
                th.setTextColor(C_TEXT);
                th.setTypeface(Typeface.DEFAULT_BOLD);
                th.setPadding(0, dp(12),0, dp(8));
                card.addView(th, wrap());

                LinearLayout header = new LinearLayout(this);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setPadding(dp(6), dp(6), dp(6), dp(6));
                header.setBackground(roundDrawable(C_BLUE_BG, 6));
                header.addView(tableCell("Emitida", true,1.2f), weight(1.2f));
                header.addView(tableCell("Alvo +8h", true,1.2f), weight(1.2f));
                header.addView(tableCell("Prev → Real", true,1.6f), weight(1.6f));
                header.addView(tableCell("Status", true,0.8f), weight(0.8f));
                card.addView(header, wrap());

                if(forecasts.isEmpty()){
                    card.addView(mutedText("Nenhuma previsão registrada. Faça uma coleta.",11), wrapPad(0,8,0,0));
                } else {
                    int maxRows = Math.min(20, forecasts.size());
                    for(int fi=0;fi<maxRows;fi++){
                        DatabaseHelper.Forecast f = forecasts.get(fi);
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(dp(6), dp(7), dp(6), dp(7));
                        row.setBackground(roundDrawable(Color.WHITE, 6, C_BORDER,1));
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                        rlp.setMargins(0, dp(4),0,0);
                        row.setLayoutParams(rlp);

                        String emit = formatDateTime(f.issuedAt) + "\n"+f.slot;
                        String alvo = formatDateTime(f.targetTime);
                        TextView tvEmit = tableCell(emit, false,1.2f);
                        tvEmit.setTextSize(9); tvEmit.setGravity(Gravity.CENTER);
                        TextView tvAlvo = tableCell(alvo, false,1.2f);
                        tvAlvo.setTextSize(9); tvAlvo.setGravity(Gravity.CENTER);

                        LinearLayout prevReal = new LinearLayout(this);
                        prevReal.setOrientation(LinearLayout.VERTICAL);
                        prevReal.setGravity(Gravity.CENTER);
                        TextView tr1 = new TextView(this);
                        tr1.setText(String.format("%.1f° → %.1f°", f.fTemp, f.verified==1? f.aTemp: Double.NaN));
                        if(f.verified==0) tr1.setText(String.format("%.1f° → ?", f.fTemp));
                        else {
                            double e = Math.abs(f.fTemp - f.aTemp);
                            tr1.setText(String.format("%.1f° → %.1f° (%.1f°)", f.fTemp, f.aTemp, e));
                            tr1.setTextColor(e<=2? C_GREEN: e<=4? C_ORANGE: C_RED);
                        }
                        tr1.setTextSize(9); tr1.setGravity(Gravity.CENTER); tr1.setTypeface(Typeface.DEFAULT_BOLD);
                        TextView tr2 = new TextView(this);
                        if(f.verified==0) tr2.setText(String.format("%.0f%% → ?", f.fHum));
                        else tr2.setText(String.format("%.0f%% → %.0f%%", f.fHum, f.aHum));
                        tr2.setTextSize(9); tr2.setGravity(Gravity.CENTER); tr2.setTextColor(C_MUTED);
                        TextView tr3 = new TextView(this);
                        if(f.verified==0) tr3.setText(String.format("%.1fmm → ?", f.fPrec));
                        else tr3.setText(String.format("%.1fmm → %.1fmm", f.fPrec, f.aPrec));
                        tr3.setTextSize(9); tr3.setGravity(Gravity.CENTER); tr3.setTextColor(C_MUTED);
                        prevReal.addView(tr1, wrap());
                        prevReal.addView(tr2, wrap());
                        prevReal.addView(tr3, wrap());

                        TextView tvStatus;
                        if(f.verified==0){
                            tvStatus = tableCell("⏳ PENDENTE", false,0.8f);
                            tvStatus.setTextColor(C_ORANGE);
                            tvStatus.setBackground(roundDrawable(Color.parseColor("#FFF3E0"), 6));
                        } else {
                            boolean okTemp = Math.abs(f.fTemp - f.aTemp)<=2;
                            boolean okHum = Math.abs(f.fHum - f.aHum)<=10;
                            boolean okPrec = Math.abs(f.fPrec - f.aPrec)<=0.5;
                            int hits = (okTemp?1:0)+(okHum?1:0)+(okPrec?1:0);
                            if(hits==3){ tvStatus=tableCell("✓ ACERTOU", false,0.8f); tvStatus.setTextColor(C_GREEN); tvStatus.setBackground(roundDrawable(Color.parseColor("#E8F5E9"), 6)); }
                            else if(hits>=1){ tvStatus=tableCell("~ PARCIAL", false,0.8f); tvStatus.setTextColor(C_ORANGE); tvStatus.setBackground(roundDrawable(Color.parseColor("#FFF3E0"), 6)); }
                            else { tvStatus=tableCell("✗ ERROU", false,0.8f); tvStatus.setTextColor(C_RED); tvStatus.setBackground(roundDrawable(Color.parseColor("#FFEBEE"), 6)); }
                        }
                        tvStatus.setTextSize(9); tvStatus.setTypeface(Typeface.DEFAULT_BOLD); tvStatus.setGravity(Gravity.CENTER);
                        tvStatus.setPadding(dp(4), dp(4), dp(4), dp(4));

                        row.addView(tvEmit, weight(1.2f));
                        row.addView(tvAlvo, weight(1.2f));
                        row.addView(prevReal, weight(1.6f));
                        row.addView(tvStatus, weight(0.8f));
                        card.addView(row, wrap());
                    }
                    if(forecasts.size()>maxRows){
                        Button moreF = smallButton("Ver +"+(forecasts.size()-maxRows)+" previsões (máx 50)", C_BG, C_BLUE);
                        final List<DatabaseHelper.Forecast> allF = forecasts;
                        final LinearLayout cardRef = card;
                        moreF.setOnClickListener(new View.OnClickListener(){
                            @Override public void onClick(View v){
                                cardRef.removeView(v);
                                for(int fi=20;fi<Math.min(50, allF.size());fi++){
                                    DatabaseHelper.Forecast f = allF.get(fi);
                                    LinearLayout row = new LinearLayout(MainActivity.this);
                                    row.setOrientation(LinearLayout.HORIZONTAL);
                                    row.setPadding(dp(6), dp(7), dp(6), dp(7));
                                    row.setBackground(roundDrawable(Color.WHITE, 6, C_BORDER,1));
                                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                                    rlp.setMargins(0, dp(4),0,0);
                                    row.setLayoutParams(rlp);
                                    row.addView(tableCell(formatDateTime(f.issuedAt)+" "+f.slot, false,1.2f), weight(1.2f));
                                    row.addView(tableCell(formatDateTime(f.targetTime), false,1.2f), weight(1.2f));
                                    TextView st = tableCell(f.verified==1?"✓":"⏳", false,0.8f);
                                    row.addView(st, weight(0.8f));
                                    cardRef.addView(row, wrap());
                                }
                            }
                        });
                        card.addView(moreF, wrapPad(0,8,0,0));
                    }
                }
            });
        });
    }

    private View metricBox(String value, String label, int color){
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(8), dp(10), dp(8), dp(10));
        col.setBackground(roundDrawable(Color.WHITE, 10, C_BORDER,1));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(9);
        l.setTextColor(C_MUTED);
        l.setGravity(Gravity.CENTER);
        l.setLineSpacing(0, 1.1f);
        col.addView(v, wrap());
        col.addView(l, wrap());
        return col;
    }

    // ==================== FAVORITOS TAB ====================
    private void buildFavoritosTab(){
        LinearLayout card = cardContainer();
        card.addView(sectionTitle("Favoritos — global", C_BLUE), wrap());
        card.addView(mutedText("Cidades favoritadas (★) são monitoradas globalmente: aparecem aqui, em Histórico e em Previsão. Toque em ★ em qualquer busca para favoritar. Esta aba reúne tanto os gráficos históricos quanto a comparação de previsões para cada favorita.",11), wrapPad(0,4,0,10));

        List<DatabaseHelper.MonitoredCity> favs = db.getMonitored();
        TextView cnt = new TextView(this);
        cnt.setText(favs.size()+" favorita(s) • toque em Ver para abrir histórico + previsão");
        cnt.setTextSize(11);
        cnt.setTextColor(favs.isEmpty()? C_MUTED : C_GREEN);
        cnt.setTypeface(Typeface.DEFAULT_BOLD);
        cnt.setPadding(0, dp(4),0, dp(8));
        card.addView(cnt, wrap());

        favoritosListContainer = new LinearLayout(this);
        favoritosListContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(favoritosListContainer, wrap());

        if(favs.isEmpty()){
            favoritosListContainer.addView(emptyBox("Nenhuma favorita ainda. Vá em Histórico, busque uma cidade (ex: Campinas) e toque em ★ para favoritar. Favoritas são coletadas às 01h/09h/15h mesmo com app fechado (1 captura por slot/dia, sem duplicatas)."), wrapPad(0,6,0,0));
            Button btnCaps = smallButton("Favoritar todas as capitais (27)", C_BLUE, Color.WHITE);
            btnCaps.setOnClickListener(v->{
                DB_EXEC.execute(new Runnable(){
                    @Override public void run(){
                        final List<DatabaseHelper.MonitoredCity> caps = getCapitalsForFavoritos();
                        for(DatabaseHelper.MonitoredCity c: caps) db.addMonitored(c.code, c.name, c.state, c.lat, c.lon);
                        runOnUiThread(new Runnable(){
                            @Override public void run(){
                                Toast.makeText(MainActivity.this, "Capitais favoritadas", Toast.LENGTH_SHORT).show();
                                rebuildTab(2);
                            }
                        });
                    }
                });
            });
            card.addView(btnCaps, wrapPad(0,10,0,0));
        } else {
            for(DatabaseHelper.MonitoredCity city: favs){
                favoritosListContainer.addView(createFavoritoRow(city));
            }
            // Batch: 1 thread p/ todas as últimas leituras (sem N threads)
            final LinearLayout listRef = favoritosListContainer;
            DB_EXEC.execute(new Runnable(){
                @Override public void run(){
                    final Map<Integer,String> summaries = new HashMap<>();
                    for(DatabaseHelper.MonitoredCity c: favs){
                        try{
                            List<DatabaseHelper.Reading> last = db.getReadingsForCity(c.code, 1);
                            if(!last.isEmpty()){
                                DatabaseHelper.Reading r = last.get(0);
                                summaries.put(Integer.valueOf(c.code), String.format(LOCALE_BR, "IBGE %d • Última: %s %.1f°C %.0f%% %.1f mm", c.code, formatDateTime(r.capturedAt), r.temp, r.humidity, r.precip));
                            } else {
                                summaries.put(Integer.valueOf(c.code), "IBGE "+c.code+" • sem coletas ainda");
                            }
                        }catch(Exception ignored){}
                    }
                    runOnUiThread(new Runnable(){
                        @Override public void run(){
                            for(int i=0;i<listRef.getChildCount();i++){
                                View row = listRef.getChildAt(i);
                                if(row instanceof LinearLayout){
                                    LinearLayout lr = (LinearLayout)row;
                                    // col é primeiro filho
                                    if(lr.getChildCount()>0 && lr.getChildAt(0) instanceof LinearLayout){
                                        LinearLayout col = (LinearLayout)lr.getChildAt(0);
                                        if(col.getChildCount()>=2 && col.getChildAt(1) instanceof TextView){
                                            TextView tv = (TextView)col.getChildAt(1);
                                            Object tag = tv.getTag();
                                            if(tag instanceof Integer){
                                                String s = summaries.get((Integer)tag);
                                                if(s!=null) tv.setText(s);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    });
                }
            });
        }

        contentArea.addView(card, wrapMargined());

        // Container de detalhe (gráficos + previsão) para favorita selecionada
        favoritosDetailContainer = new LinearLayout(this);
        favoritosDetailContainer.setOrientation(LinearLayout.VERTICAL);
        contentArea.addView(favoritosDetailContainer, wrap());

        // Se já havia selecionada, recarrega
        if(selectedFavoritoCode!=-1){
            // verifica se ainda é favorita
            boolean still = false;
            for(DatabaseHelper.MonitoredCity c: favs) if(c.code==selectedFavoritoCode) still=true;
            if(still) loadFavoritosDetail(selectedFavoritoCode, selectedFavoritoName, selectedFavoritoUF, selectedFavoritoLat, selectedFavoritoLon);
            else {
                selectedFavoritoCode=-1;
            }
        }

        // Inicializa grafos se necessário
        if(favGraphTemp==null) favGraphTemp = new GraphView(this);
        if(favGraphHum==null) favGraphHum = new GraphView(this);
        if(favGraphPrec==null) favGraphPrec = new GraphView(this);
    }

    private View createFavoritoRow(DatabaseHelper.MonitoredCity city){
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(roundDrawable(C_CARD, 10, C_BORDER, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6),0,0);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tvName = new TextView(this);
        tvName.setText(city.name+" — "+city.state);
        tvName.setTextSize(13);
        tvName.setTextColor(C_TEXT);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        TextView tvMeta = new TextView(this);
        tvMeta.setText("IBGE "+city.code);
        tvMeta.setTextSize(10);
        tvMeta.setTextColor(C_MUTED);
        // resumo da última leitura via batch (sem thread por row): tag p/ preencher depois
        tvMeta.setTag(Integer.valueOf(city.code));
        col.addView(tvName, wrap());
        col.addView(tvMeta, wrap());
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        Button btnVer = smallButton("Ver", C_BLUE, Color.WHITE);
        btnVer.setOnClickListener(v -> {
            selectedFavoritoCode = city.code;
            selectedFavoritoName = city.name;
            selectedFavoritoUF = city.state;
            selectedFavoritoLat = city.lat;
            selectedFavoritoLon = city.lon;
            loadFavoritosDetail(city.code, city.name, city.state, city.lat, city.lon);
        });
        row.addView(btnVer, new LinearLayout.LayoutParams(dp(62), dp(34)));

        Button btnRem = smallButton("✕", C_RED, Color.WHITE);
        btnRem.setOnClickListener(v -> {
            db.removeMonitored(city.code);
            Toast.makeText(this, city.name+" removida dos favoritos", Toast.LENGTH_SHORT).show();
            // recarrega aba (invalida cache p/ não duplicar)
            rebuildTab(2);
            // limpa detalhe se era a selecionada
            if(selectedFavoritoCode==city.code){
                selectedFavoritoCode=-1;
                if(favoritosDetailContainer!=null) favoritosDetailContainer.removeAllViews();
            }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(36), dp(34));
        lp2.setMargins(dp(6),0,0,0);
        row.addView(btnRem, lp2);

        return row;
    }

    private void loadFavoritosDetail(int code, String name, String uf, double lat, double lon){
        if(favoritosDetailContainer==null) return;
        favoritosDetailContainer.removeAllViews();

        // Header
        LinearLayout headerCard = cardContainer();
        headerCard.setBackground(roundDrawable(CARD_COLOR_FAV(), 12, C_BORDER, 1));
        TextView title = new TextView(this);
        title.setText(name+" / "+uf+" — Favorita");
        title.setTextSize(16);
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerCard.addView(title, wrap());
        TextView coords = mutedText(String.format("%.4f, %.4f • %s • ● favorita global", lat, lon, WeatherFetcher.slotForNow()), 10);
        headerCard.addView(coords, wrapPad(0,2,0,8));
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnRefresh = smallButton("Atualizar", C_BLUE, Color.WHITE);
        btnRefresh.setOnClickListener(v-> loadFavoritosDetail(code, name, uf, lat, lon));
        btnRow.addView(btnRefresh, new LinearLayout.LayoutParams(0, dp(36), 1f));
        Button btnNow = smallButton("Coletar agora", C_GREEN, Color.WHITE);
        btnNow.setOnClickListener(v-> triggerManualCollectForCity(code, name, uf, lat, lon));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp.setMargins(dp(8),0,0,0);
        btnRow.addView(btnNow, lp);
        Button btnUnfav = smallButton("Desfavoritar", C_RED, Color.WHITE);
        btnUnfav.setOnClickListener(v->{
            db.removeMonitored(code);
            Toast.makeText(this, "Removida dos favoritos", Toast.LENGTH_SHORT).show();
            rebuildTab(2);
        });
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp3.setMargins(dp(8),0,0,0);
        btnRow.addView(btnUnfav, lp3);
        headerCard.addView(btnRow, wrapPad(0,6,0,4));
        favoritosDetailContainer.addView(headerCard, wrapMargined());

        // Garante grafos
        if(favGraphTemp==null) favGraphTemp = new GraphView(this);
        if(favGraphHum==null) favGraphHum = new GraphView(this);
        if(favGraphPrec==null) favGraphPrec = new GraphView(this);

        // Card de gráficos históricos
        LinearLayout histCard = cardContainer();
        histCard.addView(sectionTitle("Histórico — "+name, C_BLUE), wrap());
        histCard.addView(mutedText("1 captura por slot/dia (01h azul, 09h laranja, 15h vermelho) — sem duplicatas. Dados crescem a cada slot.",10), wrapPad(0,4,0,8));
        LinearLayout graphsWrap = new LinearLayout(this);
        graphsWrap.setOrientation(LinearLayout.VERTICAL);
        graphsWrap.addView(favGraphTemp, wrap());
        graphsWrap.addView(favGraphHum, wrapPad(0,12,0,0));
        graphsWrap.addView(favGraphPrec, wrapPad(0,12,0,0));
        histCard.addView(graphsWrap, wrap());
        LinearLayout tableWrapHist = new LinearLayout(this);
        tableWrapHist.setOrientation(LinearLayout.VERTICAL);
        histCard.addView(tableWrapHist, wrapPad(0,8,0,0));
        favoritosDetailContainer.addView(histCard, wrapMargined());

        // Card de precisão
        LinearLayout precCard = cardContainer();
        precCard.addView(sectionTitle("Previsão 8h × Real — "+name, C_GREEN), wrap());
        precCard.addView(mutedText("Cada coleta salva previsão +8h; quando o horário alvo chega, compara com medição real (tolerância 3h).",10), wrapPad(0,4,0,8));
        LinearLayout precWrap = new LinearLayout(this);
        precWrap.setOrientation(LinearLayout.VERTICAL);
        precCard.addView(precWrap, wrap());
        favoritosDetailContainer.addView(precCard, wrapMargined());

        // Carrega dados em background (executor + loading + stale-cancel)
        favGraphTemp.setLoading(); favGraphHum.setLoading(); favGraphPrec.setLoading();
        final int myFav = detailSeq.incrementAndGet();
        DB_EXEC.execute(new Runnable(){
            @Override public void run(){
                final List<DatabaseHelper.Reading> readings = db.getReadingsForCityAsc(code, 0);
                final List<DatabaseHelper.Forecast> forecasts = db.getForecastsForCity(code, 20);
                final List<DatabaseHelper.Forecast> verified = db.getVerifiedForecasts(code);
                final List<DatabaseHelper.Reading> recentFav = db.getReadingsForCity(code, 8);
                runOnUiThread(new Runnable(){
                    @Override public void run(){
                        if(myFav!=detailSeq.get()) return;
                        // Popula gráficos
                        if(readings.isEmpty()){
                            favGraphTemp.setData("Temperatura — sem dados", "°C", new ArrayList<String>(), new LinkedHashMap<String,float[]>(), new HashMap<String,Integer>(), 0, 35);
                            favGraphHum.setData("Umidade — sem dados", "%", new ArrayList<String>(), new LinkedHashMap<String,float[]>(), new HashMap<String,Integer>(), 0, 100);
                            favGraphPrec.setData("Precipitação — sem dados", "mm", new ArrayList<String>(), new LinkedHashMap<String,float[]>(), new HashMap<String,Integer>(), 0, 10);
                            tableWrapHist.addView(emptyBox("Sem coletas ainda para "+name+". Toque 'Coletar agora' ou aguarde 01h/09h/15h. Favoritas são coletadas mesmo fechado."), wrap());
                        } else {
                            // Reusa lógica de historico mas com fav graphs
                            populateFavoritosGraphs(readings, tableWrapHist, recentFav);
                        }
                        // Popula precisão
                        populateFavoritosPrecisao(forecasts, verified, precWrap, name);
                    }
                });
            }
        });
    }

    private void populateFavoritosGraphs(List<DatabaseHelper.Reading> readings, LinearLayout tableWrap, List<DatabaseHelper.Reading> recentPassed){
        // Lógica similar a buildGraphs mas usando fav graphs
        List<String> dateKeys = new ArrayList<>();
        Map<String,Integer> dateIndex = new HashMap<>();
        for(DatabaseHelper.Reading r: readings){
            String dk = dateKey(r.capturedAt);
            if(!dateIndex.containsKey(dk)){
                dateIndex.put(dk, dateKeys.size());
                dateKeys.add(dk);
            }
        }
        int maxDates=30;
        if(dateKeys.size()>maxDates){
            int remove=dateKeys.size()-maxDates;
            List<String> newKeys = new ArrayList<>(dateKeys.subList(remove, dateKeys.size()));
            dateKeys=newKeys;
            dateIndex.clear();
            for(int i=0;i<dateKeys.size();i++) dateIndex.put(dateKeys.get(i), i);
            List<DatabaseHelper.Reading> filtered=new ArrayList<>();
            for(DatabaseHelper.Reading r: readings) if(dateIndex.containsKey(dateKey(r.capturedAt))) filtered.add(r);
            readings=filtered;
        }
        int n=dateKeys.size();
        String[] slots=new String[]{"01h","09h","15h"};
        Map<String,Integer> colors=new HashMap<>();
        colors.put("01h", Color.parseColor("#1E88E5"));
        colors.put("09h", Color.parseColor("#FB8C00"));
        colors.put("15h", Color.parseColor("#E53935"));
        Map<String,float[]> tempSeries=new LinkedHashMap<>(), humSeries=new LinkedHashMap<>(), precSeries=new LinkedHashMap<>();
        for(String s: slots){ tempSeries.put(s, createNaNArray(n)); humSeries.put(s, createNaNArray(n)); precSeries.put(s, createNaNArray(n)); }
        for(DatabaseHelper.Reading r: readings){
            String dk=dateKey(r.capturedAt);
            Integer idx=dateIndex.get(dk);
            if(idx==null) continue;
            String slot=r.slot;
            if(!tempSeries.containsKey(slot)){
                tempSeries.put(slot, createNaNArray(n));
                humSeries.put(slot, createNaNArray(n));
                precSeries.put(slot, createNaNArray(n));
                colors.put(slot, Color.GRAY);
            }
            tempSeries.get(slot)[idx]=(float)r.temp;
            humSeries.get(slot)[idx]=(float)r.humidity;
            precSeries.get(slot)[idx]=(float)r.precip;
        }
        float tMin=100,tMax=-100;
        for(float[] arr: tempSeries.values()) for(float v: arr) if(!Float.isNaN(v)){ if(v<tMin) tMin=v; if(v>tMax) tMax=v; }
        if(tMin==100){ tMin=0; tMax=35; } else { tMin=(float)Math.floor(tMin-1); tMax=(float)Math.ceil(tMax+1); if(tMax-tMin<5) tMax=tMin+5; }
        float pMax=5;
        for(float[] arr: precSeries.values()) for(float v: arr) if(!Float.isNaN(v) && v>pMax) pMax=v;
        pMax=(float)Math.ceil(pMax+1); if(pMax<5) pMax=5; if(pMax>50) pMax=50;
        favGraphTemp.setData("Temperatura real", "°C", dateKeys, tempSeries, colors, tMin, tMax);
        favGraphHum.setData("Umidade", "%", dateKeys, humSeries, colors, 0, 100);
        favGraphPrec.setData("Precipitação", "mm", dateKeys, precSeries, colors, 0, pMax);
        // Tabela últimas
        tableWrap.removeAllViews();
        TextView th=new TextView(this);
        th.setText("Últimas leituras");
        th.setTextSize(12); th.setTextColor(C_TEXT); th.setTypeface(Typeface.DEFAULT_BOLD);
        th.setPadding(0, dp(8),0, dp(8));
        tableWrap.addView(th, wrap());
        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(6), dp(6), dp(6), dp(6));
        header.setBackground(roundDrawable(C_BLUE_BG, 6));
        header.addView(tableCell("Data/Hora", true,1.4f), weight(1.4f));
        header.addView(tableCell("Slot", true,0.6f), weight(0.6f));
        header.addView(tableCell("Temp", true,0.7f), weight(0.7f));
        header.addView(tableCell("Umi", true,0.7f), weight(0.7f));
        header.addView(tableCell("Chuva", true,0.7f), weight(0.7f));
        tableWrap.addView(header, wrap());
        List<DatabaseHelper.Reading> recent = recentPassed!=null? recentPassed : new ArrayList<DatabaseHelper.Reading>();
        for(DatabaseHelper.Reading r: recent){
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(6), dp(7), dp(6), dp(7));
            row.setBackground(roundDrawable(Color.WHITE, 6, C_BORDER,1));
            LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, dp(4),0,0); row.setLayoutParams(rlp);
            row.addView(tableCell(formatDateTime(r.capturedAt), false,1.4f), weight(1.4f));
            TextView tvSlot=tableCell(r.slot, false,0.6f);
            if("01h".equals(r.slot)) tvSlot.setTextColor(Color.parseColor("#1E88E5"));
            else if("09h".equals(r.slot)) tvSlot.setTextColor(Color.parseColor("#FB8C00"));
            else tvSlot.setTextColor(Color.parseColor("#E53935"));
            tvSlot.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(tvSlot, weight(0.6f));
            row.addView(tableCell(String.format(LOCALE_BR,"%.1f°C", r.temp), false,0.7f), weight(0.7f));
            row.addView(tableCell(String.format(LOCALE_BR,"%.0f%%", r.humidity), false,0.7f), weight(0.7f));
            row.addView(tableCell(String.format(LOCALE_BR,"%.1f mm", r.precip), false,0.7f), weight(0.7f));
            tableWrap.addView(row, wrap());
        }
        // cobertura por dia (igual histórico)
        try{
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            Map<String,Integer> cov = new HashMap<>();
            for(DatabaseHelper.Reading r: readings){
                String dk = dateKey(r.capturedAt);
                Integer c = cov.get(dk);
                cov.put(dk, (c==null?1:c+1));
            }
            for(String dk: dateKeys){
                View dot = new View(this);
                int cnt = cov.containsKey(dk)? cov.get(dk) : 0;
                int col = cnt>=3? C_GREEN : (cnt==0? C_RED : C_ORANGE);
                dot.setBackground(roundDrawable(col, 4));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(10), 1f);
                dlp.setMargins(dp(1),0,dp(1),0);
                strip.addView(dot, dlp);
            }
            tableWrap.addView(mutedText("Cobertura por dia (verde=3 coletas, laranja=parcial, vermelho=falha)",9), wrapPad(0,10,0,0));
            tableWrap.addView(strip, wrapPad(0,4,0,0));
        }catch(Exception ignored){}
    }

    private void populateFavoritosPrecisao(List<DatabaseHelper.Forecast> forecasts, List<DatabaseHelper.Forecast> verified, LinearLayout wrap, String name){
        int total=forecasts.size();
        int verifiedCount=verified.size();
        int pending=total-verifiedCount;
        double maeTemp=0, maeHum=0, maePrec=0;
        int hitsTemp=0,hitsHum=0,hitsPrec=0;
        for(DatabaseHelper.Forecast f: verified){
            maeTemp+=Math.abs(f.fTemp-f.aTemp);
            maeHum+=Math.abs(f.fHum-f.aHum);
            maePrec+=Math.abs(f.fPrec-f.aPrec);
            if(Math.abs(f.fTemp-f.aTemp)<=2) hitsTemp++;
            if(Math.abs(f.fHum-f.aHum)<=10) hitsHum++;
            if(Math.abs(f.fPrec-f.aPrec)<=0.5) hitsPrec++;
        }
        if(verifiedCount>0){ maeTemp/=verifiedCount; maeHum/=verifiedCount; maePrec/=verifiedCount; }
        double accTemp=verifiedCount>0? (hitsTemp*100.0/verifiedCount):0;
        double accHum=verifiedCount>0? (hitsHum*100.0/verifiedCount):0;
        double accPrec=verifiedCount>0? (hitsPrec*100.0/verifiedCount):0;
        LinearLayout metrics=new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setPadding(0, dp(8),0, dp(8));
        metrics.addView(metricBox(String.valueOf(total), "previsões", C_BLUE), weight(1));
        metrics.addView(metricBox(String.valueOf(verifiedCount), "verificadas", C_GREEN), weight(1));
        metrics.addView(metricBox(String.valueOf(pending), "pendentes", C_ORANGE), weight(1));
        wrap.addView(metrics, wrap());
        if(verifiedCount==0){
            wrap.addView(emptyBox("Sem previsões verificadas ainda para "+name+". Aguarde ~8h após coleta."), wrapPad(0,6,0,0));
        } else {
            LinearLayout accGrid=new LinearLayout(this);
            accGrid.setOrientation(LinearLayout.HORIZONTAL);
            accGrid.addView(metricBox(String.format("%.1f°C", maeTemp), String.format("erro médio\n%.0f%% ≤2°C", accTemp), maeTemp<=2? C_GREEN: maeTemp<=4? C_ORANGE: C_RED), weight(1));
            accGrid.addView(metricBox(String.format("%.0f%%", maeHum), String.format("erro médio\n%.0f%% ≤10%%", accHum), maeHum<=10? C_GREEN: maeHum<=20? C_ORANGE: C_RED), weight(1));
            accGrid.addView(metricBox(String.format("%.1fmm", maePrec), String.format("erro chuva\n%.0f%% ≤0.5mm", accPrec), maePrec<=0.5? C_GREEN: maePrec<=2? C_ORANGE: C_RED), weight(1));
            for(int i=0;i<accGrid.getChildCount();i++){
                View v=accGrid.getChildAt(i);
                LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();
                if(i>0) p.setMargins(dp(6),0,0,0);
            }
            wrap.addView(accGrid, wrap());
        }
        TextView th=new TextView(this);
        th.setText("Histórico previsões (recentes)");
        th.setTextSize(12); th.setTextColor(C_TEXT); th.setTypeface(Typeface.DEFAULT_BOLD);
        th.setPadding(0, dp(12),0, dp(8));
        wrap.addView(th, wrap());
        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(6), dp(6), dp(6), dp(6));
        header.setBackground(roundDrawable(C_BLUE_BG, 6));
        header.addView(tableCell("Emitida", true,1.2f), weight(1.2f));
        header.addView(tableCell("Alvo +8h", true,1.2f), weight(1.2f));
        header.addView(tableCell("Prev→Real", true,1.6f), weight(1.6f));
        header.addView(tableCell("Status", true,0.8f), weight(0.8f));
        wrap.addView(header, wrap());
        int maxFav = Math.min(12, forecasts.size());
        for(int fi=0;fi<maxFav;fi++){
            DatabaseHelper.Forecast f = forecasts.get(fi);
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(6), dp(7), dp(6), dp(7));
            row.setBackground(roundDrawable(Color.WHITE, 6, C_BORDER,1));
            LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, dp(4),0,0); row.setLayoutParams(rlp);
            TextView tvEmit=tableCell(formatDateTime(f.issuedAt)+"\n"+f.slot, false,1.2f);
            tvEmit.setTextSize(9); tvEmit.setGravity(Gravity.CENTER);
            TextView tvAlvo=tableCell(formatDateTime(f.targetTime), false,1.2f);
            tvAlvo.setTextSize(9); tvAlvo.setGravity(Gravity.CENTER);
            LinearLayout prevReal=new LinearLayout(this);
            prevReal.setOrientation(LinearLayout.VERTICAL);
            prevReal.setGravity(Gravity.CENTER);
            TextView tr1=new TextView(this);
            if(f.verified==0) tr1.setText(String.format("%.1f° → ?", f.fTemp));
            else { double e=Math.abs(f.fTemp-f.aTemp); tr1.setText(String.format("%.1f°→%.1f° (%.1f°)", f.fTemp,f.aTemp,e)); tr1.setTextColor(e<=2? C_GREEN: e<=4? C_ORANGE: C_RED); }
            tr1.setTextSize(9); tr1.setGravity(Gravity.CENTER); tr1.setTypeface(Typeface.DEFAULT_BOLD);
            TextView tr2=new TextView(this);
            if(f.verified==0) tr2.setText(String.format("%.0f%% → ?", f.fHum));
            else tr2.setText(String.format("%.0f%%→%.0f%%", f.fHum,f.aHum));
            tr2.setTextSize(9); tr2.setGravity(Gravity.CENTER); tr2.setTextColor(C_MUTED);
            TextView tr3=new TextView(this);
            if(f.verified==0) tr3.setText(String.format("%.1fmm → ?", f.fPrec));
            else tr3.setText(String.format("%.1fmm→%.1fmm", f.fPrec,f.aPrec));
            tr3.setTextSize(9); tr3.setGravity(Gravity.CENTER); tr3.setTextColor(C_MUTED);
            prevReal.addView(tr1, wrap()); prevReal.addView(tr2, wrap()); prevReal.addView(tr3, wrap());
            TextView tvStatus;
            if(f.verified==0){ tvStatus=tableCell("⏳ PENDENTE", false,0.8f); tvStatus.setTextColor(C_ORANGE); tvStatus.setBackground(roundDrawable(Color.parseColor("#FFF3E0"), 6)); }
            else {
                boolean okT=Math.abs(f.fTemp-f.aTemp)<=2, okH=Math.abs(f.fHum-f.aHum)<=10, okP=Math.abs(f.fPrec-f.aPrec)<=0.5;
                int hits=(okT?1:0)+(okH?1:0)+(okP?1:0);
                if(hits==3){ tvStatus=tableCell("✓ ACERTOU", false,0.8f); tvStatus.setTextColor(C_GREEN); tvStatus.setBackground(roundDrawable(Color.parseColor("#E8F5E9"), 6)); }
                else if(hits>=1){ tvStatus=tableCell("~ PARCIAL", false,0.8f); tvStatus.setTextColor(C_ORANGE); tvStatus.setBackground(roundDrawable(Color.parseColor("#FFF3E0"), 6)); }
                else { tvStatus=tableCell("✗ ERROU", false,0.8f); tvStatus.setTextColor(C_RED); tvStatus.setBackground(roundDrawable(Color.parseColor("#FFEBEE"), 6)); }
            }
            tvStatus.setTextSize(9); tvStatus.setTypeface(Typeface.DEFAULT_BOLD); tvStatus.setGravity(Gravity.CENTER);
            tvStatus.setPadding(dp(4), dp(4), dp(4), dp(4));
            row.addView(tvEmit, weight(1.2f));
            row.addView(tvAlvo, weight(1.2f));
            row.addView(prevReal, weight(1.6f));
            row.addView(tvStatus, weight(0.8f));
            wrap.addView(row, wrap());
        }
        if(forecasts.size()>maxFav){
            TextView more = mutedText("+"+(forecasts.size()-maxFav)+" previsões ocultas — abra Previsão p/ ver 20", 10);
            wrap.addView(more, wrapPad(0,6,0,0));
        }
    }

    private static Map<String,Integer> CITY_KEY_MAP = null;
    private static synchronized void ensureCityMap(){
        if(CITY_KEY_MAP!=null) return;
        CITY_KEY_MAP = new HashMap<>(CitiesData.COUNT*2);
        for(int i=0;i<CitiesData.COUNT;i++){
            CITY_KEY_MAP.put(normStr(CitiesData.NAMES[i])+"\0"+CitiesData.UFS[i], Integer.valueOf(i));
        }
    }

    private List<DatabaseHelper.MonitoredCity> getCapitalsForFavoritos(){
        ensureCityMap();
        String[][] caps = {{"Rio Branco","AC"},{"Maceió","AL"},{"Macapá","AP"},{"Manaus","AM"},{"Salvador","BA"},{"Fortaleza","CE"},{"Brasília","DF"},{"Vitória","ES"},{"Goiânia","GO"},{"São Luís","MA"},{"Cuiabá","MT"},{"Campo Grande","MS"},{"Belo Horizonte","MG"},{"Belém","PA"},{"João Pessoa","PB"},{"Curitiba","PR"},{"Recife","PE"},{"Teresina","PI"},{"Rio de Janeiro","RJ"},{"Natal","RN"},{"Porto Alegre","RS"},{"Porto Velho","RO"},{"Boa Vista","RR"},{"Florianópolis","SC"},{"São Paulo","SP"},{"Aracaju","SE"},{"Palmas","TO"}};
        List<DatabaseHelper.MonitoredCity> out=new ArrayList<>();
        for(String[] cap: caps){
            Integer idx = CITY_KEY_MAP.get(normStr(cap[0])+"\0"+cap[1]);
            if(idx!=null){
                int i = idx.intValue();
                DatabaseHelper.MonitoredCity m=new DatabaseHelper.MonitoredCity();
                m.code=CitiesData.CODES[i]; m.name=CitiesData.NAMES[i]; m.state=CitiesData.UFS[i]; m.lat=CitiesData.LATS[i]; m.lon=CitiesData.LONS[i];
                out.add(m);
            }
        }
        return out;
    }

    private int CARD_COLOR_FAV(){ return Color.parseColor("#FFF8E1"); }

    // ==================== COLETAS TAB ====================
    private void buildColetasTab(){
        LinearLayout card = cardContainer();
        card.addView(sectionTitle("Coletas automáticas (mesmo fechado)", C_ORANGE), wrap());
        card.addView(mutedText("O app agenda alarmes exatos às 01h, 09h e 15h BRT via AlarmManager.setExactAndAllowWhileIdle, compatível com Doze. Ao disparar, inicia foreground service que coleta clima atual + previsão +8h (Open-Meteo) para cidades monitoradas. Depois reagenda o próximo slot.",11), wrapPad(0,4,0,10));

        // next alarms
        coletaNext = new TextView(this);
        coletaNext.setTextSize(12);
        coletaNext.setTextColor(C_TEXT);
        coletaNext.setTypeface(Typeface.MONOSPACE);
        coletaNext.setBackground(roundDrawable(C_BLUE_BG, 8));
        coletaNext.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(coletaNext, wrap());

        // log
        coletaLog = new TextView(this);
        coletaLog.setTextSize(11);
        coletaLog.setTextColor(C_MUTED);
        coletaLog.setBackground(roundDrawable(Color.WHITE, 8, C_BORDER,1));
        coletaLog.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, -2);
        logLp.setMargins(0, dp(10),0,0);
        coletaLog.setLayoutParams(logLp);
        card.addView(coletaLog, logLp);

        // buttons row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12),0,0);
        Button btnNow = smallButton("Coletar agora", C_GREEN, Color.WHITE);
        btnNow.setOnClickListener(v-> triggerCollectNow());
        btnRow.addView(btnNow, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button btnResched = smallButton("Reagendar (3 slots + watchdog)", C_BLUE, Color.WHITE);
        btnResched.setOnClickListener(v->{
            AlarmScheduler.scheduleAllThreeDaily(this);
            refreshColetas();
            Toast.makeText(this,"3 alarmes + watchdog reagendados", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp2.setMargins(dp(8),0,0,0);
        btnRow.addView(btnResched, lp2);
        card.addView(btnRow, wrap());

        // permissions / settings
        LinearLayout permCard = cardContainer();
        permCard.setBackground(roundDrawable(Color.WHITE, 12, C_BORDER,1));
        permCard.addView(sectionTitle("Permissões e bateria", C_RED), wrap());
        permCard.addView(mutedText("Para rodar fechado, o Android exige: 1) alarme exato, 2) ignorar otimização de bateria, 3) notificação. Toque abaixo para ajustar.",11), wrapPad(0,4,0,10));

        Button btnExact = smallButton("1) Permitir alarme exato", C_BG, C_TEXT);
        btnExact.setOnClickListener(v-> requestExactAlarm());
        permCard.addView(btnExact, wrapPad(0,4,0,0));

        Button btnBattery = smallButton("2) Ignorar otimização bateria", C_BG, C_TEXT);
        btnBattery.setOnClickListener(v-> requestBattery());
        permCard.addView(btnBattery, wrapPad(0,6,0,0));

        Button btnNotif = smallButton("3) Permitir notificações", C_BG, C_TEXT);
        btnNotif.setOnClickListener(v-> requestNotif());
        if(Build.VERSION.SDK_INT < 33) { btnNotif.setEnabled(false); btnNotif.setAlpha(0.5f); }
        permCard.addView(btnNotif, wrapPad(0,6,0,0));

        contentArea.addView(card, wrapMargined());
        contentArea.addView(permCard, wrapMargined());

        // modo completo card
        LinearLayout modoCard = cardContainer();
        modoCard.addView(sectionTitle("Modo de coleta", C_BLUE), wrap());
        boolean modoCompleto = prefs.getBoolean("modo_completo", false);
        TextView modoDesc = mutedText(modoCompleto? "● Modo completo ATIVO: coleta todas 5.571 cidades a cada slot (consome ~25 MB/dia, 15-20 min por coleta, maior bateria).":"○ Modo econômico (padrão): coleta apenas capitais (27) + cidades favoritas (★). Recomendado para uso diário. Toque em ★ nas buscas para adicionar.",11);
        modoCard.addView(modoDesc, wrapPad(0,4,0,10));

        Button btnToggle = smallButton(modoCompleto? "Desativar modo completo":"Ativar modo completo (5.571)", modoCompleto? C_RED: C_ORANGE, Color.WHITE);
        btnToggle.setOnClickListener(v->{
            boolean cur = prefs.getBoolean("modo_completo", false);
            prefs.edit().putBoolean("modo_completo", !cur).apply();
            Toast.makeText(this, !cur? "Modo completo ativado — próxima coleta pegará todas":"Modo econômico ativado", Toast.LENGTH_SHORT).show();
            rebuildTab(3); // rebuild sem duplicar
        });
        modoCard.addView(btnToggle, wrap());

        TextView cntMon = mutedText("Monitoradas agora: "+db.countMonitored()+" • Total leituras: "+db.countReadings()+" • Previsões: "+db.countForecasts(),10);
        cntMon.setPadding(0, dp(10),0,0);
        cntMon.setTypeface(Typeface.MONOSPACE);
        modoCard.addView(cntMon, wrap());

        contentArea.addView(modoCard, wrapMargined());

        // danger zone
        LinearLayout danger = cardContainer();
        danger.addView(sectionTitle("Banco histórico", C_MUTED), wrap());
        danger.addView(mutedText("O banco cresce indefinidamente para formar seu arquivo climático privado. Você pode limpar tudo se necessário.",11), wrapPad(0,4,0,10));
        Button btnClear = smallButton("Limpar histórico (leituras + previsões)", C_RED, Color.WHITE);
        btnClear.setOnClickListener(v->{
            db.clearAll();
            prefs.edit().remove("last_log").apply();
            Toast.makeText(this,"Banco limpo", Toast.LENGTH_SHORT).show();
            refreshColetas();
        });
        danger.addView(btnClear, wrap());
        contentArea.addView(danger, wrapMargined());

        refreshColetas();
    }

    private void buildColetasTabRefresh(){
        rebuildTab(3);
    }

    private void refreshColetas(){
        if(coletaNext!=null){
            List<Long> nexts = AlarmScheduler.getNextThreeTimes();
            StringBuilder sb = new StringBuilder();
            sb.append("Próximos alarmes BRT:\n");
            for(int i=0;i<nexts.size();i++){
                sb.append(" • ").append(AlarmScheduler.formatBRT(nexts.get(i)));
                if(i==0) sb.append("  ← próximo");
                sb.append("\n");
            }
            long next = prefs.getLong("next_alarm", 0);
            if(next!=0) sb.append("Agendado: ").append(AlarmScheduler.formatBRT(next));
            // check if can schedule exact
            try{
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if(Build.VERSION.SDK_INT>=31 && am!=null && !am.canScheduleExactAlarms()){
                    sb.append("\n⚠ Alarme exato NEGADO — ative em permissões");
                } else {
                    sb.append("\n✓ Alarme exato permitido");
                }
            }catch(Exception e){}
            // battery
            try{
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if(Build.VERSION.SDK_INT>=23 && pm!=null){
                    if(pm.isIgnoringBatteryOptimizations(getPackageName())) sb.append("\n✓ Ignorando otimização bateria");
                    else sb.append("\n⚠ Otimização bateria ATIVA — corrija");
                }
            }catch(Exception e){}
            coletaNext.setText(sb.toString());
        }
        if(coletaLog!=null){
            String log = prefs.getString("last_log","Nenhuma coleta ainda. Aguarde 01h/09h/15h ou toque 'Coletar agora'.");
            long last = prefs.getLong("last_collect",0);
            int ok = prefs.getInt("last_ok",0);
            int fail = prefs.getInt("last_fail",0);
            String txt = log;
            if(last!=0) txt += "\n\nÚltima: "+AlarmScheduler.formatBRT(last) + " • ok="+ok+" fail="+fail;
            txt += "\n\nAPI: api.open-meteo.com (grátis, sem chave) • coleta atual + hourly +8h • timezone America/Sao_Paulo";
            coletaLog.setText(txt);
        }
    }

    private void triggerCollectNow(){
        Toast.makeText(this,"Iniciando coleta foreground...", Toast.LENGTH_SHORT).show();
        // start service directly (also verifies alarm)
        try{
            Intent svc = new Intent(this, CollectService.class);
            if(Build.VERSION.SDK_INT>=26) startForegroundService(svc);
            else startService(svc);
        }catch(Exception e){
            Toast.makeText(this,"Erro: "+e.getMessage(), Toast.LENGTH_LONG).show();
        }
        // também reagenda 3 slots
        AlarmScheduler.scheduleAllThreeDaily(this);
    }

    private void requestExactAlarm(){
        if(Build.VERSION.SDK_INT>=31){
            try{
                AlarmManager am=(AlarmManager)getSystemService(Context.ALARM_SERVICE);
                if(am!=null && !am.canScheduleExactAlarms()){
                    Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    intent.setData(Uri.parse("package:"+getPackageName()));
                    startActivity(intent);
                    return;
                } else {
                    Toast.makeText(this,"Já permitido", Toast.LENGTH_SHORT).show();
                }
            }catch(Exception e){ Toast.makeText(this,e.getMessage(), Toast.LENGTH_SHORT).show(); }
        } else Toast.makeText(this,"Não necessário nesta versão", Toast.LENGTH_SHORT).show();
    }
    private void requestBattery(){
        try{
            PowerManager pm=(PowerManager)getSystemService(Context.POWER_SERVICE);
            if(pm!=null && !pm.isIgnoringBatteryOptimizations(getPackageName())){
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:"+getPackageName()));
                startActivity(intent);
            } else Toast.makeText(this,"Já ignorando", Toast.LENGTH_SHORT).show();
        }catch(Exception e){ Toast.makeText(this,e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }
    private void requestNotif(){
        if(Build.VERSION.SDK_INT>=33){
            try{ requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101); }catch(Exception e){ Toast.makeText(this,e.getMessage(), Toast.LENGTH_SHORT).show(); }
        }
    }

    private void checkPermissionsBanner(){
        // optional toast
        try{
            AlarmManager am=(AlarmManager)getSystemService(Context.ALARM_SERVICE);
            if(Build.VERSION.SDK_INT>=31 && am!=null && !am.canScheduleExactAlarms()){
                Toast.makeText(this,"⚠ Ative 'Alarme exato' em Coletas para coleta em segundo plano", Toast.LENGTH_LONG).show();
            }
        }catch(Exception e){}
    }

    // helpers UI
    private LinearLayout cardContainer(){
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(roundDrawable(C_CARD, 12, C_BORDER, 1));
        return c;
    }
    private LinearLayout.LayoutParams wrap(){ return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrapPad(int l,int t,int r,int b){
        LinearLayout.LayoutParams lp= new LinearLayout.LayoutParams(-1, -2);
        // we handle padding via view padding instead; but keep
        return lp;
    }
    private LinearLayout.LayoutParams wrapMargined(){
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0,0,0, dp(14));
        return lp;
    }
    private LinearLayout.LayoutParams weight(float w){ return new LinearLayout.LayoutParams(0, -2, w); }

    private TextView sectionTitle(String t, int color){
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }
    private TextView mutedText(String t, int size){
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(size);
        tv.setTextColor(C_MUTED);
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }
    private Button smallButton(String label, int bg, int fg){
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(roundDrawable(bg, 8));
        b.setPadding(dp(10),0,dp(10),0);
        return b;
    }
    private android.graphics.drawable.GradientDrawable roundDrawable(int color, int radius){
        return roundDrawable(color, radius, Color.TRANSPARENT, 0);
    }
    private android.graphics.drawable.GradientDrawable roundDrawable(int color, int radius, int strokeColor, int strokeW){
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if(strokeW>0) d.setStroke(dp(strokeW), strokeColor);
        return d;
    }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density + .5f); }
    private View emptyBox(String msg){
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(11);
        tv.setTextColor(C_MUTED);
        tv.setBackground(roundDrawable(C_BLUE_BG, 8));
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setLineSpacing(0,1.15f);
        return tv;
    }

    @Override protected void onResume(){
        super.onResume();
        refreshColetas();
    }

    @Override protected void onDestroy(){
        try{ searchHandler.removeCallbacksAndMessages(null); }catch(Exception ignored){}
        try{ DB_EXEC.shutdownNow(); }catch(Exception ignored){}
        try{ if(db!=null) db.close(); }catch(Exception ignored){}
        super.onDestroy();
    }
}
