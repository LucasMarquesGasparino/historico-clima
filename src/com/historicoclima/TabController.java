package com.historicoclima;

/**
 * Máquina de estado pura das abas (alto risco: reuso sem rebuild).
 * Contratos:
 * - switchTo(idx) retorna true apenas quando troca de verdade
 * - aba construída uma vez (built[]), refresh a cada ativação
 * - índice inválido é ignorado
 */
public final class TabController {
    private final int count;
    private int current = -1;
    private final boolean[] built;
    private final int[] activations;

    public TabController(int count) {
        if (count <= 0) throw new IllegalArgumentException("count>0");
        this.count = count;
        this.built = new boolean[count];
        this.activations = new int[count];
    }

    /** @return true se houve troca */
    public boolean switchTo(int idx) {
        if (idx < 0 || idx >= count) return false;
        if (idx == current) return false;
        current = idx;
        built[idx] = true;
        activations[idx]++;
        return true;
    }

    /** Invalida cache de uma aba (próxima ativação reconstrói). */
    public void invalidate(int idx) {
        if (idx < 0 || idx >= count) return;
        built[idx] = false;
    }

    public boolean isBuilt(int idx) {
        if (idx < 0 || idx >= count) return false;
        return built[idx];
    }

    public int getCurrent() {
        return current;
    }

    public int getActivations(int idx) {
        if (idx < 0 || idx >= count) return 0;
        return activations[idx];
    }

    public int getCount() {
        return count;
    }
}
