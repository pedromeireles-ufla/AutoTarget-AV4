package com.autotarget.game.model;

/**
 * Representa um canhão controlado pelo sistema.
 * Mantém posição, destino de realocação, ângulo de mira, energia e estado de atividade.
 */
public class Canhao extends Thread {
    // Posição atual do canhão; pode ser lida pela renderização enquanto a thread move o canhão.
    private volatile float x, y;
    // Ângulo de mira calculado em direção ao alvo mais próximo.
    private float angulo;
    // Controla o ciclo de vida da thread do canhão.
    private volatile boolean ativo = true;
    private final Jogo jogo;
    private final boolean isEsquerda;

    // Destino de realocação calculado pela otimização (sensor-driven via reconciliação de dados);
    // valores negativos indicam ausência de realocação pendente.
    private volatile float destinoX = -1;
    private volatile float destinoY = -1;
    private volatile boolean emRealocacao = false;

    // Velocidade de deslocamento gradual: tamanho do passo (em pixels) por ciclo,
    // evitando que o reposicionamento aconteça instantaneamente.
    private static final float PASSO_REALOCACAO = 4.0f;

    /** Inicializa o canhão em uma metade da tela e mantém referência ao jogo para disparar projéteis. */
    public Canhao(float x, float y, Jogo jogo, boolean isEsquerda) {
        this.x = x;
        this.y = y;
        this.destinoX = x;
        this.destinoY = y;
        this.jogo = jogo;
        this.isEsquerda = isEsquerda;
        setDaemon(true);
    }

    @Override
    public void run() {
        while (ativo && !Thread.currentThread().isInterrupted()) {
            if (!jogo.isPausado()) {
                float energia = isEsquerda ? jogo.getEnergiaEsquerda() : jogo.getEnergiaDireita();
                if (energia > 0) {
                    mirarEAtirar();
                }
            }
            try {
                long intervaloBase = 1000;
                int penalidadeExcesso = jogo.getPenalidade(isEsquerda);

                // Penalidade por excesso de canhões e Feedback de Temperatura (AV3)
                // intervalo = intervaloBase * (1 + penalidade) * fatorFeedback
                long intervaloFinal = (long) (intervaloBase * (1 + (penalidadeExcesso / 100.0)) * jogo.getFatorFeedback());

                Thread.sleep(intervaloFinal);
            } catch (InterruptedException e) {
                ativo = false;
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Localiza o alvo mais próximo do mesmo lado, aponta o canhão e cria um projétil. */
    private void mirarEAtirar() {
        Alvo alvoProximo = jogo.getAlvoMaisProximoNoLado(x, y, isEsquerda);
        if (alvoProximo != null) {
            float dx = alvoProximo.getX() - x;
            float dy = alvoProximo.getY() - y;
            this.angulo = (float) Math.atan2(dy, dx);

            Projetil p = new Projetil(x, y, angulo, 12.0f, jogo.getLargura(), jogo.getAltura(), isEsquerda);
            jogo.adicionarProjetil(p);
        }
    }

    /** Recebe da otimização a nova posição para onde o canhão deve se deslocar gradualmente. */
    public void definirDestino(float dx, float dy) {
        float meio = jogo.getLargura() / 2f;
        float margem = 50f;

        if (isEsquerda) dx = Math.max(margem, Math.min(dx, meio - margem));
        else             dx = Math.max(meio + margem, Math.min(dx, jogo.getLargura() - margem));
        dy = Math.max(margem, Math.min(dy, jogo.getAltura() - margem));

        this.destinoX = dx;
        this.destinoY = dy;
        this.emRealocacao = true;
    }

    /** Aproxima o canhão do destino calculado sem atravessar a posição final. */
    public void moverEmDirecaoAoDestino() {
        if (!emRealocacao) return;

        float dx = destinoX - x;
        float dy = destinoY - y;
        float distancia = (float) Math.sqrt(dx * dx + dy * dy);

        if (distancia <= PASSO_REALOCACAO) {
            x = destinoX;
            y = destinoY;
            emRealocacao = false;
        } else {
            x += (dx / distancia) * PASSO_REALOCACAO;
            y += (dy / distancia) * PASSO_REALOCACAO;
        }
    }

    /** Marca o canhão como inativo para interromper disparos, renderização e otimização. */
    public void desativar() {
        this.ativo = false;
        this.interrupt();
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getAngulo() { return angulo; }
    public boolean isAtivo() { return ativo; }
}
