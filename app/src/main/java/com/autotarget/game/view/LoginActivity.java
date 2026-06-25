package com.autotarget.game.view;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.autotarget.game.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserProfileChangeRequest;

/**
 * Tela única que alterna entre login e cadastro de conta (Firebase Authentication).
 * O botão "Criar Conta" alterna a UI para o modo de registro, exibindo o campo de
 * nickname e trocando o comportamento do botão principal, sem precisar de uma
 * segunda Activity dedicada ao cadastro.
 */
public class LoginActivity extends AppCompatActivity {
    private EditText etEmail, etPassword, etNickname;
    private Button btnLogin, btnRegister;
    private FirebaseAuth mAuth;
    private boolean modoRegistro = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        mAuth = FirebaseAuth.getInstance();

        if (mAuth.getCurrentUser() != null) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        etEmail    = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etNickname = findViewById(R.id.etNickname);
        btnLogin   = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);

        btnLogin.setOnClickListener(v -> login());

        // Ao clicar em "Criar Conta", alterna para modo registro
        btnRegister.setOnClickListener(v -> {
            if (!modoRegistro) {
                modoRegistro = true;
                etNickname.setVisibility(View.VISIBLE);
                btnRegister.setText("Confirmar Cadastro");
                btnLogin.setText("Cancelar");
                btnLogin.setOnClickListener(v2 -> {
                    modoRegistro = false;
                    etNickname.setVisibility(View.GONE);
                    btnRegister.setText("Criar Conta");
                    btnLogin.setText("Entrar");
                    btnLogin.setOnClickListener(v3 -> login());
                });
            } else {
                register();
            }
        });
    }

    /** Autentica o usuário com e-mail e senha via Firebase Authentication. */
    private void login() {
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Preencha os campos!", Toast.LENGTH_SHORT).show();
            return;
        }

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    } else {
                        String msg = task.getException() != null
                                ? task.getException().getMessage() : "Erro desconhecido";
                        Toast.makeText(LoginActivity.this, "Erro: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** Cria a conta no Firebase Authentication e salva o nickname escolhido no perfil do usuário. */
    private void register() {
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String nickname = etNickname.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Preencha e-mail e senha", Toast.LENGTH_SHORT).show();
            return;
        }
        if (nickname.isEmpty()) {
            Toast.makeText(this, "Escolha um nickname", Toast.LENGTH_SHORT).show();
            return;
        }

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    // Salva o nickname no perfil do Firebase Auth
                    UserProfileChangeRequest profileUpdate = new UserProfileChangeRequest.Builder()
                            .setDisplayName(nickname)
                            .build();
                    authResult.getUser().updateProfile(profileUpdate)
                            .addOnCompleteListener(task -> {
                                Toast.makeText(LoginActivity.this,
                                        "Bem-vindo, " + nickname + "!", Toast.LENGTH_SHORT).show();
                                startActivity(new Intent(LoginActivity.this, MainActivity.class));
                                finish();
                            });
                })
                .addOnFailureListener(e ->
                        Toast.makeText(LoginActivity.this,
                                "Erro ao criar conta: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }
}
