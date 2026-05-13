package com.photoconnect.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.*
import com.photoconnect.model.Taker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Entity(
    tableName = "takers",
    indices = [
        Index("city"),
        Index("area"),
        Index("pincode"),
        Index("serviceTypes"),
        Index(value = ["serviceTypes", "city"])
    ]
)
data class TakerEntity(
    @PrimaryKey val id: Int,
    val fullName: String, val phone: String, val email: String,
    val pincode: String, val area: String, val city: String, val state: String,
    val serviceTypes: String, val yearsExperience: Int = 0,
    val languages: String? = null, val instagramUrl: String? = null,
    val youtubeUrl: String? = null, val portfolioUrl: String? = null,
    val profileImageUrl: String? = null,
    val profileThumbUrl: String? = null,
    val profileImageScope: String = "public",
    val avgRating: Float = 0f,
    val reviewCount: Int = 0, val isFeatured: Int = 0,
    val cachedAt: Long = System.currentTimeMillis(),
)

fun TakerEntity.toModel() = Taker(
    id = id,
    fullName = fullName,
    phone = phone,
    email = email,
    pincode = pincode,
    area = area,
    city = city,
    state = state,
    serviceTypes = serviceTypes.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    yearsExperience = yearsExperience,
    languages = languages,
    instagramUrl = instagramUrl,
    youtubeUrl = youtubeUrl,
    portfolioUrl = portfolioUrl,
    profileImageUrl = profileImageUrl,
    profileThumbUrl = profileThumbUrl,
    profileImageScope = profileImageScope,
    avgRating = avgRating,
    reviewCount = reviewCount,
    isFeatured = isFeatured
)

@Entity(
    tableName = "availability",
    primaryKeys = ["takerId","date"],
    indices = [Index("date"), Index("status"), Index(value = ["takerId", "status"])]
)
data class AvailabilityEntity(val takerId: Int, val date: String, val status: String)

@Entity(
    tableName = "bookings",
    indices = [
        Index("clientId"),
        Index("takerId"),
        Index("bookingDate"),
        Index(value = ["clientId", "status", "bookingDate"]),
        Index(value = ["takerId", "status", "bookingDate"])
    ]
)
data class BookingEntity(
    @PrimaryKey val id: Int,
    val clientId: Int, val takerId: Int, val takerName: String? = null,
    val clientName: String? = null,
    val bookingDate: String, val serviceType: String,
    val eventLocation: String? = null, val notes: String? = null,
    val status: String = "Pending",
)

@Entity(tableName = "reviews", indices = [Index("takerId"), Index("clientId")])
data class ReviewEntity(
    @PrimaryKey val id: Int,
    val takerId: Int, val clientId: Int, val clientName: String,
    val rating: Int, val comment: String? = null, val createdAt: String,
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["userId", "takerId"],
    indices = [Index("userId"), Index("takerId")]
)
data class FavoriteEntity(val userId: Int, val takerId: Int, val createdAt: Long = System.currentTimeMillis())

@Dao interface TakerDao {
    @Query("SELECT * FROM takers ORDER BY isFeatured DESC, avgRating DESC")
    fun getAll(): LiveData<List<TakerEntity>>

    @Query("SELECT * FROM takers WHERE isFeatured=1 ORDER BY avgRating DESC LIMIT 10")
    fun getFeatured(): LiveData<List<TakerEntity>>

    @Query("SELECT * FROM takers WHERE id = :id")
    fun getById(id: Int): LiveData<TakerEntity?>

    @Query("""SELECT * FROM takers WHERE
        (:city IS NULL OR city LIKE '%'||:city||'%' OR area LIKE '%'||:city||'%' OR pincode=:city)
        AND (:serviceType IS NULL OR serviceTypes LIKE '%'||:serviceType||'%')
        ORDER BY isFeatured DESC, avgRating DESC""")
    fun search(city: String?, serviceType: String?): LiveData<List<TakerEntity>>

    @Upsert suspend fun upsertAll(list: List<TakerEntity>)
    @Query("""
        UPDATE takers
        SET profileImageUrl = :profileImageUrl,
            profileThumbUrl = :profileThumbUrl,
            profileImageScope = :profileImageScope,
            cachedAt = :cachedAt
        WHERE id = :id
    """)
    suspend fun updateProfileImage(
        id: Int,
        profileImageUrl: String?,
        profileThumbUrl: String?,
        profileImageScope: String,
        cachedAt: Long = System.currentTimeMillis(),
    )
    @Query("DELETE FROM takers") suspend fun deleteAll()
}

@Dao interface AvailabilityDao {
    @Query("SELECT * FROM availability WHERE takerId=:takerId ORDER BY date")
    fun getForTaker(takerId: Int): LiveData<List<AvailabilityEntity>>
    @Upsert suspend fun upsertAll(list: List<AvailabilityEntity>)
    @Query("DELETE FROM availability WHERE takerId=:takerId") suspend fun deleteForTaker(takerId: Int)

