package com.autotarget.game.model;

import com.autotarget.game.util.EvidenceLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Gerencia a adaptação dos canhões de um dos lados do campo.
 * Usa as distâncias reconciliadas para realocar canhões, adicionar novos canhões quando há ganho
 * esperado e remover canhões pouco úteis quando o custo supera o benefício.
 */
public class CannonManager {
    // Referência ao estado global do jogo, usada para consultar energia, alvos e listas de canhões.
    private final Jogo jogo;
    private final boolean isEsquerda;

    // Parâmetros de controle que limitam quantidade, custo e espaçamento dos canhões.
    private static final int    LIMITE_MINIMO_CANHOES  = 1;
    private static final int    LIMITE_MAXIMO_CANHOES  = 6;
    private static final float  CUSTO_ENERGIA_POR_CANHAO = 5.0f;
    private static final float  LIMIAR_COBERTURA       = 300f;
    private static final float  MARGEM_SEGURANCA       = 60f;
    private static final float  DISTANCIA_MINIMA_ENTRE_CANHOES = MARGEM_SEGURANCA * 2f;
    private static final float  LIMIAR_DESLOCAMENTO_SIGNIFICATIVO = 10f;
    private static final float  TAXA_DISPARO_BASE      = 1.0f;
    private static final float  DISTANCIA_IDEAL_ESPALHAMENTO = DISTANCIA_MINIMA_ENTRE_CANHOES * 1.6f;
    private static final double PESO_FORMACAO_ESPALHADA = 0.30;
    private static final double PESO_BONUS_ESPALHAMENTO_UTILIDADE = 0.20;

    // Pesos usados para balancear ganho defensivo contra custo e excesso de canhões.
    private static final double PESO_CUSTO_ENERGIA = 0.02;
    private static final double PESO_PENALIDADE_EXCESSO = 0.01;
    private static final double PESO_BAIXA_ENERGIA = 2.5;
    private static final float  ENERGIA_MINIMA_PARA_ADICIONAR = 25f;
    private static final float  ENERGIA_CRITICA_PARA_REMOVER = 15f;

    /** Define o gerenciador responsável por otimizar os canhões de um lado da arena. */
    public CannonManager(Jogo jogo, boolean isEsquerda) {
        this.jogo = jogo;
        this.isEsquerda = isEsquerda;
    }

