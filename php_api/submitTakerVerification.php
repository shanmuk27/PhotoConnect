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

$takerId = (int)($body['taker_id'] ?? 0);
$submittedLink = pc_normalize_public_url($body['social_url'] ?? null)
    ?: pc_normalize_public_url($body['portfolio_url'] ?? null);
$aadhaarSubmitted = filter_var($body['aadhaar_submitted'] ?? false, FILTER_VALIDATE_BOOLEAN);

if ($takerId <= 0) {
    respond(false, 'Missing taker_id', [], 422);
}

try {
    $db = getDB();
    ensureTrustSchema($db);
    $auth = requireAuthenticatedUser();
    authorizeTaker($db, $auth, $takerId);

    $profileStmt = $db->prepare(
        "SELECT t.instagram_url, t.youtube_url, t.portfolio_url,
                t.social_link_additional1, t.social_link_additional2,
                tv.aadhaar_status, tv.aadhaar_front_url, tv.portfolio_status, tv.social_status, tv.social_url
         FROM takers t
         LEFT JOIN taker_verifications tv ON tv.taker_id=t.id
         WHERE t.id=?
         LIMIT 1"
    );
    $profileStmt->execute([$takerId]);
    $profile = $profileStmt->fetch() ?: [];
    $existingSocialUrl = pc_normalize_public_url($profile['social_url'] ?? null)
        ?: pc_normalize_public_url($profile['social_link_additional1'] ?? null)
        ?: pc_normalize_public_url($profile['social_link_additional2'] ?? null)
        ?: pc_normalize_public_url($profile['instagram_url'] ?? null)
        ?: pc_normalize_public_url($profile['youtube_url'] ?? null)
        ?: pc_normalize_public_url($profile['portfolio_url'] ?? null);
    $socialUrl = $submittedLink ?: $existingSocialUrl;

    if ($socialUrl === null) {
        respond(false, 'Enter a valid social or portfolio URL', [], 422);
    }

    if ($submittedLink !== null) {
        $host = strtolower((string)parse_url($submittedLink, PHP_URL_HOST));
        $field = match (true) {
            str_contains($host, 'instagram.com') => 'instagram_url',
            str_contains($host, 'youtube.com'), str_contains($host, 'youtu.be') => 'youtube_url',
            default => 'portfolio_url',
        };
        $db->prepare("UPDATE takers SET {$field}=? WHERE id=?")->execute([$submittedLink, $takerId]);
    }

    // A valid URL is only evidence. Admin must still glance-check that it is photography work.
    $existingSocialStatus = (string)($profile['social_status'] ?? 'not_submitted');
    $sameSocialLink = false;
    if ($existingSocialUrl !== null && $socialUrl !== null) {
        $sameSocialLink = strtolower(rtrim($existingSocialUrl, '/')) === strtolower(rtrim($socialUrl, '/'));
    }
    $socialStatus = ($existingSocialStatus === 'approved' && $sameSocialLink) ? 'approved' : 'pending';

    $portfolio = pc_portfolio_verification_from_evidence($db, $takerId);
    $photoCount = (int)$portfolio['photo_count'];
    if ((string)($profile['portfolio_status'] ?? '') !== 'approved' && $photoCount <= 0) {
        respond(false, 'Cannot submit verification yet: no posts uploaded yet.', [], 422);
    }
    $portfolioStatus = (string)($profile['portfolio_status'] ?? '') === 'approved'
        ? 'approved'
        : (string)$portfolio['status'];
    $existingAadhaarStatus = (string)($profile['aadhaar_status'] ?? 'not_submitted');
    $hasAadhaarDocument = trim((string)($profile['aadhaar_front_url'] ?? '')) !== '';
    $aadhaarStatus = match (true) {
        $existingAadhaarStatus === 'approved' => 'approved',
        $aadhaarSubmitted && $hasAadhaarDocument => 'pending',
        $existingAadhaarStatus !== '' => $existingAadhaarStatus,
        default => 'not_submitted',
    };

    $stmt = $db->prepare(
        "INSERT INTO taker_verifications(
            taker_id, aadhaar_status, portfolio_status, portfolio_photo_count, portfolio_device_summary, portfolio_checked_at, social_status, social_url
         ) VALUES(?,?,?,?,?,NOW(),?,?)
         ON DUPLICATE KEY UPDATE
            aadhaar_status=IF(aadhaar_status='approved', aadhaar_status, VALUES(aadhaar_status)),
            portfolio_status=VALUES(portfolio_status),
            portfolio_photo_count=VALUES(portfolio_photo_count),
            portfolio_device_summary=VALUES(portfolio_device_summary),
            portfolio_checked_at=NOW(),
            social_status=VALUES(social_status),
            social_url=VALUES(social_url),
            updated_at=NOW()"
    );
    $stmt->execute([
        $takerId,
        $aadhaarStatus,
        $portfolioStatus,
        $photoCount,
        $portfolio['device_summary'],
        $socialStatus,
        $socialUrl,
    ]);

    respond(true, 'Verification submitted', [
        'taker_trust' => pc_taker_trust_summary($db, $takerId),
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
