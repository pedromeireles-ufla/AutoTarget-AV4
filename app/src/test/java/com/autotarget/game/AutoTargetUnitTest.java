package com.autotarget.game;

import static org.junit.Assert.*;
import org.junit.Test;
import com.autotarget.game.util.CalculoUtil;
import com.autotarget.game.model.Jogo;
import com.autotarget.game.model.Alvo;
import com.autotarget.game.model.AlvoComum;
import com.autotarget.game.exception.JogoException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * Testes Unitários JUnit para validar funções críticas do jogo.
 */
public class AutoTargetUnitTest {

    /** Teste 1: Validação de Posição do Canhão (JogoException) */
    @Test(expected = JogoException.class)
    public void testAdicionarCanhaoForaDosLimites() throws JogoException {
        Jogo jogo = new Jogo(800, 600, null);
        // Tenta adicionar canhão fora da tela (x=1000, largura=800)
        jogo.adicionarCanhao(1000, 300);
    }

    /** Teste 2: Cálculo Matemático de Colisão Circular */
    @Test
    public void testVerificarColisao() {
        // Posição Projétil (100, 100) Raio 10
        // Posição Alvo (115, 115) Raio 20
        // Distância: sqrt(15^2 + 15^2) = sqrt(450) ≈ 21.21
        // Soma dos raios: 30
        // Como 21.21 < 30, deve haver colisão.
        
        float px = 100, py = 100, pr = 10;
        float ax = 115, ay = 115, ar = 20;

        double distancia = CalculoUtil.calcularDistancia(px, py, ax, ay);


        assertTrue("Distância menor que a soma dos raios deve indicar colisão", 
                   distancia < (pr + ar));
    }

    /** Teste 3: Integridade da Lista de Alvos (Sincronização) */
    @Test
    public void testRemoverAlvoComSeguranca() {
        Jogo jogo = new Jogo(800, 600, null);
        Alvo alvo = new AlvoComum(100, 100, 800, 600);

        // Adiciona o alvo
        jogo.adicionarAlvo(alvo);
        assertEquals("A lista deve ter 1 alvo", 1, jogo.getAlvos().size());

        // Simula a remoção (como ocorre na colisão)
        jogo.removerAlvo(alvo);

        // Valida se a lista foi atualizada corretamente
        assertEquals("A lista deve estar vazia após a remoção", 0, jogo.getAlvos().size());
        assertFalse("O alvo não deve mais estar na lista", jogo.getAlvos().contains(alvo));
    }

    /** Teste 4: Estresse de Concorrência (Validação de Semáforos) */
    @Test
    public void testAcessoConcorrenteListaAlvos() throws InterruptedException {
        final Jogo jogo = new Jogo(800, 600, null);
        int numeroDeThreads = 10; // 10 threads tentando adicionar ao mesmo tempo
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(numeroDeThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numeroDeThreads);

        for (int i = 0; i < numeroDeThreads; i++) {
            executor.execute(() -> {
                try {
                    startLatch.await(); // Todas as threads esperam aqui o sinal de partida
                    jogo.adicionarAlvo(new AlvoComum(100, 100, 800, 600));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // DÁ A PARTIDA: Todas as 10 threads tentam adicionar ao mesmo tempo!

        // Espera até 5 segundos para todas terminarem
        boolean finalizouOk = finishLatch.await(5, TimeUnit.SECONDS);

        assertTrue("O teste demorou demais, possível Deadlock!", finalizouOk);
        assertEquals("A lista deve ter exatamente 10 alvos, sem erros de concorrência",
                10, jogo.getAlvos().size());

        executor.shutdown();
    }

    /** Teste 5: Anti-Deadlock */
    @Test
    public void testStressColisaoConcorrente() throws InterruptedException {
        final Jogo jogo = new Jogo(800, 600, null);
        int threads = 50;
        final CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                Alvo a = new AlvoComum(100, 100, 800, 600);
                jogo.adicionarAlvo(a);
                // Simula colisão massiva simultânea
                jogo.removerAlvo(a);
                latch.countDown();
            }).start();
        }

        assertTrue("O sistema travou em Deadlock!", latch.await(5, TimeUnit.SECONDS));
    }

}
