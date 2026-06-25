package com.autotarget.game.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Centraliza evidências quantitativas da AV2.
 *
 * A classe mantém logs resumidos em memória e também consegue gerar um relatório TXT
 * dentro da pasta externa privada do app. O objetivo é documentar, de forma clara,
 * os pontos cobrados pela rubrica: sensores, reconciliação antes/depois, impacto da
 * energia/penalidade e evidências dos arquivos de escalonabilidade.
 */
public final class EvidenceLogger {
    private static final String TAG = "AV2_EVIDENCIAS";
    private static final int LIMITE_EVENTOS = 400;
    private static final int LIMITE_AMOSTRAS_ENERGIA = 600;

    private static final List<String> eventos = new ArrayList<>();
    private static final List<AmostraEnergia> amostrasEnergia = new ArrayList<>();
    private static final Object LOCK = new Object();
    private static int contadorSegundosEnergia = 0;
    private static final SimpleDateFormat FORMATO_DATA =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static final class AmostraEnergia {
        final int segundo;
        final float energiaA;
        final float energiaB;
        final int canhoesA;
        final int canhoesB;
        final int penalidadeA;
        final int penalidadeB;

        AmostraEnergia(int segundo,
                       float energiaA,
                       float energiaB,
                       int canhoesA,
                       int canhoesB,
                       int penalidadeA,
                       int penalidadeB) {
            this.segundo = segundo;
            this.energiaA = energiaA;
            this.energiaB = energiaB;
            this.canhoesA = canhoesA;
            this.canhoesB = canhoesB;
            this.penalidadeA = penalidadeA;
            this.penalidadeB = penalidadeB;
        }
    }

    private EvidenceLogger() {}

    /** Limpa as evidências antigas quando uma nova partida começa. */
    public static void reiniciar() {
        synchronized (LOCK) {
            eventos.clear();
            amostrasEnergia.clear();
            contadorSegundosEnergia = 0;
            adicionarInterno("RELATÓRIO", "Nova partida iniciada; evidências anteriores foram limpas.");
        }
    }

    /** Registra uma linha genérica com categoria padronizada. */
    public static void registrarEvento(String categoria, String mensagem) {
        synchronized (LOCK) {
            adicionarInterno(categoria, mensagem);
        }
    }

    /** Registra estatísticas consolidadas de média e variância das leituras ruidosas dos sensores. */
    public static void registrarEstatisticasSensores(String lado,
                                                     int alvosMonitorados,
                                                     int totalLeituras,
                                                     double mediaX,
                                                     double mediaY,
                                                     double mediaVx,
                                                     double mediaVy,
                                                     double mediaVarX,
                                                     double mediaVarY,
                                                     double mediaVarVx,
                                                     double mediaVarVy) {
        registrarEvento("SENSORES",
                String.format(Locale.US,
                        "RESUMO | lado=%s | alvos_monitorados=%d | leituras=%d | media_posicao=(x=%.3f, y=%.3f) | variancia_posicao=(x=%.3f, y=%.3f) | media_velocidade=(vx=%.3f, vy=%.3f) | variancia_velocidade=(vx=%.3f, vy=%.3f)",
                        lado,
                        alvosMonitorados,
                        totalLeituras,
                        mediaX,
                        mediaY,
                        mediaVarX,
                        mediaVarY,
                        mediaVx,
                        mediaVy,
                        mediaVarVx,
                        mediaVarVy));
    }

    /** Registra a média e variância de um alvo individual dentro do buffer de sensores. */
    public static void registrarEstatisticaSensorPorAlvo(String lado,
                                                         int indiceAlvo,
                                                         int leituras,
                                                         double mediaX,
                                                         double mediaY,
                                                         double mediaVx,
                                                         double mediaVy,
                                                         double varX,
                                                         double varY,
                                                         double varVx,
                                                         double varVy) {
        registrarEvento("SENSORES_DETALHE",
                String.format(Locale.US,
                        "ALVO_%02d | lado=%s | leituras_buffer=%d | media_posicao=(x=%.3f, y=%.3f) | variancia_posicao=(x=%.3f, y=%.3f) | media_velocidade=(vx=%.3f, vy=%.3f) | variancia_velocidade=(vx=%.3f, vy=%.3f)",
                        indiceAlvo,
                        lado,
                        leituras,
                        mediaX,
                        mediaY,
                        varX,
                        varY,
                        mediaVx,
                        mediaVy,
                        varVx,
                        varVy));
    }

