<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

requireAdminRequest();

$body = json_decode(file_get_contents('php://input'), true);
if (!is_array($body)) {
    respond(false, 'Invalid JSON', [], 422);
}

$targetRole = strtolower(trim((string)($body['target_role'] ?? $body['role'] ?? '')));
$targetId = (int)($body['target_id'] ?? $body['id'] ?? 0);
$notes = trim((string)($body['admin_notes'] ?? ''));
if ($targetRole === 'client') {
    $targetRole = 'studio';
}
if (!in_array($targetRole, ['taker', 'studio'], true) || $targetId <= 0) {
    respond(false, 'Missing verification target', [], 422);
}
if (mb_strlen($notes) > 2000) {
    respond(false, 'Admin notes are too long', [], 422);
}

$allowedStatuses = ['not_submitted', 'pending', 'approved', 'rejected'];
$statusOrNull = function (string $key) use ($body, $allowedStatuses): ?string {
    if (!array_key_exists($key, $body)) return null;
    $status = strtolower(trim((string)$body[$key]));
    if (!in_array($status, $allowedStatuses, true)) {
        respond(false, 'Invalid status for ' . $key, [], 422);
    }
    return $status;
};

function pc_verification_rejected_labels(array $labelsByKey, array $statuses): array
{
    $rejected = [];
    foreach ($labelsByKey as $key => $label) {
        if (($statuses[$key] ?? null) === 'rejected') {
            $rejected[] = $label;
        }
    }
    return $rejected;
}

function pc_send_verification_rejection_email(PDO $db, string $targetRole, int $targetId, array $rejected, string $notes): void
{
    if (empty($rejected)) {
        return;
    }
    $items = implode(', ', $rejected);
    createNotification(
        $db,
        $targetRole === 'taker' ? 'taker' : 'client',
        $targetId,
        'verification_rejected',
        'Verification documents rejected',
        'Rejected: ' . $items . '. Please update and submit again.',
        ['rejected' => $rejected]
    );
    if ($targetRole === 'taker') {
        $stmt = $db->prepare(
            'SELECT t.full_name AS name, u.email
             FROM takers t
             JOIN users u ON u.id=t.user_id
             WHERE t.id=?
             LIMIT 1'
        );
    } else {
        $stmt = $db->prepare(
            'SELECT c.name, u.email
             FROM clients c
             JOIN users u ON u.id=c.user_id
             WHERE c.id=?
             LIMIT 1'
        );
    }
    $stmt->execute([$targetId]);
    $row = $stmt->fetch();
    $email = trim((string)($row['email'] ?? ''));
    if ($email === '') {
        return;
    }

    $name = trim((string)($row['name'] ?? 'PhotoConnect user')) ?: 'PhotoConnect user';
    $safeName = htmlspecialchars($name, ENT_QUOTES, 'UTF-8');
    $safeItems = htmlspecialchars($items, ENT_QUOTES, 'UTF-8');
    $safeNotes = htmlspecialchars($notes !== '' ? $notes : 'Please upload a clearer or correct document and submit verification again.', ENT_QUOTES, 'UTF-8');
    $html = "<p>Hello {$safeName},</p><p>Your PhotoConnect verification documents were rejected.</p><p><strong>Rejected:</strong> {$safeItems}</p><p>{$safeNotes}</p><p>Please update the rejected items and submit again from the app.</p>";
    $text = "Hello {$name},\n\nYour PhotoConnect verification documents were rejected.\nRejected: {$items}\n\n" . ($notes !== '' ? $notes : 'Please upload a clearer or correct document and submit verification again.') . "\n\nPlease update the rejected items and submit again from the app.";
    pc_send_email($email, 'PhotoConnect verification documents rejected', $html, $text);
}

