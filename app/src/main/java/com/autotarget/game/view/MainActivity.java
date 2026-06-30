package com.autotarget.game.view;

import android.annotation.SuppressLint;
import android.content.Intent;
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
import com.autotarget.game.model.Partida;
import com.autotarget.game.util.AmdahlAnalysis;
import com.autotarget.game.util.EvidenceLogger;
import com.autotarget.game.util.FirebaseRepository;
import com.autotarget.game.util.SchedulingAnalysis;
import com.google.firebase.auth.FirebaseAuth;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tela principal do AutoTarget AV4.
 * Integra os requisitos anteriores (AV2 escalonabilidade, AV3 telemetria/Firebase)
 * e adiciona a Análise de Amdahl (AV4 b): disparada automaticamente a cada início
 * de partida, em thread separada, sem bloquear a interface.
 */
public class MainActivity extends AppCompatActivity implements Jogo.JogoCallback {
    private static final String TAG       = "MainActivity";
    private static final String SCHED_TAG = "AV2_ESCALONABILIDADE";

    private GameView gameView;
    private Jogo jogo;
    private FirebaseRepository firebaseRepository = FirebaseRepository.getInstance();

    private TextView tvAbatesEsq, tvAbatesDir;
    private TextView tvEnergiaEsq, tvEnergiaDir;
    private ProgressBar pbEnergiaEsq, pbEnergiaDir;
    private TextView tvCronometro;

    private LinearLayout layoutPlacarFinal;
    private TextView tvVencedor;
    private TextView tvAbatesFinalEsq, tvAbatesFinalDir;
    private Button btnReiniciarJogo;

    private Button btnAcaoPrincipal;

    // Executor único para todas as tarefas de diagnóstico em segundo plano
    // (escalonabilidade AV2, Amdahl AV4, telemetria AV3, evidências).
    private final ExecutorService diagnosticExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean analiseEscalonabilidadeExecutada = false;

