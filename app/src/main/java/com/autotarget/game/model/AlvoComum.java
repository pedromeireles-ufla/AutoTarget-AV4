package com.autotarget.game.model;

/**
 * Alvo de velocidade padrão.
 * Serve como tipo base de ameaça comum dentro da simulação.
 */
public class AlvoComum extends Alvo {
    /** Cria um alvo de velocidade moderada, com raio 20 e direção inicial aleatória. */
    public AlvoComum(float x, float y, int larguraTela, int alturaTela) {
        super(x, y, 20f, 4.0f, larguraTela, alturaTela); // Chama construtor da classe Alvo
        this.vx = Math.random() < 0.5 ? -3.0f : 3.0f; // Direção horizontal aleatória
        this.vy = Math.random() < 0.5 ? -3.0f : 3.0f; // Direção vertical aleatória
    }

    /** Move o alvo comum de forma linear, invertendo direção ao tocar as bordas da tela. */
    @Override
    public void mover() {
        x += vx;
        y += vy;

        // Rebate nas laterais com correção de posição
        if (x < raio) {
            x = raio; // Não deixa o alvo "entrar" na parede esquerda
            vx = -vx;
        } else if (x > larguraTela - raio) {
            x = larguraTela - raio; // Não deixa o alvo "entrar" na parede direita
            vx = -vx;
        }

        // Rebate no topo e fundo com correção de posição
        if (y < raio) {
            y = raio; // Não deixa o alvo subir além do topo
            vy = -vy;
        } else if (y > alturaTela - raio) {
            y = alturaTela - raio; // Garante que ele nunca fique abaixo da área visível
            vy = -vy;
        }
    }
}
