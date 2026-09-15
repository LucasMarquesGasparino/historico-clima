package com.historicoclima;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter puro da lista de busca (alto risco: unifica Historico+Precisao,
 * elimina duplicação de listeners e limita 15 + ver mais).
 */
public final class CityRowAdapter {
    public static final int PAGE = 15;
    public static final int MAX = 60;

    private String[] normNames = new String[0];
    private final List<Integer> matches = new ArrayList<>();
    private int shown = 0;
    private String lastQuery = "";

    public void setNormNames(String[] normNames) {
        this.normNames = normNames == null ? new String[0] : normNames;
    }

    public void query(String q) {
        lastQuery = q == null ? "" : q;
        matches.clear();
        matches.addAll(CitySearch.filter(lastQuery, normNames, MAX));
        shown = Math.min(PAGE, matches.size());
    }

    public List<Integer> visible() {
        return new ArrayList<>(matches.subList(0, Math.min(shown, matches.size())));
    }

    /** Expande para até MAX. @return true se cresceu. */
    public boolean showMore() {
        int target = Math.min(MAX, matches.size());
        if (shown >= target) return false;
        shown = target;
        return true;
    }

    public int total() { return matches.size(); }
    public int remaining() { return Math.max(0, matches.size() - shown); }
    public String getLastQuery() { return lastQuery; }
}
