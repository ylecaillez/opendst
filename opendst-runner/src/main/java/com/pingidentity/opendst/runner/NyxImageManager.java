/*
 * Copyright 2026 Ping Identity Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingidentity.opendst.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Manages the nyx-lite rootfs extracted from a Docker image.
 *
 * <p>The rootfs (ext4 image) and shim binary are extracted once per image digest
 * and cached under {@code ~/.opendst/nyx-rootfs/<digest>/}. Subsequent runs reuse
 * the cache without re-extracting.
 *
 * <p>Extraction requires {@code docker} and {@code sudo} (for the mount step).
 * The kernel image path is read from the {@code NYX_KERNEL} environment variable
 * or defaults to {@code ~/git/nyx-lite/vm_image/vmlinux-6.1.58}.
 *
 * <p>Cache layout:
 * <pre>
 * ~/.opendst/nyx-rootfs/&lt;digest&gt;/
 *   rootfs.ext4          — VM disk image (extracted from Docker image)
 *   opendst-nyx-shim     — shim binary (extracted from /usr/bin/opendst-nyx-shim)
 *   vmconfig.json        — generated Firecracker config
 * </pre>
 */
final class NyxImageManager {

    private static final String SHIM_PATH_IN_IMAGE = "/usr/bin/opendst-nyx-shim";
    private static final String DEFAULT_KERNEL =
            System.getProperty("user.home") + "/git/nyx-lite/vm_image/vmlinux-6.1.58";
    private static final String NYX_CACHE_DIR = System.getProperty("user.home") + "/.opendst/nyx-rootfs";

    /** Result of {@link #prepare(String, OpenDstLogger)}. */
    record NyxSetup(Path shimBinary, String vmConfigPath) {}

    /**
     * Ensures the rootfs and shim are extracted for the given image tag,
     * then returns paths needed to start the shim.
     *
     * @param imageTag Docker image tag (e.g. {@code myapp-opendst-nyx:1.0})
     * @param logger   for progress output
     */
    static NyxSetup prepare(String imageTag, OpenDstLogger logger) throws IOException, InterruptedException {
        var digest = resolveDigest(imageTag);
        var cacheDir = Path.of(NYX_CACHE_DIR, digest);
        var rootfsPath = cacheDir.resolve("rootfs.ext4");
        var shimPath = cacheDir.resolve("opendst-nyx-shim");
        var vmConfigPath = cacheDir.resolve("vmconfig.json");

        if (!Files.exists(rootfsPath) || !Files.exists(shimPath)) {
            logger.raw().info("Extracting nyx-lite rootfs from image: " + imageTag);
            Files.createDirectories(cacheDir);
            extractRootfs(imageTag, rootfsPath, logger);
            extractShim(imageTag, shimPath, logger);
            logger.raw().info("Extraction complete: " + cacheDir);
        } else {
            logger.raw().info("Using cached nyx-lite rootfs: " + cacheDir);
        }

        writeVmConfig(rootfsPath, vmConfigPath);

        return new NyxSetup(shimPath, vmConfigPath.toString());
    }

    /**
     * Resolves the Docker image digest (short form) for use as a cache key.
     * Uses {@code docker inspect --format '{{.Id}}'} and takes the first 12 hex chars.
     */
    private static String resolveDigest(String imageTag) throws IOException, InterruptedException {
        var proc = new ProcessBuilder("docker", "inspect", "--format", "{{.Id}}", imageTag)
                .redirectErrorStream(true)
                .start();
        var output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (proc.waitFor() != 0 || output.isEmpty()) {
            throw new IOException("Failed to inspect image '" + imageTag + "': " + output);
        }
        // sha256:abcdef... → take 12 chars after the colon (or after sha256:)
        var id = output.contains(":") ? output.substring(output.indexOf(':') + 1) : output;
        return id.substring(0, Math.min(12, id.length()));
    }