    /**
     * Ponto de entrada principal: chamado pela OptimizationTask após a reconciliação.
     *
     * @param distanciasReconciliadas vetor y_hat da reconciliação (distâncias canhão-alvo ajustadas)
     * @param canhoes   lista de canhões ativos do lado
     * @param alvos     lista de alvos ativos do lado
     * @param n         número de alvos (para indexar o vetor linearizado m×n)
     */
    public void avaliarEAjustarCanhoes(double[] distanciasReconciliadas,
                                       List<Canhao> canhoes,
                                       List<Alvo> alvos,
                                       int n) {
        float energia = isEsquerda ? jogo.getEnergiaEsquerda() : jogo.getEnergiaDireita();

        if (energia <= 0f) {
            EvidenceLogger.registrarImpactoEnergia(
                    ladoTexto(),
                    energia,
                    jogo.getPenalidade(isEsquerda),
                    contarCanhoesAtivos(canhoes),
                    contarAlvosAtivos(alvos),
                    calcularTaxaDisparoEfetiva(),
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    "MANTER: energia esgotada"
            );
            return;
        }

        int numCanhoes = contarCanhoesAtivos(canhoes);
        int numAlvos = contarAlvosAtivos(alvos);

        realocarCanhoes(distanciasReconciliadas, canhoes, alvos, n);

        if (numAlvos == 0) {
            String decisao = "MANTER: nenhum alvo ativo";
            if (numCanhoes > LIMITE_MINIMO_CANHOES) {
                decisao = "REMOVER: sem alvos ativos e acima do mínimo";
                removerCanhaoMenosUtil(canhoes, alvos);
            }
            EvidenceLogger.registrarImpactoEnergia(
                    ladoTexto(),
                    energia,
                    jogo.getPenalidade(isEsquerda),
                    numCanhoes,
                    numAlvos,
                    calcularTaxaDisparoEfetiva(),
                    0.0,
                    0.0,
                    0.0,
                    calcularCustoTotalNormalizado(energia),
                    decisao
            );
            return;
        }

        double utilidadeAtual = calcularUtilidadeEsperada(distanciasReconciliadas, canhoes, alvos, n)
                + calcularBonusEspalhamento(canhoes, null) * PESO_BONUS_ESPALHAMENTO_UTILIDADE;

        float[] posNovoCanhao = calcularPosicaoOtimaNovoCanhao(distanciasReconciliadas, canhoes, alvos, n);
        double utilidadeComNovo = calcularUtilidadeEsperadaComNovoCanhao(
                distanciasReconciliadas,
                canhoes,
                alvos,
                n,
                posNovoCanhao[0],
                posNovoCanhao[1]
        ) + calcularBonusEspalhamento(canhoes, posNovoCanhao) * PESO_BONUS_ESPALHAMENTO_UTILIDADE;

        double ganhoMarginal = utilidadeComNovo - utilidadeAtual;
        double custoTotal = calcularCustoTotalNormalizado(energia);

        if (ganhoMarginal > custoTotal
                && numCanhoes < LIMITE_MAXIMO_CANHOES
                && energia >= ENERGIA_MINIMA_PARA_ADICIONAR) {
            EvidenceLogger.registrarImpactoEnergia(
                    ladoTexto(),
                    energia,
                    jogo.getPenalidade(isEsquerda),
                    numCanhoes,
                    numAlvos,
                    calcularTaxaDisparoEfetiva(),
                    utilidadeAtual,
                    utilidadeComNovo,
                    ganhoMarginal,
                    custoTotal,
                    "ADICIONAR: ganho marginal supera custo"
            );
            adicionarCanhao(posNovoCanhao[0], posNovoCanhao[1]);
            return;
        }

        if ((utilidadeAtual < custoTotal || energia <= ENERGIA_CRITICA_PARA_REMOVER)
                && numCanhoes > LIMITE_MINIMO_CANHOES) {
            EvidenceLogger.registrarImpactoEnergia(
                    ladoTexto(),
                    energia,
                    jogo.getPenalidade(isEsquerda),
                    numCanhoes,
                    numAlvos,
                    calcularTaxaDisparoEfetiva(),
                    utilidadeAtual,
                    utilidadeComNovo,
                    ganhoMarginal,
                    custoTotal,
                    "REMOVER: utilidade baixa ou energia crítica"
            );
            removerCanhaoMenosUtil(canhoes, alvos);
            return;
        }

        EvidenceLogger.registrarImpactoEnergia(
                ladoTexto(),
                energia,
                jogo.getPenalidade(isEsquerda),
                numCanhoes,
                numAlvos,
                calcularTaxaDisparoEfetiva(),
                utilidadeAtual,
                utilidadeComNovo,
                ganhoMarginal,
                custoTotal,
                "MANTER: ganho não justificou alteração"
        );
    }

    /** Combina custo de energia, energia restante e penalidade por excesso em uma métrica única. */
    /** Calcula o custo relativo dos canhões ativos em relação à energia disponível do lado. */
    private double calcularCustoTotalNormalizado(float energiaRestante) {
        double energiaNormalizada = Math.max(0.0, Math.min(100.0, energiaRestante)) / 100.0;
        double fatorBaixaEnergia = 1.0 + ((1.0 - energiaNormalizada) * PESO_BAIXA_ENERGIA);

        double custoEnergiaNormalizado = CUSTO_ENERGIA_POR_CANHAO
                * PESO_CUSTO_ENERGIA
                * fatorBaixaEnergia;

        double custoPenalidadeNormalizado = jogo.getPenalidade(isEsquerda) * PESO_PENALIDADE_EXCESSO;
        return custoEnergiaNormalizado + custoPenalidadeNormalizado;
    }

    // -------------------------------------------------------------------------
    // Realocação gradual baseada nos dados reconciliados
    // -------------------------------------------------------------------------