    /** Registra comparação quantitativa entre o vetor bruto y e o vetor reconciliado y_hat. */
    public static void registrarReconciliacao(String lado,
                                              int canhoes,
                                              int alvos,
                                              double mediaAntes,
                                              double mediaDepois,
                                              double varianciaAntes,
                                              double varianciaDepois,
                                              double ajusteMedioAbsoluto,
                                              double ajusteMaximoAbsoluto) {
        registrarEvento("RECONCILIACAO",
                String.format(Locale.US,
                        "lado=%s | canhoes=%d | alvos=%d | media_distancia_antes=%.3f | media_distancia_depois=%.3f | variancia_antes=%.3f | variancia_depois=%.3f | ajuste_medio_abs=%.3f | ajuste_max_abs=%.3f",
                        lado,
                        canhoes,
                        alvos,
                        mediaAntes,
                        mediaDepois,
                        varianciaAntes,
                        varianciaDepois,
                        ajusteMedioAbsoluto,
                        ajusteMaximoAbsoluto));
    }

    /** Registra como energia e penalidade influenciaram a decisão de otimização. */
    public static void registrarImpactoEnergia(String lado,
                                               float energia,
                                               int penalidadePercentual,
                                               int canhoes,
                                               int alvos,
                                               double taxaDisparoEfetiva,
                                               double utilidadeAtual,
                                               double utilidadeComNovo,
                                               double ganhoMarginal,
                                               double custoTotal,
                                               String decisao) {
        registrarEvento("ENERGIA_PENALIDADE",
                String.format(Locale.US,
                        "lado=%s | energia=%.2f | penalidade=%d%% | canhoes=%d | alvos=%d | taxa_disparo_efetiva=%.3f | utilidade_atual=%.4f | utilidade_com_novo=%.4f | ganho_marginal=%.4f | custo_total=%.4f | decisao=%s",
                        lado,
                        energia,
                        penalidadePercentual,
                        canhoes,
                        alvos,
                        taxaDisparoEfetiva,
                        utilidadeAtual,
                        utilidadeComNovo,
                        ganhoMarginal,
                        custoTotal,
                        decisao));
    }

    /** Registra amostras periódicas do consumo de energia dos dois lados da arena. */
    public static void registrarAmostraEnergia(float energiaEsquerda,
                                               float energiaDireita,
                                               int canhoesEsquerda,
                                               int canhoesDireita,
                                               int penalidadeEsquerda,
                                               int penalidadeDireita) {
        synchronized (LOCK) {
            contadorSegundosEnergia++;
            int segundo = contadorSegundosEnergia;
            amostrasEnergia.add(new AmostraEnergia(
                    segundo,
                    energiaEsquerda,
                    energiaDireita,
                    canhoesEsquerda,
                    canhoesDireita,
                    penalidadeEsquerda,
                    penalidadeDireita
            ));
            while (amostrasEnergia.size() > LIMITE_AMOSTRAS_ENERGIA) {
                amostrasEnergia.remove(0);
            }

            adicionarInterno("ENERGIA_TEMPO_REAL",
                    String.format(Locale.US,
                            "t=%ds | energia_A=%.2f | energia_B=%.2f | canhoes_A=%d | canhoes_B=%d | penalidade_A=%d%% | penalidade_B=%d%% | impacto=quanto mais canhoes acima do limite, maior a penalidade e menor a eficiência energética",
                            segundo,
                            energiaEsquerda,
                            energiaDireita,
                            canhoesEsquerda,
                            canhoesDireita,
                            penalidadeEsquerda,
                            penalidadeDireita));
        }
    }

