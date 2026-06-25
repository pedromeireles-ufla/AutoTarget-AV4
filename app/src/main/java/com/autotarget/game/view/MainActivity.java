package com.autotarget.game.view;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.autotarget.game.R;
import com.autotarget.game.model.Jogo;
import com.autotarget.game.util.EvidenceLogger;
import com.autotarget.game.util.SchedulingAnalysis;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tela principal do AutoTarget AV2.
 * Inicializa a interface, controla o ciclo da partida e dispara a análise de escalonabilidade.
 */
public class MainActivity extends AppCompatActivity implements Jogo.JogoCallback {
    private static final String TAG = "MainActivity";
    private static final String SCHED_TAG = "AV2_ESCALONABILIDADE";

    // Referências aos componentes de interface e ao modelo da partida atual.
    private GameView gameView;
    private Jogo jogo;

    private TextView tvAbatesEsq, tvAbatesDir;
    private TextView tvEnergiaEsq, tvEnergiaDir;
    private ProgressBar pbEnergiaEsq, pbEnergiaDir;
    private TextView tvCronometro;

    private LinearLayout layoutPlacarFinal;
    private TextView tvVencedor;
    private TextView tvAbatesFinalEsq, tvAbatesFinalDir;
    private Button btnReiniciarJogo;

    private Button btnAcaoPrincipal;

    // Executor separado para gerar o relatório de escalonabilidade sem travar a interface.
    private final ExecutorService diagnosticExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean analiseEscalonabilidadeExecutada = false;

    /** Configura a interface, associa botões e inicia uma nova partida ao abrir o app. */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Log de diagnóstico para confirmar que a Activity iniciou e que o filtro do Logcat está correto.
        Log.e(SCHED_TAG, "MainActivity.onCreate() executado. Se esta mensagem aparece, o Logcat está correto.");

        gameView = findViewById(R.id.gameView);
        tvAbatesEsq = findViewById(R.id.tvAbatesEsq);
        tvAbatesDir = findViewById(R.id.tvAbatesDir);
        tvEnergiaEsq = findViewById(R.id.tvEnergiaEsq);
        tvEnergiaDir = findViewById(R.id.tvEnergiaDir);
        pbEnergiaEsq = findViewById(R.id.pbEnergiaEsq);
        pbEnergiaDir = findViewById(R.id.pbEnergiaDir);
        tvCronometro = findViewById(R.id.tvCronometro);

        layoutPlacarFinal = findViewById(R.id.layoutPlacarFinal);
        tvVencedor = findViewById(R.id.tvVencedor);
        tvAbatesFinalEsq = findViewById(R.id.tvAbatesFinalEsq);
        tvAbatesFinalDir = findViewById(R.id.tvAbatesFinalDir);
        btnReiniciarJogo = findViewById(R.id.btnReiniciarJogo);

        btnAcaoPrincipal = findViewById(R.id.btnAcaoPrincipal);

        btnAcaoPrincipal.setOnClickListener(v -> {
            Log.e(SCHED_TAG, "Botão principal clicado.");

            if (jogo == null || !jogo.isEmAndamento()) {
                Log.e(SCHED_TAG, "Iniciando novo jogo e disparando análise de escalonabilidade.");
                iniciarNovoJogo();
            } else {
                jogo.alternarPausa();
                btnAcaoPrincipal.setText(jogo.isPausado() ? "Continuar" : "Pausar");
                Log.e(SCHED_TAG, "Jogo pausado/continuado. pausado=" + jogo.isPausado());
            }
        });

        btnReiniciarJogo.setOnClickListener(v -> {
            Log.e(SCHED_TAG, "Botão reiniciar clicado. Iniciando novo jogo.");
            iniciarNovoJogo();
        });