    /**
     * Para cada canhão, calcula o centroide ponderado dos alvos na sua zona de influência
     * usando as distâncias reconciliadas (não as brutas). Chama definirDestino() para
     * iniciar o deslizamento gradual na tela.
     */
    /** Usa os dados reconciliados para sugerir novas posições defensivas sem sobrepor canhões. */
    private void realocarCanhoes(double[] yr, List<Canhao> canhoes, List<Alvo> alvos, int n) {
        int m = canhoes.size();
        int totalAtivos = contarCanhoesAtivos(canhoes);
        int ordemAtiva = 0;
        List<float[]> destinosReservados = new ArrayList<>();

        for (int i = 0; i < m; i++) {
            Canhao c = canhoes.get(i);
            if (!c.isAtivo()) continue;

            float centroideX = 0, centroideY = 0;
            double pesoTotal = 0;

            for (int j = 0; j < alvos.size(); j++) {
                if (j >= n) break;
                Alvo a = alvos.get(j);
                if (!a.isAtivo()) continue;

                double dist = yr[i * n + j];
                // Usa apenas alvos dentro do raio de influência (distância reconciliada)
                if (dist < LIMIAR_COBERTURA && dist > 0) {
                    // Peso inversamente proporcional à distância reconciliada
                    double peso = 1.0 / dist;
                    centroideX += a.getX() * peso;
                    centroideY += a.getY() * peso;
                    pesoTotal  += peso;
                }
            }

            float[] posicaoFormacao = calcularPosicaoFormacao(ordemAtiva, totalAtivos);
            ordemAtiva++;

            if (pesoTotal > 0) {
                centroideX /= pesoTotal;
                centroideY /= pesoTotal;
            } else {
                // Quando não há alvo na zona imediata, o canhão ocupa uma faixa defensiva
                // própria, mantendo a cobertura espalhada pelo lado da arena.
                centroideX = posicaoFormacao[0];
                centroideY = posicaoFormacao[1];
            }

            float destinoX = (float) (centroideX * (1.0 - PESO_FORMACAO_ESPALHADA)
                    + posicaoFormacao[0] * PESO_FORMACAO_ESPALHADA);
            float destinoY = (float) (centroideY * (1.0 - PESO_FORMACAO_ESPALHADA)
                    + posicaoFormacao[1] * PESO_FORMACAO_ESPALHADA);

            float[] destinoSeguro = ajustarPosicaoContraSobreposicao(
                    destinoX,
                    destinoY,
                    destinosReservados,
                    canhoes,
                    c
            );

            float dxDestino = destinoSeguro[0] - c.getX();
            float dyDestino = destinoSeguro[1] - c.getY();
            float distanciaAteDestino = (float) Math.sqrt(dxDestino * dxDestino + dyDestino * dyDestino);

            if (distanciaAteDestino >= LIMIAR_DESLOCAMENTO_SIGNIFICATIVO) {
                c.definirDestino(destinoSeguro[0], destinoSeguro[1]);
            }

            destinosReservados.add(destinoSeguro);

        }
    }

    // -------------------------------------------------------------------------
    // Cálculo de utilidade marginal
    // -------------------------------------------------------------------------

    /**
     * Calcula a utilidade esperada da configuração atual de canhões.
     * A métrica estima quantos alvos podem ser abatidos por segundo com base nas distâncias reconciliadas.
     */
    /** Estima o ganho tático esperado de cada canhão considerando distância e chance de abate. */
    private double calcularUtilidadeEsperada(double[] yr, List<Canhao> canhoes, List<Alvo> alvos, int n) {
        double abatesEsperadosPorSegundo = 0.0;

        for (int j = 0; j < alvos.size() && j < n; j++) {
            Alvo alvo = alvos.get(j);
            if (!alvo.isAtivo()) continue;

            double probabilidadeAlvoSobreviver = 1.0;

            for (int i = 0; i < canhoes.size(); i++) {
                Canhao canhao = canhoes.get(i);
                if (!canhao.isAtivo()) continue;

                double distancia = yr[i * n + j];
                double probabilidadeAbate = calcularProbabilidadeAbatePorDistancia(distancia);
                double taxaDisparo = calcularTaxaDisparoEfetiva();

                probabilidadeAlvoSobreviver *= (1.0 - probabilidadeAbate * taxaDisparo);
            }

            abatesEsperadosPorSegundo += (1.0 - probabilidadeAlvoSobreviver);
        }

        return abatesEsperadosPorSegundo;
    }

