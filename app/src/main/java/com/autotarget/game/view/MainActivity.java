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
import com.autotarget.game.util.EvidenceLogger;
import com.autotarget.game.util.FirebaseRepository;
import com.autotarget.game.util.SchedulingAnalysis;
import com.google.firebase.auth.FirebaseAuth;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tela principal do AutoTarget AV3.
 * Inicializa a interface, controla o ciclo da partida, dispara a análise de
 * escalonabilidade (legado da AV2, mantido como parte do diagnóstico do projeto)
 * e integra os requisitos da AV3: persistência no Firestore, criptografia,
 * telemetria do sistema ciberfísico e controle de sessão.
 */
public class MainActivity extends AppCompatActivity implements Jogo.JogoCallback {
    private static final String TAG = "MainActivity";
    private static final String SCHED_TAG = "AV2_ESCALONABILIDADE";

    // Referências aos componentes de interface e ao modelo da partida atual.
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

    // Executor separado para gerar o relatório de escalonabilidade sem travar a interface.
    private final ExecutorService diagnosticExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean analiseEscalonabilidadeExecutada = false;

    // Evita que onResume tente redirecionar para o login durante um logoff já em andamento,
    // o que duplicaria a navegação (fazerLogoff já está saindo da tela).
    private volatile boolean saindoIntencionalmente = false;

    /** Configura a interface, associa botões e inicia uma nova partida ao abrir o app. */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tratamento de sessão: garante que só chega aqui um usuário autenticado.
        // Se a sessão tiver expirado ou o app for aberto sem login (ex: deep link, restauração
        // de estado), redireciona para o login em vez de deixar a tela quebrar mais adiante.
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.e(SCHED_TAG, "onCreate: nenhum usuário autenticado. Redirecionando para LoginActivity.");
            irParaLoginPorSessaoInvalida();
            return;
        }

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
        Button btnLogoff = findViewById(R.id.btnLogoff);
        btnLogoff.setOnClickListener(v -> fazerLogoff());
        Button btnEditarNickname = findViewById(R.id.btnEditarNickname);
        btnEditarNickname.setOnClickListener(v -> mostrarDialogoTrocarNickname());

        layoutPlacarFinal = findViewById(R.id.layoutPlacarFinal);
        tvVencedor = findViewById(R.id.tvVencedor);
        tvAbatesFinalEsq = findViewById(R.id.tvAbatesFinalEsq);
        tvAbatesFinalDir = findViewById(R.id.tvAbatesFinalDir);
        btnReiniciarJogo = findViewById(R.id.btnReiniciarJogo);
        Button btnVerRanking = findViewById(R.id.btnVerRanking);
        btnVerRanking.setOnClickListener(v ->
                startActivity(new Intent(this, RankingActivity.class))
        );

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

    /**
     * Recebe cada nova leitura de temperatura do sistema ciberfísico.
     * O histórico completo é lido diretamente do Jogo ao final da partida
     * para montar o gráfico, então aqui não é necessário fazer nada além
     * de registrar — o método existe para cumprir o contrato da interface
     * e pode ser usado no futuro para exibir a temperatura em tempo real.
     */
    @Override
    public void onTelemetriaAtualizada(float temperatura, float limiar, float fatorFeedback) {
        // Intencionalmente sem atualização de UI aqui: o gráfico é populado
        // de uma vez ao final da partida, em onJogoFinalizado.
    }

    /** Exibe o painel final com vencedor, placar consolidado e renderização interrompida. */
    @Override
    public void onJogoFinalizado(String vencedor, int abatesEsq, int abatesDir, int totalCanhoes) {
        salvarRelatorioEvidencias("fim da partida");
        salvarPartidaNoFirebase(abatesEsq, abatesDir, totalCanhoes);

        // Captura o histórico de temperatura ANTES de qualquer limpeza de estado do Jogo.
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

    /**
     * Gera, em segundo plano, o gráfico SVG de temperatura x tempo e o relatório de
     * discussão de estabilidade da partida, salvos em arquivo (mesmo padrão usado pela
     * análise de escalonabilidade). Nada é exibido na tela de fim de jogo.
     */
    private void gerarGraficoTemperaturaEmArquivo(java.util.List<Float> historico, float limiar) {
        diagnosticExecutor.execute(() -> {
            try {
                com.autotarget.game.util.TelemetriaChartWriter.Resultado resultado =
                        com.autotarget.game.util.TelemetriaChartWriter.gerarArquivos(this, historico, limiar);
                Log.e(SCHED_TAG, "Gráfico de temperatura salvo em: " + resultado.svgFile.getAbsolutePath());
                Log.e(SCHED_TAG, "Discussão de estabilidade salva em: " + resultado.reportFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(SCHED_TAG, "Erro ao gerar gráfico de temperatura", e);
            }
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
                userId,
                nickname,
                "Total: " + (abatesEsq + abatesDir),
                abatesEsq + abatesDir,
                totalCanhoes
        );

        firebaseRepository.salvarPartida(partida, new FirebaseRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Partida salva no ranking!", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Erro ao salvar no Firebase", e);

                // Tratamento de sessão: se o erro indicar que a sessão não é mais válida
                // (token expirado, permissão negada, usuário deslogado em outro dispositivo),
                // avisa o jogador e leva de volta ao login em vez de deixar a partida se perder
                // silenciosamente sem nenhuma explicação.
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

    /** Verifica se uma exceção do Firebase indica problema de sessão/autenticação. */
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

    /** Libera threads e recursos em segundo plano quando a Activity é destruída. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        diagnosticExecutor.shutdownNow();
        Log.e(SCHED_TAG, "MainActivity destruída. Executor de diagnóstico encerrado.");
    }

    /**
     * Tratamento de sessão: a cada vez que a tela volta ao primeiro plano,
     * confirma que o usuário ainda está autenticado. Cobre o caso de o token
     * expirar ou a conta ser removida/desconectada enquanto o app estava em background.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (!saindoIntencionalmente && FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.e(SCHED_TAG, "onResume: sessão ausente/expirada. Redirecionando para LoginActivity.");
            irParaLoginPorSessaoInvalida();
        }
    }

    /** Encerra a sessão atual (se houver) e envia o usuário de volta para a tela de login. */
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

        // Para o jogo se estiver rodando
        if (jogo != null && jogo.isEmAndamento()) {
            jogo.alternarPausa();
        }

        // Faz o signOut no Firebase
        FirebaseAuth.getInstance().signOut();

        // Volta para a tela de login, limpando a back stack
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /** Abre um diálogo para o usuário alterar o nickname exibido no ranking. */
    private void mostrarDialogoTrocarNickname() {
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Novo nickname");
        if (user.getDisplayName() != null) {
            input.setText(user.getDisplayName());
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Trocar nickname")
                .setView(input)
                .setPositiveButton("Salvar", (dialog, which) -> {
                    String novoNickname = input.getText().toString().trim();
                    if (novoNickname.isEmpty()) {
                        Toast.makeText(this, "Nickname não pode ser vazio", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    com.google.firebase.auth.UserProfileChangeRequest profileUpdate =
                            new com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                    .setDisplayName(novoNickname)
                                    .build();

                    user.updateProfile(profileUpdate).addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Toast.makeText(this, "Nickname atualizado!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Erro ao atualizar nickname", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}