package app.dotshermes.data.direct

import android.content.Context
import java.io.File

/** Emplacement privé explicitement exclu des sauvegardes Android. */
fun androidApiVault(context: Context): DirectAccountStorage = EncryptedAccountStorage(
    EncryptedRecords(File(context.noBackupFilesDir, "direct-api"), AndroidRecordKeys()),
)
