package com.autotarget.game.model;

import com.autotarget.game.util.DataReconciliation;
import com.autotarget.game.util.EvidenceLogger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executa periodicamente a reconciliação de dados dos sensores.
 * O ciclo coleta snapshots, calcula distâncias corrigidas por Mínimos Quadrados e repassa
 * o resultado ao gerenciador de canhões sem bloquear o movimento dos alvos.
 */
public class OptimizationTask {
    private final Jogo jogo;
    private final boolean isEsquerda;

    // ConcurrentHashMap: escritas dos sensores (a cada 1000ms por alvo) não bloqueiam
    // a leitura do snapshot em executarReconciliacao(), e vice-versa.
    // Mantém um buffer individual por alvo para separar as leituras de cada entidade.
    private final ConcurrentHashMap<Alvo, SensorBuffer> buffersPorAlvo = new ConcurrentHashMap<>();
    private static final long JANELA_RECONCILIACAO_MS = 10000;
    private static final int MIN_LEITURAS_VALIDAS_POR_ALVO = 10;
    private final CannonManager cannonManager;

    /** Inicializa a tarefa de otimização responsável por um lado da arena. */
    public OptimizationTask(Jogo jogo, boolean isEsquerda) {
        this.jogo = jogo;
        this.isEsquerda = isEsquerda;
        this.cannonManager = new CannonManager(jogo, isEsquerda);
    }

    /** Começa a acompanhar um alvo criando um buffer de leituras para ele, sem bloquear quem chama. */
    public void registrarAlvo(Alvo alvo) {
        buffersPorAlvo.putIfAbsent(alvo, new SensorBuffer());
    }

    /** Deixa de acompanhar um alvo que saiu do lado ou foi removido/destruído. */
    public void removerAlvo(Alvo alvo) {
        buffersPorAlvo.remove(alvo);
    }

    /**
     * Armazena uma leitura ruidosa recebida do alvo para uso posterior na reconciliação.
     * Chamado pelo próprio alvo a cada 1000ms — deve ser rápido e nunca bloquear;
     * ConcurrentHashMap.get() é lock-free para leituras.
     */
    public void coletarLeituraSensor(Alvo alvo, float x, float y, float vx, float vy) {
        SensorBuffer buffer = buffersPorAlvo.get(alvo);
        if (buffer != null) buffer.adicionarLeitura(x, y, vx, vy);
    }

