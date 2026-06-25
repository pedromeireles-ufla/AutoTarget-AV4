package com.autotarget.game.util;

/**
 * Funções matemáticas simples reutilizadas pelo jogo.
 * Centraliza cálculos geométricos para evitar duplicação de fórmulas.
 */
public class CalculoUtil {

    /**
     * Calcula a distância euclidiana entre dois pontos (x1, y1) e (x2, y2).
     */
    /** Calcula a distância real entre dois pontos usando Pitágoras. */
    public static double calcularDistancia(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Calcula a distância ao quadrado entre dois pontos.
     * Mais eficiente para comparações pois evita o cálculo da raiz quadrada.
     */
    /** Calcula a distância ao quadrado, útil quando só é preciso comparar proximidade. */
    public static double calcularDistanciaQuadrada(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (dx * dx + dy * dy);
    }
}
