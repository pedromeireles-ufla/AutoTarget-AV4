package com.autotarget.game.model;

import com.autotarget.game.exception.JogoException;
import com.autotarget.game.util.EvidenceLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Coordena o estado central da partida.
 * Controla alvos, canhões, projéteis, placar, energia, tempo de jogo e tarefas periódicas.
 */
public class Jogo {
    // Listas separadas por lado facilitam mira, otimização e cálculo de placar por equipe.
    private final List<Alvo> alvosEsquerda = new CopyOnWriteArrayList<>();
    private final List<Alvo> alvosDireita = new CopyOnWriteArrayList<>();
    private final List<Canhao> canhoesEsquerda = new CopyOnWriteArrayList<>();
    private final List<Canhao> canhoesDireita = new CopyOnWriteArrayList<>();
    private final List<Projetil> projeteis = new CopyOnWriteArrayList<>();

    // Pool de threads: spawn, cronometro, energia, otimizacao, reconciliacao, movimento de canhoes + margem
    // Pool central para executar threads de alvos, canhões e rotinas periódicas do jogo.
    private final ExecutorService gameExecutor = Executors.newFixedThreadPool(12);
    
    // Semáforos protegem trechos críticos quando listas compartilhadas são percorridas ou alteradas.
    private final Semaphore semAlvos = new Semaphore(1);
    private final Semaphore semProjeteis = new Semaphore(1);
    
    // Cada lado possui uma tarefa independente de otimização e reconciliação.
    private OptimizationTask optimizationEsquerda;
    private OptimizationTask optimizationDireita;
    
    private final int largura, altura;
    private static final int LIMITE_CANHOES = 5; // Quantidade máxima de canhões ativos por lado.
    private static final int LIMITE_PENALIDADE = 1;
    
    // Flags voláteis permitem que as threads percebam rapidamente pausa e encerramento.
    private volatile boolean emAndamento = false;
    private volatile boolean pausado = false;
    
    // Placar e energia são atualizados pelo modelo e refletidos na tela por callbacks.
    private volatile int abatesEsquerda = 0;
    private volatile int abatesDireita = 0;
    
    private volatile float energiaEsquerda = 100f;
    private volatile float energiaDireita = 100f;
    
    private int tempoRestante = 60;
    private final Random random = new Random();

    /**
     * Contrato usado pela tela para receber atualizações de tempo, placar, energia e fim de partida.
     */
    public interface JogoCallback {
        void onTempoAtualizado(int tempo);
        void onPlacarAtualizado(int esq, int dir);
        void onEnergiaAtualizada(float esq, float dir);
        void onJogoFinalizado(String vencedor, int abatesEsq, int abatesDir);
    }

    private JogoCallback callback;

    /** Configura a arena com dimensões conhecidas e registra a interface que será atualizada. */
    public Jogo(int largura, int altura, JogoCallback callback) {
        this.largura = largura;
        this.altura = altura;
        this.callback = callback;
        this.optimizationEsquerda = new OptimizationTask(this, true);
        this.optimizationDireita = new OptimizationTask(this, false);
    }

    /** Inicia a partida, cria entidades iniciais e agenda tarefas periódicas. */
    /** Inicia uma nova partida, cria defesas iniciais e aciona as rotinas periódicas. */
    public void iniciar() {
        this.emAndamento = true;
        this.pausado = false;
        this.abatesEsquerda = 0;
        this.abatesDireita = 0;
        this.energiaEsquerda = 100f;
        this.energiaDireita = 100f;
        this.tempoRestante = 60;

        EvidenceLogger.reiniciar();
        EvidenceLogger.registrarEvento("RELATÓRIO", "Dimensões da arena: largura=" + largura + " altura=" + altura);
        
        limparTudo();

        iniciarThreadSpawn();
        iniciarThreadCronometro();
        iniciarThreadEnergia();
        iniciarThreadReconciliacao();
        iniciarThreadMovimentoCanhoes();

        // Iniciar com 1 canhão de cada lado para cobertura inicial
        try {
            adicionarCanhao(largura * 0.25f, altura * 0.5f);
            adicionarCanhao(largura * 0.75f, altura * 0.5f);
        } catch (Exception e) {}
    }

    /** Remove entidades antigas para garantir que uma nova partida comece sem resíduos. */
    private void limparTudo() {
        // Desativação segura sem travar a thread principal
        for (Alvo a : getAlvos()) a.desativar();
        for (Canhao c : canhoesEsquerda) c.desativar();
        for (Canhao c : canhoesDireita) c.desativar();
        for (Projetil p : projeteis) p.desativar();
        
        alvosEsquerda.clear();
        alvosDireita.clear();
        canhoesEsquerda.clear();
        canhoesDireita.clear();
        projeteis.clear();
    }

