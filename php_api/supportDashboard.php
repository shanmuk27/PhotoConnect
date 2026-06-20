<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';
require_once __DIR__ . '/searchInsightsHelpers.php';

// Override the default config.php JSON header
header('Content-Type: text/html; charset=utf-8');

session_start();

$STATUSES = ['not_submitted', 'pending', 'approved', 'rejected'];

function dash_e($value): string {
    return htmlspecialchars((string)$value, ENT_QUOTES, 'UTF-8');
}

function dash_csrf(): string {
    if (empty($_SESSION['dashboard_csrf'])) {
        $_SESSION['dashboard_csrf'] = bin2hex(random_bytes(32));
    }
    return (string)$_SESSION['dashboard_csrf'];
}

function dash_check_csrf(): void {
    $token = (string)($_POST['csrf'] ?? $_GET['csrf'] ?? '');
    if ($token === '' || empty($_SESSION['dashboard_csrf']) || !hash_equals((string)$_SESSION['dashboard_csrf'], $token)) {
        throw new RuntimeException('Session expired. Refresh and try again.');
    }
}

function dash_status_label(string $status): string {
    return ucwords(str_replace('_', ' ', $status));
}

function dash_status_class(string $status): string {
    return match ($status) {
        'approved' => 'good',
        'pending' => 'warn',
        'rejected', 'not_submitted' => 'bad',
        default => 'neutral',
    };
}

function dash_stage_class(string $stage): string {
    return in_array($stage, ['verified', 'trusted', 'pro_verified', 'business_verified'], true) ? 'good' : 'bad';
}

function dash_status_select(string $name, string $current, array $statuses): string {
    $html = '<select name="' . dash_e($name) . '" class="status-select">';
    foreach ($statuses as $status) {
        $selected = $status === $current ? ' selected' : '';
        $html .= '<option value="' . dash_e($status) . '"' . $selected . '>' . dash_e(dash_status_label($status)) . '</option>';
    }
    return $html . '</select>';
}

function dash_post_status(string $key, array $statuses): string {
    $status = strtolower(trim((string)($_POST[$key] ?? '')));
    if (!in_array($status, $statuses, true)) {
        throw new RuntimeException('Invalid status for ' . $key);
    }
    return $status;
}

function dash_doc_link(string $role, int $id, string $type, bool $available, string $label): string {
    if (!$available) {
        return '<span class="doc-missing">No document</span>';
    }
    $url = 'supportDashboard.php?' . http_build_query([
        'view' => 'verification',
        'download_doc' => 1,
        'target_role' => $role,
        'target_id' => $id,
        'document_type' => $type,
        'csrf' => dash_csrf(),
    ]);
    return '<a class="doc-link" href="' . dash_e($url) . '" target="_blank" rel="noopener">View ' . dash_e($label) . '</a>';
}

function dash_external_href(string $url): string {
    $trimmed = trim($url);
    if ($trimmed === '') {
        return '';
    }
    if (preg_match('/^https?:\/\//i', $trimmed)) {
        return $trimmed;
    }
    return 'https://' . $trimmed;
}

function dash_taker_links(array $row): string {
    $candidates = [
        'Submitted link' => $row['submitted_social_url'] ?? $row['social_url'] ?? '',
        'Portfolio' => $row['portfolio_url'] ?? '',
        'Additional link 1' => $row['social_link_additional1'] ?? '',
        'Additional link 2' => $row['social_link_additional2'] ?? '',
        'Instagram' => $row['instagram_url'] ?? '',
        'YouTube' => $row['youtube_url'] ?? '',
    ];
    $seen = [];
    $links = [];
    foreach ($candidates as $label => $url) {
        $href = dash_external_href((string)$url);
        if ($href === '') {
            continue;
        }
        $key = strtolower(rtrim($href, '/'));
        if (isset($seen[$key])) {
            continue;
        }
        $seen[$key] = true;
        $links[] = '<a class="doc-link" target="_blank" rel="noopener" href="' . dash_e($href) . '">' . dash_e($label) . '</a>';
    }
    return !empty($links) ? implode('<br>', $links) : '<span class="doc-missing">No link</span>';
}

function dash_has_value($value): bool {
    return trim((string)$value) !== '';
}

function dash_taker_has_social_link(array $row): bool {
    foreach (['submitted_social_url', 'social_url', 'portfolio_url', 'social_link_additional1', 'social_link_additional2', 'instagram_url', 'youtube_url'] as $key) {
        if (dash_external_href((string)($row[$key] ?? '')) !== '') {
            return true;
        }
    }
    return false;
}

function dash_taker_requirements(array $row): array {
    $portfolioCount = (int)($row['portfolio_photo_count'] ?? 0);
    $hasAadhaar = !empty($row['has_aadhaar_front']) || dash_has_value($row['aadhaar_front_url'] ?? '');
    $hasPortfolioEvidence = $portfolioCount > 0 && dash_has_value($row['portfolio_device_summary'] ?? '');
    $hasSocialLink = dash_taker_has_social_link($row);
    return [
        'aadhaar_status' => [
            'label' => 'Aadhaar document',
            'ok' => $hasAadhaar,
            'detail' => $hasAadhaar ? 'Aadhaar image is uploaded' : dash_missing_document_hint((string)($row['aadhaar_status'] ?? ''), 'Aadhaar'),
        ],
        'portfolio_status' => [
            'label' => 'Portfolio evidence',
            'ok' => $hasPortfolioEvidence,
            'detail' => $hasPortfolioEvidence ? 'Portfolio photos and camera metadata are available' : 'Portfolio photos with camera metadata are missing',
        ],
        'social_status' => [
            'label' => 'Public work link',
            'ok' => $hasSocialLink,
            'detail' => $hasSocialLink ? 'At least one portfolio/social link is provided' : 'No portfolio/social link is saved',
        ],
    ];
}

function dash_valid_gstin(string $gstin): bool {
    return function_exists('pc_valid_gstin')
        ? pc_valid_gstin($gstin)
        : preg_match('/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/i', trim($gstin)) === 1;
}

function dash_studio_business_ready(array $row): array {
    $path = strtolower(trim((string)($row['verification_path'] ?? '')));
    $hasGstDoc = !empty($row['has_gst_certificate']) || dash_has_value($row['gst_certificate_url'] ?? '');
    $hasShopDoc = !empty($row['has_shop_license']) || dash_has_value($row['shop_license_url'] ?? '');
    $hasSignboard = !empty($row['has_signboard']) || dash_has_value($row['signboard_url'] ?? '');
    $hasMaps = dash_external_href((string)($row['google_maps_url'] ?? '')) !== '';
    $gstin = (string)($row['gstin'] ?? '');

    if ($path === 'gst') {
        return [
            'ok' => dash_valid_gstin($gstin) && $hasGstDoc,
            'detail' => 'GST path needs a valid GSTIN and GST certificate',
        ];
    }
    if ($path === 'shop_license') {
        return [
            'ok' => $hasShopDoc,
            'detail' => $hasShopDoc
                ? 'Shop license document is uploaded'
                : 'Shop license path needs the license/signboard document',
        ];
    }
    if ($path === 'google_maps') {
        return [
            'ok' => $hasMaps && $hasSignboard,
            'detail' => 'Google Maps path needs Maps link and signboard document',
        ];
    }
    if ($path === 'manual') {
        return [
            'ok' => $hasSignboard && ($hasGstDoc || $hasShopDoc || $hasMaps),
            'detail' => 'Manual path needs signboard plus one business proof',
        ];
    }
    return [
        'ok' => false,
        'detail' => 'Choose a business verification path first',
    ];
}

function dash_studio_requirements(array $row): array {
    $business = dash_studio_business_ready($row);
    $hasOwnerAadhaar = !empty($row['has_owner_aadhaar']) || dash_has_value($row['owner_aadhaar_url'] ?? '');
    return [
        'business_status' => [
            'label' => 'Business proof',
            'ok' => (bool)$business['ok'],
            'detail' => (string)$business['detail'],
        ],
        'owner_aadhaar_status' => [
            'label' => 'Owner Aadhaar',
            'ok' => $hasOwnerAadhaar,
            'detail' => $hasOwnerAadhaar ? 'Owner Aadhaar document is uploaded' : dash_missing_document_hint((string)($row['owner_aadhaar_status'] ?? ''), 'Owner Aadhaar'),
        ],
    ];
}

function dash_ready_count(array $requirements): int {
    $count = 0;
    foreach ($requirements as $requirement) {
        if (!empty($requirement['ok'])) $count++;
    }
    return $count;
}

function dash_requirements_html(array $requirements): string {
    $items = [];
    foreach ($requirements as $requirement) {
        $ok = !empty($requirement['ok']);
        $items[] = '<li class="' . ($ok ? 'ok' : 'missing') . '"><strong>' . dash_e((string)$requirement['label']) . '</strong><span>' . dash_e((string)$requirement['detail']) . '</span></li>';
    }
    return '<ul class="evidence-list">' . implode('', $items) . '</ul>';
}

function dash_missing_document_hint(string $status, string $label): string {
    if ($status === 'pending') {
        return $label . ' status is pending, but the saved file is missing. Ask the user to re-upload it.';
    }
    if ($status === 'rejected') {
        return $label . ' was rejected and the old file was removed. Ask the user to upload a corrected file.';
    }
    return 'Cannot approve until ' . strtolower($label) . ' is uploaded.';
}

function dash_assert_approval_ready(array $requirements, array $newStatuses, string $subjectLabel): void {
    $missing = [];
    foreach ($newStatuses as $key => $status) {
        if ($status === 'approved' && empty($requirements[$key]['ok'])) {
            $missing[] = (string)($requirements[$key]['label'] ?? $key);
        }
    }
    if (!empty($missing)) {
        throw new RuntimeException('Cannot approve ' . $subjectLabel . '. Missing: ' . implode(', ', $missing) . '.');
    }
}

function dash_download_document(PDO $db): void {
    dash_check_csrf();
    $targetRole = strtolower(trim((string)($_GET['target_role'] ?? '')));
    $targetId = (int)($_GET['target_id'] ?? 0);
    $documentType = strtolower(trim((string)($_GET['document_type'] ?? '')));
    if ($targetRole === 'client') $targetRole = 'studio';
    if (!in_array($targetRole, ['taker', 'studio'], true) || $targetId <= 0 || $documentType === '') {
        http_response_code(422);
        echo 'Missing verification document target';
        exit;
    }
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
        http_response_code(422);
        echo 'Invalid verification document type';
        exit;
    }
    ensureTrustSchema($db);
    $stmt = $db->prepare("SELECT {$column} FROM {$table} WHERE {$idColumn}=? LIMIT 1");
    $stmt->execute([$targetId]);
    $relativePath = (string)($stmt->fetchColumn() ?: '');
    $absolutePath = pc_verification_private_absolute_path($relativePath);
    if ($absolutePath === null || !is_file($absolutePath)) {
        http_response_code(404);
        echo 'Verification document not found';
        exit;
    }
    $mime = (new finfo(FILEINFO_MIME_TYPE))->file($absolutePath) ?: 'application/octet-stream';
    $safeName = preg_replace('/[^a-zA-Z0-9_.-]/', '_', $documentType) . '.' . pathinfo($absolutePath, PATHINFO_EXTENSION);
    header_remove('Content-Type');
    header('Content-Type: ' . $mime);
    header('Content-Length: ' . filesize($absolutePath));
    header('Content-Disposition: inline; filename="' . $safeName . '"');
    readfile($absolutePath);
    exit;
}

