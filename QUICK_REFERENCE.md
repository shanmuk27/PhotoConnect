# Quick Reference Card 📋

## The Problem
```
❌ Missing Index: idx_taker_social_links
✅ Columns exist: social_link_additional1, social_link_additional2
```

## The SQL (Copy & Paste Ready)
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

## Where to Run It
- **phpMyAdmin**: SQL tab → Paste above SQL → Click "Go"
- **Command Line**: `mysql -h localhost -u root -p photoconnect < 2026_06_11_fix_social_links_index.sql`
- **PHP Endpoint**: Upload `applyMigration.php` and visit URL

## Verification SQL
```sql
SELECT COLUMN_NAME, SEQ_IN_INDEX
FROM INFORMATION_SCHEMA.STATISTICS 
WHERE TABLE_SCHEMA = 'photoconnect' 
AND TABLE_NAME = 'takers' 
AND INDEX_NAME = 'idx_taker_social_links'
ORDER BY SEQ_IN_INDEX;
```

## Success Criteria
✅ Should return 5 rows with:
1. instagram_url
2. youtube_url
3. portfolio_url
4. social_link_additional1
5. social_link_additional2

## Files Provided
| File | Purpose |
|------|---------|
| `2026_06_11_fix_social_links_index.sql` | Migration SQL |
| `applyMigration.php` | Automated PHP endpoint |
| `APPLY_MIGRATION_NOW.md` | **START HERE** |
| `DATABASE_FIX_GUIDE.md` | Technical details |
| `MIGRATION_INSTRUCTIONS.md` | Step-by-step guide |

## Time Estimate
⏱️ **2 minutes** to apply & verify

## Safety
🟢 **LOW RISK** - Only adds index, no data modification

## Questions?
📖 Read `APPLY_MIGRATION_NOW.md` for 3 methods to apply
