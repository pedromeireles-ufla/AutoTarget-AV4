package com.autotarget.game.view;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.autotarget.game.R;
import com.autotarget.game.model.Partida;
import com.autotarget.game.util.FirebaseRepository;
import com.google.firebase.auth.FirebaseAuth;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RankingActivity extends AppCompatActivity {

    private RecyclerView recyclerRanking;
    private TextView tvCarregando;
    private FirebaseRepository firebaseRepository = FirebaseRepository.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tratamento de sessão
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this, "Sessão expirada. Faça login novamente.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        setContentView(R.layout.activity_ranking);

        ImageButton btnVoltar = findViewById(R.id.btnVoltar);
        btnVoltar.setOnClickListener(v -> finish());

        recyclerRanking = findViewById(R.id.recyclerRanking);
        tvCarregando = findViewById(R.id.tvCarregando);

        // LayoutManager que distribui os itens igualmente pela altura disponível
        recyclerRanking.setLayoutManager(new EqualSpaceLayoutManager());

        carregarTop5();
    }

    private void carregarTop5() {
        firebaseRepository.buscarRanking(new FirebaseRepository.RepositoryCallback<List<Partida>>() {
            @Override
            public void onSuccess(List<Partida> partidas) {
                runOnUiThread(() -> {
                    tvCarregando.setVisibility(View.GONE);
                    recyclerRanking.setVisibility(View.VISIBLE);
                    recyclerRanking.setAdapter(new RankingAdapter(partidas));
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> {
                    tvCarregando.setText("Erro ao carregar ranking.");
                    Toast.makeText(RankingActivity.this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * LayoutManager customizado que distribui os itens igualmente pela altura do RecyclerView,
     * fazendo os 5 jogadores ocuparem toda a tela sem desperdício de espaço.
     */
    static class EqualSpaceLayoutManager extends androidx.recyclerview.widget.LinearLayoutManager {
        EqualSpaceLayoutManager() {
            super(null, VERTICAL, false);
        }

        @Override
        public RecyclerView.LayoutParams generateDefaultLayoutParams() {
            return new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        @Override
        public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
            super.onLayoutChildren(recycler, state);
        }

        @Override
        public boolean canScrollVertically() {
            return false; // sem rolagem: os 5 itens já preenchem tudo
        }

        @Override
        public void measureChildWithMargins(View child, int widthUsed, int heightUsed) {
            if (getItemCount() <= 0) {
                super.measureChildWithMargins(child, widthUsed, heightUsed);
                return;
            }
            // Divide a altura disponível igualmente entre os itens
            int itemHeight = getHeight() / getItemCount();
            int widthSpec = View.MeasureSpec.makeMeasureSpec(
                    getWidth() - widthUsed, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(
                    itemHeight, View.MeasureSpec.EXACTLY);
            child.measure(widthSpec, heightSpec);
        }
    }

    static class RankingAdapter extends RecyclerView.Adapter<RankingAdapter.ViewHolder> {
        private final List<Partida> partidas;
        private final SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());

        // Cores dos avatares por posição
        private final String[] cores = {"#FFD700", "#C0C0C0", "#CD7F32", "#4CAF50", "#2196F3"};

        RankingAdapter(List<Partida> partidas) {
            this.partidas = partidas != null ? partidas : new ArrayList<>();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_ranking, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Partida p = partidas.get(position);

            // Inicial do nome no avatar com cor por posição
            String nome = p.getNomeJogador() != null ? p.getNomeJogador() : "?";
            String inicial = nome.isEmpty() ? "?" : String.valueOf(nome.charAt(0)).toUpperCase();
            holder.tvAvatar.setText(inicial);
            String cor = position < cores.length ? cores[position] : "#9E9E9E";
            holder.tvAvatar.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(cor)));

            // Posição: medalha para top 3, número para os demais
            switch (position) {
                case 0: holder.tvPosicao.setText("🥇"); break;
                case 1: holder.tvPosicao.setText("🥈"); break;
                case 2: holder.tvPosicao.setText("🥉"); break;
                default: holder.tvPosicao.setText(String.valueOf(position + 1)); break;
            }

            holder.tvNome.setText(nome);
            holder.tvData.setText(p.getTimestamp() != null ? sdf.format(p.getTimestamp()) : "");
            holder.tvAlvos.setText(p.getAlvosAbatidos() + " abates");
        }

        @Override
        public int getItemCount() { return partidas.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvAvatar, tvPosicao, tvNome, tvData, tvAlvos;
            ViewHolder(View v) {
                super(v);
                tvAvatar  = v.findViewById(R.id.tvAvatar);
                tvPosicao = v.findViewById(R.id.tvPosicao);
                tvNome    = v.findViewById(R.id.tvNome);
                tvData    = v.findViewById(R.id.tvData);
                tvAlvos   = v.findViewById(R.id.tvAlvos);
            }
        }
    }
}