    /**
     * Estima a utilidade caso um novo canhão seja adicionado em uma posição candidata.
     * Usa as distâncias reconciliadas para os canhões atuais e distância geométrica
     * para o novo canhão candidato.
     */
    private double calcularUtilidadeEsperadaComNovoCanhao(double[] yr,
                                                          List<Canhao> canhoes,
                                                          List<Alvo> alvos,
                                                          int n,
                                                          float novoX,
                                                          float novoY) {
        double abatesEsperadosPorSegundo = 0.0;

        for (int j = 0; j < alvos.size() && j < n; j++) {
            Alvo alvo = alvos.get(j);
            if (!alvo.isAtivo()) continue;

            double probabilidadeAlvoSobreviver = 1.0;

            for (int i = 0; i < canhoes.size(); i++) {
                Canhao canhao = canhoes.get(i);
                if (!canhao.isAtivo()) continue;

                double distancia = yr[i * n + j];
                double probabilidadeAbate = calcularProbabilidadeAbatePorDistancia(distancia);
                double taxaDisparo = calcularTaxaDisparoEfetiva();

                probabilidadeAlvoSobreviver *= (1.0 - probabilidadeAbate * taxaDisparo);
            }

            double dx = novoX - alvo.getX();
            double dy = novoY - alvo.getY();
            double distanciaNovoCanhao = Math.sqrt(dx * dx + dy * dy);
            double probabilidadeNovoCanhao = calcularProbabilidadeAbatePorDistancia(distanciaNovoCanhao);
            double taxaDisparoNovoCanhao = calcularTaxaDisparoEfetiva();

            probabilidadeAlvoSobreviver *= (1.0 - probabilidadeNovoCanhao * taxaDisparoNovoCanhao);
            abatesEsperadosPorSegundo += (1.0 - probabilidadeAlvoSobreviver);
        }

        return abatesEsperadosPorSegundo;
    }

    /**
     * Probabilidade de abate baseada na distância.
     * Quanto menor a distância reconciliada, maior a chance de abate.
     */
    /** Converte distância até o alvo em probabilidade aproximada de acerto. */
    private double calcularProbabilidadeAbatePorDistancia(double distancia) {
        if (distancia <= 0) return 1.0;
        if (distancia >= LIMIAR_COBERTURA) return 0.0;

        return 1.0 - (distancia / LIMIAR_COBERTURA);
    }

    /**
     * Taxa efetiva de disparo considerando a penalidade por excesso de canhões.
     */
    /** Reduz a taxa efetiva de disparo quando há muitos canhões competindo por energia. */
    private double calcularTaxaDisparoEfetiva() {
        double penalidade = jogo.getPenalidade(isEsquerda) / 100.0;
        return TAXA_DISPARO_BASE / (1.0 + penalidade);
    }

    // -------------------------------------------------------------------------
    // Posição ótima para novo canhão
    // -------------------------------------------------------------------------

    /**
     * Encontra o centroide dos alvos não cobertos pelos canhões atuais,
     * usando as distâncias reconciliadas como critério de cobertura.
     */
    private float[] calcularPosicaoOtimaNovoCanhao(double[] yr, List<Canhao> canhoes,
                                                   List<Alvo> alvos, int n) {
        float meio = jogo.getLargura() / 2f;
        float centroideX = 0, centroideY = 0;
        int contagem = 0;

        for (int j = 0; j < alvos.size() && j < n; j++) {
            Alvo a = alvos.get(j);
            if (!a.isAtivo()) continue;

            // Verificar se o alvo já está coberto por algum canhão existente
            boolean coberto = false;
            for (int i = 0; i < canhoes.size(); i++) {
                if (yr[i * n + j] < LIMIAR_COBERTURA) { coberto = true; break; }
            }

            if (!coberto) {
                centroideX += a.getX();
                centroideY += a.getY();
                contagem++;
            }
        }

        // Fallback: centroide geral de todos os alvos ativos
        if (contagem == 0) {
            for (Alvo a : alvos) {
                if (a.isAtivo()) {
                    centroideX += a.getX();
                    centroideY += a.getY();
                    contagem++;
                }
            }
        }

        if (contagem > 0) {
            centroideX /= contagem;
            centroideY /= contagem;
        } else {
            centroideX = isEsquerda ? meio * 0.5f : meio * 1.5f;
            centroideY = jogo.getAltura() * 0.5f;
        }

        float[] posicaoFormacaoLivre = calcularPosicaoFormacao(contarCanhoesAtivos(canhoes),
                contarCanhoesAtivos(canhoes) + 1);

        centroideX = (float) (centroideX * (1.0 - PESO_FORMACAO_ESPALHADA)
                + posicaoFormacaoLivre[0] * PESO_FORMACAO_ESPALHADA);
        centroideY = (float) (centroideY * (1.0 - PESO_FORMACAO_ESPALHADA)
                + posicaoFormacaoLivre[1] * PESO_FORMACAO_ESPALHADA);

        // Validar limites do lado
        if (isEsquerda) centroideX = Math.max(MARGEM_SEGURANCA, Math.min(centroideX, meio - MARGEM_SEGURANCA));
        else             centroideX = Math.max(meio + MARGEM_SEGURANCA, Math.min(centroideX, jogo.getLargura() - MARGEM_SEGURANCA));
        centroideY = Math.max(MARGEM_SEGURANCA, Math.min(centroideY, jogo.getAltura() - MARGEM_SEGURANCA));

        return ajustarPosicaoContraSobreposicao(centroideX, centroideY, new ArrayList<>(), canhoes, null);
    }