    /**
     * Consolida leituras recentes, aplica reconciliação por Mínimos Quadrados e aciona
     * o gerenciador de canhões. Ciclo completo chamado a cada 10 segundos — nunca bloqueia
     * o movimento dos alvos, pois trabalha sobre cópias (snapshots) do estado compartilhado.
     */
    public void executarReconciliacao() {

        // Fase 1: copia todos os alvos ativos para não impedir a lógica de criação de canhões.
        List<Alvo> alvosSnapshot = new ArrayList<>();
        List<List<SensorData>> leiturasPorAlvo = new ArrayList<>();

        for (Map.Entry<Alvo, SensorBuffer> entry : buffersPorAlvo.entrySet()) {
            Alvo alvo = entry.getKey();
            SensorBuffer buffer = entry.getValue();

            if (!alvo.isAtivo()) {
                continue;
            }

            List<SensorData> leiturasRecentes = buffer.getLeiturasUltimos(JANELA_RECONCILIACAO_MS);
            List<SensorData> leiturasEscolhidas;

            if (leiturasRecentes.size() >= MIN_LEITURAS_VALIDAS_POR_ALVO) {
                // Caso ideal: usa as leituras recentes dos últimos 10 segundos.
                leiturasEscolhidas = leiturasRecentes;
            } else {
                List<SensorData> todasAsLeituras = buffer.getLeituras();

                if (todasAsLeituras.size() >= MIN_LEITURAS_VALIDAS_POR_ALVO) {
                    // Fallback seguro: usa o histórico somente se já tiver pelo menos 10 leituras.
                    leiturasEscolhidas = todasAsLeituras;
                } else {
                    // Fallback tático: ainda não há 10 leituras, então usa a posição real atual
                    // apenas para não travar a decisão de adicionar canhões.
                    // Quando o alvo completar 10 leituras, a reconciliação passa a usar sensores normalmente.
                    leiturasEscolhidas = new ArrayList<>();
                    leiturasEscolhidas.add(new SensorData(
                            alvo.getX(),
                            alvo.getY(),
                            alvo.getVx(),
                            alvo.getVy(),
                            System.currentTimeMillis()
                    ));
                }
            }

            alvosSnapshot.add(alvo);
            leiturasPorAlvo.add(leiturasEscolhidas);
        }

        // Copia a lista de canhões do lado analisado para trabalhar sobre um estado estável.
        List<Canhao> canhoesSnapshot = new ArrayList<>(
                isEsquerda ? jogo.getCanhoesEsquerda() : jogo.getCanhoesDireita()
        );

        int m = canhoesSnapshot.size();
        int n = alvosSnapshot.size();

        registrarEstatisticasSensores(leiturasPorAlvo);

        if (n == 0 || m == 0) {
            cannonManager.avaliarEAjustarCanhoes(new double[0], canhoesSnapshot, alvosSnapshot, n);
            return;
        }

        // Fase 2: monta o vetor y com as distâncias médias entre canhões e alvos.
        double[] y = new double[m * n];
        double[] varianciasDistancias = new double[m * n];

        for (int i = 0; i < m; i++) {
            Canhao c = canhoesSnapshot.get(i);

            for (int j = 0; j < n; j++) {
                List<SensorData> leituras = leiturasPorAlvo.get(j);
                int idx = i * n + j;

                double somaDistancias = 0.0;
                for (SensorData leitura : leituras) {
                    double dx = c.getX() - leitura.getX();
                    double dy = c.getY() - leitura.getY();
                    somaDistancias += Math.sqrt(dx * dx + dy * dy);
                }

                double mediaDistancia = somaDistancias / leituras.size();
                y[idx] = mediaDistancia;

                double somaVariancia = 0.0;
                for (SensorData leitura : leituras) {
                    double dx = c.getX() - leitura.getX();
                    double dy = c.getY() - leitura.getY();
                    double distancia = Math.sqrt(dx * dx + dy * dy);
                    double diferenca = distancia - mediaDistancia;
                    somaVariancia += diferenca * diferenca;
                }

                // Quando só existe uma leitura de fallback, a variância seria zero.
                // O Math.max depois garante variância mínima 1.0 para evitar matriz degenerada.
                varianciasDistancias[idx] = somaVariancia / leituras.size();
            }
        }

        // Fase 3: monta a matriz diagonal V com a variância das medições.
        double[][] V = new double[m * n][m * n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                int idx = i * n + j;
                V[idx][idx] = Math.max(varianciasDistancias[idx], 1.0);
            }
        }

