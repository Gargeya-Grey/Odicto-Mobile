package app.odicto.mobile.storage;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {HistoryEntry.class}, version = 1, exportSchema = false)
public abstract class HistoryDatabase extends RoomDatabase {
    public abstract HistoryDao history();
    private static volatile HistoryDatabase instance;
    public static HistoryDatabase open(Context context) {
        if (instance == null) synchronized (HistoryDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), HistoryDatabase.class, "odicto-history.db").build();
        }
        return instance;
    }
}
