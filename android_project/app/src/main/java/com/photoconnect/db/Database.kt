package com.photoconnect.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.*
import com.photoconnect.model.EventItem
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
    val favoriteCount: Int = 0,
    val viewerHasFavorited: Boolean = false,
    val postCount: Int = 0,
    val activePostCount: Int = 0,
    val postReach: Int = 0,
    val rankingScore: Double = 0.0,
    val isTopTaker: Boolean = false,
    val trustStage: String = "unverified",
    val trustLabel: String = "Unverified",
    val identityVerified: Boolean = false,
    val portfolioVerified: Boolean = false,
    val socialVerified: Boolean = false,
    val completedBookingCount: Int = 0,
    val endorsementCount: Int = 0,
    val proximityLabel: String? = null,
    val distanceKm: Double? = null,
    val searchDistanceKm: Double? = null,
    val deviceDistanceKm: Double? = null,
    val isAvailable: Int? = null,
    val availabilityStatus: String? = null,
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
    isFeatured = isFeatured,
    favoriteCount = favoriteCount,
    viewerHasFavorited = viewerHasFavorited,
    postCount = postCount,
    activePostCount = activePostCount,
    postReach = postReach,
    rankingScore = rankingScore,
    isTopTaker = isTopTaker,
    trustStage = trustStage,
    trustLabel = trustLabel,
    identityVerified = identityVerified,
    portfolioVerified = portfolioVerified,
    socialVerified = socialVerified,
    completedBookingCount = completedBookingCount,
    endorsementCount = endorsementCount,
    proximityLabel = proximityLabel,
    distanceKm = distanceKm,
    searchDistanceKm = searchDistanceKm,
    deviceDistanceKm = deviceDistanceKm,
    isAvailable = isAvailable,
    availabilityStatus = availabilityStatus
)

fun Taker.toEntity() = TakerEntity(
    id = id,
    fullName = fullName,
    phone = phone.orEmpty(),
    email = email,
    pincode = pincode,
    area = area,
    city = city,
    state = state,
    serviceTypes = offeredServices.joinToString(","),
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
    isFeatured = isFeatured,
    favoriteCount = favoriteCount,
    viewerHasFavorited = viewerHasFavorited,
    postCount = postCount,
    activePostCount = activePostCount,
    postReach = postReach,
    rankingScore = rankingScore,
    isTopTaker = isTopTaker,
    trustStage = trustStage,
    trustLabel = trustLabel,
    identityVerified = identityVerified,
    portfolioVerified = portfolioVerified,
    socialVerified = socialVerified,
    completedBookingCount = completedBookingCount,
    endorsementCount = endorsementCount,
    proximityLabel = proximityLabel,
    distanceKm = distanceKm,
    searchDistanceKm = searchDistanceKm,
    deviceDistanceKm = deviceDistanceKm,
    isAvailable = isAvailable,
    availabilityStatus = availabilityStatus,
    cachedAt = System.currentTimeMillis(),
)

@Entity(
    tableName = "availability",
    primaryKeys = ["takerId","date","dayPart"],
    indices = [Index("date"), Index("status"), Index(value = ["takerId", "status"])]
)
data class AvailabilityEntity(
    val takerId: Int,
    val date: String,
    val status: String,
    val dayPart: String = "full_day",
)

@Entity(
    tableName = "pending_availability_overrides",
    primaryKeys = ["takerId", "date", "dayPart"],
    indices = [
        Index("takerId"),
        Index("expiresAt"),
        Index(value = ["takerId", "date"])
    ]
)
data class PendingAvailabilityOverrideEntity(
    val takerId: Int,
    val date: String,
    val status: String,
    val dayPart: String = "full_day",
    val expiresAt: Long,
)

@Entity(
    tableName = "bookings",
    indices = [
        Index("clientId"),
        Index("takerId"),
        Index("bookingDate"),
        Index("dayPart"),
        Index(value = ["clientId", "status", "bookingDate"]),
        Index(value = ["takerId", "status", "bookingDate"])
    ]
)
data class BookingEntity(
    @PrimaryKey val id: Int,
    val clientId: Int, val takerId: Int, val takerName: String? = null,
    val clientName: String? = null,
    val bookingDate: String, val serviceType: String, val dayPart: String = "full_day",
    val eventLocation: String? = null, val notes: String? = null,
    val status: String = "Pending",
    val clientVerificationStage: String = "unverified",
    val clientVerificationLabel: String = "Client not verified",
)

