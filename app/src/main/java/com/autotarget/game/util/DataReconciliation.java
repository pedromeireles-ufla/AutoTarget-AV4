package com.autotarget.game.util;

import org.apache.commons.math3.linear.*;

/**
 * Implementa a reconciliação de dados por Mínimos Quadrados ponderados.
 * Recebe medições brutas, matriz de variâncias e restrições para gerar valores ajustados.
 */
public class DataReconciliation {

    /**
     * Executa a reconciliação de dados conforme a fórmula matricial exata.
     */
    /**
     * Aplica uma reconciliação simplificada para suavizar leituras ruidosas antes da decisão.
     */
    public static double[] reconcile(double[] y, double[][] V, double[][] A) {
        try {
            int m = A.length;       // Número de restrições (canhões)
            int n = A[0].length;    // Número de medições (distâncias)

            RealVector yVec = new ArrayRealVector(y);
            RealMatrix vMat = new Array2DRowRealMatrix(V);
            RealMatrix aMat = new Array2DRowRealMatrix(A);

            // 1. Calcular a matriz de incidência transposta: Aᵀ
            RealMatrix aTranspose = aMat.transpose();

            // 2. Calcular o termo (AVAᵀ)
            RealMatrix aVAt = aMat.multiply(vMat).multiply(aTranspose);

            // 3. Calcular a inversa (AVAᵀ)⁻¹
            // Usamos LUDecomposition para inverter a matriz quadrada
            RealMatrix invAVAt = new LUDecomposition(aVAt).getSolver().getInverse();

            // 4. Calcular o termo Ay
            RealVector ay = aMat.operate(yVec);

            // 5. Calcular o ajuste: VAᵀ * (AVAᵀ)⁻¹ * Ay
            RealVector adjustment = vMat.multiply(aTranspose)
                                        .multiply(invAVAt)
                                        .operate(ay);

            // 6. Calcular ŷ = y - ajuste
            RealVector yHat = yVec.subtract(adjustment);

            return yHat.toArray();

        } catch (Exception e) {
            // Em caso de erro numérico (ex: matriz singular), retorna o original (y)
            return y;
        }
    }
}