function dash_save_verification(PDO $db, array $statuses): void {
    dash_check_csrf();
    if (($_POST['confirm_review'] ?? '') !== '1') {
        throw new RuntimeException('Confirm the verification update before submitting.');
    }
    $targetRole = strtolower(trim((string)($_POST['target_role'] ?? '')));
    $targetId = (int)($_POST['target_id'] ?? 0);
    if ($targetRole === 'client') $targetRole = 'studio';
    if (!in_array($targetRole, ['taker', 'studio'], true) || $targetId <= 0) {
        throw new RuntimeException('Missing verification target.');
    }
    $notes = trim((string)($_POST['admin_notes'] ?? ''));
    if ((function_exists('mb_strlen') ? mb_strlen($notes) : strlen($notes)) > 2000) {
        throw new RuntimeException('Admin notes are too long.');
    }
    $notes = $notes !== '' ? $notes : null;
    ensureTrustSchema($db);
    if ($targetRole === 'taker') {
        $aadhaarStatus = dash_post_status('aadhaar_status', $statuses);
        $portfolioStatus = dash_post_status('portfolio_status', $statuses);
        $socialStatus = dash_post_status('social_status', $statuses);
        $oldStmt = $db->prepare(
            'SELECT
                t.id AS taker_id,
                tv.aadhaar_front_url,
                CASE WHEN tv.aadhaar_front_url IS NULL OR tv.aadhaar_front_url="" THEN 0 ELSE 1 END AS has_aadhaar_front,
                COALESCE(tv.portfolio_photo_count, 0) AS portfolio_photo_count,
                tv.portfolio_device_summary,
                tv.social_url AS submitted_social_url,
                COALESCE(tv.social_url, t.social_link_additional1, t.social_link_additional2, t.portfolio_url, t.instagram_url, t.youtube_url) AS social_url,
                t.portfolio_url,
                t.instagram_url,
                t.youtube_url,
                t.social_link_additional1,
                t.social_link_additional2
             FROM takers t
             LEFT JOIN taker_verifications tv ON tv.taker_id=t.id
             WHERE t.id=?
             LIMIT 1'
        );
        $oldStmt->execute([$targetId]);
        $oldTakerDocs = $oldStmt->fetch() ?: [];
        if (empty($oldTakerDocs)) {
            throw new RuntimeException('Taker was not found.');
        }
        dash_assert_approval_ready(
            dash_taker_requirements($oldTakerDocs),
            ['aadhaar_status' => $aadhaarStatus, 'portfolio_status' => $portfolioStatus, 'social_status' => $socialStatus],
            'taker verification'
        );
        $stmt = $db->prepare(
            "INSERT INTO taker_verifications(taker_id, aadhaar_status, portfolio_status, social_status, admin_notes)
             VALUES(?,?,?,?,?)
             ON DUPLICATE KEY UPDATE aadhaar_status=VALUES(aadhaar_status), portfolio_status=VALUES(portfolio_status),
                social_status=VALUES(social_status), admin_notes=VALUES(admin_notes), updated_at=NOW()"
        );
        $stmt->execute([
            $targetId,
            $aadhaarStatus,
            $portfolioStatus,
            $socialStatus,
            $notes,
        ]);
        if (in_array($aadhaarStatus, ['rejected', 'not_submitted'], true)) {
            pc_delete_verification_private_file($oldTakerDocs['aadhaar_front_url'] ?? null);
            $db->prepare('UPDATE taker_verifications SET aadhaar_front_url=NULL WHERE taker_id=?')->execute([$targetId]);
        }
        dash_send_verification_rejection_email(
            $db,
            'taker',
            $targetId,
            dash_rejected_labels(
                ['aadhaar_status' => 'Aadhaar', 'portfolio_status' => 'Portfolio', 'social_status' => 'Social profile'],
                ['aadhaar_status' => $aadhaarStatus, 'portfolio_status' => $portfolioStatus, 'social_status' => $socialStatus]
            ),
            $notes ?? ''
        );
        pc_audit_log('admin_verification_update', null, 'admin', 'taker', $targetId, [
            'aadhaar_status' => $aadhaarStatus,
            'portfolio_status' => $portfolioStatus,
            'social_status' => $socialStatus,
        ]);
        return;
    }
    $businessStatus = dash_post_status('business_status', $statuses);
    $ownerAadhaarStatus = dash_post_status('owner_aadhaar_status', $statuses);
    $oldStmt = $db->prepare(
        'SELECT
            c.id AS client_id,
            sv.verification_path,
            sv.gstin,
            sv.google_maps_url,
            sv.gst_certificate_url,
            sv.shop_license_url,
            sv.signboard_url,
            sv.owner_aadhaar_url,
            CASE WHEN sv.gst_certificate_url IS NULL OR sv.gst_certificate_url="" THEN 0 ELSE 1 END AS has_gst_certificate,
            CASE WHEN sv.shop_license_url IS NULL OR sv.shop_license_url="" THEN 0 ELSE 1 END AS has_shop_license,
            CASE WHEN sv.signboard_url IS NULL OR sv.signboard_url="" THEN 0 ELSE 1 END AS has_signboard,
            CASE WHEN sv.owner_aadhaar_url IS NULL OR sv.owner_aadhaar_url="" THEN 0 ELSE 1 END AS has_owner_aadhaar
         FROM clients c
         LEFT JOIN studio_verifications sv ON sv.client_id=c.id
         WHERE c.id=?
         LIMIT 1'
    );
    $oldStmt->execute([$targetId]);
    $oldStudioDocs = $oldStmt->fetch() ?: [];
    if (empty($oldStudioDocs)) {
        throw new RuntimeException('Studio was not found.');
    }
    dash_assert_approval_ready(
        dash_studio_requirements($oldStudioDocs),
        ['business_status' => $businessStatus, 'owner_aadhaar_status' => $ownerAadhaarStatus],
        'studio verification'
    );
    $stmt = $db->prepare(
        "INSERT INTO studio_verifications(client_id, business_status, owner_aadhaar_status, admin_notes)
         VALUES(?,?,?,?)
         ON DUPLICATE KEY UPDATE business_status=VALUES(business_status),
            owner_aadhaar_status=VALUES(owner_aadhaar_status), admin_notes=VALUES(admin_notes), updated_at=NOW()"
    );
    $stmt->execute([
        $targetId,
        $businessStatus,
        $ownerAadhaarStatus,
        $notes,
    ]);
    if (in_array($businessStatus, ['rejected', 'not_submitted'], true)) {
        foreach (['gst_certificate_url', 'shop_license_url', 'signboard_url'] as $column) {
            pc_delete_verification_private_file($oldStudioDocs[$column] ?? null);
        }
        $db->prepare(
            'UPDATE studio_verifications
             SET gst_certificate_url=NULL, shop_license_url=NULL, signboard_url=NULL
             WHERE client_id=?'
        )->execute([$targetId]);
    }
    if (in_array($ownerAadhaarStatus, ['rejected', 'not_submitted'], true)) {
        pc_delete_verification_private_file($oldStudioDocs['owner_aadhaar_url'] ?? null);
        $db->prepare('UPDATE studio_verifications SET owner_aadhaar_url=NULL WHERE client_id=?')->execute([$targetId]);
    }
    dash_send_verification_rejection_email(
        $db,
        'studio',
        $targetId,
        dash_rejected_labels(
            ['business_status' => 'Business proof', 'owner_aadhaar_status' => 'Owner Aadhaar'],
            ['business_status' => $businessStatus, 'owner_aadhaar_status' => $ownerAadhaarStatus]
        ),
        $notes ?? ''
    );
    pc_audit_log('admin_verification_update', null, 'admin', 'studio', $targetId, [
        'business_status' => $businessStatus,
        'owner_aadhaar_status' => $ownerAadhaarStatus,
    ]);
}

function dash_rejected_labels(array $labelsByKey, array $statuses): array {
    $rejected = [];
    foreach ($labelsByKey as $key => $label) {
        if (($statuses[$key] ?? null) === 'rejected') {
            $rejected[] = $label;
        }
    }
    return $rejected;
}

function dash_send_verification_rejection_email(PDO $db, string $targetRole, int $targetId, array $rejected, string $notes): void {
    if (empty($rejected)) return;
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
        $stmt = $db->prepare('SELECT t.full_name AS name, u.email FROM takers t JOIN users u ON u.id=t.user_id WHERE t.id=? LIMIT 1');
    } else {
        $stmt = $db->prepare('SELECT c.name, u.email FROM clients c JOIN users u ON u.id=c.user_id WHERE c.id=? LIMIT 1');
    }
    $stmt->execute([$targetId]);
    $row = $stmt->fetch();
    $email = trim((string)($row['email'] ?? ''));
    if ($email === '') return;
    $name = trim((string)($row['name'] ?? 'PhotoConnect user')) ?: 'PhotoConnect user';
    $message = $notes !== '' ? $notes : 'Please upload a clearer or correct document and submit verification again.';
    $html = '<p>Hello ' . dash_e($name) . ',</p><p>Your PhotoConnect verification documents were rejected.</p><p><strong>Rejected:</strong> ' . dash_e($items) . '</p><p>' . dash_e($message) . '</p><p>Please update the rejected items and submit again from the app.</p>';
    $text = "Hello {$name},\n\nYour PhotoConnect verification documents were rejected.\nRejected: {$items}\n\n{$message}\n\nPlease update the rejected items and submit again from the app.";
    pc_send_email($email, 'PhotoConnect verification documents rejected', $html, $text);
}

