package io.novafoundation.nova.core_db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val AddTronSupport_73_74 = object : Migration(73, 74) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chains ADD COLUMN isTronBased INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE meta_accounts ADD COLUMN tronPublicKey BLOB")
        db.execSQL("ALTER TABLE meta_accounts ADD COLUMN tronAddress BLOB")
    }
}
