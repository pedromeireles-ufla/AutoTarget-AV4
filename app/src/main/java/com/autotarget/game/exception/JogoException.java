package com.autotarget.game.exception;

/**
 * Exceção específica do domínio do jogo.
 * É usada para sinalizar erros controlados nas regras de execução do AutoTarget.
 */
public class JogoException extends Exception {
    
    // Construtor da Exceção: Recebe a mensagem de erro que será exibida na UI
    /**
     * Recebe a mensagem específica do erro e a repassa para a classe base Exception.
     */
    /**
     * Recebe a mensagem que será exibida quando uma regra do jogo for violada.
     */
    public JogoException(String mensagem) {
        super(mensagem); // Repassa a mensagem para a classe pai (Exception)
    }
}
