<?php
declare(strict_types=1);

require_once __DIR__ . '/../php_api/config.php';
require_once __DIR__ . '/../php_api/trustHelpers.php';

$passed = 0;
$failed = 0;

function pc_test(string $name, callable $fn): void
{
    global $passed, $failed;
    try {
        $fn();
        $passed++;
        echo "[PASS] {$name}\n";
    } catch (Throwable $e) {
        $failed++;
        echo "[FAIL] {$name}: {$e->getMessage()}\n";
    }
}

function pc_assert_same(mixed $expected, mixed $actual, string $message = ''): void
{
    if ($expected !== $actual) {
        $prefix = $message !== '' ? $message . ' ' : '';
        throw new RuntimeException(
            $prefix . 'Expected ' . var_export($expected, true) . ', got ' . var_export($actual, true)
        );
    }
}

function pc_assert_true(bool $value, string $message = 'Expected true'): void
{
    if (!$value) {
        throw new RuntimeException($message);
    }
}

function pc_assert_false(bool $value, string $message = 'Expected false'): void
{
    if ($value) {
        throw new RuntimeException($message);
    }
}

function taker_row(array $overrides = []): array
{
    return array_merge([
        'aadhaar_status' => 'not_submitted',
        'portfolio_status' => 'not_submitted',
        'social_status' => 'not_submitted',
        'completed_booking_count' => 0,
        'endorsement_count' => 0,
        'review_count' => 0,
        'avg_rating' => 0.0,
    ], $overrides);
}

function verified_taker_row(array $overrides = []): array
{
    return taker_row(array_merge([
        'aadhaar_status' => 'approved',
        'portfolio_status' => 'approved',
        'social_status' => 'approved',
    ], $overrides));
}

function studio_row(array $overrides = []): array
{
    return array_merge([
        'business_status' => 'not_submitted',
        'owner_aadhaar_status' => 'not_submitted',
        'completed_booking_count' => 0,
        'rating_count' => 0,
        'avg_rating' => 0.0,
        'created_at' => date('Y-m-d H:i:s'),
        'verification_path' => null,
    ], $overrides);
}

pc_test('normalizes public URLs', function (): void {
    pc_assert_same('https://instagram.com/example', pc_normalize_public_url('instagram.com/example'));
    pc_assert_same('http://youtube.com/@creator', pc_normalize_public_url('http://youtube.com/@creator'));
    pc_assert_same(null, pc_normalize_public_url(''));
    pc_assert_same(null, pc_normalize_public_url('not a valid url'));
});

pc_test('accepts only Instagram and YouTube social verification URLs', function (): void {
    pc_assert_true(pc_is_supported_social_url('instagram.com/example'));
    pc_assert_true(pc_is_supported_social_url('https://www.youtube.com/@creator'));
    pc_assert_true(pc_is_supported_social_url('https://youtu.be/abc123'));
    pc_assert_false(pc_is_supported_social_url('https://example.com/portfolio'));
    pc_assert_false(pc_is_supported_social_url('https://notinstagram.com/example'));
});

pc_test('validates GSTIN checksum', function (): void {
    pc_assert_true(pc_valid_gstin('07AACCM9910C1ZP'), 'Known valid GSTIN should pass');
    pc_assert_false(pc_valid_gstin('07AACCM9910C1ZZ'), 'Bad checksum should fail');
    pc_assert_false(pc_valid_gstin('BADGSTIN'), 'Bad format should fail');
});

pc_test('taker starts unverified', function (): void {
    $trust = pc_taker_trust_from_row(taker_row());
    pc_assert_same('unverified', $trust['stage']);
    pc_assert_false($trust['identity_verified']);
    pc_assert_false($trust['portfolio_verified']);
    pc_assert_false($trust['social_verified']);
});

pc_test('taker becomes verified only after all three self-verification checks pass', function (): void {
    $partial = pc_taker_trust_from_row(taker_row([
        'aadhaar_status' => 'approved',
        'portfolio_status' => 'approved',
        'social_status' => 'pending',
    ]));
    pc_assert_same('unverified', $partial['stage']);

    $trust = pc_taker_trust_from_row(verified_taker_row());
    pc_assert_same('verified', $trust['stage']);
    pc_assert_true($trust['identity_verified']);
    pc_assert_true($trust['portfolio_verified']);
    pc_assert_true($trust['social_verified']);
});

pc_test('completed work does not bypass taker document verification', function (): void {
    $trust = pc_taker_trust_from_row(taker_row([
        'completed_booking_count' => 10,
        'endorsement_count' => 10,
        'review_count' => 10,
        'avg_rating' => 5.0,
    ]));
    pc_assert_same('unverified', $trust['stage']);
});

pc_test('taker reaches trusted after verification plus first booking and endorsement', function (): void {
    $trust = pc_taker_trust_from_row(verified_taker_row([
        'completed_booking_count' => 1,
        'endorsement_count' => 1,
        'review_count' => 1,
        'avg_rating' => 4.8,
    ]));
    pc_assert_same('trusted', $trust['stage']);
});

pc_test('taker reaches pro verified only at three bookings, three endorsements, and rating 4+', function (): void {
    $almost = pc_taker_trust_from_row(verified_taker_row([
        'completed_booking_count' => 3,
        'endorsement_count' => 3,
        'review_count' => 3,
        'avg_rating' => 3.9,
    ]));
    pc_assert_same('trusted', $almost['stage']);

    $pro = pc_taker_trust_from_row(verified_taker_row([
        'completed_booking_count' => 3,
        'endorsement_count' => 3,
        'review_count' => 3,
        'avg_rating' => 4.0,
    ]));
    pc_assert_same('pro_verified', $pro['stage']);
});

