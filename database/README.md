# PhotoConnect Database Notes

Use `schema.sql` for a fresh database install. It contains the current unified `users` model, profile tables, posts, availability, bookings, events, notifications, OTP, and support tables.

Use files in `migrations/` only when upgrading an older deployed database in chronological order.

## Migration strategy

Run `apply_migration.php` whenever schema changes are deployed. It creates and uses a `schema_migrations` table so each migration is applied once.

Common commands:

```powershell
php database/apply_migration.php --dry-run
php database/apply_migration.php
```

If a server database was already manually updated before this runner existed, first verify it with:

```powershell
php database/integrity_audit.php
```

If the audit is clean, baseline the old migration files so only future files run:

```powershell
php database/apply_migration.php --baseline
```

On shared hosting or NAS web execution, set `MIGRATION_ADMIN_TOKEN` in `php_api/.env` and pass it as `X-Admin-Token`. Do not expose migration scripts without that token.

Indexes and constraints should be applied through migrations. `API_INDEX_BOOTSTRAP_ENABLED` defaults to `0` so normal app requests do not run `ALTER TABLE` or `CREATE INDEX` while users are opening pages.
