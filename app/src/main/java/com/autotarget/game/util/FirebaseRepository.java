package com.autotarget.game.util;

import com.autotarget.game.model.Partida;
import com.autotarget.game.model.Telemetria;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repositório central para operações do Firebase.
 * Garante que todas as operações ocorram em threads separadas.
 * Atende ao requisito 6.3.2 c) da AV3.
 */
public class FirebaseRepository {
    private static FirebaseRepository instance;
    private final FirebaseFirestore db;
    private final FirebaseAuth auth;
    private final ExecutorService executor;

    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    private FirebaseRepository() {
        this.db = FirebaseFirestore.getInstance();
        this.auth = FirebaseAuth.getInstance();
        this.executor = Executors.newSingleThreadExecutor();
    }

    /** Garante uma única instância compartilhada (Singleton), evitando múltiplos executors concorrentes. */
    public static synchronized FirebaseRepository getInstance() {
        if (instance == null) {
            instance = new FirebaseRepository();
        }
        return instance;
    }

    /** Salva uma partida criptografada no Firestore, de forma sincronizada para evitar conflitos. */
    public synchronized void salvarPartida(Partida partida, RepositoryCallback<Void> callback) {
        executor.execute(() -> {
            try {
                org.json.JSONObject dadosSensiveis = new org.json.JSONObject();
                dadosSensiveis.put("nomeJogador", partida.getNomeJogador());
                dadosSensiveis.put("pontuacaoFinal", partida.getPontuacaoFinal());

                String jsonCriptografado = Cryptography.encrypt(dadosSensiveis.toString());
                partida.setDadosCriptografados(jsonCriptografado);
                partida.setNomeJogador(null);
                partida.setPontuacaoFinal(null);

                db.collection("partidas")
                        .add(partida)
                        .addOnSuccessListener(documentReference -> {
                            EvidenceLogger.registrarEvento("FIREBASE", "Partida salva com sucesso");
                            if (callback != null) callback.onSuccess(null);
                        })
                        .addOnFailureListener(e -> {
                            EvidenceLogger.registrarEvento("FIREBASE", "Erro ao salvar partida: " + e.getMessage());
                            if (callback != null) callback.onError(e);
                        });
            } catch (Exception e) {
                if (callback != null) callback.onError(e);
            }
        });
    }

    /** Salva dados de telemetria no Firestore, de forma sincronizada. */
    public synchronized void registrarTelemetria(Telemetria telemetria) {
        executor.execute(() -> {
            db.collection("telemetria")
                .add(telemetria)
                .addOnFailureListener(e ->
                    EvidenceLogger.registrarEvento("FIREBASE", "Erro ao registrar telemetria: " + e.getMessage())
                );
        });
    }

    /** Busca o ranking das melhores pontuações, de forma sincronizada para evitar conflitos. */
    public synchronized void buscarRanking(RepositoryCallback<List<Partida>> callback) {
        executor.execute(() -> {
            db.collection("partidas")
                    .orderBy("alvosAbatidos", Query.Direction.DESCENDING)
                    .orderBy("timestamp", Query.Direction.ASCENDING) // empate: mais antigo mantém a posição
                    .limit(100)
                    .get()
                    .addOnSuccessListener(queryDocumentSnapshots -> {
                        List<Partida> todas = queryDocumentSnapshots.toObjects(Partida.class);

                        for (Partida p : todas) {
                            try {
                                String jsonDescriptografado = Cryptography.decrypt(p.getDadosCriptografados());
                                org.json.JSONObject obj = new org.json.JSONObject(jsonDescriptografado);
                                p.setNomeJogador(obj.getString("nomeJogador"));
                                p.setPontuacaoFinal(obj.getString("pontuacaoFinal"));
                            } catch (Exception e) {
                                p.setNomeJogador("Erro");
                                p.setPontuacaoFinal("0");
                            }
                        }

                        // Mantém apenas a melhor partida por jogador (sem repetir nome no ranking)
                        java.util.Map<String, Partida> melhoresPorJogador = new java.util.LinkedHashMap<>();
                        for (Partida p : todas) {
                            if (!melhoresPorJogador.containsKey(p.getUserId())) {
                                melhoresPorJogador.put(p.getUserId(), p);
                            }
                        }

                        // Limita a 5 jogadores únicos
                        List<Partida> top5 = new java.util.ArrayList<>(melhoresPorJogador.values());
                        if (top5.size() > 5) top5 = top5.subList(0, 5);

                        if (callback != null) callback.onSuccess(top5);
                    })
                    .addOnFailureListener(e -> {
                        if (callback != null) callback.onError(e);
                    });
        });
    }

    public String getCurrentUserId() {
        return auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
    }
}
