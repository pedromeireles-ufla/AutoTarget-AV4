package com.autotarget.game.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gera a análise de escalonabilidade usada no relatório da AV2.
 * Combina o cálculo teórico Rate Monotonic com medições experimentais por configuração de CPU
 * e produz relatório textual e gráfico.
 */
public final class SchedulingAnalysis {
    private static final String TAG = "SchedulingAnalysis";
    // Quantidade de execuções experimentais por tarefa em cada configuração de núcleos.
    private static final int AMOSTRAS_POR_TAREFA = 4;
    private static final String SCHED_TAG = "AV2_ESCALONABILIDADE";

    private SchedulingAnalysis() {}

    /** Representa uma tarefa periódica usada na análise Rate Monotonic. */
    public static final class RealtimeTask implements Comparable<RealtimeTask> {
        // Identificação, período, tempo de execução e deadline usados nos cálculos teóricos.
        public final int taskId;
        public final String code;
        public final String name;
        public int priority;
        public final int periodMs;
        public final int executionTimeMs;
        public final int deadlineMs;
        public final int jitterMs;
        // Campos preenchidos após a análise de tempo de resposta.
        public double responseTimeMs;
        public boolean schedulable;

        public RealtimeTask(int taskId, String code, String name, int periodMs,
                            int executionTimeMs, int deadlineMs, int jitterMs) {
            this.taskId = taskId;
            this.code = code;
            this.name = name;
            this.periodMs = periodMs;
            this.executionTimeMs = executionTimeMs;
            this.deadlineMs = deadlineMs;
            this.jitterMs = jitterMs;
            this.priority = 0;
            this.responseTimeMs = 0.0;
            this.schedulable = false;
        }

        @Override
        public int compareTo(RealtimeTask other) {
            int byPeriod = Integer.compare(this.periodMs, other.periodMs);
            if (byPeriod != 0) return byPeriod;
            return Integer.compare(this.taskId, other.taskId);
        }
    }

    /** Guarda uma medição experimental de uma tarefa em uma configuração de núcleos. */
    public static final class BenchmarkMeasurement {
        // Descreve a configuração testada e a tarefa medida.
        public final String config;
        public final int mask;
        public final int coresRepresented;
        public final String taskCode;
        public final int deadlineMs;
        // Valores medidos no experimento e comparações com o deadline da tarefa.
        public final double maxResponseMs;
        public final double avgResponseMs;
        public final boolean theoreticalDeadlineMet;
        public final boolean experimentalAverageDeadlineMet;
        public final boolean experimentalPeakDeadlineMet;
        public final boolean affinityApplied;

        public BenchmarkMeasurement(String config, int mask, int coresRepresented, String taskCode,
                                    int deadlineMs, double maxResponseMs, double avgResponseMs,
                                    boolean theoreticalDeadlineMet,
                                    boolean experimentalAverageDeadlineMet,
                                    boolean experimentalPeakDeadlineMet,
                                    boolean affinityApplied) {
            this.config = config;
            this.mask = mask;
            this.coresRepresented = coresRepresented;
            this.taskCode = taskCode;
            this.deadlineMs = deadlineMs;
            this.maxResponseMs = maxResponseMs;
            this.avgResponseMs = avgResponseMs;
            this.theoreticalDeadlineMet = theoreticalDeadlineMet;
            this.experimentalAverageDeadlineMet = experimentalAverageDeadlineMet;
            this.experimentalPeakDeadlineMet = experimentalPeakDeadlineMet;
            this.affinityApplied = affinityApplied;
        }
    }

    /** Agrupa os arquivos gerados pela análise para facilitar o uso pela Activity. */
    public static final class AnalysisResult {
        public final File reportFile;
        public final File svgFile;
        public final File dependencyGraphSvgFile;
        public final String reportText;

        public AnalysisResult(File reportFile, File svgFile, File dependencyGraphSvgFile, String reportText) {
            this.reportFile = reportFile;
            this.svgFile = svgFile;
            this.dependencyGraphSvgFile = dependencyGraphSvgFile;
            this.reportText = reportText;
        }
    }

    /**
     * Tabela temporal coerente com a simulação atual.
     * Os valores de C são estimativas conservadoras em ms para análise acadêmica.
     */
    /** Define o conjunto de tarefas periódicas do projeto com C, T, D e jitter. */
    public static List<RealtimeTask> definirTarefas() {
        List<RealtimeTask> tarefas = new ArrayList<>();

        tarefas.add(new RealtimeTask(1, "T1", "Movimentação dos alvos", 30, 3, 30, 2));
        tarefas.add(new RealtimeTask(2, "T2", "Disparo dos canhões", 1000, 4, 1000, 5));
        tarefas.add(new RealtimeTask(3, "T3", "Verificação de colisões", 16, 2, 16, 1));
        tarefas.add(new RealtimeTask(4, "T4", "Atualização da UI", 100, 2, 100, 2));
        tarefas.add(new RealtimeTask(5, "T5", "Coleta de dados dos sensores", 1000, 3, 1000, 5));
        tarefas.add(new RealtimeTask(6, "T6", "Reconciliação e otimização", 10000, 40, 10000, 10));
        tarefas.add(new RealtimeTask(7, "T7", "Gerenciamento de energia e penalidades", 100, 2, 100, 2));
        tarefas.add(new RealtimeTask(8, "T8", "Renderização do GameView", 16, 3, 16, 1));
        tarefas.add(new RealtimeTask(9, "T9", "Spawn de novos alvos", 800, 2, 800, 10));

        atribuirPrioridadesRateMonotonic(tarefas);
        return tarefas;
    }

    /** Ordena por período e atribui maior prioridade às tarefas mais frequentes. */
    /** Ordena por menor período e atribui maior prioridade às tarefas mais frequentes. */
    public static void atribuirPrioridadesRateMonotonic(List<RealtimeTask> tarefas) {
        Collections.sort(tarefas);
        int prioridade = 1;
        for (RealtimeTask tarefa : tarefas) {
            tarefa.priority = prioridade++;
        }
    }

