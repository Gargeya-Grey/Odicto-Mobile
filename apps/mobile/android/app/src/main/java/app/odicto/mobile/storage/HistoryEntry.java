package app.odicto.mobile.storage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "dictation_history")
public class HistoryEntry {
    @PrimaryKey @NonNull public String id;
    @NonNull public String text;
    @NonNull public String transcript;
    @NonNull public String outcome;
    public long createdAt;
    public HistoryEntry(@NonNull String id, @NonNull String text, @NonNull String transcript, @NonNull String outcome, long createdAt) { this.id=id; this.text=text; this.transcript=transcript; this.outcome=outcome; this.createdAt=createdAt; }
}