function dash_counts(PDO $db): array {
    ensureTrustSchema($db);
    return [
        'pending_takers' => (int)$db->query("SELECT COUNT(*) FROM taker_verifications WHERE aadhaar_status='pending' OR portfolio_status='pending' OR social_status='pending'")->fetchColumn(),
        'pending_studios' => (int)$db->query("SELECT COUNT(*) FROM studio_verifications WHERE business_status='pending' OR owner_aadhaar_status='pending'")->fetchColumn(),
        'unverified' => (int)$db->query("
            SELECT
                (SELECT COUNT(*)
                 FROM takers t
                 LEFT JOIN taker_verifications tv ON tv.taker_id=t.id
                 WHERE COALESCE(tv.aadhaar_status, 'not_submitted')!='approved'
                    OR COALESCE(tv.portfolio_status, 'not_submitted')!='approved'
                    OR COALESCE(tv.social_status, 'not_submitted')!='approved')
              + (SELECT COUNT(*)
                 FROM clients c
                 LEFT JOIN studio_verifications sv ON sv.client_id=c.id
                 WHERE COALESCE(sv.business_status, 'not_submitted')!='approved')
        ")->fetchColumn(),
    ];
}

function dash_search_analytics(PDO $db): array {
    pc_ensure_search_insights_schema($db);
    $summary = $db->query("
        SELECT
            COUNT(*) AS searches,
            SUM(CASE WHEN result_count=0 THEN 1 ELSE 0 END) AS no_results,
            COUNT(DISTINCT NULLIF(location_text, '')) AS places,
            COALESCE(ROUND(AVG(NULLIF(applied_radius_km, 0)), 1), 0) AS avg_radius
        FROM search_events
        WHERE event_type='search' AND created_at >= DATE_SUB(NOW(), INTERVAL 14 DAY)
    ")->fetch() ?: [];
    $top = $db->query("
        SELECT COALESCE(NULLIF(location_text, ''), NULLIF(query_text, '')) AS term,
               COUNT(*) AS search_count,
               AVG(result_count) AS avg_results,
               MAX(created_at) AS last_seen
        FROM search_events
        WHERE event_type='search'
          AND created_at >= DATE_SUB(NOW(), INTERVAL 14 DAY)
          AND COALESCE(NULLIF(location_text, ''), NULLIF(query_text, '')) IS NOT NULL
        GROUP BY term
        ORDER BY search_count DESC, last_seen DESC
        LIMIT 12
    ")->fetchAll();
    $noResults = $db->query("
        SELECT COALESCE(NULLIF(location_text, ''), NULLIF(query_text, '')) AS term,
               COUNT(*) AS search_count,
               MAX(created_at) AS last_seen
        FROM search_events
        WHERE event_type='search'
          AND result_count=0
          AND created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
          AND COALESCE(NULLIF(location_text, ''), NULLIF(query_text, '')) IS NOT NULL
        GROUP BY term
        ORDER BY search_count DESC, last_seen DESC
        LIMIT 12
    ")->fetchAll();
    $alerts = (int)$db->query("SELECT COUNT(*) FROM saved_search_alerts WHERE is_active=1")->fetchColumn();
    return ['summary' => $summary, 'top' => $top, 'no_results' => $noResults, 'alerts' => $alerts];
}

function dash_ensure_admin_schema(PDO $db): void {
    $db->exec(
        "CREATE TABLE IF NOT EXISTS admin_user_blocks (
            user_id INT UNSIGNED NOT NULL PRIMARY KEY,
            reason VARCHAR(500) DEFAULT NULL,
            blocked_by VARCHAR(80) DEFAULT 'dashboard',
            blocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT fk_admin_user_blocks_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );
}

function dash_scalar(PDO $db, string $sql, array $params = [], int $default = 0): int {
    try {
        $stmt = $db->prepare($sql);
        $stmt->execute($params);
        $value = $stmt->fetchColumn();
        return $value === false ? $default : (int)$value;
    } catch (Throwable $e) {
        return $default;
    }
}

function dash_feature_row(PDO $db, string $label, string $table, ?string $dateColumn = 'created_at', string $where = '1=1'): array {
    if (!tableExists($db, $table)) {
        return ['label' => $label, 'total' => 0, 'last_30' => 0, 'last_7' => 0, 'last_seen' => null];
    }
    $total = dash_scalar($db, "SELECT COUNT(*) FROM {$table} WHERE {$where}");
    $last30 = $dateColumn !== null ? dash_scalar($db, "SELECT COUNT(*) FROM {$table} WHERE {$where} AND {$dateColumn} >= DATE_SUB(NOW(), INTERVAL 30 DAY)") : 0;
    $last7 = $dateColumn !== null ? dash_scalar($db, "SELECT COUNT(*) FROM {$table} WHERE {$where} AND {$dateColumn} >= DATE_SUB(NOW(), INTERVAL 7 DAY)") : 0;
    $lastSeen = null;
    if ($dateColumn !== null) {
        try {
            $lastSeen = $db->query("SELECT MAX({$dateColumn}) FROM {$table} WHERE {$where}")->fetchColumn() ?: null;
        } catch (Throwable $e) {
            $lastSeen = null;
        }
    }
    return ['label' => $label, 'total' => $total, 'last_30' => $last30, 'last_7' => $last7, 'last_seen' => $lastSeen];
}

function dash_active_user_count(PDO $db, int $days): int {
    $days = max(1, min(365, $days));
    $queries = ["SELECT id AS user_id FROM users WHERE updated_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)"];
    if (tableExists($db, 'takers')) $queries[] = "SELECT user_id FROM takers WHERE user_id IS NOT NULL AND updated_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
    if (tableExists($db, 'clients')) $queries[] = "SELECT user_id FROM clients WHERE user_id IS NOT NULL AND created_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
    if (tableExists($db, 'bookings')) {
        $queries[] = "SELECT c.user_id FROM bookings b JOIN clients c ON c.id=b.client_id WHERE c.user_id IS NOT NULL AND b.updated_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
        $queries[] = "SELECT t.user_id FROM bookings b JOIN takers t ON t.id=b.taker_id WHERE t.user_id IS NOT NULL AND b.updated_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
    }
    if (tableExists($db, 'events')) {
        $queries[] = "SELECT c.user_id FROM events e JOIN clients c ON c.id=e.client_id WHERE c.user_id IS NOT NULL AND e.updated_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
        $queries[] = "SELECT t.user_id FROM events e JOIN takers t ON t.id=e.taker_id WHERE t.user_id IS NOT NULL AND e.updated_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
    }
    if (tableExists($db, 'taker_posts')) $queries[] = "SELECT t.user_id FROM taker_posts p JOIN takers t ON t.id=p.taker_id WHERE t.user_id IS NOT NULL AND p.updated_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
    if (tableExists($db, 'search_events')) {
        $queries[] = "SELECT c.user_id FROM search_events s JOIN clients c ON s.actor_role='client' AND c.id=s.actor_id WHERE c.user_id IS NOT NULL AND s.created_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
        $queries[] = "SELECT t.user_id FROM search_events s JOIN takers t ON s.actor_role='taker' AND t.id=s.actor_id WHERE t.user_id IS NOT NULL AND s.created_at >= DATE_SUB(NOW(), INTERVAL {$days} DAY)";
    }
    return dash_scalar($db, 'SELECT COUNT(DISTINCT user_id) FROM (' . implode(' UNION ', $queries) . ') active_users');
}

function dash_admin_overview(PDO $db): array {
    dash_ensure_admin_schema($db);
    return [
        'users' => dash_scalar($db, 'SELECT COUNT(*) FROM users'),
        'takers' => tableExists($db, 'takers') ? dash_scalar($db, 'SELECT COUNT(*) FROM takers') : 0,
        'clients' => tableExists($db, 'clients') ? dash_scalar($db, 'SELECT COUNT(*) FROM clients') : 0,
        'active_30' => dash_active_user_count($db, 30),
        'active_60' => dash_active_user_count($db, 60),
        'blocked' => dash_scalar($db, 'SELECT COUNT(*) FROM admin_user_blocks'),
        'verified_takers' => tableExists($db, 'taker_verifications') ? dash_scalar($db, "SELECT COUNT(*) FROM taker_verifications WHERE aadhaar_status='approved' AND portfolio_status='approved' AND social_status='approved'") : 0,
        'verified_studios' => tableExists($db, 'studio_verifications') ? dash_scalar($db, "SELECT COUNT(*) FROM studio_verifications WHERE business_status='approved'") : 0,
        'unverified_90' => dash_scalar($db, "
            SELECT COUNT(*) FROM users u
            WHERE u.created_at < DATE_SUB(NOW(), INTERVAL 90 DAY)
              AND (
                EXISTS (
                    SELECT 1 FROM takers t
                    LEFT JOIN taker_verifications tv ON tv.taker_id=t.id
                    WHERE t.user_id=u.id
                      AND (COALESCE(tv.aadhaar_status,'not_submitted')!='approved'
                        OR COALESCE(tv.portfolio_status,'not_submitted')!='approved'
                        OR COALESCE(tv.social_status,'not_submitted')!='approved')
                )
                OR EXISTS (
                    SELECT 1 FROM clients c
                    LEFT JOIN studio_verifications sv ON sv.client_id=c.id
                    WHERE c.user_id=u.id AND COALESCE(sv.business_status,'not_submitted')!='approved'
                )
              )
        "),
    ];
}

function dash_feature_usage(PDO $db): array {
    pc_ensure_search_insights_schema($db);
    $rows = [
        dash_feature_row($db, 'Accounts created', 'users'),
        dash_feature_row($db, 'Creator searches', 'search_events', 'created_at', "event_type='search'"),
        dash_feature_row($db, 'Search result clicks', 'search_events', 'created_at', "event_type='click'"),
        dash_feature_row($db, 'Favorites', 'taker_favorites'),
        dash_feature_row($db, 'Bookings', 'bookings'),
        dash_feature_row($db, 'Events notebook entries', 'events'),
        dash_feature_row($db, 'Creator posts', 'taker_posts'),
        dash_feature_row($db, 'Uploaded post images', 'taker_post_images'),
        dash_feature_row($db, 'Post likes', 'taker_post_likes'),
        dash_feature_row($db, 'Image likes', 'taker_post_image_likes'),
        dash_feature_row($db, 'Saved posts', 'taker_post_saves'),
        dash_feature_row($db, 'Creator reviews', 'reviews'),
        dash_feature_row($db, 'Studio reviews', 'studio_reviews'),
        dash_feature_row($db, 'Notifications', 'notifications'),
        dash_feature_row($db, 'Support tickets', 'help_tickets'),
        dash_feature_row($db, 'Saved search alerts', 'saved_search_alerts'),
    ];
    usort($rows, static fn($a, $b) => ((int)$b['last_30'] <=> (int)$a['last_30']) ?: ((int)$b['total'] <=> (int)$a['total']));
    return $rows;
}

function dash_location_breakdown(PDO $db): array {
    if (!tableExists($db, 'takers')) {
        return [];
    }
    return $db->query(
        "SELECT COALESCE(NULLIF(TRIM(state), ''), 'Unknown') AS state,
                COALESCE(NULLIF(TRIM(city), ''), 'Unknown') AS city,
                COUNT(*) AS takers,
                SUM(CASE WHEN is_active=1 THEN 1 ELSE 0 END) AS active_takers,
                AVG(avg_rating) AS avg_rating
         FROM takers
         GROUP BY state, city
         ORDER BY takers DESC, state, city
         LIMIT 40"
    )->fetchAll(PDO::FETCH_ASSOC);
}

function dash_dir_stats(string $path): array {
    $bytes = 0;
    $files = 0;
    if (!is_dir($path)) {
        return ['bytes' => 0, 'files' => 0];
    }
    try {
        $iterator = new RecursiveIteratorIterator(
            new RecursiveDirectoryIterator($path, FilesystemIterator::SKIP_DOTS),
            RecursiveIteratorIterator::LEAVES_ONLY
        );
        foreach ($iterator as $file) {
            if ($file->isFile()) {
                $files++;
                $bytes += max(0, (int)$file->getSize());
            }
        }
    } catch (Throwable $e) {
        return ['bytes' => $bytes, 'files' => $files];
    }
    return ['bytes' => $bytes, 'files' => $files];
}

function dash_format_bytes(int $bytes): string {
    $units = ['B', 'KB', 'MB', 'GB', 'TB'];
    $value = max(0, $bytes);
    $unit = 0;
    while ($value >= 1024 && $unit < count($units) - 1) {
        $value /= 1024;
        $unit++;
    }
    return ($unit === 0 ? (string)(int)$value : number_format($value, 1)) . ' ' . $units[$unit];
}

function dash_storage_stats(PDO $db): array {
    $root = getProjectRootPath();
    $original = dash_dir_stats($root . DIRECTORY_SEPARATOR . 'PhotoConnectImages' . DIRECTORY_SEPARATOR . 'photos' . DIRECTORY_SEPARATOR . 'original');
    $cache = dash_dir_stats($root . DIRECTORY_SEPARATOR . 'PhotoConnectImages' . DIRECTORY_SEPARATOR . 'photos' . DIRECTORY_SEPARATOR . 'cache');
    $verification = dash_dir_stats($root . DIRECTORY_SEPARATOR . 'PhotoConnectPrivate' . DIRECTORY_SEPARATOR . 'verification_docs');
    $geocodeCacheRows = tableExists($db, 'location_geocode_cache') ? dash_scalar($db, 'SELECT COUNT(*) FROM location_geocode_cache') : 0;
    $searchAlertRows = tableExists($db, 'saved_search_alerts') ? dash_scalar($db, 'SELECT COUNT(*) FROM saved_search_alerts') : 0;
    return [
        'original' => $original,
        'cache' => $cache,
        'verification' => $verification,
        'total_bytes' => $original['bytes'] + $cache['bytes'] + $verification['bytes'],
        'total_files' => $original['files'] + $cache['files'] + $verification['files'],
        'geocode_cache_rows' => $geocodeCacheRows,
        'search_alert_cache_rows' => $searchAlertRows,
        'db_post_images' => tableExists($db, 'taker_post_images') ? dash_scalar($db, 'SELECT COUNT(*) FROM taker_post_images') : 0,
        'db_portfolio_samples' => tableExists($db, 'portfolio_samples') ? dash_scalar($db, 'SELECT COUNT(*) FROM portfolio_samples') : 0,
        'profile_images' => dash_scalar($db, "SELECT (SELECT COUNT(*) FROM takers WHERE profile_image_url IS NOT NULL AND profile_image_url!='') + (SELECT COUNT(*) FROM clients WHERE profile_image_url IS NOT NULL AND profile_image_url!='')"),
    ];
}

function dash_fetch_admin_users(PDO $db, string $query, string $filter, int $limit = 100): array {
    dash_ensure_admin_schema($db);
    $where = '1=1';
    $params = [];
    $query = trim($query);
    if ($query !== '') {
        $like = '%' . $query . '%';
        $where .= " AND (
            u.email LIKE ? OR u.phone LIKE ?
            OR EXISTS(SELECT 1 FROM takers t WHERE t.user_id=u.id AND (t.full_name LIKE ? OR t.email LIKE ? OR t.phone LIKE ? OR t.city LIKE ? OR t.state LIKE ?))
            OR EXISTS(SELECT 1 FROM clients c WHERE c.user_id=u.id AND (c.name LIKE ? OR c.email LIKE ? OR c.phone LIKE ?))
        )";
        array_push($params, $like, $like, $like, $like, $like, $like, $like, $like, $like, $like);
    }
    $stmt = $db->prepare(
        "SELECT u.id, u.email, u.phone, u.created_at, u.updated_at,
            b.reason AS block_reason, b.blocked_at,
            (SELECT GROUP_CONCAT(CONCAT(id, ':', full_name, ':', is_active) SEPARATOR '||') FROM takers WHERE user_id=u.id) AS taker_profiles,
            (SELECT GROUP_CONCAT(CONCAT(id, ':', name, ':', is_active) SEPARATOR '||') FROM clients WHERE user_id=u.id) AS client_profiles,
            (SELECT GROUP_CONCAT(DISTINCT city ORDER BY city SEPARATOR ', ') FROM takers WHERE user_id=u.id AND city IS NOT NULL AND city!='') AS cities,
            (SELECT GROUP_CONCAT(DISTINCT state ORDER BY state SEPARATOR ', ') FROM takers WHERE user_id=u.id AND state IS NOT NULL AND state!='') AS states,
            (SELECT MAX(updated_at) FROM takers WHERE user_id=u.id) AS taker_last,
            (SELECT MAX(created_at) FROM clients WHERE user_id=u.id) AS client_last,
            (SELECT MAX(bk.updated_at) FROM bookings bk JOIN clients c ON c.id=bk.client_id WHERE c.user_id=u.id) AS client_booking_last,
            (SELECT MAX(bk.updated_at) FROM bookings bk JOIN takers t ON t.id=bk.taker_id WHERE t.user_id=u.id) AS taker_booking_last,
            (SELECT MAX(p.updated_at) FROM taker_posts p JOIN takers t ON t.id=p.taker_id WHERE t.user_id=u.id) AS taker_post_last,
            (SELECT MAX(e.updated_at) FROM events e JOIN clients c ON c.id=e.client_id WHERE c.user_id=u.id) AS client_event_last,
            (SELECT MAX(e.updated_at) FROM events e JOIN takers t ON t.id=e.taker_id WHERE t.user_id=u.id) AS taker_event_last,
            (SELECT MAX(s.created_at) FROM search_events s JOIN clients c ON s.actor_role='client' AND c.id=s.actor_id WHERE c.user_id=u.id) AS client_search_last,
            (SELECT MAX(s.created_at) FROM search_events s JOIN takers t ON s.actor_role='taker' AND t.id=s.actor_id WHERE t.user_id=u.id) AS taker_search_last,
            EXISTS(
                SELECT 1 FROM takers t JOIN taker_verifications tv ON tv.taker_id=t.id
                WHERE t.user_id=u.id AND tv.aadhaar_status='approved' AND tv.portfolio_status='approved' AND tv.social_status='approved'
            ) AS has_verified_taker,
            EXISTS(
                SELECT 1 FROM clients c JOIN studio_verifications sv ON sv.client_id=c.id
                WHERE c.user_id=u.id AND sv.business_status='approved'
            ) AS has_verified_studio,
            EXISTS(
                SELECT 1 FROM takers t LEFT JOIN taker_verifications tv ON tv.taker_id=t.id
                WHERE t.user_id=u.id AND (COALESCE(tv.aadhaar_status,'not_submitted')!='approved' OR COALESCE(tv.portfolio_status,'not_submitted')!='approved' OR COALESCE(tv.social_status,'not_submitted')!='approved')
            ) AS has_unverified_taker,
            EXISTS(
                SELECT 1 FROM clients c LEFT JOIN studio_verifications sv ON sv.client_id=c.id
                WHERE c.user_id=u.id AND COALESCE(sv.business_status,'not_submitted')!='approved'
            ) AS has_unverified_studio
         FROM users u
         LEFT JOIN admin_user_blocks b ON b.user_id=u.id
         WHERE {$where}
         ORDER BY COALESCE(u.updated_at, u.created_at) DESC
         LIMIT 500"
    );
    $stmt->execute($params);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
    $filtered = [];
    foreach ($rows as $row) {
        $lastActivity = max(array_map(static fn($v) => strtotime((string)$v) ?: 0, [
            $row['updated_at'] ?? null,
            $row['taker_last'] ?? null,
            $row['client_last'] ?? null,
            $row['client_booking_last'] ?? null,
            $row['taker_booking_last'] ?? null,
            $row['taker_post_last'] ?? null,
            $row['client_event_last'] ?? null,
            $row['taker_event_last'] ?? null,
            $row['client_search_last'] ?? null,
            $row['taker_search_last'] ?? null,
        ]));
        $createdAt = strtotime((string)($row['created_at'] ?? '')) ?: time();
        $row['last_activity_at'] = $lastActivity > 0 ? date('Y-m-d H:i:s', $lastActivity) : null;
        $row['usage_status'] = $lastActivity >= strtotime('-60 days') ? 'active' : 'inactive';
        $row['inactive_since'] = $lastActivity > 0 ? date('Y-m-d', $lastActivity) : date('Y-m-d', $createdAt);
        $row['is_blocked'] = !empty($row['blocked_at']);
        $row['is_verified'] = !empty($row['has_verified_taker']) || !empty($row['has_verified_studio']);
        $row['is_unverified'] = !empty($row['has_unverified_taker']) || !empty($row['has_unverified_studio']);
        $unverified90 = $row['is_unverified'] && $createdAt < strtotime('-90 days');
        $active30 = $lastActivity >= strtotime('-30 days');
        $active60 = $lastActivity >= strtotime('-60 days');
        $include = match ($filter) {
            'active_30' => $active30,
            'active_60' => $active60,
            'inactive_60' => !$active60,
            'blocked' => $row['is_blocked'],
            'unblocked' => !$row['is_blocked'],
            'verified' => $row['is_verified'],
            'unverified' => $row['is_unverified'],
            'unverified_90' => $unverified90,
            'all' => true,
            default => true,
        };
        if ($include) {
            $filtered[] = $row;
        }
        if (count($filtered) >= $limit) {
            break;
        }
    }
    return $filtered;
}

function dash_profile_pills(string $profiles, string $role, string $csrf): string {
    $profiles = trim($profiles);
    if ($profiles === '') return '<span class="muted">None</span>';
    $html = [];
    foreach (explode('||', $profiles) as $profile) {
        [$id, $name, $active] = array_pad(explode(':', $profile, 3), 3, '');
        $isVisible = (int)$active === 1;
        $html[] = '<form method="POST" class="inline-form">'
            . '<input type="hidden" name="csrf" value="' . dash_e($csrf) . '">'
            . '<input type="hidden" name="admin_user_action" value="profile_active">'
            . '<input type="hidden" name="profile_role" value="' . dash_e($role) . '">'
            . '<input type="hidden" name="profile_id" value="' . dash_e($id) . '">'
            . '<input type="hidden" name="is_active" value="' . ($isVisible ? '0' : '1') . '">'
            . '<button class="profile-pill ' . ($isVisible ? 'active' : 'inactive') . '" type="submit" title="Toggle profile visibility">'
            . dash_e(($role === 'taker' ? 'T' : 'C') . '#' . $id . ' ' . $name . ' - ' . ($isVisible ? 'visible' : 'hidden'))
            . '</button></form>';
    }
    return implode(' ', $html);
}

function dash_handle_admin_user_action(PDO $db): void {
    dash_check_csrf();
    dash_ensure_admin_schema($db);
    $action = (string)($_POST['admin_user_action'] ?? '');
    if ($action === 'block' || $action === 'unblock') {
        $userId = (int)($_POST['user_id'] ?? 0);
        if ($userId <= 0) throw new RuntimeException('Missing user id.');
        if ($action === 'block') {
            $reason = trim((string)($_POST['reason'] ?? ''));
            $stmt = $db->prepare('INSERT INTO admin_user_blocks(user_id, reason, blocked_by) VALUES(?,?,?) ON DUPLICATE KEY UPDATE reason=VALUES(reason), blocked_by=VALUES(blocked_by), blocked_at=NOW()');
            $stmt->execute([$userId, $reason !== '' ? $reason : null, 'dashboard']);
            pc_revoke_user_sessions($db, $userId);
            pc_audit_log('admin_user_blocked', null, 'admin', 'user', $userId, ['reason' => $reason]);
        } else {
            $db->prepare('DELETE FROM admin_user_blocks WHERE user_id=?')->execute([$userId]);
            pc_revoke_user_sessions($db, $userId);
            pc_audit_log('admin_user_unblocked', null, 'admin', 'user', $userId);
        }
        return;
    }
    if ($action === 'profile_active') {
        $role = (string)($_POST['profile_role'] ?? '');
        $profileId = (int)($_POST['profile_id'] ?? 0);
        $active = (int)($_POST['is_active'] ?? 0) === 1 ? 1 : 0;
        if (!in_array($role, ['taker', 'client'], true) || $profileId <= 0) {
            throw new RuntimeException('Missing profile target.');
        }
        $table = $role === 'taker' ? 'takers' : 'clients';
        $db->prepare("UPDATE {$table} SET is_active=? WHERE id=?")->execute([$active, $profileId]);
        pc_audit_log('admin_profile_active_changed', null, 'admin', $role, $profileId, ['is_active' => $active]);
    }
}

function dash_fetch_takers(PDO $db, string $status, int $limit): array {
    $nameColumn = firstExistingColumn($db, 'takers', ['full_name', 'name']) ?: 'full_name';
    $updatedExpr = tableHasColumn($db, 'takers', 'updated_at')
        ? 'COALESCE(tv.updated_at, t.updated_at)'
        : (tableHasColumn($db, 'takers', 'created_at') ? 'COALESCE(tv.updated_at, t.created_at)' : 'tv.updated_at');
    $where = '';
    $params = [];
    if ($status !== 'all') {
        $where = "WHERE COALESCE(tv.aadhaar_status, 'not_submitted')=?
            OR COALESCE(tv.portfolio_status, 'not_submitted')=?
            OR COALESCE(tv.social_status, 'not_submitted')=?";
        $params = [$status, $status, $status];
    }
    $stmt = $db->prepare(
        "SELECT t.id AS taker_id, t.{$nameColumn} AS full_name, u.email, u.phone,
            COALESCE(tv.aadhaar_status, 'not_submitted') AS aadhaar_status,
            COALESCE(tv.portfolio_status, 'not_submitted') AS portfolio_status,
            COALESCE(tv.portfolio_photo_count, 0) AS portfolio_photo_count,
            tv.portfolio_device_summary,
            COALESCE(tv.social_status, 'not_submitted') AS social_status,
            tv.social_url AS submitted_social_url,
            COALESCE(tv.social_url, t.social_link_additional1, t.social_link_additional2, t.portfolio_url, t.instagram_url, t.youtube_url) AS social_url,
            t.portfolio_url,
            t.instagram_url,
            t.youtube_url,
            t.social_link_additional1,
            t.social_link_additional2,
            tv.admin_notes, {$updatedExpr} AS updated_at,
            CASE WHEN tv.aadhaar_front_url IS NULL OR tv.aadhaar_front_url='' THEN 0 ELSE 1 END AS has_aadhaar_front
         FROM takers t
         LEFT JOIN taker_verifications tv ON tv.taker_id=t.id
         LEFT JOIN users u ON u.id=t.user_id
         {$where}
         ORDER BY
            CASE
                WHEN COALESCE(tv.aadhaar_status, 'not_submitted')='pending'
                  OR COALESCE(tv.portfolio_status, 'not_submitted')='pending'
                  OR COALESCE(tv.social_status, 'not_submitted')='pending' THEN 0
                WHEN COALESCE(tv.aadhaar_status, 'not_submitted')!='approved'
                  OR COALESCE(tv.portfolio_status, 'not_submitted')!='approved'
                  OR COALESCE(tv.social_status, 'not_submitted')!='approved' THEN 1
                ELSE 2
            END,
            updated_at DESC
         LIMIT {$limit}"
    );
    $stmt->execute($params);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
    foreach ($rows as &$row) $row['trust'] = pc_taker_trust_summary($db, (int)$row['taker_id']);
    return $rows;
}

function dash_fetch_studios(PDO $db, string $status, int $limit): array {
    $updatedExpr = tableHasColumn($db, 'clients', 'updated_at')
        ? 'COALESCE(sv.updated_at, c.updated_at)'
        : (tableHasColumn($db, 'clients', 'created_at') ? 'COALESCE(sv.updated_at, c.created_at)' : 'sv.updated_at');
    $where = '';
    $params = [];
    if ($status !== 'all') {
        $where = "WHERE COALESCE(sv.business_status, 'not_submitted')=?
            OR COALESCE(sv.owner_aadhaar_status, 'not_submitted')=?";
        $params = [$status, $status];
    }
    $stmt = $db->prepare(
        "SELECT c.id AS client_id, c.name, u.email, u.phone,
            COALESCE(sv.business_status, 'not_submitted') AS business_status,
            sv.verification_path, sv.gstin, sv.google_maps_url,
            COALESCE(sv.owner_aadhaar_status, 'not_submitted') AS owner_aadhaar_status,
            sv.admin_notes, {$updatedExpr} AS updated_at,
            CASE WHEN sv.gst_certificate_url IS NULL OR sv.gst_certificate_url='' THEN 0 ELSE 1 END AS has_gst_certificate,
            CASE WHEN sv.shop_license_url IS NULL OR sv.shop_license_url='' THEN 0 ELSE 1 END AS has_shop_license,
            CASE WHEN sv.signboard_url IS NULL OR sv.signboard_url='' THEN 0 ELSE 1 END AS has_signboard,
            CASE WHEN sv.owner_aadhaar_url IS NULL OR sv.owner_aadhaar_url='' THEN 0 ELSE 1 END AS has_owner_aadhaar
         FROM clients c
         LEFT JOIN studio_verifications sv ON sv.client_id=c.id
         LEFT JOIN users u ON u.id=c.user_id
         {$where}
         ORDER BY
            CASE
                WHEN COALESCE(sv.business_status, 'not_submitted')='pending'
                  OR COALESCE(sv.owner_aadhaar_status, 'not_submitted')='pending' THEN 0
                WHEN COALESCE(sv.business_status, 'not_submitted')!='approved' THEN 1
                ELSE 2
            END,
            updated_at DESC
         LIMIT {$limit}"
    );
    $stmt->execute($params);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
    foreach ($rows as &$row) $row['trust'] = pc_studio_trust_summary($db, (int)$row['client_id']);
    return $rows;
}

if (isset($_POST['password'])) {
    $configuredAdminKey = trim((string)ADMIN_API_KEY);
    if ($configuredAdminKey === '') {
        $error = "ADMIN_API_KEY is not configured.";
    } elseif (function_exists('pc_is_weak_admin_key') && pc_is_weak_admin_key($configuredAdminKey)) {
        $error = "ADMIN_API_KEY is too weak. Set a private key with at least 24 characters in .env.";
    } elseif (hash_equals($configuredAdminKey, (string)$_POST['password'])) {
        $_SESSION['authenticated'] = true;
        dash_csrf();
    } else {
        $error = "Invalid password!";
    }
}

if (isset($_GET['logout'])) {
    session_destroy();
    header("Location: supportDashboard.php");
    exit;
}

$isAuthenticated = isset($_SESSION['authenticated']) && $_SESSION['authenticated'] === true;
$activeViewRaw = (string)($_GET['view'] ?? 'support');
$activeView = in_array($activeViewRaw, ['support', 'verification', 'search', 'usage', 'users', 'storage'], true) ? $activeViewRaw : 'support';
$verificationType = strtolower(trim((string)($_GET['type'] ?? 'all')));
$verificationStatus = strtolower(trim((string)($_GET['status'] ?? 'pending')));
$verificationLimit = min(100, max(10, (int)($_GET['limit'] ?? 50)));
$adminUserQuery = trim((string)($_GET['user_q'] ?? ''));
$adminUserFilter = strtolower(trim((string)($_GET['user_filter'] ?? 'all')));
if (!in_array($verificationType, ['all', 'taker', 'studio'], true)) $verificationType = 'all';
if (!in_array($verificationStatus, array_merge(['all'], $STATUSES), true)) $verificationStatus = 'pending';
if (!in_array($adminUserFilter, ['all', 'active_30', 'active_60', 'inactive_60', 'blocked', 'unblocked', 'verified', 'unverified', 'unverified_90'], true)) $adminUserFilter = 'all';

if ($isAuthenticated) {
    if (isset($_GET['download_doc'])) {
        try {
            $db = getDB();
            dash_download_document($db);
        } catch (Throwable $e) {
            $dbError = $e->getMessage();
        }
    }

    if (isset($_POST['target_role'])) {
        try {
            $db = getDB();
            dash_save_verification($db, $STATUSES);
            $_SESSION['dashboard_flash'] = ['type' => 'success', 'message' => 'Verification updated.'];
            header('Location: supportDashboard.php?view=verification&type=' . urlencode($verificationType) . '&status=' . urlencode($verificationStatus) . '&limit=' . urlencode((string)$verificationLimit));
            exit;
        } catch (Throwable $e) {
            $_SESSION['dashboard_flash'] = ['type' => 'error', 'message' => $e->getMessage()];
            header('Location: supportDashboard.php?view=verification&type=' . urlencode($verificationType) . '&status=' . urlencode($verificationStatus) . '&limit=' . urlencode((string)$verificationLimit));
            exit;
        }
    }

    if (isset($_POST['admin_user_action'])) {
        try {
            $db = getDB();
            dash_handle_admin_user_action($db);
            $_SESSION['dashboard_flash'] = ['type' => 'success', 'message' => 'User setting updated.'];
            header('Location: supportDashboard.php?view=users&user_q=' . urlencode($adminUserQuery) . '&user_filter=' . urlencode($adminUserFilter));
            exit;
        } catch (Throwable $e) {
            $_SESSION['dashboard_flash'] = ['type' => 'error', 'message' => $e->getMessage()];
            header('Location: supportDashboard.php?view=users&user_q=' . urlencode($adminUserQuery) . '&user_filter=' . urlencode($adminUserFilter));
            exit;
        }
    }

    if (isset($_POST['delete_ticket_id'])) {
        try {
            dash_check_csrf();
            $db = getDB();
            $stmt = $db->prepare("DELETE FROM help_tickets WHERE id = ?");
            $stmt->execute([(int)$_POST['delete_ticket_id']]);
        } catch (Throwable $e) {
            $dbError = "Failed to delete: " . $e->getMessage();
        }
    }

    if (isset($_POST['clear_all'])) {
        try {
            dash_check_csrf();
            $db = getDB();
            $db->exec("DELETE FROM help_tickets");
        } catch (Throwable $e) {
            $dbError = "Failed to clear all: " . $e->getMessage();
        }
    }
}

if ($isAuthenticated) {
    $tickets = [];
    $verificationCounts = ['pending_takers' => 0, 'pending_studios' => 0, 'unverified' => 0];
    $searchAnalytics = ['summary' => [], 'top' => [], 'no_results' => [], 'alerts' => 0];
    $adminOverview = [];
    $featureUsage = [];
    $adminUsers = [];
    $locationBreakdown = [];
    $storageStats = [];
    $takers = [];
    $studios = [];
    try {
        $db = getDB();
        $stmt = $db->query("SELECT * FROM help_tickets ORDER BY created_at DESC");
        $tickets = $stmt->fetchAll(PDO::FETCH_ASSOC);
        ensureTrustSchema($db);
        $verificationCounts = dash_counts($db);
        $searchAnalytics = dash_search_analytics($db);
        $adminOverview = dash_admin_overview($db);
        $featureUsage = dash_feature_usage($db);
        $locationBreakdown = dash_location_breakdown($db);
        if ($activeView === 'users') {
            $adminUsers = dash_fetch_admin_users($db, $adminUserQuery, $adminUserFilter, 100);
        }
        if ($activeView === 'storage') {
            $storageStats = dash_storage_stats($db);
        }
        if ($verificationType === 'all' || $verificationType === 'taker') {
            $takers = dash_fetch_takers($db, $verificationStatus, $verificationLimit);
        }
        if ($verificationType === 'all' || $verificationType === 'studio') {
            $studios = dash_fetch_studios($db, $verificationStatus, $verificationLimit);
        }
    } catch (PDOException $e) {
        $dbError = "Database error: " . $e->getMessage();
    }
}
$flash = $_SESSION['dashboard_flash'] ?? null;
unset($_SESSION['dashboard_flash']);
?>
<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PhotoConnect Support Dashboard</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Outfit:wght@500;600;700&display=swap" rel="stylesheet">
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root[data-theme="dark"] {
            --bg-base: #000000;
            --bg-sidebar: #09090b;
            --bg-card: #09090b;
            --bg-hover: #18181b;
            --border-subtle: #27272a;
            --border-focus: #3f3f46;
            --text-main: #fafafa;
            --text-muted: #a1a1aa;
            --accent-primary: #06b6d4; /* cyan */
            --accent-hover: #0891b2;
            --accent-glow: rgba(6, 182, 212, 0.15);
            --danger: #ef4444;
            --danger-bg: rgba(239, 68, 68, 0.1);
            --success: #10b981;
            --success-bg: rgba(16, 185, 129, 0.1);
            --warn: #f59e0b;
            --warn-bg: rgba(245, 158, 11, 0.1);
        }
        
        :root[data-theme="light"] {
            --bg-base: #f8fafc;
            --bg-sidebar: #ffffff;
            --bg-card: #ffffff;
            --bg-hover: #f1f5f9;
            --border-subtle: #e2e8f0;
            --border-focus: #cbd5e1;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --accent-primary: #0ea5e9;
            --accent-hover: #0284c7;
            --accent-glow: rgba(14, 165, 233, 0.15);
            --danger: #ef4444;
            --danger-bg: rgba(239, 68, 68, 0.1);
            --success: #10b981;
            --success-bg: rgba(16, 185, 129, 0.1);
            --warn: #f59e0b;
            --warn-bg: rgba(245, 158, 11, 0.1);
        }

        * { box-sizing: border-box; }
        body {
            margin: 0; padding: 0;
            background-color: var(--bg-base);
            color: var(--text-main);
            font-family: 'Inter', sans-serif;
            -webkit-font-smoothing: antialiased;
            transition: background-color 0.3s, color 0.3s;
            overflow-x: hidden;
        }

        h1, h2, h3, h4 { font-family: 'Outfit', sans-serif; font-weight: 600; margin: 0; }
        a { color: var(--accent-primary); text-decoration: none; transition: opacity 0.2s; }
        a:hover { opacity: 0.8; }
        
        .btn {
            display: inline-flex; align-items: center; justify-content: center;
            padding: 10px 16px; border-radius: 8px; font-weight: 500; font-size: 14px;
            cursor: pointer; transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
            border: 1px solid transparent; outline: none; font-family: 'Inter', sans-serif;
        }
        .btn-primary {
            background-color: var(--accent-primary); color: #fff;
            box-shadow: 0 4px 14px var(--accent-glow);
        }
        .btn-primary:hover { background-color: var(--accent-hover); transform: translateY(-1px); }
        .btn-outline {
            background-color: transparent; border-color: var(--border-subtle); color: var(--text-main);
        }
        .btn-outline:hover { background-color: var(--bg-hover); border-color: var(--border-focus); }
        .btn-danger { background-color: var(--danger-bg); color: var(--danger); border-color: transparent; }
        .btn-danger:hover { background-color: var(--danger); color: #fff; }

        /* Login */
        .login-wrapper { display: flex; align-items: center; justify-content: center; min-height: 100vh; background: radial-gradient(circle at 50% -20%, var(--accent-glow), var(--bg-base) 60%); }
        .login-box {
            background: var(--bg-card); padding: 48px; border-radius: 16px;
            border: 1px solid var(--border-subtle); width: 100%; max-width: 400px;
            box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
        }
        .login-box h1 { font-size: 28px; margin-bottom: 32px; text-align: center; }
        .input-group { margin-bottom: 20px; }
        .input-group label { display: block; margin-bottom: 8px; color: var(--text-muted); font-size: 13px; font-weight: 500; }
        .input-group input {
            width: 100%; padding: 12px 16px; background: var(--bg-base);
            border: 1px solid var(--border-subtle); border-radius: 8px; color: var(--text-main);
            font-size: 15px; transition: all 0.2s; outline: none;
        }
        .input-group input:focus { border-color: var(--accent-primary); box-shadow: 0 0 0 2px var(--accent-glow); }

        /* Dashboard Layout */
        .dashboard-layout { display: flex; min-height: 100vh; }
        
        /* Sidebar */
        .sidebar {
            width: 260px; background-color: var(--bg-sidebar); border-right: 1px solid var(--border-subtle);
            display: flex; flex-direction: column; position: fixed; top: 0; bottom: 0; left: 0; z-index: 40;
        }
        .sidebar-header { padding: 24px; border-bottom: 1px solid var(--border-subtle); }
        .sidebar-logo { font-size: 20px; font-family: 'Outfit', sans-serif; font-weight: 700; background: linear-gradient(90deg, var(--text-main), var(--accent-primary)); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
        
        .sidebar-nav { flex: 1; padding: 24px 16px; display: flex; flex-direction: column; gap: 8px; overflow-y: auto; }
        .nav-link {
            display: flex; align-items: center; padding: 10px 16px; border-radius: 8px;
            color: var(--text-muted); font-size: 14px; font-weight: 500; transition: all 0.2s;
        }
        .nav-link:hover { color: var(--text-main); background-color: var(--bg-hover); }
        .nav-link.active { color: var(--accent-primary); background-color: var(--accent-glow); font-weight: 600; }
        
        .sidebar-footer { padding: 16px; border-top: 1px solid var(--border-subtle); display: flex; gap: 12px; align-items: center; }

        /* Main Content */
        .main-content { flex: 1; margin-left: 260px; padding: 40px 48px; max-width: 1400px; }
        
        .top-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 32px; }
        .page-title { font-size: 28px; }
        .page-desc { color: var(--text-muted); font-size: 15px; margin-top: 6px; }

        /* Shared Components */
        .card { background: var(--bg-card); border: 1px solid var(--border-subtle); border-radius: 12px; padding: 24px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05); }
        .message-card { padding: 16px; border-radius: 8px; margin-bottom: 24px; font-size: 14px; font-weight: 500; }
        .message-card.error { background: var(--danger-bg); color: var(--danger); border: 1px solid rgba(239, 68, 68, 0.2); }
        .message-card.success { background: var(--success-bg); color: var(--success); border: 1px solid rgba(16, 185, 129, 0.2); }
        .empty-state { text-align: center; padding: 60px 24px; border: 1px dashed var(--border-subtle); border-radius: 12px; color: var(--text-muted); }

        /* Stats Grid */
        .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 20px; margin-bottom: 32px; }
        .stat-card { display: flex; flex-direction: column; justify-content: center; }
        .stat-value { font-size: 36px; font-family: 'Outfit', sans-serif; font-weight: 600; margin-bottom: 4px; color: var(--text-main); }
        .stat-label { color: var(--text-muted); font-size: 13px; font-weight: 500; text-transform: uppercase; letter-spacing: 0.05em; }

        /* User Management & Tables */
        .filters-bar {
            display: flex; gap: 16px; background: var(--bg-card); border: 1px solid var(--border-subtle);
            border-radius: 12px; padding: 16px; margin-bottom: 24px; align-items: center; flex-wrap: wrap;
        }
        .search-input {
            flex: 1; min-width: 250px; padding: 10px 16px; border-radius: 8px; border: 1px solid var(--border-subtle);
            background: var(--bg-base); color: var(--text-main); font-size: 14px; outline: none;
        }
        .search-input:focus { border-color: var(--accent-primary); }
        .filter-select {
            padding: 10px 36px 10px 16px; border-radius: 8px; border: 1px solid var(--border-subtle);
            background: var(--bg-base); color: var(--text-main); font-size: 14px; outline: none; appearance: none;
            background-image: url("data:image/svg+xml;charset=US-ASCII,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%22292.4%22%20height%3D%22292.4%22%3E%3Cpath%20fill%3D%22%2394A3B8%22%20d%3D%22M287%2069.4a17.6%2017.6%200%200%200-13-5.4H18.4c-5%200-9.3%201.8-12.9%205.4A17.6%2017.6%200%200%200%200%2082.2c0%205%201.8%209.3%205.4%2012.9l128%20127.9c3.6%203.6%207.8%205.4%2012.8%205.4s9.2-1.8%2012.8-5.4L287%2095c3.5-3.5%205.4-7.8%205.4-12.8%200-5-1.9-9.2-5.5-12.8z%22%2F%3E%3C%2Fsvg%3E");
            background-repeat: no-repeat; background-position: right 12px top 50%; background-size: 10px auto;
        }

        .data-grid { width: 100%; border-collapse: separate; border-spacing: 0; }
        .data-grid th {
            text-align: left; padding: 12px 16px; color: var(--text-muted); font-size: 12px;
            font-weight: 500; text-transform: uppercase; letter-spacing: 0.05em; border-bottom: 1px solid var(--border-subtle);
        }
        .data-grid td { padding: 16px; vertical-align: middle; border-bottom: 1px solid var(--border-subtle); font-size: 14px; }
        .data-grid tr:last-child td { border-bottom: none; }
        .data-grid tbody tr:hover { background-color: var(--bg-hover); }
        
        .user-cell { display: flex; align-items: center; gap: 12px; }
        .avatar {
            width: 40px; height: 40px; border-radius: 10px; display: flex; align-items: center; justify-content: center;
            font-weight: 600; font-size: 16px; color: #fff; flex-shrink: 0;
        }
        .user-info strong { display: block; color: var(--text-main); font-weight: 600; font-size: 14px; margin-bottom: 2px; }
        .user-info span { display: block; color: var(--text-muted); font-size: 13px; }

        .badge {
            display: inline-flex; align-items: center; padding: 4px 10px; border-radius: 999px;
            font-size: 12px; font-weight: 500; line-height: 1; white-space: nowrap; gap: 6px;
        }
        .badge::before { content: ""; width: 6px; height: 6px; border-radius: 50%; }
        .badge.good { background: var(--success-bg); color: var(--success); } .badge.good::before { background: var(--success); }
        .badge.warn { background: var(--warn-bg); color: var(--warn); } .badge.warn::before { background: var(--warn); }
        .badge.bad { background: var(--danger-bg); color: var(--danger); } .badge.bad::before { background: var(--danger); }
        .badge.neutral { background: var(--bg-hover); color: var(--text-muted); } .badge.neutral::before { background: var(--text-muted); }

        /* Segments / Toggles */
        .segmented-control { display: flex; background: var(--bg-base); border: 1px solid var(--border-subtle); border-radius: 8px; padding: 4px; gap: 4px; }
        .segment-label { flex: 1; position: relative; cursor: pointer; }
        .segment-label input { position: absolute; opacity: 0; width: 0; height: 0; }
        .segment-btn {
            display: block; text-align: center; padding: 8px 12px; border-radius: 6px; font-size: 13px; font-weight: 500;
            color: var(--text-muted); transition: all 0.2s;
        }
        .segment-label:hover .segment-btn { color: var(--text-main); }
        .segment-label input:checked + .segment-btn { background: var(--border-focus); color: var(--text-main); box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
        .segment-label input:checked + .segment-btn.good { background: var(--success-bg); color: var(--success); }
        .segment-label input:checked + .segment-btn.bad { background: var(--danger-bg); color: var(--danger); }
        .segment-label input:checked + .segment-btn.warn { background: var(--warn-bg); color: var(--warn); }

        /* Verification Page */
        .verification-layout { display: grid; grid-template-columns: 1fr; gap: 24px; }
        .verif-card { background: var(--bg-card); border: 1px solid var(--border-subtle); border-radius: 16px; overflow: hidden; box-shadow: 0 10px 30px -10px rgba(0,0,0,0.1); }
        .verif-header { padding: 24px; border-bottom: 1px solid var(--border-subtle); display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 16px; }
        .verif-header h3 { font-size: 20px; color: var(--text-main); margin-bottom: 4px; }
        .verif-body { display: grid; grid-template-columns: 1fr; gap: 0; }
        @media (min-width: 1024px) { .verif-body { grid-template-columns: 1.5fr 2fr; } }
        .verif-docs { padding: 24px; background: var(--bg-base); border-right: 1px solid var(--border-subtle); }
        .verif-form { padding: 24px; }
        
        .doc-item { margin-bottom: 20px; }
        .doc-item-title { font-size: 13px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 8px; }
        .doc-link-box {
            display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-radius: 8px;
            background: var(--bg-card); border: 1px solid var(--border-subtle); color: var(--text-main);
            font-size: 14px; font-weight: 500; transition: all 0.2s;
        }
        .doc-link-box:hover { border-color: var(--accent-primary); color: var(--accent-primary); transform: translateY(-1px); }
        .doc-missing { color: var(--danger); font-size: 13px; font-weight: 500; }

        .status-row { margin-bottom: 24px; }
        .status-row-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
        .status-row-title { font-size: 15px; font-weight: 600; }
        
        .textarea-styled {
            width: 100%; min-height: 80px; padding: 12px; border-radius: 8px; border: 1px solid var(--border-subtle);
            background: var(--bg-base); color: var(--text-main); font-family: inherit; font-size: 14px; resize: vertical; outline: none;
        }
        .textarea-styled:focus { border-color: var(--accent-primary); }

        .chart-wrap { height: 320px; width: 100%; position: relative; margin-top: 24px; }

        @media (max-width: 768px) {
            .sidebar { transform: translateX(-100%); transition: transform 0.3s; }
            .sidebar.open { transform: translateX(0); }
            .main-content { margin-left: 0; padding: 20px; }
            .verif-body { grid-template-columns: 1fr; }
            .verif-docs { border-right: none; border-bottom: 1px solid var(--border-subtle); }
        }
    </style>
</head>
<body>
<?php if (!$isAuthenticated): ?>
    <div class="login-wrapper">
        <div class="login-box">
            <h1>Admin Gateway</h1>
            <?php if (isset($error)): ?>
                <div class="error message-card"><?php echo htmlspecialchars($error); ?></div>
            <?php endif; ?>
            <form method="POST">
                <div class="input-group">
                    <label>Master Password</label>
                    <input type="password" name="password" required autofocus>
                </div>
                <button type="submit" class="btn btn-primary" style="width:100%; padding: 14px;">Authenticate</button>
            </form>
        </div>
    </div>
<?php else: ?>
    <div class="dashboard-layout">
        <!-- Sidebar -->
        <aside class="sidebar" id="sidebar">
            <div class="sidebar-header">
                <div class="sidebar-logo">PhotoConnect</div>
            </div>
            <nav class="sidebar-nav">
                <a class="nav-link <?php echo $activeView === 'support' ? 'active' : ''; ?>" href="?view=support">Help Center</a>
                <a class="nav-link <?php echo $activeView === 'verification' ? 'active' : ''; ?>" href="?view=verification">ID Verifications</a>
                <a class="nav-link <?php echo $activeView === 'search' ? 'active' : ''; ?>" href="?view=search">Search Insights</a>
                <a class="nav-link <?php echo $activeView === 'usage' ? 'active' : ''; ?>" href="?view=usage">System Usage</a>
                <a class="nav-link <?php echo $activeView === 'users' ? 'active' : ''; ?>" href="?view=users">User Management</a>
                <a class="nav-link <?php echo $activeView === 'storage' ? 'active' : ''; ?>" href="?view=storage">System Storage</a>
            </nav>
            <div class="sidebar-footer">
                <button class="btn btn-outline" id="themeToggle" style="flex:1;">Theme 🌓</button>
                <a href="?logout=1" class="btn btn-outline" style="flex:1; text-align:center;">Logout</a>
            </div>
        </aside>

        <!-- Main Content -->
        <main class="main-content">
            
            <?php if (($verificationCounts['pending_takers'] + $verificationCounts['pending_studios']) > 0): ?>
                <div class="message-card warn" id="adminVerificationWarning" style="display:flex; justify-content:space-between; align-items:center;">
                    <div><strong>Action Required:</strong> <?php echo dash_e($verificationCounts['pending_takers'] + $verificationCounts['pending_studios']); ?> verification request(s) are waiting for review.</div>
                    <div style="display:flex; gap:8px;">
                        <a href="?view=verification&status=pending" class="btn btn-outline" style="padding: 6px 12px; font-size:12px;">Review Now</a>
                        <button class="btn btn-outline" type="button" data-warning-never style="padding: 6px 12px; font-size:12px;">Dismiss</button>
                    </div>
                </div>
            <?php endif; ?>

            <?php if (isset($dbError)): ?>
                <div class="message-card error"><?php echo dash_e($dbError); ?></div>
            <?php endif; ?>
            <?php if (is_array($flash)): ?>
                <div class="message-card <?php echo dash_e($flash['type'] ?? ''); ?>"><?php echo dash_e($flash['message'] ?? ''); ?></div>
            <?php endif; ?>

            <!-- View: Support Tickets -->
            <?php if ($activeView === 'support'): ?>
                <div class="top-bar">
                    <div>
                        <h2 class="page-title">Help Center</h2>
                        <div class="page-desc">Manage user support requests and device logs.</div>
                    </div>
                    <?php if (!empty($tickets)): ?>
                        <form method="POST" style="margin: 0;" onsubmit="return confirm('Delete ALL tickets?');">
                            <input type="hidden" name="csrf" value="<?php echo dash_e(dash_csrf()); ?>">
                            <input type="hidden" name="clear_all" value="1">
                            <button type="submit" class="btn btn-danger">Clear All Tickets</button>
                        </form>
                    <?php endif; ?>
                </div>

                <?php if (!empty($tickets)): ?>
                    <div class="filters-bar">
                        <input type="text" id="ticketSearch" class="search-input" placeholder="Search tickets by contact, ID, or content...">
                    </div>
                <?php endif; ?>

                <?php if (empty($tickets)): ?>
                    <div class="empty-state">
                        <h2>Inbox Zero</h2>
                        <p>No pending support requests at the moment.</p>
                    </div>
                <?php else: ?>
                    <div id="ticketContainer" style="display:grid; gap:20px;">
                    <?php foreach ($tickets as $ticket): ?>
                        <div class="card" data-search-content="<?php echo dash_e(strtolower($ticket['phone'].' '.$ticket['user_role'].' '.$ticket['user_id'].' '.$ticket['problem'])); ?>">
                            <div style="display:flex; justify-content:space-between; margin-bottom:16px;">
                                <div style="display:flex; align-items:center; gap:12px;">
                                    <div class="avatar" style="background:var(--accent-primary);"><?php echo strtoupper(substr($ticket['user_role'],0,1)); ?></div>
                                    <div>
                                        <strong style="font-size:16px;"><?php echo dash_e($ticket['phone']); ?></strong>
                                        <div style="color:var(--text-muted); font-size:13px; margin-top:2px;">
                                            <span style="text-transform:capitalize;"><?php echo dash_e($ticket['user_role']); ?></span> (ID: <?php echo dash_e($ticket['user_id']); ?>) &bull; <?php echo dash_e($ticket['created_at']); ?>
                                        </div>
                                    </div>
                                </div>
                                <form method="POST" style="margin:0;" onsubmit="return confirm('Delete this ticket?');">
                                    <input type="hidden" name="csrf" value="<?php echo dash_e(dash_csrf()); ?>">
                                    <input type="hidden" name="delete_ticket_id" value="<?php echo dash_e($ticket['id']); ?>">
                                    <button type="submit" class="btn btn-outline" style="padding:6px 10px; font-size:12px;">Resolve & Delete</button>
                                </form>
                            </div>
                            <div style="font-size:15px; line-height:1.6; margin-bottom:20px; white-space:pre-wrap;"><?php echo dash_e($ticket['problem']); ?></div>
                            <?php if (!empty($ticket['logs'])): ?>
                                <div style="background:#020617; color:#818cf8; padding:16px; border-radius:8px; font-family:monospace; font-size:12px; max-height:200px; overflow-y:auto; white-space:pre-wrap; border:1px solid var(--border-focus);"><?php echo dash_e($ticket['logs']); ?></div>
                            <?php endif; ?>
                        </div>
                    <?php endforeach; ?>
                    </div>
                <?php endif; ?>

            <!-- View: Search Insights -->
            <?php elseif ($activeView === 'search'): ?>
                <?php $searchSummary = $searchAnalytics['summary'] ?? []; ?>
                <div class="top-bar">
                    <div>
                        <h2 class="page-title">Search Insights</h2>
                        <div class="page-desc">Analyze how users are finding creators.</div>
                    </div>
                </div>

                <div class="stats-grid">
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e((int)($searchSummary['searches'] ?? 0)); ?></div><div class="stat-label">Searches (14d)</div></div>
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e((int)($searchSummary['no_results'] ?? 0)); ?></div><div class="stat-label">No-Result Searches</div></div>
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e((int)($searchAnalytics['alerts'] ?? 0)); ?></div><div class="stat-label">Active Alerts</div></div>
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e((string)($searchSummary['avg_radius'] ?? '0')); ?> km</div><div class="stat-label">Avg Radius</div></div>
                </div>

                <div class="card" style="margin-bottom:32px;">
                    <div class="chart-wrap"><canvas id="searchChart"></canvas></div>
                </div>

                <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(400px, 1fr)); gap:24px;">
                    <div class="card">
                        <h3 style="margin-bottom:20px; border-bottom:1px solid var(--border-subtle); padding-bottom:12px;">Trending Terms</h3>
                        <?php if (empty($searchAnalytics['top'])): ?><div class="empty-state" style="padding:30px;">No data</div><?php else: ?>
                            <table style="width:100%; border-collapse:collapse; font-size:14px;">
                            <?php foreach ($searchAnalytics['top'] as $row): ?>
                                <tr style="border-bottom:1px solid var(--border-subtle);">
                                    <td style="padding:12px 0;"><strong><?php echo dash_e($row['term']); ?></strong></td>
                                    <td style="padding:12px 0; text-align:right; color:var(--text-muted);"><?php echo dash_e((int)$row['search_count']); ?> searches</td>
                                </tr>
                            <?php endforeach; ?>
                            </table>
                        <?php endif; ?>
                    </div>
                    <div class="card">
                        <h3 style="margin-bottom:20px; border-bottom:1px solid var(--border-subtle); padding-bottom:12px;">Failed Searches (No Results)</h3>
                        <?php if (empty($searchAnalytics['no_results'])): ?><div class="empty-state" style="padding:30px;">No data</div><?php else: ?>
                            <table style="width:100%; border-collapse:collapse; font-size:14px;">
                            <?php foreach ($searchAnalytics['no_results'] as $row): ?>
                                <tr style="border-bottom:1px solid var(--border-subtle);">
                                    <td style="padding:12px 0;"><strong><?php echo dash_e($row['term']); ?></strong></td>
                                    <td style="padding:12px 0; text-align:right; color:var(--danger);"><?php echo dash_e((int)$row['search_count']); ?> fails</td>
                                </tr>
                            <?php endforeach; ?>
                            </table>
                        <?php endif; ?>
                    </div>
                </div>

            <!-- View: System Usage -->
            <?php elseif ($activeView === 'usage'): ?>
                <div class="top-bar">
                    <div>
                        <h2 class="page-title">System Usage</h2>
                        <div class="page-desc">High-level metrics on platform adoption.</div>
                    </div>
                </div>

                <div class="stats-grid">
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e($adminOverview['users'] ?? 0); ?></div><div class="stat-label">Total Accounts</div></div>
                    <div class="card stat-card"><div class="stat-value" style="color:var(--success);"><?php echo dash_e($adminOverview['active_30'] ?? 0); ?></div><div class="stat-label">Used App (30d)</div></div>
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e($adminOverview['takers'] ?? 0); ?></div><div class="stat-label">Creators</div></div>
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e($adminOverview['clients'] ?? 0); ?></div><div class="stat-label">Clients</div></div>
                </div>

                <div style="display:grid; grid-template-columns:1fr 1fr; gap:24px; margin-bottom:32px;">
                    <div class="card">
                        <h3 style="margin-bottom:16px;">Activity Breakdown</h3>
                        <div class="chart-wrap" style="height:250px;"><canvas id="usageChart"></canvas></div>
                    </div>
                    <div class="card">
                        <h3 style="margin-bottom:16px;">Verification Status</h3>
                        <table style="width:100%; border-collapse:collapse; font-size:14px;">
                            <tr style="border-bottom:1px solid var(--border-subtle);"><td style="padding:16px 0;">Verified Creators</td><td style="text-align:right; font-weight:600;"><?php echo dash_e($adminOverview['verified_takers'] ?? 0); ?></td></tr>
                            <tr style="border-bottom:1px solid var(--border-subtle);"><td style="padding:16px 0;">Verified Studios</td><td style="text-align:right; font-weight:600;"><?php echo dash_e($adminOverview['verified_studios'] ?? 0); ?></td></tr>
                            <tr><td style="padding:16px 0; color:var(--danger);">Stale Unverified (>90d)</td><td style="text-align:right; font-weight:600; color:var(--danger);"><?php echo dash_e($adminOverview['unverified_90'] ?? 0); ?></td></tr>
                        </table>
                    </div>
                </div>

                <div class="card">
                    <h3 style="margin-bottom:20px;">Feature Usage</h3>
                    <div style="overflow-x:auto;">
                        <table class="data-grid">
                            <thead><tr><th>Feature</th><th>Total</th><th>Last 30 Days</th><th>Last 7 Days</th></tr></thead>
                            <tbody>
                            <?php foreach ($featureUsage as $row): ?>
                                <tr>
                                    <td><strong><?php echo dash_e($row['label']); ?></strong></td>
                                    <td><?php echo dash_e($row['total']); ?></td>
                                    <td style="color:var(--success);"><?php echo dash_e($row['last_30']); ?></td>
                                    <td><?php echo dash_e($row['last_7']); ?></td>
                                </tr>
                            <?php endforeach; ?>
                            </tbody>
                        </table>
                    </div>
                </div>

            <!-- View: User Management -->
            <?php elseif ($activeView === 'users'): ?>
                <div class="top-bar">
                    <div>
                        <h2 class="page-title">User Management</h2>
                        <div class="page-desc">Usage activity is automatic. Block/unblock controls account permission; visible/hidden controls profile listing.</div>
                    </div>
                </div>

                <form class="filters-bar" method="GET">
                    <input type="hidden" name="view" value="users">
                    <input type="text" name="user_q" value="<?php echo dash_e($adminUserQuery); ?>" class="search-input" placeholder="Search name, phone, email...">
                    <select name="user_filter" class="filter-select">
                        <option value="all" <?php echo $adminUserFilter === 'all' ? 'selected' : ''; ?>>All Users</option>
                        <option value="active_30" <?php echo $adminUserFilter === 'active_30' ? 'selected' : ''; ?>>Used app in 30 days</option>
                        <option value="active_60" <?php echo $adminUserFilter === 'active_60' ? 'selected' : ''; ?>>Used app in 60 days</option>
                        <option value="inactive_60" <?php echo $adminUserFilter === 'inactive_60' ? 'selected' : ''; ?>>No usage for 60 days</option>
                        <option value="blocked" <?php echo $adminUserFilter === 'blocked' ? 'selected' : ''; ?>>Blocked</option>
                        <option value="verified" <?php echo $adminUserFilter === 'verified' ? 'selected' : ''; ?>>Verified</option>
                        <option value="unverified" <?php echo $adminUserFilter === 'unverified' ? 'selected' : ''; ?>>Unverified</option>
                        <option value="unverified_90" <?php echo $adminUserFilter === 'unverified_90' ? 'selected' : ''; ?>>Unverified over 90 days</option>
                    </select>
                    <button type="submit" class="btn btn-primary">Filter</button>
                </form>

                <?php if (empty($adminUsers)): ?>
                    <div class="empty-state">No users found matching criteria.</div>
                <?php else: ?>
                    <div class="card" style="padding:0; overflow:hidden;">
                        <div style="overflow-x:auto;">
                            <table class="data-grid">
                                <thead><tr><th>User Info</th><th>Profiles / visibility</th><th>Permission</th><th>Usage activity</th><th>Actions</th></tr></thead>
                                <tbody>
                                <?php foreach ($adminUsers as $row): ?>
                                    <?php 
                                        $initial = strtoupper(substr($row['email'] ?: $row['phone'] ?: 'U', 0, 1));
                                        $hue = ($row['id'] * 137) % 360;
                                    ?>
                                    <tr>
                                        <td>
                                            <div class="user-cell">
                                                <div class="avatar" style="background-color: hsl(<?php echo $hue; ?>, 70%, 45%);"><?php echo $initial; ?></div>
                                                <div class="user-info">
                                                    <strong><?php echo dash_e($row['email'] ?: 'No email'); ?></strong>
                                                    <span><?php echo dash_e($row['phone'] ?: 'No phone'); ?> &bull; ID #<?php echo dash_e($row['id']); ?></span>
                                                </div>
                                            </div>
                                        </td>
                                        <td>
                                            <div style="display:flex; flex-wrap:wrap; gap:4px; max-width:180px;">
                                                <?php echo dash_profile_pills((string)($row['taker_profiles'] ?? ''), 'taker', dash_csrf()); ?>
                                                <?php echo dash_profile_pills((string)($row['client_profiles'] ?? ''), 'client', dash_csrf()); ?>
                                            </div>
                                        </td>
                                        <td>
                                            <?php if (!empty($row['is_blocked'])): ?>
                                                <span class="badge bad">Blocked</span>
                                            <?php else: ?>
                                                <span class="badge good">Allowed</span>
                                            <?php endif; ?>
                                            <div style="margin-top:6px;">
                                                <span class="badge <?php echo !empty($row['is_verified']) ? 'good' : 'warn'; ?>" style="font-size:10px;"><?php echo !empty($row['is_verified']) ? 'Verified' : 'Unverified'; ?></span>
                                            </div>
                                        </td>
                                        <td style="color:var(--text-muted); font-size:13px;">
                                            <?php if (($row['usage_status'] ?? '') === 'active'): ?>
                                                <span class="badge good">Active by usage</span><br>
                                                Last used: <?php echo dash_e(explode(' ', $row['last_activity_at'] ?: '-')[0]); ?>
                                            <?php else: ?>
                                                <span class="badge warn">Inactive by usage</span><br>
                                                Not used since: <?php echo dash_e($row['inactive_since'] ?? '-'); ?>
                                            <?php endif; ?>
                                            <br>Joined: <?php echo dash_e(explode(' ', $row['created_at'])[0]); ?>
                                        </td>
                                        <td>
                                            <?php if (!empty($row['is_blocked'])): ?>
                                                <form method="POST" style="margin:0;" onsubmit="return confirm('Unblock user?');">
                                                    <input type="hidden" name="csrf" value="<?php echo dash_e(dash_csrf()); ?>">
                                                    <input type="hidden" name="admin_user_action" value="unblock"><input type="hidden" name="user_id" value="<?php echo dash_e($row['id']); ?>">
                                                    <button class="btn btn-outline" style="padding:6px 12px; font-size:12px;">Unblock</button>
                                                </form>
                                            <?php else: ?>
                                                <form method="POST" style="margin:0; display:flex; gap:6px;" onsubmit="return confirm('Block user?');">
                                                    <input type="hidden" name="csrf" value="<?php echo dash_e(dash_csrf()); ?>">
                                                    <input type="hidden" name="admin_user_action" value="block"><input type="hidden" name="user_id" value="<?php echo dash_e($row['id']); ?>">
                                                    <input name="reason" placeholder="Reason..." style="padding:6px; border-radius:6px; border:1px solid var(--border-subtle); background:var(--bg-base); color:var(--text-main); font-size:12px; width:100px; outline:none;">
                                                    <button class="btn btn-danger" style="padding:6px 12px; font-size:12px;">Block</button>
                                                </form>
                                            <?php endif; ?>
                                        </td>
                                    </tr>
                                <?php endforeach; ?>
                                </tbody>
                            </table>
                        </div>
                    </div>
                <?php endif; ?>

            <!-- View: Storage -->
            <?php elseif ($activeView === 'storage'): ?>
                <div class="top-bar">
                    <div>
                        <h2 class="page-title">System Storage</h2>
                        <div class="page-desc">Manage physical files and database records.</div>
                    </div>
                </div>
                <div class="stats-grid">
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e(dash_format_bytes((int)($storageStats['total_bytes'] ?? 0))); ?></div><div class="stat-label">Total Allocated</div></div>
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e(dash_format_bytes((int)($storageStats['original']['bytes'] ?? 0))); ?></div><div class="stat-label">Original Photos</div></div>
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e(dash_format_bytes((int)($storageStats['cache']['bytes'] ?? 0))); ?></div><div class="stat-label">Cache Memory</div></div>
                    <div class="card stat-card"><div class="stat-value"><?php echo dash_e(dash_format_bytes((int)($storageStats['verification']['bytes'] ?? 0))); ?></div><div class="stat-label">Secure Docs</div></div>
                </div>
                <div class="card" style="padding:0; overflow:hidden;">
                    <div style="overflow-x:auto;">
                        <table class="data-grid">
                            <thead><tr><th>Storage area</th><th>Files / rows</th><th>Size</th><th>Meaning</th></tr></thead>
                            <tbody>
                                <tr>
                                    <td><strong>Original photos</strong></td>
                                    <td><?php echo dash_e((int)($storageStats['original']['files'] ?? 0)); ?> files</td>
                                    <td><?php echo dash_e(dash_format_bytes((int)($storageStats['original']['bytes'] ?? 0))); ?></td>
                                    <td class="muted">Main uploaded images stored permanently.</td>
                                </tr>
                                <tr>
                                    <td><strong>Cache memory</strong></td>
                                    <td><?php echo dash_e((int)($storageStats['cache']['files'] ?? 0)); ?> files</td>
                                    <td><?php echo dash_e(dash_format_bytes((int)($storageStats['cache']['bytes'] ?? 0))); ?></td>
                                    <td class="muted">Generated thumbnails/medium images that can be rebuilt.</td>
                                </tr>
                                <tr>
                                    <td><strong>Verification documents</strong></td>
                                    <td><?php echo dash_e((int)($storageStats['verification']['files'] ?? 0)); ?> files</td>
                                    <td><?php echo dash_e(dash_format_bytes((int)($storageStats['verification']['bytes'] ?? 0))); ?></td>
                                    <td class="muted">Private Aadhaar/business proof files.</td>
                                </tr>
                                <tr>
                                    <td><strong>Location cache records</strong></td>
                                    <td><?php echo dash_e((int)($storageStats['geocode_cache_rows'] ?? 0)); ?> rows</td>
                                    <td class="muted">Database</td>
                                    <td class="muted">Cached city/state/geocode lookups.</td>
                                </tr>
                                <tr>
                                    <td><strong>Original image DB count</strong></td>
                                    <td><?php echo dash_e((int)($storageStats['db_post_images'] ?? 0) + (int)($storageStats['db_portfolio_samples'] ?? 0) + (int)($storageStats['profile_images'] ?? 0)); ?> rows</td>
                                    <td class="muted">Database</td>
                                    <td class="muted">Post images, portfolio samples, and profile image references.</td>
                                </tr>
                            </tbody>
                        </table>
                    </div>
                </div>

            <!-- View: ID Verifications -->
            <?php else: ?>
                <div class="top-bar">
                    <div>
                        <h2 class="page-title">ID Verifications</h2>
                        <div class="page-desc">Review and approve studio and creator identities.</div>
                    </div>
                </div>

                <form class="filters-bar" method="GET">
                    <input type="hidden" name="view" value="verification">
                    <select name="type" class="filter-select">
                        <option value="all" <?php echo $verificationType === 'all' ? 'selected' : ''; ?>>All Types</option>
                        <option value="taker" <?php echo $verificationType === 'taker' ? 'selected' : ''; ?>>Creators</option>
                        <option value="studio" <?php echo $verificationType === 'studio' ? 'selected' : ''; ?>>Studios</option>
                    </select>
                    <select name="status" class="filter-select">
                        <option value="pending" <?php echo $verificationStatus === 'pending' ? 'selected' : ''; ?>>Pending Review</option>
                        <option value="all" <?php echo $verificationStatus === 'all' ? 'selected' : ''; ?>>All Statuses</option>
                        <option value="approved" <?php echo $verificationStatus === 'approved' ? 'selected' : ''; ?>>Approved</option>
                    </select>
                    <button type="submit" class="btn btn-primary">Filter Queue</button>
                    <input type="text" id="verifSearch" class="search-input" style="margin-left:auto;" placeholder="Quick search cards...">
                </form>

                <?php 
                // Helper to render segmented control inline to avoid breaking top PHP block
                function renderSegmentedControl($name, $current) {
                    global $STATUSES;
                    $html = '<div class="segmented-control">';
                    foreach ($STATUSES as $st) {
                        $checked = $current === $st ? 'checked' : '';
                        $class = dash_status_class($st);
                        $label = dash_status_label($st);
                        $html .= "<label class='segment-label'><input type='radio' name='{$name}' value='{$st}' {$checked}><span class='segment-btn {$class}'>{$label}</span></label>";
                    }
                    $html .= '</div>';
                    return $html;
                }
                ?>

                <div class="verification-layout" id="verifContainer">
                
                <?php if ($verificationType === 'all' || $verificationType === 'taker'): ?>
                    <?php foreach ($takers as $row): ?>
                        <?php
                            $requirements = dash_taker_requirements($row);
                            $readyCount = dash_ready_count($requirements);
                            $searchStr = strtolower(($row['full_name'] ?? '').' '.($row['email'] ?? '').' '.($row['taker_id'] ?? ''));
                        ?>
                        <div class="verif-card" data-search-content="<?php echo dash_e($searchStr); ?>">
                            <div class="verif-header">
                                <div style="display:flex; align-items:center; gap:16px;">
                                    <div class="avatar" style="background:#06b6d4; width:48px; height:48px; font-size:20px; border-radius:12px;"><?php echo strtoupper(substr($row['full_name'] ?: 'C', 0, 1)); ?></div>
                                    <div>
                                        <h3><?php echo dash_e($row['full_name'] ?: 'Creator'); ?></h3>
                                        <div style="color:var(--text-muted); font-size:14px; margin-top:2px;">Creator ID #<?php echo dash_e($row['taker_id']); ?> &bull; <?php echo dash_e($row['email'] ?: 'No email'); ?></div>
                                    </div>
                                </div>
                                <span class="badge <?php echo $readyCount === count($requirements) ? 'good' : 'warn'; ?>" style="font-size:14px; padding:6px 12px;"><?php echo dash_e($readyCount); ?>/<?php echo dash_e(count($requirements)); ?> Ready</span>
                            </div>
                            
                            <div class="verif-body">
                                <!-- Documents Panel -->
                                <div class="verif-docs">
                                    <div class="doc-item">
                                        <div class="doc-item-title">Aadhaar Document</div>
                                        <?php if($row['has_aadhaar_front']): ?>
                                            <a href="?view=verification&download_doc=1&target_role=taker&target_id=<?php echo $row['taker_id']; ?>&document_type=aadhaar_front&csrf=<?php echo dash_e(dash_csrf()); ?>" target="_blank" class="doc-link-box">📄 View Aadhaar Card</a>
                                        <?php else: ?><div class="doc-missing">No file uploaded</div><?php endif; ?>
                                    </div>
                                    <div class="doc-item">
                                        <div class="doc-item-title">Portfolio Evidence</div>
                                        <div style="font-size:14px; color:var(--text-main);"><?php echo dash_e((int)$row['portfolio_photo_count']); ?> photos uploaded<br><span style="color:var(--text-muted);"><?php echo dash_e($row['portfolio_device_summary'] ?: 'No EXIF metadata found'); ?></span></div>
                                    </div>
                                    <div class="doc-item">
                                        <div class="doc-item-title">Social Links</div>
                                        <div style="font-size:14px;"><?php echo dash_taker_links($row); ?></div>
                                    </div>
                                </div>
                                
                                <!-- Form Panel -->
                                <div class="verif-form">
                                    <form method="POST" data-review-form data-confirm-message="Submit review for this Creator?">
                                        <input type="hidden" name="csrf" value="<?php echo dash_e(dash_csrf()); ?>">
                                        <input type="hidden" name="confirm_review" value="1"><input type="hidden" name="target_role" value="taker"><input type="hidden" name="target_id" value="<?php echo dash_e($row['taker_id']); ?>">
                                        
                                        <div class="status-row" data-can-approve="<?php echo !empty($requirements['aadhaar_status']['ok']) ? '1' : '0'; ?>">
                                            <div class="status-row-header">
                                                <span class="status-row-title">Aadhaar Validation</span>
                                                <?php if (empty($requirements['aadhaar_status']['ok'])): ?><span class="doc-missing">Missing reqs</span><?php endif; ?>
                                            </div>
                                            <?php echo renderSegmentedControl('aadhaar_status', $row['aadhaar_status']); ?>
                                        </div>
                                        
                                        <div class="status-row" data-can-approve="<?php echo !empty($requirements['portfolio_status']['ok']) ? '1' : '0'; ?>">
                                            <div class="status-row-header"><span class="status-row-title">Portfolio Validation</span></div>
                                            <?php echo renderSegmentedControl('portfolio_status', $row['portfolio_status']); ?>
                                        </div>
                                        
                                        <div class="status-row" data-can-approve="<?php echo !empty($requirements['social_status']['ok']) ? '1' : '0'; ?>">
                                            <div class="status-row-header"><span class="status-row-title">Social Validation</span></div>
                                            <?php echo renderSegmentedControl('social_status', $row['social_status']); ?>
                                        </div>
                                        
                                        <div style="margin-top:24px;">
                                            <label style="display:block; font-size:13px; font-weight:600; color:var(--text-muted); margin-bottom:8px;">Feedback / Rejection Notes</label>
                                            <textarea name="admin_notes" class="textarea-styled" placeholder="Leave a message for the user if rejecting..."><?php echo dash_e($row['admin_notes'] ?? ''); ?></textarea>
                                        </div>
                                        <div style="margin-top:16px; text-align:right;">
                                            <button type="submit" class="btn btn-primary" style="padding:12px 24px;">Submit Decision</button>
                                        </div>
                                    </form>
                                </div>
                            </div>
                        </div>
                    <?php endforeach; ?>
                <?php endif; ?>

                <?php if ($verificationType === 'all' || $verificationType === 'studio'): ?>
                    <?php foreach ($studios as $row): ?>
                        <?php
                            $requirements = dash_studio_requirements($row);
                            $readyCount = dash_ready_count($requirements);
                            $searchStr = strtolower(($row['name'] ?? '').' '.($row['email'] ?? '').' '.($row['client_id'] ?? ''));
                        ?>
                        <div class="verif-card" data-search-content="<?php echo dash_e($searchStr); ?>">
                            <div class="verif-header">
                                <div style="display:flex; align-items:center; gap:16px;">
                                    <div class="avatar" style="background:#a855f7; width:48px; height:48px; font-size:20px; border-radius:12px;"><?php echo strtoupper(substr($row['name'] ?: 'S', 0, 1)); ?></div>
                                    <div>
                                        <h3><?php echo dash_e($row['name'] ?: 'Studio'); ?></h3>
                                        <div style="color:var(--text-muted); font-size:14px; margin-top:2px;">Studio ID #<?php echo dash_e($row['client_id']); ?> &bull; <?php echo dash_e($row['email'] ?: 'No email'); ?></div>
                                    </div>
                                </div>
                                <span class="badge <?php echo $readyCount === count($requirements) ? 'good' : 'warn'; ?>" style="font-size:14px; padding:6px 12px;"><?php echo dash_e($readyCount); ?>/<?php echo dash_e(count($requirements)); ?> Ready</span>
                            </div>
                            
                            <div class="verif-body">
                                <div class="verif-docs">
                                    <div class="doc-item">
                                        <div class="doc-item-title">Business Path: <?php echo dash_e($row['verification_path'] ?: 'None'); ?></div>
                                        <?php if (!empty($row['gstin'])): ?>
                                            <div style="font-size:13px; color:var(--text-muted); margin:6px 0 10px;">
                                                <strong style="color:var(--text-main);">GSTIN:</strong> <?php echo dash_e($row['gstin']); ?>
                                            </div>
                                        <?php endif; ?>
                                        <div style="display:flex; flex-direction:column; gap:8px;">
                                        <?php if($row['has_gst_certificate']): ?><a href="?view=verification&download_doc=1&target_role=studio&target_id=<?php echo $row['client_id']; ?>&document_type=gst_certificate&csrf=<?php echo dash_e(dash_csrf()); ?>" target="_blank" class="doc-link-box">📄 View GST Certificate</a><?php endif; ?>
                                        <?php if($row['has_shop_license']): ?><a href="?view=verification&download_doc=1&target_role=studio&target_id=<?php echo $row['client_id']; ?>&document_type=shop_license&csrf=<?php echo dash_e(dash_csrf()); ?>" target="_blank" class="doc-link-box">📄 View Shop License</a><?php endif; ?>
                                        <?php if($row['has_signboard']): ?><a href="?view=verification&download_doc=1&target_role=studio&target_id=<?php echo $row['client_id']; ?>&document_type=signboard&csrf=<?php echo dash_e(dash_csrf()); ?>" target="_blank" class="doc-link-box">📄 View Signboard</a><?php endif; ?>
                                        <?php if($row['google_maps_url']): ?><a href="<?php echo dash_e($row['google_maps_url']); ?>" target="_blank" class="doc-link-box">📍 Open Maps</a><?php endif; ?>
                                        </div>
                                    </div>
                                    <div class="doc-item">
                                        <div class="doc-item-title">Owner Identity</div>
                                        <?php if($row['has_owner_aadhaar']): ?><a href="?view=verification&download_doc=1&target_role=studio&target_id=<?php echo $row['client_id']; ?>&document_type=owner_aadhaar&csrf=<?php echo dash_e(dash_csrf()); ?>" target="_blank" class="doc-link-box">📄 View Owner Aadhaar</a><?php else: ?><div class="doc-missing">No file uploaded</div><?php endif; ?>
                                    </div>
                                </div>
                                
                                <div class="verif-form">
                                    <form method="POST" data-review-form data-confirm-message="Submit review for this Studio?">
                                        <input type="hidden" name="csrf" value="<?php echo dash_e(dash_csrf()); ?>">
                                        <input type="hidden" name="confirm_review" value="1"><input type="hidden" name="target_role" value="studio"><input type="hidden" name="target_id" value="<?php echo dash_e($row['client_id']); ?>">
                                        
                                        <div class="status-row" data-can-approve="<?php echo !empty($requirements['business_status']['ok']) ? '1' : '0'; ?>">
                                            <div class="status-row-header">
                                                <span class="status-row-title">Business Validation</span>
                                                <?php if (empty($requirements['business_status']['ok'])): ?><span class="doc-missing">Missing reqs</span><?php endif; ?>
                                            </div>
                                            <?php echo renderSegmentedControl('business_status', $row['business_status']); ?>
                                        </div>
                                        
                                        <div class="status-row" data-can-approve="<?php echo !empty($requirements['owner_aadhaar_status']['ok']) ? '1' : '0'; ?>">
                                            <div class="status-row-header"><span class="status-row-title">Identity Validation</span></div>
                                            <?php echo renderSegmentedControl('owner_aadhaar_status', $row['owner_aadhaar_status']); ?>
                                        </div>
                                        
                                        <div style="margin-top:24px;">
                                            <label style="display:block; font-size:13px; font-weight:600; color:var(--text-muted); margin-bottom:8px;">Feedback / Rejection Notes</label>
                                            <textarea name="admin_notes" class="textarea-styled" placeholder="Leave a message..."><?php echo dash_e($row['admin_notes'] ?? ''); ?></textarea>
                                        </div>
                                        <div style="margin-top:16px; text-align:right;">
                                            <button type="submit" class="btn btn-primary" style="padding:12px 24px;">Submit Decision</button>
                                        </div>
                                    </form>
                                </div>
                            </div>
                        </div>
                    <?php endforeach; ?>
                <?php endif; ?>
                </div>
            <?php endif; ?>
        </main>
    </div>
<?php endif; ?>

<script>
(() => {
    // Theme logic
    const themeToggle = document.getElementById('themeToggle');
    if (themeToggle) {
        const current = localStorage.getItem('pc_theme') || 'dark';
        document.documentElement.setAttribute('data-theme', current);
        themeToggle.addEventListener('click', () => {
            const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('pc_theme', next);
            if(window.pcCharts) window.location.reload(); // Quick refresh for chart colors
        });
    }

    // Dismiss warnings
    const warning = document.getElementById('adminVerificationWarning');
    if (warning && localStorage.getItem('pc_admin_verify_muted') === '1') warning.style.display = 'none';
    document.querySelector('[data-warning-never]')?.addEventListener('click', () => {
        localStorage.setItem('pc_admin_verify_muted', '1');
        if (warning) warning.style.display = 'none';
    });

    // Form logic protection
    document.querySelectorAll('[data-review-form]').forEach((form) => {
        form.addEventListener('submit', (e) => {
            const blocked = Array.from(form.querySelectorAll('.status-row[data-can-approve="0"] input[value="approved"]:checked'));
            if (blocked.length > 0) {
                e.preventDefault();
                alert('Cannot approve. Requirements are missing.');
                return;
            }
            if (!confirm(form.getAttribute('data-confirm-message'))) e.preventDefault();
        });
    });

    // Filtering
    const setupFilter = (inputId, containerId, cardSelector) => {
        const input = document.getElementById(inputId);
        const container = document.getElementById(containerId);
        if (input && container) {
            input.addEventListener('input', (e) => {
                const term = e.target.value.toLowerCase();
                container.querySelectorAll(cardSelector).forEach(c => {
                    c.style.display = (c.getAttribute('data-search-content')||'').includes(term) ? '' : 'none';
                });
            });
        }
    };
    setupFilter('ticketSearch', 'ticketContainer', '.card');
    setupFilter('verifSearch', 'verifContainer', '.verif-card');

    // Charts
    window.pcCharts = [];
    const color = document.documentElement.getAttribute('data-theme') === 'dark' ? '#a1a1aa' : '#64748b';
    const gridColor = document.documentElement.getAttribute('data-theme') === 'dark' ? '#27272a' : '#e2e8f0';
    const commonOpts = {
        responsive: true, maintainAspectRatio: false,
        plugins: { legend: { labels: { color, font:{family:'Inter'} } } },
        scales: { x: { ticks:{color}, grid:{color:gridColor} }, y: { ticks:{color}, grid:{color:gridColor}, beginAtZero:true } }
    };

    const sCtx = document.getElementById('searchChart');
    if(sCtx) {
        const vals = Array.from(document.querySelectorAll('.stat-value')).map(el => parseInt(el.textContent)||0);
        window.pcCharts.push(new Chart(sCtx, {
            type: 'bar',
            data: {
                labels: ['Searches (14d)', 'No Results', 'Active Alerts', 'Unique Places'],
                datasets: [{
                    label: 'Metrics', data: [vals[0], vals[1], vals[2], 0],
                    backgroundColor: 'rgba(6, 182, 212, 0.6)', borderColor: '#06b6d4', borderWidth: 1, borderRadius: 4
                }]
            }, options: commonOpts
        }));
    }

    const uCtx = document.getElementById('usageChart');
    if(uCtx) {
        const vals = Array.from(document.querySelectorAll('.stat-value')).map(el => parseInt(el.textContent)||0);
        window.pcCharts.push(new Chart(uCtx, {
            type: 'doughnut',
            data: {
                labels: ['Used in 30 days', 'No recent usage'],
                datasets: [{ data: [vals[1], Math.max(0, vals[0]-vals[1])], backgroundColor: ['#10b981', '#3f3f46'], borderWidth:0 }]
            }, options: { maintainAspectRatio:false, plugins: { legend: { position: 'right', labels:{color} } } }
        }));
    }
})();
</script>
</body>
</html>