    /**
     * Equação de tempo de resposta com prioridade fixa e jitter de liberação:
     * Ri = Ci + soma ceil((Ri + Jj) / Pj) * Cj, para tarefas j de maior prioridade.
     */
    /** Calcula iterativamente o pior tempo de resposta considerando interferência de prioridades maiores. */
    public static void calcularTemposRespostaRateMonotonic(List<RealtimeTask> tarefas) {
        Collections.sort(tarefas);

        for (int i = 0; i < tarefas.size(); i++) {
            RealtimeTask atual = tarefas.get(i);
            double ri = atual.executionTimeMs;
            boolean convergiu = false;

            for (int iteracao = 0; iteracao < 1000; iteracao++) {
                double interferencia = 0.0;

                for (int j = 0; j < i; j++) {
                    RealtimeTask hp = tarefas.get(j);
                    interferencia += Math.ceil((ri + hp.jitterMs) / (double) hp.periodMs) * hp.executionTimeMs;
                }

                double novoRi = atual.executionTimeMs + interferencia;

                if (Math.abs(novoRi - ri) < 0.0001) {
                    ri = novoRi;
                    convergiu = true;
                    break;
                }

                ri = novoRi;

                if (ri > atual.deadlineMs * 100.0) {
                    break;
                }
            }

            atual.responseTimeMs = ri;
            atual.schedulable = convergiu && ri <= atual.deadlineMs;
        }
    }

    /** Verifica se todas as tarefas têm tempo de resposta teórico menor ou igual ao deadline. */
    /** Confirma se todas as tarefas atendem ao critério Ri <= Di. */
    public static boolean verificarEscalonabilidade(List<RealtimeTask> tarefas) {
        for (RealtimeTask tarefa : tarefas) {
            if (!tarefa.schedulable) {
                return false;
            }
        }
        return true;
    }

    /** Soma a utilização Ci/Ti de todas as tarefas. */
    /** Soma C/T de todas as tarefas para estimar a carga total de CPU. */
    public static double calcularUtilizacao(List<RealtimeTask> tarefas) {
        double u = 0.0;
        for (RealtimeTask tarefa : tarefas) {
            u += tarefa.executionTimeMs / (double) tarefa.periodMs;
        }
        return u;
    }

    /** Representa um nó visual do grafo SVG de dependências entre tarefas. */
    private static final class DependencyGraphNode {
        final String code;
        final String title;
        final int x;
        final int y;

        DependencyGraphNode(String code, String title, int x, int y) {
            this.code = code;
            this.title = title;
            this.x = x;
            this.y = y;
        }
    }

    /** Representa uma seta direcionada entre duas tarefas no grafo SVG. */
    private static final class DependencyGraphEdge {
        final String from;
        final String to;

        DependencyGraphEdge(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    /** Define os nós do grafo com posições fixas para manter o desenho limpo e previsível. */
    private static List<DependencyGraphNode> definirNosGrafoDependencias() {
        List<DependencyGraphNode> nodes = new ArrayList<>();
        nodes.add(new DependencyGraphNode("T9", "Spawn de alvos", 640, 105));
        nodes.add(new DependencyGraphNode("T1", "Movimentação dos alvos", 430, 245));
        nodes.add(new DependencyGraphNode("T5", "Coleta de sensores", 285, 395));
        nodes.add(new DependencyGraphNode("T6", "Reconciliação e otimização", 430, 545));
        nodes.add(new DependencyGraphNode("T7", "Energia e penalidades", 785, 395));
        nodes.add(new DependencyGraphNode("T2", "Disparo dos canhões", 640, 545));
        nodes.add(new DependencyGraphNode("T3", "Verificação de colisões", 640, 700));
        nodes.add(new DependencyGraphNode("T4", "Atualização da UI", 480, 850));
        nodes.add(new DependencyGraphNode("T8", "Renderização", 800, 850));
        return nodes;
    }

    /** Define as dependências reais entre tarefas que serão desenhadas como setas no SVG. */
    private static List<DependencyGraphEdge> definirArestasGrafoDependencias() {
        List<DependencyGraphEdge> edges = new ArrayList<>();
        edges.add(new DependencyGraphEdge("T9", "T1"));
        edges.add(new DependencyGraphEdge("T1", "T5"));
        edges.add(new DependencyGraphEdge("T5", "T6"));
        edges.add(new DependencyGraphEdge("T6", "T2"));
        edges.add(new DependencyGraphEdge("T2", "T3"));
        edges.add(new DependencyGraphEdge("T3", "T4"));
        edges.add(new DependencyGraphEdge("T7", "T2"));
        edges.add(new DependencyGraphEdge("T1", "T8"));
        edges.add(new DependencyGraphEdge("T2", "T8"));
        edges.add(new DependencyGraphEdge("T3", "T8"));
        return edges;
    }

    /** Gera o grafo de dependências entre tarefas como SVG independente, com círculos e setas. */
    private static void escreverGrafoDependenciasSvg(File arquivo) {
        final int width = 1180;
        final int height = 980;
        final int radius = 62;
        List<DependencyGraphNode> nodes = definirNosGrafoDependencias();
        List<DependencyGraphEdge> edges = definirArestasGrafoDependencias();
        Map<String, DependencyGraphNode> byCode = new HashMap<>();
        for (DependencyGraphNode node : nodes) {
            byCode.put(node.code, node);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
                .append(width).append(" ").append(height).append("\">\n");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#FFFFFF\"/>\n");
        sb.append("<defs>\n");
        sb.append("  <marker id=\"arrow\" markerWidth=\"12\" markerHeight=\"12\" refX=\"10\" refY=\"6\" orient=\"auto\" markerUnits=\"strokeWidth\">\n");
        sb.append("    <path d=\"M2,2 L10,6 L2,10 Z\" fill=\"#2B2B2B\"/>\n");
        sb.append("  </marker>\n");
        sb.append("</defs>\n");
        sb.append("<text x=\"40\" y=\"42\" font-size=\"24\" font-family=\"sans-serif\" font-weight=\"bold\" fill=\"#1B1B1B\">Grafo de dependências entre tarefas</text>\n");
        sb.append("<text x=\"40\" y=\"68\" font-size=\"13\" font-family=\"sans-serif\" fill=\"#555555\">As setas indicam a ordem de dependência lógica entre as tarefas T1 a T9.</text>\n");

        for (DependencyGraphEdge edge : edges) {
            DependencyGraphNode from = byCode.get(edge.from);
            DependencyGraphNode to = byCode.get(edge.to);
            if (from != null && to != null) {
                appendArrow(sb, from.x, from.y, to.x, to.y, radius);
            }
        }

        for (DependencyGraphNode node : nodes) {
            appendDependencyNode(sb, node, radius);
        }

        sb.append("</svg>\n");
        escreverTexto(arquivo, sb.toString());
    }

    /** Desenha uma seta de centro a centro, ajustando início e fim para encostar na borda dos círculos. */
    private static void appendArrow(StringBuilder sb, int x1, int y1, int x2, int y2, int radius) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance == 0.0) return;

        double ux = dx / distance;
        double uy = dy / distance;
        int startX = (int) Math.round(x1 + ux * radius);
        int startY = (int) Math.round(y1 + uy * radius);
        int endX = (int) Math.round(x2 - ux * (radius + 8));
        int endY = (int) Math.round(y2 - uy * (radius + 8));

        sb.append("<line x1=\"").append(startX).append("\" y1=\"").append(startY)
                .append("\" x2=\"").append(endX).append("\" y2=\"").append(endY)
                .append("\" stroke=\"#2B2B2B\" stroke-width=\"3\" stroke-linecap=\"round\" marker-end=\"url(#arrow)\"/>\n");
    }

