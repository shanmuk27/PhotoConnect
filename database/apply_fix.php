<?php
// Apply the missing social links index fix
// This script connects to the database and applies the migration

// Load env from parent directory
$envPath = dirname(__DIR__) . DIRECTORY_SEPARATOR . 'php_api' . DIRECTORY_SEPARATOR . '.env';
$env = [];

if (file_exists($envPath)) {
    $lines = file($envPath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    foreach ($lines as $line) {
        $line = trim($line);
        if (empty($line) || $line[0] === '#') continue;
        if (strpos($line, '=') !== false) {
            [$key, $value] = explode('=', $line, 2);
            $env[trim($key)] = trim($value, '\'"');
        }
    }
}

$dbHost = $env['DB_HOST'] ?? 'localhost';
$dbUser = $env['DB_USER'] ?? 'root';
$dbPass = $env['DB_PASS'] ?? '';
$dbName = $env['DB_NAME'] ?? 'photoconnect';

echo "=== Social Links Index Migration Fix ===\n";
echo "Database: {$dbName}\n";
echo "Host: {$dbHost}\n";
echo "\n";

try {
    // Create PDO connection
    $pdo = new PDO(
        "mysql:host={$dbHost};dbname={$dbName};charset=utf8mb4",
        $dbUser,
        $dbPass,
        [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        ]
    );
    
    echo "✓ Connected to database\n\n";
    
    // Step 1: Check if columns exist
    echo "Step 1: Verifying columns exist...\n";
    $stmt = $pdo->prepare("
        SELECT COLUMN_NAME 
        FROM INFORMATION_SCHEMA.COLUMNS 
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'takers' 
        AND COLUMN_NAME IN ('social_link_additional1', 'social_link_additional2')
    ");
    $stmt->execute([$dbName]);
    $columns = $stmt->fetchAll();
    
    if (count($columns) === 2) {
        echo "✓ Both columns exist: social_link_additional1, social_link_additional2\n\n";
    } else {
        echo "✗ Missing columns. Found: " . count($columns) . "/2\n";
        exit(1);
    }
    
    // Step 2: Drop existing index if present
    echo "Step 2: Dropping existing index (if present)...\n";
    try {
        $pdo->exec("ALTER TABLE takers DROP INDEX IF EXISTS idx_taker_social_links");
        echo "✓ Index dropped or didn't exist\n\n";
    } catch (PDOException $e) {
        echo "✓ No index to drop (OK)\n\n";
    }
    
    // Step 3: Create the index
    echo "Step 3: Creating idx_taker_social_links index...\n";
    $sql = "CREATE INDEX idx_taker_social_links ON takers(
        instagram_url,
        youtube_url,
        portfolio_url,
        social_link_additional1,
        social_link_additional2
    )";
    
    $pdo->exec($sql);
    echo "✓ Index created successfully\n\n";
    
    // Step 4: Verify index was created
    echo "Step 4: Verifying index creation...\n";
    $stmt = $pdo->prepare("
        SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
        FROM INFORMATION_SCHEMA.STATISTICS 
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'takers' 
        AND INDEX_NAME = 'idx_taker_social_links'
        ORDER BY SEQ_IN_INDEX
    ");
    $stmt->execute([$dbName]);
    $indexInfo = $stmt->fetchAll();
    
    if (count($indexInfo) > 0) {
        echo "✓ Index verified! Columns in index:\n";
        foreach ($indexInfo as $col) {
            echo "  - {$col['COLUMN_NAME']} (position {$col['SEQ_IN_INDEX']})\n";
        }
        echo "\n";
    } else {
        echo "✗ Index verification failed\n";
        exit(1);
    }
    
    // Step 5: Summary
    echo "=== MIGRATION SUCCESSFUL ===\n";
    echo "✓ All steps completed successfully\n";
    echo "✓ Index 'idx_taker_social_links' is now active\n";
    echo "✓ Social links database optimization is complete\n";
    
} catch (PDOException $e) {
    echo "✗ Database Error: " . $e->getMessage() . "\n";
    exit(1);
}
?>
