package io.novafoundation.nova.core_db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val AddSolanaSupport_75_76 = object : Migration(75, 76) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chains ADD COLUMN isSolanaBased INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE meta_accounts ADD COLUMN solanaPublicKey BLOB")
        db.execSQL("ALTER TABLE meta_accounts ADD COLUMN solanaAddress BLOB")
    }
}
