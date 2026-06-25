package com.autotarget.game.model;

/**
 * Alvo com velocidade superior ao alvo comum.
 * A diferença de velocidade aumenta a dificuldade de acompanhamento pelos canhões.
 */
public class AlvoRapido extends Alvo {
    // Ângulo interno usado para criar uma oscilação vertical durante o deslocamento horizontal.
    private float anguloOscilacao = 0;

    // Construtor: Inicializa o alvo rápido com raio 20 e velocidade alta
    /** Cria um alvo menor e mais rápido, aumentando a dificuldade de rastreamento. */
    public AlvoRapido(float x, float y, int larguraTela, int alturaTela) {
        super(x, y, 20f, 7.0f, larguraTela, alturaTela); // Chama construtor da classe Alvo
        this.vx = Math.random() < 0.5 ? -6.0f : 6.0f; // Direção horizontal aleatória
        this.vy = Math.random() < 0.5 ? -6.0f : 6.0f; // Direção vertical aleatória
    }

    /** Move o alvo rápido com deslocamento horizontal acelerado e oscilação vertical. */
    @Override
    public void mover() {
        // 1. Movimento base horizontal e vertical
        x += vx;
        y += vy;

        // 2. Trajetória Não-Linear (Ondulação Senoidal)
        // Isso faz o alvo "vibrar" ou serpentear enquanto se move,
        // criando um comportamento qualitativamente diferente do AlvoComum.
        anguloOscilacao += 0.2f;
        float oscilacao = (float) Math.sin(anguloOscilacao) * 12.0f; // Amplitude de 12 pixels

        // Aplicamos a oscilação no eixo perpendicular ao movimento principal
        if (Math.abs(vx) > Math.abs(vy)) {
            y += oscilacao;
        } else {
            x += oscilacao;
        }

        // 3. Lógica de Rebate nas Bordas (com correção de posição)
        if (x < raio) {
            x = raio;
            vx = -vx;
        } else if (x > larguraTela - raio) {
            x = larguraTela - raio;
            vx = -vx;
        }

        if (y < raio) {
            y = raio;
            vy = -vy;
        } else if (y > alturaTela - raio) {
            y = alturaTela - raio;
            vy = -vy;
        }
    }
}
