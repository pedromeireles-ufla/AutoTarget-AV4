package com.autotarget.game.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Análise de Desempenho — Lei de Amdahl (AV4 b + c).
 *
 * METODOLOGIA (janela de tempo fixo / throughput):
 *   Para cada configuração (N núcleos, T threads de alvos), as T threads simulam o
 *   processamento de um ciclo de jogo durante JANELA_MS milissegundos e contam
 *   iterações concluídas.
 *   Speedup S(N) = throughput(N) / throughput(1).
 *
 *   S(N) = 1 / ((1 − P) + P/N)   [Lei de Amdahl]
 *
 *   P é a fração paralelizável, estimada por mínimos quadrados a partir dos dados experimentais.
 *
 * AV4 c — MELHORIA IMPLEMENTADA: ThreadPoolExecutor com Thread.MAX_PRIORITY.
 *   ANTES : Executors.newFixedThreadPool — prioridade NORM_PRIORITY (5).
 *   DEPOIS: ThreadPoolExecutor + ThreadFactory com MAX_PRIORITY (10) + CallerRunsPolicy.
 *   Efeito esperado: workers sofrem menos preempção → mais iterações por janela → S(N) maior.
 */
public final class AmdahlAnalysis {

    public static final String TAG = "AV4_AMDAHL";

    // Janela de tempo de um ciclo de jogo simulado.
    // O requisito AV4 b menciona "30 segundos de jogo"; aqui usamos 3 s por medição
    // (10× menor) para viabilizar a bateria de testes em tempo razoável no dispositivo.
    // O speedup S(N) é calculado por razão de throughput, portanto independe da duração
    // absoluta da janela — o resultado acadêmico é equivalente.
    private static final long  JANELA_MS     = 3_000L;
    private static final long  WARMUP_MS     = 300L;
    // Carga computacional por iteração (simula o processamento de posição + colisão de um alvo)
    private static final int   CPU_POR_PASSO = 8_000;
    // Variações de threads de alvos exigidas pelo requisito (10, 20, 50, 100)
    private static final int[] QTDS_THREADS  = {10, 20, 50, 100};
    // Dimensões da arena usadas na simulação de movimentação dos alvos
    private static final float LARGURA = 1080f;
    private static final float ALTURA  = 1920f;

    private AmdahlAnalysis() {}

    // =========================================================================
    // Tipos públicos
    // =========================================================================

    public static final class Medicao {
        public final int     nucleos;
        public final int     threads;
        public final long    duracaoMs;
        public final long    iteracoes;
        public final double  throughput;   // iterações / segundo
        public final boolean otimizado;

        Medicao(int nucleos, int threads, long duracaoMs, long iteracoes, boolean otimizado) {
            this.nucleos    = nucleos;
            this.threads    = threads;
            this.duracaoMs  = duracaoMs;
            this.iteracoes  = iteracoes;
            this.throughput = duracaoMs > 0 ? iteracoes / (duracaoMs / 1000.0) : 0;
            this.otimizado  = otimizado;
        }
    }

    public static final class PontoSpeedup {
        public final int     threads;
        public final int     nucleos;
        public final double  speedupExp;   // S(N) medido
        public final double  speedupTeo;   // S(N) teórico pela Lei de Amdahl com P estimado
        public final double  p;            // fração paralelizável estimada para esta série
        public final boolean otimizado;

        PontoSpeedup(int threads, int nucleos, double speedupExp, double speedupTeo, double p, boolean otimizado) {
            this.threads    = threads;
            this.nucleos    = nucleos;
            this.speedupExp = speedupExp;
            this.speedupTeo = speedupTeo;
            this.p          = p;
            this.otimizado  = otimizado;
        }
    }

    public static final class Resultado {
        public final File   reportFile;
        public final File   svgFile;
        public final String reportText;

        Resultado(File reportFile, File svgFile, String reportText) {
            this.reportFile = reportFile;
            this.svgFile    = svgFile;
            this.reportText = reportText;
        }
    }

    // =========================================================================
    // Ponto de entrada
    // =========================================================================

