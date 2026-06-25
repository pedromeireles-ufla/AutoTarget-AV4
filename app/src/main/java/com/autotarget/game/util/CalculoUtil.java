package com.autotarget.game.util;

/**
 * Funções matemáticas simples reutilizadas pelo jogo.
 * Centraliza cálculos geométricos para evitar duplicação de fórmulas.
 */
public class CalculoUtil {

    /**
     * Calcula a distância euclidiana entre dois pontos (x1, y1) e (x2, y2), usando o
     * Teorema de Pitágoras. Usada quando o valor real da distância é necessário
     * (ex.: verificar se um projétil está perto o bastante de um alvo para acertá-lo).
     */
    public static double calcularDistancia(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Calcula a distância ao quadrado entre dois pontos, sem extrair a raiz quadrada.
     * Mais eficiente quando o objetivo é apenas comparar proximidade entre pontos
     * (ex.: escolher o alvo mais próximo), já que a ordem relativa das distâncias
     * se mantém mesmo sem a raiz.
     */
    public static double calcularDistanciaQuadrada(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (dx * dx + dy * dy);
    }
}