    /**
     * Extracts the full Docker image filesystem to an ext4 image.
     *
     * <p>Uses the same approach as {@code build-img.sh}:
     * {@code docker run → docker cp / → mkfs.ext4 + sudo mount + copy + sudo umount}
     *
     * <p>Requires {@code sudo}, {@code mkfs.ext4}, and {@code qemu-img} or {@code dd}.
     */
    private static void extractRootfs(String imageTag, Path rootfsPath, OpenDstLogger logger)
            throws IOException, InterruptedException {
        // 1. Create a stopped container from the image
        var createProc = new ProcessBuilder("docker", "create", imageTag)
                .redirectErrorStream(true)
                .start();
        var containerId = new String(createProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (createProc.waitFor() != 0) {
            throw new IOException("docker create failed: " + containerId);
        }
        logger.raw().info("  Container: " + containerId.substring(0, 12));

        try {
            // 2. Create raw ext4 image (800MB, same as build-img.sh)
            logger.raw().info("  Creating 800MB ext4 image...");
            run("dd", "if=/dev/null", "of=" + rootfsPath, "bs=1M", "seek=800");
            run("mkfs.ext4", "-F", rootfsPath.toString());

            // 3. Mount + copy + umount (requires sudo)
            var mountDir = rootfsPath.getParent().resolve("mnt");
            Files.createDirectories(mountDir);
            logger.raw().info("  Mounting and copying filesystem (requires sudo)...");
            run("sudo", "mount", rootfsPath.toString(), mountDir.toString());
            try {
                run("sudo", "docker", "cp", containerId + ":/", mountDir.toString());
            } finally {
                run("sudo", "umount", mountDir.toString());
                deleteDir(mountDir);
            }
        } finally {
            // 4. Clean up container
            new ProcessBuilder("docker", "rm", containerId)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Extracts the shim binary from the image using {@code docker create} + {@code docker cp}.
     */
    private static void extractShim(String imageTag, Path shimPath, OpenDstLogger logger)
            throws IOException, InterruptedException {
        logger.raw().info("  Extracting shim binary...");
        var createProc = new ProcessBuilder("docker", "create", imageTag)
                .redirectErrorStream(true)
                .start();
        var containerId = new String(createProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (createProc.waitFor() != 0) {
            throw new IOException("docker create failed for shim extraction");
        }
        try {
            run("docker", "cp", containerId + ":" + SHIM_PATH_IN_IMAGE, shimPath.toString());
            shimPath.toFile().setExecutable(true);
        } finally {
            new ProcessBuilder("docker", "rm", containerId)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Writes a Firecracker vmconfig.json pointing at the extracted rootfs and kernel.
     */
    private static void writeVmConfig(Path rootfsPath, Path vmConfigPath) throws IOException {
        var kernelPath = System.getenv("NYX_KERNEL") != null ? System.getenv("NYX_KERNEL") : DEFAULT_KERNEL;

        // Validate kernel exists
        if (!Path.of(kernelPath).toFile().exists()) {
            throw new IOException(
                    "Kernel not found at: " + kernelPath + ". Set NYX_KERNEL env var to the vmlinux path.");
        }

        var config = """
                {
                  "boot-source": {
                    "kernel_image_path": "%s",
                    "boot_args": "ro console=ttyS0 noapic reboot=k panic=1 pci=off nomodules random.trust_cpu=on"
                  },
                  "drives": [
                    {
                      "drive_id": "rootfs",
                      "path_on_host": "%s",
                      "is_root_device": true,
                      "is_read_only": false,
                      "io_engine": "Cow"
                    }
                  ],
                  "network-interfaces": [],
                  "machine-config": {
                    "vcpu_count": 1,
                    "mem_size_mib": 1024
                  }
                }
                """.formatted(kernelPath, rootfsPath.toAbsolutePath());

        Files.writeString(vmConfigPath, config);
    }

    private static void run(String... cmd) throws IOException, InterruptedException {
        var proc = new ProcessBuilder(cmd).inheritIO().start();
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException("Command failed (exit " + code + "): " + String.join(" ", cmd));
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