    /** Desenha um nó circular com código da tarefa e descrição curta em duas linhas. */
    private static void appendDependencyNode(StringBuilder sb, DependencyGraphNode node, int radius) {
        sb.append("<circle cx=\"").append(node.x).append("\" cy=\"").append(node.y)
                .append("\" r=\"").append(radius)
                .append("\" fill=\"#F3F3F3\" stroke=\"#2B2B2B\" stroke-width=\"3\"/>\n");
        sb.append("<text x=\"").append(node.x).append("\" y=\"").append(node.y - 10)
                .append("\" font-size=\"18\" font-family=\"sans-serif\" font-weight=\"bold\" text-anchor=\"middle\" fill=\"#222222\">")
                .append(escXml(node.code)).append("</text>\n");
        sb.append("<text x=\"").append(node.x).append("\" y=\"").append(node.y + 14)
                .append("\" font-size=\"12\" font-family=\"sans-serif\" text-anchor=\"middle\" fill=\"#333333\">");
        appendWrappedTitle(sb, node.title, node.x, node.y + 14);
        sb.append("</text>\n");
    }

    /** Quebra descrições longas em linhas curtas dentro do círculo. */
    private static void appendWrappedTitle(StringBuilder sb, String title, int x, int firstY) {
        String[] words = title.split(" ");
        StringBuilder line = new StringBuilder();
        int writtenLines = 0;
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (candidate.length() > 17 && line.length() > 0) {
                appendTspan(sb, line.toString(), x, writtenLines == 0 ? firstY : firstY + writtenLines * 15);
                writtenLines++;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) {
            appendTspan(sb, line.toString(), x, writtenLines == 0 ? firstY : firstY + writtenLines * 15);
        }
    }

    /** Adiciona uma linha de texto centralizada dentro do nó SVG. */
    private static void appendTspan(StringBuilder sb, String text, int x, int y) {
        sb.append("<tspan x=\"").append(x).append("\" y=\"").append(y).append("\">")
                .append(escXml(text)).append("</tspan>");
    }

