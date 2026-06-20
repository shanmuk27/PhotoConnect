<?php
declare(strict_types=1);

$root = dirname(__DIR__);
$resDir = $root . '/android_project/app/src/main/res';
$localeManager = $root . '/android_project/app/src/main/java/com/photoconnect/utils/AppLocaleManager.kt';
$translationEngine = $root . '/android_project/app/src/main/java/com/photoconnect/utils/AdvancedTranslationEngine.kt';

function parse_strings(string $file): array {
    if (!is_file($file)) {
        throw new RuntimeException("Missing strings file: {$file}");
    }
    $xml = new DOMDocument();
    $previous = libxml_use_internal_errors(true);
    $loaded = $xml->load($file);
    $errors = libxml_get_errors();
    libxml_clear_errors();
    libxml_use_internal_errors($previous);
    if (!$loaded) {
        $message = $errors ? trim($errors[0]->message) : 'Unknown XML parse error';
        throw new RuntimeException("Invalid XML in {$file}: {$message}");
    }
    $strings = [];
    foreach ($xml->getElementsByTagName('string') as $node) {
        $name = $node->attributes?->getNamedItem('name')?->nodeValue;
        if ($name !== null && $name !== '') {
            $strings[$name] = true;
        }
    }
    return $strings;
}

function locale_dir_for_tag(string $resDir, string $tag): string {
    $normal = $resDir . '/values-' . $tag;
    if (is_dir($normal)) {
        return $normal;
    }
    $bcp47 = $resDir . '/values-b+' . $tag;
    if (is_dir($bcp47)) {
        return $bcp47;
    }
    throw new RuntimeException("No resource directory found for locale tag {$tag}");
}

$managerSource = file_get_contents($localeManager);
if ($managerSource === false) {
    throw new RuntimeException('Could not read AppLocaleManager.kt');
}
preg_match_all('/AppLanguage\("([^"]+)"/', $managerSource, $matches);
$tags = array_values(array_filter(array_unique($matches[1]), static fn($tag) => $tag !== '' && $tag !== 'en'));
sort($tags);

$base = parse_strings($resDir . '/values/strings.xml');
$failures = [];
$report = [];
foreach ($tags as $tag) {
    try {
        $dir = locale_dir_for_tag($resDir, $tag);
        $localized = parse_strings($dir . '/strings.xml');
        $missing = array_diff_key($base, $localized);
        $unknown = array_diff_key($localized, $base);
        if ($unknown) {
            $failures[] = "{$tag} contains unknown string keys: " . implode(', ', array_slice(array_keys($unknown), 0, 8));
        }
        $report[] = sprintf(
            '%s: %d localized, %d covered by English fallback',
            $tag,
            count($localized),
            count($missing)
        );
    } catch (Throwable $e) {
        $failures[] = $e->getMessage();
    }
}

$engineSource = file_get_contents($translationEngine);
foreach (['brx', 'doi', 'kok', 'mai', 'mni', 'sat'] as $dialectTag) {
    if ($engineSource === false || !str_contains($engineSource, '"' . $dialectTag . '" to ')) {
        $failures[] = "Missing regional fallback mapping for {$dialectTag}";
    }
}

if ($failures) {
    fwrite(STDERR, "Localization coverage failed:\n- " . implode("\n- ", $failures) . "\n");
    exit(1);
}

echo "Localization coverage OK for " . count($tags) . " regional languages.\n";
echo implode("\n", $report) . "\n";
