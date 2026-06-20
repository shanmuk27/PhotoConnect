SET @add_client_upload_id = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE taker_posts ADD COLUMN client_upload_id VARCHAR(80) DEFAULT NULL AFTER caption',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'taker_posts'
      AND column_name = 'client_upload_id'
);
PREPARE add_client_upload_id_stmt FROM @add_client_upload_id;
EXECUTE add_client_upload_id_stmt;
DEALLOCATE PREPARE add_client_upload_id_stmt;

SET @add_client_upload_index = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE UNIQUE INDEX uniq_taker_posts_client_upload ON taker_posts(taker_id, client_upload_id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'taker_posts'
      AND index_name = 'uniq_taker_posts_client_upload'
);
PREPARE add_client_upload_index_stmt FROM @add_client_upload_index;
EXECUTE add_client_upload_index_stmt;
DEALLOCATE PREPARE add_client_upload_index_stmt;
