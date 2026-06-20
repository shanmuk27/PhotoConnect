# PhotoConnect

PhotoConnect is an Android + PHP + MySQL project for connecting takers (photographers, videographers, drone operators, LED wall providers) with clients who want to search, review availability, and book services quickly.

## Project Structure

- `android_project/`
  Android Studio project written in Kotlin using MVVM, Repository pattern, Retrofit, Hilt, Room, and Material Design.
- `php_api/`
  PHP API endpoints for registration, login, search, availability, booking, and reviews.
- `database/schema.sql`
  MySQL schema optimized for date, location, and service filtering on Synology.

## Android Architecture

The Android app follows the requested clean structure:

- `app/src/main/java/com/photoconnect/ui/`
- `app/src/main/java/com/photoconnect/viewmodel/`
- `app/src/main/java/com/photoconnect/repository/`
- `app/src/main/java/com/photoconnect/model/`
- `app/src/main/java/com/photoconnect/network/`
- `app/src/main/java/com/photoconnect/db/`

Key user flows already included:

- taker registration with location, service details, social links, and portfolio URL
- client login and guest browsing
- taker accounts automatically linked to a client profile so they can also search and place bookings
- taker search by city, area, pincode, service type, and availability date
- taker detail screen with color-coded availability calendar
- booking flow and booking summary
- booking request management for confirm, cancel, and complete actions
- local Room caching for takers, availability, bookings, and reviews

## Step-by-Step Setup

### 1. Set Up MySQL on Synology

Run the schema once:

```sql
mysql -u root -p < database/schema.sql
```

The schema includes:

- `takers`
- `clients`
- `availability`
- `bookings`
- `reviews`

Performance-focused indexes included:

- location indexes on `pincode`, `city`, `area`, `state`
- service filtering indexes on `service_type`
- availability indexes on `(taker_id, date, status)` and `(date, status, taker_id)`
- booking indexes for client/taker/date lookups
- unique protection against more than one active booking on the same taker/date while still allowing cancelled slots to be reused

If your Synology database already exists from an older version, also run:

```sql
mysql -u root -p photoconnect < database/migrations/2026_04_22_booking_status_logic.sql
```

### 2. Deploy PHP APIs

Upload the contents of `php_api/` to:

`https://supriyadigitals.store/phpapp/`

Before uploading, review `php_api/config.php` and confirm:

- database host, username, password, and database name
- `API_KEY`
- PHP write permissions if you later extend the project with media uploads

All endpoints are protected with `X-API-Key`.

### 3. Android Studio Configuration

Open:

`android_project/`

The project already includes:

- Gradle wrapper
- Java runtime pinning for Android Studio JBR
- `BASE_URL = "https://supriyadigitals.store/phpapp/"`
- `API_KEY` in `BuildConfig`

Build from Android Studio or terminal:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

### 4. Offline Cache

Room is already wired for:

- takers
- availability
- bookings
- reviews

Repositories update Room after successful API calls so users still see cached data when the network is unavailable.

### 5. Test the Main Workflows

1. Register a taker with pincode, service type, languages, social links, and portfolio URL.
2. Log in as a client or browse as guest.
3. Search by location and service type.
4. Apply a date filter to show only available takers.
5. Open a taker profile and review the calendar.
6. Create a booking and confirm the booking summary screen.
7. Confirm or decline the booking from the taker account.
8. Mark completed jobs and then submit a review from the client side.
9. Update taker availability and re-check search results.

## API Endpoints

- `POST /login.php`
- `POST /registerTaker.php`
- `POST /registerClient.php`
- `GET /searchTakers.php?location=&date=&serviceType=`
- `POST /updateAvailability.php`
- `GET /getAvailability.php?takerId=&month=`
- `POST /bookTaker.php`
- `POST /updateBookingStatus.php`
- `GET /getBookings.php?clientId=&takerId=`
- `POST /addReview.php`
- `GET /getReviews.php?takerId=`

## Notes

- The app uses the free India Pincode API to auto-fill area, city, and state from a 6-digit pincode.
- Takers now keep all client abilities too, including explore, booking, and booking-history flows.
- Reviews are limited to completed bookings, which prevents pre-service ratings.
- If you want portfolio file upload instead of a portfolio URL, the current project is a good base to add a multipart upload endpoint next.
