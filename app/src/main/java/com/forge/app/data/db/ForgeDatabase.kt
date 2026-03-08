package com.forge.app.data.db

import android.content.Context
import androidx.room.*
import com.forge.app.data.models.BuildStatus
import com.forge.app.data.models.ForgeProject
import kotlinx.coroutines.flow.Flow

// --- DAO ---
@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastModifiedAt DESC")
    fun getAllProjects(): Flow<List<ForgeProject>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: String): ForgeProject?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ForgeProject)

    @Update
    suspend fun updateProject(project: ForgeProject)

    @Delete
    suspend fun deleteProject(project: ForgeProject)

    @Query("UPDATE projects SET buildStatus = :status, lastModifiedAt = :time WHERE id = :id")
    suspend fun updateBuildStatus(id: String, status: BuildStatus, time: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET lastBuiltAt = :time, buildStatus = :status WHERE id = :id")
    suspend fun markBuilt(id: String, time: Long, status: BuildStatus)
}

// --- Converters ---
class Converters {
    @TypeConverter
    fun fromBuildStatus(status: BuildStatus): String = status.name

    @TypeConverter
    fun toBuildStatus(name: String): BuildStatus = BuildStatus.valueOf(name)
}

// --- Database ---
@Database(
    entities = [ForgeProject::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var INSTANCE: ForgeDatabase? = null

        fun getInstance(context: Context): ForgeDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    ForgeDatabase::class.java,
                    "forge_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}