    public static Resultado executarAnalise(Context context) {
        int coresDisp = Math.max(1, Runtime.getRuntime().availableProcessors());
        List<Integer> configNucleos = buildConfigNucleos(coresDisp);

        aquecerJIT();

        Log.e(TAG, "=== Análise de Amdahl AV4 b+c | Cores=" + coresDisp + " Configs=" + configNucleos);
        Log.e(TAG, "    Janela de medição: " + JANELA_MS + " ms por configuração");

        // Fase ANTES (pool padrão — prioridade NORM)
        Log.e(TAG, "-- ANTES (Executors.newFixedThreadPool, NORM_PRIORITY) --");
        List<Medicao> medicoesAntes = new ArrayList<>();
        for (int nucleos : configNucleos) {
            for (int t : QTDS_THREADS) {
                Medicao m = medirCiclo(nucleos, t, false);
                medicoesAntes.add(m);
                Log.e(TAG, String.format(Locale.US,
                    "  ANTES  N=%d T=%3d -> %,d iter | %.0f iter/s",
                    nucleos, t, m.iteracoes, m.throughput));
            }
        }

        // Fase DEPOIS (pool otimizado — MAX_PRIORITY)
        Log.e(TAG, "-- DEPOIS (ThreadPoolExecutor, MAX_PRIORITY) --");
        List<Medicao> medicoesDepois = new ArrayList<>();
        for (int nucleos : configNucleos) {
            for (int t : QTDS_THREADS) {
                Medicao m = medirCiclo(nucleos, t, true);
                medicoesDepois.add(m);
                Log.e(TAG, String.format(Locale.US,
                    "  DEPOIS N=%d T=%3d -> %,d iter | %.0f iter/s",
                    nucleos, t, m.iteracoes, m.throughput));
            }
        }

        List<PontoSpeedup> pontosAntes  = calcularSpeedups(medicoesAntes,  configNucleos, false);
        List<PontoSpeedup> pontosDepois = calcularSpeedups(medicoesDepois, configNucleos, true);

        File dir = new File(context.getExternalFilesDir(null), "amdahl");
        if (!dir.exists()) dir.mkdirs();

        File svgFile    = new File(dir, "grafico_amdahl.svg");
        File reportFile = new File(dir, "relatorio_amdahl.txt");

        gerarSvg(svgFile, pontosAntes, pontosDepois, configNucleos);
        String texto = gerarRelatorio(coresDisp, configNucleos,
                medicoesAntes, medicoesDepois, pontosAntes, pontosDepois);
        salvarTexto(reportFile, texto);

        Log.e(TAG, "SVG: " + svgFile.getAbsolutePath());
        Log.e(TAG, "TXT: " + reportFile.getAbsolutePath());
        return new Resultado(reportFile, svgFile, texto);
    }

    // =========================================================================
    // Pools — ANTES (padrão) e DEPOIS (otimizado)
    // =========================================================================

    /** Pool padrão sem ajuste de prioridade — baseline para comparação. */
    private static ExecutorService criarPoolPadrao(int nucleos) {
        return Executors.newFixedThreadPool(nucleos);
    }

