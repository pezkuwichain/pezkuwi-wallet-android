package io.novafoundation.nova.core_db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val AddBitcoinSupport_74_75 = object : Migration(74, 75) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chains ADD COLUMN isBitcoinBased INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE meta_accounts ADD COLUMN bitcoinPublicKey BLOB")
        db.execSQL("ALTER TABLE meta_accounts ADD COLUMN bitcoinAddress BLOB")
    }
}