        // Fase 4: monta a matriz A indicando relações de cobertura entre canhões e alvos.
        double limiarCobertura = 300.0;
        double[][] A = new double[m][m * n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                int idx = i * n + j;
                A[i][idx] = (y[idx] < limiarCobertura) ? 1.0 : 0.0;
            }
        }

        // Fase 5: aplica a reconciliação para reduzir inconsistências das medições.
        double[] yReconciliada = DataReconciliation.reconcile(y, V, A);

        EvidenceLogger.registrarReconciliacao(
                ladoTexto(),
                m,
                n,
                calcularMedia(y),
                calcularMedia(yReconciliada),
                calcularVariancia(y),
                calcularVariancia(yReconciliada),
                calcularAjusteMedioAbsoluto(y, yReconciliada),
                calcularAjusteMaximoAbsoluto(y, yReconciliada)
        );

        // Fase 6: usa as distâncias reconciliadas para adaptar a formação dos canhões.
        cannonManager.avaliarEAjustarCanhoes(yReconciliada, canhoesSnapshot, alvosSnapshot, n);
    }

    /** Consolida estatísticas das leituras ruidosas usadas no ciclo atual. */
    private void registrarEstatisticasSensores(List<List<SensorData>> leiturasPorAlvo) {
        int alvosMonitorados = 0;
        int totalLeituras = 0;
        double somaMediaX = 0.0;
        double somaMediaY = 0.0;
        double somaMediaVx = 0.0;
        double somaMediaVy = 0.0;
        double somaVarX = 0.0;
        double somaVarY = 0.0;
        double somaVarVx = 0.0;
        double somaVarVy = 0.0;

        for (List<SensorData> leituras : leiturasPorAlvo) {
            if (leituras == null || leituras.isEmpty()) {
                continue;
            }

            alvosMonitorados++;
            totalLeituras += leituras.size();

            double mediaX = calcularMediaCampo(leituras, 0);
            double mediaY = calcularMediaCampo(leituras, 1);
            double mediaVx = calcularMediaCampo(leituras, 2);
            double mediaVy = calcularMediaCampo(leituras, 3);

            double varX = calcularVarianciaCampo(leituras, 0, mediaX);
            double varY = calcularVarianciaCampo(leituras, 1, mediaY);
            double varVx = calcularVarianciaCampo(leituras, 2, mediaVx);
            double varVy = calcularVarianciaCampo(leituras, 3, mediaVy);

            somaMediaX += mediaX;
            somaMediaY += mediaY;
            somaMediaVx += mediaVx;
            somaMediaVy += mediaVy;
            somaVarX += varX;
            somaVarY += varY;
            somaVarVx += varVx;
            somaVarVy += varVy;

            EvidenceLogger.registrarEstatisticaSensorPorAlvo(
                    ladoTexto(),
                    alvosMonitorados,
                    leituras.size(),
                    mediaX,
                    mediaY,
                    mediaVx,
                    mediaVy,
                    varX,
                    varY,
                    varVx,
                    varVy
            );
        }

        if (alvosMonitorados == 0) {
            return;
        }

        EvidenceLogger.registrarEstatisticasSensores(
                ladoTexto(),
                alvosMonitorados,
                totalLeituras,
                somaMediaX / alvosMonitorados,
                somaMediaY / alvosMonitorados,
                somaMediaVx / alvosMonitorados,
                somaMediaVy / alvosMonitorados,
                somaVarX / alvosMonitorados,
                somaVarY / alvosMonitorados,
                somaVarVx / alvosMonitorados,
                somaVarVy / alvosMonitorados
        );
    }

    private double calcularMediaCampo(List<SensorData> leituras, int campo) {
        double soma = 0.0;
        for (SensorData leitura : leituras) {
            soma += valorCampo(leitura, campo);
        }
        return soma / leituras.size();
    }

    private double calcularVarianciaCampo(List<SensorData> leituras, int campo, double media) {
        double soma = 0.0;
        for (SensorData leitura : leituras) {
            double diferenca = valorCampo(leitura, campo) - media;
            soma += diferenca * diferenca;
        }
        return soma / leituras.size();
    }

    private double valorCampo(SensorData leitura, int campo) {
        switch (campo) {
            case 0: return leitura.getX();
            case 1: return leitura.getY();
            case 2: return leitura.getVx();
            case 3: return leitura.getVy();
            default: return 0.0;
        }
    }

    private double calcularMedia(double[] valores) {
        if (valores.length == 0) return 0.0;
        double soma = 0.0;
        for (double valor : valores) soma += valor;
        return soma / valores.length;
    }

    private double calcularVariancia(double[] valores) {
        if (valores.length == 0) return 0.0;
        double media = calcularMedia(valores);
        double soma = 0.0;
        for (double valor : valores) {
            double diferenca = valor - media;
            soma += diferenca * diferenca;
        }
        return soma / valores.length;
    }

    private double calcularAjusteMedioAbsoluto(double[] antes, double[] depois) {
        int limite = Math.min(antes.length, depois.length);
        if (limite == 0) return 0.0;
        double soma = 0.0;
        for (int i = 0; i < limite; i++) {
            soma += Math.abs(depois[i] - antes[i]);
        }
        return soma / limite;
    }

    private double calcularAjusteMaximoAbsoluto(double[] antes, double[] depois) {
        int limite = Math.min(antes.length, depois.length);
        double maximo = 0.0;
        for (int i = 0; i < limite; i++) {
            maximo = Math.max(maximo, Math.abs(depois[i] - antes[i]));
        }
        return maximo;
    }

    private String ladoTexto() {
        return isEsquerda ? "Sistema A" : "Sistema B";
    }
}