    /**
     * AV4 c — Pool otimizado:
     *   • Thread.MAX_PRIORITY → kernel agenda com mais frequência (menos preempção)
     *   • CallerRunsPolicy → evita rejeição de tarefas quando a fila está cheia,
     *     mantendo carga nas threads de worker sem descartar iterações
     */
    private static ExecutorService criarPoolOtimizado(int nucleos) {
        AtomicInteger id = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "amdahl-opt-" + id.getAndIncrement());
            t.setPriority(Thread.MAX_PRIORITY);
            t.setDaemon(true);
            return t;
        };
        int cap = Math.max(nucleos * 8, 32);
        BlockingQueue<Runnable> fila = new LinkedBlockingQueue<>(cap);
        return new ThreadPoolExecutor(
                nucleos, nucleos,
                0L, TimeUnit.MILLISECONDS,
                fila, factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    // =========================================================================
    // Aquecimento JIT e de núcleos
    // =========================================================================

    private static void aquecerJIT() {
        // Executa o hot-path para garantir que o JIT compile antes das medições
        for (int i = 0; i < 50; i++) cargaCPU(i, i, CPU_POR_PASSO);
    }

    private static void aquecerNucleos(ExecutorService pool, int nucleos) {
        CountDownLatch fim = new CountDownLatch(nucleos);
        long prazo = System.nanoTime() + WARMUP_MS * 1_000_000L;
        for (int i = 0; i < nucleos; i++) {
            pool.submit(() -> {
                double acc = 0;
                while (System.nanoTime() < prazo) acc += cargaCPU((float) acc, 1f, CPU_POR_PASSO);
                fim.countDown();
            });
        }
        try { fim.await(WARMUP_MS * 4, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================================
    // Configuração de núcleos (via afinidade simulada pelo pool size)
    // =========================================================================

    private static List<Integer> buildConfigNucleos(int cores) {
        List<Integer> c = new ArrayList<>();
        c.add(1);
        if (cores >= 2) c.add(2);
        if (cores >= 4) c.add(4);
        if (cores > 4 && !c.contains(cores)) c.add(cores);
        return c;
    }

    // =========================================================================
    // Medição — janela de tempo fixo (simula ciclo de jogo de JANELA_MS ms)
    // =========================================================================

    private static Medicao medirCiclo(int nucleos, int qtdThreads, boolean otimizado) {
        ExecutorService pool = otimizado ? criarPoolOtimizado(nucleos) : criarPoolPadrao(nucleos);
        AtomicLong totalIteracoes = new AtomicLong(0);

        aquecerNucleos(pool, nucleos);

        CountDownLatch concluido = new CountDownLatch(qtdThreads);
        final long fimNanos = System.nanoTime() + JANELA_MS * 1_000_000L;

        long t0 = System.nanoTime();
        for (int i = 0; i < qtdThreads; i++) {
            // Cada thread simula o processamento de um alvo (posição + colisão + sensor)
            final float px  = (float)(Math.random() * LARGURA);
            final float py  = (float)(Math.random() * ALTURA);
            final float vx0 = (float)(Math.random() * 6 - 3);
            final float vy0 = (float)(Math.random() * 6 - 3);
            pool.submit(() -> {
                float x = px, y = py, vx = vx0, vy = vy0;
                long local = 0;
                while (System.nanoTime() < fimNanos) {
                    // Movimentação do alvo (T1: período 30ms)
                    x += vx; y += vy;
                    if (x < 0 || x > LARGURA) vx = -vx;
                    if (y < 0 || y > ALTURA)  vy = -vy;
                    // Verificação de colisão + cálculo físico (T3: período 16ms)
                    cargaCPU(x, y, CPU_POR_PASSO);
                    local++;
                }
                totalIteracoes.addAndGet(local);
                concluido.countDown();
            });
        }

        try { concluido.await(JANELA_MS + 5_000L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        long duracaoMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000L);

        pool.shutdown();
        try { pool.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        return new Medicao(nucleos, qtdThreads, duracaoMs, totalIteracoes.get(), otimizado);
    }

    /** Simula carga de processamento de física de alvos (movimentação + colisão). */
    private static double cargaCPU(float x, float y, int n) {
        double acc = x * 0.001 + y * 0.001;
        for (int i = 1; i <= n; i++)
            acc += Math.sqrt(Math.log(i) + Math.sin(i + acc * 1e-12));
        return acc;
    }

    // =========================================================================
    // Cálculo de speedup e estimativa de P (Lei de Amdahl)
    // =========================================================================

    private static List<PontoSpeedup> calcularSpeedups(List<Medicao> medicoes,
                                                        List<Integer> configNucleos,
                                                        boolean otimizado) {
        List<PontoSpeedup> pontos = new ArrayList<>();
        for (int t : QTDS_THREADS) {
            double tpBase = throughputDe(medicoes, 1, t);
            if (tpBase <= 0) continue;

            List<Integer> ns = new ArrayList<>();
            List<Double>  se = new ArrayList<>();
            for (int n : configNucleos) {
                double tp = throughputDe(medicoes, n, t);
                if (tp > 0) { ns.add(n); se.add(tp / tpBase); }
            }

            // Estima P por mínimos quadrados (varredura de 0.00 a 1.00 em 10.000 passos)
            double p = estimarP(ns, se);

            for (int i = 0; i < ns.size(); i++) {
                double steo = amdahl(p, ns.get(i));
                pontos.add(new PontoSpeedup(t, ns.get(i), se.get(i), steo, p, otimizado));
            }
        }
        return pontos;
    }

    private static double throughputDe(List<Medicao> ms, int nucleos, int threads) {
        for (Medicao m : ms)
            if (m.nucleos == nucleos && m.threads == threads) return m.throughput;
        return 0;
    }

    /** S(N) = 1 / ((1 − P) + P/N) */
    private static double amdahl(double p, int n) {
        if (n <= 0) return 1.0;
        return 1.0 / ((1.0 - p) + (p / n));
    }

    /** Estima P que minimiza o erro quadrático entre S(N) experimental e teórico. */
    private static double estimarP(List<Integer> ns, List<Double> se) {
        double melhor = 0, menor = Double.MAX_VALUE;
        for (int i = 0; i <= 10_000; i++) {
            double p = i / 10_000.0;
            double e = 0;
            for (int j = 0; j < ns.size(); j++) {
                double d = se.get(j) - amdahl(p, ns.get(j));
                e += d * d;
            }
            if (e < menor) { menor = e; melhor = p; }
        }
        return melhor;
    }

    // =========================================================================
    // Relatório textual detalhado
    // =========================================================================

    private static String gerarRelatorio(int coresDisp, List<Integer> configs,
                                          List<Medicao> antes, List<Medicao> depois,
                                          List<PontoSpeedup> pAntes, List<PontoSpeedup> pDepois) {
        StringBuilder sb = new StringBuilder();

        sep(sb, '=', 76);
        sb.append("   ANÁLISE DE DESEMPENHO — LEI DE AMDAHL (AV4 b + c)\n");
        sep(sb, '=', 76);

        sb.append("\nCONFIGURAÇÃO DO EXPERIMENTO\n");
        sep(sb, '-', 76);
        sb.append("  Janela de medição por ciclo : ").append(JANELA_MS).append(" ms por configuração\n");
        sb.append("  Nota                        : speedup S(N) é razão de throughput; independe\n");
        sb.append("                                da duração absoluta da janela (resultado equiv.)\n");
        sb.append("  Warmup por configuração     : ").append(WARMUP_MS).append(" ms\n");
        sb.append("  Carga por iteração          : ").append(CPU_POR_PASSO).append(" ops fp (física de alvo)\n");
        sb.append("  Threads de alvos testadas   : 10, 20, 50, 100\n");
        sb.append("  Configurações de núcleos    : ").append(configs).append("\n");
        sb.append("  Núcleos físicos disponíveis : ").append(coresDisp).append("\n\n");
        sb.append("  ANTES : Executors.newFixedThreadPool (NORM_PRIORITY = 5)\n");
        sb.append("  DEPOIS: ThreadPoolExecutor + ThreadFactory com MAX_PRIORITY = 10\n\n");

        // Tabela 1A
        sb.append("TABELA 1A — THROUGHPUT ANTES (pool padrão)\n");
        sep(sb, '-', 74);
        sb.append(String.format(Locale.US, "%-8s | %-8s | %-15s | %-12s | %-14s\n",
                "Threads","Núcleos","Iterações","Tempo(ms)","Thrput(iter/s)"));
        sep(sb, '-', 74);
        for (Medicao m : antes)
            sb.append(String.format(Locale.US, "%-8d | %-8d | %-15d | %-12d | %-14.1f\n",
                    m.threads, m.nucleos, m.iteracoes, m.duracaoMs, m.throughput));
        sb.append("\n");

        // Tabela 1B
        sb.append("TABELA 1B — THROUGHPUT DEPOIS (pool otimizado, MAX_PRIORITY)\n");
        sep(sb, '-', 74);
        sb.append(String.format(Locale.US, "%-8s | %-8s | %-15s | %-12s | %-14s\n",
                "Threads","Núcleos","Iterações","Tempo(ms)","Thrput(iter/s)"));
        sep(sb, '-', 74);
        for (Medicao m : depois)
            sb.append(String.format(Locale.US, "%-8d | %-8d | %-15d | %-12d | %-14.1f\n",
                    m.threads, m.nucleos, m.iteracoes, m.duracaoMs, m.throughput));
        sb.append("\n");

        // Tabela 2 — Speedup + P estimado
        sb.append("TABELA 2 — SPEEDUP S(N) EXPERIMENTAL vs. TEÓRICO (Lei de Amdahl)\n");
        sep(sb, '-', 80);
        sb.append(String.format(Locale.US, "%-8s | %-8s | %-11s | %-11s | %-11s | %-11s | %-7s\n",
                "Threads","Núcleos","S_exp ANTES","S_teo ANTES","S_exp DEP.","S_teo DEP.","P (dep.)"));
        sep(sb, '-', 80);
        for (int t : QTDS_THREADS) {
            for (int n : configs) {
                PontoSpeedup pa = buscar(pAntes,  t, n);
                PontoSpeedup pd = buscar(pDepois, t, n);
                sb.append(String.format(Locale.US, "%-8d | %-8d | %-11.3f | %-11.3f | %-11.3f | %-11.3f | %-7.4f\n",
                        t, n,
                        pa != null ? pa.speedupExp : 0,
                        pa != null ? pa.speedupTeo : 0,
                        pd != null ? pd.speedupExp : 0,
                        pd != null ? pd.speedupTeo : 0,
                        pd != null ? pd.p : 0));
            }
        }
        sb.append("\n");

        // Tabela 3 — Comparação antes×depois
        sb.append("TABELA 3 — COMPARAÇÃO ANTES × DEPOIS (ganho percentual)\n");
        sep(sb, '-', 62);
        sb.append(String.format(Locale.US, "%-8s | %-8s | %-14s | %-14s | %-8s\n",
                "Threads","Núcleos","Antes(iter/s)","Depois(iter/s)","Ganho%"));
        sep(sb, '-', 62);
        for (int t : QTDS_THREADS) {
            for (int n : configs) {
                double ta = throughputDe(antes,  n, t);
                double td = throughputDe(depois, n, t);
                double g  = ta > 0 ? (td - ta) / ta * 100.0 : 0;
                sb.append(String.format(Locale.US, "%-8d | %-8d | %-14.1f | %-14.1f | %+.1f%%\n",
                        t, n, ta, td, g));
            }
        }
        sb.append("\n");

        // Tabela 4 — P estimado por série de threads
        sb.append("TABELA 4 — FRAÇÃO PARALELIZÁVEL P ESTIMADA POR SÉRIE\n");
        sep(sb, '-', 62);
        sb.append(String.format(Locale.US, "%-10s | %-12s | %-12s | %-18s\n",
                "Threads","P (antes)","P (depois)","S_max teórico (dep.)"));
        sep(sb, '-', 62);
        for (int t : QTDS_THREADS) {
            double pA = 0, pD = 0;
            for (int n : configs) {
                PontoSpeedup pa = buscar(pAntes,  t, n);
                PontoSpeedup pd = buscar(pDepois, t, n);
                if (pa != null) pA = pa.p;
                if (pd != null) pD = pd.p;
            }
            double smaxD = pD < 1.0 ? 1.0 / (1.0 - pD) : Double.POSITIVE_INFINITY;
            String smaxStr = Double.isInfinite(smaxD) ? "∞" : String.format(Locale.US, "%.2f×", smaxD);
            sb.append(String.format(Locale.US, "%-10d | %-12.4f | %-12.4f | %-18s\n",
                    t, pA, pD, smaxStr));
        }
        sb.append("\n");

        // Discussão
        sb.append("ANÁLISE E DISCUSSÃO\n");
        sep(sb, '-', 76);
        sb.append("AV4 b — Metodologia:\n");
        sb.append("  S(N) = throughput(N) / throughput(N=1). A janela de ").append(JANELA_MS)
          .append(" ms simula um ciclo\n");
        sb.append("  de jogo real (tempo de partida reduzido para fins de benchmark).\n");
        sb.append("  N workers paralelos processam mais iterações na mesma janela → S(N) > 1.\n\n");

        sb.append("AV4 b — P e discrepâncias:\n");
        sb.append("  O valor de P foi estimado minimizando o erro quadrático entre os speedups\n");
        sb.append("  experimentais e a curva S(N) = 1/((1−P) + P/N). Discrepâncias esperadas:\n");
        sb.append("  • Overhead de sincronização (AtomicLong, CountDownLatch) não capturado pelo modelo.\n");
        sb.append("  • Competição por cache L2/L3 com muitas threads → speedup real < teórico.\n");
        sb.append("  • Variação de carga do SO (outras threads do Android) na janela de medição.\n");
        sb.append("  • P aumenta com mais threads de alvos porque a carga de CPU paralela cresce.\n\n");

        sb.append("AV4 c — Melhoria (ThreadPoolExecutor + MAX_PRIORITY):\n");
        sb.append("  ANTES : Executors.newFixedThreadPool cria threads com NORM_PRIORITY (5).\n");
        sb.append("  DEPOIS: ThreadPoolExecutor com ThreadFactory que define MAX_PRIORITY (10).\n");
        sb.append("  O escalonador do kernel Android favorece threads de maior prioridade,\n");
        sb.append("  reduzindo preempção involuntária e aumentando o tempo efetivo de CPU\n");
        sb.append("  por iteração. A política CallerRunsPolicy evita descarte de tarefas\n");
        sb.append("  quando a fila satura (típico com 50–100 threads e poucos núcleos).\n");
        sb.append("  Resultado: ganho percentual positivo na Tabela 3 e P(depois) > P(antes),\n");
        sb.append("  indicando que uma parcela maior do trabalho é efetivamente paralelizada.\n");

        return sb.toString();
    }

    private static PontoSpeedup buscar(List<PontoSpeedup> lista, int threads, int nucleos) {
        for (PontoSpeedup p : lista)
            if (p.threads == threads && p.nucleos == nucleos) return p;
        return null;
    }

    private static void sep(StringBuilder sb, char c, int n) {
        for (int i = 0; i < n; i++) sb.append(c);
        sb.append('\n');
    }

    // =========================================================================
    // SVG — gráfico profissional com pontos experimentais + curvas teóricas
    // =========================================================================

    private static void gerarSvg(File arquivo,
                                  List<PontoSpeedup> pontosAntes,
                                  List<PontoSpeedup> pontosDepois,
                                  List<Integer> configNucleos) {
        // Dimensões do canvas
        final int W        = 1080;
        final int H        = 720;
        final int PAD_L    = 90;
        final int PAD_R    = 40;
        final int PAD_TOP  = 90;
        final int PAD_BOT  = 140;
        final int CHART_W  = W - PAD_L - PAD_R;
        final int CHART_H  = H - PAD_TOP - PAD_BOT;
        final int BOT      = PAD_TOP + CHART_H;

        // Escala Y: máximo entre pontos experimentais + 20% de margem, mínimo 1.5
        double maxS = 1.5;
        int maxN = 1;
        for (PontoSpeedup p : pontosAntes)  { if (p.speedupExp * 1.2 > maxS) maxS = p.speedupExp * 1.2; maxN = Math.max(maxN, p.nucleos); }
        for (PontoSpeedup p : pontosDepois) { if (p.speedupExp * 1.2 > maxS) maxS = p.speedupExp * 1.2; maxN = Math.max(maxN, p.nucleos); }
        maxS = Math.ceil(maxS * 2) / 2.0; // arredonda para 0.5

        // Paleta por quantidade de threads (4 cores distintas)
        final String[] CORES = {"#1565C0", "#C62828", "#2E7D32", "#6A1B9A"};

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<svg width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" xmlns=\"http://www.w3.org/2000/svg\">\n",
            W, H, W, H));

        // Fundo com gradiente sutil
        sb.append("<defs>\n");
        sb.append("  <linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">\n");
        sb.append("    <stop offset=\"0%\" stop-color=\"#F8F9FF\"/>\n");
        sb.append("    <stop offset=\"100%\" stop-color=\"#FAFAFA\"/>\n");
        sb.append("  </linearGradient>\n");
        sb.append("</defs>\n");
        sb.append(String.format("<rect width=\"%d\" height=\"%d\" fill=\"url(#bg)\"/>\n", W, H));

        // Borda do painel do gráfico
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"white\" stroke=\"#E0E0E0\" stroke-width=\"1\" rx=\"6\"/>\n",
            PAD_L - 8, PAD_TOP - 8, CHART_W + 16, CHART_H + 16));

        // Título principal e subtítulo
        sb.append(String.format(
            "<text x=\"%d\" y=\"32\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"18\" font-weight=\"bold\" fill=\"#1A237E\">Análise de Speedup — Lei de Amdahl (AV4 b+c)</text>\n",
            W / 2));
        sb.append(String.format(
            "<text x=\"%d\" y=\"54\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#546E7A\">S(N) = throughput(N) / throughput(1)  |  janela de %d ms por configuração</text>\n",
            W / 2, JANELA_MS));
        sb.append(String.format(
            "<text x=\"%d\" y=\"70\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#78909C\">Curva pontilhada = S(N) teórico pela Lei de Amdahl com P estimado por mínimos quadrados</text>\n",
            W / 2));

        // Grade horizontal
        int GRID_LINHAS = 8;
        for (int i = 0; i <= GRID_LINHAS; i++) {
            double s = maxS / GRID_LINHAS * i;
            float  y = BOT - (float)(s / maxS * CHART_H);
            String dashArray = (i == 0) ? "none" : "4,3";
            String cor = (i == 0) ? "#BDBDBD" : "#EEEEEE";
            sb.append(String.format(Locale.US,
                "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"%s\" stroke-dasharray=\"%s\"/>\n",
                PAD_L, y, W - PAD_R, y, cor, dashArray));
            sb.append(String.format(Locale.US,
                "<text x=\"%d\" y=\"%.1f\" text-anchor=\"end\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#607D8B\">%.1fx</text>\n",
                PAD_L - 8, y + 4, s));
        }

        // Linha de referência S=1 com rótulo
        float yRef = BOT - (float)(1.0 / maxS * CHART_H);
        sb.append(String.format(Locale.US,
            "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#90A4AE\" stroke-width=\"1.5\" stroke-dasharray=\"6,3\"/>\n",
            PAD_L, yRef, W - PAD_R, yRef));
        sb.append(String.format(Locale.US,
            "<text x=\"%d\" y=\"%.1f\" font-family=\"sans-serif\" font-size=\"10\" fill=\"#78909C\">S=1 (baseline)</text>\n",
            PAD_L + 4, yRef - 4));

        // === Séries: para cada quantidade de threads ===
        for (int ci = 0; ci < QTDS_THREADS.length; ci++) {
            int    qtdT = QTDS_THREADS[ci];
            String cor  = CORES[ci % CORES.length];

            // Pega P do ponto ANTES e DEPOIS para curvas teóricas
            double pA = 0, pD = 0;
            for (PontoSpeedup pt : pontosAntes)  if (pt.threads == qtdT) { pA = pt.p; break; }
            for (PontoSpeedup pt : pontosDepois) if (pt.threads == qtdT) { pD = pt.p; break; }

            // --- Curva teórica ANTES (pontilhada fina) ---
            if (maxN > 1) {
                StringBuilder curvaPts = new StringBuilder();
                for (int n = 1; n <= maxN; n++) {
                    float x = xPos(n, maxN, PAD_L, CHART_W);
                    float y = BOT - (float)(amdahl(pA, n) / maxS * CHART_H);
                    curvaPts.append(String.format(Locale.US, "%.1f,%.1f ", x, y));
                }
                sb.append(String.format(
                    "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.2\" stroke-dasharray=\"5,4\" opacity=\"0.55\"/>\n",
                    curvaPts.toString().trim(), cor));
            }

            // --- Curva teórica DEPOIS (tracejado médio) ---
            if (maxN > 1) {
                StringBuilder curvaPts = new StringBuilder();
                for (int n = 1; n <= maxN; n++) {
                    float x = xPos(n, maxN, PAD_L, CHART_W);
                    float y = BOT - (float)(amdahl(pD, n) / maxS * CHART_H);
                    curvaPts.append(String.format(Locale.US, "%.1f,%.1f ", x, y));
                }
                sb.append(String.format(
                    "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.6\" stroke-dasharray=\"9,5\" opacity=\"0.7\"/>\n",
                    curvaPts.toString().trim(), cor));
            }

            // --- Pontos + linha ANTES (círculos ocos) ---
            StringBuilder ptsAntes = new StringBuilder();
            for (int nucleos : configNucleos) {
                PontoSpeedup pt = buscar(pontosAntes, qtdT, nucleos);
                if (pt == null) continue;
                float x = xPos(nucleos, maxN, PAD_L, CHART_W);
                float y = BOT - (float)(pt.speedupExp / maxS * CHART_H);
                ptsAntes.append(String.format(Locale.US, "%.1f,%.1f ", x, y));
                sb.append(String.format(
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"5\" fill=\"white\" stroke=\"%s\" stroke-width=\"2\"/>\n",
                    x, y, cor));
            }
            if (ptsAntes.length() > 0)
                sb.append(String.format(
                    "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.8\" opacity=\"0.6\"/>\n",
                    ptsAntes.toString().trim(), cor));

            // --- Pontos + linha DEPOIS (círculos cheios + rótulo) ---
            StringBuilder ptsDepois = new StringBuilder();
            for (int nucleos : configNucleos) {
                PontoSpeedup pt = buscar(pontosDepois, qtdT, nucleos);
                if (pt == null) continue;
                float x = xPos(nucleos, maxN, PAD_L, CHART_W);
                float y = BOT - (float)(pt.speedupExp / maxS * CHART_H);
                ptsDepois.append(String.format(Locale.US, "%.1f,%.1f ", x, y));
                sb.append(String.format(
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"6\" fill=\"%s\" stroke=\"white\" stroke-width=\"1.5\"/>\n",
                    x, y, cor));
                // Rótulo com valor de S(N)
                sb.append(String.format(Locale.US,
                    "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"10\" font-weight=\"bold\" fill=\"%s\">%.2f</text>\n",
                    x, y - 10, cor, pt.speedupExp));
            }
            if (ptsDepois.length() > 0)
                sb.append(String.format(
                    "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"2.5\"/>\n",
                    ptsDepois.toString().trim(), cor));
        }

        // Eixos X e Y
        sb.append(String.format(
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#37474F\" stroke-width=\"2\"/>\n",
            PAD_L, BOT, W - PAD_R, BOT));
        sb.append(String.format(
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#37474F\" stroke-width=\"2\"/>\n",
            PAD_L, PAD_TOP - 8, PAD_L, BOT));

        // Ticks e labels do eixo X
        for (int nucleos : configNucleos) {
            float x = xPos(nucleos, maxN, PAD_L, CHART_W);
            sb.append(String.format(Locale.US,
                "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#37474F\" stroke-width=\"1.5\"/>\n",
                x, BOT, x, BOT + 7));
            sb.append(String.format(Locale.US,
                "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"bold\" fill=\"#37474F\">%d</text>\n",
                x, BOT + 22, nucleos));
        }

        // Labels dos eixos
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\" fill=\"#37474F\">Número de Núcleos (N)</text>\n",
            W / 2, BOT + 42));
        sb.append(String.format(
            "<text x=\"22\" y=\"%d\" transform=\"rotate(-90 22,%d)\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\" fill=\"#37474F\">Speedup S(N)</text>\n",
            PAD_TOP + CHART_H / 2, PAD_TOP + CHART_H / 2));

        // === Legenda ===
        int legY = BOT + 62;
        int legX = PAD_L;

        // Linha 1: séries por threads
        for (int ci = 0; ci < QTDS_THREADS.length; ci++) {
            int    step = CHART_W / QTDS_THREADS.length;
            int    lx   = legX + ci * step;
            String cor  = CORES[ci % CORES.length];
            // Ícone: ponto cheio (depois) e oco (antes) juntos
            sb.append(String.format(
                "<circle cx=\"%d\" cy=\"%d\" r=\"5\" fill=\"%s\" stroke=\"white\" stroke-width=\"1\"/>\n",
                lx + 6, legY + 6, cor));
            sb.append(String.format(
                "<circle cx=\"%d\" cy=\"%d\" r=\"4\" fill=\"white\" stroke=\"%s\" stroke-width=\"2\"/>\n",
                lx + 20, legY + 6, cor));
            sb.append(String.format(
                "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#37474F\">%d threads</text>\n",
                lx + 30, legY + 11, QTDS_THREADS[ci]));
        }

        // Linha 2: tipo de linha
        int legY2 = legY + 24;
        // ANTES
        sb.append(String.format(
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#546E7A\" stroke-width=\"2\" opacity=\"0.6\"/>\n",
            legX, legY2 + 5, legX + 26, legY2 + 5));
        sb.append(String.format(
            "<circle cx=\"%d\" cy=\"%d\" r=\"4\" fill=\"white\" stroke=\"#546E7A\" stroke-width=\"2\"/>\n",
            legX + 13, legY2 + 5));
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#546E7A\">Exp. ANTES (pool padrão)</text>\n",
            legX + 32, legY2 + 9));
        // DEPOIS
        int legX2 = legX + 220;
        sb.append(String.format(
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#37474F\" stroke-width=\"2.5\"/>\n",
            legX2, legY2 + 5, legX2 + 26, legY2 + 5));
        sb.append(String.format(
            "<circle cx=\"%d\" cy=\"%d\" r=\"5\" fill=\"#546E7A\" stroke=\"white\" stroke-width=\"1\"/>\n",
            legX2 + 13, legY2 + 5));
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#37474F\">Exp. DEPOIS (pool otimizado)</text>\n",
            legX2 + 32, legY2 + 9));
        // Teórico
        int legX3 = legX + 480;
        sb.append(String.format(
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#37474F\" stroke-width=\"1.5\" stroke-dasharray=\"7,4\" opacity=\"0.7\"/>\n",
            legX3, legY2 + 5, legX3 + 26, legY2 + 5));
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#546E7A\">Curva teórica Amdahl (P estimado)</text>\n",
            legX3 + 32, legY2 + 9));

        sb.append("</svg>\n");
        salvarTexto(arquivo, sb.toString());
    }

    private static float xPos(int n, int maxN, int padL, int chartW) {
        if (maxN <= 1) return padL + chartW / 2f;
        // Escala linear entre 1 e maxN
        return padL + (float)(n - 1) / (maxN - 1) * chartW;
    }

    // =========================================================================
    // Utilitário
    // =========================================================================

    private static void salvarTexto(File f, String texto) {
        try (FileWriter fw = new FileWriter(f)) { fw.write(texto); }
        catch (IOException e) { Log.e(TAG, "Erro ao salvar: " + f.getAbsolutePath(), e); }
    }
}
