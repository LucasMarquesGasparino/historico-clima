package com.historicoclima;

import java.util.ArrayList;
import java.util.List;

/** Busca pura (sem Android) para teste unitário do alto risco (ListView + debounce). */
public final class CitySearch {
    private CitySearch() {}

    public static String normStr(String s) {
        if (s == null) return "";
        String t = s.toLowerCase();
        int n = t.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char ch = t.charAt(i);
            if (ch == 'á' || ch == 'ã' || ch == 'â' || ch == 'à') sb.append('a');
            else if (ch == 'é' || ch == 'ê') sb.append('e');
            else if (ch == 'í') sb.append('i');
            else if (ch == 'ó' || ch == 'ô' || ch == 'õ') sb.append('o');
            else if (ch == 'ú' || ch == 'ü') sb.append('u');
            else if (ch == 'ç') sb.append('c');
            else sb.append(ch);
        }
        return sb.toString();
    }

    public static String[] buildNormCache(String[] names, String[] ufs) {
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = normStr(names[i]) + " " + (ufs[i] == null ? "" : ufs[i].toLowerCase());
        }
        return out;
    }

    /**
     * 1 pass, sem O(n²), limite max. Contratos testados:
     * - retorna no máximo max itens
     * - startswith tem prioridade sobre contains
     * - query vazia/curta (<2) retorna lista vazia
     */
    public static List<Integer> filter(String q, String[] normNames, int max) {
        List<Integer> out = new ArrayList<>();
        List<Integer> second = new ArrayList<>();
        if (q == null) return out;
        String qq = normStr(q.trim());
        if (qq.length() < 2) return out;
        if (max <= 0) max = 60;
        for (int i = 0; i < normNames.length; i++) {
            String norm = normNames[i];
            if (norm == null) continue;
            if (norm.startsWith(qq) || (qq.contains(" ") && norm.contains(qq))) {
                out.add(i);
                if (out.size() >= max) break;
            } else if (norm.contains(qq)) {
                if (second.size() < max) second.add(i);
            }
            if (out.size() >= max) break;
        }
        if (out.size() < max) {
            int need = max - out.size();
            for (int i = 0; i < second.size() && i < need; i++) out.add(second.get(i));
        }
        return out;
    }
}
