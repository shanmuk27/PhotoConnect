<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true);
if (!is_array($body)) {
    respond(false, 'Invalid JSON', [], 422);
}

$clientId = (int)($body['client_id'] ?? 0);
$path = trim((string)($body['verification_path'] ?? ''));
$gstin = strtoupper(trim((string)($body['gstin'] ?? '')));
$googleMapsUrl = pc_normalize_public_url($body['google_maps_url'] ?? null);
$ownerAadhaarSubmitted = filter_var($body['owner_aadhaar_submitted'] ?? false, FILTER_VALIDATE_BOOLEAN);

if ($clientId <= 0) {
    respond(false, 'Missing client_id', [], 422);
}

try {
    $db = getDB();
    ensureTrustSchema($db);
    $auth = requireAuthenticatedUser();
    authorizeClientProfile($db, $auth, $clientId);

    $allowedPaths = ['gst', 'google_maps', 'shop_license', 'manual'];
    if (!in_array($path, $allowedPaths, true)) {
        respond(false, 'Choose GST, Google Maps, or manual verification', [], 422);
    }

    if ($gstin !== '' && !pc_valid_gstin($gstin)) {
        respond(false, 'Enter a valid GST number', ['field' => 'gstin'], 422);
    }

    if ($path === 'gst') {
        if ($gstin === '') {
            respond(false, 'Enter a valid GST number', ['field' => 'gstin'], 422);
        }
    } elseif ($path === 'google_maps') {
        if ($googleMapsUrl === null || stripos($googleMapsUrl, 'google.') === false && stripos($googleMapsUrl, 'maps.app.goo.gl') === false) {
            respond(false, 'Enter a valid Google Maps business link', [], 422);
        }
    }
    $businessStatus = 'pending';

    $existingStmt = $db->prepare('SELECT owner_aadhaar_status, owner_aadhaar_url FROM studio_verifications WHERE client_id=? LIMIT 1');
    $existingStmt->execute([$clientId]);
    $existing = $existingStmt->fetch() ?: [];
    $existingOwnerStatus = (string)($existing['owner_aadhaar_status'] ?? 'not_submitted');
    $hasOwnerAadhaarDocument = trim((string)($existing['owner_aadhaar_url'] ?? '')) !== '';
    $ownerStatus = match (true) {
        $existingOwnerStatus === 'approved' => 'approved',
        $ownerAadhaarSubmitted && $hasOwnerAadhaarDocument => 'pending',
        $existingOwnerStatus !== '' => $existingOwnerStatus,
        default => 'not_submitted',
    };
    $stmt = $db->prepare(
        "INSERT INTO studio_verifications(
            client_id, business_status, verification_path, gstin, google_maps_url, owner_aadhaar_status
         ) VALUES(?,?,?,?,?,?)
         ON DUPLICATE KEY UPDATE
            business_status=IF(business_status='approved', business_status, VALUES(business_status)),
            verification_path=VALUES(verification_path),
            gstin=VALUES(gstin),
            google_maps_url=VALUES(google_maps_url),
            owner_aadhaar_status=IF(
                VALUES(owner_aadhaar_status)='not_submitted',
                owner_aadhaar_status,
                IF(owner_aadhaar_status='approved', owner_aadhaar_status, VALUES(owner_aadhaar_status))
            ),
            updated_at=NOW()"
    );
    $stmt->execute([
        $clientId,
        $businessStatus,
        $path,
        $gstin !== '' ? $gstin : null,
        $googleMapsUrl,
        $ownerStatus,
    ]);

    respond(true, 'Verification submitted for admin review', [
        'studio_trust' => pc_studio_trust_summary($db, $clientId),
    ]);
} catch (PDOException $e) {
    pc_log_runtime_error('submitStudioVerification DB error: ' . $e->getMessage());
    respond(false, 'Could not submit verification right now. Please try again.', [], 500);
}