    /** Registra os arquivos gerados pela análise de escalonabilidade. */
    public static void registrarArquivosEscalonabilidade(File reportFile,
                                                         File svgFile,
                                                         File dependencyGraphSvgFile) {
        registrarEvento("ESCALONABILIDADE",
                "relatorio=" + caminhoComExistencia(reportFile)
                        + " | grafico_svg=" + caminhoComExistencia(svgFile)
                        + " | grafo_svg=" + caminhoComExistencia(dependencyGraphSvgFile));
    }

    /** Salva o relatório consolidado e retorna o arquivo gerado. */
    public static File salvarRelatorio(Context context) throws IOException {
        File baseDir = context.getExternalFilesDir(null);
        if (baseDir == null) {
            baseDir = context.getFilesDir();
        }
        File dir = new File(baseDir, "evidencias_av2");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Não foi possível criar diretório: " + dir.getAbsolutePath());
        }

        List<AmostraEnergia> snapshotEnergia;
        synchronized (LOCK) {
            snapshotEnergia = new ArrayList<>(amostrasEnergia);
        }

        File csvEnergia = new File(dir, "energia_penalidade_amostras.csv");
        File graficoEnergia = new File(dir, "grafico_energia_penalidade.svg");
        salvarCsvEnergia(csvEnergia, snapshotEnergia);
        salvarGraficoEnergiaSvg(graficoEnergia, snapshotEnergia);

        synchronized (LOCK) {
            adicionarInterno("ENERGIA_IMPACTO", "csv_amostras=" + caminhoComExistencia(csvEnergia));
            adicionarInterno("ENERGIA_IMPACTO", "grafico_svg=" + caminhoComExistencia(graficoEnergia));
        }

        File arquivo = new File(dir, "relatorio_evidencias_av2.txt");
        try (FileWriter writer = new FileWriter(arquivo, false)) {
            writer.write(gerarTextoRelatorio());
        }

