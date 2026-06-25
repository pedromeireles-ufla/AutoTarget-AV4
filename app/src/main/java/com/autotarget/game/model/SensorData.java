package com.autotarget.game.model;

/**
 * Registro imutável de uma leitura de sensor.
 * Guarda posição, velocidade e instante de coleta para posterior análise estatística.
 */
public class SensorData {
    // Coordenadas e velocidades capturadas em um instante específico.
    private final float x;
    private final float y;
    private final float vx;
    private final float vy;
    // Momento da leitura, usado para filtrar amostras recentes.
    private final long timestamp;

    /** Cria uma amostra imutável de sensor com posição, velocidade e horário. */
    public SensorData(float x, float y, float vx, float vy, long timestamp) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.timestamp = timestamp;
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getVx() { return vx; }
    public float getVy() { return vy; }
    public long getTimestamp() { return timestamp; }

    /**
     * Calcula a distância euclidiana entre este ponto e outro.
     */
    /** Calcula a distância desta leitura até um ponto informado. */
    public double distancia(float px, float py) {
        float dx = x - px;
        float dy = y - py;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