    /** Escapa caracteres especiais para manter o SVG XML válido. */
    private static String escXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** Monta o relatório textual com análise teórica, medições experimentais e conclusões. */
    public static String gerarRelatorio(List<RealtimeTask> tarefas,
                                        List<BenchmarkMeasurement> medicoes,
                                        File svgFile,
                                        File dependencyGraphSvgFile) {
        StringBuilder sb = new StringBuilder();
        double utilizacao = calcularUtilizacao(tarefas);
        boolean escalonavel = verificarEscalonabilidade(tarefas);

        sb.append(linha('=', 112)).append("\n");
        sb.append("RELATÓRIO DE ESCALONABILIDADE - AUTOTARGET AV2\n");
        sb.append(linha('=', 112)).append("\n\n");

        sb.append("RESUMO EXECUTIVO\n");
        sb.append(linha('-', 112)).append("\n");
        sb.append(String.format(Locale.US, "Utilização total estimada em 1 processador : %.2f%%\n", utilizacao * 100.0));
        sb.append("Escalonável por Rate Monotonic             : ")
                .append(escalonavel ? "SIM" : "NÃO")
                .append("\n");
        sb.append("Quantidade de tarefas analisadas           : ").append(tarefas.size()).append("\n");
        sb.append("Amostras por tarefa no benchmark           : ").append(AMOSTRAS_POR_TAREFA).append("\n");
        sb.append("Configurações avaliadas                    : 1 núcleo, 2 núcleos e todos os núcleos disponíveis\n\n");

        sb.append("TABELA 1 - TAREFAS DE TEMPO REAL E ANÁLISE TEÓRICA\n");
        sb.append(linha('-', 112)).append("\n");
        sb.append(String.format(Locale.US,
                "%-5s | %-36s | %4s | %7s | %7s | %7s | %7s | %9s | %-12s\n",
                "Cod", "Descrição", "Prio", "Pi(ms)", "Ci(ms)", "Di(ms)", "Ji(ms)", "Ri(ms)", "Status"));
        sb.append(linha('-', 112)).append("\n");
        for (RealtimeTask t : tarefas) {
            sb.append(String.format(Locale.US,
                    "%-5s | %-36s | %4d | %7d | %7d | %7d | %7d | %9.2f | %-12s\n",
                    t.code,
                    limitar(t.name, 36),
                    t.priority,
                    t.periodMs,
                    t.executionTimeMs,
                    t.deadlineMs,
                    t.jitterMs,
                    t.responseTimeMs,
                    t.schedulable ? "OK" : "ESTOURO"));
        }
        sb.append(linha('-', 112)).append("\n\n");

        sb.append("LEGENDA DA TABELA 1\n");
        sb.append(linha('-', 112)).append("\n");
        sb.append("Pi = período; Ci = tempo de execução estimado; Di = deadline; Ji = jitter; Ri = tempo de resposta calculado.\n");
        sb.append("A prioridade RM menor indica maior prioridade, pois o Rate Monotonic prioriza tarefas com menor período.\n\n");

        sb.append("GRAFO DE DEPENDÊNCIAS ENTRE TAREFAS\n");
        sb.append(linha('-', 112)).append("\n");
        sb.append("O grafo de dependências T1-T9 foi gerado como SVG independente, com nós circulares e setas direcionais.\n");
        sb.append("Arquivo do grafo: ").append(dependencyGraphSvgFile.getAbsolutePath()).append("\n\n");
        sb.append("TABELA 2 - MEDIÇÕES PRÁTICAS POR CONFIGURAÇÃO DE PROCESSADORES\n");
        sb.append(linha('-', 136)).append("\n");
        sb.append(String.format(Locale.US,
                "%-16s | %-6s | %6s | %11s | %11s | %-9s | %-12s | %-10s | %-13s\n",
                "Configuração", "Tarefa", "Di(ms)", "Máx(ms)", "Média(ms)", "RM", "Exp. médio", "Pico", "Afinidade CPU"));
        sb.append(linha('-', 136)).append("\n");
        for (BenchmarkMeasurement m : medicoes) {
            sb.append(String.format(Locale.US,
                    "%-16s | %-6s | %6d | %11.3f | %11.3f | %-9s | %-12s | %-10s | %-13s\n",
                    m.config,
                    m.taskCode,
                    m.deadlineMs,
                    m.maxResponseMs,
                    m.avgResponseMs,
                    situacaoPrazoTeorico(m),
                    situacaoExperimentalMedio(m),
                    situacaoPicoExperimental(m),
                    m.affinityApplied ? "APLICADA" : "INDISPONÍVEL"));
        }
        sb.append(linha('-', 136)).append("\n");
        sb.append("Observação: a coluna 'RM' vem da análise teórica Rate Monotonic da Tabela 1. ");
        sb.append("A coluna 'Exp. médio' compara a média experimental medida com Di, enquanto 'Pico' indica se a maior amostra passou de Di. ");
        sb.append("Assim, o relatório continua comparando dados experimentais, mas separa média, pico e análise teórica para evitar falso diagnóstico por uma amostra isolada.\n\n");

        sb.append("RESUMO POR CONFIGURAÇÃO\n");
        sb.append(linha('-', 112)).append("\n");
        sb.append(construirResumoConfiguracoes(medicoes));
        sb.append("\n");

        sb.append("ARQUIVOS GERADOS\n");
        sb.append(linha('-', 112)).append("\n");
        sb.append("Relatório TXT : ").append("relatorio_escalonabilidade.txt").append("\n");
        sb.append("Gráfico SVG   : ").append(svgFile.getAbsolutePath()).append("\n");
        sb.append("Grafo SVG     : ").append(dependencyGraphSvgFile.getAbsolutePath()).append("\n\n");

        sb.append("CONCLUSÃO\n");
        sb.append(linha('-', 112)).append("\n");
        if (escalonavel) {
            sb.append("A análise teórica indica que o conjunto de tarefas é escalonável em um processador, ");
            sb.append("pois todos os tempos de resposta Ri ficaram dentro dos respectivos deadlines Di. ");
        } else {
            sb.append("A análise teórica indica que o conjunto de tarefas não é escalonável em um processador, ");
            sb.append("pois pelo menos uma tarefa excedeu seu deadline Di. ");
        }
        sb.append("As medições práticas complementam a análise ao comparar custos experimentais com 1 núcleo, 2 núcleos ");
        sb.append("e todos os núcleos disponíveis. A Tabela 2 compara a média experimental com o deadline Di e também informa ");
        sb.append("se houve pico acima de Di, separando resultado experimental médio, pico experimental e análise RM. ");
        sb.append("Quando a afinidade de CPU não puder ser aplicada pelo dispositivo Android, essa limitação fica registrada ");
        sb.append("na coluna de afinidade do relatório, sem esconder o resultado experimental.\n");

        return sb.toString();
    }

