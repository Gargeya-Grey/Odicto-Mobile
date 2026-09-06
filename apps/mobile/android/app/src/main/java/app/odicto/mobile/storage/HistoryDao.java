package app.odicto.mobile.storage;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(HistoryEntry entry);
    @Query("SELECT * FROM dictation_history ORDER BY createdAt DESC") List<HistoryEntry> list();
    @Query("SELECT * FROM dictation_history ORDER BY createdAt DESC LIMIT 30") List<HistoryEntry> recent();
    @Query("DELETE FROM dictation_history") void clear();
}