    private volatile boolean saindoIntencionalmente = false;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.e(SCHED_TAG, "onCreate: sem usuário autenticado. Redirecionando para login.");
            irParaLoginPorSessaoInvalida();
            return;
        }

        setContentView(R.layout.activity_main);
        Log.e(SCHED_TAG, "MainActivity.onCreate() executado.");

        gameView     = findViewById(R.id.gameView);
        tvAbatesEsq  = findViewById(R.id.tvAbatesEsq);
        tvAbatesDir  = findViewById(R.id.tvAbatesDir);
        tvEnergiaEsq = findViewById(R.id.tvEnergiaEsq);
        tvEnergiaDir = findViewById(R.id.tvEnergiaDir);
        pbEnergiaEsq = findViewById(R.id.pbEnergiaEsq);
        pbEnergiaDir = findViewById(R.id.pbEnergiaDir);
        tvCronometro = findViewById(R.id.tvCronometro);

        Button btnLogoff = findViewById(R.id.btnLogoff);
        btnLogoff.setOnClickListener(v -> fazerLogoff());

        Button btnEditarNickname = findViewById(R.id.btnEditarNickname);
        btnEditarNickname.setOnClickListener(v -> mostrarDialogoTrocarNickname());

        layoutPlacarFinal  = findViewById(R.id.layoutPlacarFinal);
        tvVencedor         = findViewById(R.id.tvVencedor);
        tvAbatesFinalEsq   = findViewById(R.id.tvAbatesFinalEsq);
        tvAbatesFinalDir   = findViewById(R.id.tvAbatesFinalDir);
        btnReiniciarJogo   = findViewById(R.id.btnReiniciarJogo);

        Button btnVerRanking = findViewById(R.id.btnVerRanking);
        btnVerRanking.setOnClickListener(v ->
                startActivity(new Intent(this, RankingActivity.class)));

        btnAcaoPrincipal = findViewById(R.id.btnAcaoPrincipal);
        btnAcaoPrincipal.setOnClickListener(v -> {
            if (jogo == null || !jogo.isEmAndamento()) {
                iniciarNovoJogo();
            } else {
                jogo.alternarPausa();
                btnAcaoPrincipal.setText(jogo.isPausado() ? "Continuar" : "Pausar");
            }
        });

        btnReiniciarJogo.setOnClickListener(v -> iniciarNovoJogo());
    }

    // -------------------------------------------------------------------------
    // Início de jogo
    // -------------------------------------------------------------------------

    /**
     * Reinicia a interface, cria um novo Jogo e dispara em segundo plano:
     *  1. Análise de escalonabilidade (AV2) — apenas na primeira vez.
     *  2. Análise de Amdahl (AV4 b) — toda vez que um jogo é iniciado.
     */
    private void iniciarNovoJogo() {
        tvCronometro.setVisibility(View.VISIBLE);
        layoutPlacarFinal.setVisibility(View.GONE);

        gameView.post(() -> {
            jogo = new Jogo(gameView.getWidth(), gameView.getHeight(), this);
            gameView.setJogo(jogo);
            jogo.iniciar();
            gameView.iniciarRenderizacao();
            btnAcaoPrincipal.setText("Pausar");
            tvAbatesEsq.setText("Abates: 0");
            tvAbatesDir.setText("Abates: 0");

            // AV2 — escalonabilidade (uma única vez por sessão)
            executarAnaliseEscalonabilidadeUmaVez();

            // AV4 b — Amdahl: executa a cada início de partida
            executarAnaliseAmdahl();
        });
    }

    // -------------------------------------------------------------------------
    // AV4 b — Análise de Amdahl (toda partida)
    // -------------------------------------------------------------------------

    /**
     * Dispara a análise de Amdahl em thread de diagnóstico.
     * Gera grafico_amdahl.svg e relatorio_amdahl.txt no diretório externo do app.
     * Exibe Toast com o caminho ao concluir, ou aviso de erro.
     */
    private void executarAnaliseAmdahl() {
        Log.e(AmdahlAnalysis.TAG, "Análise de Amdahl agendada para esta partida.");
        Toast.makeText(this, "Análise de Amdahl iniciada (AV4 b)…", Toast.LENGTH_SHORT).show();

        diagnosticExecutor.execute(() -> {
            try {
                AmdahlAnalysis.Resultado resultado = AmdahlAnalysis.executarAnalise(this);

                Log.e(AmdahlAnalysis.TAG, "Análise concluída.");
                Log.e(AmdahlAnalysis.TAG, "Relatório: " + resultado.reportFile.getAbsolutePath());
                Log.e(AmdahlAnalysis.TAG, "SVG      : " + resultado.svgFile.getAbsolutePath());

                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Amdahl concluído. SVG: " + resultado.svgFile.getName(),
                        Toast.LENGTH_LONG
                ).show());

            } catch (Exception e) {
                Log.e(AmdahlAnalysis.TAG, "Erro na análise de Amdahl", e);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Erro na análise de Amdahl. Veja o Logcat (AV4_AMDAHL).",
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    // -------------------------------------------------------------------------
    // AV2 — Escalonabilidade (uma vez por sessão)
    // -------------------------------------------------------------------------

    private void executarAnaliseEscalonabilidadeUmaVez() {
        if (analiseEscalonabilidadeExecutada) return;
        analiseEscalonabilidadeExecutada = true;

        Toast.makeText(this, "Análise de escalonabilidade iniciada", Toast.LENGTH_LONG).show();

        diagnosticExecutor.execute(() -> {
            try {
                Log.e(SCHED_TAG, "Chamando SchedulingAnalysis.executarAnaliseCompleta().");
                SchedulingAnalysis.AnalysisResult result =
                        SchedulingAnalysis.executarAnaliseCompleta(this);

                Log.e(SCHED_TAG, "Relatório: " + result.reportFile.getAbsolutePath());
                Log.e(SCHED_TAG, "SVG      : " + result.svgFile.getAbsolutePath());
                Log.e(SCHED_TAG, "Grafo    : " + result.dependencyGraphSvgFile.getAbsolutePath());

                EvidenceLogger.registrarArquivosEscalonabilidade(
                        result.reportFile,
                        result.svgFile,
                        result.dependencyGraphSvgFile);
                File relEvid = EvidenceLogger.salvarRelatorio(this);
                Log.e(SCHED_TAG, "Evidências: " + relEvid.getAbsolutePath());

                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Escalonabilidade concluída. Veja Logcat: AV2_ESCALONABILIDADE",
                        Toast.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                Log.e(SCHED_TAG, "Erro na análise de escalonabilidade", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Callbacks do Jogo
    // -------------------------------------------------------------------------

    @Override
    public void onTempoAtualizado(int tempo) {
        runOnUiThread(() -> tvCronometro.setText(tempo + "s"));
    }

    @Override
    public void onPlacarAtualizado(int esq, int dir) {
        runOnUiThread(() -> {
            tvAbatesEsq.setText("Abates: " + esq);
            tvAbatesDir.setText("Abates: " + dir);
        });
    }

    @Override
    public void onEnergiaAtualizada(float esq, float dir) {
        runOnUiThread(() -> {
            tvEnergiaEsq.setText("Energia: " + (int) esq);
            tvEnergiaDir.setText("Energia: " + (int) dir);
            pbEnergiaEsq.setProgress((int) esq);
            pbEnergiaDir.setProgress((int) dir);
        });
    }

    @Override
    public void onTelemetriaAtualizada(float temperatura, float limiar, float fatorFeedback) {
        // Histórico lido de uma vez em onJogoFinalizado para montar o gráfico.
    }

    @Override
    public void onJogoFinalizado(String vencedor, int abatesEsq, int abatesDir, int totalCanhoes) {
        salvarRelatorioEvidencias("fim da partida");
        salvarPartidaNoFirebase(abatesEsq, abatesDir, totalCanhoes);

        java.util.List<Float> historico = jogo.getHistoricoTemperatura();
        float limiar = jogo.getLimiarTemperatura();
        gerarGraficoTemperaturaEmArquivo(historico, limiar);

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

    // -------------------------------------------------------------------------
    // Utilitários
    // -------------------------------------------------------------------------

    private void gerarGraficoTemperaturaEmArquivo(java.util.List<Float> historico, float limiar) {
        diagnosticExecutor.execute(() -> {
            try {
                com.autotarget.game.util.TelemetriaChartWriter.Resultado resultado =
                        com.autotarget.game.util.TelemetriaChartWriter.gerarArquivos(
                                this, historico, limiar);
                Log.e(SCHED_TAG, "Temperatura SVG : " + resultado.svgFile.getAbsolutePath());
                Log.e(SCHED_TAG, "Temperatura TXT : " + resultado.reportFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(SCHED_TAG, "Erro ao gerar gráfico de temperatura", e);
            }
        });
    }

    private void salvarRelatorioEvidencias(String origem) {
        diagnosticExecutor.execute(() -> {
            try {
                File arquivo = EvidenceLogger.salvarRelatorio(this);
                Log.e(SCHED_TAG, "Evidências salvas após " + origem + ": " + arquivo.getAbsolutePath());
            } catch (Exception e) {
                Log.e(SCHED_TAG, "Erro ao salvar evidências após " + origem, e);
            }
        });
    }

    private void salvarPartidaNoFirebase(int abatesEsq, int abatesDir, int totalCanhoes) {
        String userId = firebaseRepository.getCurrentUserId();
        if (userId == null) return;

        com.google.firebase.auth.FirebaseUser user =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        String nickname = (user != null && user.getDisplayName() != null
                && !user.getDisplayName().isEmpty())
                ? user.getDisplayName()
                : "Jogador_" + userId.substring(0, 5);

        Partida partida = new Partida(
                userId, nickname,
                "Total: " + (abatesEsq + abatesDir),
                abatesEsq + abatesDir,
                totalCanhoes);

        firebaseRepository.salvarPartida(partida, new FirebaseRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Partida salva no ranking!", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Erro ao salvar no Firebase", e);
                if (isErroDeSessao(e)) {
                    runOnUiThread(() -> irParaLoginPorSessaoInvalida());
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Não foi possível salvar a partida. Verifique sua conexão.",
                            Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private boolean isErroDeSessao(Exception e) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return true;
        if (e == null || e.getMessage() == null) return false;
        String msg = e.getMessage().toUpperCase();
        return msg.contains("PERMISSION_DENIED")
                || msg.contains("UNAUTHENTICATED")
                || msg.contains("INVALID_CREDENTIAL")
                || msg.contains("USER_TOKEN_EXPIRED")
                || msg.contains("USER_DISABLED");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        diagnosticExecutor.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!saindoIntencionalmente && FirebaseAuth.getInstance().getCurrentUser() == null) {
            irParaLoginPorSessaoInvalida();
        }
    }

    private void irParaLoginPorSessaoInvalida() {
        saindoIntencionalmente = true;
        Toast.makeText(this, "Sessão expirada. Faça login novamente.", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void fazerLogoff() {
        saindoIntencionalmente = true;
        if (jogo != null && jogo.isEmAndamento()) jogo.alternarPausa();
        FirebaseAuth.getInstance().signOut();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void mostrarDialogoTrocarNickname() {
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Novo nickname");
        if (user.getDisplayName() != null) input.setText(user.getDisplayName());

        new android.app.AlertDialog.Builder(this)
                .setTitle("Trocar nickname")
                .setView(input)
                .setPositiveButton("Salvar", (dialog, which) -> {
                    String novo = input.getText().toString().trim();
                    if (novo.isEmpty()) {
                        Toast.makeText(this, "Nickname não pode ser vazio", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    com.google.firebase.auth.UserProfileChangeRequest req =
                            new com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                    .setDisplayName(novo).build();
                    user.updateProfile(req).addOnCompleteListener(task -> {
                        if (task.isSuccessful())
                            Toast.makeText(this, "Nickname atualizado!", Toast.LENGTH_SHORT).show();
                        else
                            Toast.makeText(this, "Erro ao atualizar nickname", Toast.LENGTH_SHORT).show();
                    });
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
