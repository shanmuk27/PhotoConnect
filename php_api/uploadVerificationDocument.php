<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$targetRole = strtolower(trim((string)($_POST['target_role'] ?? $_POST['role'] ?? '')));
$targetId = (int)($_POST['target_id'] ?? $_POST['actor_id'] ?? 0);
$documentType = strtolower(trim((string)($_POST['document_type'] ?? '')));
$documentType = match ($documentType) {
    'owner_aadhaar_front', 'owner_aadhaar_back' => 'owner_aadhaar',
    default => $documentType,
};
$file = $_FILES['document'] ?? $_FILES['file'] ?? null;

if (!in_array($targetRole, ['taker', 'studio', 'client'], true) || $targetId <= 0 || $documentType === '') {
    respond(false, 'Missing verification target or document type', [], 422);
}
if ($targetRole === 'client') {
    $targetRole = 'studio';
}
if (!is_array($file)) {
    respond(false, 'Attach a verification document', [], 422);
}

try {
    $db = getDB();
    ensureTrustSchema($db);
    $auth = requireAuthenticatedUser();

    if ($targetRole === 'taker') {
        authorizeTaker($db, $auth, $targetId);
        $allowed = ['aadhaar_front'];
        if (!in_array($documentType, $allowed, true)) {
            respond(false, 'Invalid taker verification document type', [], 422);
        }
        $oldStmt = $db->prepare('SELECT aadhaar_front_url FROM taker_verifications WHERE taker_id=? LIMIT 1');
        $oldStmt->execute([$targetId]);
        $oldPath = (string)($oldStmt->fetchColumn() ?: '');
        $relativePath = pc_store_verification_upload($file, 'taker', $targetId, $documentType);
        $stmt = $db->prepare(
            "INSERT INTO taker_verifications(taker_id, aadhaar_status, aadhaar_front_url)
             VALUES(?, 'pending', ?)
             ON DUPLICATE KEY UPDATE
                aadhaar_status='pending',
                aadhaar_front_url=VALUES(aadhaar_front_url),
                updated_at=NOW()"
        );
        $stmt->execute([$targetId, $relativePath]);
        if ($oldPath !== '' && $oldPath !== $relativePath) {
            pc_delete_verification_private_file($oldPath);
        }
        respond(true, 'Aadhaar document submitted for admin review', [
            'taker_trust' => pc_taker_trust_summary($db, $targetId),
        ]);
    }

    authorizeClientProfile($db, $auth, $targetId);
    $allowed = ['gst_certificate', 'shop_license', 'signboard', 'owner_aadhaar'];
    if (!in_array($documentType, $allowed, true)) {
        respond(false, 'Invalid studio verification document type', [], 422);
    }

    $relativePath = pc_store_verification_upload($file, 'studio', $targetId, $documentType);
    $columnMap = [
        'gst_certificate' => 'gst_certificate_url',
        'shop_license' => 'shop_license_url',
        'signboard' => 'signboard_url',
        'owner_aadhaar' => 'owner_aadhaar_url',
    ];
    $path = $documentType === 'gst_certificate' ? 'gst' : ($documentType === 'shop_license' || $documentType === 'signboard' ? 'shop_license' : 'manual');
    $column = $columnMap[$documentType];
    $oldStmt = $db->prepare("SELECT {$column} FROM studio_verifications WHERE client_id=? LIMIT 1");
    $oldStmt->execute([$targetId]);
    $oldPath = (string)($oldStmt->fetchColumn() ?: '');
    if ($documentType === 'owner_aadhaar') {
        $stmt = $db->prepare(
            "INSERT INTO studio_verifications(client_id, owner_aadhaar_status, owner_aadhaar_url)
             VALUES(?, 'pending', ?)
             ON DUPLICATE KEY UPDATE
                owner_aadhaar_status='pending',
                owner_aadhaar_url=VALUES(owner_aadhaar_url),
                updated_at=NOW()"
        );
        $stmt->execute([$targetId, $relativePath]);
    } else {
        $stmt = $db->prepare(
            "INSERT INTO studio_verifications(client_id, business_status, verification_path, {$column})
             VALUES(?, 'pending', ?, ?)
             ON DUPLICATE KEY UPDATE
                business_status=IF(business_status='approved', business_status, 'pending'),
                verification_path=VALUES(verification_path),
                {$column}=VALUES({$column}),
                updated_at=NOW()"
        );
        $stmt->execute([$targetId, $path, $relativePath]);
    }
    if ($oldPath !== '' && $oldPath !== $relativePath) {
        pc_delete_verification_private_file($oldPath);
    }

    respond(true, 'Studio verification document submitted for admin review', [
        'studio_trust' => pc_studio_trust_summary($db, $targetId),
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