    /**
     * Afasta uma posição candidata de todos os canhões existentes e dos destinos
     * já reservados no mesmo ciclo de reconciliação. Isso evita que dois canhões
     * sejam enviados para o mesmo centroide quando observam o mesmo grupo de alvos.
     */
    private float[] ajustarPosicaoContraSobreposicao(float x, float y,
                                                     List<float[]> destinosReservados,
                                                     List<Canhao> canhoesExistentes,
                                                     Canhao canhaoIgnorado) {
        float[] posicao = limitarPosicaoAoLado(x, y);

        for (int tentativa = 0; tentativa < 16; tentativa++) {
            float[] conflito = encontrarConflitoMaisProximo(
                    posicao[0],
                    posicao[1],
                    destinosReservados,
                    canhoesExistentes,
                    canhaoIgnorado
            );

            if (conflito == null) {
                return posicao;
            }

            double dx = posicao[0] - conflito[0];
            double dy = posicao[1] - conflito[1];
            double distancia = Math.sqrt(dx * dx + dy * dy);

            double angulo;
            if (distancia < 0.001) {
                angulo = (Math.PI * 2.0 * tentativa) / 16.0;
            } else {
                angulo = Math.atan2(dy, dx);
            }

            // Pequeno deslocamento extra para evitar ficar exatamente no limite mínimo.
            float novoX = conflito[0] + (float) Math.cos(angulo) * (DISTANCIA_MINIMA_ENTRE_CANHOES + 10f);
            float novoY = conflito[1] + (float) Math.sin(angulo) * (DISTANCIA_MINIMA_ENTRE_CANHOES + 10f);
            posicao = limitarPosicaoAoLado(novoX, novoY);
        }

        return posicao;
    }


    /** Calcula uma posição de formação para manter os canhões distribuídos no lado da arena. */
    private float[] calcularPosicaoFormacao(int indice, int total) {
        float meio = jogo.getLargura() / 2f;
        float minX = isEsquerda ? MARGEM_SEGURANCA : meio + MARGEM_SEGURANCA;
        float maxX = isEsquerda ? meio - MARGEM_SEGURANCA : jogo.getLargura() - MARGEM_SEGURANCA;
        float minY = MARGEM_SEGURANCA;
        float maxY = jogo.getAltura() - MARGEM_SEGURANCA;

        int totalSeguro = Math.max(1, total);
        int indiceSeguro = Math.max(0, Math.min(indice, totalSeguro - 1));

        float y = minY + ((maxY - minY) * (indiceSeguro + 1) / (totalSeguro + 1));
        float x;

        if (totalSeguro == 1) {
            x = (minX + maxX) / 2f;
        } else {
            float coluna = (indiceSeguro % 2 == 0) ? 0.35f : 0.65f;
            x = minX + (maxX - minX) * coluna;
        }

        return limitarPosicaoAoLado(x, y);
    }