pc_test('studio starts unverified and cannot book', function (): void {
    $trust = pc_studio_trust_from_row(studio_row());
    pc_assert_same('unverified', $trust['stage']);
    pc_assert_false($trust['business_verified']);
    pc_assert_false($trust['can_book']);
});

pc_test('business verified studio can book before it is trusted', function (): void {
    $trust = pc_studio_trust_from_row(studio_row([
        'business_status' => 'approved',
        'verification_path' => 'gst',
    ]));
    pc_assert_same('business_verified', $trust['stage']);
    pc_assert_true($trust['business_verified']);
    pc_assert_true($trust['can_book']);
    pc_assert_false($trust['trusted']);
});

pc_test('studio cannot become trusted without business verification', function (): void {
    $trust = pc_studio_trust_from_row(studio_row([
        'business_status' => 'pending',
        'owner_aadhaar_status' => 'approved',
        'completed_booking_count' => 3,
        'rating_count' => 3,
        'avg_rating' => 4.8,
        'created_at' => date('Y-m-d H:i:s', strtotime('-8 months')),
    ]));
    pc_assert_same('unverified', $trust['stage']);
    pc_assert_false($trust['trusted']);
    pc_assert_false($trust['can_book']);
});

pc_test('studio becomes trusted after business verification plus two earned conditions', function (): void {
    $trust = pc_studio_trust_from_row(studio_row([
        'business_status' => 'approved',
        'completed_booking_count' => 3,
        'rating_count' => 3,
        'avg_rating' => 4.2,
    ]));
    pc_assert_same('trusted', $trust['stage']);
    pc_assert_true($trust['trusted']);
    pc_assert_same(2, $trust['earned_condition_count']);
});

pc_test('studio owner Aadhaar plus six months active count as earned trust conditions', function (): void {
    $trust = pc_studio_trust_from_row(studio_row([
        'business_status' => 'approved',
        'owner_aadhaar_status' => 'approved',
        'created_at' => date('Y-m-d H:i:s', strtotime('-7 months')),
    ]));
    pc_assert_same('trusted', $trust['stage']);
    pc_assert_same(2, $trust['earned_condition_count']);
});

pc_test('studio rating condition requires at least one rating', function (): void {
    $trust = pc_studio_trust_from_row(studio_row([
        'business_status' => 'approved',
        'avg_rating' => 5.0,
        'rating_count' => 0,
    ]));
    pc_assert_same('business_verified', $trust['stage']);
    pc_assert_same(0, $trust['earned_condition_count']);
});

pc_test('deletes rejected verification file and empty folders', function (): void {
    $root = pc_verification_private_root();
    $roleDir = 'testcleanup_' . bin2hex(random_bytes(4));
    $relativePath = $roleDir . '/42/aadhaar_front_test.jpg';
    $absolutePath = $root . DIRECTORY_SEPARATOR . str_replace('/', DIRECTORY_SEPARATOR, $relativePath);
    $targetDir = dirname($absolutePath);

    if (!is_dir($targetDir) && !mkdir($targetDir, 0750, true)) {
        throw new RuntimeException('Could not create test verification directory');
    }
    file_put_contents($absolutePath, 'test');

    pc_delete_verification_private_file($relativePath);

    pc_assert_false(is_file($absolutePath), 'Verification file should be deleted');
    pc_assert_false(is_dir($targetDir), 'Empty verification target folder should be deleted');
    pc_assert_false(is_dir(dirname($targetDir)), 'Empty verification role folder should be deleted');
    @rmdir($root);
    @rmdir(dirname($root));
});

pc_test('deletes managed image cache files and empty folders', function (): void {
    $token = 'testcleanup_' . bin2hex(random_bytes(4));
    $photosRoot = getProjectRootPath() . DIRECTORY_SEPARATOR . 'PhotoConnectImages' . DIRECTORY_SEPARATOR . 'photos';
    $originalDir = $photosRoot . DIRECTORY_SEPARATOR . 'original' . DIRECTORY_SEPARATOR . 'taker_posts' . DIRECTORY_SEPARATOR . $token . DIRECTORY_SEPARATOR . '99';
    $cacheDir = $photosRoot . DIRECTORY_SEPARATOR . 'cache' . DIRECTORY_SEPARATOR . 'taker_posts' . DIRECTORY_SEPARATOR . $token . DIRECTORY_SEPARATOR . '99';
    $originalPath = $originalDir . DIRECTORY_SEPARATOR . 'image_1_' . $token . '.jpg';
    $thumbPath = $cacheDir . DIRECTORY_SEPARATOR . 'thumb_1_' . $token . '.jpg';
    $mediumPath = $cacheDir . DIRECTORY_SEPARATOR . 'medium_1_' . $token . '.jpg';

    foreach ([$originalDir, $cacheDir] as $dir) {
        if (!is_dir($dir) && !mkdir($dir, 0755, true)) {
            throw new RuntimeException('Could not create test image directory');
        }
    }
    foreach ([$originalPath, $thumbPath, $mediumPath] as $path) {
        file_put_contents($path, 'test');
    }

    pc_delete_image_and_cache($originalPath);

    pc_assert_false(is_file($originalPath), 'Original image should be deleted');
    pc_assert_false(is_file($thumbPath), 'Thumbnail cache should be deleted');
    pc_assert_false(is_file($mediumPath), 'Medium cache should be deleted');
    pc_assert_false(is_dir($originalDir), 'Empty original image folder should be deleted');
    pc_assert_false(is_dir($cacheDir), 'Empty cache image folder should be deleted');
    @rmdir($photosRoot);
    @rmdir(dirname($photosRoot));
});

echo "\n{$passed} passed, {$failed} failed\n";
exit($failed > 0 ? 1 : 0);
