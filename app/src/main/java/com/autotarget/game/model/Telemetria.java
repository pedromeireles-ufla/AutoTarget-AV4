package com.autotarget.game.model;

import com.google.firebase.firestore.ServerTimestamp;
import java.util.Date;

/**
 * Modelo de dados para registrar a telemetria de temperatura no Firebase.
 * Atende ao requisito 6.3.2 d) da AV3.
 */
public class Telemetria {
    private float temperatura;
    private String estadoSistema; // "NORMAL" ou "SUPERAQUECIDO"
    
    @ServerTimestamp
    private Date timestamp;

    public Telemetria() {
    }

    public Telemetria(float temperatura, String estadoSistema) {
        this.temperatura = temperatura;
        this.estadoSistema = estadoSistema;
    }

    public float getTemperatura() { return temperatura; }
    public void setTemperatura(float temperatura) { this.temperatura = temperatura; }

    public String getEstadoSistema() { return estadoSistema; }
    public void setEstadoSistema(String estadoSistema) { this.estadoSistema = estadoSistema; }

    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }
}
