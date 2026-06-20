<?php
require_once __DIR__ . '/config.php';
requireAdminRequest();

function pc_recursive_rmdir($dir): void
{
    $real = realpath($dir);
    $root = realpath(getProjectRootPath() . '/PhotoConnectImages/photos');
    if ($real === false || $root === false || strpos($real, $root) !== 0 || !is_dir($real)) {
        respond(false, 'Invalid cache directory', [], 400);
    }
    foreach (scandir($real) ?: [] as $object) {
        if ($object === '.' || $object === '..') {
            continue;
        }
        $path = $real . DIRECTORY_SEPARATOR . $object;
        if (is_dir($path) && !is_link($path)) {
            pc_recursive_rmdir($path);
        } else {
            @unlink($path);
        }
    }
    @rmdir($real);
}

$cacheDir = getProjectRootPath() . '/PhotoConnectImages/photos/cache';
if (is_dir($cacheDir)) {
    pc_recursive_rmdir($cacheDir);
    respond(true, 'Cache directory wiped', ['deleted' => true]);
}
respond(true, 'Cache directory does not exist', ['deleted' => false]);