    /** Cria linhas divisórias usadas para formatar tabelas em texto puro. */
    private static String linha(char caractere, int tamanho) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tamanho; i++) {
            sb.append(caractere);
        }
        return sb.toString();
    }

    /** Limita textos longos para preservar o alinhamento das tabelas do relatório. */
    private static String limitar(String texto, int tamanhoMaximo) {
        if (texto == null) return "";
        if (texto.length() <= tamanhoMaximo) return texto;
        if (tamanhoMaximo <= 3) return texto.substring(0, tamanhoMaximo);
        return texto.substring(0, tamanhoMaximo - 3) + "...";
    }

    /** Traduz o resultado teórico RM para um texto curto da Tabela 2. */
    private static String situacaoPrazoTeorico(BenchmarkMeasurement m) {
        if (m.maxResponseMs < 0 || m.avgResponseMs < 0) return "ERRO";
        return m.theoreticalDeadlineMet ? "OK" : "ESTOURO";
    }

    /** Indica se a média experimental ficou dentro do deadline da tarefa. */
    private static String situacaoExperimentalMedio(BenchmarkMeasurement m) {
        if (m.maxResponseMs < 0 || m.avgResponseMs < 0) return "ERRO";
        return m.experimentalAverageDeadlineMet ? "OK" : "ACIMA Di";
    }

    /** Destaca picos experimentais isolados que ultrapassaram o deadline. */
    private static String situacaoPicoExperimental(BenchmarkMeasurement m) {
        if (m.maxResponseMs < 0 || m.avgResponseMs < 0) return "ERRO";
        return m.experimentalPeakDeadlineMet ? "OK" : "PICO > Di";
    }

    /** Consolida, por configuração, quantas medições médias e de pico passaram no experimento. */
    private static String construirResumoConfiguracoes(List<BenchmarkMeasurement> medicoes) {
        Map<String, int[]> contadores = new LinkedHashMap<>();
        Map<String, Double> maioresTempos = new LinkedHashMap<>();

        for (BenchmarkMeasurement m : medicoes) {
            if (!contadores.containsKey(m.config)) {
                contadores.put(m.config, new int[]{0, 0, 0, 0, 0});
                maioresTempos.put(m.config, 0.0);
            }

            int[] valores = contadores.get(m.config);
            valores[0]++;
            if (m.theoreticalDeadlineMet) valores[1]++;
            if (m.experimentalAverageDeadlineMet) valores[2]++;
            if (m.experimentalPeakDeadlineMet) valores[3]++;
            if (m.affinityApplied) valores[4]++;
            maioresTempos.put(m.config, Math.max(maioresTempos.get(m.config), m.maxResponseMs));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "%-18s | %8s | %12s | %13s | %9s | %13s | %14s\n",
                "Configuração", "Tarefas", "RM OK", "Exp. méd OK", "Picos OK", "Afinidade OK", "Maior máx(ms)"));
        sb.append(linha('-', 106)).append("\n");

        for (Map.Entry<String, int[]> entry : contadores.entrySet()) {
            int[] valores = entry.getValue();
            sb.append(String.format(Locale.US,
                    "%-18s | %8d | %12d | %13d | %9d | %13d | %14.3f\n",
                    entry.getKey(),
                    valores[0],
                    valores[1],
                    valores[2],
                    valores[3],
                    valores[4],
                    maioresTempos.get(entry.getKey())));
        }

        return sb.toString();
    }

    /** Executa todo o fluxo: tarefas, análise RM, benchmark, relatório e SVG. */
    /** Executa a análise inteira, salva relatório TXT e gera o gráfico SVG final. */
    public static AnalysisResult executarAnaliseCompleta(Context context) {
        Log.e(SCHED_TAG, "[1/5] Iniciando definição das tarefas de tempo real.");
        List<RealtimeTask> tarefas = definirTarefas();

        Log.e(SCHED_TAG, "[2/5] Calculando tempos de resposta por Rate Monotonic.");
        calcularTemposRespostaRateMonotonic(tarefas);

        File baseDir = context.getExternalFilesDir(null);
        File dir = new File(baseDir, "scheduling");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(SCHED_TAG, "Não foi possível criar diretório: " + dir.getAbsolutePath());
        }

        Log.e(SCHED_TAG, "[3/5] Iniciando benchmark curto com máscaras de 1, 2 e todos os núcleos.");
        List<BenchmarkMeasurement> medicoes = executarBenchmarkAfinidade(tarefas);

        File svg = new File(dir, "grafico_escalonabilidade.svg");
        File dependencyGraphSvg = new File(dir, "grafo_dependencias_tarefas.svg");
        File report = new File(dir, "relatorio_escalonabilidade.txt");

        Log.e(SCHED_TAG, "[4/5] Gerando gráfico SVG comparativo e grafo SVG de dependências.");
        escreverSvg(svg, tarefas, medicoes);
        escreverGrafoDependenciasSvg(dependencyGraphSvg);

        Log.e(SCHED_TAG, "[5/5] Gerando relatório textual final.");
        String texto = gerarRelatorio(tarefas, medicoes, svg, dependencyGraphSvg);
        escreverTexto(report, texto);

        Log.e(SCHED_TAG, "ANÁLISE FINALIZADA DENTRO DE SchedulingAnalysis.");
        Log.e(SCHED_TAG, "Relatório: " + report.getAbsolutePath());
        Log.e(SCHED_TAG, "SVG: " + svg.getAbsolutePath());
        Log.e(SCHED_TAG, "Grafo SVG: " + dependencyGraphSvg.getAbsolutePath());

        return new AnalysisResult(report, svg, dependencyGraphSvg, texto);
    }

    /** Mede o comportamento experimental das tarefas em configurações de afinidade de CPU. */
    /** Mede experimentalmente cada tarefa em diferentes quantidades de núcleos disponíveis. */
    public static List<BenchmarkMeasurement> executarBenchmarkAfinidade(List<RealtimeTask> tarefas) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        Map<String, Integer> configs = new LinkedHashMap<>();
        configs.put("1 núcleo", criarMascara(1, cores));
        configs.put("2 núcleos", criarMascara(Math.min(2, cores), cores));
        configs.put("todos os núcleos", criarMascara(cores, cores));

        List<BenchmarkMeasurement> todas = new ArrayList<>();
        for (Map.Entry<String, Integer> config : configs.entrySet()) {
            todas.addAll(medirConfiguracao(config.getKey(), config.getValue(), cores, tarefas));
        }
        return todas;
    }

    private static List<BenchmarkMeasurement> medirConfiguracao(String nomeConfig, int mask, int cores,
                                                                List<RealtimeTask> tarefas) {
        Log.e(SCHED_TAG, "Benchmark iniciado para configuração: " + nomeConfig + " mask=" + mask);

        List<BenchmarkMeasurement> resultados = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(tarefas.size());

        for (RealtimeTask tarefa : tarefas) {
            Thread worker = new Thread(() -> {
                try {
                    boolean afinidadeAplicada = Process.setThreadAffinityMask(mask);
                    double soma = 0.0;
                    double max = 0.0;

                    for (int i = 0; i < AMOSTRAS_POR_TAREFA; i++) {
                        long release = System.nanoTime();
                        simularExecucaoMs(tarefa.executionTimeMs);
                        long finish = System.nanoTime();

                        double respostaMs = (finish - release) / 1_000_000.0;
                        soma += respostaMs;
                        max = Math.max(max, respostaMs);

                        // Pausa curta apenas para evitar monopolizar o emulador.
                        // Não espera Pi completo, pois isso faria tarefas de 10000ms travarem o diagnóstico por minutos.
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    double media = soma / AMOSTRAS_POR_TAREFA;
                    resultados.add(new BenchmarkMeasurement(
                            nomeConfig,
                            mask,
                            cores,
                            tarefa.code,
                            tarefa.deadlineMs,
                            max,
                            media,
                            tarefa.schedulable,
                            media <= tarefa.deadlineMs,
                            max <= tarefa.deadlineMs,
                            afinidadeAplicada
                    ));
                } catch (Throwable e) {
                    Log.e(SCHED_TAG, "Erro no benchmark da tarefa " + tarefa.code + " em " + nomeConfig, e);
                    resultados.add(new BenchmarkMeasurement(
                            nomeConfig,
                            mask,
                            cores,
                            tarefa.code,
                            tarefa.deadlineMs,
                            -1.0,
                            -1.0,
                            false,
                            false,
                            false,
                            false
                    ));
                } finally {
                    latch.countDown();
                }
            }, "sched-bench-" + nomeConfig.replace(" ", "-") + "-" + tarefa.code);

            worker.start();
        }

        try {
            boolean terminou = latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!terminou) {
                Log.e(SCHED_TAG, "Timeout no benchmark da configuração " + nomeConfig + ". O relatório continuará com os dados já coletados.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(SCHED_TAG, "Benchmark interrompido em " + nomeConfig, e);
        }

        Collections.sort(resultados, Comparator
                .comparing((BenchmarkMeasurement m) -> m.config)
                .thenComparing(m -> m.taskCode));

        Log.e(SCHED_TAG, "Benchmark finalizado para configuração: " + nomeConfig + ". Medições coletadas=" + resultados.size());
        return resultados;
    }

    /** Simula carga de CPU pelo tempo aproximado de execução da tarefa. */
    /** Simula carga de CPU durante a quantidade de milissegundos definida para a tarefa. */
    private static void simularExecucaoMs(int ms) {
        long fim = System.nanoTime() + ms * 1_000_000L;
        double sink = 0.0;
        while (System.nanoTime() < fim) {
            sink += Math.sqrt(System.nanoTime() % 1000);
        }
        if (sink == -1.0) Log.d(TAG, "valor impossível");
    }

    /** Constrói a máscara binária de afinidade para representar os núcleos selecionados. */
    private static int criarMascara(int qtdCores, int coresDisponiveis) {
        int coresUsados = Math.max(1, Math.min(qtdCores, coresDisponiveis));
        if (coresUsados >= 31) return -1;
        return (1 << coresUsados) - 1;
    }

    /**
     * Gera o gráfico SVG comparando tempo de resposta máximo experimental
     * com o deadline teórico de cada tarefa, por configuração de núcleos.
     * Cada tarefa recebe um grupo de 3 barras (1, 2 e todos os núcleos).
     * A linha vermelha horizontal indica o deadline Di de cada tarefa.
     */
    private static void escreverSvg(File arquivo, List<RealtimeTask> tarefas,
                                    List<BenchmarkMeasurement> medicoes) {
        final int WIDTH     = 1280;
        final int HEIGHT    = 800;
        final int PAD_LEFT  = 100;
        final int PAD_RIGHT = 40;
        final int PAD_TOP   = 90;
        final int CHART_H   = HEIGHT - PAD_TOP - 150;
        final int CHART_BOT = PAD_TOP + CHART_H;
        final int CHART_W   = WIDTH - PAD_LEFT - PAD_RIGHT;

        // Agrupa medições por tarefa para desenhar grupos de barras
        Map<String, List<BenchmarkMeasurement>> porTarefa = new LinkedHashMap<>();
        Map<String, Integer> deadlines = new HashMap<>();
        for (RealtimeTask t : tarefas) {
            porTarefa.put(t.code, new ArrayList<>());
            deadlines.put(t.code, t.deadlineMs);
        }
        for (BenchmarkMeasurement m : medicoes) {
            List<BenchmarkMeasurement> lista = porTarefa.get(m.taskCode);
            if (lista != null) lista.add(m);
        }

        // Escala Y: máximo entre pico experimental e deadline, +15% margem
        double maxValor = 1.0;
        for (BenchmarkMeasurement m : medicoes)
            if (m.maxResponseMs > 0) maxValor = Math.max(maxValor, m.maxResponseMs);
        for (RealtimeTask t : tarefas)
            maxValor = Math.max(maxValor, t.deadlineMs);
        final double maxEscala = Math.ceil(maxValor * 1.15 / 5.0) * 5.0;

        final int N_TAREFAS   = tarefas.size();
        final int BAR_W       = 18;
        final int BAR_GAP     = 5;
        final int GROUP_W     = 3 * BAR_W + 2 * BAR_GAP;
        final int GROUP_GAP   = 24;
        final int TOTAL_W     = N_TAREFAS * (GROUP_W + GROUP_GAP);
        final int GROUPS_START = PAD_LEFT + Math.max(0, (CHART_W - TOTAL_W) / 2) + 10;

        Map<String, Integer> groupIndex = new LinkedHashMap<>();
        int idx = 0;
        for (RealtimeTask t : tarefas) groupIndex.put(t.code, idx++);

        final String[] CONFIG_CORES = {"#1565C0", "#2E7D32", "#6A1B9A"};
        final String[] CONFIG_NOMES = {"1 n\u00FAcleo", "2 n\u00FAcleos", "todos os n\u00FAcleos"};

        StringBuilder sb = new StringBuilder();

        // Abertura do SVG
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\"");
        sb.append(" width=\"").append(WIDTH).append("\"");
        sb.append(" height=\"").append(HEIGHT).append("\"");
        sb.append(" viewBox=\"0 0 ").append(WIDTH).append(" ").append(HEIGHT).append("\">\n");

        // Fundo com gradiente
        sb.append("<defs>\n");
        sb.append("  <linearGradient id=\"bgGrad\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">\n");
        sb.append("    <stop offset=\"0%\" stop-color=\"#F8F9FF\"/>\n");
        sb.append("    <stop offset=\"100%\" stop-color=\"#FAFAFA\"/>\n");
        sb.append("  </linearGradient>\n");
        sb.append("</defs>\n");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"url(#bgGrad)\"/>\n");

        // Painel branco interno
        sb.append("<rect");
        sb.append(" x=\"").append(PAD_LEFT - 10).append("\"");
        sb.append(" y=\"").append(PAD_TOP - 10).append("\"");
        sb.append(" width=\"").append(CHART_W + 20).append("\"");
        sb.append(" height=\"").append(CHART_H + 20).append("\"");
        sb.append(" fill=\"white\" stroke=\"#E0E0E0\" stroke-width=\"1\" rx=\"6\"/>\n");

        // Títulos
        sb.append("<text x=\"").append(WIDTH / 2).append("\" y=\"32\"");
        sb.append(" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"17\"");
        sb.append(" font-weight=\"bold\" fill=\"#1A237E\">");
        sb.append("Tempos de Resposta por Configura\u00E7\u00E3o de N\u00FAcleos</text>\n");

        sb.append("<text x=\"").append(WIDTH / 2).append("\" y=\"54\"");
        sb.append(" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#546E7A\">");
        sb.append("Barras: tempo de resposta m\u00E1ximo experimental (ms)");
        sb.append(" | Linha pontilhada: deadline Di da tarefa</text>\n");

        sb.append("<text x=\"").append(WIDTH / 2).append("\" y=\"71\"");
        sb.append(" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#78909C\">");
        sb.append("Contorno vermelho indica pico experimental acima do deadline Di</text>\n");

        // Grade horizontal (8 linhas)
        final int GRID_N = 8;
        for (int gi = 0; gi <= GRID_N; gi++) {
            double val = maxEscala / GRID_N * gi;
            int gy = CHART_BOT - (int) Math.round(val / maxEscala * CHART_H);
            String dash = (gi == 0) ? "none" : "4,3";
            String corG = (gi == 0) ? "#BDBDBD" : "#EEEEEE";
            sb.append("<line x1=\"").append(PAD_LEFT).append("\" y1=\"").append(gy).append("\"");
            sb.append(" x2=\"").append(WIDTH - PAD_RIGHT).append("\" y2=\"").append(gy).append("\"");
            sb.append(" stroke=\"").append(corG).append("\" stroke-dasharray=\"").append(dash).append("\"/>\n");
            sb.append("<text x=\"").append(PAD_LEFT - 8).append("\" y=\"").append(gy + 4).append("\"");
            sb.append(" font-size=\"11\" font-family=\"sans-serif\" fill=\"#607D8B\" text-anchor=\"end\">");
            sb.append(String.format(Locale.US, "%.0f ms", val)).append("</text>\n");
        }

        // Eixos
        sb.append("<line x1=\"").append(PAD_LEFT).append("\" y1=\"").append(CHART_BOT).append("\"");
        sb.append(" x2=\"").append(WIDTH - PAD_RIGHT).append("\" y2=\"").append(CHART_BOT).append("\"");
        sb.append(" stroke=\"#37474F\" stroke-width=\"2\"/>\n");
        sb.append("<line x1=\"").append(PAD_LEFT).append("\" y1=\"").append(PAD_TOP - 10).append("\"");
        sb.append(" x2=\"").append(PAD_LEFT).append("\" y2=\"").append(CHART_BOT).append("\"");
        sb.append(" stroke=\"#37474F\" stroke-width=\"2\"/>\n");

        // Label eixo Y (rotacionado)
        int midY = PAD_TOP + CHART_H / 2;
        sb.append("<text x=\"22\" y=\"").append(midY).append("\"");
        sb.append(" font-size=\"13\" font-family=\"sans-serif\" fill=\"#37474F\"");
        sb.append(" transform=\"rotate(-90 22,").append(midY).append(")\">");
        sb.append("Tempo de Resposta M\u00E1ximo (ms)</text>\n");

        // Grupos de barras por tarefa
        for (RealtimeTask tarefa : tarefas) {
            int gi  = groupIndex.get(tarefa.code);
            int gx0 = GROUPS_START + gi * (GROUP_W + GROUP_GAP);

            List<BenchmarkMeasurement> lista = porTarefa.get(tarefa.code);
            Collections.sort(lista, Comparator.comparingInt(m -> m.coresRepresented));

            // Linha de deadline desta tarefa
            int dMs = deadlines.getOrDefault(tarefa.code, 0);
            if (dMs > 0 && (double) dMs <= maxEscala) {
                int dy = CHART_BOT - (int) Math.round((double) dMs / maxEscala * CHART_H);
                sb.append("<line x1=\"").append(gx0 - 4).append("\" y1=\"").append(dy).append("\"");
                sb.append(" x2=\"").append(gx0 + GROUP_W + 4).append("\" y2=\"").append(dy).append("\"");
                sb.append(" stroke=\"#E53935\" stroke-width=\"1.5\" stroke-dasharray=\"5,3\" opacity=\"0.8\"/>\n");
            }

            // 3 barras: 1 núcleo, 2 núcleos, todos
            for (int bi = 0; bi < Math.min(3, lista.size()); bi++) {
                BenchmarkMeasurement m = lista.get(bi);
                int bx = gx0 + bi * (BAR_W + BAR_GAP);
                double valorBarra = Math.max(0.0, m.maxResponseMs);
                int bh = (int) Math.round(valorBarra / maxEscala * CHART_H);
                if (valorBarra > 0 && bh < 3) bh = 3;
                int by = CHART_BOT - bh;
                String fillCor = m.maxResponseMs < 0 ? "#9E9E9E" : CONFIG_CORES[bi % 3];
                boolean peakOver = m.maxResponseMs >= 0 && !m.experimentalPeakDeadlineMet;

                sb.append("<rect x=\"").append(bx).append("\" y=\"").append(by).append("\"");
                sb.append(" width=\"").append(BAR_W).append("\" height=\"").append(bh).append("\"");
                sb.append(" fill=\"").append(fillCor).append("\" rx=\"3\"/>\n");

                if (peakOver) {
                    sb.append("<rect x=\"").append(bx).append("\" y=\"").append(by).append("\"");
                    sb.append(" width=\"").append(BAR_W).append("\" height=\"").append(bh).append("\"");
                    sb.append(" fill=\"none\" stroke=\"#C62828\" stroke-width=\"2\" rx=\"3\"/>\n");
                }

                if (m.maxResponseMs >= 0) {
                    int tyVal = Math.max(PAD_TOP + 12, by - 3);
                    sb.append("<text x=\"").append(bx + BAR_W / 2).append("\" y=\"").append(tyVal).append("\"");
                    sb.append(" font-size=\"9\" font-family=\"sans-serif\" fill=\"#222\"");
                    sb.append(" text-anchor=\"middle\">");
                    sb.append(String.format(Locale.US, "%.1f", m.maxResponseMs)).append("</text>\n");
                }
            }

            // Labels eixo X: código e nome curto
            int labelX = gx0 + GROUP_W / 2;
            sb.append("<text x=\"").append(labelX).append("\" y=\"").append(CHART_BOT + 18).append("\"");
            sb.append(" font-size=\"12\" font-weight=\"bold\" font-family=\"sans-serif\"");
            sb.append(" text-anchor=\"middle\" fill=\"#37474F\">");
            sb.append(escXml(tarefa.code)).append("</text>\n");

            String nome = tarefa.name.length() > 16 ? tarefa.name.substring(0, 14) + "\u2026" : tarefa.name;
            sb.append("<text x=\"").append(labelX).append("\" y=\"").append(CHART_BOT + 30).append("\"");
            sb.append(" font-size=\"9\" font-family=\"sans-serif\" fill=\"#607D8B\" text-anchor=\"middle\">");
            sb.append(escXml(nome)).append("</text>\n");
        }

        // Legenda
        int legY = CHART_BOT + 55;

        for (int li = 0; li < CONFIG_NOMES.length; li++) {
            int lx = PAD_LEFT + li * 220;
            sb.append("<rect x=\"").append(lx).append("\" y=\"").append(legY).append("\"");
            sb.append(" width=\"14\" height=\"14\" fill=\"").append(CONFIG_CORES[li]).append("\" rx=\"2\"/>\n");
            sb.append("<text x=\"").append(lx + 20).append("\" y=\"").append(legY + 11).append("\"");
            sb.append(" font-size=\"12\" font-family=\"sans-serif\" fill=\"#37474F\">");
            sb.append(CONFIG_NOMES[li]).append("</text>\n");
        }

        int legX2 = PAD_LEFT + 680;
        sb.append("<line x1=\"").append(legX2).append("\" y1=\"").append(legY + 7).append("\"");
        sb.append(" x2=\"").append(legX2 + 26).append("\" y2=\"").append(legY + 7).append("\"");
        sb.append(" stroke=\"#E53935\" stroke-width=\"1.5\" stroke-dasharray=\"5,3\" opacity=\"0.8\"/>\n");
        sb.append("<text x=\"").append(legX2 + 32).append("\" y=\"").append(legY + 11).append("\"");
        sb.append(" font-size=\"12\" font-family=\"sans-serif\" fill=\"#37474F\">");
        sb.append("Deadline Di da tarefa</text>\n");

        int legX3 = PAD_LEFT + 900;
        sb.append("<rect x=\"").append(legX3).append("\" y=\"").append(legY).append("\"");
        sb.append(" width=\"14\" height=\"14\" fill=\"none\" stroke=\"#C62828\" stroke-width=\"2\" rx=\"2\"/>\n");
        sb.append("<text x=\"").append(legX3 + 20).append("\" y=\"").append(legY + 11).append("\"");
        sb.append(" font-size=\"12\" font-family=\"sans-serif\" fill=\"#37474F\">");
        sb.append("Pico acima do Di</text>\n");

        // Nota de rodapé
        sb.append("<text x=\"").append(WIDTH / 2).append("\" y=\"").append(CHART_BOT + 90).append("\"");
        sb.append(" font-size=\"10\" font-family=\"sans-serif\" fill=\"#9E9E9E\" text-anchor=\"middle\">");
        sb.append("Barras cinzas indicam erro ou medi\u00E7\u00E3o indispon\u00EDvel.");
        sb.append(" Cores identificam a configura\u00E7\u00E3o de n\u00FAcleos conforme legenda.</text>\n");

        sb.append("</svg>\n");
        escreverTexto(arquivo, sb.toString());
    }

    /** Escolhe uma cor fixa para cada configuração no gráfico SVG. */
    /** Escolhe uma cor fixa para cada configuração no gráfico SVG. */
    private static String corConfiguracao(String config) {
        if ("1 núcleo".equals(config)) return "#1565C0";
        if ("2 núcleos".equals(config)) return "#2E7D32";
        return "#6A1B9A";
    }

    /** Persiste o conteúdo textual gerado em arquivo local da aplicação. */
    private static void escreverTexto(File arquivo, String texto) {
        try (FileWriter fw = new FileWriter(arquivo, false)) {
            fw.write(texto);
        } catch (IOException e) {
            Log.e(TAG, "Erro ao escrever arquivo: " + arquivo.getAbsolutePath(), e);
        }
    }
}