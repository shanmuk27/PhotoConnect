<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

requireAdminRequest();

$targetRole = strtolower(trim((string)($_GET['target_role'] ?? $_GET['role'] ?? '')));
$targetId = (int)($_GET['target_id'] ?? $_GET['id'] ?? 0);
$documentType = strtolower(trim((string)($_GET['document_type'] ?? '')));
if ($targetRole === 'client') {
    $targetRole = 'studio';
}
if (!in_array($targetRole, ['taker', 'studio'], true) || $targetId <= 0 || $documentType === '') {
    respond(false, 'Missing verification document target', [], 422);
}

$column = null;
if ($targetRole === 'taker') {
    $column = $documentType === 'aadhaar_front' ? 'aadhaar_front_url' : null;
    $table = 'taker_verifications';
    $idColumn = 'taker_id';
} else {
    $map = [
        'gst_certificate' => 'gst_certificate_url',
        'shop_license' => 'shop_license_url',
        'signboard' => 'signboard_url',
        'owner_aadhaar' => 'owner_aadhaar_url',
    ];
    $column = $map[$documentType] ?? null;
    $table = 'studio_verifications';
    $idColumn = 'client_id';
}
if ($column === null) {
    respond(false, 'Invalid verification document type', [], 422);
}

try {
    $db = getDB();
    ensureTrustSchema($db);
    $stmt = $db->prepare("SELECT {$column} FROM {$table} WHERE {$idColumn}=? LIMIT 1");
    $stmt->execute([$targetId]);
    $relativePath = (string)($stmt->fetchColumn() ?: '');
    $absolutePath = pc_verification_private_absolute_path($relativePath);
    if ($absolutePath === null || !is_file($absolutePath)) {
        respond(false, 'Verification document not found', [], 404);
    }

    $mime = (new finfo(FILEINFO_MIME_TYPE))->file($absolutePath) ?: 'application/octet-stream';
    $safeName = preg_replace('/[^a-zA-Z0-9_.-]/', '_', $documentType) . '.' . pathinfo($absolutePath, PATHINFO_EXTENSION);
    header_remove('Content-Type');
    header('Content-Type: ' . $mime);
    header('Content-Length: ' . filesize($absolutePath));
    header('Content-Disposition: inline; filename="' . $safeName . '"');
    readfile($absolutePath);
    exit;
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
