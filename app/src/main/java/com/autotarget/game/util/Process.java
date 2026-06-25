package com.autotarget.game.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Utilitário para tentar aplicar afinidade de CPU à thread atual durante os benchmarks.
 * Primeiro tenta a API nativa disponível no Android e, se necessário, usa `taskset` como alternativa.
 */
public final class Process {
    private static final String TAG = "AV2_ESCALONABILIDADE";

    private Process() {}

    /** Tenta fixar a thread atual em um conjunto de núcleos e retorna se a operação funcionou. */
    public static boolean setThreadAffinityMask(int mask) {
        if (tryAndroidHiddenApi(mask)) {
            Log.e(TAG, "Afinidade aplicada por API interna. mask=" + mask);
            return true;
        }

        boolean tasksetOk = tryTasksetWithTimeout(mask);
        if (!tasksetOk) {
            Log.e(TAG, "Afinidade não aplicada neste dispositivo/emulador. A medição continuará sem travar. mask=" + mask);
        }
        return tasksetOk;
    }

    /** Primeiro tenta usar APIs internas do Android para aplicar afinidade de CPU. */
    private static boolean tryAndroidHiddenApi(int mask) {
        try {
            Class<?> processClass = Class.forName("android.os.Process");
            Method method = processClass.getDeclaredMethod("setThreadAffinityMask", int.class);
            method.setAccessible(true);
            method.invoke(null, mask);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Como alternativa, tenta executar o comando taskset com timeout curto. */
    private static boolean tryTasksetWithTimeout(int mask) {
        java.lang.Process proc = null;
        try {
            int tid = android.os.Process.myTid();
            String hexMask = Integer.toHexString(mask);
            String command = String.format(Locale.US, "taskset -p %s %d", hexMask, tid);
            proc = Runtime.getRuntime().exec(command);

            boolean finished = proc.waitFor(150, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                Log.e(TAG, "taskset excedeu timeout de 150ms e foi interrompido.");
                return false;
            }

            if (proc.exitValue() == 0) {
                return true;
            }

            StringBuilder erro = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    erro.append(line).append('\n');
                }
            }
            Log.e(TAG, "taskset retornou erro. exit=" + proc.exitValue() + " erro=" + erro);
            return false;
        } catch (Throwable e) {
            Log.e(TAG, "Afinidade de CPU não suportada neste ambiente; seguindo sem afinidade real.", e);
            return false;
        } finally {
            if (proc != null) {
                proc.destroy();
            }
        }
    }
}