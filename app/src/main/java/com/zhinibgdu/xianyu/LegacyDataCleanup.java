package com.zhinibgdu.xianyu;
import android.content.Context;
import java.io.File;
/** Removes retired demonstration data without touching task records. */
final class LegacyDataCleanup {
    private LegacyDataCleanup() {}
    static void remove(Context context) {
        for (String name : new String[]{"xianyu_learning_v412", "xianyu_learning_v413"}) {
            context.deleteSharedPreferences(name);
            removeFile(context.getFilesDir(), name + "_log.txt");
            removeFile(context.getExternalFilesDir(null), name + "_log.txt");
        }
    }
    private static void removeFile(File dir, String name) {
        if (dir != null) {
            File file = new File(dir, name);
            if (file.exists() && !file.delete()) android.util.Log.w("LegacyDataCleanup", "Unable to remove retired data: " + name);
        }
    }
}
