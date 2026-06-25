package com.autotarget.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Armazena leituras recentes de sensor de um alvo.
 * Fornece acesso ao histórico e janelas temporais usadas pela reconciliação de dados
 * (ver OptimizationTask e DataReconciliation).
 */
public class SensorBuffer {
    // Lista thread-safe para permitir leitura pela otimização enquanto novas amostras são adicionadas.
    private final List<SensorData> leituras = new CopyOnWriteArrayList<>();
    // Limite de histórico para evitar crescimento indefinido de memória.
    private static final int MAX_LEITURAS = 60;

    /** Adiciona uma nova leitura e descarta a mais antiga quando o limite é ultrapassado. */
    public void adicionarLeitura(float x, float y, float vx, float vy) {
        long timestamp = System.currentTimeMillis();
        leituras.add(new SensorData(x, y, vx, vy, timestamp));

        // Mantém apenas as últimas MAX_LEITURAS.
        if (leituras.size() > MAX_LEITURAS) {
            leituras.remove(0);
        }
    }

    /** Retorna uma cópia das leituras para proteger o buffer original. */
    public List<SensorData> getLeituras() {
        return new ArrayList<>(leituras);
    }

    /**
     * Retorna somente as leituras feitas dentro da janela de tempo informada.
     * Exemplo: janelaMs = 10000 retorna as leituras dos últimos 10 segundos.
     */
    public List<SensorData> getLeiturasUltimos(long janelaMs) {
        long agora = System.currentTimeMillis();
        List<SensorData> recentes = new ArrayList<>();

        for (SensorData data : leituras) {
            if (agora - data.getTimestamp() <= janelaMs) {
                recentes.add(data);
            }
        }

        return recentes;
    }
}