    /** Mede se os canhões ativos estão bem espalhados, retornando valor entre 0 e 1. */
    private double calcularBonusEspalhamento(List<Canhao> canhoes, float[] novoCanhao) {
        List<float[]> posicoes = new ArrayList<>();

        for (Canhao canhao : canhoes) {
            if (canhao.isAtivo()) {
                posicoes.add(new float[]{canhao.getX(), canhao.getY()});
            }
        }

        if (novoCanhao != null) {
            posicoes.add(new float[]{novoCanhao[0], novoCanhao[1]});
        }

        if (posicoes.size() <= 1) {
            return 1.0;
        }

        double soma = 0.0;
        for (int i = 0; i < posicoes.size(); i++) {
            double menorDistancia = Double.MAX_VALUE;
            for (int j = 0; j < posicoes.size(); j++) {
                if (i == j) continue;
                float[] a = posicoes.get(i);
                float[] b = posicoes.get(j);
                menorDistancia = Math.min(menorDistancia, calcularDistancia(a[0], a[1], b[0], b[1]));
            }
            soma += Math.min(1.0, menorDistancia / DISTANCIA_IDEAL_ESPALHAMENTO);
        }

        return soma / posicoes.size();
    }

    /** Localiza o canhão ou destino reservado mais próximo que viola a distância mínima. */
    private float[] encontrarConflitoMaisProximo(float x, float y,
                                                 List<float[]> destinosReservados,
                                                 List<Canhao> canhoesExistentes,
                                                 Canhao canhaoIgnorado) {
        float[] conflitoMaisProximo = null;
        double menorDistancia = Double.MAX_VALUE;

        for (float[] destino : destinosReservados) {
            double distancia = calcularDistancia(x, y, destino[0], destino[1]);
            if (distancia < DISTANCIA_MINIMA_ENTRE_CANHOES && distancia < menorDistancia) {
                menorDistancia = distancia;
                conflitoMaisProximo = destino;
            }
        }

        for (Canhao canhao : canhoesExistentes) {
            if (canhao == canhaoIgnorado || !canhao.isAtivo()) continue;

            double distancia = calcularDistancia(x, y, canhao.getX(), canhao.getY());
            if (distancia < DISTANCIA_MINIMA_ENTRE_CANHOES && distancia < menorDistancia) {
                menorDistancia = distancia;
                conflitoMaisProximo = new float[]{canhao.getX(), canhao.getY()};
            }
        }

        return conflitoMaisProximo;
    }

