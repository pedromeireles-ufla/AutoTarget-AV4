package com.autotarget.game.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Representa um projétil disparado por um canhão.
 * Atualiza sua trajetória em thread própria e desativa-se ao atingir um alvo ou sair da tela.
 */
public class Projetil extends Thread {
    // Posição atual do projétil, atualizada a cada ciclo de movimento.
    private float x, y;
    // Velocidade calculada a partir do ângulo do disparo.
    private final float vx, vy;
    private final float raio = 8.0f;
    private volatile boolean ativo = true;
    private final int larguraTela, alturaTela;
    private Jogo jogo;
    private final boolean isEsquerda;

    /** Cria um projétil com direção definida pelo ângulo atual do canhão. */
    public Projetil(float x, float y, float angulo, float velocidade, int larguraTela, int alturaTela, boolean isEsquerda) {
        this.x = x;
        this.y = y;
        this.vx = (float) Math.cos(angulo) * velocidade;
        this.vy = (float) Math.sin(angulo) * velocidade;
        this.larguraTela = larguraTela;
        this.alturaTela = alturaTela;
        this.isEsquerda = isEsquerda;
        setDaemon(true);
    }

    /** Permite que o projétil consulte o jogo para detectar colisões e remover entidades. */
    public void setJogo(Jogo jogo) { this.jogo = jogo; }

    /** Executa a movimentação contínua do projétil enquanto ele está ativo. */
    @Override
    public void run() {
        while (ativo && !Thread.currentThread().isInterrupted()) {
            if (jogo == null || !jogo.isPausado()) {
                mover();
                verificarLimites();
                verificarDivisoriaCentral();
                verificarColisaoComAlvo();
            }
            try { Thread.sleep(16); } catch (InterruptedException e) {
                ativo = false;
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Avança a posição do projétil de acordo com sua velocidade nos eixos X e Y. */
    private void mover() {
        x += vx;
        y += vy;
    }

    /** Desativa e remove o projétil quando ele sai dos limites visíveis da tela. */
    private void verificarLimites() {
        if (x < 0 || x > larguraTela || y < 0 || y > alturaTela) {
            removerDoJogo();
        }
    }

    /**
     * Desativa e remove o projétil quando ele toca a divisória central.
     * Projéteis do sistema A/esquerdo não atravessam para o sistema B/direito,
     * e projéteis do sistema B/direito não atravessam para o sistema A/esquerdo.
     */
    private void verificarDivisoriaCentral() {
        if (!ativo) return;

        float meio = larguraTela / 2f;
        boolean projetilEsquerdoTocouDivisoria = isEsquerda && (x + raio >= meio);
        boolean projetilDireitoTocouDivisoria = !isEsquerda && (x - raio <= meio);

        if (projetilEsquerdoTocouDivisoria || projetilDireitoTocouDivisoria) {
            removerDoJogo();
        }
    }

    /** Verifica se o projétil atingiu algum alvo ativo e atualiza abates e remoções. */
    private void verificarColisaoComAlvo() {
        if (jogo == null || jogo.isPausado() || !ativo) return;

        Alvo alvoAtingido = null;
        try {
            jogo.getSemAlvos().acquire();
            List<Alvo> listaAlvos = new ArrayList<>(jogo.getAlvos());
            for (Alvo a : listaAlvos) {
                if (a.isAtivo()) {
                    double dist = com.autotarget.game.util.CalculoUtil.calcularDistancia(x, y, a.getX(), a.getY());
                    if (dist < (raio + a.getRaio())) {
                        alvoAtingido = a;
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            jogo.getSemAlvos().release();
        }

        if (alvoAtingido != null) {
            alvoAtingido.desativar();
            this.desativar();
            jogo.incrementarAbates(isEsquerda);
            jogo.removerAlvo(alvoAtingido);
            jogo.removerProjetil(this);
        }
    }

    /** Remove o projétil da partida e interrompe sua thread de movimento. */
    private void removerDoJogo() {
        desativar();
        if (jogo != null) {
            jogo.removerProjetil(this);
        }
    }

    /** Sinaliza que o projétil não deve mais se mover, colidir ou ser desenhado. */
    public void desativar() {
        this.ativo = false;
        this.interrupt();
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getRaio() { return raio; }
    public boolean isAtivo() { return ativo; }
}