    /** `ym` format yyyy-MM — used when syncing a single month without wiping others. */
    @Query("DELETE FROM availability WHERE takerId=:takerId AND substr(`date`, 1, 7) = :ym")
    suspend fun deleteMonth(takerId: Int, ym: String)
}

@Dao interface BookingDao {
    @Query("""
        SELECT * FROM bookings
        WHERE clientId=:id
        ORDER BY
            CASE WHEN status IN ('Pending','Confirmed') THEN 0 ELSE 1 END,
            CASE WHEN status IN ('Pending','Confirmed') THEN bookingDate END ASC,
            CASE WHEN status NOT IN ('Pending','Confirmed') THEN bookingDate END DESC
    """)
    fun getByClient(id: Int): LiveData<List<BookingEntity>>

    @Query("""
        SELECT * FROM bookings
        WHERE takerId=:id
        ORDER BY
            CASE WHEN status IN ('Pending','Confirmed') THEN 0 ELSE 1 END,
            CASE WHEN status IN ('Pending','Confirmed') THEN bookingDate END ASC,
            CASE WHEN status NOT IN ('Pending','Confirmed') THEN bookingDate END DESC
    """)
    fun getByTaker(id: Int): LiveData<List<BookingEntity>>

    @Upsert suspend fun upsert(b: BookingEntity)
    @Upsert suspend fun upsertAll(list: List<BookingEntity>)
    @Query("UPDATE bookings SET status=:status WHERE id=:bookingId")
    suspend fun updateStatus(bookingId: Int, status: String)
    @Query("DELETE FROM bookings") suspend fun deleteAll()
}

@Dao interface ReviewDao {
    @Query("SELECT * FROM reviews WHERE takerId=:takerId ORDER BY createdAt DESC")
    fun getForTaker(takerId: Int): LiveData<List<ReviewEntity>>
    @Upsert suspend fun upsertAll(list: List<ReviewEntity>)
    @Query("DELETE FROM reviews WHERE takerId=:takerId") suspend fun deleteForTaker(takerId: Int)
}

@Dao interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE userId = :userId")
    fun getFavorites(userId: Int): LiveData<List<FavoriteEntity>>
    @Upsert suspend fun add(fav: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE userId = :userId AND takerId = :takerId") suspend fun remove(userId: Int, takerId: Int)
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE userId = :userId AND takerId = :takerId)")
    fun isFavorite(userId: Int, takerId: Int): LiveData<Boolean>
}

@Database(entities=[TakerEntity::class,AvailabilityEntity::class,BookingEntity::class,ReviewEntity::class,FavoriteEntity::class], version=7, exportSchema=true)
abstract class PhotoConnectDatabase : RoomDatabase() {
    abstract fun takerDao(): TakerDao
    abstract fun availabilityDao(): AvailabilityDao
    abstract fun bookingDao(): BookingDao
    abstract fun reviewDao(): ReviewDao
    abstract fun favoriteDao(): FavoriteDao
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorites` (
                `userId` INTEGER NOT NULL,
                `takerId` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `takerId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_userId` ON `favorites` (`userId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_takerId` ON `favorites` (`takerId`)")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val favoritesExists = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='favorites' LIMIT 1"
        ).use { cursor -> cursor.moveToFirst() }

        if (favoritesExists) {
            db.execSQL("ALTER TABLE favorites RENAME TO favorites_legacy")
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorites` (
                `userId` INTEGER NOT NULL,
                `takerId` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `takerId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_userId` ON `favorites` (`userId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_takerId` ON `favorites` (`takerId`)")

        if (favoritesExists) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO favorites (`userId`, `takerId`, `createdAt`)
                SELECT `userId`, `takerId`,
                       CASE
                           WHEN `createdAt` IS NULL OR `createdAt` <= 0 THEN CAST(strftime('%s','now') AS INTEGER) * 1000
                           ELSE `createdAt`
                       END
                FROM favorites_legacy
                """.trimIndent()
            )
            db.execSQL("DROP TABLE favorites_legacy")
        }
    }
}

@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): PhotoConnectDatabase =
        Room.databaseBuilder(ctx, PhotoConnectDatabase::class.java, "pc_v7.db")
            .addMigrations(MIGRATION_5_6)
            .addMigrations(MIGRATION_6_7)
            // Older installs may still have pre-v5 schemas. Prefer upgrading v5 cleanly,
            // but allow a reset from legacy schemas instead of crashing on app launch.
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4)
            .build()

    @Provides fun takerDao(db: PhotoConnectDatabase)        = db.takerDao()
    @Provides fun availabilityDao(db: PhotoConnectDatabase) = db.availabilityDao()
    @Provides fun bookingDao(db: PhotoConnectDatabase)      = db.bookingDao()
    @Provides fun reviewDao(db: PhotoConnectDatabase)       = db.reviewDao()
    @Provides fun favoriteDao(db: PhotoConnectDatabase)     = db.favoriteDao()
}