        gameView.setOnTouchListener(null);
    }

    /** Reinicia a interface, cria um novo objeto Jogo e inicia renderização e simulação. */
    private void iniciarNovoJogo() {
        tvCronometro.setVisibility(View.VISIBLE);
        layoutPlacarFinal.setVisibility(View.GONE);

        Log.e(SCHED_TAG, "iniciarNovoJogo() chamado antes do gameView.post().");

        gameView.post(() -> {
            Log.e(SCHED_TAG, "gameView.post() executado. Criando Jogo.");

            jogo = new Jogo(gameView.getWidth(), gameView.getHeight(), this);
            gameView.setJogo(jogo);
            jogo.iniciar();
            gameView.iniciarRenderizacao();
            btnAcaoPrincipal.setText("Pausar");
            tvAbatesEsq.setText("Abates: 0");
            tvAbatesDir.setText("Abates: 0");

            // A análise é disparada junto com o primeiro jogo para gerar os arquivos exigidos pela AV2.
            Toast.makeText(this, "Análise de escalonabilidade iniciada", Toast.LENGTH_LONG).show();
            executarAnaliseEscalonabilidadeUmaVez();
        });
    }

    /** Executa a análise de escalonabilidade uma única vez em thread separada para não bloquear a interface. */
    private void executarAnaliseEscalonabilidadeUmaVez() {
        if (analiseEscalonabilidadeExecutada) {
            Log.e(SCHED_TAG, "Análise de escalonabilidade já tinha sido executada. Ignorando nova chamada.");
            return;
        }

        analiseEscalonabilidadeExecutada = true;
        Log.e(SCHED_TAG, "Entrou em executarAnaliseEscalonabilidadeUmaVez(). A thread de diagnóstico será iniciada agora.");

        diagnosticExecutor.execute(() -> {
            try {
                Log.e(SCHED_TAG, "Thread de diagnóstico começou. Chamando SchedulingAnalysis.executarAnaliseCompleta().");

                SchedulingAnalysis.AnalysisResult result = SchedulingAnalysis.executarAnaliseCompleta(this);

                Log.e(SCHED_TAG, "ANÁLISE FINALIZADA COM SUCESSO.");
                Log.e(SCHED_TAG, "Relatório salvo em: " + result.reportFile.getAbsolutePath());
                Log.e(SCHED_TAG, "Gráfico SVG salvo em: " + result.svgFile.getAbsolutePath());
                Log.e(SCHED_TAG, "Grafo de dependências SVG salvo em: " + result.dependencyGraphSvgFile.getAbsolutePath());

                EvidenceLogger.registrarArquivosEscalonabilidade(
                        result.reportFile,
                        result.svgFile,
                        result.dependencyGraphSvgFile
                );
                File relatorioEvidencias = EvidenceLogger.salvarRelatorio(this);
                Log.e(SCHED_TAG, "Relatório consolidado de evidências salvo em: " + relatorioEvidencias.getAbsolutePath());

                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Relatório, gráfico e grafo gerados. Veja Logcat: AV2_ESCALONABILIDADE",
                        Toast.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                Log.e(SCHED_TAG, "ERRO AO EXECUTAR ANÁLISE DE ESCALONABILIDADE", e);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Erro na análise de escalonabilidade. Veja o Logcat.",
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    /** Atualiza o cronômetro exibido na interface sempre que o modelo informa um novo tempo. */
    @Override
    public void onTempoAtualizado(int tempo) {
        runOnUiThread(() -> tvCronometro.setText(tempo + "s"));
    }

    /** Atualiza os contadores de abates dos dois lados na interface. */
    @Override
    public void onPlacarAtualizado(int esq, int dir) {
        runOnUiThread(() -> {
            tvAbatesEsq.setText("Abates: " + esq);
            tvAbatesDir.setText("Abates: " + dir);
        });
    }

    /** Sincroniza textos e barras de energia com os valores recebidos do modelo. */
    @Override
    public void onEnergiaAtualizada(float esq, float dir) {
        runOnUiThread(() -> {
            tvEnergiaEsq.setText("Energia: " + (int) esq);
            tvEnergiaDir.setText("Energia: " + (int) dir);
            pbEnergiaEsq.setProgress((int) esq);
            pbEnergiaDir.setProgress((int) dir);
        });
    }

    /** Exibe o painel final com vencedor, placar consolidado e renderização interrompida. */
    @Override
    public void onJogoFinalizado(String vencedor, int abatesEsq, int abatesDir) {
        salvarRelatorioEvidencias("fim da partida");

        runOnUiThread(() -> {
            btnAcaoPrincipal.setText("Iniciar Jogo");
            tvVencedor.setText(vencedor);
            tvAbatesFinalEsq.setText(String.valueOf(abatesEsq));
            tvAbatesFinalDir.setText(String.valueOf(abatesDir));
            layoutPlacarFinal.setVisibility(View.VISIBLE);
            tvCronometro.setVisibility(View.GONE);
            gameView.pararRenderizacao();
        });
    }

    /** Salva o relatório consolidado em segundo plano para não travar a interface. */
    private void salvarRelatorioEvidencias(String origem) {
        diagnosticExecutor.execute(() -> {
            try {
                File arquivo = EvidenceLogger.salvarRelatorio(this);
                Log.e(SCHED_TAG, "Relatório de evidências salvo após " + origem + ": " + arquivo.getAbsolutePath());
            } catch (Exception e) {
                Log.e(SCHED_TAG, "Erro ao salvar relatório de evidências após " + origem, e);
            }
        });
    }

    /** Libera threads e recursos em segundo plano quando a Activity é destruída. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        diagnosticExecutor.shutdownNow();
        Log.e(SCHED_TAG, "MainActivity destruída. Executor de diagnóstico encerrado.");
    }
}