package com.autotarget.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Armazena leituras recentes de sensor de um alvo.
 * Fornece médias, variâncias e janelas temporais usadas pela reconciliação de dados.
 */
public class SensorBuffer {
    // Lista thread-safe para permitir leitura pela otimização enquanto novas amostras são adicionadas.
    private final List<SensorData> leituras = new CopyOnWriteArrayList<>();
    // Limite de histórico para evitar crescimento indefinido de memória.
    private static final int MAX_LEITURAS = 60;

    /**
     * Adiciona uma nova leitura de sensor ao buffer.
     */
    /** Adiciona uma nova leitura e descarta a mais antiga quando o limite é ultrapassado. */
    public void adicionarLeitura(float x, float y, float vx, float vy) {
        long timestamp = System.currentTimeMillis();
        leituras.add(new SensorData(x, y, vx, vy, timestamp));

        // Mantém apenas as últimas MAX_LEITURAS.
        if (leituras.size() > MAX_LEITURAS) {
            leituras.remove(0);
        }
    }

    /**
     * Retorna uma cópia da lista de leituras.
     */
    /** Retorna uma cópia das leituras para proteger o buffer original. */
    public List<SensorData> getLeituras() {
        return new ArrayList<>(leituras);
    }

    /**
     * Retorna somente as leituras feitas dentro da janela de tempo informada.
     * Exemplo: janelaMs = 10000 retorna as leituras dos últimos 10 segundos.
     */
    /** Filtra leituras recentes dentro de uma janela temporal em milissegundos. */
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

    /**
     * Limpa todas as leituras do buffer.
     */
    /** Esvazia o histórico de leituras deste buffer. */
    public void limpar() {
        leituras.clear();
    }

    /**
     * Retorna o número de leituras no buffer.
     */
    /** Informa quantas leituras estão armazenadas atualmente. */
    public int size() {
        return leituras.size();
    }

    /**
     * Calcula a média das posições (x, y) no buffer.
     */
    /** Calcula a posição média observada nas leituras armazenadas. */
    public float[] calcularMediaPosicao() {
        if (leituras.isEmpty()) return new float[]{0, 0};

        float somaX = 0, somaY = 0;
        for (SensorData data : leituras) {
            somaX += data.getX();
            somaY += data.getY();
        }
        return new float[]{somaX / leituras.size(), somaY / leituras.size()};
    }

    /**
     * Calcula a variância das posições no buffer.
     */
    /** Mede a dispersão das posições, ajudando a estimar incerteza de sensor. */
    public float[] calcularVarianciaPosicao() {
        if (leituras.isEmpty()) return new float[]{0, 0};

        float[] media = calcularMediaPosicao();
        float varX = 0, varY = 0;

        for (SensorData data : leituras) {
            float dx = data.getX() - media[0];
            float dy = data.getY() - media[1];
            varX += dx * dx;
            varY += dy * dy;
        }

        return new float[]{varX / leituras.size(), varY / leituras.size()};
    }

    /**
     * Calcula a média das velocidades (vx, vy) no buffer.
     */
    /** Calcula a velocidade média observada nas leituras armazenadas. */
    public float[] calcularMediaVelocidade() {
        if (leituras.isEmpty()) return new float[]{0, 0};

        float somaVx = 0, somaVy = 0;
        for (SensorData data : leituras) {
            somaVx += data.getVx();
            somaVy += data.getVy();
        }
        return new float[]{somaVx / leituras.size(), somaVy / leituras.size()};
    }
    /**
     * Calcula a variância das velocidades (vx, vy) no buffer.
     */
    /** Mede a dispersão das velocidades registradas pelo sensor. */
    public float[] calcularVarianciaVelocidade() {
        if (leituras.isEmpty()) return new float[]{0, 0};

        float[] media = calcularMediaVelocidade();
        float varVx = 0, varVy = 0;

        for (SensorData data : leituras) {
            float dvx = data.getVx() - media[0];
            float dvy = data.getVy() - media[1];
            varVx += dvx * dvx;
            varVy += dvy * dvy;
        }

        return new float[]{varVx / leituras.size(), varVy / leituras.size()};
    }
}
