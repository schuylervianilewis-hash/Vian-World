package shura.home.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import shura.home.helpers.Converters
import shura.home.interfaces.AppLaunchersDao
import shura.home.interfaces.HiddenIconsDao
import shura.home.interfaces.HomeScreenGridItemsDao
import shura.home.interfaces.LockedAppsDao
import shura.home.models.AppLauncher
import shura.home.models.HiddenIcon
import shura.home.models.HomeScreenGridItem
import shura.home.models.LockedApp

@Database(
    entities = [AppLauncher::class, HomeScreenGridItem::class, HiddenIcon::class, LockedApp::class],
    version = 6
)
@TypeConverters(Converters::class)
abstract class AppsDatabase : RoomDatabase() {

    abstract fun AppLaunchersDao(): AppLaunchersDao

    abstract fun HomeScreenGridItemsDao(): HomeScreenGridItemsDao

    abstract fun HiddenIconsDao(): HiddenIconsDao

    abstract fun LockedAppsDao(): LockedAppsDao

    companion object {
        private var db: AppsDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `locked_apps` (`packageName` TEXT NOT NULL, `title` TEXT NOT NULL, `lockedAt` INTEGER NOT NULL, PRIMARY KEY(`packageName`))"
                )
            }
        }

        fun getInstance(context: Context): AppsDatabase {
            if (db == null) {
                synchronized(AppsDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(
                            context.applicationContext,
                            AppsDatabase::class.java,
                            "apps.db"
                        )
                            .addMigrations(MIGRATION_5_6)
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
