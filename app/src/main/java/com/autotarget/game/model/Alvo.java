package com.autotarget.game.model;

import com.autotarget.game.util.CalculoUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Representa um alvo móvel do jogo.
 * Controla posição, velocidade, ciclo de vida e coleta periódica de leituras de sensor.
 */
public abstract class Alvo extends Thread {
    // Intervalo fixo usado para gerar leituras simuladas de sensor durante o movimento.
    private static final long INTERVALO_COLETA_SENSOR_MS = 1000;

    // Posição atual do alvo; é volatile porque pode ser lida por threads de desenho, colisão e otimização.
    protected volatile float x, y;
    // Características físicas usadas no desenho, na movimentação e no cálculo de colisão.
    protected float raio;
    protected float velocidade;
    // Velocidade decomposta nos eixos X e Y para alimentar o modelo de sensores.
    protected volatile float vx = 0, vy = 0;
    // Controla quando a thread do alvo deve continuar rodando ou finalizar.
    protected volatile boolean ativo = true;
    protected int larguraTela, alturaTela;
    protected Jogo jogo;
    private final Random random = new Random();
    // Armazena as leituras ruidosas que serão usadas pela reconciliação de dados.
    private final SensorBuffer sensorBuffer = new SensorBuffer();
    private long ultimaColetaSensor = 0;

    /**
     * Inicializa um alvo genérico com posição, tamanho, velocidade e limites da tela.
     */
    public Alvo(float x, float y, float raio, float velocidade, int larguraTela, int alturaTela) {
        this.x = x;
        this.y = y;
        this.raio = raio;
        this.velocidade = velocidade;
        this.larguraTela = larguraTela;
        this.alturaTela = alturaTela;
        setDaemon(true);
    }

    /** Vincula o alvo à instância do jogo para permitir colisões, transferências e registro de sensores. */
    public void setJogo(Jogo jogo) { this.jogo = jogo; }

    /** Executa o ciclo do alvo enquanto ele permanece ativo. */
    @Override
    public void run() {
        while (ativo && !Thread.currentThread().isInterrupted()) {
            if (jogo == null || !jogo.isPausado()) {
                mover();

                // Mantém o alvo registrado na lista e na tarefa de otimização do lado correto da tela.
                float meio = larguraTela / 2f;
                if (x < meio) {
                    jogo.transferirAlvo(this, true);
                    jogo.getOptimizationEsquerda().registrarAlvo(this);
                    jogo.getOptimizationDireita().removerAlvo(this);
                } else {
                    jogo.transferirAlvo(this, false);
                    jogo.getOptimizationDireita().registrarAlvo(this);
                    jogo.getOptimizationEsquerda().removerAlvo(this);
                }

                coletarLeituraSensorSeNecessario();
                verificarColisaoComProjetil();
            }

            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                ativo = false;
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Verifica se já passou tempo suficiente para registrar uma nova leitura de sensor.
     */
    private void coletarLeituraSensorSeNecessario() {
        long agora = System.currentTimeMillis();

        if (agora - ultimaColetaSensor >= INTERVALO_COLETA_SENSOR_MS) {
            ultimaColetaSensor = agora;
            coletarLeituraSensor();
        }
    }

    /**
     * Simula ruído nas coordenadas e velocidades, aproximando uma leitura de sensor real.
     */
    private void coletarLeituraSensor() {
        float ruidoX  = (float) (random.nextGaussian() * Math.abs(x  * 0.05));
        float ruidoY  = (float) (random.nextGaussian() * Math.abs(y  * 0.05));
        float ruidoVx = (float) (random.nextGaussian() * Math.abs(vx * 0.05));
        float ruidoVy = (float) (random.nextGaussian() * Math.abs(vy * 0.05));

        sensorBuffer.adicionarLeitura(x + ruidoX, y + ruidoY, vx + ruidoVx, vy + ruidoVy);

        // A leitura é enviada apenas para a tarefa de otimização responsável pelo lado atual do alvo.
        if (x < larguraTela / 2f) {
            jogo.getOptimizationEsquerda().coletarLeituraSensor(this, x + ruidoX, y + ruidoY, vx + ruidoVx, vy + ruidoVy);
        } else {
            jogo.getOptimizationDireita().coletarLeituraSensor(this, x + ruidoX, y + ruidoY, vx + ruidoVx, vy + ruidoVy);
        }
    }

    /** Cada tipo concreto de alvo define sua própria regra de movimento. */
    public abstract void mover();

    /**
     * Procura projéteis ativos próximos o suficiente para considerar que este alvo foi abatido.
     */
    protected void verificarColisaoComProjetil() {
        if (jogo == null || jogo.isPausado() || !ativo) return;

        Projetil projetilAtingido = null;
        try {
            jogo.getSemProjeteis().acquire();

            // A cópia evita conflito caso outro trecho adicione ou remova projéteis durante a iteração.
            List<Projetil> projeteisSnapshot = new ArrayList<>(jogo.getProjeteis());
            for (Projetil p : projeteisSnapshot) {
                if (p.isAtivo()) {
                    double distQuadrada = CalculoUtil.calcularDistanciaQuadrada(x, y, p.getX(), p.getY());
                    if (distQuadrada < (raio + p.getRaio()) * (raio + p.getRaio())) {
                        projetilAtingido = p;
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            jogo.getSemProjeteis().release();
        }

        if (projetilAtingido != null) {
            this.desativar();
            projetilAtingido.desativar();
            jogo.removerAlvo(this);
            jogo.removerProjetil(projetilAtingido);

            // Notificar otimização.
            jogo.getOptimizationEsquerda().removerAlvo(this);
            jogo.getOptimizationDireita().removerAlvo(this);
        }
    }

    /** Finaliza a participação do alvo no jogo e encerra seu loop de execução. */
    public void desativar() {
        this.ativo = false;
        this.interrupt();
    }

    public float getX()         { return x; }
    public float getY()         { return y; }
    public float getRaio()      { return raio; }
    public float getVelocidade(){ return velocidade; }
    public float getVx()        { return vx; }
    public float getVy()        { return vy; }
    public boolean isAtivo()    { return ativo; }
}