# Database Migration Fix: Missing Social Links Index

## Problem
The migration `2026_06_10_additional_social_links.sql` partially executed:
- ✅ **SUCCESS**: Columns `social_link_additional1` and `social_link_additional2` were added to the `takers` table
- ❌ **FAILED**: The index `idx_taker_social_links` was not created

## Current Table Structure
Your `takers` table now has these social link columns:
```sql
`instagram_url` varchar(500) DEFAULT NULL,
`youtube_url` varchar(500) DEFAULT NULL,
`portfolio_url` varchar(500) DEFAULT NULL,
`social_link_additional1` varchar(500) DEFAULT NULL,    -- ✅ Added
`social_link_additional2` varchar(500) DEFAULT NULL,    -- ✅ Added
```

But the index is missing from the indexes section.

## 🚀 Quickest Solution: Use the PHP Migration Endpoint

A ready-to-use PHP file has been created at `php_api/applyMigration.php` that will automatically apply the fix via your web server.

### Option 1: Direct HTTP Call (Recommended)
Since your database is on a live server at `https://supriyadigitals.store/`, you can:

1. **Upload the migration file** (`php_api/applyMigration.php`) to your server
2. **Call it from your browser or API client**:
   ```
   https://supriyadigitals.store/phpapp/applyMigration.php
   ```
3. **You'll see JSON response** confirming the migration was applied

### Option 2: Using phpMyAdmin (Manual Method)

1. **Login to phpMyAdmin** on your hosting panel
2. **Select the `photoconnect` database**
3. **Go to the SQL tab**
4. **Paste this SQL**:

```sql
ALTER TABLE `takers` DROP INDEX IF EXISTS `idx_taker_social_links`;
CREATE INDEX `idx_taker_social_links` ON `takers`(
    `instagram_url`,
    `youtube_url`,
    `portfolio_url`,
    `social_link_additional1`,
    `social_link_additional2`
);
```

5. **Click "Go"** to execute

### Option 3: Using MySQL Command Line
```bash
mysql -h your-host -u your-user -p your-password photoconnect < database/migrations/2026_06_11_fix_social_links_index.sql
```

## ✅ Verification

After running the migration, verify the index was created:

### Via phpMyAdmin:
1. Go to `photoconnect` → `takers` table
2. Click the **Indexes** tab
3. Look for `idx_taker_social_links` in the list

### Via SQL Query:
```sql
SELECT COLUMN_NAME, SEQ_IN_INDEX
FROM INFORMATION_SCHEMA.STATISTICS 
WHERE TABLE_SCHEMA = 'photoconnect' 
AND TABLE_NAME = 'takers' 
AND INDEX_NAME = 'idx_taker_social_links'
ORDER BY SEQ_IN_INDEX;
```

**Expected Result**: Should return 5 rows with columns:
- instagram_url (position 1)
- youtube_url (position 2)
- portfolio_url (position 3)
- social_link_additional1 (position 4)
- social_link_additional2 (position 5)

## 📁 Migration Files
Two files in `database/migrations/`:

1. **2026_06_10_additional_social_links.sql** - Original (added the columns)
2. **2026_06_11_fix_social_links_index.sql** - New (adds the missing index)

Both are idempotent (safe to run multiple times).

## 📊 PHP Migration Endpoint

A complete PHP endpoint has been created: `php_api/applyMigration.php`

This file:
- ✓ Verifies both columns exist
- ✓ Drops the index if it exists (for safety)
- ✓ Creates the index
- ✓ Verifies the index was created
- ✓ Returns JSON status response

You can safely run it multiple times - it won't cause errors if the index already exists.

## Why This Index?
The index on `(instagram_url, youtube_url, portfolio_url, social_link_additional1, social_link_additional2)` improves performance for:
- Filtering or searching by social media URLs
- Retrieving taker profiles with specific social links
- Features that display takers with social verification

## Technical Details
- **Columns**: All are `VARCHAR(500)` and allow NULL
- **Index Type**: Non-unique compound index (B-tree)
- **Performance Impact**: No degradation; improves SELECT queries only
- **Idempotent**: Safe to apply multiple times

## Troubleshooting

### If the index still doesn't appear after running the migration:
1. Clear your database cache (if any)
2. Restart MySQL: `sudo systemctl restart mysql`
3. Run the verification query again
4. Check for any error messages in the SQL response

### If you get an access denied error:
- Verify your database credentials in `php_api/.env`
- Make sure the database user has ALTER TABLE permissions
- Contact your hosting provider if you don't have database access
