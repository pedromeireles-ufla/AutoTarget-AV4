package com.autotarget.game.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import com.autotarget.game.model.Alvo;
import com.autotarget.game.model.Canhao;
import com.autotarget.game.model.Jogo;
import com.autotarget.game.model.Projetil;

import androidx.annotation.Nullable;

/**
 * Componente visual responsável por desenhar a partida.
 * Renderiza campo, alvos, canhões e projéteis em uma thread simples de atualização de tela.
 */
public class GameView extends View implements Runnable {
    // Thread dedicada a solicitar redesenho contínuo da tela do jogo.
    private Thread renderThread;
    private volatile boolean rodando = false;
    // Objeto reutilizado para configurar cores, textos e formas durante o desenho.
    private final Paint paint = new Paint();
    private Jogo jogo;

    /** Inicializa a view customizada que desenha o estado atual do jogo. */
    public GameView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setAntiAlias(true);
        setFocusable(true);
    }

    /** Recebe o modelo do jogo que será usado como fonte para renderização. */
    public void setJogo(Jogo jogo) { this.jogo = jogo; }

    /** Cria a thread de desenho caso ela ainda não esteja ativa. */
    public void iniciarRenderizacao() {
        if (rodando) return;
        rodando = true;
        renderThread = new Thread(this);
        renderThread.start();
    }

    /** Interrompe o loop de renderização quando a tela deixa de precisar ser atualizada. */
    public void pararRenderizacao() {
        rodando = false;
        if (renderThread != null) {
            renderThread.interrupt();
            try { renderThread.join(500); } catch (InterruptedException e) {}
        }
    }

    /** Solicita redesenhos sucessivos enquanto a renderização estiver ativa. */
    @Override
    public void run() {
        while (rodando && !Thread.currentThread().isInterrupted()) {
            postInvalidate();
            try { Thread.sleep(16); } catch (InterruptedException e) {
                rodando = false;
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Desenha todos os elementos visuais do estado atual da partida. */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);
        if (jogo == null) return;

        float meio = getWidth() / 2f;

        // Divide visualmente os sistemas A e B.
        paint.setColor(Color.LTGRAY);
        paint.setStrokeWidth(5);
        canvas.drawLine(meio, 0, meio, getHeight(), paint);

        // Desenha os alvos ativos; vermelho indica alvo comum e azul indica alvo rápido.
        for (Alvo a : jogo.getAlvos()) {
            if (!a.isAtivo()) continue;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            paint.setColor(Color.BLACK);
            canvas.drawCircle(a.getX(), a.getY(), a.getRaio(), paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(a.getVelocidade() < 5.0f ? Color.RED : Color.BLUE);
            canvas.drawCircle(a.getX(), a.getY(), a.getRaio(), paint);
        }

        // Desenha os canhões do Sistema A no lado esquerdo.
        for (Canhao c : jogo.getCanhoesEsquerda()) {
            paint.setColor(Color.BLACK);
            desenharTriangulo(canvas, c.getX(), c.getY(), 40, c.getAngulo());
        }

        // Desenha os canhões do Sistema B no lado direito.
        for (Canhao c : jogo.getCanhoesDireita()) {
            paint.setColor(Color.DKGRAY);
            desenharTriangulo(canvas, c.getX(), c.getY(), 40, c.getAngulo());
        }

        // Desenha apenas projéteis ainda ativos na partida.
        for (Projetil p : jogo.getProjeteis()) {
            if (!p.isAtivo()) continue;
            paint.setColor(Color.MAGENTA);
            canvas.drawCircle(p.getX(), p.getY(), p.getRaio(), paint);
        }
    }

    /** Desenha a ponta do canhão como triângulo rotacionado na direção da mira. */
    private void desenharTriangulo(Canvas canvas, float x, float y, float size, float angulo) {
        Path path = new Path();
        path.moveTo(x + (float) Math.cos(angulo) * size, y + (float) Math.sin(angulo) * size);
        path.lineTo(x + (float) Math.cos(angulo + 2.4) * size, y + (float) Math.sin(angulo + 2.4) * size);
        path.lineTo(x + (float) Math.cos(angulo - 2.4) * size, y + (float) Math.sin(angulo - 2.4) * size);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }
}