@Entity(
    tableName = "events",
    indices = [
        Index("bookingId"),
        Index("createdByRole"),
        Index("createdById"),
        Index("clientId"),
        Index("takerId"),
        Index("eventDate"),
        Index("dayPart"),
        Index("status"),
        Index(value = ["createdByRole", "createdById", "eventDate"]),
        Index(value = ["clientId", "eventDate"]),
        Index(value = ["takerId", "eventDate"])
    ]
)
data class EventEntity(
    @PrimaryKey val id: Int,
    val bookingId: Int? = null,
    val createdByRole: String,
    val createdById: Int,
    val clientId: Int? = null,
    val takerId: Int? = null,
    val title: String,
    val eventDate: String,
    val dayPart: String = "full_day",
    val serviceType: String? = null,
    val location: String? = null,
    val clientName: String? = null,
    val clientPhone: String? = null,
    val takerName: String? = null,
    val takerPhone: String? = null,
    val totalAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val balanceAmount: Double = 0.0,
    val notes: String? = null,
    val status: String = "Upcoming",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

fun EventItem.toEntity() = EventEntity(
    id = id,
    bookingId = bookingId,
    createdByRole = createdByRole,
    createdById = createdById,
    clientId = clientId,
    takerId = takerId,
    title = title,
    eventDate = eventDate,
    dayPart = dayPart,
    serviceType = serviceType,
    location = location,
    clientName = clientName,
    clientPhone = clientPhone,
    takerName = takerName,
    takerPhone = takerPhone,
    totalAmount = totalAmount,
    paidAmount = paidAmount,
    balanceAmount = balanceAmount,
    notes = notes,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
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
    indices = [
        Index("userId"),
        Index("takerId")
    ]
)
data class FavoriteEntity(
    val userId: Int,
    val takerId: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "help_tickets")
data class HelpTicketEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao interface TakerDao {
    @Query("SELECT * FROM takers ORDER BY isFeatured DESC, avgRating DESC")
    fun getAll(): LiveData<List<TakerEntity>>

    @Query("SELECT * FROM takers WHERE isFeatured=1 ORDER BY avgRating DESC LIMIT 10")
    fun getFeatured(): LiveData<List<TakerEntity>>

    @Query("SELECT * FROM takers WHERE id = :id")
    fun getById(id: Int): LiveData<TakerEntity?>

    @Query("SELECT * FROM takers WHERE id = :id LIMIT 1")
    suspend fun getByIdSync(id: Int): TakerEntity?

    @Query("""SELECT * FROM takers WHERE
        (:city IS NULL OR city LIKE '%'||:city||'%' OR area LIKE '%'||:city||'%' OR pincode=:city)
        AND (:serviceType IS NULL OR serviceTypes LIKE '%'||:serviceType||'%')
        ORDER BY isFeatured DESC, avgRating DESC""")
    fun search(city: String?, serviceType: String?): LiveData<List<TakerEntity>>

    @Query("""SELECT * FROM takers WHERE
        (:city IS NULL OR city LIKE '%'||:city||'%' OR area LIKE '%'||:city||'%' OR pincode=:city)
        AND (:serviceType IS NULL OR serviceTypes LIKE '%'||:serviceType||'%')
        ORDER BY cachedAt DESC, isFeatured DESC, avgRating DESC
        LIMIT :limit""")
    suspend fun searchOnce(city: String?, serviceType: String?, limit: Int = 60): List<TakerEntity>

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
    @Query("DELETE FROM availability WHERE takerId=:takerId AND date=:date AND dayPart='full_day'")
    suspend fun deleteFullDay(takerId: Int, date: String)
    @Query("DELETE FROM availability WHERE takerId=:takerId AND date=:date")
    suspend fun deleteDate(takerId: Int, date: String)
    @Query("DELETE FROM availability WHERE takerId=:takerId AND date=:date AND dayPart IN ('first_half','second_half')")
    suspend fun deleteHalfDays(takerId: Int, date: String)
    @Query("DELETE FROM availability WHERE takerId=:takerId AND date=:date AND dayPart != :currentDayPart")
    suspend fun deleteOtherHalf(takerId: Int, date: String, currentDayPart: String)
    @Query("DELETE FROM availability WHERE takerId=:takerId") suspend fun deleteForTaker(takerId: Int)

    /** `ym` format yyyy-MM — used when syncing a single month without wiping others. */
    @Query("DELETE FROM availability WHERE takerId=:takerId AND substr(`date`, 1, 7) = :ym")
    suspend fun deleteMonth(takerId: Int, ym: String)
}

@Dao interface PendingAvailabilityOverrideDao {
    @Upsert suspend fun upsertAll(list: List<PendingAvailabilityOverrideEntity>)

    @Query("DELETE FROM pending_availability_overrides WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM pending_availability_overrides WHERE takerId=:takerId AND date=:date")
    suspend fun deleteDate(takerId: Int, date: String)

    @Query("SELECT * FROM pending_availability_overrides WHERE takerId=:takerId AND expiresAt > :now")
    suspend fun activeForTaker(takerId: Int, now: Long): List<PendingAvailabilityOverrideEntity>

    @Query("SELECT * FROM pending_availability_overrides WHERE takerId=:takerId AND date >= :start AND date < :end AND expiresAt > :now")
    suspend fun activeForMonth(takerId: Int, start: String, end: String, now: Long): List<PendingAvailabilityOverrideEntity>
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

@Dao interface EventDao {
    @Query("""
        SELECT * FROM events
        WHERE (createdByRole=:role AND createdById=:id)
           OR (:role='client' AND clientId=:id)
           OR (:role='taker' AND takerId=:id)
           OR (:role='taker' AND :clientProfileId > 0 AND clientId=:clientProfileId)
        ORDER BY
            CASE WHEN status IN ('Upcoming','Pending','Confirmed') THEN 0 ELSE 1 END,
            CASE WHEN status IN ('Upcoming','Pending','Confirmed') THEN eventDate END ASC,
            CASE WHEN status NOT IN ('Upcoming','Pending','Confirmed') THEN eventDate END DESC,
            id DESC
    """)
    fun getForActor(role: String, id: Int, clientProfileId: Int = 0): LiveData<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id=:eventId LIMIT 1")
    suspend fun getByIdOnce(eventId: Int): EventEntity?
    @Upsert suspend fun upsert(event: EventEntity)
    @Upsert suspend fun upsertAll(events: List<EventEntity>)
    @Query("UPDATE events SET status=:status, updatedAt=:updatedAt WHERE bookingId=:bookingId")
    suspend fun updateStatusForBooking(bookingId: Int, status: String, updatedAt: String)
    @Query("DELETE FROM events WHERE id=:eventId") suspend fun deleteById(eventId: Int)
    @Query("UPDATE events SET notes=NULL")
    suspend fun clearOfflineNotes()
    @Query("""
        DELETE FROM events
        WHERE (createdByRole=:role AND createdById=:id)
           OR (:role='client' AND clientId=:id)
           OR (:role='taker' AND takerId=:id)
           OR (:role='taker' AND :clientProfileId > 0 AND clientId=:clientProfileId)
    """)
    suspend fun deleteForActor(role: String, id: Int, clientProfileId: Int = 0)
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
    @Query("SELECT * FROM favorites WHERE userId = :userId")
    suspend fun getFavoritesOnce(userId: Int): List<FavoriteEntity>
    @Upsert suspend fun add(fav: FavoriteEntity)
    @Upsert suspend fun addAll(favorites: List<FavoriteEntity>)
    @Query("DELETE FROM favorites WHERE userId = :userId AND takerId = :takerId") suspend fun remove(userId: Int, takerId: Int)
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE userId = :userId AND takerId = :takerId)")
    fun isFavorite(userId: Int, takerId: Int): LiveData<Boolean>
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE userId = :userId AND takerId = :takerId)")
    suspend fun isFavoriteOnce(userId: Int, takerId: Int): Boolean
}