try {
    $db = getDB();
    ensureTrustSchema($db);

    if ($targetRole === 'taker') {
        $aadhaar = $statusOrNull('aadhaar_status');
        $portfolio = $statusOrNull('portfolio_status');
        $social = $statusOrNull('social_status');
        if ($aadhaar === null && $portfolio === null && $social === null && $notes === '') {
            respond(false, 'Nothing to review', [], 422);
        }

        $oldStmt = $db->prepare('SELECT aadhaar_front_url FROM taker_verifications WHERE taker_id=? LIMIT 1');
        $oldStmt->execute([$targetId]);
        $oldTakerDocs = $oldStmt->fetch() ?: [];
        $stmt = $db->prepare(
            "INSERT INTO taker_verifications(taker_id, aadhaar_status, portfolio_status, social_status, admin_notes)
             VALUES(?, COALESCE(?, 'not_submitted'), COALESCE(?, 'not_submitted'), COALESCE(?, 'not_submitted'), ?)
             ON DUPLICATE KEY UPDATE
                aadhaar_status=IF(? IS NULL, aadhaar_status, ?),
                portfolio_status=IF(? IS NULL, portfolio_status, ?),
                social_status=IF(? IS NULL, social_status, ?),
                admin_notes=VALUES(admin_notes),
                updated_at=NOW()"
        );
        $stmt->execute([
            $targetId,
            $aadhaar,
            $portfolio,
            $social,
            $notes !== '' ? $notes : null,
            $aadhaar,
            $aadhaar,
            $portfolio,
            $portfolio,
            $social,
            $social,
        ]);
        if (in_array($aadhaar, ['rejected', 'not_submitted'], true)) {
            pc_delete_verification_private_file($oldTakerDocs['aadhaar_front_url'] ?? null);
            $db->prepare('UPDATE taker_verifications SET aadhaar_front_url=NULL WHERE taker_id=?')->execute([$targetId]);
        }
        pc_send_verification_rejection_email(
            $db,
            'taker',
            $targetId,
            pc_verification_rejected_labels(
                ['aadhaar_status' => 'Aadhaar', 'portfolio_status' => 'Portfolio', 'social_status' => 'Social profile'],
                ['aadhaar_status' => $aadhaar, 'portfolio_status' => $portfolio, 'social_status' => $social]
            ),
            $notes
        );
        respond(true, 'Taker verification reviewed', [
            'taker_trust' => pc_taker_trust_summary($db, $targetId),
        ]);
    }

    $business = $statusOrNull('business_status');
    $ownerAadhaar = $statusOrNull('owner_aadhaar_status');
    if ($business === null && $ownerAadhaar === null && $notes === '') {
        respond(false, 'Nothing to review', [], 422);
    }

    $oldStmt = $db->prepare(
        'SELECT gst_certificate_url, shop_license_url, signboard_url, owner_aadhaar_url
         FROM studio_verifications
         WHERE client_id=?
         LIMIT 1'
    );
    $oldStmt->execute([$targetId]);
    $oldStudioDocs = $oldStmt->fetch() ?: [];
    $stmt = $db->prepare(
        "INSERT INTO studio_verifications(client_id, business_status, owner_aadhaar_status, admin_notes)
         VALUES(?, COALESCE(?, 'not_submitted'), COALESCE(?, 'not_submitted'), ?)
         ON DUPLICATE KEY UPDATE
            business_status=IF(? IS NULL, business_status, ?),
            owner_aadhaar_status=IF(? IS NULL, owner_aadhaar_status, ?),
            admin_notes=VALUES(admin_notes),
            updated_at=NOW()"
    );
    $stmt->execute([
        $targetId,
        $business,
        $ownerAadhaar,
        $notes !== '' ? $notes : null,
        $business,
        $business,
        $ownerAadhaar,
        $ownerAadhaar,
    ]);
    if (in_array($business, ['rejected', 'not_submitted'], true)) {
        foreach (['gst_certificate_url', 'shop_license_url', 'signboard_url'] as $column) {
            pc_delete_verification_private_file($oldStudioDocs[$column] ?? null);
        }
        $db->prepare(
            'UPDATE studio_verifications
             SET gst_certificate_url=NULL, shop_license_url=NULL, signboard_url=NULL
             WHERE client_id=?'
        )->execute([$targetId]);
    }
    if (in_array($ownerAadhaar, ['rejected', 'not_submitted'], true)) {
        pc_delete_verification_private_file($oldStudioDocs['owner_aadhaar_url'] ?? null);
        $db->prepare('UPDATE studio_verifications SET owner_aadhaar_url=NULL WHERE client_id=?')->execute([$targetId]);
    }
    pc_send_verification_rejection_email(
        $db,
        'studio',
        $targetId,
        pc_verification_rejected_labels(
            ['business_status' => 'Business proof', 'owner_aadhaar_status' => 'Owner Aadhaar'],
            ['business_status' => $business, 'owner_aadhaar_status' => $ownerAadhaar]
        ),
        $notes
    );
    respond(true, 'Studio verification reviewed', [
        'studio_trust' => pc_studio_trust_summary($db, $targetId),
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