        Log.e(TAG, "Relatório de evidências salvo em: " + arquivo.getAbsolutePath());
        Log.e(TAG, "Gráfico de energia e penalidade salvo em: " + graficoEnergia.getAbsolutePath());
        return arquivo;
    }

    /** Monta o texto do relatório em formato simples, legível e pronto para anexar na entrega. */
    public static String gerarTextoRelatorio() {
        List<String> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(eventos);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("RELATÓRIO DE EVIDÊNCIAS AV2 - AUTOTARGET\n");
        sb.append("============================================================\n\n");
        sb.append("Este relatório consolida evidências adicionais para a rubrica: ");
        sb.append("sensores ruidosos com média/variância, reconciliação antes/depois, ");
        sb.append("impacto de energia/penalidade na otimização e arquivos de escalonabilidade.\n\n");

        escreverCabecalhoSensores(sb);
        escreverSecao(sb, snapshot, "SENSORES", "1.1 RESUMO ESTATÍSTICO DOS SENSORES");
        escreverSecao(sb, snapshot, "SENSORES_DETALHE", "1.2 MÉDIA E VARIÂNCIA POR ALVO MONITORADO");
        escreverSecao(sb, snapshot, "RECONCILIACAO", "2. RECONCILIAÇÃO DE DADOS - ANTES/DEPOIS");
        escreverResumoEnergia(sb);
        escreverSecao(sb, snapshot, "ENERGIA_PENALIDADE", "3.1 LOGS DE IMPACTO DA ENERGIA/PENALIDADE NA OTIMIZAÇÃO");
        escreverSecao(sb, snapshot, "ENERGIA_TEMPO_REAL", "3.2 AMOSTRAS DE ENERGIA EM TEMPO REAL");
        escreverSecao(sb, snapshot, "ENERGIA_IMPACTO", "3.3 ARQUIVOS GERADOS PARA COMPROVAR O IMPACTO");
        escreverSecao(sb, snapshot, "ESCALONABILIDADE", "5. EVIDÊNCIAS DOS ARQUIVOS DE ESCALONABILIDADE");
        escreverSecao(sb, snapshot, "PLACAR", "6. PLACAR FINAL DA PARTIDA");

        sb.append("\n============================================================\n");
        sb.append("EVENTOS COMPLETOS\n");
        sb.append("============================================================\n");
        if (snapshot.isEmpty()) {
            sb.append("Nenhum evento registrado ainda. Execute uma partida para popular as evidências.\n");
        } else {
            for (String evento : snapshot) {
                sb.append(evento).append('\n');
            }
        }

        return sb.toString();
    }

    private static void escreverResumoEnergia(StringBuilder sb) {
        List<AmostraEnergia> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(amostrasEnergia);
        }

        sb.append("3. MODELO DE ENERGIA E PENALIDADE - IMPACTO OBSERVADO\n");
        sb.append("------------------------------------------------------------\n");
        if (snapshot.isEmpty()) {
            sb.append("Nenhuma amostra de energia foi registrada ainda. Execute a partida por alguns segundos para gerar dados.\n\n");
            return;
        }

        double somaEnergiaA = 0.0;
        double somaEnergiaB = 0.0;
        double somaPenalidadeA = 0.0;
        double somaPenalidadeB = 0.0;
        float minEnergiaA = Float.MAX_VALUE;
        float minEnergiaB = Float.MAX_VALUE;
        int maxPenalidadeA = 0;
        int maxPenalidadeB = 0;

        for (AmostraEnergia amostra : snapshot) {
            somaEnergiaA += amostra.energiaA;
            somaEnergiaB += amostra.energiaB;
            somaPenalidadeA += amostra.penalidadeA;
            somaPenalidadeB += amostra.penalidadeB;
            minEnergiaA = Math.min(minEnergiaA, amostra.energiaA);
            minEnergiaB = Math.min(minEnergiaB, amostra.energiaB);
            maxPenalidadeA = Math.max(maxPenalidadeA, amostra.penalidadeA);
            maxPenalidadeB = Math.max(maxPenalidadeB, amostra.penalidadeB);
        }

        int total = snapshot.size();
        sb.append(String.format(Locale.US,
                "Amostras analisadas: %d | energia_media_A=%.2f | energia_media_B=%.2f | energia_minima_A=%.2f | energia_minima_B=%.2f\n",
                total,
                somaEnergiaA / total,
                somaEnergiaB / total,
                minEnergiaA,
                minEnergiaB));
        sb.append(String.format(Locale.US,
                "penalidade_media_A=%.2f%% | penalidade_media_B=%.2f%% | penalidade_maxima_A=%d%% | penalidade_maxima_B=%d%%\n",
                somaPenalidadeA / total,
                somaPenalidadeB / total,
                maxPenalidadeA,
                maxPenalidadeB));
        sb.append("Interpretação: o gráfico SVG e o CSV mostram a queda de energia ao longo do tempo e permitem comparar essa queda com o aumento da penalidade por excesso de canhões.\n\n");
    }

    private static void escreverCabecalhoSensores(StringBuilder sb) {
        sb.append("1. SENSORES RUIDOSOS + BUFFERS - APRESENTAÇÃO ESTATÍSTICA\n");
        sb.append("------------------------------------------------------------\n");
        sb.append("Cada linha abaixo usa as leituras armazenadas no buffer temporal de sensores. ");
        sb.append("A média indica o valor central observado; a variância indica a dispersão causada pelo ruído. ");
        sb.append("Variância maior significa leitura mais instável; variância menor significa leitura mais consistente.\n\n");
    }

    private static void escreverSecao(StringBuilder sb,
                                      List<String> snapshot,
                                      String categoria,
                                      String titulo) {
        sb.append(titulo).append('\n');
        sb.append("------------------------------------------------------------\n");
        int total = 0;
        for (String evento : snapshot) {
            if (evento.contains("[" + categoria + "]")) {
                sb.append(evento).append('\n');
                total++;
            }
        }
        if (total == 0) {
            sb.append("Nenhum registro desta seção foi produzido ainda.\n");
        }
        sb.append('\n');
    }

    private static void adicionarInterno(String categoria, String mensagem) {
        String linha = String.format(Locale.US,
                "%s [%s] %s",
                FORMATO_DATA.format(new Date()),
                categoria,
                mensagem);

        eventos.add(linha);
        while (eventos.size() > LIMITE_EVENTOS) {
            eventos.remove(0);
        }

        Log.e(TAG, linha);
    }

    private static void salvarCsvEnergia(File arquivo, List<AmostraEnergia> amostras) throws IOException {
        try (FileWriter writer = new FileWriter(arquivo, false)) {
            writer.write("segundo,energia_A,energia_B,canhoes_A,canhoes_B,penalidade_A_percentual,penalidade_B_percentual\n");
            for (AmostraEnergia amostra : amostras) {
                writer.write(String.format(Locale.US,
                        "%d,%.2f,%.2f,%d,%d,%d,%d\n",
                        amostra.segundo,
                        amostra.energiaA,
                        amostra.energiaB,
                        amostra.canhoesA,
                        amostra.canhoesB,
                        amostra.penalidadeA,
                        amostra.penalidadeB));
            }
        }
    }

    private static void salvarGraficoEnergiaSvg(File arquivo, List<AmostraEnergia> amostras) throws IOException {
        StringBuilder svg = new StringBuilder();
        int largura = 900;
        int altura = 520;
        int margemEsq = 70;
        int margemTop = 50;
        int graficoLargura = 760;
        int graficoAltura = 350;

        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(largura).append("\" height=\"").append(altura).append("\" viewBox=\"0 0 ").append(largura).append(' ').append(altura).append("\">\n");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
        svg.append("<text x=\"450\" y=\"28\" text-anchor=\"middle\" font-size=\"20\" font-family=\"sans-serif\" font-weight=\"bold\">Impacto da Energia e Penalidade</text>\n");
        svg.append("<text x=\"450\" y=\"48\" text-anchor=\"middle\" font-size=\"12\" font-family=\"sans-serif\">Queda de energia comparada com penalidade por excesso de canhões</text>\n");
        svg.append("<line x1=\"").append(margemEsq).append("\" y1=\"").append(margemTop + graficoAltura).append("\" x2=\"").append(margemEsq + graficoLargura).append("\" y2=\"").append(margemTop + graficoAltura).append("\" stroke=\"#333\"/>\n");
        svg.append("<line x1=\"").append(margemEsq).append("\" y1=\"").append(margemTop).append("\" x2=\"").append(margemEsq).append("\" y2=\"").append(margemTop + graficoAltura).append("\" stroke=\"#333\"/>\n");

        for (int i = 0; i <= 5; i++) {
            int y = margemTop + (graficoAltura * i / 5);
            int valor = 100 - (20 * i);
            svg.append("<line x1=\"").append(margemEsq).append("\" y1=\"").append(y).append("\" x2=\"").append(margemEsq + graficoLargura).append("\" y2=\"").append(y).append("\" stroke=\"#e0e0e0\"/>\n");
            svg.append("<text x=\"60\" y=\"").append(y + 4).append("\" text-anchor=\"end\" font-size=\"11\" font-family=\"sans-serif\">").append(valor).append("</text>\n");
        }

        if (!amostras.isEmpty()) {
            svg.append("<polyline fill=\"none\" stroke=\"#1565c0\" stroke-width=\"3\" points=\"").append(pontosEnergia(amostras, true, margemEsq, margemTop, graficoLargura, graficoAltura)).append("\"/>\n");
            svg.append("<polyline fill=\"none\" stroke=\"#c62828\" stroke-width=\"3\" points=\"").append(pontosEnergia(amostras, false, margemEsq, margemTop, graficoLargura, graficoAltura)).append("\"/>\n");
            svg.append("<polyline fill=\"none\" stroke=\"#2e7d32\" stroke-width=\"2\" stroke-dasharray=\"6 4\" points=\"").append(pontosPenalidade(amostras, true, margemEsq, margemTop, graficoLargura, graficoAltura)).append("\"/>\n");
            svg.append("<polyline fill=\"none\" stroke=\"#ef6c00\" stroke-width=\"2\" stroke-dasharray=\"6 4\" points=\"").append(pontosPenalidade(amostras, false, margemEsq, margemTop, graficoLargura, graficoAltura)).append("\"/>\n");
        } else {
            svg.append("<text x=\"450\" y=\"230\" text-anchor=\"middle\" font-size=\"16\" font-family=\"sans-serif\">Sem amostras de energia registradas ainda.</text>\n");
        }

        svg.append("<text x=\"450\" y=\"435\" text-anchor=\"middle\" font-size=\"12\" font-family=\"sans-serif\">Tempo de partida em segundos</text>\n");
        svg.append("<text x=\"20\" y=\"225\" transform=\"rotate(-90 20 225)\" text-anchor=\"middle\" font-size=\"12\" font-family=\"sans-serif\">Energia / Penalidade (%)</text>\n");
        svg.append("<rect x=\"120\" y=\"455\" width=\"18\" height=\"4\" fill=\"#1565c0\"/><text x=\"145\" y=\"461\" font-size=\"12\" font-family=\"sans-serif\">Energia Sistema A</text>\n");
        svg.append("<rect x=\"280\" y=\"455\" width=\"18\" height=\"4\" fill=\"#c62828\"/><text x=\"305\" y=\"461\" font-size=\"12\" font-family=\"sans-serif\">Energia Sistema B</text>\n");
        svg.append("<rect x=\"440\" y=\"455\" width=\"18\" height=\"4\" fill=\"#2e7d32\"/><text x=\"465\" y=\"461\" font-size=\"12\" font-family=\"sans-serif\">Penalidade A</text>\n");
        svg.append("<rect x=\"580\" y=\"455\" width=\"18\" height=\"4\" fill=\"#ef6c00\"/><text x=\"605\" y=\"461\" font-size=\"12\" font-family=\"sans-serif\">Penalidade B</text>\n");
        svg.append("</svg>\n");

        try (FileWriter writer = new FileWriter(arquivo, false)) {
            writer.write(svg.toString());
        }
    }

    private static String pontosEnergia(List<AmostraEnergia> amostras, boolean sistemaA, int x0, int y0, int largura, int altura) {
        StringBuilder pontos = new StringBuilder();
        int maxIndex = Math.max(1, amostras.size() - 1);
        for (int i = 0; i < amostras.size(); i++) {
            AmostraEnergia a = amostras.get(i);
            double valor = sistemaA ? a.energiaA : a.energiaB;
            int x = x0 + (largura * i / maxIndex);
            int y = y0 + altura - (int) Math.round((Math.max(0.0, Math.min(100.0, valor)) / 100.0) * altura);
            pontos.append(x).append(',').append(y).append(' ');
        }
        return pontos.toString().trim();
    }

    private static String pontosPenalidade(List<AmostraEnergia> amostras, boolean sistemaA, int x0, int y0, int largura, int altura) {
        StringBuilder pontos = new StringBuilder();
        int maxIndex = Math.max(1, amostras.size() - 1);
        for (int i = 0; i < amostras.size(); i++) {
            AmostraEnergia a = amostras.get(i);
            double valor = sistemaA ? a.penalidadeA : a.penalidadeB;
            int x = x0 + (largura * i / maxIndex);
            int y = y0 + altura - (int) Math.round((Math.max(0.0, Math.min(100.0, valor)) / 100.0) * altura);
            pontos.append(x).append(',').append(y).append(' ');
        }
        return pontos.toString().trim();
    }

    private static String caminhoComExistencia(File file) {
        if (file == null) return "arquivo_nulo";
        return file.getAbsolutePath() + " (existe=" + file.exists() + ", bytes=" + (file.exists() ? file.length() : 0) + ")";
    }
}