    /** Calcula a distância euclidiana entre dois pontos do campo. */
    /** Calcula a distância euclidiana entre dois pontos da tela. */
    private double calcularDistancia(float x1, float y1, float x2, float y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Mantém a posição candidata dentro do lado correto e longe das bordas. */
    /** Garante que uma posição otimizada permaneça dentro da metade da tela correta. */
    private float[] limitarPosicaoAoLado(float x, float y) {
        float meio = jogo.getLargura() / 2f;

        if (isEsquerda) {
            x = Math.max(MARGEM_SEGURANCA, Math.min(x, meio - MARGEM_SEGURANCA));
        } else {
            x = Math.max(meio + MARGEM_SEGURANCA, Math.min(x, jogo.getLargura() - MARGEM_SEGURANCA));
        }

        y = Math.max(MARGEM_SEGURANCA, Math.min(y, jogo.getAltura() - MARGEM_SEGURANCA));
        return new float[]{x, y};
    }

    // -------------------------------------------------------------------------
    // Remoção: elimina o canhão com pior cobertura média (mais distante dos alvos)
    // -------------------------------------------------------------------------

    /** Remove o canhão ativo com pior proximidade média em relação aos alvos. */
    /** Remove o canhão com menor contribuição defensiva quando o custo fica alto. */
    private void removerCanhaoMenosUtil(List<Canhao> canhoes, List<Alvo> alvos) {
        if (contarCanhoesAtivos(canhoes) <= LIMITE_MINIMO_CANHOES) return;

        Canhao pior = null;
        double piorPontuacao = Double.MAX_VALUE;

        for (Canhao c : canhoes) {
            if (!c.isAtivo()) continue;

            double cobertura = 0.0;
            int count = 0;
            for (Alvo a : alvos) {
                if (!a.isAtivo()) continue;
                double dx = c.getX() - a.getX();
                double dy = c.getY() - a.getY();
                double distancia = Math.sqrt(dx * dx + dy * dy);
                cobertura += calcularProbabilidadeAbatePorDistancia(distancia);
                count++;
            }
            if (count > 0) cobertura /= count;

            double menorDistanciaOutroCanhao = DISTANCIA_IDEAL_ESPALHAMENTO;
            for (Canhao outro : canhoes) {
                if (outro == c || !outro.isAtivo()) continue;
                menorDistanciaOutroCanhao = Math.min(
                        menorDistanciaOutroCanhao,
                        calcularDistancia(c.getX(), c.getY(), outro.getX(), outro.getY())
                );
            }

            double bonusEspalhamentoLocal = Math.min(1.0, menorDistanciaOutroCanhao / DISTANCIA_IDEAL_ESPALHAMENTO);
            double pontuacao = cobertura + bonusEspalhamentoLocal * 0.35;

            if (pontuacao < piorPontuacao) {
                piorPontuacao = pontuacao;
                pior = c;
            }
        }

        if (pior != null) {
            jogo.removerCanhao(pior);
        }
    }

    /** Conta apenas canhões ainda ativos. */
    /** Conta apenas canhões ainda ativos, ignorando objetos que já foram desativados. */
    private int contarCanhoesAtivos(List<Canhao> canhoes) {
        int total = 0;

        for (Canhao canhao : canhoes) {
            if (canhao.isAtivo()) {
                total++;
            }
        }

        return total;
    }

    /** Conta apenas alvos ainda ativos. */
    /** Conta os alvos que continuam vivos e relevantes para a tomada de decisão. */
    private int contarAlvosAtivos(List<Alvo> alvos) {
        int total = 0;

        for (Alvo alvo : alvos) {
            if (alvo.isAtivo()) {
                total++;
            }
        }

        return total;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String ladoTexto() {
        return isEsquerda ? "Sistema A" : "Sistema B";
    }

    /** Encapsula a criação do canhão e evita que exceções interrompam o ciclo de otimização. */
    /** Cria um novo canhão em posição segura quando há ganho esperado suficiente. */
    private void adicionarCanhao(float x, float y) {
        try { jogo.adicionarCanhao(x, y); } catch (Exception ignored) {}
    }

    /**
     * Estima ganho de forma simplificada quando não há dados reconciliados disponíveis.
     * Este método é mantido como apoio para cenários de fallback geométrico.
     */
    /** Aproxima o benefício de adicionar defesa com base na relação entre alvos e canhões. */
    private double calcularGanhoEsperadoSimples(int numCanhoes, int numAlvos) {
        double fatorDiminuicao = 1.0 / (1.0 + numCanhoes * 0.2);
        double probAcerto = Math.min(0.9, 0.4 + (numAlvos * 0.08));
        return TAXA_DISPARO_BASE * probAcerto * fatorDiminuicao;
    }

    /** Escolhe uma posição inicial para novo canhão usando a concentração atual de alvos. */
    private float[] calcularPosicaoOtimaSimples(List<Canhao> canhoes, List<Alvo> alvos) {
        float meio = jogo.getLargura() / 2f;
        float cx = 0, cy = 0;
        int cont = 0;
        for (Alvo a : alvos) {
            if (!a.isAtivo()) continue;
            boolean coberto = false;
            for (Canhao c : canhoes) {
                double dx = c.getX() - a.getX(), dy = c.getY() - a.getY();
                if (Math.sqrt(dx * dx + dy * dy) < LIMIAR_COBERTURA) { coberto = true; break; }
            }
            if (!coberto) { cx += a.getX(); cy += a.getY(); cont++; }
        }
        if (cont == 0) {
            for (Alvo a : alvos) { if (a.isAtivo()) { cx += a.getX(); cy += a.getY(); cont++; } }
        }
        if (cont > 0) { cx /= cont; cy /= cont; }
        else { cx = isEsquerda ? meio * 0.5f : meio * 1.5f; cy = jogo.getAltura() * 0.5f; }

        if (isEsquerda) cx = Math.max(MARGEM_SEGURANCA, Math.min(cx, meio - MARGEM_SEGURANCA));
        else             cx = Math.max(meio + MARGEM_SEGURANCA, Math.min(cx, jogo.getLargura() - MARGEM_SEGURANCA));
        cy = Math.max(MARGEM_SEGURANCA, Math.min(cy, jogo.getAltura() - MARGEM_SEGURANCA));
        return ajustarPosicaoContraSobreposicao(cx, cy, new ArrayList<>(), canhoes, null);
    }
}