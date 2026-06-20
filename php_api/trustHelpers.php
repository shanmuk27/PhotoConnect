<?php

function ensureTrustSchema(PDO $db): void
{
    $db->exec("
        CREATE TABLE IF NOT EXISTS taker_verifications (
            taker_id INT UNSIGNED PRIMARY KEY,
            aadhaar_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
            aadhaar_front_url VARCHAR(512) DEFAULT NULL,
            portfolio_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
            portfolio_photo_count INT UNSIGNED NOT NULL DEFAULT 0,
            portfolio_device_summary VARCHAR(255) DEFAULT NULL,
            portfolio_checked_at DATETIME DEFAULT NULL,
            social_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
            social_url VARCHAR(500) DEFAULT NULL,
            admin_notes TEXT DEFAULT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            CONSTRAINT fk_taker_verifications_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    ");

    $db->exec("
        CREATE TABLE IF NOT EXISTS taker_endorsements (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            taker_id INT UNSIGNED NOT NULL,
            client_id INT UNSIGNED NOT NULL,
            booking_id INT UNSIGNED NOT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY uq_taker_endorsement_client(taker_id, client_id),
            UNIQUE KEY uq_taker_endorsement_booking(booking_id),
            INDEX idx_taker_endorsements_taker(taker_id, created_at),
            INDEX idx_taker_endorsements_client(client_id, created_at),
            CONSTRAINT fk_taker_endorsements_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE,
            CONSTRAINT fk_taker_endorsements_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE,
            CONSTRAINT fk_taker_endorsements_booking FOREIGN KEY(booking_id) REFERENCES bookings(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    ");

    $db->exec("
        CREATE TABLE IF NOT EXISTS taker_portfolio_evidence (
            image_id INT UNSIGNED PRIMARY KEY,
            taker_id INT UNSIGNED NOT NULL,
            has_camera_exif TINYINT(1) NOT NULL DEFAULT 0,
            device_make VARCHAR(120) DEFAULT NULL,
            device_model VARCHAR(160) DEFAULT NULL,
            captured_at DATETIME DEFAULT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_taker_portfolio_evidence_taker(taker_id, has_camera_exif),
            CONSTRAINT fk_taker_portfolio_evidence_image FOREIGN KEY(image_id) REFERENCES taker_post_images(id) ON DELETE CASCADE,
            CONSTRAINT fk_taker_portfolio_evidence_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    ");

    $db->exec("
        CREATE TABLE IF NOT EXISTS studio_verifications (
            client_id INT UNSIGNED PRIMARY KEY,
            business_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
            verification_path ENUM('gst','shop_license','google_maps','manual') DEFAULT NULL,
            gstin VARCHAR(20) DEFAULT NULL,
            gst_certificate_url VARCHAR(512) DEFAULT NULL,
            shop_license_url VARCHAR(512) DEFAULT NULL,
            google_maps_url VARCHAR(500) DEFAULT NULL,
            signboard_url VARCHAR(512) DEFAULT NULL,
            owner_aadhaar_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
            owner_aadhaar_url VARCHAR(512) DEFAULT NULL,
            admin_notes TEXT DEFAULT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            CONSTRAINT fk_studio_verifications_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    ");

    $db->exec("
        CREATE TABLE IF NOT EXISTS studio_reviews (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            client_id INT UNSIGNED NOT NULL,
            taker_id INT UNSIGNED NOT NULL,
            booking_id INT UNSIGNED NOT NULL,
            rating TINYINT UNSIGNED NOT NULL,
            comment TEXT DEFAULT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY uq_studio_review_booking(booking_id),
            INDEX idx_studio_reviews_client(client_id, created_at),
            INDEX idx_studio_reviews_taker(taker_id, created_at),
            CONSTRAINT fk_studio_reviews_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE,
            CONSTRAINT fk_studio_reviews_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE,
            CONSTRAINT fk_studio_reviews_booking FOREIGN KEY(booking_id) REFERENCES bookings(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    ");
}

function ensureBookingClientVerificationSchema(PDO $db): void
{
    if (!tableHasColumn($db, 'bookings', 'client_verification_stage')) {
        try {
            $db->exec("ALTER TABLE bookings ADD COLUMN client_verification_stage VARCHAR(32) NOT NULL DEFAULT 'unverified' AFTER status");
        } catch (Throwable $e) {
            // Another request may have added it first.
        }
        tableHasColumn($db, 'bookings', 'client_verification_stage', true);
    }
}

function pc_studio_booking_stage(array $studioTrust): string
{
    $stage = strtolower(trim((string)($studioTrust['stage'] ?? 'unverified')));
    return in_array($stage, ['business_verified', 'trusted'], true) ? $stage : 'unverified';
}

function pc_public_url_valid(string $url): bool
{
    $url = trim($url);
    if ($url === '') return false;
    if (!preg_match('#^https?://#i', $url)) {
        $url = 'https://' . $url;
    }
    return filter_var($url, FILTER_VALIDATE_URL) !== false;
}

function pc_normalize_public_url(?string $url): ?string
{
    $url = trim((string)$url);
    if ($url === '') return null;
    if (!preg_match('#^https?://#i', $url)) {
        $url = 'https://' . $url;
    }
    return filter_var($url, FILTER_VALIDATE_URL) !== false ? $url : null;
}

function pc_is_supported_social_url(?string $url): bool
{
    $url = pc_normalize_public_url($url);
    if ($url === null) return false;
    $host = strtolower((string)parse_url($url, PHP_URL_HOST));
    $endsWith = function (string $value, string $suffix): bool {
        return $suffix === '' || substr($value, -strlen($suffix)) === $suffix;
    };
    return $host === 'instagram.com'
        || $endsWith($host, '.instagram.com')
        || $host === 'youtube.com'
        || $endsWith($host, '.youtube.com')
        || $host === 'youtu.be'
        || $endsWith($host, '.youtu.be');
}

function pc_valid_gstin(string $gstin): bool
{
    $gstin = strtoupper(trim($gstin));
    if (!preg_match('/^[0-3][0-9][A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/', $gstin)) {
        return false;
    }
    $chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
    $factor = 2;
    $sum = 0;
    for ($i = 13; $i >= 0; $i--) {
        $codePoint = strpos($chars, $gstin[$i]);
        if ($codePoint === false) return false;
        $addend = $factor * $codePoint;
        $factor = $factor === 2 ? 1 : 2;
        $sum += intdiv($addend, 36) + ($addend % 36);
    }
    $checkCodePoint = (36 - ($sum % 36)) % 36;
    return $gstin[14] === $chars[$checkCodePoint];
}

function pc_verification_private_root(): string
{
    return getProjectRootPath() . DIRECTORY_SEPARATOR . 'PhotoConnectPrivate' . DIRECTORY_SEPARATOR . 'verification_docs';
}

function pc_verification_private_relative_path(string $targetRole, int $targetId, string $documentType, string $extension): string
{
    $safeRole = preg_replace('/[^a-z_]/', '', strtolower($targetRole)) ?: 'unknown';
    $safeType = preg_replace('/[^a-z_]/', '', strtolower($documentType)) ?: 'document';
    $safeExt = preg_replace('/[^a-z0-9]/', '', strtolower($extension)) ?: 'bin';
    return $safeRole . '/' . $targetId . '/' . $safeType . '_' . bin2hex(random_bytes(16)) . '.' . $safeExt;
}

function pc_verification_private_absolute_path(string $relativePath): ?string
{
    $relativePath = ltrim(str_replace('\\', '/', trim($relativePath)), '/');
    if ($relativePath === '' || strpos($relativePath, '..') !== false) {
        return null;
    }
    $root = pc_verification_private_root();
    $fullPath = $root . DIRECTORY_SEPARATOR . str_replace('/', DIRECTORY_SEPARATOR, $relativePath);
    $parent = realpath(dirname($fullPath));
    $realRoot = realpath($root);
    if ($realRoot === false || $parent === false || !pc_string_starts_with($parent, $realRoot)) {
        return null;
    }
    return $fullPath;
}

function pc_delete_verification_private_file(?string $relativePath): void
{
    $absolutePath = pc_verification_private_absolute_path((string)$relativePath);
    $directory = $absolutePath !== null ? dirname($absolutePath) : null;
    if ($absolutePath !== null && is_file($absolutePath)) {
        @unlink($absolutePath);
    }
    if ($directory !== null) {
        pc_remove_empty_directories_up_to($directory, pc_verification_private_root());
    }
}

function pc_validate_verification_upload(array $file): array
{
    $error = (int)($file['error'] ?? UPLOAD_ERR_NO_FILE);
    if ($error !== UPLOAD_ERR_OK) {
        respond(false, 'Verification document upload failed', [], 422);
    }
    $size = (int)($file['size'] ?? 0);
    if ($size <= 0 || $size > 5 * 1024 * 1024) {
        respond(false, 'Verification document must be under 5 MB', [], 422);
    }
    $tmp = (string)($file['tmp_name'] ?? '');
    if ($tmp === '' || !is_uploaded_file($tmp)) {
        respond(false, 'Invalid verification document upload', [], 422);
    }
    $finfo = new finfo(FILEINFO_MIME_TYPE);
    $mime = (string)$finfo->file($tmp);
    $map = [
        'image/jpeg' => 'jpg',
        'image/png' => 'png',
        'image/webp' => 'webp',
        'application/pdf' => 'pdf',
    ];
    if (!isset($map[$mime])) {
        respond(false, 'Upload JPG, PNG, WebP, or PDF only', [], 422);
    }
    return ['tmp_name' => $tmp, 'mime_type' => $mime, 'extension' => $map[$mime], 'size' => $size];
}

function pc_store_verification_upload(array $file, string $targetRole, int $targetId, string $documentType): string
{
    $valid = pc_validate_verification_upload($file);
    $relativePath = pc_verification_private_relative_path($targetRole, $targetId, $documentType, $valid['extension']);
    $absolutePath = pc_verification_private_root() . DIRECTORY_SEPARATOR . str_replace('/', DIRECTORY_SEPARATOR, $relativePath);
    $directory = dirname($absolutePath);
    if (!is_dir($directory) && !mkdir($directory, 0750, true)) {
        respond(false, 'Could not create secure verification storage', [], 500);
    }
    if (!move_uploaded_file($valid['tmp_name'], $absolutePath)) {
        respond(false, 'Could not save verification document securely', [], 500);
    }
    @chmod($absolutePath, 0640);
    return $relativePath;
}

function pc_taker_trust_summary(PDO $db, int $takerId, ?string $viewerRole = null, ?int $viewerId = null): array
{
    ensureTrustSchema($db);
    $stmt = $db->prepare("
        SELECT
            t.id,
            COALESCE(t.avg_rating, 0) AS avg_rating,
            COALESCE(t.review_count, 0) AS review_count,
            COALESCE(tv.aadhaar_status, 'not_submitted') AS aadhaar_status,
            COALESCE(tv.portfolio_status, 'not_submitted') AS portfolio_status,
            COALESCE(tv.social_status, 'not_submitted') AS social_status,
            tv.social_url,
            tv.portfolio_photo_count,
            (SELECT COUNT(*) FROM bookings b WHERE b.taker_id=t.id AND b.status='Completed') AS completed_booking_count,
            (SELECT COUNT(*) FROM taker_endorsements e WHERE e.taker_id=t.id) AS endorsement_count
        FROM takers t
        LEFT JOIN taker_verifications tv ON tv.taker_id=t.id
        WHERE t.id=?
        LIMIT 1
    ");
    $stmt->execute([$takerId]);
    $row = $stmt->fetch();
    if (!$row) {
        return pc_empty_taker_trust();
    }

    $summary = pc_taker_trust_from_row($row);
    $summary['can_endorse'] = false;
    $summary['viewer_has_endorsed'] = false;
    if ($viewerRole === 'client' && (int)$viewerId > 0) {
        $summary['can_endorse'] = pc_client_has_completed_booking($db, (int)$viewerId, $takerId);
        $summary['viewer_has_endorsed'] = pc_client_has_endorsed($db, (int)$viewerId, $takerId);
    }
    return $summary;
}

function pc_photo_attestation_hmac_key(string $bearerToken): string
{
    return hash('sha256', $bearerToken, true);
}

/**
 * Validates signed photo attestation from the Android app (HMAC keyed by bearer token).
 *
 * @return array<int, array{sha256:string, client_verified:bool}>
 */
function pc_verify_photo_attestation_for_upload(
    string $bearerToken,
    int $takerId,
    int $imageCount,
    ?string $payloadRaw,
    ?string $signatureHex,
): array {
    $payloadRaw = trim((string)$payloadRaw);
    $signatureHex = strtolower(trim((string)$signatureHex));
    if ($payloadRaw === '' || $signatureHex === '') {
        respond(false, 'Missing photo verification code. Please update the app and try again.', [], 422);
    }
    if (!preg_match('/^[a-f0-9]{64}$/', $signatureHex)) {
        respond(false, 'Invalid photo verification signature', [], 422);
    }

    $expectedSig = hash_hmac('sha256', $payloadRaw, pc_photo_attestation_hmac_key($bearerToken));
    if (!hash_equals($expectedSig, $signatureHex)) {
        respond(false, 'Photo verification code could not be validated', [], 422);
    }

    $payload = json_decode($payloadRaw, true);
    if (!is_array($payload)) {
        respond(false, 'Invalid photo verification payload', [], 422);
    }
    if ((int)($payload['v'] ?? 0) !== 1) {
        respond(false, 'Unsupported photo verification version', [], 422);
    }
    if ((int)($payload['taker_id'] ?? 0) !== $takerId) {
        respond(false, 'Photo verification does not match this account', [], 422);
    }

    $issuedAt = (int)($payload['issued_at'] ?? 0);
    $maxAge = max(300, (int)pc_env('PHOTO_ATTESTATION_MAX_AGE', '900'));
    if ($issuedAt <= 0 || abs(time() - $issuedAt) > $maxAge) {
        respond(false, 'Photo verification code expired. Please try again.', [], 422);
    }

    $images = $payload['images'] ?? null;
    if (!is_array($images) || count($images) !== $imageCount) {
        respond(false, 'Photo verification does not match uploaded files', [], 422);
    }

    $normalized = [];
    foreach ($images as $index => $image) {
        if (!is_array($image)) {
            respond(false, 'Invalid photo verification entry', [], 422);
        }
        $sha256 = strtolower(trim((string)($image['sha256'] ?? '')));
        if (!preg_match('/^[a-f0-9]{64}$/', $sha256)) {
            respond(false, 'Invalid photo fingerprint in verification code', [], 422);
        }
        $clientVerified = !empty($image['client_verified']);
        if (!$clientVerified) {
            respond(false, 'Only original camera photos can be posted. Image ' . ($index + 1) . ' failed verification.', [], 422);
        }
        $normalized[] = [
            'sha256' => $sha256,
            'client_verified' => true,
        ];
    }

    return $normalized;
}

function pc_assert_upload_hashes_match_attestation(array $attestationImages, array $tmpFiles): void
{
    foreach ($attestationImages as $index => $expected) {
        $tmpFile = $tmpFiles[$index] ?? '';
        if ($tmpFile === '' || !is_file($tmpFile)) {
            respond(false, 'Uploaded file missing during verification', [], 422);
        }
        $actual = hash_file('sha256', $tmpFile);
        if (!is_string($actual) || !hash_equals($expected['sha256'], strtolower($actual))) {
            respond(false, 'Uploaded photo does not match verification code', [], 422);
        }
    }
}

function pc_extract_portfolio_exif(string $tmpFile, string $mimeType): array
{
    $make = null;
    $model = null;
    $capturedAt = null;
    if ($mimeType === 'image/jpeg' && function_exists('exif_read_data')) {
        $exif = @exif_read_data($tmpFile, 'IFD0,EXIF', true);
        if (is_array($exif)) {
            $ifd0 = is_array($exif['IFD0'] ?? null) ? $exif['IFD0'] : [];
            $exifBlock = is_array($exif['EXIF'] ?? null) ? $exif['EXIF'] : [];
            $make = trim((string)($ifd0['Make'] ?? $exifBlock['Make'] ?? '')) ?: null;
            $model = trim((string)($ifd0['Model'] ?? $exifBlock['Model'] ?? '')) ?: null;
            $rawDate = trim((string)($exifBlock['DateTimeOriginal'] ?? $ifd0['DateTime'] ?? ''));
            if ($rawDate !== '' && preg_match('/^\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}$/', $rawDate)) {
                $capturedAt = str_replace(':', '-', substr($rawDate, 0, 10)) . substr($rawDate, 10);
            }
        }
    }

    return [
        'has_camera_exif' => $make !== null || $model !== null,
        'device_make' => $make,
        'device_model' => $model,
        'captured_at' => $capturedAt,
    ];
}

function pc_record_portfolio_evidence(PDO $db, int $takerId, int $imageId, array $evidence): void
{
    $stmt = $db->prepare(
        "INSERT INTO taker_portfolio_evidence(
            image_id, taker_id, has_camera_exif, device_make, device_model, captured_at
         ) VALUES(?,?,?,?,?,?)
         ON DUPLICATE KEY UPDATE
            taker_id=VALUES(taker_id),
            has_camera_exif=VALUES(has_camera_exif),
            device_make=VALUES(device_make),
            device_model=VALUES(device_model),
            captured_at=VALUES(captured_at),
            updated_at=NOW()"
    );
    $stmt->execute([
        $imageId,
        $takerId,
        !empty($evidence['has_camera_exif']) ? 1 : 0,
        $evidence['device_make'] ?? null,
        $evidence['device_model'] ?? null,
        $evidence['captured_at'] ?? null,
    ]);
}

function pc_portfolio_verification_from_evidence(PDO $db, int $takerId): array
{
    $countStmt = $db->prepare(
        "SELECT COUNT(*)
         FROM taker_post_images i
         INNER JOIN taker_posts p ON p.id=i.post_id
         WHERE p.taker_id=?"
    );
    $countStmt->execute([$takerId]);
    $photoCount = (int)$countStmt->fetchColumn();

    $evidenceStmt = $db->prepare(
        "SELECT has_camera_exif, device_make, device_model
         FROM taker_portfolio_evidence
         WHERE taker_id=?"
    );
    $evidenceStmt->execute([$takerId]);
    $rows = $evidenceStmt->fetchAll();

    $deviceCounts = [];
    $deviceLabels = [];
    foreach ($rows as $row) {
        if ((int)($row['has_camera_exif'] ?? 0) !== 1) {
            continue;
        }
        $label = trim(implode(' ', array_filter([
            trim((string)($row['device_make'] ?? '')),
            trim((string)($row['device_model'] ?? '')),
        ])));
        if ($label === '') {
            continue;
        }
        $key = strtolower(preg_replace('/\s+/', ' ', $label));
        $deviceCounts[$key] = ($deviceCounts[$key] ?? 0) + 1;
        $deviceLabels[$key] = $label;
    }
    arsort($deviceCounts);
    $topKey = array_key_first($deviceCounts);
    $topCount = $topKey !== null ? (int)$deviceCounts[$topKey] : 0;
    $topLabel = $topKey !== null ? $deviceLabels[$topKey] : null;

    $status = $photoCount > 0 ? 'pending' : 'not_submitted';
    if ($photoCount >= 5 && $topCount >= 5) {
        $status = 'approved';
    } elseif ($photoCount >= 5) {
        $status = 'rejected';
    }

    $summary = $photoCount <= 0
        ? 'No posts uploaded yet'
        : ($topLabel !== null
        ? $topLabel . ' (' . $topCount . '/' . $photoCount . ' photos)'
        : 'No readable camera EXIF (' . $photoCount . ' photos)');

    return [
        'status' => $status,
        'photo_count' => $photoCount,
        'device_summary' => $summary,
    ];
}

function pc_refresh_taker_portfolio_verification(PDO $db, int $takerId): array
{
    $portfolio = pc_portfolio_verification_from_evidence($db, $takerId);
    $stmt = $db->prepare(
        "INSERT INTO taker_verifications(
            taker_id, portfolio_status, portfolio_photo_count, portfolio_device_summary, portfolio_checked_at
         ) VALUES(?,?,?,?,NOW())
         ON DUPLICATE KEY UPDATE
            portfolio_status=VALUES(portfolio_status),
            portfolio_photo_count=VALUES(portfolio_photo_count),
            portfolio_device_summary=VALUES(portfolio_device_summary),
            portfolio_checked_at=NOW(),
            updated_at=NOW()"
    );
    $stmt->execute([
        $takerId,
        $portfolio['status'],
        $portfolio['photo_count'],
        $portfolio['device_summary'],
    ]);
    return $portfolio;
}

function pc_empty_taker_trust(): array
{
    return [
        'stage' => 'unverified',
        'label' => 'Unverified',
        'identity_verified' => false,
        'portfolio_verified' => false,
        'social_verified' => false,
        'aadhaar_status' => 'not_submitted',
        'portfolio_status' => 'not_submitted',
        'social_status' => 'not_submitted',
        'completed_booking_count' => 0,
        'endorsement_count' => 0,
        'review_count' => 0,
        'avg_rating' => 0.0,
        'can_endorse' => false,
        'viewer_has_endorsed' => false,
    ];
}

function pc_taker_trust_from_row(array $row): array
{
    $identity = ($row['aadhaar_status'] ?? '') === 'approved';
    $portfolio = ($row['portfolio_status'] ?? '') === 'approved';
    $social = ($row['social_status'] ?? '') === 'approved';
    $statuses = [
        (string)($row['aadhaar_status'] ?? 'not_submitted'),
        (string)($row['portfolio_status'] ?? 'not_submitted'),
        (string)($row['social_status'] ?? 'not_submitted'),
    ];
    $completed = (int)($row['completed_booking_count'] ?? 0);
    $endorsements = (int)($row['endorsement_count'] ?? 0);
    $avg = (float)($row['avg_rating'] ?? 0);
    $reviews = (int)($row['review_count'] ?? 0);

    $stage = 'unverified';
    $label = 'Unverified';
    if (in_array('rejected', $statuses, true)) {
        $stage = 'rejected';
        $label = 'Verification Rejected';
    }
    if ($identity && $portfolio && $social) {
        $stage = 'verified';
        $label = 'Verified';
    }
    if ($identity && $portfolio && $social && $completed >= 1 && $endorsements >= 1) {
        $stage = 'trusted';
        $label = 'Trusted';
    }
    if ($identity && $portfolio && $social && $completed >= 3 && $endorsements >= 3 && $avg >= 4.0) {
        $stage = 'pro_verified';
        $label = 'Pro Verified';
    }

    return [
        'stage' => $stage,
        'label' => $label,
        'identity_verified' => $identity,
        'portfolio_verified' => $portfolio,
        'social_verified' => $social,
        'aadhaar_status' => $row['aadhaar_status'] ?? 'not_submitted',
        'portfolio_status' => $row['portfolio_status'] ?? 'not_submitted',
        'social_status' => $row['social_status'] ?? 'not_submitted',
        'completed_booking_count' => $completed,
        'endorsement_count' => $endorsements,
        'review_count' => $reviews,
        'avg_rating' => $avg,
    ];
}

function pc_client_has_completed_booking(PDO $db, int $clientId, int $takerId): bool
{
    $stmt = $db->prepare("SELECT id FROM bookings WHERE client_id=? AND taker_id=? AND status='Completed' LIMIT 1");
    $stmt->execute([$clientId, $takerId]);
    return (bool)$stmt->fetch();
}

function pc_client_has_endorsed(PDO $db, int $clientId, int $takerId): bool
{
    ensureTrustSchema($db);
    $stmt = $db->prepare("SELECT id FROM taker_endorsements WHERE client_id=? AND taker_id=? LIMIT 1");
    $stmt->execute([$clientId, $takerId]);
    return (bool)$stmt->fetch();
}

function pc_studio_trust_summary(PDO $db, int $clientId): array
{
    ensureTrustSchema($db);
    $createdAtSelect = tableHasColumn($db, 'clients', 'created_at') ? 'c.created_at' : 'NOW() AS created_at';
    $stmt = $db->prepare("
        SELECT
            c.id,
            $createdAtSelect,
            COALESCE(sv.business_status, 'not_submitted') AS business_status,
            COALESCE(sv.owner_aadhaar_status, 'not_submitted') AS owner_aadhaar_status,
            sv.verification_path,
            sv.gstin,
            sv.google_maps_url,
            (SELECT COUNT(*) FROM bookings b WHERE b.client_id=c.id AND b.status='Completed') AS completed_booking_count,
            (SELECT COUNT(*) FROM studio_reviews sr WHERE sr.client_id=c.id) AS rating_count,
            (SELECT COALESCE(AVG(sr.rating), 0) FROM studio_reviews sr WHERE sr.client_id=c.id) AS avg_rating
        FROM clients c
        LEFT JOIN studio_verifications sv ON sv.client_id=c.id
        WHERE c.id=?
        LIMIT 1
    ");
    $stmt->execute([$clientId]);
    $row = $stmt->fetch();
    if (!$row) {
        return pc_empty_studio_trust();
    }
    return pc_studio_trust_from_row($row);
}

function pc_empty_studio_trust(): array
{
    return [
        'stage' => 'unverified',
        'label' => 'Unverified studio',
        'business_verified' => false,
        'trusted' => false,
        'can_book' => false,
        'business_status' => 'not_submitted',
        'owner_aadhaar_status' => 'not_submitted',
        'completed_booking_count' => 0,
        'avg_rating' => 0.0,
        'rating_count' => 0,
        'earned_condition_count' => 0,
    ];
}

function pc_studio_trust_from_row(array $row): array
{
    $business = ($row['business_status'] ?? '') === 'approved';
    $completed = (int)($row['completed_booking_count'] ?? 0);
    $avg = (float)($row['avg_rating'] ?? 0);
    $ratingCount = (int)($row['rating_count'] ?? 0);
    $ownerAadhaar = ($row['owner_aadhaar_status'] ?? '') === 'approved';
    $created = strtotime((string)($row['created_at'] ?? ''));
    $sixMonths = $created > 0 && $created <= strtotime('-6 months');

    $conditions = 0;
    if ($completed >= 3) $conditions++;
    if ($ratingCount > 0 && $avg >= 4.0) $conditions++;
    if ($ownerAadhaar) $conditions++;
    if ($sixMonths) $conditions++;

    $trusted = $business && $conditions >= 2;
    return [
        'stage' => $trusted ? 'trusted' : ($business ? 'business_verified' : 'unverified'),
        'label' => $trusted ? 'Trusted studio' : ($business ? 'Business verified' : 'Unverified studio'),
        'business_verified' => $business,
        'trusted' => $trusted,
        'can_book' => $business,
        'business_status' => $row['business_status'] ?? 'not_submitted',
        'owner_aadhaar_status' => $row['owner_aadhaar_status'] ?? 'not_submitted',
        'completed_booking_count' => $completed,
        'avg_rating' => $avg,
        'rating_count' => $ratingCount,
        'earned_condition_count' => $conditions,
        'verification_path' => $row['verification_path'] ?? null,
    ];
}