@Dao
interface HelpTicketDao {
    @Query("SELECT * FROM help_tickets ORDER BY timestamp DESC")
    fun getAllTickets(): LiveData<List<HelpTicketEntity>>

    @Insert
    suspend fun insert(ticket: HelpTicketEntity)

    @Query("DELETE FROM help_tickets")
    suspend fun deleteAll()
}

@Database(
    entities=[
        TakerEntity::class,
        AvailabilityEntity::class,
        PendingAvailabilityOverrideEntity::class,
        BookingEntity::class,
        EventEntity::class,
        ReviewEntity::class,
        FavoriteEntity::class,
        HelpTicketEntity::class
    ],
    version=14,
    exportSchema=true
)
abstract class PhotoConnectDatabase : RoomDatabase() {
    abstract fun takerDao(): TakerDao
    abstract fun availabilityDao(): AvailabilityDao
    abstract fun pendingAvailabilityOverrideDao(): PendingAvailabilityOverrideDao
    abstract fun bookingDao(): BookingDao
    abstract fun eventDao(): EventDao
    abstract fun reviewDao(): ReviewDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun helpTicketDao(): HelpTicketDao
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

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `events` (
                `id` INTEGER NOT NULL,
                `bookingId` INTEGER,
                `createdByRole` TEXT NOT NULL,
                `createdById` INTEGER NOT NULL,
                `clientId` INTEGER,
                `takerId` INTEGER,
                `title` TEXT NOT NULL,
                `eventDate` TEXT NOT NULL,
                `serviceType` TEXT,
                `location` TEXT,
                `clientName` TEXT,
                `clientPhone` TEXT,
                `takerName` TEXT,
                `totalAmount` REAL NOT NULL,
                `paidAmount` REAL NOT NULL,
                `balanceAmount` REAL NOT NULL,
                `notes` TEXT,
                `status` TEXT NOT NULL,
                `createdAt` TEXT,
                `updatedAt` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_bookingId` ON `events` (`bookingId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_createdByRole` ON `events` (`createdByRole`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_createdById` ON `events` (`createdById`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_clientId` ON `events` (`clientId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_takerId` ON `events` (`takerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_eventDate` ON `events` (`eventDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_status` ON `events` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_createdByRole_createdById_eventDate` ON `events` (`createdByRole`, `createdById`, `eventDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_clientId_eventDate` ON `events` (`clientId`, `eventDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_takerId_eventDate` ON `events` (`takerId`, `eventDate`)")
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `availability_new` (
                `takerId` INTEGER NOT NULL,
                `date` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `dayPart` TEXT NOT NULL DEFAULT 'full_day',
                PRIMARY KEY(`takerId`, `date`, `dayPart`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `availability_new` (`takerId`, `date`, `status`, `dayPart`)
            SELECT `takerId`, `date`, `status`, 'full_day'
            FROM `availability`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `availability`")
        db.execSQL("ALTER TABLE `availability_new` RENAME TO `availability`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_availability_date` ON `availability` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_availability_status` ON `availability` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_availability_takerId_status` ON `availability` (`takerId`, `status`)")

        db.execSQL("ALTER TABLE `bookings` ADD COLUMN `dayPart` TEXT NOT NULL DEFAULT 'full_day'")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookings_dayPart` ON `bookings` (`dayPart`)")

        db.execSQL("ALTER TABLE `events` ADD COLUMN `dayPart` TEXT NOT NULL DEFAULT 'full_day'")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_dayPart` ON `events` (`dayPart`)")
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `help_tickets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `message` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookings` ADD COLUMN `clientVerificationStage` TEXT NOT NULL DEFAULT 'unverified'")
        db.execSQL("ALTER TABLE `bookings` ADD COLUMN `clientVerificationLabel` TEXT NOT NULL DEFAULT 'Client not verified'")
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `favoriteCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `viewerHasFavorited` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `postCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `activePostCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `postReach` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `rankingScore` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `isTopTaker` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `trustStage` TEXT NOT NULL DEFAULT 'unverified'")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `trustLabel` TEXT NOT NULL DEFAULT 'Unverified'")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `identityVerified` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `portfolioVerified` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `socialVerified` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `completedBookingCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `endorsementCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `proximityLabel` TEXT")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `distanceKm` REAL")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `searchDistanceKm` REAL")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `deviceDistanceKm` REAL")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `isAvailable` INTEGER")
        db.execSQL("ALTER TABLE `takers` ADD COLUMN `availabilityStatus` TEXT")
    }
}

private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_availability_overrides` (
                `takerId` INTEGER NOT NULL,
                `date` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `dayPart` TEXT NOT NULL DEFAULT 'full_day',
                `expiresAt` INTEGER NOT NULL,
                PRIMARY KEY(`takerId`, `date`, `dayPart`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_availability_overrides_takerId` ON `pending_availability_overrides` (`takerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_availability_overrides_expiresAt` ON `pending_availability_overrides` (`expiresAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_availability_overrides_takerId_date` ON `pending_availability_overrides` (`takerId`, `date`)")
    }
}

private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `events` ADD COLUMN `takerPhone` TEXT")
    }
}

@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): PhotoConnectDatabase =
        Room.databaseBuilder(ctx, PhotoConnectDatabase::class.java, "pc_v7.db")
            .addMigrations(MIGRATION_5_6)
            .addMigrations(MIGRATION_6_7)
            .addMigrations(MIGRATION_7_8)
            .addMigrations(MIGRATION_8_9)
            .addMigrations(MIGRATION_9_10)
            .addMigrations(MIGRATION_10_11)
            .addMigrations(MIGRATION_11_12)
            .addMigrations(MIGRATION_12_13)
            .addMigrations(MIGRATION_13_14)
            // Older installs may still have pre-v5 schemas. Prefer upgrading v5 cleanly,
            // but allow a reset from legacy schemas instead of crashing on app launch.
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()

    @Provides fun takerDao(db: PhotoConnectDatabase)        = db.takerDao()
    @Provides fun availabilityDao(db: PhotoConnectDatabase) = db.availabilityDao()
    @Provides fun pendingAvailabilityOverrideDao(db: PhotoConnectDatabase) = db.pendingAvailabilityOverrideDao()
    @Provides fun bookingDao(db: PhotoConnectDatabase)      = db.bookingDao()
    @Provides fun eventDao(db: PhotoConnectDatabase)        = db.eventDao()
    @Provides fun reviewDao(db: PhotoConnectDatabase)       = db.reviewDao()
    @Provides fun favoriteDao(db: PhotoConnectDatabase)     = db.favoriteDao()
    @Provides fun helpTicketDao(db: PhotoConnectDatabase)   = db.helpTicketDao()
}
