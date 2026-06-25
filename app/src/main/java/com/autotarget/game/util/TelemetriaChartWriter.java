package com.autotarget.game.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Gera, em arquivo, o gráfico de temperatura x tempo da partida (SVG) e um pequeno
 * relatório textual de discussão sobre estabilidade/limiar do sistema ciberfísico.
 *
 * Segue o mesmo padrão de SchedulingAnalysis: nada é desenhado na tela, tudo é
 * salvo em arquivo dentro de getExternalFilesDir, para consulta posterior
 * (ex.: anexar ao relatório da disciplina).
 *
 * Critério adicional: "Inclui análise (gráfico) e discussão de estabilidade/limiar".
 */
public final class TelemetriaChartWriter {
    private static final String TAG = "TelemetriaChartWriter";

    public static final class Resultado {
        public final File svgFile;
        public final File reportFile;

        public Resultado(File svgFile, File reportFile) {
            this.svgFile = svgFile;
            this.reportFile = reportFile;
        }
    }

    private TelemetriaChartWriter() {
    }

    /**
     * Gera o SVG do gráfico e o relatório de discussão para o histórico de
     * temperatura de uma partida específica. Deve ser chamado em thread separada.
     */
    public static Resultado gerarArquivos(Context context, List<Float> historico, float limiar) {
        File baseDir = context.getExternalFilesDir(null);
        File dir = new File(baseDir, "telemetria");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Não foi possível criar diretório: " + dir.getAbsolutePath());
        }

        File svg = new File(dir, "grafico_temperatura.svg");
        File report = new File(dir, "discussao_estabilidade.txt");


        escreverSvg(svg, historico, limiar);
        String discussao = construirDiscussaoEstabilidade(historico, limiar);
        escreverTexto(report, discussao);

        Log.e(TAG, "Gráfico de temperatura salvo em: " + svg.getAbsolutePath());
        Log.e(TAG, "Discussão de estabilidade salva em: " + report.getAbsolutePath());

