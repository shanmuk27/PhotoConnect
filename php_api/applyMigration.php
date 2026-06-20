<?php
// Migration endpoint - applies the social links storage/index fix
// This file should be deleted after running

header('Content-Type: application/json; charset=utf-8');

try {
    // Include config which loads .env and defines getDB()
    require_once __DIR__ . '/config.php';
    requireAdminRequest();
    
    // Get database connection
    $db = getDB();
    
    echo json_encode(['status' => 'Starting migration...'], JSON_PRETTY_PRINT);
    ob_flush();
    
    $columnExists = static function (PDO $db, string $column): bool {
        $stmt = $db->prepare("
            SELECT 1
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'takers' AND COLUMN_NAME = ?
            LIMIT 1
        ");
        $stmt->execute([DB_NAME, $column]);
        return (bool)$stmt->fetchColumn();
    };

    $indexExists = static function (PDO $db, string $index): bool {
        $stmt = $db->prepare("
            SELECT 1
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'takers' AND INDEX_NAME = ?
            LIMIT 1
        ");
        $stmt->execute([DB_NAME, $index]);
        return (bool)$stmt->fetchColumn();
    };

    // Step 1: Add missing columns.
    if (!$columnExists($db, 'social_link_additional1')) {
        $db->exec("ALTER TABLE takers ADD COLUMN social_link_additional1 VARCHAR(500) DEFAULT NULL AFTER portfolio_url");
    }
    if (!$columnExists($db, 'social_link_additional2')) {
        $db->exec("ALTER TABLE takers ADD COLUMN social_link_additional2 VARCHAR(500) DEFAULT NULL AFTER social_link_additional1");
    }

    // Step 2: Verify columns.
    $stmt = $db->prepare("
        SELECT COLUMN_NAME 
        FROM INFORMATION_SCHEMA.COLUMNS 
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'takers' 
        AND COLUMN_NAME IN ('social_link_additional1', 'social_link_additional2')
    ");
    $stmt->execute([DB_NAME]);
    $columns = $stmt->fetchAll();
    
    if (count($columns) !== 2) {
        http_response_code(400);
        die(json_encode([
            'success' => false,
            'message' => 'Missing required columns. Found: ' . count($columns) . '/2',
            'data' => []
        ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
    }

    // Step 3: Drop the unsafe composite URL index if a previous migration left it behind.
    if ($indexExists($db, 'idx_taker_social_links')) {
        $db->exec("DROP INDEX idx_taker_social_links ON takers");
    }

    // Step 4: Create safe prefix indexes. Full utf8mb4 VARCHAR(500) indexes can exceed
    // MySQL's 3072-byte key limit, especially when combined in one composite key.
    if (!$indexExists($db, 'idx_takers_social_additional1')) {
        $db->exec("CREATE INDEX idx_takers_social_additional1 ON takers(social_link_additional1(191))");
    }
    if (!$indexExists($db, 'idx_takers_social_additional2')) {
        $db->exec("CREATE INDEX idx_takers_social_additional2 ON takers(social_link_additional2(191))");
    }

    // Step 5: Verify indexes were created.
    $stmt = $db->prepare("
        SELECT INDEX_NAME, COLUMN_NAME, SUB_PART
        FROM INFORMATION_SCHEMA.STATISTICS 
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'takers' 
        AND INDEX_NAME IN ('idx_takers_social_additional1', 'idx_takers_social_additional2')
        ORDER BY INDEX_NAME, SEQ_IN_INDEX
    ");
    $stmt->execute([DB_NAME]);
    $indexInfo = $stmt->fetchAll();
    
    if (count($indexInfo) < 2) {
        http_response_code(400);
        die(json_encode([
            'success' => false,
            'message' => 'Index creation failed - expected two safe prefix indexes',
            'data' => []
        ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
    }
    
    // Success!
    http_response_code(200);
    echo json_encode([
        'success' => true,
        'message' => 'Migration applied successfully',
        'data' => [
            'indexes' => $indexInfo,
            'status' => 'Columns and safe prefix indexes created and verified'
        ]
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
    
} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode([
        'success' => false,
        'message' => 'Database error: ' . $e->getMessage(),
        'data' => []
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
    exit(1);
} catch (Throwable $e) {
    http_response_code(500);
    echo json_encode([
        'success' => false,
        'message' => 'Error: ' . $e->getMessage(),
        'data' => []
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
    exit(1);
}
?>