    /** Atualiza energia periodicamente conforme a penalidade de excesso de canhões. */
    private void iniciarThreadEnergia() {
        gameExecutor.execute(() -> {
            int ciclos = 0;
            while (emAndamento) {
                if (!pausado) {
                    // Consumo em tempo real: 1 unidade por segundo por canhão.
                    // Como esta thread roda a cada 100ms, cada canhão consome 0.1 por ciclo.
                    energiaEsquerda = Math.max(0, energiaEsquerda - (canhoesEsquerda.size() * 0.1f));
                    energiaDireita = Math.max(0, energiaDireita - (canhoesDireita.size() * 0.1f));

                    ciclos++;
                    if (ciclos % 10 == 0) {
                        EvidenceLogger.registrarAmostraEnergia(
                                energiaEsquerda,
                                energiaDireita,
                                contarCanhoesAtivos(canhoesEsquerda),
                                contarCanhoesAtivos(canhoesDireita),
                                getPenalidade(true),
                                getPenalidade(false)
                        );
                    }

                    if (callback != null) callback.onEnergiaAtualizada(energiaEsquerda, energiaDireita);
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /** Aciona a reconciliação de dados em ciclos regulares para reposicionar defesas. */
    private void iniciarThreadReconciliacao() {
        gameExecutor.execute(() -> {
            while (emAndamento) {
                try {
                    Thread.sleep(10000); // A cada 10 segundos
                    if (!pausado) {
                        optimizationEsquerda.executarReconciliacao();
                        optimizationDireita.executarReconciliacao();
                    }
                } catch (InterruptedException e) { break; }
            }
        });
    }

    /** Move canhões gradualmente rumo aos destinos definidos pela otimização. */
    private void iniciarThreadMovimentoCanhoes() {
        gameExecutor.execute(() -> {
            while (emAndamento) {
                if (!pausado) {
                    for (Canhao c : canhoesEsquerda) c.moverEmDirecaoAoDestino();
                    for (Canhao c : canhoesDireita) c.moverEmDirecaoAoDestino();
                }
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        });
    }

    /** Adiciona canhão respeitando limite por lado e inicia sua thread de atuação. */
    public synchronized void adicionarCanhao(float x, float y) throws JogoException {
        boolean isEsquerda = x < largura / 2f;
        List<Canhao> lista = isEsquerda ? canhoesEsquerda : canhoesDireita;

        if (contarCanhoesAtivos(lista) >= LIMITE_CANHOES) {
            throw new JogoException("Limite de " + LIMITE_CANHOES + " canhões atingido para este lado!");
        }

        if (x < 50 || x > largura - 50 || y < 50 || y > altura - 50) {
            throw new JogoException("Posição fora dos limites!");
        }

        Canhao c = new Canhao(x, y, this, isEsquerda);
        lista.add(c);
        c.start();
    }

    /** Desativa e remove um canhão da lista correspondente ao seu lado. */
    public synchronized void removerCanhao(Canhao canhao) {
        if (canhao == null) return;

        canhoesEsquerda.remove(canhao);
        canhoesDireita.remove(canhao);
        canhao.desativar();
    }

    /** Conta quantos canhões ainda estão ativos em uma lista. */
    private int contarCanhoesAtivos(List<Canhao> canhoes) {
        int total = 0;
        for (Canhao canhao : canhoes) {
            if (canhao.isAtivo()) {
                total++;
            }
        }
        return total;
    }

    /** Mantém as listas de alvos sincronizadas quando um alvo cruza o meio da tela. */
    public void transferirAlvo(Alvo alvo, boolean paraEsquerda) {
        semAlvos.acquireUninterruptibly();
        try {
            if (paraEsquerda) {
                if (alvosDireita.remove(alvo)) {
                    alvosEsquerda.add(alvo);
                }
            } else {
                if (alvosEsquerda.remove(alvo)) {
                    alvosDireita.add(alvo);
                }
            }
        } finally {
            semAlvos.release();
        }
    }

    /** Retorna o alvo ativo mais próximo de um canhão dentro do mesmo lado da arena. */
    public Alvo getAlvoMaisProximoNoLado(float cx, float cy, boolean isEsquerda) {
        List<Alvo> listaAlvos = isEsquerda ? alvosEsquerda : alvosDireita;
        Alvo maisProximo = null;
        double menorDistancia = Double.MAX_VALUE;
        for (Alvo a : listaAlvos) {
            if (!a.isAtivo()) continue;
            float dx = a.getX() - cx;
            float dy = a.getY() - cy;
            double dist = dx * dx + dy * dy;
            if (dist < menorDistancia) {
                menorDistancia = dist;
                maisProximo = a;
            }
        }
        return maisProximo;
    }

    /** Atualiza o placar do lado que acertou o alvo e notifica a interface. */
    public synchronized void incrementarAbates(boolean isEsquerda) {
        if (isEsquerda) abatesEsquerda++;
        else abatesDireita++;
        if (callback != null) callback.onPlacarAtualizado(abatesEsquerda, abatesDireita);
    }

    /** Insere um alvo no lado inicial correto e inicia sua execução. */
    public void adicionarAlvo(Alvo alvo) {
        if (alvo.getX() < largura / 2f) alvosEsquerda.add(alvo);
        else alvosDireita.add(alvo);
        alvo.setJogo(this);
        alvo.start();
    }

    /** Remove o alvo das listas, garantindo que não continue sendo desenhado ou otimizado. */
    public void removerAlvo(Alvo alvo) {
        alvosEsquerda.remove(alvo);
        alvosDireita.remove(alvo);
    }

    /** Retorna uma visão combinada dos alvos dos dois lados da arena. */
    public List<Alvo> getAlvos() {
        List<Alvo> todos = new ArrayList<>(alvosEsquerda);
        todos.addAll(alvosDireita);
        return todos;
    }

    /** Registra e inicia um projétil disparado por algum canhão. */
    public void adicionarProjetil(Projetil p) {
        projeteis.add(p);
        p.setJogo(this);
        p.start();
    }

    public void removerProjetil(Projetil p) { projeteis.remove(p); }

    /** Alterna entre estado pausado e em execução. */
    public void alternarPausa() { this.pausado = !this.pausado; }
    public boolean isPausado() { return pausado; }
    public boolean isEmAndamento() { return emAndamento; }
    public int getLargura() { return largura; }
    public int getAltura() { return altura; }
    public List<Alvo> getAlvosEsquerda() { return alvosEsquerda; }
    public List<Alvo> getAlvosDireita() { return alvosDireita; }
    public List<Canhao> getCanhoesEsquerda() { return canhoesEsquerda; }
    public List<Canhao> getCanhoesDireita() { return canhoesDireita; }
    public List<Projetil> getProjeteis() { return projeteis; }
    public float getEnergiaEsquerda() { return energiaEsquerda; }
    public float getEnergiaDireita() { return energiaDireita; }
    public Semaphore getSemAlvos() { return semAlvos; }
    public Semaphore getSemProjeteis() { return semProjeteis; }

    /** Calcula a penalidade de energia de acordo com a quantidade de canhões do lado. */
    public int getPenalidade(boolean isEsquerda) {
        List<Canhao> lista = isEsquerda ? canhoesEsquerda : canhoesDireita;
        int n = contarCanhoesAtivos(lista);

        if (n <= LIMITE_PENALIDADE) {
            return 0;
        }

        return (n - LIMITE_PENALIDADE) * 20;
    }

    public OptimizationTask getOptimizationEsquerda() { return optimizationEsquerda; }
    public OptimizationTask getOptimizationDireita() { return optimizationDireita; }
    public int getAbatesEsquerda() { return abatesEsquerda; }
    public int getAbatesDireita() { return abatesDireita; }

    /** Gera novos alvos periodicamente enquanto a partida estiver em andamento. */
    private void iniciarThreadSpawn() {
        gameExecutor.execute(() -> {
            while (emAndamento) {
                if (!pausado) {
                    boolean spawnEsquerda = random.nextBoolean();

                    float margem = 50f;
                    float meio = largura / 2f;
                    float rx;

                    if (spawnEsquerda) {
                        // Sorteia somente dentro da metade esquerda, respeitando a margem.
                        rx = margem + random.nextFloat() * (meio - margem);
                    } else {
                        // Sorteia somente dentro da metade direita, respeitando a margem.
                        rx = meio + random.nextFloat() * (largura - meio - margem);
                    }

                    float ry = margem + random.nextFloat() * (altura - 2 * margem);

                    if (random.nextBoolean()) {
                        adicionarAlvo(new AlvoComum(rx, ry, largura, altura));
                    } else {
                        adicionarAlvo(new AlvoRapido(rx, ry, largura, altura));
                    }
                }

                try {
                    Thread.sleep(600 + random.nextInt(400));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /** Decrementa o tempo da partida e encerra o jogo quando o cronômetro chega a zero. */
    private void iniciarThreadCronometro() {
        gameExecutor.execute(() -> {
            while (tempoRestante > 0 && emAndamento) {
                try {
                    Thread.sleep(1000);
                    if (!pausado) {
                        tempoRestante--;
                        if (callback != null) callback.onTempoAtualizado(tempoRestante);
                    }
                } catch (InterruptedException e) { break; }
            }
            if (emAndamento && tempoRestante <= 0) finalizarJogo();
        });
    }

    /** Finaliza a partida, calcula o vencedor e informa a interface. */
    /** Encerra a partida, interrompe entidades e informa o vencedor para a interface. */
    public void finalizarJogo() {
        if (!emAndamento) return;
        this.emAndamento = false;
        
        String vencedor;
        if (abatesEsquerda > abatesDireita) vencedor = "VENCEDOR: SISTEMA A";
        else if (abatesDireita > abatesEsquerda) vencedor = "VENCEDOR: SISTEMA B";
        else vencedor = "EMPATE!";

        EvidenceLogger.registrarEvento(
                "PLACAR",
                "resultado=" + vencedor + " | abates_A=" + abatesEsquerda + " | abates_B=" + abatesDireita
        );

        gameExecutor.execute(() -> {
            limparTudo();
            if (callback != null) callback.onJogoFinalizado(vencedor, abatesEsquerda, abatesDireita);
        });
    }
}