        return new Resultado(svg, report);
    }

    /** Desenha o gráfico de temperatura x tempo com a linha de limiar, como SVG independente. */
    private static void escreverSvg(File arquivo, List<Float> historico, float limiar) {
        int width = 640;
        int height = 360;
        int paddingEsq = 60;
        int paddingDir = 20;
        int paddingTopo = 30;
        int paddingBaixo = 50;
        int areaW = width - paddingEsq - paddingDir;
        int areaH = height - paddingTopo - paddingBaixo;

        float minTemp = 20f;
        float maxTemp = 50f;

        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\">\n");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#1B1B1B\"/>\n");
        sb.append("<text x=\"").append(width / 2).append("\" y=\"20\" fill=\"#FFFFFF\" font-size=\"16\" " +
                "text-anchor=\"middle\" font-family=\"sans-serif\">Telemetria de Temperatura x Tempo</text>\n");

        // Eixos
        sb.append(String.format(Locale.US,
                "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#AAAAAA\" stroke-width=\"2\"/>\n",
                paddingEsq, paddingTopo, paddingEsq, paddingTopo + areaH));
        sb.append(String.format(Locale.US,
                "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#AAAAAA\" stroke-width=\"2\"/>\n",
                paddingEsq, paddingTopo + areaH, paddingEsq + areaW, paddingTopo + areaH));

        // Marcações do eixo Y (temperatura)
        for (int t = (int) minTemp; t <= maxTemp; t += 10) {
            float y = paddingTopo + areaH - ((t - minTemp) / (maxTemp - minTemp)) * areaH;
            sb.append(String.format(Locale.US,
                    "<text x=\"%d\" y=\"%.1f\" fill=\"#AAAAAA\" font-size=\"11\" font-family=\"sans-serif\" text-anchor=\"end\">%d°C</text>\n",
                    paddingEsq - 8, y + 4, t));
        }

        // Linha do limiar (tracejada, vermelha)
        float yLimiar = paddingTopo + areaH - ((limiar - minTemp) / (maxTemp - minTemp)) * areaH;
        sb.append(String.format(Locale.US,
                "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#FF5252\" stroke-width=\"2\" stroke-dasharray=\"8,6\"/>\n",
                paddingEsq, yLimiar, paddingEsq + areaW, yLimiar));
        sb.append(String.format(Locale.US,
                "<text x=\"%d\" y=\"%.1f\" fill=\"#FF5252\" font-size=\"12\" font-family=\"sans-serif\">Limiar: %.0f°C</text>\n",
                paddingEsq + 8, yLimiar - 6, limiar));

        if (historico == null || historico.size() < 2) {
            sb.append(String.format(Locale.US,
                    "<text x=\"%d\" y=\"%d\" fill=\"#FFFFFF\" font-size=\"13\" font-family=\"sans-serif\">" +
                            "Leituras insuficientes para desenhar a curva (partida muito curta).</text>\n",
                    paddingEsq + 8, paddingTopo + areaH / 2));
        } else {
            float passoX = (float) areaW / (historico.size() - 1);
            StringBuilder path = new StringBuilder();
            StringBuilder pontos = new StringBuilder();

            for (int i = 0; i < historico.size(); i++) {
                float temp = historico.get(i);
                float x = paddingEsq + passoX * i;
                float y = paddingTopo + areaH - ((temp - minTemp) / (maxTemp - minTemp)) * areaH;
                y = Math.max(paddingTopo, Math.min(paddingTopo + areaH, y));

                if (i == 0) {
                    path.append(String.format(Locale.US, "M %.1f %.1f ", x, y));
                } else {
                    path.append(String.format(Locale.US, "L %.1f %.1f ", x, y));
                }

                String cor = temp > limiar ? "#FF5252" : "#4CAF50";
                pontos.append(String.format(Locale.US,
                        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4\" fill=\"%s\"/>\n", x, y, cor));
            }

            sb.append("<path d=\"").append(path).append("\" stroke=\"#4CAF50\" stroke-width=\"3\" fill=\"none\"/>\n");
            sb.append(pontos);
        }

        sb.append(String.format(Locale.US,
                "<text x=\"%d\" y=\"%d\" fill=\"#AAAAAA\" font-size=\"12\" font-family=\"sans-serif\" text-anchor=\"middle\">" +
                        "Tempo (uma leitura a cada 10s)</text>\n",
                paddingEsq + areaW / 2, height - 10));

        sb.append("</svg>\n");
        escreverTexto(arquivo, sb.toString());
    }

    /**
     * Monta um pequeno texto de análise sobre a estabilidade térmica da partida,
     * comparando o tempo em estado normal x superaquecido em relação ao limiar.
     */
    private static String construirDiscussaoEstabilidade(List<Float> historico, float limiar) {
        if (historico == null || historico.isEmpty()) {
            return "Nenhuma leitura de temperatura coletada nesta partida (duração menor que 10s).";
        }

        int acimaDoLimiar = 0;
        float somaTemp = 0f;
        float pico = Float.MIN_VALUE;
        for (Float t : historico) {
            somaTemp += t;
            if (t > pico) pico = t;
            if (t > limiar) acimaDoLimiar++;
        }
        float media = somaTemp / historico.size();
        float percentualAcima = (acimaDoLimiar * 100f) / historico.size();

        if (acimaDoLimiar == 0) {
            return String.format(Locale.US,
                    "Sistema estável: temperatura média de %.1f°C, sempre abaixo do limiar de %.0f°C. " +
                            "Pico de %.1f°C. Feedback de redução de disparo não foi necessário.",
                    media, limiar, pico);
        } else {
            return String.format(Locale.US,
                    "Sistema instável em %.0f%% das leituras (%d de %d) acima do limiar de %.0f°C. " +
                            "Temperatura média %.1f°C, pico de %.1f°C. O controle por feedback reduziu a taxa " +
                            "de disparo dos canhões nesses momentos para evitar superaquecimento.",
                    percentualAcima, acimaDoLimiar, historico.size(), limiar, media, pico);
        }
    }

    private static void escreverTexto(File arquivo, String texto) {
        try (FileWriter fw = new FileWriter(arquivo, false)) {
            fw.write(texto);
        } catch (IOException e) {
            Log.e(TAG, "Erro ao escrever arquivo: " + arquivo.getAbsolutePath(), e);
        }
    }
}
